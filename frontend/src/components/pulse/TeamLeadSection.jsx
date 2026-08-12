import React from 'react';
import { ChevronDown, ChevronRight, Users, AlertTriangle } from 'lucide-react';
import { T, CARD } from '../../theme/salesTokens';
import { Delta, MomentumChip, SignalChip, DependencyChip, TargetCell, Sparkline } from './pulseVocab';

/*
 * One Team Lead and the sales executives beneath them.
 *
 * Team totals are whatever the API summed from the members shown here, so the
 * header can never disagree with its own rows. Nothing in this component
 * recomputes a total.
 *
 * Below ~900px the table becomes cards. Sales, growth and momentum stay visible
 * at every width — they are the three facts the page exists to deliver; target,
 * contribution and signal move into the card's second line.
 */

const th = {
  padding: '8px 10px', fontSize: 10.5, fontWeight: 700, color: T.textMut,
  textTransform: 'uppercase', letterSpacing: 0.5, textAlign: 'right',
  borderBottom: `1px solid ${T.border}`, whiteSpace: 'nowrap',
};
const td = {
  padding: '9px 10px', fontSize: 12.5, color: T.text, textAlign: 'right',
  fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap',
};

function ExecRow({ row, money, onSelect }) {
  return (
    <tr
      onClick={() => onSelect(row)}
      style={{ borderBottom: `1px solid ${T.borderLt}`, cursor: 'pointer' }}
      onMouseOver={(e) => { e.currentTarget.style.background = T.hover; }}
      onMouseOut={(e) => { e.currentTarget.style.background = 'transparent'; }}
    >
      <td style={{ ...td, textAlign: 'left' }}>
        <div style={{ fontWeight: 600, color: T.text }}>{row.name}</div>
        {row.email && (
          <div style={{ fontSize: 10.5, color: T.textMut, overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 220 }}>
            {row.email}
          </div>
        )}
      </td>
      <td style={{ ...td, fontWeight: 700 }}>{money(row.sales)}</td>
      <td style={td}><Delta pct={row.growthPct} /></td>
      <td style={td}>
        {row.teamContribution == null
          ? <span style={{ color: T.textMut }}>—</span>
          : `${Math.round(row.teamContribution)}%`}
      </td>
      <td style={td}><TargetCell pct={row.targetAchievement} /></td>
      <td style={{ ...td, textAlign: 'left' }}><MomentumChip state={row.momentum} /></td>
      <td style={{ ...td, textAlign: 'left' }}><SignalChip signal={row.signal} row={row} /></td>
      <td style={{ ...td, width: 110 }}><Sparkline series={row.series} /></td>
    </tr>
  );
}

function ExecCard({ row, money, onSelect }) {
  return (
    <div
      onClick={() => onSelect(row)}
      style={{
        padding: '11px 12px', borderBottom: `1px solid ${T.borderLt}`, cursor: 'pointer',
        display: 'flex', flexDirection: 'column', gap: 7,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 10 }}>
        <span style={{ fontWeight: 600, fontSize: 13, color: T.text }}>{row.name}</span>
        <span style={{ fontWeight: 700, fontSize: 13.5, color: T.text, fontVariantNumeric: 'tabular-nums' }}>
          {money(row.sales)}
        </span>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <MomentumChip state={row.momentum} compact />
        <Delta pct={row.growthPct} size={11.5} />
        <span style={{ fontSize: 11, color: T.textMut }}>
          {row.teamContribution == null ? '—' : `${Math.round(row.teamContribution)}% of team`}
        </span>
        <span style={{ fontSize: 11, color: T.textMut, display: 'inline-flex', gap: 4 }}>
          Target <TargetCell pct={row.targetAchievement} />
        </span>
      </div>
      {row.signal && <div><SignalChip signal={row.signal} row={row} compact /></div>}
    </div>
  );
}

export default function TeamLeadSection({ team, money, narrow, expanded, onToggle, onSelect }) {
  const members = team.salesExecutives || [];
  const key = team.teamLeadId ?? team.teamLeadName;

  return (
    <div style={{ ...CARD, padding: 0, marginBottom: 12, overflow: 'hidden' }}>
      {/* ── Team Lead header ─────────────────────────────────────────────── */}
      <div
        onClick={() => onToggle(key)}
        style={{
          display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap',
          padding: '12px 14px', cursor: 'pointer',
          borderBottom: expanded ? `1px solid ${T.border}` : 'none',
          background: T.subtle,
        }}
      >
        <span style={{ display: 'inline-flex', alignItems: 'center', color: T.textMut }}>
          {expanded ? <ChevronDown size={17} /> : <ChevronRight size={17} />}
        </span>
        <span style={{
          display: 'inline-flex', width: 28, height: 28, borderRadius: 8,
          alignItems: 'center', justifyContent: 'center', background: T.infoCh, color: T.brand,
        }}>
          <Users size={14} />
        </span>

        <div style={{ minWidth: 0, flex: '1 1 180px' }}>
          <div style={{ fontWeight: 700, fontSize: 14, color: T.text }}>{team.teamLeadName}</div>
          <div style={{ fontSize: 11, color: T.textMut }}>
            {members.length} Sales {members.length === 1 ? 'Executive' : 'Executives'}
            {team.countryLeadName ? ` · ${team.countryLeadName}` : ''}
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 18, flexWrap: 'wrap' }}>
          <Metric label="Team Sales" value={money(team.teamSales)} strong />
          <Metric label="Growth" value={<Delta pct={team.teamGrowth} />} />
          <Metric
            label="Needs Attention"
            value={
              team.needsAttentionCount > 0
                ? (
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: T.dangerTx, fontWeight: 700 }}>
                    <AlertTriangle size={12} />{team.needsAttentionCount}
                  </span>
                )
                : <span style={{ color: T.textMut }}>0</span>
            }
          />
          <DependencyChip
            status={team.dependencyStatus}
            topContributor={team.dependencyTopContributor}
            sharePct={team.dependencySharePct}
          />
        </div>
      </div>

      {/* ── Members ──────────────────────────────────────────────────────── */}
      {expanded && (
        members.length === 0 ? (
          <div style={{ padding: 18, fontSize: 12.5, color: T.textMut, textAlign: 'center' }}>
            No sales executives are assigned to this team lead.
          </div>
        ) : narrow ? (
          <div>{members.map((m) => <ExecCard key={m.id} row={m} money={money} onSelect={onSelect} />)}</div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 880 }}>
              <thead>
                <tr>
                  <th style={{ ...th, textAlign: 'left' }}>Sales Executive</th>
                  <th style={th}>Sales</th>
                  <th style={th}>vs Previous</th>
                  <th style={th}>Contribution</th>
                  <th style={th}>Target</th>
                  <th style={{ ...th, textAlign: 'left' }}>Momentum</th>
                  <th style={{ ...th, textAlign: 'left' }}>Executive Signal</th>
                  <th style={th}>Trend</th>
                </tr>
              </thead>
              <tbody>
                {members.map((m) => <ExecRow key={m.id} row={m} money={money} onSelect={onSelect} />)}
              </tbody>
            </table>
          </div>
        )
      )}
    </div>
  );
}

const Metric = ({ label, value, strong }) => (
  <div style={{ textAlign: 'right' }}>
    <div style={{
      fontSize: strong ? 14 : 12.5, fontWeight: strong ? 700 : 600,
      color: T.text, fontVariantNumeric: 'tabular-nums',
    }}>
      {value}
    </div>
    <div style={{ fontSize: 10, color: T.textMut, textTransform: 'uppercase', letterSpacing: 0.4 }}>{label}</div>
  </div>
);
