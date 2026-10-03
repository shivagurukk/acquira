import axios, { AxiosHeaders } from 'axios';

/**
 * In-memory cache for report responses, installed as an axios adapter on the
 * shared `api` instance.
 *
 * Only responses the server marks with `X-Acquira-Cache: report` (handlers
 * annotated @ReportResponse — side-effect-free report reads) are kept, so the
 * server decides what is cacheable and nothing here needs a path list.
 *
 *  - Within FRESH_MS of being fetched, a repeat of the same request (method,
 *    URL + params, body, tenant, user) is answered from memory with no network
 *    call — navigating back to a page is instant.
 *  - After that the request goes out with If-None-Match; while the data is
 *    unchanged the server answers a bodiless 304 and the stored copy is reused,
 *    so a multi-MB payload is not downloaded again.
 *  - Identical requests in flight at the same time share one network call.
 *
 * Freshness: any non-GET request that is NOT a marked report read (a save,
 * delete, upload, ...) clears the whole cache, whether it succeeded or not —
 * a failed write may still have changed data. Logout clears it too
 * (clearAuthStorage). Server-side ingests are picked up at the latest after
 * FRESH_MS, when the ETag stops matching.
 *
 * The raw response text is stored and handed back to axios before
 * transformResponse runs, so every caller parses its own copy — one page
 * mutating its data can never corrupt another page's.
 */

const FRESH_MS = 60 * 1000;
const MAX_ENTRIES = 100;
/** Budget for stored response text, in UTF-16 chars (~2 bytes each). */
const MAX_CHARS = 30 * 1000 * 1000;

const entries = new Map(); // key -> { etag, data, headers, status, statusText, at, size }
const inflight = new Map(); // key -> Promise<response>
let totalChars = 0;

export function clearResponseCache() {
    entries.clear();
    totalChars = 0;
}

function headerOf(headers, name) {
    if (!headers) return undefined;
    if (typeof headers.get === 'function') return headers.get(name) ?? undefined;
    return headers[name] ?? headers[name.toLowerCase()];
}

function remove(key) {
    const e = entries.get(key);
    if (e) {
        totalChars -= e.size;
        entries.delete(key);
    }
}

function store(key, res) {
    const size = typeof res.data === 'string' ? res.data.length : 0;
    if (!size || size > MAX_CHARS) return;
    remove(key);
    entries.set(key, {
        etag: headerOf(res.headers, 'etag'),
        data: res.data,
        headers: res.headers,
        status: res.status,
        statusText: res.statusText,
        at: Date.now(),
        size,
    });
    totalChars += size;
    // Map iteration order is insertion order: the first key is the least
    // recently stored/used one.
    while (entries.size > MAX_ENTRIES || totalChars > MAX_CHARS) {
        remove(entries.keys().next().value);
    }
}

/** Re-insert so the entry counts as most recently used. */
function touch(key, entry) {
    entries.delete(key);
    entries.set(key, entry);
}

function toResponse(entry, config) {
    return {
        data: entry.data,
        status: entry.status,
        statusText: entry.statusText,
        headers: entry.headers,
        config,
        request: null,
        fromCache: true,
    };
}

function keyOf(instance, config) {
    const headers = AxiosHeaders.from(config.headers);
    return [
        config.method.toUpperCase(),
        instance.getUri(config),
        typeof config.data === 'string' ? config.data : '',
        headers.get('X-Tenant-Id') || '',
        localStorage.getItem('username') || '',
    ].join('\u0000');
}

function isCacheable(config) {
    const method = (config.method || 'get').toLowerCase();
    if (method !== 'get' && method !== 'post') return false;
    if (config.noCache) return false;
    if (config.responseType && config.responseType !== 'json') return false;
    // Only plain JSON/empty bodies can be part of a key (no uploads).
    return config.data === undefined || config.data === null || typeof config.data === 'string';
}

export function installResponseCache(instance) {
    const baseAdapter = axios.getAdapter(instance.defaults.adapter);

    instance.defaults.adapter = async (config) => {
        const method = (config.method || 'get').toLowerCase();

        if (!isCacheable(config)) {
            if (method === 'get') return baseAdapter(config);
            try {
                return await baseAdapter(config);
            } finally {
                clearResponseCache();
            }
        }

        const key = keyOf(instance, config);
        const hit = entries.get(key);
        if (hit && Date.now() - hit.at < FRESH_MS) {
            touch(key, hit);
            return toResponse(hit, config);
        }

        const pending = inflight.get(key);
        if (pending) return { ...(await pending), config };

        const run = (async () => {
            let outgoing = config;
            if (hit?.etag) {
                const headers = AxiosHeaders.from(config.headers);
                headers.set('If-None-Match', hit.etag);
                const validate = config.validateStatus || ((s) => s >= 200 && s < 300);
                outgoing = { ...config, headers, validateStatus: (s) => s === 304 || validate(s) };
            }

            let res;
            try {
                res = await baseAdapter(outgoing);
            } catch (err) {
                // A non-GET that failed may still have written; it isn't a
                // marked read we know to be harmless.
                if (method !== 'get') clearResponseCache();
                throw err;
            }

            if (res.status === 304 && hit) {
                hit.at = Date.now();
                touch(key, hit);
                return toResponse(hit, config);
            }
            if (headerOf(res.headers, 'x-acquira-cache') === 'report' && headerOf(res.headers, 'etag')) {
                store(key, res);
            } else {
                remove(key);
                // An unmarked POST may be a write — drop everything.
                if (method !== 'get') clearResponseCache();
            }
            return res;
        })();

        inflight.set(key, run);
        try {
            // Every caller gets its own response object: axios writes the
            // parsed body back onto it, which must not leak between callers.
            return { ...(await run), config };
        } finally {
            inflight.delete(key);
        }
    };
}
