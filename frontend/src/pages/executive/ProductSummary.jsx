import React, { useState, useEffect, useCallback } from 'react';
import { RefreshCw, Download, PackageOpen } from 'lucide-react';
import api from '../../api/axios';
import EmptyState from '../../components/EmptyState';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import {
    resolveDecimals,
    isUsdDisplay, convertForDisplay, displayCurrencyCode, usdRateInfo,
} from '../../utils/formatters';

/* ════════════════════════════════════════════════════════════════════
   Product Summary — a per-tenant P&L that reads down the revenue
   PRODUCTS rather than across merchants:

     Acquiring · POS    Acquiring · ECOM    DCC    Rental    (FX)    TOTAL

   with volume, MSF, interchange, scheme fee, net margin and net spread.

     net margin = MSF − interchange − scheme fee − PG            (4-leg)
     net spread = net margin + DCC acquirer share + rental (+ FX)

   Acquiring rows come from the channel-grain sum_daily_full (same
   source as the channel selector); the ancillary rows from
   sum_daily_merchant (same columns as the Net Spread page). Summary
   reads only — nothing is re-priced here.

   Data: POST /api/business/product-summary — defaults to the latest
   loaded business month.
   ════════════════════════════════════════════════════════════════════ */

const num = (v) => (v == null ? 0 : Number(v));

const monthLabel = (ym) => {
    const m = /^(\d{4})-(\d{2})$/.exec(ym || '');
    return m ? new Date(Number(m[1]), Number(m[2]) - 1, 1)
        .toLocaleDateString('en-US', { month: 'long', year: 'numeric' }) : ym;
};

/* Columns after the product name. Volume/count/fees are blank on the
   ancillary rows (they carry no transactions); net spread closes every row. */
const COLS = [
    { key: 'volume', label: 'Volume',          money: true },
    { key: 'msf',    label: 'MSF',             money: true },
    { key: 'icf',    label: 'Interchange Fee', money: true },
    { key: 'sf',     label: 'Scheme Fee',      money: true },
    { key: 'pg',     label: 'Gateway Fee',     money: true },
    { key: 'nm',     label: 'Net Margin',      money: true, signed: true },
    { key: 'spread', label: 'Net Spread',      money: true, signed: true, strong: true },
];

const PRODUCT_SUB = {
    'Acquiring · POS':  'card-present · terminal',
    'Acquiring · ECOM': 'card-not-present · gateway',
    'DCC':              'dynamic currency conversion · acquirer share',
    'Rental':           'terminal rental income',
    'FX Income':        'e-commerce FX margin',
    'TOTAL':            'net spread across all products',
};

const ProductSummary = () => {
    const { currencySymbol, currencyCode, currencyDecimals, tenantVersion } = useAuth();

    const dp = isUsdDisplay(currencyCode) ? 2 : resolveDecimals(currencyDecimals, currencyCode);
    const money = useCallback((v) => convertForDisplay(Number(v || 0), currencyCode).toLocaleString('en-US',
        { minimumFractionDigits: dp, maximumFractionDigits: dp }), [dp, currencyCode]);
    const ccyLabel = displayCurrencyCode(currencyCode) || currencySymbol || '';

    /* Volume is the widest figure on the page — render it in millions/
       billions so the table breathes; the exact figure stays on hover and
       in the CSV export. */
    const compactVol = useCallback((v) => {
        const n = convertForDisplay(Number(v || 0), currencyCode);
        const a = Math.abs(n);
        if (a >= 1e9) return (n / 1e9).toFixed(2) + 'B';
        if (a >= 1e6) return (n / 1e6).toFixed(2) + 'M';
        if (a >= 1e3) return (n / 1e3).toFixed(1) + 'K';
        return n.toLocaleString('en-US', { minimumFractionDigits: dp, maximumFractionDigits: dp });
    }, [currencyCode, dp]);

    const [months, setMonths] = useState([]);
    const [latest, setLatest] = useState('');
    const [month, setMonth] = useState('');
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [bootstrapped, setBootstrapped] = useState(false);
    const [exporting, setExporting] = useState(false);
    const [lastRefresh, setLastRefresh] = useState(null);

    /* Calendar: months that hold data + the latest date (→ default month). */
    useEffect(() => {
        let cancelled = false;
        api.get('/business/product-summary/calendar')
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
            const res = await api.post('/business/product-summary', {}, {
                signal, params: month ? { month } : {},
            });
            setData(res.data);
            setLastRefresh(new Date());
        } catch (e) {
            if (e?.name === 'CanceledError' || e?.code === 'ERR_CANCELED') return;
            setError(e?.response?.data?.message || 'Could not load the product summary.');
        } finally {
            setLoading(false);
        }
    }, [month]);

    useEffect(() => {
        if (!bootstrapped) return;
        const ac = new AbortController();
        load(ac.signal);
        return () => ac.abort();
    }, [load, tenantVersion, bootstrapped]);

    /* Tenant switch: clear and re-bootstrap so no stale numbers flash. */
    useEffect(() => {
        setMonth(''); setData(null); setLoading(true); setBootstrapped(false);
    }, [tenantVersion]);

    const rows = data?.rows || [];
    const totals = data?.totals;
    const totalSpread = num(totals?.spread);
    const selection = data?.selection || (month ? month : '—');

    const share = (v) => (totalSpread === 0 ? '—'
        : `${((num(v) / totalSpread) * 100).toFixed(1)}%`);

    const cell = (row, col) => {
        const v = row[col.key];
        if (v == null) return <span className="ps-dash">—</span>;
        const n = num(v);
        if (col.key === 'volume') {
            return (
                <span className="ps-num" title={`${ccyLabel} ${money(n)}`.trim()}>
                    {compactVol(n)}
                </span>
            );
        }
        if (col.signed) {
            const pos = n >= 0;
            return (
                <span className="ps-signed" style={{ color: pos ? 'var(--success-text)' : 'var(--danger-text)' }}>
                    <span aria-hidden="true" style={{ fontSize: 9, opacity: 0.85 }}>{pos ? '▲' : '▼'}</span>
                    {money(n)}
                </span>
            );
        }
        return <span className="ps-num">{money(n)}</span>;
    };

    const exportCsv = () => {
        setExporting(true);
        try {
            const cv = (v) => convertForDisplay(num(v), currencyCode);
            const esc = (s) => `"${String(s ?? '').replace(/"/g, '""')}"`;
            const fx = usdRateInfo(currencyCode);
            const head = ['Product', 'Volume', 'Count', 'MSF', 'Interchange Fee', 'Scheme Fee',
                'Gateway Fee', 'Net Margin', 'DCC (Acquirer)', 'DCC (Merchant)', 'Rental',
                ...(data?.fxEnabled ? ['FX Income'] : []), 'Net Spread', 'Share of Spread'];
            const line = (r) => [
                esc(r.product),
                r.volume == null ? '' : cv(r.volume).toFixed(dp),
                r.count == null ? '' : num(r.count),
                r.msf == null ? '' : cv(r.msf).toFixed(dp),
                r.icf == null ? '' : cv(r.icf).toFixed(dp),
                r.sf == null ? '' : cv(r.sf).toFixed(dp),
                r.pg == null ? '' : cv(r.pg).toFixed(dp),
                r.nm == null ? '' : cv(r.nm).toFixed(dp),
                cv(r.dcc).toFixed(dp), cv(r.dccMerchant).toFixed(dp), cv(r.rental).toFixed(dp),
                ...(data?.fxEnabled ? [cv(r.fx).toFixed(dp)] : []),
                cv(r.spread).toFixed(dp),
                totalSpread ? ((num(r.spread) / totalSpread) * 100).toFixed(1) + '%' : '',
            ].join(',');
            const lines = [
                `Currency,${ccyLabel || 'UNKNOWN'}`,
                ...(fx ? [`FX Rate,1 ${fx.base} = ${fx.rate} USD (indicative; as of ${fx.asOf})`] : []),
                `Period,${selection}`,
                head.join(','),
                ...rows.map(line),
                ...(totals ? [line({ ...totals })] : []),
            ];
            const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' });
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = `product-summary-${String(selection).replace(/[^0-9A-Za-z-]+/g, '_')}.csv`;
            a.click();
            URL.revokeObjectURL(a.href);
        } catch {
            showToast('Export failed. Try again.', 'error', 4000);
        } finally {
            setExporting(false);
        }
    };

    const netMargin = num(totals?.nm);
    const ancillary = num(totals?.dcc) + num(totals?.rental) + (data?.fxEnabled ? num(totals?.fx) : 0);
    const spreadPct = totals && num(totals.volume) ? (totalSpread / num(totals.volume)) * 100 : null;
    const refreshedAt = lastRefresh
        ? lastRefresh.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }) : null;

    const firstLoad = loading && !data;
    const hasData = rows.length > 0;

    return (
        <div className="ps-page" style={{ padding: '22px 26px 32px', width: '100%', boxSizing: 'border-box' }}>
            <style>{`
                .ps-panel { background: var(--surface, var(--bg-elevated, #fff)); border: 1px solid var(--border);
                    border-radius: 12px; }
                .ps-eyebrow { font-size: 10.5px; letter-spacing: .08em; text-transform: uppercase;
                    color: var(--text-muted); font-weight: 600; }
                .ps-num, .ps-signed { font-family: var(--font-mono); font-variant-numeric: tabular-nums;
                    font-weight: 600; white-space: nowrap; }
                .ps-signed { display: inline-flex; align-items: center; gap: 5px; justify-content: flex-end; }
                .ps-dash { color: var(--text-muted); }
                .ps-table { width: 100%; border-collapse: collapse; font-size: 13px; }
                .ps-table th { text-align: right; padding: 11px 16px; font-size: 10.5px; letter-spacing: .05em;
                    text-transform: uppercase; color: var(--text-secondary); font-weight: 600;
                    background: var(--bg-subtle); border-bottom: 1px solid var(--border); white-space: nowrap; }
                .ps-table th.ps-l { text-align: left; }
                .ps-table td { padding: 13px 16px; text-align: right; border-bottom: 1px solid var(--border-light, var(--border)); }
                .ps-table td.ps-l { text-align: left; }
                .ps-row-total td { border-top: 2px solid var(--border); border-bottom: none;
                    background: var(--bg-subtle); font-weight: 700; }
                .ps-row-anc td { background: color-mix(in srgb, var(--bg-subtle) 45%, transparent); }
                .ps-prod { font-weight: 600; color: var(--text); }
                .ps-prod-sub { font-size: 10.5px; color: var(--text-muted); margin-top: 2px; }
                .ps-chip { display: inline-flex; align-items: center; padding: 2px 8px; border-radius: 999px;
                    font-size: 10px; font-weight: 700; letter-spacing: .04em; }
                .ps-btn { display: inline-flex; align-items: center; gap: 6px; padding: 8px 13px;
                    border: 1px solid var(--border); border-radius: 9px; background: var(--surface, #fff);
                    color: var(--text); font-size: 12.5px; font-weight: 600; cursor: pointer; }
                .ps-btn:hover { background: var(--bg-subtle); }
                .ps-tile { padding: 14px 18px; border-right: 1px solid var(--border-light, var(--border)); }
                .ps-tile:last-child { border-right: none; }
                .ps-tile-val { font-family: var(--font-mono); font-variant-numeric: tabular-nums;
                    font-size: 23px; font-weight: 700; margin-top: 7px; }
                .ps-select { padding: 8px 11px; border: 1px solid var(--border); border-radius: 9px;
                    background: var(--surface, #fff); color: var(--text); font-size: 13px; font-weight: 600; }
                @media (max-width: 720px) { .ps-tblwrap { overflow-x: auto; } }
            `}</style>

            {/* Header */}
            <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between',
                gap: 16, flexWrap: 'wrap', marginBottom: 16 }}>
                <div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
                        <PackageOpen size={20} strokeWidth={2} color="var(--primary, var(--text))" />
                        <h1 style={{ margin: 0, fontSize: 21, fontWeight: 700, color: 'var(--text)' }}>
                            Product Summary
                        </h1>
                    </div>
                    <div style={{ marginTop: 5, fontSize: 12.5, color: 'var(--text-secondary)' }}>
                        Revenue by product — POS &amp; ECOM acquiring, DCC, rentals{data?.fxEnabled ? ' and FX' : ''}
                        {' · '}{monthLabel(selection) || selection}
                        {ccyLabel ? ` · ${ccyLabel}` : ''}
                    </div>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 9, flexWrap: 'wrap' }}>
                    <select className="ps-select" value={month}
                        onChange={(e) => setMonth(e.target.value)} aria-label="Period">
                        {latest && !months.includes(latest.slice(0, 7)) && (
                            <option value={latest.slice(0, 7)}>{monthLabel(latest.slice(0, 7))}</option>
                        )}
                        {months.map(m => <option key={m} value={m}>{monthLabel(m)}</option>)}
                    </select>
                    <button className="ps-btn" onClick={() => load()} disabled={loading} title="Refresh">
                        <RefreshCw size={15} className={loading ? 'ps-spin' : undefined} /> Refresh
                    </button>
                    <button className="ps-btn" onClick={exportCsv} disabled={exporting || !hasData} title="Export CSV">
                        <Download size={15} /> {exporting ? 'Exporting…' : 'CSV'}
                    </button>
                </div>
            </div>

            {error && (
                <div className="ps-panel" style={{ padding: 16, marginBottom: 14, borderColor: 'var(--danger)',
                    color: 'var(--danger-text)', fontSize: 13 }}>{error}</div>
            )}

            {firstLoad ? (
                <div className="ps-panel" style={{ padding: 40, textAlign: 'center', color: 'var(--text-muted)' }}>
                    Loading the product summary…
                </div>
            ) : !hasData ? (
                <EmptyState title="No product data for this period"
                    subtitle="Once transactions are loaded for the selected month, the product breakdown appears here." />
            ) : (
                <>
                    {/* Headline tiles */}
                    <div className="ps-panel" style={{ display: 'grid',
                        gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', marginBottom: 14, overflow: 'hidden' }}>
                        <div className="ps-tile">
                            <div className="ps-eyebrow">Net Spread</div>
                            <div className="ps-tile-val" style={{ color: totalSpread >= 0 ? 'var(--success-text)' : 'var(--danger-text)' }}>
                                {ccyLabel} {money(totalSpread)}
                            </div>
                            <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 3 }}>
                                {spreadPct == null ? 'margin + DCC + rentals' : `${spreadPct.toFixed(2)}% of volume`}
                            </div>
                        </div>
                        <div className="ps-tile">
                            <div className="ps-eyebrow">Net Margin</div>
                            <div className="ps-tile-val" style={{ color: netMargin >= 0 ? 'var(--text)' : 'var(--danger-text)' }}>
                                {ccyLabel} {money(netMargin)}
                            </div>
                            <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 3 }}>MSF − IC − SF − PG</div>
                        </div>
                        <div className="ps-tile">
                            <div className="ps-eyebrow">Ancillary Revenue</div>
                            <div className="ps-tile-val" style={{ color: 'var(--text)' }}>{ccyLabel} {money(ancillary)}</div>
                            <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 3 }}>
                                {data?.fxEnabled ? 'DCC + rentals + FX' : 'DCC + rentals'}
                            </div>
                        </div>
                        <div className="ps-tile">
                            <div className="ps-eyebrow">Card Volume</div>
                            <div className="ps-tile-val" style={{ color: 'var(--text)' }}
                                title={`${ccyLabel} ${money(num(totals?.volume))}`.trim()}>
                                {ccyLabel} {compactVol(num(totals?.volume))}
                            </div>
                            <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 3 }}>
                                {num(totals?.count).toLocaleString()} transactions
                            </div>
                        </div>
                    </div>

                    {/* Product table */}
                    <div className="ps-panel ps-tblwrap" style={{ overflow: 'hidden' }}>
                        <table className="ps-table">
                            <thead>
                                <tr>
                                    <th className="ps-l">Product</th>
                                    {COLS.map(c => <th key={c.key}>{c.label}</th>)}
                                    <th>Share</th>
                                </tr>
                            </thead>
                            <tbody>
                                {rows.map((r) => {
                                    const isTotal = r.kind === 'TOTAL';
                                    const isAnc = r.kind === 'DCC' || r.kind === 'RENTAL' || r.kind === 'FX';
                                    return (
                                        <tr key={r.product}
                                            className={isTotal ? 'ps-row-total' : isAnc ? 'ps-row-anc' : undefined}>
                                            <td className="ps-l">
                                                <div className="ps-prod">{r.product}</div>
                                                <div className="ps-prod-sub">{PRODUCT_SUB[r.product] || ''}</div>
                                                {r.kind === 'DCC' && num(r.dccMerchant) !== 0 && (
                                                    <div className="ps-prod-sub" title="Merchant's share of DCC — not part of the spread">
                                                        merchant share {money(r.dccMerchant)} (excluded)
                                                    </div>
                                                )}
                                            </td>
                                            {COLS.map(c => <td key={c.key}>{cell(r, c)}</td>)}
                                            <td>
                                                {isTotal ? <span className="ps-num">100%</span>
                                                    : <span className="ps-chip" style={{
                                                        background: 'color-mix(in srgb, var(--primary, #3B82F6) 12%, transparent)',
                                                        color: 'var(--primary, #3B82F6)' }}>{share(r.spread)}</span>}
                                            </td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>

                    <div style={{ marginTop: 12, fontSize: 11, color: 'var(--text-muted)', lineHeight: 1.6 }}>
                        Net margin = MSF − interchange − scheme fee − gateway fee. Net spread adds the DCC
                        acquirer share and rental income{data?.fxEnabled ? ' and ECOM FX margin' : ''}; the DCC
                        merchant share is shown for reference only and is never part of the spread. Acquiring
                        rows are settlement-currency volume from the channel pre-aggregate; DCC and rentals have
                        no transactions of their own, so their fee columns are blank.
                        {refreshedAt ? ` Refreshed ${refreshedAt}.` : ''}
                    </div>
                </>
            )}
            <style>{`@keyframes ps-spin { to { transform: rotate(360deg); } } .ps-spin { animation: ps-spin 1s linear infinite; }`}</style>
        </div>
    );
};

export default ProductSummary;
