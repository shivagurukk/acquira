import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Target, Loader2, Save, Trash2, ChevronDown, ChevronRight,
  Users, AlertTriangle, CheckCircle2, Layers,
} from 'lucide-react';
import api from '../../api/axios';
import { useAuth } from '../../contexts/AuthContext';
import { formatCompactCurrency } from '../../utils/formatters';
import { T, CARD } from '../../theme/salesTokens';

/*
 * SALES TARGETS — where an admin sets each sales executive's annual number.
 *
 * Entered ANNUALLY, stored MONTHLY. The admin types one figure for the year; the
 * backend writes twelve monthly rows so that month-to-date and quarter views can
 * prorate correctly. Expanding a row reveals those twelve months for hand-editing
 * when a year is genuinely seasonal.
 *
 * SHIPS EMPTY. Nothing is pre-filled and nothing is migrated from the legacy
 * per-agent monthly_target. Until someone saves here, the Executive Sales Pulse
 * page shows "—" in every target column and grades everyone against their own
 * history — which is the intended behaviour, not a degraded mode.
 *
 * Targets are per tenant. The API scopes every read and write to the caller's
 * tenant and rejects an agent that is not theirs, so switching tenant switches
 * the whole grid.
 */

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const yearOptions = () => {
  const now = new Date().getFullYear();
  return [now - 1, now, now + 1, now + 2];
};

export default function SalesTargetManagement() {
  const { tenantVersion } = useAuth();

  const [year, setYear] = useState(new Date().getFullYear());
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState('');            // salesUserId currently saving
  const [notice, setNotice] = useState(null);          // { kind, text }
  const [expanded, setExpanded] = useState(new Set());
  const [drafts, setDrafts] = useState({});            // salesUserId -> { annual, months[] }
  const [bulk, setBulk] = useState({ teamLeadId: '', amount: '' });

  const fetchGrid = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get(`/sales/targets/${year}`);
      setData(res.data);
      setDrafts({});
    } catch (e) {
      setNotice({ kind: 'error', text: e?.response?.data?.error || 'Could not load targets.' });
    } finally {
      setLoading(false);
    }
  }, [year]);

  useEffect(() => { fetchGrid(); }, [fetchGrid, tenantVersion]);

  const money = useCallback((v) => {
    if (v == null || v === '') return '—';
    const c = data?.currency;
    return c?.resolved ? formatCompactCurrency(v, c.code, c.decimals) : formatCompactCurrency(v);
  }, [data?.currency]);

  const agents = data?.agents || [];

  const teams = useMemo(() => {
    const byTeam = new Map();
    for (const a of agents) {
      const key = a.teamLeadId ?? 'none';
      if (!byTeam.has(key)) {
        byTeam.set(key, { id: a.teamLeadId, name: a.teamLeadName || 'Unassigned', agents: [] });
      }
      byTeam.get(key).agents.push(a);
    }
    return [...byTeam.values()];
  }, [agents]);

  // ── Draft handling ────────────────────────────────────────────────────────
  const draftFor = (a) => drafts[a.salesUserId] || {
    annual: a.annualTarget == null ? '' : String(a.annualTarget),
    months: a.months || Array(12).fill(null),
    phasing: a.source === 'MANUAL' && a.annualTarget != null ? 'MANUAL' : 'EQUAL',
  };

  const setAnnual = (a, value) => {
    // Editing the annual figure re-splits the year evenly. Any hand-edited months
    // are intentionally discarded — leaving them would produce twelve months that
    // no longer add up to the annual number on screen, which is worse than losing
    // the edit.
    setDrafts((d) => ({
      ...d,
      [a.salesUserId]: { ...(d[a.salesUserId] || draftFor(a)), annual: value, phasing: 'EQUAL', months: null },
    }));
  };

  const setMonth = (a, idx, value) => {
    const cur = drafts[a.salesUserId] || draftFor(a);
    const months = [...(cur.months || evenSplit(cur.annual) || Array(12).fill(null))];
    months[idx] = value === '' ? null : value;
    setDrafts((d) => ({ ...d, [a.salesUserId]: { ...cur, months, phasing: 'MANUAL' } }));
  };

  const save = async (a) => {
    const draft = drafts[a.salesUserId] || draftFor(a);
    setSaving(a.salesUserId);
    try {
      const body = draft.phasing === 'MANUAL' && draft.months
        ? {
            year, salesUserId: a.salesUserId, phasing: 'MANUAL',
            months: draft.months.map((m) => (m === null || m === '' ? null : Number(m))),
          }
        : { year, salesUserId: a.salesUserId, phasing: 'EQUAL', annualTarget: Number(draft.annual) };

      if (body.phasing === 'EQUAL' && !(body.annualTarget > 0)) {
        setNotice({ kind: 'error', text: 'Enter an annual target greater than zero, or clear the row instead.' });
        return;
      }
      await api.post('/sales/targets/yearly', body);
      setNotice({ kind: 'ok', text: `${a.displayName}'s ${year} target saved.` });
      await fetchGrid();
    } catch (e) {
      setNotice({ kind: 'error', text: e?.response?.data?.error || 'Could not save the target.' });
    } finally {
      setSaving('');
    }
  };

  const clear = async (a) => {
    if (!window.confirm(`Clear ${a.displayName}'s ${year} target? Their performance will then be measured against their own history only.`)) return;
    setSaving(a.salesUserId);
    try {
      await api.delete(`/sales/targets/${year}/${encodeURIComponent(a.salesUserId)}`);
      setNotice({ kind: 'ok', text: `${a.displayName}'s ${year} target cleared.` });
      await fetchGrid();
    } catch (e) {
      setNotice({ kind: 'error', text: e?.response?.data?.error || 'Could not clear the target.' });
    } finally {
      setSaving('');
    }
  };

  const applyBulk = async () => {
    const amount = Number(bulk.amount);
    if (!(amount > 0)) {
      setNotice({ kind: 'error', text: 'Enter an annual target greater than zero.' });
      return;
    }
    const targetAgents = bulk.teamLeadId
      ? agents.filter((a) => String(a.teamLeadId) === String(bulk.teamLeadId))
      : agents;
    if (targetAgents.length === 0) {
      setNotice({ kind: 'error', text: 'No sales executives match that selection.' });
      return;
    }
    if (!window.confirm(`Set the ${year} target to ${money(amount)} for ${targetAgents.length} sales executive(s)? This replaces any existing ${year} target for them.`)) return;

    setSaving('BULK');
    try {
      await api.post('/sales/targets/bulk', targetAgents.map((a) => ({
        year, salesUserId: a.salesUserId, phasing: 'EQUAL', annualTarget: amount,
      })));
      setNotice({ kind: 'ok', text: `${targetAgents.length} target(s) saved for ${year}.` });
      setBulk({ teamLeadId: '', amount: '' });
      await fetchGrid();
    } catch (e) {
      setNotice({ kind: 'error', text: e?.response?.data?.error || 'Bulk apply failed — nothing was saved.' });
    } finally {
      setSaving('');
    }
  };

  const toggle = (id) => setExpanded((s) => {
    const n = new Set(s);
    if (n.has(id)) n.delete(id); else n.add(id);
    return n;
  });

  const configuredCount = agents.filter((a) => a.annualTarget != null).length;

  return (
    <div style={{ padding: 20, background: T.bg, minHeight: '100%' }}>

      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 14, flexWrap: 'wrap', marginBottom: 14 }}>
        <div>
          <h1 style={{ display: 'flex', alignItems: 'center', gap: 9, margin: 0, fontSize: 21, fontWeight: 700, color: T.text }}>
            <Target size={21} color={T.brand} /> Sales Targets
          </h1>
          <p style={{ margin: '4px 0 0', fontSize: 12, color: T.textMut, maxWidth: 620, lineHeight: 1.5 }}>
            Set each sales executive's target for the year. Targets are optional —
            the Executive Sales Pulse page works without them and measures everyone
            against their own history. {configuredCount} of {agents.length} configured for {year}.
          </p>
        </div>

        <select value={year} onChange={(e) => setYear(Number(e.target.value))} style={control}>
          {yearOptions().map((y) => <option key={y} value={y}>{y}</option>)}
        </select>
      </div>

      {notice && (
        <div style={{
          ...CARD, padding: '10px 13px', marginBottom: 12, fontSize: 12.5,
          display: 'flex', alignItems: 'center', gap: 8,
          background: notice.kind === 'ok' ? T.successBg : T.dangerBg,
          borderColor: notice.kind === 'ok' ? T.success : T.danger,
          color: notice.kind === 'ok' ? T.successTx : T.dangerTx,
        }}>
          {notice.kind === 'ok' ? <CheckCircle2 size={15} /> : <AlertTriangle size={15} />}
          <span style={{ flex: 1 }}>{notice.text}</span>
          <button onClick={() => setNotice(null)} style={{ border: 'none', background: 'none', cursor: 'pointer', color: 'inherit', fontSize: 12 }}>
            Dismiss
          </button>
        </div>
      )}

      {/* ── Bulk apply ───────────────────────────────────────────────────── */}
      <div style={{ ...CARD, padding: 14, marginBottom: 14, display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, fontSize: 12.5, fontWeight: 600, color: T.text }}>
          <Layers size={15} color={T.brand} /> Bulk apply
        </span>
        <select value={bulk.teamLeadId} onChange={(e) => setBulk((b) => ({ ...b, teamLeadId: e.target.value }))} style={control}>
          <option value="">Every sales executive</option>
          {teams.filter((t) => t.id != null).map((t) => (
            <option key={t.id} value={t.id}>{t.name}'s team</option>
          ))}
        </select>
        <input
          type="number" min="0" placeholder="Annual target" value={bulk.amount}
          onChange={(e) => setBulk((b) => ({ ...b, amount: e.target.value }))}
          style={{ ...control, cursor: 'text', width: 160 }}
        />
        <button onClick={applyBulk} disabled={saving === 'BULK'} style={primaryBtn}>
          {saving === 'BULK' ? <Loader2 size={14} className="spin" /> : <Save size={14} />} Apply to {year}
        </button>
      </div>

      {/* ── Grid ─────────────────────────────────────────────────────────── */}
      {loading ? (
        <div style={{ ...CARD, padding: 40, textAlign: 'center', color: T.textMut }}>
          <Loader2 size={20} className="spin" />
          <div style={{ marginTop: 8, fontSize: 13 }}>Loading targets…</div>
        </div>
      ) : agents.length === 0 ? (
        <div style={{ ...CARD, padding: 40, textAlign: 'center' }}>
          <div style={{ fontSize: 14, fontWeight: 600, color: T.text, marginBottom: 4 }}>No sales executives found.</div>
          <div style={{ fontSize: 12.5, color: T.textMut }}>
            Sales executives appear here once they exist in the Sales Agent Directory.
          </div>
        </div>
      ) : (
        teams.map((team) => (
          <div key={team.id ?? 'none'} style={{ ...CARD, padding: 0, marginBottom: 12, overflow: 'hidden' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '10px 14px', background: T.subtle, borderBottom: `1px solid ${T.border}` }}>
              <Users size={14} color={T.brand} />
              <span style={{ fontWeight: 700, fontSize: 13.5, color: T.text }}>{team.name}</span>
              <span style={{ fontSize: 11.5, color: T.textMut }}>
                {team.agents.length} sales {team.agents.length === 1 ? 'executive' : 'executives'}
              </span>
            </div>

            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 660 }}>
                <thead>
                  <tr>
                    <th style={{ ...th, textAlign: 'left' }}>Sales Executive</th>
                    <th style={{ ...th, width: 180 }}>Annual Target {year}</th>
                    <th style={{ ...th, width: 130 }}>Monthly (even)</th>
                    <th style={{ ...th, width: 180 }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {team.agents.map((a) => {
                    const draft = drafts[a.salesUserId];
                    const annual = draft ? draft.annual : (a.annualTarget == null ? '' : a.annualTarget);
                    const dirty = !!draft;
                    const open = expanded.has(a.salesUserId);
                    const perMonth = Number(annual) > 0 ? Number(annual) / 12 : null;

                    return (
                      <React.Fragment key={a.salesUserId}>
                        <tr style={{ borderBottom: `1px solid ${T.borderLt}` }}>
                          <td style={{ ...td, textAlign: 'left' }}>
                            <button onClick={() => toggle(a.salesUserId)}
                                    title="Edit individual months"
                                    style={{ border: 'none', background: 'none', cursor: 'pointer', color: T.textMut, marginRight: 6, verticalAlign: 'middle' }}>
                              {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                            </button>
                            <span style={{ fontWeight: 600 }}>{a.displayName}</span>
                            <div style={{ fontSize: 10.5, color: T.textMut, marginLeft: 20 }}>
                              {a.salesUserId}{a.salesEmail ? ` · ${a.salesEmail}` : ''}
                            </div>
                          </td>
                          <td style={td}>
                            <input
                              type="number" min="0" placeholder="Not set"
                              value={annual === null ? '' : annual}
                              onChange={(e) => setAnnual(a, e.target.value)}
                              style={{ ...control, cursor: 'text', width: 150, textAlign: 'right' }}
                            />
                          </td>
                          <td style={{ ...td, color: T.textMut }}>
                            {perMonth ? money(perMonth) : '—'}
                          </td>
                          <td style={td}>
                            <button onClick={() => save(a)} disabled={saving === a.salesUserId || !dirty}
                                    style={{ ...primaryBtn, opacity: dirty ? 1 : 0.45, marginRight: 6 }}>
                              {saving === a.salesUserId ? <Loader2 size={13} className="spin" /> : <Save size={13} />} Save
                            </button>
                            {a.annualTarget != null && (
                              <button onClick={() => clear(a)} disabled={saving === a.salesUserId} title="Clear this year's target"
                                      style={ghostBtn}>
                                <Trash2 size={13} />
                              </button>
                            )}
                          </td>
                        </tr>

                        {open && (
                          <tr>
                            <td colSpan={4} style={{ padding: '10px 14px 14px', background: T.subtle }}>
                              <div style={{ fontSize: 11, color: T.textMut, marginBottom: 8 }}>
                                Individual months — leave a month blank for no target. Editing any
                                month switches this year to manual phasing; editing the annual figure
                                above resets it to an even split.
                              </div>
                              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(96px, 1fr))', gap: 8 }}>
                                {MONTHS.map((label, i) => {
                                  const cur = drafts[a.salesUserId];
                                  const fromDraft = cur?.months ? cur.months[i] : undefined;
                                  const fallback = a.months ? a.months[i] : null;
                                  const value = fromDraft !== undefined ? fromDraft : fallback;
                                  return (
                                    <label key={label} style={{ display: 'block' }}>
                                      <span style={{ display: 'block', fontSize: 10, color: T.textMut, marginBottom: 3 }}>{label}</span>
                                      <input
                                        type="number" min="0" placeholder="—"
                                        value={value == null ? '' : value}
                                        onChange={(e) => setMonth(a, i, e.target.value)}
                                        style={{ ...control, cursor: 'text', width: '100%', textAlign: 'right', padding: '5px 7px' }}
                                      />
                                    </label>
                                  );
                                })}
                              </div>
                            </td>
                          </tr>
                        )}
                      </React.Fragment>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        ))
      )}
    </div>
  );
}

/** Preview split used when a row is expanded before anything is typed. */
function evenSplit(annual) {
  const n = Number(annual);
  if (!(n > 0)) return null;
  const per = n / 12;
  return Array(12).fill(Math.round(per * 10000) / 10000);
}

const control = {
  padding: '7px 10px', borderRadius: 8, border: `1px solid ${T.border}`,
  background: T.card, color: T.text, fontSize: 12.5, fontWeight: 500,
};

const primaryBtn = {
  display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 12px',
  borderRadius: 8, border: 'none', background: T.brand, color: '#fff',
  fontSize: 12.5, fontWeight: 600, cursor: 'pointer',
};

const ghostBtn = {
  display: 'inline-flex', alignItems: 'center', padding: '7px 9px',
  borderRadius: 8, border: `1px solid ${T.border}`, background: T.card,
  color: T.danger, cursor: 'pointer',
};

const th = {
  padding: '8px 12px', fontSize: 10.5, fontWeight: 700, color: T.textMut,
  textTransform: 'uppercase', letterSpacing: 0.5, textAlign: 'right',
  borderBottom: `1px solid ${T.border}`, whiteSpace: 'nowrap',
};

const td = {
  padding: '9px 12px', fontSize: 12.5, color: T.text, textAlign: 'right',
  fontVariantNumeric: 'tabular-nums',
};
