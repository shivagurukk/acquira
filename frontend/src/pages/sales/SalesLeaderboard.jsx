import React, { useState, useEffect, useCallback } from 'react';
import {
  Trophy, Users, UserPlus, TrendingUp, DollarSign, Loader2, Crown,
  BarChart3, ArrowUpRight, ArrowDownRight, Download, Percent, Globe, CalendarClock, AlertCircle
} from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { T, CARD, BTN } from '../../theme/salesTokens';
import { chartTooltipStyle } from '../../theme/dataGridStyles';

// ─── Constants ───────────────────────────────────────────────
const MEDAL_COLORS = ['#FFD700', '#C0C0C0', '#CD7F32'];

// Periods are data-anchored server-side (resolved against the latest
// business_date), so MTD is always populated and can be the default.
const PERIODS = [
  { label: 'MTD', value: 'MTD' }, { label: 'QTD', value: 'QTD' },
  { label: 'YTD', value: 'YTD' }, { label: 'Last Month', value: 'LAST_MONTH' },
  { label: 'Last Quarter', value: 'LAST_QUARTER' }, { label: 'All Time', value: '' }
];

const TABS = [
  { key: 'agents', label: 'Sales Agents', icon: Users, nameKey: 'agent', tierLabel: 'Sales Agent' },
  { key: 'teams', label: 'Team Leads', icon: Crown, nameKey: 'team_lead', tierLabel: 'Team Lead' },
  { key: 'countries', label: 'Country Leads', icon: Globe, nameKey: 'country_lead', tierLabel: 'Country Lead' },
];

const fmt = (v) => v == null ? '0' : Number(v).toLocaleString('en-US', { maximumFractionDigits: 0 });
const fmtM = (v) => { const n = Number(v) || 0; const a = Math.abs(n); if (a >= 1e6) return (n/1e6).toFixed(1)+'M'; if (a >= 1e3) return (n/1e3).toFixed(1)+'K'; return n.toFixed(0); };
const fmtPct = (v) => v == null ? '0%' : Number(v).toFixed(2) + '%';

// Backend sends stable badge KEYS; icon/label/colors are owned here.
const BADGE_CONFIG = {
  top_performer: { icon: '🥇', label: 'Top Performer', bg: T.warningCh, color: T.warningTx },
  runner_up: { icon: '🥈', label: 'Runner Up', bg: T.borderLt, color: T.textSec },
  bronze: { icon: '🥉', label: 'Bronze', bg: 'var(--bronze-bg, #fed7aa)', color: 'var(--bronze-text, #9a3412)' },
  onboarding_star: { icon: '🚀', label: 'Onboarding Star', bg: T.indigoBg, color: T.indigoTx },
  growing: { icon: '⭐', label: 'Growing Portfolio', bg: T.warningCh, color: T.warningTx },
  high_activation: { icon: '🔥', label: 'High Activation', bg: 'var(--pink-bg, #fce7f3)', color: 'var(--pink-text, #9d174d)' },
  million_club: { icon: '💎', label: 'Million Club', bg: T.purpleBg, color: 'var(--purple-text, #5b21b6)' },
  half_m: { icon: '🏆', label: '500K+ Club', bg: T.infoCh, color: T.infoTx },
  top_team: { icon: '🏆', label: '#1 Team', bg: T.warningCh, color: T.warningTx },
  large_team: { icon: '👥', label: 'Large Team', bg: T.successBg, color: T.successTx },
  '5m_club': { icon: '💎', label: '5M Club', bg: T.purpleBg, color: 'var(--purple-text, #5b21b6)' },
  million_team: { icon: '🏅', label: 'Million Team', bg: T.infoCh, color: T.infoTx },
  top_country: { icon: '👑', label: '#1 Country', bg: T.warningCh, color: T.warningTx },
  multi_team: { icon: '🌐', label: 'Multi-Team', bg: T.successBg, color: T.successTx },
  '10m_club': { icon: '💎', label: '10M Club', bg: T.purpleBg, color: 'var(--purple-text, #5b21b6)' },
  '5m_country': { icon: '🏅', label: '5M Country', bg: T.infoCh, color: T.infoTx },
};

const displayName = (name) => name?.includes('@') ? name.split('@')[0] : name;
const rowName = (item, nameKey) => nameKey === 'agent' ? (item.agent_email || item.agent) : item[nameKey];
const netOf = (item) => Number(item.net_revenue != null ? item.net_revenue : item.total_msf) || 0;

// ─── Small building blocks ───────────────────────────────────
const Badge = ({ badgeKey }) => {
  const c = BADGE_CONFIG[badgeKey] || { icon: '🏷️', label: badgeKey, bg: T.borderLt, color: T.textSec };
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 8px', borderRadius: 12, fontSize: 10.5, fontWeight: 600, background: c.bg, color: c.color, whiteSpace: 'nowrap' }}>
      {c.icon} {c.label}
    </span>
  );
};

const DeltaChip = ({ value }) => value == null
  ? <span style={{ fontSize: 11, color: T.textMut }}>—</span>
  : (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 2, fontSize: 11, fontWeight: 700, color: value >= 0 ? T.success : T.danger }}>
      {value >= 0 ? <ArrowUpRight size={12} /> : <ArrowDownRight size={12} />}
      {Math.abs(value)}%
    </span>
  );

// ─── Overview Stats ──────────────────────────────────────────
const OverviewStats = ({ data }) => {
  if (!data) return null;
  const net = data.totalNetRevenue != null ? data.totalNetRevenue : data.totalMsf;
  const stats = [
    { icon: Users, label: 'Sales Agents', value: fmt(data.totalAgents), color: T.info, bg: T.infoBg },
    { icon: Crown, label: 'Team Leads', value: fmt(data.totalTeams), color: T.warning, bg: T.warningBg },
    { icon: UserPlus, label: 'Onboarded', value: fmt(data.merchantsOnboarded), color: T.purple, bg: T.purpleBg },
    { icon: DollarSign, label: 'Total Volume', value: fmtM(data.totalVolume), color: T.success, bg: T.successBg },
    { icon: TrendingUp, label: 'Net Margin', value: fmtM(net), color: '#f97316', bg: 'var(--orange-bg, #fff7ed)' },
    { icon: Percent, label: 'Avg Net Rate', value: data.totalVolume > 0 ? fmtPct(net / data.totalVolume * 100) : '—', color: '#ec4899', bg: 'var(--pink-bg, #fdf2f8)' },
  ];
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(155px, 1fr))', gap: 12, marginBottom: 24 }}>
      {stats.map(s => (
        <div key={s.label} style={{ ...CARD, display: 'flex', alignItems: 'center', gap: 12, padding: 16 }}>
          <div style={{ width: 40, height: 40, borderRadius: 10, background: s.bg, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
            <s.icon size={18} color={s.color} />
          </div>
          <div>
            <div style={{ fontSize: 20, fontWeight: 800, color: T.text, lineHeight: 1.1, fontVariantNumeric: 'tabular-nums' }}>{s.value}</div>
            <div style={{ fontSize: 11, color: T.textSec, fontWeight: 500 }}>{s.label}</div>
          </div>
        </div>
      ))}
    </div>
  );
};

// ─── Podium (Top 3) ──────────────────────────────────────────
const Podium = ({ items, nameKey }) => {
  if (!items || items.length < 1) return null;
  const top3 = items.slice(0, 3);
  const order = top3.length >= 3 ? [top3[1], top3[0], top3[2]] : top3;
  const heights = [130, 170, 110];
  const indexMap = top3.length >= 3 ? [1, 0, 2] : top3.map((_, i) => i);

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'flex-end', gap: 16, marginBottom: 32, padding: '40px 0 0' }}>
      {order.map((item, idx) => {
        const origRank = indexMap[idx];
        const net = netOf(item);
        return (
          <div key={idx} style={{ textAlign: 'center', width: 170 }}>
            <div style={{
              width: origRank === 0 ? 72 : 60, height: origRank === 0 ? 72 : 60,
              borderRadius: '50%', margin: '0 auto 10px',
              background: `linear-gradient(135deg, ${MEDAL_COLORS[origRank]}44, ${MEDAL_COLORS[origRank]}88)`,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              border: `3px solid ${MEDAL_COLORS[origRank]}`, boxShadow: origRank === 0 ? `0 0 24px ${MEDAL_COLORS[0]}55` : 'none'
            }}>
              <span style={{ fontSize: origRank === 0 ? 28 : 22 }}>{origRank === 0 ? '👑' : origRank === 1 ? '🥈' : '🥉'}</span>
            </div>
            <div style={{ fontSize: 13, fontWeight: 700, color: T.text, marginBottom: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {displayName(rowName(item, nameKey))}
            </div>
            <div style={{ fontSize: 11, color: T.textSec, marginBottom: 4 }}>
              {fmtM(item.total_volume)} vol • {fmtM(net)} net
            </div>
            <div style={{ fontSize: 10, color: T.textMut, marginBottom: 8 }}>
              {fmt(item.merchants_onboarded)} onboarded • {fmtPct(item.net_rate)} net rate
            </div>
            <div style={{
              height: heights[idx], borderRadius: '12px 12px 0 0',
              background: `linear-gradient(180deg, ${MEDAL_COLORS[origRank]}66, ${MEDAL_COLORS[origRank]}22)`,
              display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center',
              border: `1px solid ${MEDAL_COLORS[origRank]}44`, borderBottom: 'none'
            }}>
              <span style={{ fontSize: 26, fontWeight: 900, color: MEDAL_COLORS[origRank] }}>#{origRank + 1}</span>
            </div>
          </div>
        );
      })}
    </div>
  );
};

// ─── Ranking Table ───────────────────────────────────────────
const th = { padding: '10px 8px', color: T.textSec, fontWeight: 600, fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.04em' };

const LeaderTable = ({ items, tab, onAgentClick }) => {
  if (!items || items.length === 0) return <div style={{ ...CARD, textAlign: 'center', padding: 60, color: T.textMut }}>No data available for this period</div>;
  const isAgents = tab.key === 'agents';

  return (
    <div style={CARD}>
      <div style={{ overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ borderBottom: `2px solid ${T.borderLt}` }}>
              <th style={{ ...th, textAlign: 'left', width: 50 }}>Rank</th>
              <th style={{ ...th, textAlign: 'left' }}>{tab.tierLabel}</th>
              {tab.key === 'countries' && <th style={{ ...th, textAlign: 'center' }}>Teams</th>}
              {!isAgents && <th style={{ ...th, textAlign: 'center' }}>Agents</th>}
              <th style={{ ...th, textAlign: 'center' }}>Onboarded</th>
              <th style={{ ...th, textAlign: 'center' }}>Active</th>
              <th style={{ ...th, textAlign: 'right' }}>Volume</th>
              <th style={{ ...th, textAlign: 'right' }}>Net Margin</th>
              <th style={{ ...th, textAlign: 'right' }}>Net Rate</th>
              <th style={{ ...th, textAlign: 'right' }}>Txns</th>
              <th style={{ ...th, textAlign: 'center' }}>Active %</th>
              <th style={{ ...th, textAlign: 'center' }}>Δ Net</th>
              <th style={{ ...th, textAlign: 'left' }}>Badges</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item, i) => {
              const name = rowName(item, tab.nameKey);
              const shortName = displayName(name);
              const rank = item.rank || i + 1;
              const net = netOf(item);
              const netRate = item.net_rate != null ? Number(item.net_rate)
                : (item.total_volume > 0 ? (net / item.total_volume * 100) : 0);

              return (
                <tr key={i} style={{ borderBottom: `1px solid ${T.borderLt}`, cursor: isAgents ? 'pointer' : 'default', transition: 'background .15s' }}
                    onMouseOver={e => e.currentTarget.style.background = T.hover}
                    onMouseOut={e => e.currentTarget.style.background = ''}
                    onClick={() => isAgents && onAgentClick && onAgentClick(item.agent)}>
                  <td style={{ padding: '12px 8px' }}>
                    {rank <= 3 ? (
                      <div style={{ width: 30, height: 30, borderRadius: '50%', background: MEDAL_COLORS[rank-1] + '22', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 800, fontSize: 14, color: MEDAL_COLORS[rank-1] }}>
                        {rank}
                      </div>
                    ) : <span style={{ color: T.textMut, fontWeight: 600, paddingLeft: 8 }}>{rank}</span>}
                  </td>
                  <td style={{ padding: '12px 8px', fontWeight: 600 }}>
                    <div style={{ color: T.text }}>{shortName}</div>
                    {name !== shortName && <div style={{ fontSize: 11, color: T.textMut, fontWeight: 400 }}>{name}</div>}
                  </td>
                  {tab.key === 'countries' && <td style={{ padding: '12px 8px', textAlign: 'center', fontWeight: 600, color: T.text }}>{item.team_count}</td>}
                  {!isAgents && <td style={{ padding: '12px 8px', textAlign: 'center', fontWeight: 600, color: T.text }}>{item.agent_count}</td>}
                  <td style={{ padding: '12px 8px', textAlign: 'center' }}>
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 8px', borderRadius: 12, fontSize: 11, fontWeight: 600, background: T.indigoBg, color: T.indigoTx }}>
                      <UserPlus size={11} /> {item.merchants_onboarded}
                    </span>
                  </td>
                  <td style={{ padding: '12px 8px', textAlign: 'center', fontWeight: 600, color: T.success }}>
                    {fmt(item.active_merchants)}<span style={{ color: T.textMut, fontWeight: 400 }}>/{fmt(item.total_merchants)}</span>
                  </td>
                  <td style={{ padding: '12px 8px', textAlign: 'right', fontWeight: 700, color: T.text, fontVariantNumeric: 'tabular-nums' }}>{fmtM(item.total_volume)}</td>
                  <td style={{ padding: '12px 8px', textAlign: 'right', fontWeight: 600, color: net >= 0 ? T.warning : T.danger, fontVariantNumeric: 'tabular-nums' }}>{fmtM(net)}</td>
                  <td style={{ padding: '12px 8px', textAlign: 'right' }}>
                    <span style={{ fontSize: 12, fontWeight: 700, color: netRate >= 0.4 ? T.success : netRate >= 0.2 ? T.warning : T.danger }}>{fmtPct(netRate)}</span>
                  </td>
                  <td style={{ padding: '12px 8px', textAlign: 'right', color: T.textSec, fontVariantNumeric: 'tabular-nums' }}>{fmt(item.txn_count)}</td>
                  <td style={{ padding: '12px 8px', textAlign: 'center' }}>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6 }}>
                      <div style={{ width: 50, height: 6, borderRadius: 3, background: T.borderLt }}>
                        <div style={{ height: '100%', borderRadius: 3, width: `${Math.min(item.active_rate || 0, 100)}%`, background: item.active_rate >= 80 ? T.success : item.active_rate >= 50 ? T.warning : T.danger }} />
                      </div>
                      <span style={{ fontSize: 12, fontWeight: 600, color: item.active_rate >= 80 ? T.success : T.textSec }}>{item.active_rate || 0}%</span>
                    </div>
                  </td>
                  <td style={{ padding: '12px 8px', textAlign: 'center' }}>
                    <DeltaChip value={item.net_change_pct != null ? item.net_change_pct : item.volume_change_pct} />
                  </td>
                  <td style={{ padding: '12px 8px' }}>
                    <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                      {(item.badges || []).map((b, bi) => <Badge key={bi} badgeKey={b} />)}
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
};

// ─── Agent Detail Panel ──────────────────────────────────────
// salesUserId is the sales rep CODE (dim_merchant.sales_user_id) — the key the
// leaderboard rows and the whole sales hierarchy are grouped by.
const AgentDetail = ({ salesUserId, period, onClose }) => {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const load = async () => {
      setLoading(true);
      try { const r = await api.get(`/leaderboard/agents/${encodeURIComponent(salesUserId)}?period=${period}`); setData(r.data); }
      catch (e) { console.error(e); }
      finally { setLoading(false); }
    };
    load();
  }, [salesUserId, period]);

  if (loading) return <div style={{ ...CARD, textAlign: 'center', padding: 40 }}><Loader2 size={24} className="spin" /></div>;
  if (!data) return null;

  const trendData = [...(data.monthlyTrend || [])].reverse();
  const merchants = data.merchants || [];
  const totalVol = merchants.reduce((s, m) => s + Number(m.volume || 0), 0);
  const totalNet = merchants.reduce((s, m) => s + Number(m.net != null ? m.net : m.msf || 0), 0);

  return (
    <div style={{ ...CARD, marginBottom: 24, border: `2px solid ${T.brandAlt}` }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
        <div>
          <h3 style={{ fontSize: 16, fontWeight: 700, margin: 0, color: T.text }}>
            {data.agentEmail ? data.agentEmail.split('@')[0] : salesUserId}
          </h3>
          <div style={{ fontSize: 12, color: T.textSec }}>{data.agentEmail || salesUserId}</div>
          <div style={{ display: 'flex', gap: 12, marginTop: 8 }}>
            <span style={{ fontSize: 12, color: T.textSec }}><strong>{merchants.length}</strong> merchants</span>
            <span style={{ fontSize: 12, color: T.textSec }}><strong>{fmtM(totalVol)}</strong> volume</span>
            <span style={{ fontSize: 12, color: T.warning }}><strong>{fmtM(totalNet)}</strong> net margin</span>
            <span style={{ fontSize: 12, color: T.success }}><strong>{totalVol > 0 ? fmtPct(totalNet/totalVol*100) : '—'}</strong> net rate</span>
          </div>
        </div>
        <button style={BTN(T.subtle, T.textSec)} onClick={onClose}>✕ Close</button>
      </div>

      {trendData.length > 1 && (
        <div style={{ height: 200, marginBottom: 24 }}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={trendData}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border-light, #f3f4f6)" />
              <XAxis dataKey="month" tick={{ fontSize: 11, fill: 'var(--text-muted, #94a3b8)' }} />
              <YAxis tick={{ fontSize: 11, fill: 'var(--text-muted, #94a3b8)' }} tickFormatter={fmtM} />
              <Tooltip formatter={(v) => fmtM(v)} contentStyle={chartTooltipStyle} />
              <Bar dataKey="volume" fill={T.brandAlt} radius={[4, 4, 0, 0]} name="Volume" />
              <Bar dataKey="net" fill={T.warning} radius={[4, 4, 0, 0]} name="Net Margin" />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}

      <div style={{ fontSize: 13, fontWeight: 600, color: T.textSec, marginBottom: 8 }}>Merchants ({merchants.length})</div>
      <div style={{ maxHeight: 300, overflow: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
          <thead>
            <tr style={{ borderBottom: `2px solid ${T.borderLt}`, position: 'sticky', top: 0, background: T.card }}>
              {['MID', 'Name', 'Status', 'Volume', 'Net Rev', 'Net Rate', 'Txns'].map(h =>
                <th key={h} style={{ padding: '6px 8px', textAlign: h === 'MID' || h === 'Name' || h === 'Status' ? 'left' : 'right', color: T.textSec, fontWeight: 600, fontSize: 10, textTransform: 'uppercase' }}>{h}</th>
              )}
            </tr>
          </thead>
          <tbody>
            {merchants.map((m, i) => {
              const mNet = Number(m.net != null ? m.net : m.msf || 0);
              const netRate = m.volume > 0 ? (mNet / m.volume * 100) : 0;
              return (
                <tr key={i} style={{ borderBottom: `1px solid ${T.borderLt}` }}>
                  <td style={{ padding: '6px 8px', fontFamily: 'monospace', fontSize: 11, color: T.textSec }}>{m.mid}</td>
                  <td style={{ padding: '6px 8px', fontWeight: 500, color: T.text }}>{m.name || '—'}</td>
                  <td style={{ padding: '6px 8px' }}>
                    <span style={{ display: 'inline-flex', padding: '1px 6px', borderRadius: 8, fontSize: 10, fontWeight: 600, background: m.status === 'Active' ? T.successCh : T.dangerCh, color: m.status === 'Active' ? T.successTx : T.dangerTx }}>{m.status || 'Unknown'}</span>
                  </td>
                  <td style={{ padding: '6px 8px', fontWeight: 600, textAlign: 'right', color: T.text }}>{fmtM(m.volume)}</td>
                  <td style={{ padding: '6px 8px', color: mNet >= 0 ? T.warning : T.danger, fontWeight: 600, textAlign: 'right' }}>{fmtM(mNet)}</td>
                  <td style={{ padding: '6px 8px', textAlign: 'right', fontWeight: 600, color: netRate >= 0.4 ? T.success : netRate >= 0 ? T.textSec : T.danger }}>{fmtPct(netRate)}</td>
                  <td style={{ padding: '6px 8px', color: T.textSec, textAlign: 'right' }}>{fmt(m.txn_count)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
};

// ═══════════════════════════════════════════════════════════════
//  MAIN COMPONENT
// ═══════════════════════════════════════════════════════════════
const SalesLeaderboard = () => {
  const { tenantVersion } = useAuth();
  const [activeTab, setActiveTab] = useState('agents');
  const [period, setPeriod] = useState('MTD');
  const [overview, setOverview] = useState(null);
  const [boards, setBoards] = useState({ agents: [], teams: [], countries: [] });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selectedAgent, setSelectedAgent] = useState(null);

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const p = `period=${period}`;
      const [ov, ag, tm, co] = await Promise.all([
        api.get(`/leaderboard/overview?${p}`),
        api.get(`/leaderboard/agents?${p}`),
        api.get(`/leaderboard/teams?${p}`),
        api.get(`/leaderboard/countries?${p}`)
      ]);
      setOverview(ov.data);
      setBoards({ agents: ag.data || [], teams: tm.data || [], countries: co.data || [] });
    } catch (e) {
      console.error(e);
      setError(e?.response?.data?.error || e.message || 'Failed to load leaderboard');
    } finally { setLoading(false); }
  }, [period]);

  useEffect(() => { load(); }, [load, tenantVersion]);

  const tab = TABS.find(t => t.key === activeTab) || TABS[0];
  const data = boards[tab.key];

  const handleExportCSV = () => {
    const isAgents = tab.key === 'agents';
    const headers = ['Rank', tab.tierLabel,
      ...(tab.key === 'countries' ? ['Teams'] : []), ...(!isAgents ? ['Agents'] : []),
      'Onboarded', 'Active', 'Total', 'Volume', 'MSF', 'Net Margin', 'Net Rate', 'Txns', 'Active %', 'Net Change %'];
    const rows = data.map((item, i) => {
      const net = netOf(item);
      return [
        item.rank || i + 1, rowName(item, tab.nameKey),
        ...(tab.key === 'countries' ? [item.team_count] : []), ...(!isAgents ? [item.agent_count] : []),
        item.merchants_onboarded, item.active_merchants, item.total_merchants,
        item.total_volume, item.total_msf, net,
        item.net_rate != null ? item.net_rate + '%' : '0%',
        item.txn_count, (item.active_rate || 0) + '%',
        item.net_change_pct != null ? item.net_change_pct + '%' : 'N/A'
      ];
    });
    const csv = [headers, ...rows].map(r => r.join(',')).join('\n');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
    const a = document.createElement('a'); a.href = url; a.download = `leaderboard_${tab.key}_${period || 'all'}.csv`; a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div style={{ padding: '0 0 40px' }}>
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 20, flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h1 style={{ fontSize: 22, fontWeight: 700, color: T.text, marginBottom: 4, display: 'flex', alignItems: 'center', gap: 8 }}>
            <Trophy size={22} color={T.warning} />
            Sales Leaderboard
          </h1>
          <p style={{ fontSize: 13, color: T.textSec, margin: 0 }}>Ranked by net margin (MSF − interchange − scheme fee), with onboarding, volume, and margin</p>
          {overview?.dataThrough && (
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, marginTop: 6, padding: '3px 10px', borderRadius: 12, fontSize: 11, fontWeight: 600, background: T.infoCh, color: T.infoTx }}>
              <CalendarClock size={12} />
              Data through {overview.dataThrough}
              {overview.periodFrom && overview.periodTo && ` • ${overview.periodFrom} → ${overview.periodTo}`}
            </span>
          )}
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <button style={BTN(T.subtle, T.textSec)} onClick={handleExportCSV}>
            <Download size={14} /> Export CSV
          </button>
          <div style={{ display: 'flex', gap: 3, background: T.subtle, borderRadius: 10, padding: 3 }}>
            {PERIODS.map(p => (
              <button key={p.value} onClick={() => setPeriod(p.value)} style={{
                padding: '6px 12px', borderRadius: 8, border: 'none', cursor: 'pointer', fontSize: 12, fontWeight: 600,
                background: period === p.value ? T.card : 'transparent', color: period === p.value ? T.brand : T.textSec,
                boxShadow: period === p.value ? T.shadowXs : 'none'
              }}>{p.label}</button>
            ))}
          </div>
        </div>
      </div>

      {error && (
        <div style={{ ...CARD, display: 'flex', alignItems: 'center', gap: 10, marginBottom: 20, borderColor: T.danger, color: T.dangerTx, background: T.dangerBg }}>
          <AlertCircle size={18} color={T.danger} /> {error}
        </div>
      )}

      {loading ? (
        <div style={{ textAlign: 'center', padding: 80 }}><Loader2 size={32} className="spin" color={T.brand} /></div>
      ) : (
        <>
          <OverviewStats data={overview} />

          {/* Tab Bar */}
          <div style={{ display: 'flex', gap: 2, marginBottom: 24, background: T.subtle, borderRadius: 12, padding: 4 }}>
            {TABS.map(t => {
              const Icon = t.icon;
              const active = activeTab === t.key;
              return (
                <button key={t.key} onClick={() => { setActiveTab(t.key); setSelectedAgent(null); }} style={{
                  flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
                  padding: '10px 16px', borderRadius: 10, border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600,
                  background: active ? T.card : 'transparent', color: active ? T.brand : T.textSec,
                  boxShadow: active ? T.shadowXs : 'none', transition: 'background .2s, color .2s'
                }}>
                  <Icon size={16} /> {t.label} ({boards[t.key].length})
                </button>
              );
            })}
          </div>

          {/* Podium */}
          <Podium items={data} nameKey={tab.nameKey} />

          {/* Agent Detail */}
          {selectedAgent && <AgentDetail salesUserId={selectedAgent} period={period} onClose={() => setSelectedAgent(null)} />}

          {/* Volume vs Net Margin — top 15, matches the ranking metric */}
          {data.length > 1 && (
            <div style={{ ...CARD, marginBottom: 24, padding: 20 }}>
              <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 12, display: 'flex', alignItems: 'center', gap: 6, color: T.text }}>
                <BarChart3 size={16} /> Volume & Net Margin Distribution
              </div>
              <div style={{ height: 220 }}>
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={data.slice(0, 15)} layout="vertical">
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--border-light, #f3f4f6)" />
                    <XAxis type="number" tick={{ fontSize: 11, fill: 'var(--text-muted, #94a3b8)' }} tickFormatter={fmtM} />
                    <YAxis dataKey={tab.nameKey} type="category" width={120} tick={{ fontSize: 11, fill: 'var(--text-muted, #94a3b8)' }}
                      tickFormatter={v => v?.includes('@') ? v.split('@')[0] : v?.substring(0, 15)} />
                    <Tooltip formatter={v => fmtM(v)} contentStyle={chartTooltipStyle} />
                    <Legend wrapperStyle={{ fontSize: 11 }} />
                    <Bar dataKey="total_volume" fill={T.brandAlt} radius={[0, 6, 6, 0]} name="Volume" />
                    <Bar dataKey="net_revenue" fill={T.warning} radius={[0, 6, 6, 0]} name="Net Margin" />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </div>
          )}

          {/* Full Ranking Table */}
          <LeaderTable items={data} tab={tab} onAgentClick={setSelectedAgent} />
        </>
      )}

      <style>{`.spin { animation: spin 1s linear infinite; } @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }`}</style>
    </div>
  );
};

export default SalesLeaderboard;
