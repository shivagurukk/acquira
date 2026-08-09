import React, { useState, useEffect, useCallback, useRef } from 'react';
import { CreditCard, Upload, Search, Trash2, RefreshCw } from 'lucide-react';
import api from '../../api/axios';
import { showToast } from '../../contexts/ToastContext';
import {
  Page, Card, Button, Badge, Alert, DataTable, FormField, Select, Input,
} from '../../components/ui';

/**
 * Super Admin > BIN Management (V2026_08_08_06).
 *
 * Loads the platform-wide 8-digit BIN -> scheme / card type / product /
 * issuer-country mapping from an uploaded CSV or Excel file. Full refresh
 * (replace all) or append/merge. Configuration only for now: whether a
 * tenant's card product/type comes from this table or from the transaction
 * file is the "Card product/type source" setting in Tenant Management, and
 * no ingestion logic reads it yet.
 *
 * Expected columns (case-insensitive, any order, extras ignored):
 *   BIN (exactly 8 digits), SCHEME, CARD_TYPE, PRODUCT, COUNTRY, ISSUER
 */

const fmtTs = (s) => (s ? new Date(s).toLocaleString() : '—');

const BinManagement = () => {
  const [stats, setStats] = useState(null);
  const [rows, setRows] = useState([]);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [mode, setMode] = useState('REPLACE');
  const [lastResult, setLastResult] = useState(null);
  const fileRef = useRef(null);

  const [rangeRows, setRangeRows] = useState([]);
  const [rangeQuery, setRangeQuery] = useState('');

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
    } catch {
      showToast('Failed to load BIN data', 'error');
    }
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const onUpload = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    if (mode === 'REPLACE' && stats?.totalBins > 0
        && !window.confirm(`Full refresh will DELETE all ${stats.totalBins} existing BINs and load "${file.name}". Continue?`)) {
      return;
    }
    setUploading(true);
    try {
      const fd = new FormData();
      fd.append('file', file);
      const res = await api.post(`/admin/bins/upload?mode=${mode}`, fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      setLastResult(res.data);
      showToast(`Loaded ${res.data.loaded} ${res.data.format === 'VISA_BIN_LIST' ? 'ranges' : 'BINs'} (${res.data.rejected} rejected)`,
        res.data.rejected > 0 ? 'warning' : 'success');
      load(query, rangeQuery);
    } catch (err) {
      showToast(err.response?.data?.error || 'Upload failed', 'error');
    }
    setUploading(false);
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
      subtitle="8-digit BIN reference: scheme, card type, product and issuer country. Used as the card product/type source for tenants configured to it."
      icon={CreditCard}
      actions={
        <>
          <Button variant="ghost" icon={RefreshCw} onClick={() => load(query, rangeQuery)}>Refresh</Button>
          <Button variant="danger" icon={Trash2} onClick={onClear} disabled={!stats?.totalBins}>Clear all</Button>
        </>
      }
    >
      <div className="ui-stack ui-stack--md">
        <Card title="Load BIN file">
          <div className="ui-stack ui-stack--sm">
            <Alert variant="info">
              File type is detected by <b>name</b>: <b>VISA*</b> loads as the fixed-width Visa BIN list (into
              scheme ranges below); <b>T067/T068</b> (and test T167/T168) Mastercard MPE deliveries — zip or raw —
              are staged and validated against header, directory and trailer counts (range activation follows once
              the T068 confirms the table edition); anything else loads as CSV/Excel with columns <b>BIN</b> (exactly
              8 digits), <b>SCHEME</b>, <b>CARD_TYPE</b>, <b>PRODUCT</b>, <b>COUNTRY</b>, <b>ISSUER</b>.
              Invalid rows are rejected and counted, never silently dropped.
            </Alert>
            <div className="ui-row" style={{ gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' }}>
              <FormField label="Load mode" hint={mode === 'REPLACE'
                ? 'Full refresh: deletes every existing BIN, then loads the file.'
                : 'Append/merge: upserts by BIN — the uploaded file wins per BIN, others stay.'}>
                <Select
                  value={mode}
                  onChange={e => setMode(e.target.value)}
                  options={[
                    { value: 'REPLACE', label: 'Full refresh (replace all)' },
                    { value: 'APPEND', label: 'Append / merge by BIN' },
                  ]}
                />
              </FormField>
              <Button variant="primary" icon={Upload} loading={uploading} onClick={() => fileRef.current?.click()}>
                Upload BIN file
              </Button>
              <input ref={fileRef} type="file" accept=".csv,.xlsx,.xls" style={{ display: 'none' }} onChange={onUpload} />
            </div>
            {lastResult && (
              <Alert variant={lastResult.rejected > 0 ? 'warning' : 'success'}>
                <b>{lastResult.file}</b> ({lastResult.format || 'CSV'}, {lastResult.mode}): {lastResult.loaded} loaded,{' '}
                {lastResult.rejected} rejected.
                {lastResult.format === 'VISA_BIN_LIST'
                  ? <> Total Visa ranges now {lastResult.totalRanges} · {lastResult.distinctCountries} countries · {lastResult.bahrainRanges} Bahrain ranges.</>
                  : lastResult.format?.startsWith('MC_MPE')
                  ? <> {lastResult.status} (file id {lastResult.mpeFileId}, header date {lastResult.headerDate}).
                      {lastResult.mastercardRanges && (
                        <b> Mastercard ranges {lastResult.mastercardRanges.mode}: {lastResult.mastercardRanges.inserted} inserted,{' '}
                        {lastResult.mastercardRanges.updated} updated, {lastResult.mastercardRanges.deleted} deleted,{' '}
                        {lastResult.mastercardRanges.quarantined} quarantined · total now {lastResult.mastercardRanges.totalMastercardRanges}.</b>
                      )}
                      {lastResult.tables?.length > 0 && (
                        <span> Tables: {lastResult.tables.map(t => `${t.table} (${t.staged})`).join(', ')}.</span>
                      )}</>
                  : <> Total BINs now {lastResult.totalBins}.</>}
                {lastResult.rejectSamples?.length > 0 && (
                  <ul style={{ margin: '6px 0 0 16px' }}>
                    {lastResult.rejectSamples.map((s, i) => <li key={i}>{s}</li>)}
                  </ul>
                )}
              </Alert>
            )}
          </div>
        </Card>

        <Card title={`Scheme ranges: ${stats?.totalRanges ?? '—'}`}>
          <div className="ui-stack ui-stack--sm">
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
                { key: 'bin6', header: 'BIN' },
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
                onKeyDown={e => { if (e.key === 'Enter') load(query); }}
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
