import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
    ShieldCheck, AlertTriangle, XCircle, CheckCircle2, Clock, RefreshCw,
    Loader2, ChevronRight, Database, Layers, TrendingDown,
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import PageHeader from '../../components/PageHeader';

/* ════════════════════════════════════════════════════════════════════
   INGEST TRUST BOARD

   Four panels, each answering one question that used to require reading
   a log or waiting for a user to complain:

     1. Freshness   — did the data arrive?
     2. Coverage    — which days are missing, stale, or reloaded?
     3. Run detail  — did ALL of it arrive? (four-tier funnel + stage waterfall)
     4. Durations   — did it arrive on time?

   MOTION: deliberately none. This screen is read during an incident, and
   the browser preview pane serves no animation frames when hidden, so a
   motion-driven page renders as a frozen one. Everything here is static
   layout with CSS transitions only.
   ════════════════════════════════════════════════════════════════════ */

/* Freshness states, in the order they should draw attention. */
const STATE_META = {
    FAILING:       { label: 'Failing',       color: 'var(--negative)',  bg: 'var(--danger-bg)',   Icon: XCircle },
    NO_DATA:       { label: 'No data',       color: 'var(--negative)',  bg: 'var(--danger-bg)',   Icon: AlertTriangle },
    STALE:         { label: 'Stale',         color: 'var(--attention)', bg: 'var(--warning-bg)',  Icon: Clock },
    OK_WITH_GAPS:  { label: 'Gaps',          color: 'var(--attention)', bg: 'var(--warning-bg)',  Icon: AlertTriangle },
    RUNNING:       { label: 'Running',       color: 'var(--projected)', bg: 'var(--info-bg)',     Icon: Loader2 },
    OK:            { label: 'Current',       color: 'var(--positive, #0FA070)', bg: 'var(--success-bg)', Icon: CheckCircle2 },
    NOT_MONITORED: { label: 'Not monitored', color: 'var(--text-muted)', bg: 'var(--bg-subtle)',  Icon: ShieldCheck },
};

const STATE_ORDER = ['FAILING', 'NO_DATA', 'STALE', 'OK_WITH_GAPS', 'RUNNING', 'OK', 'NOT_MONITORED'];

const card = {
    background: 'var(--bg-card)',
    border: '1px solid var(--border)',
    borderRadius: 8,
    padding: 16,
};

const sectionTitle = {
    fontSize: '0.78rem',
    fontWeight: 700,
    letterSpacing: '0.04em',
    textTransform: 'uppercase',
    color: 'var(--text-secondary)',
    marginBottom: 12,
};

const th = {
    padding: '10px 12px',
    textAlign: 'left',
    fontSize: '0.7rem',
    fontWeight: 700,
    letterSpacing: '0.02em',
    color: 'var(--text-secondary)',
    borderBottom: '1px solid var(--border)',
    whiteSpace: 'nowrap',
};

const td = {
    padding: '10px 12px',
    fontSize: '0.8rem',
    borderBottom: '1px solid var(--border-light)',
    color: 'var(--text-primary)',
};

const fmtInt = (n) => (n === null || n === undefined ? '—' : Number(n).toLocaleString());

export const fmtDuration = (ms) => {
    if (ms === null || ms === undefined) return '—';
    const s = Math.round(ms / 1000);
    if (s < 60) return `${s}s`;
    const m = Math.floor(s / 60);
    if (m < 60) return `${m}m ${s % 60}s`;
    return `${Math.floor(m / 60)}h ${m % 60}m`;
};

const fmtAgo = (iso) => {
    if (!iso) return 'never';
    const then = new Date(iso).getTime();
    if (Number.isNaN(then)) return '—';
    const mins = Math.round((Date.now() - then) / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return `${mins}m ago`;
    const hrs = Math.floor(mins / 60);
    if (hrs < 48) return `${hrs}h ago`;
    return `${Math.floor(hrs / 24)}d ago`;
};

/* Parse 'YYYY-MM-DD' component-wise. new Date(iso) is UTC midnight and slides a
   day in some zones — the same trap weekRules.js documents. */
export const parseDay = (iso) => {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso || '');
    return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null;
};

const DOW_NAMES = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'];

const IngestTrust = () => {
    const { tenantVersion } = useAuth();

    const [overview, setOverview] = useState([]);
    const [runs, setRuns] = useState([]);
    const [coverage, setCoverage] = useState(null);
    const [trend, setTrend] = useState(null);
    const [selectedRun, setSelectedRun] = useState(null);
    const [selectedTenant, setSelectedTenant] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            const params = selectedTenant ? `?tenantId=${selectedTenant}` : '';
            const [ov, rn, cv, tr] = await Promise.all([
                api.get('/ops/ingest/overview'),
                api.get(`/ops/ingest/runs${params}${params ? '&' : '?'}size=25`),
                api.get(`/ops/ingest/coverage${params}`),
                api.get(`/ops/ingest/duration-trend${params}`),
            ]);
            setOverview(ov.data || []);
            setRuns(rn.data?.items || []);
            setCoverage(cv.data || null);
            setTrend(tr.data || null);
        } catch (e) {
            setError(e?.response?.data?.message || e.message || 'Failed to load ingest trust data');
        } finally {
            setLoading(false);
        }
    }, [selectedTenant]);

    useEffect(() => { load(); }, [load, tenantVersion]);

    const openRun = async (id) => {
        try {
            const res = await api.get(`/ops/ingest/runs/${id}`);
            setSelectedRun(res.data);
        } catch (e) {
            setError('Could not open run ' + id);
        }
    };

    const acknowledge = async (id) => {
        try {
            await api.post(`/ops/ingest/runs/${id}/acknowledge`, { note: 'Reviewed from Ingest Trust' });
            await load();
            if (selectedRun?.id === id) openRun(id);
        } catch (e) {
            setError('Could not acknowledge run ' + id);
        }
    };

    /* Sort tiles so whatever is wrong is at the top left. */
    const sortedOverview = useMemo(() => {
        return [...overview].sort(
            (a, b) => STATE_ORDER.indexOf(a.state) - STATE_ORDER.indexOf(b.state)
        );
    }, [overview]);

    return (
        <>
            <PageHeader
                title="Ingest Trust"
                subtitle="Freshness, coverage and reconciliation for every ingestion path"
                actions={
                    <button
                        onClick={load}
                        disabled={loading}
                        style={{
                            display: 'flex', alignItems: 'center', gap: 6,
                            padding: '7px 14px', fontSize: '0.78rem', fontWeight: 600,
                            background: 'var(--bg-subtle)', color: 'var(--text-primary)',
                            border: '1px solid var(--border)', borderRadius: 6,
                            cursor: loading ? 'default' : 'pointer',
                        }}
                    >
                        <RefreshCw size={14} /> Refresh
                    </button>
                }
            />

            <div style={{ padding: 'var(--space-page, 20px)', display: 'grid', gap: 20 }}>
                {error && (
                    <div style={{ ...card, borderColor: 'var(--negative)', background: 'var(--danger-bg)' }}>
                        <strong style={{ color: 'var(--negative)' }}>{error}</strong>
                    </div>
                )}

                <FreshnessStrip
                    tiles={sortedOverview}
                    loading={loading}
                    selected={selectedTenant}
                    onSelect={(id) => setSelectedTenant((cur) => (cur === id ? null : id))}
                />

                <CoverageCalendar coverage={coverage} onPickDay={() => { /* runs list is already filtered */ }} />

                <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1.4fr) minmax(0, 1fr)', gap: 20 }}>
                    <RunsTable runs={runs} onOpen={openRun} onAcknowledge={acknowledge} />
                    <DurationTrend trend={trend} />
                </div>

                {selectedRun && (
                    <RunDetail run={selectedRun} onClose={() => setSelectedRun(null)} />
                )}
            </div>
        </>
    );
};

/* ── 1. Freshness ──────────────────────────────────────────────────── */

const FreshnessStrip = ({ tiles, loading, selected, onSelect }) => (
    <section>
        <div style={sectionTitle}>Tenant freshness</div>
        {loading && tiles.length === 0 ? (
            <div style={{ ...card, color: 'var(--text-muted)' }}>Loading…</div>
        ) : tiles.length === 0 ? (
            <div style={{ ...card, color: 'var(--text-muted)' }}>No tenants visible.</div>
        ) : (
            <div style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fill, minmax(230px, 1fr))',
                gap: 12,
            }}>
                {tiles.map((t) => {
                    const meta = STATE_META[t.state] || STATE_META.NOT_MONITORED;
                    const Icon = meta.Icon;
                    const isSelected = selected === t.tenant_id;
                    return (
                        <button
                            key={t.tenant_id}
                            onClick={() => onSelect(t.tenant_id)}
                            style={{
                                ...card,
                                textAlign: 'left',
                                cursor: 'pointer',
                                background: meta.bg,
                                borderColor: isSelected ? meta.color : 'var(--border)',
                                borderWidth: isSelected ? 2 : 1,
                                transition: 'border-color 120ms ease',
                            }}
                        >
                            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
                                <Icon size={15} style={{ color: meta.color, flexShrink: 0 }} />
                                <span style={{ fontWeight: 700, fontSize: '0.85rem' }}>{t.institution_id}</span>
                                <span style={{
                                    marginLeft: 'auto', fontSize: '0.68rem', fontWeight: 700,
                                    color: meta.color, letterSpacing: '0.03em', textTransform: 'uppercase',
                                }}>{meta.label}</span>
                            </div>
                            <div style={{ fontSize: '0.74rem', color: 'var(--text-secondary)', lineHeight: 1.7 }}>
                                <div>
                                    Latest data:{' '}
                                    <span className="num" style={{ color: 'var(--text-primary)' }}>
                                        {t.latest_data_date || '—'}
                                    </span>
                                </div>
                                <div>Last good load: {fmtAgo(t.last_good_at)}</div>
                                {(t.failures_7d > 0 || t.gaps_7d > 0) && (
                                    <div style={{ color: meta.color, fontWeight: 600 }}>
                                        {t.failures_7d > 0 && `${t.failures_7d} failed`}
                                        {t.failures_7d > 0 && t.gaps_7d > 0 && ' · '}
                                        {t.gaps_7d > 0 && `${t.gaps_7d} unreconciled`}
                                        {' (7d)'}
                                    </div>
                                )}
                            </div>
                        </button>
                    );
                })}
            </div>
        )}
    </section>
);

/* ── 2. Coverage calendar ──────────────────────────────────────────── */

/* Cell states. A weekend for THIS tenant is drawn as inert rather than
   missing — a Bahraini Friday is not a gap, and colouring it red is how a
   board teaches people to ignore it. */
export const cellStyle = (day, isWeekend) => {
    const hasFact = day && day.rows_fact !== null && day.rows_fact !== undefined;
    const anyRows = day && ((hasFact && day.rows_fact > 0) || day.rows_summary > 0);

    if (isWeekend && !anyRows) {
        return { background: 'var(--bg-subtle)', border: '1px dashed var(--border-light)' };
    }
    if (!anyRows) {
        return { background: 'var(--danger-bg)', border: '1px solid var(--negative)' };
    }
    /* PRE-LEDGER: backfilled by the migration from sum_daily_full, so we know a
       day held data but not its fact-tier count or fee coverage. Drawn as a
       distinct muted state — claiming green would assert a reconciliation that
       never happened, and red would paint years of good history as missing. */
    if (!hasFact) {
        return { background: 'var(--bg-muted)', border: '1px solid var(--border-light)' };
    }
    if (day.load_count > 1) {
        return { background: 'var(--warning-bg)', border: '1px solid var(--attention)' };
    }
    const feeRatio = (day.fee_priced_rows || 0) / day.rows_fact;
    if (feeRatio < 0.95) {
        return { background: 'var(--warning-bg)', border: '1px solid var(--attention)' };
    }
    return { background: 'var(--success-bg)', border: '1px solid var(--border-light)' };
};

const CoverageCalendar = ({ coverage }) => {
    if (!coverage) return null;

    const byTenant = {};
    (coverage.days || []).forEach((d) => {
        (byTenant[d.tenant_id] = byTenant[d.tenant_id] || {})[String(d.txn_date).slice(0, 10)] = d;
    });

    const start = parseDay(coverage.from);
    const end = parseDay(coverage.to);
    if (!start || !end) return null;

    const dates = [];
    for (let d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) {
        dates.push(new Date(d));
    }

    const tenantIds = Object.keys(byTenant);
    if (tenantIds.length === 0) {
        return (
            <section>
                <div style={sectionTitle}>Day coverage</div>
                <div style={{ ...card, color: 'var(--text-muted)' }}>No coverage recorded yet.</div>
            </section>
        );
    }

    return (
        <section>
            <div style={sectionTitle}>Day coverage · {coverage.from} → {coverage.to}</div>
            <div style={{ ...card, overflowX: 'auto' }}>
                {tenantIds.map((tid) => {
                    const weekend = (coverage.weekendDays && coverage.weekendDays[tid]) || [];
                    return (
                        <div key={tid} style={{ marginBottom: 16 }}>
                            <div style={{
                                fontSize: '0.72rem', fontWeight: 700, marginBottom: 6,
                                color: 'var(--text-secondary)',
                            }}>
                                Tenant {tid}
                                <span style={{ fontWeight: 500, color: 'var(--text-muted)', marginLeft: 8 }}>
                                    weekend: {weekend.map((w) => w.slice(0, 3)).join(' ') || '—'}
                                </span>
                            </div>
                            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 3, minWidth: 640 }}>
                                {dates.map((d) => {
                                    const iso = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
                                    const day = byTenant[tid][iso];
                                    const isWeekend = weekend.includes(DOW_NAMES[d.getDay()]);
                                    const title = [
                                        iso,
                                        isWeekend ? '(weekend)' : '',
                                        day
                                            ? (day.rows_fact === null || day.rows_fact === undefined
                                                ? `${fmtInt(day.rows_summary)} summarised rows (pre-ledger)`
                                                : `${fmtInt(day.rows_fact)} fact rows`)
                                            : 'no data',
                                        day && day.load_count > 1 ? `loaded ${day.load_count}×` : '',
                                        day && day.rows_fact
                                            ? `${Math.round(((day.fee_priced_rows || 0) / day.rows_fact) * 100)}% fee-priced`
                                            : '',
                                    ].filter(Boolean).join(' · ');
                                    return (
                                        <div
                                            key={iso}
                                            title={title}
                                            style={{
                                                width: 14, height: 14, borderRadius: 2,
                                                ...cellStyle(day, isWeekend),
                                            }}
                                        />
                                    );
                                })}
                            </div>
                        </div>
                    );
                })}
                <Legend />
            </div>
        </section>
    );
};

const Legend = () => (
    <div style={{
        display: 'flex', gap: 16, flexWrap: 'wrap', marginTop: 8,
        fontSize: '0.7rem', color: 'var(--text-muted)',
    }}>
        {[
            ['Loaded', 'var(--success-bg)', 'var(--border-light)'],
            ['Reloaded / low fee coverage', 'var(--warning-bg)', 'var(--attention)'],
            ['Missing', 'var(--danger-bg)', 'var(--negative)'],
            ['Pre-ledger (summary only)', 'var(--bg-muted)', 'var(--border-light)'],
            ['Weekend', 'var(--bg-subtle)', 'var(--border-light)'],
        ].map(([label, bg, bc]) => (
            <span key={label} style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                <span style={{ width: 11, height: 11, borderRadius: 2, background: bg, border: `1px solid ${bc}` }} />
                {label}
            </span>
        ))}
    </div>
);

/* ── 3. Runs ───────────────────────────────────────────────────────── */

const reconChip = (status) => {
    const map = {
        OK:      ['var(--positive, #0FA070)', 'var(--success-bg)'],
        GAP:     ['var(--negative)', 'var(--danger-bg)'],
        UNKNOWN: ['var(--text-muted)', 'var(--bg-subtle)'],
    };
    const [color, bg] = map[status] || map.UNKNOWN;
    return (
        <span style={{
            padding: '2px 8px', borderRadius: 10, fontSize: '0.68rem', fontWeight: 700,
            color, background: bg, letterSpacing: '0.02em',
        }}>{status || 'UNKNOWN'}</span>
    );
};

const RunsTable = ({ runs, onOpen, onAcknowledge }) => (
    <section>
        <div style={sectionTitle}>Recent runs</div>
        <div style={{ ...card, padding: 0, overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 720 }}>
                <thead>
                    <tr>
                        <th style={th}>Started</th>
                        <th style={th}>Tenant</th>
                        <th style={th}>Source</th>
                        <th style={th}>File</th>
                        <th style={th}>Status</th>
                        <th style={th}>Recon</th>
                        <th style={th}>Rows</th>
                        <th style={th}>Took</th>
                        <th style={th} />
                    </tr>
                </thead>
                <tbody>
                    {runs.length === 0 && (
                        <tr><td style={{ ...td, color: 'var(--text-muted)' }} colSpan={9}>No runs recorded yet.</td></tr>
                    )}
                    {runs.map((r) => (
                        <tr key={r.id}>
                            <td style={{ ...td, whiteSpace: 'nowrap' }} className="num">
                                {String(r.started_at || '').replace('T', ' ').slice(0, 16)}
                            </td>
                            <td style={td}>{r.institution_id || r.tenant_id}</td>
                            <td style={{ ...td, fontSize: '0.72rem', color: 'var(--text-secondary)' }}>{r.source}</td>
                            <td style={{ ...td, maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                                title={r.file_name || ''}>
                                {r.file_name || '—'}
                            </td>
                            <td style={{
                                ...td, fontWeight: 700, fontSize: '0.72rem',
                                color: r.status === 'FAILED' ? 'var(--negative)'
                                    : r.status === 'RUNNING' ? 'var(--projected)'
                                    : 'var(--text-secondary)',
                            }}>{r.status}</td>
                            <td style={td}>{reconChip(r.recon_status)}</td>
                            <td style={td} className="num">{fmtInt(r.rows_facted)}</td>
                            <td style={td} className="num">{fmtDuration(r.duration_ms)}</td>
                            <td style={{ ...td, whiteSpace: 'nowrap' }}>
                                <button onClick={() => onOpen(r.id)} style={linkBtn}>
                                    Detail <ChevronRight size={12} />
                                </button>
                                {(r.status === 'FAILED' || r.recon_status === 'GAP') && !r.acknowledged_at && (
                                    <button onClick={() => onAcknowledge(r.id)} style={{ ...linkBtn, marginLeft: 8 }}>
                                        Ack
                                    </button>
                                )}
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    </section>
);

const linkBtn = {
    display: 'inline-flex', alignItems: 'center', gap: 3,
    background: 'none', border: 'none', padding: 0, cursor: 'pointer',
    color: 'var(--primary)', fontSize: '0.74rem', fontWeight: 600,
};

/* ── 4. Duration trend ─────────────────────────────────────────────── */

const DurationTrend = ({ trend }) => {
    if (!trend) return null;
    const stages = trend.stages || [];
    const max = Math.max(1, ...stages.map((s) => Number(s.p95_ms) || 0));
    const slaMs = trend.slaMinutes ? trend.slaMinutes * 60000 : null;

    return (
        <section>
            <div style={sectionTitle}>Stage durations · p50 / p95 over {trend.windowDays}d</div>
            <div style={card}>
                {stages.length === 0 ? (
                    <div style={{ color: 'var(--text-muted)', fontSize: '0.8rem' }}>
                        No completed stages in this window yet.
                    </div>
                ) : stages.map((s) => {
                    const p95 = Number(s.p95_ms) || 0;
                    const p50 = Number(s.p50_ms) || 0;
                    const overSla = slaMs && p95 > slaMs;
                    return (
                        <div key={s.stage_name} style={{ marginBottom: 10 }}>
                            <div style={{
                                display: 'flex', justifyContent: 'space-between',
                                fontSize: '0.72rem', marginBottom: 3,
                            }}>
                                <span style={{ color: 'var(--text-secondary)' }}>{s.stage_name}</span>
                                <span className="num" style={{ color: overSla ? 'var(--negative)' : 'var(--text-muted)' }}>
                                    {fmtDuration(p50)} / {fmtDuration(p95)}
                                </span>
                            </div>
                            <div style={{ position: 'relative', height: 8, background: 'var(--bg-subtle)', borderRadius: 4 }}>
                                <div style={{
                                    position: 'absolute', inset: 0, width: `${(p95 / max) * 100}%`,
                                    background: overSla ? 'var(--negative)' : 'var(--cat-1)',
                                    borderRadius: 4, opacity: 0.35,
                                }} />
                                <div style={{
                                    position: 'absolute', inset: 0, width: `${(p50 / max) * 100}%`,
                                    background: overSla ? 'var(--negative)' : 'var(--cat-1)',
                                    borderRadius: 4,
                                }} />
                            </div>
                        </div>
                    );
                })}
            </div>
        </section>
    );
};

/* ── Run detail: funnel + waterfall ────────────────────────────────── */

const RunDetail = ({ run, onClose }) => {
    const funnel = run.funnel || [];
    const top = Math.max(1, ...funnel.map((f) => Number(f.value) || 0));
    const stages = run.stages || [];
    const longest = Math.max(1, ...stages.map((s) => Number(s.duration_ms) || 0));

    return (
        <section>
            <div style={{ ...sectionTitle, display: 'flex', alignItems: 'center', gap: 10 }}>
                Run #{run.id} · {run.institution_id || run.tenant_id} · {run.source}
                <button onClick={onClose} style={{ ...linkBtn, marginLeft: 'auto' }}>Close</button>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1.2fr)', gap: 20 }}>
                {/* Funnel — where rows were lost */}
                <div style={card}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 12 }}>
                        <Layers size={14} style={{ color: 'var(--text-secondary)' }} />
                        <strong style={{ fontSize: '0.8rem' }}>Row funnel</strong>
                    </div>
                    {funnel.map((f) => (
                        <div key={f.key} style={{ marginBottom: 10 }}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.74rem', marginBottom: 3 }}>
                                <span style={{ color: 'var(--text-secondary)' }}>{f.label}</span>
                                <span className="num">
                                    {fmtInt(f.value)}
                                    {f.dropFromPrevious > 0 && (
                                        <span style={{ color: 'var(--negative)', marginLeft: 8, fontWeight: 700 }}>
                                            −{fmtInt(f.dropFromPrevious)}
                                        </span>
                                    )}
                                </span>
                            </div>
                            <div style={{ height: 10, background: 'var(--bg-subtle)', borderRadius: 4 }}>
                                <div style={{
                                    height: '100%',
                                    width: `${((Number(f.value) || 0) / top) * 100}%`,
                                    background: f.dropFromPrevious > 0 ? 'var(--attention)' : 'var(--cat-1)',
                                    borderRadius: 4,
                                }} />
                            </div>
                        </div>
                    ))}

                    <dl style={{ marginTop: 14, fontSize: '0.74rem', display: 'grid', gap: 4 }}>
                        <Row label="Load mode" value={run.load_mode} />
                        <Row label="Fee-priced" value={run.fee_priced_pct != null ? `${run.fee_priced_pct}%` : '—'} />
                        <Row label="Rows deleted" value={fmtInt(run.fact_rows_deleted)} />
                        <Row label="Unresolved merchants" value={fmtInt(run.unresolved_merchants)} />
                        <Row label="Dates" value={`${run.min_txn_date || '—'} → ${run.max_txn_date || '—'} (${run.distinct_days ?? '—'})`} />
                        <Row label="Triggered by" value={run.triggered_by || '—'} />
                    </dl>

                    {run.recon_detail && (
                        <div style={{
                            marginTop: 12, padding: 10, borderRadius: 6,
                            background: 'var(--danger-bg)', border: '1px solid var(--negative)',
                            fontSize: '0.74rem', whiteSpace: 'pre-wrap', lineHeight: 1.6,
                        }}>
                            <div style={{ display: 'flex', gap: 6, alignItems: 'center', marginBottom: 6, fontWeight: 700 }}>
                                <TrendingDown size={13} /> Reconciliation gaps
                            </div>
                            {run.recon_detail}
                        </div>
                    )}
                    {run.error_message && (
                        <div style={{
                            marginTop: 12, padding: 10, borderRadius: 6,
                            background: 'var(--danger-bg)', border: '1px solid var(--negative)',
                            fontSize: '0.74rem', whiteSpace: 'pre-wrap',
                        }}>
                            {run.error_message}
                        </div>
                    )}
                </div>

                {/* Waterfall — where time went */}
                <div style={card}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 12 }}>
                        <Database size={14} style={{ color: 'var(--text-secondary)' }} />
                        <strong style={{ fontSize: '0.8rem' }}>Stage waterfall</strong>
                    </div>
                    {stages.length === 0 ? (
                        <div style={{ color: 'var(--text-muted)', fontSize: '0.78rem' }}>
                            No stages recorded for this run.
                        </div>
                    ) : stages.map((s) => (
                        <div key={`${s.seq}-${s.stage_name}`} style={{ marginBottom: 8 }}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.72rem', marginBottom: 3 }}>
                                <span style={{ color: 'var(--text-secondary)' }}>
                                    {s.seq}. {s.stage_name}
                                    {s.note && <span style={{ color: 'var(--text-muted)' }}> · {s.note}</span>}
                                </span>
                                <span className="num" style={{ color: 'var(--text-muted)' }}>
                                    {fmtDuration(s.duration_ms)}
                                </span>
                            </div>
                            <div style={{ height: 8, background: 'var(--bg-subtle)', borderRadius: 4 }}>
                                <div style={{
                                    height: '100%',
                                    width: `${((Number(s.duration_ms) || 0) / longest) * 100}%`,
                                    background: s.status === 'FAILED' ? 'var(--negative)' : 'var(--cat-2, var(--cat-1))',
                                    borderRadius: 4,
                                }} />
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        </section>
    );
};

const Row = ({ label, value }) => (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
        <dt style={{ color: 'var(--text-muted)' }}>{label}</dt>
        <dd style={{ margin: 0, textAlign: 'right' }} className="num">{value ?? '—'}</dd>
    </div>
);

export default IngestTrust;
