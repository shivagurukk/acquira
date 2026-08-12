import React from 'react';
import {
  Flame, CircleCheck, Minus, TrendingDown, TrendingUp, AlertTriangle,
  Trophy, Rocket, Star, ArrowDown, Users, Sparkles,
} from 'lucide-react';
import { T } from '../../theme/salesTokens';

/*
 * Shared vocabulary for the Executive Sales Pulse.
 *
 * The backend emits STABLE KEYS ("ACCELERATING", "TOP_PERFORMER"), never labels
 * or icons — so wording and iconography can change here without a deploy of the
 * API, and no component has to substring-match an emoji to know what it is
 * looking at.
 *
 * Every state carries an icon AND a text label. Colour is never the only carrier
 * of meaning: a red chip that reads "Attention" survives colour-blindness, a
 * greyscale print-out, and a projector that washes out the reds — which is
 * exactly the setting this page gets used in.
 */

// ── Momentum ────────────────────────────────────────────────────────────────
export const MOMENTUM = {
  ACCELERATING: { label: 'Accelerating', icon: Flame,        fg: T.successDk, bg: T.successCh, tone: 'positive' },
  STRONG:       { label: 'Strong',       icon: CircleCheck,  fg: T.successDk, bg: T.successCh, tone: 'positive' },
  STABLE:       { label: 'Stable',       icon: Minus,        fg: T.infoTx,    bg: T.infoCh,    tone: 'neutral'  },
  SLOWING:      { label: 'Slowing',      icon: TrendingDown, fg: T.warningTx, bg: T.warningCh, tone: 'warning'  },
  ATTENTION:    { label: 'Attention',    icon: AlertTriangle,fg: T.dangerTx,  bg: T.dangerCh,  tone: 'critical' },
  // Not a performance judgement — a statement that there isn't enough history to
  // make one. Rendered neutrally on purpose.
  NEW:          { label: 'New / Insufficient History', icon: Sparkles, fg: T.textSec, bg: T.subtle, tone: 'neutral' },
};

export const MomentumChip = ({ state, compact = false }) => {
  const m = MOMENTUM[state] || MOMENTUM.STABLE;
  const Icon = m.icon;
  return (
    <span
      title={m.label}
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 5, whiteSpace: 'nowrap',
        padding: compact ? '2px 7px' : '3px 9px', borderRadius: 999,
        background: m.bg, color: m.fg, fontSize: compact ? 11 : 11.5, fontWeight: 600,
      }}
    >
      <Icon size={compact ? 11 : 12} />
      {compact && state === 'NEW' ? 'New' : m.label}
    </span>
  );
};

// ── Executive signals ───────────────────────────────────────────────────────
export const SIGNAL = {
  TOP_PERFORMER:          { icon: Trophy,        fg: T.successDk, bg: T.successCh },
  FASTEST_IMPROVING:      { icon: Rocket,        fg: T.successDk, bg: T.successCh },
  MOST_CONSISTENT:        { icon: Star,          fg: T.infoTx,    bg: T.infoCh    },
  BIGGEST_DECLINE:        { icon: AlertTriangle, fg: T.dangerTx,  bg: T.dangerCh  },
  BELOW_PERSONAL_AVERAGE: { icon: ArrowDown,     fg: T.warningTx, bg: T.warningCh },
  CONSECUTIVE_DECLINE:    { icon: ArrowDown,     fg: T.dangerTx,  bg: T.dangerCh  },
  TEAM_DEPENDENCY:        { icon: Users,         fg: T.warningTx, bg: T.warningCh },
};

/**
 * Signal labels are built from the row's own numbers where a bare label would be
 * vague: "3 Month Decline" tells an executive what to do with the row;
 * "Consecutive Decline" makes them go looking.
 */
export function signalLabel(signal, row = {}) {
  switch (signal) {
    case 'TOP_PERFORMER':          return 'Top Performer';
    case 'FASTEST_IMPROVING':      return 'Fastest Improving';
    case 'MOST_CONSISTENT':        return 'Most Consistent';
    case 'BIGGEST_DECLINE':        return 'Biggest Decline';
    case 'BELOW_PERSONAL_AVERAGE': return 'Below Personal Average';
    case 'CONSECUTIVE_DECLINE':
      return `${row.consecutiveDeclines || 2} Month Decline`;
    case 'TEAM_DEPENDENCY':
      return row.teamContribution != null
        ? `${Math.round(row.teamContribution)}% of Team Sales`
        : 'Team Dependency';
    default: return null;
  }
}

export const SignalChip = ({ signal, row, compact = false }) => {
  const meta = SIGNAL[signal];
  const label = signalLabel(signal, row);
  if (!meta || !label) return <span style={{ color: T.textMut, fontSize: 11.5 }}>—</span>;
  const Icon = meta.icon;
  return (
    <span
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 5, whiteSpace: 'nowrap',
        padding: compact ? '2px 7px' : '3px 9px', borderRadius: 6,
        background: meta.bg, color: meta.fg, fontSize: compact ? 11 : 11.5, fontWeight: 600,
      }}
    >
      <Icon size={compact ? 11 : 12} />{label}
    </span>
  );
};

// ── Team dependency ─────────────────────────────────────────────────────────
export const DEPENDENCY = {
  NORMAL:   { label: 'Normal',              fg: T.textSec,   bg: T.subtle    },
  MODERATE: { label: 'Moderate Dependency', fg: T.warningTx, bg: T.warningCh },
  HIGH:     { label: 'High Dependency',     fg: T.dangerTx,  bg: T.dangerCh  },
};

export const DependencyChip = ({ status, topContributor, sharePct }) => {
  const d = DEPENDENCY[status] || DEPENDENCY.NORMAL;
  const detail = status !== 'NORMAL' && topContributor && sharePct != null
    ? `${topContributor} contributes ${Math.round(sharePct)}% of team sales.`
    : 'No single salesperson dominates this team.';
  return (
    <span
      title={detail}
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 5,
        padding: '2px 8px', borderRadius: 6, background: d.bg, color: d.fg,
        fontSize: 11, fontWeight: 600,
      }}
    >
      {status !== 'NORMAL' && <AlertTriangle size={11} />}
      {d.label}
    </span>
  );
};

// ── Change indicator ────────────────────────────────────────────────────────
/**
 * Null is rendered as an em dash, never as 0%. "No comparable previous period"
 * and "flat versus last period" are different facts and an executive will act on
 * them differently.
 */
export const Delta = ({ pct, size = 12 }) => {
  if (pct == null) {
    return <span title="No comparable previous period" style={{ fontSize: size, color: T.textMut }}>—</span>;
  }
  const n = Number(pct);
  const flat = Math.abs(n) < 0.05;
  const Icon = flat ? Minus : n > 0 ? TrendingUp : TrendingDown;
  const color = flat ? T.textMut : n > 0 ? T.successDk : T.danger;
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 3, fontSize: size, fontWeight: 600, color }}>
      <Icon size={size} />{flat ? '0%' : `${n > 0 ? '+' : ''}${n}%`}
    </span>
  );
};

// ── Target cell ─────────────────────────────────────────────────────────────
/**
 * A missing target is a missing target. It renders as an em dash with an
 * explanation, and NEVER as 0% — which would read as a total miss and is the
 * single most damaging thing this page could get wrong about someone.
 */
export const TargetCell = ({ pct }) => {
  if (pct == null) {
    return (
      <span title="No sales target has been configured for this salesperson."
            style={{ color: T.textMut, fontSize: 12.5 }}>—</span>
    );
  }
  const n = Number(pct);
  const color = n >= 100 ? T.successDk : n >= 80 ? T.text : n >= 60 ? T.warningTx : T.danger;
  return (
    <span style={{ fontSize: 12.5, fontWeight: 600, color, fontVariantNumeric: 'tabular-nums' }}>
      {Math.round(n)}%
    </span>
  );
};

// ── Sparkline ───────────────────────────────────────────────────────────────
/**
 * Deliberately tiny and axis-free. Its whole job is to give the momentum chip
 * some context — "Slowing, and here is the shape of it" — not to be an analytics
 * chart. The product brief is explicit that this page should not turn into one.
 */
export const Sparkline = ({ series, width = 96, height = 26 }) => {
  const pts = (series || []).filter((v) => v != null).map(Number);
  if (pts.length < 2) {
    return <span style={{ fontSize: 11, color: T.textMut }}>—</span>;
  }
  const min = Math.min(...pts, 0);
  const max = Math.max(...pts);
  const span = max - min || 1;
  const stepX = width / (pts.length - 1);
  const coords = pts.map((v, i) => [i * stepX, height - ((v - min) / span) * height]);
  const path = coords.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ');

  const rising = pts[pts.length - 1] >= pts[0];
  const stroke = rising ? T.successDk : T.danger;
  const [lastX, lastY] = coords[coords.length - 1];

  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img"
         aria-label={rising ? 'Trend rising' : 'Trend falling'} style={{ display: 'block', overflow: 'visible' }}>
      <path d={path} fill="none" stroke={stroke} strokeWidth="1.6"
            strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={lastX} cy={lastY} r="2.4" fill={stroke} />
    </svg>
  );
};
