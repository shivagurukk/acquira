import React, { useState, useEffect } from 'react';
import { Filter, X, ChevronDown, ChevronUp, RefreshCw, Layers } from 'lucide-react';
import MultiSelect from './MultiSelect';

const BusinessFilters = ({ filters, onChange, onApply, variant = 'default' }) => {
    const [expanded, setExpanded] = useState(true);
    const [dateType, setDateType] = useState('TRANSACTION'); // TRANSACTION | OPEN

    const [options, setOptions] = useState({
        partners: [],
        mccs: [],
        industries: ['Retail', 'F&B', 'Services', 'Travel', 'Education', 'Healthcare'],
        rms: [],
        teamLeaders: [],
        sectors: ['SME', 'Corporate', 'Government'],
        schemes: ['VISA', 'MASTERCARD', 'MADA', 'AMEX'],
        cardTypes: ['CREDIT', 'DEBIT', 'PREPAID', 'COMMERCIAL'],
        destinations: ['DOMESTIC', 'INTERNATIONAL'],
        channels: ['POS', 'ECOM', 'MOTO']
    });

    useEffect(() => {
        const fetchOptions = async () => {
            try {
                const token = localStorage.getItem('token');
                const res = await fetch('/api/business/filter-options', {
                    headers: { 'Authorization': `Bearer ${token}` }
                });
                if (res.ok) {
                    const data = await res.json();
                    setOptions(prev => ({ ...prev, ...data }));
                }
            } catch (error) {
                console.error("Failed to fetch filter options", error);
            }
        };
        fetchOptions();
    }, []);

    const update = (key, val) => {
        onChange({ ...filters, [key]: val });
    };

    // Helper for active count
    const getActiveCount = () => {
        let count = 0;
        if (filters.startDate || filters.endDate) count++;
        if (filters.merchantName) count++;
        if ((filters.partnerList || []).length) count++;
        if ((filters.rmList || []).length) count++;
        if ((filters.teamLeaderList || []).length) count++;
        if ((filters.sectorList || []).length) count++;
        if ((filters.industryList || []).length) count++;
        if ((filters.mccList || []).length) count++;
        if ((filters.channelList || []).length) count++;
        if ((filters.destinationList || []).length) count++;
        if ((filters.schemeList || []).length) count++;
        if ((filters.cardTypeList || []).length) count++;
        return count;
    };

    const setDatePreset = (type) => {
        const now = new Date();
        const todayStr = now.toISOString().split('T')[0];
        let start = todayStr;
        let end = todayStr;

        if (type === 'Today') {
            start = end = todayStr;
        } else if (type === 'This Month') {
            const m = new Date(now.getFullYear(), now.getMonth(), 1);
            start = m.toISOString().split('T')[0];
        } else if (type === 'Last Month') {
            const first = new Date(now.getFullYear(), now.getMonth() - 1, 1);
            const last = new Date(now.getFullYear(), now.getMonth(), 0);
            start = first.toISOString().split('T')[0];
            end = last.toISOString().split('T')[0];
        } else if (type === 'Last Year') {
            const y = new Date(now.getFullYear() - 1, 0, 1);
            const ye = new Date(now.getFullYear() - 1, 11, 31);
            start = y.toISOString().split('T')[0];
            end = ye.toISOString().split('T')[0];
        } else if (type === 'Custom') {
            return;
        }

        if (dateType === 'TRANSACTION') {
            onChange({ ...filters, startDate: start, endDate: end, openDateStart: '', openDateEnd: '' });
        } else {
            onChange({ ...filters, openDateStart: start, openDateEnd: end, startDate: '', endDate: '' });
        }
    };

    const handlePresetClick = (preset) => {
        setDatePreset(preset);
    };

    // Render Logic based on Variant
    if (variant === 'panel') {
        return (
            <div className="grid grid-cols-12 gap-6">
                {/* Row 2: Primary Filters */}
                <div className="col-span-12 md:col-span-4">
                    <label className="text-xs font-bold text-slate-500 mb-1 block">Merchant Name / MID</label>
                    <input
                        type="text"
                        className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm focus:outline-none focus:border-blue-500 hover:border-slate-400 transition-colors shadow-sm"
                        placeholder="Search by Name or ID..."
                        value={filters.merchantName || ''}
                        onChange={e => update('merchantName', e.target.value)}
                    />
                </div>
                <div className="col-span-12 md:col-span-4">
                    <MultiSelect label="Partner" options={options.partners} selectedValues={filters.partnerList || []} onChange={v => update('partnerList', v)} placeholder="All Partners" />
                </div>
                <div className="col-span-12 md:col-span-4">
                    <MultiSelect label="Relationship Manager" options={options.rms} selectedValues={filters.rmList || []} onChange={v => update('rmList', v)} placeholder="All RMs" />
                </div>

                {/* Row 3: Management & Segment */}
                <div className="col-span-12 md:col-span-4">
                    <MultiSelect label="Team Leader" options={options.teamLeaders} selectedValues={filters.teamLeaderList || []} onChange={v => update('teamLeaderList', v)} placeholder="All Team Leads" />
                </div>
                <div className="col-span-12 md:col-span-4">
                    <MultiSelect label="Sector" options={options.sectors} selectedValues={filters.sectorList || []} onChange={v => update('sectorList', v)} placeholder="All Sectors" />
                </div>
                <div className="col-span-12 md:col-span-4">
                    <MultiSelect label="Industry" options={options.industries} selectedValues={filters.industryList || []} onChange={v => update('industryList', v)} placeholder="All Industries" />
                </div>

                {/* Row 4: Classification */}
                <div className="col-span-12 md:col-span-4">
                    <MultiSelect label="MCC" options={options.mccs} selectedValues={filters.mccList || []} onChange={v => update('mccList', v)} placeholder="All MCCs" />
                </div>
                <div className="col-span-12 md:col-span-4">
                    <MultiSelect label="Channel" options={options.channels} selectedValues={filters.channelList || []} onChange={v => update('channelList', v)} placeholder="All Channels" />
                </div>
                <div className="col-span-12 md:col-span-4">
                    <MultiSelect label="Destination" options={options.destinations} selectedValues={filters.destinationList || []} onChange={v => update('destinationList', v)} placeholder="All Destinations" />
                </div>

                {/* Row 5: Card Details */}
                <div className="col-span-12 md:col-span-4">
                    <MultiSelect label="Card Scheme" options={options.schemes} selectedValues={filters.schemeList || []} onChange={v => update('schemeList', v)} placeholder="All Schemes" />
                </div>
                <div className="col-span-12 md:col-span-4">
                    <MultiSelect label="Card Type" options={options.cardTypes} selectedValues={filters.cardTypeList || []} onChange={v => update('cardTypeList', v)} placeholder="All Card Types" />
                </div>
            </div>
        );
    }

    // Default Variant (Original Behavior)
    return (
        <div className="bg-white rounded-xl shadow-sm border border-slate-200 relative mb-6">
            {/* Header / Toggle */}
            <div
                className="px-6 py-4 border-b border-slate-200 flex justify-between items-center cursor-pointer hover:bg-slate-50 transition-colors rounded-t-xl"
                onClick={() => setExpanded(!expanded)}
            >
                <div className="flex items-center gap-3">
                    <div className="bg-blue-50 p-2 rounded-lg text-blue-600">
                        <Filter size={18} />
                    </div>
                    <div>
                        <h3 className="text-sm font-bold text-slate-800">Filter Report Data</h3>
                        <p className="text-xs text-slate-500 font-medium">
                            {getActiveCount()} active filters applied
                        </p>
                    </div>
                </div>
                {expanded ? <ChevronUp size={18} className="text-slate-400" /> : <ChevronDown size={18} className="text-slate-400" />}
            </div>

            {expanded && (
                <div className="p-6">
                    <div className="grid grid-cols-12 gap-6">

                        {/* Row 1: Date Filters (Full Width) */}
                        <div className="col-span-12 p-5 bg-slate-50 rounded-xl border border-slate-100">
                            <div className="grid grid-cols-12 gap-6 items-end">
                                {/* Toggle */}
                                <div className="col-span-12 md:col-span-3">
                                    <label className="text-xs font-bold text-slate-500 uppercase mb-2 tracking-wide block">Date Type</label>
                                    <div className="flex bg-white p-1 rounded-lg border border-slate-200 shadow-sm">
                                        <button
                                            onClick={() => setDateType('TRANSACTION')}
                                            className={`flex-1 py-1.5 text-xs font-bold rounded-md transition-all ${dateType === 'TRANSACTION' ? 'bg-blue-600 text-white shadow-sm' : 'text-slate-500 hover:bg-slate-50'}`}
                                        >
                                            Transaction
                                        </button>
                                        <button
                                            onClick={() => setDateType('OPEN')}
                                            className={`flex-1 py-1.5 text-xs font-bold rounded-md transition-all ${dateType === 'OPEN' ? 'bg-blue-600 text-white shadow-sm' : 'text-slate-500 hover:bg-slate-50'}`}
                                        >
                                            Open Date
                                        </button>
                                    </div>
                                </div>

                                {/* Inputs */}
                                <div className="col-span-12 md:col-span-4 flex items-center gap-4">
                                    <div className="flex-1">
                                        <label className="text-xs font-bold text-slate-500 mb-2 block">From</label>
                                        <input
                                            type="date"
                                            className="w-full px-3 py-2 bg-white border border-slate-300 rounded-lg text-sm focus:outline-none focus:border-blue-500 hover:border-slate-400 transition-colors shadow-sm"
                                            value={dateType === 'TRANSACTION' ? filters.startDate : filters.openDateStart}
                                            onChange={(e) => update(dateType === 'TRANSACTION' ? 'startDate' : 'openDateStart', e.target.value)}
                                        />
                                    </div>
                                    <div className="flex-1">
                                        <label className="text-xs font-bold text-slate-500 mb-2 block">To</label>
                                        <input
                                            type="date"
                                            className="w-full px-3 py-2 bg-white border border-slate-300 rounded-lg text-sm focus:outline-none focus:border-blue-500 hover:border-slate-400 transition-colors shadow-sm"
                                            value={dateType === 'TRANSACTION' ? filters.endDate : filters.openDateEnd}
                                            onChange={(e) => update(dateType === 'TRANSACTION' ? 'endDate' : 'openDateEnd', e.target.value)}
                                        />
                                    </div>
                                </div>

                                {/* Presets */}
                                <div className="col-span-12 md:col-span-5">
                                    <label className="text-xs font-bold text-slate-500 uppercase mb-2 tracking-wide block">Quick Select</label>
                                    <div className="flex flex-wrap gap-2">
                                        {['Today', 'This Month', 'Last Month', 'Last Year', 'Custom'].map(preset => (
                                            <button
                                                key={preset}
                                                onClick={() => handlePresetClick(preset)}
                                                className="px-3 py-2 text-xs font-bold bg-white border border-slate-200 text-slate-600 rounded-lg hover:border-blue-500 hover:text-blue-600 transition-all shadow-sm active:scale-95 whitespace-nowrap"
                                            >
                                                {preset}
                                            </button>
                                        ))}
                                    </div>
                                </div>
                            </div>
                        </div>

                        {/* Row 2: Primary Filters */}
                        <div className="col-span-12 md:col-span-4">
                            <label className="text-xs font-bold text-slate-500 mb-1 block">Merchant Name / MID</label>
                            <input
                                type="text"
                                className="w-full px-3 py-2 border border-slate-300 rounded-lg text-sm focus:outline-none focus:border-blue-500 hover:border-slate-400 transition-colors shadow-sm"
                                placeholder="Search by Name or ID..."
                                value={filters.merchantName || ''}
                                onChange={e => update('merchantName', e.target.value)}
                            />
                        </div>
                        <div className="col-span-12 md:col-span-4">
                            <MultiSelect label="Partner" options={options.partners} selectedValues={filters.partnerList || []} onChange={v => update('partnerList', v)} placeholder="All Partners" />
                        </div>
                        <div className="col-span-12 md:col-span-4">
                            <MultiSelect label="Relationship Manager" options={options.rms} selectedValues={filters.rmList || []} onChange={v => update('rmList', v)} placeholder="All RMs" />
                        </div>

                        {/* Row 3: Management & Segment */}
                        <div className="col-span-12 md:col-span-4">
                            <MultiSelect label="Team Leader" options={options.teamLeaders} selectedValues={filters.teamLeaderList || []} onChange={v => update('teamLeaderList', v)} placeholder="All Team Leads" />
                        </div>
                        <div className="col-span-12 md:col-span-4">
                            <MultiSelect label="Sector" options={options.sectors} selectedValues={filters.sectorList || []} onChange={v => update('sectorList', v)} placeholder="All Sectors" />
                        </div>
                        <div className="col-span-12 md:col-span-4">
                            <MultiSelect label="Industry" options={options.industries} selectedValues={filters.industryList || []} onChange={v => update('industryList', v)} placeholder="All Industries" />
                        </div>

                        {/* Row 4: Classification */}
                        <div className="col-span-12 md:col-span-4">
                            <MultiSelect label="MCC" options={options.mccs} selectedValues={filters.mccList || []} onChange={v => update('mccList', v)} placeholder="All MCCs" />
                        </div>
                        <div className="col-span-12 md:col-span-4">
                            <MultiSelect label="Channel" options={options.channels} selectedValues={filters.channelList || []} onChange={v => update('channelList', v)} placeholder="All Channels" />
                        </div>
                        <div className="col-span-12 md:col-span-4">
                            <MultiSelect label="Destination" options={options.destinations} selectedValues={filters.destinationList || []} onChange={v => update('destinationList', v)} placeholder="All Destinations" />
                        </div>

                        {/* Row 5: Card Details */}
                        <div className="col-span-12 md:col-span-4">
                            <MultiSelect label="Card Scheme" options={options.schemes} selectedValues={filters.schemeList || []} onChange={v => update('schemeList', v)} placeholder="All Schemes" />
                        </div>
                        <div className="col-span-12 md:col-span-4">
                            <MultiSelect label="Card Type" options={options.cardTypes} selectedValues={filters.cardTypeList || []} onChange={v => update('cardTypeList', v)} placeholder="All Card Types" />
                        </div>

                    </div>
                </div>
            )}

            {/* Row 6: Sticky Action Footer */}
            {expanded && (
                <div className="sticky bottom-0 left-0 right-0 bg-white border-t border-slate-200 px-8 py-5 rounded-b-xl flex justify-between items-center shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.05)] z-20">
                    <div className="flex gap-2 items-center overflow-x-auto max-w-[60%] no-scrollbar px-2">
                        {/* Active Filter Chips Summary */}
                        <span className="text-xs font-bold text-slate-400 uppercase tracking-wide mr-2 whitespace-nowrap">Active:</span>
                        {getActiveCount() === 0 && <span className="text-xs text-slate-400 italic">None</span>}
                        {filters.startDate && <Chip label={`From: ${filters.startDate}`} />}
                        {filters.merchantName && <Chip label={`Merch: ${filters.merchantName}`} />}
                        {/* Add more chips logic here nicely later */}
                        {getActiveCount() > 2 && <span className="text-xs text-blue-600 bg-blue-50 px-2 py-1 rounded font-bold">+ {getActiveCount() - 2} More</span>}
                    </div>

                    <div className="flex items-center gap-4 shrink-0">
                        <button
                            onClick={() => onChange({
                                startDate: '', endDate: '', openDateStart: '', openDateEnd: '',
                                partnerList: [], mccList: [], industryList: [], rmList: [], teamLeaderList: [],
                                sectorList: [], destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
                                merchantName: ''
                            })}
                            className="px-5 py-2.5 text-sm text-slate-600 font-bold hover:text-red-600 hover:bg-red-50 rounded-lg transition-colors border border-transparent"
                        >
                            Reset
                        </button>
                        <button
                            onClick={onApply}
                            className="px-8 py-2.5 bg-blue-600 text-white font-bold text-sm rounded-lg hover:bg-blue-700 shadow-lg shadow-blue-200 transition-all flex items-center gap-2 transform active:scale-95"
                        >
                            <RefreshCw size={18} /> Apply Filters
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
};

const Chip = ({ label }) => (
    <span className="bg-blue-50 text-blue-700 text-xs px-2 py-1 rounded border border-blue-100 font-medium whitespace-nowrap">
        {label}
    </span>
);

export default BusinessFilters;
