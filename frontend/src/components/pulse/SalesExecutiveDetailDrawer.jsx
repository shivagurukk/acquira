import React, { useEffect, useState } from 'react';
import { X, Loader2, Users } from 'lucide-react';
import api from '../../api/axios';
import { T } from '../../theme/salesTokens';
import { Delta, MomentumChip, SignalChip, TargetCell, signalLabel, SIGNAL } from './pulseVocab';

/*
 * One sales executive's performance, opened from a row.
 *
 * Fetches /agent/{id} rather than reusing the row object it was opened from.
 * That endpoint runs the SAME assembly as the list, so the drawer cannot drift
 * from the row — and it carries the months that the row only sparklines.
 *
 * The trend chart is deliberately small and unadorned: it exists to explain the
 * momentum label, not to be an analytics surface.
 */

const MONTH_ABBR = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** Labels the series' points by walking back from the momentum window's last month. */
function monthLabels(count, lastMonthKey) {
  if (!lastMonthKey) return Array.from({ length: count }, (_, i) => `P${i + 1}`);
  const [y, m] = String(lastMonthKey).split('-').map(Number);
  const out = [];
  for (let i = count - 1; i >= 0; i--) {
    const d = new Date(y, (m - 1) - i, 1);
    out.push(MONTH_ABBR[d.getMonth()]);
  }
  return out;
}

function TrendChart({ series, labels, money }) {
  const pts = (series || []).map(Number);
  if (pts.length < 2) {
    return (
      <div style={{ padding: 20, textAlign: 'center', fontSize: 12, color: T.textMut }}>
        Not enough history to plot a trend.
      </div>
    );
  }

  const W = 100, H = 46;                       // viewBox units; scales to any width
  const min = Math.min(...pts, 0);
  const max = Math.max(...pts);
  const span = max - min || 1;
  const stepX = W / (pts.length - 1);
  const coords = pts.map((v, i) => [i * stepX, H - ((v - min) / span) * H]);
  const line = coords.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(2)},${y.toFixed(2)}`).join(' ');
  const area = `${line} L${W},${H} L0,${H} Z`;
  const rising = pts[pts.length - 1] >= pts[0];
  const stroke = rising ? T.successDk : T.danger;

  return (
    <div>
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img"
           aria-label={`Sales over the last ${pts.length} months`}
           style={{ width: '100%', height: 96, display: 'block' }}>
        <path d={area} fill={stroke} opacity="0.10" />
        <path d={line} fill="none" stroke={stroke} strokeWidth="1.2"
              vectorEffect="non-scaling-stroke" strokeLinejoin="round" strokeLinecap="round" />
        {coords.map(([x, y], i) => (
          <circle key={i} cx={x} cy={y} r="1.1" fill={stroke} vectorEffect="non-scaling-stroke" />
        ))}
      </svg>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 6 }}>
        {pts.map((v, i) => (
          <div key={i} style={{ flex: 1, textAlign: 'center', minWidth: 0 }}>
            <div style={{ fontSize: 10.5, fontWeight: 600, color: T.text, fontVariantNumeric: 'tabular-nums' }}>
              {money(v)}
            </div>
            <div style={{ fontSize: 10, color: T.textMut }}>{labels[i]}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

const Field = ({ label, children }) => (
  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, padding: '8px 0', borderBottom: `1px solid ${T.borderLt}` }}>
    <span style={{ fontSize: 12, color: T.textSec }}>{label}</span>
    <span style={{ fontSize: 12.5, fontWeight: 600, color: T.text, fontVariantNumeric: 'tabular-nums' }}>{children}</span>
  </div>
);

export default function SalesExecutiveDetailDrawer({ agent, query, money, onClose }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true); setErr('');
      try {
        const res = await api.get(`/executive/sales-pulse/agent/${encodeURIComponent(agent.id)}`, { params: query });
        if (!cancelled) setData(res.data);
      } catch (e) {
        if (!cancelled) setErr(e?.response?.data?.error || 'Could not load this sales executive.');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [agent.id, JSON.stringify(query)]);

  // Fall back to the row we were opened from, so the drawer shows something
  // immediately rather than a spinner over an empty panel.
  const d = data || agent;
  const series = d.series || [];
  const labels = monthLabels(series.length, data?.momentumWindow?.to);
  const signals = d.signals || [];

  return (
    <>
      <div
        onClick={onClose}
        style={{ position: 'fixed', inset: 0, background: 'rgba(15,23,42,.32)', zIndex: 1200 }}
      />
      <aside
        role="dialog"
        aria-label={`${d.name} performance detail`}
        style={{
          position: 'fixed', top: 0, right: 0, bottom: 0, width: 'min(440px, 100vw)',
          background: T.card, borderLeft: `1px solid ${T.border}`, zIndex: 1201,
          display: 'flex', flexDirection: 'column', boxShadow: '-8px 0 24px rgba(15,23,42,.10)',
        }}
      >
        <header style={{
          display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between',
          gap: 12, padding: '14px 16px', borderBottom: `1px solid ${T.border}`,
        }}>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 16, fontWeight: 700, color: T.text }}>{d.name}</div>
            <div style={{ fontSize: 11.5, color: T.textMut, display: 'flex', alignItems: 'center', gap: 5, marginTop: 2 }}>
              <Users size={12} />
              {d.teamLeadName ? `${d.teamLeadName}'s team` : 'No team lead assigned'}
            </div>
          </div>
          <button onClick={onClose} aria-label="Close"
                  style={{ border: 'none', background: 'none', cursor: 'pointer', color: T.textMut, padding: 4 }}>
            <X size={18} />
          </button>
        </header>

        <div style={{ padding: 16, overflowY: 'auto', flex: 1 }}>
          {loading && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: T.textMut, fontSize: 12.5, marginBottom: 12 }}>
              <Loader2 size={14} className="spin" /> Loading detail…
            </div>
          )}
          {err && (
            <div style={{ background: T.dangerBg, color: T.dangerTx, padding: 10, borderRadius: 8, fontSize: 12.5, marginBottom: 12 }}>
              {err}
            </div>
          )}

          {/* Headline */}
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 4 }}>
            <span style={{ fontSize: 26, fontWeight: 700, color: T.text, fontVariantNumeric: 'tabular-nums' }}>
              {money(d.sales)}
            </span>
            <Delta pct={d.growthPct} size={13} />
          </div>
          <div style={{ fontSize: 11.5, color: T.textMut, marginBottom: 14 }}>
            Current period sales
            {data?.period?.from && ` · ${data.period.from} → ${data.period.to}`}
          </div>

          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 16 }}>
            <MomentumChip state={d.momentum} />
            {signals.map((s) => <SignalChip key={s} signal={s} row={d} />)}
            {signals.length === 0 && (
              <span style={{ fontSize: 11.5, color: T.textMut }}>No executive signals for this period.</span>
            )}
          </div>

          {/* Trend */}
          <div style={{ marginBottom: 18 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 8 }}>
              <span style={{ fontSize: 11, fontWeight: 700, color: T.textMut, textTransform: 'uppercase', letterSpacing: 0.5 }}>
                Monthly trend
              </span>
              {data?.momentumWindow && (
                <span style={{ fontSize: 10.5, color: T.textMut }}>
                  {data.momentumWindow.from} → {data.momentumWindow.to}
                </span>
              )}
            </div>
            <TrendChart series={series} labels={labels} money={money} />
          </div>

          {/* Detail */}
          <Field label="Previous period">
            {d.previousSales == null ? <span style={{ color: T.textMut }}>—</span> : money(d.previousSales)}
          </Field>
          <Field label="Growth"><Delta pct={d.growthPct} /></Field>
          <Field label="Recent monthly average">
            {d.recentAverage == null ? <span style={{ color: T.textMut }}>—</span> : money(d.recentAverage)}
          </Field>
          <Field label="Team contribution">
            {d.teamContribution == null ? <span style={{ color: T.textMut }}>—</span> : `${Math.round(d.teamContribution)}%`}
          </Field>
          <Field label="Target">
            {d.target == null
              ? <span title="No sales target has been configured for this salesperson." style={{ color: T.textMut }}>—</span>
              : money(d.target)}
          </Field>
          <Field label="Target achievement"><TargetCell pct={d.targetAchievement} /></Field>
          <Field label="Consecutive declining months">{d.consecutiveDeclines ?? 0}</Field>
          <Field label="Active merchants">{d.merchants ?? 0}</Field>
          <Field label="Transactions">{Number(d.txns || 0).toLocaleString('en-US')}</Field>

          {d.momentum === 'NEW' && (
            <p style={{ fontSize: 11.5, color: T.textMut, marginTop: 14, lineHeight: 1.5 }}>
              This salesperson does not yet have enough monthly history for a momentum
              reading. Growth and momentum will appear once more complete months are available.
            </p>
          )}
        </div>
      </aside>
    </>
  );
}
