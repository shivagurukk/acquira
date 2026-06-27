import React from 'react';

/**
 * Acquira loaders — single source of truth for loading visuals.
 *
 *   <PageLoader />       Full-area branded loader. Used as the Suspense
 *                        fallback in App.jsx (first visit to a lazy route)
 *                        and anywhere a whole screen is still resolving.
 *   <Spinner size={…}/>  Small inline spinner for buttons / sections.
 *   <ContentLoader />    Skeleton-shimmer placeholder a page can render
 *                        while its own data fetch is in flight.
 *
 * The global top progress bar (every API call, all pages) lives in
 * contexts/LoadingContext.jsx and is wired through the axios interceptors —
 * pages do not need to render anything for that one.
 *
 * All colours come from the CSS custom properties published by
 * ThemeContext, so these react to dark mode automatically.
 */

/* Injected once — keyframes shared by every loader below. */
const LoaderKeyframes = () => (
  <style>{`
    @keyframes acq-spin   { to { transform: rotate(360deg); } }
    @keyframes acq-pulse  { 0%,100% { opacity: 0.35; } 50% { opacity: 1; } }
    @keyframes acq-shimmer{ 0% { background-position: -468px 0; } 100% { background-position: 468px 0; } }
    @keyframes acq-fade   { from { opacity: 0; } to { opacity: 1; } }
  `}</style>
);

/** Small inline spinner. */
export const Spinner = ({ size = 20, stroke = 2.5, color }) => (
  <span
    aria-hidden="true"
    style={{
      display: 'inline-block',
      width: size,
      height: size,
      border: `${stroke}px solid var(--border, #E5E7EB)`,
      borderTopColor: color || 'var(--accent, #1E3A8A)',
      borderRadius: '50%',
      animation: 'acq-spin 0.7s linear infinite',
      verticalAlign: 'middle',
    }}
  >
    <LoaderKeyframes />
  </span>
);

/**
 * Full-area branded loader.
 * Concentric ring + spinning accent arc + pulsing label.
 */
export const PageLoader = ({ label = 'Loading', minHeight = '60vh' }) => (
  <div
    role="status"
    aria-live="polite"
    style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      minHeight,
      width: '100%',
      animation: 'acq-fade 0.2s ease',
    }}
  >
    <LoaderKeyframes />
    <div style={{ textAlign: 'center' }}>
      <div style={{ position: 'relative', width: 52, height: 52, margin: '0 auto 16px' }}>
        {/* track */}
        <div
          style={{
            position: 'absolute', inset: 0,
            border: '4px solid var(--border-light, #F3F4F6)',
            borderRadius: '50%',
          }}
        />
        {/* spinning arc */}
        <div
          style={{
            position: 'absolute', inset: 0,
            border: '4px solid transparent',
            borderTopColor: 'var(--accent, #1E3A8A)',
            borderRightColor: 'var(--accent, #1E3A8A)',
            borderRadius: '50%',
            animation: 'acq-spin 0.85s cubic-bezier(0.5,0.1,0.5,0.9) infinite',
          }}
        />
        {/* centre dot */}
        <div
          style={{
            position: 'absolute', top: '50%', left: '50%',
            width: 8, height: 8, marginTop: -4, marginLeft: -4,
            background: 'var(--accent, #1E3A8A)',
            borderRadius: '50%',
            animation: 'acq-pulse 1.2s ease-in-out infinite',
          }}
        />
      </div>
      <div
        style={{
          fontSize: 13,
          fontWeight: 500,
          letterSpacing: 0.2,
          color: 'var(--text-secondary, #6B7280)',
          fontFamily: 'var(--font-sans, Inter, system-ui, sans-serif)',
          animation: 'acq-pulse 1.4s ease-in-out infinite',
        }}
      >
        {label}
      </div>
    </div>
  </div>
);

/** One shimmer line. */
const SkeletonLine = ({ width = '100%', height = 14, radius = 6 }) => (
  <div
    style={{
      width,
      height,
      borderRadius: radius,
      background:
        'linear-gradient(90deg, var(--bg-subtle,#F3F4F6) 8%, var(--bg-hover,#E9ECF1) 18%, var(--bg-subtle,#F3F4F6) 33%)',
      backgroundSize: '800px 100%',
      animation: 'acq-shimmer 1.3s linear infinite',
    }}
  />
);

/**
 * Skeleton placeholder a page can drop in while its data loads, e.g.:
 *   if (loading) return <ContentLoader rows={6} />;
 * Looks like content arriving rather than a blank card.
 */
export const ContentLoader = ({ rows = 5, cards = 0 }) => (
  <div style={{ width: '100%', animation: 'acq-fade 0.2s ease' }}>
    <LoaderKeyframes />
    {cards > 0 && (
      <div style={{ display: 'flex', gap: 16, marginBottom: 24, flexWrap: 'wrap' }}>
        {Array.from({ length: cards }).map((_, i) => (
          <div
            key={i}
            style={{
              flex: '1 1 180px',
              minWidth: 180,
              padding: 20,
              borderRadius: 12,
              border: '1px solid var(--border, #E5E7EB)',
              background: 'var(--bg-card, #FFFFFF)',
              display: 'flex', flexDirection: 'column', gap: 12,
            }}
          >
            <SkeletonLine width="55%" height={12} />
            <SkeletonLine width="80%" height={26} />
          </div>
        ))}
      </div>
    )}
    <div
      style={{
        padding: 20,
        borderRadius: 12,
        border: '1px solid var(--border, #E5E7EB)',
        background: 'var(--bg-card, #FFFFFF)',
        display: 'flex', flexDirection: 'column', gap: 14,
      }}
    >
      {Array.from({ length: rows }).map((_, i) => (
        <SkeletonLine key={i} width={`${100 - (i % 3) * 12}%`} />
      ))}
    </div>
  </div>
);

export default PageLoader;
