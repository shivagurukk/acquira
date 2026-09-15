import React, { useState, useEffect } from 'react';
import { Tooltip } from '@mui/material';
import { Store, Hash } from 'lucide-react';
import api from '../api/axios';
import { formatNumber } from '../utils/formatters';

/**
 * Shared executive header strip — distinct count of ACTIVE MIDs and SIDs for
 * the window the host screen is currently showing. One backend endpoint
 * (/executive/mid-sid-summary) so the figure is identical on every EXECUTIVE
 * screen; each screen just passes the window (and channel) it displays.
 *
 * "Active" = transacted in the window (total_txns > 0), read off sum_daily_full
 * which is the one summary carrying both the MID (merchant_id) and SID
 * (store_id) grains together with the channel dimension.
 *
 * Props
 *   from, to  — inclusive ISO dates (YYYY-MM-DD). Omit BOTH to let the backend
 *               fall back to the tenant's latest loaded month (MTD).
 *   channel   — 'POS' | 'ECOM' | 'ALL' (default ALL). Screens without a channel
 *               toggle simply leave it ALL.
 *   compact   — tighter padding for dense headers.
 */
export default function MidSidSummary({ from, to, channel = 'ALL', compact = false, style }) {
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        const params = { channel: channel || 'ALL' };
        // Send dates only when the host has a resolved window; a half-open range
        // is left to the backend's MTD fallback rather than guessed at here.
        if (from && to) { params.from = from; params.to = to; }
        api.get('/executive/mid-sid-summary', { params })
            .then((res) => { if (!cancelled) setData(res.data); })
            .catch(() => { if (!cancelled) setData(null); })
            .finally(() => { if (!cancelled) setLoading(false); });
        return () => { cancelled = true; };
    }, [from, to, channel]);

    const mids = data ? data.mids : null;
    const sids = data ? data.sids : null;

    /* `definition` surfaces on hover so the strip explains its own terms —
       what counts as "active", and at which grain, for the window shown. */
    const cell = (icon, label, value, definition) => (
        <Tooltip title={definition} arrow placement="bottom">
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0, cursor: 'help' }}>
            <span style={{
                display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                width: 30, height: 30, borderRadius: 8, flexShrink: 0,
                background: 'var(--accent-light, rgba(0,0,0,0.05))', color: 'var(--accent, #2563eb)',
            }}>{icon}</span>
            <div style={{ display: 'flex', flexDirection: 'column', lineHeight: 1.1, minWidth: 0 }}>
                <span style={{
                    fontSize: 17, fontWeight: 700, fontVariantNumeric: 'tabular-nums',
                    color: 'var(--text, #1a2233)',
                }}>
                    {loading ? '—' : (value == null ? '—' : formatNumber(value))}
                </span>
                <span style={{
                    fontSize: 10.5, fontWeight: 600, letterSpacing: '0.06em',
                    textTransform: 'uppercase', color: 'var(--text-muted, #64748b)',
                }}>{label}</span>
            </div>
        </div>
        </Tooltip>
    );

    return (
        <div
            aria-label="Active MID and SID summary"
            style={{
                display: 'inline-flex', alignItems: 'center', gap: compact ? 16 : 22,
                padding: compact ? '8px 14px' : '10px 16px',
                borderRadius: 12, border: '1px solid var(--border, #e2e8f0)',
                background: 'var(--bg-card, var(--surface, #fff))',
                ...style,
            }}
        >
            {cell(<Hash size={16} strokeWidth={2.4} />, 'Active MIDs', mids,
                'Merchant IDs (MIDs) with at least one transaction in the period and channel shown on this screen.')}
            <span style={{ width: 1, alignSelf: 'stretch', background: 'var(--border, #e2e8f0)' }} />
            {cell(<Store size={16} strokeWidth={2.4} />, 'Active SIDs', sids,
                'Store IDs (SIDs) — individual stores / terminals under the MIDs — with at least one transaction in the period and channel shown on this screen.')}
        </div>
    );
}
