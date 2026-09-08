import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import {
  CreditCard, Upload, Search, Trash2, RefreshCw, FileUp, FileSpreadsheet,
  FileArchive, FileText, Crosshair, Layers, ListChecks, X,
} from 'lucide-react';
import api, { UPLOAD_TIMEOUT, isTimeoutError } from '../../api/axios';
import { showToast } from '../../contexts/ToastContext';
import {
  Page, Card, Button, Badge, Alert, DataTable, Input, Tabs, Stack, Row, useConfirm,
} from '../../components/ui';

/**
 * Super Admin > BIN Management — full redesign 2026-08-25.
 *
 * The screen is organized as the operator actually works:
 *   1. Registry pulse  — how much reference data is loaded, per source.
 *   2. Intake          — drop a file, SEE what the platform detected it as and
 *                        what the load will replace, then load it. No surprise
 *                        replaces: the old screen uploaded on pick.
 *   3. Deliveries      — Mastercard MPE (T067/T068) staging pipeline + delete.
 *   4. Resolution test — type a card prefix, see exactly how ingest resolves
 *                        it (manual override → scheme range → fallback).
 *   5. Registries      — the two tables (scheme ranges / manual BINs), tabbed.
 *
 * Backend contract unchanged: /api/admin/bins (+/stats /ranges /upload /mpe).
 * File-type routing is decided by the SERVER (name + content); the intake
 * panel mirrors the same rules client-side purely as a preview.
 */

const fmtTs = (s) => (s ? new Date(s).toLocaleString() : '—');
const fmtN = (n) => (n == null ? '—' : Number(n).toLocaleString());

/** Client-side mirror of the server's file-type routing — preview only. */
const detectFileType = (fileName) => {
  const base = (fileName || '').toLowerCase().replace(/^.*[/\\]/, '');
  if (base.startsWith('visa')) {
    return {
      kind: 'VISA', icon: FileText, label: 'Visa BIN list (fixed width)',
      effect: 'Fully replaces every VISA scheme range. Other schemes and manual BINs are untouched.',
      destructive: false,
    };
  }
  if (/t?t[01]6[78]/.test(base)) {
    const isT67 = /t?t[01]67/.test(base);
    return {
      kind: 'MPE', icon: FileArchive,
      label: `Mastercard MPE delivery (${isT67 ? 'T067 update' : 'T068 replacement'})`,
      effect: isT67
        ? 'Staged in the background, then applied as a delta: changed ranges updated, inactivated ones removed.'
        : 'Staged in the background, then fully replaces the MASTERCARD scheme ranges.',
      destructive: false,
    };
  }
  if (base.endsWith('.xlsx') || base.endsWith('.xls')) {
    return {
      kind: 'MAPPING', icon: FileSpreadsheet, label: 'Excel BIN mapping',
      effect: 'Fully replaces the manual BIN table (ref_bin). Scheme ranges are untouched.',
      destructive: true,
    };
  }
  return {
    kind: 'MAPPING', icon: FileText, label: 'CSV BIN mapping',
    effect: 'Fully replaces the manual BIN table (ref_bin). Scheme ranges are untouched.',
    destructive: true,
  };
};

const MAPPING_COLUMNS = 'BIN (6 or 8 digits), SCHEME, CARD_TYPE, PRODUCT, COUNTRY, ISSUER';

/* ─── Registry pulse ─────────────────────────────────────────────────── */

const PulseTile = ({ label, value, hint, icon: Icon }) => (
  <div
    style={{
      flex: '1 1 180px', minWidth: 170, padding: '14px 16px',
      background: 'var(--bg-card)', border: '1px solid var(--border)',
      borderRadius: 10,
    }}
  >
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--text-muted)', fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.06em' }}>
      {Icon && <Icon size={13} />} {label}
    </div>
    <div style={{ fontSize: 24, fontWeight: 700, fontVariantNumeric: 'tabular-nums', marginTop: 4 }}>{value}</div>
    {hint && <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 2 }}>{hint}</div>}
  </div>
);

/* ─── Intake dropzone ────────────────────────────────────────────────── */

const DropZone = ({ onFile, disabled }) => {
  const inputRef = useRef(null);
  const [over, setOver] = useState(false);
  return (
    <div
      role="button"
      tabIndex={0}
      aria-label="Choose or drop a BIN file"
      onClick={() => !disabled && inputRef.current?.click()}
      onKeyDown={(e) => { if (!disabled && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); inputRef.current?.click(); } }}
      onDragOver={(e) => { e.preventDefault(); if (!disabled) setOver(true); }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault(); setOver(false);
        if (disabled) return;
        const f = e.dataTransfer?.files?.[0];
        if (f) onFile(f);
      }}
      style={{
        border: `1.5px dashed ${over ? 'var(--brand)' : 'var(--border)'}`,
        background: over ? 'var(--bg-hover)' : 'var(--bg-subtle)',
        borderRadius: 10, padding: '28px 16px', textAlign: 'center',
        cursor: disabled ? 'not-allowed' : 'pointer', transition: 'border-color 120ms, background 120ms',
        opacity: disabled ? 0.6 : 1,
      }}
    >
      <FileUp size={22} style={{ color: 'var(--text-muted)' }} aria-hidden />
      <div style={{ fontWeight: 600, marginTop: 6 }}>Drop a BIN file here, or click to browse</div>
      <div style={{ fontSize: 12.5, color: 'var(--text-muted)', marginTop: 4 }}>
        Visa BIN list (VISA*) · Mastercard MPE T067/T068 (zip or raw) · CSV/Excel mapping ({MAPPING_COLUMNS})
      </div>
      {/* No accept filter: MPE deliveries arrive as .zip or bare names like
          TT068.B.CCAR1212.D260729.T083002.A001 that a filter would hide. */}
      <input
        ref={inputRef} type="file" style={{ display: 'none' }}
        onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) onFile(f); }}
      />
    </div>
  );
};

/* ─── Resolution tester ──────────────────────────────────────────────── */

const ResolveField = ({ label, value }) => (
  <div style={{ minWidth: 110 }}>
    <div style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-muted)' }}>{label}</div>
    <div style={{ fontWeight: 600, marginTop: 2 }}>{value || <span style={{ color: 'var(--text-muted)' }}>—</span>}</div>
  </div>
);

const ResolutionTester = () => {
  const [digits, setDigits] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);

  const run = async () => {
    const d = digits.replace(/\D/g, '');
    if (d.length < 6) { showToast('Enter at least the first 6 digits of a card number', 'warning'); return; }
    setBusy(true);
    try {
      const [manualRes, rangeRes] = await Promise.all([
        api.get('/admin/bins', { params: { q: d.slice(0, 6) } }),
        api.get('/admin/bins/ranges', { params: { q: d } }),
      ]);
      // Mirror of the ingest lookup: an exact 6-digit manual row wins; else the
      // containing range with the greatest range_low (binary-search floor).
      const manual = (manualRes.data || []).find((r) => r.bin === d.slice(0, 6)) || null;
      const pan19 = (d + '0000000000000000000').slice(0, 19);
      const containing = (rangeRes.data || [])
        .filter((r) => r.range_low <= pan19 && r.range_high >= pan19)
        .sort((a, b) => (a.range_low < b.range_low ? 1 : -1))[0] || null;
      setResult({ digits: d, manual, range: containing });
    } catch {
      showToast('Resolution lookup failed', 'error');
    } finally {
      setBusy(false);
    }
  };

  const winner = result && (result.manual ? 'manual' : result.range ? 'range' : 'none');
  const hit = result && (result.manual || result.range);

  return (
    <Card
      title="Resolution tester"
      subtitle="Type a card prefix and see how ingest will resolve it — a manual BIN overrides a scheme range; no match falls back to the feed value (or the premium default on BIN-typed tenants)."
      pad
    >
      <Stack gap="sm">
        <Row style={{ gap: 8, flexWrap: 'wrap' }}>
          <Input
            value={digits}
            onChange={(e) => setDigits(e.target.value.replace(/[^\d]/g, '').slice(0, 12))}
            onKeyDown={(e) => { if (e.key === 'Enter') run(); }}
            placeholder="First 6–12 digits, e.g. 451396"
            aria-label="Card prefix to resolve"
            style={{ maxWidth: 260, fontFamily: 'var(--font-mono, monospace)', letterSpacing: '0.08em' }}
          />
          <Button icon={Crosshair} loading={busy} onClick={run}>Resolve</Button>
        </Row>

        {result && (
          <div
            style={{
              borderLeft: `3px solid ${winner === 'none' ? 'var(--border)' : 'var(--brand)'}`,
              background: 'var(--bg-subtle)', borderRadius: 8, padding: '12px 16px',
            }}
          >
            <Row style={{ gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
              <Badge mono>{result.digits}</Badge>
              {winner === 'manual' && <Badge tone="success">Manual BIN override</Badge>}
              {winner === 'range' && <Badge tone="info">Scheme range</Badge>}
              {winner === 'none' && <Badge tone="warning">No match — ingest fallback applies</Badge>}
            </Row>
            {hit ? (
              <Row style={{ gap: 28, marginTop: 10, flexWrap: 'wrap' }}>
                <ResolveField label="Scheme" value={(result.manual || result.range).scheme} />
                <ResolveField label="Card type" value={(result.manual || result.range).card_type} />
                <ResolveField label="Product" value={(result.manual || result.range).product_code} />
                <ResolveField label="Issuer country" value={(result.manual || result.range).issuer_country} />
                {result.manual?.issuer_name && <ResolveField label="Issuer" value={result.manual.issuer_name} />}
                {!result.manual && result.range && (
                  <ResolveField label="Range" value={`${result.range.range_bin}…`} />
                )}
              </Row>
            ) : (
              <div style={{ fontSize: 13, color: 'var(--text-muted)', marginTop: 8 }}>
                Neither a manual BIN nor a scheme range covers this prefix. At ingest the row keeps the
                feed's card type; on BIN-typed tenants a blank type resolves Benefit → DEBIT, anything else → CREDIT (premium).
              </div>
            )}
            {result.manual && result.range && (
              <div style={{ fontSize: 12.5, color: 'var(--text-muted)', marginTop: 8 }}>
                A scheme range also covers this prefix ({result.range.scheme} · {result.range.card_type} · {result.range.issuer_country}),
                but the manual row wins.
              </div>
            )}
          </div>
        )}
      </Stack>
    </Card>
  );
};

/* ─── Page ───────────────────────────────────────────────────────────── */

const BinManagement = () => {
  const confirm = useConfirm();
  const [stats, setStats] = useState(null);
  const [rows, setRows] = useState([]);
  const [query, setQuery] = useState('');
  const [rangeRows, setRangeRows] = useState([]);
  const [rangeQuery, setRangeQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [pending, setPending] = useState(null);       // { file, detect }
  const [lastResult, setLastResult] = useState(null);
  const [registryTab, setRegistryTab] = useState('ranges');

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
      // Inline error too — a toast alone leaves the tables looking merely empty.
      setLoadError(true);
      showToast('Failed to load BIN data', 'error');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  // MPE deliveries load in the background — poll while any is PROCESSING so the
  // operator sees STAGED / COUNT_MISMATCH / FAILED without hammering refresh.
  const processing = stats?.mpeFiles?.some((f) => f.status === 'PROCESSING');
  useEffect(() => {
    if (!processing) return undefined;
    const inFlight = new Set(
      (stats?.mpeFiles || []).filter((f) => f.status === 'PROCESSING').map((f) => f.id));
    let ticks = 0;
    const MAX_TICKS = 60; // 5 minutes at 5s
    const t = setInterval(async () => {
      try {
        const s = await api.get('/admin/bins/stats');
        setStats(s.data);
        const still = s.data?.mpeFiles?.some((f) => f.status === 'PROCESSING');
        if (!still) {
          const done = (s.data?.mpeFiles || []).find((f) => inFlight.has(f.id)) || s.data?.mpeFiles?.[0];
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
  }, [processing]); // eslint-disable-line react-hooks/exhaustive-deps

  /* ── intake ── */

  const onPick = (file) => setPending({ file, detect: detectFileType(file.name) });

  const startUpload = async () => {
    if (!pending) return;
    const { file, detect } = pending;
    if (detect.destructive && stats?.totalBins > 0) {
      const ok = await confirm({
        title: 'Replace the manual BIN table',
        message: `Loading "${file.name}" replaces all ${fmtN(stats.totalBins)} existing manual BIN mappings. Scheme ranges are not touched.`,
        confirmLabel: 'Replace and load',
        tone: 'danger',
      });
      if (!ok) return;
    }
    setUploading(true);
    try {
      const fd = new FormData();
      fd.append('file', file);
      const res = await api.post('/admin/bins/upload', fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: UPLOAD_TIMEOUT,
      });
      setLastResult(res.data);
      setPending(null);
      if (res.data.status === 'PROCESSING') {
        showToast('MPE delivery accepted — staging and range promotion run in the background.', 'info', 6000);
      } else {
        showToast(`Loaded ${fmtN(res.data.loaded)} ${res.data.format === 'VISA_BIN_LIST' ? 'ranges' : 'BINs'} (${fmtN(res.data.rejected)} rejected)`,
          res.data.rejected > 0 ? 'warning' : 'success');
      }
      load(query, rangeQuery);
    } catch (err) {
      // Never say "failed" on a timeout: a full BIN load REPLACES every existing
      // mapping, so re-running one that actually landed is destructive.
      showToast(isTimeoutError(err)
        ? 'The server did not respond in time. The load may still be running — refresh and check the counts before retrying.'
        : (err.response?.data?.error || 'Upload failed'), 'error', 8000);
    } finally {
      setUploading(false);
    }
  };

  /* ── deletes ── */

  const onDeleteMpe = async (f) => {
    const ok = await confirm({
      title: 'Delete staged delivery',
      message: `Delete "${f.file_name}" (id ${f.id})? Its staged records are removed and the file can be re-uploaded. Promoted scheme ranges are not touched.`,
      confirmLabel: 'Delete delivery',
      tone: 'danger',
    });
    if (!ok) return;
    try {
      await api.delete(`/admin/bins/mpe/${f.id}`);
      showToast(`Deleted staged delivery ${f.id}`, 'success');
      load(query, rangeQuery);
    } catch {
      showToast('Failed to delete staged delivery', 'error');
    }
  };

  const onClear = async () => {
    const ok = await confirm({
      title: 'Delete all manual BINs',
      message: `Delete all ${fmtN(stats?.totalBins)} manual BIN mappings? Tenants configured to the BIN source lose their override data until a new file is loaded. Scheme ranges are not touched.`,
      confirmLabel: 'Delete all',
      tone: 'danger',
    });
    if (!ok) return;
    try {
      const res = await api.delete('/admin/bins');
      showToast(`Deleted ${fmtN(res.data.deleted)} BINs`, 'success');
      setLastResult(null);
      load();
    } catch {
      showToast('Failed to clear the BIN table', 'error');
    }
  };

  /* ── derived ── */

  const schemeChips = useMemo(() => [
    ...(stats?.rangesByScheme || []).map((s) => ({ key: `r-${s.scheme}`, text: `${s.scheme} · ${fmtN(s.ranges)} ranges` })),
    ...(stats?.byScheme || []).map((s) => ({ key: `b-${s.scheme}`, text: `${s.scheme} · ${fmtN(s.bins)} manual` })),
  ], [stats]);

  const lastDelivery = stats?.mpeFiles?.[0];
  const PendIcon = pending?.detect?.icon || FileUp;

  const rangeColumns = [
    { key: 'scheme', header: 'Scheme', sortable: true },
    { key: 'range_bin', header: 'Range BIN', render: (r) => <Badge mono>{r.range_bin}</Badge> },
    { key: 'range_low', header: 'Range low', mono: true, muted: true, nowrap: true },
    { key: 'range_high', header: 'Range high', mono: true, muted: true, nowrap: true },
    // bin6 is the file's LICENSED BIN — it legitimately differs from the card
    // prefix on most Visa rows; labeling it plain "BIN" made lookups look wrong.
    { key: 'bin6', header: 'Licensed BIN', mono: true },
    { key: 'issuer_country', header: 'Country', sortable: true },
    { key: 'product_code', header: 'Product', sortable: true },
    { key: 'funding_source', header: 'Funding' },
    { key: 'card_type', header: 'Card type', sortable: true },
  ];

  const binColumns = [
    { key: 'bin', header: 'BIN', sortable: true, render: (r) => <Badge mono>{r.bin}</Badge> },
    { key: 'scheme', header: 'Scheme', sortable: true },
    { key: 'card_type', header: 'Card type', sortable: true },
    { key: 'product_code', header: 'Product', sortable: true },
    { key: 'issuer_country', header: 'Issuer country', sortable: true },
    { key: 'issuer_name', header: 'Issuer', render: (r) => r.issuer_name || <span className="ui-td--muted">—</span> },
    { key: 'loaded_at', header: 'Loaded', render: (r) => <span className="ui-td--muted">{fmtTs(r.loaded_at)}</span> },
  ];

  return (
    <Page
      title="BIN Management"
      subtitle="Platform-wide card BIN reference — scheme ranges from Visa and Mastercard deliveries, plus the bank's own manual overrides. BIN-typed tenants resolve card type, product and issuer country from here at ingest."
      icon={CreditCard}
      actions={(
        <>
          <Button variant="ghost" icon={RefreshCw} onClick={() => load(query, rangeQuery)}>Refresh</Button>
          <Button variant="danger-ghost" icon={Trash2} onClick={onClear} disabled={!stats?.totalBins}>
            Delete all manual BINs
          </Button>
        </>
      )}
    >
      <Stack gap="md">
        {loadError && (
          <Alert variant="error">
            BIN data failed to load — the tables below may show empty rather than the real contents.{' '}
            <Button variant="ghost" size="sm" onClick={() => load(query, rangeQuery)}>Retry</Button>
          </Alert>
        )}

        {/* 1 ── registry pulse */}
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          <PulseTile icon={Layers} label="Scheme ranges" value={fmtN(stats?.totalRanges)}
            hint={`${stats?.rangesByScheme?.length ?? 0} scheme file${(stats?.rangesByScheme?.length ?? 0) === 1 ? '' : 's'} promoted`} />
          <PulseTile icon={ListChecks} label="Manual BINs" value={fmtN(stats?.totalBins)}
            hint="Bank-authored overrides — win over ranges" />
          <PulseTile icon={FileArchive} label="Last delivery"
            value={lastDelivery ? lastDelivery.file_type : '—'}
            hint={lastDelivery ? `${lastDelivery.status} · ${fmtTs(lastDelivery.loaded_at)}` : 'No MPE deliveries yet'} />
          <PulseTile icon={Crosshair} label="Countries covered"
            value={fmtN(stats?.rangesByCountry?.length)}
            hint={stats?.rangesByCountry?.slice(0, 3).map((c) => c.country || '—').join(' · ') || '—'} />
        </div>

        {stats?.malformedRanges > 0 && (
          <Alert variant="warning">
            {fmtN(stats.malformedRanges)} range rows are not 19-digit normalized — loaded by an older build, they resolve
            card prefixes incorrectly. Re-upload the Visa BIN list (full refresh) to fix them.
          </Alert>
        )}

        {/* 2 ── intake */}
        <Card title="Load a BIN file" subtitle="File type and refresh mode are detected automatically — review the detection before anything is replaced." pad>
          <Stack gap="sm">
            {!pending && <DropZone onFile={onPick} disabled={uploading} />}

            {pending && (
              <div style={{ border: '1px solid var(--border)', borderRadius: 10, padding: '14px 16px', background: 'var(--bg-subtle)' }}>
                <Row style={{ gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
                  <PendIcon size={18} style={{ color: 'var(--brand)' }} aria-hidden />
                  <span style={{ fontWeight: 600, wordBreak: 'break-all' }}>{pending.file.name}</span>
                  <Badge>{pending.detect.label}</Badge>
                  <span style={{ fontSize: 12.5, color: 'var(--text-muted)' }}>{fmtN(Math.ceil(pending.file.size / 1024))} KB</span>
                </Row>
                <div style={{ fontSize: 13, color: 'var(--text-muted)', margin: '8px 0 12px' }}>{pending.detect.effect}</div>
                <Row style={{ gap: 8 }}>
                  <Button variant="primary" icon={Upload} loading={uploading} onClick={startUpload}>Load file</Button>
                  <Button variant="ghost" icon={X} disabled={uploading} onClick={() => setPending(null)}>Choose a different file</Button>
                </Row>
              </div>
            )}

            {lastResult && (lastResult.status === 'PROCESSING' ? (
              <Alert variant="info">
                <b>{lastResult.file}</b> accepted (delivery id {lastResult.mpeFileId}). {lastResult.note}
              </Alert>
            ) : (
              <Alert variant={lastResult.rejected > 0 ? 'warning' : 'success'}>
                <b>{lastResult.file}</b> ({lastResult.format || 'CSV'}, {lastResult.mode}): {fmtN(lastResult.loaded)} loaded,{' '}
                {fmtN(lastResult.rejected)} rejected.
                {lastResult.format === 'VISA_BIN_LIST'
                  ? <> Visa ranges now {fmtN(lastResult.totalRanges)} · {fmtN(lastResult.distinctCountries)} countries · {fmtN(lastResult.bahrainRanges)} Bahrain ranges.</>
                  : <> Manual BINs now {fmtN(lastResult.totalBins)}.</>}
                {lastResult.rejectSamples?.length > 0 && (
                  <ul style={{ margin: '6px 0 0 16px' }}>
                    {lastResult.rejectSamples.map((s, i) => <li key={i}>{s}</li>)}
                  </ul>
                )}
              </Alert>
            ))}

            {lastResult?.productCodesNotInRateVocabulary?.length > 0 && (
              <Alert variant="warning">
                {lastResult.productCodeWarning}
                <ul style={{ margin: '6px 0 0 16px' }}>
                  {lastResult.productCodesNotInRateVocabulary.map((p, i) => (
                    <li key={i}><b>{p.product_code}</b> — {fmtN(p.bins)} BIN{p.bins === 1 ? '' : 's'}</li>
                  ))}
                </ul>
              </Alert>
            )}

            {stats?.lastLoad?.length > 0 && (
              <div style={{ fontSize: 12.5, color: 'var(--text-muted)' }}>
                Recent manual loads:{' '}
                {stats.lastLoad.map((l) => `${l.source_file} (${fmtN(l.rows)} rows, ${fmtTs(l.loaded_at)})`).join(' · ')}
              </div>
            )}
          </Stack>
        </Card>

        {/* 3 ── MPE delivery pipeline */}
        {stats?.mpeFiles?.length > 0 && (
          <Card title="Mastercard MPE deliveries" subtitle="T067 updates and T068 replacements stage in the background; ranges promote once the counts validate.">
            {processing && (
              <Alert variant="info" style={{ margin: '0 16px 8px' }}>
                A delivery is being staged and promoted in the background — this list refreshes automatically.
              </Alert>
            )}
            <DataTable
              columns={[
                { key: 'id', header: 'Id', width: 56 },
                { key: 'file_name', header: 'File', render: (f) => <span style={{ wordBreak: 'break-all' }}>{f.file_name}</span> },
                { key: 'file_type', header: 'Type', width: 80 },
                { key: 'record_count', header: 'Records', align: 'right', numeric: true, render: (f) => fmtN(f.record_count) },
                {
                  key: 'status', header: 'Status',
                  render: (f) => (
                    <Badge tone={f.status === 'STAGED' ? 'success' : f.status === 'PROCESSING' ? 'info' : f.status === 'FAILED' ? 'danger' : 'warning'}>
                      {f.status}
                    </Badge>
                  ),
                },
                {
                  key: 'error_text', header: 'Details',
                  render: (f) => f.error_text
                    ? <span className="ui-td--muted" title={f.error_text}>{f.error_text.slice(0, 120)}{f.error_text.length > 120 ? '…' : ''}</span>
                    : <span className="ui-td--muted">—</span>,
                },
                { key: 'loaded_at', header: 'Uploaded', render: (f) => <span className="ui-td--muted">{fmtTs(f.loaded_at)}</span> },
                {
                  key: '_actions', header: '', align: 'right',
                  render: (f) => (
                    <Button
                      variant="danger-ghost" size="sm" iconOnly icon={Trash2}
                      aria-label={`Delete staged delivery ${f.id}`}
                      disabled={f.status === 'PROCESSING'}
                      onClick={() => onDeleteMpe(f)}
                    />
                  ),
                },
              ]}
              rows={stats.mpeFiles}
              rowKey={(f) => f.id}
              emptyVariant="data"
            />
          </Card>
        )}

        {/* 4 ── resolution tester */}
        <ResolutionTester />

        {/* 5 ── registries */}
        <Card
          title="Registries"
          subtitle="What is loaded, searchable. A card resolves against a manual BIN first, then by range containment."
          actions={(
            <Tabs
              inline
              variant="pills"
              active={registryTab}
              onChange={setRegistryTab}
              tabs={[
                { key: 'ranges', label: `Scheme ranges (${fmtN(stats?.totalRanges)})` },
                { key: 'bins', label: `Manual BINs (${fmtN(stats?.totalBins)})` },
              ]}
            />
          )}
        >
          <Stack gap="sm" style={{ padding: '0 16px 16px' }}>
            {schemeChips.length > 0 && (
              <Row style={{ gap: 8, flexWrap: 'wrap', paddingTop: 12 }}>
                {schemeChips.map((c) => <Badge key={c.key}>{c.text}</Badge>)}
              </Row>
            )}

            {registryTab === 'ranges' ? (
              <>
                <Row style={{ gap: 8 }}>
                  <Input
                    value={rangeQuery}
                    onChange={(e) => setRangeQuery(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') load(query, rangeQuery); }}
                    placeholder="Card prefix (finds the containing range), country, product, BIN…"
                    aria-label="Search scheme ranges"
                    style={{ maxWidth: 420 }}
                  />
                  <Button icon={Search} onClick={() => load(query, rangeQuery)}>Search ranges</Button>
                </Row>
                <DataTable
                  columns={rangeColumns}
                  rows={rangeRows}
                  rowKey={(r, i) => `${r.scheme}-${r.range_low}-${r.range_high}-${i}`}
                  loading={loading}
                  pageSize={25}
                  emptyVariant="data"
                />
              </>
            ) : (
              <>
                <Row style={{ gap: 8 }}>
                  <Input
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') load(query, rangeQuery); }}
                    placeholder="Search BIN prefix, scheme, country, product, issuer…"
                    aria-label="Search manual BINs"
                    style={{ maxWidth: 420 }}
                  />
                  <Button icon={Search} onClick={() => load(query, rangeQuery)}>Search BINs</Button>
                </Row>
                <DataTable
                  columns={binColumns}
                  rows={rows}
                  rowKey={(r) => r.bin}
                  loading={loading}
                  pageSize={25}
                  defaultSort={{ key: 'bin', dir: 'asc' }}
                  emptyVariant="data"
                />
              </>
            )}
          </Stack>
        </Card>
      </Stack>
    </Page>
  );
};

export default BinManagement;
