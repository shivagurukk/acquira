import axios from './axios';

export const merchantApi = {
    search: async (query) => {
        const params = new URLSearchParams({ page: 0, size: 50 });
        if (query) params.append('search', query);
        // axios interceptor already attaches Authorization and X-Tenant-Id headers
        const res = await axios.get(`/merchants/hierarchy?${params.toString()}`);
        // hierarchy returns { content: [...] } — each item has merchantId, name, mid, status
        return (res.data.content || []).map(m => ({
            merchantId: m.merchantId,
            name: m.name,
            mid: m.mid || '',
            status: m.status || 'ACTIVE',
            city: m.stores?.[0]?.city || ''
        }));
    },

    compare: async (merchantIds, startDate, endDate) => {
        const body = { merchantIds, startDate, endDate };
        const res = await axios.post('/merchants/compare', body);
        return res.data;
    }
};
