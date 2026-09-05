import { useCallback, useEffect, useState } from 'react';
import { Mail, Save, Send, Eye, RefreshCw, CalendarSearch, Clock } from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { showToast } from '../../contexts/ToastContext';
import {
    Page, Stack, Card, Button, FormField, FormGrid, Input, Switch, StatusBadge,
} from '../../components/ui';

/**
 * /ops/daily-digest — configure the Daily Dashboard Digest email, watch its
 * dispatch ledger, and run it by hand for a chosen day.
 *
 * The digest is sent by a backend timer only after ALL required feeds for a
 * business day have landed (transactions + DCC + rentals, each toggleable
 * below), debounced by a quiet period, and — when a send time is set — held
 * until that tenant-local time. The "Run for a day" card is the manual path:
 * check what the gate sees for a date (yesterday by default), then send the
 * real digest for it on the spot.
 */

const WAITING_LABELS = {
    MERCHANT: 'merchant master', TRX: 'transactions', DCC: 'DCC feed', RENTAL: 'rental feed',
    RUNNING: 'an ingest still running', QUIET: 'quiet period',
    SCHEDULE: 'scheduled send time',
};

const waitingText = (w) => !w ? '' :
    w.split('+').map(k => WAITING_LABELS[k] || k).join(' + ');

const MONO = "var(--font-mono, 'IBM Plex Mono', ui-monospace, monospace)";
const yesterday = () => {
    const d = new Date(Date.now() - 86400000);
    return d.toISOString().slice(0, 10);
};

/** Small stat tile for the status strip. */
function Stat({ label, value, sub, tone }) {
    const color = tone === 'neg' ? 'var(--negative, #B3382C)'
        : tone === 'pos' ? 'var(--chart-pos, #0FA070)'
        : tone === 'warn' ? 'var(--attention, #8C5E12)'
        : 'var(--ink, #14295E)';
    return (
        <div style={{
            flex: '1 1 150px', minWidth: 0, background: 'var(--surface, #EAF1FA)',
            border: '1px solid var(--hairline, #E4E7EC)', borderRadius: 12, padding: '13px 16px',
        }}>
            <div style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: 0.8, textTransform: 'uppercase', color: 'var(--muted, #51618C)', marginBottom: 5 }}>{label}</div>
            <div style={{ fontSize: 18, fontWeight: 700, color, fontFamily: MONO, lineHeight: 1.15 }}>{value}</div>
            {sub && <div style={{ fontSize: 11, color: 'var(--muted, #51618C)', marginTop: 3 }}>{sub}</div>}
        </div>
    );
}

/** ✓/✗ chip for one feed in the readiness panel. */
function FeedChip({ label, ok }) {
    const color = ok ? 'var(--chart-pos, #0FA070)' : 'var(--negative, #B3382C)';
    return (
        <span style={{
            display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12.5, fontWeight: 600,
            padding: '5px 12px', borderRadius: 999, color,
            border: `1px solid ${color}`, background: ok ? 'rgba(15,160,112,0.07)' : 'rgba(179,56,44,0.06)',
        }}>
            <span style={{ fontFamily: MONO }}>{ok ? '✓' : '✗'}</span> {label}
        </span>
    );
}

export default function DailyDigest() {
    const { tenantVersion } = useAuth();
    const [cfg, setCfg] = useState(null);
    const [dispatches, setDispatches] = useState([]);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [testing, setTesting] = useState(false);
    const [previewHtml, setPreviewHtml] = useState(null);
    const [previewLoading, setPreviewLoading] = useState(false);

    // Run-for-a-day tool
    const [runDate, setRunDate] = useState(yesterday());
    const [dayStatus, setDayStatus] = useState(null);
    const [dayLoading, setDayLoading] = useState(false);
    const [running, setRunning] = useState(false);
    const [needsForce, setNeedsForce] = useState(false);

    const load = useCallback(async () => {
        setLoading(true);
        try {
            const [c, d] = await Promise.all([
                api.get('/ops/digest/config'),
                api.get('/ops/digest/dispatches'),
            ]);
            setCfg({
                enabled: !!c.data.enabled,
                recipients: c.data.recipients || '',
                quietMinutes: c.data.quiet_minutes ?? 15,
                requireMerchant: c.data.require_merchant !== false,
                requireTrx: c.data.require_trx !== false,
                requireDcc: c.data.require_dcc !== false,
                requireRental: c.data.require_rental !== false,
                backfillWindowDays: c.data.backfill_window_days ?? 3,
                sendNotBefore: c.data.send_not_before || '',
            });
            setDispatches(d.data || []);
        } catch (e) {
            showToast(e?.response?.data?.error || 'Failed to load digest settings', 'error');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => { load(); }, [load, tenantVersion]);

    const save = async () => {
        setSaving(true);
        try {
            await api.put('/ops/digest/config', cfg);
            showToast('Digest settings saved.', 'success');
            load();
        } catch (e) {
            showToast(e?.response?.data?.error || 'Save failed', 'error');
        } finally {
            setSaving(false);
        }
    };

    const testSend = async () => {
        setTesting(true);
        try {
            const res = await api.post('/ops/digest/test-send', { recipients: cfg.recipients });
            showToast(`Test digest sent to ${res.data.sent}/${res.data.of} recipient(s) for ${res.data.date}.`, 'success');
        } catch (e) {
            showToast(e?.response?.data?.error || 'Test send failed', 'error');
        } finally {
            setTesting(false);
        }
    };

    const preview = async (date) => {
        setPreviewLoading(true);
        try {
            const res = await api.get('/ops/digest/preview', { params: date ? { date } : {} });
            setPreviewHtml(res.data);
        } catch (e) {
            showToast(e?.response?.data?.error || 'Preview failed', 'error');
        } finally {
            setPreviewLoading(false);
        }
    };

    // needsForce is deliberately NOT reset here — runDay re-checks the day
    // after an "already sent" response, and the Resend button must survive it.
    // The flag resets when the date changes (input onChange).
    const checkDay = async (date) => {
        setDayLoading(true);
        try {
            const res = await api.get('/ops/digest/day-status', { params: { date } });
            setDayStatus(res.data);
        } catch (e) {
            showToast(e?.response?.data?.error || 'Readiness check failed', 'error');
            setDayStatus(null);
        } finally {
            setDayLoading(false);
        }
    };

    const runDay = async (force) => {
        setRunning(true);
        try {
            const res = await api.post('/ops/digest/run', { date: runDate, force: !!force });
            if (res.data.alreadySent) {
                setNeedsForce(true);
                showToast(`The digest for ${res.data.date} was already sent. Use "Resend anyway" to send it again.`, 'warning');
            } else if (res.data.status === 'SENT') {
                setNeedsForce(false);
                showToast(`Digest for ${res.data.date} sent to ${res.data.recipients_sent || 'recipients'}.`, 'success');
            } else {
                showToast(res.data.error_message || `Digest for ${res.data.date} did not send — see dispatch history.`, 'error');
            }
            load();
            checkDay(runDate);
        } catch (e) {
            showToast(e?.response?.data?.error || 'Run failed', 'error');
        } finally {
            setRunning(false);
        }
    };

    const set = (k) => (v) => setCfg(c => ({ ...c, [k]: v }));
    const setToggle = (k) => (e) => set(k)(e.target.checked);

    // status strip derived numbers
    const lastSent = dispatches.find(r => r.status === 'SENT');
    const pendingCount = dispatches.filter(r => r.status === 'PENDING').length;
    const failedCount = dispatches.filter(r => r.status === 'FAILED').length;

    return (
        <Page
            title="Daily Digest"
            subtitle="One executive summary email per business day, sent automatically once transactions, DCC and rental feeds are all loaded."
            icon={Mail}
            actions={(
                <span className="ui-row" style={{ gap: 8 }}>
                    <Button icon={RefreshCw} onClick={load} disabled={loading}>Refresh</Button>
                    <Button variant="primary" icon={Save} onClick={save} loading={saving} disabled={loading || !cfg}>
                        Save settings
                    </Button>
                </span>
            )}
        >
            {loading || !cfg ? (
                <Card pad><p className="ui-field__hint" style={{ margin: 0 }}>Loading…</p></Card>
            ) : (
                <Stack>
                    {/* ── status strip ─────────────────────────────────── */}
                    <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                        <Stat label="Digest" value={cfg.enabled ? 'On' : 'Off'}
                            tone={cfg.enabled ? 'pos' : 'warn'}
                            sub={cfg.enabled
                                ? (cfg.sendNotBefore ? `sends after ${cfg.sendNotBefore}` : 'sends as soon as ready')
                                : 'days tracked, no email sent'} />
                        <Stat label="Last sent" value={lastSent ? lastSent.business_date : '—'}
                            sub={lastSent?.sent_at ? `at ${String(lastSent.sent_at).replace('T', ' ').slice(11, 16)}` : 'no digest sent yet'} />
                        <Stat label="Waiting" value={String(pendingCount)}
                            tone={pendingCount ? 'warn' : undefined}
                            sub={pendingCount ? 'day(s) held by the feed gate' : 'nothing pending'} />
                        <Stat label="Failed" value={String(failedCount)}
                            tone={failedCount ? 'neg' : 'pos'}
                            sub={failedCount ? 'check SMTP settings and retry below' : 'no delivery failures'} />
                    </div>

                    {/* ── delivery + schedule ──────────────────────────── */}
                    <Card pad title="Delivery">
                        <FormGrid>
                            <FormField label="Digest enabled"
                                hint="When off, days are still tracked but no email is sent.">
                                <Switch checked={cfg.enabled} onChange={setToggle('enabled')} />
                            </FormField>
                            <FormField label="Recipients"
                                hint="Comma-separated email addresses.">
                                <Input value={cfg.recipients}
                                    onChange={e => set('recipients')(e.target.value)}
                                    placeholder="cfo@bank.com, ops@bank.com" />
                            </FormField>
                            <FormField label="Scheduled send time"
                                hint="A ready day is held until this bank-local time, so the email lands at a predictable hour. Blank = send as soon as the feeds are in. Uses the timezone from Regional & Data settings.">
                                <span className="ui-row" style={{ gap: 8, alignItems: 'center' }}>
                                    <Clock size={15} style={{ color: 'var(--muted, #51618C)', flexShrink: 0 }} />
                                    <Input type="time" value={cfg.sendNotBefore}
                                        onChange={e => set('sendNotBefore')(e.target.value)}
                                        style={{ maxWidth: 140 }} />
                                    {cfg.sendNotBefore && (
                                        <Button onClick={() => set('sendNotBefore')('')}>Clear</Button>
                                    )}
                                </span>
                            </FormField>
                            <FormField label="Quiet period (minutes)"
                                hint="Wait this long after the last ingest finishes, so a multi-file upload session emails once.">
                                <Input type="number" min={0} max={240} value={cfg.quietMinutes}
                                    onChange={e => set('quietMinutes')(Number(e.target.value))}
                                    style={{ maxWidth: 140 }} />
                            </FormField>
                            <FormField label="Look-back window (days)"
                                hint="Only days this recent trigger an email — an old-history backfill stays silent.">
                                <Input type="number" min={1} max={14} value={cfg.backfillWindowDays}
                                    onChange={e => set('backfillWindowDays')(Number(e.target.value))}
                                    style={{ maxWidth: 140 }} />
                            </FormField>
                        </FormGrid>
                    </Card>

                    <Card pad title="Required feeds"
                        subtitle="The digest waits for every feed switched on here. Switch one off if this bank does not receive that feed.">
                        <FormGrid>
                            <FormField label="Merchant master"
                                hint="Occasional upsert feed — passes once the bank's merchant dimension has ever been loaded.">
                                <Switch checked={cfg.requireMerchant} onChange={setToggle('requireMerchant')} />
                            </FormField>
                            <FormField label="Merchant transactions">
                                <Switch checked={cfg.requireTrx} onChange={setToggle('requireTrx')} />
                            </FormField>
                            <FormField label="DCC revenue">
                                <Switch checked={cfg.requireDcc} onChange={setToggle('requireDcc')} />
                            </FormField>
                            <FormField label="Rentals / one-time fees"
                                hint="Monthly feed — a rental file covering the month satisfies every day in it.">
                                <Switch checked={cfg.requireRental} onChange={setToggle('requireRental')} />
                            </FormField>
                        </FormGrid>
                    </Card>

                    {/* ── run for a specific day ───────────────────────── */}
                    <Card pad title="Run for a day"
                        subtitle="Check what the gate sees for a business date — yesterday by default — then send the real digest for it now. Manual sends skip the quiet period and the send time.">
                        <span className="ui-row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
                            <Input type="date" value={runDate} max={new Date().toISOString().slice(0, 10)}
                                onChange={e => { setRunDate(e.target.value); setDayStatus(null); setNeedsForce(false); }}
                                style={{ maxWidth: 170 }} />
                            <Button icon={CalendarSearch} onClick={() => checkDay(runDate)} loading={dayLoading}>
                                Check readiness
                            </Button>
                            <Button icon={Eye} onClick={() => preview(runDate)} loading={previewLoading}>
                                Preview this day
                            </Button>
                            {needsForce ? (
                                <Button variant="primary" icon={Send} onClick={() => runDay(true)} loading={running}>
                                    Resend anyway
                                </Button>
                            ) : (
                                <Button variant="primary" icon={Send} onClick={() => runDay(false)} loading={running}
                                    disabled={!cfg.recipients}>
                                    Send digest now
                                </Button>
                            )}
                        </span>
                        {!cfg.recipients && (
                            <p className="ui-field__hint" style={{ marginTop: 8 }}>
                                Add recipients above before sending.
                            </p>
                        )}

                        {dayStatus && (
                            <div style={{ marginTop: 14, borderTop: '1px solid var(--hairline, #E4E7EC)', paddingTop: 12 }}>
                                <div style={{ fontSize: 12, color: 'var(--muted, #51618C)', marginBottom: 8, fontFamily: MONO }}>
                                    {dayStatus.date}
                                </div>
                                <span className="ui-row" style={{ gap: 8, flexWrap: 'wrap' }}>
                                    <FeedChip label="Merchants" ok={!!dayStatus.merchant} />
                                    <FeedChip label="Transactions" ok={!!dayStatus.trx} />
                                    <FeedChip label="DCC" ok={!!dayStatus.dcc} />
                                    <FeedChip label="Rentals" ok={!!dayStatus.rental} />
                                    {dayStatus.running && <FeedChip label="Ingest running" ok={false} />}
                                </span>
                                <div style={{ marginTop: 10, fontSize: 12.5, color: 'var(--muted, #51618C)' }}>
                                    {dayStatus.dispatch ? (
                                        <span className="ui-row" style={{ gap: 8, alignItems: 'center' }}>
                                            <StatusBadge status={dayStatus.dispatch.status} />
                                            {dayStatus.dispatch.status === 'SENT' && dayStatus.dispatch.sent_at &&
                                                <>sent {String(dayStatus.dispatch.sent_at).replace('T', ' ').slice(0, 16)} to {dayStatus.dispatch.recipients_sent}</>}
                                            {dayStatus.dispatch.status === 'PENDING' &&
                                                <>waiting on {waitingText(dayStatus.dispatch.waiting_on) || 'the next sweep'}</>}
                                            {dayStatus.dispatch.status === 'FAILED' &&
                                                <span title={dayStatus.dispatch.error_message || ''}>{dayStatus.dispatch.error_message || 'delivery failed'}</span>}
                                        </span>
                                    ) : (
                                        'No dispatch row yet — sending now will create one.'
                                    )}
                                </div>
                            </div>
                        )}
                    </Card>

                    {/* ── preview & test ───────────────────────────────── */}
                    <Card pad title="Try it"
                        subtitle="Both use the latest loaded business day and skip the feed gate. Test sends stamp [TEST] on the subject.">
                        <span className="ui-row" style={{ gap: 8 }}>
                            <Button icon={Eye} onClick={() => preview()} loading={previewLoading}>Preview email</Button>
                            <Button icon={Send} onClick={testSend} loading={testing}>
                                Send test to recipients
                            </Button>
                        </span>
                        {previewHtml && (
                            <div style={{
                                marginTop: 12, border: '1px solid var(--hairline, #E4E7EC)',
                                borderRadius: 8, overflow: 'hidden', background: 'var(--canvas, #F1F7FF)',
                            }}>
                                <iframe title="Digest preview" srcDoc={previewHtml}
                                    style={{ width: '100%', height: 640, border: 'none', display: 'block' }} />
                            </div>
                        )}
                    </Card>

                    {/* ── dispatch history ─────────────────────────────── */}
                    <Card pad title="Dispatch history"
                        subtitle="One row per business day. Waiting rows show which feed the digest is still holding for.">
                        {dispatches.length === 0 ? (
                            <p className="ui-field__hint" style={{ margin: 0 }}>
                                Nothing yet — rows appear as soon as data lands for a recent day.
                            </p>
                        ) : (
                            <div style={{ overflowX: 'auto' }}>
                                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                                    <thead>
                                        <tr style={{ textAlign: 'left', color: 'var(--muted, #51618C)' }}>
                                            <th style={{ padding: '6px 8px', fontSize: 11, textTransform: 'uppercase', letterSpacing: 0.5 }}>Business date</th>
                                            <th style={{ padding: '6px 8px', fontSize: 11, textTransform: 'uppercase', letterSpacing: 0.5 }}>Status</th>
                                            <th style={{ padding: '6px 8px', fontSize: 11, textTransform: 'uppercase', letterSpacing: 0.5 }}>Waiting on</th>
                                            <th style={{ padding: '6px 8px', fontSize: 11, textTransform: 'uppercase', letterSpacing: 0.5 }}>Sent at</th>
                                            <th style={{ padding: '6px 8px', fontSize: 11, textTransform: 'uppercase', letterSpacing: 0.5 }}>Recipients</th>
                                            <th style={{ padding: '6px 8px', fontSize: 11, textTransform: 'uppercase', letterSpacing: 0.5 }}>Note</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {dispatches.map(row => (
                                            <tr key={row.id} style={{ borderTop: '1px solid var(--hairline, #E4E7EC)' }}>
                                                <td style={{ padding: '7px 8px', fontFamily: MONO, fontSize: 12.5 }}>
                                                    {row.business_date}
                                                </td>
                                                <td style={{ padding: '7px 8px' }}>
                                                    <StatusBadge status={row.status} />
                                                </td>
                                                <td style={{ padding: '7px 8px', color: 'var(--muted, #51618C)' }}>
                                                    {row.status === 'PENDING' ? waitingText(row.waiting_on) : ''}
                                                </td>
                                                <td style={{ padding: '7px 8px', color: 'var(--muted, #51618C)', fontFamily: MONO, fontSize: 12.5 }}>
                                                    {row.sent_at ? String(row.sent_at).replace('T', ' ').slice(0, 16) : '—'}
                                                </td>
                                                <td style={{ padding: '7px 8px', color: 'var(--muted, #51618C)',
                                                    maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis',
                                                    whiteSpace: 'nowrap' }}>
                                                    {row.recipients_sent || ''}
                                                </td>
                                                <td style={{ padding: '7px 8px', color: 'var(--muted, #51618C)',
                                                    maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis',
                                                    whiteSpace: 'nowrap' }} title={row.error_message || ''}>
                                                    {row.error_message || ''}
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </Card>
                </Stack>
            )}
        </Page>
    );
}