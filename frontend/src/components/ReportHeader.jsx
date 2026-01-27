import React, { useState, useEffect } from 'react';
import { Download, Filter, Calendar, RefreshCw, ChevronDown } from 'lucide-react';

const ReportHeader = ({
    title,
    subtitle,
    onExport,
    onRunReport,
    filters,
    onFilterChange,
    showFilters,
    onToggleFilters,
    loading
}) => {
    // Local state for tracking which preset is active if not passed directly, 
    // but ideally we rely on filters.datePreset or similar if available.
    // For now, we assume `filters` contains `datePreset` or we derive it.

    const PRESETS = [
        { label: "Today", value: "Today" },
        { label: "Yesterday", value: "Yesterday" },
        { label: "This Year", value: "This Year" },
        { label: "Last Year", value: "Last Year" },
        { label: "Custom", value: "Custom" }
    ];

    // Helper to handle date preset clicks
    const handlePresetClick = (presetValue) => {
        const now = new Date();
        const todayStr = now.toISOString().split('T')[0];
        let start = todayStr;
        let end = todayStr;

        if (presetValue === 'Today') {
            start = end = todayStr;
        } else if (presetValue === 'Yesterday') {
            const y = new Date(now);
            y.setDate(y.getDate() - 1);
            start = end = y.toISOString().split('T')[0];
        } else if (presetValue === 'This Month') {
            const m = new Date(now.getFullYear(), now.getMonth(), 1);
            start = m.toISOString().split('T')[0];
        } else if (presetValue === 'Last Month') {
            const first = new Date(now.getFullYear(), now.getMonth() - 1, 1);
            const last = new Date(now.getFullYear(), now.getMonth(), 0);
            start = first.toISOString().split('T')[0];
            end = last.toISOString().split('T')[0];
        } else if (presetValue === 'This Year') {
            const y = new Date(now.getFullYear(), 0, 1);
            start = y.toISOString().split('T')[0];
        } else if (presetValue === 'Last Year') {
            const y = new Date(now.getFullYear() - 1, 0, 1);
            const ye = new Date(now.getFullYear() - 1, 11, 31);
            start = y.toISOString().split('T')[0];
            end = ye.toISOString().split('T')[0];
        } else if (presetValue === 'Custom') {
            // Keep current dates
        }

        if (onFilterChange) {
            // Using a batched update pattern for the parent
            // Parent should handle object spread: setFilters(prev => ({...prev, ...obj}))
            // Or if parent expects single key, we might need a workaround.
            // But standardizing on object update is cleaner.
            // We'll pass an object if the preset is not Custom, else just the preset.
            if (presetValue === 'Custom') {
                // For Custom, we just switch the mode, dates are manual
                onFilterChange('datePreset', presetValue);
            } else {
                // We need to pass both the preset AND the dates.
                // To support parents that expect (key, value), we might need to iterate?
                // Let's assume onFilterChange can accept an object OR we call it multiple times.
                // Calling multiple times is safer for existing `setFilters(prev => ({...prev, [key]: val}))` if wrapped.
                // But `BusinessFilters` does `onChange({...filters, [key]: val})`.
                // So the parent `setFilters` just replaces state. 
                // We should assume `onFilterChange` replaces state or merges.
                // If we look at VolumeRevenueSummary: `const handleFilterChange = (key, val) => setFilters(prev => ({ ...prev, [key]: val }));`
                // So it expects key, val. 
                // We can't pass an object to that specific handler unless we change it.

                // Strategy: We will update the reports to accept an object OR (key, val).
                // For now, let's call it multiple times? No, that causes re-renders.
                // Better: Pass a special key 'BATCH_UPDATE' or simply update the reports.

                // I will assume I will update the report handlers to support object merge.
                onFilterChange({ datePreset: presetValue, startDate: start, endDate: end });
            }
        }
    };

    const activePreset = filters?.datePreset || 'Custom'; // Default or derived

    return (
        <div className="flex flex-col md:flex-row justify-between items-start md:items-center mb-6 gap-4">
            {/* Title Section */}
            <div>
                <h1 className="text-2xl font-bold text-slate-800">{title}</h1>
                {subtitle && <p className="text-slate-500 text-sm mt-1">{subtitle}</p>}
            </div>

            {/* Actions Section */}
            <div className="flex flex-wrap items-center gap-3">

                {/* Date Presets Pill Group */}
                {!loading && filters && !filters.hideDatePresets && (
                    <div className="flex items-center bg-slate-100 p-1 rounded-lg border border-slate-200">
                        {PRESETS.map(preset => (
                            <button
                                key={preset.value}
                                onClick={() => handlePresetClick(preset.value)}
                                className={`px-3 py-1.5 text-xs font-bold rounded-md transition-all whitespace-nowrap ${activePreset === preset.value
                                    ? 'bg-white text-slate-800 shadow-sm'
                                    : 'text-slate-500 hover:text-slate-700'
                                    }`}
                            >
                                {preset.label}
                            </button>
                        ))}

                        {/* Inline Date Inputs if Custom */}
                        {activePreset === 'Custom' && (
                            <div className="flex items-center gap-2 ml-2 pl-2 border-l border-slate-300 pr-1">
                                <input
                                    type="date"
                                    value={filters?.startDate || ''}
                                    onChange={(e) => onFilterChange('startDate', e.target.value)}
                                    className="px-2 py-1 text-xs border border-slate-300 rounded bg-white focus:border-blue-500 outline-none w-28"
                                />
                                <span className="text-slate-400">-</span>
                                <input
                                    type="date"
                                    value={filters?.endDate || ''}
                                    onChange={(e) => onFilterChange('endDate', e.target.value)}
                                    className="px-2 py-1 text-xs border border-slate-300 rounded bg-white focus:border-blue-500 outline-none w-28"
                                />
                            </div>
                        )}
                    </div>
                )}

                {/* Filters Toggle */}
                <button
                    onClick={onToggleFilters}
                    className={`px-4 py-2 rounded-lg font-bold text-sm bg-white border transition-colors flex items-center gap-2 ${showFilters
                        ? 'border-blue-500 text-blue-600 bg-blue-50'
                        : 'border-slate-200 text-slate-600 hover:bg-slate-50'
                        }`}
                >
                    <Filter size={14} />
                    Filters
                </button>

                {/* Run Report Button */}
                <button
                    onClick={onRunReport}
                    className="px-5 py-2 bg-blue-600 text-white rounded-lg font-bold text-sm hover:bg-blue-700 transition-colors flex items-center gap-2 shadow-sm"
                >
                    {loading ? <RefreshCw size={16} className="animate-spin" /> : <RefreshCw size={16} />}
                    Run Report
                </button>

                {/* Export Button */}
                {onExport && (
                    <button
                        onClick={onExport}
                        className="px-5 py-2 bg-slate-800 text-white rounded-lg font-bold text-sm hover:bg-slate-900 transition-colors flex items-center gap-2 shadow-sm"
                    >
                        <Download size={16} />
                        Export
                    </button>
                )}
            </div>
        </div>
    );
};

export default ReportHeader;
