import axios from 'axios';
import { clearAuthStorage } from '../utils/authStorage';
import { showToast } from '../contexts/ToastContext';
import { startLoading, stopLoading } from '../contexts/LoadingContext';

const api = axios.create({
    baseURL: '/api',
    withCredentials: true, // #12: Send HttpOnly cookies with requests (for refresh token)
    // A hung request must eventually settle — callers rely on finally{} to
    // re-enable buttons (tenant switch, uploads). Long-running calls can
    // override per-request.
    timeout: 60000,
});

/**
 * Timeout for multipart uploads, which must cover the whole file transfer PLUS
 * whatever the server does synchronously before it hands back a jobId.
 *
 * The 60s default is far too short for that: axios aborts client-side while the
 * server carries on ingesting, so the user sees a failure for an upload that
 * actually succeeded, re-uploads, and the file lands twice. Still finite rather
 * than 0 (no limit), because a genuinely hung request must eventually settle or
 * the page's buttons never come back.
 *
 * Deliberately ABOVE nginx's proxy_read_timeout (600s in every deployed conf):
 * when something really does stall, the proxy's 504 should reach us as a real
 * HTTP error rather than the client aborting first and leaving us guessing.
 */
export const UPLOAD_TIMEOUT = 15 * 60 * 1000;

/**
 * True when axios gave up on its own — the request was aborted client-side and
 * the server was never heard from. It says nothing about what the server did,
 * which is exactly why callers must not report it as "upload failed".
 */
export const isTimeoutError = (err) =>
    err?.code === 'ECONNABORTED' || err?.code === 'ETIMEDOUT';

/**
 * Timeout for the token-refresh call.
 *
 * This one is load-bearing for the whole app, not just one screen. The refresh
 * runs on the raw `axios` (it must not re-enter this instance's interceptor and
 * recurse), and raw axios defaults to timeout: 0 — NO timeout at all. When the
 * backend accepted the connection but never answered (pod restarting, DB pool
 * exhausted, nginx upstream hung), that promise never settled, so `isRefreshing`
 * stayed true forever and every request queued behind it hung with it — leaving
 * ProtectedRoute stuck on "Validating Session..." indefinitely, past its own
 * 60s timeout, with no way out but a manual reload. Verified 2026-09-04 against
 * a stub that 401s /auth/session then never answers /auth/refresh.
 *
 * Short on purpose: a refresh that has not answered in 15s is not going to.
 */
export const REFRESH_TIMEOUT = 15000;

/**
 * True when the server was never reached, or answered as a dead gateway — as
 * opposed to answering with a real application error. Callers use it to tell
 * "backend is down" apart from "backend says no", which must not look the same
 * to the user: a failed fetch rendered as an empty table is indistinguishable
 * from a genuine zero.
 */
export const isBackendUnreachable = (err) => {
    if (!err) return false;
    if (isTimeoutError(err)) return true;
    if (!err.response) return true;              // network error / DNS / refused
    return [502, 503, 504].includes(err.response.status);
};

// Refresh token cache. The primary mechanism is the HttpOnly cookie set
// by the backend; this localStorage copy is a backward-compat fallback
// used when the cookie is unavailable (e.g. plain-HTTP dev, where the
// Secure cookie is not sent). It is therefore NOT XSS-safe — the cookie is.
let _memRefreshToken = localStorage.getItem('refreshToken') || null;

// Request interceptor — attach JWT and tenant header
api.interceptors.request.use((config) => {
    const token = localStorage.getItem('token');
    const tenantId = localStorage.getItem('defaultTenantId');

    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    if (tenantId && tenantId !== 'null' && tenantId !== 'undefined') {
        config.headers['X-Tenant-Id'] = tenantId;
    }
    // Global top progress bar — one tick per outgoing request.
    startLoading();
    return config;
}, (error) => {
    return Promise.reject(error);
});

// Response interceptor — auto-refresh on 401
let isRefreshing = false;
let failedQueue = [];

const processQueue = (error, token = null) => {
    failedQueue.forEach(prom => {
        if (error) {
            prom.reject(error);
        } else {
            prom.resolve(token);
        }
    });
    failedQueue = [];
};

api.interceptors.response.use(
    (response) => {
        // Request settled — release one tick of the progress bar.
        stopLoading();
        return response;
    },
    async (error) => {
        // Release the tick for THIS request up front. Any auto-refresh
        // retry below re-enters the request interceptor and starts its own.
        stopLoading();
        const originalRequest = error.config;

        // If 401 and not already retrying, attempt refresh
        if (error.response && error.response.status === 401 && !originalRequest._retry) {
            let refreshToken = localStorage.getItem('refreshToken') || _memRefreshToken;

            // If no refresh token or this IS the refresh request, logout
            if (!refreshToken || originalRequest.url === '/auth/refresh') {
                clearAuthStorage();
                showToast('Your session has expired. Please log in again.', 'warning', 5000);
                setTimeout(() => { window.location.href = '/login'; }, 1200);
                return Promise.reject(error);
            }

            if (isRefreshing) {
                // Queue requests while refreshing
                return new Promise((resolve, reject) => {
                    failedQueue.push({ resolve, reject });
                }).then(token => {
                    originalRequest.headers.Authorization = `Bearer ${token}`;
                    return api(originalRequest);
                }).catch(err => Promise.reject(err));
            }

            originalRequest._retry = true;
            isRefreshing = true;

            try {
                // #12: Cookie is sent automatically; body is fallback for backward compat
                const res = await axios.post('/api/auth/refresh', { refreshToken },
                    { withCredentials: true, timeout: REFRESH_TIMEOUT });
                const { jwt, refreshToken: newRefresh } = res.data;

                localStorage.setItem('token', jwt);
                // Fallback copy; HttpOnly cookie remains the primary store.
                _memRefreshToken = newRefresh;
                localStorage.setItem('refreshToken', newRefresh);

                api.defaults.headers.common.Authorization = `Bearer ${jwt}`;
                originalRequest.headers.Authorization = `Bearer ${jwt}`;

                processQueue(null, jwt);
                return api(originalRequest);
            } catch (refreshError) {
                processQueue(refreshError, null);
                // A refresh that could not REACH the server says nothing about
                // whether the session is still good. Logging out here would
                // destroy a valid session over a transient outage — and send the
                // user to a login screen that cannot authenticate them either,
                // because the same backend is down. Keep the credentials, report
                // the outage, and let the caller offer a retry.
                if (isBackendUnreachable(refreshError)) {
                    showToast('Cannot reach the server. Check your connection and retry.', 'error', 6000);
                    return Promise.reject(refreshError);
                }
                clearAuthStorage();
                showToast('Your session has expired. Please log in again.', 'warning', 5000);
                setTimeout(() => { window.location.href = '/login'; }, 1200);
                return Promise.reject(refreshError);
            } finally {
                isRefreshing = false;
            }
        }

        // 403 — forbidden (not token expiry)
        if (error.response && error.response.status === 403) {
            // Server-side forced-password-change gate: re-arm the flag and send
            // the user to the change-password screen (covers the case where the
            // client-side flag was lost or tampered with).
            if (error.response.data?.code === 'PASSWORD_CHANGE_REQUIRED') {
                localStorage.setItem('mustChangePassword', 'true');
                if (window.location.pathname !== '/change-password') {
                    showToast('You must change your password before continuing.', 'warning', 5000);
                    setTimeout(() => { window.location.href = '/change-password'; }, 800);
                }
            } else {
                // Don't logout on 403, just let the UI handle it
                console.warn('Access denied:', error.response.config.url);
            }
        }

        return Promise.reject(error);
    }
);

export default api;
