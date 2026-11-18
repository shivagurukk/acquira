import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
    Calculator, Save, FileSpreadsheet, FileText, FolderOpen, Wand2,
    AlertTriangle, Trash2, Info,
} from 'lucide-react';
import api from '../../api/axios';
import { showToast } from '../../contexts/ToastContext';
import {
    Page, Stack, Row, Card, Button, DataTable, Modal, Badge, Alert,
    FormField, FormGrid, Input, Select,
} from '../../components/ui';

/* ════════════════════════════════════════════════════════════════════
   PRICE BUILDER  (/mis/price-builder)

   A PRE-SALE calculator. It prices a merchant that does not exist yet:
   from an MCC, a channel, an annual volume and the split of that volume
   across scheme rows, it builds the cost stack (interchange + scheme fee
   + per-transaction fees), applies the MSF being proposed, and reports
   the margin — then recommends an MSF that clears the target.

   It reads NO transaction history. That is what separates it from
   /business/pricing-simulator, which re-prices the book we already have.

   Everything the engine returns is USD; volumes are entered in the
   country's own currency and converted once, server-side.

   All arithmetic lives in the backend (PriceBuilderCalculator). This
   screen collects inputs and renders what comes back — it never does its
   own pricing, so an exported figure can never disagree with the screen.

   MOTION: none beyond CSS transitions (a hidden browser pane serves no
   animation frames, so anything rAF-driven would not verify).
   ════════════════════════════════════════════════════════════════════ */

const CHANNELS = [
    { value: 'POS',  label: 'POS' },
    { value: 'ECOM', label: 'E-commerce' },
];

const STATUSES = [
    { value: 'DRAFT',     label: 'Draft' },
    { value: 'SUBMITTED', label: 'Submitted' },
    { value: 'WON',       label: 'Won' },
    { value: 'LOST',      label: 'Lost' },
];

// Transport shape: what the engine expects on /calculate. Kept in one place
// so save, calculate and both exports can never drift apart.
const buildPayload = (s, mixRows) => ({
    merchantName:           s.merchantName,
    countryCode:            s.countryCode,
    channel:                s.channel,
    mcc:                    s.mcc || null,
    annualVolume:           n(s.annualVolume),
    totalTxns:              n(s.totalTxns),
    mixRows:                mixRows.map(r => ({
        schemeKey:  r.schemeKey,
        volumePct:  n(r.volumePct),
        txnPct:     n(r.txnPct),
        msfPct:     n(r.msfPct),
    })),
    gatewayCostUsd:         n(s.gatewayCostUsd),
    posUnits:               n(s.posUnits),
    posRateMonthly:         n(s.posRateMonthly),
    ecomSetupFee:           n(s.ecomSetupFee),
    ecomAnnualFee:          n(s.ecomAnnualFee),
    settlementFeePerTxnUsd: n(s.settlementFeePerTxnUsd),
    dccOptInPct:            n(s.dccOptInPct),
    dccMsfPct:              n(s.dccMsfPct),
    dccMerchantRebatePct:   n(s.dccMerchantRebatePct),
    dccVendorRebatePct:     n(s.dccVendorRebatePct),
    // null, not 0: "no split given" means "all transactions below the
    // threshold", which the engine decides — not "0% are below it".
    debitSmallTxnPct:       s.debitSmallTxnPct  === '' ? null : n(s.debitSmallTxnPct),
    creditSmallTxnPct:      s.creditSmallTxnPct === '' ? null : n(s.creditSmallTxnPct),
});

const n = (v) => {
    const x = parseFloat(v);
    return Number.isFinite(x) ? x : 0;
};

const money = (v, dp = 2) =>
    (Number.isFinite(v) ? v : 0).toLocaleString(undefined, {
        minimumFractionDigits: dp, maximumFractionDigits: dp,
    });

const pct = (v, dp = 3) => `${money(Number.isFinite(v) ? v : 0, dp)}%`;

const INITIAL = {
    merchantName: '',
    countryCode: '',
    channel: 'POS',
    mcc: '',
    annualVolume: '',
    totalTxns: '',
    gatewayCostUsd: '',
    posUnits: '',
    posRateMonthly: '',
    ecomSetupFee: '',
    ecomAnnualFee: '',
    settlementFeePerTxnUsd: '',
    dccOptInPct: '',
    dccMsfPct: '',
    dccMerchantRebatePct: '',
    dccVendorRebatePct: '',
    debitSmallTxnPct: '',
    creditSmallTxnPct: '',
    status: 'DRAFT',
};

export default function PriceBuilder() {
    const [config, setConfig]   = useState(null);
    const [loading, setLoading] = useState(true);
    const [form, setForm]       = useState(INITIAL);
    const [mixRows, setMixRows] = useState([]);
    const [result, setResult]   = useState(null);
    const [pricing, setPricing] = useState(false);
    const [saving, setSaving]   = useState(false);
    const [quotesOpen, setQuotesOpen] = useState(false);
    const [quotes, setQuotes]   = useState([]);
    const [quotesLoading, setQuotesLoading] = useState(false);

    const set = useCallback((k, v) => setForm(f => ({ ...f, [k]: v })), []);

    const ccy      = config?.country?.currencyCode || '';
    const isEcom   = form.channel === 'ECOM';
    const schemes  = useMemo(() => config?.schemeRows || [], [config]);
    const selMcc   = useMemo(
        () => (config?.mccs || []).find(m => m.mcc === form.mcc) || null,
        [config, form.mcc]);

    // ── Which transaction-size controls this deal needs ────────────────
    // Driven entirely by the rule table the backend returns: a split row
    // with no MCC applies to every merchant, one that names an MCC applies
    // only there. Nothing about "5,000 dirhams" is known to this file.
    const splits = useMemo(() => {
        const all = config?.splits || [];
        const pick = (cardType) => all.filter(s =>
            s.cardType === cardType && (s.mcc === null || s.mcc === form.mcc));
        const debit  = pick('DEBIT');
        const credit = pick('CREDIT');
        return {
            debit:  debit.length  ? debit[debit.length - 1]   : null,
            credit: credit.length ? credit[credit.length - 1] : null,
        };
    }, [config, form.mcc]);

    // ── Load reference data ────────────────────────────────────────────
    useEffect(() => {
        let alive = true;
        (async () => {
            try {
                const { data } = await api.get('/mis/price-builder/config');
                if (!alive) return;
                const first = (data.countries || []).find(c => c.active);
                setConfig(data);
                if (first) set('countryCode', first.countryCode);
            } catch (e) {
                showToast(e?.response?.data?.error || 'Could not load the rate book', 'error');
            } finally {
                if (alive) setLoading(false);
            }
        })();
        return () => { alive = false; };
    }, [set]);

    // Country drives the whole rate book, the currency and the scheme rows,
    // so changing it resets the mix rather than carrying across a split that
    // refers to schemes the new country does not quote.
    useEffect(() => {
        if (!form.countryCode) return;
        let alive = true;
        (async () => {
            try {
                const { data } = await api.get('/mis/price-builder/config', {
                    params: { country: form.countryCode },
                });
                if (!alive) return;
                setConfig(data);
                setMixRows((data.schemeRows || []).map(r => ({
                    schemeKey: r.schemeKey,
                    label: r.label,
                    cardType: r.cardType,
                    locality: r.locality,
                    volumePct: '',
                    txnPct: '',
                    msfPct: '',
                })));
                setResult(null);
            } catch (e) {
                showToast(e?.response?.data?.error || 'Could not load that country', 'error');
            }
        })();
        return () => { alive = false; };
    }, [form.countryCode]);

    // ── Mix validation ─────────────────────────────────────────────────
    const volumeTotal = useMemo(
        () => mixRows.reduce((s, r) => s + n(r.volumePct), 0), [mixRows]);
    const txnTotal = useMemo(
        () => mixRows.reduce((s, r) => s + n(r.txnPct), 0), [mixRows]);

    const near100 = (v) => Math.abs(v - 100) < 0.005;
    const mixReady = near100(volumeTotal) && near100(txnTotal);
    const hasDeal  = n(form.annualVolume) > 0 && n(form.totalTxns) > 0 && !!form.mcc;
    const canPrice = hasDeal && mixReady;

    const setMix = (schemeKey, field, value) =>
        setMixRows(rows => rows.map(r =>
            r.schemeKey === schemeKey ? { ...r, [field]: value } : r));

    /** Spreads the remainder over the rows that already carry volume. */
    const normalise = (field) => {
        const total = mixRows.reduce((s, r) => s + n(r[field]), 0);
        if (total <= 0) {
            showToast(`Enter at least one ${field === 'volumePct' ? 'volume' : 'transaction'} share first`, 'warning');
            return;
        }
        setMixRows(rows => rows.map(r => ({
            ...r,
            [field]: n(r[field]) === 0 ? '' : String(Math.round(n(r[field]) / total * 1000000) / 10000),
        })));
    };

    /** Copies the volume split onto the transaction split. */
    const copyVolumeToTxn = () =>
        setMixRows(rows => rows.map(r => ({ ...r, txnPct: r.volumePct })));

    // ── Pricing ────────────────────────────────────────────────────────
    // Debounced so typing a volume does not fire a request per keystroke.
    const timer = useRef(null);
    useEffect(() => {
        if (!canPrice) { setResult(null); return; }
        clearTimeout(timer.current);
        timer.current = setTimeout(async () => {
            setPricing(true);
            try {
                const { data } = await api.post('/mis/price-builder/calculate',
                    buildPayload(form, mixRows));
                setResult(data);
            } catch (e) {
                showToast(e?.response?.data?.error || 'Pricing failed', 'error');
            } finally {
                setPricing(false);
            }
        }, 350);
        return () => clearTimeout(timer.current);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [JSON.stringify(buildPayload(form, mixRows)), canPrice]);

    /** Writes the recommended MSF onto every row of the matching kind. */
    const applyRecommended = () => {
        const t = result?.totals;
        if (!t) return;
        setMixRows(rows => rows.map(r => {
            if (r.locality === 'INTERNATIONAL') return { ...r, msfPct: String(t.recommendedIntlMsfPct) };
            if (r.cardType === 'DEBIT')         return { ...r, msfPct: String(t.recommendedDebitMsfPct) };
            return { ...r, msfPct: String(t.recommendedCreditMsfPct) };
        }));
        showToast('Recommended MSF applied to every row', 'success');
    };

    // ── Saving and exporting ───────────────────────────────────────────
    const saveQuote = async () => {
        if (!form.merchantName.trim()) {
            showToast('Give the quote a merchant name first', 'warning');
            return;
        }
        setSaving(true);
        try {
            const { data } = await api.post('/mis/price-builder/quotes', {
                ...buildPayload(form, mixRows),
                status: form.status,
            });
            showToast(`Saved as ${data.quoteRef}`, 'success');
        } catch (e) {
            showToast(e?.response?.data?.error || 'Could not save the quote', 'error');
        } finally {
            setSaving(false);
        }
    };

    const download = async (kind) => {
        try {
            const res = await api.post(`/mis/price-builder/export/${kind}`,
                buildPayload(form, mixRows), { responseType: 'blob' });
            const name = (res.headers['content-disposition'] || '')
                .match(/filename="?([^"]+)"?/)?.[1]
                || `price-builder.${kind === 'excel' ? 'xlsx' : 'pdf'}`;
            const url = URL.createObjectURL(res.data);
            const a = document.createElement('a');
            a.href = url;
            a.download = name;
            document.body.appendChild(a);
            a.click();
            a.remove();
            URL.revokeObjectURL(url);
        } catch (e) {
            showToast(e?.response?.data?.error || 'Export failed', 'error');
        }
    };

    const openQuotes = async () => {
        setQuotesOpen(true);
        setQuotesLoading(true);
        try {
            const { data } = await api.get('/mis/price-builder/quotes');
            setQuotes(data || []);
        } catch (e) {
            showToast(e?.response?.data?.error || 'Could not load saved quotes', 'error');
        } finally {
            setQuotesLoading(false);
        }
    };

    const loadQuote = async (row) => {
        try {
            const { data } = await api.get(`/mis/price-builder/quotes/${row.quote_id}`);
            const saved = data.inputs || {};
            setForm({
                ...INITIAL,
                ...Object.fromEntries(Object.entries(saved)
                    .filter(([k]) => k in INITIAL)
                    .map(([k, v]) => [k, v === null || v === undefined ? '' : String(v)])),
                merchantName: data.merchant_name || '',
                countryCode: data.country_code || '',
                channel: data.channel || 'POS',
                mcc: data.mcc || '',
                status: data.status || 'DRAFT',
            });
            // The mix comes back with the quote; the country effect would
            // otherwise blank it, so it is applied after that settles.
            setTimeout(() => {
                if (Array.isArray(saved.mixRows)) {
                    setMixRows(rows => rows.map(r => {
                        const m = saved.mixRows.find(x => x.schemeKey === r.schemeKey);
                        return m ? {
                            ...r,
                            volumePct: String(m.volumePct ?? ''),
                            txnPct:    String(m.txnPct ?? ''),
                            msfPct:    String(m.msfPct ?? ''),
                        } : r;
                    }));
                }
            }, 150);
            setQuotesOpen(false);
            showToast(`Loaded ${data.quote_ref}`, 'success');
        } catch (e) {
            showToast(e?.response?.data?.error || 'Could not open that quote', 'error');
        }
    };

    const deleteQuote = async (row) => {
        try {
            await api.delete(`/mis/price-builder/quotes/${row.quote_id}`);
            setQuotes(q => q.filter(x => x.quote_id !== row.quote_id));
            showToast('Quote deleted', 'success');
        } catch (e) {
            showToast(e?.response?.data?.error || 'Could not delete that quote', 'error');
        }
    };

    // ── Render ─────────────────────────────────────────────────────────
    const totals = result?.totals;
    const rows   = result?.rows || [];
    const notes  = result?.notes || [];

    const countryOptions = (config?.countries || []).map(c => ({
        value: c.countryCode,
        label: c.active ? c.countryName : `${c.countryName} — no rates on file`,
        disabled: !c.active,
    }));

    const mccOptions = (config?.mccs || []).map(m => ({
        value: m.mcc,
        label: `${m.mcc} — ${m.description}${m.mccType === 'PREFERENTIAL' ? ' (preferential)' : ''}`,
    }));

    return (
        <Page
            title="Price Builder"
            subtitle="Quote a prospective merchant from the cost up — interchange, scheme fees and ancillary income."
            icon={Calculator}
            actions={
                <Row gap="sm">
                    <Button icon={FolderOpen} onClick={openQuotes}>Saved quotes</Button>
                    <Button icon={FileSpreadsheet} disabled={!result} onClick={() => download('excel')}>Excel</Button>
                    <Button icon={FileText} disabled={!result} onClick={() => download('pdf')}>PDF</Button>
                    <Button variant="primary" icon={Save} loading={saving}
                            disabled={!result} onClick={saveQuote}>Save quote</Button>
                </Row>
            }>

            {loading ? <Card pad>Loading the rate book…</Card> : (
            <Stack gap="lg">

                {/* ── The deal ─────────────────────────────────────────── */}
                <Card title="The deal" subtitle="Who is being quoted, and on what volume." pad>
                    <FormGrid cols={3}>
                        <FormField label="Merchant">
                            <Input value={form.merchantName} placeholder="Trading name"
                                   onChange={e => set('merchantName', e.target.value)} />
                        </FormField>
                        <FormField label="Country" required>
                            <Select value={form.countryCode} options={countryOptions}
                                    onChange={e => set('countryCode', e.target.value)} />
                        </FormField>
                        <FormField label="Channel" required>
                            <Select value={form.channel} options={CHANNELS}
                                    onChange={e => set('channel', e.target.value)} />
                        </FormField>

                        <FormField label="MCC" required className="ui-form-grid--span"
                                   hint={selMcc ? `${selMcc.sector} · ${selMcc.mccType.toLowerCase()}` : 'Drives the interchange for every row below'}>
                            <Select value={form.mcc} placeholder="Select an MCC…" options={mccOptions}
                                    onChange={e => set('mcc', e.target.value)} />
                        </FormField>
                        <FormField label={`Annual volume (${ccy})`} required>
                            <Input type="number" value={form.annualVolume} placeholder="0"
                                   onChange={e => set('annualVolume', e.target.value)} />
                        </FormField>
                        <FormField label="Annual transactions" required>
                            <Input type="number" value={form.totalTxns} placeholder="0"
                                   onChange={e => set('totalTxns', e.target.value)} />
                        </FormField>
                        <FormField label="Average ticket"
                                   hint={`${ccy} — derived from volume and count`}>
                            <Input readOnly value={
                                n(form.totalTxns) > 0
                                    ? money(n(form.annualVolume) / n(form.totalTxns))
                                    : '—'} />
                        </FormField>
                        <FormField label="Status">
                            <Select value={form.status} options={STATUSES}
                                    onChange={e => set('status', e.target.value)} />
                        </FormField>
                    </FormGrid>
                </Card>

                {/* ── Transaction size ─────────────────────────────────── */}
                {(splits.debit || splits.credit) && (
                    <Card title="Transaction size"
                          subtitle="Interchange on these rows changes above a ticket threshold, so the split has to be stated."
                          pad>
                        <Alert tone="info" title="How the split is applied">
                            The percentage below is a share of <strong>transactions</strong>, but it is
                            applied to <strong>volume</strong> — matching the pricing spreadsheets this
                            tool replaces, so a quote reconciles with what has already been signed off.
                            Leave it blank to price every transaction at the below-threshold rate.
                        </Alert>
                        <div style={{ height: 12 }} />
                        <FormGrid cols={2}>
                            {splits.debit && (
                                <FormField label="Debit — % below threshold"
                                           hint={`Threshold ${ccy} ${money(n(splits.debit.threshold), 0)}`}>
                                    <Input type="number" value={form.debitSmallTxnPct} placeholder="100"
                                           onChange={e => set('debitSmallTxnPct', e.target.value)} />
                                </FormField>
                            )}
                            {splits.credit && (
                                <FormField label="Credit — % below threshold"
                                           hint={`Threshold ${ccy} ${money(n(splits.credit.threshold), 0)} · applies to MCC ${splits.credit.mcc || 'all'}`}>
                                    <Input type="number" value={form.creditSmallTxnPct} placeholder="100"
                                           onChange={e => set('creditSmallTxnPct', e.target.value)} />
                                </FormField>
                            )}
                        </FormGrid>
                    </Card>
                )}

                {/* ── The mix ──────────────────────────────────────────── */}
                <Card
                    title="Scheme mix"
                    subtitle="How the volume, the transactions and the MSF split across the rows this country quotes on."
                    actions={
                        <Row gap="sm">
                            <Button size="sm" onClick={copyVolumeToTxn}>Copy volume → txns</Button>
                            <Button size="sm" onClick={() => normalise('volumePct')}>Normalise volume</Button>
                            <Button size="sm" onClick={() => normalise('txnPct')}>Normalise txns</Button>
                            <Button size="sm" variant="primary" icon={Wand2}
                                    disabled={!result} onClick={applyRecommended}>
                                Apply recommended MSF
                            </Button>
                        </Row>
                    }>
                    <table className="ui-table">
                        <thead>
                            <tr>
                                <th>Scheme</th>
                                <th style={{ width: 120, textAlign: 'right' }}>Volume %</th>
                                <th style={{ width: 120, textAlign: 'right' }}>Txn %</th>
                                <th style={{ width: 120, textAlign: 'right' }}>MSF %</th>
                                <th style={{ width: 150, textAlign: 'right' }}>Volume ({ccy})</th>
                                <th style={{ width: 130, textAlign: 'right' }}>Transactions</th>
                            </tr>
                        </thead>
                        <tbody>
                            {mixRows.map(r => {
                                const priced = rows.find(x => x.schemeKey === r.schemeKey);
                                return (
                                    <tr key={r.schemeKey}>
                                        <td>
                                            {r.label}{' '}
                                            <Badge tone={r.cardType === 'DEBIT' ? 'info' : 'neutral'}>
                                                {r.cardType === 'DEBIT' ? 'Debit' : 'Credit'}
                                            </Badge>
                                        </td>
                                        <td style={{ textAlign: 'right' }}>
                                            <Input type="number" value={r.volumePct} placeholder="0"
                                                   onChange={e => setMix(r.schemeKey, 'volumePct', e.target.value)} />
                                        </td>
                                        <td style={{ textAlign: 'right' }}>
                                            <Input type="number" value={r.txnPct} placeholder="0"
                                                   onChange={e => setMix(r.schemeKey, 'txnPct', e.target.value)} />
                                        </td>
                                        <td style={{ textAlign: 'right' }}>
                                            <Input type="number" value={r.msfPct} placeholder="0"
                                                   onChange={e => setMix(r.schemeKey, 'msfPct', e.target.value)} />
                                        </td>
                                        <td style={{ textAlign: 'right' }}>
                                            {priced ? money(priced.volumeLocal) : '—'}
                                        </td>
                                        <td style={{ textAlign: 'right' }}>
                                            {priced ? money(priced.transactions, 0) : '—'}
                                        </td>
                                    </tr>
                                );
                            })}
                            <tr>
                                <td><strong>Total</strong></td>
                                <td style={{ textAlign: 'right' }}>
                                    <Badge tone={near100(volumeTotal) ? 'success' : 'warning'}>
                                        {pct(volumeTotal, 2)}
                                    </Badge>
                                </td>
                                <td style={{ textAlign: 'right' }}>
                                    <Badge tone={near100(txnTotal) ? 'success' : 'warning'}>
                                        {pct(txnTotal, 2)}
                                    </Badge>
                                </td>
                                <td colSpan={3} />
                            </tr>
                        </tbody>
                    </table>
                </Card>

                {/* ── Ancillary ────────────────────────────────────────── */}
                <Card title="Ancillary" subtitle="Income and cost that sit outside the scheme stack." pad>
                    <FormGrid cols={3}>
                        {isEcom ? (
                            <>
                                <FormField label={`Setup fee (${ccy})`} hint="One-time">
                                    <Input type="number" value={form.ecomSetupFee}
                                           onChange={e => set('ecomSetupFee', e.target.value)} />
                                </FormField>
                                <FormField label={`Annual fee (${ccy})`}>
                                    <Input type="number" value={form.ecomAnnualFee}
                                           onChange={e => set('ecomAnnualFee', e.target.value)} />
                                </FormField>
                                <FormField label="Payment gateway">
                                    <Select value={form.gatewayCostUsd}
                                            placeholder="Select…"
                                            options={(config?.gateways || []).map(g => ({
                                                value: String(g.costPerTxn),
                                                label: `${g.label} — USD ${money(n(g.costPerTxn), 2)}/txn`,
                                            }))}
                                            onChange={e => set('gatewayCostUsd', e.target.value)} />
                                </FormField>
                            </>
                        ) : (
                            <>
                                <FormField label="Terminals">
                                    <Input type="number" value={form.posUnits}
                                           onChange={e => set('posUnits', e.target.value)} />
                                </FormField>
                                <FormField label={`Rental per terminal / month (${ccy})`}>
                                    <Input type="number" value={form.posRateMonthly}
                                           onChange={e => set('posRateMonthly', e.target.value)} />
                                </FormField>
                                <FormField label="Annual rental" hint={`${ccy} — terminals × rate × 12`}>
                                    <Input readOnly value={money(n(form.posUnits) * n(form.posRateMonthly) * 12)} />
                                </FormField>
                            </>
                        )}
                        <FormField label="Settlement fee / txn (USD)">
                            <Input type="number" value={form.settlementFeePerTxnUsd}
                                   onChange={e => set('settlementFeePerTxnUsd', e.target.value)} />
                        </FormField>
                    </FormGrid>

                    <div style={{ height: 16 }} />
                    <FormGrid cols={4}>
                        <FormField label="DCC opt-in %" hint="Share of international volume">
                            <Input type="number" value={form.dccOptInPct}
                                   onChange={e => set('dccOptInPct', e.target.value)} />
                        </FormField>
                        <FormField label="DCC MSF %">
                            <Input type="number" value={form.dccMsfPct}
                                   onChange={e => set('dccMsfPct', e.target.value)} />
                        </FormField>
                        <FormField label="Merchant rebate %">
                            <Input type="number" value={form.dccMerchantRebatePct}
                                   onChange={e => set('dccMerchantRebatePct', e.target.value)} />
                        </FormField>
                        <FormField label="Vendor rebate %">
                            <Input type="number" value={form.dccVendorRebatePct}
                                   onChange={e => set('dccVendorRebatePct', e.target.value)} />
                        </FormField>
                    </FormGrid>
                </Card>

                {/* ── Result ───────────────────────────────────────────── */}
                {!canPrice && (
                    <Alert tone="warning" title="Not enough to price yet">
                        {!hasDeal
                            ? 'Choose an MCC and enter an annual volume and transaction count.'
                            : `The volume split totals ${pct(volumeTotal, 2)} and the transaction split ${pct(txnTotal, 2)} — both have to reach 100%.`}
                    </Alert>
                )}

                {notes.map((note, i) => (
                    <Alert key={i} tone="warning" title="Check this before quoting">{note}</Alert>
                ))}

                {totals && (
                    <>
                        <Card title="Pricing and cost"
                              subtitle={`Every figure in USD, converted at ${money(n(config?.country?.usdRate), 6)} per ${ccy}.`}>
                            <DataTable
                                loading={pricing}
                                rowKey={r => r.schemeKey}
                                rows={rows}
                                columns={[
                                    { key: 'label', header: 'Scheme' },
                                    { key: 'volumeUsd', header: 'Volume USD', align: 'right', numeric: true,
                                      render: r => money(r.volumeUsd) },
                                    { key: 'transactions', header: 'Txns', align: 'right', numeric: true,
                                      render: r => money(r.transactions, 0) },
                                    { key: 'msfRevenueUsd', header: 'MSF revenue', align: 'right', numeric: true,
                                      render: r => money(r.msfRevenueUsd) },
                                    { key: 'icPct', header: 'IC %', align: 'right', numeric: true,
                                      render: r => pct(r.icPct) },
                                    { key: 'interchangeUsd', header: 'Interchange', align: 'right', numeric: true,
                                      render: r => money(r.interchangeUsd) },
                                    { key: 'sfPct', header: 'SF %', align: 'right', numeric: true,
                                      render: r => pct(r.sfPct) },
                                    { key: 'schemeFeeUsd', header: 'Scheme fee', align: 'right', numeric: true,
                                      render: r => money(r.schemeFeeUsd) },
                                    { key: 'netMarginUsd', header: 'Net margin', align: 'right', numeric: true,
                                      render: r => (
                                        <span style={{ color: r.netMarginUsd < 0 ? 'var(--danger)' : 'var(--success)',
                                                       fontWeight: 600 }}>
                                            {money(r.netMarginUsd)}
                                        </span>) },
                                    { key: 'netMarginPct', header: 'Margin %', align: 'right', numeric: true,
                                      render: r => pct(r.netMarginPct) },
                                    { key: 'basis', header: 'Rate basis', muted: true },
                                ]}
                            />
                        </Card>

                        <Row gap="lg" style={{ alignItems: 'stretch' }}>
                            <Card title="Annual economics" subtitle="USD" pad style={{ flex: 1 }}>
                                <Line label="MSF revenue"      value={totals.msfRevenueUsd} />
                                <Line label="Interchange"      value={totals.interchangeUsd} />
                                <Line label="Scheme fees"      value={totals.schemeFeesUsd} />
                                <Line label="Transaction fees" value={-totals.txnFeesUsd} />
                                <Line label={isEcom ? 'E-commerce fees' : 'Terminal rental'}
                                      value={totals.rentalUsd + totals.ecomSetupUsd} />
                                <Line label="Settlement fees"  value={totals.settlementUsd} />
                                <Line label="DCC revenue"      value={totals.dccRevenueUsd} />
                                <Line label="Net spread"       value={totals.netSpreadUsd} strong />
                            </Card>

                            <Card title="Blended position" pad style={{ flex: 1 }}>
                                <Line label="Effective MSF"       value={totals.effectiveMsfPct} suffix="%" />
                                <Line label="Blended interchange" value={totals.blendedIcPct} suffix="%" />
                                <Line label="Blended scheme fee"  value={totals.blendedSfPct} suffix="%" />
                                <Line label="Net spread"          value={totals.netSpreadPct} suffix="%" strong />
                                <Line label="Margin per txn"      value={totals.marginPerTxnUsd} suffix=" USD" dp={4} />
                                <Line label={`Average ticket (${ccy})`} value={totals.avgTicketLocal} />
                            </Card>

                            <Card title="Recommended MSF"
                                  subtitle={`Blended cost plus a ${pct(totals.targetMarginPct, 2)} target margin.`}
                                  pad style={{ flex: 1 }}>
                                <Line label="Domestic debit"  value={totals.recommendedDebitMsfPct} suffix="%" strong />
                                <Line label="Domestic credit" value={totals.recommendedCreditMsfPct} suffix="%" strong />
                                <Line label="International"   value={totals.recommendedIntlMsfPct} suffix="%" strong />
                                <div style={{ marginTop: 12, fontSize: 12, color: 'var(--text-muted)',
                                              display: 'flex', gap: 6 }}>
                                    <Info size={14} style={{ flexShrink: 0, marginTop: 1 }} />
                                    <span>
                                        International is a fixed commercial rate, not a build-up from cost.
                                    </span>
                                </div>
                            </Card>
                        </Row>
                    </>
                )}
            </Stack>
            )}

            {/* ── Saved quotes ─────────────────────────────────────────── */}
            <Modal open={quotesOpen} onClose={() => setQuotesOpen(false)}
                   title="Saved quotes" size="xl"
                   footer={<Button onClick={() => setQuotesOpen(false)}>Close</Button>}>
                <DataTable
                    loading={quotesLoading}
                    rowKey={r => r.quote_id}
                    rows={quotes}
                    pageSize={10}
                    empty="No quotes have been saved yet."
                    columns={[
                        { key: 'quote_ref', header: 'Ref', mono: true },
                        { key: 'merchant_name', header: 'Merchant' },
                        { key: 'country_code', header: 'Country' },
                        { key: 'channel', header: 'Channel' },
                        { key: 'mcc', header: 'MCC', mono: true },
                        { key: 'net_spread_usd', header: 'Net spread USD', align: 'right', numeric: true,
                          render: r => money(n(r.net_spread_usd)) },
                        { key: 'status', header: 'Status',
                          render: r => <Badge tone={
                              r.status === 'WON' ? 'success'
                            : r.status === 'LOST' ? 'danger'
                            : r.status === 'SUBMITTED' ? 'info' : 'neutral'}>{r.status}</Badge> },
                        { key: 'created_by', header: 'By', muted: true },
                        { key: '_actions', header: '', align: 'right',
                          render: r => (
                            <Row gap="sm">
                                <Button size="sm" onClick={() => loadQuote(r)}>Open</Button>
                                <Button size="sm" variant="danger-ghost" iconOnly icon={Trash2}
                                        aria-label={`Delete ${r.quote_ref}`}
                                        onClick={() => deleteQuote(r)} />
                            </Row>) },
                    ]}
                />
            </Modal>
        </Page>
    );
}

/** One label/value line inside a summary card. */
function Line({ label, value, suffix = '', strong = false, dp = 2 }) {
    const v = Number.isFinite(value) ? value : 0;
    return (
        <div style={{
            display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
            padding: '6px 0',
            borderTop: strong ? '1px solid var(--border)' : 'none',
            marginTop: strong ? 6 : 0,
        }}>
            <span style={{ fontSize: 13, color: 'var(--text-muted)' }}>{label}</span>
            <span style={{
                fontSize: strong ? 15 : 13,
                fontWeight: strong ? 700 : 500,
                fontVariantNumeric: 'tabular-nums',
                color: v < 0 ? 'var(--danger)' : 'var(--text)',
            }}>
                {money(v, dp)}{suffix}
            </span>
        </div>
    );
}
