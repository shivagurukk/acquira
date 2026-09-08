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
 *
 * WHY A RELOAD USED TO SHOW STALE DATA
 * ------------------------------------
 * sessionStorage survives a reload — including a hard reload, which only clears
 * the HTTP cache. So an entry written before the reload was still served after
 * it, for the rest of its TTL, and no amount of Ctrl+Shift+R helped. Two rules
 * fix that without giving up the round-trip savings:
 *
 *   1. Build-keyed storage. Entries are namespaced by the hash of the loaded
 *      bundle, so a new deploy can never read entries written by the old one —
 *      which is exactly when a response's *shape* is most likely to have
 *      changed. Old-build entries are swept at module load.
 *   2. Revalidate-once-per-load. The first time a key is read in a given page
 *      load it is still served instantly from cache, but a background refetch
 *      goes out and updates the store. Callers that pass `onUpdate` are handed
 *      the fresh data as soon as it lands, so a reload — or just navigating
 *      back to the page — reflects reality instead of a five-minute-old copy.
 */

const MEM = new Map();            // key -> { at, data }
const INFLIGHT = new Map();       // key -> Promise
const REVALIDATED = new Set();    // keys already background-checked this page load
const DEFAULT_TTL = 5 * 60 * 1000; // 5 minutes
const BASE_PREFIX = 'acq_apicache:';

/**
 * Identity of the currently loaded bundle. In a production build the entry
 * script is /assets/index-<hash>.js, so this changes on every deploy; in dev
 * it is /src/main.jsx and stays 'dev'. Used to namespace stored entries so a
 * newly deployed bundle never reads a previous build's cached payloads.
 */
const BUILD_KEY = (() => {
    try {
        const el = document.querySelector('script[type="module"][src]');
        const src = (el && el.getAttribute('src')) || '';
        const hash = src.match(/-([A-Za-z0-9_-]{6,})\.js$/);
        return hash ? hash[1] : 'dev';
    } catch { return 'dev'; }
})();

const SS_PREFIX = `${BASE_PREFIX}${BUILD_KEY}:`;

const tenantId = () => localStorage.getItem('defaultTenantId') || 'none';

const keyFor = (url, params) => {
    const p = params ? JSON.stringify(params) : '';
    return `${tenantId()}|${url}|${p}`;
};

/** Every acq_apicache key currently in sessionStorage (all builds). */
const storedKeys = () => {
    const keys = [];
    try {
        for (let i = 0; i < sessionStorage.length; i++) {
            const k = sessionStorage.key(i);
            if (k && k.startsWith(BASE_PREFIX)) keys.push(k);
        }
    } catch { /* storage blocked */ }
    return keys;
};

const dropStored = (predicate) => {
    const doomed = storedKeys().filter(predicate);
    try { doomed.forEach(k => sessionStorage.removeItem(k)); }
    catch { /* ignore */ }
};

// Sweep entries left behind by a previous build of the app.
dropStored(k => !k.startsWith(SS_PREFIX));

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

/** Fetch + store, deduped by key. Resolves to the payload. */
const fetchAndStore = (key, url, params) => {
    if (INFLIGHT.has(key)) return INFLIGHT.get(key);

    const promise = api.get(url, params ? { params } : undefined)
        .then((res) => {
            const entry = { at: Date.now(), data: res.data };
            MEM.set(key, entry);
            writeSession(key, entry);
            return res.data;
        })
        .finally(() => { INFLIGHT.delete(key); });

    INFLIGHT.set(key, promise);
    return promise;
};

/**
 * Background refresh behind a cache hit. Fires at most once per key per page
 * load. Failures are swallowed on purpose — the caller already has usable data
 * and the next real miss will surface any genuine error.
 */
const revalidate = (key, url, params, onUpdate) => {
    if (REVALIDATED.has(key)) return;
    REVALIDATED.add(key);
    fetchAndStore(key, url, params)
        .then((data) => { if (onUpdate) onUpdate(data); })
        .catch(() => { /* keep serving the cached copy */ });
};

/**
 * Cached GET. Resolves to { data } like axios. Falls through to a real
 * api.get() on a miss/stale and stores the result.
 *
 * @param {object}   [opts]
 * @param {number}   [opts.ttlMs]    freshness window (default 5 min)
 * @param {object}   [opts.params]   query params, also part of the cache key
 * @param {Function} [opts.onUpdate] called with fresh data when a cache hit is
 *                                   later found to be out of date
 */
export async function cachedGet(url, { ttlMs = DEFAULT_TTL, params, onUpdate } = {}) {
    const key = keyFor(url, params);

    // 1) In-memory hit.
    const mem = MEM.get(key);
    if (isFresh(mem, ttlMs)) {
        revalidate(key, url, params, onUpdate);
        return { data: mem.data };
    }

    // 2) sessionStorage hit (rehydrate memory). This is the path taken right
    //    after a reload, so the revalidate() below is what keeps a reload
    //    honest instead of replaying a pre-reload copy.
    const ss = readSession(key);
    if (isFresh(ss, ttlMs)) {
        MEM.set(key, ss);
        revalidate(key, url, params, onUpdate);
        return { data: ss.data };
    }

    // 3) Miss or stale — fetch (sharing any in-flight request for this key).
    REVALIDATED.add(key);   // this IS the fresh read; don't background-check it too
    const data = await fetchAndStore(key, url, params);
    return { data };
}

/** Drop the whole cache (call on tenant switch / logout). */
export function invalidateApiCache() {
    MEM.clear();
    INFLIGHT.clear();
    REVALIDATED.clear();
    dropStored(() => true);
}

/** Drop a single url (any params, current tenant). */
export function invalidateApiCacheUrl(url) {
    const prefix = `${tenantId()}|${url}|`;
    [...MEM.keys()].forEach(k => { if (k.startsWith(prefix)) MEM.delete(k); });
    [...REVALIDATED].forEach(k => { if (k.startsWith(prefix)) REVALIDATED.delete(k); });
    dropStored(k => k.startsWith(SS_PREFIX + prefix));
}

export default cachedGet;
