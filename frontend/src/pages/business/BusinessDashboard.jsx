import React, { useState, useEffect } from 'react';
import { LayoutGrid, TrendingUp, Users, UserPlus, UserMinus, AlertCircle } from 'lucide-react';
import Loader from '../../components/Loader';
import BusinessFilters from '../../components/BusinessFilters';

const BusinessDashboard = () => {
    const [kpis, setKpis] = useState(null);
    const [loading, setLoading] = useState(true);
    const [filters, setFilters] = useState({});

    useEffect(() => {
        fetchKpis();
    }, [filters]);

    const fetchKpis = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');

            const queryParams = new URLSearchParams();
            if (filters.startDate) queryParams.append('startDate', filters.startDate);
            if (filters.endDate) queryParams.append('endDate', filters.endDate);

            const res = await fetch(`/api/business/dashboard/kpis?${queryParams.toString()}`, {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });
            if (res.ok) setKpis(await res.json());
        } catch (error) {
            console.error(error);
        } finally {
            setLoading(false);
        }
    };

    const handleFilterChange = (newFilters) => {
        setFilters(newFilters);
    };

    const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(val || 0);

    return (
        <div className="p-6 bg-slate-50 min-h-screen flex flex-col gap-6">
            <div className="flex justify-between items-center">
                <h2 className="text-2xl font-bold text-slate-900 flex items-center gap-2">
                    <LayoutGrid size={24} /> Business Dashboard
                </h2>
                <div className="flex items-center gap-4">
                    <a href="/business/insights" className="flex items-center gap-2 bg-[#0B1630] text-white px-4 py-2 rounded-lg text-sm font-bold hover:bg-[#1F3B6D] transition-colors">
                        <TrendingUp size={16} /> VIEW PREMIUM INSIGHTS
                    </a>
                    {kpis?.effectiveDate && (
                        <div className="text-sm text-slate-500">
                            Data as of: <strong>{kpis.effectiveDate}</strong>
                        </div>
                    )}
                </div>
            </div>

            <BusinessFilters onFilterChange={handleFilterChange} />

            {loading ? <Loader /> : (
                <>
                    {/* KPI Grid */}
                    {/* KPI Grid */}
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '20px' }}>
                        {/* Transaction Counts */}
                        <KpiTile title="Daily Transactions" value={kpis?.dailyCount} icon={LayoutGrid} color="blue" />
                        <KpiTile title="MTD Transactions" value={kpis?.mtdCount} icon={LayoutGrid} color="indigo" />
                        <KpiTile title="YTD Transactions" value={kpis?.ytdCount} icon={LayoutGrid} color="violet" />

                        {/* Transaction Volumes */}
                        <KpiTile title="Daily Volume" value={formatCurrency(kpis?.dailyVolume)} icon={TrendingUp} color="green" />
                        <KpiTile title="MTD Volume" value={formatCurrency(kpis?.mtdVolume)} icon={TrendingUp} color="teal" />
                        <KpiTile title="YTD Volume" value={formatCurrency(kpis?.ytdVolume)} icon={TrendingUp} color="emerald" />

                        {/* Merchant Stats */}
                        <KpiTile title="Active Merchants" value={kpis?.activeMerchants} icon={Users} color="cyan" />
                        <KpiTile title="New Merchants" value={kpis?.newMerchants} icon={UserPlus} color="lime" />
                        <KpiTile title="Dormant Merchants" value={kpis?.dormantMerchants} icon={UserMinus} color="orange" />
                        <KpiTile title="Zero Sales" value={kpis?.zeroSalesMerchants} icon={AlertCircle} color="red" />
                    </div>

                    <div style={{ padding: '20px', background: 'white', borderRadius: '12px', boxShadow: '0 1px 3px rgba(0,0,0,0.05)' }}>
                        <h3 style={{ fontSize: '1.1rem', fontWeight: 'bold', color: '#334155', marginBottom: '15px' }}>Performance Overview</h3>
                        <div style={{ color: '#64748b', fontSize: '0.9rem' }}>
                            Select "Sales Trends" from the menu for detailed charts.
                        </div>
                    </div>
                </>
            )}
        </div>
    );
};

const KpiTile = ({ title, value, icon: Icon, color }) => {
    const colors = {
        blue: { bg: '#eff6ff', text: '#1d4ed8' },
        green: { bg: '#f0fdf4', text: '#15803d' },
        indigo: { bg: '#eef2ff', text: '#4338ca' },
        teal: { bg: '#f0fdfa', text: '#0f766e' },
        orange: { bg: '#fff7ed', text: '#c2410c' },
        red: { bg: '#fef2f2', text: '#b91c1c' },
        violet: { bg: '#f5f3ff', text: '#7c3aed' },
        emerald: { bg: '#ecfdf5', text: '#059669' },
        cyan: { bg: '#ecfeff', text: '#0891b2' },
        lime: { bg: '#f7fee7', text: '#65a30d' },
    };
    const c = colors[color] || colors.blue;

    return (
        <div style={{ background: 'white', padding: '20px', borderRadius: '12px', boxShadow: '0 1px 3px rgba(0,0,0,0.05)', display: 'flex', flexDirection: 'column', gap: '10px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'start' }}>
                <div style={{ padding: '8px', borderRadius: '8px', background: c.bg, color: c.text }}>
                    <Icon size={20} />
                </div>
                {/* Could add % change here */}
            </div>
            <div>
                <div style={{ fontSize: '0.85rem', color: '#64748b', fontWeight: '500' }}>{title}</div>
                <div style={{ fontSize: '1.5rem', fontWeight: 'bold', color: '#0f172a', marginTop: '4px' }}>{value}</div>
            </div>
        </div>
    );
};

export default BusinessDashboard;
