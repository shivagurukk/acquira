// ─── Sales-suite design tokens ───────────────────────────────
// Single source of truth for the Sales pages (Leaderboard, Team/Country
// management, Agent Directory, Hierarchy Tree). Every colour routes through a
// CSS variable with a light-mode fallback so the pages adapt cleanly under
// html.dark + ThemeContext instead of baking in hardcoded hex.
//
// Import: import { T, CARD, BTN, cardSx, statusChip } from '../../theme/salesTokens';

export const T = {
  // surfaces
  card:       'var(--bg-card, #ffffff)',
  bg:         'var(--bg, #f8fafc)',
  subtle:     'var(--bg-subtle, #f8fafc)',
  hover:      'var(--bg-hover, #f0f7ff)',
  // borders
  border:     'var(--border, #e2e8f0)',
  borderLt:   'var(--border-light, #f1f5f9)',
  // text
  text:       'var(--text, #0f172a)',
  textSec:    'var(--text-secondary, #64748b)',
  textMut:    'var(--text-muted, #94a3b8)',
  // brand
  brand:      'var(--brand, #2563eb)',
  brandAlt:   'var(--brand-alt, #3b82f6)',
  // status (foreground)
  success:    'var(--success, #10b981)',
  successDk:  'var(--success-dark, #059669)',
  warning:    'var(--warning, #f59e0b)',
  danger:     'var(--danger, #ef4444)',
  info:       'var(--info, #3b82f6)',
  purple:     'var(--accent-purple, #8b5cf6)',
  // status (subtle backgrounds) — fall back to the old tints in light mode
  successBg:  'var(--success-bg, #f0fdf4)',
  successTx:  'var(--success-text, #166534)',
  successCh:  'var(--success-chip, #dcfce7)',
  warningBg:  'var(--warning-bg, #fffbeb)',
  warningTx:  'var(--warning-text, #92400e)',
  warningCh:  'var(--warning-chip, #fef9c3)',
  dangerBg:   'var(--danger-bg, #fef2f2)',
  dangerTx:   'var(--danger-text, #991b1b)',
  dangerCh:   'var(--danger-chip, #fee2e2)',
  infoBg:     'var(--info-bg, #eff6ff)',
  infoTx:     'var(--info-text, #1e40af)',
  infoCh:     'var(--info-chip, #dbeafe)',
  indigoBg:   'var(--indigo-bg, #e0e7ff)',
  indigoTx:   'var(--indigo-text, #3730a3)',
  purpleBg:   'var(--purple-bg, #f5f3ff)',
  // radii / shadow
  radius:     'var(--radius-md, 10px)',
  radiusLg:   'var(--radius-lg, 14px)',
  shadowXs:   'var(--shadow-xs, 0 1px 4px rgba(16,23,38,.06))',
};

// Plain-inline-style card (used by Leaderboard + Hierarchy which are not MUI).
export const CARD = {
  background: T.card,
  borderRadius: 14,
  padding: 24,
  boxShadow: T.shadowXs,
  border: `1px solid ${T.border}`,
};

// Plain-inline-style button factory.
export const BTN = (bg = T.brand, fg = '#fff') => ({
  display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 14px',
  borderRadius: 8, background: bg, color: fg, border: 'none', cursor: 'pointer',
  fontSize: 13, fontWeight: 600,
});

// MUI sx object for the standard bordered card used across the management pages.
export const cardSx = {
  border: `1px solid ${T.border}`, boxShadow: 'none', borderRadius: 3, bgcolor: T.card,
};

// Status-chip colour pairs {bg, color} keyed by a small vocabulary.
export const statusChip = {
  mapped:   { bg: T.infoCh,    color: T.infoTx },
  unmapped: { bg: T.warningCh, color: T.warningTx },
  active:   { bg: T.successCh, color: T.successTx },
  inactive: { bg: T.dangerCh,  color: T.dangerTx },
};
