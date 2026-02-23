import axios from 'axios';

const api = axios.create({
    baseURL: '/api',
    withCredentials: true, // #12: Send HttpOnly cookies with requests (for refresh token)
});

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
    (response) => response,
    async (error) => {
        const originalRequest = error.config;

        // If 401 and not already retrying, attempt refresh
        if (error.response && error.response.status === 401 && !originalRequest._retry) {
            const refreshToken = localStorage.getItem('refreshToken');

            // If no refresh token or this IS the refresh request, logout
            if (!refreshToken || originalRequest.url === '/auth/refresh') {
                localStorage.clear();
                window.location.href = '/login';
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
                const res = await axios.post('/api/auth/refresh', { refreshToken }, { withCredentials: true });
                const { jwt, refreshToken: newRefresh } = res.data;

                localStorage.setItem('token', jwt);
                localStorage.setItem('refreshToken', newRefresh);

                api.defaults.headers.common.Authorization = `Bearer ${jwt}`;
                originalRequest.headers.Authorization = `Bearer ${jwt}`;

                processQueue(null, jwt);
                return api(originalRequest);
            } catch (refreshError) {
                processQueue(refreshError, null);
                localStorage.clear();
                window.location.href = '/login';
                return Promise.reject(refreshError);
            } finally {
                isRefreshing = false;
            }
        }

        // 403 — forbidden (not token expiry)
        if (error.response && error.response.status === 403) {
            // Don't logout on 403, just let the UI handle it
            console.warn('Access denied:', error.response.config.url);
        }

        return Promise.reject(error);
    }
);

export default api;
