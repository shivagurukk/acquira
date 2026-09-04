import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { Receipt, RefreshCw, Download, AlertTriangle, Search } from 'lucide-react';
import { createFmt, formatNumber } from '../../utils/formatters';
import { useAuth } from '../../contexts/AuthContext';
import api from '../../api/axios';

/* ════════════════════════════════════════════════════════════════════
   Rentals — terminal / store / merchant rental charges from the
   DEDICATED rental feed (fact_rental). The visible levels are
   tenant-driven: CMM-format tenants get Store only, AMS-format tenants
   get Merchant / Store / Terminal — the backend's /overview response
   says which (`levels`), the page never hardcodes it.

   Each charge is a dated record (payment date), so the page is a
   date-ranged ledger, not a snapshot. Exceptions (REJECTED id combos,
   UNMATCHED ids the dims don't know yet, DUPLICATE re-uploads) come
   from the latest load's staging rows.                        ═══════ */

const num = (v) => (v == null ? 0 : Number(v));

const fmtDate = (d) => {
    const yr = d.getFullYear();
    const mo = String(d.getMonth() + 1).padStart(2, '0');
    const dy = String(d.getDate()).padStart(2, '0');
    return `${yr}-${mo}-${dy}`;
};

const PRESETS = [
    { key: 'MTD', label: 'This month' },
    { key: 'LM', label: 'Previous month' },
    { key: 'D90', label: 'Last 90 days' },
    { key: 'YTD', label: 'This year' },
];
const computeRange = (preset) => {
    const a = new Date();
    switch (preset) {
        case 'LM': return {
            from: fmtDate(new Date(a.getFullYear(), a.getMonth() - 1, 1)),
            to: fmtDate(new Date(a.getFullYear(), a.getMonth(), 0)),
        };
        case 'D90': { const d = new Date(a); d.setDate(d.getDate() - 89); return { from: fmtDate(d), to: fmtDate(a) }; }
        case 'YTD': return { from: fmtDate(new Date(a.getFullYear(), 0, 1)), to: fmtDate(a) };
        case 'MTD':
        default: return { from: fmtDate(new Date(a.getFullYear(), a.getMonth(), 1)), to: fmtDate(a) };
    }
};

const LEVEL_LABELS = { MERCHANT: 'Merchant (MID)', STORE: 'Store (SID)', TERMINAL: 'Terminal (TID)' };
const PAGE_SIZE = 50;

const Tile = ({ label, value, sub }) => (
    <div style={{
        padding: '15px 20px', minWidth: 170, borderRadius: 'var(--radius-lg)',
        border: '1px solid var(--border)', background: 'var(--surface)',
    }}>
        <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--text-secondary)' }}>
            {label}
        </div>
        <div style={{
            marginTop: 7, fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums',
            fontSize: 22, fontWeight: 600, letterSpacing: '-0.02em', color: 'var(--text)', whiteSpace: 'nowrap',
        }}>
            {value}
        </div>
        {sub && <div style={{ marginTop: 3, fontSize: 11, color: 'var(--text-secondary)' }}>{sub}</div>}
    </div>
);

const RentalOverview = () => {
    const { currencySymbol, currencyCode, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);

    const [preset, setPreset] = useState('MTD');
    const [range, setRange] = useState(computeRange('MTD'));
    const [overview, setOverview] = useState(null);
    const [overviewErr, setOverviewErr] = useState(null);

    const [level, setLevel] = useState(null);
    const [rows, setRows] = useState([]);
    const [total, setTotal] = useState(0);
    const [totalAmount, setTotalAmount] = useState(0);
    const [page, setPage] = useState(0);
    const [listLoading, setListLoading] = useState(false);
    const [search, setSearch] = useState('');
    const [searchDraft, setSearchDraft] = useState('');

    const [exceptions, setExceptions] = useState([]);
    const [showExceptions, setShowExceptions] = useState(false);

    const levels = overview?.levels || [];

    const loadOverview = useCallback(async () => {
        setOverviewErr(null);
        try {
            const res = await api.get('/business/rentals/overview', { params: range });
            setOverview(res.data);
            // Keep the active tab valid for this tenant (CMM only has STORE).
            setLevel((prev) => (prev && res.data.levels.includes(prev)) ? prev : res.data.levels[0]);
        } catch (e) {
            setOverviewErr(e?.response?.data?.message || 'Could not load rental overview');
        }
    }, [range]);

    useEffect(() => { loadOverview(); }, [loadOverview, tenantVersion]);

    useEffect(() => {
        if (!level) return;
        let cancelled = false;
        setListLoading(true);
        api.get('/business/rentals/list', { params: { level, ...range, search: search || undefined, page, size: PAGE_SIZE } })
            .then((res) => {
                if (cancelled) return;
                setRows(res.data.rows || []);
                setTotal(num(res.data.total));
                setTotalAmount(num(res.data.totalAmount));
            })
            .catch(() => { if (!cancelled) { setRows([]); setTotal(0); setTotalAmount(0); } })
            .finally(() => { if (!cancelled) setListLoading(false); });
        return () => { cancelled = true; };
    }, [level, range, search, page, tenantVersion]);

    useEffect(() => {
        api.get('/business/rentals/exceptions')
            .then((res) => setExceptions(res.data || []))
            .catch(() => setExceptions([]));
    }, [tenantVersion, overview]);

    const pickPreset = (key) => { setPreset(key); setRange(computeRange(key)); setPage(0); };

    const exportCsv = async () => {
        try {
            const res = await api.get('/business/rentals/export', {
                params: { level, ...range }, responseType: 'blob',
            });
            const url = URL.createObjectURL(res.data);
            const a = document.createElement('a');
            a.href = url;
            a.download = `rentals_${level.toLowerCase()}_${range.from}_${range.to}.csv`;
            a.click();
            URL.revokeObjectURL(url);
        } catch { /* surface nothing — button stays usable */ }
    };

    const perLevelMap = useMemo(() => {
        const m = {};
        (overview?.perLevel || []).forEach((r) => { m[r.level] = r; });
        return m;
    }, [overview]);

    const grandTotal = (overview?.perLevel || []).reduce((s, r) => s + num(r.total_amount), 0);
    const exCounts = overview?.exceptions || {};
    const exTotal = num(exCounts.rejected) + num(exCounts.unmatched);

    const lastPage = Math.max(Math.ceil(total / PAGE_SIZE) - 1, 0);

    const th = { textAlign: 'left', padding: '9px 14px', fontSize: 11, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', color: 'var(--text-secondary)', borderBottom: '1px solid var(--border)', whiteSpace: 'nowrap' };
    const td = { padding: '9px 14px', fontSize: 13, color: 'var(--text)', borderBottom: '1px solid var(--border)', whiteSpace: 'nowrap' };
    const tdMono = { ...td, fontFamily: 'var(--font-mono)', fontVariantNumeric: 'tabular-nums' };

    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
            {/* Masthead */}
            <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
                <div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <Receipt size={20} style={{ color: 'var(--accent)' }} />
                        <h1 style={{ margin: 0, fontSize: 20, fontWeight: 700, color: 'var(--text)' }}>Rentals</h1>
                    </div>
                    <div style={{ marginTop: 4, fontSize: 12.5, color: 'var(--text-secondary)' }}>
                        Rental charges from the dedicated rental feed — {levels.length > 1
                            ? 'merchant, store and terminal level'
                            : 'store level'} · {currencyCode}
                        {overview?.lastLoad?.last_load_time && (
                            <> · last file loaded {String(overview.lastLoad.last_load_time).slice(0, 16).replace('T', ' ')}</>
                        )}
                    </div>
                </div>
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                    {PRESETS.map((p) => (
                        <button key={p.key} onClick={() => pickPreset(p.key)} style={{
                            padding: '6px 12px', fontSize: 12, fontWeight: 600, cursor: 'pointer',
                            borderRadius: 'var(--radius-sm)',
                            border: `1px solid ${preset === p.key ? 'var(--accent)' : 'var(--border)'}`,
                            background: preset === p.key ? 'var(--accent-soft, rgba(94,130,210,0.12))' : 'var(--surface)',
                            color: preset === p.key ? 'var(--accent)' : 'var(--text-secondary)',
                        }}>
                            {p.label}
                        </button>
                    ))}
                    <button onClick={loadOverview} title="Refresh" style={{
                        padding: '6px 10px', cursor: 'pointer', borderRadius: 'var(--radius-sm)',
                        border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-secondary)',
                    }}>
                        <RefreshCw size={14} />
                    </button>
                </div>
            </div>

            {overviewErr && (
                <div style={{
                    padding: '14px 18px', borderRadius: 'var(--radius-lg)', display: 'flex', alignItems: 'center', gap: 10,
                    border: '1px solid var(--danger-border, #fecaca)', background: 'var(--danger-bg, #fef2f2)',
                    fontSize: 13, fontWeight: 600, color: 'var(--danger-text, #991b1b)',
                }}>
                    <AlertTriangle size={16} /> {overviewErr}
                </div>
            )}

            {/* Tiles */}
            <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                <Tile label="Total rentals in range" value={fmt.currency(grandTotal)} sub={`${range.from} → ${range.to}`} />
                {levels.map((l) => (
                    <Tile key={l}
                        label={LEVEL_LABELS[l] || l}
                        value={fmt.currency(num(perLevelMap[l]?.total_amount))}
                        sub={`${formatNumber(num(perLevelMap[l]?.charge_count))} charges · ${formatNumber(num(perLevelMap[l]?.entity_count))} entities`} />
                ))}
                {exTotal > 0 && (
                    <button onClick={() => setShowExceptions((v) => !v)} style={{
                        padding: '15px 20px', minWidth: 170, cursor: 'pointer', textAlign: 'left',
                        borderRadius: 'var(--radius-lg)', border: '1px solid var(--danger-border, #fecaca)',
                        background: 'var(--danger-bg, #fef2f2)',
                    }}>
                        <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--danger-text, #991b1b)', display: 'flex', alignItems: 'center', gap: 6 }}>
                            <AlertTriangle size={12} /> Exceptions
                        </div>
                        <div style={{ marginTop: 7, fontFamily: 'var(--font-mono)', fontSize: 22, fontWeight: 600, color: 'var(--danger-text, #991b1b)' }}>
                            {formatNumber(exTotal)}
                        </div>
                        <div style={{ marginTop: 3, fontSize: 11, color: 'var(--danger-text, #991b1b)' }}>
                            {num(exCounts.rejected)} rejected · {num(exCounts.unmatched)} unmatched — click to {showExceptions ? 'hide' : 'view'}
                        </div>
                    </button>
                )}
            </div>

            {/* Exceptions panel */}
            {showExceptions && exceptions.length > 0 && (
                <div style={{ borderRadius: 'var(--radius-lg)', border: '1px solid var(--danger-border, #fecaca)', overflow: 'auto' }}>
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                        <thead><tr>
                            <th style={th}>Status</th><th style={th}>Reason</th><th style={th}>MID</th>
                            <th style={th}>SID</th><th style={th}>TID</th>
                            <th style={{ ...th, textAlign: 'right' }}>Amount</th><th style={th}>Payment date</th>
                        </tr></thead>
                        <tbody>
                            {exceptions.map((r) => (
                                <tr key={r.raw_id}>
                                    <td style={{ ...td, fontWeight: 700, color: 'var(--danger-text, #991b1b)' }}>{r.status}</td>
                                    <td style={{ ...td, whiteSpace: 'normal' }}>{r.error_message}</td>
                                    <td style={tdMono}>{r.mid || '—'}</td>
                                    <td style={tdMono}>{r.sid || '—'}</td>
                                    <td style={tdMono}>{r.tid || '—'}</td>
                                    <td style={{ ...tdMono, textAlign: 'right' }}>{r.rental_amount != null ? fmt.money(num(r.rental_amount)) : '—'}</td>
                                    <td style={tdMono}>{r.payment_date || '—'}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}

            {/* Level tabs + search + export */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10 }}>
                <div style={{ display: 'flex', gap: 4 }}>
                    {levels.map((l) => (
                        <button key={l} onClick={() => { setLevel(l); setPage(0); }} style={{
                            padding: '7px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer',
                            borderRadius: 'var(--radius-sm)', border: 'none',
                            background: level === l ? 'var(--accent)' : 'transparent',
                            color: level === l ? '#fff' : 'var(--text-secondary)',
                        }}>
                            {LEVEL_LABELS[l] || l}
                        </button>
                    ))}
                </div>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <form onSubmit={(e) => { e.preventDefault(); setSearch(searchDraft.trim()); setPage(0); }}
                        style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '5px 10px', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)', background: 'var(--surface)' }}>
                        <Search size={13} style={{ color: 'var(--text-secondary)' }} />
                        <input value={searchDraft} onChange={(e) => setSearchDraft(e.target.value)}
                            placeholder="MID / SID / TID / name…"
                            style={{ border: 'none', outline: 'none', background: 'transparent', fontSize: 12.5, color: 'var(--text)', width: 180 }} />
                    </form>
                    <button onClick={exportCsv} disabled={!level} style={{
                        display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer',
                        padding: '7px 13px', fontSize: 12, fontWeight: 700, borderRadius: 'var(--radius-sm)',
                        border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-secondary)',
                    }}>
                        <Download size={13} /> CSV
                    </button>
                </div>
            </div>

            {/* Charges table */}
            <div style={{ borderRadius: 'var(--radius-lg)', border: '1px solid var(--border)', background: 'var(--surface)', overflow: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead><tr>
                        {level === 'MERCHANT' && <th style={th}>MID</th>}
                        {level !== 'MERCHANT' && <th style={th}>SID</th>}
                        {level === 'TERMINAL' && <th style={th}>TID</th>}
                        <th style={th}>Merchant</th>
                        {level !== 'MERCHANT' && <th style={th}>Store</th>}
                        <th style={{ ...th, textAlign: 'right' }}>Rental amount</th>
                        <th style={th}>Payment date</th>
                    </tr></thead>
                    <tbody>
                        {listLoading && (
                            <tr><td style={{ ...td, textAlign: 'center', color: 'var(--text-secondary)' }} colSpan={7}>Loading…</td></tr>
                        )}
                        {!listLoading && rows.length === 0 && (
                            <tr><td style={{ ...td, textAlign: 'center', color: 'var(--text-secondary)' }} colSpan={7}>
                                No rental charges in this range.
                            </td></tr>
                        )}
                        {!listLoading && rows.map((r) => (
                            <tr key={r.rental_id}>
                                {level === 'MERCHANT' && <td style={tdMono}>{r.mid}</td>}
                                {level !== 'MERCHANT' && <td style={tdMono}>{r.sid || '—'}</td>}
                                {level === 'TERMINAL' && <td style={tdMono}>{r.tid}</td>}
                                <td style={td}>{r.merchant_name || '—'}</td>
                                {level !== 'MERCHANT' && <td style={td}>{r.store_name || '—'}</td>}
                                <td style={{ ...tdMono, textAlign: 'right', fontWeight: 600 }}>{fmt.money(num(r.rental_amount))}</td>
                                <td style={tdMono}>{r.payment_date}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
                {/* Footer: totals + pager */}
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '9px 14px', fontSize: 12, color: 'var(--text-secondary)' }}>
                    <span>
                        {formatNumber(total)} charges · total <span style={{ fontFamily: 'var(--font-mono)', fontWeight: 700, color: 'var(--text)' }}>{fmt.money(totalAmount)}</span>
                    </span>
                    <span style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                        <button disabled={page <= 0} onClick={() => setPage((p) => p - 1)}
                            style={{ cursor: page > 0 ? 'pointer' : 'default', border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-secondary)', borderRadius: 'var(--radius-sm)', padding: '3px 10px', fontSize: 12 }}>
                            Prev
                        </button>
                        page {page + 1} / {lastPage + 1}
                        <button disabled={page >= lastPage} onClick={() => setPage((p) => p + 1)}
                            style={{ cursor: page < lastPage ? 'pointer' : 'default', border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text-secondary)', borderRadius: 'var(--radius-sm)', padding: '3px 10px', fontSize: 12 }}>
                            Next
                        </button>
                    </span>
                </div>
            </div>
        </div>
    );
};

export default RentalOverview;
