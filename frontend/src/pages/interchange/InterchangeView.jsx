import React, { useState, useEffect, useCallback } from 'react';
import { RefreshCw, FolderSync, ArrowUpFromLine, ArrowDownToLine, AlertTriangle, CheckCircle2, FileText } from 'lucide-react';
import api from '../../api/axios';
import EmptyState from '../../components/EmptyState';
import { showToast } from '../../contexts/ToastContext';

/* ════════════════════════════════════════════════════════════════════
   Interchange — scheme (Visa BASE II) clearing-file data integrity.

   Lists the clearing files read from the watched folder for one direction
   (Outgoing = submitted to VisaNet, Incoming = received from VisaNet),
   and drills into a file's transactions and its data-integrity
   violations (structural, balancing, field edit / requiredness).

   Data:
     GET  /api/interchange/files?direction=OUTGOING|INCOMING
     GET  /api/interchange/files/{id}
     POST /api/interchange/scan?direction=...        (manual "Scan now")
   ════════════════════════════════════════════════════════════════════ */

const sevColor = { ERROR: '#c0392b', WARN: '#b7791f', INFO: '#6b7280' };
const catLabel = { STRUCTURAL: 'Structural', BALANCING: 'Balancing', FIELD_EDIT: 'Field edit', REQUIRED: 'Required', DIMP: 'DIMP edit' };

const money = (v, ccy) => (v == null ? '' :
    Number(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + (ccy ? ' ' + ccy : ''));

const fmtDate = (d) => (d ? String(d).slice(0, 10) : '—');

const SevBadge = ({ severity }) => (
    <span style={{
        display: 'inline-block', padding: '1px 8px', borderRadius: 10, fontSize: 11, fontWeight: 700,
        color: '#fff', background: sevColor[severity] || '#6b7280',
    }}>{severity}</span>
);

const InterchangeView = ({ direction, title }) => {
    const isOut = direction === 'OUTGOING';
    const [files, setFiles] = useState([]);
    const [loading, setLoading] = useState(true);
    const [scanning, setScanning] = useState(false);
    const [selected, setSelected] = useState(null);   // file detail
    const [detailLoading, setDetailLoading] = useState(false);

    const loadFiles = useCallback(async () => {
        setLoading(true);
        try {
            const { data } = await api.get('/interchange/files', { params: { direction } });
            setFiles(data || []);
        } catch (e) {
            showToast(e?.response?.data?.message || 'Could not load interchange files', 'error');
        } finally {
            setLoading(false);
        }
    }, [direction]);

    useEffect(() => { setSelected(null); loadFiles(); }, [loadFiles]);

    const scanNow = async () => {
        setScanning(true);
        try {
            const { data } = await api.post('/interchange/scan', null, { params: { direction } });
            showToast(`Scanned ${data.scanned} file(s)`, 'success');
            await loadFiles();
        } catch (e) {
            showToast(e?.response?.data?.message || 'Scan failed', 'error');
        } finally {
            setScanning(false);
        }
    };

    const openFile = async (id) => {
        setDetailLoading(true);
        try {
            const { data } = await api.get(`/interchange/files/${id}`);
            setSelected(data);
        } catch (e) {
            showToast('Could not load file detail', 'error');
        } finally {
            setDetailLoading(false);
        }
    };

    const DirIcon = isOut ? ArrowUpFromLine : ArrowDownToLine;

    return (
        <div style={{ padding: '20px 24px', maxWidth: 1280, margin: '0 auto' }}>
            {/* Header */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                    <DirIcon size={22} />
                    <h1 style={{ margin: 0, fontSize: 22 }}>{title}</h1>
                </div>
                <div style={{ display: 'flex', gap: 8 }}>
                    <button onClick={loadFiles} disabled={loading} style={btnGhost}>
                        <RefreshCw size={15} style={{ marginRight: 6 }} />Refresh
                    </button>
                    <button onClick={scanNow} disabled={scanning} style={btnPrimary}>
                        <FolderSync size={15} style={{ marginRight: 6 }} />
                        {scanning ? 'Scanning…' : 'Scan folder now'}
                    </button>
                </div>
            </div>
            <p style={{ marginTop: 0, color: '#6b7280', fontSize: 13 }}>
                {isOut
                    ? 'Visa BASE II clearing files submitted to VisaNet, validated against scheme data-element requirements.'
                    : 'Visa BASE II clearing files received from VisaNet, validated against scheme data-element requirements.'}
            </p>

            {/* Files table */}
            {loading ? (
                <div style={{ padding: 40, textAlign: 'center', color: '#6b7280' }}>Loading…</div>
            ) : files.length === 0 ? (
                <EmptyState
                    icon={FileText}
                    title="No clearing files yet"
                    message={`Drop ${isOut ? 'outgoing' : 'incoming'} CTF files into the watched folder and click “Scan folder now”.`}
                />
            ) : (
                <div style={{ overflowX: 'auto', border: '1px solid var(--border, #e5e7eb)', borderRadius: 8 }}>
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                        <thead>
                            <tr style={{ textAlign: 'left', background: 'var(--surface-2, #f8fafc)' }}>
                                <th style={th}>File</th>
                                <th style={th}>Processing date</th>
                                <th style={thR}>Txns</th>
                                <th style={thR}>Records</th>
                                <th style={thR}>Batches</th>
                                <th style={thC}>Balanced</th>
                                <th style={thR}>Errors</th>
                                <th style={thR}>Warnings</th>
                                <th style={thC}>Status</th>
                            </tr>
                        </thead>
                        <tbody>
                            {files.map((f) => (
                                <tr key={f.id}
                                    onClick={() => openFile(f.id)}
                                    style={{ cursor: 'pointer', borderTop: '1px solid var(--border, #eef0f3)',
                                        background: selected?.file?.id === f.id ? 'var(--surface-2, #eef2ff)' : 'transparent' }}>
                                    <td style={td}>
                                        <span style={{ fontFamily: 'monospace' }}>{f.fileName}</span>
                                        {f.testFile && <span style={pill}>TEST</span>}
                                    </td>
                                    <td style={td}>{fmtDate(f.processingDate)}</td>
                                    <td style={tdR}>{f.transactionCount}</td>
                                    <td style={tdR}>{f.recordCount}</td>
                                    <td style={tdR}>{f.batchCount}</td>
                                    <td style={tdC}>{f.balanced
                                        ? <CheckCircle2 size={16} color="#2e7d32" />
                                        : <AlertTriangle size={16} color="#c0392b" />}</td>
                                    <td style={{ ...tdR, color: f.errorCount > 0 ? '#c0392b' : 'inherit', fontWeight: f.errorCount > 0 ? 700 : 400 }}>{f.errorCount}</td>
                                    <td style={{ ...tdR, color: f.warningCount > 0 ? '#b7791f' : 'inherit' }}>{f.warningCount}</td>
                                    <td style={tdC}>
                                        <span style={{ ...pill, background: f.status === 'PARSED' ? '#e6f4ea' : '#fdecea',
                                            color: f.status === 'PARSED' ? '#1e7e34' : '#c0392b' }}>{f.status}</span>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}

            {/* Detail */}
            {detailLoading && <div style={{ padding: 20, color: '#6b7280' }}>Loading file…</div>}
            {selected && !detailLoading && <FileDetail detail={selected} onClose={() => setSelected(null)} />}
        </div>
    );
};

const FileDetail = ({ detail, onClose }) => {
    const f = detail.file || {};
    const txns = detail.transactions || [];
    const violations = detail.violations || [];
    const [tab, setTab] = useState(violations.length ? 'violations' : 'transactions');

    return (
        <div style={{ marginTop: 24, border: '1px solid var(--border, #e5e7eb)', borderRadius: 10, overflow: 'hidden' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                padding: '12px 16px', background: 'var(--surface-2, #f8fafc)', borderBottom: '1px solid var(--border,#e5e7eb)' }}>
                <div style={{ fontFamily: 'monospace', fontWeight: 700 }}>{f.fileName}</div>
                <div style={{ display: 'flex', gap: 16, alignItems: 'center', fontSize: 13, color: '#6b7280' }}>
                    <span>CIB {f.centerInfoBlock || '—'}</span>
                    <span>{f.transactionCount} txns · {f.recordCount} records</span>
                    <span style={{ color: f.balanced ? '#2e7d32' : '#c0392b', fontWeight: 700 }}>
                        {f.balanced ? 'Balanced' : 'Out of balance'}</span>
                    <button onClick={onClose} style={btnGhost}>Close</button>
                </div>
            </div>

            <div style={{ display: 'flex', gap: 0, borderBottom: '1px solid var(--border,#eef0f3)' }}>
                <TabBtn active={tab === 'violations'} onClick={() => setTab('violations')}>
                    Violations ({violations.length})
                </TabBtn>
                <TabBtn active={tab === 'transactions'} onClick={() => setTab('transactions')}>
                    Transactions ({txns.length})
                </TabBtn>
            </div>

            {tab === 'violations' && (
                violations.length === 0
                    ? <div style={{ padding: 24, color: '#2e7d32', display: 'flex', gap: 8, alignItems: 'center' }}>
                        <CheckCircle2 size={18} /> No data-integrity violations found.
                      </div>
                    : <div style={{ overflowX: 'auto' }}>
                        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12.5 }}>
                            <thead><tr style={{ textAlign: 'left', background: 'var(--surface-2,#f8fafc)' }}>
                                <th style={th}>Severity</th><th style={th}>Category</th><th style={th}>Rec#</th>
                                <th style={th}>TC/TCR</th><th style={th}>Field</th><th style={th}>Rule</th>
                                <th style={th}>Message</th><th style={th}>Expected</th><th style={th}>Actual</th>
                            </tr></thead>
                            <tbody>
                                {violations.map((v) => (
                                    <tr key={v.id} style={{ borderTop: '1px solid var(--border,#eef0f3)' }}>
                                        <td style={td}><SevBadge severity={v.severity} /></td>
                                        <td style={td}>{catLabel[v.category] || v.category}</td>
                                        <td style={td}>{v.recordNo ?? '—'}</td>
                                        <td style={td}>{v.transactionCode || '—'}{v.tcr ? `/${v.tcr}` : ''}</td>
                                        <td style={td}>{v.fieldName || '—'}{v.fieldPosition ? ` (${v.fieldPosition})` : ''}</td>
                                        <td style={{ ...td, fontFamily: 'monospace' }}>{v.ruleCode}</td>
                                        <td style={td}>{v.message}</td>
                                        <td style={{ ...td, fontFamily: 'monospace' }}>{v.expectedValue ?? ''}</td>
                                        <td style={{ ...td, fontFamily: 'monospace' }}>{v.actualValue ?? ''}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                      </div>
            )}

            {tab === 'transactions' && (
                <div style={{ overflowX: 'auto' }}>
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12.5 }}>
                        <thead><tr style={{ textAlign: 'left', background: 'var(--surface-2,#f8fafc)' }}>
                            <th style={th}>#</th><th style={th}>TC</th><th style={th}>TCRs</th><th style={th}>Account</th>
                            <th style={th}>ARN</th><th style={th}>Merchant</th><th style={th}>MCC</th>
                            <th style={thR}>Source</th><th style={thR}>Destination</th><th style={th}>FPI</th><th style={thR}>Issues</th>
                        </tr></thead>
                        <tbody>
                            {txns.map((t) => (
                                <tr key={t.id} style={{ borderTop: '1px solid var(--border,#eef0f3)' }}>
                                    <td style={td}>{t.seqInFile}</td>
                                    <td style={td}>{t.transactionCode}</td>
                                    <td style={{ ...td, fontFamily: 'monospace' }}>{t.tcrPresent}</td>
                                    <td style={{ ...td, fontFamily: 'monospace' }}>{t.accountMasked || '—'}</td>
                                    <td style={{ ...td, fontFamily: 'monospace' }}>{t.acquirerRefNumber || '—'}</td>
                                    <td style={td}>{t.merchantName} {t.merchantCountry ? `· ${t.merchantCountry}` : ''}</td>
                                    <td style={td}>{t.mcc}</td>
                                    <td style={tdR}>{money(t.sourceAmount, t.sourceCcy)}</td>
                                    <td style={tdR}>{money(t.destinationAmount, t.destinationCcy)}</td>
                                    <td style={td}>{t.feeProgramInd || '—'}</td>
                                    <td style={{ ...tdR, color: t.violationCount > 0 ? '#c0392b' : 'inherit', fontWeight: t.violationCount > 0 ? 700 : 400 }}>{t.violationCount}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    );
};

const TabBtn = ({ active, onClick, children }) => (
    <button onClick={onClick} style={{
        padding: '10px 16px', border: 'none', background: 'transparent', cursor: 'pointer',
        fontSize: 13, fontWeight: active ? 700 : 500,
        borderBottom: active ? '2px solid var(--accent, #4f46e5)' : '2px solid transparent',
        color: active ? 'var(--accent, #4f46e5)' : 'inherit',
    }}>{children}</button>
);

const th = { padding: '8px 12px', fontWeight: 600, fontSize: 12, whiteSpace: 'nowrap' };
const thR = { ...th, textAlign: 'right' };
const thC = { ...th, textAlign: 'center' };
const td = { padding: '8px 12px', verticalAlign: 'top' };
const tdR = { ...td, textAlign: 'right', fontVariantNumeric: 'tabular-nums' };
const tdC = { ...td, textAlign: 'center' };
const pill = { display: 'inline-block', marginLeft: 8, padding: '1px 7px', borderRadius: 9, fontSize: 10.5,
    fontWeight: 700, background: '#eef2ff', color: '#4f46e5', verticalAlign: 'middle' };
const btnBase = { display: 'inline-flex', alignItems: 'center', padding: '7px 14px', borderRadius: 7,
    fontSize: 13, fontWeight: 600, cursor: 'pointer', border: '1px solid transparent' };
const btnPrimary = { ...btnBase, background: 'var(--accent, #4f46e5)', color: '#fff' };
const btnGhost = { ...btnBase, background: 'transparent', color: 'inherit', borderColor: 'var(--border, #d1d5db)' };

export default InterchangeView;
