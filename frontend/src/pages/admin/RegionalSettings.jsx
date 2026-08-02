import React, { useEffect, useState } from 'react';
import { Globe2, Clock, UploadCloud, Save } from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import { Page, Stack, Card, Button, FormField, Select } from '../../components/ui';

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

const DATE_FORMAT_OPTIONS = DATE_FORMATS.map(f => ({
    value: f.value,
    label: `${f.value} (e.g. ${f.example})`,
}));

const TIMEZONE_OPTIONS = TIMEZONES.filter(Boolean).map(tz => ({ value: tz, label: tz }));

const LOAD_MODE_OPTIONS = [
    { value: 'REPLACE', label: 'REPLACE (delete existing rows for the file dates, then insert)' },
    { value: 'APPEND', label: 'APPEND (layer the file onto existing rows for those dates)' },
];

const titleIconStyle = { color: 'var(--brand)', flexShrink: 0 };
const titleRowStyle = { gap: 8, flexWrap: 'nowrap' };

const RegionalSettings = () => {
    const { tenantVersion } = useAuth();
    const [values, setValues] = useState({ dateFormat: 'DD/MM/YYYY', timezone: '', loadMode: '' });
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);

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
        try {
            await api.put('/admin/settings', { settingKey: KEYS.dateFormat, settingValue: values.dateFormat });
            await api.put('/admin/settings', { settingKey: KEYS.timezone, settingValue: values.timezone });
            // Only persist load.mode when explicitly chosen — blank means "follow
            // the global acquira.load.mode property", so don't write an empty row.
            if (values.loadMode) {
                await api.put('/admin/settings', { settingKey: KEYS.loadMode, settingValue: values.loadMode });
            }
            showToast('Settings saved. Date format applies after the next page refresh; load mode applies to the next upload.', 'success');
        } catch (e) {
            showToast(e?.response?.data?.error || 'Save failed', 'error');
        } finally {
            setSaving(false);
        }
    };

    return (
        <Page
            width="narrow"
            title="Regional and data settings"
            subtitle="Per-bank display and ingestion preferences. These apply to this bank only."
            icon={Globe2}
            actions={
                <Button variant="primary" icon={Save} onClick={save} loading={saving} disabled={loading}>
                    Save settings
                </Button>
            }
        >
            {loading ? (
                <Card pad>
                    <p className="ui-field__hint" style={{ margin: 0 }}>Loading regional settings…</p>
                </Card>
            ) : (
                <Stack>
                    <Card
                        pad
                        title={
                            <span className="ui-row" style={titleRowStyle}>
                                <Globe2 size={15} strokeWidth={2} style={titleIconStyle} />
                                Date format
                            </span>
                        }
                    >
                        <FormField
                            label="How dates are displayed across dashboards and reports"
                            hint={'Applies to every user of this bank. Chart axis labels keep their short form ("15 Sep" / "Sep 15") but follow the day-first or month-first order chosen here.'}
                        >
                            <Select
                                value={values.dateFormat}
                                onChange={e => setValues(v => ({ ...v, dateFormat: e.target.value }))}
                                options={DATE_FORMAT_OPTIONS}
                                style={{ maxWidth: 340 }}
                            />
                        </FormField>
                    </Card>

                    <Card
                        pad
                        title={
                            <span className="ui-row" style={titleRowStyle}>
                                <Clock size={15} strokeWidth={2} style={titleIconStyle} />
                                Timezone
                            </span>
                        }
                    >
                        <FormField
                            label="Timezone used when rendering timestamps"
                            hint="Business dates in reports are calendar dates and are unaffected. This only changes how timestamps (e.g. batch run times) are displayed."
                        >
                            <Select
                                value={values.timezone}
                                onChange={e => setValues(v => ({ ...v, timezone: e.target.value }))}
                                placeholder="Browser default (each user's own zone)"
                                options={TIMEZONE_OPTIONS}
                                style={{ maxWidth: 340 }}
                            />
                        </FormField>
                    </Card>

                    <Card
                        pad
                        title={
                            <span className="ui-row" style={titleRowStyle}>
                                <UploadCloud size={15} strokeWidth={2} style={titleIconStyle} />
                                Transaction load mode
                            </span>
                        }
                    >
                        <FormField
                            label="How transaction file uploads treat existing data for the same dates"
                            hint="Overrides the platform-wide setting for this bank only. JCB files always load in APPEND mode regardless. Merchant-master files are unaffected (always upsert). Takes effect from the next upload."
                        >
                            <Select
                                value={values.loadMode}
                                onChange={e => setValues(v => ({ ...v, loadMode: e.target.value }))}
                                placeholder="Platform default (follows server configuration)"
                                options={LOAD_MODE_OPTIONS}
                                style={{ maxWidth: 460 }}
                            />
                        </FormField>
                    </Card>
                </Stack>
            )}
        </Page>
    );
};

export default RegionalSettings;
