import React, { useState, useEffect } from 'react';
import {
    BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
    PieChart, Pie, Cell
} from 'recharts';
import { Calendar, Layers, RefreshCw, Filter } from 'lucide-react';
import ReportHeader from '../../components/ReportHeader';

const COLORS = ['#0088FE', '#00C49F', '#FFBB28', '#FF8042', '#8884d8', '#82ca9d'];

const ExecutiveDashboardReport = () => {
    const [loading, setLoading] = useState(false);
    const [asOfDate, setAsOfDate] = useState(new Date().toISOString().split('T')[0]);
    const [dataset, setDataset] = useState('SID_Data_2026');
    const [availableDatasets, setAvailableDatasets] = useState([]);
    const [showFilters, setShowFilters] = useState(true);

    const [data, setData] = useState({
        kpis: {
            ytdSid: 0, ytdMid: 0, mtdSid: 0, wtdSid: 0, mtdMsfUsd: 0
        },
        charts: {
            ytdByAgent: [],
            ytdByProgram: [],
            mtdVolumeSplit: [],
            mtdSidByProgram: []
        }
    });

    useEffect(() => {
        // Fetch Datasets
        fetch('/api/dashboard/v2/datasets')
            .then(res => res.json())
            .then(sets => {
                setAvailableDatasets(sets);
                if (sets.length > 0 && !dataset) setDataset(sets[0]);
            })
            .catch(err => console.error(err));
    }, []);

    useEffect(() => {
        fetchDashboardData();
    }, [asOfDate, dataset]);

    const fetchDashboardData = async () => {
        if (!dataset) return;
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');
            const res = await fetch(`/api/dashboard/v2/data?dataset=${dataset}&asOfDate=${asOfDate}`, {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });
            if (res.ok) {
                const result = await res.json();
                setData(result);
            }
        } catch (error) {
            console.error("Failed to fetch dashboard data", error);
        } finally {
            setLoading(false);
        }
    };

    const formatCurrency = (val) => {
        return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(val);
    };

    return (
        <div className="flex-1 p-6 bg-slate-50 min-h-screen overflow-y-auto">
            {/* Header */}
            <ReportHeader
                title="Executive Dashboard 2.0"
                subtitle="SID Acquisition & Performance Report"
                // No CSV export for dashboard charts currently, or could implement later
                onRunReport={fetchDashboardData}
                filters={{ hideDatePresets: true }}
                onFilterChange={() => {}}
                showFilters={showFilters}
                onToggleFilters={() => setShowFilters(!showFilters)}
                loading={loading}
            />

            {/* Filter Panel */}
            {showFilters && (
                <div className="bg-white p-4 rounded-xl border border-slate-200 shadow-sm mb-6 flex flex-wrap items-end gap-6">
                    {/* Dataset Selector */}
                    <div className="flex flex-col gap-1">
                        <label className="text-xs font-bold text-slate-500 flex items-center gap-1">
                            <Layers size={12} /> Data Sheet
                        </label>
                        <select
                            className="px-3 py-2 border border-slate-200 rounded-lg text-sm bg-slate-50 min-w-[200px] focus:outline-none focus:border-blue-500"
                            value={dataset}
                            onChange={e => setDataset(e.target.value)}
                        >
                            {availableDatasets.map(ds => (
                                <option key={ds} value={ds}>{ds}</option>
                            ))}
                        </select>
                    </div>

                    {/* As Of Date Selector */}
                    <div className="flex flex-col gap-1">
                        <label className="text-xs font-bold text-slate-500 flex items-center gap-1">
                            <Calendar size={12} /> As of Date
                        </label>
                        <input
                            type="date"
                            className="px-3 py-2 border border-slate-200 rounded-lg text-sm focus:outline-none focus:border-blue-500"
                            value={asOfDate}
                            onChange={e => setAsOfDate(e.target.value)}
                        />
                    </div>
                </div>
            )}

            {/* Charts Grid (2x2) */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mb-6">

                {/* Chart 1: Number of SID YTD by Introducing Agent */}
                <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm h-[400px]">
                    <h3 className="text-sm font-bold text-slate-700 mb-4 border-b pb-2">Number of SID YTD by Introducing Agent</h3>
                    <ResponsiveContainer width="100%" height="100%">
                        <BarChart
                            layout="vertical"
                            data={data.charts.ytdByAgent}
                            margin={{ top: 5, right: 30, left: 20, bottom: 5 }}
                        >
                            <CartesianGrid strokeDasharray="3 3" horizontal={false} />
                            <XAxis type="number" />
                            <YAxis dataKey="agent" type="category" width={100} tick={{ fontSize: 11 }} />
                            <Tooltip />
                            <Bar dataKey="count" fill="#3B82F6" radius={[0, 4, 4, 0]} label={{ position: 'right', fill: '#64748b', fontSize: 11 }} />
                        </BarChart>
                    </ResponsiveContainer>
                </div>

                {/* Chart 2: Number of SID YTD by Merchant Referral Program */}
                <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm h-[400px]">
                    <h3 className="text-sm font-bold text-slate-700 mb-4 border-b pb-2">Number of SID YTD by Program</h3>
                    <ResponsiveContainer width="100%" height="100%">
                        <BarChart
                            data={data.charts.ytdByProgram}
                            margin={{ top: 5, right: 30, left: 20, bottom: 5 }}
                        >
                            <CartesianGrid strokeDasharray="3 3" vertical={false} />
                            <XAxis dataKey="program" tick={{ fontSize: 11 }} />
                            <YAxis />
                            <Tooltip />
                            <Bar dataKey="count" fill="#10B981" radius={[4, 4, 0, 0]} label={{ position: 'top', fill: '#64748b', fontSize: 11 }} />
                        </BarChart>
                    </ResponsiveContainer>
                </div>

                {/* Chart 3: MTD Volume USD Split by Program */}
                <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm h-[400px]">
                    <h3 className="text-sm font-bold text-slate-700 mb-4 border-b pb-2">MTD Volume USD Split by Program</h3>
                    <ResponsiveContainer width="100%" height="100%">
                        <PieChart>
                            <Pie
                                data={data.charts.mtdVolumeSplit}
                                cx="50%"
                                cy="50%"
                                labelLine={false}
                                label={({ name, percent }) => `${name} ${(percent * 100).toFixed(0)}%`}
                                outerRadius={120}
                                fill="#8884d8"
                                dataKey="value"
                            >
                                {data.charts.mtdVolumeSplit.map((entry, index) => (
                                    <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                                ))}
                            </Pie>
                            <Tooltip formatter={(value) => formatCurrency(value)} />
                            <Legend />
                        </PieChart>
                    </ResponsiveContainer>
                </div>

                {/* Chart 4: MTD SID Count by Program */}
                <div className="bg-white p-5 rounded-xl border border-slate-200 shadow-sm h-[400px]">
                    <h3 className="text-sm font-bold text-slate-700 mb-4 border-b pb-2">Number of SID for the Month by Program</h3>
                    <ResponsiveContainer width="100%" height="100%">
                        <BarChart
                            data={data.charts.mtdSidByProgram}
                            margin={{ top: 5, right: 30, left: 20, bottom: 5 }}
                        >
                            <CartesianGrid strokeDasharray="3 3" vertical={false} />
                            <XAxis dataKey="program" tick={{ fontSize: 11 }} />
                            <YAxis />
                            <Tooltip />
                            <Bar dataKey="count" fill="#8B5CF6" radius={[4, 4, 0, 0]} label={{ position: 'top', fill: '#64748b', fontSize: 11 }} />
                        </BarChart>
                    </ResponsiveContainer>
                </div>
            </div>

            {/* KPI Tiles (Bottom Row) */}
            <div className="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-6 gap-4">
                <KpiTile label="As of Date" value={asOfDate} sublabel="Selection" color="slate" />
                <KpiTile label="YTD SID" value={data.kpis.ytdSid.toLocaleString()} sublabel="Stores Created" color="blue" />
                <KpiTile label="YTD MID" value={data.kpis.ytdMid.toLocaleString()} sublabel="Merchants Created" color="indigo" />
                <KpiTile label="MTD SID Created" value={data.kpis.mtdSid.toLocaleString()} sublabel="This Month" color="green" />
                <KpiTile label="WTD SID Created" value={data.kpis.wtdSid.toLocaleString()} sublabel="This Week (Mon-Sun)" color="amber" />
                <KpiTile label="Sum of MTD MSF" value={formatCurrency(data.kpis.mtdMsfUsd)} sublabel="USD Revenue" color="emerald" isMoney />
            </div>
        </div>
    );
};

const KpiTile = ({ label, value, sublabel, color, isMoney }) => {
    const colorClasses = {
        slate: 'bg-slate-50 border-slate-200 text-slate-700',
        blue: 'bg-blue-50 border-blue-200 text-blue-700',
        indigo: 'bg-indigo-50 border-indigo-200 text-indigo-700',
        green: 'bg-green-50 border-green-200 text-green-700',
        amber: 'bg-amber-50 border-amber-200 text-amber-700',
        emerald: 'bg-emerald-50 border-emerald-200 text-emerald-700',
    };

    return (
        <div className={`p-4 rounded-xl border ${colorClasses[color]} shadow-sm`}>
            <p className="text-xs font-bold opacity-70 uppercase tracking-wide">{label}</p>
            <h3 className="text-2xl font-bold mt-1 mb-1 truncate" title={value}>{value}</h3>
            <p className="text-xs opacity-60 font-medium">{sublabel}</p>
        </div>
    );
};

export default ExecutiveDashboardReport;
