import { describe, it, expect, beforeEach, vi } from 'vitest';
import axios, { AxiosHeaders } from 'axios';
import { installResponseCache, clearResponseCache } from '../api/responseCache';

const REPORT = { 'x-acquira-cache': 'report', etag: 'W/"v1"', 'content-type': 'application/json' };

/** axios instance whose network is a scripted fake adapter. */
function setup(handler) {
    const calls = [];
    const instance = axios.create({ baseURL: '/api' });
    instance.defaults.adapter = async (config) => {
        calls.push(config);
        const { status = 200, data = '{}', headers = {} } = handler(config, calls.length);
        const res = { data, status, statusText: '', headers: new AxiosHeaders(headers), config, request: {} };
        const ok = config.validateStatus ? config.validateStatus(status) : status >= 200 && status < 300;
        if (!ok) {
            const err = new Error(`status ${status}`);
            err.response = res;
            throw err;
        }
        return res;
    };
    installResponseCache(instance);
    return { instance, calls };
}

describe('responseCache', () => {
    beforeEach(() => {
        clearResponseCache();
        localStorage.clear();
        vi.useRealTimers();
    });

    it('serves a repeat marked report read from memory', async () => {
        const { instance, calls } = setup(() => ({ data: '{"n":1}', headers: REPORT }));
        const a = await instance.post('/business/x', { f: 1 });
        const b = await instance.post('/business/x', { f: 1 });
        expect(calls).toHaveLength(1);
        expect(a.data).toEqual({ n: 1 });
        expect(b.data).toEqual({ n: 1 });
        // each caller parses its own copy
        expect(a.data).not.toBe(b.data);
    });

    it('does not cache responses the server did not mark', async () => {
        const { instance, calls } = setup(() => ({ data: '{"n":1}' }));
        await instance.get('/users');
        await instance.get('/users');
        expect(calls).toHaveLength(2);
    });

    it('keys on body and tenant', async () => {
        const { instance, calls } = setup(() => ({ data: '{}', headers: REPORT }));
        await instance.post('/business/x', { f: 1 });
        await instance.post('/business/x', { f: 2 });
        await instance.post('/business/x', { f: 1 }, { headers: { 'X-Tenant-Id': '7' } });
        expect(calls).toHaveLength(3);
    });

    it('revalidates with If-None-Match after the fresh window and reuses the body on 304', async () => {
        vi.useFakeTimers();
        const { instance, calls } = setup((config, n) =>
            n === 1 ? { data: '{"n":1}', headers: REPORT } : { status: 304, data: '' });
        await instance.get('/business/x');
        vi.advanceTimersByTime(61 * 1000);
        const res = await instance.get('/business/x');
        expect(calls).toHaveLength(2);
        expect(AxiosHeaders.from(calls[1].headers).get('If-None-Match')).toBe('W/"v1"');
        expect(res.status).toBe(200);
        expect(res.data).toEqual({ n: 1 });
    });

    it('clears everything after an unmarked write, even a failed one', async () => {
        const { instance, calls } = setup((config) => {
            if (config.url === '/save') return { status: 500, data: '{}' };
            return { data: '{}', headers: REPORT };
        });
        await instance.get('/business/x');
        await expect(instance.put('/save', {})).rejects.toThrow();
        await instance.get('/business/x');
        expect(calls.filter((c) => c.url === '/business/x')).toHaveLength(2);
    });

    it('shares one network call between identical concurrent requests', async () => {
        const { instance, calls } = setup(() => ({ data: '{"n":1}', headers: REPORT }));
        const [a, b] = await Promise.all([instance.get('/business/x'), instance.get('/business/x')]);
        expect(calls).toHaveLength(1);
        expect(a.data).toEqual({ n: 1 });
        expect(b.data).toEqual({ n: 1 });
        expect(a.data).not.toBe(b.data);
    });
});
