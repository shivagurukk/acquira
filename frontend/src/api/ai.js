import api from './axios';

export const aiApi = {
    health:     ()          => api.get('/ai/health'),
    models:     ()          => api.get('/ai/models'),
    ask:        (question, model) => api.post('/ai/ask', { question, model }),
    explain:    (question, model) => api.post('/ai/explain', { question, model }),
    history:    (limit = 15) => api.get('/ai/history', { params: { limit } }),
};
