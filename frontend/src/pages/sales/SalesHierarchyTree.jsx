import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Globe, Users, User, Loader2, Calendar, RefreshCw, Layers,
  DollarSign, Hash, Percent
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { T, CARD } from '../../theme/salesTokens';

/*
 * Sales Hierarchy Explorer — top-down ORG CHART.
 *   Country Lead (top)  →  Team Leads  →  Sales Agents
 * Each node is a card with an avatar (photo or initials) + its values.
 * Pick a country lead from the chips; the chart is built from /sales-portfolio
 * (country → teams, then one fetch per team → agents). Reads sum_daily_merchant.
 */

const fmt = (v) => v == null ? '—' : Number(v).toLocaleString('en-US', { maximumFractionDigits: 0 });
const fmtM = (v) => { const n = Number(v || 0); if (n >= 1e6) return (n / 1e6).toFixed(2) + 'M'; if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K'; return n.toFixed(0); };

// Tier accents keep their brand-ish hue but route through vars where available.
const TIER = {
  country: { color: 'var(--tier-country, #1E3A8A)', bg: T.indigoBg, icon: Globe, role: 'Country Lead' },
  team:    { color: T.brand,                          bg: T.infoCh,   icon: Users, role: 'Team Lead' },
  agent:   { color: 'var(--tier-agent, #0891b2)',    bg: 'var(--tier-agent-bg, #cffafe)', icon: User, role: 'Sales Agent' },
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

// Connector lines use a CSS variable so they don't disappear on dark backgrounds.
const ORG_CSS = `
.acq-org { display:inline-block; min-width:100%; padding:8px 4px 16px; }
.acq-org ul { list-style:none; margin:0; padding:0; display:flex; justify-content:center; padding-top:26px; position:relative; }
.acq-org li { list-style:none; position:relative; padding:26px 12px 0; display:flex; flex-direction:column; align-items:center; }
.acq-org li::before, .acq-org li::after { content:''; position:absolute; top:0; right:50%; border-top:2px solid var(--org-line, #d3dbe6); width:50%; height:26px; }
.acq-org li::after { right:auto; left:50%; border-left:2px solid var(--org-line, #d3dbe6); }
.acq-org li:only-child::before, .acq-org li:only-child::after { display:none; }
.acq-org li:only-child { padding-top:0; }
.acq-org li:first-child::before, .acq-org li:last-child::after { border:0 none; }
.acq-org li:last-child::before { border-right:2px solid var(--org-line, #d3dbe6); border-radius:0 7px 0 0; }
.acq-org li:first-child::after { border-radius:7px 0 0 0; }
.acq-org ul ul::before { content:''; position:absolute; top:0; left:50%; border-left:2px solid var(--org-line, #d3dbe6); width:0; height:26px; }
.acq-org > ul { padding-top:0; }
.acq-org > ul > li { padding-top:0; }
.acq-org > ul > li::before, .acq-org > ul > li::after { display:none; }
`;

const initials = (name) => {
  if (!name) return '?';
  const parts = String(name).trim().split(/[\s@._-]+/).filter(Boolean);
  return ((parts[0]?.[0] || '') + (parts[1]?.[0] || '')).toUpperCase() || name[0].toUpperCase();
};

function NodeCard({ tier, name, photoUrl, volume, txns, net, sub, width = 170, dim }) {
  const t = TIER[tier];
  return (
    <div style={{
      width, background: T.card, borderRadius: 12, border: `1px solid ${T.border}`,
      borderTop: `3px solid ${t.color}`, boxShadow: T.shadowXs,
      padding: '12px 10px 10px', textAlign: 'center', opacity: dim ? 0.55 : 1,
    }}>
      <div style={{ position: 'relative', width: 46, height: 46, margin: '0 auto 7px' }}>
        {photoUrl ? (
          <img src={photoUrl} alt={name} style={{ width: 46, height: 46, borderRadius: '50%', objectFit: 'cover', border: `2px solid ${t.bg}` }} />
        ) : (
          <div style={{ width: 46, height: 46, borderRadius: '50%', background: t.bg, color: t.color, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 16, fontWeight: 700, border: `2px solid ${T.card}`, boxShadow: `0 0 0 2px ${t.bg}` }}>
            {initials(name)}
          </div>
        )}
      </div>
      <div style={{ fontSize: 13, fontWeight: 700, color: T.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{name}</div>
      <div style={{ fontSize: 10, color: t.color, fontWeight: 600, textTransform: 'uppercase', letterSpacing: 0.4, marginBottom: 7 }}>{t.role}</div>
      <div style={{ fontSize: 17, fontWeight: 800, color: T.text, fontVariantNumeric: 'tabular-nums', lineHeight: 1 }}>{fmtM(volume)}</div>
      <div style={{ fontSize: 9.5, color: T.textMut, marginBottom: 7 }}>volume</div>
      <div style={{ display: 'flex', justifyContent: 'center', gap: 10, borderTop: `1px solid ${T.borderLt}`, paddingTop: 7 }}>
        <span title="Transactions" style={{ display: 'inline-flex', alignItems: 'center', gap: 3, fontSize: 11, color: T.textSec, fontWeight: 600 }}><Hash size={10} />{fmt(txns)}</span>
        <span title="Net revenue (MSF − interchange − scheme fee)" style={{ display: 'inline-flex', alignItems: 'center', gap: 3, fontSize: 11, color: Number(net) < 0 ? T.danger : T.textSec, fontWeight: 600 }}><Percent size={10} />{fmtM(net)}</span>
      </div>
      {sub && <div style={{ fontSize: 10, color: T.textMut, marginTop: 5 }}>{sub}</div>}
    </div>
  );
}

export default function SalesHierarchyTree() {
  const { tenantVersion } = useAuth();
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
          volume: Number(cv.total_volume || 0), txns: Number(cv.txn_count || 0),
          net: Number(cv.net_revenue != null ? cv.net_revenue : cv.total_msf || 0), teamCount: Number(cv.team_count || 0) };
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

  useEffect(() => { loadRoot(); }, [loadRoot, tenantVersion]);

  // Build the org chart for the selected country (root → teams → agents)
  const buildTree = useCallback(async () => {
    if (selected == null) { setTree(null); return; }
    setTreeLoading(true);
    try {
      let rootNode, teamList;
      if (selected === 'unassigned') {
        rootNode = { tier: 'country', label: 'Unassigned', volume: unassignedCv?.total_volume, txns: unassignedCv?.txn_count,
          net: unassignedCv?.net_revenue != null ? unassignedCv.net_revenue : unassignedCv?.total_msf };
        teamList = unmappedTeams.map(t => ({ id: t.teamLeadId, label: t.teamLeadName }));
      } else {
        const cp = await api.get(`/sales-portfolio/country/${selected}`, { params: rangeParams() });
        const d = cp.data;
        rootNode = { tier: 'country', label: d.countryLeadName, volume: d.totalVolume, txns: d.totalTxns,
          net: d.totalNet != null ? d.totalNet : d.totalMsf, photoUrl: d.photoUrl };
        teamList = (d.teams || []).map(t => ({ id: t.team_lead_id, label: t.team_lead_name, volume: t.volume, txns: t.txn_count,
          net: t.net != null ? t.net : t.msf }));
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
          net: t.net != null ? t.net : (d?.totalNet != null ? d.totalNet : d?.totalMsf),
          agents: (d?.agents || []).map(a => ({ id: a.agent, label: a.displayName || a.agent, volume: a.volume, txns: a.txn_count,
            net: a.net != null ? a.net : a.msf, sub: `${fmt(a.merchants)} merchants`, photoUrl: a.photoUrl })),
        };
      });
      setTree({ root: rootNode, teams });
    } catch (e) {
      setErr(e?.response?.data?.error || e.message || 'Failed to build chart');
    } finally { setTreeLoading(false); }
  }, [selected, unmappedTeams, unassignedCv, rangeParams]);

  useEffect(() => { buildTree(); }, [buildTree]);

  const totals = useMemo(() => {
    let vol = 0, net = 0, txns = 0, teams = 0;
    countryCards.forEach(c => { vol += c.volume; net += c.net; txns += c.txns; teams += c.teamCount; });
    if (unassignedCv) {
      vol += Number(unassignedCv.total_volume || 0);
      net += Number(unassignedCv.net_revenue != null ? unassignedCv.net_revenue : unassignedCv.total_msf || 0);
      txns += Number(unassignedCv.txn_count || 0);
    }
    teams += unmappedTeams.length;
    return { vol, net, txns, teams, leads: countryCards.length };
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
          <Layers size={22} color="var(--tier-country, #1E3A8A)" />
          <div>
            <h2 style={{ margin: 0, fontSize: 20, color: T.text }}>Sales Hierarchy Explorer</h2>
            <div style={{ fontSize: 12.5, color: T.textMut }}>Country Lead → Team Leads → Sales Agents</div>
          </div>
        </div>
        <button onClick={loadRoot} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 14px', borderRadius: 8, background: T.card, color: T.textSec, border: `1px solid ${T.border}`, cursor: 'pointer', fontSize: 13, fontWeight: 600 }}>
          <RefreshCw size={14} /> Refresh
        </button>
      </div>

      <div style={{ ...CARD, padding: 14, marginBottom: 16, display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12.5, fontWeight: 600, color: T.textSec }}><Calendar size={15} /> Range</span>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {PRESETS.map(p => (
            <button key={p.value || 'all'} onClick={() => setPeriod(p.value)}
              style={{ padding: '6px 12px', borderRadius: 7, fontSize: 12.5, fontWeight: 600, cursor: 'pointer', border: '1px solid',
                borderColor: period === p.value ? 'var(--tier-country, #1E3A8A)' : T.border, background: period === p.value ? 'var(--tier-country, #1E3A8A)' : T.card, color: period === p.value ? '#fff' : T.textSec }}>
              {p.label}
            </button>
          ))}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginLeft: 'auto' }}>
          <span style={{ fontSize: 12, color: T.textMut }}>Custom</span>
          <input type="date" value={customFrom} max={customTo || undefined} onChange={e => { setCustomFrom(e.target.value); if (e.target.value && customTo) setPeriod('CUSTOM'); }} style={inputStyle} />
          <span style={{ color: T.textMut }}>→</span>
          <input type="date" value={customTo} min={customFrom || undefined} onChange={e => { setCustomTo(e.target.value); if (customFrom && e.target.value) setPeriod('CUSTOM'); }} style={inputStyle} />
          {period === 'CUSTOM' && <button onClick={() => { setPeriod(''); setCustomFrom(''); setCustomTo(''); }} style={{ fontSize: 12, color: T.textSec, background: 'none', border: 'none', cursor: 'pointer', textDecoration: 'underline' }}>clear</button>}
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12, marginBottom: 16 }}>
        <Kpi label="Country Leads" value={fmt(totals.leads)} icon={Globe} color="var(--tier-country, #1E3A8A)" />
        <Kpi label="Teams" value={fmt(totals.teams)} icon={Users} color={T.brand} />
        <Kpi label="Volume" value={fmtM(totals.vol)} icon={DollarSign} color="var(--tier-agent, #0891b2)" />
        <Kpi label="Transactions" value={fmt(totals.txns)} icon={Hash} color="var(--accent-purple, #7c3aed)" />
        <Kpi label="Net Revenue" value={fmtM(totals.net)} icon={Percent} color={T.successDk} />
      </div>

      {/* country-lead selector chips */}
      {!rootLoading && chips.length > 0 && (
        <div style={{ display: 'flex', gap: 8, marginBottom: 14, flexWrap: 'wrap' }}>
          {chips.map(c => (
            <button key={c.k} onClick={() => setSelected(c.k)}
              style={{ display: 'inline-flex', alignItems: 'center', gap: 7, padding: '7px 13px', borderRadius: 20, fontSize: 12.5, fontWeight: 600, cursor: 'pointer',
                border: '1px solid', borderColor: selected === c.k ? 'var(--tier-country, #1E3A8A)' : T.border,
                background: selected === c.k ? 'var(--tier-country, #1E3A8A)' : T.card, color: selected === c.k ? '#fff' : T.textSec }}>
              <Globe size={13} /> {c.label}
              <span style={{ fontSize: 11, opacity: 0.85, fontWeight: 700 }}>{fmtM(c.vol)}</span>
            </button>
          ))}
        </div>
      )}

      <div style={{ ...CARD, padding: 0, overflowX: 'auto' }}>
        {rootLoading ? (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, padding: 50, color: T.textMut }}><Loader2 size={18} className="acq-spin" /> Loading…</div>
        ) : err ? (
          <div style={{ padding: 24, color: T.danger, fontSize: 13 }}>{err}</div>
        ) : !tree ? (
          <div style={{ padding: 36, textAlign: 'center', color: T.textMut, fontSize: 13.5 }}>No country leads or teams yet.</div>
        ) : (
          <div style={{ position: 'relative' }}>
            {treeLoading && (
              <div style={{ position: 'absolute', top: 10, right: 14, display: 'inline-flex', gap: 6, alignItems: 'center', fontSize: 12, color: T.textMut }}>
                <Loader2 size={13} className="acq-spin" /> updating…
              </div>
            )}
            <div className="acq-org">
              <ul>
                <li>
                  <NodeCard tier="country" name={tree.root.label} photoUrl={tree.root.photoUrl}
                    volume={tree.root.volume} txns={tree.root.txns} net={tree.root.net} width={186} dim={treeLoading} />
                  {tree.teams.length > 0 && (
                    <ul>
                      {tree.teams.map(tm => (
                        <li key={tm.id}>
                          <NodeCard tier="team" name={tm.label} volume={tm.volume} txns={tm.txns} net={tm.net} dim={treeLoading} />
                          {tm.agents.length > 0 && (
                            <ul>
                              {tm.agents.map(a => (
                                <li key={a.id}>
                                  <NodeCard tier="agent" name={a.label} photoUrl={a.photoUrl}
                                    volume={a.volume} txns={a.txns} net={a.net} sub={a.sub} dim={treeLoading} />
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
              <div style={{ textAlign: 'center', padding: '0 24px 28px', color: T.textMut, fontSize: 12.5, fontStyle: 'italic' }}>
                {selected === 'unassigned' ? 'No unmapped teams.' : 'No teams mapped to this country lead yet — map one via Sales → Country Lead Management.'}
              </div>
            )}
          </div>
        )}
      </div>

      {!rootLoading && totals.vol === 0 && chips.length > 0 && (
        <div style={{ ...CARD, padding: 14, marginTop: 12, background: T.warningBg, borderColor: 'var(--warning-border, #fde68a)', fontSize: 12.5, color: T.warningTx, lineHeight: 1.6 }}>
          <b>All volumes are zero.</b> Either the team isn't mapped to a country lead (it's under <b>Unassigned</b> — map it via <i>Sales → Country Lead Management → Auto-assign</i>), no transaction file has been loaded yet (<code>sum_daily_merchant</code> empty), or merchants aren't linked to agents (<code>dim_merchant.sales_user_id</code> blank).
        </div>
      )}
      <div style={{ fontSize: 11.5, color: T.textMut, marginTop: 10 }}>Volume is the single-currency settlement figure (store base), from the pre-aggregated daily summary. Avatars show initials; photo support can be added per agent.</div>
    </div>
  );
}

const inputStyle = { padding: '6px 8px', borderRadius: 7, border: `1px solid ${T.border}`, fontSize: 12.5, color: T.textSec, fontFamily: 'inherit', background: T.card };

function Kpi({ label, value, icon: Icon, color }) {
  return (
    <div style={{ ...CARD, padding: 14, display: 'flex', alignItems: 'center', gap: 12 }}>
      <span style={{ display: 'inline-flex', width: 36, height: 36, borderRadius: 9, alignItems: 'center', justifyContent: 'center', background: `color-mix(in srgb, ${color} 12%, transparent)`, color }}><Icon size={18} /></span>
      <div>
        <div style={{ fontSize: 19, fontWeight: 700, color: T.text, fontVariantNumeric: 'tabular-nums' }}>{value}</div>
        <div style={{ fontSize: 11.5, color: T.textMut }}>{label}</div>
      </div>
    </div>
  );
}
