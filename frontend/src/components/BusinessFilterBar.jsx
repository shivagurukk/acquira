import React, { useState } from 'react';
import { Calendar, Search, Filter, RefreshCcw } from 'lucide-react';

const BusinessFilterBar = ({ onFilterChange, showMerchantSearch = false }) => {
    const [filters, setFilters] = useState({
        startDate: '',
        endDate: '',
        merchantId: '',
    });

    const handleChange = (e) => {
        const { name, value } = e.target;
        setFilters(prev => ({ ...prev, [name]: value }));
    };

    const handleApply = () => {
        onFilterChange(filters);
    };

    const handleReset = () => {
        const clean = { startDate: '', endDate: '', merchantId: '' };
        setFilters(clean);
        onFilterChange(clean);
    };

    return (
        <div style={{
            background: 'white', padding: '16px', borderRadius: '12px',
            boxShadow: '0 1px 3px rgba(0,0,0,0.05)', display: 'flex', gap: '16px', flexWrap: 'wrap', alignItems: 'end'
        }}>
            {/* Date Range */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                <label style={{ fontSize: '12px', fontWeight: '600', color: '#64748b' }}>From Date</label>
                <div style={{ position: 'relative' }}>
                    <Calendar size={16} color="#94a3b8" style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)' }} />
                    <input
                        type="date"
                        name="startDate"
                        value={filters.startDate}
                        onChange={handleChange}
                        style={{ padding: '8px 10px 8px 32px', borderRadius: '6px', border: '1px solid #e2e8f0', fontSize: '14px', color: '#334155' }}
                    />
                </div>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                <label style={{ fontSize: '12px', fontWeight: '600', color: '#64748b' }}>To Date</label>
                <div style={{ position: 'relative' }}>
                    <Calendar size={16} color="#94a3b8" style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)' }} />
                    <input
                        type="date"
                        name="endDate"
                        value={filters.endDate}
                        onChange={handleChange}
                        style={{ padding: '8px 10px 8px 32px', borderRadius: '6px', border: '1px solid #e2e8f0', fontSize: '14px', color: '#334155' }}
                    />
                </div>
            </div>

            {/* Merchant Search - Optional */}
            {showMerchantSearch && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', flex: 1, minWidth: '200px' }}>
                    <label style={{ fontSize: '12px', fontWeight: '600', color: '#64748b' }}>Merchant ID / Name</label>
                    <div style={{ position: 'relative' }}>
                        <Search size={16} color="#94a3b8" style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)' }} />
                        <input
                            type="text"
                            name="merchantId"
                            placeholder="Search Merchant..."
                            value={filters.merchantId}
                            onChange={handleChange}
                            style={{ width: '100%', padding: '8px 10px 8px 32px', borderRadius: '6px', border: '1px solid #e2e8f0', fontSize: '14px', color: '#334155' }}
                        />
                    </div>
                </div>
            )}

            {/* Actions */}
            <div style={{ display: 'flex', gap: '10px' }}>
                <button onClick={handleApply} style={{
                    display: 'flex', alignItems: 'center', gap: '8px',
                    padding: '8px 16px', borderRadius: '6px', border: 'none', background: '#3b82f6', color: 'white', fontWeight: '500', cursor: 'pointer'
                }}>
                    <Filter size={16} /> Apply
                </button>
                <button onClick={handleReset} style={{
                    display: 'flex', alignItems: 'center', gap: '8px',
                    padding: '8px 16px', borderRadius: '6px', border: '1px solid #e2e8f0', background: 'white', color: '#64748b', fontWeight: '500', cursor: 'pointer'
                }}>
                    <RefreshCcw size={16} /> Reset
                </button>
            </div>
        </div>
    );
};

export default BusinessFilterBar;
