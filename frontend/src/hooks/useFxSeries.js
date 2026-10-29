import { useEffect, useState } from 'react';
import api from '../api/axios';
import { getDisplayMode, setFxSeries, restoreDefaultFxSeries } from '../utils/formatters';
import { USD_PER_UNIT } from '../config/fxRates';
import { useAuth } from '../contexts/AuthContext';

/**
 * Load the tenant currency's DAILY USD rates for the date range a page is
 * showing, and hand them to the shared formatters (utils/formatters.js) so
 * every money figure on the page converts at the rate of its own dates.
 *
 * Only does anything when the executive USD toggle is on AND the currency
 * floats (EGP). Pegged Gulf currencies convert at the constants in
 * config/fxRates.js and never hit the network; USD tenants have no toggle.
 *
 * `range` is { from: 'YYYY-MM-DD', to: 'YYYY-MM-DD' } or null while the page
 * does not yet know its dates. On unmount the Layout's default (trailing
 * window) series is restored so the next page never formats with this one.
 *
 * Returns the series payload (or null) purely so the caller re-renders once
 * the rates arrive — the conversion itself happens inside the formatters.
 */
export default function useFxSeries(range) {
    const { currencyCode, tenantVersion } = useAuth();
    const [series, setSeries] = useState(null);
    const from = range?.from || null;
    const to = range?.to || null;

    useEffect(() => {
        const floating = currencyCode && currencyCode !== 'USD' && !USD_PER_UNIT[currencyCode];
        if (getDisplayMode() !== 'USD' || !from || !to || !floating) {
            setSeries(null);
            return undefined;
        }
        let cancelled = false;
        api.get('/fx/series', { params: { from, to } })
            .then(res => {
                if (cancelled) return;
                if (res.status === 204 || !res.data || !res.data.base) { setSeries(null); return; }
                setFxSeries(res.data);
                setSeries(res.data);
            })
            .catch(() => { if (!cancelled) setSeries(null); });
        return () => { cancelled = true; restoreDefaultFxSeries(); };
    }, [from, to, currencyCode, tenantVersion]);

    return series;
}
