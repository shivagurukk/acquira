import React from 'react';
import { X } from 'lucide-react';

const ActiveFilterChips = ({ filters, onRemove }) => {
    const activeFilters = [];

    // Date
    if (filters.startDate) activeFilters.push({ label: `From: ${filters.startDate}`, key: 'startDate' });
    if (filters.endDate) activeFilters.push({ label: `To: ${filters.endDate}`, key: 'endDate' });
    if (filters.openDateStart) activeFilters.push({ label: `Open From: ${filters.openDateStart}`, key: 'openDateStart' });
    if (filters.openDateEnd) activeFilters.push({ label: `Open To: ${filters.openDateEnd}`, key: 'openDateEnd' });

    // MID / SID
    (filters.midList || []).forEach(v => activeFilters.push({ label: `MID: ${v}`, key: 'midList', value: v }));
    (filters.sidList || []).forEach(v => activeFilters.push({ label: `SID: ${v}`, key: 'sidList', value: v }));

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
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', marginBottom: '12px' }}>
            {activeFilters.map((f, i) => (
                <span key={i} style={{
                    display: 'inline-flex', alignItems: 'center', gap: '5px',
                    padding: '3px 10px', borderRadius: '8px', fontSize: '11px', fontWeight: '600',
                    background: 'var(--wash)', color: 'var(--primary)', border: '1px solid var(--wash)',
                    transition: 'all 0.15s',
                }}>
                    {f.label}
                    <button onClick={() => onRemove(f.key, f.value)}
                        style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 0, display: 'flex', opacity: 0.7 }}
                        onMouseEnter={e => e.target.style.opacity = 1} onMouseLeave={e => e.target.style.opacity = 0.7}
                    >
                        <X size={11} color="var(--primary)" />
                    </button>
                </span>
            ))}
            {activeFilters.length > 1 && (
                <button onClick={() => onRemove('ALL')}
                    style={{ fontSize: '11px', color: '#94a3b8', textDecoration: 'underline', border: 'none', background: 'none', cursor: 'pointer', fontWeight: 500 }}>
                    Clear All
                </button>
            )}
        </div>
    );
};

export default ActiveFilterChips;
