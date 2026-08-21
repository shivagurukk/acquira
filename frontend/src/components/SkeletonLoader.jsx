import React from 'react';

/* ─── Shimmer pulse CSS ─────────────────────────────────────── */
const shimmerStyle = {
    background: 'linear-gradient(90deg, var(--bg-subtle) 25%, color-mix(in srgb, var(--primary) 12%, var(--bg-subtle)) 50%, var(--bg-subtle) 75%)',
    backgroundSize: '400% 100%',
    animation: 'shimmer 1.8s ease-in-out infinite',
    borderRadius: 6,
};

const SHIMMER_KEYFRAMES = `
    @keyframes shimmer { 0%{background-position:200% 0} 100%{background-position:-200% 0} }
    @media (prefers-reduced-motion: reduce) { [style*="shimmer"] { animation: none; } }
`;

const Bone = ({ w = '100%', h = 14, r = 6, style = {} }) => (
    <div style={{ ...shimmerStyle, width: w, height: h, borderRadius: r, flexShrink: 0, ...style }} />
);

/* ─── KPI skeleton ──────────────────────────────────────────── */
const KpiSkeleton = () => (
    <div style={{
        flex: 1, minWidth: 160, padding: '24px',
        borderRadius: 'var(--radius-lg,14px)',
        background: 'var(--bg-card,#fff)',
        border: '1px solid var(--border,#e5e7eb)',
    }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
            <Bone w={40} h={40} r={12} />
            <Bone w={48} h={22} r={8} />
        </div>
        <Bone w="55%" h={28} r={6} style={{ marginBottom: 10 }} />
        <Bone w="35%" h={14} r={4} />
    </div>
);

/* ─── Chart skeleton ────────────────────────────────────────── */
const ChartSkeleton = ({ height = 320 }) => (
    <div style={{
        background: 'var(--bg-card,#fff)',
        border: '1px solid var(--border,#e5e7eb)',
        borderRadius: 'var(--radius-lg,14px)',
        padding: '24px',
    }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 20 }}>
            <div>
                <Bone w={140} h={16} r={6} style={{ marginBottom: 8 }} />
                <Bone w={200} h={12} r={4} />
            </div>
            <div style={{ display: 'flex', gap: 10 }}>
                <Bone w={52} h={12} r={6} />
                <Bone w={52} h={12} r={6} />
            </div>
        </div>
        <Bone w="100%" h={height - 90} r={10} />
    </div>
);

/* ─── Table skeleton ────────────────────────────────────────── */
const TableSkeleton = ({ rows = 6, cols = 5 }) => {
    const widths = ['28%', '18%', '14%', '14%', '14%', '12%'];
    return (
        <div style={{
            background: 'var(--bg-card,#fff)',
            border: '1px solid var(--border,#e5e7eb)',
            borderRadius: 'var(--radius-lg,14px)',
            overflow: 'hidden',
        }}>
            <div style={{
                display: 'flex', gap: 16, padding: '14px 20px',
                background: 'var(--bg-subtle,#f3f4f6)',
                borderBottom: '1px solid var(--border,#e5e7eb)',
            }}>
                {Array.from({ length: cols }).map((_, i) => (
                    <Bone key={i} w={widths[i] || '12%'} h={12} r={4} />
                ))}
            </div>
            {Array.from({ length: rows }).map((_, ri) => (
                <div key={ri} style={{
                    display: 'flex', gap: 16, padding: '14px 20px',
                    borderBottom: ri < rows - 1 ? '1px solid var(--border-light,#f3f4f6)' : 'none',
                }}>
                    {Array.from({ length: cols }).map((_, ci) => (
                        <Bone key={ci} w={widths[ci] || '12%'} h={14} r={4} />
                    ))}
                </div>
            ))}
        </div>
    );
};

/* ─── Main Loader ───────────────────────────────────────────── */
const SkeletonLoader = ({ variant = 'page', ...props }) => {
    switch (variant) {
        case 'kpi-row':
            return (
                <>
                    <style>{SHIMMER_KEYFRAMES}</style>
                    <div style={{ display: 'flex', gap: 16, marginBottom: 28 }}>
                        {Array.from({ length: props.count || 5 }).map((_, i) => <KpiSkeleton key={i} />)}
                    </div>
                </>
            );
        case 'chart':
            return (
                <>
                    <style>{SHIMMER_KEYFRAMES}</style>
                    <ChartSkeleton height={props.height || 320} />
                </>
            );
        case 'table':
            return (
                <>
                    <style>{SHIMMER_KEYFRAMES}</style>
                    <TableSkeleton rows={props.rows || 6} cols={props.cols || 5} />
                </>
            );
        default:
            return (
                <>
                    <style>{SHIMMER_KEYFRAMES}</style>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 20, padding: '28px' }}>
                        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px,1fr))', gap: 16 }}>
                            {Array.from({ length: 5 }).map((_, i) => <KpiSkeleton key={i} />)}
                        </div>
                        <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 16 }}>
                            <ChartSkeleton height={320} />
                            <ChartSkeleton height={320} />
                        </div>
                        <TableSkeleton rows={5} cols={5} />
                    </div>
                </>
            );
    }
};

export default SkeletonLoader;
export { KpiSkeleton, ChartSkeleton, TableSkeleton, Bone };
