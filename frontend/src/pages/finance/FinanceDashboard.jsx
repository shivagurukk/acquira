import React, { useState, useEffect } from 'react';
import axios from '../../api/axios';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer, BarChart, Bar } from 'recharts';
import { DollarSign, TrendingUp, Percent, CreditCard, Activity } from 'lucide-react';
import BusinessFilterBar from '../../components/BusinessFilterBar';

const FinanceDashboard = () => {
    const [kpis, setKpis] = useState(null);
    const [trends, setTrends] = useState([]);
    const [loading, setLoading] = useState(true);
    const [filters, setFilters] = useState({});

    // TODO: Context for Tenant
    const tenantId = localStorage.getItem('tenantId') || 1;

    useEffect(() => {
        fetchData();
    }, [filters, tenantId]);

    const fetchData = async () => {
        setLoading(true);
        try {
            const queryParams = new URLSearchParams();
            if (filters.startDate) queryParams.append('from', filters.startDate);
            if (filters.endDate) queryParams.append('to', filters.endDate);
            // Default to MTD if no dates provided (handled by backend or here)

            const [kpiRes, trendRes] = await Promise.all([
                axios.get(`/api/finance/dashboard/kpis?${queryParams.toString()}`, { headers: { 'X-Tenant-Id': tenantId } }),
                // Trend mode logic: if range > 32 days, maybe YTD mode? For now default MTD or let backend decide
                axios.get(`/api/finance/dashboard/trends/MTD?${queryParams.toString()}`, { headers: { 'X-Tenant-Id': tenantId } })
            ]);
            setKpis(kpiRes.data);
            setTrends(trendRes.data);
        } catch (error) {
            console.error("Error fetching finance dashboard data", error);
        } finally {
            setLoading(false);
        }
    };

    const handleFilterChange = (newFilters) => {
        setFilters(newFilters);
    };

    if (loading && !kpis) return <div className="p-8 text-center text-gray-500">Loading Finance Data...</div>;

    const KPITile = ({ title, value, icon: Icon, subtext, color = "blue" }) => (
        <div className={`bg-white p-6 rounded-xl shadow-sm border-l-4 border-${color}-500 flex items-center justify-between`}>
            <div>
                <p className="text-sm font-semibold text-gray-500 uppercase tracking-wider">{title}</p>
                <h3 className="text-2xl font-bold mt-1 text-gray-900">{value}</h3>
                {subtext && <p className={`text-xs mt-1 ${subtext.includes('-') ? 'text-red-500' : 'text-green-500'}`}>{subtext}</p>}
            </div>
            <div className={`p-3 bg-${color}-50 rounded-full text-${color}-600`}>
                <Icon size={24} />
            </div>
        </div>
    );

    const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(val || 0);

    return (
        <div className="p-8 bg-gray-50 min-h-screen font-sans">
            <div className="flex justify-between items-center mb-8">
                <div>
                    <h1 className="text-3xl font-bold text-gray-900">Finance Dashboard</h1>
                    <p className="text-gray-500 mt-1">Financial performance overview and profitability metrics</p>
                </div>
            </div>

            <BusinessFilterBar onFilterChange={handleFilterChange} />

            {/* KPI Grid */}
            {/* KPI Grid */}
            <div className="mb-8">
                <h3 className="text-lg font-bold text-gray-800 mb-4">Net Revenue Performance</h3>
                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    <KPITile title="Daily Net Revenue" value={formatCurrency(kpis?.dailyNetRevenue)} icon={TrendingUp} color="blue" subtext="Today" />
                    <KPITile title="MTD Net Revenue" value={formatCurrency(kpis?.mtdNetRevenue)} icon={TrendingUp} color="indigo" subtext="Month to Date" />
                    <KPITile title="YTD Net Revenue" value={formatCurrency(kpis?.ytdNetRevenue)} icon={TrendingUp} color="purple" subtext="Year to Date" />
                </div>
            </div>

            <div className="mb-8">
                <h3 className="text-lg font-bold text-gray-800 mb-4">Volume Performance</h3>
                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    <KPITile title="Daily Volume" value={formatCurrency(kpis?.dailyVolume)} icon={Activity} color="green" subtext="Today" />
                    <KPITile title="MTD Volume" value={formatCurrency(kpis?.mtdVolume)} icon={Activity} color="teal" subtext="Month to Date" />
                    <KPITile title="YTD Volume" value={formatCurrency(kpis?.ytdVolume)} icon={Activity} color="emerald" subtext="Year to Date" />
                </div>
            </div>

            <h3 className="text-lg font-bold text-gray-800 mb-4">Cost Analysis ({dateMode})</h3>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-8">
                <KPITile
                    title="MSF Revenue"
                    value={formatCurrency(kpis?.msfRevenue)}
                    icon={DollarSign}
                    color="blue"
                    subtext="Gross Fees"
                />
                <KPITile
                    title="Interchange Costs"
                    value={formatCurrency(kpis?.interchangeFees)}
                    icon={CreditCard}
                    color="orange"
                    subtext="Network Interchange"
                />
                <KPITile
                    title="Scheme Fees"
                    value={formatCurrency(kpis?.schemeFees)}
                    icon={Activity}
                    color="red"
                    subtext="Card Scheme Fees"
                />
                <KPITile
                    title="Margin %"
                    value={`${kpis?.marginPct || 0}%`}
                    icon={Percent}
                    color="yellow"
                    subtext="Net / Volume"
                />
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 mb-8">
                {/* Revenue Trend Chart */}
                <div className="bg-white p-6 rounded-xl shadow-sm border border-gray-100">
                    <h3 className="text-lg font-bold text-gray-800 mb-4">Revenue Trends ({dateMode})</h3>
                    <div className="h-80">
                        <ResponsiveContainer width="100%" height="100%">
                            <LineChart data={trends}>
                                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                                <XAxis dataKey="key" stroke="#94a3b8" tick={{ fontSize: 12 }} tickFormatter={(val) => val.slice(-2)} /> {/* Simple tick format */}
                                <YAxis stroke="#94a3b8" tick={{ fontSize: 12 }} tickFormatter={(val) => `$${val / 1000}k`} />
                                <Tooltip
                                    contentStyle={{ borderRadius: '8px', border: 'none', boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)' }}
                                    formatter={(val) => formatCurrency(val)}
                                />
                                <Legend />
                                <Line type="monotone" dataKey="netRevenue" stroke="#10b981" strokeWidth={3} dot={{ r: 4 }} name="Net Revenue" />
                                <Line type="monotone" dataKey="msf" stroke="#3b82f6" strokeWidth={2} dot={false} name="MSF Rev" />
                                <Line type="monotone" dataKey="interchange" stroke="#f97316" strokeWidth={2} dot={false} name="Interchange" />
                            </LineChart>
                        </ResponsiveContainer>
                    </div>
                </div>

                {/* Margin Trend Chart */}
                <div className="bg-white p-6 rounded-xl shadow-sm border border-gray-100">
                    <h3 className="text-lg font-bold text-gray-800 mb-4">Margin % Trend ({dateMode})</h3>
                    <div className="h-80">
                        <ResponsiveContainer width="100%" height="100%">
                            <LineChart data={trends}>
                                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f1f5f9" />
                                <XAxis dataKey="key" stroke="#94a3b8" tick={{ fontSize: 12 }} tickFormatter={(val) => val.slice(-2)} />
                                <YAxis stroke="#94a3b8" tick={{ fontSize: 12 }} domain={[0, 'auto']} tickFormatter={(val) => `${val}%`} />
                                <Tooltip
                                    contentStyle={{ borderRadius: '8px', border: 'none', boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)' }}
                                    formatter={(val) => `${val}%`}
                                />
                                <Legend />
                                <Line type="step" dataKey="marginPct" stroke="#8b5cf6" strokeWidth={3} dot={{ r: 4 }} name="Margin %" />
                            </LineChart>
                        </ResponsiveContainer>
                    </div>
                </div>
            </div>

            {/* Quick Stats Row (Scheme Fees & VAT) */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                <div className="bg-white p-6 rounded-xl shadow-sm border border-gray-100 flex justify-between items-center">
                    <div>
                        <p className="text-sm font-semibold text-gray-500">Scheme Fees (Est.)</p>
                        <h3 className="text-xl font-bold mt-1 text-gray-800">{formatCurrency(kpis?.schemeFees)}</h3>
                    </div>
                    <div className="p-2 bg-gray-100 rounded-lg text-gray-500">
                        <Activity size={20} />
                    </div>
                </div>
                <div className="bg-white p-6 rounded-xl shadow-sm border border-gray-100 flex justify-between items-center">
                    <div>
                        <p className="text-sm font-semibold text-gray-500">VAT Collected</p>
                        <h3 className="text-xl font-bold mt-1 text-gray-800">{formatCurrency(kpis?.vat)}</h3>
                    </div>
                    <div className="p-2 bg-gray-100 rounded-lg text-gray-500">
                        <Activity size={20} />
                    </div>
                </div>
            </div>

        </div>
    );
};

export default FinanceDashboard;
