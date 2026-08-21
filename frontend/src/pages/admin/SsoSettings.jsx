import React, { useState, useEffect } from 'react';
import {
  Shield, Globe, Save, Eye, EyeOff, RefreshCw, ExternalLink, Copy, Workflow,
} from 'lucide-react';
import api from '../../api/axios';
import { showToast } from '../../contexts/ToastContext';
import {
  Page, Stack, Card, Button, Badge, FormField, Input, Switch,
} from '../../components/ui';

/**
 * Admin > Single sign-on.
 *
 * Reads and writes the `sso_*` keys in tenant_setting through the shared
 * GET/PUT /api/admin/settings pair, so the configuration is per-bank. The
 * "Test configuration" action hits the public /api/sso/microsoft/config
 * endpoint to confirm the backend can actually build an authorisation URL.
 */

const SSO_FLOW = [
  'User clicks "Sign in with Microsoft"',
  'Microsoft authenticates',
  'Redirect back with code',
  'Backend validates token',
];

const SsoSettings = () => {
  const [config, setConfig] = useState({
    sso_enabled: 'false',
    sso_provider: 'MICROSOFT',
    sso_client_id: '',
    sso_client_secret: '',
    sso_tenant_id: '',
    sso_email_domains: '',
  });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [showSecret, setShowSecret] = useState(false);
  const [ssoStatus, setSsoStatus] = useState(null); // result from test

  useEffect(() => { loadSettings(); }, []);

  const loadSettings = async () => {
    setLoading(true);
    try {
      const res = await api.get('/admin/settings');
      const settings = {};
      (res.data || []).forEach(s => {
        const k = s.key || s.settingKey;
        const v = s.value || s.settingValue;
        if (k?.startsWith('sso_')) {
          settings[k] = v || '';
        }
      });
      setConfig(prev => ({ ...prev, ...settings }));
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      // Save each SSO setting
      for (const [key, value] of Object.entries(config)) {
        if (key.startsWith('sso_')) {
          await api.put('/admin/settings', { settingKey: key, settingValue: value });
        }
      }
      showToast('SSO settings saved', 'success');
    } catch (e) {
      showToast(e.response?.data?.error || 'Failed to save', 'error');
    } finally { setSaving(false); }
  };

  const handleTestConfig = async () => {
    setSsoStatus('testing');
    try {
      // Just verify the config endpoint returns properly
      const res = await fetch('/api/sso/microsoft/config');
      const data = await res.json();
      if (data.enabled && data.authUrl) {
        setSsoStatus('ok');
        showToast('SSO configuration is valid', 'success');
      } else {
        setSsoStatus('disabled');
        showToast('SSO is currently disabled or not configured', 'error');
      }
    } catch {
      setSsoStatus('error');
      showToast('Failed to verify SSO config', 'error');
    }
  };

  const isEnabled = config.sso_enabled === 'true';
  const redirectUri = window.location.origin + '/auth/sso/callback';

  const copyRedirectUri = () => {
    navigator.clipboard.writeText(redirectUri);
    showToast('Redirect URI copied', 'success');
  };

  // GAP-16: when the secret is supplied by environment variables the field can
  // stay blank — say so instead of pointing at the Azure portal.
  const secretHint = !config.sso_client_secret && ssoStatus === 'ok'
    ? 'Configured via environment variables. Leave blank to keep using it.'
    : 'From Azure portal → Certificates & secrets → New client secret.';

  return (
    <Page
      width="narrow"
      title="Single sign-on"
      subtitle="Configure Microsoft Entra ID (Azure AD) single sign-on for your organization."
      icon={Shield}
      actions={
        <>
          <Button icon={RefreshCw} onClick={handleTestConfig} loading={ssoStatus === 'testing'}>
            Test configuration
          </Button>
          <Button variant="primary" icon={Save} onClick={handleSave} loading={saving} disabled={loading}>
            Save settings
          </Button>
        </>
      }
    >
      {loading ? (
        <Card pad>
          <p className="ui-field__hint" style={{ margin: 0 }}>Loading SSO settings…</p>
        </Card>
      ) : (
        <Stack>
          <Card
            title="Enable Microsoft SSO"
            subtitle="Show the Sign in with Microsoft button on the login page."
            actions={
              <Switch
                checked={isEnabled}
                onChange={e => setConfig({ ...config, sso_enabled: e.target.checked ? 'true' : 'false' })}
                aria-label="Enable Microsoft SSO"
              />
            }
          />

          <Card
            title={
              <span className="ui-row" style={{ gap: 8, flexWrap: 'nowrap' }}>
                <Globe size={15} strokeWidth={2} style={{ color: 'var(--brand)', flexShrink: 0 }} />
                Azure AD / Entra ID configuration
              </span>
            }
            subtitle={isEnabled ? undefined : 'Enable Microsoft SSO above to edit these values.'}
            pad
            style={{ opacity: isEnabled ? 1 : 0.6 }}
          >
            <div className="ui-stack ui-stack--sm">
              <FormField
                label="Application (client) ID"
                required
                hint="From Azure portal → App registrations → Overview."
              >
                <Input
                  value={config.sso_client_id}
                  onChange={e => setConfig({ ...config, sso_client_id: e.target.value })}
                  placeholder="e.g. 12345678-abcd-1234-efgh-123456789012"
                  disabled={!isEnabled}
                />
              </FormField>

              <FormField
                label="Client secret"
                required
                hint={secretHint}
                htmlFor="sso-client-secret"
              >
                <div id="sso-client-secret-row" className="ui-row" style={{ gap: 6, flexWrap: 'nowrap' }}>
                  <Input
                    id="sso-client-secret"
                    type={showSecret ? 'text' : 'password'}
                    value={config.sso_client_secret}
                    onChange={e => setConfig({ ...config, sso_client_secret: e.target.value })}
                    placeholder="Enter client secret"
                    disabled={!isEnabled}
                    style={{ flex: 1 }}
                  />
                  <Button
                    variant="ghost"
                    iconOnly
                    icon={showSecret ? EyeOff : Eye}
                    onClick={() => setShowSecret(!showSecret)}
                    aria-label={showSecret ? 'Hide client secret' : 'Show client secret'}
                    disabled={!isEnabled}
                  />
                </div>
              </FormField>

              <FormField
                label="Directory (tenant) ID"
                hint="Use 'common' to allow any Microsoft account, or a specific tenant ID for a single organization."
              >
                <Input
                  value={config.sso_tenant_id}
                  onChange={e => setConfig({ ...config, sso_tenant_id: e.target.value })}
                  placeholder="e.g. 12345678-abcd-1234-efgh-123456789012 or 'common'"
                  disabled={!isEnabled}
                />
              </FormField>

              <FormField
                label="Email domains (per-bank SSO routing)"
                hint="Comma-separated. This SSO configuration applies to this bank only. When several banks enable SSO, users are routed to the right identity provider by matching their email domain against this list. With a single SSO-enabled bank this can stay empty."
              >
                <Input
                  value={config.sso_email_domains}
                  onChange={e => setConfig({ ...config, sso_email_domains: e.target.value })}
                  placeholder="e.g. acmebank.com, acme.co"
                  disabled={!isEnabled}
                />
              </FormField>

              <FormField
                label="Redirect URI"
                hint="Add this as a redirect URI in Azure portal → Authentication → Web."
                htmlFor="sso-redirect-uri"
              >
                <div id="sso-redirect-uri-row" className="ui-row" style={{ gap: 6, flexWrap: 'nowrap' }}>
                  <Input id="sso-redirect-uri" value={redirectUri} readOnly style={{ flex: 1 }} />
                  <Button icon={Copy} onClick={copyRedirectUri} disabled={!isEnabled}>Copy</Button>
                </div>
              </FormField>
            </div>
          </Card>

          <Card
            title={
              <span className="ui-row" style={{ gap: 8, flexWrap: 'nowrap' }}>
                <ExternalLink size={15} strokeWidth={2} style={{ color: 'var(--brand)', flexShrink: 0 }} />
                Azure portal setup guide
              </span>
            }
            pad
          >
            <ol style={{ margin: 0, paddingLeft: 20, fontSize: '0.82rem', lineHeight: 1.9, color: 'var(--text-secondary)' }}>
              <li>Go to <strong>Azure portal</strong> → Microsoft Entra ID → App registrations.</li>
              <li>Click <strong>New registration</strong> and name it {'"Acquira SSO"'}.</li>
              <li>Set the redirect URI to <code>{redirectUri}</code>.</li>
              <li>Copy the <strong>Application (client) ID</strong> and <strong>Directory (tenant) ID</strong>.</li>
              <li>Go to <strong>Certificates &amp; secrets</strong> → New client secret → copy the value.</li>
              <li>Go to <strong>API permissions</strong> → add <code>openid</code>, <code>profile</code>, <code>email</code>, <code>User.Read</code>.</li>
              <li>Paste all values above and click <strong>Save settings</strong>.</li>
            </ol>
          </Card>

          <Card
            title={
              <span className="ui-row" style={{ gap: 8, flexWrap: 'nowrap' }}>
                <Workflow size={15} strokeWidth={2} style={{ color: 'var(--brand)', flexShrink: 0 }} />
                How SSO login works
              </span>
            }
            pad
          >
            <div className="ui-row" style={{ gap: 8, rowGap: 8 }}>
              {SSO_FLOW.map(step => (
                <React.Fragment key={step}>
                  <Badge>{step}</Badge>
                  <span aria-hidden="true" style={{ color: 'var(--text-muted)' }}>→</span>
                </React.Fragment>
              ))}
              <Badge tone="success">Email found, user is signed in</Badge>
              <span aria-hidden="true" style={{ color: 'var(--text-muted)' }}>|</span>
              <Badge tone="warning">Not found, access request goes to an admin</Badge>
            </div>
          </Card>
        </Stack>
      )}
    </Page>
  );
};

export default SsoSettings;
