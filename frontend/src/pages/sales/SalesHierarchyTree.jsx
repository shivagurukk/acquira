import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Globe, Users, User, Loader2, Calendar, RefreshCw, Layers,
  DollarSign, Hash, Percent
} from 'lucide-react';
import api from '../../api/axios';

/*
 * Sales Hierarchy Explorer — top-down ORG CHART.
 *   Country Lead (top)  →  Team Leads  →  Sales Agents
 * Each node is a card with an avatar (photo or initials) + its values.
 * Pick a country lead from the chips; the chart is built from /sales-portfolio
 * (country → teams, then one fetch per team → agents). Reads sum_daily_merchant.
 */

const CARD = { background: '#fff', borderRadius: 14, padding: 20, boxShadow: '0 1px 4px rgba(0,0,0,.06)', border: '1px solid #eef0f4' };
const fmt = (v) => v == null ? '—' : Number(v).toLocaleString('en-US', { maximumFractionDigits: 0 });
const fmtM = (v) => { const n = Number(v || 0); if (n >= 1e6) return (n / 1e6).toFixed(2) + 'M'; if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K'; return n.toFixed(0); };

const TIER = {
  country: { color: '#1E3A8A', bg: '#e0e7ff', icon: Globe, role: 'Country Lead' },
  team:    { color: '#2563eb', bg: '#dbeafe', icon: Users, role: 'Team Lead' },
  agent:   { color: '#0891b2', bg: '#cffafe', icon: User,  role: 'Sales Agent' },
};

const PRESETS = [
  { label: 'All Time', value: '' }, { label: 'MTD', value: 'MTD' }, { label: 'QTD', value: 'QTD' },
  { label: 'YTD', value: 'YTD' }, { label: 'Last Month', value: 'LAST_MONTH' },
];
const isoLocal = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
function periodToRange(p) {
  const t = new Date(), y = t.getFullYear(), m = t.getMonth();
  switch (p) {
    case 'MTD': return { from: isoLocal(new Date(y, m, 1)), to: isoLocal(t) };
    case 'QTD': return { from: isoLocal(new Date(y, Math.floor(m / 3) * 3, 1)), to: isoLocal(t) };
    case 'YTD': return { from: isoLocal(new Date(y, 0, 1)), to: isoLocal(t) };
    case 'LAST_MONTH': return { from: isoLocal(new Date(y, m - 1, 1)), to: isoLocal(new Date(y, m, 0)) };
    default: return { from: '', to: '' };
  }
}

const ORG_CSS = `
.acq-org { display:inline-block; min-width:100%; padding:8px 4px 16px; }
.acq-org ul { list-style:none; margin:0; padding:0; display:flex; justify-content:center; padding-top:26px; position:relative; }
.acq-org li { list-style:none; position:relative; padding:26px 12px 0; display:flex; flex-direction:column; align-items:center; }
.acq-org li::before, .acq-org li::after { content:''; position:absolute; top:0; right:50%; border-top:2px solid #d3dbe6; width:50%; height:26px; }
.acq-org li::after { right:auto; left:50%; border-left:2px solid #d3dbe6; }
.acq-org li:only-child::before, .acq-org li:only-child::after { display:none; }
.acq-org li:only-child { padding-top:0; }
.acq-org li:first-child::before, .acq-org li:last-child::after { border:0 none; }
.acq-org li:last-child::before { border-right:2px solid #d3dbe6; border-radius:0 7px 0 0; }
.acq-org li:first-child::after { border-radius:7px 0 0 0; }
.acq-org ul ul::before { content:''; position:absolute; top:0; left:50%; border-left:2px solid #d3dbe6; width:0; height:26px; }
.acq-org > ul { padding-top:0; }
.acq-org > ul > li { padding-top:0; }
.acq-org > ul > li::before, .acq-org > ul > li::after { display:none; }
`;

const initials = (name) => {
  if (!name) return '?';
  const parts = String(name).trim().split(/[\s@._-]+/).filter(Boolean);
  return ((parts[0]?.[0] || '') + (parts[1]?.[0] || '')).toUpperCase() || name[0].toUpperCase();
};

function NodeCard({ tier, name, photoUrl, volume, txns, msf, sub, width = 170, dim }) {
  const t = TIER[tier];
  return (
    <div style={{
      width, background: '#fff', borderRadius: 12, border: '1px solid #e8edf3',
      borderTop: `3px solid ${t.color}`, boxShadow: '0 2px 6px rgba(15,23,42,.06)',
      padding: '12px 10px 10px', textAlign: 'center', opacity: dim ? 0.55 : 1,
    }}>
      <div style={{ position: 'relative', width: 46, height: 46, margin: '0 auto 7px' }}>
        {photoUrl ? (
          <img src={photoUrl} alt={name} style={{ width: 46, height: 46, borderRadius: '50%', objectFit: 'cover', border: `2px solid ${t.bg}` }} />
        ) : (
          <div style={{ width: 46, height: 46, borderRadius: '50%', background: t.bg, color: t.color, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 16, fontWeight: 700, border: `2px solid #fff`, boxShadow: `0 0 0 2px ${t.bg}` }}>
            {initials(name)}
          </div>
        )}
      </div>
      <div style={{ fontSize: 13, fontWeight: 700, color: '#0f172a', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{name}</div>
      <div style={{ fontSize: 10, color: t.color, fontWeight: 600, textTransform: 'uppercase', letterSpacing: 0.4, marginBottom: 7 }}>{t.role}</div>
      <div style={{ fontSize: 17, fontWeight: 800, color: '#0f172a', fontVariantNumeric: 'tabular-nums', lineHeight: 1 }}>{fmtM(volume)}</div>
      <div style={{ fontSize: 9.5, color: '#94a3b8', marginBottom: 7 }}>volume</div>
      <div style={{ display: 'flex', justifyContent: 'center', gap: 10, borderTop: '1px solid #f1f5f9', paddingTop: 7 }}>
        <span title="Transactions" style={{ display: 'inline-flex', alignItems: 'center', gap: 3, fontSize: 11, color: '#64748b', fontWeight: 600 }}><Hash size={10} />{fmt(txns)}</span>
        <span title="MSF" style={{ display: 'inline-flex', alignItems: 'center', gap: 3, fontSize: 11, color: '#64748b', fontWeight: 600 }}><Percent size={10} />{fmtM(msf)}</span>
      </div>
      {sub && <div style={{ fontSize: 10, color: '#94a3b8', marginTop: 5 }}>{sub}</div>}
    </div>
  );
}

export default function SalesHierarchyTree() {
  const [period, setPeriod] = useState('');
  const [customFrom, setCustomFrom] = useState('');
  const [customTo, setCustomTo] = useState('');

  const [countryCards, setCountryCards] = useState([]);
  const [unmappedTeams, setUnmappedTeams] = useState([]);
  const [unassignedCv, setUnassignedCv] = useState(null);
  const [selected, setSelected] = useState(null);    // country id  | 'unassigned'
  const [rootLoading, setRootLoading] = useState(true);
  const [err, setErr] = useState('');

  const [tree, setTree] = useState(null);            // { root, teams:[{...,agents:[]}] }
  const [treeLoading, setTreeLoading] = useState(false);

  const range = useMemo(() => period === 'CUSTOM' ? { from: customFrom, to: customTo } : periodToRange(period), [period, customFrom, customTo]);
  const rangeParams = useCallback(() => (range.from && range.to ? { dateFrom: range.from, dateTo: range.to } : {}), [range]);

  const loadRoot = useCallback(async () => {
    setRootLoading(true); setErr('');
    try {
      const params = range.from && range.to ? { dateFrom: range.from, dateTo: range.to } : {};
      const [leadsRes, cvRes, tlRes] = await Promise.all([
        api.get('/sales-country-lead/country-leads'),
        api.get('/leaderboard/countries', { params }),
        api.get('/sales-country-lead/team-leads'),
      ]);
      const leads = leadsRes.data || [], cvRows = cvRes.data || [], teamLeads = tlRes.data || [];
      const byEmail = {}, byName = {};
      cvRows.forEach(r => { if (r.country_lead_email) byEmail[String(r.country_lead_email).toLowerCase()] = r; if (r.country_lead) byName[String(r.country_lead)] = r; });
      const cards = leads.map(l => {
        const cv = (l.countryLeadEmail && byEmail[String(l.countryLeadEmail).toLowerCase()]) || byName[l.countryLeadName] || {};
        return { id: l.id, label: l.countryLeadName, countryCode: l.countryCode,
          volume: Number(cv.total_volume || 0), txns: Number(cv.txn_count || 0), msf: Number(cv.total_msf || 0), teamCount: Number(cv.team_count || 0) };
      });
      const un = byName['Unassigned'] || null;
      const unmapped = teamLeads.filter(t => t.countryLeadId == null);
      setCountryCards(cards); setUnassignedCv(un); setUnmappedTeams(unmapped);

      // default selection = highest-volume entity so data shows up immediately
      setSelected(prev => {
        if (prev != null) return prev;
        const all = [...cards.map(c => ({ k: c.id, v: c.volume })), ...(un || unmapped.length ? [{ k: 'unassigned', v: Number(un?.total_volume || 0) }] : [])];
        all.sort((a, b) => b.v - a.v);
        return all.length ? all[0].k : null;
      });
    } catch (e) {
      setErr(e?.response?.data?.error || e.message || 'Failed to load');
    } finally { setRootLoading(false); }
  }, [range]);

  useEffect(() => { loadRoot(); }, [loadRoot]);

  // Build the org chart for the selected country (root → teams → agents)
  const buildTree = useCallback(async () => {
    if (selected == null) { setTree(null); return; }
    setTreeLoading(true);
    try {
      let rootNode, teamList;
      if (selected === 'unassigned') {
        rootNode = { tier: 'country', label: 'Unassigned', volume: unassignedCv?.total_volume, txns: unassignedCv?.txn_count, msf: unassignedCv?.total_msf };
        teamList = unmappedTeams.map(t => ({ id: t.teamLeadId, label: t.teamLeadName }));
      } else {
        const cp = await api.get(`/sales-portfolio/country/${selected}`, { params: rangeParams() });
        const d = cp.data;
        rootNode = { tier: 'country', label: d.countryLeadName, volume: d.totalVolume, txns: d.totalTxns, msf: d.totalMsf, photoUrl: d.photoUrl };
        teamList = (d.teams || []).map(t => ({ id: t.team_lead_id, label: t.team_lead_name, volume: t.volume, txns: t.txn_count, msf: t.msf }));
      }

      // fetch agents for each team in parallel
      const teamPortfolios = await Promise.allSettled(
        teamList.map(t => api.get(`/sales-portfolio/team/${t.id}`, { params: rangeParams() }))
      );
      const teams = teamList.map((t, i) => {
        const r = teamPortfolios[i];
        const d = r.status === 'fulfilled' ? r.value.data : null;
        return {
          ...t,
          volume: t.volume != null ? t.volume : d?.totalVolume,
          txns: t.txns != null ? t.txns : d?.totalTxns,
          msf: t.msf != null ? t.msf : d?.totalMsf,
          agents: (d?.agents || []).map(a => ({ id: a.agent, label: a.displayName || a.agent, volume: a.volume, txns: a.txn_count, msf: a.msf, sub: `${fmt(a.merchants)} merchants`, photoUrl: a.photoUrl })),
        };
      });
      setTree({ root: rootNode, teams });
    } catch (e) {
      setErr(e?.response?.data?.error || e.message || 'Failed to build chart');
    } finally { setTreeLoading(false); }
  }, [selected, unmappedTeams, unassignedCv, rangeParams]);

  useEffect(() => { buildTree(); }, [buildTree]);

  const totals = useMemo(() => {
    let vol = 0, msf = 0, txns = 0, teams = 0;
    countryCards.forEach(c => { vol += c.volume; msf += c.msf; txns += c.txns; teams += c.teamCount; });
    if (unassignedCv) { vol += Number(unassignedCv.total_volume || 0); msf += Number(unassignedCv.total_msf || 0); txns += Number(unassignedCv.txn_count || 0); }
    teams += unmappedTeams.length;
    return { vol, msf, txns, teams, leads: countryCards.length };
  }, [countryCards, unassignedCv, unmappedTeams]);

  const chips = [
    ...countryCards.map(c => ({ k: c.id, label: c.label, vol: c.volume })),
    ...(unassignedCv || unmappedTeams.length ? [{ k: 'unassigned', label: 'Unassigned', vol: Number(unassignedCv?.total_volume || 0) }] : []),
  ];

  return (
    <div style={{ padding: 24, fontFamily: 'Inter, sans-serif', maxWidth: 1280, margin: '0 auto' }}>
      <style>{`@keyframes acqspin{to{transform:rotate(360deg)}} .acq-spin{animation:acqspin .8s linear infinite} ${ORG_CSS}`}</style>

      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16, flexWrap: 'wrap', gap: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <Layers size={22} color="#1E3A8A" />
          <div>
            <h2 style={{ margin: 0, fontSize: 20, color: '#0f172a' }}>Sales Hierarchy Explorer</h2>
            <div style={{ fontSize: 12.5, color: '#94a3b8' }}>Country Lead → Team Leads → Sales Agents</div>
          </div>
        </div>
        <button onClick={loadRoot} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 14px', borderRadius: 8, background: '#fff', color: '#334155', border: '1px solid #e2e8f0', cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
          <RefreshCw size={14} /> Refresh
        </button>
      </div>

      <div style={{ ...CARD, padding: 14, marginBottom: 16, display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12.5, fontWeight: 600, color: '#64748b' }}><Calendar size={15} /> Range</span>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {PRESETS.map(p => (
            <button key={p.value || 'all'} onClick={() => setPeriod(p.value)}
              style={{ padding: '6px 12px', borderRadius: 7, fontSize: 12.5, fontWeight: 600, cursor: 'pointer', border: '1px solid',
                borderColor: period === p.value ? '#1E3A8A' : '#e2e8f0', background: period === p.value ? '#1E3A8A' : '#fff', color: period === p.value ? '#fff' : '#475569' }}>
              {p.label}
            </button>
          ))}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginLeft: 'auto' }}>
          <span style={{ fontSize: 12, color: '#94a3b8' }}>Custom</span>
          <input type="date" value={customFrom} max={customTo || undefined} onChange={e => { setCustomFrom(e.target.value); if (e.target.value && customTo) setPeriod('CUSTOM'); }} style={inputStyle} />
          <span style={{ color: '#cbd5e1' }}>→</span>
          <input type="date" value={customTo} min={customFrom || undefined} onChange={e => { setCustomTo(e.target.value); if (customFrom && e.target.value) setPeriod('CUSTOM'); }} style={inputStyle} />
          {period === 'CUSTOM' && <button onClick={() => { setPeriod(''); setCustomFrom(''); setCustomTo(''); }} style={{ fontSize: 12, color: '#64748b', background: 'none', border: 'none', cursor: 'pointer', textDecoration: 'underline' }}>clear</button>}
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12, marginBottom: 16 }}>
        <Kpi label="Country Leads" value={fmt(totals.leads)} icon={Globe} color="#1E3A8A" />
        <Kpi label="Teams" value={fmt(totals.teams)} icon={Users} color="#2563eb" />
        <Kpi label="Volume" value={fmtM(totals.vol)} icon={DollarSign} color="#0891b2" />
        <Kpi label="Transactions" value={fmt(totals.txns)} icon={Hash} color="#7c3aed" />
        <Kpi label="MSF Revenue" value={fmtM(totals.msf)} icon={Percent} color="#16a34a" />
      </div>

      {/* country-lead selector chips */}
      {!rootLoading && chips.length > 0 && (
        <div style={{ display: 'flex', gap: 8, marginBottom: 14, flexWrap: 'wrap' }}>
          {chips.map(c => (
            <button key={c.k} onClick={() => setSelected(c.k)}
              style={{ display: 'inline-flex', alignItems: 'center', gap: 7, padding: '7px 13px', borderRadius: 20, fontSize: 12.5, fontWeight: 600, cursor: 'pointer',
                border: '1px solid', borderColor: selected === c.k ? '#1E3A8A' : '#e2e8f0',
                background: selected === c.k ? '#1E3A8A' : '#fff', color: selected === c.k ? '#fff' : '#475569' }}>
              <Globe size={13} /> {c.label}
              <span style={{ fontSize: 11, opacity: 0.85, fontWeight: 700 }}>{fmtM(c.vol)}</span>
            </button>
          ))}
        </div>
      )}

      <div style={{ ...CARD, padding: 0, overflowX: 'auto' }}>
        {rootLoading ? (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, padding: 50, color: '#94a3b8' }}><Loader2 size={18} className="acq-spin" /> Loading…</div>
        ) : err ? (
          <div style={{ padding: 24, color: '#dc2626', fontSize: 13 }}>{err}</div>
        ) : !tree ? (
          <div style={{ padding: 36, textAlign: 'center', color: '#94a3b8', fontSize: 13.5 }}>No country leads or teams yet.</div>
        ) : (
          <div style={{ position: 'relative' }}>
            {treeLoading && (
              <div style={{ position: 'absolute', top: 10, right: 14, display: 'inline-flex', gap: 6, alignItems: 'center', fontSize: 12, color: '#94a3b8' }}>
                <Loader2 size={13} className="acq-spin" /> updating…
              </div>
            )}
            <div className="acq-org">
              <ul>
                <li>
                  <NodeCard tier="country" name={tree.root.label} photoUrl={tree.root.photoUrl}
                    volume={tree.root.volume} txns={tree.root.txns} msf={tree.root.msf} width={186} dim={treeLoading} />
                  {tree.teams.length > 0 && (
                    <ul>
                      {tree.teams.map(tm => (
                        <li key={tm.id}>
                          <NodeCard tier="team" name={tm.label} volume={tm.volume} txns={tm.txns} msf={tm.msf} dim={treeLoading} />
                          {tm.agents.length > 0 && (
                            <ul>
                              {tm.agents.map(a => (
                                <li key={a.id}>
                                  <NodeCard tier="agent" name={a.label} photoUrl={a.photoUrl}
                                    volume={a.volume} txns={a.txns} msf={a.msf} sub={a.sub} dim={treeLoading} />
                                </li>
                              ))}
                            </ul>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              </ul>
            </div>
            {tree.teams.length === 0 && (
              <div style={{ textAlign: 'center', padding: '0 24px 28px', color: '#cbd5e1', fontSize: 12.5, fontStyle: 'italic' }}>
                {selected === 'unassigned' ? 'No unmapped teams.' : 'No teams mapped to this country lead yet — map one via Sales → Country Lead Management.'}
              </div>
            )}
          </div>
        )}
      </div>

      {!rootLoading && totals.vol === 0 && chips.length > 0 && (
        <div style={{ ...CARD, padding: 14, marginTop: 12, background: '#fffbeb', borderColor: '#fde68a', fontSize: 12.5, color: '#92400e', lineHeight: 1.6 }}>
          <b>All volumes are zero.</b> Either the team isn't mapped to a country lead (it's under <b>Unassigned</b> — map it via <i>Sales → Country Lead Management → Auto-assign</i>), no transaction file has been loaded yet (<code>sum_daily_merchant</code> empty), or merchants aren't linked to agents (<code>dim_merchant.sales_user_id</code> blank).
        </div>
      )}
      <div style={{ fontSize: 11.5, color: '#cbd5e1', marginTop: 10 }}>Volume is the single-currency settlement figure (store base), from the pre-aggregated daily summary. Avatars show initials; photo support can be added per agent.</div>
    </div>
  );
}

const inputStyle = { padding: '6px 8px', borderRadius: 7, border: '1px solid #e2e8f0', fontSize: 12.5, color: '#334155', fontFamily: 'inherit' };

function Kpi({ label, value, icon: Icon, color }) {
  return (
    <div style={{ ...CARD, padding: 14, display: 'flex', alignItems: 'center', gap: 12 }}>
      <span style={{ display: 'inline-flex', width: 36, height: 36, borderRadius: 9, alignItems: 'center', justifyContent: 'center', background: color + '14', color }}><Icon size={18} /></span>
      <div>
        <div style={{ fontSize: 19, fontWeight: 700, color: '#0f172a', fontVariantNumeric: 'tabular-nums' }}>{value}</div>
        <div style={{ fontSize: 11.5, color: '#94a3b8' }}>{label}</div>
      </div>
    </div>
  );
}
