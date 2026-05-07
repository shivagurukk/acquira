import React, { useState, useEffect } from 'react';
import { RefreshCw, Download, Calendar, ArrowRight, Filter } from 'lucide-react';
import ActiveFilterChips from './ActiveFilterChips';

const StandardReportHeader = ({ title, subtitle, onExport, onRefresh, onFilterChange, loading, showFilters, onToggleFilters, filters }) => {
    const [period, setPeriod] = useState('MONTH');
    const [customRange, setCustomRange] = useState({ start: '', end: '' });
    const [showCustomPicker, setShowCustomPicker] = useState(false);

    // Local-date formatter — toISOString() converts to UTC which can shift
    // the date by one day in non-UTC timezones (e.g. IST). See PremiumReportHeader
    // for the full explanation of this bug.
    const fmtLocal = (d) => {
        const yr = d.getFullYear();
        const mo = String(d.getMonth() + 1).padStart(2, '0');
        const dy = String(d.getDate()).padStart(2, '0');
        return `${yr}-${mo}-${dy}`;
    };

    useEffect(() => {
        if (period === 'CUSTOM') return;
        const today = new Date();
        let start = '', end = '';
        if (period === 'TODAY') {
            const dateStr = fmtLocal(today);
            start = dateStr; end = dateStr;
        } else if (period === 'MONTH') {
            start = fmtLocal(new Date(today.getFullYear(), today.getMonth(), 1));
            end = fmtLocal(new Date(today.getFullYear(), today.getMonth() + 1, 0));
        } else if (period === 'LAST_MONTH') {
            // First and last day of the previous calendar month.
            start = fmtLocal(new Date(today.getFullYear(), today.getMonth() - 1, 1));
            end   = fmtLocal(new Date(today.getFullYear(), today.getMonth(), 0));
        } else if (period === 'YEAR') {
            start = fmtLocal(new Date(today.getFullYear(), 0, 1));
            end = fmtLocal(new Date(today.getFullYear(), 11, 31));
        } else if (period === 'PY') {
            start = fmtLocal(new Date(today.getFullYear() - 1, 0, 1));
            end = fmtLocal(new Date(today.getFullYear() - 1, 11, 31));
        }
        if (onFilterChange) onFilterChange({ startDate: start, endDate: end });
        setTimeout(() => { if (onRefresh) onRefresh(); }, 50);
    }, [period]);

    const handleApplyCustom = () => {
        if (onFilterChange) onFilterChange({ startDate: customRange.start, endDate: customRange.end });
        setShowCustomPicker(false);
        if (onRefresh) onRefresh();
    };

    const handleRemoveFilter = (key, value) => {
        if (!filters || !onFilterChange) return;
        if (key === 'ALL') {
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
            onFilterChange({ [key]: filters[key].filter(item => item !== value) });
        }
        setTimeout(() => { if (onRefresh) onRefresh(); }, 50);
    };

    const periodBtn = (p, label) => (
        <button
            key={p}
            onClick={() => { setPeriod(p); setShowCustomPicker(false); }}
            style={{
                padding: '7px 14px', borderRadius: '8px', fontSize: '12px', fontWeight: '600',
                border: 'none', cursor: 'pointer',
                background: period === p ? 'var(--bg-card, #fff)' : 'transparent',
                color: period === p ? 'var(--text, #111827)' : 'var(--text-secondary, #6b7280)',
                boxShadow: period === p ? '0 1px 2px rgba(0,0,0,0.04)' : 'none',
                transition: 'all 0.15s',
            }}
        >
            {label || p}
        </button>
    );

    return (
        <div style={{ marginBottom: '20px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px', flexWrap: 'wrap', gap: 12 }}>
                <div>
                    <h1 style={{ fontSize: '1.3rem', fontWeight: '700', color: 'var(--text, #111827)', letterSpacing: '-0.03em', margin: 0 }}>{title}</h1>
                    <p style={{ color: 'var(--text-muted, #9ca3af)', fontSize: '0.82rem', marginTop: '4px', margin: '4px 0 0' }}>{subtitle}</p>
                </div>

                <div style={{ display: 'flex', gap: '8px', alignItems: 'center', position: 'relative' }}>
                    <div style={{
                        background: 'var(--bg-subtle, #f3f4f6)', padding: '4px',
                        borderRadius: '10px', display: 'flex', gap: '2px',
                        border: '1px solid var(--border, #e5e7eb)',
                    }}>
                        {periodBtn('TODAY', 'Today')}
                        {periodBtn('MONTH', 'This Month')}
                        {periodBtn('LAST_MONTH', 'Last Month')}
                        {periodBtn('YEAR', 'This Year')}
                        {periodBtn('PY', 'Last Year')}
                        <button
                            onClick={() => setShowCustomPicker(!showCustomPicker)}
                            style={{
                                padding: '7px 14px', borderRadius: '8px', fontSize: '12px', fontWeight: '600',
                                border: 'none', cursor: 'pointer',
                                background: period === 'CUSTOM' ? 'var(--bg-card, #fff)' : 'transparent',
                                color: period === 'CUSTOM' ? 'var(--text, #111827)' : 'var(--text-secondary, #6b7280)',
                                boxShadow: period === 'CUSTOM' ? '0 1px 2px rgba(0,0,0,0.04)' : 'none',
                                display: 'flex', alignItems: 'center', gap: '4px',
                            }}
                        >
                            Custom <Calendar size={12} />
                        </button>
                    </div>

                    {onToggleFilters && (
                        <button
                            onClick={onToggleFilters}
                            style={{
                                padding: '8px 14px',
                                background: showFilters ? 'var(--brand-50, #eff6ff)' : 'var(--bg-subtle, #f3f4f6)',
                                borderRadius: '10px',
                                border: '1px solid ' + (showFilters ? 'rgba(37,99,235,0.2)' : 'var(--border, #e5e7eb)'),
                                cursor: 'pointer',
                                color: showFilters ? 'var(--brand, #2563eb)' : 'var(--text-secondary, #6b7280)',
                                display: 'flex', alignItems: 'center', gap: '6px',
                                fontSize: '13px', fontWeight: '600',
                                transition: 'all 0.15s',
                            }}
                        >
                            <Filter size={15} /> Filters
                        </button>
                    )}

                    <button
                        onClick={onRefresh}
                        disabled={loading}
                        style={{
                            padding: '9px', background: 'var(--bg-subtle, #f3f4f6)',
                            borderRadius: '10px', border: '1px solid var(--border, #e5e7eb)',
                            cursor: 'pointer', opacity: loading ? 0.6 : 1,
                            display: 'flex', alignItems: 'center',
                            transition: 'all 0.15s',
                        }}>
                        <RefreshCw size={15} color="var(--text-secondary, #6b7280)" className={loading ? 'spin' : ''} />
                    </button>

                    <button
                        onClick={onExport}
                        style={{
                            padding: '8px 18px', background: 'var(--text, #111827)',
                            color: 'white', borderRadius: '10px', border: 'none', cursor: 'pointer',
                            display: 'flex', alignItems: 'center', gap: '7px', fontSize: '13px', fontWeight: 600,
                            transition: 'all 0.15s',
                        }}
                        onMouseEnter={e => { e.currentTarget.style.opacity = '0.85'; }}
                        onMouseLeave={e => { e.currentTarget.style.opacity = '1'; }}
                    >
                        <Download size={15} /> Export
                    </button>

                    {showCustomPicker && (
                        <div style={{
                            position: 'absolute', right: '0', top: '120%',
                            background: 'var(--bg-card, #fff)', padding: '18px',
                            borderRadius: '14px', boxShadow: 'var(--shadow-lg)',
                            zIndex: 50, border: '1px solid var(--border, #e5e7eb)', minWidth: '300px',
                        }}>
                            <div style={{ display: 'flex', gap: '10px', alignItems: 'center', marginBottom: '14px' }}>
                                <input type="date" value={customRange.start}
                                    onChange={e => setCustomRange({ ...customRange, start: e.target.value })}
                                    style={{
                                        padding: '9px 12px', borderRadius: '10px', border: '1px solid var(--border, #e5e7eb)',
                                        width: '100%', fontSize: '13px', fontFamily: 'inherit',
                                        background: 'var(--bg-subtle, #f3f4f6)', color: 'var(--text, #111827)',
                                    }} />
                                <ArrowRight size={16} color="var(--text-muted, #9ca3af)" style={{ flexShrink: 0 }} />
                                <input type="date" value={customRange.end}
                                    onChange={e => setCustomRange({ ...customRange, end: e.target.value })}
                                    style={{
                                        padding: '9px 12px', borderRadius: '10px', border: '1px solid var(--border, #e5e7eb)',
                                        width: '100%', fontSize: '13px', fontFamily: 'inherit',
                                        background: 'var(--bg-subtle, #f3f4f6)', color: 'var(--text, #111827)',
                                    }} />
                            </div>
                            <button onClick={handleApplyCustom} style={{
                                width: '100%', padding: '9px', background: 'var(--brand, #2563eb)',
                                color: 'white', border: 'none', borderRadius: '10px',
                                fontWeight: '600', cursor: 'pointer', fontSize: '13px', fontFamily: 'inherit',
                            }}>
                                Apply Filter
                            </button>
                        </div>
                    )}
                </div>
            </div>

            {filters && !loading && (
                <ActiveFilterChips filters={filters} onRemove={handleRemoveFilter} />
            )}
        </div>
    );
};

export default StandardReportHeader;
