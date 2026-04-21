import React, { useState, useEffect, useCallback } from 'react';
import {
  MailOpen, Plus, Edit2, Trash2, X, Check, Send, Eye, Pause, Play, RotateCcw,
  FileText, AlertTriangle, Zap, ChevronDown, ChevronUp, Loader2, Users,
  CheckCircle, XCircle, Clock, RefreshCw, Copy, Code, Variable
} from 'lucide-react';
import api from '../../api/axios';

const card = { background: '#fff', borderRadius: 12, padding: 24, boxShadow: '0 1px 3px rgba(0,0,0,.08)', border: '1px solid #e5e7eb' };
const badge = (c) => ({ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 10px', borderRadius: 12, fontSize: 12, fontWeight: 600, background: c + '18', color: c });
const btn = (bg = '#2563eb', fg = '#fff') => ({ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '8px 16px', borderRadius: 8, background: bg, color: fg, border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600 });
const input = { width: '100%', padding: '8px 12px', borderRadius: 8, border: '1px solid #d1d5db', fontSize: 14, outline: 'none', boxSizing: 'border-box' };
const selectS = { ...input, background: '#fff' };
const labelS = { display: 'block', fontSize: 12, fontWeight: 600, color: '#374151', marginBottom: 4 };
const overlay = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.4)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 };
const modal = { background: '#fff', borderRadius: 16, padding: 28, width: '95%', maxWidth: 720, maxHeight: '90vh', overflow: 'auto', boxShadow: '0 20px 60px rgba(0,0,0,.2)' };

const STATUS_COLORS = { DRAFT: '#6b7280', SCHEDULED: '#f59e0b', SENDING: '#2563eb', COMPLETED: '#16a34a', PAUSED: '#9ca3af', FAILED: '#dc2626' };
const TYPE_COLORS = { STATEMENT: '#8b5cf6', WELCOME: '#06b6d4', ALERT: '#dc2626', PROMOTION: '#f59e0b', CUSTOM: '#6b7280' };

const MERGE_VARS = ['merchant_name', 'mid', 'contact_name', 'contact_email', 'month', 'year', 'total_volume', 'total_count', 'total_msf', 'merchant_status', 'city', 'onboarding_date', 'days_since_last_txn', 'tenant_name', 'store_count', 'terminal_count'];

// ─── Templates Tab ───────────────────────────────────────────
const TemplatesTab = () => {
  const [templates, setTemplates] = useState([]);
  const [loading, setLoading] = useState(true);
  const [modalOpen, setModalOpen] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(null);
  const [editId, setEditId] = useState(null);
  const [form, setForm] = useState({ name: '', templateType: 'CUSTOM', subjectTemplate: '', bodyHtml: '', isActive: true, isDefaultForType: false });

  const load = async () => { try { const r = await api.get('/email-campaigns/templates'); setTemplates(r.data); } catch (e) { console.error(e); } finally { setLoading(false); } };
  useEffect(() => { load(); }, []);

  const openAdd = () => { setForm({ name: '', templateType: 'CUSTOM', subjectTemplate: '', bodyHtml: '', isActive: true, isDefaultForType: false }); setEditId(null); setModalOpen(true); };
  const openEdit = (t) => { setForm({ ...t }); setEditId(t.id); setModalOpen(true); };

  const save = async () => {
    try {
      if (editId) await api.put(`/email-campaigns/templates/${editId}`, form);
      else await api.post('/email-campaigns/templates', form);
      setModalOpen(false); load();
    } catch (e) { alert('Save failed: ' + e.message); }
  };

  const remove = async (id) => { if (window.confirm('Delete template?')) { await api.delete(`/email-campaigns/templates/${id}`); load(); } };

  const preview = async (id) => {
    try { const r = await api.post(`/email-campaigns/templates/${id}/preview`); setPreviewOpen(r.data); }
    catch (e) { alert('Preview failed'); }
  };

  const insertVar = (varName) => {
    setForm(f => ({ ...f, bodyHtml: f.bodyHtml + `{{${varName}}}` }));
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Loader2 size={28} className="spin" /></div>;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <div style={{ fontSize: 14, color: '#6b7280' }}>{templates.length} template{templates.length !== 1 ? 's' : ''}</div>
        <button style={btn()} onClick={openAdd}><Plus size={16} /> New Template</button>
      </div>

      {templates.length === 0 ? (
        <div style={{ ...card, textAlign: 'center', padding: 60, color: '#9ca3af' }}><FileText size={40} style={{ margin: '0 auto 12px', opacity: .4 }} /><div>No templates. Create your first email template.</div></div>
      ) : (
        <div style={{ display: 'grid', gap: 12 }}>
          {templates.map(t => (
            <div key={t.id} style={{ ...card, opacity: t.isActive === false ? 0.5 : 1 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                    <span style={{ fontWeight: 600, fontSize: 14 }}>{t.name}</span>
                    <span style={badge(TYPE_COLORS[t.templateType] || '#6b7280')}>{t.templateType}</span>
                    {t.isDefaultForType && <span style={badge('#16a34a')}>Default</span>}
                  </div>
                  <div style={{ fontSize: 12, color: '#6b7280', fontFamily: 'monospace' }}>Subject: {t.subjectTemplate}</div>
                </div>
                <div style={{ display: 'flex', gap: 6 }}>
                  <button style={btn('#eff6ff', '#2563eb')} onClick={() => preview(t.id)}><Eye size={14} /> Preview</button>
                  <button style={btn('#f3f4f6', '#374151')} onClick={() => openEdit(t)}><Edit2 size={14} /></button>
                  <button style={btn('#fee2e2', '#dc2626')} onClick={() => remove(t.id)}><Trash2 size={14} /></button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Template Editor Modal */}
      {modalOpen && (
        <div style={overlay} onClick={() => setModalOpen(false)}>
          <div style={{ ...modal, maxWidth: 900 }} onClick={e => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 20 }}>
              <h2 style={{ fontSize: 18, fontWeight: 700 }}>{editId ? 'Edit' : 'New'} Email Template</h2>
              <button style={{ background: 'none', border: 'none', cursor: 'pointer' }} onClick={() => setModalOpen(false)}><X size={20} /></button>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
              <div><label style={labelS}>Template Name</label><input style={input} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} placeholder="Monthly Statement" /></div>
              <div><label style={labelS}>Type</label>
                <select style={selectS} value={form.templateType} onChange={e => setForm({ ...form, templateType: e.target.value })}>
                  <option value="STATEMENT">Statement</option><option value="WELCOME">Welcome</option>
                  <option value="ALERT">Alert</option><option value="PROMOTION">Promotion</option><option value="CUSTOM">Custom</option>
                </select>
              </div>
              <div style={{ gridColumn: 'span 2' }}>
                <label style={labelS}>Subject Line <span style={{ fontWeight: 400, color: '#9ca3af' }}>— use {'{{variable}}'} syntax</span></label>
                <input style={input} value={form.subjectTemplate} onChange={e => setForm({ ...form, subjectTemplate: e.target.value })} placeholder="Your {{month}} Statement - {{merchant_name}}" />
              </div>
              <div style={{ gridColumn: 'span 2' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
                  <label style={{ ...labelS, marginBottom: 0 }}>Email Body (HTML)</label>
                  <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                    {MERGE_VARS.slice(0, 8).map(v => (
                      <button key={v} onClick={() => insertVar(v)} style={{ padding: '2px 8px', borderRadius: 6, border: '1px solid #e5e7eb', background: '#f9fafb', fontSize: 11, cursor: 'pointer', color: '#4b5563' }}>
                        {`{{${v}}}`}
                      </button>
                    ))}
                    <span style={{ fontSize: 11, color: '#9ca3af', padding: '4px 0' }}>+{MERGE_VARS.length - 8} more</span>
                  </div>
                </div>
                <textarea style={{ ...input, minHeight: 250, fontFamily: 'monospace', fontSize: 12 }} value={form.bodyHtml} onChange={e => setForm({ ...form, bodyHtml: e.target.value })} />
              </div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 20 }}>
              <button style={btn('#f3f4f6', '#374151')} onClick={() => setModalOpen(false)}>Cancel</button>
              <button style={btn()} onClick={save}><Check size={16} /> {editId ? 'Update' : 'Create'}</button>
            </div>
          </div>
        </div>
      )}

      {/* Preview Modal */}
      {previewOpen && (
        <div style={overlay} onClick={() => setPreviewOpen(null)}>
          <div style={{ ...modal, maxWidth: 700 }} onClick={e => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
              <h2 style={{ fontSize: 16, fontWeight: 700 }}>Email Preview (Sample Data)</h2>
              <button style={{ background: 'none', border: 'none', cursor: 'pointer' }} onClick={() => setPreviewOpen(null)}><X size={20} /></button>
            </div>
            <div style={{ padding: '8px 14px', background: '#f3f4f6', borderRadius: 8, marginBottom: 12, fontSize: 13 }}>
              <strong>Subject:</strong> {previewOpen.subject}
            </div>
            <div style={{ border: '1px solid #e5e7eb', borderRadius: 8, overflow: 'hidden', maxHeight: 500, overflowY: 'auto' }}
              dangerouslySetInnerHTML={{ __html: previewOpen.body }} />
          </div>
        </div>
      )}
    </div>
  );
};

// ─── Campaigns Tab ───────────────────────────────────────────
const CampaignsTab = () => {
  const [campaigns, setCampaigns] = useState([]);
  const [templates, setTemplates] = useState([]);
  const [loading, setLoading] = useState(true);
  const [modalOpen, setModalOpen] = useState(false);
  const [recipientCount, setRecipientCount] = useState(null);
  const [form, setForm] = useState({ name: '', templateId: '', campaignType: 'BULK', recipientFilterJson: '', attachmentType: 'NONE', statementMonth: new Date().toISOString().slice(0, 7) });
  const [expanded, setExpanded] = useState(null);

  const load = async () => {
    try {
      const [c, t] = await Promise.all([api.get('/email-campaigns/campaigns'), api.get('/email-campaigns/templates')]);
      setCampaigns(c.data); setTemplates(t.data);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); const t = setInterval(load, 10000); return () => clearInterval(t); }, []);

  const previewRecipients = async () => {
    try {
      const r = await api.post('/email-campaigns/campaigns/preview-recipients', { filterJson: form.recipientFilterJson || '{}' });
      setRecipientCount(r.data);
    } catch (e) { console.error(e); }
  };

  const save = async () => {
    try {
      await api.post('/email-campaigns/campaigns', form);
      setModalOpen(false); load();
    } catch (e) { alert('Save failed: ' + e.message); }
  };

  const launch = async (id) => {
    if (!window.confirm('Launch this campaign? Emails will be sent immediately.')) return;
    try { await api.post(`/email-campaigns/campaigns/${id}/launch`); load(); }
    catch (e) { alert('Launch failed'); }
  };

  const retryFailed = async (id) => {
    try { await api.post(`/email-campaigns/campaigns/${id}/retry-failed`); load(); }
    catch (e) { alert('Retry failed'); }
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Loader2 size={28} className="spin" /></div>;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <div style={{ fontSize: 14, color: '#6b7280' }}>{campaigns.length} campaign{campaigns.length !== 1 ? 's' : ''}</div>
        <button style={btn()} onClick={() => { setForm({ name: '', templateId: templates[0]?.id || '', campaignType: 'BULK', recipientFilterJson: '', attachmentType: 'NONE', statementMonth: new Date().toISOString().slice(0, 7) }); setRecipientCount(null); setModalOpen(true); }}><Plus size={16} /> New Campaign</button>
      </div>

      {campaigns.length === 0 ? (
        <div style={{ ...card, textAlign: 'center', padding: 60, color: '#9ca3af' }}><Send size={40} style={{ margin: '0 auto 12px', opacity: .4 }} /><div>No campaigns. Create your first email campaign.</div></div>
      ) : (
        <div style={{ display: 'grid', gap: 12 }}>
          {campaigns.map(c => (
            <div key={c.id} style={card}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', cursor: 'pointer' }} onClick={() => setExpanded(expanded === c.id ? null : c.id)}>
                <div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                    <span style={{ fontWeight: 600, fontSize: 14 }}>{c.name}</span>
                    <span style={badge(STATUS_COLORS[c.status] || '#6b7280')}>
                      {c.status === 'SENDING' && <Loader2 size={12} className="spin" />} {c.status}
                    </span>
                    <span style={badge(TYPE_COLORS[c.template?.templateType] || '#6b7280')}>{c.template?.name || 'No template'}</span>
                  </div>
                  <div style={{ fontSize: 12, color: '#6b7280' }}>
                    Recipients: {c.totalRecipients || '—'} • Sent: {c.sentCount || 0} • Failed: {c.failedCount || 0}
                    {c.statementMonth && <> • Period: {c.statementMonth}</>}
                  </div>
                </div>
                <div style={{ display: 'flex', gap: 6 }}>
                  {(c.status === 'DRAFT' || c.status === 'FAILED') && (
                    <button style={btn('#eff6ff', '#2563eb')} onClick={(e) => { e.stopPropagation(); launch(c.id); }}><Send size={14} /> Launch</button>
                  )}
                  {c.status === 'COMPLETED' && c.failedCount > 0 && (
                    <button style={btn('#fef3c7', '#d97706')} onClick={(e) => { e.stopPropagation(); retryFailed(c.id); }}><RotateCcw size={14} /> Retry Failed</button>
                  )}
                  {expanded === c.id ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
                </div>
              </div>

              {/* Progress bar for active campaigns */}
              {c.status === 'SENDING' && c.totalRecipients > 0 && (
                <div style={{ marginTop: 12 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: '#6b7280', marginBottom: 4 }}>
                    <span>Progress</span><span>{Math.round((c.sentCount + c.failedCount) / c.totalRecipients * 100)}%</span>
                  </div>
                  <div style={{ height: 6, borderRadius: 3, background: '#f3f4f6' }}>
                    <div style={{ height: '100%', borderRadius: 3, width: `${(c.sentCount + c.failedCount) / c.totalRecipients * 100}%`, background: '#2563eb', transition: 'width .5s' }} />
                  </div>
                </div>
              )}

              {expanded === c.id && (
                <div style={{ marginTop: 16, padding: 16, background: '#f9fafb', borderRadius: 8 }}>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, fontSize: 13 }}>
                    <div><strong>Type:</strong> {c.campaignType}</div>
                    <div><strong>Attachment:</strong> {c.attachmentType}</div>
                    <div><strong>Created:</strong> {c.createdAt ? new Date(c.createdAt).toLocaleString() : '—'}</div>
                    <div><strong>Sent At:</strong> {c.sentAt ? new Date(c.sentAt).toLocaleString() : '—'}</div>
                  </div>
                  {c.recipientFilterJson && c.recipientFilterJson !== '{}' && (
                    <div style={{ marginTop: 8, fontSize: 12, color: '#6b7280' }}>
                      <strong>Filter:</strong> <code style={{ background: '#e5e7eb', padding: '2px 6px', borderRadius: 4 }}>{c.recipientFilterJson}</code>
                    </div>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* New Campaign Modal */}
      {modalOpen && (
        <div style={overlay} onClick={() => setModalOpen(false)}>
          <div style={{ ...modal, maxWidth: 640 }} onClick={e => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 20 }}>
              <h2 style={{ fontSize: 18, fontWeight: 700 }}>New Email Campaign</h2>
              <button style={{ background: 'none', border: 'none', cursor: 'pointer' }} onClick={() => setModalOpen(false)}><X size={20} /></button>
            </div>
            <div style={{ display: 'grid', gap: 14 }}>
              <div><label style={labelS}>Campaign Name</label><input style={input} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} placeholder="January 2026 Statements" /></div>
              <div><label style={labelS}>Email Template</label>
                <select style={selectS} value={form.templateId} onChange={e => setForm({ ...form, templateId: e.target.value })}>
                  <option value="">— Select Template —</option>
                  {templates.filter(t => t.isActive !== false).map(t => <option key={t.id} value={t.id}>{t.name} ({t.templateType})</option>)}
                </select>
              </div>
              <div><label style={labelS}>Campaign Type</label>
                <select style={selectS} value={form.campaignType} onChange={e => setForm({ ...form, campaignType: e.target.value })}>
                  <option value="BULK">Bulk (All Merchants)</option>
                  <option value="TARGETED">Targeted (Filtered)</option>
                </select>
              </div>
              {form.campaignType === 'TARGETED' && (
                <div>
                  <label style={labelS}>Recipient Filter (JSON)</label>
                  <textarea style={{ ...input, minHeight: 80, fontFamily: 'monospace', fontSize: 12 }} value={form.recipientFilterJson}
                    onChange={e => setForm({ ...form, recipientFilterJson: e.target.value })}
                    placeholder='{"status":["ACTIVE"],"city":["Dubai","Riyadh"]}' />
                  <button style={{ ...btn('#f0fdf4', '#16a34a'), marginTop: 8 }} onClick={previewRecipients}>
                    <Users size={14} /> Preview Recipients
                  </button>
                  {recipientCount && (
                    <div style={{ marginTop: 8, fontSize: 13, color: '#16a34a', fontWeight: 600 }}>
                      {recipientCount.count} merchants match your criteria
                    </div>
                  )}
                </div>
              )}
              <div><label style={labelS}>Statement Month (for statement templates)</label>
                <input style={input} type="month" value={form.statementMonth} onChange={e => setForm({ ...form, statementMonth: e.target.value })} />
              </div>
              <div><label style={labelS}>Attachment</label>
                <select style={selectS} value={form.attachmentType} onChange={e => setForm({ ...form, attachmentType: e.target.value })}>
                  <option value="NONE">No Attachment</option>
                  <option value="STATEMENT_PDF">Statement PDF</option>
                </select>
              </div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 20 }}>
              <button style={btn('#f3f4f6', '#374151')} onClick={() => setModalOpen(false)}>Cancel</button>
              <button style={btn()} onClick={save}><Check size={16} /> Create Campaign</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

// ─── History Tab ─────────────────────────────────────────────
const HistoryTab = () => {
  const [logs, setLogs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await api.get(`/email-campaigns/campaign-logs?page=${page}&size=50`);
      setLogs(r.data.content || []); setTotal(r.data.totalElements || 0);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  }, [page]);
  useEffect(() => { load(); }, [load]);

  const statusIcon = (s) => {
    if (s === 'SENT') return <CheckCircle size={14} color="#16a34a" />;
    if (s === 'FAILED') return <XCircle size={14} color="#dc2626" />;
    if (s === 'PENDING') return <Clock size={14} color="#f59e0b" />;
    return <Clock size={14} color="#6b7280" />;
  };

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <div style={{ fontSize: 14, color: '#6b7280' }}>{total} log entries</div>
        <button style={btn('#f3f4f6', '#374151')} onClick={load}><RefreshCw size={14} /> Refresh</button>
      </div>

      {loading && logs.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 60 }}><Loader2 size={28} className="spin" /></div>
      ) : logs.length === 0 ? (
        <div style={{ ...card, textAlign: 'center', padding: 60, color: '#9ca3af' }}>No email logs yet</div>
      ) : (
        <div style={card}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ borderBottom: '2px solid #f3f4f6' }}>
                {['Status', 'Merchant', 'Email', 'Subject', 'Sent At', 'Error'].map(h =>
                  <th key={h} style={{ textAlign: 'left', padding: '8px 8px', color: '#6b7280', fontWeight: 600 }}>{h}</th>
                )}
              </tr>
            </thead>
            <tbody>
              {logs.map(l => (
                <tr key={l.id} style={{ borderBottom: '1px solid #f3f4f6' }}>
                  <td style={{ padding: 8 }}>{statusIcon(l.status)} <span style={{ marginLeft: 4 }}>{l.status}</span></td>
                  <td style={{ padding: 8, fontWeight: 500 }}>{l.merchantName || '—'}</td>
                  <td style={{ padding: 8, color: '#6b7280', fontSize: 12 }}>{l.recipientEmail || '—'}</td>
                  <td style={{ padding: 8, color: '#6b7280', fontSize: 12, maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{l.subjectRendered || '—'}</td>
                  <td style={{ padding: 8, color: '#6b7280', fontSize: 12 }}>{l.sentAt ? new Date(l.sentAt).toLocaleString() : '—'}</td>
                  <td style={{ padding: 8, color: '#dc2626', fontSize: 11, maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{l.errorMessage || ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {total > 50 && (
            <div style={{ display: 'flex', justifyContent: 'center', gap: 8, padding: '16px 0' }}>
              <button style={btn('#f3f4f6', '#374151')} disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</button>
              <span style={{ padding: '8px 12px', fontSize: 13, color: '#6b7280' }}>Page {page + 1} of {Math.ceil(total / 50)}</span>
              <button style={btn('#f3f4f6', '#374151')} disabled={(page + 1) * 50 >= total} onClick={() => setPage(p => p + 1)}>Next</button>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

// ═══════════════════════════════════════════════════════════════
const TABS = [
  { key: 'campaigns', label: 'Campaigns', icon: Send },
  { key: 'templates', label: 'Email Templates', icon: FileText },
  { key: 'history', label: 'Send History', icon: Clock },
];

const EmailCampaignHub = () => {
  const [activeTab, setActiveTab] = useState('campaigns');

  return (
    <div style={{ padding: '0 0 40px' }}>
      <div style={{ marginBottom: 24 }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, color: '#111', marginBottom: 4 }}>
          <MailOpen size={22} style={{ verticalAlign: 'middle', marginRight: 8, color: '#8b5cf6' }} />
          Email Campaign Hub
        </h1>
        <p style={{ fontSize: 13, color: '#6b7280', margin: 0 }}>
          Create customizable email templates with merge variables, build targeted campaigns, and track delivery.
        </p>
      </div>

      <div style={{ display: 'flex', gap: 2, marginBottom: 24, background: '#f3f4f6', borderRadius: 12, padding: 4 }}>
        {TABS.map(tab => {
          const Icon = tab.icon;
          const active = activeTab === tab.key;
          return (
            <button key={tab.key} onClick={() => setActiveTab(tab.key)} style={{
              flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
              padding: '10px 16px', borderRadius: 10, border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600,
              background: active ? '#fff' : 'transparent', color: active ? '#8b5cf6' : '#6b7280',
              boxShadow: active ? '0 1px 3px rgba(0,0,0,.1)' : 'none', transition: 'all .2s'
            }}>
              <Icon size={16} /> {tab.label}
            </button>
          );
        })}
      </div>

      {activeTab === 'campaigns' && <CampaignsTab />}
      {activeTab === 'templates' && <TemplatesTab />}
      {activeTab === 'history' && <HistoryTab />}

      <style>{`.spin { animation: spin 1s linear infinite; } @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }`}</style>
    </div>
  );
};

export default EmailCampaignHub;
