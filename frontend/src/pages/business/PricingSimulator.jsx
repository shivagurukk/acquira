import React, { useState, useEffect, useMemo, useCallback } from 'react';
import api from '../../api/axios';
import { explorerApi } from '../../api/explorer';
import { formatCompactCurrency } from '../../utils/formatters';

/*
 * What-If Pricing Simulator
 * -------------------------
 * Pick a cohort (whole bank, or by MCC / RM / referral partner / card scheme /
 * merchant MID), then drag three levers — MSF rate, scheme mix, DCC opt-in —
 * and watch projected net margin and a modeled churn-risk delta update live.
 *
 * Data sourcing (SUMMARY GRAIN ONLY — no fact_transaction scan):
 *   - Cohort headline + per-scheme mix: POST /analytics/explorer/query with
 *     ONLY summary-capable measures (total_volume, total_msf, txn_count), which
 *     routes to sum_daily_insight (pre-aggregated, dimensional, cohort-filtered).
 *   - DCC block + effective MSF rate: POST /business/revenue-kpis (sum_daily_
 *     insight for the filtered rate; sum_daily_merchant for the dcc_* columns).
 *   - Cost rate: sum_daily_insight carries no interchange/scheme/VAT, so the
 *     cohort COST rate is taken from the bank-grain take-rate spread
 *     (msfRateBps − netTakeRateBps from an unfiltered revenue-kpis call) and
 *     applied to the cohort. The lever DELTAS use real cohort MSF/volume; only
 *     the absolute baseline cost-split uses this bank-average approximation.
 *   All three calls hit summary tables — fast even at billions of fact rows.
 *
 * Churn and DCC-margin are transparent, user-tunable ASSUMPTIONS, clearly
 * labelled — not derived figures.
 */

// ── cohort dimension → (explorer filter key, revenue-kpis DTO list key, distinct field) ──
const COHORTS = {
  ALL:              { label: 'Whole bank',       explorerKey: null,               dtoKey: null,          distinct: null },
  mcc:              { label: 'MCC (industry)',   explorerKey: 'mcc',              dtoKey: 'mccList',     distinct: 'mcc' },
  sales_user:       { label: 'RM (sales email)', explorerKey: 'sales_user',       dtoKey: 'rmList',      distinct: 'sales_user' },
  referral_partner: { label: 'Referral partner', explorerKey: 'referral_partner', dtoKey: 'partnerList', distinct: 'referral_partner' },
  card_scheme:      { label: 'Card scheme',      explorerKey: 'card_scheme',      dtoKey: 'schemeList',  distinct: 'card_scheme' },
  mid:              { label: 'Merchant (MID)',   explorerKey: 'mid',              dtoKey: 'midList',     distinct: 'mid' },
};

const TOP_SCHEMES = 6;

// ── formatting helpers ──
const num = (v) => (v == null || isNaN(Number(v)) ? 0 : Number(v));
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
// Money — carries the tenant currency and precision (was a bare number).
const fmtMoney = (v) => formatCompactCurrency(v);
const fmtSigned = (v) => (num(v) >= 0 ? '+' : '') + fmtMoney(v);
const fmtBps = (v) => `${num(v).toFixed(1)} bps`;
const fmtPct = (v) => `${num(v).toFixed(1)}%`;

// ── design tokens (with hard fallbacks so it renders even if a var is missing) ──
const T = {
  card: 'var(--bg-card, #ffffff)',
  bg: 'var(--bg, #f8fafc)',
  border: 'var(--border, #e5e7eb)',
  text: 'var(--text, #0f172a)',
  muted: 'var(--text-muted, #64748b)',
  brand: 'var(--brand, #2563eb)',
  pos: 'var(--success, #16a34a)',
  neg: 'var(--danger, #dc2626)',
  warn: 'var(--warning, #d97706)',
  rlg: 'var(--radius-lg, 14px)',
  rmd: 'var(--radius-md, 10px)',
  shadow: 'var(--shadow-sm, 0 1px 2px rgba(0,0,0,0.06))',
};

function Slider({ label, value, min, max, step, onChange, suffix, hint, accent }) {
  return (
    <div style={{ marginBottom: 18 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 6 }}>
        <span style={{ fontSize: 13, fontWeight: 600, color: T.text }}>{label}</span>
        <span style={{ fontSize: 13, fontWeight: 700, color: accent || T.brand, fontVariantNumeric: 'tabular-nums' }}>
          {value}{suffix || ''}
        </span>
      </div>
      <input
        type="range" min={min} max={max} step={step} value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        style={{ width: '100%', accentColor: accent || T.brand, cursor: 'pointer' }}
      />
      {hint && <div style={{ fontSize: 11, color: T.muted, marginTop: 3 }}>{hint}</div>}
    </div>
  );
}

function Chip({ label, value, sub, tone }) {
  const color = tone === 'pos' ? T.pos : tone === 'neg' ? T.neg : tone === 'warn' ? T.warn : T.text;
  return (
    <div style={{ background: T.bg, border: `1px solid ${T.border}`, borderRadius: T.rmd, padding: '12px 14px', minWidth: 0 }}>
      <div style={{ fontSize: 11, color: T.muted, textTransform: 'uppercase', letterSpacing: 0.4, marginBottom: 4 }}>{label}</div>
      <div style={{ fontSize: 20, fontWeight: 700, color, fontVariantNumeric: 'tabular-nums', lineHeight: 1.1 }}>{value}</div>
      {sub && <div style={{ fontSize: 11, color: T.muted, marginTop: 3 }}>{sub}</div>}
    </div>
  );
}

export default function PricingSimulator() {
  // cohort selection
  const [cohortDim, setCohortDim] = useState('ALL');
  const [cohortOptions, setCohortOptions] = useState([]);
  const [cohortValues, setCohortValues] = useState([]);
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');

  // fetched base aggregate
  const [base, setBase] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [window, setWindow] = useState({ start: null, end: null, days: 30 });

  // levers
  const [msfDeltaBps, setMsfDeltaBps] = useState(0);
  const [rawShares, setRawShares] = useState({});      // {schemeCode: 0..100}
  const [dccTarget, setDccTarget] = useState(0);       // opt-in target %
  const [dccMarginBps, setDccMarginBps] = useState(250); // assumption: DCC margin (bps of opted-in volume)
  const [elasticity, setElasticity] = useState(1.5);   // assumption: % volume churn per +10 bps
  const [annualized, setAnnualized] = useState(true);

  // ── load distinct cohort values when dimension changes ──
  useEffect(() => {
    setCohortValues([]);
    const def = COHORTS[cohortDim];
    if (!def || !def.distinct) { setCohortOptions([]); return; }
    let alive = true;
    explorerApi.getDistinct(def.distinct)
      .then((res) => { if (alive) setCohortOptions(Array.isArray(res.data) ? res.data.filter(Boolean) : []); })
      .catch(() => { if (alive) setCohortOptions([]); });
    return () => { alive = false; };
  }, [cohortDim]);

  // ── build request payloads for the current cohort/window ──
  const buildRequests = useCallback(() => {
    const def = COHORTS[cohortDim];
    const filtered = !!(def.dtoKey && cohortValues.length);
    const dto = {};
    if (startDate) dto.startDate = startDate;
    if (endDate) dto.endDate = endDate;
    if (filtered) dto[def.dtoKey] = cohortValues;

    const filters = {};
    if (def.explorerKey && cohortValues.length) filters[def.explorerKey] = cohortValues;
    return { dto, filters, filtered };
  }, [cohortDim, cohortValues, startDate, endDate]);

  // ── fetch base aggregate (summary grain only) ──
  const fetchBase = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const { dto, filters, filtered } = buildRequests();

      // 1) revenue-kpis (cohort) — anchors the window, gives effective MSF rate + DCC block.
      const kpiRes = await api.post('/business/revenue-kpis', dto);
      const kpi = kpiRes.data || {};
      const winStart = kpi.startDate || startDate || null;
      const winEnd = kpi.endDate || endDate || null;
      const days = daysBetween(winStart, winEnd);

      // 2) Cost rate — from the bank-grain take-rate spread over the SAME window.
      //    For an unfiltered cohort, call 1 already carries the real net take rate.
      let bankKpi = kpi;
      if (filtered) {
        const bankDto = {};
        if (winStart) bankDto.startDate = winStart;
        if (winEnd) bankDto.endDate = winEnd;
        try { bankKpi = (await api.post('/business/revenue-kpis', bankDto)).data || {}; } catch (_) { bankKpi = kpi; }
      }
      const costBps = Math.max(0, num(bankKpi.msfRateBps) - num(bankKpi.netTakeRateBps));

      // 3) Explorer (SUMMARY grain — summary-capable measures only) — per-scheme mix.
      const exPayload = {
        dimensions: ['card_scheme'],
        measures: ['total_volume', 'total_msf', 'txn_count'],
        filters,
        limit: 100,
      };
      if (winStart) exPayload.startDate = winStart;
      if (winEnd) exPayload.endDate = winEnd;

      let rows = [];
      try {
        const exRes = await explorerApi.query(exPayload);
        rows = (exRes.data && exRes.data.data) || [];
      } catch (_) { rows = []; }

      const b = deriveBase(rows, kpi, costBps);
      setBase(b);
      setWindow({ start: winStart, end: winEnd, days });

      // reset levers to reflect this cohort's actuals
      const rs = {};
      b.schemes.forEach((s) => { rs[s.code] = Math.round(s.share * 100); });
      setRawShares(rs);
      setDccTarget(Math.round(b.dccOptinRatePct));
      setMsfDeltaBps(0);
    } catch (e) {
      setError(e?.response?.data?.error || e.message || 'Failed to load cohort');
      setBase(null);
    } finally {
      setLoading(false);
    }
  }, [buildRequests, startDate, endDate]);

  // initial load
  useEffect(() => { fetchBase(); /* eslint-disable-next-line */ }, []);

  // ── the model (all client-side) ──
  const scenario = useMemo(() => {
    if (!base || base.volume <= 0) return null;
    const V = base.volume;

    // Scheme mix — normalise raw slider shares, compute blended MSF/net bps.
    const codes = base.schemes.map((s) => s.code);
    const rawSum = codes.reduce((a, c) => a + Math.max(0, num(rawShares[c])), 0);
    const useRaw = rawSum > 0;
    let blendedMsfBps = 0, blendedNetBps = 0;
    base.schemes.forEach((s) => {
      const share = useRaw ? Math.max(0, num(rawShares[s.code])) / rawSum : s.share;
      blendedMsfBps += share * s.msfBps;
      blendedNetBps += share * s.netBps;
    });
    const mixMsfDeltaBps = blendedMsfBps - base.msfBps;      // bps change from mix alone
    const mixNetDeltaBps = blendedNetBps - base.netBps;

    // Levers → net-revenue deltas (period).
    const repricingDelta = V * msfDeltaBps / 10000;          // added MSF bps flow 1:1 to net (costs ~fixed)
    const mixDelta = V * mixNetDeltaBps / 10000;             // reweighting toward higher/lower-margin schemes

    // DCC capture — incremental margin on newly opted-in eligible volume.
    const targetOptinVol = base.dccEligible * clamp(dccTarget, 0, 100) / 100;
    const dccDeltaVol = targetOptinVol - base.dccOptin;
    const dccDelta = dccDeltaVol * dccMarginBps / 10000;

    // Churn — modeled from the effective price increase the merchant feels.
    const priceUpBps = Math.max(0, mixMsfDeltaBps + msfDeltaBps);
    const churnFrac = clamp((elasticity / 100) * (priceUpBps / 10), 0, 0.30);
    const churnedVol = V * churnFrac;
    const scenarioNetBps = base.netBps + msfDeltaBps + mixNetDeltaBps;
    const churnDrag = churnedVol * scenarioNetBps / 10000;   // net margin lost with the departing volume

    const baselineNet = base.net;
    const scenarioNet = baselineNet + repricingDelta + mixDelta + dccDelta - churnDrag;

    const f = annualized ? (365 / Math.max(1, window.days)) : 1;
    const scale = (x) => x * f;

    return {
      f,
      blendedMsfBps, mixMsfDeltaBps, scenarioNetBps,
      baselineNet: scale(baselineNet),
      scenarioNet: scale(scenarioNet),
      repricingDelta: scale(repricingDelta),
      mixDelta: scale(mixDelta),
      dccDelta: scale(dccDelta),
      churnDrag: scale(churnDrag),
      totalDelta: scale(scenarioNet - baselineNet),
      churnFrac, churnedVol: scale(churnedVol),
      dccDeltaVol: scale(dccDeltaVol),
      priceUpBps,
    };
  }, [base, rawShares, msfDeltaBps, dccTarget, dccMarginBps, elasticity, annualized, window.days]);

  const churnTone = scenario ? (scenario.churnFrac >= 0.08 ? 'neg' : scenario.churnFrac >= 0.03 ? 'warn' : 'pos') : 'pos';
  const churnLabel = scenario ? (scenario.churnFrac >= 0.08 ? 'High' : scenario.churnFrac >= 0.03 ? 'Medium' : 'Low') : '—';

  // waterfall steps
  const steps = scenario ? [
    { label: 'Baseline net', value: scenario.baselineNet, kind: 'base' },
    { label: 'MSF repricing', value: scenario.repricingDelta, kind: 'delta' },
    { label: 'Scheme mix', value: scenario.mixDelta, kind: 'delta' },
    { label: 'DCC capture', value: scenario.dccDelta, kind: 'delta' },
    { label: 'Churn drag', value: -scenario.churnDrag, kind: 'delta' },
    { label: 'Scenario net', value: scenario.scenarioNet, kind: 'total' },
  ] : [];
  const maxStep = steps.length ? Math.max(...steps.map((s) => Math.abs(s.value)), 1) : 1;

  const resetLevers = () => {
    if (!base) return;
    const rs = {}; base.schemes.forEach((s) => { rs[s.code] = Math.round(s.share * 100); });
    setRawShares(rs); setDccTarget(Math.round(base.dccOptinRatePct)); setMsfDeltaBps(0);
  };

  const toggleValue = (v) => setCohortValues((prev) => prev.includes(v) ? prev.filter((x) => x !== v) : [...prev, v]);

  return (
    <div style={{ padding: 24, color: T.text, maxWidth: 1280, margin: '0 auto' }}>
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, flexWrap: 'wrap', marginBottom: 18 }}>
        <div>
          <h1 style={{ fontSize: 22, fontWeight: 700, margin: 0 }}>What-If Pricing Simulator</h1>
          <p style={{ fontSize: 13, color: T.muted, margin: '4px 0 0' }}>
            Model MSF repricing, scheme-mix shifts and DCC capture against projected net margin and churn.
          </p>
        </div>
        <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: T.muted, cursor: 'pointer' }}>
          <input type="checkbox" checked={annualized} onChange={(e) => setAnnualized(e.target.checked)} />
          Annualize (×365/{window.days}d)
        </label>
      </div>

      {/* Cohort bar */}
      <div style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: T.rlg, boxShadow: T.shadow, padding: 16, marginBottom: 18 }}>
        <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', alignItems: 'flex-end' }}>
          <Field label="Cohort">
            <select value={cohortDim} onChange={(e) => setCohortDim(e.target.value)} style={selStyle}>
              {Object.entries(COHORTS).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
            </select>
          </Field>
          {cohortDim !== 'ALL' && (
            <Field label={`${COHORTS[cohortDim].label} values ${cohortValues.length ? `(${cohortValues.length})` : ''}`}>
              <div style={{ maxHeight: 92, overflowY: 'auto', border: `1px solid ${T.border}`, borderRadius: T.rmd, padding: 6, minWidth: 240, display: 'flex', flexWrap: 'wrap', gap: 6, background: T.bg }}>
                {cohortOptions.length === 0 && <span style={{ fontSize: 12, color: T.muted }}>No values</span>}
                {cohortOptions.slice(0, 300).map((v) => {
                  const on = cohortValues.includes(v);
                  return (
                    <button key={v} onClick={() => toggleValue(v)} style={{
                      fontSize: 12, padding: '3px 8px', borderRadius: 999, cursor: 'pointer',
                      border: `1px solid ${on ? T.brand : T.border}`,
                      background: on ? T.brand : T.card, color: on ? '#fff' : T.text,
                    }}>{String(v)}</button>
                  );
                })}
              </div>
            </Field>
          )}
          <Field label="Start (optional)"><input type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} style={selStyle} /></Field>
          <Field label="End (optional)"><input type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} style={selStyle} /></Field>
          <button onClick={fetchBase} disabled={loading} style={{
            padding: '9px 18px', borderRadius: T.rmd, border: 'none', background: T.brand, color: '#fff',
            fontWeight: 600, fontSize: 13, cursor: loading ? 'wait' : 'pointer', opacity: loading ? 0.7 : 1,
          }}>{loading ? 'Loading…' : 'Load cohort'}</button>
        </div>
        {window.start && (
          <div style={{ fontSize: 12, color: T.muted, marginTop: 10 }}>
            Window <b style={{ color: T.text }}>{window.start} → {window.end}</b> ({window.days} days) · figures {annualized ? 'annualized' : 'for the window'}
          </div>
        )}
      </div>

      {error && <div style={{ background: 'rgba(220,38,38,0.08)', border: `1px solid ${T.neg}`, color: T.neg, borderRadius: T.rmd, padding: 12, marginBottom: 18, fontSize: 13 }}>{error}</div>}

      {base && base.volume <= 0 && !loading && (
        <div style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: T.rlg, padding: 40, textAlign: 'center', color: T.muted }}>
          No volume for this cohort in the selected window. Widen the cohort or date range.
        </div>
      )}

      {base && base.volume > 0 && scenario && (
        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(320px, 1fr) minmax(0, 1.4fr)', gap: 18, alignItems: 'start' }}>
          {/* LEVERS */}
          <div style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: T.rlg, boxShadow: T.shadow, padding: 18 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
              <h3 style={{ fontSize: 15, fontWeight: 700, margin: 0 }}>Levers</h3>
              <button onClick={resetLevers} style={ghostBtn}>Reset</button>
            </div>

            <Slider label="MSF rate change" value={msfDeltaBps} min={-50} max={100} step={1} suffix=" bps"
              onChange={setMsfDeltaBps} accent={msfDeltaBps >= 0 ? T.pos : T.neg}
              hint={`Base effective MSF ${fmtBps(base.msfBps)} → ${fmtBps(base.msfBps + scenario.mixMsfDeltaBps + msfDeltaBps)}`} />

            <Slider label="DCC opt-in target" value={dccTarget} min={0} max={100} step={1} suffix="%"
              onChange={setDccTarget} accent={T.brand}
              hint={`Current opt-in ${fmtPct(base.dccOptinRatePct)} of eligible · eligible ${fmtMoney(base.dccEligible)}`} />

            {/* Scheme mix */}
            {base.schemes.length > 1 && (
              <div style={{ marginTop: 8, borderTop: `1px dashed ${T.border}`, paddingTop: 14 }}>
                <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 4 }}>Scheme mix</div>
                <div style={{ fontSize: 11, color: T.muted, marginBottom: 10 }}>
                  Shares auto-normalize to 100%. Higher-margin schemes lift blended rate.
                </div>
                {base.schemes.map((s) => {
                  const rawSum = base.schemes.reduce((a, c) => a + Math.max(0, num(rawShares[c.code])), 0) || 1;
                  const normPct = (Math.max(0, num(rawShares[s.code])) / rawSum) * 100;
                  return (
                    <div key={s.code} style={{ marginBottom: 12 }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, marginBottom: 4 }}>
                        <span style={{ fontWeight: 600 }}>{s.code}</span>
                        <span style={{ color: T.muted, fontVariantNumeric: 'tabular-nums' }}>
                          {normPct.toFixed(0)}% · {fmtBps(s.msfBps)}
                        </span>
                      </div>
                      <input type="range" min={0} max={100} step={1}
                        value={num(rawShares[s.code])}
                        onChange={(e) => setRawShares((p) => ({ ...p, [s.code]: Number(e.target.value) }))}
                        style={{ width: '100%', accentColor: T.brand, cursor: 'pointer' }} />
                    </div>
                  );
                })}
              </div>
            )}

            {/* Assumptions */}
            <details style={{ marginTop: 10, borderTop: `1px dashed ${T.border}`, paddingTop: 14 }}>
              <summary style={{ fontSize: 13, fontWeight: 600, cursor: 'pointer', color: T.muted }}>Model assumptions</summary>
              <div style={{ marginTop: 12 }}>
                <Slider label="DCC margin captured" value={dccMarginBps} min={0} max={500} step={10} suffix=" bps"
                  onChange={setDccMarginBps} accent={T.brand}
                  hint="Acquirer margin on opted-in DCC volume." />
                <Slider label="Churn elasticity" value={elasticity} min={0} max={8} step={0.1} suffix="% / +10bps"
                  onChange={setElasticity} accent={T.warn}
                  hint="Volume that leaves per +10 bps of effective price increase." />
                <div style={{ fontSize: 11, color: T.muted, marginTop: 4 }}>
                  Cohort cost rate ≈ bank-average spread ({fmtBps(base.costBps)}); dimensional summaries carry no interchange.
                </div>
              </div>
            </details>
          </div>

          {/* RESULTS */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
            {/* headline */}
            <div style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: T.rlg, boxShadow: T.shadow, padding: 18 }}>
              <div style={{ fontSize: 12, color: T.muted, textTransform: 'uppercase', letterSpacing: 0.5 }}>
                Projected net-revenue change {annualized ? '(annualized)' : '(window)'}
              </div>
              <div style={{ fontSize: 40, fontWeight: 800, color: scenario.totalDelta >= 0 ? T.pos : T.neg, fontVariantNumeric: 'tabular-nums', lineHeight: 1.1, margin: '4px 0 2px' }}>
                {fmtSigned(scenario.totalDelta)}
              </div>
              <div style={{ fontSize: 13, color: T.muted }}>
                {fmtMoney(scenario.baselineNet)} → <b style={{ color: T.text }}>{fmtMoney(scenario.scenarioNet)}</b>
                {scenario.baselineNet > 0 && (
                  <span> · {((scenario.totalDelta / scenario.baselineNet) * 100).toFixed(1)}%</span>
                )}
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 10, marginTop: 16 }}>
                <Chip label="Blended MSF rate" value={fmtBps(scenario.blendedMsfBps + msfDeltaBps)} sub={`base ${fmtBps(base.msfBps)}`} />
                <Chip label="Net take rate" value={fmtBps(scenario.scenarioNetBps)} sub={`base ${fmtBps(base.netBps)}`} />
                <Chip label="Churn risk" value={churnLabel} sub={`${fmtPct(scenario.churnFrac * 100)} of volume`} tone={churnTone} />
                <Chip label="DCC volume shift" value={fmtSigned(scenario.dccDeltaVol)} sub="opted-in eligible" tone={scenario.dccDeltaVol >= 0 ? 'pos' : 'neg'} />
              </div>
            </div>

            {/* waterfall */}
            <div style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: T.rlg, boxShadow: T.shadow, padding: 18 }}>
              <h3 style={{ fontSize: 15, fontWeight: 700, margin: '0 0 14px' }}>Revenue bridge</h3>
              {steps.map((s) => {
                const w = (Math.abs(s.value) / maxStep) * 100;
                const isNeg = s.value < 0;
                const barColor = s.kind === 'base' ? T.muted : s.kind === 'total' ? T.brand : (isNeg ? T.neg : T.pos);
                return (
                  <div key={s.label} style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 10 }}>
                    <div style={{ width: 110, fontSize: 12, color: T.muted, textAlign: 'right', flexShrink: 0 }}>{s.label}</div>
                    <div style={{ flex: 1, height: 22, background: T.bg, borderRadius: 6, position: 'relative', overflow: 'hidden' }}>
                      <div style={{ position: 'absolute', top: 0, bottom: 0, left: 0, width: `${w}%`, background: barColor, opacity: s.kind === 'delta' ? 0.85 : 1, borderRadius: 6, transition: 'width 0.18s ease' }} />
                    </div>
                    <div style={{ width: 92, fontSize: 13, fontWeight: 700, textAlign: 'right', color: barColor, fontVariantNumeric: 'tabular-nums', flexShrink: 0 }}>
                      {s.kind === 'delta' ? fmtSigned(s.value) : fmtMoney(s.value)}
                    </div>
                  </div>
                );
              })}
            </div>

            {/* scheme table */}
            {base.schemes.length > 1 && (
              <div style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: T.rlg, boxShadow: T.shadow, padding: 18 }}>
                <h3 style={{ fontSize: 15, fontWeight: 700, margin: '0 0 12px' }}>Scheme economics (actuals)</h3>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                  <thead>
                    <tr style={{ color: T.muted, textAlign: 'right' }}>
                      <th style={{ textAlign: 'left', padding: '6px 4px' }}>Scheme</th>
                      <th style={{ padding: '6px 4px' }}>Volume</th>
                      <th style={{ padding: '6px 4px' }}>Share</th>
                      <th style={{ padding: '6px 4px' }}>MSF bps</th>
                      <th style={{ padding: '6px 4px' }}>Net bps</th>
                    </tr>
                  </thead>
                  <tbody>
                    {base.schemes.map((s) => (
                      <tr key={s.code} style={{ borderTop: `1px solid ${T.border}`, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                        <td style={{ textAlign: 'left', padding: '6px 4px', fontWeight: 600 }}>{s.code}</td>
                        <td style={{ padding: '6px 4px' }}>{fmtMoney(s.vol)}</td>
                        <td style={{ padding: '6px 4px', color: T.muted }}>{fmtPct(s.share * 100)}</td>
                        <td style={{ padding: '6px 4px' }}>{s.msfBps.toFixed(1)}</td>
                        <td style={{ padding: '6px 4px', color: s.netBps >= 0 ? T.text : T.neg }}>{s.netBps.toFixed(1)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <div style={{ fontSize: 11, color: T.muted, lineHeight: 1.6 }}>
              Sourced from pre-aggregated summary tables (no fact scan): per-scheme volume &amp; MSF from
              <b> sum_daily_insight</b>, DCC eligibility from the merchant grain. The cohort <b>cost rate</b> is
              approximated from the bank-average take-rate spread (dimensional summaries carry no interchange), so
              lever <i>deltas</i> are exact while the absolute baseline cost-split is an estimate. <b>DCC margin</b> and
              <b> churn elasticity</b> are tunable assumptions under “Model assumptions”. Card-level cohorts (e.g. a
              single scheme) don't narrow the DCC block, which is merchant-grained.
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ── derive the base aggregate from Explorer scheme rows + revenue-kpis DCC + bank cost rate ──
function deriveBase(rows, kpi, costBps) {
  const parsed = (rows || []).map((r) => ({
    code: r.card_scheme != null && String(r.card_scheme).trim() !== '' ? String(r.card_scheme) : 'UNKNOWN',
    vol: num(r.total_volume),
    msf: num(r.total_msf),
    txns: num(r.txn_count),
  })).filter((s) => s.vol > 0);

  let volume = parsed.reduce((a, s) => a + s.vol, 0);
  let msf = parsed.reduce((a, s) => a + s.msf, 0);

  // Fallback to revenue-kpis headline if the explorer scan returned nothing.
  if (volume <= 0) {
    volume = num(kpi.totalVolume);
    msf = num(kpi.totalMsf);
  }

  const cohortMsfBps = volume > 0 ? (msf / volume) * 10000 : 0;
  const cohortNetBps = cohortMsfBps - num(costBps);
  const net = volume * cohortNetBps / 10000;

  // Top schemes + "OTHER" bucket.
  parsed.sort((a, b) => b.vol - a.vol);
  const top = parsed.slice(0, TOP_SCHEMES);
  const rest = parsed.slice(TOP_SCHEMES);
  if (rest.length) {
    top.push(rest.reduce((acc, s) => ({
      code: 'OTHER', vol: acc.vol + s.vol, msf: acc.msf + s.msf, txns: acc.txns + s.txns,
    }), { code: 'OTHER', vol: 0, msf: 0, txns: 0 }));
  }
  const schemes = top.map((s) => {
    const msfBps = s.vol > 0 ? (s.msf / s.vol) * 10000 : 0;
    return {
      code: s.code, vol: s.vol,
      share: volume > 0 ? s.vol / volume : 0,
      msfBps,
      netBps: msfBps - num(costBps), // uniform cost rate across schemes (bank-average approximation)
    };
  });

  return {
    volume, msf, net,
    costBps: num(costBps),
    msfBps: cohortMsfBps,
    netBps: cohortNetBps,
    schemes,
    dccEligible: num(kpi.dccEligibleVolume),
    dccOptin: num(kpi.dccOptinVolume),
    dccMissed: num(kpi.dccMissedVolume),
    dccOptinRatePct: num(kpi.dccOptinRatePct),
    dccPenetrationPct: num(kpi.dccPenetrationPct),
  };
}

function daysBetween(a, b) {
  if (!a || !b) return 30;
  const d = Math.round((new Date(b) - new Date(a)) / 86400000) + 1;
  return d > 0 ? d : 30;
}

// ── small style helpers ──
const selStyle = {
  padding: '8px 10px', borderRadius: T.rmd, border: `1px solid ${T.border}`,
  background: T.card, color: T.text, fontSize: 13, minWidth: 150,
};
const ghostBtn = {
  fontSize: 12, padding: '4px 10px', borderRadius: T.rmd, cursor: 'pointer',
  border: `1px solid ${T.border}`, background: T.bg, color: T.muted,
};
function Field({ label, children }) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
      <span style={{ fontSize: 11, color: T.muted, fontWeight: 600, textTransform: 'uppercase', letterSpacing: 0.4 }}>{label}</span>
      {children}
    </label>
  );
}
