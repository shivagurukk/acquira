import React, { useState, useEffect } from 'react';
import { RefreshCw, Download, Calendar, ArrowRight, Filter } from 'lucide-react';
import ActiveFilterChips from './ActiveFilterChips';

const StandardReportHeader = ({ title, subtitle, onExport, onRefresh, onFilterChange, loading, showFilters, onToggleFilters, filters }) => {
    const [period, setPeriod] = useState('MONTH'); // TODAY, MONTH, YEAR, PY, CUSTOM
    const [customRange, setCustomRange] = useState({ start: '', end: '' });
    const [showCustomPicker, setShowCustomPicker] = useState(false);

    // Calculate dates on period change
    useEffect(() => {
        if (period === 'CUSTOM') return;

        const today = new Date();
        let start = '';
        let end = '';

        if (period === 'TODAY') {
            const dateStr = today.toISOString().split('T')[0];
            start = dateStr;
            end = dateStr;
        } else if (period === 'MONTH') {
            start = new Date(today.getFullYear(), today.getMonth(), 1).toISOString().split('T')[0];
            end = new Date(today.getFullYear(), today.getMonth() + 1, 0).toISOString().split('T')[0];
        } else if (period === 'YEAR') {
            start = new Date(today.getFullYear(), 0, 1).toISOString().split('T')[0];
            end = new Date(today.getFullYear(), 11, 31).toISOString().split('T')[0];
        } else if (period === 'PY') {
            start = new Date(today.getFullYear() - 1, 0, 1).toISOString().split('T')[0];
            end = new Date(today.getFullYear() - 1, 11, 31).toISOString().split('T')[0];
        }

        if (onFilterChange) {
            onFilterChange({ startDate: start, endDate: end });
        }
        setTimeout(() => { if (onRefresh) onRefresh(); }, 50);

    }, [period]);

    const handleApplyCustom = () => {
        if (onFilterChange) {
            onFilterChange({ startDate: customRange.start, endDate: customRange.end });
        }
        setShowCustomPicker(false);
        if (onRefresh) onRefresh();
    };

    const handleRemoveFilter = (key, value) => {
        if (!filters || !onFilterChange) return;

        if (key === 'ALL') {
            // Reset all known filters to empty
            onFilterChange({
                startDate: '', endDate: '', openDateStart: '', openDateEnd: '',
                partnerList: [], mccList: [], industryList: [], rmList: [], teamLeaderList: [],
                sectorList: [], destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
                merchantName: ''
            });
            return;
        }

        if (key === 'startDate' || key === 'endDate' || key === 'openDateStart' || key === 'openDateEnd' || key === 'merchantName') {
            onFilterChange({ [key]: '' });
        } else if (Array.isArray(filters[key])) {
            const newList = filters[key].filter(item => item !== value);
            onFilterChange({ [key]: newList });
        }

        // Trigger refresh if needed? Usually parent handles it or user clicks Refresh. 
        // For chips, instant refresh is nice.
        setTimeout(() => { if (onRefresh) onRefresh(); }, 50);
    };

    return (
        <div style={{ marginBottom: '20px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
                <div>
                    <h1 style={{ fontSize: '24px', fontWeight: '800', color: '#0f172a', letterSpacing: '-0.5px' }}>{title}</h1>
                    <p style={{ color: '#64748b', fontSize: '13px', marginTop: '4px' }}>{subtitle}</p>
                </div>

                <div style={{ display: 'flex', gap: '10px', alignItems: 'center', position: 'relative' }}>
                    <div style={{ background: '#f1f5f9', padding: '4px', borderRadius: '8px', display: 'flex', gap: '4px' }}>
                        {['TODAY', 'MONTH', 'YEAR', 'PY'].map(p => (
                            <button
                                key={p}
                                onClick={() => { setPeriod(p); setShowCustomPicker(false); }}
                                style={{
                                    padding: '6px 12px', borderRadius: '6px', fontSize: '12px', fontWeight: '600', border: 'none', cursor: 'pointer',
                                    background: period === p ? 'white' : 'transparent',
                                    color: period === p ? '#0f172a' : '#64748b',
                                    boxShadow: period === p ? '0 1px 2px rgba(0,0,0,0.05)' : 'none',
                                    transition: 'all 0.2s'
                                }}
                            >
                                {p === 'PY' ? 'Prev Year' : p}
                            </button>
                        ))}
                        <button
                            onClick={() => setShowCustomPicker(!showCustomPicker)}
                            style={{
                                padding: '6px 12px', borderRadius: '6px', fontSize: '12px', fontWeight: '600', border: 'none', cursor: 'pointer',
                                background: period === 'CUSTOM' ? 'white' : 'transparent',
                                color: period === 'CUSTOM' ? '#0f172a' : '#64748b',
                                boxShadow: period === 'CUSTOM' ? '0 1px 2px rgba(0,0,0,0.05)' : 'none',
                                display: 'flex', alignItems: 'center', gap: '4px'
                            }}
                        >
                            Custom <Calendar size={12} />
                        </button>
                    </div>

                    {onToggleFilters && (
                        <button
                            onClick={onToggleFilters}
                            style={{
                                padding: '8px 12px', background: showFilters ? '#e2e8f0' : '#f1f5f9', borderRadius: '8px', border: 'none', cursor: 'pointer',
                                color: showFilters ? '#0f172a' : '#64748b', display: 'flex', alignItems: 'center', gap: '6px', fontSize: '13px', fontWeight: '600'
                            }}
                            title="Toggle Advanced Filters"
                        >
                            <Filter size={16} /> Filters
                        </button>
                    )}

                    <button
                        onClick={onRefresh}
                        disabled={loading}
                        style={{
                            padding: '8px', background: '#f1f5f9', borderRadius: '8px', border: 'none', cursor: 'pointer',
                            opacity: loading ? 0.7 : 1
                        }}>
                        <RefreshCw size={16} color="#64748b" className={loading ? 'animate-spin' : ''} />
                    </button>

                    <button
                        onClick={onExport}
                        style={{
                            padding: '8px 16px', background: '#0f172a', color: 'white', borderRadius: '8px', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px',
                            boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06)'
                        }}>
                        <Download size={16} /> Export
                    </button>

                    {/* Custom Date Picker Popover */}
                    {showCustomPicker && (
                        <div style={{ position: 'absolute', right: '0', top: '120%', background: 'white', padding: '16px', borderRadius: '12px', boxShadow: '0 10px 15px -3px rgba(0, 0, 0, 0.1)', zIndex: 50, border: '1px solid #e2e8f0', minWidth: '300px' }}>
                            <div style={{ display: 'flex', gap: '10px', alignItems: 'center', marginBottom: '12px' }}>
                                <input type="date" value={customRange.start} onChange={e => setCustomRange({ ...customRange, start: e.target.value })} style={{ padding: '8px', borderRadius: '6px', border: '1px solid #cbd5e1', width: '100%', fontSize: '13px' }} />
                                <ArrowRight size={16} color="#94a3b8" />
                                <input type="date" value={customRange.end} onChange={e => setCustomRange({ ...customRange, end: e.target.value })} style={{ padding: '8px', borderRadius: '6px', border: '1px solid #cbd5e1', width: '100%', fontSize: '13px' }} />
                            </div>
                            <button onClick={handleApplyCustom} style={{ width: '100%', padding: '8px', background: '#3b82f6', color: 'white', border: 'none', borderRadius: '6px', fontWeight: '600', cursor: 'pointer', fontSize: '13px' }}>
                                Apply Filter
                            </button>
                        </div>
                    )}
                </div>
            </div>

            {/* Active Filters Display */}
            {filters && !loading && (
                <ActiveFilterChips filters={filters} onRemove={handleRemoveFilter} />
            )}
        </div>
    );
};

export default StandardReportHeader;
