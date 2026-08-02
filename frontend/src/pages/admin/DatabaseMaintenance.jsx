import React, { useState, useEffect, useCallback } from 'react';
import { Database, Save, Play, Clock, Activity, RefreshCw } from 'lucide-react';
import api from '../../api/axios';
import { showToast } from '../../contexts/ToastContext';
import {
  Page, Stack, Card, Button, Badge, StatusBadge, Alert, DataTable,
  FormField, Textarea, Select, Switch,
} from '../../components/ui';

/**
 * Admin > Database maintenance.
 *
 * Nightly VACUUM (ANALYZE) over the high-churn tables. The scheduler refuses to
 * run while a batch job is active; "Run now" ignores the window but keeps that
 * guard. Config lives behind /admin/maintenance/{status,config,run}.
 */

const HOURS = Array.from({ length: 24 }, (_, i) => i);
const hourLabel = (h) => `${String(h).padStart(2, '0')}:00`;
const HOUR_OPTIONS = HOURS.map((h) => ({ value: h, label: hourLabel(h) }));

const fmtTs = (s) => (s ? new Date(s).toLocaleString() : '—');

const DatabaseMaintenance = () => {
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [running, setRunning] = useState(false);

  // editable form state
  const [enabled, setEnabled] = useState(true);
  const [startHour, setStartHour] = useState(2);
  const [endHour, setEndHour] = useState(5);
  const [useDefaultTables, setUseDefaultTables] = useState(true);
  const [tablesText, setTablesText] = useState('');

  const applyStatus = (d) => {
    setStatus(d);
    setEnabled(!!d.enabled);
    setStartHour(d.windowStartHour ?? 2);
    setEndHour(d.windowEndHour ?? 5);
    setUseDefaultTables(!!d.usingDefaultTables);
    setTablesText((d.tables || []).join(', '));
  };

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get('/admin/maintenance/status');
      applyStatus(res.data);
    } catch {
      showToast('Failed to load maintenance status', 'error');
    }
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const save = async () => {
    setSaving(true);
    try {
      const body = {
        enabled, windowStartHour: startHour, windowEndHour: endHour,
        tables: useDefaultTables ? '' : tablesText,
      };
      const res = await api.put('/admin/maintenance/config', body);
      applyStatus(res.data);
      showToast('Maintenance settings saved', 'success');
    } catch (e) {
      showToast(e.response?.status === 403
        ? 'Only a Super Admin can change maintenance settings'
        : 'Failed to save settings', 'error');
    }
    setSaving(false);
  };

  const runNow = async (overrideBatch = false) => {
    setRunning(true);
    try {
      const res = await api.post(`/admin/maintenance/run?force=true&overrideBatch=${overrideBatch}`);
      const r = res.data;
      if (r.status === 'SKIPPED') {
        showToast(`Skipped: ${r.reason}`, 'warning');
      } else {
        showToast(
          `${r.status}: ${r.tablesDone}/${r.tablesTotal} tables in ${(r.durationMs / 1000).toFixed(1)}s`,
          r.status === 'SUCCESS' ? 'success' : 'error'
        );
      }
      load();
    } catch (e) {
      showToast(e.response?.status === 403
        ? 'Only a Super Admin can run maintenance'
        : 'Failed to run maintenance', 'error');
    }
    setRunning(false);
  };

  const windowDesc = startHour === endHour
    ? 'Window is empty, so the job will not run automatically.'
    : `Runs nightly between ${hourLabel(startHour)} and ${hourLabel(endHour)}${startHour > endHour ? ' (crosses midnight)' : ''}, server time.`;

  const runColumns = [
    {
      key: 'started_at', header: 'Started', sortable: true, nowrap: true,
      render: (r) => fmtTs(r.started_at),
    },
    {
      key: 'finished_at', header: 'Finished', sortable: true, nowrap: true,
      render: (r) => fmtTs(r.finished_at),
    },
    { key: 'trigger', header: 'Trigger', render: (r) => <Badge>{r.trigger}</Badge> },
    { key: 'status', header: 'Status', sortable: true, render: (r) => <StatusBadge status={r.status} /> },
    { key: 'tables_done', header: 'Tables', sortable: true, align: 'right', numeric: true },
    {
      key: 'detail', header: 'Detail', muted: true,
      render: (r) => (
        <span
          title={r.detail || ''}
          style={{ display: 'block', maxWidth: 360, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
        >
          {r.detail || '—'}
        </span>
      ),
    },
  ];

  return (
    <Page
      title="Database maintenance"
      subtitle="Nightly VACUUM and ANALYZE. Runs only when no batch job is active."
      icon={Database}
      actions={
        <>
          <Button icon={RefreshCw} onClick={load} disabled={loading}>Refresh</Button>
          <Button variant="primary" icon={Play} onClick={() => runNow(false)} loading={running} disabled={loading}>
            {running ? 'Running…' : 'Run now'}
          </Button>
        </>
      }
    >
      <Stack>
        {/* Live state strip */}
        <div className="ui-row" style={{ rowGap: 8 }}>
          <Badge tone={status?.batchRunning ? 'warning' : 'success'} icon={Activity}>
            {status?.batchRunning ? 'Batch job running' : 'No batch running'}
          </Badge>
          <Badge tone={status?.inWindowNow ? 'info' : 'neutral'} icon={Clock}>
            {status?.inWindowNow ? 'In maintenance window' : 'Outside window'}
          </Badge>
          <Badge>Last run: {status?.lastRunDate || 'never'}</Badge>
          <Badge tone={enabled ? 'success' : 'danger'}>{enabled ? 'Enabled' : 'Disabled'}</Badge>
        </div>

        <div
          style={{
            display: 'grid',
            gap: 'var(--space-lg)',
            gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
            alignItems: 'start',
          }}
        >
          <Card title="Schedule" pad>
            <div className="ui-stack ui-stack--sm">
              <Switch
                checked={enabled}
                onChange={(e) => setEnabled(e.target.checked)}
                label="Enable nightly maintenance"
                disabled={loading}
              />
              <div className="ui-form-grid ui-form-grid--2">
                <FormField label="Window start">
                  <Select
                    value={startHour}
                    onChange={(e) => setStartHour(Number(e.target.value))}
                    options={HOUR_OPTIONS}
                    disabled={loading}
                  />
                </FormField>
                <FormField label="Window end">
                  <Select
                    value={endHour}
                    onChange={(e) => setEndHour(Number(e.target.value))}
                    options={HOUR_OPTIONS}
                    disabled={loading}
                  />
                </FormField>
              </div>
              <Alert tone={startHour === endHour ? 'warning' : 'info'}>{windowDesc}</Alert>
            </div>
          </Card>

          <Card title="Tables to maintain" pad>
            <div className="ui-stack ui-stack--sm">
              <Switch
                checked={useDefaultTables}
                onChange={(e) => setUseDefaultTables(e.target.checked)}
                label="Use the recommended high-churn table list"
                disabled={loading}
              />
              {useDefaultTables ? (
                <div className="ui-row" style={{ gap: 6, rowGap: 6 }}>
                  {(status?.tables || []).map((t) => (
                    <Badge key={t} mono>{t}</Badge>
                  ))}
                </div>
              ) : (
                <FormField
                  label="Tables (comma-separated)"
                  hint="VACUUM (ANALYZE) runs on each. Vacuuming a partitioned parent covers all its partitions."
                >
                  <Textarea
                    mono
                    rows={3}
                    value={tablesText}
                    onChange={(e) => setTablesText(e.target.value)}
                    placeholder="transaction, settlement, merchant_daily"
                    disabled={loading}
                  />
                </FormField>
              )}
            </div>
          </Card>
        </div>

        <div className="ui-row" style={{ justifyContent: 'flex-end' }}>
          <Button onClick={load} disabled={saving || loading}>Reset</Button>
          <Button variant="primary" icon={Save} onClick={save} loading={saving} disabled={loading}>
            Save settings
          </Button>
        </div>

        <Card
          title="Recent runs"
          footer={
            <p className="ui-field__hint" style={{ margin: 0 }}>
              {'"Run now" ignores the schedule window but still refuses if a batch job is active. To force a run during ingestion, use the API with '}
              <code>overrideBatch=true</code>
              {' (not recommended).'}
            </p>
          }
        >
          <DataTable
            columns={runColumns}
            rows={status?.recentRuns || []}
            rowKey={(r) => r.id}
            loading={loading}
            defaultSort={{ key: 'started_at', dir: 'desc' }}
            empty={
              <div style={{ padding: 'var(--space-3xl)', textAlign: 'center', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                No maintenance runs recorded yet.
              </div>
            }
          />
        </Card>
      </Stack>
    </Page>
  );
};

export default DatabaseMaintenance;
