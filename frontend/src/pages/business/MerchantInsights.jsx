import React, { useState, useEffect } from 'react';
import { Download, Calendar, ArrowUp, ArrowDown, ChevronRight, ChevronLeft, CreditCard, LayoutGrid, Users, Award, PieChart } from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, LineChart, Line } from 'recharts';

const MerchantInsights = () => {
    const [activeTab, setActiveTab] = useState('overview');
    const [data, setData] = useState(null);
    const [loading, setLoading] = useState(true);
    const [monthOffset, setMonthOffset] = useState(1); // 1 = Last Month

    useEffect(() => {
        fetchInsights();
    }, [monthOffset]);

    const fetchInsights = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');

            // Calculate Year/Month based on offset
            const date = new Date();
            date.setMonth(date.getMonth() - monthOffset);
            const year = date.getFullYear();
            const month = date.getMonth() + 1;

            const res = await fetch(`/api/business/insights/overview?year=${year}&month=${month}`, {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });
            if (res.ok) setData(await res.json());
        } catch (error) {
            console.error("Failed to fetch insights", error);
        } finally {
            setLoading(false);
        }
    };

    const downloadPdf = async () => {
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');

            const date = new Date();
            date.setMonth(date.getMonth() - monthOffset);
            const year = date.getFullYear();
            const month = date.getMonth() + 1;

            const response = await fetch(`/api/business/insights/pdf?year=${year}&month=${month}`, {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });

            if (response.ok) {
                const blob = await response.blob();
                const url = window.URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `Merchant_Insight_${year}_${month}.pdf`;
                document.body.appendChild(a);
                a.click();
                a.remove();
            }
        } catch (error) {
            console.error("Download failed", error);
        }
    };

    const currentDate = new Date();
    currentDate.setMonth(currentDate.getMonth() - monthOffset);
    const monthLabel = currentDate.toLocaleString('default', { month: 'long', year: 'numeric' });

    if (loading) return <div className="min-h-screen bg-[#0B1630] flex items-center justify-center text-white">Loading Insights...</div>;
    if (!data) return <div className="min-h-screen bg-[#0B1630] flex items-center justify-center text-white">No Data Available</div>;

    return (
        <div className="min-h-screen bg-[#0B1630] text-white font-sans">
            {/* Header */}
            <div className="bg-[#0B1630] border-b border-[#1F3B6D] p-4 flex justify-between items-center sticky top-0 z-10">
                <div className="flex items-center gap-4">
                    <div className="text-xl font-bold tracking-wider text-cyan-400">MAGNATI</div>
                    <div className="h-6 w-px bg-[#1F3B6D]"></div>
                    <div className="text-sm text-gray-400">Payment into Possibilities</div>
                </div>

                <div className="flex items-center gap-4">
                    <div className="flex items-center bg-[#0F2347] rounded-lg border border-[#1F3B6D] p-1">
                        <button onClick={() => setMonthOffset(m => m + 1)} className="p-1 hover:bg-[#1F3B6D] rounded text-gray-300"><ChevronLeft size={16} /></button>
                        <span className="px-4 text-sm font-semibold min-w-[120px] text-center">{monthLabel}</span>
                        <button onClick={() => setMonthOffset(m => Math.max(0, m - 1))} className="p-1 hover:bg-[#1F3B6D] rounded text-gray-300"><ChevronRight size={16} /></button>
                    </div>

                    <button
                        onClick={downloadPdf}
                        className="flex items-center gap-2 bg-[#FF5A5F] hover:bg-[#E0484D] text-white px-4 py-2 rounded-lg text-sm font-bold transition-colors">
                        <Download size={16} /> DOWNLOAD REPORT
                    </button>
                </div>
            </div>

            {/* Navigation Tabs */}
            <div className="flex border-b border-[#1F3B6D] bg-[#0F2347]/50">
                <NavTab active={activeTab === 'overview'} onClick={() => setActiveTab('overview')} icon={LayoutGrid} label="BUSINESS OVERVIEW" />
                <NavTab active={activeTab === 'achievements'} onClick={() => setActiveTab('achievements')} icon={Award} label="BUSINESS ACHIEVEMENTS" />
                <NavTab active={activeTab === 'loyalty'} onClick={() => setActiveTab('loyalty')} icon={Users} label="CONSUMER LOYALTY" />
                <NavTab active={activeTab === 'customers'} onClick={() => setActiveTab('customers')} icon={PieChart} label="WHO ARE YOUR CUSTOMERS?" />
            </div>

            {/* Content Area */}
            <div className="p-6 max-w-[1600px] mx-auto">
                {activeTab === 'overview' && <OverviewTab data={data.overview} />}
                {activeTab === 'achievements' && <AchievementsTab data={data.achievements} />}
            </div>
        </div>
    );
};

const NavTab = ({ active, onClick, icon: Icon, label }) => (
    <button
        onClick={onClick}
        className={`flex items-center gap-2 px-6 py-4 text-sm font-bold border-b-2 transition-colors ${active
                ? 'border-[#FF5A5F] text-white bg-[#1F3B6D]/20'
                : 'border-transparent text-gray-400 hover:text-white hover:bg-[#1F3B6D]/10'
            }`}
    >
        <Icon size={18} className={active ? 'text-[#FF5A5F]' : ''} />
        {label}
    </button>
);

const OverviewTab = ({ data }) => {
    return (
        <div className="flex flex-col gap-6">
            {/* KPI Row 1 */}
            <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                <KpiCard title="SALES (AED)" value={data.sales.formattedValue} growth={data.sales.momGrowth} trend={data.sales.trend} icon={CreditCard} />
                <KpiCard title="TRANSACTIONS" value={data.transactions.formattedValue} growth={data.transactions.momGrowth} trend={data.transactions.trend} icon={LayoutGrid} />
                <KpiCard title="CUSTOMERS" value={data.customers.formattedValue} growth={data.customers.momGrowth} trend={data.customers.trend} icon={Users} />
            </div>

            {/* KPI Row 2 - Peak Stats */}
            <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                <KpiCard title="MAX DAILY SALES (AED)" value={data.peakStats.maxDailySales.formattedValue} growth={data.peakStats.maxDailySales.momGrowth} trend={data.peakStats.maxDailySales.trend} />
                <KpiCard title="MAX NO. OF TXNS IN A DAY" value={data.peakStats.maxTxnsInDay.formattedValue} growth={data.peakStats.maxTxnsInDay.momGrowth} trend={data.peakStats.maxTxnsInDay.trend} />
                <KpiCard title="HIGHEST TRANSACTION VALUE (AED)" value={data.peakStats.highestTxnValue.formattedValue} growth={data.peakStats.highestTxnValue.momGrowth} trend={data.peakStats.highestTxnValue.trend} />
            </div>

            {/* Charts Row */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mt-4">
                <ChartCard title="SALES BY DAY OF WEEK">
                    <ResponsiveContainer width="100%" height={300}>
                        <BarChart data={data.salesByDayOfWeek}>
                            <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#1F3B6D" />
                            <XAxis dataKey="label" stroke="#B9C6DD" tick={{ fontSize: 12 }} />
                            <YAxis stroke="#B9C6DD" tick={{ fontSize: 12 }} />
                            <Tooltip
                                contentStyle={{ backgroundColor: '#0F2347', borderColor: '#1F3B6D', color: '#fff' }}
                                cursor={{ fill: 'rgba(255,255,255,0.05)' }}
                            />
                            <Bar dataKey="value" fill="#0B1630" radius={[4, 4, 0, 0]} barSize={40} />
                        </BarChart>
                    </ResponsiveContainer>
                </ChartCard>

                <ChartCard title="TRANSACTIONS BY DAY OF WEEK">
                    <ResponsiveContainer width="100%" height={300}>
                        <BarChart data={data.transactionsByDayOfWeek}>
                            <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#1F3B6D" />
                            <XAxis dataKey="label" stroke="#B9C6DD" tick={{ fontSize: 12 }} />
                            <YAxis stroke="#B9C6DD" tick={{ fontSize: 12 }} />
                            <Tooltip
                                contentStyle={{ backgroundColor: '#0F2347', borderColor: '#1F3B6D', color: '#fff' }}
                                cursor={{ fill: 'rgba(255,255,255,0.05)' }}
                            />
                            <Bar dataKey="value" fill="#7CB4FF" radius={[4, 4, 0, 0]} barSize={40} />
                        </BarChart>
                    </ResponsiveContainer>
                </ChartCard>
            </div>
        </div>
    );
};

const AchievementsTab = ({ data }) => (
    <div className="flex flex-col gap-6">
        <ChartCard title="DAILY SALES & COUNT">
            <ResponsiveContainer width="100%" height={350}>
                <BarChart data={data.dailySalesAndCount}>
                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#1F3B6D" />
                    <XAxis dataKey="label" stroke="#B9C6DD" tickFormatter={(v) => v.slice(-2)} />
                    <YAxis yAxisId="left" stroke="#FF5A5F" />
                    <YAxis yAxisId="right" orientation="right" stroke="#7CB4FF" />
                    <Tooltip contentStyle={{ backgroundColor: '#0F2347', borderColor: '#1F3B6D', color: '#fff' }} />
                    <Bar yAxisId="left" dataKey="value" name="Sales" fill="#FF5A5F" radius={[2, 2, 0, 0]} />
                    <Bar yAxisId="right" dataKey="value2" name="Txn Count" fill="#7CB4FF" radius={[2, 2, 0, 0]} />
                </BarChart>
            </ResponsiveContainer>
        </ChartCard>

        <ChartCard title="UNIQUE CUSTOMERS BY DAY">
            <ResponsiveContainer width="100%" height={250}>
                <BarChart data={data.uniqueCustomersByDay}>
                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#1F3B6D" />
                    <XAxis dataKey="label" stroke="#B9C6DD" tickFormatter={(v) => v.slice(-2)} />
                    <YAxis stroke="#B9C6DD" />
                    <Tooltip contentStyle={{ backgroundColor: '#0F2347', borderColor: '#1F3B6D', color: '#fff' }} />
                    <Bar dataKey="value" fill="#0B1630" stroke="#7CB4FF" strokeWidth={1} radius={[2, 2, 0, 0]} />
                </BarChart>
            </ResponsiveContainer>
        </ChartCard>
    </div>
);

const KpiCard = ({ title, value, growth, trend, icon: Icon }) => {
    const isUp = trend === 'UP';
    const isFlat = trend === 'FLAT';

    return (
        <div className="bg-[#0F2347] p-5 rounded-sm border-t-4 border-[#1F3B6D] hover:border-[#FF5A5F] transition-colors relative shadow-lg">
            {Icon && <Icon className="absolute top-5 right-5 text-[#1F3B6D]" size={32} />}
            <div className="text-[#B9C6DD] text-xs font-bold uppercase tracking-wider mb-2">{title}</div>
            <div className="text-3xl font-bold text-white mb-4">{value}</div>

            {growth !== null && (
                <div className="flex items-center gap-2">
                    {isFlat ? (
                        <span className="text-gray-400 font-bold text-lg">-</span>
                    ) : (
                        isUp ? <ArrowUp className="text-green-500" size={24} /> : <ArrowDown className="text-red-500" size={24} />
                    )}
                    <span className={`text-xl font-bold ${isUp ? 'text-green-500' : isFlat ? 'text-gray-400' : 'text-red-500'}`}>
                        {Math.abs(growth).toFixed(1)} %
                    </span>
                    <span className="text-[#B9C6DD] text-[10px] uppercase font-semibold mt-1">Month on Month Growth</span>
                </div>
            )}
        </div>
    );
};

const ChartCard = ({ title, children }) => (
    <div className="bg-[#FFFFFF] p-6 rounded-sm shadow-sm">
        <div className="flex items-center gap-3 mb-6">
            <div className="p-2 bg-[#0B1630] rounded text-white"><LayoutGrid size={18} /></div>
            <h3 className="text-[#0B1630] font-bold text-sm tracking-wider uppercase">{title}</h3>
        </div>
        {children}
    </div>
);

export default MerchantInsights;
