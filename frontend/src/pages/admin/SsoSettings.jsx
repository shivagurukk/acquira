import React, { useState, useEffect } from 'react';
import { Shield, Globe, Save, Eye, EyeOff, Check, AlertTriangle, RefreshCw, ExternalLink, Copy } from 'lucide-react';
import api from '../../api/axios';

const card = { background: '#fff', borderRadius: 12, padding: 24, boxShadow: '0 1px 3px rgba(0,0,0,.06)', border: '1px solid #e5e7eb' };

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
  const [notification, setNotification] = useState(null);
  const [ssoStatus, setSsoStatus] = useState(null); // result from test

  const notify = (msg, type = 'success') => { setNotification({ msg, type }); setTimeout(() => setNotification(null), 4000); };

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
      notify('SSO settings saved successfully');
    } catch (e) {
      notify(e.response?.data?.error || 'Failed to save', 'error');
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
        notify('SSO configuration is valid');
      } else {
        setSsoStatus('disabled');
        notify('SSO is currently disabled or not configured', 'error');
      }
    } catch (e) {
      setSsoStatus('error');
      notify('Failed to verify SSO config', 'error');
    }
  };

  const isEnabled = config.sso_enabled === 'true';
  const redirectUri = window.location.origin + '/auth/sso/callback';

  if (loading) return <div style={{ padding: 60, textAlign: 'center', color: '#9ca3af' }}>Loading SSO settings...</div>;

  return (
    <div style={{ padding: '24px 32px', maxWidth: 800, margin: '0 auto' }}>
      {/* Notification */}
      {notification && (
        <div style={{
          position: 'fixed', top: 20, right: 20, zIndex: 100, padding: '12px 20px', borderRadius: 10,
          background: notification.type === 'error' ? '#fef2f2' : '#f0fdf4',
          color: notification.type === 'error' ? '#dc2626' : '#16a34a',
          border: `1px solid ${notification.type === 'error' ? '#fecaca' : '#bbf7d0'}`,
          boxShadow: '0 4px 12px rgba(0,0,0,.1)', fontSize: 13, fontWeight: 500, display: 'flex', alignItems: 'center', gap: 8
        }}>
          {notification.type === 'error' ? <AlertTriangle size={16} /> : <Check size={16} />} {notification.msg}
        </div>
      )}

      {/* Header */}
      <div style={{ marginBottom: 24 }}>
        <h1 style={{ fontSize: 22, fontWeight: 700, margin: 0, display: 'flex', alignItems: 'center', gap: 10 }}>
          <Shield size={24} color="#6366f1" /> SSO Settings
        </h1>
        <p style={{ fontSize: 13, color: '#64748b', margin: '4px 0 0' }}>
          Configure Microsoft Entra ID (Azure AD) Single Sign-On for your organization
        </p>
      </div>

      {/* Enable/Disable Toggle */}
      <div style={{ ...card, marginBottom: 20, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 2 }}>Enable Microsoft SSO</div>
          <div style={{ fontSize: 12, color: '#64748b' }}>
            Show "Sign in with Microsoft" button on the login page
          </div>
        </div>
        <div onClick={() => setConfig({ ...config, sso_enabled: isEnabled ? 'false' : 'true' })}
          style={{
            width: 52, height: 28, borderRadius: 14, cursor: 'pointer', padding: 3, transition: 'all .2s',
            background: isEnabled ? '#6366f1' : '#d1d5db',
          }}>
          <div style={{
            width: 22, height: 22, borderRadius: '50%', background: '#fff',
            transition: 'transform .2s', transform: `translateX(${isEnabled ? 24 : 0}px)`,
            boxShadow: '0 1px 3px rgba(0,0,0,.2)',
          }} />
        </div>
      </div>

      {/* Configuration */}
      <div style={{ ...card, marginBottom: 20, opacity: isEnabled ? 1 : 0.5, pointerEvents: isEnabled ? 'auto' : 'none' }}>
        <h3 style={{ fontSize: 15, fontWeight: 600, marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
          <Globe size={18} color="#3b82f6" /> Azure AD / Entra ID Configuration
        </h3>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {/* Client ID */}
          <div>
            <label style={labelStyle}>Application (Client) ID *</label>
            <input value={config.sso_client_id} onChange={e => setConfig({ ...config, sso_client_id: e.target.value })}
              style={inputStyle} placeholder="e.g. 12345678-abcd-1234-efgh-123456789012" />
            <div style={helpStyle}>From Azure Portal → App Registrations → Overview</div>
          </div>

          {/* Client Secret */}
          <div>
            <label style={labelStyle}>Client Secret *</label>
            <div style={{ position: 'relative' }}>
              <input type={showSecret ? 'text' : 'password'} value={config.sso_client_secret}
                onChange={e => setConfig({ ...config, sso_client_secret: e.target.value })}
                style={inputStyle} placeholder="Enter client secret" />
              <button type="button" onClick={() => setShowSecret(!showSecret)}
                style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: '#94a3b8' }}>
                {showSecret ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </div>
            {/* GAP-16: Show indicator when secret is from environment */}
            {!config.sso_client_secret && ssoStatus === 'ok' && (
              <div style={{ fontSize: 11, color: '#059669', marginTop: 4, display: 'flex', alignItems: 'center', gap: 4 }}>
                <Check size={12} /> Configured via environment variables (leave blank to keep using it)
              </div>
            )}
            {!config.sso_client_secret && ssoStatus !== 'ok' && (
              <div style={helpStyle}>From Azure Portal → Certificates & secrets → New client secret</div>
            )}
            {config.sso_client_secret && (
              <div style={helpStyle}>From Azure Portal → Certificates & secrets → New client secret</div>
            )}
          </div>

          {/* Tenant ID */}
          <div>
            <label style={labelStyle}>Directory (Tenant) ID</label>
            <input value={config.sso_tenant_id} onChange={e => setConfig({ ...config, sso_tenant_id: e.target.value })}
              style={inputStyle} placeholder="e.g. 12345678-abcd-1234-efgh-123456789012 or 'common'" />
            <div style={helpStyle}>Use "common" to allow any Microsoft account, or a specific tenant ID for single-org</div>
          </div>

          {/* Email domains — per-bank SSO routing */}
          <div>
            <label style={labelStyle}>Email domains (per-bank SSO routing)</label>
            <input value={config.sso_email_domains} onChange={e => setConfig({ ...config, sso_email_domains: e.target.value })}
              style={inputStyle} placeholder="e.g. acmebank.com, acme.co" />
            <div style={helpStyle}>
              Comma-separated. This SSO configuration applies to THIS bank only. When several banks
              enable SSO, users are routed to the right bank's identity provider by matching their
              email domain against this list. With a single SSO-enabled bank this can stay empty.
            </div>
          </div>

          {/* Redirect URI (read-only) */}
          <div>
            <label style={labelStyle}>Redirect URI (copy this to Azure)</label>
            <div style={{ display: 'flex', gap: 8 }}>
              <input value={redirectUri} readOnly style={{ ...inputStyle, background: '#f8fafc', color: '#64748b', flex: 1 }} />
              <button onClick={() => { navigator.clipboard.writeText(redirectUri); notify('Copied!'); }}
                style={{ padding: '8px 14px', borderRadius: 10, border: '1px solid #e2e8f0', background: '#fff', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4, fontSize: 12, fontWeight: 500, color: '#475569' }}>
                <Copy size={14} /> Copy
              </button>
            </div>
            <div style={helpStyle}>Add this as a redirect URI in Azure Portal → Authentication → Web</div>
          </div>
        </div>
      </div>

      {/* Setup Guide */}
      <div style={{ ...card, marginBottom: 20, background: '#f0f9ff', borderColor: '#bae6fd' }}>
        <h3 style={{ fontSize: 14, fontWeight: 600, marginBottom: 10, color: '#0369a1', display: 'flex', alignItems: 'center', gap: 6 }}>
          <ExternalLink size={16} /> Azure Portal Setup Guide
        </h3>
        <ol style={{ margin: 0, paddingLeft: 20, color: '#0c4a6e', fontSize: 13, lineHeight: 1.8 }}>
          <li>Go to <strong>Azure Portal</strong> → Microsoft Entra ID → App registrations</li>
          <li>Click <strong>New registration</strong> → Name: "Acquira SSO"</li>
          <li>Set redirect URI to: <code style={{ background: '#e0f2fe', padding: '1px 6px', borderRadius: 4, fontSize: 12 }}>{redirectUri}</code></li>
          <li>Copy <strong>Application (client) ID</strong> and <strong>Directory (tenant) ID</strong></li>
          <li>Go to <strong>Certificates & secrets</strong> → New client secret → Copy the value</li>
          <li>Go to <strong>API permissions</strong> → Add: <code>openid</code>, <code>profile</code>, <code>email</code>, <code>User.Read</code></li>
          <li>Paste all values above and click <strong>Save</strong></li>
        </ol>
      </div>

      {/* Actions */}
      <div style={{ display: 'flex', gap: 12, justifyContent: 'flex-end' }}>
        <button onClick={handleTestConfig}
          style={{ padding: '10px 20px', borderRadius: 10, border: '1px solid #e2e8f0', background: '#fff', cursor: 'pointer', fontSize: 13, fontWeight: 600, color: '#475569', display: 'flex', alignItems: 'center', gap: 6 }}>
          <RefreshCw size={15} /> Test Configuration
        </button>
        <button onClick={handleSave} disabled={saving}
          style={{ padding: '10px 20px', borderRadius: 10, border: 'none', background: '#6366f1', color: '#fff', cursor: 'pointer', fontSize: 13, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 6, opacity: saving ? 0.7 : 1 }}>
          <Save size={15} /> {saving ? 'Saving...' : 'Save Settings'}
        </button>
      </div>

      {/* SSO Flow Diagram */}
      <div style={{ ...card, marginTop: 24, background: '#fafafa' }}>
        <h3 style={{ fontSize: 14, fontWeight: 600, marginBottom: 10, color: '#374151' }}>How SSO Login Works</h3>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', fontSize: 12, color: '#6b7280' }}>
          {['User clicks "Sign in with Microsoft"', '→', 'Microsoft authenticates', '→', 'Redirect back with code', '→', 'Backend validates token', '→'].map((step, i) => (
            <span key={i} style={step === '→' ? { color: '#d1d5db' } : { background: '#fff', padding: '4px 10px', borderRadius: 6, border: '1px solid #e5e7eb' }}>{step}</span>
          ))}
          <span style={{ background: '#f0fdf4', padding: '4px 10px', borderRadius: 6, border: '1px solid #bbf7d0', color: '#166534' }}>
            Email found → Login
          </span>
          <span style={{ color: '#d1d5db' }}>|</span>
          <span style={{ background: '#fef9c3', padding: '4px 10px', borderRadius: 6, border: '1px solid #fde68a', color: '#854d0e' }}>
            Not found → Request Access → Admin Approves
          </span>
        </div>
      </div>
    </div>
  );
};

const labelStyle = { display: 'block', fontSize: 12, fontWeight: 600, color: '#374151', marginBottom: 5 };
const inputStyle = { width: '100%', padding: '10px 14px', borderRadius: 10, border: '1px solid #e2e8f0', fontSize: 13, outline: 'none', boxSizing: 'border-box' };
const helpStyle = { fontSize: 11, color: '#94a3b8', marginTop: 4 };

export default SsoSettings;
