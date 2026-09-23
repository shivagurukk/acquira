import React, { useState, useEffect, useCallback } from 'react';
import { RefreshCw, ShieldCheck, ShieldAlert, ShieldQuestion } from 'lucide-react';
import api from '../../api/axios';
import { showToast } from '../../contexts/ToastContext';

/* ════════════════════════════════════════════════════════════════════
   DIMP Dashboard — Mastercard Data Integrity Monitoring Program
   (Acquirer Clearing 1240 edits) assessment for the tenant's clearing
   files: the 24 edits with status, per-edit violation counts and rates,
   and a by-month trend.

   Data: GET /api/interchange/dimp/assessment
   ════════════════════════════════════════════════════════════════════ */

const STATUS = {
    FILE:    { label: 'File check',     color: '#1e7e34', bg: '#e6f4ea', Icon: ShieldCheck },
    PARTIAL: { label: 'Clearing-side',  color: '#2563eb', bg: '#e7efff', Icon: ShieldQuestion },
    AUTH:    { label: 'No auth · shown', color: '#b7791f', bg: '#fdf3e2', Icon: ShieldAlert },
};

const num = (v) => (v == null ? 0 : Number(v)).toLocaleString('en-US');

const StatusBadge = ({ status }) => {
    const s = STATUS[status] || STATUS.NOT_IMPLEMENTED;
    return (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '1px 8px',
            borderRadius: 10, fontSize: 11, fontWeight: 700, color: s.color, background: s.bg }}>
            <s.Icon size={12} /> {s.label}
        </span>
    );
};

const Tile = ({ label, value, sub }) => (
    <div style={{ flex: 1, minWidth: 150, border: '1px solid var(--border,#e5e7eb)', borderRadius: 10, padding: '14px 16px' }}>
        <div style={{ fontSize: 12, color: '#6b7280', textTransform: 'uppercase', letterSpacing: 0.4 }}>{label}</div>
        <div style={{ fontSize: 26, fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{value}</div>
        {sub && <div style={{ fontSize: 12, color: '#6b7280' }}>{sub}</div>}
    </div>
);

const DimpDashboard = () => {
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);

    const load = useCallback(async () => {
        setLoading(true);
        try {
            const { data } = await api.get('/interchange/dimp/assessment');
            setData(data);
        } catch (e) {
            showToast(e?.response?.data?.message || 'Could not load DIMP assessment', 'error');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => { load(); }, [load]);

    const s = data?.summary || {};
    const byEdit = data?.byEdit || [];
    const byMonth = data?.byMonth || [];
    const maxMonth = Math.max(1, ...byMonth.map((m) => num2(m.violations)));

    return (
        <div style={{ padding: '20px 24px', maxWidth: 1240, margin: '0 auto' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                    <ShieldCheck size={22} />
                    <h1 style={{ margin: 0, fontSize: 22 }}>DIMP Dashboard</h1>
                </div>
                <button onClick={load} disabled={loading} style={btnGhost}>
                    <RefreshCw size={15} style={{ marginRight: 6 }} />Refresh
                </button>
            </div>
            <p style={{ marginTop: 0, color: '#6b7280', fontSize: 13 }}>
                Mastercard Data Integrity Monitoring Program — Acquirer Clearing (1240) edits, assessed across this
                tenant's clearing files. MATCH edits that compare clearing to authorization show as “Needs auth”.
            </p>

            {loading ? (
                <div style={{ padding: 40, textAlign: 'center', color: '#6b7280' }}>Loading…</div>
            ) : (
                <>
                    {/* Summary tiles */}
                    <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', margin: '12px 0 22px' }}>
                        <Tile label="Files" value={num(s.files)} sub="Mastercard clearing" />
                        <Tile label="Presentments monitored" value={num(s.presentments)} />
                        <Tile label="DIMP violations" value={num(s.dimpViolations)} />
                        <Tile label="Edits fired" value={`${num(s.editsFired)}`} sub={`of ${num(s.editsTotal)} edits · ${num(s.editsFileChecked)} full file-checks`} />
                    </div>

                    {/* By edit */}
                    <h2 style={{ fontSize: 15, margin: '0 0 8px' }}>Assessment by edit</h2>
                    <div style={{ overflowX: 'auto', border: '1px solid var(--border,#e5e7eb)', borderRadius: 8, marginBottom: 26 }}>
                        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                            <thead>
                                <tr style={{ textAlign: 'left', background: 'var(--surface-2,#f8fafc)' }}>
                                    <th style={th}>Edit</th><th style={th}>Name</th><th style={th}>Title</th>
                                    <th style={th}>Checks (clearing field)</th><th style={th}>Billing</th><th style={th}>Status</th>
                                    <th style={thR}>Findings</th><th style={thR}>Rate %</th>
                                </tr>
                            </thead>
                            <tbody>
                                {byEdit.map((e) => (
                                    <tr key={e.editNumber} style={{ borderTop: '1px solid var(--border,#eef0f3)' }}>
                                        <td style={td}>{e.editNumber}</td>
                                        <td style={{ ...td, fontFamily: 'monospace' }}>{e.name}</td>
                                        <td style={td}>{e.title}</td>
                                        <td style={{ ...td, color: '#6b7280' }}>{e.field}</td>
                                        <td style={{ ...td, fontFamily: 'monospace' }}>{e.billingCode}</td>
                                        <td style={td}><StatusBadge status={e.status} /></td>
                                        <td style={{ ...tdR, fontWeight: num2(e.count) > 0 ? 700 : 400,
                                            color: num2(e.count) > 0 ? '#c0392b' : 'inherit' }}>{num(e.count)}</td>
                                        <td style={tdR}>{e.rate}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>

                    {/* By month */}
                    <h2 style={{ fontSize: 15, margin: '0 0 8px' }}>By month</h2>
                    {byMonth.length === 0 ? (
                        <div style={{ color: '#6b7280', fontSize: 13 }}>No clearing files processed yet.</div>
                    ) : (
                        <div style={{ border: '1px solid var(--border,#e5e7eb)', borderRadius: 8, padding: '12px 16px' }}>
                            {byMonth.map((m) => (
                                <div key={m.month} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '4px 0' }}>
                                    <div style={{ width: 74, fontSize: 12, color: '#6b7280' }}>{m.month}</div>
                                    <div style={{ flex: 1, background: 'var(--surface-2,#eef2f7)', borderRadius: 4, height: 16, position: 'relative' }}>
                                        <div style={{ width: `${(num2(m.violations) / maxMonth) * 100}%`, height: '100%',
                                            background: 'var(--accent,#4f46e5)', borderRadius: 4 }} />
                                    </div>
                                    <div style={{ width: 120, fontSize: 12, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                                        {num(m.violations)} viol · {num(m.files)} files
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                </>
            )}
        </div>
    );
};

function num2(v) { return v == null ? 0 : Number(v); }

const th = { padding: '8px 12px', fontWeight: 600, fontSize: 12, whiteSpace: 'nowrap' };
const thR = { ...th, textAlign: 'right' };
const td = { padding: '8px 12px', verticalAlign: 'top' };
const tdR = { ...td, textAlign: 'right', fontVariantNumeric: 'tabular-nums' };
const btnGhost = { display: 'inline-flex', alignItems: 'center', padding: '7px 14px', borderRadius: 7,
    fontSize: 13, fontWeight: 600, cursor: 'pointer', background: 'transparent',
    color: 'inherit', border: '1px solid var(--border,#d1d5db)' };

export default DimpDashboard;
