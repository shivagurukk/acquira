import React, { useState, useEffect } from 'react';
import { TrendingUp } from 'lucide-react';
import Loader from '../../components/Loader';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import BusinessFilters from '../../components/BusinessFilters';

const SalesTrends = () => {
    const [data, setData] = useState([]);
    const [loading, setLoading] = useState(true);
    const [mode, setMode] = useState('daily'); // daily | monthly
    const [filters, setFilters] = useState({});

    useEffect(() => {
        fetchTrends();
    }, [mode, filters]);

    const fetchTrends = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');

            const queryParams = new URLSearchParams();
            if (filters.startDate) queryParams.append('startDate', filters.startDate);
            if (filters.endDate) queryParams.append('endDate', filters.endDate);

            const res = await fetch(`/api/business/dashboard/trends/${mode}?${queryParams.toString()}`, {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });
            if (res.ok) setData(await res.json());
        } catch (error) {
            console.error(error);
        } finally {
            setLoading(false);
        }
    };

    const handleFilterChange = (newFilters) => {
        setFilters(newFilters);
    };

    return (
        <div className="p-6 bg-slate-50 min-h-screen flex flex-col gap-6">
            <div className="flex justify-between items-center">
                <h2 className="text-2xl font-bold text-slate-900 flex items-center gap-2">
                    <TrendingUp size={24} /> Sales Trends
                </h2>
                <div className="bg-white rounded-lg p-1 shadow-sm border border-slate-200 flex">
                    <button
                        onClick={() => setMode('daily')}
                        className={`px-4 py-1.5 rounded-md text-sm font-medium transition-all ${mode === 'daily' ? 'bg-slate-100 text-slate-900' : 'text-slate-500 hover:text-slate-700'}`}
                    >
                        Daily
                    </button>
                    <button
                        onClick={() => setMode('monthly')}
                        className={`px-4 py-1.5 rounded-md text-sm font-medium transition-all ${mode === 'monthly' ? 'bg-slate-100 text-slate-900' : 'text-slate-500 hover:text-slate-700'}`}
                    >
                        Monthly
                    </button>
                </div>
            </div>

            <BusinessFilters onFilterChange={handleFilterChange} />

            <div style={{ background: 'white', padding: '24px', borderRadius: '12px', boxShadow: '0 1px 3px rgba(0,0,0,0.05)', height: '400px' }}>
                {loading ? <Loader /> : (
                    <ResponsiveContainer width="100%" height="100%">
                        <LineChart data={data}>
                            <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e2e8f0" />
                            <XAxis dataKey="date" tick={{ fontSize: 12 }} stroke="#94a3b8" />
                            <YAxis tick={{ fontSize: 12 }} stroke="#94a3b8" />
                            <Tooltip />
                            <Line type="monotone" dataKey="value" stroke="#3b82f6" strokeWidth={3} dot={{ r: 4 }} activeDot={{ r: 6 }} name="Volume" />
                            <Line type="monotone" dataKey="count" stroke="#10b981" strokeWidth={3} dot={{ r: 4 }} name="Count" />
                        </LineChart>
                    </ResponsiveContainer>
                )}
            </div>
        </div>
    );
};

const tabStyle = (active) => ({
    padding: '8px 16px', borderRadius: '6px', border: 'none', background: active ? '#f1f5f9' : 'transparent',
    color: active ? '#0f172a' : '#64748b', fontWeight: '600', cursor: 'pointer', transition: 'all 0.2s'
});

export default SalesTrends;
