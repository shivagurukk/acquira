import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Upload, Trash2, Download, RefreshCw, Folder, FileText, ChevronRight,
  HardDrive, AlertTriangle, FolderPlus, Pencil,
} from 'lucide-react';
import api, { UPLOAD_TIMEOUT, isTimeoutError } from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import {
  Card, Button, Badge, Alert, Stack, Row, DataTable, Checkbox, Modal,
  FormField, Input, useConfirm,
} from '../../components/ui';

/**
 * S3 file maintenance — the "Files" tab of Admin > S3 Report Storage.
 *
 * Browses the bucket one folder at a time (list with delimiter '/'), so a
 * bucket holding a year of per-merchant PDFs opens as {bankCode}/{YYYY-MM}/
 * rather than one 200k-row table. Upload and delete both act on the folder the
 * operator is currently looking at; the server scopes every key to the
 * configured prefix, so nothing here can touch another tenant's reports.
 *
 * Deletes are permanent unless the bucket has versioning on — which is the
 * bucket owner's business, not something this screen can detect — so every
 * delete is confirmed with the file names spelled out.
 */

const fmtBytes = (n) => {
  if (n == null) return '—';
  if (n < 1024) return `${n} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i += 1; }
  return `${v < 10 ? v.toFixed(1) : Math.round(v)} ${units[i]}`;
};

const fmtWhen = (iso) => {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit',
  });
};

const errMsg = (err, fallback) =>
  err?.response?.data?.message || err?.response?.data?.error || err?.message || fallback;

/** Breadcrumb segments for the current relative path, plus the prefix root. */
const crumbsFor = (path) => {
  const segs = (path || '').split('/').filter(Boolean);
  return segs.map((name, i) => ({ name, path: `${segs.slice(0, i + 1).join('/')}/` }));
};

export default function S3FileManager({ onGoToConfig }) {
  const confirm = useConfirm();
  const { tenantVersion } = useAuth();
  const fileInputRef = useRef(null);

  const [path, setPath] = useState('');          // relative to the configured prefix
  const [data, setData] = useState(null);        // last successful listing
  const [loading, setLoading] = useState(true);
  const [notConfigured, setNotConfigured] = useState(null);
  const [loadError, setLoadError] = useState(null);
  const [selected, setSelected] = useState(() => new Set());
  const [query, setQuery] = useState('');
  const [uploading, setUploading] = useState(false);
  const [busyKey, setBusyKey] = useState(null);  // key being downloaded or deleted
  // { mode: 'create' | 'rename', key?, isFolder?, value, saving, error }
  const [dialog, setDialog] = useState(null);

  const load = useCallback(async (nextPath) => {
    setLoading(true);
    setLoadError(null);
    try {
      const res = await api.get('/admin/s3-files', { params: { path: nextPath } });
      setData(res.data);
      setNotConfigured(null);
      setSelected(new Set());
    } catch (err) {
      if (err.response?.status === 409) {
        setNotConfigured(errMsg(err, 'S3 is not configured'));
        setData(null);
      } else {
        setLoadError(errMsg(err, 'Could not list the bucket'));
      }
    } finally {
      setLoading(false);
    }
  }, []);

  // Re-lists on a tenant switch too: the bucket, prefix and credentials all
  // belong to the tenant, so a stale listing here would show another bank's
  // folder names with this bank's delete buttons beside them.
  useEffect(() => { setPath(''); }, [tenantVersion]);
  useEffect(() => { load(path); }, [load, path, tenantVersion]);

  // Memoised off `data` rather than off a `data?.files || []` fallback, whose
  // fresh [] every render would re-run every downstream memo.
  const files   = useMemo(() => data?.files   || [], [data]);
  const folders = useMemo(() => data?.folders || [], [data]);

  const visibleFiles = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? files.filter((f) => f.name.toLowerCase().includes(q)) : files;
  }, [files, query]);

  const visibleFolders = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? folders.filter((f) => f.name.toLowerCase().includes(q)) : folders;
  }, [folders, query]);

  /* ── selection ── */

  const toggle = (key) => setSelected((prev) => {
    const next = new Set(prev);
    if (next.has(key)) next.delete(key); else next.add(key);
    return next;
  });

  const allVisibleSelected = visibleFiles.length > 0
    && visibleFiles.every((f) => selected.has(f.key));

  const toggleAll = () => setSelected((prev) => {
    if (allVisibleSelected) {
      const next = new Set(prev);
      visibleFiles.forEach((f) => next.delete(f.key));
      return next;
    }
    return new Set([...prev, ...visibleFiles.map((f) => f.key)]);
  });

  /* ── upload ── */

  const doUpload = async (picked) => {
    const list = Array.from(picked || []);
    if (!list.length) return;

    const clashes = list.filter((f) => files.some((e) => e.name === f.name));
    if (clashes.length) {
      const ok = await confirm({
        title: clashes.length === 1 ? 'Overwrite this file?' : `Overwrite ${clashes.length} files?`,
        message: `${clashes.map((f) => f.name).join(', ')} already exist${clashes.length === 1 ? 's' : ''} in this folder. `
               + 'S3 has no merge — uploading replaces the stored object.',
        confirmLabel: 'Upload and overwrite',
        tone: 'warning',
      });
      if (!ok) return;
    }

    setUploading(true);
    try {
      const fd = new FormData();
      list.forEach((f) => fd.append('files', f));
      const res = await api.post('/admin/s3-files/upload', fd, {
        params: { path },
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: UPLOAD_TIMEOUT,
      });

      const up = res.data.uploaded?.length || 0;
      const bad = res.data.failed || [];
      if (up) showToast(`Uploaded ${up} file${up === 1 ? '' : 's'} to S3`, 'success');
      bad.forEach((f) => showToast(`${f.name}: ${f.message}`, 'error', 8000));
      load(path);
    } catch (err) {
      // A timed-out upload may well have landed — say so rather than "failed",
      // because a blind retry silently overwrites whatever did arrive.
      showToast(isTimeoutError(err)
        ? 'The upload did not finish in time. Refresh the list before retrying — some files may already be in the bucket.'
        : errMsg(err, 'Upload failed'), 'error', 8000);
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  /* ── download ── */

  const doDownload = async (file) => {
    setBusyKey(file.key);
    try {
      const res = await api.get('/admin/s3-files/download', {
        params: { key: file.key },
        responseType: 'blob',
        timeout: UPLOAD_TIMEOUT,
      });
      const url = URL.createObjectURL(res.data);
      const a = document.createElement('a');
      a.href = url;
      a.download = file.name;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      showToast(errMsg(err, 'Download failed'), 'error');
    } finally {
      setBusyKey(null);
    }
  };

  /* ── create folder / rename ── */

  const openCreate = () => setDialog({ mode: 'create', value: '', saving: false, error: null });

  const openRename = (item, isFolder) => setDialog({
    mode: 'rename', key: item.key, isFolder, value: item.name, saving: false, error: null,
  });

  // Mirrors the server's sanitiseSegment so a bad name is caught before the
  // round trip; the server still enforces it, this only saves the trip.
  const nameError = (value) => {
    const v = (value || '').trim();
    if (!v) return 'A name is required';
    if (v.includes('/') || v.includes('\\')) return "A name cannot contain '/'";
    if (v === '.' || v === '..') return `'${v}' is not a usable name`;
    if (v.length > 200) return 'That name is too long (max 200 characters)';
    return null;
  };

  const submitDialog = async (e) => {
    e.preventDefault();
    if (!dialog) return;

    const name = dialog.value.trim();
    const bad = nameError(name);
    if (bad) { setDialog((d) => ({ ...d, error: bad })); return; }

    const creating = dialog.mode === 'create';

    // A folder rename copies every object under the prefix and is not atomic —
    // the operator should know that before starting, not after.
    if (!creating && dialog.isFolder) {
      const ok = await confirm({
        title: 'Rename this folder?',
        message: 'S3 has no rename for a prefix: every object under it is copied to the new '
               + 'name and the originals are then deleted. It is not atomic, so avoid it on a '
               + 'folder that a batch run is currently writing into.',
        confirmLabel: 'Copy and rename',
        tone: 'warning',
      });
      if (!ok) return;
    }

    setDialog((d) => ({ ...d, saving: true, error: null }));
    try {
      if (creating) {
        await api.post('/admin/s3-files/folder', { path, name });
        showToast(`Created folder "${name}"`, 'success');
      } else {
        const res = await api.post('/admin/s3-files/rename', { key: dialog.key, newName: name });
        const moved = res.data?.moved;
        showToast(moved
          ? `Renamed folder to "${name}" (${moved} object${moved === 1 ? '' : 's'} moved)`
          : `Renamed to "${name}"`, 'success');
      }
      setDialog(null);
      load(path);
    } catch (err) {
      // Stay open with the message in place: the name is almost always the
      // problem (already taken, illegal character), and retyping it is the fix.
      setDialog((d) => ({ ...d, saving: false, error: errMsg(err, 'That did not work') }));
    }
  };

  /* ── delete ── */

  const reportDelete = (res) => {
    const gone = res.deleted?.length || 0;
    const errors = res.errors || [];
    if (gone) showToast(`Deleted ${gone} object${gone === 1 ? '' : 's'} from S3`, 'success');
    errors.forEach((e) => showToast(`${e.key}: ${e.message}`, 'error', 8000));
    load(path);
  };

  const doDeleteOne = async (file) => {
    const ok = await confirm({
      title: 'Delete this file from S3?',
      message: `"${file.name}" (${fmtBytes(file.size)}) will be removed from s3://${data.bucket}/${file.key}. `
             + 'Unless the bucket has versioning enabled, this cannot be undone.',
      confirmLabel: 'Delete from S3',
      tone: 'danger',
    });
    if (!ok) return;

    setBusyKey(file.key);
    try {
      const res = await api.delete('/admin/s3-files', { params: { key: file.key } });
      reportDelete(res.data);
    } catch (err) {
      showToast(errMsg(err, 'Delete failed'), 'error');
    } finally {
      setBusyKey(null);
    }
  };

  const doDeleteSelected = async () => {
    const keys = [...selected];
    if (!keys.length) return;

    const names = files.filter((f) => selected.has(f.key)).map((f) => f.name);
    const preview = names.slice(0, 8).join(', ') + (names.length > 8 ? `, +${names.length - 8} more` : '');
    const ok = await confirm({
      title: `Delete ${keys.length} object${keys.length === 1 ? '' : 's'} from S3?`,
      message: `${preview}\n\nThey will be removed from s3://${data.bucket}/${data.basePrefix}${path}. `
             + 'Unless the bucket has versioning enabled, this cannot be undone.',
      confirmLabel: `Delete ${keys.length} object${keys.length === 1 ? '' : 's'}`,
      tone: 'danger',
    });
    if (!ok) return;

    setLoading(true);
    try {
      const res = await api.post('/admin/s3-files/delete', { keys });
      reportDelete(res.data);
    } catch (err) {
      showToast(errMsg(err, 'Delete failed'), 'error');
      setLoading(false);
    }
  };

  /* ── states that replace the table entirely ── */

  if (notConfigured) {
    return (
      <Card pad>
        <Stack gap="md">
          <Alert tone="warning" icon={AlertTriangle} title="S3 is not ready yet">
            {notConfigured}
          </Alert>
          <p style={{ margin: 0, fontSize: '0.82rem', color: 'var(--text-secondary)' }}>
            Enable S3 archiving and save a bucket plus credentials on the configuration
            tab, then come back here to browse the bucket.
          </p>
          {onGoToConfig && (
            <Row>
              <Button variant="primary" icon={HardDrive} onClick={onGoToConfig}>
                Open configuration
              </Button>
            </Row>
          )}
        </Stack>
      </Card>
    );
  }

  const columns = [
    {
      key: '_sel',
      width: 40,
      header: (
        <Checkbox
          checked={allVisibleSelected}
          onChange={toggleAll}
          aria-label="Select all files in this folder"
        />
      ),
      render: (r) => (
        <Checkbox
          checked={selected.has(r.key)}
          onChange={() => toggle(r.key)}
          aria-label={`Select ${r.name}`}
        />
      ),
    },
    {
      key: 'name',
      header: 'File',
      sortable: true,
      render: (r) => (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
          <FileText size={14} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
          <span style={{ fontWeight: 500, wordBreak: 'break-all' }}>{r.name}</span>
        </span>
      ),
    },
    { key: 'size', header: 'Size', align: 'right', numeric: true, sortable: true, render: (r) => fmtBytes(r.size) },
    { key: 'lastModified', header: 'Last modified', sortable: true, nowrap: true, muted: true, render: (r) => fmtWhen(r.lastModified) },
    {
      key: 'storageClass',
      header: 'Class',
      render: (r) => (r.storageClass ? <Badge tone="neutral">{r.storageClass}</Badge> : '—'),
    },
    {
      key: '_actions',
      header: '',
      align: 'right',
      nowrap: true,
      render: (r) => (
        <Row>
          <Button
            variant="ghost"
            size="sm"
            iconOnly
            icon={Pencil}
            aria-label={`Rename ${r.name}`}
            disabled={busyKey === r.key}
            onClick={() => openRename(r, false)}
          />
          <Button
            variant="ghost"
            size="sm"
            iconOnly
            icon={Download}
            aria-label={`Download ${r.name}`}
            loading={busyKey === r.key}
            onClick={() => doDownload(r)}
          />
          <Button
            variant="danger-ghost"
            size="sm"
            iconOnly
            icon={Trash2}
            aria-label={`Delete ${r.name}`}
            disabled={busyKey === r.key}
            onClick={() => doDeleteOne(r)}
          />
        </Row>
      ),
    },
  ];

  const crumbs = crumbsFor(path);

  return (
    <Stack gap="lg">
      {loadError && <Alert tone="danger" title="Could not list the bucket">{loadError}</Alert>}

      {/* ── Location + upload ────────────────────────────────────────────── */}
      <Card
        title="Bucket location"
        subtitle={data ? `s3://${data.bucket}/${data.basePrefix}${path}` : 'Loading…'}
        actions={
          <Row>
            <input
              ref={fileInputRef}
              type="file"
              multiple
              hidden
              onChange={(e) => doUpload(e.target.files)}
            />
            <Button
              icon={RefreshCw}
              size="sm"
              onClick={() => load(path)}
              disabled={loading || uploading}
            >
              Refresh
            </Button>
            <Button
              icon={FolderPlus}
              size="sm"
              onClick={openCreate}
              disabled={loading || uploading}
            >
              New folder
            </Button>
            <Button
              variant="primary"
              icon={Upload}
              size="sm"
              loading={uploading}
              onClick={() => fileInputRef.current?.click()}
            >
              Upload files
            </Button>
          </Row>
        }
        pad
      >
        <Stack gap="md">
          {/* Breadcrumbs — the prefix root is the first crumb and is never navigable away from. */}
          <div
            className="ui-row"
            style={{ flexWrap: 'wrap', gap: 4, fontSize: '0.82rem', alignItems: 'center' }}
          >
            <Button
              variant={path ? 'ghost' : 'subtle'}
              size="sm"
              icon={HardDrive}
              onClick={() => setPath('')}
              disabled={!path}
            >
              {data?.basePrefix ? data.basePrefix.replace(/\/$/, '') : 'bucket root'}
            </Button>
            {crumbs.map((c, i) => (
              <span key={c.path} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                <ChevronRight size={13} style={{ color: 'var(--text-muted)' }} />
                <Button
                  variant={i === crumbs.length - 1 ? 'subtle' : 'ghost'}
                  size="sm"
                  onClick={() => setPath(c.path)}
                  disabled={i === crumbs.length - 1}
                >
                  {c.name}
                </Button>
              </span>
            ))}
          </div>

          <p style={{ margin: 0, fontSize: '0.78rem', color: 'var(--text-secondary)' }}>
            Uploads land in the folder shown above. S3 has no real directories: a
            folder appears the moment a file is uploaded under its name, and
            &ldquo;New folder&rdquo; writes an empty placeholder so you can create
            one before there is anything to put in it. Renaming copies and then
            deletes, since S3 has no rename either.
          </p>

          {data?.truncated && (
            <Alert tone="info">
              This folder holds more than 1,000 objects — only the first page is shown.
              Open a sub-folder to narrow the listing.
            </Alert>
          )}
        </Stack>
      </Card>

      {/* ── Folders ──────────────────────────────────────────────────────── */}
      {visibleFolders.length > 0 && (
        <Card title="Folders" subtitle={`${visibleFolders.length} sub-folder(s)`} pad>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--space-sm)' }}>
            {visibleFolders.map((f) => (
              <Row key={f.key} style={{ gap: 2 }}>
                <Button icon={Folder} size="sm" onClick={() => setPath(f.path)}>
                  {f.name}
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  iconOnly
                  icon={Pencil}
                  aria-label={`Rename folder ${f.name}`}
                  onClick={() => openRename(f, true)}
                />
              </Row>
            ))}
          </div>
        </Card>
      )}

      {/* ── Files ────────────────────────────────────────────────────────── */}
      <Card
        title="Files"
        subtitle={loading ? 'Loading…' : `${files.length} object(s) in this folder`}
        actions={
          selected.size > 0 ? (
            <Button
              variant="danger"
              icon={Trash2}
              size="sm"
              onClick={doDeleteSelected}
              disabled={loading}
            >
              Delete {selected.size} selected
            </Button>
          ) : null
        }
      >
        <DataTable
          columns={columns}
          rows={visibleFiles}
          rowKey={(r) => r.key}
          loading={loading}
          compact
          pageSize={25}
          defaultSort={{ key: 'lastModified', dir: 'desc' }}
          search={{ value: query, onChange: setQuery, placeholder: 'Search files in this folder…' }}
          empty={
            <span style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
              {query
                ? 'No files match that search.'
                : 'This folder holds no objects yet. Upload a file to get started.'}
            </span>
          }
        />
      </Card>

      {/* ── New folder / rename ──────────────────────────────────────────── */}
      <Modal
        open={!!dialog}
        onClose={() => setDialog(null)}
        as="form"
        onSubmit={submitDialog}
        size="sm"
        title={
          dialog?.mode === 'create'
            ? 'New folder'
            : `Rename ${dialog?.isFolder ? 'folder' : 'file'}`
        }
        subtitle={
          dialog?.mode === 'create'
            ? `Created inside ${data?.basePrefix || ''}${path || ''}`
            : dialog?.key
        }
        footer={
          <>
            <Button onClick={() => setDialog(null)} disabled={dialog?.saving}>Cancel</Button>
            <Button
              type="submit"
              variant="primary"
              loading={dialog?.saving}
              disabled={!dialog?.value?.trim()}
            >
              {dialog?.mode === 'create' ? 'Create folder' : 'Rename'}
            </Button>
          </>
        }
      >
        <Stack gap="md">
          <FormField
            label={dialog?.mode === 'create' ? 'Folder name' : 'New name'}
            hint={dialog?.isFolder
              ? 'The folder stays where it is; only its name changes.'
              : "No '/' — this names one folder or file, it does not move it."}
          >
            <Input
              value={dialog?.value || ''}
              onChange={(e) => setDialog((d) => ({ ...d, value: e.target.value, error: null }))}
              placeholder={dialog?.mode === 'create' ? '2026-09' : 'new-name.pdf'}
              autoComplete="off"
              autoFocus
              mono
            />
          </FormField>

          {dialog?.error && <Alert tone="danger">{dialog.error}</Alert>}
        </Stack>
      </Modal>
    </Stack>
  );
}
