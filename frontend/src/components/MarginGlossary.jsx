import React, { useState } from 'react';
import { Info } from 'lucide-react';

/*
 * ONE definition of the executive margin vocabulary, shared by every page
 * that shows it. Before 2026-09-02 each page footnote described net margin
 * in its own words (three of them omitted the PG fee, and were computing it
 * that way too). The backend definition lives in NetSpreadSql.java; these
 * strings are its plain-English mirror — change both together.
 */
export const MARGIN_GLOSSARY = {
    netMargin: 'Net Margin = MSF − interchange − scheme fee − PG (gateway) fee. The fee margin the bank keeps on card volume.',
    ancillary: 'Ancillary income = the acquirer\'s share of DCC revenue + POS/terminal rental. Booked from the DCC and rental feeds, not from transactions.',
    netSpread: 'Net Spread = Net Margin + DCC (acquirer share) + rental. The full acquiring economics of a merchant.',
    rescued: 'Rescued = negative on Net Margin, but zero or better once DCC and rental are added.',
    pct: 'Percentages are the figure ÷ settlement volume for the same period.',
};

export const GLOSSARY_TEXT = Object.values(MARGIN_GLOSSARY).join('\n');

/**
 * Small info affordance for page headers. Hover/focus shows the glossary;
 * `compact` renders only the icon, otherwise an "How margin is calculated"
 * label sits beside it. `light` is for dark mastheads.
 */
export default function MarginGlossaryHint({ compact = false, light = false, style }) {
    const [open, setOpen] = useState(false);
    const fg = light ? 'rgba(241,245,249,0.75)' : 'var(--text-secondary)';
    return (
        <span style={{ position: 'relative', display: 'inline-flex', alignItems: 'center', ...style }}
            onMouseEnter={() => setOpen(true)} onMouseLeave={() => setOpen(false)}>
            <button type="button" aria-label="How margin and spread are calculated"
                onFocus={() => setOpen(true)} onBlur={() => setOpen(false)}
                onClick={() => setOpen(o => !o)}
                style={{
                    display: 'inline-flex', alignItems: 'center', gap: 5, background: 'none',
                    border: `1px solid ${light ? 'rgba(241,245,249,0.25)' : 'var(--border)'}`,
                    borderRadius: 999, padding: compact ? 3 : '2px 9px 2px 6px', cursor: 'help',
                    color: fg, fontSize: 11, fontWeight: 600, lineHeight: 1.4,
                }}>
                <Info size={12} />
                {!compact && <span>How margin is calculated</span>}
            </button>
            {open && (
                <div role="tooltip" style={{
                    position: 'absolute', top: 'calc(100% + 6px)', left: 0, zIndex: 60,
                    width: 340, padding: '10px 12px', borderRadius: 10,
                    background: 'var(--bg-card, #fff)', color: 'var(--text)',
                    border: '1px solid var(--border)', boxShadow: 'var(--shadow-pop, 0 8px 24px rgba(2,8,23,0.18))',
                    fontSize: 12, lineHeight: 1.5, textAlign: 'left', fontWeight: 400, whiteSpace: 'normal',
                }}>
                    {Object.entries(MARGIN_GLOSSARY).map(([k, v]) => (
                        <div key={k} style={{ padding: '3px 0' }}>{v}</div>
                    ))}
                </div>
            )}
        </span>
    );
}
