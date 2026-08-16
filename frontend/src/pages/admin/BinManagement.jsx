import React, { useState, useEffect, useCallback, useRef } from 'react';
import { CreditCard, Upload, Search, Trash2, RefreshCw } from 'lucide-react';
import api from '../../api/axios';
import { showToast } from '../../contexts/ToastContext';
import {
  Page, Card, Button, Badge, Alert, DataTable, Input,
} from '../../components/ui';

/**
 * Super Admin > BIN Management (V2026_08_08_06).
 *
 * Loads the platform-wide 6/8-digit BIN -> scheme / card type / product /
 * issuer-country mapping from an uploaded CSV or Excel file. Load mode is
 * inferred from the filename (scheme deliveries replace/delta their scheme;
 * generic mapping files full-replace ref_bin). Configuration only for now: whether a
 * tenant's card product/type comes from this table or from the transaction
 * file is the "Card product/type source" setting in Tenant Management, and
 * no ingestion logic reads it yet.
 *
 * Expected columns (case-insensitive, any order, extras ignored):
 *   BIN (6 or 8 digits), SCHEME, CARD_TYPE, PRODUCT, COUNTRY, ISSUER
 */

const fmtTs = (s) => (s ? new Date(s).toLocaleString() : '—');

const BinManagement = () => {
  const [stats, setStats] = useState(null);
  const [rows, setRows] = useState([]);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [lastResult, setLastResult] = useState(null);
  const fileRef = useRef(null);

  const [rangeRows, setRangeRows] = useState([]);
  const [rangeQuery, setRangeQuery] = useState('');
  const [loadError, setLoadError] = useState(false);

  const load = useCallback(async (q = '', rq = '') => {
    setLoading(true);
    try {
      const [s, r, rr] = await Promise.all([
        api.get('/admin/bins/stats'),
        api.get('/admin/bins', { params: q ? { q } : {} }),
        api.get('/admin/bins/ranges', { params: rq ? { q: rq } : {} }),
      ]);
      setStats(s.data);
      setRows(r.data || []);
      setRangeRows(rr.data || []);
      setLoadError(false);
    } catch {
      // Keep an inline error too — a toast alone leaves the tables showing
      // the "no data" empty state, indistinguishable from a truly empty table.
      setLoadError(true);
      showToast('Failed to load BIN data', 'error');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  // MPE deliveries (T067/T068) load in the background — poll while any is
  // PROCESSING so the operator sees STAGED/COUNT_MISMATCH/FAILED without
  // hammering refresh.
  const processing = stats?.mpeFiles?.some(f => f.status === 'PROCESSING');
  useEffect(() => {
    if (!processing) return undefined;
    // Remember WHICH deliveries were in flight so the completion toast names
    // the right file (mpeFiles ordering is not guaranteed), and cap the poll
    // so a wedged delivery doesn't poll forever.
    const inFlight = new Set(
      (stats?.mpeFiles || []).filter(f => f.status === 'PROCESSING').map(f => f.id));
    let ticks = 0;
    const MAX_TICKS = 60; // 5 minutes at 5s
    const t = setInterval(async () => {
      try {
        const s = await api.get('/admin/bins/stats');
        setStats(s.data);
        const still = s.data?.mpeFiles?.some(f => f.status === 'PROCESSING');
        if (!still) {
          const done = (s.data?.mpeFiles || []).find(f => inFlight.has(f.id))
            || s.data?.mpeFiles?.[0];
          showToast(`MPE delivery ${done?.file_name || ''}: ${done?.status}`,
            done?.status === 'FAILED' ? 'error' : done?.status === 'COUNT_MISMATCH' ? 'warning' : 'success');
        }
      } catch { /* next tick retries */ }
      if (++ticks >= MAX_TICKS) {
        clearInterval(t);
        showToast('A delivery is still processing after 5 minutes — refresh the page to keep watching it.', 'warning', 8000);
      }
    }, 5000);
    return () => clearInterval(t);
  }, [processing]);

  const onUpload = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    // Refresh mode is decided by file type, never asked: VISA* and T068 fully
    // replace their scheme's ranges, T067 applies as a delta, and a CSV/Excel
    // BIN mapping fully replaces ref_bin (confirm that one — it deletes).
    const base = file.name.toLowerCase();
    // Anchored: only a filename that STARTS with the delivery code (T067/T068/
    // TT067/TT068…) is a scheme delivery. An unanchored match classified e.g.
    // "q3-t067-reconciliation.csv" as a scheme file and skipped the
    // replaces-all confirm below.
    const isSchemeFile = base.startsWith('visa') || /^t?t[01]6[78]/.test(base);
    if (!isSchemeFile && stats?.totalBins > 0
        && !window.confirm(`Loading "${file.name}" replaces all ${stats.totalBins} existing BIN mappings. Continue?`)) {
      return;
    }
    setUploading(true);
    try {
      const fd = new FormData();
      fd.append('file', file);
      const res = await api.post('/admin/bins/upload', fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      setLastResult(res.data);
      if (res.data.status === 'PROCESSING') {
        showToast('MPE delivery accepted — staging and range promotion run in the background.', 'info', 6000);
      } else {
        showToast(`Loaded ${res.data.loaded} ${res.data.format === 'VISA_BIN_LIST' ? 'ranges' : 'BINs'} (${res.data.rejected} rejected)`,
          res.data.rejected > 0 ? 'warning' : 'success');
      }
      load(query, rangeQuery);
    } catch (err) {
      showToast(err.response?.data?.error || 'Upload failed', 'error');
    } finally {
      setUploading(false);
    }
  };

  const onDeleteMpe = async (f) => {
    if (!window.confirm(`Delete staged delivery "${f.file_name}" (id ${f.id})? Its staged records are removed and the file can be re-uploaded. Promoted ranges are NOT touched.`)) return;
    try {
      await api.delete(`/admin/bins/mpe/${f.id}`);
      showToast(`Deleted staged delivery ${f.id}`, 'success');
      load(query, rangeQuery);
    } catch {
      showToast('Failed to delete staged delivery', 'error');
    }
  };

  const onClear = async () => {
    if (!window.confirm('Delete ALL BIN mappings? Tenants configured to use the BIN source will have no reference data until a new file is loaded.')) return;
    try {
      const res = await api.delete('/admin/bins');
      showToast(`Deleted ${res.data.deleted} BINs`, 'success');
      setLastResult(null);
      load();
    } catch {
      showToast('Failed to clear BIN table', 'error');
    }
  };

  const columns = [
    { key: 'bin', header: 'BIN', sortable: true, render: r => <Badge mono>{r.bin}</Badge> },
    { key: 'scheme', header: 'Scheme', sortable: true },
    { key: 'card_type', header: 'Card type', sortable: true },
    { key: 'product_code', header: 'Product', sortable: true },
    { key: 'issuer_country', header: 'Issuer country', sortable: true },
    { key: 'issuer_name', header: 'Issuer', render: r => r.issuer_name || <span className="ui-td--muted">—</span> },
    { key: 'loaded_at', header: 'Loaded', render: r => <span className="ui-td--muted">{fmtTs(r.loaded_at)}</span> },
  ];

  return (
    <Page
      title="BIN Management"
      subtitle="6/8-digit BIN reference: scheme, card type, product and issuer country. Used as the card product/type source for tenants configured to it."
      icon={CreditCard}
      actions={
        <>
          <Button variant="ghost" icon={RefreshCw} onClick={() => load(query, rangeQuery)}>Refresh</Button>
          <Button variant="danger" icon={Trash2} onClick={onClear} disabled={!stats?.totalBins}>Clear all</Button>
        </>
      }
    >
      <div className="ui-stack ui-stack--md">
        {loadError && (
          <Alert variant="error">
            BIN data failed to load — the tables below may show empty rather than the real contents.{' '}
            <Button variant="ghost" onClick={() => load(query, rangeQuery)}>Retry</Button>
          </Alert>
        )}
        <Card title="Load BIN file">
          <div className="ui-stack ui-stack--sm">
            <Alert variant="info">
              File type is detected by <b>name</b>: <b>VISA*</b> loads as the fixed-width Visa BIN list (into
              scheme ranges below); <b>T067/T068</b> (and test T167/T168) Mastercard MPE deliveries — zip or raw —
              are staged and validated against header, directory and trailer counts (range activation follows once
              the T068 confirms the table edition); anything else loads as CSV/Excel with columns <b>BIN</b> (6 or
              8 digits — feeds mask the PAN to first-6 + last-4, so only a 6-digit BIN is extractable today),
              <b>SCHEME</b>, <b>CARD_TYPE</b>, <b>PRODUCT</b>, <b>COUNTRY</b>, <b>ISSUER</b>.
              Invalid rows are rejected and counted, never silently dropped.
              Refresh mode is automatic per file type: the Visa list and T068 fully replace their scheme's ranges,
              T067 applies as a delta (changed ranges updated, inactivated ones removed), and a CSV/Excel mapping
              fully replaces the BIN table. Re-uploading the same file is allowed and replaces the previous load.
            </Alert>
            <div className="ui-row" style={{ gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' }}>
              <Button variant="primary" icon={Upload} loading={uploading} onClick={() => fileRef.current?.click()}>
                Upload BIN file
              </Button>
              {/* No accept filter: MPE deliveries arrive as .zip or with
                  bare names like TT068.B.CCAR1212.D260729.T083002.A001 that
                  an extension filter would hide from the picker. */}
              <input ref={fileRef} type="file" style={{ display: 'none' }} onChange={onUpload} />
            </div>
            {lastResult && (lastResult.status === 'PROCESSING' ? (
              <Alert variant="info">
                <b>{lastResult.file}</b> accepted (delivery id {lastResult.mpeFileId}). {lastResult.note}
              </Alert>
            ) : (
              <Alert variant={lastResult.rejected > 0 ? 'warning' : 'success'}>
                <b>{lastResult.file}</b> ({lastResult.format || 'CSV'}, {lastResult.mode}): {lastResult.loaded} loaded,{' '}
                {lastResult.rejected} rejected.
                {lastResult.format === 'VISA_BIN_LIST'
                  ? <> Total Visa ranges now {lastResult.totalRanges} · {lastResult.distinctCountries} countries · {lastResult.bahrainRanges} Bahrain ranges.</>
                  : <> Total BINs now {lastResult.totalBins}.</>}
                {lastResult.rejectSamples?.length > 0 && (
                  <ul style={{ margin: '6px 0 0 16px' }}>
                    {lastResult.rejectSamples.map((s, i) => <li key={i}>{s}</li>)}
                  </ul>
                )}
              </Alert>
            ))}
          </div>
        </Card>

        {stats?.mpeFiles?.length > 0 && (
          <Card title="Mastercard MPE deliveries (T067/T068)">
            <div className="ui-stack ui-stack--sm">
              {stats.mpeFiles.some(f => f.status === 'PROCESSING') && (
                <Alert variant="info">A delivery is being staged and promoted in the background — this list refreshes automatically.</Alert>
              )}
              <DataTable
                columns={[
                  { key: 'id', header: 'Id' },
                  { key: 'file_name', header: 'File' },
                  { key: 'file_type', header: 'Type' },
                  { key: 'record_count', header: 'Records' },
                  {
                    key: 'status', header: 'Status',
                    render: f => (
                      <Badge tone={f.status === 'STAGED' ? 'success' : f.status === 'PROCESSING' ? 'info' : f.status === 'FAILED' ? 'danger' : 'warning'}>
                        {f.status}
                      </Badge>
                    ),
                  },
                  {
                    key: 'error_text', header: 'Details',
                    render: f => f.error_text
                      ? <span className="ui-td--muted" title={f.error_text}>{f.error_text.slice(0, 120)}</span>
                      : <span className="ui-td--muted">—</span>,
                  },
                  { key: 'loaded_at', header: 'Uploaded', render: f => <span className="ui-td--muted">{fmtTs(f.loaded_at)}</span> },
                  {
                    key: 'actions', header: '',
                    render: f => (
                      <Button variant="ghost" icon={Trash2} disabled={f.status === 'PROCESSING'} onClick={() => onDeleteMpe(f)} />
                    ),
                  },
                ]}
                rows={stats.mpeFiles}
                rowKey={f => f.id}
                emptyVariant="data"
              />
            </div>
          </Card>
        )}

        <Card title={`Scheme ranges: ${stats?.totalRanges ?? '—'}`}>
          <div className="ui-stack ui-stack--sm">
            {stats?.malformedRanges > 0 && (
              <Alert variant="warning">
                {stats.malformedRanges} range rows are not 19-digit normalized — they were loaded by an older build and
                will resolve card prefixes incorrectly. Re-upload the Visa BIN list (full refresh) to fix them.
              </Alert>
            )}
            {stats?.rangesByScheme?.length > 0 && (
              <div className="ui-row" style={{ gap: 8, flexWrap: 'wrap' }}>
                {stats.rangesByScheme.map(s => (
                  <Badge key={s.scheme}>{s.scheme}: {s.ranges} ranges</Badge>
                ))}
                {stats.rangesByCountry?.slice(0, 8).map(c => (
                  <Badge key={c.country} mono>{c.country || '—'}: {c.ranges}</Badge>
                ))}
              </div>
            )}
            <div className="ui-row" style={{ gap: 8 }}>
              <Input
                value={rangeQuery}
                onChange={e => setRangeQuery(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') load(query, rangeQuery); }}
                placeholder="Card prefix (finds containing range), country, product, BIN…"
                style={{ maxWidth: 380 }}
              />
              <Button icon={Search} onClick={() => load(query, rangeQuery)}>Search ranges</Button>
            </div>
            <DataTable
              columns={[
                { key: 'scheme', header: 'Scheme', sortable: true },
                { key: 'range_low', header: 'Range low', render: r => <Badge mono>{r.range_low}</Badge> },
                { key: 'range_high', header: 'Range high', render: r => <Badge mono>{r.range_high}</Badge> },
                // range_bin is the card prefix; bin6 is the file's LICENSED
                // BIN, which legitimately differs on most Visa rows — labeling
                // it plain "BIN" made lookups look wrong.
                { key: 'range_bin', header: 'Range BIN', render: r => <Badge mono>{r.range_bin}</Badge> },
                { key: 'bin6', header: 'Licensed BIN' },
                { key: 'issuer_country', header: 'Country', sortable: true },
                { key: 'product_code', header: 'Product', sortable: true },
                { key: 'funding_source', header: 'Funding' },
                { key: 'card_type', header: 'Card type', sortable: true },
              ]}
              rows={rangeRows}
              rowKey={(r, i) => `${r.scheme}-${r.range_low}-${r.range_high}-${i}`}
              loading={loading}
              emptyVariant="data"
            />
          </div>
        </Card>

        <Card title={`BINs loaded: ${stats?.totalBins ?? '—'}`}>
          <div className="ui-stack ui-stack--sm">
            {stats?.byScheme?.length > 0 && (
              <div className="ui-row" style={{ gap: 8, flexWrap: 'wrap' }}>
                {stats.byScheme.map(s => (
                  <Badge key={s.scheme}>{s.scheme}: {s.bins}</Badge>
                ))}
              </div>
            )}
            <div className="ui-row" style={{ gap: 8 }}>
              <Input
                value={query}
                onChange={e => setQuery(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') load(query, rangeQuery); }}
                placeholder="Search BIN prefix, scheme, country, product, issuer…"
                style={{ maxWidth: 380 }}
              />
              <Button icon={Search} onClick={() => load(query, rangeQuery)}>Search</Button>
            </div>
            <DataTable
              columns={columns}
              rows={rows}
              rowKey={r => r.bin}
              loading={loading}
              defaultSort={{ key: 'bin', dir: 'asc' }}
              emptyVariant="data"
            />
          </div>
        </Card>
      </div>
    </Page>
  );
};

export default BinManagement;
