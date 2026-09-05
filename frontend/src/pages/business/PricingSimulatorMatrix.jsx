import React, { useState, useEffect, useMemo } from 'react';
import api from '../../api/axios';
import { formatCompactCurrency } from '../../utils/formatters';

// ═══════════════════════════════════════════════════════════════════════════
// Pricing Simulator v2 — Segment Margin Matrix (Meridian steel skin)
// ---------------------------------------------------------------------------
// card_scheme × card_type (CREDIT/DEBIT/PREPAID/…) × DOMESTIC/INTERNATIONAL,
// each cell carrying the REAL realized fee stack from sum_daily_full.
//
// Signature device: the margin gauge in every cell — the bar's full width is
// the segment's MSF, the navy fill is its cost stack (interchange + scheme +
// ecom), and the jade remainder is the margin the bank keeps. A below-cost
// segment shows a full navy bar with a red baseline: cost has consumed the
// entire MSF and more. The whole below-cost story reads without numbers.
//
// Card-type presence stays first-class: untyped volume renders in a muted
// "No card type" column with levers disabled, plus per-scheme coverage.
// ═══════════════════════════════════════════════════════════════════════════

const T = {
  card: 'var(--surface, #EAF1FA)',
  bg: 'var(--canvas, #F1F7FF)',
  border: 'var(--hairline, #E4E7EC)',
  text: 'var(--ink, #14295E)',
  muted: 'var(--muted, #51618C)',
  brand: 'var(--primary, #3F63B0)',
  wash: 'var(--wash, #DCE8F7)',
  navy: 'var(--chart-1, #263C6E)',
  pos: 'var(--chart-pos, #0FA070)',
  neg: 'var(--negative, #B3382C)',
  warn: 'var(--attention, #8C5E12)',
  mono: "var(--font-mono, 'IBM Plex Mono', ui-monospace, monospace)",
  rlg: 12,
  rmd: 8,
  shadow: '0 1px 2px rgba(20,41,94,0.06)',
};

const num = (v) => (v == null || isNaN(Number(v)) ? 0 : Number(v));
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
const fmtMoney = (v) => formatCompactCurrency(v);
const fmtSigned = (v) => (num(v) >= 0 ? '+' : '') + fmtMoney(v);
const fmtBps = (v) => `${num(v).toFixed(1)} bps`;
const fmtPct = (v) => `${num(v).toFixed(1)}%`;

const ghostBtn = {
  fontSize: 12, fontWeight: 600, padding: '5px 12px', borderRadius: T.rmd, cursor: 'pointer',
  border: `1px solid ${T.border}`, background: 'transparent', color: T.muted,
  transition: 'all 120ms ease',
};

const thStyle = {
  padding: '6px 8px', fontWeight: 600, fontSize: 11, letterSpacing: 0.4,
  textTransform: 'uppercase', color: T.muted, textAlign: 'right', whiteSpace: 'nowrap',
};
const thLeft = { ...thStyle, textAlign: 'left' };
const tdNum = { padding: '7px 8px', textAlign: 'right', fontFamily: T.mono, fontSize: 12.5, whiteSpace: 'nowrap' };

function daysBetween(a, b) {
  if (!a || !b) return 30;
  const d = Math.round((new Date(b) - new Date(a)) / 86400000) + 1;
  return d > 0 ? d : 30;
}

function Slider({ label, value, min, max, step, onChange, suffix, hint, accent }) {
  return (
    <div style={{ marginBottom: 14 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 6 }}>
        <span style={{ fontSize: 13, fontWeight: 600, color: T.text }}>{label}</span>
        <span style={{ fontSize: 13, fontWeight: 700, color: accent || T.brand, fontFamily: T.mono }}>
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

/** Stat tile — mono numerals on the steel surface, matching the app's KPI idiom. */
function Stat({ label, value, sub, tone }) {
  const color = tone === 'pos' ? T.pos : tone === 'neg' ? T.neg : tone === 'warn' ? T.warn : T.text;
  return (
    <div style={{
      background: T.card, border: `1px solid ${T.border}`, borderRadius: T.rlg, boxShadow: T.shadow,
      padding: '14px 16px', minWidth: 0, flex: '1 1 150px',
    }}>
      <div style={{ fontSize: 10.5, color: T.muted, textTransform: 'uppercase', letterSpacing: 0.8, fontWeight: 700, marginBottom: 6 }}>{label}</div>
      <div style={{ fontSize: 21, fontWeight: 700, color, fontFamily: T.mono, lineHeight: 1.1 }}>{value}</div>
      {sub && <div style={{ fontSize: 11, color: T.muted, marginTop: 4 }}>{sub}</div>}
    </div>
  );
}

/**
 * The margin gauge. Full width = the segment's MSF; navy fill = its cost
 * stack; jade remainder = kept margin. Below cost: full navy + red baseline.
 */
function MarginGauge({ msfBps, costBps, belowCost }) {
  if (msfBps == null || costBps == null || num(msfBps) <= 0) return null;
  const costShare = clamp(num(costBps) / num(msfBps), 0, 1) * 100;
  return (
    <div style={{ marginTop: 6 }}>
      <div style={{ display: 'flex', height: 4, borderRadius: 2, overflow: 'hidden', background: T.wash }}>
        <div style={{ width: `${costShare}%`, background: T.navy }} />
        {!belowCost && <div style={{ flex: 1, background: T.pos }} />}
      </div>
      {belowCost && <div style={{ height: 2, background: T.neg, borderRadius: 1, marginTop: 1 }} />}
    </div>
  );
}

const CT_ORDER = ['CREDIT', 'DEBIT', 'PREPAID'];
const DESTS = ['DOMESTIC', 'INTERNATIONAL'];
const DEST_LABEL = { DOMESTIC: 'Local', INTERNATIONAL: 'Intl' };
const segKey = (s) => `${s.scheme}|${s.cardType}|${s.destination}`;

function orderCardTypes(types) {
  const known = CT_ORDER.filter((t) => types.includes(t));
  const other = types.filter((t) => !CT_ORDER.includes(t) && t !== 'UNSPECIFIED').sort();
  const tail = types.includes('UNSPECIFIED') ? ['UNSPECIFIED'] : [];
  return [...known, ...other, ...tail];
}

export default function SegmentMatrix({ reloadKey, buildDto, elasticity, annualized }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [selected, setSelected] = useState(null);        // segment object
  const [deltas, setDeltas] = useState({});              // {segKey: bps}
  const [drill, setDrill] = useState(null);              // {key, rows, p25, median, loading}
  const [merchant, setMerchant] = useState(null);        // {mid, name} → opens the MID-wise panel

  useEffect(() => {
    if (!reloadKey) return;
    let alive = true;
    setLoading(true); setError(null); setSelected(null); setDrill(null); setDeltas({}); setMerchant(null);
    api.post('/business/pricing-simulator/segment-matrix', buildDto())
      .then((res) => { if (alive) setData(res.data || null); })
      .catch((e) => {
        if (!alive) return;
        setData(null);
        setError(e?.response?.data?.error || e.message || 'Failed to load segment matrix');
      })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
    // eslint-disable-next-line
  }, [reloadKey]);

  const model = useMemo(() => {
    if (!data || !Array.isArray(data.segments)) return null;
    const segs = data.segments.filter((s) => num(s.volume) !== 0);
    const bySeg = {};
    segs.forEach((s) => { bySeg[segKey(s)] = s; });

    // scheme rows ordered by volume; card-type columns = union, ordered.
    const schemeVol = {};
    segs.forEach((s) => { schemeVol[s.scheme] = (schemeVol[s.scheme] || 0) + num(s.volume); });
    const totalVol = Object.values(schemeVol).reduce((a, v) => a + v, 0) || 1;
    const schemes = Object.keys(schemeVol).sort((a, b) => schemeVol[b] - schemeVol[a]);
    const cardTypes = orderCardTypes([...new Set(segs.map((s) => s.cardType))]);

    const covByScheme = {};
    (data.schemeCoverage || []).forEach((c) => { covByScheme[c.scheme] = c; });

    const days = daysBetween(data.windowStart, data.windowEnd);
    const annualFactor = annualized ? 365 / Math.max(1, days) : 1;

    // headline: the money currently burned by below-cost segments
    const belowCostDrag = segs
      .filter((s) => s.belowCost)
      .reduce((a, s) => a + num(s.netRevenue), 0) * annualFactor;
    const belowCostCount = segs.filter((s) => s.belowCost).length;

    // uplift per adjusted segment (reuses the page churn-elasticity assumption)
    let totalUplift = 0;
    const adjusted = [];
    Object.entries(deltas).forEach(([k, bpsUp]) => {
      const s = bySeg[k];
      if (!s || !bpsUp) return;
      const churnFrac = clamp((elasticity / 100) * (Math.max(0, bpsUp) / 10), 0, 0.30);
      const uplift = (num(s.volume) * bpsUp / 10000) * (1 - churnFrac) * annualFactor;
      totalUplift += uplift;
      adjusted.push({ key: k, seg: s, bpsUp, churnFrac, uplift });
    });
    adjusted.sort((a, b) => b.uplift - a.uplift);

    return { segs, bySeg, schemes, cardTypes, covByScheme, schemeVol, totalVol, days, annualFactor, totalUplift, adjusted, belowCostDrag, belowCostCount };
  }, [data, deltas, elasticity, annualized]);

  const setDelta = (key, v) => setDeltas((p) => ({ ...p, [key]: v }));

  const loadDrill = async (seg) => {
    const key = segKey(seg);
    setDrill({ key, rows: [], loading: true });
    try {
      const qs = `scheme=${encodeURIComponent(seg.scheme)}&cardType=${encodeURIComponent(seg.cardType)}&destination=${encodeURIComponent(seg.destination)}&limit=20`;
      const res = await api.post(`/business/pricing-simulator/segment-merchants?${qs}`, buildDto());
      const d = res.data || {};
      setDrill({ key, rows: d.merchants || [], p25: d.p25MsfBps, median: d.medianMsfBps, loading: false });
    } catch (e) {
      setDrill({ key, rows: [], loading: false, error: e?.response?.data?.error || 'Failed to load merchants' });
    }
  };

  if (!reloadKey) return null;

  const totals = data?.totals || {};
  const annualNote = annualized ? 'annualized' : 'window';

  return (
    <div style={{ color: T.text }}>
      {loading && (
        <div style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: T.rlg, padding: 32, textAlign: 'center', color: T.muted, fontSize: 13 }}>
          Computing segment margins…
        </div>
      )}
      {error && !loading && (
        <div style={{ background: 'rgba(179,56,44,0.08)', border: `1px solid ${T.neg}`, color: T.neg, borderRadius: T.rmd, padding: 12, fontSize: 13 }}>{error}</div>
      )}

      {model && !loading && model.segs.length === 0 && (
        <div style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: T.rlg, padding: 32, textAlign: 'center', color: T.muted, fontSize: 13 }}>
          No priced volume in this window. Widen the cohort or date range, then Apply.
        </div>
      )}

      {model && !loading && model.segs.length > 0 && (
        <>
          {/* ── KPI strip ─────────────────────────────────────────────── */}
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 14 }}>
            <Stat label="Volume" value={fmtMoney(num(totals.volume))} sub={`${Number(totals.txns || 0).toLocaleString()} transactions`} />
            <Stat label="Blended MSF" value={totals.msfBps == null ? '—' : fmtBps(num(totals.msfBps))} />
            <Stat label="Cost stack" value={totals.costBps == null ? '—' : fmtBps(num(totals.costBps))} sub="interchange + scheme + ecom" />
            <Stat label="Net take" value={totals.netBps == null ? '—' : fmtBps(num(totals.netBps))}
              tone={num(totals.netBps) < 0 ? 'neg' : 'pos'} />
            <Stat label="Below-cost drag" tone={model.belowCostCount ? 'neg' : 'pos'}
              value={model.belowCostCount ? fmtSigned(model.belowCostDrag) : 'None'}
              sub={model.belowCostCount ? `${model.belowCostCount} segment${model.belowCostCount > 1 ? 's' : ''} priced below cost · ${annualNote}` : 'no segment priced below cost'} />
          </div>

          {/* ── the matrix ────────────────────────────────────────────── */}
          <div style={{ background: T.card, border: `1px solid ${T.border}`, borderRadius: T.rlg, boxShadow: T.shadow, padding: '16px 16px 14px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 10, flexWrap: 'wrap', marginBottom: 12 }}>
              <div style={{ fontSize: 14, fontWeight: 700 }}>Margin by segment</div>
              <div style={{ display: 'flex', gap: 14, alignItems: 'center', flexWrap: 'wrap', fontSize: 11, color: T.muted }}>
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                  <span style={{ width: 14, height: 4, borderRadius: 2, background: T.navy, display: 'inline-block' }} /> cost
                </span>
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                  <span style={{ width: 14, height: 4, borderRadius: 2, background: T.pos, display: 'inline-block' }} /> kept margin
                </span>
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                  <span style={{ width: 14, height: 2, borderRadius: 1, background: T.neg, display: 'inline-block' }} /> below cost
                </span>
                <span style={{
                  fontWeight: 600, padding: '3px 10px', borderRadius: 999, fontSize: 11.5,
                  border: `1px solid ${num(data.cardTypeCoveragePct) < 90 ? T.warn : T.border}`,
                  color: num(data.cardTypeCoveragePct) < 90 ? T.warn : T.muted, background: T.bg,
                }}>
                  card type known · {fmtPct(data.cardTypeCoveragePct)}
                </span>
              </div>
            </div>

            <div style={{ overflowX: 'auto' }}>
              <table style={{ borderCollapse: 'separate', borderSpacing: '4px 3px', minWidth: 680, width: '100%' }}>
                <thead>
                  <tr>
                    <th style={{ ...thLeft, minWidth: 118 }}>Scheme</th>
                    <th style={thStyle}></th>
                    {model.cardTypes.map((ct) => (
                      <th key={ct} style={{ ...thStyle, textAlign: 'center', color: ct === 'UNSPECIFIED' ? T.muted : T.text }}>
                        {ct === 'UNSPECIFIED' ? 'No card type' : ct.toLowerCase()}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {model.schemes.map((scheme) => {
                    const cov = model.covByScheme[scheme];
                    const sharePct = (model.schemeVol[scheme] / model.totalVol) * 100;
                    return DESTS.map((dest, di) => (
                      <tr key={`${scheme}-${dest}`}>
                        {di === 0 && (
                          <td rowSpan={2} style={{ verticalAlign: 'top', padding: '8px 8px 0 4px' }}>
                            <div style={{ fontSize: 13, fontWeight: 700, letterSpacing: 0.2 }}>{scheme}</div>
                            <div style={{ fontSize: 10.5, color: T.muted, fontFamily: T.mono, marginTop: 2 }}>
                              {sharePct.toFixed(0)}% of volume
                            </div>
                            {cov && num(cov.unknownSharePct) > 0 && (
                              <div style={{ fontSize: 10.5, color: cov.lowCoverage ? T.warn : T.muted, marginTop: 2 }}>
                                {fmtPct(cov.unknownSharePct)} untyped{cov.lowCoverage ? ' ⚠' : ''}
                              </div>
                            )}
                          </td>
                        )}
                        <td style={{ fontSize: 10.5, color: T.muted, fontWeight: 700, padding: '0 6px', whiteSpace: 'nowrap', textTransform: 'uppercase', letterSpacing: 0.6 }}>{DEST_LABEL[dest]}</td>
                        {model.cardTypes.map((ct) => {
                          const s = model.bySeg[`${scheme}|${ct}|${dest}`];
                          if (!s) {
                            return (
                              <td key={ct} style={{ minWidth: 128 }}>
                                <div style={{ borderRadius: T.rmd, border: `1px dashed ${T.border}`, padding: '14px 8px', textAlign: 'center', fontSize: 11, color: T.muted }}>—</div>
                              </td>
                            );
                          }
                          const key = segKey(s);
                          const isSel = selected && segKey(selected) === key;
                          const untyped = ct === 'UNSPECIFIED';
                          const hasDelta = num(deltas[key]) > 0;
                          return (
                            <td key={ct} style={{ minWidth: 128 }}>
                              <button
                                onClick={() => { setSelected(isSel ? null : s); setDrill(null); setMerchant(null); }}
                                title={`${scheme} · ${ct} · ${DEST_LABEL[dest]}`}
                                style={{
                                  width: '100%', textAlign: 'left', cursor: 'pointer',
                                  borderRadius: T.rmd, padding: '9px 11px',
                                  border: `1.5px solid ${isSel ? T.brand : hasDelta ? T.pos : T.border}`,
                                  background: s.belowCost ? 'rgba(179,56,44,0.05)' : T.bg,
                                  opacity: untyped ? 0.72 : 1,
                                  boxShadow: isSel ? `0 0 0 3px ${'var(--brand-ring, rgba(63,99,176,0.2))'}` : 'none',
                                  transition: 'border-color 120ms ease, box-shadow 120ms ease, transform 120ms ease',
                                }}
                                onMouseEnter={(e) => { e.currentTarget.style.transform = 'translateY(-1px)'; }}
                                onMouseLeave={(e) => { e.currentTarget.style.transform = 'none'; }}
                              >
                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
                                  <span style={{ fontSize: 16, fontWeight: 700, fontFamily: T.mono, color: s.belowCost ? T.neg : s.compressed ? T.warn : T.text }}>
                                    {s.netBps == null ? '—' : num(s.netBps).toFixed(0)}
                                  </span>
                                  <span style={{ fontSize: 9.5, color: T.muted, textTransform: 'uppercase', letterSpacing: 0.5 }}>net bps</span>
                                </div>
                                <MarginGauge msfBps={s.msfBps} costBps={s.costBps} belowCost={s.belowCost} />
                                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 10.5, color: T.muted, fontFamily: T.mono, marginTop: 5 }}>
                                  <span>{fmtMoney(num(s.volume))}</span>
                                  <span>{s.msfBps == null ? '—' : num(s.msfBps).toFixed(0)}−{s.costBps == null ? '—' : num(s.costBps).toFixed(0)}{hasDelta ? ` · +${deltas[key]}` : ''}</span>
                                </div>
                              </button>
                            </td>
                          );
                        })}
                      </tr>
                    ));
                  })}
                </tbody>
              </table>
            </div>

            {/* selected-segment panel */}
            {selected && (() => {
              const s = model.bySeg[segKey(selected)] || selected;
              const key = segKey(s);
              const untyped = s.cardType === 'UNSPECIFIED';
              const bpsUp = num(deltas[key]);
              const churnFrac = clamp((elasticity / 100) * (bpsUp / 10), 0, 0.30);
              const uplift = (num(s.volume) * bpsUp / 10000) * (1 - churnFrac) * model.annualFactor;
              return (
                <div style={{ marginTop: 14, borderTop: `1px solid ${T.border}`, paddingTop: 14, display: 'grid', gridTemplateColumns: 'minmax(260px, 1fr) minmax(0, 1.6fr)', gap: 18, alignItems: 'start' }}>
                  <div>
                    <div style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: 0.8, textTransform: 'uppercase', color: T.brand, marginBottom: 3 }}>Selected segment</div>
                    <div style={{ fontSize: 14, fontWeight: 700 }}>
                      {s.scheme} · {untyped ? 'No card type' : s.cardType.toLowerCase()} · {DEST_LABEL[s.destination]}
                    </div>
                    <div style={{ fontSize: 12, color: T.muted, marginTop: 3, fontFamily: T.mono }}>
                      {fmtMoney(num(s.volume))} · {Number(s.txns).toLocaleString()} txns · {Number(s.merchants).toLocaleString()} merchants
                    </div>
                    <div style={{ marginTop: 12 }}>
                      {untyped ? (
                        <div style={{ fontSize: 12.5, color: T.warn, lineHeight: 1.55 }}>
                          This volume carries no card type, so its credit/debit/prepaid economics are unknown.
                          Repricing is disabled — classify the volume first (feed mapping or BIN typing).
                        </div>
                      ) : (
                        <>
                          <Slider label="Raise MSF by" value={bpsUp} min={0} max={100} step={1} suffix=" bps"
                            onChange={(v) => setDelta(key, v)} accent={T.pos}
                            hint={`Effective MSF ${s.msfBps == null ? '—' : fmtBps(num(s.msfBps))} → ${s.msfBps == null ? '—' : fmtBps(num(s.msfBps) + bpsUp)} · cost floor ${s.costBps == null ? '—' : fmtBps(num(s.costBps))}`} />
                          {bpsUp > 0 && (
                            <div style={{ fontSize: 13, fontWeight: 700, fontFamily: T.mono, color: uplift >= 0 ? T.pos : T.neg }}>
                              {fmtSigned(uplift)} <span style={{ fontWeight: 400, color: T.muted, fontFamily: 'inherit' }}>{annualized ? '/yr' : '/window'} · churn haircut {(churnFrac * 100).toFixed(1)}%</span>
                            </div>
                          )}
                        </>
                      )}
                    </div>
                    <button onClick={() => (drill && drill.key === key ? setDrill(null) : loadDrill(s))} style={{ ...ghostBtn, marginTop: 12, borderColor: T.brand, color: T.brand }}>
                      {drill && drill.key === key ? 'Hide worklist' : 'View repricing worklist'}
                    </button>
                  </div>

                  {/* merchant worklist — the repricing candidates */}
                  <div>
                    {drill && drill.key === key && (
                      drill.loading ? (
                        <div style={{ fontSize: 12.5, color: T.muted }}>Loading merchants…</div>
                      ) : drill.error ? (
                        <div style={{ fontSize: 12.5, color: T.neg }}>{drill.error}</div>
                      ) : drill.rows.length === 0 ? (
                        <div style={{ fontSize: 12.5, color: T.muted }}>No merchants with volume in this segment.</div>
                      ) : (
                        <div style={{ overflowX: 'auto' }}>
                          <div style={{ fontSize: 11.5, color: T.muted, marginBottom: 8 }}>
                            Lowest effective MSF first — priced furthest below segment peers
                            {drill.median != null && <> (median {fmtBps(num(drill.median))}{drill.p25 != null && <>, p25 {fmtBps(num(drill.p25))}</>})</>}.
                            Select a merchant to reprice it segment by segment.
                          </div>
                          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12.5 }}>
                            <thead>
                              <tr>
                                <th style={thLeft}>Merchant</th>
                                <th style={thStyle}>Volume</th>
                                <th style={thStyle}>MSF bps</th>
                                <th style={thStyle}>Cost bps</th>
                                <th style={thStyle}>Net bps</th>
                              </tr>
                            </thead>
                            <tbody>
                              {drill.rows.map((r, i) => {
                                const active = merchant && merchant.mid === r.mid;
                                return (
                                  <tr key={r.mid || i}
                                      onClick={() => r.mid && setMerchant({ mid: r.mid, name: r.name })}
                                      onMouseEnter={(e) => { if (!active) e.currentTarget.style.background = T.wash; }}
                                      onMouseLeave={(e) => { if (!active) e.currentTarget.style.background = 'transparent'; }}
                                      style={{ borderTop: `1px solid ${T.border}`, cursor: r.mid ? 'pointer' : 'default',
                                               background: active ? T.wash : 'transparent', transition: 'background 100ms ease' }}>
                                    <td style={{ padding: '7px 8px' }}>
                                      <span style={{ fontWeight: 600 }}>{r.name || r.mid}</span>
                                      {r.mid && <span style={{ color: T.muted, fontFamily: T.mono, fontSize: 11 }}> {r.mid}</span>}
                                    </td>
                                    <td style={tdNum}>{fmtMoney(num(r.volume))}</td>
                                    <td style={tdNum}>{r.msfBps == null ? '—' : num(r.msfBps).toFixed(0)}</td>
                                    <td style={{ ...tdNum, color: T.muted }}>{r.costBps == null ? '—' : num(r.costBps).toFixed(0)}</td>
                                    <td style={{ ...tdNum, fontWeight: 700, color: num(r.netBps) < 0 ? T.neg : T.pos }}>
                                      {r.netBps == null ? '—' : num(r.netBps).toFixed(0)}
                                    </td>
                                  </tr>
                                );
                              })}
                            </tbody>
                          </table>
                        </div>
                      )
                    )}
                  </div>
                </div>
              );
            })()}

            {/* MID-wise repricing panel */}
            {merchant && model && (
              <MerchantPanel
                key={merchant.mid}
                mid={merchant.mid}
                name={merchant.name}
                buildDto={buildDto}
                benchmarks={model.bySeg}
                elasticity={elasticity}
                annualFactor={model.annualFactor}
                annualized={annualized}
                onClose={() => setMerchant(null)}
              />
            )}

            {/* uplift summary across all adjusted segments */}
            {model.adjusted.length > 0 && (
              <div style={{ marginTop: 14, borderTop: `1px solid ${T.border}`, paddingTop: 14, display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
                <div>
                  <div style={{ fontSize: 10.5, color: T.muted, textTransform: 'uppercase', letterSpacing: 0.8, fontWeight: 700, marginBottom: 4 }}>
                    Total segment uplift · {annualNote}
                  </div>
                  <div style={{ fontSize: 22, fontWeight: 700, fontFamily: T.mono, color: model.totalUplift >= 0 ? T.pos : T.neg }}>
                    {fmtSigned(model.totalUplift)}
                  </div>
                </div>
                <div style={{ fontSize: 12, color: T.muted, flex: 1, minWidth: 220, fontFamily: T.mono }}>
                  {model.adjusted.slice(0, 4).map((a) => (
                    <div key={a.key}>
                      {a.seg.scheme} · {a.seg.cardType.toLowerCase()} · {DEST_LABEL[a.seg.destination]} +{a.bpsUp}bps → {fmtSigned(a.uplift)}
                    </div>
                  ))}
                </div>
                <button onClick={() => setDeltas({})} style={ghostBtn}>Clear all</button>
              </div>
            )}

            <div style={{ fontSize: 11, color: T.muted, lineHeight: 1.6, marginTop: 14, borderTop: `1px solid ${T.border}`, paddingTop: 10 }}>
              Realized rates from priced summaries — interchange, scheme and ecom fees as the fee engine computed them, so caps and
              tier blends are already reflected. Untyped volume is shown separately and cannot be repriced until it is classified.
              Uplift applies the churn-elasticity assumption from the Blended what-if tab.
            </div>
          </div>
        </>
      )}
    </div>
  );
}

// ═══════════════════════════════════════════════════════════════════════════
// MID-wise repricing panel — one merchant's full segment breakdown
// (POST /business/pricing-simulator/merchant-matrix?mid=…). Every row is one
// (scheme × card type × Local/Intl) cell the merchant trades in, shown
// against the tenant segment benchmark (from the already-loaded matrix, no
// extra query), with a per-row Δbps lever and per-merchant uplift total.
// ═══════════════════════════════════════════════════════════════════════════
function MerchantPanel({ mid, name, buildDto, benchmarks, elasticity, annualFactor, annualized, onClose }) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [deltas, setDeltas] = useState({});  // {segKey: bps} — local to this merchant

  useEffect(() => {
    let alive = true;
    setLoading(true); setError(null); setDeltas({});
    api.post(`/business/pricing-simulator/merchant-matrix?mid=${encodeURIComponent(mid)}`, buildDto())
      .then((res) => { if (alive) setData(res.data || null); })
      .catch((e) => { if (alive) setError(e?.response?.data?.error || e.message || 'Failed to load merchant'); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
    // eslint-disable-next-line
  }, [mid]);

  const segs = (data?.segments || []).filter((s) => num(s.volume) !== 0);

  let totalUplift = 0;
  const rows = segs.map((s) => {
    const key = segKey(s);
    const bench = benchmarks[key];
    const vsPeer = bench && s.msfBps != null && bench.msfBps != null ? num(s.msfBps) - num(bench.msfBps) : null;
    const bpsUp = num(deltas[key]);
    const churnFrac = clamp((elasticity / 100) * (bpsUp / 10), 0, 0.30);
    const uplift = (num(s.volume) * bpsUp / 10000) * (1 - churnFrac) * annualFactor;
    totalUplift += uplift;
    return { s, key, vsPeer, bpsUp, uplift };
  });

  return (
    <div style={{ marginTop: 14, border: `1px solid ${T.brand}`, borderRadius: T.rlg, padding: 16, background: T.bg }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 10, flexWrap: 'wrap', marginBottom: 8 }}>
        <div>
          <div style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: 0.8, textTransform: 'uppercase', color: T.brand, marginBottom: 3 }}>Merchant repricing</div>
          <div style={{ fontSize: 14, fontWeight: 700 }}>
            {name || mid} <span style={{ color: T.muted, fontWeight: 400, fontFamily: T.mono, fontSize: 12 }}>{mid}</span>
          </div>
          {data && (
            <div style={{ fontSize: 12, color: T.muted, marginTop: 3, fontFamily: T.mono }}>
              {fmtMoney(num(data.totals?.volume))} · blended MSF {data.totals?.msfBps == null ? '—' : fmtBps(num(data.totals.msfBps))} · net take {data.totals?.netBps == null ? '—' : fmtBps(num(data.totals.netBps))}
            </div>
          )}
        </div>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
          {totalUplift !== 0 && (
            <span style={{ fontSize: 14, fontWeight: 700, fontFamily: T.mono, color: totalUplift >= 0 ? T.pos : T.neg }}>
              {fmtSigned(totalUplift)}{annualized ? '/yr' : ''}
            </span>
          )}
          {Object.keys(deltas).length > 0 && (
            <button onClick={() => setDeltas({})} style={ghostBtn}>Reset</button>
          )}
          <button onClick={onClose} style={ghostBtn}>Close</button>
        </div>
      </div>

      {loading && <div style={{ fontSize: 12.5, color: T.muted }}>Loading merchant segments…</div>}
      {error && !loading && <div style={{ fontSize: 12.5, color: T.neg }}>{error}</div>}
      {!loading && !error && rows.length === 0 && (
        <div style={{ fontSize: 12.5, color: T.muted }}>No priced volume for this merchant in the window.</div>
      )}

      {!loading && !error && rows.length > 0 && (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12.5 }}>
            <thead>
              <tr>
                <th style={thLeft}>Segment</th>
                <th style={thStyle}>Volume</th>
                <th style={thStyle}>MSF bps</th>
                <th style={thStyle}>vs peers</th>
                <th style={thStyle}>Cost bps</th>
                <th style={thStyle}>Net bps</th>
                <th style={thStyle}>Raise by</th>
                <th style={thStyle}>Uplift</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ s, key, vsPeer, bpsUp, uplift }) => {
                const untyped = s.cardType === 'UNSPECIFIED';
                return (
                  <tr key={key} style={{ borderTop: `1px solid ${T.border}`, background: s.belowCost ? 'rgba(179,56,44,0.05)' : 'transparent' }}>
                    <td style={{ padding: '7px 8px', whiteSpace: 'nowrap' }}>
                      <span style={{ fontWeight: 600 }}>{s.scheme}</span>
                      <span style={{ color: untyped ? T.warn : T.muted }}> · {untyped ? 'no card type' : s.cardType.toLowerCase()}</span>
                      <span style={{ color: T.muted }}> · {DEST_LABEL[s.destination]}</span>
                      {s.belowCost && <span style={{ color: T.neg, fontWeight: 700 }}> · below cost</span>}
                    </td>
                    <td style={tdNum}>{fmtMoney(num(s.volume))}</td>
                    <td style={tdNum}>{s.msfBps == null ? '—' : num(s.msfBps).toFixed(0)}</td>
                    <td style={{ ...tdNum, fontWeight: 700, color: vsPeer == null ? T.muted : vsPeer < 0 ? T.neg : T.pos }}>
                      {vsPeer == null ? '—' : `${vsPeer >= 0 ? '+' : ''}${vsPeer.toFixed(0)}`}
                    </td>
                    <td style={{ ...tdNum, color: T.muted }}>{s.costBps == null ? '—' : num(s.costBps).toFixed(0)}</td>
                    <td style={{ ...tdNum, fontWeight: 700, color: num(s.netBps) < 0 ? T.neg : T.pos }}>
                      {s.netBps == null ? '—' : num(s.netBps).toFixed(0)}
                    </td>
                    <td style={{ padding: '7px 8px', textAlign: 'right' }}>
                      {untyped ? (
                        <span title="No card type — classify first" style={{ color: T.muted, fontSize: 11 }}>n/a</span>
                      ) : (
                        <span style={{ whiteSpace: 'nowrap' }}>
                          <input
                            type="number" min={0} max={200} step={1}
                            value={bpsUp || ''}
                            placeholder="0"
                            onChange={(e) => setDeltas((p) => ({ ...p, [key]: clamp(num(e.target.value), 0, 200) }))}
                            style={{ width: 56, padding: '3px 6px', borderRadius: 6, border: `1px solid ${bpsUp ? T.pos : T.border}`, background: '#fff', color: T.text, fontSize: 12, fontFamily: T.mono, textAlign: 'right' }}
                          /> <span style={{ fontSize: 10, color: T.muted }}>bps</span>
                        </span>
                      )}
                    </td>
                    <td style={{ ...tdNum, fontWeight: 700, color: uplift > 0 ? T.pos : T.muted }}>
                      {bpsUp ? fmtSigned(uplift) : '—'}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <div style={{ fontSize: 11, color: T.muted, marginTop: 8, lineHeight: 1.55 }}>
            "vs peers" compares this merchant's effective MSF to the whole-tenant rate for the same segment —
            negative means priced under peers, which is repricing headroom. Uplift applies the churn-elasticity
            assumption{annualized ? ', annualized.' : ' over the window.'}
          </div>
        </div>
      )}
    </div>
  );
}
