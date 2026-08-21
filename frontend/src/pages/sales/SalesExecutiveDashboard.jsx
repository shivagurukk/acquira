import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Globe, Users, User, Loader2, Calendar, RefreshCw, LayoutDashboard,
  DollarSign, Hash, Percent, ChevronRight, ChevronDown, Store,
  TrendingUp, TrendingDown, Minus, X,
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { formatCompactCurrency } from '../../utils/formatters';
import { T, CARD } from '../../theme/salesTokens';

/*
 * Sales Executive Dashboard — management view of the whole sales org.
 *
 * One call to /sales-portfolio/executive returns the entire
 * Country Lead → Team Lead → Sales Agent tree with every node carrying the same
 * metric set, so a parent's figures are the sum of the rows beneath it. Selecting
 * an agent drills into their merchants via /sales-portfolio/agent/{salesUserId}.
 *
 * Every value on this screen respects the selected date range, and each range
 * carries an implied comparison period of the same length immediately before it,
 * so the Δ column always answers "versus the equivalent previous stretch".
 */

const fmt = (v) => v == null ? '—' : Number(v).toLocaleString('en-US', { maximumFractionDigits: 0 });
// fmtM renders MONEY — it now carries the tenant currency and the tenant's
// decimal precision (3dp for BHD) instead of a bare, unlabelled number.
const fmtM = (v) => formatCompactCurrency(v);
const fmtDate = (v) => {
  if (!v) return '—';
  const d = new Date(v);
  return isNaN(d) ? String(v).slice(0, 10) : d.toISOString().slice(0, 10);
};

const TIER = {
  country: { color: 'var(--tier-country, #1E3A8A)', bg: T.indigoBg, icon: Globe, role: 'Sales Head' },
  team:    { color: T.brand, bg: T.infoCh, icon: Users, role: 'Sales Lead' },
  agent:   { color: 'var(--tier-agent, #0891b2)', bg: 'var(--tier-agent-bg, #cffafe)', icon: User, role: 'Sales Agent' },
};

// ── Date presets ────────────────────────────────────────────────────────────
const isoLocal = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const addDays = (d, n) => { const c = new Date(d); c.setDate(c.getDate() + n); return c; };

const PRESETS = [
  { label: 'Today', value: 'TODAY' },
  { label: 'Yesterday', value: 'YESTERDAY' },
  { label: 'This Week', value: 'WEEK' },
  { label: 'This Month', value: 'MONTH' },
  { label: 'Last Month', value: 'LAST_MONTH' },
  { label: 'Custom', value: 'CUSTOM' },
];

function presetToRange(p) {
  const t = new Date(), y = t.getFullYear(), m = t.getMonth();
  switch (p) {
    case 'TODAY': return { from: isoLocal(t), to: isoLocal(t) };
    case 'YESTERDAY': { const d = addDays(t, -1); return { from: isoLocal(d), to: isoLocal(d) }; }
    // Week starts Monday — the convention the rest of the reporting suite uses.
    case 'WEEK': { const dow = (t.getDay() + 6) % 7; return { from: isoLocal(addDays(t, -dow)), to: isoLocal(t) }; }
    case 'MONTH': return { from: isoLocal(new Date(y, m, 1)), to: isoLocal(t) };
    case 'LAST_MONTH': return { from: isoLocal(new Date(y, m - 1, 1)), to: isoLocal(new Date(y, m, 0)) };
    default: return { from: '', to: '' };
  }
}

/*
 * The comparison period for a range. "Last month" compares against the month
 * before it — a calendar step, not an arithmetic one, so a 31-day month is never
 * compared against 31 days that straddle two months. Everything else compares
 * against the same number of days immediately before the range starts.
 */
function comparisonRange(preset, from, to) {
  if (!from || !to) return { from: '', to: '' };
  if (preset === 'LAST_MONTH') {
    const s = new Date(from);
    return {
      from: isoLocal(new Date(s.getFullYear(), s.getMonth() - 1, 1)),
      to: isoLocal(new Date(s.getFullYear(), s.getMonth(), 0)),
    };
  }
  if (preset === 'MONTH') {
    // Month-to-date compares against the same span of the previous month, so a
    // 6th-of-the-month view isn't flattered by a full 31-day month.
    const s = new Date(from), e = new Date(to);
    const days = Math.round((e - s) / 86400000);
    const prevStart = new Date(s.getFullYear(), s.getMonth() - 1, 1);
    return { from: isoLocal(prevStart), to: isoLocal(addDays(prevStart, days)) };
  }
  const s = new Date(from), e = new Date(to);
  const days = Math.round((e - s) / 86400000) + 1;
  return { from: isoLocal(addDays(s, -days)), to: isoLocal(addDays(s, -1)) };
}

// ── Change indicator ────────────────────────────────────────────────────────
function Delta({ pct, size = 11.5 }) {
  if (pct == null) return <span style={{ fontSize: size, color: T.textMut }}>—</span>;
  const n = Number(pct);
  const flat = Math.abs(n) < 0.05;
  const Icon = flat ? Minus : n > 0 ? TrendingUp : TrendingDown;
  const color = flat ? T.textMut : n > 0 ? T.successDk : T.danger;
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 3, fontSize: size, fontWeight: 600, color }}>
      <Icon size={size} />{flat ? '0%' : `${n > 0 ? '+' : ''}${n}%`}
    </span>
  );
}

function Kpi({ label, value, sub, pct, icon: Icon, color }) {
  return (
    <div style={{ ...CARD, padding: 14 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <span style={{ display: 'inline-flex', width: 36, height: 36, borderRadius: 9, alignItems: 'center', justifyContent: 'center', background: `color-mix(in srgb, ${color} 12%, transparent)`, color }}>
          <Icon size={18} />
        </span>
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 19, fontWeight: 700, color: T.text, fontVariantNumeric: 'tabular-nums' }}>{value}</div>
          <div style={{ fontSize: 11.5, color: T.textMut }}>{label}</div>
        </div>
      </div>
      {(sub || pct !== undefined) && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 8, paddingTop: 8, borderTop: `1px solid ${T.borderLt}` }}>
          <span style={{ fontSize: 11.5, color: T.textMut }}>{sub}</span>
          {pct !== undefined && <Delta pct={pct} />}
        </div>
      )}
    </div>
  );
}

// ── One row of the hierarchy tree ───────────────────────────────────────────
function TreeRow({ node, depth, expanded, onToggle, onSelect, selectedKey }) {
  const tier = TIER[node.level] || TIER.agent;
  const Icon = tier.icon;
  const key = `${node.level}:${node.id ?? node.salesUserId ?? node.name}`;
  const hasChildren = (node.children || []).length > 0;
  const isOpen = expanded.has(key);
  const isSelected = selectedKey === key;

  return (
    <>
      <tr
        onClick={() => onSelect(node, key)}
        style={{
          borderBottom: `1px solid ${T.borderLt}`, cursor: 'pointer',
          background: isSelected ? T.infoBg : 'transparent',
        }}
        onMouseOver={(e) => { if (!isSelected) e.currentTarget.style.background = T.hover; }}
        onMouseOut={(e) => { if (!isSelected) e.currentTarget.style.background = 'transparent'; }}
      >
        <td style={{ ...td, paddingLeft: 10 + depth * 22, textAlign: 'left' }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
            <button
              onClick={(e) => { e.stopPropagation(); if (hasChildren) onToggle(key); }}
              aria-label={hasChildren ? (isOpen ? 'Collapse' : 'Expand') : undefined}
              style={{
                width: 18, height: 18, display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                border: 'none', background: 'none', padding: 0, color: T.textMut,
                cursor: hasChildren ? 'pointer' : 'default', visibility: hasChildren ? 'visible' : 'hidden',
              }}
            >
              {isOpen ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
            </button>
            <span style={{ display: 'inline-flex', width: 22, height: 22, borderRadius: 6, alignItems: 'center', justifyContent: 'center', background: tier.bg, color: tier.color }}>
              <Icon size={12} />
            </span>
            <span style={{ fontWeight: node.level === 'agent' ? 500 : 700, color: T.text, fontSize: 13 }}>{node.name}</span>
            <span style={{ fontSize: 10, color: tier.color, fontWeight: 600, textTransform: 'uppercase', letterSpacing: 0.4 }}>{tier.role}</span>
          </span>
        </td>
        <td style={td}>{fmt(node.merchantCount)}</td>
        <td style={{ ...td, color: T.successDk }}>{fmt(node.activeMerchants)}</td>
        <td style={{ ...td, color: T.textMut }}>{fmt(node.inactiveMerchants)}</td>
        <td style={{ ...td, fontWeight: 600 }}>{fmt(node.newMerchants)}</td>
        <td style={{ ...td, fontWeight: 700 }}>{fmtM(node.totalVolume)}</td>
        <td style={{ ...td, color: Number(node.totalNet) < 0 ? T.danger : T.text }}>{fmtM(node.totalNet)}</td>
        <td style={td}>{fmt(node.totalTxns)}</td>
        <td style={td}><Delta pct={node.volumeChangePct} /></td>
      </tr>
      {isOpen && (node.children || []).map((child) => (
        <TreeRow
          key={`${child.level}:${child.id ?? child.salesUserId ?? child.name}`}
          node={child} depth={depth + 1} expanded={expanded}
          onToggle={onToggle} onSelect={onSelect} selectedKey={selectedKey}
        />
      ))}
    </>
  );
}

// ── Agent drill-down ────────────────────────────────────────────────────────
function AgentDrillDown({ agent, range, onClose }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true); setErr('');
      try {
        const params = range.from && range.to ? { dateFrom: range.from, dateTo: range.to } : {};
        const r = await api.get(`/sales-portfolio/agent/${encodeURIComponent(agent.salesUserId)}`, { params });
        if (!cancelled) setData(r.data);
      } catch (e) {
        if (!cancelled) setErr(e?.response?.data?.error || e.message || 'Failed to load merchants');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [agent.salesUserId, range.from, range.to]);

  const merchants = data?.merchants || [];

  return (
    <div style={{ ...CARD, marginTop: 16, border: `2px solid ${TIER.agent.color}` }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 14 }}>
        <div>
          <h3 style={{ margin: 0, fontSize: 15, fontWeight: 700, color: T.text }}>{agent.name}</h3>
          <div style={{ fontSize: 12, color: T.textMut }}>
            {agent.email || agent.salesUserId} · {fmt(merchants.length)} merchants
          </div>
        </div>
        <button onClick={onClose} style={{ display: 'inline-flex', alignItems: 'center', gap: 5, padding: '6px 11px', borderRadius: 7, background: T.subtle, color: T.textSec, border: `1px solid ${T.border}`, cursor: 'pointer', fontSize: 12.5 }}>
          <X size={13} /> Close
        </button>
      </div>

      {loading ? (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, padding: 36, color: T.textMut }}>
          <Loader2 size={16} className="acq-spin" /> Loading merchants…
        </div>
      ) : err ? (
        <div style={{ padding: 16, color: T.danger, fontSize: 13 }}>{err}</div>
      ) : merchants.length === 0 ? (
        <div style={{ padding: 28, textAlign: 'center', color: T.textMut, fontSize: 13 }}>No merchants assigned to this agent.</div>
      ) : (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 980 }}>
            <thead>
              <tr style={{ borderBottom: `2px solid ${T.border}` }}>
                <th style={{ ...th, textAlign: 'left' }}>Merchant</th>
                <th style={{ ...th, textAlign: 'left' }}>MID</th>
                <th style={th}>Status</th>
                <th style={th}>Assigned</th>
                <th style={th}>Gross Volume</th>
                <th style={th}>Net Margin</th>
                <th style={th}>Txns</th>
                <th style={th}>Last Txn</th>
                <th style={{ ...th, textAlign: 'left' }}>Sales Lead</th>
                <th style={{ ...th, textAlign: 'left' }}>Sales Agent</th>
              </tr>
            </thead>
            <tbody>
              {merchants.map((m) => (
                <tr key={m.merchant_id} style={{ borderBottom: `1px solid ${T.borderLt}` }}>
                  <td style={{ ...td, textAlign: 'left', fontWeight: 600 }}>{m.name || '—'}</td>
                  <td style={{ ...td, textAlign: 'left', color: T.textSec, fontFamily: 'monospace', fontSize: 12 }}>{m.mid}</td>
                  <td style={td}>
                    <span style={{
                      padding: '2px 8px', borderRadius: 11, fontSize: 11, fontWeight: 600,
                      background: String(m.status).toUpperCase() === 'ACTIVE' ? T.successBg : T.subtle,
                      color: String(m.status).toUpperCase() === 'ACTIVE' ? T.successTx : T.textMut,
                    }}>{m.status || 'UNKNOWN'}</span>
                  </td>
                  <td style={{ ...td, color: T.textSec }}>{fmtDate(m.assigned_date)}</td>
                  <td style={{ ...td, fontWeight: 700 }}>{fmtM(m.volume)}</td>
                  <td style={{ ...td, color: Number(m.net) < 0 ? T.danger : T.text }}>{fmtM(m.net)}</td>
                  <td style={td}>{fmt(m.txn_count)}</td>
                  <td style={{ ...td, color: T.textSec }}>{fmtDate(m.last_txn_date)}</td>
                  <td style={{ ...td, textAlign: 'left', color: T.textSec }}>{m.current_sales_lead || '—'}</td>
                  <td style={{ ...td, textAlign: 'left', color: T.textSec }}>
                    {m.current_sales_agent_name || m.current_sales_agent || '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ── Page ────────────────────────────────────────────────────────────────────
export default function SalesExecutiveDashboard() {
  const { tenantVersion } = useAuth();
  const [preset, setPreset] = useState('MONTH');
  const [customFrom, setCustomFrom] = useState('');
  const [customTo, setCustomTo] = useState('');

  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');

  const [expanded, setExpanded] = useState(new Set());
  const [selectedKey, setSelectedKey] = useState(null);
  const [selectedAgent, setSelectedAgent] = useState(null);

  const range = useMemo(
    () => preset === 'CUSTOM' ? { from: customFrom, to: customTo } : presetToRange(preset),
    [preset, customFrom, customTo]
  );
  const compare = useMemo(() => comparisonRange(preset, range.from, range.to), [preset, range]);

  const load = useCallback(async () => {
    if (!range.from || !range.to) { setData(null); setLoading(false); return; }
    setLoading(true); setErr('');
    try {
      const r = await api.get('/sales-portfolio/executive', {
        params: {
          dateFrom: range.from, dateTo: range.to,
          compareFrom: compare.from, compareTo: compare.to,
        },
      });
      setData(r.data);
      // Open the top level on first load so the screen is never a wall of
      // collapsed rows; deeper levels stay closed until asked for.
      setExpanded((prev) => {
        if (prev.size > 0) return prev;
        return new Set((r.data.tree || []).map((n) => `country:${n.id ?? n.name}`));
      });
    } catch (e) {
      setErr(e?.response?.data?.error || e.message || 'Failed to load dashboard');
    } finally {
      setLoading(false);
    }
  }, [range, compare]);

  useEffect(() => { load(); }, [load, tenantVersion]);

  const toggle = useCallback((key) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  }, []);

  const select = useCallback((node, key) => {
    setSelectedKey(key);
    setSelectedAgent(node.level === 'agent' ? node : null);
  }, []);

  const expandAll = useCallback(() => {
    const keys = new Set();
    const walk = (nodes) => (nodes || []).forEach((n) => {
      if ((n.children || []).length) {
        keys.add(`${n.level}:${n.id ?? n.name}`);
        walk(n.children);
      }
    });
    walk(data?.tree);
    setExpanded(keys);
  }, [data]);

  const totals = data?.totals;

  return (
    <div style={{ padding: 24, fontFamily: 'Inter, sans-serif', maxWidth: 1400, margin: '0 auto' }}>
      <style>{`@keyframes acqspin{to{transform:rotate(360deg)}} .acq-spin{animation:acqspin .8s linear infinite}`}</style>

      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16, flexWrap: 'wrap', gap: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <LayoutDashboard size={22} color="var(--tier-country, #1E3A8A)" />
          <div>
            <h2 style={{ margin: 0, fontSize: 20, color: T.text }}>Sales Executive</h2>
            <div style={{ fontSize: 12.5, color: T.textMut }}>Performance across every sales lead, agent and merchant</div>
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <button onClick={expandAll} style={btnStyle}>Expand all</button>
          <button onClick={() => setExpanded(new Set())} style={btnStyle}>Collapse all</button>
          <button onClick={load} style={btnStyle}><RefreshCw size={14} /> Refresh</button>
        </div>
      </div>

      {/* Date range */}
      <div style={{ ...CARD, padding: 14, marginBottom: 16, display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12.5, fontWeight: 600, color: T.textSec }}>
          <Calendar size={15} /> Period
        </span>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {PRESETS.map((p) => (
            <button key={p.value} onClick={() => setPreset(p.value)}
              style={{
                padding: '6px 12px', borderRadius: 7, fontSize: 12.5, fontWeight: 600, cursor: 'pointer', border: '1px solid',
                borderColor: preset === p.value ? 'var(--tier-country, #1E3A8A)' : T.border,
                background: preset === p.value ? 'var(--tier-country, #1E3A8A)' : T.card,
                color: preset === p.value ? '#fff' : T.textSec,
              }}>
              {p.label}
            </button>
          ))}
        </div>
        {preset === 'CUSTOM' && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <input type="date" value={customFrom} max={customTo || undefined}
              onChange={(e) => setCustomFrom(e.target.value)} style={inputStyle} />
            <span style={{ color: T.textMut }}>→</span>
            <input type="date" value={customTo} min={customFrom || undefined}
              onChange={(e) => setCustomTo(e.target.value)} style={inputStyle} />
          </div>
        )}
        <div style={{ marginLeft: 'auto', fontSize: 11.5, color: T.textMut }}>
          {range.from && range.to
            ? <>Showing <b>{range.from} → {range.to}</b>{compare.from && <> · compared with <b>{compare.from} → {compare.to}</b></>}</>
            : 'Pick a custom date range to load the dashboard.'}
        </div>
      </div>

      {/* KPI row */}
      {totals && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 16 }}>
          <Kpi label="Total Volume" value={fmtM(totals.totalVolume)} icon={DollarSign} color="var(--tier-agent, #0891b2)"
               sub="vs previous period" pct={totals.volumeChangePct} />
          <Kpi label="Net Margin" value={fmtM(totals.totalNet)} icon={Percent} color={T.successDk}
               sub="vs previous period" pct={totals.netChangePct} />
          <Kpi label="Transactions" value={fmt(totals.totalTxns)} icon={Hash} color="var(--accent-purple, #7c3aed)"
               sub="vs previous period" pct={totals.txnChangePct} />
          <Kpi label="Merchants" value={fmt(totals.merchantCount)} icon={Store} color={T.brand}
               sub={`${fmt(totals.activeMerchants)} active · ${fmt(totals.inactiveMerchants)} inactive`} />
          <Kpi label="New Merchants" value={fmt(totals.newMerchants)} icon={Users} color="var(--tier-country, #1E3A8A)"
               sub="added this period" />
        </div>
      )}

      {/* Hierarchy */}
      <div style={{ ...CARD, padding: 0, overflowX: 'auto' }}>
        {loading ? (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, padding: 50, color: T.textMut }}>
            <Loader2 size={18} className="acq-spin" /> Loading…
          </div>
        ) : err ? (
          <div style={{ padding: 24, color: T.danger, fontSize: 13 }}>{err}</div>
        ) : !data || (data.tree || []).length === 0 ? (
          <div style={{ padding: 36, textAlign: 'center', color: T.textMut, fontSize: 13.5 }}>
            No sales hierarchy yet — add sales leads via Sales → Team Management.
          </div>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 1080 }}>
            <thead>
              <tr style={{ borderBottom: `2px solid ${T.border}` }}>
                <th style={{ ...th, textAlign: 'left', paddingLeft: 10 }}>Sales Hierarchy</th>
                <th style={th}>Merchants</th>
                <th style={th}>Active</th>
                <th style={th}>Inactive</th>
                <th style={th}>New</th>
                <th style={th}>Volume</th>
                <th style={th}>Net Margin</th>
                <th style={th}>Txns</th>
                <th style={th}>Δ Volume</th>
              </tr>
            </thead>
            <tbody>
              {(data.tree || []).map((node) => (
                <TreeRow
                  key={`country:${node.id ?? node.name}`}
                  node={node} depth={0} expanded={expanded}
                  onToggle={toggle} onSelect={select} selectedKey={selectedKey}
                />
              ))}
            </tbody>
          </table>
        )}
      </div>

      {selectedAgent && (
        <AgentDrillDown agent={selectedAgent} range={range} onClose={() => { setSelectedAgent(null); setSelectedKey(null); }} />
      )}

      <div style={{ fontSize: 11.5, color: T.textMut, marginTop: 10, lineHeight: 1.6 }}>
        Volume is the single-currency settlement figure (store base currency), read from the pre-aggregated daily
        summary. Net margin is MSF minus interchange and scheme fees. Merchant counts are the agent's whole portfolio;
        only <b>New</b> is bounded by the selected period. Click any sales agent to see their merchants.
      </div>
    </div>
  );
}

const th = { padding: '10px 8px', textAlign: 'center', fontSize: 11, fontWeight: 700, color: T.textMut, textTransform: 'uppercase', letterSpacing: 0.4, whiteSpace: 'nowrap' };
const td = { padding: '9px 8px', textAlign: 'center', fontSize: 12.5, color: T.text, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' };
const inputStyle = { padding: '6px 8px', borderRadius: 7, border: `1px solid ${T.border}`, fontSize: 12.5, color: T.textSec, fontFamily: 'inherit', background: T.card };
const btnStyle = { display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 14px', borderRadius: 8, background: T.card, color: T.textSec, border: `1px solid ${T.border}`, cursor: 'pointer', fontSize: 13, fontWeight: 600 };
