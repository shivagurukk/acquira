import React from 'react';

/**
 * <BenchmarkRail> — the signature ornament of the ledger design.
 *
 * Renders under a KPI value: a 2px full-width hairline track with a teal
 * fill showing the metric's percentile against its peer or RM benchmark
 * (the backend's percentile_cont output), a 1px vertical tick at the
 * median, and a muted 11px mono label beneath, e.g. "68th pct vs peer group".
 *
 * Use ONLY where a real benchmark exists (forecasting / benchmarking
 * pages). Percentile is 0–100.
 */
const ordinal = (n) => {
    const v = Math.round(n);
    const rem10 = v % 10, rem100 = v % 100;
    if (rem10 === 1 && rem100 !== 11) return `${v}st`;
    if (rem10 === 2 && rem100 !== 12) return `${v}nd`;
    if (rem10 === 3 && rem100 !== 13) return `${v}rd`;
    return `${v}th`;
};

const BenchmarkRail = ({ percentile, benchmarkLabel = 'peer group', label }) => {
    if (percentile == null || Number.isNaN(Number(percentile))) return null;
    const pct = Math.max(0, Math.min(100, Number(percentile)));
    const text = label || `${ordinal(pct)} pct vs ${benchmarkLabel}`;

    return (
        <div aria-label={text} style={{ width: '100%' }}>
            <div style={{
                position: 'relative',
                height: 2,
                width: '100%',
                background: 'var(--border)',
            }}>
                {/* teal percentile fill */}
                <div style={{
                    position: 'absolute', left: 0, top: 0, bottom: 0,
                    width: `${pct}%`,
                    background: 'var(--primary)',
                    transition: 'width 150ms ease-out',
                }} />
                {/* 1px tick at the median */}
                <div style={{
                    position: 'absolute', left: '50%', top: -2, bottom: -2,
                    width: 1,
                    background: 'var(--text-muted)',
                }} />
            </div>
            <div style={{
                marginTop: 4,
                fontFamily: 'var(--font-mono)',
                fontVariantNumeric: 'tabular-nums',
                fontSize: 11,
                color: 'var(--text-muted)',
            }}>
                {text}
            </div>
        </div>
    );
};

export default BenchmarkRail;
