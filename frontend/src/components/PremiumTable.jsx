import React from 'react';
import { Loader2 } from 'lucide-react';

const PremiumTable = ({ columns, data, loading, emptyMessage = "No data available" }) => {
    return (
        <div style={{
            background: 'var(--bg-card)',
            borderRadius: '16px',
            boxShadow: '0 4px 20px rgba(0, 0, 0, 0.03)',
            border: '1px solid #f1f5f9',
            overflow: 'hidden',
            display: 'flex',
            flexDirection: 'column',
            height: '100%'
        }}>
            <div style={{ flex: 1, overflow: 'auto', position: 'relative' }}>
                <table style={{ minWidth: '100%', width: 'max-content', borderCollapse: 'separate', borderSpacing: 0 }}>
                    <thead style={{ position: 'sticky', top: 0, zIndex: 30, background: '#1e293b' }}>
                        <tr>
                            {columns.map((col, idx) => (
                                <th key={idx} style={{
                                    padding: '16px 24px',
                                    textAlign: col.align || 'left',
                                    fontSize: '11px',
                                    fontWeight: '700',
                                    textTransform: 'uppercase',
                                    color: '#f8fafc', // White text
                                    letterSpacing: '0.05em',
                                    borderBottom: '1px solid #334155', // Darker border
                                    whiteSpace: 'nowrap',
                                    position: col.sticky ? 'sticky' : 'relative',
                                    left: col.sticky ? 0 : 'auto',
                                    background: '#1e293b', // Header BG
                                    zIndex: col.sticky ? 25 : 20,
                                    minWidth: col.width || 'auto'
                                }}>
                                    {col.header}
                                </th>
                            ))}
                        </tr>
                    </thead>
                    <tbody style={{ background: 'var(--bg-card)' }}>
                        {loading ? (
                            <tr>
                                <td colSpan={columns.length} style={{ padding: '60px', textAlign: 'center' }}>
                                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '12px', color: '#94a3b8' }}>
                                        <Loader2 className="animate-spin" size={24} />
                                        <span style={{ fontSize: '13px', fontWeight: '500' }}>Loading data...</span>
                                    </div>
                                </td>
                            </tr>
                        ) : data.length === 0 ? (
                            <tr>
                                <td colSpan={columns.length} style={{ padding: '60px', textAlign: 'center', color: '#94a3b8', fontSize: '13px' }}>
                                    {emptyMessage}
                                </td>
                            </tr>
                        ) : (
                            data.map((row, rIdx) => (
                                <tr key={rIdx} className="group transition-colors duration-150 ease-in-out hover:bg-slate-50/80">
                                    {columns.map((col, cIdx) => (
                                        <td key={cIdx} style={{
                                            padding: '16px 24px',
                                            textAlign: col.align || 'left',
                                            fontSize: '13px',
                                            color: col.highlight ? '#0f172a' : '#334155',
                                            fontWeight: col.highlight || col.sticky ? '600' : '500',
                                            borderBottom: '1px solid #f1f5f9',
                                            whiteSpace: 'nowrap',
                                            position: col.sticky ? 'sticky' : 'relative',
                                            left: col.sticky ? 0 : 'auto',
                                            background: col.sticky ? 'inherit' : 'transparent', // Inherit from tr hover
                                            zIndex: col.sticky ? 10 : 'auto',
                                            fontFamily: col.mono ? 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace' : 'inherit'
                                        }}>
                                            {/* Sticky Cell Shadow Mask (Optional, for advanced styling) */}
                                            {col.sticky && (
                                                <div style={{
                                                    position: 'absolute', right: 0, top: 0, bottom: 0, width: '1px',
                                                    background: '#e2e8f0', // Border
                                                    boxShadow: '4px 0 8px -2px rgba(0,0,0,0.05)' // Subtle shadow to right
                                                }} />
                                            )}
                                            {col.render ? col.render(row) : row[col.accessor]}
                                        </td>
                                    ))}
                                </tr>
                            ))
                        )}
                    </tbody>
                </table>
            </div>
            {/* Optional Footer or Page Summary could go here */}
        </div>
    );
};

export default PremiumTable;
