import { useState, useEffect } from 'react';
import api from '../api/axios';

/**
 * useDataBounds — shared default-date-range resolver for business report pages.
 *
 * THE PROBLEM THIS SOLVES
 * -----------------------
 * Every business report page (Group Reports, Merchant Analytics, Debit/Prepaid
 * Metrics, etc.) needs a sensible default date range on first load. The original
 * copy-pasted logic in each page had two bugs:
 *
 *   1. It defaulted to the CURRENT calendar month. In deployments where
 *      transaction data lags real time (e.g. data runs through April but the
 *      calendar says May), the page opened on an empty range and showed
 *      "no data" even though months of data existed.
 *
 *   2. When it did use /api/business/data-bounds, it defaulted to only the
 *      LAST partial month (first-of-latest-month -> latest) instead of the
 *      full data window.
 *
 * Because the logic was copy-pasted into ~10 pages, every page had to be fixed
 * separately and could silently drift. This hook is the single correct
 * implementation — fix it here, every page benefits.
 *
 * WHAT IT RETURNS
 * ---------------
 *   { startDate, endDate, earliest, latest, boundsLoaded, error }
 *
 *   - startDate / endDate : 'YYYY-MM-DD' strings spanning the FULL data window
 *                           (earliest -> latest). Empty strings until resolved.
 *   - earliest / latest   : the raw bounds from the backend (or null).
 *   - boundsLoaded        : false until the bounds request settles. Pages should
 *                           wait for this before firing their first data fetch,
 *                           otherwise they query with empty dates.
 *   - error               : a message string if the bounds request failed
 *                           (the hook still returns a usable wide fallback range).
 *
 * USAGE
 * -----
 *   const { startDate, endDate, boundsLoaded } = useDataBounds();
 *   useEffect(() => {
 *     if (!boundsLoaded) return;
 *     setFilters(prev => ({ ...prev, datePreset: 'CUSTOM', startDate, endDate }));
 *   }, [boundsLoaded, startDate, endDate]);
 *
 * NOTE: callers that drive their filter state from these values should mark the
 * preset as 'CUSTOM', since the hook supplies an explicit range, not a preset.
 */

// Local-date formatter. toISOString() converts to UTC first, which shifts the
// date by a day for users in positive-offset timezones (e.g. IST, UTC+5:30) —
// building the string from local Y/M/D components avoids that.
const fmtLocal = (d) => {
    const yr = d.getFullYear();
    const mo = String(d.getMonth() + 1).padStart(2, '0');
    const dy = String(d.getDate()).padStart(2, '0');
    return `${yr}-${mo}-${dy}`;
};

// Wide fallback range used when /api/business/data-bounds is unavailable or
// returns nothing: all of last year through today. A wide range may over-fetch
// slightly, but it NEVER produces a misleading empty screen on first load —
// unlike defaulting to the (often empty) current calendar month.
const wideFallbackRange = () => {
    const now = new Date();
    return {
        startDate: fmtLocal(new Date(now.getFullYear() - 1, 0, 1)),
        endDate: fmtLocal(now),
    };
};

export function useDataBounds() {
    const [state, setState] = useState({
        startDate: '',
        endDate: '',
        earliest: null,
        latest: null,
        boundsLoaded: false,
        error: null,
    });

    useEffect(() => {
        let cancelled = false;

        const load = async () => {
            try {
                // api (axios instance) already attaches Authorization + X-Tenant-Id.
                const res = await api.get('/business/data-bounds');
                const b = res?.data || {};

                if (b.latest) {
                    const latestDate = new Date(b.latest);
                    // Full data window: earliest -> latest. b.earliest may be
                    // absent on older backends; fall back to first-of-latest-month.
                    const startDate = b.earliest
                        ? fmtLocal(new Date(b.earliest))
                        : fmtLocal(new Date(latestDate.getFullYear(), latestDate.getMonth(), 1));
                    const endDate = fmtLocal(latestDate);

                    if (!cancelled) {
                        setState({
                            startDate,
                            endDate,
                            earliest: b.earliest || startDate,
                            latest: b.latest,
                            boundsLoaded: true,
                            error: b.error || null,
                        });
                    }
                    return;
                }

                // Backend reachable but reported no data — use the wide fallback.
                if (!cancelled) {
                    const range = wideFallbackRange();
                    setState({
                        ...range,
                        earliest: null,
                        latest: null,
                        boundsLoaded: true,
                        error: b.error || null,
                    });
                }
            } catch (e) {
                // Bounds request failed entirely — still hand back a usable wide
                // range so the page renders data instead of an empty screen.
                if (!cancelled) {
                    const range = wideFallbackRange();
                    setState({
                        ...range,
                        earliest: null,
                        latest: null,
                        boundsLoaded: true,
                        error: e?.message || 'Failed to load data bounds',
                    });
                }
            }
        };

        load();
        return () => { cancelled = true; };
    }, []);

    return state;
}

export default useDataBounds;
