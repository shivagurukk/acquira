import React, { useEffect, useState } from 'react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { Globe2, Clock, UploadCloud, Save } from 'lucide-react';

// ============================================================================
// Regional & Data Settings — per-tenant, saved to tenant_setting via the
// hardened PUT /api/admin/settings (TenantContext-scoped).
//
// Keys managed here:
//   locale.date_format — DD/MM/YYYY | MM/DD/YYYY | YYYY-MM-DD | DD-MMM-YYYY
//                        Consumed app-wide by utils/formatters.js (formatDate /
//                        fmt.date), pushed by AuthContext from /users/me/locale.
//   locale.timezone    — IANA zone id; blank = each user's browser zone.
//   load.mode          — REPLACE | APPEND. Per-tenant override of the global
//                        acquira.load.mode flag; read by FileUploadService at
//                        upload time. JCB* files always APPEND regardless.
// ============================================================================

const DATE_FORMATS = [
    { value: 'DD/MM/YYYY', example: '15/09/2025' },
    { value: 'MM/DD/YYYY', example: '09/15/2025' },
    { value: 'YYYY-MM-DD', example: '2025-09-15' },
    { value: 'DD-MMM-YYYY', example: '15-Sep-2025' },
];

const TIMEZONES = [
    '', 'Asia/Bahrain', 'Asia/Dubai', 'Asia/Riyadh', 'Asia/Kuwait', 'Asia/Qatar',
    'Asia/Muscat', 'Asia/Kolkata', 'Asia/Singapore', 'Europe/London', 'Europe/Paris',
    'America/New_York', 'America/Chicago', 'America/Los_Angeles', 'UTC',
];

const KEYS = {
    dateFormat: 'locale.date_format',
    timezone: 'locale.timezone',
    loadMode: 'load.mode',
};

const card = {
    background: 'var(--bg-card)', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-lg, 12px)', padding: 24, marginBottom: 20, maxWidth: 720,
};
const labelStyle = { display: 'block', fontSize: 13, fontWeight: 600, color: 'var(--text)', marginBottom: 6 };
const hintStyle = { fontSize: 12, color: 'var(--text-secondary)', marginTop: 6, lineHeight: 1.5 };
const selectStyle = {
    width: '100%', maxWidth: 340, padding: '9px 12px', borderRadius: 8,
    border: '1px solid var(--border)', background: 'var(--bg)', color: 'var(--text)',
    fontSize: 13.5, outline: 'none', fontFamily: 'inherit',
};

const RegionalSettings = () => {
    const { tenantVersion } = useAuth();
    const [values, setValues] = useState({ dateFormat: 'DD/MM/YYYY', timezone: '', loadMode: '' });
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [msg, setMsg] = useState(null);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        api.get('/admin/settings')
            .then(res => {
                if (cancelled) return;
                const next = { dateFormat: 'DD/MM/YYYY', timezone: '', loadMode: '' };
                (res.data || []).forEach(s => {
                    const k = s.key || s.settingKey;
                    const v = s.value ?? s.settingValue ?? '';
                    if (k === KEYS.dateFormat && v) next.dateFormat = v;
                    if (k === KEYS.timezone) next.timezone = v || '';
                    if (k === KEYS.loadMode) next.loadMode = v || '';
                });
                setValues(next);
            })
            .catch(() => { /* keep defaults */ })
            .finally(() => { if (!cancelled) setLoading(false); });
        return () => { cancelled = true; };
    }, [tenantVersion]);

    const save = async () => {
        setSaving(true);
        setMsg(null);
        try {
            await api.put('/admin/settings', { settingKey: KEYS.dateFormat, settingValue: values.dateFormat });
            await api.put('/admin/settings', { settingKey: KEYS.timezone, settingValue: values.timezone });
            // Only persist load.mode when explicitly chosen — blank means "follow
            // the global acquira.load.mode property", so don't write an empty row.
            if (values.loadMode) {
                await api.put('/admin/settings', { settingKey: KEYS.loadMode, settingValue: values.loadMode });
            }
            setMsg({ ok: true, text: 'Saved. Date format applies after the next page refresh; load mode applies to the next upload.' });
        } catch (e) {
            setMsg({ ok: false, text: e?.response?.data?.error || 'Save failed' });
        } finally {
            setSaving(false);
        }
    };

    if (loading) {
        return <div style={{ padding: 40, color: 'var(--text-secondary)' }}>Loading regional settings…</div>;
    }

    return (
        <div style={{ padding: 32, color: 'var(--text)' }}>
            <h1 style={{ fontSize: 20, fontWeight: 700, marginBottom: 4 }}>Regional &amp; Data Settings</h1>
            <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 24 }}>
                Per-bank display and ingestion preferences. These apply to this bank only.
            </p>

            <div style={card}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16 }}>
                    <Globe2 size={16} style={{ color: 'var(--brand, #2563eb)' }} />
                    <span style={{ fontSize: 14, fontWeight: 700 }}>Date format</span>
                </div>
                <label style={labelStyle}>How dates are displayed across dashboards and reports</label>
                <select
                    value={values.dateFormat}
                    onChange={e => setValues(v => ({ ...v, dateFormat: e.target.value }))}
                    style={selectStyle}
                >
                    {DATE_FORMATS.map(f => (
                        <option key={f.value} value={f.value}>{f.value} — e.g. {f.example}</option>
                    ))}
                </select>
                <div style={hintStyle}>
                    Applies to every user of this bank. Chart axis labels keep their short form
                    ("15 Sep" / "Sep 15") but follow the day-first / month-first order chosen here.
                </div>
            </div>

            <div style={card}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16 }}>
                    <Clock size={16} style={{ color: 'var(--brand, #2563eb)' }} />
                    <span style={{ fontSize: 14, fontWeight: 700 }}>Timezone</span>
                </div>
                <label style={labelStyle}>Timezone used when rendering timestamps</label>
                <select
                    value={values.timezone}
                    onChange={e => setValues(v => ({ ...v, timezone: e.target.value }))}
                    style={selectStyle}
                >
                    {TIMEZONES.map(tz => (
                        <option key={tz || 'browser'} value={tz}>{tz || 'Browser default (each user\u2019s own zone)'}</option>
                    ))}
                </select>
                <div style={hintStyle}>
                    Business dates in reports are calendar dates and are unaffected; this only
                    changes how timestamps (e.g. batch run times) are displayed.
                </div>
            </div>

            <div style={card}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16 }}>
                    <UploadCloud size={16} style={{ color: 'var(--brand, #2563eb)' }} />
                    <span style={{ fontSize: 14, fontWeight: 700 }}>Transaction load mode</span>
                </div>
                <label style={labelStyle}>How transaction file uploads treat existing data for the same dates</label>
                <select
                    value={values.loadMode}
                    onChange={e => setValues(v => ({ ...v, loadMode: e.target.value }))}
                    style={selectStyle}
                >
                    <option value="">Platform default (follows server configuration)</option>
                    <option value="REPLACE">REPLACE — delete existing rows for the file&apos;s dates, then insert</option>
                    <option value="APPEND">APPEND — layer the file onto existing rows for those dates</option>
                </select>
                <div style={hintStyle}>
                    Overrides the platform-wide setting for this bank only. JCB files always load in
                    APPEND mode regardless. Merchant-master files are unaffected (always upsert).
                    Takes effect from the next upload.
                </div>
            </div>

            {msg && (
                <div style={{
                    padding: '10px 14px', borderRadius: 8, marginBottom: 16, fontSize: 13, maxWidth: 720,
                    background: msg.ok ? 'var(--success-bg, #ecfdf5)' : 'var(--danger-bg, #fef2f2)',
                    color: msg.ok ? 'var(--success, #059669)' : 'var(--danger, #dc2626)',
                    border: `1px solid ${msg.ok ? 'var(--success, #059669)' : 'var(--danger, #dc2626)'}`,
                }}>
                    {msg.text}
                </div>
            )}

            <button
                onClick={save}
                disabled={saving}
                style={{
                    display: 'inline-flex', alignItems: 'center', gap: 8, padding: '10px 18px',
                    borderRadius: 9, border: 'none', cursor: saving ? 'default' : 'pointer',
                    background: 'var(--brand, #2563eb)', color: '#fff', fontSize: 13.5, fontWeight: 600,
                    opacity: saving ? 0.7 : 1, fontFamily: 'inherit',
                }}
            >
                <Save size={15} /> {saving ? 'Saving…' : 'Save settings'}
            </button>
        </div>
    );
};

export default RegionalSettings;
