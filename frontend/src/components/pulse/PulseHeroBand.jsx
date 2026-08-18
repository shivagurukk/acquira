import React, { useState } from 'react';
import { TrendingUp, TrendingDown, Minus, CalendarClock, Database, Target } from 'lucide-react';

/*
 * PULSE HERO BAND — the page's headline moment.
 *
 * One constant-dark panel that answers, in reading order, the three questions a
 * CEO opens the page with: how much did we sell, which way is it moving, and
 * what should I know about it (the generated insight sentence, promoted from a
 * grey footnote to the second-loudest element on the page). The org-level trend
 * chart sits beside it so the direction is a picture, not just a percentage.
 *
 * CONSTANT-DARK ON PURPOSE. Like the app's navigation rail, this band does not
 * flip with the theme — it is the page's anchor in both modes, and it is the
 * element most likely to be projected in a boardroom, where a dark panel with
 * light numerals survives a washed-out projector far better than grey-on-white.
 * Every colour in here is therefore a hard-coded dark-palette value, deliberately
 * NOT routed through the theme tokens.
 */

// ── Constant dark palette (never themed) ────────────────────────────────────
const D = {
  bg:      'linear-gradient(135deg, #0b1424 0%, #101f38 55%, #0d2338 100%)',
  border:  'rgba(148, 163, 184, 0.16)',
  text:    '#f1f5f9',
  textSec: '#cbd5e1',
  textMut: '#7d8ba1',
  accent:  '#38bdf8',
  up:      '#34d399',
  down:    '#f87171',
  warn:    '#fbbf24',
  chip:    'rgba(148, 163, 184, 0.12)',
};

const NUM = { fontVariantNumeric: 'tabular-nums' };

// Bright-on-dark delta — pulseVocab's Delta is tuned for light surfaces.
const HeroDelta = ({ pct }) => {
  if (pct == null) {
    return (
      <span title="No comparable previous period"
            style={{ fontSize: 13, color: D.textMut, fontWeight: 600 }}>
        — no prior period
      </span>
    );
  }
  const n = Number(pct);
  const flat = Math.abs(n) < 0.05;
  const Icon = flat ? Minus : n > 0 ? TrendingUp : TrendingDown;
  const color = flat ? D.textMut : n > 0 ? D.up : D.down;
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 5, padding: '4px 10px',
      borderRadius: 999, background: `color-mix(in srgb, ${color} 14%, transparent)`,
      color, fontSize: 13.5, fontWeight: 700, ...NUM,
    }}>
      <Icon size={15} />
      {flat ? '0%' : `${n > 0 ? '+' : ''}${n}%`}
      <span style={{ fontWeight: 500, color: D.textSec, fontSize: 12 }}>vs previous</span>
    </span>
  );
};

const Stat = ({ label, value, tone }) => (
  <div style={{ minWidth: 0 }}>
    <div style={{ fontSize: 16, fontWeight: 700, color: tone || D.text, ...NUM, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
      {value}
    </div>
    <div style={{ fontSize: 10.5, fontWeight: 600, color: D.textMut, textTransform: 'uppercase', letterSpacing: 0.6, marginTop: 2 }}>
      {label}
    </div>
  </div>
);

// ── Org trend chart ─────────────────────────────────────────────────────────
const MONTH_ABBR = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const monthLabel = (key) => {
  const [, m] = String(key).split('-').map(Number);
  return MONTH_ABBR[(m || 1) - 1] || key;
};

function OrgTrendChart({ series, money }) {
  const [active, setActive] = useState(null);
  const pts = (series || []).map((p) => Number(p.sales) || 0);
  if (pts.length < 2) return null;

  const W = 560, H = 150, PAD_T = 14, PAD_B = 6;
  const plotH = H - PAD_T - PAD_B;
  const min = Math.min(...pts, 0);
  const max = Math.max(...pts);
  const span = max - min || 1;
  const stepX = W / (pts.length - 1);
  const coords = pts.map((v, i) => [i * stepX, PAD_T + plotH - ((v - min) / span) * plotH]);
  const line = coords.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
  const area = `${line} L${W},${H} L0,${H} Z`;
  const [lastX, lastY] = coords[coords.length - 1];
  const peak = pts.indexOf(max);

  return (
    <div>
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img"
           aria-label={`Organisation sales over the last ${pts.length} months`}
           style={{ width: '100%', height: 150, display: 'block', overflow: 'visible' }}
           onMouseLeave={() => setActive(null)}>
        <defs>
          <linearGradient id="pulseHeroFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={D.accent} stopOpacity="0.32" />
            <stop offset="100%" stopColor={D.accent} stopOpacity="0.02" />
          </linearGradient>
        </defs>
        <path d={area} fill="url(#pulseHeroFill)" />
        <path d={line} fill="none" stroke={D.accent} strokeWidth="2"
              vectorEffect="non-scaling-stroke" strokeLinejoin="round" strokeLinecap="round" />
        {coords.map(([x, y], i) => (
          <g key={i}>
            {/* generous invisible hit area; the visible dot stays small */}
            <rect x={x - stepX / 2} y={0} width={stepX} height={H} fill="transparent"
                  style={{ cursor: 'default' }} onMouseEnter={() => setActive(i)} />
            <circle cx={x} cy={y} r={i === active ? 4 : 2.4} fill={i === active ? '#fff' : D.accent}
                    stroke={D.accent} strokeWidth={i === active ? 2 : 0} vectorEffect="non-scaling-stroke" />
          </g>
        ))}
        {/* ring on the latest point — "you are here" */}
        <circle cx={lastX} cy={lastY} r="7" fill="none" stroke={D.accent} strokeOpacity="0.4"
                strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
      </svg>

      {/* Month labels + values. The hovered point shows its value; at rest the
          first, peak and last are labelled — the three an executive asks about. */}
      <div style={{ display: 'flex', marginTop: 6 }}>
        {series.map((p, i) => {
          const showValue = active === i || (active === null && (i === 0 || i === peak || i === series.length - 1));
          return (
            <div key={p.month} style={{ flex: 1, textAlign: i === 0 ? 'left' : i === series.length - 1 ? 'right' : 'center', minWidth: 0 }}>
              <div style={{ fontSize: 11, fontWeight: 700, color: active === i ? D.text : D.textSec, ...NUM, minHeight: 15, whiteSpace: 'nowrap' }}>
                {showValue ? money(p.sales) : ''}
              </div>
              <div style={{ fontSize: 10, fontWeight: active === i ? 700 : 500, color: active === i ? D.textSec : D.textMut }}>
                {monthLabel(p.month)}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ── The band ────────────────────────────────────────────────────────────────
export default function PulseHeroBand({ data, money, periodLabel, narrow }) {
  const s = data?.summary || {};
  const attention = s.needsAttentionCount || 0;
  const orgSeries = data?.orgSeries || [];
  const hasChart = orgSeries.filter((p) => p != null).length >= 2;

  return (
    <section style={{
      background: D.bg, borderRadius: 18, border: `1px solid ${D.border}`,
      padding: narrow ? '20px 18px' : '26px 30px', marginBottom: 16,
      boxShadow: '0 12px 32px rgba(2, 8, 23, 0.25)',
    }}>
      {/* Top row: label + data-through badge */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 14 }}>
        <span style={{ fontSize: 11, fontWeight: 700, color: D.textMut, textTransform: 'uppercase', letterSpacing: 1.2 }}>
          Net Sales · {periodLabel}
        </span>
        {data?.dataThrough && (
          <span style={{
            display: 'inline-flex', alignItems: 'center', gap: 5, padding: '3px 10px',
            borderRadius: 999, background: D.chip, color: D.textSec, fontSize: 11, fontWeight: 600,
          }}>
            <Database size={11} /> Data through {data.dataThrough}
          </span>
        )}
      </div>

      <div style={{
        display: 'grid', gap: narrow ? 22 : 34, alignItems: 'start',
        gridTemplateColumns: narrow || !hasChart ? '1fr' : 'minmax(300px, 5fr) minmax(320px, 6fr)',
      }}>
        {/* ── Left: headline + insight + stats ── */}
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap' }}>
            <span style={{ fontSize: narrow ? 34 : 42, fontWeight: 800, lineHeight: 1.05, color: D.text, letterSpacing: -0.5, ...NUM }}>
              {money(s.totalSales)}
            </span>
            <HeroDelta pct={s.growth} />
          </div>
          {s.previousTotalSales != null && (
            <div style={{ fontSize: 12, color: D.textMut, marginTop: 6, ...NUM }}>
              Previous period {money(s.previousTotalSales)}
            </div>
          )}

          {/* The insight sentence — the page's actual product. */}
          {data?.executiveInsight && (
            <p style={{
              margin: '16px 0 0', fontSize: narrow ? 13.5 : 14.5, lineHeight: 1.6, color: D.textSec,
              borderLeft: `3px solid ${D.accent}`, paddingLeft: 12, maxWidth: 560,
            }}>
              {data.executiveInsight}
            </p>
          )}

          <div style={{
            display: 'flex', gap: narrow ? 20 : 30, flexWrap: 'wrap', marginTop: 20,
            paddingTop: 16, borderTop: `1px solid ${D.border}`,
          }}>
            <Stat label="Teams" value={s.teamCount ?? '—'} />
            <Stat label="Sales Executives" value={s.salesExecutiveCount ?? '—'} />
            <Stat
              label="Needs Attention"
              value={attention}
              tone={attention === 0 ? D.up : attention > 3 ? D.down : D.warn}
            />
            {s.topTeam && <Stat label="Top Team" value={s.topTeam.teamLeadName} />}
          </div>
        </div>

        {/* ── Right: org trend ── */}
        {hasChart && (
          <div style={{ minWidth: 0 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 4 }}>
              <span style={{ fontSize: 11, fontWeight: 700, color: D.textMut, textTransform: 'uppercase', letterSpacing: 1.2 }}>
                Monthly Trend
              </span>
              {data?.momentumWindow && (
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 10.5, color: D.textMut }}>
                  <CalendarClock size={11} />
                  Complete months {data.momentumWindow.from} → {data.momentumWindow.to}
                </span>
              )}
            </div>
            <OrgTrendChart series={orgSeries} money={money} />
          </div>
        )}
      </div>

      {/* Footnote: the one caveat worth stating on the band itself. */}
      {data && !data.targetsConfigured && (
        <div style={{
          display: 'flex', alignItems: 'center', gap: 6, marginTop: 16,
          fontSize: 11, color: D.textMut,
        }}>
          <Target size={12} />
          No sales targets configured — performance is measured against each salesperson's own history.
        </div>
      )}
    </section>
  );
}
