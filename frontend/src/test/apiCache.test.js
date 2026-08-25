import { describe, it, expect, beforeEach, vi } from 'vitest';

/**
 * apiCache — the guarantee under test is that a cached entry never outlives the
 * page load that wrote it *silently*. It may still be SERVED instantly (that is
 * the whole point of the cache), but the module must go and check.
 */

const get = vi.fn();
vi.mock('../api/axios', () => ({ default: { get: (...a) => get(...a) } }));

const flush = () => new Promise((r) => setTimeout(r, 0));

/** Re-import the module with a clean in-memory state, as a reload would. */
const load = async () => {
    vi.resetModules();
    return import('../api/apiCache');
};

describe('apiCache', () => {
    beforeEach(() => {
        get.mockReset();
        sessionStorage.clear();
        localStorage.clear();
    });

    it('serves repeat reads in one page load from memory, without refetching', async () => {
        get.mockResolvedValue({ data: { options: ['a'] } });
        const { cachedGet } = await load();

        const first = await cachedGet('/business/filter-options');
        const second = await cachedGet('/business/filter-options');
        await flush();

        expect(first.data).toEqual({ options: ['a'] });
        expect(second.data).toEqual({ options: ['a'] });
        expect(get).toHaveBeenCalledTimes(1);
    });

    it('revalidates a stored entry on the next page load and pushes the fresh data', async () => {
        get.mockResolvedValue({ data: { options: ['stale'] } });
        const first = await load();
        await first.cachedGet('/business/filter-options');
        expect(get).toHaveBeenCalledTimes(1);

        // Reload: sessionStorage survives, module state does not.
        get.mockResolvedValue({ data: { options: ['fresh'] } });
        const { cachedGet } = await load();

        const onUpdate = vi.fn();
        const served = await cachedGet('/business/filter-options', { onUpdate });
        // Still instant — the cached copy is handed back with no await on the wire.
        expect(served.data).toEqual({ options: ['stale'] });

        await flush();
        expect(get).toHaveBeenCalledTimes(2);
        expect(onUpdate).toHaveBeenCalledWith({ options: ['fresh'] });

        // ...and the refreshed copy is what the next read sees.
        const after = await cachedGet('/business/filter-options');
        expect(after.data).toEqual({ options: ['fresh'] });
    });

    it('revalidates each key at most once per page load', async () => {
        get.mockResolvedValue({ data: 1 });
        const first = await load();
        await first.cachedGet('/business/data-bounds');

        const { cachedGet } = await load();
        await cachedGet('/business/data-bounds');
        await cachedGet('/business/data-bounds');
        await cachedGet('/business/data-bounds');
        await flush();

        expect(get).toHaveBeenCalledTimes(2); // 1 initial + 1 revalidation
    });

    it('keeps serving the cached copy when the revalidation fails', async () => {
        get.mockResolvedValue({ data: { options: ['stale'] } });
        const first = await load();
        await first.cachedGet('/business/filter-options');

        get.mockRejectedValue(new Error('backend down'));
        const { cachedGet } = await load();
        const served = await cachedGet('/business/filter-options');
        await flush();

        expect(served.data).toEqual({ options: ['stale'] });
    });

    it('ignores and sweeps entries written by a previous build', async () => {
        sessionStorage.setItem(
            'acq_apicache:oldbuildhash:none|/business/filter-options|',
            JSON.stringify({ at: Date.now(), data: { options: ['from-old-build'] } }),
        );
        get.mockResolvedValue({ data: { options: ['from-this-build'] } });

        const { cachedGet } = await load();
        const res = await cachedGet('/business/filter-options');

        expect(res.data).toEqual({ options: ['from-this-build'] });
        expect(sessionStorage.getItem('acq_apicache:oldbuildhash:none|/business/filter-options|')).toBeNull();
    });

    it('does not leak one tenant cache into another', async () => {
        localStorage.setItem('defaultTenantId', '1');
        get.mockResolvedValue({ data: 'tenant-1' });
        const { cachedGet } = await load();
        await cachedGet('/business/filter-options');

        localStorage.setItem('defaultTenantId', '2');
        get.mockResolvedValue({ data: 'tenant-2' });
        const res = await cachedGet('/business/filter-options');

        expect(res.data).toBe('tenant-2');
    });

    it('invalidateApiCache drops stored entries so the next read hits the wire', async () => {
        get.mockResolvedValue({ data: 'v1' });
        const { cachedGet, invalidateApiCache } = await load();
        await cachedGet('/business/filter-options');
        await flush();

        invalidateApiCache();
        get.mockResolvedValue({ data: 'v2' });
        const res = await cachedGet('/business/filter-options');

        expect(res.data).toBe('v2');
        expect(sessionStorage.length).toBeGreaterThanOrEqual(0);
    });
});
