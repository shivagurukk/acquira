import React from 'react';
import { AlertTriangle, Users } from 'lucide-react';
import { T, CARD } from '../../theme/salesTokens';
import { Delta, DependencyChip } from './pulseVocab';

/*
 * TEAM RACE — every team on one ranked canvas.
 *
 * Replaces "scan each section header and compare numbers in your head" with a
 * bar per team, longest first. The answer to "which team is winning, and by how
 * much" becomes a picture; growth, attention and dependency ride along as
 * compact annotations. Clicking a row hands the teamLeadId back to the page,
 * which scrolls to and opens that team's detail section — the panel is an index,
 * not another data island.
 *
 * Bars are scaled against the LARGEST team, not the sum: the question is
 * "how far ahead is #1", and a share-of-total scale flattens exactly that.
 * Negative net margin clamps to a zero-width bar — direction is the Delta's job.
 */

export default function TeamRacePanel({ teams, money, narrow, onFocusTeam }) {
  const ranked = teams || [];
  if (ranked.length < 2) return null;   // one team has nothing to race against

  const maxSales = Math.max(...ranked.map((t) => Number(t.teamSales) || 0), 0);
  if (maxSales <= 0) return null;

  return (
    <section style={{ ...CARD, padding: narrow ? '16px 14px' : '20px 24px', marginBottom: 16 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 10, flexWrap: 'wrap', marginBottom: 14 }}>
        <h2 style={{ margin: 0, fontSize: 15, fontWeight: 700, color: T.text }}>Team Race</h2>
        <span style={{ fontSize: 11.5, color: T.textMut }}>Ranked by net sales · click a team for detail</span>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {ranked.map((team, i) => {
          const sales = Number(team.teamSales) || 0;
          const pct = Math.max(0, Math.min(100, (sales / maxSales) * 100));
          const key = team.teamLeadId ?? team.teamLeadName;
          const unassigned = team.teamLeadId == null;
          const leader = i === 0;

          return (
            <div
              key={key}
              role="button"
              tabIndex={0}
              onClick={() => onFocusTeam?.(key)}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onFocusTeam?.(key); } }}
              aria-label={`${team.teamLeadName}: ${money(sales)}. Show team detail.`}
              style={{
                display: 'grid', alignItems: 'center', gap: narrow ? 8 : 14,
                gridTemplateColumns: narrow ? '18px 1fr auto' : '22px 190px 1fr auto',
                padding: '7px 8px', margin: '0 -8px', borderRadius: 10, cursor: 'pointer',
              }}
              onMouseOver={(e) => { e.currentTarget.style.background = T.hover; }}
              onMouseOut={(e) => { e.currentTarget.style.background = 'transparent'; }}
            >
              {/* Rank */}
              <span style={{
                fontSize: 12, fontWeight: 800, textAlign: 'center',
                color: leader ? T.brand : T.textMut, fontVariantNumeric: 'tabular-nums',
              }}>
                {i + 1}
              </span>

              {/* Name (own column on wide screens so the bars share one baseline) */}
              {!narrow && (
                <div style={{ minWidth: 0 }}>
                  <div style={{
                    fontSize: 13, fontWeight: leader ? 700 : 600, color: unassigned ? T.textSec : T.text,
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                  }}>
                    {team.teamLeadName}
                  </div>
                  <div style={{ fontSize: 10.5, color: T.textMut, display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                    <Users size={10} />
                    {team.salesExecutiveCount}
                    {team.countryLeadName ? ` · ${team.countryLeadName}` : ''}
                  </div>
                </div>
              )}

              {/* Bar */}
              <div style={{ minWidth: 0 }}>
                {narrow && (
                  <div style={{ fontSize: 12, fontWeight: 600, color: T.text, marginBottom: 3, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {team.teamLeadName}
                  </div>
                )}
                <div style={{ height: 22, borderRadius: 6, background: T.borderLt, overflow: 'hidden' }}>
                  <div style={{
                    width: `${pct}%`, height: '100%', borderRadius: 6, minWidth: sales > 0 ? 3 : 0,
                    background: unassigned
                      ? T.textMut
                      : `linear-gradient(90deg, ${T.brand}, ${T.brandAlt})`,
                    opacity: leader ? 1 : 0.8 - i * 0.04,
                    transition: 'width .5s cubic-bezier(.22,.61,.36,1)',
                  }} />
                </div>
              </div>

              {/* Value + annotations */}
              <div style={{ display: 'flex', alignItems: 'center', gap: narrow ? 8 : 12, justifyContent: 'flex-end' }}>
                <span style={{ fontSize: 13, fontWeight: 700, color: T.text, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                  {money(sales)}
                </span>
                <span style={{ width: 58, textAlign: 'left' }}><Delta pct={team.teamGrowth} size={11.5} /></span>
                {!narrow && (
                  <>
                    {/* Annotations only when they carry news — a NORMAL badge on
                        every row would train the eye to skip the column. */}
                    <span style={{ width: 42 }}>
                      {team.needsAttentionCount > 0 && (
                        <span title={`${team.needsAttentionCount} sales executive${team.needsAttentionCount === 1 ? '' : 's'} slowing or needing attention`}
                              style={{ display: 'inline-flex', alignItems: 'center', gap: 3, color: T.dangerTx, fontSize: 11.5, fontWeight: 700 }}>
                          <AlertTriangle size={12} />{team.needsAttentionCount}
                        </span>
                      )}
                    </span>
                    {team.dependencyStatus && team.dependencyStatus !== 'NORMAL' && (
                      <DependencyChip
                        status={team.dependencyStatus}
                        topContributor={team.dependencyTopContributor}
                        sharePct={team.dependencySharePct}
                      />
                    )}
                  </>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}
