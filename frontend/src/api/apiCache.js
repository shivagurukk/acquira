import api from './axios';

/**
 * apiCache — a tiny client-side cache for near-static GET endpoints.
 *
 * THE PROBLEM THIS SOLVES
 * -----------------------
 * A few endpoints return data that is effectively constant for the life of a
 * session within a given tenant — the filter-dropdown values
 * (/business/filter-options, /reports/filters/*) and the data-window bounds
 * (/business/data-bounds). Today every page that opens the BusinessFilters
 * drawer re-fetches the option lists, and every business report page re-fetches
 * data-bounds on mount. That is one extra round-trip per navigation, each one
 * lighting up the global progress bar and delaying first paint of real content.
 *
 * WHAT THIS DOES
 * --------------
 * cachedGet(url, { ttlMs, params }) returns the same shape api.get() resolves to
 * ({ data }) but serves a cached copy when one is fresh. The cache is:
 *   - keyed by tenant + url + params, so tenants never see each other's lists
 *     (defence-in-depth on top of the server-side tenant scoping);
 *   - TTL-bounded (default 5 min) so genuinely changed data still refreshes;
 *   - backed by sessionStorage so it survives route changes within the tab but
 *     is dropped when the tab closes (no stale data across logins);
 *   - in-flight-deduped: concurrent callers for the same key share one request.
 *
 * It deliberately does NOT touch the axios interceptors — auth, refresh, and the
 * progress bar all keep working exactly as before for every other call. A cache
 * HIT simply never reaches axios, so it also never ticks the progress bar, which
 * is the desired effect (no spinner for data we already have).
 *
 * On tenant switch, call invalidateApiCache() to drop everything.
 */

const MEM = new Map();            // key -> { at, data }
const INFLIGHT = new Map();       // key -> Promise
const DEFAULT_TTL = 5 * 60 * 1000; // 5 minutes
const SS_PREFIX = 'acq_apicache:';

const tenantId = () => localStorage.getItem('defaultTenantId') || 'none';

const keyFor = (url, params) => {
    const p = params ? JSON.stringify(params) : '';
    return `${tenantId()}|${url}|${p}`;
};

const readSession = (key) => {
    try {
        const raw = sessionStorage.getItem(SS_PREFIX + key);
        return raw ? JSON.parse(raw) : null;
    } catch { return null; }
};

const writeSession = (key, entry) => {
    try { sessionStorage.setItem(SS_PREFIX + key, JSON.stringify(entry)); }
    catch { /* quota / disabled — in-memory still works */ }
};

const isFresh = (entry, ttlMs) =>
    entry && typeof entry.at === 'number' && (Date.now() - entry.at) < ttlMs;

/**
 * Cached GET. Resolves to { data } like axios. Falls through to a real
 * api.get() on a miss/stale and stores the result.
 */
export async function cachedGet(url, { ttlMs = DEFAULT_TTL, params } = {}) {
    const key = keyFor(url, params);

    // 1) In-memory hit.
    const mem = MEM.get(key);
    if (isFresh(mem, ttlMs)) return { data: mem.data };

    // 2) sessionStorage hit (rehydrate memory).
    const ss = readSession(key);
    if (isFresh(ss, ttlMs)) {
        MEM.set(key, ss);
        return { data: ss.data };
    }

    // 3) Share an in-flight request if one is already running for this key.
    if (INFLIGHT.has(key)) {
        const data = await INFLIGHT.get(key);
        return { data };
    }

    // 4) Miss — fetch, store, dedupe.
    const promise = api.get(url, params ? { params } : undefined)
        .then((res) => {
            const entry = { at: Date.now(), data: res.data };
            MEM.set(key, entry);
            writeSession(key, entry);
            return res.data;
        })
        .finally(() => { INFLIGHT.delete(key); });

    INFLIGHT.set(key, promise);
    const data = await promise;
    return { data };
}

/** Drop the whole cache (call on tenant switch / logout). */
export function invalidateApiCache() {
    MEM.clear();
    INFLIGHT.clear();
    try {
        const toRemove = [];
        for (let i = 0; i < sessionStorage.length; i++) {
            const k = sessionStorage.key(i);
            if (k && k.startsWith(SS_PREFIX)) toRemove.push(k);
        }
        toRemove.forEach(k => sessionStorage.removeItem(k));
    } catch { /* ignore */ }
}

/** Drop a single url (any params, current tenant). */
export function invalidateApiCacheUrl(url) {
    const prefix = `${tenantId()}|${url}|`;
    [...MEM.keys()].forEach(k => { if (k.startsWith(prefix)) MEM.delete(k); });
    try {
        const toRemove = [];
        for (let i = 0; i < sessionStorage.length; i++) {
            const k = sessionStorage.key(i);
            if (k && k.startsWith(SS_PREFIX + prefix)) toRemove.push(k);
        }
        toRemove.forEach(k => sessionStorage.removeItem(k));
    } catch { /* ignore */ }
}

export default cachedGet;
