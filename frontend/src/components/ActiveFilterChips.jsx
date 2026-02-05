import React from 'react';
import { X } from 'lucide-react';

const ActiveFilterChips = ({ filters, onRemove }) => {
    const activeFilters = [];

    // Date
    if (filters.startDate) activeFilters.push({ label: `From: ${filters.startDate}`, key: 'startDate' });
    if (filters.endDate) activeFilters.push({ label: `To: ${filters.endDate}`, key: 'endDate' });
    if (filters.openDateStart) activeFilters.push({ label: `Open From: ${filters.openDateStart}`, key: 'openDateStart' });
    if (filters.openDateEnd) activeFilters.push({ label: `Open To: ${filters.openDateEnd}`, key: 'openDateEnd' });

    // Entity
    if (filters.merchantName) activeFilters.push({ label: `Merchant: ${filters.merchantName}`, key: 'merchantName' });
    (filters.partnerList || []).forEach(v => activeFilters.push({ label: `Partner: ${v}`, key: 'partnerList', value: v }));
    (filters.rmList || []).forEach(v => activeFilters.push({ label: `RM: ${v}`, key: 'rmList', value: v }));
    (filters.teamLeaderList || []).forEach(v => activeFilters.push({ label: `Team Lead: ${v}`, key: 'teamLeaderList', value: v }));

    // Segment
    (filters.sectorList || []).forEach(v => activeFilters.push({ label: `Sector: ${v}`, key: 'sectorList', value: v }));
    (filters.industryList || []).forEach(v => activeFilters.push({ label: `Industry: ${v}`, key: 'industryList', value: v }));
    (filters.mccList || []).forEach(v => activeFilters.push({ label: `MCC: ${v}`, key: 'mccList', value: v }));

    // Tech
    (filters.channelList || []).forEach(v => activeFilters.push({ label: `Channel: ${v}`, key: 'channelList', value: v }));
    (filters.destinationList || []).forEach(v => activeFilters.push({ label: `Dest: ${v}`, key: 'destinationList', value: v }));
    (filters.schemeList || []).forEach(v => activeFilters.push({ label: `Scheme: ${v}`, key: 'schemeList', value: v }));
    (filters.cardTypeList || []).forEach(v => activeFilters.push({ label: `Card: ${v}`, key: 'cardTypeList', value: v }));

    if (activeFilters.length === 0) return null;

    return (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', marginBottom: '16px' }}>
            {activeFilters.map((f, i) => (
                <span key={i} style={{
                    display: 'inline-flex', alignItems: 'center', gap: '6px',
                    padding: '4px 8px', borderRadius: '6px', fontSize: '11px', fontWeight: '600',
                    background: '#eff6ff', color: '#1d4ed8', border: '1px solid #dbeafe'
                }}>
                    {f.label}
                    <button
                        onClick={() => onRemove(f.key, f.value)}
                        style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 0, display: 'flex' }}
                    >
                        <X size={12} color="#1d4ed8" />
                    </button>
                </span>
            ))}
            {activeFilters.length > 0 && (
                <button
                    onClick={() => onRemove('ALL')}
                    style={{ fontSize: '11px', color: '#64748b', textDecoration: 'underline', border: 'none', background: 'none', cursor: 'pointer' }}
                >
                    Clear All
                </button>
            )}
        </div>
    );
};

export default ActiveFilterChips;
