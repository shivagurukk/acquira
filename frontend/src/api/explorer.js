import api from './axios';

export const explorerApi = {
    getFields:      ()             => api.get('/analytics/explorer/fields'),
    getDistinct:    (fieldKey)     => api.get(`/analytics/explorer/distinct/${fieldKey}`),
    query:          (payload)      => api.post('/analytics/explorer/query', payload),
    queryMerchants: (payload)      => api.post('/analytics/explorer/query/merchants', payload),
};

export const savedViewsApi = {
    list:       (dashboardType) => api.get(`/filters/views/${dashboardType}`),
    create:     (data)          => api.post('/filters/views', data),
    update:     (id, data)      => api.put(`/filters/views/${id}`, data),
    remove:     (id)            => api.delete(`/filters/views/${id}`),
    setDefault: (id)            => api.put(`/filters/views/${id}/default`),
};
