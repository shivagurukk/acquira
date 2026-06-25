import React, { useState, useEffect } from 'react';

const SHORTCUTS = [
    { key: '⌘ K', desc: 'Focus sidebar search', group: 'Navigation' },
    { key: '?',   desc: 'Show this panel',       group: 'Navigation' },
    { key: 'Esc', desc: 'Close panel / clear search', group: 'Navigation' },
    { key: 'G B', desc: 'Go → Business dashboard',    group: 'Go to' },
    { key: 'G E', desc: 'Go → Executive dashboard',   group: 'Go to' },
    { key: 'G U', desc: 'Go → Upload page',            group: 'Go to' },
    { key: 'G M', desc: 'Go → Merchant analytics',    group: 'Go to' },
    { key: 'G L', desc: 'Go → Batch logs',             group: 'Go to' },
    { key: 'R',   desc: 'Refresh current report',     group: 'Actions' },
    { key: 'E',   desc: 'Export current data',        group: 'Actions' },
    { key: 'F',   desc: 'Toggle filter panel',        group: 'Actions' },
];
const GROUPS = [...new Set(SHORTCUTS.map(s => s.group))];

const Kbd = ({ k }) => (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 3 }}>
        {k.split(' ').map((part, i) => (
            <kbd key={i} style={{
                fontFamily: 'var(--font-mono, monospace)',
                fontSize: 11, fontWeight: 600,
                padding: '2px 7px', borderRadius: 5,
                background: 'var(--color-background-secondary)',
                border: '0.5px solid var(--color-border-secondary)',
                color: 'var(--color-text-primary)',
            }}>{part}</kbd>
        ))}
    </span>
);

const ShortcutsPanel = ({ navigate }) => {
    const [open, setOpen] = useState(false);

    useEffect(() => {
        const GOTO = { b: '/business/dashboard', e: '/dashboard', u: '/upload', m: '/business/merchant-analytics', l: '/ops/batch-logs' };
        let gMode = false;
        const h = (e) => {
            const tag = document.activeElement?.tagName;
            if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;

            if (e.key === '?') { setOpen(v => !v); return; }
            if (e.key === 'Escape') { setOpen(false); gMode = false; return; }

            if (gMode) {
                const dest = GOTO[e.key.toLowerCase()];
                if (dest && navigate) navigate(dest);
                gMode = false;
                return;
            }
            if (e.key.toLowerCase() === 'g') { gMode = true; setTimeout(() => { gMode = false; }, 2000); }
        };
        window.addEventListener('keydown', h);
        return () => window.removeEventListener('keydown', h);
    }, [navigate]);

    if (!open) return null;

    return (
        <div style={{
            position: 'fixed', inset: 0, zIndex: 9000,
            background: 'rgba(0,0,0,0.45)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            padding: 20,
        }} onClick={() => setOpen(false)}>
            <div onClick={e => e.stopPropagation()} style={{
                background: 'var(--color-background-primary)',
                border: '0.5px solid var(--color-border-tertiary)',
                borderRadius: 14, width: '100%', maxWidth: 480,
                boxShadow: '0 20px 48px rgba(0,0,0,0.2)',
                overflow: 'hidden',
            }}>
                <div style={{ padding: '14px 18px', borderBottom: '0.5px solid var(--color-border-tertiary)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--color-text-primary)' }}>Keyboard shortcuts</span>
                    <button onClick={() => setOpen(false)} style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: 18, color: 'var(--color-text-tertiary)', padding: 0, lineHeight: 1 }}>×</button>
                </div>
                <div style={{ padding: '12px 18px 18px', maxHeight: '60vh', overflowY: 'auto' }}>
                    {GROUPS.map(group => (
                        <div key={group} style={{ marginBottom: 16 }}>
                            <div style={{ fontSize: 10, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.07em', color: 'var(--color-text-tertiary)', marginBottom: 8 }}>{group}</div>
                            {SHORTCUTS.filter(s => s.group === group).map(s => (
                                <div key={s.key} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '6px 0', borderBottom: '0.5px solid var(--color-border-tertiary)' }}>
                                    <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>{s.desc}</span>
                                    <Kbd k={s.key} />
                                </div>
                            ))}
                        </div>
                    ))}
                </div>
                <div style={{ padding: '10px 18px', borderTop: '0.5px solid var(--color-border-tertiary)', fontSize: 11, color: 'var(--color-text-tertiary)', textAlign: 'center' }}>
                    Press <Kbd k="?" /> to toggle · <Kbd k="Esc" /> to close
                </div>
            </div>
        </div>
    );
};

export default ShortcutsPanel;
