import React, { useMemo, useState, useCallback, useRef, useEffect } from 'react';
import {
    BookOpen, Search, FileText, ChevronRight, X, Landmark, CalendarClock,
    Receipt, Filter,
} from 'lucide-react';
import PageHeader from '../../components/PageHeader';
import mcbs from '../../data/mcbsAcquirerReports.json';

/* ════════════════════════════════════════════════════════════════════
   SCHEME BILLING REFERENCE (Mastercard MCBS)

   Read-only, fully static reference of the acquirer-relevant report and
   invoice-file specifications from the Mastercard Consolidated Billing
   System manual. The JSON bundle is extracted OFFLINE from the DITA-XML
   manual (see docs — mcbsAcquirerReports.json); nothing here talks to
   the backend, and per the reference-data rule this screen never writes.

   This is the companion to the (future) T0CH/BFIL invoice reader: when a
   billing line appears, its report spec is one click away here.

   MOTION: none — CSS transitions only (browser preview panes serve no
   animation frames while hidden).
   ════════════════════════════════════════════════════════════════════ */

const card = {
    background: 'var(--bg-card)',
    border: '1px solid var(--border)',
    borderRadius: 8,
};

/* Meta fields lifted out of a topic's first Field/Description table for
   the summary chips. Everything else stays in the rendered tables. */
const META_KEYS = ['Audience', 'Delivery Frequency', 'Delivery By (Timing)', 'Delivery Mode', 'Billable'];

function topicMeta(topic) {
    const meta = {};
    for (const t of topic.tables) {
        for (const row of t.rows) {
            if (row.length >= 2 && META_KEYS.includes(row[0])) meta[row[0]] = row[1];
        }
    }
    return meta;
}

/* Coarse grouping by report-id prefix so the left list reads as a TOC. */
function topicGroup(title) {
    if (/^AB\d/.test(title)) return 'Authorization (AB)';
    if (/^GB\d/.test(title)) return 'Clearing & Services (GB)';
    if (/T0CH|BFIL|TN3A|T0CF|Bulk Data File/i.test(title)) return 'Invoice Data Files';
    if (/invoice|credit note|commission note|statement/i.test(title)) return 'Invoices & Statements';
    if (/billing summary|summarized billing|collection/i.test(title)) return 'Billing Summaries';
    return 'Other Reports';
}
const GROUP_ORDER = [
    'Invoice Data Files', 'Billing Summaries', 'Invoices & Statements',
    'Authorization (AB)', 'Clearing & Services (GB)', 'Other Reports',
];

const BILLABLE_FILTERS = ['All', 'Billable', 'Non-billable'];

export default function SchemeBillingReference() {
    const [query, setQuery] = useState('');
    const [billable, setBillable] = useState('All');
    const [selected, setSelected] = useState(0);
    const detailRef = useRef(null);

    const topics = mcbs.topics;
    const metas = useMemo(() => topics.map(topicMeta), [topics]);

    const filtered = useMemo(() => {
        const q = query.trim().toLowerCase();
        return topics
            .map((t, i) => ({ t, i, meta: metas[i] }))
            .filter(({ t, meta }) => {
                if (billable === 'Billable' && !/yes/i.test(meta.Billable || '')) return false;
                if (billable === 'Non-billable' && /yes/i.test(meta.Billable || '')) return false;
                if (!q) return true;
                if (t.title.toLowerCase().includes(q)) return true;
                if ((t.short || '').toLowerCase().includes(q)) return true;
                // search inside spec tables too — field names matter here
                return t.tables.some(tb => tb.rows.some(r => r.some(c => c.toLowerCase().includes(q))));
            });
    }, [topics, metas, query, billable]);

    const grouped = useMemo(() => {
        const g = {};
        for (const item of filtered) {
            const grp = topicGroup(item.t.title);
            (g[grp] = g[grp] || []).push(item);
        }
        return GROUP_ORDER.filter(k => g[k]?.length).map(k => [k, g[k]]);
    }, [filtered]);

    // Keep selection valid as filters change.
    useEffect(() => {
        if (!filtered.some(f => f.i === selected) && filtered.length) setSelected(filtered[0].i);
    }, [filtered, selected]);

    const select = useCallback((i) => {
        setSelected(i);
        if (detailRef.current) detailRef.current.scrollTop = 0;
    }, []);

    const topic = topics[selected];
    const meta = metas[selected] || {};

    return (
        <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
            <PageHeader
                title="Scheme Billing Reference"
                subtitle={`Mastercard Consolidated Billing System — ${mcbs.topicCount} acquirer-relevant report & file specifications (${mcbs.source.replace('Mastercard Consolidated Billing System manual, ', '')})`}
            />

            <div style={{
                display: 'flex', gap: 16, padding: 'var(--space-page, 24px)',
                flex: 1, minHeight: 0, alignItems: 'stretch',
            }}>
                {/* ── Left: search + grouped TOC ─────────────────────── */}
                <div style={{ ...card, width: 340, minWidth: 280, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
                    <div style={{ padding: 12, borderBottom: '1px solid var(--border)' }}>
                        <div style={{ position: 'relative' }}>
                            <Search size={14} style={{ position: 'absolute', left: 10, top: 9, color: 'var(--text-muted)' }} />
                            <input
                                value={query}
                                onChange={e => setQuery(e.target.value)}
                                placeholder="Search reports, fields, record layouts…"
                                style={{
                                    width: '100%', boxSizing: 'border-box',
                                    padding: '7px 28px 7px 30px',
                                    background: 'var(--bg-subtle)', color: 'var(--text)',
                                    border: '1px solid var(--border)', borderRadius: 6,
                                    fontSize: 13, outline: 'none',
                                }}
                            />
                            {query && (
                                <button onClick={() => setQuery('')} aria-label="Clear search" style={{
                                    position: 'absolute', right: 6, top: 6, border: 'none',
                                    background: 'transparent', cursor: 'pointer', color: 'var(--text-muted)', padding: 2,
                                }}><X size={14} /></button>
                            )}
                        </div>
                        <div style={{ display: 'flex', gap: 6, marginTop: 10, alignItems: 'center' }}>
                            <Filter size={12} style={{ color: 'var(--text-muted)' }} />
                            {BILLABLE_FILTERS.map(f => (
                                <button key={f} onClick={() => setBillable(f)} style={{
                                    padding: '3px 10px', borderRadius: 999, fontSize: 11.5, cursor: 'pointer',
                                    border: `1px solid ${billable === f ? 'var(--primary)' : 'var(--border)'}`,
                                    background: billable === f ? 'var(--wash, var(--bg-subtle))' : 'transparent',
                                    color: billable === f ? 'var(--primary)' : 'var(--text-muted)',
                                    transition: 'all .15s',
                                }}>{f}</button>
                            ))}
                        </div>
                    </div>

                    <div style={{ overflowY: 'auto', flex: 1, minHeight: 0, padding: '6px 0' }}>
                        {grouped.map(([grp, items]) => (
                            <div key={grp}>
                                <div style={{
                                    padding: '10px 14px 4px', fontSize: 10.5, fontWeight: 700,
                                    letterSpacing: '0.08em', textTransform: 'uppercase',
                                    color: 'var(--text-muted)',
                                }}>{grp} · {items.length}</div>
                                {items.map(({ t, i, meta: m }) => (
                                    <button key={i} onClick={() => select(i)} style={{
                                        display: 'flex', alignItems: 'center', gap: 8, width: '100%',
                                        textAlign: 'left', padding: '7px 14px', border: 'none',
                                        cursor: 'pointer', fontSize: 12.5, lineHeight: 1.35,
                                        background: i === selected ? 'var(--wash, var(--bg-subtle))' : 'transparent',
                                        color: i === selected ? 'var(--primary)' : 'var(--text)',
                                        borderLeft: `3px solid ${i === selected ? 'var(--primary)' : 'transparent'}`,
                                        transition: 'background .15s',
                                    }}>
                                        <FileText size={13} style={{ flexShrink: 0, opacity: 0.6 }} />
                                        <span style={{ flex: 1 }}>{t.title}</span>
                                        {/yes/i.test(m.Billable || '') && (
                                            <Receipt size={12} style={{ flexShrink: 0, color: 'var(--attention)' }} title="Billable" />
                                        )}
                                        {i === selected && <ChevronRight size={13} style={{ flexShrink: 0 }} />}
                                    </button>
                                ))}
                            </div>
                        ))}
                        {!filtered.length && (
                            <div style={{ padding: 20, fontSize: 13, color: 'var(--text-muted)', textAlign: 'center' }}>
                                No reports match “{query}”.
                            </div>
                        )}
                    </div>
                </div>

                {/* ── Right: spec detail ─────────────────────────────── */}
                <div ref={detailRef} style={{ ...card, flex: 1, minWidth: 0, overflowY: 'auto', padding: 20 }}>
                    {topic ? (
                        <>
                            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
                                <BookOpen size={18} style={{ color: 'var(--primary)', marginTop: 2, flexShrink: 0 }} />
                                <div style={{ minWidth: 0 }}>
                                    <div style={{ fontSize: 16, fontWeight: 700, color: 'var(--text)' }}>{topic.title}</div>
                                    {topic.short && (
                                        <div style={{ fontSize: 13, color: 'var(--text-muted)', marginTop: 4 }}>{topic.short}</div>
                                    )}
                                </div>
                            </div>

                            {/* meta chips */}
                            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, margin: '14px 0 4px' }}>
                                {meta.Audience && <MetaChip Icon={Landmark} label="Audience" value={meta.Audience} />}
                                {meta['Delivery Frequency'] && <MetaChip Icon={CalendarClock} label="Frequency" value={meta['Delivery Frequency']} />}
                                {meta['Delivery Mode'] && <MetaChip Icon={FileText} label="Mode" value={meta['Delivery Mode']} />}
                                {meta.Billable && (
                                    <MetaChip Icon={Receipt} label="Billable" value={meta.Billable}
                                        accent={/yes/i.test(meta.Billable)} />
                                )}
                            </div>

                            {topic.tables.map((tb, ti) => (
                                <SpecTable key={ti} table={tb} topicTitle={topic.title} />
                            ))}
                        </>
                    ) : (
                        <div style={{ color: 'var(--text-muted)', fontSize: 13 }}>Select a report on the left.</div>
                    )}
                </div>
            </div>
        </div>
    );
}

const MetaChip = ({ Icon, label, value, accent }) => (
    <span style={{
        display: 'inline-flex', alignItems: 'center', gap: 6,
        padding: '4px 10px', borderRadius: 6, fontSize: 12,
        background: accent ? 'var(--warning-bg, var(--bg-subtle))' : 'var(--bg-subtle)',
        border: '1px solid var(--border)',
        color: accent ? 'var(--attention)' : 'var(--text)',
    }}>
        <Icon size={12} style={{ opacity: 0.7 }} />
        <span style={{ color: 'var(--text-muted)' }}>{label}:</span>
        <span style={{ fontWeight: 600 }}>{value}</span>
    </span>
);

function SpecTable({ table, topicTitle }) {
    const showTitle = table.title && table.title !== topicTitle;
    return (
        <div style={{ marginTop: 16 }}>
            {showTitle && (
                <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--text)', marginBottom: 6 }}>
                    {table.title}
                </div>
            )}
            <div style={{ overflowX: 'auto', border: '1px solid var(--border)', borderRadius: 6 }}>
                <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: 12.5 }}>
                    <tbody>
                        {table.rows.map((row, ri) => {
                            const isHead = ri < (table.headerCount || 0);
                            return (
                                <tr key={ri} style={{
                                    background: isHead ? 'var(--bg-subtle)' : 'transparent',
                                    borderTop: ri ? '1px solid var(--border)' : 'none',
                                }}>
                                    {row.map((cell, ci) => (
                                        <td key={ci} style={{
                                            padding: '6px 10px', verticalAlign: 'top', textAlign: 'left',
                                            fontWeight: isHead ? 700 : ci === 0 ? 600 : 400,
                                            color: isHead || ci === 0 ? 'var(--text)' : 'var(--text-muted)',
                                            whiteSpace: 'pre-wrap',
                                            // field-name column stays narrow; numeric position cols stay tight
                                            minWidth: ci === 0 ? 130 : undefined,
                                        }}>{cell}</td>
                                    ))}
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
