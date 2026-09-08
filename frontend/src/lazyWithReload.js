import { lazy } from 'react';

/**
 * lazyWithReload — React.lazy that survives a mid-session redeploy.
 *
 * THE PROBLEM
 * -----------
 * Every route is code-split, so navigating fires a dynamic import() for a
 * content-hashed chunk, e.g. /assets/MerchantReportManager-YWThirv-.js. When
 * the frontend is redeployed, Vite emits NEW hashes and deletes the old files.
 * A browser that loaded the app before the deploy still references the OLD
 * hash, so the first navigation to an as-yet-unvisited route 404s with:
 *
 *     TypeError: Failed to fetch dynamically imported module: .../<old-hash>.js
 *
 * WHAT THIS DOES
 * --------------
 * On a chunk-fetch failure we force ONE full page reload. The reload pulls the
 * fresh index.html (with the new hashes), so the retried navigation resolves
 * the current chunk and the user never sees the crash screen.
 *
 * LOOP GUARD
 * ----------
 * We stamp sessionStorage before reloading and only reload again if the last
 * attempt was more than RELOAD_COOLDOWN_MS ago. A genuinely broken build (the
 * chunk 404s even after a fresh index.html) therefore reloads at most once and
 * then falls through to the real error / ErrorBoundary instead of looping.
 * A successful load clears the stamp so a LATER deploy gets its own retry.
 */

const RELOAD_KEY = 'lazyChunkReloadAt';
const RELOAD_COOLDOWN_MS = 10000;

// Matches the browser variants: Chrome "Failed to fetch dynamically imported
// module", Firefox "error loading dynamically imported module", Safari
// "Importing a module script failed".
function isChunkLoadError(err) {
    const msg = String((err && err.message) || err || '');
    return /dynamically imported module|Importing a module script failed|error loading dynamically/i.test(msg);
}

export function lazyWithReload(factory) {
    return lazy(async () => {
        try {
            const mod = await factory();
            try { sessionStorage.removeItem(RELOAD_KEY); } catch { /* private mode */ }
            return mod;
        } catch (err) {
            if (isChunkLoadError(err)) {
                let last = 0;
                try { last = Number(sessionStorage.getItem(RELOAD_KEY) || 0); } catch { /* ignore */ }
                if (Date.now() - last > RELOAD_COOLDOWN_MS) {
                    try { sessionStorage.setItem(RELOAD_KEY, String(Date.now())); } catch { /* ignore */ }
                    window.location.reload();
                    // Hold the Suspense boundary until the reload takes over so
                    // nothing flashes the error screen in the meantime.
                    return new Promise(() => {});
                }
            }
            // Not a stale-chunk error, or we already reloaded once — let the
            // ErrorBoundary show the real failure.
            throw err;
        }
    });
}

export default lazyWithReload;
