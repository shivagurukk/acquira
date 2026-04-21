import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import {
  Cloud, Save, Eye, EyeOff, CheckCircle2, XCircle,
  RefreshCw, Shield, Info, Loader2, ChevronDown,
  ChevronUp, AlertTriangle, ToggleLeft, ToggleRight
} from 'lucide-react';
import api from '../../api/axios';

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

export default function S3Settings() {
  const [form, setForm]             = useState(DEFAULT);
  const [saveDone, setSaveDone]     = useState(false);
  const [testing, setTesting]       = useState(false);
  const [testResult, setTestResult] = useState(null);
  const [saving, setSaving]         = useState(false);
  const [loading, setLoading]       = useState(true);
  const [showSecret, setShowSecret] = useState(false);
  const [infoOpen, setInfoOpen]     = useState(false);
  const [dirty, setDirty]           = useState(false);

  useEffect(() => {
    api.get('/admin/s3-settings')
      .then(res => setForm(f => ({ ...f, ...res.data })))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const set = (key, val) => {
    setForm(f => ({ ...f, [key]: val }));
    setDirty(true);
    setTestResult(null);
    setSaveDone(false);
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
      setSaveDone(true);
      setDirty(false);
      setTimeout(() => setSaveDone(false), 4000);
    } catch (err) {
      alert('Save failed: ' + (err.response?.data?.message || err.message));
    } finally {
      setSaving(false);
    }
  };

  const pathPreview = `s3://${form.s3Bucket || '<bucket>'}/${form.s3Prefix || 'reports'}/{bankCode}/{YYYY-MM}/Insight_*.pdf`;
  const canTest = !testing && form.s3Bucket && form.s3AccessKeyId && form.s3SecretAccessKey;

  if (loading) return (
    <div style={S.loader}>
      <Loader2 size={32} style={{ animation: 'spin 1s linear infinite', color: '#6366f1' }} />
      <p style={{ marginTop: 12, color: '#64748b' }}>Loading S3 configuration…</p>
      <style>{`@keyframes spin{to{transform:rotate(360deg)}}`}</style>
    </div>
  );

  return (
    <div style={S.page}>
      <link href="https://fonts.googleapis.com/css2?family=DM+Sans:wght@300;400;500;600;700&family=DM+Mono:wght@400;500&display=swap" rel="stylesheet" />

      {/* ── Header ── */}
      <motion.div initial={{ opacity:0, y:-16 }} animate={{ opacity:1, y:0 }} style={S.header}>
        <div style={{ display:'flex', alignItems:'center', gap:16 }}>
          <div style={S.iconBadge}><Cloud size={22} color="#fff" /></div>
          <div>
            <h1 style={S.title}>S3 Report Storage</h1>
            <p style={S.subtitle}>Archive generated PDFs to AWS S3 after merchant emails are sent</p>
          </div>
        </div>
        <div style={{ ...S.pill, ...(form.enabled ? S.pillOn : S.pillOff) }}>
          <span style={{ width:8, height:8, borderRadius:'50%', background: form.enabled ? '#22c55e' : '#94a3b8', display:'inline-block' }} />
          {form.enabled ? 'S3 Archiving Enabled' : 'Local Storage Only'}
        </div>
      </motion.div>

      <form onSubmit={handleSave}>
        <div style={S.grid}>

          {/* ── LEFT: Toggle + Info ── */}
          <motion.div initial={{ opacity:0, x:-20 }} animate={{ opacity:1, x:0, transition:{ delay:.05 } }} style={S.card}>

            {/* Big prominent toggle */}
            <div style={{
              background: form.enabled ? 'linear-gradient(135deg, #f0fdf4, #dcfce7)' : 'linear-gradient(135deg, #f8fafc, #f1f5f9)',
              border: `2px solid ${form.enabled ? '#86efac' : '#e2e8f0'}`,
              borderRadius: 12,
              padding: '18px 20px',
              marginBottom: 20,
              transition: 'all 0.3s ease',
            }}>
              <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', gap:12 }}>
                <div>
                  <p style={{ margin:0, fontWeight:700, fontSize:15, color: form.enabled ? '#14532d' : '#0f172a' }}>
                    {form.enabled ? '✓ S3 Archiving is ON' : 'Enable S3 Archiving'}
                  </p>
                  <p style={{ margin:'5px 0 0', fontSize:13, color: form.enabled ? '#166534' : '#64748b', lineHeight:1.5 }}>
                    {form.enabled
                      ? 'PDFs will be uploaded to S3 after each email is sent.'
                      : 'Click the toggle to enable S3 upload after email.'}
                  </p>
                </div>
                {/* Large, obvious toggle button */}
                <button
                  type="button"
                  onClick={() => set('enabled', !form.enabled)}
                  title={form.enabled ? 'Click to disable S3' : 'Click to enable S3'}
                  style={{
                    background: 'none', border: 'none', cursor: 'pointer',
                    padding: 4, flexShrink: 0,
                    color: form.enabled ? '#16a34a' : '#94a3b8',
                    transition: 'color 0.2s, transform 0.15s',
                    display: 'flex', alignItems: 'center',
                  }}
                  onMouseEnter={e => { e.currentTarget.style.transform = 'scale(1.08)'; }}
                  onMouseLeave={e => { e.currentTarget.style.transform = 'scale(1)'; }}
                >
                  {form.enabled
                    ? <ToggleRight size={48} strokeWidth={1.5} />
                    : <ToggleLeft  size={48} strokeWidth={1.5} />
                  }
                </button>
              </div>
              {!form.enabled && (
                <p style={{
                  margin: '12px 0 0', fontSize: 12, color: '#6366f1', fontWeight: 500,
                  display: 'flex', alignItems: 'center', gap: 6,
                }}>
                  <span style={{ fontSize: 16 }}>👆</span>
                  Toggle ON above, fill in credentials on the right, then Save.
                </p>
              )}
            </div>

            <div style={S.hr} />

            {/* Info accordion */}
            <button type="button" onClick={() => setInfoOpen(v => !v)} style={S.infoBtn}>
              <Info size={15} color="#6366f1" />
              <span>How S3 archiving works</span>
              {infoOpen ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
            </button>

            <AnimatePresence>
              {infoOpen && (
                <motion.div initial={{ height:0, opacity:0 }} animate={{ height:'auto', opacity:1 }} exit={{ height:0, opacity:0 }} style={{ overflow:'hidden' }}>
                  <div style={S.infoBox}>
                    {[
                      ['1.', 'Batch job generates all merchant PDFs to local disk'],
                      ['2.', 'Emails are sent to each merchant with PDF attached'],
                      ['3.', 'After email success, PDF is uploaded to S3 under {bankCode}/{YYYY-MM}/'],
                      ['4.', 'Failed uploads are logged; local files are NOT deleted automatically'],
                      ['5.', 'Credentials stored AES-256 encrypted in the tenant_setting table'],
                    ].map(([n, t]) => (
                      <div key={n} style={{ display:'flex', gap:10, marginBottom:8 }}>
                        <span style={{ fontFamily:'DM Mono, monospace', fontSize:12, color:'#6366f1', minWidth:18 }}>{n}</span>
                        <span style={{ fontSize:13, color:'#475569', lineHeight:1.5 }}>{t}</span>
                      </div>
                    ))}
                  </div>
                </motion.div>
              )}
            </AnimatePresence>

            <div style={S.hr} />

            {/* Encryption badge */}
            <div style={S.encBadge}>
              <Shield size={15} color="#059669" style={{ flexShrink:0, marginTop:2 }} />
              <div>
                <p style={{ margin:0, fontWeight:600, fontSize:13, color:'#065f46' }}>AES-256 Encrypted Storage</p>
                <p style={{ margin:'3px 0 0', fontSize:12, color:'#047857', lineHeight:1.5 }}>
                  Secret key is encrypted before persisting. Never logged or returned.
                </p>
              </div>
            </div>

            {/* Path preview */}
            {(form.s3Bucket || form.s3Prefix) && (
              <div style={S.pathBox}>
                <p style={{ margin:'0 0 5px', fontSize:11, fontWeight:600, color:'#6366f1', textTransform:'uppercase', letterSpacing:'.08em' }}>S3 Path Pattern</p>
                <code style={{ fontSize:12, color:'#1e293b', wordBreak:'break-all', lineHeight:1.6, fontFamily:'DM Mono, monospace' }}>{pathPreview}</code>
              </div>
            )}
          </motion.div>

          {/* ── RIGHT: Credentials ── */}
          <motion.div initial={{ opacity:0, x:20 }} animate={{ opacity:1, x:0, transition:{ delay:.1 } }} style={S.card}>
            <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', marginBottom:20 }}>
              <p style={{ ...S.sectionTitle, marginBottom:0 }}><Cloud size={15} style={{ marginRight:6 }} />AWS Credentials</p>
              {!form.enabled && (
                <span style={{
                  fontSize:11, fontWeight:600, color:'#9333ea',
                  background:'#faf5ff', border:'1px solid #e9d5ff',
                  borderRadius:6, padding:'3px 9px',
                }}>
                  Enable toggle to activate
                </span>
              )}
            </div>

            {/* Fields are ALWAYS editable — no disabled state based on toggle */}

            {/* Region */}
            <Field label="AWS Region">
              <div style={{ position:'relative' }}>
                <select value={form.s3Region} onChange={e => set('s3Region', e.target.value)}
                  style={{ ...S.input, paddingRight:36, appearance:'none', cursor:'pointer' }}>
                  {AWS_REGIONS.map(r => <option key={r} value={r}>{r}</option>)}
                </select>
                <ChevronDown size={14} style={{ position:'absolute', right:12, top:'50%', transform:'translateY(-50%)', color:'#94a3b8', pointerEvents:'none' }} />
              </div>
            </Field>

            {/* Access Key ID */}
            <Field label="AWS Access Key ID" hint="IAM user access key with S3 write permissions">
              <input value={form.s3AccessKeyId} onChange={e => set('s3AccessKeyId', e.target.value)}
                placeholder="AKIA…" autoComplete="off" style={S.input} />
            </Field>

            {/* Secret Access Key */}
            <Field label="AWS Secret Access Key" hint="Encrypted with AES-256 before saving — never stored in plain text">
              <div style={{ position:'relative' }}>
                <input value={form.s3SecretAccessKey} onChange={e => set('s3SecretAccessKey', e.target.value)}
                  type={showSecret ? 'text' : 'password'}
                  placeholder="••••••••••••••••••••••"
                  autoComplete="new-password"
                  style={{ ...S.input, paddingRight:40 }} />
                <button type="button" onClick={() => setShowSecret(v => !v)} style={S.eyeBtn}>
                  {showSecret ? <EyeOff size={15} /> : <Eye size={15} />}
                </button>
              </div>
            </Field>

            {/* Bucket */}
            <Field label="S3 Bucket Name" hint="Bucket must already exist in the selected region">
              <input value={form.s3Bucket} onChange={e => set('s3Bucket', e.target.value)}
                placeholder="my-reports-bucket" autoComplete="off" style={S.input} />
            </Field>

            {/* Prefix */}
            <Field label="Key Prefix (folder)" hint="Folder prefix inside the bucket — no leading slash">
              <input value={form.s3Prefix} onChange={e => set('s3Prefix', e.target.value)}
                placeholder="reports" autoComplete="off" style={S.input} />
            </Field>

            {/* Actions */}
            <div style={S.actions}>
              <button type="button" onClick={handleTest}
                disabled={!canTest}
                style={{ ...S.btnOutline, ...(!canTest ? S.btnDis : {}) }}>
                {testing
                  ? <Loader2 size={15} style={{ animation:'spin 1s linear infinite' }} />
                  : <RefreshCw size={15} />}
                Test Connection
              </button>

              <button type="submit" disabled={saving || !dirty}
                style={{ ...S.btnPrimary, ...(!dirty ? S.btnDis : {}) }}>
                {saving
                  ? <Loader2 size={15} style={{ animation:'spin 1s linear infinite' }} />
                  : <Save size={15} />}
                Save Settings
              </button>
            </div>

            {/* Test result */}
            <AnimatePresence>
              {testResult && (
                <motion.div initial={{ opacity:0, y:8 }} animate={{ opacity:1, y:0 }} exit={{ opacity:0 }}
                  style={{ ...S.testResult, background: testResult.ok ? '#f0fdf4' : '#fef2f2', borderColor: testResult.ok ? '#bbf7d0' : '#fecaca' }}>
                  {testResult.ok
                    ? <CheckCircle2 size={16} color="#16a34a" />
                    : <XCircle size={16} color="#dc2626" />}
                  <span style={{ fontSize:13, color: testResult.ok ? '#15803d' : '#dc2626' }}>{testResult.msg}</span>
                </motion.div>
              )}
            </AnimatePresence>

            {/* Unsaved warning */}
            <AnimatePresence>
              {dirty && (
                <motion.div initial={{ opacity:0 }} animate={{ opacity:1 }} exit={{ opacity:0 }} style={S.dirtyWarn}>
                  <AlertTriangle size={14} color="#b45309" />
                  <span style={{ fontSize:12, color:'#92400e' }}>You have unsaved changes</span>
                </motion.div>
              )}
            </AnimatePresence>
          </motion.div>
        </div>
      </form>

      {/* Toast */}
      <AnimatePresence>
        {saveDone && (
          <motion.div initial={{ opacity:0, y:40 }} animate={{ opacity:1, y:0 }} exit={{ opacity:0, y:40 }} style={S.toast}>
            <CheckCircle2 size={18} color="#fff" />
            <span>S3 settings saved &amp; encrypted successfully</span>
          </motion.div>
        )}
      </AnimatePresence>

      <style>{`
        @keyframes spin{to{transform:rotate(360deg)}}
        input:focus,select:focus{
          outline:2px solid #6366f1 !important;
          outline-offset:1px;
          border-color:#6366f1 !important;
        }
        input,select{transition:border-color .15s;}
        button:hover:not(:disabled){opacity:.88;}
      `}</style>
    </div>
  );
}

function Field({ label, hint, children }) {
  return (
    <div style={{ marginBottom:16 }}>
      <label style={{ display:'block', marginBottom:6, fontSize:13, fontWeight:500, color:'#374151' }}>{label}</label>
      {children}
      {hint && <p style={{ margin:'5px 0 0', fontSize:12, color:'#94a3b8', lineHeight:1.4 }}>{hint}</p>}
    </div>
  );
}

const S = {
  page:        { padding:'36px 40px', minHeight:'100vh', background:'#f8fafc', fontFamily:"'DM Sans',-apple-system,sans-serif", color:'#1e293b' },
  loader:      { display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center', minHeight:'60vh' },
  header:      { display:'flex', justifyContent:'space-between', alignItems:'center', marginBottom:28, background:'white', padding:'20px 28px', borderRadius:14, border:'1px solid #e2e8f0', boxShadow:'0 1px 4px rgba(0,0,0,.06)' },
  iconBadge:   { width:48, height:48, borderRadius:12, background:'linear-gradient(135deg,#6366f1,#818cf8)', display:'flex', alignItems:'center', justifyContent:'center', flexShrink:0, boxShadow:'0 4px 12px rgba(99,102,241,.3)' },
  title:       { margin:0, fontSize:22, fontWeight:700, color:'#0f172a' },
  subtitle:    { margin:'4px 0 0', fontSize:14, color:'#64748b' },
  pill:        { display:'flex', alignItems:'center', gap:8, padding:'8px 16px', borderRadius:999, fontSize:13, fontWeight:600 },
  pillOn:      { background:'#f0fdf4', color:'#166534', border:'1px solid #bbf7d0' },
  pillOff:     { background:'#f1f5f9', color:'#475569', border:'1px solid #e2e8f0' },
  grid:        { display:'grid', gridTemplateColumns:'1fr 1.6fr', gap:24, alignItems:'start' },
  card:        { background:'white', borderRadius:14, border:'1px solid #e2e8f0', padding:'28px', boxShadow:'0 1px 4px rgba(0,0,0,.05)' },
  sectionTitle:{ margin:'0 0 20px', fontWeight:700, fontSize:15, color:'#0f172a', display:'flex', alignItems:'center' },
  hr:          { height:1, background:'#f1f5f9', margin:'20px 0' },
  infoBtn:     { display:'flex', alignItems:'center', gap:8, background:'none', border:'none', cursor:'pointer', padding:'4px 0', fontSize:13, fontWeight:500, color:'#6366f1', fontFamily:'inherit' },
  infoBox:     { background:'#f8f9ff', border:'1px solid #e0e7ff', borderRadius:10, padding:16, marginTop:12 },
  encBadge:    { display:'flex', gap:12, alignItems:'flex-start', background:'#ecfdf5', border:'1px solid #a7f3d0', borderRadius:10, padding:'14px 16px', marginTop:4 },
  pathBox:     { marginTop:16, background:'#f8faff', border:'1px solid #e0e7ff', borderRadius:10, padding:'14px 16px' },
  input:       { width:'100%', padding:'10px 12px', border:'1.5px solid #e2e8f0', borderRadius:8, fontSize:14, color:'#1e293b', background:'white', fontFamily:"'DM Mono',monospace", boxSizing:'border-box' },
  eyeBtn:      { position:'absolute', right:10, top:'50%', transform:'translateY(-50%)', background:'none', border:'none', cursor:'pointer', color:'#94a3b8', padding:4 },
  actions:     { display:'flex', gap:12, marginTop:24, paddingTop:20, borderTop:'1px solid #f1f5f9' },
  btnPrimary:  { flex:1, display:'flex', alignItems:'center', justifyContent:'center', gap:8, background:'linear-gradient(135deg,#6366f1,#818cf8)', color:'white', border:'none', borderRadius:9, padding:'11px 20px', fontWeight:600, fontSize:14, cursor:'pointer', fontFamily:'inherit', boxShadow:'0 2px 8px rgba(99,102,241,.3)' },
  btnOutline:  { flex:1, display:'flex', alignItems:'center', justifyContent:'center', gap:8, background:'white', color:'#475569', border:'1.5px solid #e2e8f0', borderRadius:9, padding:'11px 20px', fontWeight:500, fontSize:14, cursor:'pointer', fontFamily:'inherit' },
  btnDis:      { opacity:.4, cursor:'not-allowed', pointerEvents:'none' },
  testResult:  { display:'flex', alignItems:'center', gap:10, marginTop:14, padding:'12px 14px', borderRadius:9, border:'1px solid' },
  dirtyWarn:   { display:'flex', alignItems:'center', gap:6, marginTop:10, padding:'8px 12px', background:'#fffbeb', border:'1px solid #fde68a', borderRadius:8 },
  toast:       { position:'fixed', bottom:28, right:28, background:'#1e293b', color:'white', padding:'14px 20px', borderRadius:12, display:'flex', alignItems:'center', gap:10, fontSize:14, fontWeight:500, boxShadow:'0 8px 24px rgba(0,0,0,.2)', zIndex:9999 },
};
