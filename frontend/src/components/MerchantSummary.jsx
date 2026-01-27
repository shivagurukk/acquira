import React, { useState, useEffect } from 'react';
import { Calendar, ChevronRight, ChevronLeft, LayoutGrid, RotateCcw, TrendingUp, Download, Sparkles, Loader2, Search } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';

// --- Aurora Components ---

const GlassCard = ({ children, className = "" }) => (
    <div className={`bg-white/80 backdrop-blur-xl border border-white/60 shadow-[0_8px_30px_rgb(0,0,0,0.04)] rounded-3xl ${className}`}>
        {children}
    </div>
);

const SectionTitle = ({ icon: Icon, title }) => (
    <h3 className="text-base font-black text-indigo-400 uppercase tracking-widest flex items-center gap-3 mb-6">
        {Icon && <Icon size={18} />}
        {title}
    </h3>
);

const MerchantSummary = () => {
    const [summaries, setSummaries] = useState([]);
    const [loading, setLoading] = useState(false);

    // Date Filters
    const [year, setYear] = useState(new Date().getFullYear());
    const [month, setMonth] = useState(new Date().getMonth() + 1);
    const [day, setDay] = useState(new Date().getDate());

    // Pagination
    const [page, setPage] = useState(0);
    const [size, setSize] = useState(20);
    const [totalElements, setTotalElements] = useState(0);
    const [totalPages, setTotalPages] = useState(0);

    const years = Array.from({ length: 5 }, (_, i) => new Date().getFullYear() - i);
    const months = Array.from({ length: 12 }, (_, i) => i + 1);
    const days = Array.from({ length: 31 }, (_, i) => i + 1);

    useEffect(() => {
        fetchSummaries();
    }, [page, size, year, month, day]);

    const fetchSummaries = async () => {
        setLoading(true);
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');
            const params = new URLSearchParams();
            params.append('year', year);
            params.append('month', month);
            params.append('day', day);
            params.append('page', page);
            params.append('size', size);

            const res = await fetch(`/api/analytics/merchant-summaries?${params.toString()}`, {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });

            if (res.ok) {
                const data = await res.json();
                setSummaries(data.content);
                setTotalElements(data.totalElements);
                setTotalPages(data.totalPages);
            }
        } catch (error) { console.error("Failed to fetch merchant summaries", error); }
        finally { setLoading(false); }
    };

    const formatCurrency = (val) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(val || 0);

    const handleExport = async () => {
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');
            const params = new URLSearchParams();
            params.append('year', year);
            params.append('month', month);
            params.append('day', day);

            const res = await fetch(`/api/analytics/merchant-summaries/export?${params.toString()}`, {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });

            if (res.ok) {
                const blob = await res.blob();
                const url = window.URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `Merchant_Summary_${year}-${month}-${day}.csv`;
                document.body.appendChild(a);
                a.click();
                a.remove();
            } else { console.error("Export request failed"); }
        } catch (error) { console.error("Export failed", error); }
    };

    return (
        <div className="min-h-screen bg-[#F8F9FC] font-sans text-slate-900 pb-20">

            {/* --- Floating Header --- */}
            <div className="sticky top-0 z-40 px-6 py-4">
                <GlassCard className="px-6 py-4 flex justify-between items-center shadow-lg bg-white/90">
                    <div className="flex items-center gap-4">
                        <div className="bg-emerald-500 p-2.5 rounded-xl shadow-lg shadow-emerald-500/20">
                            <LayoutGrid className="text-white" size={20} />
                        </div>
                        <div>
                            <h1 className="text-xl font-bold text-slate-900 tracking-tight">Merchant Performance</h1>
                            <p className="text-xs text-slate-500 font-medium mt-0.5">Daily & Monthly Volume Analytics</p>
                        </div>
                    </div>

                    <div className="flex items-center gap-3">
                        <button onClick={fetchSummaries} className="px-4 py-2.5 rounded-xl text-xs font-bold text-slate-500 hover:text-slate-700 hover:bg-slate-100 transition-colors flex items-center gap-2">
                            <RotateCcw size={14} /> REFRESH
                        </button>
                        <button onClick={handleExport} className="bg-emerald-600 hover:bg-emerald-700 text-white text-sm font-bold px-6 py-2.5 rounded-xl shadow-lg shadow-emerald-600/20 hover:shadow-emerald-600/30 transition-all flex items-center gap-2 transform active:scale-95">
                            <Download size={16} /> EXPORT CSV
                        </button>
                    </div>
                </GlassCard>
            </div>

            {/* --- Main Content --- */}
            <div className="px-6 max-w-[1920px] mx-auto space-y-6">

                {/* --- Filter Control Center --- */}
                <GlassCard className="p-8">
                    <SectionTitle icon={Calendar} title="Reference Date Selection" />
                    <div className="flex flex-wrap items-center gap-4">
                        <div className="relative group">
                            <div className="absolute -top-2.5 left-3 bg-white px-1 text-[10px] font-bold text-slate-400 uppercase tracking-wide">Year</div>
                            <select value={year} onChange={(e) => setYear(Number(e.target.value))} className="w-32 px-4 py-3 bg-white border border-slate-200 rounded-xl text-sm font-bold text-slate-700 outline-none focus:border-indigo-500 focus:ring-4 focus:ring-indigo-500/10 transition-all appearance-none cursor-pointer hover:border-indigo-300">
                                {years.map(y => <option key={y} value={y}>{y}</option>)}
                            </select>
                        </div>
                        <div className="relative group">
                            <div className="absolute -top-2.5 left-3 bg-white px-1 text-[10px] font-bold text-slate-400 uppercase tracking-wide">Month</div>
                            <select value={month} onChange={(e) => setMonth(Number(e.target.value))} className="w-48 px-4 py-3 bg-white border border-slate-200 rounded-xl text-sm font-bold text-slate-700 outline-none focus:border-indigo-500 focus:ring-4 focus:ring-indigo-500/10 transition-all appearance-none cursor-pointer hover:border-indigo-300">
                                {months.map(m => <option key={m} value={m}>{new Date(0, m - 1).toLocaleString('default', { month: 'long' })}</option>)}
                            </select>
                        </div>
                        <div className="relative group">
                            <div className="absolute -top-2.5 left-3 bg-white px-1 text-[10px] font-bold text-slate-400 uppercase tracking-wide">Day</div>
                            <select value={day} onChange={(e) => setDay(Number(e.target.value))} className="w-24 px-4 py-3 bg-white border border-slate-200 rounded-xl text-sm font-bold text-slate-700 outline-none focus:border-indigo-500 focus:ring-4 focus:ring-indigo-500/10 transition-all appearance-none cursor-pointer hover:border-indigo-300">
                                {days.map(d => <option key={d} value={d}>{d}</option>)}
                            </select>
                        </div>
                        <div className="ml-auto text-xs text-slate-400 font-medium italic">
                            Viewing snapshot for <span className="font-bold text-slate-600">{new Date(year, month - 1, day).toDateString()}</span>
                        </div>
                    </div>
                </GlassCard>

                {/* --- Results Table --- */}
                <GlassCard className="overflow-hidden min-h-[600px] flex flex-col">
                    <div className="px-8 py-5 border-b border-slate-100 flex justify-between items-center bg-slate-50/30">
                        <div className="flex items-center gap-3">
                            <h2 className="text-base font-bold text-slate-800 flex items-center gap-2 uppercase tracking-wide">
                                <TrendingUp size={18} className="text-emerald-500" /> Performance Data
                            </h2>
                            <span className="px-3 py-1.5 rounded-lg bg-white border border-slate-200 text-slate-600 text-sm font-mono font-bold shadow-sm">{totalElements} Merchants</span>
                        </div>
                        <div className="flex items-center gap-4">
                            <span className="text-xs text-slate-400 font-bold uppercase tracking-wide">Page {page + 1} of {totalPages || 1}</span>
                            <div className="flex gap-1">
                                <button disabled={page === 0} onClick={() => setPage(p => p - 1)} className="w-10 h-10 rounded-lg border border-slate-200 flex items-center justify-center hover:bg-slate-50 disabled:opacity-50 transition-all active:scale-95"><ChevronLeft size={18} className="text-slate-600" /></button>
                                <button disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)} className="w-10 h-10 rounded-lg border border-slate-200 flex items-center justify-center hover:bg-slate-50 disabled:opacity-50 transition-all active:scale-95"><ChevronRight size={18} className="text-slate-600" /></button>
                            </div>
                        </div>
                    </div>

                    <div className="flex-1 overflow-auto">
                        <table className="w-full text-left text-base border-collapse">
                            <thead className="bg-[#F8F9FC] text-slate-400 font-extrabold uppercase text-sm tracking-wider border-b-2 border-slate-200">
                                <tr>
                                    <th className="px-8 py-6 border-r border-slate-200/60">Merchant</th>
                                    <th className="px-6 py-6 border-r border-slate-200/60">Sales User</th>
                                    <th className="px-6 py-6 text-right border-r border-slate-200/60">Credit Vol</th>
                                    <th className="px-6 py-6 text-right border-r border-slate-200/60">Debit Vol</th>
                                    <th className="px-6 py-6 text-right border-r border-slate-200/60">Daily Total</th>
                                    <th className="px-6 py-6 text-right border-r border-slate-200/60">Daily Count</th>
                                    <th className="px-6 py-6 text-right border-r border-slate-200/60">MTD Volume</th>
                                    <th className="px-6 py-6 text-right">YTD Volume</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100">
                                {loading ? (
                                    <tr>
                                        <td colSpan="8" className="px-6 py-32 text-center">
                                            <div className="flex flex-col items-center gap-4">
                                                <Loader2 size={40} className="animate-spin text-emerald-500" />
                                                <p className="text-slate-500 font-medium text-sm animate-pulse">Aggregating performance metrics...</p>
                                            </div>
                                        </td>
                                    </tr>
                                ) : summaries.length === 0 ? (
                                    <tr>
                                        <td colSpan="8" className="px-6 py-32 text-center">
                                            <div className="inline-flex flex-col items-center justify-center p-8 rounded-3xl bg-slate-50 border border-slate-100 border-dashed">
                                                <div className="bg-white p-4 rounded-full shadow-sm mb-4"><Search size={24} className="text-slate-300" /></div>
                                                <p className="text-slate-800 font-bold mb-1">No performance data found</p>
                                                <p className="text-slate-400 text-xs">Try selecting a different date.</p>
                                            </div>
                                        </td>
                                    </tr>
                                ) : (
                                    summaries.map((s, idx) => (
                                        <tr key={idx} className="group hover:bg-slate-50 transition-colors border-b border-slate-200/60 last:border-0">
                                            <td className="px-8 py-6 border-r border-slate-100">
                                                <div className="flex flex-col">
                                                    <span className="font-bold text-slate-800 text-base line-clamp-1 group-hover:text-emerald-700 transition-colors" title={s.merchantName}>{s.merchantName}</span>
                                                    <span className="font-mono text-sm text-slate-400 mt-1">{s.mid}</span>
                                                </div>
                                            </td>
                                            <td className="px-6 py-6 text-slate-600 font-medium border-r border-slate-100">
                                                {s.salesUserId || <span className="text-slate-300 italic text-sm">Unassigned</span>}
                                            </td>
                                            <td className="px-6 py-6 text-right font-mono text-slate-600 text-base border-r border-slate-100">{formatCurrency(s.creditVolume)}</td>
                                            <td className="px-6 py-6 text-right font-mono text-slate-600 text-base border-r border-slate-100">{formatCurrency(s.debitPrepaidVolume)}</td>
                                            <td className="px-6 py-6 text-right border-r border-slate-100 bg-slate-50/30">
                                                <span className="font-mono font-bold text-slate-900 text-base px-3 py-1.5 bg-white/80 rounded-lg group-hover:bg-white transition-colors border border-slate-200 group-hover:border-slate-300">
                                                    {formatCurrency(s.dailyVolume)}
                                                </span>
                                            </td>
                                            <td className="px-6 py-6 text-right font-mono text-slate-500 text-base border-r border-slate-100">{s.dailyCount}</td>
                                            <td className="px-6 py-6 text-right font-mono font-bold text-emerald-600 text-base border-r border-slate-100">{formatCurrency(s.mtdVolume)}</td>
                                            <td className="px-6 py-6 text-right font-mono font-bold text-amber-600 text-base">{formatCurrency(s.ytdVolume)}</td>
                                        </tr>
                                    ))
                                )}
                            </tbody>
                        </table>
                    </div>
                </GlassCard>
            </div>
        </div>
    );
};

export default MerchantSummary;
