import React from 'react';
import { AlertTriangle, RefreshCw } from 'lucide-react';

/**
 * #10: React Error Boundary
 * Catches unhandled errors in child components and shows a friendly fallback.
 * Prevents full white-screen crashes.
 */
class ErrorBoundary extends React.Component {
    constructor(props) {
        super(props);
        this.state = { hasError: false, error: null, errorInfo: null };
    }

    static getDerivedStateFromError(error) {
        return { hasError: true, error };
    }

    componentDidCatch(error, errorInfo) {
        this.setState({ errorInfo });
        console.error('[ErrorBoundary] Caught error:', error, errorInfo);
    }

    handleReload = () => {
        this.setState({ hasError: false, error: null, errorInfo: null });
        window.location.reload();
    };

    handleGoBack = () => {
        this.setState({ hasError: false, error: null, errorInfo: null });
        window.location.href = '/dashboard';
    };

    render() {
        if (this.state.hasError) {
            return (
                <div style={{
                    minHeight: '100vh',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    background: 'var(--bg, #F9FAFB)',
                    fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, sans-serif",
                }}>
                    <div style={{
                        maxWidth: 480,
                        padding: 40,
                        background: 'var(--bg-card, #ffffff)',
                        borderRadius: 16,
                        boxShadow: '0 4px 24px rgba(0,0,0,0.12)',
                        textAlign: 'center',
                    }}>
                        <div style={{
                            width: 64, height: 64,
                            borderRadius: 16,
                            background: '#FEF3C7',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            margin: '0 auto 20px',
                        }}>
                            <AlertTriangle size={32} color="#D97706" />
                        </div>
                        <h2 style={{ fontSize: 22, fontWeight: 700, color: 'var(--text, #111827)', margin: '0 0 8px' }}>
                            Something went wrong
                        </h2>
                        <p style={{ fontSize: 14, color: 'var(--text-secondary, #6B7280)', margin: '0 0 24px', lineHeight: 1.5 }}>
                            An unexpected error occurred. This has been logged. You can try reloading the page or going back to the dashboard.
                        </p>
                        {this.state.error && (
                            <div style={{
                                background: '#FEF2F2',
                                border: '1px solid #FECACA',
                                borderRadius: 8,
                                padding: '10px 14px',
                                marginBottom: 20,
                                textAlign: 'left',
                                fontSize: 12,
                                color: '#991B1B',
                                maxHeight: 80,
                                overflow: 'auto',
                                fontFamily: 'monospace',
                            }}>
                                {this.state.error.toString()}
                            </div>
                        )}
                        <div style={{ display: 'flex', gap: 12, justifyContent: 'center' }}>
                            <button
                                onClick={this.handleReload}
                                style={{
                                    display: 'flex', alignItems: 'center', gap: 6,
                                    padding: '10px 20px',
                                    background: 'var(--primary)', color: 'white',
                                    border: 'none', borderRadius: 8,
                                    fontSize: 14, fontWeight: 600, cursor: 'pointer',
                                }}
                            >
                                <RefreshCw size={16} /> Reload Page
                            </button>
                            <button
                                onClick={this.handleGoBack}
                                style={{
                                    padding: '10px 20px',
                                    background: '#F3F4F6', color: '#374151',
                                    border: '1px solid #D1D5DB', borderRadius: 8,
                                    fontSize: 14, fontWeight: 600, cursor: 'pointer',
                                }}
                            >
                                Go to Dashboard
                            </button>
                        </div>
                    </div>
                </div>
            );
        }
        return this.props.children;
    }
}

export default ErrorBoundary;
