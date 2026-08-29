import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  DatabaseZap, Play, Eye, CheckCircle, XCircle, Clock, ChevronDown, ChevronUp,
  RefreshCw, Table2, Columns, Zap, Trash2, AlertTriangle,
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext'; // #12: Dynamic tenantId
import { showToast } from '../../contexts/ToastContext';
import {
  Page, Stack, Row, Card, Button, Badge, Alert, Tabs, DataTable,
  FormField, FormGrid, Input, Select, useConfirm,
} from '../../components/ui';

/**
 * Admin > Data Migration (SUPER_ADMIN only).
 *
 * Two separate concerns, deliberately kept on separate tabs so the
 * irreversible day-correction tool is never adjacent to the migration flow:
 *
 *  1. Bulk migration — configure a source table, run a READ-ONLY validation
 *     preview (dry run), then start the real migration. The real run is
 *     armed by an explicit step and then gated by a danger confirmation that
 *     names the tenant, the table, the month range and the row count.
 *  2. Day correction — permanent full-day delete for one tenant + one date.
 *     Armed explicitly, then gated by a danger confirmation naming the exact
 *     date and tenant. The backend also demands `confirm: true` in the body.
 */

/* Phase → badge tone + meter colour. Replaces the old hard-coded hex map. */
const phaseTone = (phase) => {
  if (!phase) return 'neutral';
  if (phase.startsWith('FAILED')) return 'danger';
  if (phase === 'COMPLETED') return 'success';
  if (phase === 'IDLE') return 'neutral';
  return 'info';
};
const TONE_COLOR = {
  neutral: 'var(--text-muted)',
  info: 'var(--brand)',
  success: 'var(--success)',
  warning: 'var(--warning)',
  danger: 'var(--danger)',
};

// All mappable columns with descriptions
const COLUMN_DEFS = [
  { key: 'mid', label: 'Merchant ID (MID)', required: true, hint: 'Unique merchant identifier' },
  { key: 'payment_date', label: 'Payment Date', required: true, hint: 'Transaction settlement date (TIMESTAMP)' },
  { key: 'txn_currency_amount', label: 'Transaction Amount', required: true, hint: 'Transaction value in txn currency' },
  { key: 'merchant_name', label: 'Merchant Name', required: false, hint: 'Auto-creates merchants if mapped' },
  { key: 'card_number', label: 'Card Number', required: false, hint: 'Masked card PAN for loyalty tracking' },
  { key: 'card_scheme', label: 'Card Scheme', required: false, hint: 'VISA, MASTERCARD, AMEX, etc.' },
  { key: 'card_type', label: 'Card Type', required: false, hint: 'DEBIT, CREDIT, PREPAID' },
  { key: 'dcc', label: 'DCC Flag', required: false, hint: 'Boolean: true/false for DCC opt-in' },
  { key: 'destination', label: 'Destination', required: false, hint: 'DOMESTIC or INTERNATIONAL' },
  { key: 'txn_currency', label: 'Txn Currency', required: false, hint: 'ISO currency code (BHD, USD, etc.)' },
  { key: 'store_base_currency', label: 'Base Currency', required: false, hint: 'Store settlement currency' },
  { key: 'store_base_currency_amount', label: 'Base Currency Amount', required: false, hint: 'Amount in base currency' },
  { key: 'msf', label: 'MSF', required: false, hint: 'Merchant Service Fee' },
  { key: 'interchange_fee', label: 'Interchange Fee', required: false, hint: 'Interchange cost' },
  { key: 'transaction_type', label: 'Transaction Type', required: false, hint: 'SALE, REFUND, etc.' },
  { key: 'transaction_date', label: 'Transaction Date/Time', required: false, hint: 'Actual swipe datetime (for heatmap hours)' },
  { key: 'arn', label: 'ARN', required: false, hint: 'Acquirer Reference Number' },
  { key: 'rrn_number', label: 'RRN', required: false, hint: 'Retrieval Reference Number' },
  { key: 'auth_code', label: 'Auth Code', required: false, hint: 'Authorization code' },
];

const SECTION_LABEL = {
  fontSize: '0.7rem',
  fontWeight: 700,
  textTransform: 'uppercase',
  letterSpacing: '0.05em',
  margin: '0 0 var(--space-md)',
};

/** Small stat tile. One-off layout, so it stays inline. */
const Stat = ({ label, value, color }) => (
  <div style={{
    padding: '12px 14px',
    borderRadius: 'var(--radius-md)',
    background: 'var(--bg-muted)',
    border: '1px solid var(--border)',
    textAlign: 'center',
  }}>
    <div style={{
      fontSize: '0.68rem', color: 'var(--text-secondary)',
      textTransform: 'uppercase', fontWeight: 600, letterSpacing: '0.04em',
    }}>
      {label}
    </div>
    <div style={{
      fontSize: '1.35rem', fontWeight: 700, marginTop: 2,
      fontVariantNumeric: 'tabular-nums', color: color || 'var(--text)',
    }}>
      {value}
    </div>
  </div>
);

const Meter = ({ pct, color }) => (
  <div style={{ height: 10, borderRadius: 5, background: 'var(--bg-subtle)', overflow: 'hidden' }}>
    <div style={{
      height: '100%', borderRadius: 5, background: color,
      width: `${Math.min(Math.max(pct, 0), 100)}%`, transition: 'width .5s ease',
    }} />
  </div>
);

// ═══════════════════════════════════════════════════════════════
//  STEP 1: SOURCE TABLE CONFIGURATION  (read-only validation only)
// ═══════════════════════════════════════════════════════════════
const ConfigureStep = ({ config, setConfig, onDryRun, dryRunResult, dryRunLoading }) => {
  const [showOptional, setShowOptional] = useState(false);
  const requiredCols = COLUMN_DEFS.filter(c => c.required);
  const optionalCols = COLUMN_DEFS.filter(c => !c.required);

  const updateMapping = (key, value) => {
    setConfig(prev => ({
      ...prev,
      columnMapping: { ...prev.columnMapping, [key]: value || undefined }
    }));
  };

  // Remove undefined entries
  const cleanMapping = (m) => {
    const cleaned = {};
    Object.entries(m || {}).forEach(([k, v]) => { if (v && v.trim()) cleaned[k] = v.trim(); });
    return cleaned;
  };

  // Source columns from dry-run for autocomplete
  const sourceCols = (dryRunResult?.columns || []).map(c => c.column_name);
  const sampleRows = dryRunResult?.sampleRows || [];
  const sampleColumns = sampleRows.length > 0
    ? Object.keys(sampleRows[0]).map(k => ({
      key: k,
      header: k,
      nowrap: true,
      render: (row) => (row[k] === null || row[k] === undefined
        ? <span className="ui-td--muted">NULL</span>
        : String(row[k])),
    }))
    : [];

  const mappingControl = (col, placeholderOption) => (
    sourceCols.length > 0 ? (
      <Select
        value={config.columnMapping[col.key] || ''}
        onChange={e => updateMapping(col.key, e.target.value)}
        placeholder={placeholderOption}
        options={sourceCols}
      />
    ) : (
      <Input
        value={config.columnMapping[col.key] || ''}
        onChange={e => updateMapping(col.key, e.target.value)}
        placeholder={col.key}
      />
    )
  );

  return (
    <Stack gap="md">
      {/* Source table */}
      <Card
        title={<span className="ui-row" style={{ gap: 8 }}><Table2 size={16} /> Source table</span>}
        subtitle="Must be in the same PostgreSQL database."
        pad
      >
        <Stack gap="sm">
          <FormGrid cols={4}>
            <FormField label="Table name" required className="ui-form-grid--span">
              <Input
                value={config.sourceTable}
                onChange={e => setConfig({ ...config, sourceTable: e.target.value })}
                placeholder="legacy_transactions"
              />
            </FormField>
            <FormField label="Start month" required>
              <Input
                type="month"
                value={config.startMonth}
                onChange={e => setConfig({ ...config, startMonth: e.target.value })}
              />
            </FormField>
            <FormField label="End month" required>
              <Input
                type="month"
                value={config.endMonth}
                onChange={e => setConfig({ ...config, endMonth: e.target.value })}
              />
            </FormField>
          </FormGrid>

          <Row>
            <Button
              icon={Eye}
              loading={dryRunLoading}
              disabled={dryRunLoading || !config.sourceTable}
              onClick={() => onDryRun(cleanMapping(config.columnMapping))}
            >
              Validate and preview
            </Button>
            <span style={{ fontSize: '0.75rem', color: 'var(--text-secondary)' }}>
              Read only. Nothing is written and no data is changed.
            </span>
          </Row>
        </Stack>
      </Card>

      {/* Dry-run result */}
      {dryRunResult && (
        dryRunResult.error ? (
          <Alert tone="danger" title="Validation failed">{dryRunResult.error}</Alert>
        ) : (
          <Card
            title={
              <span className="ui-row" style={{ gap: 8, color: 'var(--success)' }}>
                <CheckCircle size={16} /> Table found, {(dryRunResult.totalRows || 0).toLocaleString()} rows
              </span>
            }
            subtitle="Preview only. No rows have been migrated."
            pad
          >
            <Stack gap="sm">
              <div style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))',
                gap: 'var(--space-md)',
              }}>
                <Stat label="Total rows" value={(dryRunResult.totalRows || 0).toLocaleString()} />
                <Stat
                  label="Date range"
                  value={
                    <span style={{ fontSize: '0.85rem' }}>
                      {dryRunResult.dateRange?.min_date ? new Date(dryRunResult.dateRange.min_date).toLocaleDateString() : '—'}
                      {' → '}
                      {dryRunResult.dateRange?.max_date ? new Date(dryRunResult.dateRange.max_date).toLocaleDateString() : '—'}
                    </span>
                  }
                />
                <Stat label="Columns" value={(dryRunResult.columns || []).length} />
              </div>

              {/* Available columns */}
              <div>
                <p style={{ ...SECTION_LABEL, color: 'var(--text-secondary)' }}>Available columns</p>
                <div className="ui-row" style={{ gap: 4 }}>
                  {sourceCols.map(c => <Badge key={c} mono>{c}</Badge>)}
                </div>
              </div>

              {/* Sample data */}
              {sampleColumns.length > 0 && (
                <div style={{
                  border: '1px solid var(--border)',
                  borderRadius: 'var(--radius-md)',
                  overflow: 'hidden',
                  maxHeight: 260,
                  overflowY: 'auto',
                }}>
                  <DataTable
                    columns={sampleColumns}
                    rows={sampleRows}
                    rowKey={(_row, i) => i}
                    loading={dryRunLoading}
                    compact
                    stickyHeader
                  />
                </div>
              )}
            </Stack>
          </Card>
        )
      )}

      {/* Column mapping */}
      <Card
        title={<span className="ui-row" style={{ gap: 8 }}><Columns size={16} /> Column mapping</span>}
        subtitle="Map your source table columns to Acquira fields. Only 3 fields are required, everything else has safe defaults."
        pad
      >
        <Stack gap="md">
          <div>
            <p style={{ ...SECTION_LABEL, color: 'var(--danger)' }}>Required fields</p>
            <FormGrid cols={3}>
              {requiredCols.map(col => (
                <FormField key={col.key} label={col.label} required hint={col.hint}>
                  {mappingControl(col, 'Select column')}
                </FormField>
              ))}
            </FormGrid>
          </div>

          <div>
            <Button
              variant="subtle"
              size="sm"
              icon={showOptional ? ChevronUp : ChevronDown}
              onClick={() => setShowOptional(!showOptional)}
            >
              {showOptional ? 'Hide' : 'Show'} optional fields ({optionalCols.length})
            </Button>

            {showOptional && (
              <div style={{ marginTop: 'var(--space-lg)' }}>
                <FormGrid cols={3}>
                  {optionalCols.map(col => (
                    <FormField key={col.key} label={col.label} hint={col.hint}>
                      {mappingControl(col, 'Not mapped (default)')}
                    </FormField>
                  ))}
                </FormGrid>
              </div>
            )}
          </div>
        </Stack>
      </Card>
    </Stack>
  );
};

// ═══════════════════════════════════════════════════════════════
//  STEP 2: MIGRATION PROGRESS MONITOR
// ═══════════════════════════════════════════════════════════════
const ProgressMonitor = ({ progress, onRefresh, refreshing }) => {
  if (!progress) return null;

  const pct = progress.totalMonths > 0 ? Math.round((progress.completedMonths / progress.totalMonths) * 100) : 0;
  const isRunning = progress.phase && !['IDLE', 'COMPLETED'].includes(progress.phase) && !progress.phase.startsWith('FAILED');
  const isComplete = progress.phase === 'COMPLETED';
  const isFailed = progress.phase?.startsWith('FAILED');

  const tone = phaseTone(progress.phase);
  const color = TONE_COLOR[tone];

  const formatTime = (sec) => {
    if (!sec || sec <= 0) return '—';
    if (sec < 60) return `${sec}s`;
    if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`;
    return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`;
  };

  const StatusIcon = isComplete ? CheckCircle : isFailed ? XCircle : isRunning ? Zap : Clock;

  return (
    <Card
      title={
        <span className="ui-row" style={{ gap: 8, color }}>
          <StatusIcon size={17} />
          {isComplete ? 'Migration complete'
            : isFailed ? 'Migration failed'
              : isRunning ? 'Migration in progress' : 'Idle'}
        </span>
      }
      actions={
        <Button icon={RefreshCw} size="sm" onClick={onRefresh} loading={refreshing}>Refresh</Button>
      }
      pad
    >
      <Stack gap="sm">
        <Row>
          <span style={{ fontSize: '0.78rem', color: 'var(--text-secondary)' }}>Phase</span>
          <Badge tone={tone}>{progress.phase || 'IDLE'}</Badge>
          {progress.currentMonth && (
            <span style={{ fontSize: '0.78rem', color: 'var(--text-secondary)' }}>
              Month <strong>{progress.currentMonth}</strong>
            </span>
          )}
        </Row>

        <div>
          <div className="ui-row ui-row--between" style={{ marginBottom: 6 }}>
            <span style={{ fontSize: '0.8rem', fontWeight: 600 }}>
              {progress.completedMonths} / {progress.totalMonths} months
            </span>
            <span style={{ fontSize: '0.8rem', fontWeight: 700, color }}>{pct}%</span>
          </div>
          <Meter pct={pct} color={color} />
        </div>

        <div style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
          gap: 'var(--space-md)',
        }}>
          <Stat label="Rows migrated" value={(progress.totalRowsMigrated || 0).toLocaleString()} color="var(--success)" />
          <Stat label="Months done" value={progress.completedMonths || 0} color="var(--brand)" />
          <Stat label="Elapsed" value={formatTime(progress.elapsedSeconds)} />
          <Stat label="ETA remaining" value={formatTime(progress.estimatedRemainingSeconds)} color="var(--warning)" />
        </div>

        {isFailed && (
          <Alert tone="danger" title="Failure detail">
            <span style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace', fontSize: '0.78rem' }}>
              {progress.phase.replace('FAILED: ', '')}
            </span>
          </Alert>
        )}
      </Stack>
    </Card>
  );
};

// ═══════════════════════════════════════════════════════════════
//  DAY CORRECTION — irreversible, super-admin only
// ═══════════════════════════════════════════════════════════════
const DeleteDayPanel = ({ activeTenantId }) => {
  const confirmDialog = useConfirm();
  const [date, setDate] = useState('');
  const [armed, setArmed] = useState(false);          // guard 2: explicit arming step
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);

  const reset = () => { setArmed(false); setResult(null); };

  const handleDelete = async () => {
    // Guard 3: danger confirmation naming the exact day and tenant.
    const ok = await confirmDialog({
      title: `Permanently delete every transaction dated ${date}?`,
      message: `All AMS and CMM transactions for ${date} in tenant ${activeTenantId || 'unknown'} will be deleted, and every summary for that day is cleared so dashboards show it as empty. Monthly totals are rebuilt from the remaining days. This cannot be undone. Re-upload that day's file to restore it.`,
      confirmLabel: `Delete ${date}`,
      tone: 'danger',
    });
    if (!ok) return;

    setLoading(true);
    setResult(null);
    if (!activeTenantId) {
      setResult({ error: 'No active tenant. Please re-login or pick a tenant.' });
      setLoading(false); setArmed(false);
      return;
    }
    try {
      const res = await api.post('/admin/migration/delete-day', {
        tenantId: activeTenantId,
        date,
        confirm: true
      });
      setResult(res.data);
    } catch (e) {
      setResult({ error: e.response?.data?.error || e.message || 'Delete failed' });
    } finally { setLoading(false); setArmed(false); }
  };

  const removedRows = Object.entries(result?.removed || {}).map(([table, count]) => ({
    table, count: String(count),
  }));

  return (
    <Stack gap="md">
      <Alert tone="danger" title="Irreversible operation">
        Super-admin only. Deleting a day permanently removes <strong>all</strong> transactions
        (AMS and CMM) for the selected date in the current tenant, and clears every summary so
        dashboards show the day as empty. The monthly totals are rebuilt from the remaining days.
        Re-upload that day's file to restore it.
      </Alert>

      <Card
        title={<span className="ui-row" style={{ gap: 8, color: 'var(--danger)' }}><Trash2 size={16} /> Delete a day</span>}
        subtitle={`Target tenant: ${activeTenantId || 'none selected'}`}
        pad
      >
        <Stack gap="sm">
          <FormGrid cols={4}>
            <FormField label="Date to delete" required hint="Future dates are not selectable.">
              <Input
                type="date"
                value={date}
                max={new Date().toISOString().slice(0, 10)}
                onChange={e => { setDate(e.target.value); reset(); }}
              />
            </FormField>
          </FormGrid>

          {/* Guard 1: nothing is clickable until a date is chosen. */}
          {!armed ? (
            <Row>
              <Button variant="danger" icon={Trash2} disabled={!date} onClick={() => setArmed(true)}>
                Delete this day
              </Button>
            </Row>
          ) : (
            <Row>
              <AlertTriangle size={16} style={{ color: 'var(--danger)', flexShrink: 0 }} />
              <span style={{ fontSize: '0.82rem', color: 'var(--danger)', fontWeight: 600 }}>
                Permanently delete every transaction dated {date}? This cannot be undone.
              </span>
              <Button variant="danger" icon={Trash2} loading={loading} onClick={handleDelete}>
                Yes, delete {date}
              </Button>
              <Button onClick={() => setArmed(false)}>Cancel</Button>
            </Row>
          )}

          {result && (
            result.error ? (
              <Alert tone="danger" title="Delete failed">{result.error}</Alert>
            ) : (
              <Stack gap="sm">
                <Alert tone="success" title={`Deleted ${result.date}`}>
                  Dashboards now show this day as empty.
                </Alert>
                <Card>
                  <DataTable
                    columns={[
                      { key: 'table', header: 'Table', mono: true },
                      { key: 'count', header: 'Rows removed', align: 'right', numeric: true },
                    ]}
                    rows={removedRows}
                    rowKey={(r) => r.table}
                    loading={loading}
                    compact
                  />
                </Card>
              </Stack>
            )
          )}
        </Stack>
      </Card>
    </Stack>
  );
};

// ═══════════════════════════════════════════════════════════════
//  REBUILD SUMMARIES — re-derive all summaries from fact_transaction
// ═══════════════════════════════════════════════════════════════
const RebuildSummariesPanel = ({ activeTenantId, progress, onRefresh, refreshing, onStart, starting, isRunning }) => {
  const confirmDialog = useConfirm();
  const [dates, setDates] = useState({ start: '', end: '' });
  const [armed, setArmed] = useState(false);
  // Re-price fact from the current rate cards before rebuilding — needed after
  // an interchange/scheme rate-card change (plain rebuild only re-sums the fees
  // already on fact and so can't see a rate change).
  const [reprice, setReprice] = useState(false);

  // Summaries are rebuilt a whole month at a time (monthly rollups have to be
  // re-derived from every day in the month), so a picked date widens to the
  // month that contains it. The label below says so explicitly.
  const monthOf = (d) => (d ? d.slice(0, 7) : '');

  const rangeLabel = (dates.start || dates.end)
    ? `${monthOf(dates.start) || 'the first transaction'} → ${monthOf(dates.end) || 'the last transaction'}`
    : 'the full transaction history';

  const handleStart = async () => {
    if (dates.start && dates.end && dates.start > dates.end) {
      showToast('Start date must be before or equal to end date.', 'error');
      setArmed(false);
      return;
    }
    // Danger confirmation naming the tenant and the exact range.
    const ok = await confirmDialog({
      title: reprice ? 'Re-price transactions and rebuild summaries now?' : 'Rebuild every summary table now?',
      message: reprice
        ? `fact_transaction in tenant ${activeTenantId || 'unknown'} will be RE-PRICED from the current rate cards (interchange, scheme, e-com fees are recomputed and overwritten) for ${rangeLabel}, then all 13 summary tables and the dashboard metrics are rebuilt from the new fees. This modifies transaction fees and WIPES any interchange normalization on those months. Only the months whose payment dates fall inside a rate's effective window will change.`
        : `All 13 summary tables and the dashboard metrics in tenant ${activeTenantId || 'unknown'} will be deleted and recalculated from fact_transaction for ${rangeLabel}. Transactions themselves are not modified. Dashboards may show partial numbers while the rebuild is running.`,
      confirmLabel: reprice ? 'Re-price and rebuild' : 'Rebuild summaries',
      tone: 'danger',
    });
    setArmed(false);
    if (!ok) return;
    onStart({ start: monthOf(dates.start), end: monthOf(dates.end), reprice });
  };

  return (
    <Stack gap="md">
      <Alert tone="info" title="How it works">
        Recalculates every summary table (<code>sum_daily_*</code>, <code>sum_monthly_*</code>) and
        the dashboard metrics directly from <code>fact_transaction</code> for the current tenant,
        month by month. Pick the start and end dates you want covered — because the monthly
        rollups are re-derived from every day in a month, the rebuild always runs over the whole
        months containing those dates. Nothing is ingested and no transactions are changed — use this after
        correcting transaction data directly in the database, so every dashboard replicates the
        change end to end without re-uploading files.
      </Alert>

      {progress && progress.phase !== 'IDLE' && (
        <ProgressMonitor progress={progress} onRefresh={onRefresh} refreshing={refreshing} />
      )}

      <Card
        title={<span className="ui-row" style={{ gap: 8 }}><RefreshCw size={16} /> Rebuild summaries</span>}
        subtitle={`Target tenant: ${activeTenantId || 'none selected'}`}
        pad
      >
        <Stack gap="sm">
          <FormGrid cols={4}>
            <FormField label="Start date" hint="Leave blank to start from the first transaction.">
              <Input
                type="date"
                value={dates.start}
                max={new Date().toISOString().slice(0, 10)}
                onChange={e => { const v = e.target.value; setDates(d => ({ ...d, start: v })); setArmed(false); }}
              />
            </FormField>
            <FormField label="End date" hint="Leave blank to end at the last transaction.">
              <Input
                type="date"
                value={dates.end}
                max={new Date().toISOString().slice(0, 10)}
                onChange={e => { const v = e.target.value; setDates(d => ({ ...d, end: v })); setArmed(false); }}
              />
            </FormField>
          </FormGrid>

          {(dates.start || dates.end) && (
            <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
              Rebuilds whole months: <strong>{rangeLabel}</strong>
            </span>
          )}

          {/* Re-price toggle — off by default (plain rebuild is the safe path). */}
          <label className="ui-row" style={{ gap: 8, alignItems: 'flex-start', cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={reprice}
              disabled={isRunning || starting}
              onChange={e => { setReprice(e.target.checked); setArmed(false); }}
              style={{ marginTop: 3, flexShrink: 0 }}
            />
            <span style={{ fontSize: '0.82rem', color: 'var(--text)' }}>
              <strong>Re-price transactions from the current rate cards first</strong>
              <span style={{ display: 'block', color: 'var(--text-muted)', marginTop: 2 }}>
                Turn this on after changing an interchange or scheme rate. It recomputes and
                overwrites the interchange, scheme and e-com fees on <code>fact_transaction</code>{' '}
                before rebuilding — a plain rebuild only re-sums the existing fees and cannot see a
                rate change. Only months inside the rate's effective window change, and any
                interchange normalization on those months is wiped.
              </span>
            </span>
          </label>

          {/* Guard 1: explicit arming step before the danger dialog. */}
          {!armed ? (
            <Row>
              <Button variant="danger" icon={RefreshCw} disabled={isRunning || starting} onClick={() => setArmed(true)}>
                {reprice ? 'Re-price and rebuild' : 'Rebuild summaries'}
              </Button>
              {isRunning && <Badge tone="warning" dot>A migration or rebuild is already running</Badge>}
            </Row>
          ) : (
            <Row>
              <AlertTriangle size={16} style={{ color: 'var(--danger)', flexShrink: 0 }} />
              <span style={{ fontSize: '0.82rem', color: 'var(--danger)', fontWeight: 600 }}>
                {reprice
                  ? `Re-price fact and recalculate ${rangeLabel} for tenant ${activeTenantId || 'unknown'}?`
                  : `Recalculate ${rangeLabel} for tenant ${activeTenantId || 'unknown'}?`}
              </span>
              <Button variant="danger" icon={Play} loading={starting} onClick={handleStart}>
                {reprice ? 'Yes, re-price and rebuild now' : 'Yes, rebuild now'}
              </Button>
              <Button onClick={() => setArmed(false)}>Cancel</Button>
            </Row>
          )}
        </Stack>
      </Card>
    </Stack>
  );
};

// ═══════════════════════════════════════════════════════════════
//  MAIN COMPONENT
// ═══════════════════════════════════════════════════════════════
const DataMigration = () => {
  const { activeTenantId } = useAuth(); // #12: Dynamic tenantId from auth context
  const confirmDialog = useConfirm();
  const [tab, setTab] = useState('migration');

  // Dynamic month defaults: roughly 2 years back to last completed month.
  // Was hardcoded '2024-01' → '2025-12' which is in the past as of mid-2026 and
  // would force users to update them every time they came to the screen.
  const _defaultMonths = (() => {
    const now = new Date();
    const lastFull = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    const start = new Date(lastFull.getFullYear() - 2, lastFull.getMonth(), 1);
    const ymOf = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    return { start: ymOf(start), end: ymOf(lastFull) };
  })();

  const [config, setConfig] = useState({
    sourceTable: '',
    startMonth: _defaultMonths.start,
    endMonth: _defaultMonths.end,
    columnMapping: { mid: 'mid', payment_date: 'payment_date', txn_currency_amount: 'txn_currency_amount' }
  });
  const [dryRunResult, setDryRunResult] = useState(null);
  const [dryRunLoading, setDryRunLoading] = useState(false);
  const [progress, setProgress] = useState(null);
  const [progressLoading, setProgressLoading] = useState(true);
  const [starting, setStarting] = useState(false);
  const [confirmStart, setConfirmStart] = useState(false);
  const pollRef = useRef(null);

  // Poll progress
  const fetchProgress = useCallback(async () => {
    setProgressLoading(true);
    try {
      const res = await api.get('/admin/migration/progress');
      setProgress(res.data);
      // Stop polling if done
      if (res.data.phase === 'COMPLETED' || res.data.phase?.startsWith('FAILED') || res.data.phase === 'IDLE') {
        if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null; }
      }
    } catch (e) { console.error('Progress fetch error', e); }
    finally { setProgressLoading(false); }
  }, []);

  // Initial progress check
  useEffect(() => { fetchProgress(); return () => { if (pollRef.current) clearInterval(pollRef.current); }; }, [fetchProgress]);

  // Dry run — READ ONLY. Validates the source table and previews sample rows.
  const handleDryRun = async (cleanedMapping) => {
    setDryRunLoading(true);
    setDryRunResult(null);
    if (!activeTenantId) {
      setDryRunResult({ error: 'No active tenant. Please re-login or pick a tenant.' });
      setDryRunLoading(false);
      return;
    }
    try {
      const res = await api.post('/admin/migration/dry-run', {
        tenantId: activeTenantId,
        sourceTable: config.sourceTable,
        columnMapping: cleanedMapping
      });
      setDryRunResult(res.data);
    } catch (e) {
      setDryRunResult({ error: e.response?.data?.error || e.message || 'Dry run failed' });
    } finally { setDryRunLoading(false); }
  };

  // Start migration — the real, irreversible run.
  const handleStart = async () => {
    if (!activeTenantId) {
      showToast('No active tenant. Please re-login or pick a tenant.', 'error');
      setConfirmStart(false);
      return;
    }

    // Guard 3: danger confirmation naming tenant, table, month range and rows.
    const rowsNote = (dryRunResult && !dryRunResult.error && dryRunResult.totalRows != null)
      ? `Validation last reported ${Number(dryRunResult.totalRows).toLocaleString()} rows in this table.`
      : 'This table has not been validated with a dry run yet, so the row count is unknown.';
    const ok = await confirmDialog({
      title: 'Start the real migration now?',
      message: `This is not a dry run. Every month from ${config.startMonth} to ${config.endMonth} will be read from "${config.sourceTable}" and written into tenant ${activeTenantId}, replacing any existing data for those months and rebuilding all 13 summary tables. ${rowsNote} The write cannot be undone.`,
      confirmLabel: 'Start real migration',
      tone: 'danger',
    });
    if (!ok) return;

    setStarting(true);
    setConfirmStart(false);
    try {
      // Clean mapping — remove empty values
      const cleaned = {};
      Object.entries(config.columnMapping).forEach(([k, v]) => { if (v && v.trim()) cleaned[k] = v.trim(); });

      await api.post('/admin/migration/start', {
        tenantId: activeTenantId,
        sourceTable: config.sourceTable,
        startMonth: config.startMonth,
        endMonth: config.endMonth,
        columnMapping: cleaned
      });
      // Start polling
      if (pollRef.current) clearInterval(pollRef.current);
      pollRef.current = setInterval(fetchProgress, 5000);
      setTimeout(fetchProgress, 1000); // Quick first fetch
    } catch (e) {
      showToast('Failed to start migration: ' + (e.response?.data?.error || e.message), 'error');
    } finally { setStarting(false); }
  };

  // Start summary rebuild — recalculates all summaries from fact_transaction.
  // The backend takes the tenant from X-Tenant-Id (the shared api client sends
  // it), so no tenantId goes in the body.
  const [rebuildStarting, setRebuildStarting] = useState(false);
  const handleRebuildStart = async ({ start, end, reprice }) => {
    if (!activeTenantId) {
      showToast('No active tenant. Please re-login or pick a tenant.', 'error');
      return;
    }
    if (start && end && start > end) {
      showToast('Start month must be before or equal to end month.', 'error');
      return;
    }
    setRebuildStarting(true);
    try {
      const body = { confirm: true };
      if (start) body.startMonth = start;
      if (end) body.endMonth = end;
      if (reprice) body.reprice = true;
      await api.post('/admin/migration/rebuild-summaries', body);
      if (pollRef.current) clearInterval(pollRef.current);
      pollRef.current = setInterval(fetchProgress, 5000);
      setTimeout(fetchProgress, 1000); // Quick first fetch
    } catch (e) {
      showToast('Failed to start rebuild: ' + (e.response?.data?.error || e.message), 'error');
    } finally { setRebuildStarting(false); }
  };

  const isRunning = progress?.phase && !['IDLE', 'COMPLETED'].includes(progress.phase) && !progress.phase?.startsWith('FAILED');
  const canStart = config.sourceTable && config.startMonth && config.endMonth
    && config.columnMapping.mid && config.columnMapping.payment_date && config.columnMapping.txn_currency_amount
    && !isRunning;

  const mappedCount = Object.values(config.columnMapping).filter(v => v && v.trim()).length;

  const tabs = [
    { key: 'migration', label: 'Bulk migration', icon: DatabaseZap },
    { key: 'rebuild', label: 'Rebuild summaries', icon: RefreshCw },
    { key: 'day', label: 'Day correction', icon: Trash2 },
  ];

  return (
    <Page
      title="Data migration"
      subtitle="Migrate historical transaction data from an existing database table into the Acquira platform. Bypasses CSV upload and processes millions of rows via bulk SQL, month by month."
      icon={DatabaseZap}
    >
      <Tabs tabs={tabs} active={tab} onChange={setTab} />

      {tab === 'migration' && (
        <Stack gap="md">
          <Alert tone="info" title="How it works">
            Your source table must be in the same PostgreSQL database. The migration reads directly
            via <code>INSERT...SELECT</code>, so there are no files and no network transfer. For each
            month it inserts into <code>fact_transaction</code>, populates all 13 summary tables, and
            calculates dashboard metrics. Safe to re-run: existing data for each month is replaced.
          </Alert>

          {/* Progress (if running or completed) */}
          {progress && progress.phase !== 'IDLE' && (
            <ProgressMonitor progress={progress} onRefresh={fetchProgress} refreshing={progressLoading} />
          )}

          {/* Configuration + read-only validation */}
          <ConfigureStep
            config={config}
            setConfig={setConfig}
            onDryRun={handleDryRun}
            dryRunResult={dryRunResult}
            dryRunLoading={dryRunLoading}
          />

          {/* Start the real migration — deliberately kept in its own card,
              well away from the read-only "Validate and preview" action. */}
          <Card
            title={<span className="ui-row" style={{ gap: 8 }}><Zap size={16} /> Run the real migration</span>}
            subtitle="This writes live data into the tenant. It is not a dry run."
            pad
          >
            <Stack gap="sm">
              <Alert tone="warning" title="This step is irreversible">
                Data for every month in the range is replaced in the target tenant. Run
                &quot;Validate and preview&quot; first if you have not already.
              </Alert>

              <div className="ui-row ui-row--between">
                <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
                  <div><strong>Tenant:</strong> {activeTenantId || 'none selected'}</div>
                  <div>
                    <strong>Source:</strong> {config.sourceTable || '(no table)'} ·{' '}
                    {config.startMonth || '?'} → {config.endMonth || '?'} · {mappedCount} columns mapped
                  </div>
                </div>

                <Row>
                  {isRunning && <Badge tone="warning" dot>Migration in progress</Badge>}

                  {/* Guard 1: disabled until required mapping + range are set. */}
                  {!confirmStart ? (
                    <Button variant="danger" icon={Zap} disabled={!canStart} onClick={() => setConfirmStart(true)}>
                      Start migration
                    </Button>
                  ) : (
                    /* Guard 2: explicit arming step, then the danger dialog. */
                    <Row>
                      <AlertTriangle size={16} style={{ color: 'var(--danger)', flexShrink: 0 }} />
                      <span style={{ fontSize: '0.82rem', color: 'var(--danger)', fontWeight: 600 }}>
                        Are you sure? This writes live data.
                      </span>
                      <Button variant="danger" icon={Play} loading={starting} onClick={handleStart}>
                        Yes, start now
                      </Button>
                      <Button onClick={() => setConfirmStart(false)}>Cancel</Button>
                    </Row>
                  )}
                </Row>
              </div>
            </Stack>
          </Card>
        </Stack>
      )}

      {tab === 'rebuild' && (
        <RebuildSummariesPanel
          activeTenantId={activeTenantId}
          progress={progress}
          onRefresh={fetchProgress}
          refreshing={progressLoading}
          onStart={handleRebuildStart}
          starting={rebuildStarting}
          isRunning={isRunning}
        />
      )}

      {tab === 'day' && <DeleteDayPanel activeTenantId={activeTenantId} />}
    </Page>
  );
};

export default DataMigration;
