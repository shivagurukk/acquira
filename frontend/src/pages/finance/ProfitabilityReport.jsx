import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { RefreshCw, Download, Calculator, Search, Settings2, Trash2, Plus, X } from 'lucide-react';
import api from '../../api/axios';
import EmptyState from '../../components/EmptyState';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import { resolveDecimals } from '../../utils/formatters';

/* ════════════════════════════════════════════════════════════════════
   Profitability Report — the finance team's monthly MID P&L in the
   agreed column layout:

     Month Name | MID | Merchant Name | Merchant Type | MCC Category |
     Destination | Scheme | Card Interchange | Count | Volume | MSF |
     IC | Net revenue | Scheme Fees | MPGS Fees | PIE MSF Rebate/Recovery |
     KFCA Processing Fee | KFCA Per Txn Margin | IC++ Billing | Net Margin

   Count … MPGS Fees come off the fee engine's stored columns on the
   fact table. PIE / KFCA / IC++ are contractual terms the fee engine
   does not price — they are keyed in under "Adjustment rates" (tenant
   default, optional per-MID override, effective window) and applied
   per row as  volume·pct + MSF·pct + count·per-txn.

     net revenue = MSF − IC
     net margin  = net revenue − scheme − MPGS (− other PG) − PIE
                   − KFCA fee + KFCA per-txn margin + IC++ billing

   Data: POST /api/finance/profitability?month=YYYY-MM[&search=]
   ════════════════════════════════════════════════════════════════════ */

const num = (v) => (v == null ? 0 : Number(v));

const monthLabel = (ym) => {
    const m = /^(\d{4})-(\d{2})$/.exec(ym || '');
    return m ? new Date(Number(m[1]), Number(m[2]) - 1, 1)
        .toLocaleDateString('en-US', { month: 'long', year: 'numeric' }) : ym;
};

const ADJ_LABEL = {
    PIE_MSF_REBATE:      'PIE MSF Rebate/Recovery',
    KFCA_PROCESSING_FEE: 'KFCA Processing Fee',
    KFCA_PER_TXN_MARGIN: 'KFCA Per Txn Margin',
    ICPP_BILLING:        'IC++ Billing',
};
const ADJ_SIGN = {
    PIE_MSF_REBATE: 'cost', KFCA_PROCESSING_FEE: 'cost',
    KFCA_PER_TXN_MARGIN: 'income', ICPP_BILLING: 'income',
};

/* Column spec — `header` is the exact export header; money columns get
   " <CCY>" appended in the CSV so the sheet is self-describing. */
const COLS = [
    { key: 'monthName',         header: 'Month Name',               text: true },
    { key: 'mid',               header: 'MID',                      text: true },
    { key: 'merchantName',      header: 'Merchant Name',            text: true, wide: true },
    { key: 'merchantType',      header: 'Merchant Type',            text: true },
    { key: 'mccCategory',       header: 'MCC Category',             text: true, wide: true },
    { key: 'destination',       header: 'Destination',              text: true },
    { key: 'scheme',            header: 'Scheme',                   text: true },
    { key: 'cardInterchange',   header: 'Card Interchange',         text: true, wide: true },
    { key: 'count',             header: 'Count',                    int: true },
    { key: 'volume',            header: 'Volume',                   money: true },
    { key: 'msf',               header: 'MSF',                      money: true },
    { key: 'ic',                header: 'IC',                       money: true },
    { key: 'netRevenue',        header: 'Net revenue',              money: true, signed: true },
    { key: 'schemeFees',        header: 'Scheme Fees',              money: true },
    { key: 'mpgsFees',          header: 'MPGS Fees',                money: true },
    { key: 'pieMsfRebate',      header: 'PIE MSF Rebate/Recovery',  money: true },
    { key: 'kfcaProcessingFee', header: 'KFCA Processing Fee',      money: true },
    { key: 'kfcaPerTxnMargin',  header: 'KFCA Per Txn Margin',      money: true },
    { key: 'icppBilling',       header: 'IC++ Billing',             money: true },
    { key: 'netMargin',         header: 'Net Margin',               money: true, signed: true, strong: true },
];

const PAGE_SIZE = 200;

const ProfitabilityReport = () => {
    const { currencySymbol, currencyCode, currencyDecimals, tenantVersion } = useAuth();

    const [months, setMonths] = useState([]);
    const [latest, setLatest] = useState('');
    const [month, setMonth] = useState('');
    const [search, setSearch] = useState('');
    const [query, setQuery] = useState('');
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [bootstrapped, setBootstrapped] = useState(false);
    const [page, setPage] = useState(0);
    const [showRates, setShowRates] = useState(false);

    const ccyCode = data?.currency?.code || currencyCode || '';
    const dp = resolveDecimals(data?.currency?.decimals ?? currencyDecimals, ccyCode);
    const ccyLabel = ccyCode || currencySymbol || '';
    const money = useCallback((v) => Number(v || 0).toLocaleString('en-US',
        { minimumFractionDigits: dp, maximumFractionDigits: dp }), [dp]);

    /* Calendar: months that hold data + the latest date (→ default month). */
    useEffect(() => {
        let cancelled = false;
        api.get('/finance/profitability/calendar')
            .then(res => {
                if (cancelled) return;
                const ms = res.data?.months || [];
                const lt = res.data?.latest || '';
                setMonths(ms); setLatest(lt);
                if (lt) setMonth(lt.slice(0, 7));
                else if (ms.length) setMonth(ms[0]);
            })
            .catch(() => { /* the table still loads on the backend default */ })
            .finally(() => { if (!cancelled) setBootstrapped(true); });
        return () => { cancelled = true; };
    }, [tenantVersion]);

    const load = useCallback(async (signal) => {
        setLoading(true); setError(null);
        try {
            const params = {};
            if (month) params.month = month;
            if (query) params.search = query;
            const res = await api.post('/finance/profitability', {}, { signal, params });
            setData(res.data);
            setPage(0);
        } catch (e) {
            if (e?.name === 'CanceledError' || e?.code === 'ERR_CANCELED') return;
            setError(e?.response?.data?.message || 'Could not load the profitability report.');
        } finally {
            setLoading(false);
        }
    }, [month, query]);

    useEffect(() => {
        if (!bootstrapped) return;
        const ac = new AbortController();
        load(ac.signal);
        return () => ac.abort();
    }, [load, tenantVersion, bootstrapped]);

    /* Tenant switch: clear and re-bootstrap so no stale numbers flash. */
    useEffect(() => {
        setMonth(''); setQuery(''); setSearch(''); setData(null); setLoading(true); setBootstrapped(false);
    }, [tenantVersion]);

    const rows = data?.rows || [];
    const totals = data?.totals;
    const selection = data?.month || month || '—';
    const pageCount = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
    const pageRows = useMemo(() => rows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE), [rows, page]);
    const unconfigured = useMemo(() => {
        const have = new Set(data?.ratesConfigured || []);
        return Object.keys(ADJ_LABEL).filter(c => !have.has(c));
    }, [data]);

    const cell = (row, col) => {
        const v = row[col.key];
        if (col.text) return <span className="pr-txt">{v ?? '—'}</span>;
        if (col.int) return <span className="pr-num">{num(v).toLocaleString('en-US')}</span>;
        const n = num(v);
        if (col.signed) {
            return (
                <span className="pr-num" style={{ color: n >= 0 ? 'var(--success-text)' : 'var(--danger-text)' }}>
                    {money(n)}
                </span>
            );
        }
        return <span className="pr-num">{money(n)}</span>;
    };

    const exportCsv = () => {
        try {
            const esc = (s) => {
                const v = s == null ? '' : String(s);
                return /[",\n\r]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
            };
            const head = COLS.map(c => c.money ? `${c.header} ${ccyLabel}`.trim() : c.header);
            const line = (r) => COLS.map(c => {
                const v = r[c.key];
                if (c.text) return esc(v);
                if (c.int) return num(v);
                return num(v).toFixed(dp);
            }).join(',');
            const totalLine = totals ? line({
                ...totals, monthName: 'TOTAL', mid: '', merchantName: '', merchantType: '',
                mccCategory: '', destination: '', scheme: '', cardInterchange: '',
            }) : null;
            const lines = [head.join(','), ...rows.map(line), ...(totalLine ? [totalLine] : [])];
            const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = `profitability-report-${String(selection).replace(/[^0-9A-Za-z-]+/g, '_')}.csv`;
            a.click();
            URL.revokeObjectURL(a.href);
        } catch {
            showToast('Export failed. Try again.', 'error', 4000);
        }
    };

    const firstLoad = loading && !data;
    const hasData = rows.length > 0;

    return (
        <div className="pr-page" style={{ padding: '22px 26px 32px', width: '100%', boxSizing: 'border-box' }}>
            <style>{`
                .pr-panel { background: var(--surface, var(--bg-elevated, #fff)); border: 1px solid var(--border);
                    border-radius: 12px; }
                .pr-eyebrow { font-size: 10.5px; letter-spacing: .08em; text-transform: uppercase;
                    color: var(--text-muted); font-weight: 600; }
                .pr-num { font-family: var(--font-mono); font-variant-numeric: tabular-nums; font-weight: 600;
                    white-space: nowrap; }
                .pr-txt { white-space: nowrap; }
                .pr-tblwrap { overflow: auto; max-height: calc(100vh - 330px); }
                .pr-table { border-collapse: separate; border-spacing: 0; font-size: 12px; min-width: 100%; }
                .pr-table th { position: sticky; top: 0; z-index: 1; text-align: right; padding: 9px 12px;
                    font-size: 10px; letter-spacing: .05em; text-transform: uppercase; color: var(--text-secondary);
                    font-weight: 600; background: var(--bg-subtle); border-bottom: 1px solid var(--border);
                    white-space: nowrap; }
                .pr-table th.pr-l, .pr-table td.pr-l { text-align: left; }
                .pr-table td { padding: 8px 12px; text-align: right; border-bottom: 1px solid var(--border-light, var(--border)); }
                .pr-table td.pr-wide { max-width: 260px; overflow: hidden; text-overflow: ellipsis; }
                .pr-row-total td { position: sticky; bottom: 0; border-top: 2px solid var(--border); border-bottom: none;
                    background: var(--bg-subtle); font-weight: 700; }
                .pr-btn { display: inline-flex; align-items: center; gap: 6px; padding: 8px 13px;
                    border: 1px solid var(--border); border-radius: 9px; background: var(--surface, #fff);
                    color: var(--text); font-size: 12.5px; font-weight: 600; cursor: pointer; }
                .pr-btn:hover { background: var(--bg-subtle); }
                .pr-btn:disabled { opacity: .55; cursor: default; }
                .pr-tile { padding: 14px 18px; border-right: 1px solid var(--border-light, var(--border)); }
                .pr-tile:last-child { border-right: none; }
                .pr-tile-val { font-family: var(--font-mono); font-variant-numeric: tabular-nums;
                    font-size: 21px; font-weight: 700; margin-top: 7px; }
                .pr-select, .pr-input { padding: 8px 11px; border: 1px solid var(--border); border-radius: 9px;
                    background: var(--surface, #fff); color: var(--text); font-size: 13px; font-weight: 600; }
                .pr-input { font-weight: 500; min-width: 220px; }
                .pr-note { margin-top: 12px; font-size: 11px; color: var(--text-muted); line-height: 1.6; }
                .pr-warn { padding: 10px 14px; margin-bottom: 14px; border-radius: 10px; font-size: 12px;
                    background: color-mix(in srgb, var(--warning, #F59E0B) 12%, transparent);
                    border: 1px solid color-mix(in srgb, var(--warning, #F59E0B) 40%, transparent); color: var(--text); }
                @keyframes pr-spin { to { transform: rotate(360deg); } } .pr-spin { animation: pr-spin 1s linear infinite; }
            `}</style>

            {/* Header */}
            <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between',
                gap: 16, flexWrap: 'wrap', marginBottom: 16 }}>
                <div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
                        <Calculator size={20} strokeWidth={2} color="var(--primary, var(--text))" />
                        <h1 style={{ margin: 0, fontSize: 21, fontWeight: 700, color: 'var(--text)' }}>
                            Profitability Report
                        </h1>
                    </div>
                    <div style={{ marginTop: 5, fontSize: 12.5, color: 'var(--text-secondary)' }}>
                        Monthly MID P&amp;L by destination, scheme and card interchange
                        {' · '}{monthLabel(selection) || selection}
                        {ccyLabel ? ` · ${ccyLabel}` : ''}
                    </div>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 9, flexWrap: 'wrap' }}>
                    <form onSubmit={(e) => { e.preventDefault(); setQuery(search.trim()); }}
                        style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                        <input className="pr-input" value={search} onChange={(e) => setSearch(e.target.value)}
                            placeholder="Merchant name or MID" aria-label="Search merchant" />
                        <button className="pr-btn" type="submit" title="Search"><Search size={15} /></button>
                        {query && (
                            <button className="pr-btn" type="button" title="Clear search"
                                onClick={() => { setSearch(''); setQuery(''); }}><X size={15} /></button>
                        )}
                    </form>
                    <select className="pr-select" value={month}
                        onChange={(e) => setMonth(e.target.value)} aria-label="Period">
                        {latest && !months.includes(latest.slice(0, 7)) && (
                            <option value={latest.slice(0, 7)}>{monthLabel(latest.slice(0, 7))}</option>
                        )}
                        {months.map(m => <option key={m} value={m}>{monthLabel(m)}</option>)}
                    </select>
                    <button className="pr-btn" onClick={() => load()} disabled={loading} title="Refresh">
                        <RefreshCw size={15} className={loading ? 'pr-spin' : undefined} /> Refresh
                    </button>
                    <button className="pr-btn" onClick={() => setShowRates(s => !s)} title="Adjustment rates">
                        <Settings2 size={15} /> Rates
                    </button>
                    <button className="pr-btn" onClick={exportCsv} disabled={!hasData} title="Export CSV">
                        <Download size={15} /> CSV
                    </button>
                </div>
            </div>

            {error && (
                <div className="pr-panel" style={{ padding: 16, marginBottom: 14, borderColor: 'var(--danger)',
                    color: 'var(--danger-text)', fontSize: 13 }}>{error}</div>
            )}

            {showRates && (
                <AdjustmentRates ccyLabel={ccyLabel} onChanged={() => load()} onClose={() => setShowRates(false)} />
            )}

            {data && unconfigured.length > 0 && (
                <div className="pr-warn">
                    No rate configured for {unconfigured.map(c => ADJ_LABEL[c]).join(', ')} — those columns
                    read 0 until a rate is keyed in under <b>Rates</b>.
                </div>
            )}
            {data?.truncated && (
                <div className="pr-warn">
                    This month has more than {(50000).toLocaleString()} grouped rows; only the first
                    {' '}{(50000).toLocaleString()} are shown. Narrow by merchant to see the rest.
                </div>
            )}

            {firstLoad ? (
                <div className="pr-panel" style={{ padding: 40, textAlign: 'center', color: 'var(--text-muted)' }}>
                    Loading the profitability report…
                </div>
            ) : !hasData ? (
                <EmptyState title="No transactions for this period"
                    subtitle="Once transactions are loaded for the selected month, the MID profitability rows appear here." />
            ) : (
                <>
                    {/* Headline tiles */}
                    <div className="pr-panel" style={{ display: 'grid',
                        gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', marginBottom: 14, overflow: 'hidden' }}>
                        {[
                            ['Net Margin', totals?.netMargin, true],
                            ['Net Revenue (MSF − IC)', totals?.netRevenue, true],
                            ['MSF', totals?.msf],
                            ['Interchange', totals?.ic],
                            ['Scheme + MPGS', num(totals?.schemeFees) + num(totals?.mpgsFees)],
                            ['Volume', totals?.volume],
                        ].map(([label, v, signed]) => (
                            <div className="pr-tile" key={label}>
                                <div className="pr-eyebrow">{label}</div>
                                <div className="pr-tile-val" style={{ color: signed && num(v) < 0 ? 'var(--danger-text)' : 'var(--text)' }}>
                                    {ccyLabel} {money(num(v))}
                                </div>
                            </div>
                        ))}
                        <div className="pr-tile">
                            <div className="pr-eyebrow">Rows · Transactions</div>
                            <div className="pr-tile-val">{rows.length.toLocaleString()} · {num(totals?.count).toLocaleString()}</div>
                        </div>
                    </div>

                    {/* Report table */}
                    <div className="pr-panel pr-tblwrap">
                        <table className="pr-table">
                            <thead>
                                <tr>
                                    {COLS.map(c => (
                                        <th key={c.key} className={c.text ? 'pr-l' : undefined}>
                                            {c.header}{c.money && ccyLabel ? ` ${ccyLabel}` : ''}
                                        </th>
                                    ))}
                                </tr>
                            </thead>
                            <tbody>
                                {pageRows.map((r, i) => (
                                    <tr key={`${r.mid}-${r.merchantType}-${r.destination}-${r.scheme}-${r.cardInterchange}-${i}`}>
                                        {COLS.map(c => (
                                            <td key={c.key} className={`${c.text ? 'pr-l' : ''} ${c.wide ? 'pr-wide' : ''}`.trim()}
                                                title={c.wide ? r[c.key] : undefined}>
                                                {cell(r, c)}
                                            </td>
                                        ))}
                                    </tr>
                                ))}
                                {totals && (
                                    <tr className="pr-row-total">
                                        {COLS.map((c, i) => (
                                            <td key={c.key} className={c.text ? 'pr-l' : undefined}>
                                                {i === 0 ? 'TOTAL' : c.text ? '' : cell(totals, c)}
                                            </td>
                                        ))}
                                    </tr>
                                )}
                            </tbody>
                        </table>
                    </div>

                    {pageCount > 1 && (
                        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 10, fontSize: 12 }}>
                            <button className="pr-btn" disabled={page === 0} onClick={() => setPage(p => p - 1)}>‹ Prev</button>
                            <span>Page {page + 1} of {pageCount} · rows {page * PAGE_SIZE + 1}–{Math.min(rows.length, (page + 1) * PAGE_SIZE)}</span>
                            <button className="pr-btn" disabled={page >= pageCount - 1} onClick={() => setPage(p => p + 1)}>Next ›</button>
                        </div>
                    )}

                    <div className="pr-note">
                        Net revenue = MSF − IC. Net margin = net revenue − scheme fees − MPGS fees
                        {num(totals?.otherPgFees) ? ` − other gateway fees (${ccyLabel} ${money(totals.otherPgFees)})` : ''}
                        {' '}− PIE MSF rebate − KFCA processing fee + KFCA per-txn margin + IC++ billing.
                        Merchant Type is the transaction channel the fee engine resolved (POS / ECOM); MCC Category is
                        the store's MCC sector; Card Interchange is the interchange rule that priced the row. Fees are the
                        stored fee-engine values for the month, never re-priced here. The CSV carries every row; the table
                        pages {PAGE_SIZE} at a time.
                    </div>
                </>
            )}
        </div>
    );
};

/* ── Adjustment rates panel ─────────────────────────────────────────
   Tenant default (MID blank) or per-MID override, each with an
   effective window. amount = volume·pctOfVolume + MSF·pctOfMsf +
   count·perTxn.  Percentages are entered as percent (0.10 = 0.10%)
   and stored as fractions. */
const EMPTY_FORM = { id: null, code: 'PIE_MSF_REBATE', mid: '', pctOfVolume: '', pctOfMsf: '', perTxn: '',
    effectiveFrom: '', effectiveTo: '', note: '' };

const AdjustmentRates = ({ ccyLabel, onChanged, onClose }) => {
    const [rows, setRows] = useState([]);
    const [form, setForm] = useState(EMPTY_FORM);
    const [busy, setBusy] = useState(false);

    const refresh = useCallback(() => {
        api.get('/finance/profitability/adjustments')
            .then(res => setRows(res.data?.rows || []))
            .catch(() => showToast('Could not load adjustment rates.', 'error', 4000));
    }, []);
    useEffect(() => { refresh(); }, [refresh]);

    const pct = (v) => (v === '' || v == null ? 0 : Number(v) / 100);
    const save = async (e) => {
        e.preventDefault();
        setBusy(true);
        try {
            await api.put('/finance/profitability/adjustments', {
                id: form.id, code: form.code, mid: form.mid || null,
                pctOfVolume: pct(form.pctOfVolume), pctOfMsf: pct(form.pctOfMsf),
                perTxn: form.perTxn === '' ? 0 : Number(form.perTxn),
                effectiveFrom: form.effectiveFrom || null, effectiveTo: form.effectiveTo || null,
                note: form.note || null,
            });
            setForm(EMPTY_FORM); refresh(); onChanged?.();
            showToast('Rate saved.', 'success', 2500);
        } catch (err) {
            showToast(err?.response?.data?.message || 'Save failed (EDIT permission required).', 'error', 4000);
        } finally { setBusy(false); }
    };
    const remove = async (id) => {
        if (!window.confirm('Delete this rate?')) return;
        try {
            await api.delete(`/finance/profitability/adjustments/${id}`);
            refresh(); onChanged?.();
        } catch {
            showToast('Delete failed (EDIT permission required).', 'error', 4000);
        }
    };
    const edit = (r) => setForm({
        id: r.id, code: r.code, mid: r.mid || '',
        pctOfVolume: r.pctOfVolume == null ? '' : (Number(r.pctOfVolume) * 100).toString(),
        pctOfMsf: r.pctOfMsf == null ? '' : (Number(r.pctOfMsf) * 100).toString(),
        perTxn: r.perTxn == null ? '' : String(r.perTxn),
        effectiveFrom: r.effectiveFrom || '', effectiveTo: r.effectiveTo || '', note: r.note || '',
    });
    const f = (k) => (e) => setForm(s => ({ ...s, [k]: e.target.value }));
    const inp = { padding: '6px 9px', border: '1px solid var(--border)', borderRadius: 7, fontSize: 12,
        background: 'var(--surface, #fff)', color: 'var(--text)' };

    return (
        <div className="pr-panel" style={{ padding: 16, marginBottom: 14 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
                <div>
                    <div style={{ fontWeight: 700, fontSize: 14 }}>Adjustment rates</div>
                    <div style={{ fontSize: 11.5, color: 'var(--text-muted)' }}>
                        Per row: volume × % of volume + MSF × % of MSF + count × per-txn. Blank MID = tenant default;
                        a MID row overrides it. PIE and KFCA fee are deducted; KFCA margin and IC++ billing are added.
                    </div>
                </div>
                <button className="pr-btn" onClick={onClose} title="Close"><X size={15} /></button>
            </div>

            <form onSubmit={save} style={{ display: 'grid', gap: 8, alignItems: 'end', marginBottom: 12,
                gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))' }}>
                <label style={{ fontSize: 11 }}>Line<br />
                    <select style={{ ...inp, width: '100%' }} value={form.code} onChange={f('code')}>
                        {Object.entries(ADJ_LABEL).map(([k, v]) => <option key={k} value={k}>{v} ({ADJ_SIGN[k]})</option>)}
                    </select></label>
                <label style={{ fontSize: 11 }}>MID (blank = all)<br />
                    <input style={{ ...inp, width: '100%' }} value={form.mid} onChange={f('mid')} /></label>
                <label style={{ fontSize: 11 }}>% of volume<br />
                    <input style={{ ...inp, width: '100%' }} type="number" step="0.0001" value={form.pctOfVolume} onChange={f('pctOfVolume')} placeholder="0.10" /></label>
                <label style={{ fontSize: 11 }}>% of MSF<br />
                    <input style={{ ...inp, width: '100%' }} type="number" step="0.0001" value={form.pctOfMsf} onChange={f('pctOfMsf')} placeholder="10" /></label>
                <label style={{ fontSize: 11 }}>Per txn {ccyLabel}<br />
                    <input style={{ ...inp, width: '100%' }} type="number" step="0.0001" value={form.perTxn} onChange={f('perTxn')} placeholder="0.050" /></label>
                <label style={{ fontSize: 11 }}>From<br />
                    <input style={{ ...inp, width: '100%' }} type="date" value={form.effectiveFrom} onChange={f('effectiveFrom')} /></label>
                <label style={{ fontSize: 11 }}>To<br />
                    <input style={{ ...inp, width: '100%' }} type="date" value={form.effectiveTo} onChange={f('effectiveTo')} /></label>
                <label style={{ fontSize: 11 }}>Note<br />
                    <input style={{ ...inp, width: '100%' }} value={form.note} onChange={f('note')} maxLength={200} /></label>
                <div style={{ display: 'flex', gap: 6 }}>
                    <button className="pr-btn" type="submit" disabled={busy}><Plus size={14} /> {form.id ? 'Update' : 'Add'}</button>
                    {form.id && <button className="pr-btn" type="button" onClick={() => setForm(EMPTY_FORM)}>Cancel</button>}
                </div>
            </form>

            <table className="pr-table" style={{ fontSize: 12 }}>
                <thead><tr>
                    <th className="pr-l">Line</th><th className="pr-l">MID</th><th>% of volume</th><th>% of MSF</th>
                    <th>Per txn</th><th className="pr-l">From</th><th className="pr-l">To</th><th className="pr-l">Note</th>
                    <th className="pr-l">By</th><th></th>
                </tr></thead>
                <tbody>
                    {rows.length === 0 && (
                        <tr><td colSpan={10} className="pr-l" style={{ color: 'var(--text-muted)' }}>No rates configured.</td></tr>
                    )}
                    {rows.map(r => (
                        <tr key={r.id}>
                            <td className="pr-l">{ADJ_LABEL[r.code] || r.code}</td>
                            <td className="pr-l">{r.mid || <i style={{ color: 'var(--text-muted)' }}>all</i>}</td>
                            <td className="pr-num">{(Number(r.pctOfVolume || 0) * 100).toFixed(4)}%</td>
                            <td className="pr-num">{(Number(r.pctOfMsf || 0) * 100).toFixed(4)}%</td>
                            <td className="pr-num">{Number(r.perTxn || 0).toFixed(4)}</td>
                            <td className="pr-l">{r.effectiveFrom || ''}</td>
                            <td className="pr-l">{r.effectiveTo || <i style={{ color: 'var(--text-muted)' }}>open</i>}</td>
                            <td className="pr-l">{r.note || ''}</td>
                            <td className="pr-l">{r.updatedBy || ''}</td>
                            <td style={{ whiteSpace: 'nowrap' }}>
                                <button className="pr-btn" style={{ padding: '4px 8px' }} onClick={() => edit(r)}>Edit</button>{' '}
                                <button className="pr-btn" style={{ padding: '4px 8px' }} onClick={() => remove(r.id)} title="Delete"><Trash2 size={13} /></button>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
};

export default ProfitabilityReport;
