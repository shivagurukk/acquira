import { useState, useEffect } from 'react';
import {
  Cloud, Save, Eye, EyeOff, RefreshCw, Shield, Info, ChevronDown, ChevronUp,
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import {
  Page, Stack, Card, Button, Badge, Alert,
  FormField, Input, Select, Switch,
} from '../../components/ui';

/**
 * Admin > S3 report storage.
 *
 * Reads and writes /admin/s3-settings, plus a credential check against
 * /admin/s3-settings/test. The secret access key is AES-256 encrypted server
 * side, so it is masked in the UI and only revealed on request.
 */

const AWS_REGIONS = [
  'us-east-1','us-east-2','us-west-1','us-west-2',
  'eu-west-1','eu-west-2','eu-west-3','eu-central-1',
  'ap-south-1','ap-southeast-1','ap-southeast-2','ap-northeast-1',
  'me-south-1','me-central-1','af-south-1','ca-central-1','sa-east-1',
];

const DEFAULT = {
  enabled: false,
  s3AccessKeyId: '',
  s3SecretAccessKey: '',
  s3Bucket: '',
  s3Region: 'ap-south-1',
  s3Prefix: 'reports',
};

const HOW_IT_WORKS = [
  'Batch job generates all merchant PDFs to local disk.',
  'Emails are sent to each merchant with the PDF attached.',
  'After email success, the PDF is uploaded to S3 under {bankCode}/{YYYY-MM}/.',
  'Failed uploads are logged; local files are not deleted automatically.',
  'Credentials are stored AES-256 encrypted in the tenant_setting table.',
];

const SUBTITLE = 'Archive generated PDFs to AWS S3 after merchant emails are sent.';

export default function S3Settings() {
  const { tenantVersion } = useAuth();
  const [form, setForm] = useState(DEFAULT);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState(null);
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(true);
  const [showSecret, setShowSecret] = useState(false);
  const [infoOpen, setInfoOpen] = useState(false);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    api.get('/admin/s3-settings')
      .then(res => setForm(f => ({ ...f, ...res.data })))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [tenantVersion]);

  const set = (key, val) => {
    setForm(f => ({ ...f, [key]: val }));
    setDirty(true);
    setTestResult(null);
  };

  const handleTest = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      const res = await api.post('/admin/s3-settings/test', {
        s3AccessKeyId:     form.s3AccessKeyId,
        s3SecretAccessKey: form.s3SecretAccessKey,
        s3Bucket:          form.s3Bucket,
        s3Region:          form.s3Region,
        s3Prefix:          form.s3Prefix,
      });
      setTestResult({ ok: res.data.success, msg: res.data.message });
    } catch (e) {
      setTestResult({ ok: false, msg: e.response?.data?.message || 'Connection failed' });
    } finally {
      setTesting(false);
    }
  };

  const handleSave = async (e) => {
    e.preventDefault();
    setSaving(true);
    try {
      await api.post('/admin/s3-settings', form);
      setDirty(false);
      showToast('S3 settings saved and encrypted', 'success');
    } catch (err) {
      showToast('Save failed: ' + (err.response?.data?.message || err.message), 'error');
    } finally {
      setSaving(false);
    }
  };

  const pathPreview = `s3://${form.s3Bucket || '<bucket>'}/${form.s3Prefix || 'reports'}/{bankCode}/{YYYY-MM}/Insight_*.pdf`;
  const canTest = !testing && form.s3Bucket && form.s3AccessKeyId && form.s3SecretAccessKey;

  if (loading) {
    return (
      <Page title="S3 report storage" subtitle={SUBTITLE} icon={Cloud}>
        <Card pad>
          <p style={{ margin: 0, color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
            Loading S3 configuration…
          </p>
        </Card>
      </Page>
    );
  }

  return (
    <Page
      title="S3 report storage"
      subtitle={SUBTITLE}
      icon={Cloud}
      actions={
        <Badge tone={form.enabled ? 'success' : 'neutral'} dot>
          {form.enabled ? 'S3 archiving enabled' : 'Local storage only'}
        </Badge>
      }
    >
      <form onSubmit={handleSave}>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'minmax(260px, 1fr) minmax(320px, 1.6fr)',
            gap: 'var(--space-2xl)',
            alignItems: 'start',
          }}
        >
          {/* ── Archiving switch, reference notes ─────────────────────────── */}
          <Card
            title="S3 archiving"
            subtitle={
              form.enabled
                ? 'PDFs are uploaded to S3 after each email is sent.'
                : 'Reports stay on local disk only.'
            }
            pad
          >
            <Stack gap="md">
              <Switch
                checked={form.enabled}
                onChange={e => set('enabled', e.target.checked)}
                label={form.enabled ? 'S3 archiving is on' : 'Enable S3 archiving'}
              />

              {!form.enabled && (
                <p style={{ margin: 0, fontSize: '0.78rem', color: 'var(--text-secondary)' }}>
                  Turn the switch on, fill in the credentials, then save.
                </p>
              )}

              <div>
                <Button
                  variant="ghost"
                  size="sm"
                  icon={Info}
                  iconRight={infoOpen ? ChevronUp : ChevronDown}
                  onClick={() => setInfoOpen(v => !v)}
                  aria-expanded={infoOpen}
                >
                  How S3 archiving works
                </Button>
                {infoOpen && (
                  <ol
                    style={{
                      margin: 'var(--space-sm) 0 0',
                      paddingLeft: 22,
                      fontSize: '0.8rem',
                      lineHeight: 1.7,
                      color: 'var(--text-secondary)',
                    }}
                  >
                    {HOW_IT_WORKS.map(step => <li key={step}>{step}</li>)}
                  </ol>
                )}
              </div>

              <Alert tone="success" icon={Shield} title="AES-256 encrypted storage">
                The secret key is encrypted before it is persisted. It is never logged or returned.
              </Alert>

              {(form.s3Bucket || form.s3Prefix) && (
                <div>
                  <p
                    style={{
                      margin: '0 0 6px',
                      fontSize: '0.68rem',
                      fontWeight: 600,
                      letterSpacing: '.08em',
                      textTransform: 'uppercase',
                      color: 'var(--text-muted)',
                    }}
                  >
                    S3 path pattern
                  </p>
                  <code
                    className="ui-td--mono"
                    style={{ wordBreak: 'break-all', color: 'var(--text-secondary)', lineHeight: 1.6 }}
                  >
                    {pathPreview}
                  </code>
                </div>
              )}
            </Stack>
          </Card>

          {/* ── Credentials ───────────────────────────────────────────────── */}
          <Card
            title="AWS credentials"
            actions={!form.enabled ? <Badge tone="info">Enable the switch to activate</Badge> : null}
            pad
          >
            <Stack gap="md">
              <FormField label="AWS region">
                <Select
                  value={form.s3Region}
                  onChange={e => set('s3Region', e.target.value)}
                  options={AWS_REGIONS}
                />
              </FormField>

              <FormField
                label="AWS access key ID"
                hint="IAM user access key with S3 write permissions."
              >
                <Input
                  value={form.s3AccessKeyId}
                  onChange={e => set('s3AccessKeyId', e.target.value)}
                  placeholder="AKIA…"
                  autoComplete="off"
                  mono
                />
              </FormField>

              <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)' }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <FormField
                    label="AWS secret access key"
                    hint="Encrypted with AES-256 before saving. Never stored in plain text."
                  >
                    <Input
                      value={form.s3SecretAccessKey}
                      onChange={e => set('s3SecretAccessKey', e.target.value)}
                      type={showSecret ? 'text' : 'password'}
                      placeholder="••••••••••••••••••••••"
                      autoComplete="new-password"
                      mono
                    />
                  </FormField>
                </div>
                <Button
                  variant="ghost"
                  iconOnly
                  icon={showSecret ? EyeOff : Eye}
                  onClick={() => setShowSecret(v => !v)}
                  aria-label={showSecret ? 'Hide secret access key' : 'Show secret access key'}
                />
              </div>

              <FormField
                label="S3 bucket name"
                hint="The bucket must already exist in the selected region."
              >
                <Input
                  value={form.s3Bucket}
                  onChange={e => set('s3Bucket', e.target.value)}
                  placeholder="my-reports-bucket"
                  autoComplete="off"
                  mono
                />
              </FormField>

              <FormField
                label="Key prefix (folder)"
                hint="Folder prefix inside the bucket, with no leading slash."
              >
                <Input
                  value={form.s3Prefix}
                  onChange={e => set('s3Prefix', e.target.value)}
                  placeholder="reports"
                  autoComplete="off"
                  mono
                />
              </FormField>

              <div
                className="ui-row"
                style={{
                  paddingTop: 'var(--space-lg)',
                  borderTop: '1px solid var(--border-light)',
                }}
              >
                <Button
                  icon={RefreshCw}
                  onClick={handleTest}
                  disabled={!canTest}
                  loading={testing}
                >
                  Test connection
                </Button>
                <Button
                  type="submit"
                  variant="primary"
                  icon={Save}
                  disabled={saving || !dirty}
                  loading={saving}
                >
                  Save settings
                </Button>
              </div>

              {testResult && (
                <Alert tone={testResult.ok ? 'success' : 'danger'}>{testResult.msg}</Alert>
              )}

              {dirty && <Alert tone="warning">You have unsaved changes.</Alert>}
            </Stack>
          </Card>
        </div>
      </form>
    </Page>
  );
}
