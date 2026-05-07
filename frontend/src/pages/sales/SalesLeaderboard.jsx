import React, { useState, useEffect, useCallback } from 'react';
import {
  Trophy, Medal, Users, TrendingUp, DollarSign, UserPlus, Activity,
  ChevronDown, ChevronUp, Loader2, Crown, Star, Flame, Gem, Target,
  BarChart3, ArrowUpRight, ArrowDownRight, Filter, Calendar, Eye,
  Download, Percent, Zap, Award
} from 'lucide-react';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import api from '../../api/axios';

const CARD = { background: '#fff', borderRadius: 14, padding: 24, boxShadow: '0 1px 4px rgba(0,0,0,.06)', border: '1px solid #eef0f4' };
const BTN = (bg = '#2563eb', fg = '#fff') => ({ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 14px', borderRadius: 8, background: bg, color: fg, border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600 });

const MEDAL_COLORS = ['#FFD700', '#C0C0C0', '#CD7F32'];
const PERIODS = [
  { label: 'MTD', value: 'MTD' }, { label: 'QTD', value: 'QTD' },
  { label: 'YTD', value: 'YTD' }, { label: 'Last Month', value: 'LAST_MONTH' },
  { label: 'Last Quarter', value: 'LAST_QUARTER' }, { label: 'All Time', value: '' }
];

const fmt = (v) => v == null ? '0' : Number(v).toLocaleString('en-US', { maximumFractionDigits: 0 });
const fmtM = (v) => { const n = Number(v); if (n >= 1e6) return (n/1e6).toFixed(1)+'M'; if (n >= 1e3) return (n/1e3).toFixed(1)+'K'; return n.toFixed(0); };
const fmtPct = (v) => v == null ? '0%' : Number(v).toFixed(2) + '%';

// Badge config: icon, label, bg, text color
const BADGE_CONFIG = {
  'top_performer': { icon: '🥇', label: 'Top Performer', bg: '#fef3c7', color: '#92400e' },
  'runner_up': { icon: '🥈', label: 'Runner Up', bg: '#f1f5f9', color: '#475569' },
  'bronze': { icon: '🥉', label: 'Bronze', bg: '#fed7aa', color: '#9a3412' },
  'onboarding_star': { icon: '🚀', label: 'Onboarding Star', bg: '#e0e7ff', color: '#3730a3' },
  'growing': { icon: '⭐', label: 'Growing Portfolio', bg: '#fef9c3', color: '#854d0e' },
  'high_activation': { icon: '🔥', label: 'High Activation', bg: '#fce7f3', color: '#9d174d' },
  'million_club': { icon: '💎', label: 'Million Club', bg: '#ede9fe', color: '#5b21b6' },
  'half_m': { icon: '🏆', label: '500K+ Club', bg: '#dbeafe', color: '#1e40af' },
  'top_team': { icon: '🏆', label: '#1 Team', bg: '#fef3c7', color: '#92400e' },
  'large_team': { icon: '👥', label: 'Large Team', bg: '#f0fdf4', color: '#166534' },
  '5m_club': { icon: '💎', label: '5M Club', bg: '#ede9fe', color: '#5b21b6' },
  'million_team': { icon: '🏅', label: 'Million Team', bg: '#dbeafe', color: '#1e40af' },
};

const StyledBadge = ({ badgeKey, label }) => {
  const config = BADGE_CONFIG[badgeKey] || { icon: '🏷️', label: label || badgeKey, bg: '#f1f5f9', color: '#475569' };
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 8px', borderRadius: 12, fontSize: 10.5, fontWeight: 600, background: config.bg, color: config.color, whiteSpace: 'nowrap' }}>
      {config.icon} {config.label}
    </span>
  );
};

// Map raw badge strings to keys
const mapBadge = (raw) => {
  if (raw.includes('Top Performer')) return 'top_performer';
  if (raw.includes('Runner Up')) return 'runner_up';
  if (raw.includes('Bronze')) return 'bronze';
  if (raw.includes('Onboarding Star')) return 'onboarding_star';
  if (raw.includes('Growing')) return 'growing';
  if (raw.includes('High Activation')) return 'high_activation';
  if (raw.includes('Million Club')) return 'million_club';
  if (raw.includes('Half-M') || raw.includes('500K')) return 'half_m';
  if (raw.includes('#1 Team')) return 'top_team';
  if (raw.includes('Large Team')) return 'large_team';
  if (raw.includes('5M Club')) return '5m_club';
  if (raw.includes('Million Team')) return 'million_team';
  return null;
};

// ─── Overview Stats ──────────────────────────────────────────
const OverviewStats = ({ data }) => {
  if (!data) return null;
  const stats = [
    { icon: Users, label: 'Sales Agents', value: fmt(data.totalAgents), color: '#3b82f6', bg: '#eff6ff' },
    { icon: Crown, label: 'Team Leads', value: fmt(data.totalTeams), color: '#f59e0b', bg: '#fffbeb' },
    { icon: UserPlus, label: 'Onboarded', value: fmt(data.merchantsOnboarded), color: '#8b5cf6', bg: '#f5f3ff' },
    { icon: DollarSign, label: 'Total Volume', value: fmtM(data.totalVolume), color: '#10b981', bg: '#f0fdf4' },
    { icon: TrendingUp, label: 'Total MSF', value: fmtM(data.totalMsf), color: '#f97316', bg: '#fff7ed' },
    { icon: Percent, label: 'Avg MSF Rate', value: data.totalVolume > 0 ? fmtPct(data.totalMsf / data.totalVolume * 100) : '—', color: '#ec4899', bg: '#fdf2f8' },
  ];
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(155px, 1fr))', gap: 12, marginBottom: 24 }}>
      {stats.map(s => (
        <div key={s.label} style={{ ...CARD, display: 'flex', alignItems: 'center', gap: 12, padding: 16 }}>
          <div style={{ width: 40, height: 40, borderRadius: 10, background: s.bg, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
            <s.icon size={18} color={s.color} />
          </div>
          <div>
            <div style={{ fontSize: 20, fontWeight: 800, color: '#111', lineHeight: 1.1 }}>{s.value}</div>
            <div style={{ fontSize: 11, color: '#6b7280', fontWeight: 500 }}>{s.label}</div>
          </div>
        </div>
      ))}
    </div>
  );
};

// ─── Podium (Top 3) ──────────────────────────────────────────
const Podium = ({ items, isTeam }) => {
  if (!items || items.length < 1) return null;
  const top3 = items.slice(0, 3);
  const order = top3.length >= 3 ? [top3[1], top3[0], top3[2]] : top3;
  const heights = [130, 170, 110];
  const indexMap = top3.length >= 3 ? [1, 0, 2] : top3.map((_, i) => i);

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'flex-end', gap: 16, marginBottom: 32, padding: '40px 0 0' }}>
      {order.map((item, idx) => {
        const origRank = indexMap[idx];
        const name = isTeam ? item.team_lead : item.agent;
        const shortName = name?.includes('@') ? name.split('@')[0] : name;
        const msfRate = item.total_volume > 0 ? (item.total_msf / item.total_volume * 100) : 0;
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
            <div style={{ fontSize: 13, fontWeight: 700, color: '#111', marginBottom: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{shortName}</div>
            <div style={{ fontSize: 11, color: '#6b7280', marginBottom: 4 }}>
              {fmtM(item.total_volume)} vol • {fmtM(item.total_msf)} MSF
            </div>
            <div style={{ fontSize: 10, color: '#9ca3af', marginBottom: 8 }}>
              {fmt(item.merchants_onboarded)} onboarded • {fmtPct(msfRate)} rate
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

// ─── Leaderboard Table ───────────────────────────────────────
const LeaderTable = ({ items, isTeam, onAgentClick }) => {
  if (!items || items.length === 0) return <div style={{ ...CARD, textAlign: 'center', padding: 60, color: '#9ca3af' }}>No data available for this period</div>;

  return (
    <div style={CARD}>
      <div style={{ overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ borderBottom: '2px solid #f3f4f6' }}>
              <th style={{ padding: '10px 8px', textAlign: 'left', color: '#6b7280', fontWeight: 600, width: 50, fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.04em' }}>Rank</th>
              <th style={{ padding: '10px 8px', textAlign: 'left', color: '#6b7280', fontWeight: 600, fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.04em' }}>{isTeam ? 'Team Lead' : 'Sales Agent'}</th>
              {isTeam && <th style={{ padding: '10px 8px', textAlign: 'center', color: '#6b7280', fontWeight: 600, fontSize: 11, textTransform: 'uppercase' }}>Agents</th>}
              <th style={{ padding: '10px 8px', textAlign: 'center', color: '#6b7280', fontWeight: 600, fontSize: 11, textTransform: 'uppercase' }}>Onboarded</th>
              <th style={{ padding: '10px 8px', textAlign: 'center', color: '#6b7280', fontWeight: 600, fontSize: 11, textTransform: 'uppercase' }}>Active</th>
              <th style={{ padding: '10px 8px', textAlign: 'right', color: '#6b7280', fontWeight: 600, fontSize: 11, textTransform: 'uppercase' }}>Volume</th>
              <th style={{ padding: '10px 8px', textAlign: 'right', color: '#6b7280', fontWeight: 600, fontSize: 11, textTransform: 'uppercase' }}>MSF</th>
              <th style={{ padding: '10px 8px', textAlign: 'right', color: '#6b7280', fontWeight: 600, fontSize: 11, textTransform: 'uppercase' }}>MSF Rate</th>
              <th style={{ padding: '10px 8px', textAlign: 'right', color: '#6b7280', fontWeight: 600, fontSize: 11, textTransform: 'uppercase' }}>Txns</th>
              <th style={{ padding: '10px 8px', textAlign: 'center', color: '#6b7280', fontWeight: 600, fontSize: 11, textTransform: 'uppercase' }}>Active %</th>
              {!isTeam && <th style={{ padding: '10px 8px', textAlign: 'center', color: '#6b7280', fontWeight: 600, fontSize: 11, textTransform: 'uppercase' }}>Δ Vol</th>}
              <th style={{ padding: '10px 8px', textAlign: 'left', color: '#6b7280', fontWeight: 600, fontSize: 11, textTransform: 'uppercase' }}>Badges</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item, i) => {
              const name = isTeam ? item.team_lead : item.agent;
              const shortName = name?.includes('@') ? name.split('@')[0] : name;
              const rank = item.rank || i + 1;
              const msfRate = item.total_volume > 0 ? (item.total_msf / item.total_volume * 100) : 0;

              return (
                <tr key={i} style={{ borderBottom: '1px solid #f3f4f6', cursor: !isTeam ? 'pointer' : 'default', transition: 'background .15s' }}
                    onMouseOver={e => e.currentTarget.style.background = '#f8fafc'}
                    onMouseOut={e => e.currentTarget.style.background = ''}
                    onClick={() => !isTeam && onAgentClick && onAgentClick(item.agent)}>
                  <td style={{ padding: '12px 8px' }}>
                    {rank <= 3 ? (
                      <div style={{ width: 30, height: 30, borderRadius: '50%', background: MEDAL_COLORS[rank-1] + '22', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 800, fontSize: 14, color: MEDAL_COLORS[rank-1] }}>
                        {rank}
                      </div>
                    ) : <span style={{ color: '#9ca3af', fontWeight: 600, paddingLeft: 8 }}>{rank}</span>}
                  </td>
                  <td style={{ padding: '12px 8px', fontWeight: 600 }}>
                    <div style={{ color: '#111' }}>{shortName}</div>
                    {name !== shortName && <div style={{ fontSize: 11, color: '#9ca3af', fontWeight: 400 }}>{name}</div>}
                  </td>
                  {isTeam && <td style={{ padding: '12px 8px', textAlign: 'center', fontWeight: 600 }}>{item.agent_count}</td>}
                  <td style={{ padding: '12px 8px', textAlign: 'center' }}>
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 8px', borderRadius: 12, fontSize: 11, fontWeight: 600, background: '#e0e7ff', color: '#3730a3' }}>
                      <UserPlus size={11} /> {item.merchants_onboarded}
                    </span>
                  </td>
                  <td style={{ padding: '12px 8px', textAlign: 'center', fontWeight: 600, color: '#10b981' }}>
                    {fmt(item.active_merchants)}<span style={{ color: '#d1d5db', fontWeight: 400 }}>/{fmt(item.total_merchants)}</span>
                  </td>
                  <td style={{ padding: '12px 8px', textAlign: 'right', fontWeight: 700, color: '#111' }}>{fmtM(item.total_volume)}</td>
                  <td style={{ padding: '12px 8px', textAlign: 'right', fontWeight: 600, color: '#f59e0b' }}>{fmtM(item.total_msf)}</td>
                  <td style={{ padding: '12px 8px', textAlign: 'right' }}>
                    <span style={{ fontSize: 12, fontWeight: 700, color: msfRate >= 2 ? '#10b981' : msfRate >= 1 ? '#f59e0b' : '#ef4444' }}>{fmtPct(msfRate)}</span>
                  </td>
                  <td style={{ padding: '12px 8px', textAlign: 'right', color: '#6b7280' }}>{fmt(item.txn_count)}</td>
                  <td style={{ padding: '12px 8px', textAlign: 'center' }}>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6 }}>
                      <div style={{ width: 50, height: 6, borderRadius: 3, background: '#f3f4f6' }}>
                        <div style={{ height: '100%', borderRadius: 3, width: `${Math.min(item.active_rate || 0, 100)}%`, background: item.active_rate >= 80 ? '#10b981' : item.active_rate >= 50 ? '#f59e0b' : '#ef4444' }} />
                      </div>
                      <span style={{ fontSize: 12, fontWeight: 600, color: item.active_rate >= 80 ? '#10b981' : '#6b7280' }}>{item.active_rate || 0}%</span>
                    </div>
                  </td>
                  {!isTeam && (
                    <td style={{ padding: '12px 8px', textAlign: 'center' }}>
                      {item.volume_change_pct != null ? (
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 2, fontSize: 11, fontWeight: 700, color: item.volume_change_pct >= 0 ? '#10b981' : '#ef4444' }}>
                          {item.volume_change_pct >= 0 ? <ArrowUpRight size={12} /> : <ArrowDownRight size={12} />}
                          {Math.abs(item.volume_change_pct)}%
                        </span>
                      ) : <span style={{ fontSize: 11, color: '#d1d5db' }}>—</span>}
                    </td>
                  )}
                  <td style={{ padding: '12px 8px' }}>
                    <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                      {(item.badges || []).map((b, bi) => {
                        const key = mapBadge(b);
                        return key ? <StyledBadge key={bi} badgeKey={key} /> : <span key={bi} style={{ fontSize: 11 }}>{b}</span>;
                      })}
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
const AgentDetail = ({ agentEmail, period, onClose }) => {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const load = async () => {
      setLoading(true);
      try { const r = await api.get(`/leaderboard/agents/${encodeURIComponent(agentEmail)}?period=${period}`); setData(r.data); }
      catch (e) { console.error(e); }
      finally { setLoading(false); }
    };
    load();
  }, [agentEmail, period]);

  if (loading) return <div style={{ ...CARD, textAlign: 'center', padding: 40 }}><Loader2 size={24} className="spin" /></div>;
  if (!data) return null;

  const trendData = (data.monthlyTrend || []).reverse();
  const merchants = data.merchants || [];
  const totalVol = merchants.reduce((s, m) => s + Number(m.volume || 0), 0);
  const totalMsf = merchants.reduce((s, m) => s + Number(m.msf || 0), 0);

  return (
    <div style={{ ...CARD, marginBottom: 24, border: '2px solid #3b82f6' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
        <div>
          <h3 style={{ fontSize: 16, fontWeight: 700, margin: 0, color: '#111' }}>{agentEmail.split('@')[0]}</h3>
          <div style={{ fontSize: 12, color: '#6b7280' }}>{agentEmail}</div>
          <div style={{ display: 'flex', gap: 12, marginTop: 8 }}>
            <span style={{ fontSize: 12, color: '#374151' }}><strong>{merchants.length}</strong> merchants</span>
            <span style={{ fontSize: 12, color: '#374151' }}><strong>{fmtM(totalVol)}</strong> volume</span>
            <span style={{ fontSize: 12, color: '#f59e0b' }}><strong>{fmtM(totalMsf)}</strong> MSF</span>
            <span style={{ fontSize: 12, color: '#10b981' }}><strong>{totalVol > 0 ? fmtPct(totalMsf/totalVol*100) : '—'}</strong> rate</span>
          </div>
        </div>
        <button style={BTN('#f3f4f6', '#374151')} onClick={onClose}>✕ Close</button>
      </div>

      {trendData.length > 1 && (
        <div style={{ height: 200, marginBottom: 24 }}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={trendData}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f3f4f6" />
              <XAxis dataKey="month" tick={{ fontSize: 11 }} />
              <YAxis tick={{ fontSize: 11 }} tickFormatter={fmtM} />
              <Tooltip formatter={(v) => fmtM(v)} />
              <Bar dataKey="volume" fill="#3b82f6" radius={[4, 4, 0, 0]} name="Volume" />
              <Bar dataKey="msf" fill="#f59e0b" radius={[4, 4, 0, 0]} name="MSF" />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}

      <div style={{ fontSize: 13, fontWeight: 600, color: '#374151', marginBottom: 8 }}>Merchants ({merchants.length})</div>
      <div style={{ maxHeight: 300, overflow: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
          <thead>
            <tr style={{ borderBottom: '2px solid #f3f4f6', position: 'sticky', top: 0, background: '#fff' }}>
              {['MID', 'Name', 'Status', 'Volume', 'MSF', 'MSF Rate', 'Txns'].map(h =>
                <th key={h} style={{ padding: '6px 8px', textAlign: h === 'MID' || h === 'Name' || h === 'Status' ? 'left' : 'right', color: '#6b7280', fontWeight: 600, fontSize: 10, textTransform: 'uppercase' }}>{h}</th>
              )}
            </tr>
          </thead>
          <tbody>
            {merchants.map((m, i) => {
              const msfRate = m.volume > 0 ? (m.msf / m.volume * 100) : 0;
              return (
                <tr key={i} style={{ borderBottom: '1px solid #f3f4f6' }}>
                  <td style={{ padding: '6px 8px', fontFamily: 'monospace', fontSize: 11 }}>{m.mid}</td>
                  <td style={{ padding: '6px 8px', fontWeight: 500 }}>{m.name || '—'}</td>
                  <td style={{ padding: '6px 8px' }}>
                    <span style={{ display: 'inline-flex', padding: '1px 6px', borderRadius: 8, fontSize: 10, fontWeight: 600, background: m.status === 'Active' ? '#dcfce7' : '#fee2e2', color: m.status === 'Active' ? '#166534' : '#991b1b' }}>{m.status || 'Unknown'}</span>
                  </td>
                  <td style={{ padding: '6px 8px', fontWeight: 600, textAlign: 'right' }}>{fmtM(m.volume)}</td>
                  <td style={{ padding: '6px 8px', color: '#f59e0b', fontWeight: 600, textAlign: 'right' }}>{fmtM(m.msf)}</td>
                  <td style={{ padding: '6px 8px', textAlign: 'right', fontWeight: 600, color: msfRate >= 2 ? '#10b981' : '#6b7280' }}>{fmtPct(msfRate)}</td>
                  <td style={{ padding: '6px 8px', color: '#6b7280', textAlign: 'right' }}>{fmt(m.txn_count)}</td>
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
const TABS = [
  { key: 'agents', label: 'Sales Agents', icon: Users },
  { key: 'teams', label: 'Team Leads', icon: Crown },
];

const SalesLeaderboard = () => {
  const [activeTab, setActiveTab] = useState('agents');
  // Default to LAST_MONTH instead of MTD: in environments where transaction
  // data lags real-time (e.g. it's May but data ends in April), MTD will
  // render an empty leaderboard on first load. LAST_MONTH always reaches
  // back to a complete month and is much more likely to have data.
  const [period, setPeriod] = useState('LAST_MONTH');
  const [overview, setOverview] = useState(null);
  const [agents, setAgents] = useState([]);
  const [teams, setTeams] = useState([]);
  const [loading, setLoading] = useState(true);
  const [selectedAgent, setSelectedAgent] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const p = `period=${period}`;
      const [ov, ag, tm] = await Promise.all([
        api.get(`/leaderboard/overview?${p}`),
        api.get(`/leaderboard/agents?${p}`),
        api.get(`/leaderboard/teams?${p}`)
      ]);
      setOverview(ov.data); setAgents(ag.data); setTeams(tm.data);
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  }, [period]);

  useEffect(() => { load(); }, [load]);

  const data = activeTab === 'agents' ? agents : teams;

  const handleExportCSV = () => {
    const isTeam = activeTab === 'teams';
    const headers = isTeam
      ? ['Rank', 'Team Lead', 'Agents', 'Onboarded', 'Active', 'Total', 'Volume', 'MSF', 'MSF Rate', 'Txns', 'Active %']
      : ['Rank', 'Agent', 'Onboarded', 'Active', 'Total', 'Volume', 'MSF', 'MSF Rate', 'Txns', 'Active %', 'Volume Change %'];
    const rows = data.map((item, i) => {
      const name = isTeam ? item.team_lead : item.agent;
      const msfRate = item.total_volume > 0 ? (item.total_msf / item.total_volume * 100).toFixed(2) + '%' : '0%';
      const base = [item.rank || i+1, name, item.merchants_onboarded, item.active_merchants, item.total_merchants, item.total_volume, item.total_msf, msfRate, item.txn_count, (item.active_rate || 0) + '%'];
      if (isTeam) base.splice(2, 0, item.agent_count);
      else base.push(item.volume_change_pct != null ? item.volume_change_pct + '%' : 'N/A');
      return base;
    });
    const csv = [headers, ...rows].map(r => r.join(',')).join('\n');
    const blob = new Blob([csv], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a'); a.href = url; a.download = `leaderboard_${activeTab}_${period || 'all'}.csv`; a.click();
  };

  return (
    <div style={{ padding: '0 0 40px' }}>
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 20, flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h1 style={{ fontSize: 22, fontWeight: 700, color: '#111', marginBottom: 4, display: 'flex', alignItems: 'center', gap: 8 }}>
            <Trophy size={22} color="#f59e0b" />
            Sales Leaderboard
          </h1>
          <p style={{ fontSize: 13, color: '#6b7280', margin: 0 }}>Track performance by onboarding, volume, MSF revenue, and margin rate</p>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <button style={BTN('#f3f4f6', '#374151')} onClick={handleExportCSV}>
            <Download size={14} /> Export CSV
          </button>
          <div style={{ display: 'flex', gap: 3, background: '#f3f4f6', borderRadius: 10, padding: 3 }}>
            {PERIODS.map(p => (
              <button key={p.value} onClick={() => setPeriod(p.value)} style={{
                padding: '6px 12px', borderRadius: 8, border: 'none', cursor: 'pointer', fontSize: 12, fontWeight: 600,
                background: period === p.value ? '#fff' : 'transparent', color: period === p.value ? '#2563eb' : '#6b7280',
                boxShadow: period === p.value ? '0 1px 3px rgba(0,0,0,.1)' : 'none'
              }}>{p.label}</button>
            ))}
          </div>
        </div>
      </div>

      {loading ? (
        <div style={{ textAlign: 'center', padding: 80 }}><Loader2 size={32} className="spin" color="#2563eb" /></div>
      ) : (
        <>
          <OverviewStats data={overview} />

          {/* Tab Bar */}
          <div style={{ display: 'flex', gap: 2, marginBottom: 24, background: '#f3f4f6', borderRadius: 12, padding: 4 }}>
            {TABS.map(tab => {
              const Icon = tab.icon;
              const active = activeTab === tab.key;
              const count = tab.key === 'agents' ? agents.length : teams.length;
              return (
                <button key={tab.key} onClick={() => { setActiveTab(tab.key); setSelectedAgent(null); }} style={{
                  flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
                  padding: '10px 16px', borderRadius: 10, border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600,
                  background: active ? '#fff' : 'transparent', color: active ? '#2563eb' : '#6b7280',
                  boxShadow: active ? '0 1px 3px rgba(0,0,0,.1)' : 'none', transition: 'all .2s'
                }}>
                  <Icon size={16} /> {tab.label} ({count})
                </button>
              );
            })}
          </div>

          {/* Podium */}
          <Podium items={data} isTeam={activeTab === 'teams'} />

          {/* Agent Detail */}
          {selectedAgent && <AgentDetail agentEmail={selectedAgent} period={period} onClose={() => setSelectedAgent(null)} />}

          {/* Volume Distribution Chart */}
          {data.length > 1 && (
            <div style={{ ...CARD, marginBottom: 24, padding: 20 }}>
              <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 12, display: 'flex', alignItems: 'center', gap: 6 }}>
                <BarChart3 size={16} /> Volume & MSF Distribution
              </div>
              <div style={{ height: 220 }}>
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={data.slice(0, 15)} layout="vertical">
                    <CartesianGrid strokeDasharray="3 3" stroke="#f3f4f6" />
                    <XAxis type="number" tick={{ fontSize: 11 }} tickFormatter={fmtM} />
                    <YAxis dataKey={activeTab === 'teams' ? 'team_lead' : 'agent'} type="category" width={120} tick={{ fontSize: 11 }}
                      tickFormatter={v => v?.includes('@') ? v.split('@')[0] : v?.substring(0, 15)} />
                    <Tooltip formatter={v => fmtM(v)} />
                    <Bar dataKey="total_volume" fill="#3b82f6" radius={[0, 6, 6, 0]} name="Volume" />
                    <Bar dataKey="total_msf" fill="#f59e0b" radius={[0, 6, 6, 0]} name="MSF" />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </div>
          )}

          {/* Full Ranking Table */}
          <LeaderTable items={data} isTeam={activeTab === 'teams'} onAgentClick={(email) => setSelectedAgent(email)} />
        </>
      )}

      <style>{`.spin { animation: spin 1s linear infinite; } @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }`}</style>
    </div>
  );
};

export default SalesLeaderboard;
