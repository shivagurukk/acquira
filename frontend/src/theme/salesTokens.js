// ─── Sales-suite design tokens ───────────────────────────────
// Single source of truth for the Sales pages (Leaderboard, Team/Country
// management, Agent Directory, Hierarchy Tree). Every colour routes through a
// CSS variable with a light-mode fallback so the pages adapt cleanly under
// html.dark + ThemeContext instead of baking in hardcoded hex.
//
// Import: import { T, CARD, BTN, cardSx, statusChip } from '../../theme/salesTokens';

export const T = {
  // surfaces
  card:       'var(--bg-card, #FFFFFF)',
  bg:         'var(--bg, #F5F6F8)',
  subtle:     'var(--bg-subtle, #F8F9FA)',
  hover:      'var(--bg-hover, #F1F2F4)',
  // borders
  border:     'var(--border, #E4E7EC)',
  borderLt:   'var(--border-light, #E4E7EC)',
  // text
  text:       'var(--text, #191D24)',
  textSec:    'var(--text-secondary, #5C6675)',
  textMut:    'var(--text-muted, #5C6675)',
  // brand
  brand:      'var(--brand, #3F63B0)',
  brandAlt:   'var(--primary-soft, #5578C4)',
  // status (foreground)
  success:    'var(--success, #3F63B0)',
  successDk:  'var(--chart-1, #263C6E)',
  warning:    'var(--warning, #8C5E12)',
  danger:     'var(--danger, #B3382C)',
  info:       'var(--info, #64748B)',
  purple:     'var(--projected, #64748B)',
  // status (subtle backgrounds) — fall back to the light-mode tints
  successBg:  'var(--success-bg, #EAF0F9)',
  successTx:  'var(--success-text, #3F63B0)',
  successCh:  'var(--wash, #EAF0F9)',
  warningBg:  'var(--warning-bg, #F5EDDB)',
  warningTx:  'var(--warning-text, #8C5E12)',
  warningCh:  'var(--warning-bg, #F5EDDB)',
  dangerBg:   'var(--danger-bg, #F8E7E5)',
  dangerTx:   'var(--danger-text, #B3382C)',
  dangerCh:   'var(--danger-bg, #F8E7E5)',
  infoBg:     'var(--info-bg, #EEF0F3)',
  infoTx:     'var(--info-text, #64748B)',
  infoCh:     'var(--info-bg, #EEF0F3)',
  indigoBg:   'var(--info-bg, #EEF0F3)',
  indigoTx:   'var(--info-text, #64748B)',
  purpleBg:   'var(--info-bg, #EEF0F3)',
  // radii / shadow
  radius:     'var(--radius-md, 8px)',
  radiusLg:   'var(--radius-lg, 12px)',
  shadowXs:   'var(--shadow-xs, 0 1px 2px rgba(15,35,80,.05))',
};

// Animated border sweep — mirrors cardSweep in utils/chartConfig.jsx
// (--dxa + dxBorderSweep live in index.css).
const SWEEP = {
  background: `      radial-gradient(140% 90% at 50% 0%,
        color-mix(in srgb, var(--primary) var(--dxg, 6%), transparent) 0%,
        transparent 60%) padding-box,
      var(--dx-card-grid),
      conic-gradient(from var(--dxa),
        ${T.border} 0deg, ${T.border} 280deg,
        color-mix(in srgb, var(--dx-sweep, var(--primary)) 40%, ${T.border}) 310deg,
        var(--dx-sweep, var(--primary)) 332deg,
        ${T.border} 352deg) border-box`,
  border: '2px solid transparent',
  // Inline styles cannot carry a media query, so reduced motion is
  // checked here instead of in CSS.
  animation: (typeof window !== 'undefined'
    && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches)
    ? 'none' : 'dxBorderSweep 6s linear infinite, dxGridPulse 5s ease-in-out infinite',
};

// Plain-inline-style card (used by Leaderboard + Hierarchy which are not MUI).
export const CARD = {
  ...SWEEP,
  borderRadius: 14,
  padding: 24,
  boxShadow: T.shadowXs,
};

// Plain-inline-style button factory.
export const BTN = (bg = T.brand, fg = '#fff') => ({
  display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 14px',
  borderRadius: 8, background: bg, color: fg, border: 'none', cursor: 'pointer',
  fontSize: 13, fontWeight: 600,
});

// MUI sx object for the standard bordered card used across the management pages.
export const cardSx = {
  ...SWEEP, boxShadow: 'none', borderRadius: 3,
};

// Status-chip colour pairs {bg, color} keyed by a small vocabulary.
export const statusChip = {
  mapped:   { bg: T.infoCh,    color: T.infoTx },
  unmapped: { bg: T.warningCh, color: T.warningTx },
  active:   { bg: T.successCh, color: T.successTx },
  inactive: { bg: T.dangerCh,  color: T.dangerTx },
};
