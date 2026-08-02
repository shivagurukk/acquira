import React, { createContext, useContext, useState, useCallback, useRef } from 'react';

const ToastContext = createContext(null);

let _externalShow = null;

/**
 * showToast(message, type, duration)
 * Call from OUTSIDE React (e.g. axios interceptors) without needing the hook.
 * type: 'success' | 'error' | 'warning' | 'info'
 */
export function showToast(message, type = 'info', duration = 4000) {
    if (_externalShow) _externalShow(message, type, duration);
    else console.warn('[Toast] Provider not mounted yet:', message);
}

const ICONS = {
    success: '✓',
    error:   '✕',
    warning: '⚠',
    info:    'ℹ',
};

// Resolved through the theme tokens so toasts follow light/dark like everything else.
const COLORS = {
    success: { bg: 'var(--success-bg)', border: 'var(--success)', text: 'var(--success-text)', icon: 'var(--success)' },
    error:   { bg: 'var(--danger-bg)',  border: 'var(--danger)',  text: 'var(--danger-text)',  icon: 'var(--danger)' },
    warning: { bg: 'var(--warning-bg)', border: 'var(--warning)', text: 'var(--warning-text)', icon: 'var(--warning)' },
    info:    { bg: 'var(--info-bg)',    border: 'var(--info)',    text: 'var(--info-text)',    icon: 'var(--info)' },
};

export const ToastProvider = ({ children }) => {
    const [toasts, setToasts] = useState([]);
    const counterRef = useRef(0);

    const show = useCallback((message, type = 'info', duration = 4000) => {
        const id = ++counterRef.current;
        setToasts(prev => [...prev, { id, message, type, duration }]);
        if (duration > 0) {
            setTimeout(() => {
                setToasts(prev => prev.filter(t => t.id !== id));
            }, duration);
        }
        return id;
    }, []);

    const dismiss = useCallback((id) => {
        setToasts(prev => prev.filter(t => t.id !== id));
    }, []);

    // Expose to external callers (axios interceptors etc.)
    _externalShow = show;

    return (
        <ToastContext.Provider value={{ show, dismiss }}>
            {children}
            <div role="status" aria-live="polite" style={{
                position: 'fixed',
                bottom: 24,
                right: 24,
                zIndex: 9999,
                display: 'flex',
                flexDirection: 'column',
                gap: 8,
                pointerEvents: 'none',
            }}>
                {toasts.map(toast => {
                    const c = COLORS[toast.type] || COLORS.info;
                    return (
                        <div
                            key={toast.id}
                            style={{
                                display: 'flex',
                                alignItems: 'flex-start',
                                gap: 10,
                                padding: '12px 14px',
                                borderRadius: 10,
                                border: `1px solid ${c.border}`,
                                background: c.bg,
                                color: c.text,
                                fontSize: 13,
                                fontWeight: 500,
                                fontFamily: 'var(--font-sans, system-ui)',
                                maxWidth: 360,
                                minWidth: 220,
                                pointerEvents: 'all',
                                animation: 'toastIn 0.22s cubic-bezier(0.34,1.56,0.64,1)',
                                boxShadow: 'var(--shadow-lg)',
                                lineHeight: 1.4,
                            }}
                        >
                            <span style={{
                                width: 20, height: 20, borderRadius: 5,
                                background: 'color-mix(in srgb, ' + c.icon + ' 18%, transparent)',
                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                color: c.icon, fontWeight: 700, fontSize: 11, flexShrink: 0, marginTop: 1,
                            }}>
                                {ICONS[toast.type]}
                            </span>
                            <span style={{ flex: 1 }}>{toast.message}</span>
                            <button
                                onClick={() => dismiss(toast.id)}
                                aria-label="Dismiss notification"
                                onMouseEnter={e => { e.currentTarget.style.opacity = 1; }}
                                onMouseLeave={e => { e.currentTarget.style.opacity = 0.5; }}
                                style={{
                                    background: 'none', border: 'none', cursor: 'pointer',
                                    color: c.text, opacity: 0.5, fontSize: 14,
                                    padding: 0, lineHeight: 1, flexShrink: 0,
                                    transition: 'opacity 0.15s ease',
                                }}
                            >×</button>
                        </div>
                    );
                })}
            </div>
            <style>{`
                @keyframes toastIn {
                    from { opacity: 0; transform: translateY(12px) scale(0.95); }
                    to   { opacity: 1; transform: translateY(0) scale(1); }
                }
            `}</style>
        </ToastContext.Provider>
    );
};

export const useToast = () => {
    const ctx = useContext(ToastContext);
    if (!ctx) throw new Error('useToast must be used inside ToastProvider');
    return ctx;
};

export default ToastContext;
