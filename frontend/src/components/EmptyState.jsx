import React from 'react';
import { useNavigate } from 'react-router-dom';
import {
    BarChart3, FileText, Upload, Search, Users,
    Database, ArrowRight, RefreshCw
} from 'lucide-react';

const VARIANTS = {
    chart:    { icon: BarChart3, title: 'No chart data yet',     message: 'Upload transaction data to see trends and analytics here.' },
    table:    { icon: FileText,  title: 'No records found',      message: 'Try adjusting your filters or date range.' },
    upload:   { icon: Upload,    title: 'No files uploaded',     message: 'Upload an Excel or CSV file to start processing.' },
    search:   { icon: Search,    title: 'No results',           message: 'Try a different search term or clear your filters.' },
    merchant: { icon: Users,     title: 'No merchant data',     message: 'Merchant data will appear after processing transactions.' },
    data:     { icon: Database,  title: 'No data available',    message: 'Data will appear here once available.' },
    generic:  { icon: FileText,  title: 'Nothing here yet',     message: 'Content will appear once data is available.' },
};

const EmptyState = ({
    icon: IconOverride,
    title: titleOverride,
    message: messageOverride,
    action,
    variant = 'generic',
    compact = false,
}) => {
    const navigate = useNavigate();
    const cfg  = VARIANTS[variant] || VARIANTS.generic;
    const Icon = IconOverride || cfg.icon;
    const title   = titleOverride   || cfg.title;
    const message = messageOverride || cfg.message;

    const handleAction = () => {
        if (action?.onClick) action.onClick();
        else if (action?.to) navigate(action.to);
    };

    return (
        <div style={{
            display: 'flex', flexDirection: 'column',
            alignItems: 'center', justifyContent: 'center',
            padding: compact ? '32px 20px' : '56px 24px',
            textAlign: 'center',
        }}>
            {/* Icon */}
            <div style={{
                width: compact ? 48 : 56,
                height: compact ? 48 : 56,
                borderRadius: '50%',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                background: 'var(--bg-subtle, #f3f4f6)',
                border: '1px solid var(--border, #e5e7eb)',
                marginBottom: compact ? 12 : 16,
            }}>
                <Icon
                    size={compact ? 20 : 24}
                    style={{ color: 'var(--text-muted, #9ca3af)' }}
                    strokeWidth={1.5}
                />
            </div>

            {/* Title */}
            <p style={{
                fontSize: compact ? '0.88rem' : '0.95rem',
                fontWeight: 600,
                color: 'var(--text, #111827)',
                margin: '0 0 6px',
                lineHeight: 1.3,
            }}>
                {title}
            </p>

            {/* Message */}
            <p style={{
                fontSize: compact ? '0.78rem' : '0.84rem',
                color: 'var(--text-muted, #9ca3af)',
                maxWidth: 280,
                lineHeight: 1.6,
                margin: action ? '0 0 18px' : '0',
            }}>
                {message}
            </p>

            {/* CTA */}
            {action && (
                <button
                    onClick={handleAction}
                    style={{
                        display: 'inline-flex', alignItems: 'center', gap: 6,
                        padding: compact ? '7px 16px' : '9px 20px',
                        fontSize: compact ? '0.78rem' : '0.84rem',
                        fontWeight: 600,
                        color: 'var(--brand, #2563eb)',
                        background: 'var(--brand-50, #eff6ff)',
                        border: '1px solid rgba(37,99,235,0.15)',
                        borderRadius: '10px',
                        cursor: 'pointer',
                        transition: 'all 0.15s ease',
                        fontFamily: 'inherit',
                    }}
                    onMouseEnter={e => {
                        e.currentTarget.style.background = 'rgba(37,99,235,0.1)';
                        e.currentTarget.style.borderColor = 'rgba(37,99,235,0.3)';
                    }}
                    onMouseLeave={e => {
                        e.currentTarget.style.background = 'var(--brand-50, #eff6ff)';
                        e.currentTarget.style.borderColor = 'rgba(37,99,235,0.15)';
                    }}
                >
                    {action.label}
                    <ArrowRight size={13} strokeWidth={2.5} />
                </button>
            )}
        </div>
    );
};

export default EmptyState;
