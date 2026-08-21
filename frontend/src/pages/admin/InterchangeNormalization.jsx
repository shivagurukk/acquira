import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { Scale, RefreshCw, History, Download, Play, X, CheckCircle2 } from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import { formatMsf, formatNumber, formatPercent, formatDateTime } from '../../utils/formatters';
import { exportRowsToCsv } from '../../utils/exportExcel';
import {
    Page, Stack, Row, Card, Button, Badge, Alert, DataTable,
    FormField, FormGrid, Input, Select, useConfirm,
} from '../../components/ui';

/* ────────────────────────────────────────────────────────────────────────────
   Interchange Fee Normalization (Super Admin)

   Finance supplies the CORRECT interchange total for a month. Every
   transaction KEEPS its existing interchange; the extra (target - current
   total) is added on top weighted by VOLUME — merchant share of the month's
   volume, then each transaction's share of its merchant's volume
   (new = old + volumeShare * extra) — reconciled with the largest-remainder
   method so the amounts sum EXACTLY to the target. Apply
   updates every fact transaction of that month and rebuilds all summary
   tables — every screen shows only the normalized figures afterwards. The
   pre-normalization values survive only in the run history (versioned; a
   re-run supersedes, never overwrites, the previous version).
   ──────────────────────────────────────────────────────────────────────────── */

const MONTH_NAMES = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const monthLabel = (mk) => `${MONTH_NAMES[(mk % 100) - 1]} ${Math.floor(mk / 100)}`;

const currentYear = new Date().getFullYear();
const YEAR_OPTIONS = [currentYear - 2, currentYear - 1, currentYear, currentYear + 1]
    .map(y => ({ value: y, label: String(y) }));

const STATUS_TONE = {
    APPLIED: 'success', APPLYING: 'warning', PREVIEW: 'brand',
    SUPERSEDED: 'neutral', CANCELLED: 'neutral', FAILED: 'danger',
};

const InterchangeNormalization = () => {
    const confirm = useConfirm();
    const { currencySymbol, tenantVersion } = useAuth();
    const money = useCallback((v) => formatMsf(v, currencySymbol), [currencySymbol]);

    const [year, setYear] = useState(currentYear);
    const [months, setMonths] = useState([]);
    const [loading, setLoading] = useState(true);

    // Preview state
    const [selMonth, setSelMonth] = useState(null);     // monthKey being worked on
    const [target, setTarget] = useState('');
    const [preview, setPreview] = useState(null);       // /preview response
    const [calculating, setCalculating] = useState(false);
    const [applying, setApplying] = useState(false);    // apply requested / polling

    // History state
    const [showHistory, setShowHistory] = useState(false);
    const [history, setHistory] = useState([]);
    const [historyDetails, setHistoryDetails] = useState(null); // {run, rows}

    const loadSummary = useCallback(async () => {
        setLoading(true);
        try {
            const res = await api.get('/admin/interchange-normalization/summary', { params: { year } });
            setMonths(res.data || []);
        } catch (e) {
            showToast(e?.response?.data?.error || 'Failed to load months', 'error');
            setMonths([]);
        } finally {
            setLoading(false);
        }
    }, [year]);

    useEffect(() => { loadSummary(); }, [loadSummary, tenantVersion]);

    /* ── Preview ── */
    const startMonth = (mk, existingTarget) => {
        setSelMonth(mk);
        setTarget(existingTarget != null ? String(existingTarget) : '');
        setPreview(null);
    };

    /* Calculate then IMMEDIATELY apply — one click, one confirmation, no
       review stop in between. The preview table still fills in as soon as the
       calculation lands, purely as feedback while the apply runs. */
    const calculate = async () => {
        if (target === '' || Number(target) < 0 || Number.isNaN(Number(target))) {
            showToast('Enter the normalized interchange total for the month (non-negative number)', 'error');
            return;
        }
        const ok = await confirm({
            title: `Normalize ${monthLabel(selMonth)}?`,
            message: `This calculates the distribution and applies it in one step: every transaction's interchange ` +
                `is adjusted so the month total becomes exactly ${money(Number(target))}, then every summary is ` +
                `rebuilt. All screens will show only the normalized figures. The current values are preserved in ` +
                `the run history (version).`,
            confirmLabel: 'Calculate & apply',
            tone: 'danger',
        });
        if (!ok) return;
        setCalculating(true);
        try {
            // The calculation reads a month of fact_transaction per merchant, which
            // is far too slow for one request on a large tenant, so the server runs
            // it on a worker thread and we poll the run until it is ready.
            const { data: started } = await api.post('/admin/interchange-normalization/preview', {
                monthKey: selMonth, target: Number(target),
            });
            const runId = started.runId;
            let misses = 0;                          // consecutive poll failures
            for (let i = 0; i < 900; i++) {          // ~30 min ceiling at 2s
                await new Promise(r => setTimeout(r, 2000));
                let res;
                try {
                    res = (await api.get(`/admin/interchange-normalization/runs/${runId}/preview`)).data;
                    misses = 0;
                } catch {
                    // Transient network blips are fine, but a poll that NEVER
                    // succeeds means the endpoint itself is unreachable (e.g. a
                    // backend build that predates it) — fail loudly, don't spin.
                    if (++misses >= 10) {
                        showToast('Cannot read the calculation status — is the backend up to date?', 'error');
                        return;
                    }
                    continue;
                }
                if (res.status === 'PREVIEW') {
                    setPreview(res);
                    if (Number(res.remainingDifference) !== 0) {
                        showToast('Calculation did not reconcile to the target — not applying. Contact support.', 'error');
                        return;
                    }
                    // Auto-apply: no review stop. Failure here leaves the preview
                    // on screen so the manual Apply button can retry it.
                    setApplying(true);
                    try {
                        await api.post('/admin/interchange-normalization/apply', { runId, confirm: true });
                        showToast('Applying — updating transactions and rebuilding summaries…', 'info');
                        await pollRun(runId);
                        setPreview(null);
                        setSelMonth(null);
                        setTarget('');
                        loadSummary();
                    } catch (e) {
                        showToast(e?.response?.data?.error || 'Apply failed — the calculation is kept, use Apply Normalization to retry', 'error');
                    } finally {
                        setApplying(false);
                    }
                    return;
                }
                if (res.status === 'FAILED') {
                    showToast(res.statusDetail || 'Preview failed', 'error');
                    return;
                }
                if (res.status === 'CANCELLED') return;   // superseded by a newer calculation
            }
            showToast('Preview is taking unusually long — check back shortly.', 'warning');
        } catch (e) {
            showToast(e?.response?.data?.error || 'Preview failed', 'error');
        } finally {
            setCalculating(false);
        }
    };

    const cancelPreview = async () => {
        if (preview?.runId) {
            try { await api.post(`/admin/interchange-normalization/runs/${preview.runId}/cancel`); } catch { /* already gone */ }
        }
        setPreview(null);
        setSelMonth(null);
        setTarget('');
    };

    const exportPreview = () => {
        if (!preview) return;
        exportRowsToCsv(
            (preview.rows || []).map(r => ({
                'Merchant ID': r.mid || r.merchantId,
                'Merchant Name': r.merchantName,
                'Transactions': r.txnCount,
                'Transaction Volume': r.txnVolume,
                'Existing Interchange': r.originalInterchange,
                'Weight %': r.weightPct,
                'Normalized Interchange': r.normalizedInterchange,
                'Difference': r.difference,
            })),
            { fileName: `interchange-normalization-${selMonth}` },
        );
    };

    /* ── Apply + poll until the batch (fact update + summary rebuild) finishes ── */
    const pollRun = useCallback(async (runId) => {
        for (let i = 0; i < 600; i++) {
            await new Promise(r => setTimeout(r, 2000));
            let run;
            try {
                run = (await api.get(`/admin/interchange-normalization/runs/${runId}`)).data;
            } catch { continue; }
            if (run.status === 'FAILED') {
                showToast(`Normalization failed: ${run.status_detail || 'see server logs'}`, 'error');
                return;
            }
            if (run.status === 'APPLIED' && run.status_detail !== 'Rebuilding summaries') {
                const ok = run.status_detail === 'COMPLETED';
                showToast(ok
                    ? 'Normalization applied — all screens now show the normalized interchange.'
                    : `Applied, but: ${run.status_detail}`, ok ? 'success' : 'warning');
                return;
            }
        }
        showToast('Normalization is still running in the background — check history later.', 'warning');
    }, []);

    const apply = async () => {
        if (!preview) return;
        const zero = Number(preview.remainingDifference) === 0;
        if (!zero) { showToast('Remaining difference must be 0.00 before applying', 'error'); return; }
        const ok = await confirm({
            title: `Apply normalization for ${monthLabel(selMonth)}?`,
            message: `This adjusts the interchange fee on all ${formatNumber(preview.merchantCount)} merchants' ` +
                `transactions — each keeps its current value plus its volume-weighted share of the extra — so the ` +
                `month total becomes exactly ${money(preview.target)}, then rebuilds every summary. All screens will ` +
                `show only the normalized figures. The current values are preserved in the run history (version).`,
            confirmLabel: 'Apply normalization',
            tone: 'danger',
        });
        if (!ok) return;
        setApplying(true);
        try {
            await api.post('/admin/interchange-normalization/apply', { runId: preview.runId, confirm: true });
            showToast('Normalization started — updating transactions and rebuilding summaries…', 'info');
            await pollRun(preview.runId);
            setPreview(null);
            setSelMonth(null);
            setTarget('');
            loadSummary();
        } catch (e) {
            showToast(e?.response?.data?.error || 'Apply failed', 'error');
        } finally {
            setApplying(false);
        }
    };

    /* ── History ── */
    const loadHistory = async () => {
        try {
            const res = await api.get('/admin/interchange-normalization/history');
            setHistory(res.data || []);
            setShowHistory(true);
            setHistoryDetails(null);
        } catch (e) {
            showToast(e?.response?.data?.error || 'Failed to load history', 'error');
        }
    };

    const loadRunDetails = async (run) => {
        try {
            const res = await api.get(`/admin/interchange-normalization/runs/${run.run_id}/details`);
            setHistoryDetails({ run, rows: res.data || [] });
        } catch (e) {
            showToast(e?.response?.data?.error || 'Failed to load run details', 'error');
        }
    };

    /* ── Columns ── */
    const monthColumns = [
        { key: 'monthKey', header: 'Month', nowrap: true, render: r => monthLabel(r.monthKey) },
        { key: 'originalInterchange', header: 'System Interchange', align: 'right', numeric: true, render: r => money(r.originalInterchange) },
        { key: 'lastTarget', header: 'Normalized Target', align: 'right', numeric: true, render: r => (r.lastTarget != null ? money(r.lastTarget) : '—') },
        {
            key: 'diff', header: 'Difference', align: 'right', numeric: true,
            render: r => (r.lastTarget != null ? money(Number(r.lastTarget) - Number(r.originalInterchange)) : '—'),
        },
        { key: 'merchantCount', header: 'Merchants', align: 'right', render: r => formatNumber(r.merchantCount) },
        {
            key: 'lastStatus', header: 'Status', render: r => (r.lastStatus
                ? <Badge tone={STATUS_TONE[r.lastStatus] || 'neutral'}>{r.lastStatus}{r.lastVersion ? ` v${r.lastVersion}` : ''}</Badge>
                : <Badge tone="neutral">Not normalized</Badge>),
        },
        { key: 'lastNormalizedBy', header: 'Last Normalized By', render: r => r.lastNormalizedBy || '—' },
        { key: 'lastNormalizedAt', header: 'Date', nowrap: true, render: r => (r.lastNormalizedAt ? formatDateTime(r.lastNormalizedAt) : '—') },
        {
            key: 'actions', header: '', render: r => (
                <Button size="sm" onClick={() => startMonth(r.monthKey, r.lastTarget)}>
                    Normalize
                </Button>
            ),
        },
    ];

    const previewColumns = [
        { key: 'mid', header: 'Merchant ID', nowrap: true, render: r => (r.merchantId > 0 ? (r.mid || r.merchantId) : '—') },
        { key: 'merchantName', header: 'Merchant Name', sortable: true },
        { key: 'txnVolume', header: 'Transaction Volume', align: 'right', numeric: true, sortable: true, render: r => money(r.txnVolume) },
        { key: 'originalInterchange', header: 'Existing Interchange', align: 'right', numeric: true, sortable: true, render: r => money(r.originalInterchange) },
        { key: 'weightPct', header: 'Weight % (volume share)', align: 'right', numeric: true, render: r => formatPercent(r.weightPct, 4) },
        { key: 'extraAdded', header: 'Extra Added', align: 'right', numeric: true, render: r => money(r.extraAdded) },
        { key: 'normalizedInterchange', header: 'Normalized Interchange', align: 'right', numeric: true, render: r => money(r.normalizedInterchange) },
        { key: 'difference', header: 'Difference', align: 'right', numeric: true, render: r => money(r.difference) },
        {
            key: 'adjPct', header: 'Adjustment %', align: 'right', numeric: true,
            render: r => (Number(r.originalInterchange) !== 0
                ? formatPercent((r.difference / r.originalInterchange) * 100, 2)
                : '—'),
        },
    ];

    const historyColumns = [
        { key: 'month_key', header: 'Month', nowrap: true, render: r => monthLabel(r.month_key) },
        { key: 'version_no', header: 'Version', align: 'right' },
        { key: 'status', header: 'Status', render: r => <Badge tone={STATUS_TONE[r.status] || 'neutral'}>{r.status}</Badge> },
        { key: 'original_interchange_total', header: 'Original Total', align: 'right', numeric: true, render: r => money(r.original_interchange_total) },
        { key: 'target_normalized_total', header: 'Normalized Total', align: 'right', numeric: true, render: r => money(r.target_normalized_total) },
        { key: 'difference', header: 'Difference', align: 'right', numeric: true, render: r => money(r.difference) },
        { key: 'merchant_count', header: 'Merchants', align: 'right' },
        { key: 'by', header: 'By', render: r => r.applied_by || r.created_by || '—' },
        { key: 'at', header: 'Date', nowrap: true, render: r => formatDateTime(r.applied_at || r.created_at) },
        { key: 'view', header: '', render: r => <Button size="sm" variant="ghost" onClick={() => loadRunDetails(r)}>Merchants</Button> },
    ];

    const remainingIsZero = preview && Number(preview.remainingDifference) === 0;

    const totalsBox = preview && (
        <FormGrid>
            <FormField label="Total Existing Interchange"><strong>{money(preview.originalTotal)}</strong></FormField>
            <FormField label="Target Normalized Interchange"><strong>{money(preview.target)}</strong></FormField>
            <FormField label="Total Proposed Normalized"><strong>{money(preview.proposedTotal)}</strong></FormField>
            <FormField label="Remaining Difference">
                <strong style={{ color: remainingIsZero ? 'var(--success)' : 'var(--danger)' }}>
                    {money(preview.remainingDifference)} {remainingIsZero && <CheckCircle2 size={14} style={{ verticalAlign: '-2px' }} />}
                </strong>
            </FormField>
        </FormGrid>
    );

    return (
        <Page
            title="Interchange Fee Normalization"
            subtitle="Enter the correct monthly interchange total; each transaction keeps its current interchange and the extra is added on top weighted by transaction volume, so every screen shows the normalized figures."
            icon={Scale}
            actions={
                <Row>
                    <Select value={year} onChange={e => setYear(Number(e.target.value))} options={YEAR_OPTIONS} />
                    <Button icon={History} onClick={loadHistory}>View History</Button>
                    <Button icon={RefreshCw} onClick={loadSummary}>Refresh</Button>
                </Row>
            }
        >
            <Stack>
                <Card title={`Months in ${year}`} subtitle="Only months with transaction data are listed. System Interchange is the live value on the transactions — after a normalization it already shows the normalized figure.">
                    <DataTable
                        columns={monthColumns}
                        rows={months}
                        rowKey={r => r.monthKey}
                        loading={loading}
                        empty={<div style={{ padding: 'var(--space-3xl)', textAlign: 'center', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                            No transaction data in {year} for this tenant.
                        </div>}
                    />
                </Card>

                {selMonth && (
                    <Card
                        pad
                        title={`Normalize ${monthLabel(selMonth)}`}
                        subtitle="Enter the correct (normalized) interchange total for the month, calculate the merchant-level preview, verify the remaining difference is zero, then apply."
                    >
                        <Stack>
                            <Row>
                                <FormField label={`Target normalized interchange total (${currencySymbol})`}>
                                    <Input
                                        type="number" min="0" step="any" value={target}
                                        onChange={e => setTarget(e.target.value)}
                                        placeholder="e.g. 100000.000"
                                        disabled={applying}
                                    />
                                </FormField>
                                <Button variant="primary" icon={Play} onClick={calculate} disabled={applying} loading={calculating || applying}>
                                    Calculate & Apply
                                </Button>
                                <Button icon={Download} onClick={exportPreview} disabled={!preview}>
                                    Export Preview
                                </Button>
                                <Button variant="ghost" icon={X} onClick={cancelPreview} disabled={applying}>
                                    Cancel
                                </Button>
                            </Row>

                            {preview && (
                                <>
                                    {totalsBox}
                                    {Number(preview.unattributedOriginal) !== 0 && (
                                        <Alert tone="info">
                                            {money(preview.unattributedOriginal)} of interchange sits on transactions with no merchant.
                                            They keep their values and get their volume-weighted share of the extra like the rest
                                            (bucket total after normalization: {money(preview.unattributedNormalized)}).
                                        </Alert>
                                    )}
                                    <DataTable
                                        columns={previewColumns}
                                        rows={preview.rows || []}
                                        rowKey={r => r.merchantId}
                                    />
                                    <Row>
                                        <Button
                                            variant="danger"
                                            onClick={apply}
                                            disabled={!remainingIsZero || applying}
                                            loading={applying}
                                        >
                                            Apply Normalization
                                        </Button>
                                        {!remainingIsZero && (
                                            <Badge tone="danger">Remaining difference must be 0.00 to apply</Badge>
                                        )}
                                        {applying && (
                                            <Badge tone="warning">Updating transactions and rebuilding summaries — keep this page open</Badge>
                                        )}
                                    </Row>
                                </>
                            )}
                        </Stack>
                    </Card>
                )}

                {showHistory && (
                    <Card
                        pad
                        title="Normalization history"
                        subtitle="Every run is versioned; re-normalizing a month supersedes (never overwrites) the previous version. Merchant-level old vs normalized values are kept per run."
                        actions={<Button size="sm" variant="ghost" icon={X} onClick={() => { setShowHistory(false); setHistoryDetails(null); }}>Close</Button>}
                    >
                        <Stack>
                            <DataTable
                                columns={historyColumns}
                                rows={history}
                                rowKey={r => r.run_id}
                                empty={<div style={{ padding: 'var(--space-2xl)', textAlign: 'center', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                                    No normalization runs yet for this tenant.
                                </div>}
                            />
                            {historyDetails && (
                                <Card title={`Run ${historyDetails.run.run_id} — ${monthLabel(historyDetails.run.month_key)} v${historyDetails.run.version_no} merchant detail`}>
                                    <DataTable
                                        columns={[
                                            { key: 'mid', header: 'Merchant ID', render: r => r.mid || r.merchant_id },
                                            { key: 'merchant_name', header: 'Merchant Name' },
                                            { key: 'txn_volume', header: 'Volume', align: 'right', numeric: true, render: r => money(r.txn_volume) },
                                            { key: 'original_interchange', header: 'Old Interchange', align: 'right', numeric: true, render: r => money(r.original_interchange) },
                                            { key: 'weight_pct', header: 'Weight %', align: 'right', numeric: true, render: r => formatPercent(r.weight_pct, 4) },
                                            { key: 'normalized_interchange', header: 'Normalized', align: 'right', numeric: true, render: r => money(r.normalized_interchange) },
                                            { key: 'difference', header: 'Difference', align: 'right', numeric: true, render: r => money(r.difference) },
                                        ]}
                                        rows={historyDetails.rows}
                                        rowKey={r => r.merchant_id}
                                    />
                                </Card>
                            )}
                        </Stack>
                    </Card>
                )}
            </Stack>
        </Page>
    );
};

export default InterchangeNormalization;
