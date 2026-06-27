import api from './axios';

export const explorerApi = {
    getFields:      ()             => api.get('/analytics/explorer/fields'),
    getDistinct:    (fieldKey)     => api.get(`/analytics/explorer/distinct/${fieldKey}`),
    query:          (payload)      => api.post('/analytics/explorer/query', payload),
    queryMerchants: (payload)      => api.post('/analytics/explorer/query/merchants', payload),
    associative:    (payload)      => api.post('/analytics/explorer/associative', payload),

    // Phase 4.x — master items (governed, shareable definitions)
    listMaster:     ()             => api.get('/analytics/explorer/master-items'),
    createMaster:   (data)         => api.post('/analytics/explorer/master-items', data),
    deleteMaster:   (id)           => api.delete(`/analytics/explorer/master-items/${id}`),

    // Phase 4.x — threshold alerts
    listAlerts:     ()             => api.get('/analytics/explorer/alerts'),
    createAlert:    (data)         => api.post('/analytics/explorer/alerts', data),
    updateAlert:    (id, data)     => api.put(`/analytics/explorer/alerts/${id}`, data),
    deleteAlert:    (id)           => api.delete(`/analytics/explorer/alerts/${id}`),
    runAlert:       (id)           => api.post(`/analytics/explorer/alerts/${id}/run`),
};

export const reportApi = {
    exportExcel:    (payload)      => api.post('/reports/export/excel', payload, { responseType: 'blob' }),
    exportCsv:      (payload)      => api.post('/reports/export/csv', payload, { responseType: 'blob' }),
    getTemplates:   ()             => api.get('/reports/templates'),
    createTemplate: (data)         => api.post('/reports/templates', data),
    updateTemplate: (id, data)     => api.put(`/reports/templates/${id}`, data),
    deleteTemplate: (id)           => api.delete(`/reports/templates/${id}`),
    getSchedules:   ()             => api.get('/reports/schedules'),
    createSchedule: (tplId, data)  => api.post(`/reports/templates/${tplId}/schedule`, data),
    deleteSchedule: (id)           => api.delete(`/reports/schedules/${id}`),
};

export const savedViewsApi = {
    list:       (dashboardType) => api.get(`/filters/views/${dashboardType}`),
    create:     (data)          => api.post('/filters/views', data),
    update:     (id, data)      => api.put(`/filters/views/${id}`, data),
    remove:     (id)            => api.delete(`/filters/views/${id}`),
    setDefault: (id)            => api.put(`/filters/views/${id}/default`),
};
