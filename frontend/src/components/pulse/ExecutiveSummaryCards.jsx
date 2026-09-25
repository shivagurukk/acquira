import React from 'react';
import { DollarSign, TrendingUp, Trophy, AlertTriangle } from 'lucide-react';
import { T, CARD } from '../../theme/salesTokens';

/*
 * The four figures an executive reads first: how much, which direction, who is
 * winning, and where to look. Everything else on the page is elaboration.
 */

const Card = ({ label, value, sub, icon: Icon, color, extra, muted }) => (
  <div style={{ ...CARD, padding: 16 }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
      <span style={{
        display: 'inline-flex', width: 38, height: 38, borderRadius: 10,
        alignItems: 'center', justifyContent: 'center',
        background: `color-mix(in srgb, ${color} 12%, transparent)`, color,
      }}>
        <Icon size={19} />
      </span>
      <div style={{ minWidth: 0 }}>
        <div style={{
          fontSize: 22, fontWeight: 700, lineHeight: 1.15,
          color: muted ? T.textMut : T.text, fontVariantNumeric: 'tabular-nums',
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>
          {value}
        </div>
        <div style={{ fontSize: 11.5, color: T.textMut, marginTop: 2 }}>{label}</div>
      </div>
    </div>
    {(sub || extra) && (
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8,
        marginTop: 10, paddingTop: 10, borderTop: `1px solid ${T.borderLt}`,
      }}>
        <span style={{
          fontSize: 11.5, color: T.textMut, overflow: 'hidden',
          textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>
          {sub}
        </span>
        {extra}
      </div>
    )}
  </div>
);

export default function ExecutiveSummaryCards({ summary, money, periodLabel }) {
  const s = summary || {};
  const top = s.topTeam;
  const attention = s.needsAttentionCount || 0;

  return (
    <div style={{
      display: 'grid', gap: 12, marginBottom: 14,
      gridTemplateColumns: 'repeat(auto-fit, minmax(230px, 1fr))',
    }}>
      <Card
        label="Total Sales"
        value={money(s.totalSales)}
        sub={periodLabel}
        icon={DollarSign}
        color={T.brand}
      />

      {/* Growth reads "—" rather than 0% when there is no comparable previous
          period — a page that cannot compare must say so, not imply stability. */}
      <Card
        label="Sales Growth"
        value={s.growth == null ? '—' : `${s.growth > 0 ? '+' : ''}${s.growth}%`}
        muted={s.growth == null}
        sub={s.growth == null ? 'No comparable previous period' : 'vs previous period'}
        icon={TrendingUp}
        color={s.growth == null ? T.textMut : s.growth >= 0 ? T.successDk : T.danger}
        extra={s.previousTotalSales != null
          ? <span style={{ fontSize: 11.5, color: T.textMut }}>{money(s.previousTotalSales)}</span>
          : null}
      />

      <Card
        label="Top Team"
        value={top ? `${top.teamLeadName}'s Team` : '—'}
        muted={!top}
        sub={top ? money(top.sales) : 'No sales recorded'}
        icon={Trophy}
        color={T.successDk}
      />

      <Card
        label="Needs Attention"
        value={`${attention} Sales ${attention === 1 ? 'Executive' : 'Executives'}`}
        muted={attention === 0}
        sub={attention === 0 ? 'Everyone at or above their norm' : 'Slowing or requiring attention'}
        icon={AlertTriangle}
        color={attention === 0 ? T.successDk : attention > 3 ? T.danger : T.warning}
        extra={s.salesExecutiveCount
          ? <span style={{ fontSize: 11.5, color: T.textMut }}>of {s.salesExecutiveCount}</span>
          : null}
      />
    </div>
  );
}
