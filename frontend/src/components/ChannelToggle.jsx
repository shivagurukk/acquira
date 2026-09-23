/**
 * ChannelToggle — the executive POS / ECOM / All channel selector
 * (user request 2026-09-08), shared by every EXECUTIVE-menu page.
 *
 * Values are the backend contract of ChannelSql.normalize: 'ALL' | 'POS' |
 * 'ECOM' — anything else is treated as ALL server-side. 'ALL' reads the
 * untouched sum_daily_merchant path; POS/ECOM route to the channel-scoped
 * relation over sum_daily_full, with ancillary revenue attributed wholesale
 * (DCC + rental -> POS, ecom FX income -> ECOM).
 *
 * Pure controlled component — pages own the state so they can reset it on
 * tenant switch and feed it into their fetch params + cache-busting keys.
 */
import React from 'react';

export const CHANNEL_OPTIONS = [
    { value: 'ALL', label: 'All' },
    { value: 'POS', label: 'POS' },
    { value: 'ECOM', label: 'Ecom' },
];

export default function ChannelToggle({ value = 'ALL', onChange, compact = false }) {
    return (
        <div role="group" aria-label="Channel"
            style={{
                display: 'inline-flex', alignItems: 'center',
                border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)',
                overflow: 'hidden', background: 'var(--bg-card)', flexShrink: 0,
            }}>
            {CHANNEL_OPTIONS.map((opt, i) => {
                const on = value === opt.value;
                return (
                    <button key={opt.value} type="button"
                        aria-pressed={on}
                        onClick={() => { if (!on && onChange) onChange(opt.value); }}
                        style={{
                            padding: compact ? '4px 9px' : '6px 12px',
                            fontSize: compact ? 11 : 12,
                            fontWeight: on ? 700 : 600,
                            fontFamily: 'var(--font-ui)',
                            letterSpacing: '0.02em',
                            border: 0,
                            borderLeft: i > 0 ? '1px solid var(--border)' : 0,
                            cursor: on ? 'default' : 'pointer',
                            background: on ? 'var(--accent, #2f6f6a)' : 'transparent',
                            color: on ? '#fff' : 'var(--text-muted, var(--text))',
                            transition: 'background 120ms ease, color 120ms ease',
                        }}>
                        {opt.label}
                    </button>
                );
            })}
        </div>
    );
}
