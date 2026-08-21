import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { Target, Plus, Trash2, TrendingUp, Calendar, CalendarRange } from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import { createFmt } from '../../utils/formatters';
import {
    Page, Stack, Row, Card, Button, Badge, Alert, DataTable,
    FormField, FormGrid, Input, Select, useConfirm,
} from '../../components/ui';

/* ────────────────────────────────────────────────────────────────────────────
   Budget Targets
   Storage is monthly (bank_budget_target); this page adds two things on top:
     - a YEARLY entry mode that writes 12 monthly rows in one go, phased
       Equal / Seasonal (prior-year mix) / Manual (12-cell grid)
     - a YEARLY view (year selector) that shows YTD attainment against the
       elapsed-to-date target plus a run-rate projection to the full year
   The in-progress calendar month is always shown with its PACE attainment
   (actual vs. target pro-rated for days elapsed) alongside the raw one, so a
   month that isn't over yet doesn't read as "behind" just because it's early.

   Future / not-yet-ingested months (beyond the backend's `dataThrough`) are
   shown as a neutral "Upcoming" state rather than 0% / Behind — the backend
   already excludes them from YTD/run-rate math (2026-07-10 fix), and the
   yearly tile status now reflects YTD pace rather than the raw annual %
   (which would otherwise read "Behind" for the whole year until December).
   ──────────────────────────────────────────────────────────────────────────── */

const METRICS = [
    { key: 'VOLUME',      label: 'Volume',            basis: 'cardholder' },
    { key: 'BASE_VOLUME', label: 'Settlement Volume',  basis: 'settlement' },
    { key: 'NET_REVENUE', label: 'Net Margin',        basis: 'cardholder' },
    { key: 'MSF',         label: 'MSF',                basis: 'cardholder' },
    { key: 'TXNS',        label: 'Transactions',       basis: 'cardholder' },
];

const PHASING_OPTIONS = [
    { key: 'EQUAL',    label: 'Equal split',   hint: '1/12 of the annual number each month' },
    { key: 'SEASONAL', label: 'Seasonal',      hint: "Weighted by last year's monthly mix" },
    { key: 'MANUAL',   label: 'Manual grid',   hint: 'Enter each month yourself' },
];

/* Backend status → kit badge tone + the meter fill colour. */
const STATUS_META = {
    MET:      { tone: 'success', label: 'Met',      bar: 'var(--success)' },
    ON_TRACK: { tone: 'warning', label: 'On track', bar: 'var(--warning)' },
    BEHIND:   { tone: 'danger',  label: 'Behind',   bar: 'var(--danger)' },
    UPCOMING: { tone: 'neutral', label: 'Upcoming', bar: 'var(--text-muted)' },
};
const statusMeta = (status) => STATUS_META[status] || STATUS_META.BEHIND;

const MONTH_NAMES = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const now = new Date();
const currentYear = now.getFullYear();
const defaultMonthKey = currentYear * 100 + (now.getMonth() + 1);
const YEAR_OPTIONS = [currentYear - 1, currentYear, currentYear + 1];

const metricLabelOf = (key) => METRICS.find(m => m.key === key)?.label || key;
const METRIC_OPTIONS = METRICS.map(m => ({ value: m.key, label: m.label }));
const YEAR_SELECT_OPTIONS = YEAR_OPTIONS.map(y => ({ value: y, label: String(y) }));

/** Thin attainment meter. One-off layout, so it stays inline on the page. */
const Meter = ({ value, color }) => (
    <div style={{ height: 7, borderRadius: 4, background: 'var(--bg-subtle)', overflow: 'hidden' }}>
        <div
            style={{
                height: '100%',
                borderRadius: 4,
                background: color,
                width: `${Math.min(Math.max(Number(value) || 0, 0), 100)}%`,
                transition: 'width .4s ease',
            }}
        />
    </div>
);

const BudgetTargets = () => {
    const confirm = useConfirm();
    const { currencySymbol, currencyDecimals, tenantVersion } = useAuth();
    const fmt = useMemo(() => createFmt(currencySymbol, currencyDecimals), [currencySymbol, currencyDecimals]);

    const [viewMode, setViewMode] = useState('YEARLY'); // MONTHLY | YEARLY
    const [viewYear, setViewYear] = useState(currentYear);

    const [attainment, setAttainment] = useState({ rows: [], summary: [], currentMonthKey: defaultMonthKey });
    const [loading, setLoading] = useState(true);

    // Monthly entry form
    const [monthKey, setMonthKey] = useState(String(defaultMonthKey));
    const [metricType, setMetricType] = useState('VOLUME');
    const [targetValue, setTargetValue] = useState('');
    const [saving, setSaving] = useState(false);

    // Yearly entry form
    const [yEntryYear, setYEntryYear] = useState(currentYear);
    const [yMetricType, setYMetricType] = useState('VOLUME');
    const [phasing, setPhasing] = useState('EQUAL');
    const [annualTarget, setAnnualTarget] = useState('');
    const [manualValues, setManualValues] = useState(Array(12).fill(''));
    const [ySaving, setYSaving] = useState(false);

    const load = useCallback(async () => {
        setLoading(true);
        try {
            const params = viewMode === 'YEARLY' ? { year: viewYear } : {};
            const res = await api.get('/business/budget/attainment', { params });
            setAttainment(res.data || { rows: [], summary: [] });
        } catch (e) {
            console.error('attainment load failed', e);
            setAttainment({ rows: [], summary: [] });
        } finally {
            setLoading(false);
        }
    }, [viewMode, viewYear]);

    useEffect(() => { load(); }, [load, tenantVersion]);

    const formatMetric = (metric, val) =>
        metric === 'TXNS' ? fmt.number(val) : fmt.currency(val);

    const dataThroughLabel = useMemo(() => {
        if (!attainment.dataThrough) return null;
        const y = Math.floor(attainment.dataThrough / 100);
        const m = attainment.dataThrough % 100;
        if (m < 1 || m > 12) return null;
        return `${MONTH_NAMES[m - 1]} ${y}`;
    }, [attainment.dataThrough]);

    /* ── Monthly entry ── */
    const saveTarget = async (e) => {
        e?.preventDefault();
        const mk = parseInt(monthKey, 10);
        if (!mk || String(mk).length !== 6) {
            showToast('Month must be YYYYMM (e.g. 202607)', 'error');
            return;
        }
        if (targetValue === '' || Number(targetValue) < 0) {
            showToast('Target must be a non-negative number', 'error');
            return;
        }
        setSaving(true);
        try {
            await api.post('/business/budget/targets', {
                monthKey: mk, metricType, targetValue: Number(targetValue),
            });
            showToast('Target saved', 'success');
            setTargetValue('');
            load();
        } catch (err) {
            showToast(err?.response?.data?.error || 'Save failed', 'error');
        } finally {
            setSaving(false);
        }
    };

    /* ── Yearly entry ── */
    const saveYearlyTarget = async (e) => {
        e?.preventDefault();
        if (phasing === 'MANUAL') {
            if (manualValues.some(v => v === '' || Number(v) < 0)) {
                showToast('Fill in all 12 months with non-negative numbers', 'error');
                return;
            }
        } else if (annualTarget === '' || Number(annualTarget) < 0) {
            showToast('Annual target must be a non-negative number', 'error');
            return;
        }
        setYSaving(true);
        try {
            const body = {
                year: yEntryYear, metricType: yMetricType, phasing,
                ...(phasing === 'MANUAL'
                    ? { monthlyValues: manualValues.map(Number) }
                    : { annualTarget: Number(annualTarget) }),
            };
            const res = await api.post('/business/budget/targets/yearly', body);
            const fallbackNote = res.data?.phasingFallback
                ? '. Prior-year data was incomplete, so an equal split was used instead' : '';
            showToast(
                `Annual target saved across 12 months${fallbackNote}`,
                res.data?.phasingFallback ? 'warning' : 'success'
            );
            setAnnualTarget('');
            setManualValues(Array(12).fill(''));
            if (viewMode === 'YEARLY' && viewYear === yEntryYear) load();
        } catch (err) {
            showToast(err?.response?.data?.error || 'Save failed', 'error');
        } finally {
            setYSaving(false);
        }
    };

    const deleteTarget = async (row) => {
        const ok = await confirm({
            title: 'Remove this target?',
            message: `The ${metricLabelOf(row.metricType)} target of ${formatMetric(row.metricType, row.targetValue)} for ${row.monthLabel} will be deleted. Attainment for that month stops being tracked until a new target is set.`,
            confirmLabel: 'Remove target',
            tone: 'danger',
        });
        if (!ok) return;
        try {
            await api.delete(`/business/budget/targets/${row.budgetId}`);
            showToast('Target removed', 'success');
            load();
        } catch {
            showToast('Delete failed', 'error');
        }
    };

    /* ── Attainment detail table ── */
    const rowColumns = [
        {
            key: 'monthLabel',
            header: 'Month',
            sortable: true,
            nowrap: true,
            render: (r) => (
                <Row>
                    <span>{r.monthLabel}</span>
                    {r.partial && (
                        <Badge
                            tone="brand"
                            title={`In progress, day ${r.daysElapsed} of ${r.daysInMonth}`}
                        >
                            In progress
                        </Badge>
                    )}
                </Row>
            ),
        },
        {
            key: 'metricType',
            header: 'Metric',
            sortable: true,
            render: (r) => metricLabelOf(r.metricType),
        },
        {
            key: 'targetValue',
            header: 'Target',
            align: 'right',
            numeric: true,
            sortable: true,
            render: (r) => formatMetric(r.metricType, r.targetValue),
        },
        {
            key: 'actualValue',
            header: 'Actual',
            align: 'right',
            numeric: true,
            sortable: true,
            render: (r) => (r.future || r.status === 'UPCOMING'
                ? <span className="ui-td--muted">—</span>
                : formatMetric(r.metricType, r.actualValue)),
        },
        {
            key: 'attainmentPct',
            header: 'Attainment',
            align: 'right',
            numeric: true,
            sortable: true,
            render: (r) => {
                const isUpcoming = r.future || r.status === 'UPCOMING';
                if (isUpcoming) return <span className="ui-td--muted">—</span>;
                const meta = statusMeta(r.status);
                return (
                    <>
                        <span style={{ fontWeight: 700, color: meta.bar }}>
                            {Number(r.attainmentPct).toFixed(1)}%
                        </span>
                        {r.partial && r.paceAttainmentPct !== undefined && (
                            <span
                                title="Attainment against the target pro-rated for days elapsed this month"
                                style={{ display: 'block', fontSize: '0.7rem', fontWeight: 600, color: 'var(--text-secondary)' }}
                            >
                                pace {Number(r.paceAttainmentPct).toFixed(1)}%
                            </span>
                        )}
                    </>
                );
            },
        },
        {
            key: 'variance',
            header: 'Variance',
            align: 'right',
            numeric: true,
            sortable: true,
            render: (r) => {
                const isUpcoming = r.future || r.status === 'UPCOMING';
                if (isUpcoming) return <span className="ui-td--muted">—</span>;
                const positive = Number(r.variance) >= 0;
                return (
                    <span style={{ color: positive ? 'var(--success)' : 'var(--danger)' }}>
                        {positive ? '+' : ''}{formatMetric(r.metricType, r.variance)}
                    </span>
                );
            },
        },
        {
            key: 'status',
            header: 'Status',
            align: 'center',
            sortable: true,
            render: (r) => {
                const meta = statusMeta(r.status);
                return <Badge tone={meta.tone}>{meta.label}</Badge>;
            },
        },
        {
            key: '_actions',
            header: '',
            align: 'right',
            width: 60,
            render: (r) => (
                <Button
                    variant="danger-ghost"
                    size="sm"
                    iconOnly
                    icon={Trash2}
                    onClick={() => deleteTarget(r)}
                    aria-label={`Remove the ${metricLabelOf(r.metricType)} target for ${r.monthLabel}`}
                />
            ),
        },
    ];

    /* ── Full-year roll-up (was the table footer in the MUI version) ── */
    const summaryColumns = [
        {
            key: 'metricType',
            header: 'Metric',
            render: (s) => <strong>{metricLabelOf(s.metricType)}</strong>,
        },
        {
            key: 'fullYearTarget',
            header: 'Full year target',
            align: 'right',
            numeric: true,
            render: (s) => formatMetric(s.metricType, s.fullYearTarget),
        },
        {
            key: 'actualValue',
            header: 'Actual',
            align: 'right',
            numeric: true,
            render: (s) => formatMetric(s.metricType, s.actualValue),
        },
        {
            key: 'ytdAttainmentPct',
            header: 'YTD attainment',
            align: 'right',
            numeric: true,
            render: (s) => (
                <span style={{ fontWeight: 700, color: statusMeta(s.status).bar }}>
                    {Number(s.ytdAttainmentPct).toFixed(1)}%
                </span>
            ),
        },
        {
            key: 'ytdVariance',
            header: 'YTD variance',
            align: 'right',
            numeric: true,
            render: (s) => {
                const ytdVariance = Number(s.actualValue) - Number(s.ytdTarget);
                return (
                    <span style={{ color: ytdVariance >= 0 ? 'var(--success)' : 'var(--danger)' }}>
                        {ytdVariance >= 0 ? '+' : ''}{formatMetric(s.metricType, ytdVariance)}
                    </span>
                );
            },
        },
        {
            key: 'status',
            header: 'Status',
            align: 'center',
            render: (s) => {
                const meta = statusMeta(s.status);
                return <Badge tone={meta.tone}>{meta.label}</Badge>;
            },
        },
    ];

    const isYearly = viewMode === 'YEARLY';

    return (
        <Page
            title="Budget targets"
            subtitle="Set goals monthly or by year, and track attainment against actuals."
            icon={Target}
            actions={
                <Row>
                    <Button
                        size="sm"
                        icon={Calendar}
                        variant={viewMode === 'MONTHLY' ? 'primary' : 'default'}
                        aria-pressed={viewMode === 'MONTHLY'}
                        onClick={() => setViewMode('MONTHLY')}
                    >
                        Monthly
                    </Button>
                    <Button
                        size="sm"
                        icon={CalendarRange}
                        variant={isYearly ? 'primary' : 'default'}
                        aria-pressed={isYearly}
                        onClick={() => setViewMode('YEARLY')}
                    >
                        Yearly
                    </Button>
                    {isYearly && (
                        <Select
                            value={viewYear}
                            onChange={e => setViewYear(Number(e.target.value))}
                            options={YEAR_SELECT_OPTIONS}
                            style={{ width: 110 }}
                            aria-label="Year to view"
                        />
                    )}
                </Row>
            }
        >
            <Stack gap="md">
                {/* Data-lag notice, only shown when ingestion is behind the calendar */}
                {isYearly && attainment.dataLag && dataThroughLabel && !loading && (
                    <Alert tone="warning" title="Actuals are still loading in">
                        Actuals are loaded through <strong>{dataThroughLabel}</strong>. Later months are
                        shown as Upcoming and excluded from YTD and run-rate until data lands.
                    </Alert>
                )}

                {attainment.summary?.length === 0 && !loading && (
                    <Alert tone="info">
                        {isYearly
                            ? `No targets set for ${viewYear} yet. Use "Add annual target" below to start tracking.`
                            : 'No targets set yet. Add one below to start tracking attainment.'}
                    </Alert>
                )}

                {/* Summary tiles */}
                {attainment.summary?.length > 0 && (
                    <div
                        style={{
                            display: 'grid',
                            gap: 'var(--space-lg)',
                            gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
                        }}
                    >
                        {attainment.summary.map((s) => {
                            const meta = statusMeta(s.status);
                            const pctClamped = Math.min(Number(s.attainmentPct) || 0, 100);
                            const tileYearly = isYearly && s.fullYearTarget !== undefined;
                            return (
                                <Card key={s.metricType} pad>
                                    <div className="ui-row ui-row--between" style={{ marginBottom: 10 }}>
                                        <span style={{
                                            fontSize: '0.7rem', fontWeight: 700, textTransform: 'uppercase',
                                            letterSpacing: '.05em', color: 'var(--text-secondary)',
                                        }}>
                                            {metricLabelOf(s.metricType)}
                                            <span style={{
                                                marginLeft: 6, fontWeight: 500, textTransform: 'none',
                                                color: 'var(--text-muted)',
                                            }}>
                                                ({s.basis})
                                            </span>
                                        </span>
                                        <Badge tone={meta.tone}>{meta.label}</Badge>
                                    </div>

                                    {tileYearly ? (
                                        <>
                                            <div style={{ fontSize: '0.72rem', fontWeight: 700, color: 'var(--text-secondary)' }}>
                                                YTD attainment
                                            </div>
                                            <div style={{
                                                fontSize: '1.5rem', fontWeight: 800,
                                                fontVariantNumeric: 'tabular-nums', marginBottom: 6,
                                            }}>
                                                {Number(s.ytdAttainmentPct).toFixed(1)}%
                                            </div>
                                            <Meter value={s.ytdAttainmentPct} color={meta.bar} />
                                            <div style={{ fontSize: '0.72rem', color: 'var(--text-secondary)', marginTop: 8 }}>
                                                {formatMetric(s.metricType, s.actualValue)} of{' '}
                                                {formatMetric(s.metricType, s.ytdTarget)} elapsed-to-date
                                            </div>
                                            <div style={{
                                                borderTop: '1px solid var(--border)',
                                                margin: '10px 0 8px',
                                            }} />
                                            <div style={{ fontSize: '0.72rem', color: 'var(--text-secondary)' }}>
                                                Run-rate: <strong>{formatMetric(s.metricType, s.runRateProjection)}</strong>,
                                                projecting{' '}
                                                <strong style={{
                                                    color: Number(s.projectedAttainmentPct) >= 100
                                                        ? 'var(--success)' : 'var(--warning)',
                                                }}>
                                                    {Number(s.projectedAttainmentPct).toFixed(1)}%
                                                </strong>{' '}
                                                of the {formatMetric(s.metricType, s.fullYearTarget)} annual target
                                            </div>
                                        </>
                                    ) : (
                                        <>
                                            <div style={{
                                                fontSize: '1.5rem', fontWeight: 800,
                                                fontVariantNumeric: 'tabular-nums', marginBottom: 6,
                                            }}>
                                                {Number(s.attainmentPct).toFixed(1)}%
                                            </div>
                                            <Meter value={pctClamped} color={meta.bar} />
                                            <div style={{ fontSize: '0.72rem', color: 'var(--text-secondary)', marginTop: 8 }}>
                                                {formatMetric(s.metricType, s.actualValue)} of{' '}
                                                {formatMetric(s.metricType, s.targetValue)}
                                            </div>
                                        </>
                                    )}
                                </Card>
                            );
                        })}
                    </div>
                )}

                {/* Entry forms */}
                <div
                    style={{
                        display: 'grid',
                        gap: 'var(--space-2xl)',
                        gridTemplateColumns: isYearly ? 'repeat(auto-fit, minmax(340px, 1fr))' : '1fr',
                        alignItems: 'start',
                    }}
                >
                    <Card
                        title="Add or update monthly target"
                        subtitle="One target per metric per month. Saving the same month and metric updates the existing value."
                        pad
                    >
                        <form onSubmit={saveTarget}>
                            <Stack gap="sm">
                                <FormGrid cols={3}>
                                    <FormField label="Month (YYYYMM)" required>
                                        <Input
                                            value={monthKey}
                                            onChange={e => setMonthKey(e.target.value.replace(/[^0-9]/g, '').slice(0, 6))}
                                            placeholder="202607"
                                            inputMode="numeric"
                                        />
                                    </FormField>
                                    <FormField label="Metric">
                                        <Select
                                            value={metricType}
                                            onChange={e => setMetricType(e.target.value)}
                                            options={METRIC_OPTIONS}
                                        />
                                    </FormField>
                                    <FormField label="Target value" required>
                                        <Input
                                            type="number"
                                            value={targetValue}
                                            onChange={e => setTargetValue(e.target.value)}
                                            placeholder="1000000"
                                        />
                                    </FormField>
                                </FormGrid>
                                <Row>
                                    <Button type="submit" variant="primary" icon={Plus} loading={saving}>
                                        Save target
                                    </Button>
                                </Row>
                            </Stack>
                        </form>
                    </Card>

                    {isYearly && (
                        <Card
                            title="Add annual target"
                            subtitle={`Writes 12 monthly rows. Seasonal phasing uses the ${yEntryYear - 1} actual monthly mix for this metric, and falls back to an equal split automatically if that year is incomplete.`}
                            pad
                        >
                            <form onSubmit={saveYearlyTarget}>
                                <Stack gap="sm">
                                    <FormGrid cols={4}>
                                        <FormField label="Year">
                                            <Select
                                                value={yEntryYear}
                                                onChange={e => setYEntryYear(Number(e.target.value))}
                                                options={YEAR_SELECT_OPTIONS}
                                            />
                                        </FormField>
                                        <FormField label="Metric">
                                            <Select
                                                value={yMetricType}
                                                onChange={e => setYMetricType(e.target.value)}
                                                options={METRIC_OPTIONS}
                                            />
                                        </FormField>
                                        <FormField
                                            label="Phasing"
                                            className="ui-form-grid--span"
                                            hint={PHASING_OPTIONS.find(p => p.key === phasing)?.hint}
                                        >
                                            <Select
                                                value={phasing}
                                                onChange={e => setPhasing(e.target.value)}
                                                options={PHASING_OPTIONS.map(p => ({ value: p.key, label: p.label }))}
                                            />
                                        </FormField>
                                    </FormGrid>

                                    {phasing !== 'MANUAL' ? (
                                        <FormField label="Annual target" required>
                                            <Input
                                                type="number"
                                                value={annualTarget}
                                                onChange={e => setAnnualTarget(e.target.value)}
                                                placeholder="12000000"
                                            />
                                        </FormField>
                                    ) : (
                                        <div>
                                            <p style={{
                                                fontSize: '0.75rem', color: 'var(--text-secondary)',
                                                margin: '0 0 var(--space-sm)',
                                            }}>
                                                Enter each month target directly.
                                            </p>
                                            <FormGrid cols={4}>
                                                {MONTH_NAMES.map((mn, i) => (
                                                    <FormField key={mn} label={mn}>
                                                        <Input
                                                            type="number"
                                                            value={manualValues[i]}
                                                            onChange={e => {
                                                                const next = [...manualValues];
                                                                next[i] = e.target.value;
                                                                setManualValues(next);
                                                            }}
                                                        />
                                                    </FormField>
                                                ))}
                                            </FormGrid>
                                        </div>
                                    )}

                                    <Row>
                                        <Button type="submit" variant="primary" icon={CalendarRange} loading={ySaving}>
                                            Save annual target
                                        </Button>
                                    </Row>
                                </Stack>
                            </form>
                        </Card>
                    )}
                </div>

                {/* Attainment detail */}
                <Card
                    title={
                        <span className="ui-row" style={{ gap: 8 }}>
                            <TrendingUp size={16} /> {isYearly ? `${viewYear} monthly attainment` : 'Monthly attainment'}
                        </span>
                    }
                >
                    <DataTable
                        columns={rowColumns}
                        rows={attainment.rows || []}
                        rowKey={(r) => r.budgetId}
                        loading={loading}
                        rowClassName={(r) =>
                            (r.future || r.status === 'UPCOMING') ? 'ui-tr--dimmed'
                            : r.partial ? 'ui-tr--current'
                            : null
                        }
                        empty={
                            <div style={{
                                padding: 'var(--space-3xl)', textAlign: 'center',
                                color: 'var(--text-secondary)', fontSize: '0.85rem',
                            }}>
                                No targets in the selected range.
                            </div>
                        }
                    />
                </Card>

                {/* Full-year roll-up */}
                {isYearly && attainment.summary?.length > 0 && (
                    <Card title={`${viewYear} full year roll-up`} subtitle="Year totals against YTD actuals.">
                        <DataTable
                            columns={summaryColumns}
                            rows={attainment.summary}
                            rowKey={(s) => s.metricType}
                            loading={loading}
                        />
                    </Card>
                )}
            </Stack>
        </Page>
    );
};

export default BudgetTargets;
