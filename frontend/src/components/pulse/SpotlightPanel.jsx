import React from 'react';
import { Trophy, Rocket, AlertTriangle, ChevronRight } from 'lucide-react';
import { T, CARD } from '../../theme/salesTokens';
import { Delta, Sparkline, MOMENTUM } from './pulseVocab';

/*
 * SPOTLIGHT — the people the page wants leadership to notice.
 *
 * The backend already crowns a Top Performer and a Fastest Improving and flags
 * who is slowing — but as row-level chips inside collapsed tables, where nobody
 * scanning the page meets them. This panel lifts exactly those people out as
 * cards: two wins on top, then the attention list. Nothing here is recomputed;
 * every fact is read off the rows the API already classified, so the spotlight
 * can never disagree with the table beneath it.
 *
 * Clicking any person opens the same detail drawer as their table row.
 */

const initial = (name) => (name || '?').trim().charAt(0).toUpperCase();

/** Plain-language reason a person is on the attention list. */
function attentionReason(row) {
  if ((row.consecutiveDeclines || 0) >= 2) return `${row.consecutiveDeclines}-month decline`;
  if (row.momentum === 'ATTENTION') return 'Well below recent average';
  return 'Slowing vs recent months';
}

function WinCard({ row, icon: Icon, label, color, chipBg, money, onSelect }) {
  const mom = MOMENTUM[row.momentum];
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => onSelect(row)}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(row); } }}
      aria-label={`${label}: ${row.name}. Show detail.`}
      style={{
        flex: '1 1 200px', minWidth: 0, padding: '12px 14px', borderRadius: 12,
        border: `1px solid ${T.border}`, background: T.subtle, cursor: 'pointer',
      }}
      onMouseOver={(e) => { e.currentTarget.style.background = T.hover; }}
      onMouseOut={(e) => { e.currentTarget.style.background = T.subtle; }}
    >
      <span style={{
        display: 'inline-flex', alignItems: 'center', gap: 5, padding: '2px 8px',
        borderRadius: 999, background: chipBg, color, fontSize: 10.5, fontWeight: 700,
        textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 10,
      }}>
        <Icon size={11} />{label}
      </span>

      <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
        <span style={{
          display: 'inline-flex', width: 32, height: 32, borderRadius: '50%', flexShrink: 0,
          alignItems: 'center', justifyContent: 'center', background: chipBg, color,
          fontSize: 13, fontWeight: 700,
        }}>
          {initial(row.name)}
        </span>
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 13.5, fontWeight: 700, color: T.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {row.name}
          </div>
          {row.teamLeadName && (
            <div style={{ fontSize: 10.5, color: T.textMut, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
              {row.teamLeadName}'s team
            </div>
          )}
        </div>
      </div>

      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 8, marginTop: 10 }}>
        <div>
          <div style={{ fontSize: 16, fontWeight: 800, color: T.text, fontVariantNumeric: 'tabular-nums' }}>
            {money(row.sales)}
          </div>
          <Delta pct={row.growthPct} size={11.5} />
        </div>
        <Sparkline series={row.series} width={72} height={22} stroke={mom?.fg} />
      </div>
    </div>
  );
}

export default function SpotlightPanel({ executives, money, narrow, onSelect }) {
  const rows = executives || [];
  if (rows.length === 0) return null;

  const bySignal = (sig) => rows.find((r) => (r.signals || []).includes(sig));
  const top = bySignal('TOP_PERFORMER');
  const fastest = bySignal('FASTEST_IMPROVING');
  const attention = rows
    .filter((r) => r.momentum === 'SLOWING' || r.momentum === 'ATTENTION')
    .sort((a, b) => (b.momentum === 'ATTENTION') - (a.momentum === 'ATTENTION')
      || (b.consecutiveDeclines || 0) - (a.consecutiveDeclines || 0));

  const wins = [];
  if (top) wins.push({ row: top, icon: Trophy, label: 'Top Performer', color: T.successTx, chipBg: T.successCh });
  // The same person can hold both crowns; one card with one label is clearer
  // than the same face twice.
  if (fastest && fastest !== top) {
    wins.push({ row: fastest, icon: Rocket, label: 'Fastest Improving', color: T.infoTx, chipBg: T.infoCh });
  }
  if (wins.length === 0 && attention.length === 0) return null;

  const SHOWN = 4;

  return (
    <section style={{ ...CARD, padding: narrow ? '16px 14px' : '20px 24px', marginBottom: 16, display: 'flex', flexDirection: 'column' }}>
      <h2 style={{ margin: '0 0 14px', fontSize: 15, fontWeight: 700, color: T.text }}>Spotlight</h2>

      {wins.length > 0 && (
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginBottom: attention.length > 0 ? 16 : 0 }}>
          {wins.map((w) => <WinCard key={w.label} {...w} money={money} onSelect={onSelect} />)}
        </div>
      )}

      {attention.length > 0 && (
        <div>
          <div style={{
            display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6,
            fontSize: 11, fontWeight: 700, color: T.textMut, textTransform: 'uppercase', letterSpacing: 0.6,
          }}>
            <AlertTriangle size={12} color={T.warning} />
            Needs Attention · {attention.length}
          </div>
          {attention.slice(0, SHOWN).map((row) => {
            const critical = row.momentum === 'ATTENTION';
            return (
              <div
                key={row.id}
                role="button"
                tabIndex={0}
                onClick={() => onSelect(row)}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(row); } }}
                aria-label={`${row.name}: ${attentionReason(row)}. Show detail.`}
                style={{
                  display: 'flex', alignItems: 'center', gap: 10, padding: '7px 8px', margin: '0 -8px',
                  borderRadius: 8, cursor: 'pointer',
                }}
                onMouseOver={(e) => { e.currentTarget.style.background = T.hover; }}
                onMouseOut={(e) => { e.currentTarget.style.background = 'transparent'; }}
              >
                <span style={{ width: 3, alignSelf: 'stretch', borderRadius: 2, background: critical ? T.danger : T.warning, flexShrink: 0 }} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 12.5, fontWeight: 600, color: T.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {row.name}
                  </div>
                  <div style={{ fontSize: 10.5, color: critical ? T.dangerTx : T.warningTx }}>
                    {attentionReason(row)}
                  </div>
                </div>
                <div style={{ textAlign: 'right', flexShrink: 0 }}>
                  <div style={{ fontSize: 12, fontWeight: 700, color: T.text, fontVariantNumeric: 'tabular-nums' }}>
                    {money(row.sales)}
                  </div>
                  <Delta pct={row.growthPct} size={10.5} />
                </div>
                <ChevronRight size={13} color={T.textMut} style={{ flexShrink: 0 }} />
              </div>
            );
          })}
          {attention.length > SHOWN && (
            <div style={{ fontSize: 11, color: T.textMut, padding: '6px 0 0 13px' }}>
              +{attention.length - SHOWN} more in the team tables below
            </div>
          )}
        </div>
      )}
    </section>
  );
}
