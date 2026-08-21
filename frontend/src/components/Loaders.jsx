import React from 'react';

/**
 * Acquira loaders — single source of truth for loading visuals.
 *
 *   <PageLoader />       Full-area branded loader. Used as the Suspense
 *                        fallback in App.jsx (first visit to a lazy route)
 *                        and anywhere a whole screen is still resolving.
 *   <SectionLoader />    Full centered branded loader that REPLACES a
 *                        section's content while its data is (re)fetching —
 *                        the "in-between" state loader.
 *   <Spinner size={…}/>  Small inline spinner for buttons / sections.
 *   <ContentLoader />    Skeleton-shimmer placeholder a page can render
 *                        while its own data fetch is in flight.
 *
 * Signature mark: the "data pulse" — equalizer-style chart bars breathing
 * behind an ECG pulse line that continuously draws itself across the tile.
 * Reads as live transaction data arriving, not a generic spinner.
 *
 * The global top progress bar (every API call, all pages) lives in
 * contexts/LoadingContext.jsx and is wired through the axios interceptors —
 * pages do not need to render anything for that one.
 *
 * All colours come from the CSS custom properties published by
 * ThemeContext, so these react to dark mode automatically. Every animation
 * is disabled under prefers-reduced-motion.
 */

/* Injected once — keyframes shared by every loader below. */
const LoaderKeyframes = () => (
  <style>{`
    @keyframes acq-spin    { to { transform: rotate(360deg); } }
    @keyframes acq-pulse   { 0%,100% { opacity: 0.35; } 50% { opacity: 1; } }
    @keyframes acq-shimmer { 0% { background-position: -468px 0; } 100% { background-position: 468px 0; } }
    @keyframes acq-fade    { from { opacity: 0; } to { opacity: 1; } }
    @keyframes acq-bar {
      0%, 100% { transform: scaleY(0.35); opacity: 0.45; }
      50%      { transform: scaleY(1);    opacity: 1; }
    }
    @keyframes acq-trace {
      0%   { stroke-dashoffset: 180; opacity: 0; }
      8%   { opacity: 1; }
      55%  { stroke-dashoffset: 0;   opacity: 1; }
      75%  { stroke-dashoffset: 0;   opacity: 1; }
      100% { stroke-dashoffset: -180; opacity: 0; }
    }
    @keyframes acq-tile-glow {
      0%, 100% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--accent, #1E3A8A) 0%, transparent); }
      50%      { box-shadow: 0 0 24px 2px color-mix(in srgb, var(--accent, #1E3A8A) 22%, transparent); }
    }
    @keyframes acq-ellipsis {
      0%   { content: ''; }
      25%  { content: '.'; }
      50%  { content: '..'; }
      75%  { content: '...'; }
    }
    .acq-loader-label::after {
      display: inline-block;
      width: 1.2em;
      text-align: left;
      content: '';
      animation: acq-ellipsis 1.6s steps(1) infinite;
    }
    @media (prefers-reduced-motion: reduce) {
      .acq-anim, .acq-anim * { animation: none !important; }
      .acq-loader-label::after { content: '…'; animation: none; }
    }
  `}</style>
);

/** Small inline spinner. */
export const Spinner = ({ size = 20, stroke = 2.5, color }) => (
  <span
    aria-hidden="true"
    className="acq-anim"
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
 * The "data pulse" mark.
 *
 * A rounded tile framing five equalizer bars that breathe on a staggered
 * rhythm, while an ECG-style pulse line repeatedly draws itself across the
 * tile with a soft glow. Colour + size are token-driven.
 */
export const PulseMark = ({ size = 64, color }) => {
  const accent = color || 'var(--accent, #1E3A8A)';
  const barXs = [10, 21, 32, 43, 54];
  const barHs = [22, 34, 44, 30, 38]; // full heights; scaleY animates them
  return (
    <div
      className="acq-anim"
      aria-hidden="true"
      style={{
        width: size,
        height: size,
        borderRadius: Math.round(size * 0.22),
        border: '1px solid var(--border, #E5E7EB)',
        background: 'var(--bg-card, #FFFFFF)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        animation: 'acq-tile-glow 2.4s ease-in-out infinite',
      }}
    >
      <LoaderKeyframes />
      <svg width={size * 0.82} height={size * 0.82} viewBox="0 0 64 64" fill="none">
        {/* breathing chart bars */}
        {barXs.map((x, i) => (
          <rect
            key={i}
            className="acq-anim"
            x={x - 3}
            y={54 - barHs[i]}
            width={6}
            height={barHs[i]}
            rx={3}
            fill={accent}
            opacity={0.35}
            style={{
              transformOrigin: `${x}px 54px`,
              animation: 'acq-bar 1.5s ease-in-out infinite',
              animationDelay: `${i * 0.14}s`,
            }}
          />
        ))}
        {/* ECG pulse line drawing itself across the bars */}
        <polyline
          className="acq-anim"
          points="2,34 16,34 22,20 30,46 38,12 46,38 50,30 62,30"
          stroke={accent}
          strokeWidth="2.75"
          strokeLinecap="round"
          strokeLinejoin="round"
          style={{
            strokeDasharray: 180,
            strokeDashoffset: 180,
            animation: 'acq-trace 2.4s cubic-bezier(0.4, 0, 0.2, 1) infinite',
            filter: 'drop-shadow(0 0 4px color-mix(in srgb, var(--accent, #1E3A8A) 60%, transparent))',
          }}
        />
      </svg>
    </div>
  );
};

/** Shared label under the marks — pulsing text with animated ellipsis. */
const LoaderLabel = ({ children }) => (
  <div
    className="acq-anim acq-loader-label"
    style={{
      fontSize: 13,
      fontWeight: 500,
      letterSpacing: 0.2,
      color: 'var(--text-secondary, #6B7280)',
      fontFamily: 'var(--font-sans, Inter, system-ui, sans-serif)',
      animation: 'acq-pulse 1.6s ease-in-out infinite',
    }}
  >
    {children}
  </div>
);

/**
 * Full centered branded loader — the "in-between" state loader.
 * Drop it in place of a section's content while a (re)fetch is running:
 *
 *   if (loading) return <SectionLoader label="Loading merchants…" />;
 *
 * Fills its container (minHeight default), centers the data-pulse mark
 * over a pulsing label. Card-framed by default so it reads as a deliberate
 * loading panel; pass framed={false} to drop the border/background.
 */
export const SectionLoader = ({
  label = 'Loading',
  minHeight = '52vh',
  size = 64,
  framed = true,
}) => (
  <div
    role="status"
    aria-live="polite"
    aria-busy="true"
    className="acq-anim"
    style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      minHeight,
      width: '100%',
      animation: 'acq-fade 0.2s ease',
      ...(framed
        ? {
            border: '1px solid var(--border, #E5E7EB)',
            borderRadius: 'var(--radius-lg, 14px)',
            background: 'var(--bg-card, #FFFFFF)',
            boxShadow: 'var(--shadow-sm, 0 1px 2px rgba(15,23,42,0.04))',
          }
        : {}),
    }}
  >
    <LoaderKeyframes />
    <div style={{ textAlign: 'center' }}>
      <div style={{ margin: '0 auto 18px', width: size, height: size }}>
        <PulseMark size={size} />
      </div>
      <LoaderLabel>{label}</LoaderLabel>
    </div>
  </div>
);

/**
 * Full-area branded loader — the data-pulse mark over a pulsing label.
 * Pass overlay to cover the whole viewport (route-level boots).
 */
export const PageLoader = ({ label = 'Loading', minHeight = '60vh', overlay = false }) => (
  <div
    role="status"
    aria-live="polite"
    className="acq-anim"
    style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      width: '100%',
      animation: 'acq-fade 0.2s ease',
      ...(overlay
        ? {
            position: 'fixed',
            inset: 0,
            zIndex: 9999,
            background: 'var(--bg, #F9FAFB)',
          }
        : { minHeight }),
    }}
  >
    <LoaderKeyframes />
    <div style={{ textAlign: 'center' }}>
      <div style={{ margin: '0 auto 16px', width: 64, height: 64 }}>
        <PulseMark size={64} />
      </div>
      <LoaderLabel>{label}</LoaderLabel>
    </div>
  </div>
);

/** One shimmer line. */
const SkeletonLine = ({ width = '100%', height = 14, radius = 6 }) => (
  <div
    className="acq-anim"
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
  <div className="acq-anim" style={{ width: '100%', animation: 'acq-fade 0.2s ease' }}>
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
