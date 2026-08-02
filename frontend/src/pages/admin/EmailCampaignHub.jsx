import { useState, useEffect, useCallback } from 'react';
import {
  MailOpen, Plus, Edit2, Trash2, Send, Eye, RotateCcw,
  FileText, Users, Clock, RefreshCw,
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import {
  Page, Stack, Row, Card, Button, Badge, StatusBadge, Alert, Tabs, DataTable, Modal,
  FormField, FormGrid, Input, Textarea, Select, useConfirm,
} from '../../components/ui';

/**
 * Admin > Email campaign hub.
 *
 * Three concerns:
 *  1. Campaigns — create, launch and monitor bulk/targeted merchant mailings.
 *  2. Templates — CRUD over reusable subject/body templates with merge variables.
 *  3. Send history — per-recipient delivery log.
 *
 * Anything that dispatches mail (launch, retry failed) is behind a confirm that
 * names the recipients.
 */

const TYPE_TONES = {
  STATEMENT: 'brand',
  WELCOME: 'info',
  ALERT: 'danger',
  PROMOTION: 'warning',
  CUSTOM: 'neutral',
};

const TEMPLATE_TYPES = [
  { value: 'STATEMENT', label: 'Statement' },
  { value: 'WELCOME', label: 'Welcome' },
  { value: 'ALERT', label: 'Alert' },
  { value: 'PROMOTION', label: 'Promotion' },
  { value: 'CUSTOM', label: 'Custom' },
];

const MERGE_VARS = ['merchant_name', 'mid', 'contact_name', 'contact_email', 'month', 'year', 'total_volume', 'total_count', 'total_msf', 'merchant_status', 'city', 'onboarding_date', 'days_since_last_txn', 'tenant_name', 'store_count', 'terminal_count'];

const emptyTemplate = {
  name: '', templateType: 'CUSTOM', subjectTemplate: '', bodyHtml: '',
  isActive: true, isDefaultForType: false,
};

const HISTORY_PAGE_SIZE = 50;

// ─── Templates Tab ───────────────────────────────────────────
const TemplatesTab = () => {
  const { tenantVersion } = useAuth();
  const confirm = useConfirm();
  const [templates, setTemplates] = useState([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(null);
  const [editId, setEditId] = useState(null);
  const [form, setForm] = useState(emptyTemplate);

  const load = async () => {
    try {
      const r = await api.get('/email-campaigns/templates');
      setTemplates(r.data);
    } catch (e) {
      console.error(e);
      showToast('Could not load templates', 'error');
    } finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [tenantVersion]);

  const openAdd = () => { setForm(emptyTemplate); setEditId(null); setModalOpen(true); };
  const openEdit = (t) => { setForm({ ...t }); setEditId(t.id); setModalOpen(true); };

  const save = async (e) => {
    e?.preventDefault();
    setSaving(true);
    try {
      if (editId) await api.put(`/email-campaigns/templates/${editId}`, form);
      else await api.post('/email-campaigns/templates', form);
      setModalOpen(false);
      showToast(editId ? 'Template updated' : 'Template created', 'success');
      load();
    } catch (e) {
      showToast(e?.response?.data?.error || `Save failed: ${e.message}`, 'error');
    } finally { setSaving(false); }
  };

  const remove = async (t) => {
    const ok = await confirm({
      title: 'Delete template?',
      message: `"${t.name}" will no longer be available to new campaigns. Campaigns already sent are not affected.`,
      confirmLabel: 'Delete template',
      tone: 'danger',
    });
    if (!ok) return;
    try {
      await api.delete(`/email-campaigns/templates/${t.id}`);
      showToast('Template deleted', 'success');
      load();
    } catch (e) {
      showToast(e?.response?.data?.error || 'Delete failed', 'error');
    }
  };

  const preview = async (id) => {
    try {
      const r = await api.post(`/email-campaigns/templates/${id}/preview`);
      setPreviewOpen(r.data);
    } catch {
      showToast('Preview failed', 'error');
    }
  };

  const insertVar = (varName) => {
    setForm(f => ({ ...f, bodyHtml: f.bodyHtml + `{{${varName}}}` }));
  };

  const columns = [
    {
      key: 'name',
      header: 'Template',
      sortable: true,
      render: (t) => <strong>{t.name}</strong>,
    },
    {
      key: 'templateType',
      header: 'Type',
      sortable: true,
      nowrap: true,
      render: (t) => (
        <span className="ui-row" style={{ gap: 6, flexWrap: 'nowrap' }}>
          <Badge tone={TYPE_TONES[t.templateType] || 'neutral'}>{t.templateType}</Badge>
          {t.isDefaultForType && <Badge tone="success">Default</Badge>}
        </span>
      ),
    },
    {
      key: 'subjectTemplate',
      header: 'Subject',
      mono: true,
      muted: true,
      render: (t) => t.subjectTemplate || '—',
    },
    {
      key: 'isActive',
      header: 'Status',
      sortable: true,
      render: (t) => <StatusBadge status={t.isActive === false ? 'Inactive' : 'Active'} />,
    },
    {
      key: '_actions',
      header: '',
      align: 'right',
      nowrap: true,
      render: (t) => (
        <>
          <Button size="sm" icon={Eye} onClick={() => preview(t.id)}>Preview</Button>
          <Button size="sm" variant="ghost" iconOnly icon={Edit2} onClick={() => openEdit(t)} aria-label={`Edit ${t.name}`} />
          <Button size="sm" variant="danger-ghost" iconOnly icon={Trash2} onClick={() => remove(t)} aria-label={`Delete ${t.name}`} />
        </>
      ),
    },
  ];

  return (
    <Stack gap="sm">
      <Row between>
        <span className="ui-field__hint">
          {templates.length} template{templates.length !== 1 ? 's' : ''}
        </span>
        <Button variant="primary" icon={Plus} onClick={openAdd}>New template</Button>
      </Row>

      <Card>
        <DataTable
          columns={columns}
          rows={templates}
          rowKey={(t) => t.id}
          loading={loading}
          defaultSort={{ key: 'name', dir: 'asc' }}
          empty={
            <div style={{ padding: 'var(--space-3xl)', textAlign: 'center' }}>
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginBottom: 14 }}>
                No templates yet. Create the first email template to start building campaigns.
              </p>
              <Button variant="subtle" icon={Plus} onClick={openAdd}>Add a template</Button>
            </div>
          }
        />
      </Card>

      {/* Template editor */}
      <Modal
        as="form"
        onSubmit={save}
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        size="xl"
        title={editId ? 'Edit email template' : 'New email template'}
        subtitle="Use {{variable}} syntax in the subject and body to merge merchant data at send time."
        footer={
          <>
            <Button type="button" onClick={() => setModalOpen(false)}>Cancel</Button>
            <Button type="submit" variant="primary" loading={saving}>
              {editId ? 'Update template' : 'Create template'}
            </Button>
          </>
        }
      >
        <div className="ui-stack ui-stack--sm">
          <FormGrid cols={2}>
            <FormField label="Template name" required>
              <Input
                value={form.name}
                onChange={e => setForm({ ...form, name: e.target.value })}
                placeholder="Monthly statement"
                required
              />
            </FormField>
            <FormField label="Type">
              <Select
                value={form.templateType}
                onChange={e => setForm({ ...form, templateType: e.target.value })}
                options={TEMPLATE_TYPES}
              />
            </FormField>
          </FormGrid>

          <FormField label="Subject line" hint="Merge variables are substituted per recipient.">
            <Input
              value={form.subjectTemplate}
              onChange={e => setForm({ ...form, subjectTemplate: e.target.value })}
              placeholder="Your {{month}} statement - {{merchant_name}}"
            />
          </FormField>

          <FormField label="Merge variables" hint="Select one to append it to the email body.">
            <div className="ui-row" style={{ gap: 4 }}>
              {MERGE_VARS.map(v => (
                <Button key={v} size="sm" variant="subtle" onClick={() => insertVar(v)}>
                  {`{{${v}}}`}
                </Button>
              ))}
            </div>
          </FormField>

          <FormField label="Email body (HTML)">
            <Textarea
              mono
              rows={14}
              value={form.bodyHtml}
              onChange={e => setForm({ ...form, bodyHtml: e.target.value })}
              placeholder={'<p>Hello {{contact_name}},</p>'}
            />
          </FormField>
        </div>
      </Modal>

      {/* Rendered preview */}
      <Modal
        open={!!previewOpen}
        onClose={() => setPreviewOpen(null)}
        size="lg"
        title="Email preview"
        subtitle="Rendered with sample merchant data."
      >
        {previewOpen && (
          <div className="ui-stack ui-stack--sm">
            <Alert tone="info" title="Subject">{previewOpen.subject}</Alert>
            <div
              style={{
                border: '1px solid var(--border)',
                borderRadius: 'var(--radius-md)',
                overflow: 'auto',
                maxHeight: 480,
                background: 'var(--bg-card)',
              }}
              dangerouslySetInnerHTML={{ __html: previewOpen.body }}
            />
          </div>
        )}
      </Modal>
    </Stack>
  );
};

// ─── Campaigns Tab ───────────────────────────────────────────
const emptyCampaign = () => ({
  name: '', templateId: '', campaignType: 'BULK', recipientFilterJson: '',
  attachmentType: 'NONE', statementMonth: new Date().toISOString().slice(0, 7),
});

const CampaignsTab = () => {
  const { tenantVersion } = useAuth();
  const confirm = useConfirm();
  const [campaigns, setCampaigns] = useState([]);
  const [templates, setTemplates] = useState([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [recipientCount, setRecipientCount] = useState(null);
  const [previewing, setPreviewing] = useState(false);
  const [form, setForm] = useState(emptyCampaign);
  const [detailsId, setDetailsId] = useState(null);

  // Read the detail row back out of `campaigns` so the 10s poll keeps it live.
  const details = campaigns.find((c) => c.id === detailsId) || null;

  const load = async () => {
    try {
      const [c, t] = await Promise.all([api.get('/email-campaigns/campaigns'), api.get('/email-campaigns/templates')]);
      setCampaigns(c.data); setTemplates(t.data);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); const t = setInterval(load, 10000); return () => clearInterval(t); }, [tenantVersion]);

  const openCreate = () => {
    setForm({ ...emptyCampaign(), templateId: templates[0]?.id || '' });
    setRecipientCount(null);
    setModalOpen(true);
  };

  const previewRecipients = async () => {
    setPreviewing(true);
    try {
      const r = await api.post('/email-campaigns/campaigns/preview-recipients', { filterJson: form.recipientFilterJson || '{}' });
      setRecipientCount(r.data);
    } catch (e) {
      console.error(e);
      showToast('Could not resolve recipients for that filter', 'error');
    } finally { setPreviewing(false); }
  };

  const save = async (e) => {
    e?.preventDefault();
    setSaving(true);
    try {
      await api.post('/email-campaigns/campaigns', form);
      setModalOpen(false);
      showToast('Campaign created as a draft. Launch it when you are ready to send.', 'success');
      load();
    } catch (e) {
      showToast(e?.response?.data?.error || `Save failed: ${e.message}`, 'error');
    } finally { setSaving(false); }
  };

  const launch = async (c) => {
    const audience = c.totalRecipients
      ? `${c.totalRecipients} recipient${c.totalRecipients === 1 ? '' : 's'}`
      : c.campaignType === 'TARGETED'
        ? 'every merchant matching the campaign filter'
        : 'every merchant with a contact email';
    const ok = await confirm({
      title: 'Launch campaign and send email?',
      message: `"${c.name}" will send immediately to ${audience} using the template "${c.template?.name || 'none selected'}". Mail that has gone out cannot be recalled.`,
      confirmLabel: 'Launch and send',
      tone: 'warning',
    });
    if (!ok) return;
    try {
      await api.post(`/email-campaigns/campaigns/${c.id}/launch`);
      showToast('Campaign launched', 'success');
      load();
    } catch (e) {
      showToast(e?.response?.data?.error || 'Launch failed', 'error');
    }
  };

  const retryFailed = async (c) => {
    const ok = await confirm({
      title: 'Retry failed sends?',
      message: `The ${c.failedCount} recipient${c.failedCount === 1 ? '' : 's'} that failed in "${c.name}" will be emailed again.`,
      confirmLabel: 'Retry sends',
      tone: 'warning',
    });
    if (!ok) return;
    try {
      await api.post(`/email-campaigns/campaigns/${c.id}/retry-failed`);
      showToast('Retry queued', 'success');
      load();
    } catch (e) {
      showToast(e?.response?.data?.error || 'Retry failed', 'error');
    }
  };

  const progressPct = (c) =>
    c.totalRecipients > 0
      ? Math.round(((c.sentCount || 0) + (c.failedCount || 0)) / c.totalRecipients * 100)
      : 0;

  const columns = [
    {
      key: 'name',
      header: 'Campaign',
      sortable: true,
      render: (c) => <strong>{c.name}</strong>,
    },
    {
      key: 'status',
      header: 'Status',
      sortable: true,
      nowrap: true,
      render: (c) => (c.status === 'SENDING'
        ? <Badge tone="info" dot>Sending</Badge>
        : <StatusBadge status={c.status} />),
    },
    {
      key: '_template',
      header: 'Template',
      sortValue: (c) => c.template?.name || '',
      sortable: true,
      render: (c) => (
        <Badge tone={TYPE_TONES[c.template?.templateType] || 'neutral'}>
          {c.template?.name || 'No template'}
        </Badge>
      ),
    },
    {
      key: 'statementMonth',
      header: 'Period',
      sortable: true,
      muted: true,
      nowrap: true,
      render: (c) => c.statementMonth || '—',
    },
    {
      key: '_delivery',
      header: 'Delivery',
      width: 200,
      sortValue: (c) => c.sentCount || 0,
      render: (c) => (
        <div>
          <span className="ui-row" style={{ gap: 6, flexWrap: 'nowrap' }}>
            <span className="ui-td--num">{c.sentCount || 0}</span>
            <span className="ui-td--muted">of {c.totalRecipients || '—'} sent</span>
            {c.failedCount > 0 && <Badge tone="danger">{c.failedCount} failed</Badge>}
          </span>
          {c.status === 'SENDING' && c.totalRecipients > 0 && (
            <div style={{ marginTop: 6 }}>
              <div style={{ height: 5, borderRadius: 3, background: 'var(--bg-subtle)', overflow: 'hidden' }}>
                <div style={{
                  height: '100%', borderRadius: 3, width: `${progressPct(c)}%`,
                  background: 'var(--brand)', transition: 'width .5s',
                }} />
              </div>
              <span className="ui-field__hint">{progressPct(c)}% complete</span>
            </div>
          )}
        </div>
      ),
    },
    {
      key: '_actions',
      header: '',
      align: 'right',
      nowrap: true,
      render: (c) => (
        <>
          {(c.status === 'DRAFT' || c.status === 'FAILED') && (
            <Button
              size="sm"
              variant="primary"
              icon={Send}
              onClick={(e) => { e.stopPropagation(); launch(c); }}
            >
              Launch
            </Button>
          )}
          {c.status === 'COMPLETED' && c.failedCount > 0 && (
            <Button
              size="sm"
              icon={RotateCcw}
              onClick={(e) => { e.stopPropagation(); retryFailed(c); }}
            >
              Retry failed
            </Button>
          )}
          <Button size="sm" variant="ghost" onClick={(e) => { e.stopPropagation(); setDetailsId(c.id); }}>
            Details
          </Button>
        </>
      ),
    },
  ];

  const activeTemplates = templates.filter(t => t.isActive !== false);

  return (
    <Stack gap="sm">
      <Row between>
        <span className="ui-field__hint">
          {campaigns.length} campaign{campaigns.length !== 1 ? 's' : ''}
        </span>
        <Button variant="primary" icon={Plus} onClick={openCreate}>New campaign</Button>
      </Row>

      <Card>
        <DataTable
          columns={columns}
          rows={campaigns}
          rowKey={(c) => c.id}
          loading={loading}
          onRowClick={(c) => setDetailsId(c.id)}
          empty={
            <div style={{ padding: 'var(--space-3xl)', textAlign: 'center' }}>
              <p style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginBottom: 14 }}>
                No campaigns yet. Create one to send a templated mailing to your merchants.
              </p>
              <Button variant="subtle" icon={Plus} onClick={openCreate}>Add a campaign</Button>
            </div>
          }
        />
      </Card>

      {/* New campaign */}
      <Modal
        as="form"
        onSubmit={save}
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        size="lg"
        title="New email campaign"
        subtitle="Campaigns are created as drafts. Nothing is sent until you launch them."
        footer={
          <>
            <Button type="button" onClick={() => setModalOpen(false)}>Cancel</Button>
            <Button type="submit" variant="primary" loading={saving}>Create campaign</Button>
          </>
        }
      >
        <div className="ui-stack ui-stack--sm">
          <FormField label="Campaign name" required>
            <Input
              value={form.name}
              onChange={e => setForm({ ...form, name: e.target.value })}
              placeholder="January 2026 statements"
              required
            />
          </FormField>

          <FormField label="Email template">
            <Select
              value={form.templateId}
              onChange={e => setForm({ ...form, templateId: e.target.value })}
              placeholder="Select template"
              options={activeTemplates.map(t => ({ value: t.id, label: `${t.name} (${t.templateType})` }))}
            />
          </FormField>

          <FormField label="Campaign type">
            <Select
              value={form.campaignType}
              onChange={e => setForm({ ...form, campaignType: e.target.value })}
              options={[
                { value: 'BULK', label: 'Bulk (all merchants)' },
                { value: 'TARGETED', label: 'Targeted (filtered)' },
              ]}
            />
          </FormField>

          {form.campaignType === 'TARGETED' && (
            <FormField label="Recipient filter (JSON)" hint="Check the recipient count before you launch.">
              <div className="ui-stack ui-stack--sm">
                <Textarea
                  mono
                  rows={4}
                  value={form.recipientFilterJson}
                  onChange={e => setForm({ ...form, recipientFilterJson: e.target.value })}
                  placeholder='{"status":["ACTIVE"],"city":["Dubai","Riyadh"]}'
                />
                <Row>
                  <Button type="button" icon={Users} onClick={previewRecipients} loading={previewing}>
                    Preview recipients
                  </Button>
                  {recipientCount && (
                    <span style={{ fontSize: '0.82rem', fontWeight: 600, color: 'var(--success)' }}>
                      {recipientCount.count} merchants match your criteria
                    </span>
                  )}
                </Row>
              </div>
            </FormField>
          )}

          <FormGrid cols={2}>
            <FormField label="Statement month" hint="Used by statement templates.">
              <Input
                type="month"
                value={form.statementMonth}
                onChange={e => setForm({ ...form, statementMonth: e.target.value })}
              />
            </FormField>
            <FormField label="Attachment">
              <Select
                value={form.attachmentType}
                onChange={e => setForm({ ...form, attachmentType: e.target.value })}
                options={[
                  { value: 'NONE', label: 'No attachment' },
                  { value: 'STATEMENT_PDF', label: 'Statement PDF' },
                ]}
              />
            </FormField>
          </FormGrid>
        </div>
      </Modal>

      {/* Campaign details */}
      <Modal
        open={!!details}
        onClose={() => setDetailsId(null)}
        title={details?.name}
        subtitle="Campaign configuration and delivery summary."
        footer={<Button onClick={() => setDetailsId(null)}>Close</Button>}
      >
        {details && (
          <div className="ui-stack ui-stack--sm">
            <FormGrid cols={2}>
              <FormField label="Status">
                <StatusBadge status={details.status} />
              </FormField>
              <FormField label="Template">
                <span>{details.template?.name || '—'}</span>
              </FormField>
              <FormField label="Type">
                <span>{details.campaignType}</span>
              </FormField>
              <FormField label="Attachment">
                <span>{details.attachmentType}</span>
              </FormField>
              <FormField label="Created">
                <span>{details.createdAt ? new Date(details.createdAt).toLocaleString() : '—'}</span>
              </FormField>
              <FormField label="Sent at">
                <span>{details.sentAt ? new Date(details.sentAt).toLocaleString() : '—'}</span>
              </FormField>
              <FormField label="Recipients">
                <span>{details.totalRecipients ?? '—'}</span>
              </FormField>
              <FormField label="Sent / failed">
                <span>{details.sentCount || 0} / {details.failedCount || 0}</span>
              </FormField>
            </FormGrid>

            {details.recipientFilterJson && details.recipientFilterJson !== '{}' && (
              <FormField label="Recipient filter">
                <Textarea mono rows={3} value={details.recipientFilterJson} readOnly />
              </FormField>
            )}
          </div>
        )}
      </Modal>
    </Stack>
  );
};

// ─── History Tab ─────────────────────────────────────────────
const HistoryTab = () => {
  const { tenantVersion } = useAuth();
  const [logs, setLogs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await api.get(`/email-campaigns/campaign-logs?page=${page}&size=${HISTORY_PAGE_SIZE}`);
      setLogs(r.data.content || []); setTotal(r.data.totalElements || 0);
    } catch (e) {
      console.error(e);
      showToast('Could not load send history', 'error');
    }
    finally { setLoading(false); }
  }, [page]);
  useEffect(() => { load(); }, [load, tenantVersion]);

  const columns = [
    {
      key: 'status',
      header: 'Status',
      sortable: true,
      nowrap: true,
      render: (l) => <StatusBadge status={l.status} />,
    },
    {
      key: 'merchantName',
      header: 'Merchant',
      sortable: true,
      render: (l) => l.merchantName || '—',
    },
    {
      key: 'recipientEmail',
      header: 'Email',
      sortable: true,
      muted: true,
      render: (l) => l.recipientEmail || '—',
    },
    {
      key: 'subjectRendered',
      header: 'Subject',
      muted: true,
      render: (l) => (
        <span style={{ display: 'block', maxWidth: 280, wordBreak: 'break-word' }}>
          {l.subjectRendered || '—'}
        </span>
      ),
    },
    {
      key: 'sentAt',
      header: 'Sent at',
      sortable: true,
      nowrap: true,
      muted: true,
      render: (l) => (l.sentAt ? new Date(l.sentAt).toLocaleString() : '—'),
    },
    {
      key: 'errorMessage',
      header: 'Error',
      render: (l) => (l.errorMessage
        ? (
          <span style={{ display: 'block', maxWidth: 280, wordBreak: 'break-word', color: 'var(--danger)', fontSize: '0.76rem' }}>
            {l.errorMessage}
          </span>
        )
        : ''),
    },
  ];

  const totalPages = Math.max(1, Math.ceil(total / HISTORY_PAGE_SIZE));

  return (
    <Card
      footer={total > HISTORY_PAGE_SIZE ? (
        <Row between>
          <span className="ui-field__hint">
            Showing {(page * HISTORY_PAGE_SIZE + 1).toLocaleString()} to{' '}
            {Math.min(total, (page + 1) * HISTORY_PAGE_SIZE).toLocaleString()} of {total.toLocaleString()}
          </span>
          <Row>
            <Button size="sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</Button>
            <span className="ui-field__hint" style={{ whiteSpace: 'nowrap' }}>
              Page {page + 1} of {totalPages}
            </span>
            <Button
              size="sm"
              disabled={(page + 1) * HISTORY_PAGE_SIZE >= total}
              onClick={() => setPage(p => p + 1)}
            >
              Next
            </Button>
          </Row>
        </Row>
      ) : undefined}
    >
      <DataTable
        columns={columns}
        rows={logs}
        rowKey={(l) => l.id}
        loading={loading}
        toolbarLeft={
          <span className="ui-field__hint">{total.toLocaleString()} log entries</span>
        }
        toolbarRight={<Button icon={RefreshCw} onClick={load}>Refresh</Button>}
        empty={
          <div style={{ padding: 'var(--space-3xl)', textAlign: 'center', color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
            No email has been sent yet.
          </div>
        }
      />
    </Card>
  );
};

// ═══════════════════════════════════════════════════════════════
const TABS = [
  { key: 'campaigns', label: 'Campaigns', icon: Send },
  { key: 'templates', label: 'Email templates', icon: FileText },
  { key: 'history', label: 'Send history', icon: Clock },
];

const EmailCampaignHub = () => {
  const [activeTab, setActiveTab] = useState('campaigns');

  return (
    <Page
      title="Email campaign hub"
      subtitle="Build templates with merge variables, target merchants, and track delivery. Campaigns only send once you launch them."
      icon={MailOpen}
    >
      <Tabs tabs={TABS} active={activeTab} onChange={setActiveTab} />

      {activeTab === 'campaigns' && <CampaignsTab />}
      {activeTab === 'templates' && <TemplatesTab />}
      {activeTab === 'history' && <HistoryTab />}
    </Page>
  );
};

export default EmailCampaignHub;
