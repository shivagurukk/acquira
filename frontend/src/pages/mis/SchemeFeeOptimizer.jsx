import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
    Receipt, Upload, Search, Gauge, Layers, PiggyBank, ListOrdered, Settings2,
    ScanSearch, Trash2, CheckCircle2, Plus, UserPlus, Route,
} from 'lucide-react';
import {
    ResponsiveContainer, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as RTooltip,
} from 'recharts';
import api from '../../api/axios';
import { showToast } from '../../contexts/ToastContext';
import {
    Page, Stack, Row, Card, Button, DataTable, Modal, Badge, Alert, Tabs,
    FormField, FormGrid, Input, Select, useConfirm,
} from '../../components/ui';

/* ════════════════════════════════════════════════════════════════════
   SCHEME FEE OPTIMIZER  (/mis/fee-optimizer)

   Reads the scheme's monthly billing invoice line by line, classifies
   every billing event, and flags what should not be there — duplicates,
   rate drift, fixed fees with no quantity behind them, repeated minimum
   top-ups, penalties, and events never billed before.

   Companion to /ops/scheme-billing-reference: that holds the PUBLISHED
   fee specifications, this holds what was ACTUALLY CHARGED. An invoice
   is evidence for a dispute, never a source of pricing — nothing here
   writes to a rate card.

   Seven tabs behind one route, the same shape the Integration Hub uses,
   so the sidebar carries one entry rather than seven.

   MOTION: none beyond CSS transitions.
   ════════════════════════════════════════════════════════════════════ */

const TABS = [
    { key: 'overview',  label: 'Overview',      icon: Gauge },
    { key: 'breakdown', label: 'Fee breakdown', icon: Layers },
    { key: 'savings',   label: 'Savings centre', icon: PiggyBank },
    { key: 'events',    label: 'Event explorer', icon: Search },
    { key: 'uploads',   label: 'Uploads',       icon: Upload },
    { key: 'lines',     label: 'Fee lines',     icon: ListOrdered },
    { key: 'settings',  label: 'Settings',      icon: Settings2 },
];

const CATEGORY_LABEL = {
    AUTHORISATION: 'Authorisation',
    CLEARING:      'Clearing',
    CROSS_BORDER:  'Cross-border',
    ASSESSMENTS:   'Assessments',
    FIXED_FEES:    'Fixed fees',
    PENALTIES:     'Penalties',
    OTHER:         'Unclassified',
};

const FINDING_LABEL = {
    DUPLICATE_CHARGE:   'Duplicate charge',
    RATE_ANOMALY:       'Rate anomaly',
    ZERO_QTY_FIXED_FEE: 'Zero-quantity fee',
    DUPLICATE_MINIMUM:  'Duplicate minimum',
    PENALTY:            'Penalty',
    UNEXPECTED_EVENT:   'Unexpected event',
};

const STATUS_FLOW = ['FLAGGED', 'UNDER_REVIEW', 'DISPUTED', 'RECOVERED', 'DISMISSED'];
const STATUS_LABEL = {
    FLAGGED: 'Flagged', UNDER_REVIEW: 'Under review', DISPUTED: 'Disputed',
    RECOVERED: 'Recovered', DISMISSED: 'Dismissed',
};
const STATUS_TONE = {
    FLAGGED: 'warning', UNDER_REVIEW: 'info', DISPUTED: 'brand',
    RECOVERED: 'success', DISMISSED: 'neutral',
};
const MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];

const n = (v) => { const x = parseFloat(v); return Number.isFinite(x) ? x : 0; };
const money = (v, dp = 2) => n(v).toLocaleString(undefined,
    { minimumFractionDigits: dp, maximumFractionDigits: dp });
const compact = (v) => {
    const x = n(v);
    if (Math.abs(x) >= 1e6) return `${(x / 1e6).toFixed(1)}M`;
    if (Math.abs(x) >= 1e3) return `${(x / 1e3).toFixed(1)}k`;
    return x.toFixed(0);
};

export default function SchemeFeeOptimizer() {
    const { section } = useParams();
    const navigate = useNavigate();
    const tab = TABS.some(t => t.key === section) ? section : 'overview';

    const [filters, setFilters] = useState(null);
    const [f, setF] = useState({ scheme: '', entity: '', year: '', currency: 'USD' });

    useEffect(() => {
        (async () => {
            try {
                const { data } = await api.get('/mis/fee-optimizer/filters');
                setFilters(data);
            } catch (e) {
                showToast(e?.response?.data?.error || 'Could not load the filters', 'error');
            }
        })();
    }, []);

    const setFilter = (k, v) => setF(prev => ({ ...prev, [k]: v }));

    const filterBar = (
        <Row gap="sm">
            <Select value={f.scheme} placeholder="All schemes"
                    options={[{ value: '', label: 'All schemes' },
                        ...(filters?.schemes || []).map(s => ({ value: s, label: s }))]}
                    onChange={e => setFilter('scheme', e.target.value)} />
            <Select value={f.entity} placeholder="All entities"
                    options={[{ value: '', label: 'All entities' },
                        ...(filters?.entities || []).map(s => ({ value: s, label: s }))]}
                    onChange={e => setFilter('entity', e.target.value)} />
            <Select value={f.year} placeholder="All years"
                    options={[{ value: '', label: 'All years' },
                        ...(filters?.years || []).map(y => ({ value: String(y), label: String(y) }))]}
                    onChange={e => setFilter('year', e.target.value)} />
            <Select value={f.currency}
                    options={(filters?.currencies || [{ currency_code: 'USD' }])
                        .map(c => ({ value: c.currency_code, label: c.currency_code }))}
                    onChange={e => setFilter('currency', e.target.value)} />
        </Row>
    );

    const params = useMemo(() => ({
        scheme: f.scheme || undefined,
        entity: f.entity || undefined,
        year: f.year || undefined,
        currency: f.currency || 'USD',
    }), [f]);

    return (
        <Page
            title="Scheme Fee Optimizer"
            subtitle="Read the scheme's invoice line by line, classify every billing event, and flag what should not be there."
            icon={Receipt}
            width="wide"
            actions={filterBar}>

            <Tabs tabs={TABS} active={tab}
                  onChange={k => navigate(`/mis/fee-optimizer/${k}`)} />

            <div style={{ height: 16 }} />

            {tab === 'overview'  && <Overview params={params} currency={f.currency} />}
            {tab === 'breakdown' && <Breakdown params={params} currency={f.currency} />}
            {tab === 'savings'   && <Savings />}
            {tab === 'events'    && <Events scheme={f.scheme} />}
            {tab === 'uploads'   && <Uploads />}
            {tab === 'lines'     && <Lines params={params} />}
            {tab === 'settings'  && <SettingsTab filters={filters} />}
        </Page>
    );
}

/* ── Overview ─────────────────────────────────────────────────────── */

function Overview({ params, currency }) {
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        let alive = true;
        setLoading(true);
        (async () => {
            try {
                const res = await api.get('/mis/fee-optimizer/overview', { params });
                if (alive) setData(res.data);
            } catch (e) {
                showToast(e?.response?.data?.error || 'Could not load the overview', 'error');
            } finally {
                if (alive) setLoading(false);
            }
        })();
        return () => { alive = false; };
    }, [params]);

    if (loading) return <Card pad>Loading…</Card>;
    if (!data || !data.kpis) return <Card pad>No data.</Card>;

    const k = data.kpis;
    const noData = n(k.lineCount) === 0;

    return (
        <Stack gap="lg">
            {noData && (
                <Alert tone="info" title="Nothing has been imported yet">
                    Load a Visa or Mastercard billing invoice on the Uploads tab. The parser reads
                    Excel and CSV, detects the scheme, entity, period, currency and invoice total,
                    and shows you what it found before anything is counted.
                </Alert>
            )}

            <Row gap="lg">
                <Kpi label={`Total scheme fees (${currency})`} value={money(k.totalFees)} />
                <Kpi label="Fee lines" value={money(k.lineCount, 0)} />
                {/* Excludes unexpected events: a new charge is something to
                    look at, not money to claim back. */}
                <Kpi label={`Claimable, annualised (${currency})`}
                     value={money(k.flaggedSavings)} tone="warning" />
                <Kpi label="Period movement" value={`${money(k.movement)} (${money(k.movementPct)}%)`}
                     tone={n(k.movement) > 0 ? 'danger' : 'success'} />
            </Row>

            {data.trend?.length > 0 && (
                <Card title="Fee evolution" subtitle={`Month by month, in ${currency}.`} pad>
                    <div style={{ width: '100%', height: 260 }}>
                        <ResponsiveContainer>
                            <BarChart data={data.trend} margin={{ top: 8, right: 8, left: 8, bottom: 8 }}>
                                <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
                                <XAxis dataKey="label" tick={{ fontSize: 11, fill: 'var(--text-muted)' }} />
                                <YAxis tickFormatter={compact}
                                       tick={{ fontSize: 11, fill: 'var(--text-muted)' }} />
                                <RTooltip formatter={(v) => `${money(v)} ${currency}`}
                                          contentStyle={{
                                              background: 'var(--bg-card)',
                                              border: '1px solid var(--border)',
                                              borderRadius: 6, fontSize: 12,
                                          }} />
                                <Bar dataKey="total" fill="var(--accent, #2F7E78)" radius={[3, 3, 0, 0]} />
                            </BarChart>
                        </ResponsiveContainer>
                    </div>
                </Card>
            )}

            <Row gap="lg" style={{ alignItems: 'flex-start' }}>
                <Card title="By category" style={{ flex: 1 }}>
                    <DataTable
                        rowKey={r => r.category}
                        rows={data.byCategory || []}
                        empty="Nothing classified yet."
                        columns={[
                            { key: 'category', header: 'Category',
                              render: r => CATEGORY_LABEL[r.category] || r.category },
                            { key: 'total', header: `Total ${currency}`, align: 'right', numeric: true,
                              render: r => money(r.total) },
                            { key: 'share', header: 'Share', align: 'right', numeric: true,
                              render: r => `${money(r.share, 1)}%` },
                            { key: 'lines', header: 'Lines', align: 'right', numeric: true },
                        ]}
                    />
                </Card>

                <Card title="Year comparison"
                      subtitle="A year total is only reported once all twelve months are loaded."
                      style={{ flex: 1 }}>
                    <DataTable
                        rowKey={r => r.year}
                        rows={data.years || []}
                        empty="No complete period yet."
                        columns={[
                            { key: 'year', header: 'Year' },
                            { key: 'total', header: `Total ${currency}`, align: 'right', numeric: true,
                              render: r => money(r.total) },
                            { key: 'status', header: 'Coverage',
                              render: r => r.complete
                                ? <Badge tone="success">Complete</Badge>
                                : <Badge tone="warning">{`Partial (${r.months} of 12)`}</Badge> },
                            { key: 'projected', header: `Projected ${currency}`, align: 'right', numeric: true,
                              render: r => r.projected == null
                                ? <span style={{ color: 'var(--text-muted)' }}>—</span>
                                : <span title="Monthly average × 12">{money(r.projected)}</span> },
                            { key: 'missing', header: 'Missing', muted: true,
                              render: r => (r.missingMonths || []).length === 0 ? '—'
                                : r.missingMonths.map(m => MONTHS[m - 1]).join(', ') },
                        ]}
                    />
                </Card>
            </Row>

            {(data.likeForLike?.years || []).length > 1 && (
                <Card title="Like-for-like"
                      subtitle={`Only the months every year holds: ${(data.likeForLike.months || [])
                          .map(m => MONTHS[m - 1]).join(', ') || 'none in common'}.`}>
                    <DataTable
                        rowKey={r => r.year}
                        rows={data.likeForLike.years}
                        columns={[
                            { key: 'year', header: 'Year' },
                            { key: 'total', header: `Total ${currency}`, align: 'right', numeric: true,
                              render: r => money(r.total) },
                        ]}
                    />
                </Card>
            )}
        </Stack>
    );
}

function Kpi({ label, value, tone }) {
    const color = tone === 'danger' ? 'var(--danger)'
                : tone === 'success' ? 'var(--success)'
                : tone === 'warning' ? 'var(--warning)' : 'var(--text)';
    return (
        <Card pad style={{ flex: 1 }}>
            <div style={{ fontSize: 11, letterSpacing: '.06em', textTransform: 'uppercase',
                          color: 'var(--text-muted)' }}>{label}</div>
            <div style={{ fontSize: 24, fontWeight: 700, marginTop: 6, color,
                          fontVariantNumeric: 'tabular-nums' }}>{value}</div>
        </Card>
    );
}

/* ── Fee breakdown ────────────────────────────────────────────────── */

function Breakdown({ params, currency }) {
    const [rows, setRows] = useState([]);
    const [loading, setLoading] = useState(true);
    const [open, setOpen] = useState(() => new Set());

    useEffect(() => {
        let alive = true;
        setLoading(true);
        (async () => {
            try {
                const { data } = await api.get('/mis/fee-optimizer/breakdown', { params });
                if (alive) setRows(data || []);
            } catch (e) {
                showToast(e?.response?.data?.error || 'Could not load the breakdown', 'error');
            } finally {
                if (alive) setLoading(false);
            }
        })();
        return () => { alive = false; };
    }, [params]);

    const grouped = useMemo(() => {
        const byCat = new Map();
        for (const r of rows) {
            if (!byCat.has(r.category)) byCat.set(r.category, { total: 0, lines: 0, events: [] });
            const g = byCat.get(r.category);
            g.total += n(r.total);
            g.lines += n(r.lines);
            g.events.push(r);
        }
        return [...byCat.entries()].sort((a, b) => b[1].total - a[1].total);
    }, [rows]);

    const grandTotal = grouped.reduce((s, [, g]) => s + g.total, 0);

    if (loading) return <Card pad>Loading…</Card>;
    if (!rows.length) return <Card pad>Nothing to break down yet — import an invoice first.</Card>;

    const toggle = (cat) => setOpen(prev => {
        const next = new Set(prev);
        if (next.has(cat)) next.delete(cat); else next.add(cat);
        return next;
    });

    return (
        <Stack gap="md">
            {grouped.map(([cat, g]) => (
                <Card key={cat}
                      title={CATEGORY_LABEL[cat] || cat}
                      subtitle={`${money(g.total)} ${currency} · ${money(g.lines, 0)} lines · ${
                          grandTotal ? money(g.total / grandTotal * 100, 1) : '0.0'}% of total`}
                      actions={<Button size="sm" onClick={() => toggle(cat)}>
                          {open.has(cat) ? 'Hide events' : `Show ${g.events.length} events`}
                      </Button>}>
                    {open.has(cat) && (
                        <DataTable
                            rowKey={r => `${r.scheme}-${r.eventCode}`}
                            rows={g.events}
                            defaultSort={{ key: 'total', dir: 'desc' }}
                            columns={[
                                { key: 'eventCode', header: 'Event', mono: true },
                                { key: 'description', header: 'Description' },
                                { key: 'scheme', header: 'Scheme' },
                                { key: 'quantity', header: 'Quantity', align: 'right', numeric: true,
                                  render: r => money(r.quantity, 0) },
                                { key: 'total', header: `Total ${currency}`, align: 'right', numeric: true,
                                  render: r => money(r.total) },
                                { key: 'confidence', header: 'Confidence', align: 'right',
                                  render: r => <ConfidenceBadge value={r.confidence} by={r.classifiedBy} /> },
                            ]}
                        />
                    )}
                </Card>
            ))}
        </Stack>
    );
}

/** Says how the classification was reached, not just how sure it is. */
function ConfidenceBadge({ value, by }) {
    const v = n(value);
    const tone = by === 'OVERRIDE' ? 'success' : v >= 80 ? 'info' : v >= 50 ? 'warning' : 'danger';
    const label = by === 'OVERRIDE' ? 'Set by you'
                : by === 'DICTIONARY' ? `Dictionary ${v.toFixed(0)}%`
                : by === 'PATTERN' ? `Pattern ${v.toFixed(0)}%`
                : 'Unclassified';
    return <Badge tone={tone}>{label}</Badge>;
}

/* ── Savings centre ───────────────────────────────────────────────── */

function Savings() {
    const [rows, setRows] = useState([]);
    const [teams, setTeams] = useState([]);
    const [queues, setQueues] = useState([]);
    const [loading, setLoading] = useState(true);
    const [status, setStatus] = useState('');
    const [team, setTeam] = useState('');
    const [overdueOnly, setOverdueOnly] = useState(false);
    const [scanning, setScanning] = useState(false);
    const [detail, setDetail] = useState(null);
    const [assigning, setAssigning] = useState(null);

    const load = useCallback(async (st, tm, od) => {
        setLoading(true);
        try {
            const [f, q] = await Promise.all([
                api.get('/mis/fee-optimizer/savings', {
                    params: {
                        status: st || undefined,
                        team: tm || undefined,
                        overdue: od ? true : undefined,
                    },
                }),
                api.get('/mis/fee-optimizer/savings/by-team'),
            ]);
            setRows(f.data || []);
            setQueues(q.data || []);
        } catch (e) {
            showToast(e?.response?.data?.error || 'Could not load the findings', 'error');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => { load(status, team, overdueOnly); }, [load, status, team, overdueOnly]);

    useEffect(() => {
        api.get('/mis/fee-optimizer/teams')
            .then(({ data }) => setTeams(data || []))
            .catch(() => { /* the filter just stays empty */ });
    }, []);

    const scan = async () => {
        setScanning(true);
        try {
            const { data } = await api.post('/mis/fee-optimizer/savings/scan');
            showToast(
                `${data.raised} new, ${data.updated} refreshed`
                + (data.retired ? `, ${data.retired} closed` : '')
                + (data.routed ? `, ${data.routed} routed` : '')
                + ` across ${data.scanned} lines`, 'success');
            load(status, team, overdueOnly);
        } catch (e) {
            showToast(e?.response?.data?.error || 'The scan failed', 'error');
        } finally {
            setScanning(false);
        }
    };

    const move = async (row, next) => {
        try {
            await api.put(`/mis/fee-optimizer/savings/${row.opportunity_id}`, { status: next });
            setRows(rs => rs.map(r => r.opportunity_id === row.opportunity_id
                ? { ...r, status: next } : r));
            showToast(`Moved to ${STATUS_LABEL[next].toLowerCase()}`, 'success');
            // The queue counts move with it, so they are re-read rather than
            // left showing a number that no longer matches the list.
            api.get('/mis/fee-optimizer/savings/by-team')
                .then(({ data }) => setQueues(data || []))
                .catch(() => {});
        } catch (e) {
            showToast(e?.response?.data?.error || 'Could not update the finding', 'error');
        }
    };

    const saveAssignment = async (form) => {
        try {
            await api.put(`/mis/fee-optimizer/savings/${assigning.opportunity_id}/assign`, form);
            showToast('Finding assigned', 'success');
            setAssigning(null);
            load(status, team, overdueOnly);
        } catch (e) {
            showToast(e?.response?.data?.error || 'Could not assign the finding', 'error');
        }
    };

    const open = rows.filter(r => ['FLAGGED', 'UNDER_REVIEW', 'DISPUTED'].includes(r.status));
    // Same definition as the Overview's headline: an unexpected event is a
    // watch item, not money to claim back. Counting it in both places under
    // two different totals is how a number loses its meaning.
    const claimable = open.filter(r => r.finding_type !== 'UNEXPECTED_EVENT');
    const openValue = claimable.reduce((s, r) => s + n(r.annualised_value_usd), 0);
    const watching  = open.length - claimable.length;
    const recovered = rows.filter(r => r.status === 'RECOVERED')
        .reduce((s, r) => s + n(r.annualised_value_usd), 0);

    const overdueCount = queues.reduce((s, q) => s + n(q.overdue_count), 0);

    return (
        <Stack gap="lg">
            <Row gap="lg">
                <Kpi label="Open findings" value={money(claimable.length, 0)} />
                <Kpi label="Claimable, annualised (USD)" value={money(openValue)} tone="warning" />
                <Kpi label="Watch items" value={money(watching, 0)} />
                <Kpi label="Past due" value={money(overdueCount, 0)}
                     tone={overdueCount > 0 ? 'danger' : undefined} />
                <Kpi label="Recovered, annualised (USD)" value={money(recovered)} tone="success" />
            </Row>

            {/* The queues. Clicking one filters the list below to that team —
                this strip is the way into the work, not just a summary. */}
            <Card title="Team queues"
                  subtitle="Routed to whoever can fix the cause, not whoever pays the bill.">
                <DataTable
                    rowKey={q => q.team_code}
                    rows={queues}
                    empty="Nothing open."
                    onRowClick={q => setTeam(q.team_code === team ? '' : q.team_code)}
                    rowClassName={q => q.team_code === team && 'ui-tr--current'}
                    columns={[
                        { key: 'team_name', header: 'Team',
                          render: q => q.team_name
                            || (q.team_code === 'UNROUTED'
                                ? <Badge tone="warning">Unrouted — no rule claims these</Badge>
                                : q.team_code) },
                        { key: 'open_count', header: 'Open', align: 'right', numeric: true },
                        { key: 'overdue_count', header: 'Past due', align: 'right', numeric: true,
                          render: q => n(q.overdue_count) > 0
                            ? <Badge tone="danger">{money(q.overdue_count, 0)}</Badge>
                            : <span style={{ color: 'var(--text-muted)' }}>—</span> },
                        { key: 'claimable_usd', header: 'Claimable USD', align: 'right', numeric: true,
                          render: q => money(q.claimable_usd) },
                        { key: 'next_due', header: 'Next due', nowrap: true, muted: true,
                          render: q => q.next_due || '—' },
                    ]}
                />
            </Card>

            <Card
                title="Findings"
                subtitle="A finding is a prompt to look, not a proof — the evidence travels with it."
                actions={
                    <Row gap="sm">
                        <Select value={team}
                                options={[{ value: '', label: 'All teams' },
                                    ...teams.map(x => ({ value: x.team_code, label: x.team_name })),
                                    { value: 'UNROUTED', label: 'Unrouted' }]}
                                onChange={e => setTeam(e.target.value)} />
                        <Select value={status}
                                options={[{ value: '', label: 'All statuses' },
                                    ...STATUS_FLOW.map(s => ({ value: s, label: STATUS_LABEL[s] }))]}
                                onChange={e => setStatus(e.target.value)} />
                        <Button size="sm" variant={overdueOnly ? 'primary' : 'default'}
                                onClick={() => setOverdueOnly(v => !v)}>
                            Past due only
                        </Button>
                        <Button variant="primary" icon={ScanSearch} loading={scanning} onClick={scan}>
                            Run detection
                        </Button>
                    </Row>
                }>
                <DataTable
                    loading={loading}
                    rowKey={r => r.opportunity_id}
                    rows={rows}
                    pageSize={20}
                    empty="No findings. Run detection after importing an invoice."
                    onRowClick={setDetail}
                    columns={[
                        { key: 'severity', header: '', width: 8,
                          render: r => <Badge tone={r.severity === 'HIGH' ? 'danger'
                              : r.severity === 'MEDIUM' ? 'warning' : 'neutral'}>{r.severity}</Badge> },
                        { key: 'finding_type', header: 'Finding',
                          render: r => FINDING_LABEL[r.finding_type] || r.finding_type },
                        { key: 'description', header: 'What was found' },
                        { key: 'period', header: 'Period', nowrap: true,
                          render: r => r.period_year
                            ? `${MONTHS[(r.period_month || 1) - 1]} ${r.period_year}` : '—' },
                        { key: 'monthly_value', header: 'Monthly', align: 'right', numeric: true,
                          render: r => `${money(r.monthly_value)} ${r.currency_code || ''}` },
                        { key: 'annualised_value_usd', header: 'Annualised USD',
                          align: 'right', numeric: true,
                          render: r => <strong>{money(r.annualised_value_usd)}</strong> },
                        { key: 'team_code', header: 'Team', nowrap: true,
                          render: r => r.team_code
                            ? <span title={r.routed_by === 'MANUAL'
                                  ? 'Assigned by hand — re-scans leave this alone'
                                  : 'Placed by a routing rule'}>
                                {teamName(teams, r.team_code)}
                                {r.routed_by === 'MANUAL' && ' *'}
                              </span>
                            : <Badge tone="warning">Unrouted</Badge> },
                        { key: 'assignee', header: 'Owner', muted: true,
                          render: r => r.assignee || '—' },
                        { key: 'due_date', header: 'Due', nowrap: true,
                          render: r => !r.due_date
                            ? <span style={{ color: 'var(--text-muted)' }}>—</span>
                            : r.is_overdue
                              ? <Badge tone="danger">{`${r.days_past_due}d late`}</Badge>
                              : r.due_date },
                        { key: 'status', header: 'Status',
                          render: r => <Badge tone={STATUS_TONE[r.status]}>{STATUS_LABEL[r.status]}</Badge> },
                        { key: '_assign', header: '', align: 'right',
                          render: r => (
                            <Button size="sm" icon={UserPlus} iconOnly
                                    aria-label={`Assign finding ${r.opportunity_id}`}
                                    onClick={(e) => { e.stopPropagation(); setAssigning(r); }} />) },
                    ]}
                />
            </Card>

            <AssignModal
                finding={assigning}
                teams={teams}
                onClose={() => setAssigning(null)}
                onSave={saveAssignment}
            />

            <Modal open={!!detail} onClose={() => setDetail(null)}
                   title={detail ? (FINDING_LABEL[detail.finding_type] || detail.finding_type) : ''}
                   size="lg"
                   footer={detail && (
                       <Row gap="sm" between style={{ width: '100%' }}>
                           <Row gap="sm">
                               {STATUS_FLOW.filter(s => s !== detail.status).map(s => (
                                   <Button key={s} size="sm"
                                           variant={s === 'RECOVERED' ? 'primary' : 'default'}
                                           onClick={() => { move(detail, s); setDetail(null); }}>
                                       {STATUS_LABEL[s]}
                                   </Button>
                               ))}
                           </Row>
                           <Button onClick={() => setDetail(null)}>Close</Button>
                       </Row>
                   )}>
                {detail && (
                    <Stack gap="md">
                        <Alert tone={detail.severity === 'HIGH' ? 'warning' : 'info'}
                               title={detail.description} />
                        <FormGrid cols={2}>
                            <Field label="Scheme" value={detail.scheme || '—'} />
                            <Field label="Entity" value={detail.entity_code || '—'} />
                            <Field label="Event code" value={detail.event_code || '—'} />
                            <Field label="Period" value={detail.period_year
                                ? `${MONTHS[(detail.period_month || 1) - 1]} ${detail.period_year}` : '—'} />
                            <Field label="Monthly value"
                                   value={`${money(detail.monthly_value)} ${detail.currency_code || ''}`} />
                            <Field label="Annualised (USD)" value={money(detail.annualised_value_usd)} />
                            <Field label="Team" value={detail.team_code
                                ? `${teamName(teams, detail.team_code)}${
                                      detail.routed_by === 'MANUAL' ? ' (assigned by hand)' : ''}`
                                : 'Unrouted'} />
                            <Field label="Owner" value={detail.assignee || 'Nobody yet'} />
                            <Field label="Due" value={detail.due_date
                                ? `${detail.due_date}${detail.is_overdue
                                    ? ` — ${detail.days_past_due} days late` : ''}`
                                : '—'} />
                        </FormGrid>
                        <div>
                            <div style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '.06em',
                                          color: 'var(--text-muted)', marginBottom: 6 }}>Evidence</div>
                            <pre style={{ margin: 0, padding: 12, fontSize: 12, overflowX: 'auto',
                                          background: 'var(--bg-subtle, rgba(127,127,127,.08))',
                                          border: '1px solid var(--border)', borderRadius: 6 }}>
{prettyJson(detail.evidence)}
                            </pre>
                        </div>
                    </Stack>
                )}
            </Modal>
        </Stack>
    );
}

/** Team code to its display name, falling back to the code itself. */
const teamName = (teams, code) =>
    (teams.find(t => t.team_code === code) || {}).team_name || code;

/**
 * Hands a finding to a team and a person.
 *
 * Saving marks the finding hand-assigned, which is what stops the next
 * re-scan moving it back to wherever the rules would have put it — so the
 * dialog says so rather than leaving it as a surprise.
 */
function AssignModal({ finding, teams, onClose, onSave }) {
    const [form, setForm] = useState({ teamCode: '', assignee: '', dueDate: '' });
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        if (finding) {
            setForm({
                teamCode: finding.team_code || '',
                assignee: finding.assignee || '',
                dueDate: finding.due_date || '',
            });
        }
    }, [finding]);

    if (!finding) return null;

    const submit = async (e) => {
        e.preventDefault();
        setSaving(true);
        try { await onSave(form); } finally { setSaving(false); }
    };

    return (
        <Modal as="form" open onClose={onClose} onSubmit={submit}
               title="Assign this finding"
               footer={<>
                   <Button type="button" onClick={onClose}>Cancel</Button>
                   <Button type="submit" variant="primary" loading={saving}>Assign</Button>
               </>}>
            <Stack gap="md">
                <Alert tone="info" title="This becomes a hand assignment">
                    Once set by hand, re-running detection will not move this finding back to
                    whichever team the rules would have chosen.
                </Alert>
                <Field label="Finding" value={finding.description} />
                <FormGrid cols={2}>
                    <FormField label="Team" required>
                        <Select value={form.teamCode} placeholder="Select a team…"
                                options={teams.map(t => ({
                                    value: t.team_code,
                                    label: `${t.team_name} — ${t.open_count} open`,
                                }))}
                                onChange={e => setForm(f => ({ ...f, teamCode: e.target.value }))} />
                    </FormField>
                    <FormField label="Owner" hint="Who is actually chasing it">
                        <Input value={form.assignee} placeholder="username"
                               onChange={e => setForm(f => ({ ...f, assignee: e.target.value }))} />
                    </FormField>
                    <FormField label="Due" className="ui-form-grid--span"
                               hint="Leave as it is to keep the SLA date the rule set">
                        <Input type="date" value={form.dueDate}
                               onChange={e => setForm(f => ({ ...f, dueDate: e.target.value }))} />
                    </FormField>
                </FormGrid>
            </Stack>
        </Modal>
    );
}

function Field({ label, value }) {
    return (
        <div>
            <div style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '.06em',
                          color: 'var(--text-muted)' }}>{label}</div>
            <div style={{ fontSize: 14, marginTop: 2 }}>{value}</div>
        </div>
    );
}

const prettyJson = (raw) => {
    if (!raw) return '{}';
    try { return JSON.stringify(typeof raw === 'string' ? JSON.parse(raw) : raw, null, 2); }
    catch { return String(raw); }
};

/* ── Event explorer ───────────────────────────────────────────────── */

function Events({ scheme }) {
    const [rows, setRows] = useState([]);
    const [loading, setLoading] = useState(true);
    const [q, setQ] = useState('');
    const [editing, setEditing] = useState(null);
    const [category, setCategory] = useState('OTHER');
    const [note, setNote] = useState('');
    const [saving, setSaving] = useState(false);
    const timer = useRef(null);

    const load = useCallback(async (search) => {
        setLoading(true);
        try {
            const { data } = await api.get('/mis/fee-optimizer/events',
                { params: { q: search || undefined, scheme: scheme || undefined } });
            setRows(data || []);
        } catch (e) {
            showToast(e?.response?.data?.error || 'Could not search the events', 'error');
        } finally {
            setLoading(false);
        }
    }, [scheme]);

    useEffect(() => {
        clearTimeout(timer.current);
        timer.current = setTimeout(() => load(q), 300);
        return () => clearTimeout(timer.current);
    }, [q, load]);

    const save = async () => {
        setSaving(true);
        try {
            const { data } = await api.post('/mis/fee-optimizer/overrides', {
                scheme: editing.scheme, eventCode: editing.event_code, category, note,
            });
            showToast(`Saved — ${data.reclassified} existing lines reclassified`, 'success');
            setEditing(null);
            setNote('');
            load(q);
        } catch (e) {
            showToast(e?.response?.data?.error || 'Could not save the classification', 'error');
        } finally {
            setSaving(false);
        }
    };

    return (
        <Card
            title="Event explorer"
            subtitle="Every billing event seen, what we believe it is, and how that was decided. Correct one here and it stays corrected.">
            <DataTable
                loading={loading}
                rowKey={r => `${r.scheme}-${r.event_code}`}
                rows={rows}
                pageSize={25}
                search={{ value: q, onChange: setQ, placeholder: 'Search code or description…' }}
                empty="No billing events yet — import an invoice first."
                columns={[
                    { key: 'event_code', header: 'Code', mono: true },
                    { key: 'description', header: 'Description' },
                    { key: 'scheme', header: 'Scheme' },
                    { key: 'category', header: 'Category',
                      render: r => CATEGORY_LABEL[r.category] || r.category },
                    { key: 'confidence', header: 'Classified', align: 'right',
                      render: r => <ConfidenceBadge value={r.confidence}
                          by={r.overridden ? 'OVERRIDE' : r.classified_by} /> },
                    { key: 'lines', header: 'Lines', align: 'right', numeric: true },
                    { key: 'total_usd', header: 'Total USD', align: 'right', numeric: true,
                      render: r => money(r.total_usd) },
                    { key: '_actions', header: '', align: 'right',
                      render: r => <Button size="sm" onClick={() => {
                          setEditing(r);
                          setCategory(r.category || 'OTHER');
                          setNote('');
                      }}>Classify</Button> },
                ]}
            />

            <Modal as="form" open={!!editing} onClose={() => setEditing(null)}
                   onSubmit={(e) => { e.preventDefault(); save(); }}
                   title={editing ? `Classify ${editing.event_code}` : ''}
                   footer={<>
                       <Button type="button" onClick={() => setEditing(null)}>Cancel</Button>
                       <Button type="submit" variant="primary" loading={saving}>Save classification</Button>
                   </>}>
                {editing && (
                    <Stack gap="md">
                        <Alert tone="info" title="This applies to every line, past and future">
                            Saving reclassifies the {editing.lines} line{editing.lines === 1 ? '' : 's'} already
                            imported under this code, and fixes how future invoices read it.
                        </Alert>
                        <Field label="Description" value={editing.description || '—'} />
                        <FormField label="Category" required>
                            <Select value={category}
                                    options={Object.entries(CATEGORY_LABEL)
                                        .map(([v, l]) => ({ value: v, label: l }))}
                                    onChange={e => setCategory(e.target.value)} />
                        </FormField>
                        <FormField label="Note" hint="Why this is the right reading — for whoever looks next.">
                            <Input value={note} onChange={e => setNote(e.target.value)} />
                        </FormField>
                    </Stack>
                )}
            </Modal>
        </Card>
    );
}

/* ── Uploads ──────────────────────────────────────────────────────── */

function Uploads() {
    const [rows, setRows] = useState([]);
    const [loading, setLoading] = useState(true);
    const [busy, setBusy] = useState(false);
    const [review, setReview] = useState(null);
    const [confirmForm, setConfirmForm] = useState({});
    const fileRef = useRef(null);
    const confirmDialog = useConfirm();

    const load = useCallback(async () => {
        setLoading(true);
        try {
            const { data } = await api.get('/mis/fee-optimizer/uploads');
            setRows(data || []);
        } catch (e) {
            showToast(e?.response?.data?.error || 'Could not load the uploads', 'error');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => { load(); }, [load]);

    const pick = () => fileRef.current?.click();

    const upload = async (e) => {
        const file = e.target.files?.[0];
        e.target.value = '';
        if (!file) return;
        setBusy(true);
        try {
            const fd = new FormData();
            fd.append('file', file);
            const { data } = await api.post('/mis/fee-optimizer/uploads', fd, {
                headers: { 'Content-Type': 'multipart/form-data' },
            });
            setReview(data);
            setConfirmForm({
                scheme:      valueOf(data.fields?.scheme) || '',
                entityCode:  valueOf(data.fields?.entityCode) || '',
                currency:    valueOf(data.fields?.currency) || '',
                periodYear:  valueOf(data.fields?.periodYear) || '',
                periodMonth: valueOf(data.fields?.periodMonth) || '',
            });
            load();
        } catch (err) {
            showToast(err?.response?.data?.error || 'The upload failed', 'error');
        } finally {
            setBusy(false);
        }
    };

    const confirmImport = async () => {
        setBusy(true);
        try {
            const { data } = await api.post(
                `/mis/fee-optimizer/uploads/${review.uploadId}/confirm`, confirmForm);
            showToast(
                `${data.imported} lines imported · ${data.findingsRaised} findings raised`, 'success');
            setReview(null);
            load();
        } catch (e) {
            showToast(e?.response?.data?.error || 'The import failed', 'error');
        } finally {
            setBusy(false);
        }
    };

    const remove = async (row) => {
        const ok = await confirmDialog({
            title: 'Delete this upload?',
            message: `Every fee line imported from ${row.file_name} is removed with it, and the `
                   + 'dashboard stops counting them. Findings already raised stay, but their '
                   + 'evidence will point at lines that are gone.',
            confirmLabel: 'Delete upload',
            tone: 'danger',
        });
        if (!ok) return;
        try {
            await api.delete(`/mis/fee-optimizer/uploads/${row.upload_id}`);
            showToast('Upload removed', 'success');
            load();
        } catch (e) {
            showToast(e?.response?.data?.error || 'Could not delete the upload', 'error');
        }
    };

    const reconciles = review
        && review.invoiceTotal != null
        && Math.abs(n(review.invoiceTotal) - n(review.parsedTotal)) < 0.01;

    return (
        <Stack gap="lg">
            <Card
                title="Import a billing invoice"
                subtitle="Excel or CSV. Nothing is counted until you have seen what was detected and agreed it."
                actions={
                    <>
                        <input ref={fileRef} type="file" hidden
                               accept=".xlsx,.xls,.csv,.txt" onChange={upload} />
                        <Button variant="primary" icon={Upload} loading={busy} onClick={pick}>
                            Choose a file
                        </Button>
                    </>
                }
                pad>
                <Alert tone="info" title="What the parser works out for itself">
                    It finds the header row wherever it sits, maps the columns it recognises, and
                    detects the scheme, billing entity, period, currency and printed invoice total —
                    then checks that the lines it read add up to that total. Anything it cannot work
                    out, it asks you for.
                </Alert>
            </Card>

            <Card title="Uploads">
                <DataTable
                    loading={loading}
                    rowKey={r => r.upload_id}
                    rows={rows}
                    pageSize={15}
                    empty="No invoices have been uploaded yet."
                    columns={[
                        { key: 'file_name', header: 'File' },
                        { key: 'scheme', header: 'Scheme' },
                        { key: 'entity_code', header: 'Entity' },
                        { key: 'period', header: 'Period', nowrap: true,
                          render: r => r.period_year
                            ? `${MONTHS[(r.period_month || 1) - 1]} ${r.period_year}` : '—' },
                        { key: 'line_count', header: 'Lines', align: 'right', numeric: true },
                        { key: 'parsed_total', header: 'Parsed total', align: 'right', numeric: true,
                          render: r => `${money(r.parsed_total)} ${r.currency_code || ''}` },
                        { key: 'status', header: 'Status',
                          render: r => <Badge tone={
                              r.status === 'DONE' ? 'success'
                            : r.status === 'FAILED' ? 'danger'
                            : r.status === 'NEEDS_REVIEW' ? 'warning' : 'info'
                          }>{r.status.replace('_', ' ').toLowerCase()}</Badge> },
                        { key: 'uploaded_by', header: 'By', muted: true },
                        { key: '_actions', header: '', align: 'right',
                          render: r => (
                            <Button size="sm" variant="danger-ghost" iconOnly icon={Trash2}
                                    aria-label={`Delete ${r.file_name}`} onClick={() => remove(r)} />) },
                    ]}
                />
            </Card>

            <Modal open={!!review} onClose={() => setReview(null)}
                   title="Confirm before importing" size="xl"
                   footer={<>
                       <Button onClick={() => setReview(null)}>Cancel</Button>
                       <Button variant="primary" icon={CheckCircle2} loading={busy}
                               onClick={confirmImport}>Import {review?.lineCount} lines</Button>
                   </>}>
                {review && (
                    <Stack gap="md">
                        {review.warnings?.length > 0 && review.warnings.map((w, i) => (
                            <Alert key={i} tone="warning" title="Check this">{w}</Alert>
                        ))}

                        <Alert tone={reconciles ? 'success' : 'warning'}
                               title={reconciles
                                   ? 'The lines reconcile against the printed invoice total'
                                   : 'The lines do not reconcile'}>
                            Read {money(review.parsedTotal)} across {review.lineCount} lines
                            {review.invoiceTotal != null
                                ? ` against a printed total of ${money(review.invoiceTotal)}.`
                                : '. No printed total was found on the file, so nothing could be checked against it.'}
                        </Alert>

                        <FormGrid cols={3}>
                            <FormField label="Scheme" required
                                       hint={evidenceOf(review.fields?.scheme)}>
                                <Select value={confirmForm.scheme}
                                        placeholder="Select…"
                                        options={[{ value: 'VISA', label: 'Visa' },
                                                  { value: 'MASTERCARD', label: 'Mastercard' }]}
                                        onChange={e => setConfirmForm(c => ({ ...c, scheme: e.target.value }))} />
                            </FormField>
                            <FormField label="Currency" required
                                       hint={evidenceOf(review.fields?.currency)}>
                                <Input value={confirmForm.currency} placeholder="AED"
                                       onChange={e => setConfirmForm(c => ({
                                           ...c, currency: e.target.value.toUpperCase() }))} />
                            </FormField>
                            <FormField label="Billing entity"
                                       hint={evidenceOf(review.fields?.entityCode)}>
                                <Input value={confirmForm.entityCode}
                                       onChange={e => setConfirmForm(c => ({ ...c, entityCode: e.target.value }))} />
                            </FormField>
                            <FormField label="Period year" required
                                       hint={evidenceOf(review.fields?.periodYear)}>
                                <Input type="number" value={confirmForm.periodYear}
                                       onChange={e => setConfirmForm(c => ({ ...c, periodYear: e.target.value }))} />
                            </FormField>
                            <FormField label="Period month" required
                                       hint={evidenceOf(review.fields?.periodMonth)}>
                                <Select value={String(confirmForm.periodMonth || '')}
                                        placeholder="Select…"
                                        options={MONTHS.map((m, i) => ({ value: String(i + 1), label: m }))}
                                        onChange={e => setConfirmForm(c => ({ ...c, periodMonth: e.target.value }))} />
                            </FormField>
                            <FormField label="Invoice reference"
                                       hint={evidenceOf(review.fields?.invoiceRef)}>
                                <Input readOnly value={valueOf(review.fields?.invoiceRef) || '—'} />
                            </FormField>
                        </FormGrid>

                        <div>
                            <div style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '.06em',
                                          color: 'var(--text-muted)', marginBottom: 6 }}>
                                First lines read (header on row {review.headerRow + 1})
                            </div>
                            <DataTable
                                rowKey={r => r.sourceRow}
                                rows={review.sample || []}
                                compact
                                columns={[
                                    { key: 'sourceRow', header: 'Row', align: 'right', muted: true },
                                    { key: 'eventCode', header: 'Code', mono: true },
                                    { key: 'description', header: 'Description' },
                                    { key: 'quantity', header: 'Qty', align: 'right', numeric: true },
                                    { key: 'unitRate', header: 'Rate', align: 'right', numeric: true },
                                    { key: 'amount', header: 'Amount', align: 'right', numeric: true,
                                      render: r => money(r.amount) },
                                ]}
                            />
                        </div>
                    </Stack>
                )}
            </Modal>
        </Stack>
    );
}

const valueOf = (field) => (field && field.value != null ? String(field.value) : '');
const evidenceOf = (field) => {
    if (!field) return undefined;
    const c = n(field.confidence);
    return c > 0 ? `${field.evidence} · ${c}% sure` : field.evidence;
};

/* ── Fee lines ────────────────────────────────────────────────────── */

function Lines({ params }) {
    const [data, setData] = useState({ rows: [], total: 0 });
    const [loading, setLoading] = useState(true);
    const [q, setQ] = useState('');
    const [page, setPage] = useState(0);
    const timer = useRef(null);

    useEffect(() => {
        clearTimeout(timer.current);
        timer.current = setTimeout(async () => {
            setLoading(true);
            try {
                const { data: res } = await api.get('/mis/fee-optimizer/lines',
                    { params: { ...params, q: q || undefined, page, size: 100 } });
                setData(res);
            } catch (e) {
                showToast(e?.response?.data?.error || 'Could not load the fee lines', 'error');
            } finally {
                setLoading(false);
            }
        }, 250);
        return () => clearTimeout(timer.current);
    }, [params, q, page]);

    const pages = Math.ceil(n(data.total) / 100) || 1;

    return (
        <Card
            title="Fee lines"
            subtitle={`${money(data.total, 0)} lines. Every one traces back to the file it came from.`}
            actions={
                <Row gap="sm">
                    <Button size="sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>
                        Previous
                    </Button>
                    <span style={{ fontSize: 13, color: 'var(--text-muted)', alignSelf: 'center' }}>
                        Page {page + 1} of {pages}
                    </span>
                    <Button size="sm" disabled={page + 1 >= pages} onClick={() => setPage(p => p + 1)}>
                        Next
                    </Button>
                </Row>
            }>
            <DataTable
                loading={loading}
                rowKey={r => r.line_id}
                rows={data.rows || []}
                search={{ value: q, onChange: (v) => { setQ(v); setPage(0); },
                          placeholder: 'Search code or description…' }}
                empty="No fee lines match."
                columns={[
                    { key: 'period', header: 'Period', nowrap: true,
                      render: r => `${MONTHS[(r.period_month || 1) - 1]} ${r.period_year}` },
                    { key: 'scheme', header: 'Scheme' },
                    { key: 'entity_code', header: 'Entity', muted: true },
                    { key: 'event_code', header: 'Code', mono: true },
                    { key: 'description', header: 'Description' },
                    { key: 'quantity', header: 'Qty', align: 'right', numeric: true,
                      render: r => r.quantity == null ? '—' : money(r.quantity, 0) },
                    { key: 'unit_rate', header: 'Rate', align: 'right', numeric: true,
                      render: r => r.unit_rate == null ? '—' : money(r.unit_rate, 6) },
                    { key: 'amount', header: 'Amount', align: 'right', numeric: true,
                      render: r => `${money(r.amount)} ${r.currency_code}` },
                    { key: 'amount_usd', header: 'USD', align: 'right', numeric: true,
                      render: r => money(r.amount_usd) },
                    { key: 'category', header: 'Category',
                      render: r => CATEGORY_LABEL[r.category] || r.category },
                    { key: 'confidence', header: 'Classified', align: 'right',
                      render: r => <ConfidenceBadge value={r.confidence} by={r.classified_by} /> },
                ]}
            />
        </Card>
    );
}

/* ── Settings ─────────────────────────────────────────────────────── */

function SettingsTab({ filters }) {
    const [overrides, setOverrides] = useState([]);
    const [entities, setEntities] = useState([]);
    const [teams, setTeams] = useState([]);
    const [rules, setRules] = useState([]);
    const [loading, setLoading] = useState(true);
    const [entityOpen, setEntityOpen] = useState(false);
    const [entity, setEntity] = useState({ entityCode: '', entityName: '', scheme: '' });
    const [ruleOpen, setRuleOpen] = useState(false);
    const [applying, setApplying] = useState(false);
    const [rule, setRule] = useState({
        findingType: '', category: '', matchText: '', teamCode: '',
        slaDays: '', priority: '50', note: '',
    });
    const confirmDialog = useConfirm();

    const load = useCallback(async () => {
        setLoading(true);
        try {
            const [o, e, tm, rr] = await Promise.all([
                api.get('/mis/fee-optimizer/overrides'),
                api.get('/mis/fee-optimizer/entities'),
                api.get('/mis/fee-optimizer/teams'),
                api.get('/mis/fee-optimizer/routing-rules'),
            ]);
            setOverrides(o.data || []);
            setEntities(e.data || []);
            setTeams(tm.data || []);
            setRules(rr.data || []);
        } catch (err) {
            showToast(err?.response?.data?.error || 'Could not load the settings', 'error');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => { load(); }, [load]);

    const removeOverride = async (row) => {
        const ok = await confirmDialog({
            title: 'Remove this override?',
            message: `${row.scheme} ${row.event_code} goes back to being classified automatically. `
                   + 'Lines already imported keep their current category until the next import.',
            confirmLabel: 'Remove override',
            tone: 'danger',
        });
        if (!ok) return;
        try {
            await api.delete(`/mis/fee-optimizer/overrides/${row.override_id}`);
            showToast('Override removed', 'success');
            load();
        } catch (e) {
            showToast(e?.response?.data?.error || 'Could not remove the override', 'error');
        }
    };

    const saveEntity = async () => {
        try {
            await api.post('/mis/fee-optimizer/entities', entity);
            showToast('Entity saved', 'success');
            setEntityOpen(false);
            setEntity({ entityCode: '', entityName: '', scheme: '' });
            load();
        } catch (e) {
            showToast(e?.response?.data?.error || 'Could not save the entity', 'error');
        }
    };

    const saveRule = async () => {
        try {
            await api.post('/mis/fee-optimizer/routing-rules', rule);
            showToast('Rule added', 'success');
            setRuleOpen(false);
            setRule({ findingType: '', category: '', matchText: '', teamCode: '',
                      slaDays: '', priority: '50', note: '' });
            load();
        } catch (e) {
            showToast(e?.response?.data?.error || 'Could not add the rule', 'error');
        }
    };

    const removeRule = async (row) => {
        const ok = await confirmDialog({
            title: 'Remove this routing rule?',
            message: 'Findings already routed keep the team they have. New ones, and anything '
                   + 'you re-route, will fall through to the next matching rule.',
            confirmLabel: 'Remove rule',
            tone: 'danger',
        });
        if (!ok) return;
        try {
            await api.delete(`/mis/fee-optimizer/routing-rules/${row.rule_id}`);
            showToast('Rule removed', 'success');
            load();
        } catch (e) {
            showToast(e?.response?.data?.error || 'Could not remove the rule', 'error');
        }
    };

    const applyRouting = async (reset) => {
        if (reset) {
            const ok = await confirmDialog({
                title: 'Re-route everything?',
                message: 'Every rule-routed finding is cleared and placed again against the '
                       + 'current rules. Findings somebody assigned by hand are left alone.',
                confirmLabel: 'Re-route',
                tone: 'warning',
            });
            if (!ok) return;
        }
        setApplying(true);
        try {
            const { data } = await api.post('/mis/fee-optimizer/routing-rules/apply',
                null, { params: { reset: !!reset } });
            showToast(`${data.routed} findings routed`
                + (data.cleared ? `, ${data.cleared} cleared first` : ''), 'success');
            load();
        } catch (e) {
            showToast(e?.response?.data?.error || 'Could not apply routing', 'error');
        } finally {
            setApplying(false);
        }
    };

    return (
        <Stack gap="lg">
            <Card title="Teams"
                  subtitle="Classified by who can change the thing that caused the charge — not by who pays it. A penalty routed to Finance gets paid; routed to the team that owns the authorisation message, it gets fixed.">
                <DataTable
                    loading={loading}
                    rowKey={t => t.team_id}
                    rows={teams}
                    empty="No teams."
                    columns={[
                        { key: 'team_code', header: 'Code', mono: true },
                        { key: 'team_name', header: 'Team' },
                        { key: 'description', header: 'Owns', muted: true },
                        { key: 'default_sla_days', header: 'SLA days', align: 'right', numeric: true },
                        { key: 'open_count', header: 'Open now', align: 'right', numeric: true },
                    ]}
                />
            </Card>

            <Card
                title="Routing rules"
                subtitle="Highest priority wins; within a priority, the rule naming the most. A blank column means “any”. The text is matched against both the event code and the finding's description."
                actions={
                    <Row gap="sm">
                        <Button size="sm" loading={applying} onClick={() => applyRouting(false)}>
                            Route unrouted
                        </Button>
                        <Button size="sm" icon={Route} loading={applying}
                                onClick={() => applyRouting(true)}>
                            Re-route all
                        </Button>
                        <Button size="sm" variant="primary" icon={Plus}
                                onClick={() => setRuleOpen(true)}>Add rule</Button>
                    </Row>
                }>
                <DataTable
                    loading={loading}
                    rowKey={r => r.rule_id}
                    rows={rules}
                    pageSize={15}
                    empty="No routing rules — every finding will stay unrouted."
                    columns={[
                        { key: 'priority', header: 'Pri', align: 'right', numeric: true },
                        { key: 'finding_type', header: 'Finding',
                          render: r => r.finding_type
                            ? (FINDING_LABEL[r.finding_type] || r.finding_type)
                            : <span style={{ color: 'var(--text-muted)' }}>any</span> },
                        { key: 'category', header: 'Category',
                          render: r => r.category
                            ? (CATEGORY_LABEL[r.category] || r.category)
                            : <span style={{ color: 'var(--text-muted)' }}>any</span> },
                        { key: 'match_text', header: 'Text contains', mono: true,
                          render: r => r.match_text
                            || <span style={{ color: 'var(--text-muted)' }}>any</span> },
                        { key: 'team_name', header: 'Goes to',
                          render: r => r.team_name || r.team_code },
                        { key: 'sla_days', header: 'SLA', align: 'right', numeric: true,
                          render: r => r.sla_days == null
                            ? <span style={{ color: 'var(--text-muted)' }}>team default</span>
                            : `${r.sla_days}d` },
                        { key: 'note', header: 'Why', muted: true },
                        { key: '_actions', header: '', align: 'right',
                          render: r => (
                            <Button size="sm" variant="danger-ghost" iconOnly icon={Trash2}
                                    aria-label={`Remove rule ${r.rule_id}`}
                                    onClick={() => removeRule(r)} />) },
                    ]}
                />
            </Card>

            <Modal as="form" open={ruleOpen} onClose={() => setRuleOpen(false)}
                   onSubmit={(e) => { e.preventDefault(); saveRule(); }}
                   title="Add a routing rule" size="lg"
                   footer={<>
                       <Button type="button" onClick={() => setRuleOpen(false)}>Cancel</Button>
                       <Button type="submit" variant="primary">Add rule</Button>
                   </>}>
                <Stack gap="md">
                    <Alert tone="info" title="Leave a field blank to mean “any”">
                        A rule only claims a finding when every field it does fill matches. Give it a
                        higher priority than the rule it should beat.
                    </Alert>
                    <FormGrid cols={2}>
                        <FormField label="Finding type">
                            <Select value={rule.findingType} placeholder="Any"
                                    options={[{ value: '', label: 'Any' },
                                        ...Object.entries(FINDING_LABEL)
                                            .map(([v, l]) => ({ value: v, label: l }))]}
                                    onChange={e => setRule(s => ({ ...s, findingType: e.target.value }))} />
                        </FormField>
                        <FormField label="Category">
                            <Select value={rule.category} placeholder="Any"
                                    options={[{ value: '', label: 'Any' },
                                        ...Object.entries(CATEGORY_LABEL)
                                            .map(([v, l]) => ({ value: v, label: l }))]}
                                    onChange={e => setRule(s => ({ ...s, category: e.target.value }))} />
                        </FormField>
                        <FormField label="Text contains" className="ui-form-grid--span"
                                   hint="Matched against the event code and the description, e.g. “integrity”">
                            <Input value={rule.matchText}
                                   onChange={e => setRule(s => ({ ...s, matchText: e.target.value }))} />
                        </FormField>
                        <FormField label="Goes to" required>
                            <Select value={rule.teamCode} placeholder="Select a team…"
                                    options={teams.map(t => ({ value: t.team_code, label: t.team_name }))}
                                    onChange={e => setRule(s => ({ ...s, teamCode: e.target.value }))} />
                        </FormField>
                        <FormField label="SLA days" hint="Blank uses the team's default">
                            <Input type="number" value={rule.slaDays}
                                   onChange={e => setRule(s => ({ ...s, slaDays: e.target.value }))} />
                        </FormField>
                        <FormField label="Priority" hint="Higher wins">
                            <Input type="number" value={rule.priority}
                                   onChange={e => setRule(s => ({ ...s, priority: e.target.value }))} />
                        </FormField>
                        <FormField label="Why" hint="For whoever reads this rule next">
                            <Input value={rule.note}
                                   onChange={e => setRule(s => ({ ...s, note: e.target.value }))} />
                        </FormField>
                    </FormGrid>
                </Stack>
            </Modal>

            <Card title="Classification overrides"
                  subtitle="How this institution reads a billing event. An override outranks the dictionary and the patterns.">
                <DataTable
                    loading={loading}
                    rowKey={r => r.override_id}
                    rows={overrides}
                    empty="No overrides. Classify an event on the Event explorer tab to add one."
                    columns={[
                        { key: 'scheme', header: 'Scheme' },
                        { key: 'event_code', header: 'Code', mono: true },
                        { key: 'category', header: 'Reads as',
                          render: r => CATEGORY_LABEL[r.category] || r.category },
                        { key: 'note', header: 'Note', muted: true },
                        { key: 'created_by', header: 'By', muted: true },
                        { key: '_actions', header: '', align: 'right',
                          render: r => (
                            <Button size="sm" variant="danger-ghost" iconOnly icon={Trash2}
                                    aria-label={`Remove override for ${r.event_code}`}
                                    onClick={() => removeOverride(r)} />) },
                    ]}
                />
            </Card>

            <Card title="Billing entities"
                  subtitle="The ICAs and BIDs this institution is billed under. An entity named on an imported invoice is registered automatically."
                  actions={<Button size="sm" icon={Plus} onClick={() => setEntityOpen(true)}>
                      Add entity</Button>}>
                <DataTable
                    loading={loading}
                    rowKey={r => r.entity_id}
                    rows={entities}
                    empty="No entities yet."
                    columns={[
                        { key: 'entity_code', header: 'Code', mono: true },
                        { key: 'entity_name', header: 'Name' },
                        { key: 'scheme', header: 'Scheme', muted: true },
                        { key: 'is_active', header: 'Active',
                          render: r => <Badge tone={r.is_active ? 'success' : 'neutral'}>
                              {r.is_active ? 'Active' : 'Inactive'}</Badge> },
                    ]}
                />
            </Card>

            <Card title="Conversion rates"
                  subtitle="Indicative only. Findings always carry their value in the invoice's own currency as well, so a dispute is never argued off a converted number."
                  pad>
                <DataTable
                    rowKey={r => r.currency_code}
                    rows={filters?.currencies || []}
                    compact
                    columns={[
                        { key: 'currency_code', header: 'Currency', mono: true },
                        { key: 'usd_rate', header: 'USD per unit', align: 'right', numeric: true,
                          render: r => money(r.usd_rate, 8) },
                        { key: 'note', header: 'Basis', muted: true },
                    ]}
                />
            </Card>

            <Modal as="form" open={entityOpen} onClose={() => setEntityOpen(false)}
                   onSubmit={(e) => { e.preventDefault(); saveEntity(); }}
                   title="Add a billing entity"
                   footer={<>
                       <Button type="button" onClick={() => setEntityOpen(false)}>Cancel</Button>
                       <Button type="submit" variant="primary">Save entity</Button>
                   </>}>
                <FormGrid cols={2}>
                    <FormField label="Entity code" required hint="Exactly as it appears on the invoice">
                        <Input value={entity.entityCode}
                               onChange={e => setEntity(s => ({ ...s, entityCode: e.target.value }))} />
                    </FormField>
                    <FormField label="Name">
                        <Input value={entity.entityName}
                               onChange={e => setEntity(s => ({ ...s, entityName: e.target.value }))} />
                    </FormField>
                    <FormField label="Scheme" className="ui-form-grid--span">
                        <Select value={entity.scheme} placeholder="Both"
                                options={[{ value: '', label: 'Both' },
                                          { value: 'VISA', label: 'Visa' },
                                          { value: 'MASTERCARD', label: 'Mastercard' }]}
                                onChange={e => setEntity(s => ({ ...s, scheme: e.target.value }))} />
                    </FormField>
                </FormGrid>
            </Modal>
        </Stack>
    );
}
