import React from 'react';
import { Box, Typography } from '@mui/material';
import { ArrowRight, CalendarClock } from 'lucide-react';

/**
 * DataBoundsBanner — "your view doesn't reach the latest data" nudge.
 *
 * WHY
 * ---
 * Report pages resolve the real data window from useDataBounds (earliest ->
 * latest). When the user narrows the date range to something that ENDS before
 * `latest` — or a page defaults to a partial/stale range — there is newer data
 * they aren't seeing. This banner surfaces that and offers a one-click jump to
 * the latest window, so "why is my data old?" stops being a support question.
 *
 * DESIGN
 * ------
 * Additive and opt-in: drop it in above a report grid. It renders NOTHING when
 * the current range already reaches latest (or bounds haven't resolved), so it
 * is safe to leave mounted unconditionally. Colours route through CSS variables
 * (register aesthetic, dark-mode safe) — no hardcoded hex.
 *
 * USAGE
 * -----
 *   const { latest, boundsLoaded, startDate: bStart, endDate: bEnd } = useDataBounds(tenantVersion);
 *   ...
 *   <DataBoundsBanner
 *       latest={latest}
 *       boundsLoaded={boundsLoaded}
 *       currentEnd={filters.endDate}
 *       onJumpToLatest={() => handleFilterChange({ datePreset: 'CUSTOM', startDate: bStart, endDate: bEnd })}
 *   />
 *
 * PROPS
 *   latest         : 'YYYY-MM-DD' (or Date) — newest date that has data. Required to show.
 *   boundsLoaded   : boolean — don't show until bounds resolve.
 *   currentEnd     : 'YYYY-MM-DD' — the end of the currently-selected range.
 *   onJumpToLatest : () => void — sets the page's range to the full data window.
 *   label          : optional override for the leading text.
 */

const toYmd = (v) => {
    if (!v) return '';
    if (typeof v === 'string') return v.slice(0, 10);
    try { return new Date(v).toISOString().slice(0, 10); } catch { return ''; }
};

const prettyDate = (ymd) => {
    if (!ymd) return '';
    try {
        return new Date(ymd + 'T00:00:00').toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
    } catch { return ymd; }
};

const DataBoundsBanner = ({ latest, boundsLoaded, currentEnd, onJumpToLatest, label }) => {
    const latestYmd = toYmd(latest);
    const endYmd = toYmd(currentEnd);

    // Only nudge when we KNOW there is newer data than the current view end.
    // String compare is valid for zero-padded ISO dates.
    const stale = boundsLoaded && latestYmd && endYmd && endYmd < latestYmd;
    if (!stale) return null;

    return (
        <Box
            role="status"
            sx={{
                display: 'flex', alignItems: 'center', gap: 1.25, flexWrap: 'wrap',
                px: 1.75, py: 1, mb: 1.5,
                borderRadius: 'var(--radius-md, 10px)',
                border: '1px solid var(--warning-border, #fde68a)',
                bgcolor: 'var(--warning-bg, #fffbeb)',
                color: 'var(--warning-text, #92400e)',
            }}
        >
            <CalendarClock size={15} style={{ flexShrink: 0 }} />
            <Typography sx={{ fontSize: '0.82rem', fontWeight: 600, color: 'inherit' }}>
                {label || `Newer data is available through ${prettyDate(latestYmd)}. You're viewing up to ${prettyDate(endYmd)}.`}
            </Typography>
            <Box
                onClick={onJumpToLatest}
                sx={{
                    ml: 'auto', display: 'inline-flex', alignItems: 'center', gap: 0.5,
                    px: 1.25, py: 0.5, borderRadius: 'var(--radius-sm, 6px)', cursor: 'pointer',
                    fontSize: '0.78rem', fontWeight: 700, whiteSpace: 'nowrap',
                    color: 'var(--brand, #2563eb)',
                    bgcolor: 'var(--bg-card, #ffffff)',
                    border: '1px solid var(--border, #e2e8f0)',
                    transition: 'border-color 0.15s ease, color 0.15s ease',
                    '&:hover': { borderColor: 'var(--brand, #2563eb)' },
                }}
            >
                Jump to latest <ArrowRight size={13} />
            </Box>
        </Box>
    );
};

export default DataBoundsBanner;
