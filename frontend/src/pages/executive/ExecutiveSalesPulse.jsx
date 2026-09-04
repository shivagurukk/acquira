import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { Activity, Loader2 } from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { formatCompactCurrency } from '../../utils/formatters';
import { T, CARD } from '../../theme/salesTokens';
import PulseHeroBand from '../../components/pulse/PulseHeroBand';
import MarginGlossaryHint from '../../components/MarginGlossary';
import TeamRacePanel from '../../components/pulse/TeamRacePanel';
import SpotlightPanel from '../../components/pulse/SpotlightPanel';
import TeamLeadSection from '../../components/pulse/TeamLeadSection';
import SalesExecutiveDetailDrawer from '../../components/pulse/SalesExecutiveDetailDrawer';

/*
 * EXECUTIVE SALES PULSE
 *
 * A C-level read of the sales organisation: how much was sold, which direction
 * it is moving, which Team Lead is strongest, and — the part that earns the page
 * — who is improving and who needs attention, derived automatically from history
 * rather than left for the reader to spot.
 *
 * NOT a CRM view. There is no pipeline, deal or opportunity here, because
 * Acquira holds no such data; "sales" is realised net margin from ingested
 * merchant activity, the same measure the Leaderboard and Sales Portfolio pages
 * rank on.
 *
 * TARGETS ARE OPTIONAL. The page ships before any target has been entered. Every
 * target cell then reads "—" and nothing else changes: momentum, growth and
 * signals come from history alone. A salesperson is never marked down for the
 * absence of a target.
 *
 * PERIODS ARE ANCHORED TO THE DATA. The period keywords are resolved server-side
 * against the tenant's latest business_date, so "this month" means month-to-date
 * of the DATA. With ingestion running a few days behind, a calendar-anchored page
 * would show a half-empty month and read as a collapse in sales.
 */

const PERIODS = [
  { value: 'MTD',           label: 'This Month' },
  { value: 'LAST_MONTH',    label: 'Last Month' },
  { value: 'QTD',           label: 'This Quarter' },
  { value: 'LAST_QUARTER',  label: 'Last Quarter' },
  { value: 'YTD',           label: 'This Year' },
  { value: 'CUSTOM',        label: 'Custom Range' },
];

const NARROW_AT = 900;

export default function ExecutiveSalesPulse() {
  const { tenantVersion } = useAuth();

  const [period, setPeriod] = useState('MTD');
  const [range, setRange] = useState({ from: '', to: '' });
  const [teamLeadId, setTeamLeadId] = useState('');
  const [countryLeadId, setCountryLeadId] = useState('');

  const [data, setData] = useState(null);
  const [teamLeads, setTeamLeads] = useState([]);
  const [countryLeads, setCountryLeads] = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');
  // Collapsed by default: the hero, race and spotlight ARE the summary — the
  // tables are detail, opened per-team (or via the Team Race).
  const [expanded, setExpanded] = useState(() => new Set());
  const [selected, setSelected] = useState(null);

  const [narrow, setNarrow] = useState(() => window.innerWidth < NARROW_AT);
  useEffect(() => {
    const onResize = () => setNarrow(window.innerWidth < NARROW_AT);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  // A custom range is only sent once BOTH ends are set. An empty period keyword
  // means "all time" to the backend, so a half-filled custom range would swing
  // the whole page to all-time between the two clicks — hold on This Month until
  // the range is complete.
  const query = useMemo(() => {
    const custom = period === 'CUSTOM' && range.from && range.to;
    const q = { period: custom ? '' : (period === 'CUSTOM' ? 'MTD' : period) };
    if (custom) {
      q.dateFrom = range.from;
      q.dateTo = range.to;
    }
    if (teamLeadId) q.teamLeadId = teamLeadId;
    if (countryLeadId) q.countryLeadId = countryLeadId;
    return q;
  }, [period, range.from, range.to, teamLeadId, countryLeadId]);

  const fetchPulse = useCallback(async () => {
    setLoading(true); setErr('');
    try {
      const res = await api.get('/executive/sales-pulse', { params: query });
      setData(res.data);
    } catch (e) {
      setErr(e?.response?.data?.error || 'Could not load the sales pulse.');
      setData(null);
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => { fetchPulse(); }, [fetchPulse, tenantVersion]);

  // Filter options. Failures here are non-fatal: the page still works with the
  // filters empty, so a broken lookup must not take the whole screen down.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [teams, countries] = await Promise.all([
          api.get('/sales-team/team-leads').catch(() => ({ data: [] })),
          api.get('/sales-country-lead/country-leads').catch(() => ({ data: [] })),
        ]);
        if (cancelled) return;
        setTeamLeads(Array.isArray(teams.data) ? teams.data : []);
        setCountryLeads(Array.isArray(countries.data) ? countries.data : []);
      } catch { /* filters stay empty */ }
    })();
    return () => { cancelled = true; };
  }, [tenantVersion]);

  // Money always renders in the TENANT's currency and precision, from the block
  // the API stamps on the response — never a client-side guess, which goes wrong
  // the moment someone switches tenant without a reload (and silently drops fils
  // on 3-decimal currencies).
  const money = useCallback((v) => {
    if (v == null) return '—';
    const c = data?.currency;
    return c?.resolved ? formatCompactCurrency(v, c.code, c.decimals) : formatCompactCurrency(v);
  }, [data?.currency]);

  const teams = data?.teams || [];
  const isOpen = (key) => expanded.has(String(key));
  const toggle = (key) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      const k = String(key);
      if (next.has(k)) next.delete(k); else next.add(k);
      return next;
    });
  };

  // Team Race → this team's detail section: make sure it is open, then scroll.
  const focusTeam = (key) => {
    setExpanded((prev) => new Set(prev).add(String(key)));
    requestAnimationFrame(() => {
      document.getElementById(`pulse-team-${key}`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  };

  // The spotlight reads across every team, so each row is stamped with its
  // team lead's name on the way through.
  const allExecutives = useMemo(
    () => teams.flatMap((t) => (t.salesExecutives || []).map((m) => ({
      ...m,
      // "Unassigned" is a bucket, not a person — "Unassigned's team" reads wrong.
      teamLeadName: t.teamLeadId != null ? t.teamLeadName : null,
    }))),
    [teams],
  );

  const periodLabel = data?.period?.from
    ? `${data.period.from} → ${data.period.to}`
    : PERIODS.find((p) => p.value === period)?.label;

  return (
    <div style={{ padding: narrow ? 12 : 20, background: T.bg, minHeight: '100%' }}>

      {/* ── Header + filters ────────────────────────────────────────────── */}
      <div style={{
        display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between',
        gap: 14, flexWrap: 'wrap', marginBottom: 14,
      }}>
        <div>
          <h1 style={{ display: 'flex', alignItems: 'center', gap: 9, margin: 0, fontSize: 21, fontWeight: 700, color: T.text }}>
            <Activity size={21} color={T.brand} /> Executive Sales Pulse
          </h1>
          <p style={{ margin: '4px 0 0', fontSize: 12, color: T.textMut, display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <span>
              Who is performing, who is improving, and where leadership should look.
              {data?.dataThrough && ` Data through ${data.dataThrough}.`}
            </span>
            <MarginGlossaryHint compact />
          </p>
        </div>

        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <Select value={period} onChange={setPeriod} aria-label="Period">
            {PERIODS.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
          </Select>

          {period === 'CUSTOM' && (
            <>
              <DateInput value={range.from} onChange={(v) => setRange((r) => ({ ...r, from: v }))} label="From" />
              <DateInput value={range.to} onChange={(v) => setRange((r) => ({ ...r, to: v }))} label="To" />
            </>
          )}

          {countryLeads.length > 0 && (
            <Select value={countryLeadId} onChange={setCountryLeadId} aria-label="Region">
              <option value="">All Regions</option>
              {countryLeads.map((c) => (
                <option key={c.id} value={c.id}>{c.countryLeadName}</option>
              ))}
            </Select>
          )}

          <Select value={teamLeadId} onChange={setTeamLeadId} aria-label="Team Lead">
            <option value="">All Team Leads</option>
            {teamLeads.map((t) => (
              <option key={t.id} value={t.id}>{t.teamLeadName}</option>
            ))}
          </Select>

          {/* Every filter change refetches on its own; a Refresh button implied
              the page could go stale, which it cannot. A quiet spinner covers
              the in-flight moment. */}
          {loading && <Loader2 size={15} className="spin" color={T.textMut} />}
        </div>
      </div>

      {err && (
        <div style={{ ...CARD, padding: 14, marginBottom: 12, background: T.dangerBg, borderColor: T.danger, color: T.dangerTx, fontSize: 13 }}>
          {err}
        </div>
      )}

      {/* ── Hero band: headline, insight, org trend ─────────────────────── */}
      {data && (
        <PulseHeroBand data={data} money={money} periodLabel={periodLabel} narrow={narrow} />
      )}

      {/* ── Race + spotlight: teams ranked on the left, people on the right.
             Both panels hide themselves when they have nothing to say, and the
             grid collapses around whichever remains. ──────────────────────── */}
      {data && (
        <div style={{
          display: 'grid', gap: 16, alignItems: 'start',
          gridTemplateColumns: narrow ? '1fr' : 'repeat(auto-fit, minmax(360px, 1fr))',
        }}>
          <TeamRacePanel teams={teams} money={money} narrow={narrow} onFocusTeam={focusTeam} />
          <SpotlightPanel executives={allExecutives} money={money} narrow={narrow} onSelect={setSelected} />
        </div>
      )}

      {/* ── Teams ───────────────────────────────────────────────────────── */}
      {loading && !data ? (
        <div style={{ ...CARD, padding: 40, textAlign: 'center', color: T.textMut, fontSize: 13 }}>
          <Loader2 size={20} className="spin" />
          <div style={{ marginTop: 8 }}>Loading sales performance…</div>
        </div>
      ) : teams.length === 0 ? (
        <div style={{ ...CARD, padding: 40, textAlign: 'center' }}>
          <div style={{ fontSize: 14, fontWeight: 600, color: T.text, marginBottom: 4 }}>
            No sales activity is available for the selected period.
          </div>
          <div style={{ fontSize: 12.5, color: T.textMut }}>
            Try a wider period, or clear the region and team filters.
          </div>
        </div>
      ) : (
        teams.map((team) => {
          const key = team.teamLeadId ?? team.teamLeadName;
          return (
            /* The wrapper carries the anchor the Team Race scrolls to. */
            <div key={key} id={`pulse-team-${key}`} style={{ scrollMarginTop: 12 }}>
              <TeamLeadSection
                team={team}
                money={money}
                narrow={narrow}
                expanded={isOpen(key)}
                onToggle={toggle}
                onSelect={setSelected}
              />
            </div>
          );
        })
      )}

      {selected && (
        <SalesExecutiveDetailDrawer
          agent={selected}
          query={query}
          money={money}
          onClose={() => setSelected(null)}
        />
      )}
    </div>
  );
}

// ── Small form controls, styled to the sales suite ──────────────────────────
const controlStyle = {
  padding: '7px 10px', borderRadius: 8, border: `1px solid ${T.border}`,
  background: T.card, color: T.text, fontSize: 12.5, fontWeight: 500, cursor: 'pointer',
};

const Select = ({ value, onChange, children, ...rest }) => (
  <select value={value} onChange={(e) => onChange(e.target.value)} style={controlStyle} {...rest}>
    {children}
  </select>
);

const DateInput = ({ value, onChange, label }) => (
  <input
    type="date" value={value} aria-label={label} title={label}
    onChange={(e) => onChange(e.target.value)}
    style={{ ...controlStyle, cursor: 'text' }}
  />
);
