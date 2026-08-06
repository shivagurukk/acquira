import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
    Box, Typography, IconButton, Button, TextField, CircularProgress,
    Tooltip, Dialog, DialogTitle, DialogContent, DialogActions, Checkbox,
    FormControlLabel, Collapse, InputAdornment, LinearProgress, Fade, Stack,
    Paper, Card, CardContent, Divider, Chip, Badge, Avatar, Menu, MenuItem
} from '@mui/material';
import {
    Compass, BarChart3, Table2, PieChart, TrendingUp,
    Save, X, Play, Download, ChevronDown, ChevronRight, Search,
    Filter, Star, Share2, Eye, CreditCard, MapPin, Users,
    Tag, Calendar, DollarSign, Activity, Settings,
    Maximize2, Minimize2, Zap, Database, ArrowRight, Hash, Bookmark,
    RotateCcw, Sparkles, Clock, Target, Layers, Plus, Trash2,
    Palette, Radar, GitBranch, Gauge,
    LayoutGrid, ArrowUpDown, Copy, ChevronLeft, BarChart2, Columns, Crosshair, Bell, AlarmClock, Sigma, Percent, Calculator, Pencil, Wand2, GripVertical
} from 'lucide-react';
import {
    BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as RTooltip,
    ResponsiveContainer, PieChart as RPieChart, Pie, Cell, Legend,
    LineChart, Line, AreaChart, Area, ScatterChart, Scatter, ZAxis,
    RadarChart, Radar as RadarShape, PolarGrid, PolarAngleAxis, PolarRadiusAxis,
    ComposedChart, Treemap, RadialBarChart, RadialBar,
    ReferenceLine, LabelList, FunnelChart, Funnel, Sankey, Layer, Rectangle
} from 'recharts';
import { explorerApi, reportApi, savedViewsApi } from '../../api/explorer';
import { SectionLoader } from '../../components/Loaders';
import { useAuth } from '../../contexts/AuthContext';
import RGL, { WidthProvider } from 'react-grid-layout';

const SheetGrid = WidthProvider(RGL);

/* ═══════════════════════════════════════════════════════
   DESIGN TOKENS
   ═══════════════════════════════════════════════════════ */
const T = {
    primary: '#4361ee', primaryDark: '#3a56d4', primaryLight: '#6980f2',
    primaryGhost: 'rgba(67,97,238,0.06)', primaryBorder: 'rgba(67,97,238,0.18)',
    green: '#00b37e', greenGhost: 'rgba(0,179,126,0.06)', greenBorder: 'rgba(0,179,126,0.18)',
    amber: '#ff9f1c', rose: '#ef476f', cyan: '#06d6a0', purple: '#7209b7',
    sidebar: '#0a0e1a', sidebarHover: 'rgba(255,255,255,0.04)', sidebarActive: 'rgba(67,97,238,0.12)',
    workspace: '#eef1f7', card: '#ffffff', cardBorder: 'rgba(15,23,42,0.08)',
    surfaceMuted: '#eef1f6', surfaceMutedHover: '#e4e9f1', hairline: 'rgba(15,23,42,0.06)',
    tw: 'rgba(255,255,255,0.92)', tw2: 'rgba(255,255,255,0.52)', tw3: 'rgba(255,255,255,0.26)',
    td: '#0f172a', td2: '#64748b', td3: '#94a3b8',
    radius: 11, shadow: '0 1px 2px rgba(15,23,42,0.04), 0 1px 3px rgba(15,23,42,0.05)',
    shadowMd: '0 6px 20px -6px rgba(15,23,42,0.12), 0 2px 6px -2px rgba(15,23,42,0.06)',
    shadowLg: '0 24px 50px -12px rgba(15,23,42,0.20)',
};

/* ═══ COLOR THEMES ═══ */
const THEMES = {
    default:  { name: 'Vivid',     colors: ['#4361ee','#00b37e','#ff9f1c','#ef476f','#7209b7','#06d6a0','#f72585','#4cc9f0','#fb5607','#8338ec'] },
    ocean:    { name: 'Ocean',     colors: ['#0077b6','#00b4d8','#48cae4','#90e0ef','#023e8a','#0096c7','#caf0f8','#03045e','#ade8f4','#0077b6'] },
    sunset:   { name: 'Sunset',    colors: ['#e63946','#f4845f','#f7b267','#f25c54','#c9184a','#ff6b6b','#ffd166','#f77f00','#d62828','#fcbf49'] },
    forest:   { name: 'Forest',    colors: ['#2d6a4f','#40916c','#52b788','#74c69d','#95d5b2','#1b4332','#b7e4c7','#081c15','#d8f3dc','#344e41'] },
    purple:   { name: 'Amethyst',  colors: ['#7209b7','#560bad','#480ca8','#3a0ca3','#b5179e','#f72585','#7b2cbf','#9d4edd','#c77dff','#e0aaff'] },
    mono:     { name: 'Monochrome',colors: ['#212529','#495057','#6c757d','#adb5bd','#343a40','#868e96','#ced4da','#495057','#212529','#dee2e6'] },
    neon:     { name: 'Neon',      colors: ['#00f5d4','#00bbf9','#fee440','#f15bb5','#9b5de5','#00f5d4','#fb5607','#ff006e','#8338ec','#3a86ff'] },
    earth:    { name: 'Earth',     colors: ['#774936','#a68a64','#656d4a','#936639','#7f5539','#b6ad90','#414833','#582f0e','#a4ac86','#6b705c'] },
    pastel:   { name: 'Pastel',    colors: ['#a2d2ff','#bde0fe','#ffafcc','#ffc8dd','#cdb4db','#bee1e6','#fad2e1','#e2ece9','#dfe7fd','#fff1e6'] },
    finance:  { name: 'Finance',   colors: ['#1a535c','#4ecdc4','#ff6b6b','#ffe66d','#2ec4b6','#e71d36','#011627','#fdfffc','#ff9f1c','#c5c3c6'] },
    corporate:{ name: 'Corporate', colors: ['#003049','#d62828','#f77f00','#fcbf49','#eae2b7','#264653','#2a9d8f','#e9c46a','#f4a261','#e76f51'] },
    candy:    { name: 'Candy',     colors: ['#ff595e','#ffca3a','#8ac926','#1982c4','#6a4c93','#ff595e','#ff924c','#ffca3a','#c5ca30','#36949d'] },
};

/* ═══ CHART TYPE REGISTRY ═══ */
const CHART_TYPES = [
    { key: 'bar',        label: 'Bar',          icon: BarChart3,    group: 'basic' },
    { key: 'stackedBar', label: 'Stacked Bar',  icon: BarChart2,    group: 'basic' },
    { key: 'hbar',       label: 'Horizontal',   icon: Columns,      group: 'basic' },
    { key: 'line',       label: 'Line',         icon: TrendingUp,   group: 'basic' },
    { key: 'area',       label: 'Area',         icon: Activity,     group: 'basic' },
    { key: 'pie',        label: 'Donut',        icon: PieChart,     group: 'basic' },
    { key: 'scatter',    label: 'Scatter',      icon: Crosshair,    group: 'advanced' },
    { key: 'radar',      label: 'Radar',        icon: Radar,        group: 'advanced' },
    { key: 'combo',      label: 'Combo',        icon: GitBranch,    group: 'advanced' },
    { key: 'treemap',    label: 'Treemap',      icon: LayoutGrid,   group: 'advanced' },
    { key: 'gauge',      label: 'Gauge',        icon: Gauge,        group: 'advanced' },
    { key: 'waterfall',  label: 'Waterfall',    icon: ArrowUpDown,  group: 'advanced' },
    { key: 'heatmap',    label: 'Heatmap',      icon: Layers,       group: 'advanced' },
    { key: 'funnel',     label: 'Funnel',       icon: Filter,       group: 'advanced' },
    { key: 'sankey',     label: 'Sankey',       icon: GitBranch,    group: 'advanced' },
    { key: 'kpi',        label: 'KPI',          icon: Hash,         group: 'advanced' },
    { key: 'bullet',     label: 'Bullet',       icon: Target,       group: 'advanced' },
];

const CATS = {
    identity:{ icon: Hash, color: '#6980f2' }, organization:{ icon: Layers, color: '#a78bfa' },
    people:{ icon: Users, color: '#f472b6' }, classification:{ icon: Tag, color: '#fbbf24' },
    location:{ icon: MapPin, color: '#34d399' }, status:{ icon: Activity, color: '#22d3ee' },
    terminal:{ icon: Settings, color: '#c084fc' }, dates:{ icon: Clock, color: '#fb923c' },
    card:{ icon: CreditCard, color: '#fb7185' }, flags:{ icon: Target, color: '#a3e635' },
    amount:{ icon: DollarSign, color: '#2dd4bf' },
};
const gc = k => CATS[k] || { icon: Tag, color: '#9ca3af' };
const fmt = v => v == null ? '—' : Number(v).toLocaleString('en-US', { maximumFractionDigits: 2 });
const fmtK = v => {
    if (v == null) return '—';
    const n = Number(v);
    if (Math.abs(n) >= 1e9) return (n / 1e9).toFixed(1) + 'B';
    if (Math.abs(n) >= 1e6) return (n / 1e6).toFixed(1) + 'M';
    if (Math.abs(n) >= 1e3) return (n / 1e3).toFixed(1) + 'K';
    return n % 1 === 0 ? n.toString() : n.toFixed(2);
};

/* ═══ Per-measure display formatting (Measure Studio · A5) ═══ */
// Sensible defaults for the built-in base measures; custom measures carry their own `format`.
const BASE_FMT = {
    txn_count: { unit: 'number', decimals: 0 },
    distinct_merchants: { unit: 'number', decimals: 0 },
    distinct_cards: { unit: 'number', decimals: 0 },
    total_volume: { unit: 'currency', decimals: 2 },
    // MSF is stored at 4-dp precision so finance can reconcile to the fils;
    // 2 dp here would hide exactly the digits they check against source files.
    total_msf: { unit: 'currency', decimals: 4 },
    total_vat: { unit: 'currency', decimals: 2 },
    total_settled: { unit: 'currency', decimals: 2 },
    total_interchange: { unit: 'currency', decimals: 2 },
    total_txn_currency_amount: { unit: 'currency', decimals: 2 },
    avg_txn_value: { unit: 'currency', decimals: 2 },
    net_revenue: { unit: 'currency', decimals: 2 },
    avg_msf_per_txn: { unit: 'currency', decimals: 4 },
    effective_msf_rate: { unit: 'number', decimals: 1, suffix: ' bps' },
    interchange_rate: { unit: 'number', decimals: 1, suffix: ' bps' },
    settlement_ratio: { unit: 'number', decimals: 1, suffix: '%' },
};
// Format a number per a {unit,decimals,scale,prefix,suffix} spec. Currency is symbol-agnostic
// (multi-tenant currencies vary) — use prefix/suffix for a symbol.
const formatMeasure = (n, f) => {
    f = f || {};
    if (n == null || isNaN(Number(n))) return '—';
    let v = Number(n);
    const unit = f.unit || 'number';
    const dec = f.decimals != null ? f.decimals : (unit === 'percent' ? 1 : 2);
    let scale = f.scale || 'none';
    if (scale === 'auto') { const a = Math.abs(v); scale = a >= 1e9 ? 'B' : a >= 1e6 ? 'M' : a >= 1e3 ? 'K' : 'none'; }
    const div = scale === 'B' ? 1e9 : scale === 'M' ? 1e6 : scale === 'K' ? 1e3 : 1;
    const body = (v / div).toLocaleString('en-US', { minimumFractionDigits: dec, maximumFractionDigits: dec });
    const sUnit = scale === 'none' ? '' : scale;
    const pct = unit === 'percent' ? '%' : '';
    return `${f.prefix || ''}${body}${sUnit}${pct}${f.suffix || ''}`;
};
const AGG_LABELS = { SUM: 'Sum', AVG: 'Average', MIN: 'Min', MAX: 'Max', MEDIAN: 'Median', STDDEV: 'Std Dev', P90: '90th pct', P95: '95th pct', COUNT: 'Count', COUNT_DISTINCT: 'Distinct count' };
// Chart formatting bridge: every chart on this screen shares one measure-format map,
// so a module-level handoff is safe and avoids prop-drilling into 12 tooltip sites.
let CHART_FMT = {};
const chartFmt = (value, key, compact) => {
    const f = (CHART_FMT && CHART_FMT[key]) || BASE_FMT[key] || { unit: 'number' };
    const eff = compact ? { ...f, scale: (f.scale && f.scale !== 'none') ? f.scale : 'auto' } : f;
    return formatMeasure(value, eff);
};

/* ═══ INJECT GLOBAL STYLES ═══ */
const STYLE_ID = 'qlik-explorer-v4';
if (typeof document !== 'undefined' && !document.getElementById(STYLE_ID)) {
    const s = document.createElement('style'); s.id = STYLE_ID;
    s.textContent = `
        .qe4 { font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif !important; }
        .qe4 * { font-family: inherit !important; box-sizing: border-box; }
        .qe4-scroll::-webkit-scrollbar { width: 5px; height: 5px; }
        .qe4-scroll::-webkit-scrollbar-track { background: transparent; }
        .qe4-scroll::-webkit-scrollbar-thumb { background: rgba(0,0,0,0.08); border-radius: 10px; }
        .qe4-dark-scroll::-webkit-scrollbar { width: 4px; }
        .qe4-dark-scroll::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.06); border-radius: 10px; }
        .qe4-pill:active { transform: scale(0.94) !important; }
        .qe4-row:hover td { background: rgba(67,97,238,0.025) !important; }
        @keyframes qe4Float { 0%,100% { transform: translateY(0); } 50% { transform: translateY(-8px); } }
        @keyframes qe4FadeIn { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: translateY(0); } }
        .qe4-fade { animation: qe4FadeIn 0.4s ease-out forwards; }
        .qe4-tile { transition: box-shadow 0.25s cubic-bezier(0.4,0,0.2,1), transform 0.25s cubic-bezier(0.4,0,0.2,1), border-color 0.25s; }
        .qe4-tile:hover { box-shadow: 0 10px 30px rgba(15,23,42,0.08); transform: translateY(-2px); }
        .qe4-kpi { transition: box-shadow 0.25s cubic-bezier(0.4,0,0.2,1), transform 0.25s cubic-bezier(0.4,0,0.2,1), border-color 0.25s; }
        .qe4-glass { background: rgba(255,255,255,0.72); backdrop-filter: blur(12px) saturate(140%); -webkit-backdrop-filter: blur(12px) saturate(140%); }
        @keyframes qe4Rise { from { opacity: 0; transform: translateY(10px) scale(0.99); } to { opacity: 1; transform: none; } }
        .qe4-rise { animation: qe4Rise 0.45s cubic-bezier(0.16,1,0.3,1) forwards; }
        .qe4-chip-soft { transition: transform 0.15s ease, box-shadow 0.15s ease, border-color 0.15s ease; }
        .qe4-chip-soft:hover { transform: translateY(-1px); }
        .react-grid-layout { position: relative; transition: height 200ms ease; }
        .react-grid-item { box-sizing: border-box; transition: all 200ms ease; transition-property: left, top, width, height; }
        .react-grid-item.cssTransforms { transition-property: transform, width, height; }
        .react-grid-item.resizing { z-index: 3; opacity: 0.92; will-change: width, height; }
        .react-grid-item.react-draggable-dragging { transition: none; z-index: 4; will-change: transform; }
        .react-grid-item.react-grid-placeholder { background: rgba(67,97,238,0.12); border: 2px dashed rgba(67,97,238,0.4); border-radius: 16px; transition-duration: 100ms; z-index: 2; }
        .react-grid-item > .react-resizable-handle { position: absolute; width: 20px; height: 20px; bottom: 0; right: 0; cursor: se-resize; }
        .react-grid-item > .react-resizable-handle::after { content: ""; position: absolute; right: 5px; bottom: 5px; width: 7px; height: 7px; border-right: 2px solid rgba(120,130,150,0.55); border-bottom: 2px solid rgba(120,130,150,0.55); border-bottom-right-radius: 2px; }
        .qe4-drag { cursor: move; }
        .qe4 { font-variant-numeric: tabular-nums; }
        .qe4-scroll::-webkit-scrollbar-thumb { background: rgba(15,23,42,0.13); }
        .qe4 *:focus-visible { outline: 2px solid rgba(67,97,238,0.45); outline-offset: 1px; border-radius: 5px; }
        .qe4-tile:hover { box-shadow: 0 14px 30px -10px rgba(15,23,42,0.18), 0 4px 10px -4px rgba(15,23,42,0.08); transform: translateY(-3px); }
        .qe4-caret { width: 3px; height: 26px; border-radius: 3px; background: #4361ee; box-shadow: 0 0 8px rgba(67,97,238,0.6); animation: qe4Caret 0.8s ease-in-out infinite; align-self: center; }
        @keyframes qe4Caret { 0%,100% { opacity: 1; } 50% { opacity: 0.35; } }
        @keyframes qe4Shake { 0%,100% { transform: translateX(0); } 20%,60% { transform: translateX(-4px); } 40%,80% { transform: translateX(4px); } }
        .qe4-shake { animation: qe4Shake 0.35s ease; }
        .qe4-droptarget { outline: 2px dashed rgba(67,97,238,0.55) !important; outline-offset: -2px; }
    `;
    document.head.appendChild(s);
}

/* ═══════════════════════════════════════════════════════
   SUB COMPONENTS
   ═══════════════════════════════════════════════════════ */
/* DnD context — module-level so drop targets can inspect the in-flight payload
   during dragover (dataTransfer data is unreadable until drop). Same-page only. */
let DRAG_CTX = null;
/* Double-click-to-add bridge (set by the main component, like CHART_FMT). */
let QE4_ADD = null;
/* Custom drag ghost: styled chip instead of the browser's washed-out snapshot. */
const dragGhost = (e, label, color) => {
    try {
        const g = document.createElement('div');
        g.textContent = label;
        g.style.cssText = `position:fixed;top:-1000px;left:-1000px;padding:5px 12px;border-radius:7px;font:700 12px Inter,system-ui,sans-serif;color:#fff;background:${color};box-shadow:0 8px 24px rgba(15,23,42,.35);pointer-events:none;z-index:9999;`;
        document.body.appendChild(g);
        e.dataTransfer.setDragImage(g, 12, 14);
        setTimeout(() => { try { document.body.removeChild(g); } catch {} }, 0);
    } catch {}
};

const SideChip = ({ field, source, active }) => {
    const c = gc(field.category); const I = c.icon;
    return (
        <Box className="qe4-pill qe4-chip-soft" draggable
            onDragStart={e => { DRAG_CTX = { zone: null, item: { ...field, source }, consumed: false }; e.dataTransfer.setData('text/plain', JSON.stringify({ ...field, source })); e.dataTransfer.effectAllowed = 'copy'; dragGhost(e, field.label, c.color); }}
            onDragEnd={() => { DRAG_CTX = null; }}
            onDoubleClick={() => QE4_ADD?.dim?.(field)}
            title={active ? `${field.label} — in use` : `${field.label} · double-click to add`}
            sx={{ display: 'inline-flex', alignItems: 'center', gap: '5px', px: '9px', py: '4.5px', borderRadius: '6px', fontSize: 11.5, fontWeight: 600, letterSpacing: '-0.01em', cursor: 'grab', userSelect: 'none', whiteSpace: 'nowrap', color: active ? '#fff' : 'rgba(255,255,255,0.75)', bgcolor: active ? `${c.color}26` : 'rgba(255,255,255,0.04)', border: `1px solid ${active ? `${c.color}66` : 'rgba(255,255,255,0.06)'}`, transition: 'all 0.2s ease', '&:hover': { bgcolor: active ? `${c.color}33` : 'rgba(255,255,255,0.08)', borderColor: `${c.color}55`, transform: 'translateY(-1px)', boxShadow: `0 4px 12px ${c.color}20` } }}>
            {active && <Box component="span" sx={{ width: 5, height: 5, borderRadius: '50%', bgcolor: c.color, boxShadow: `0 0 6px ${c.color}` }} />}
            <I size={11} style={{ opacity: 0.6 }} />{field.label}
        </Box>
    );
};

const SideMeasChip = ({ measure }) => (
    <Box className="qe4-pill" draggable
        onDragStart={e => { DRAG_CTX = { zone: null, item: { ...measure, source: 'measure' }, consumed: false }; e.dataTransfer.setData('text/plain', JSON.stringify({ ...measure, source: 'measure' })); dragGhost(e, measure.label, '#00b37e'); }}
        onDragEnd={() => { DRAG_CTX = null; }}
        onDoubleClick={() => QE4_ADD?.meas?.(measure)}
        sx={{ display: 'inline-flex', alignItems: 'center', gap: '5px', px: '9px', py: '4.5px', borderRadius: '6px', fontSize: 11.5, fontWeight: 600, cursor: 'grab', userSelect: 'none', whiteSpace: 'nowrap', color: 'rgba(0,179,126,0.85)', bgcolor: 'rgba(0,179,126,0.05)', border: '1px solid rgba(0,179,126,0.1)', transition: 'all 0.2s ease', '&:hover': { bgcolor: 'rgba(0,179,126,0.12)', transform: 'translateY(-1px)' } }}>
        <BarChart3 size={11} style={{ opacity: 0.6 }} />{measure.label}
    </Box>
);

const ZoneChip = ({ field, onRemove }) => {
    const c = gc(field.category); const I = c.icon;
    return <Chip size="small" label={field.label} icon={<I size={12} color={c.color} />} onDelete={onRemove} deleteIcon={<X size={12} />}
        sx={{ height: 28, fontSize: 12, fontWeight: 600, bgcolor: `${c.color}0c`, color: c.color, border: `1px solid ${c.color}22`, '& .MuiChip-deleteIcon': { color: c.color, opacity: 0.5, '&:hover': { opacity: 1, color: T.rose } }, '& .MuiChip-icon': { ml: 0.5 } }} />;
};

const ZoneMeasChip = ({ measure, onRemove }) => (
    <Chip size="small" label={measure.label} icon={<BarChart3 size={12} color={T.green} />} onDelete={onRemove} deleteIcon={<X size={12} />}
        sx={{ height: 28, fontSize: 12, fontWeight: 600, bgcolor: T.greenGhost, color: T.green, border: `1px solid ${T.greenBorder}`, '& .MuiChip-deleteIcon': { color: T.green, opacity: 0.5, '&:hover': { opacity: 1, color: T.rose } }, '& .MuiChip-icon': { ml: 0.5 } }} />
);

/* DnD-aware drop zone: reorder by dragging chips, swap by dropping a field onto
   a chip, insert-at-caret, wrong-type shake feedback, drag-out-to-remove. */
const DropZone = ({ label, items, onChange, validate, zoneId, accent, emptyText, renderItem }) => {
    const [over, setOver] = useState(null);   // 'ok' | 'no' | null
    const [caret, setCaret] = useState(-1);
    const [shake, setShake] = useState(false);
    const wrapRef = useRef(null);

    const payloadOf = e => { try { return JSON.parse(e.dataTransfer.getData('text/plain')); } catch { return null; } };
    const caretFrom = e => {
        const wrap = wrapRef.current; if (!wrap) return items.length;
        const chips = [...wrap.querySelectorAll('[data-zi]')];
        for (let i = 0; i < chips.length; i++) { const r = chips[i].getBoundingClientRect(); if (e.clientY < r.top - 4) continue; if (e.clientY <= r.bottom + 4 && e.clientX < r.left + r.width / 2) return i; }
        return items.length;
    };
    const acceptable = () => !DRAG_CTX || DRAG_CTX.zone === zoneId || (DRAG_CTX.zone == null && !!validate(DRAG_CTX.item));
    const reject = () => { setShake(true); setTimeout(() => setShake(false), 380); };

    const handleDrop = (e, chipIdx = null) => {
        e.preventDefault(); e.stopPropagation();
        setOver(null); setCaret(-1);
        if (DRAG_CTX) DRAG_CTX.consumed = true;
        if (DRAG_CTX?.zone === zoneId) {                       // reorder within this zone
            const from = DRAG_CTX.index;
            let to = chipIdx != null ? chipIdx : caretFrom(e);
            if (to > from) to -= 1;
            if (from === to) return;
            const n = [...items]; const [m] = n.splice(from, 1); n.splice(to, 0, m);
            onChange(n); return;
        }
        if (DRAG_CTX?.zone) { reject(); return; }              // chip from the other zone
        const p = DRAG_CTX?.item || payloadOf(e);
        const v = p ? validate(p) : null;
        if (!v) { reject(); return; }
        if (chipIdx != null) {                                 // swap-on-drop over a chip
            if (items.some((x, i) => x.key === v.key && i !== chipIdx)) return;
            const n = [...items]; n[chipIdx] = v; onChange(n); return;
        }
        if (items.some(x => x.key === v.key)) return;
        const n = [...items]; n.splice(caretFrom(e), 0, v); onChange(n);
    };

    return (
        <Paper elevation={0} className={shake ? 'qe4-shake' : ''}
            onDragOver={e => { e.preventDefault(); const ok = acceptable(); e.dataTransfer.dropEffect = ok ? 'copy' : 'none'; setOver(ok ? 'ok' : 'no'); setCaret(ok && DRAG_CTX ? caretFrom(e) : -1); }}
            onDragLeave={() => { setOver(null); setCaret(-1); }}
            onDrop={e => handleDrop(e)}
            sx={{ px: 1.75, py: 1.15, borderRadius: `${T.radius}px`, minHeight: 56, bgcolor: over === 'ok' ? `${accent}08` : over === 'no' ? `${T.rose}06` : T.surfaceMuted, border: `1px solid ${over === 'ok' ? accent : over === 'no' ? T.rose : T.cardBorder}`, boxShadow: over === 'ok' ? `0 0 0 3px ${accent}1f` : 'inset 0 1px 2px rgba(15,23,42,0.03)', transition: 'background 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease' }}>
            <Stack direction="row" spacing={0.75} alignItems="center" sx={{ mb: items.length ? 0.85 : 0 }}>
                <Box sx={{ width: 4, height: 13, borderRadius: 4, bgcolor: over === 'no' ? T.rose : accent, opacity: over ? 1 : 0.7 }} />
                <Typography variant="caption" sx={{ fontWeight: 800, color: T.td2, textTransform: 'uppercase', letterSpacing: 1.4, fontSize: 9.5 }}>{label}</Typography>
                {items.length > 0 && <Chip size="small" label={items.length} sx={{ height: 18, fontSize: 10, fontWeight: 800, bgcolor: `${accent}14`, color: accent, '& .MuiChip-label': { px: 0.6 } }} />}
                {over === 'no' && <Typography sx={{ fontSize: 9.5, fontWeight: 800, color: T.rose, textTransform: 'none', letterSpacing: 0 }}>not allowed here</Typography>}
            </Stack>
            {items.length > 0 ? (
                <Stack ref={wrapRef} direction="row" flexWrap="wrap" useFlexGap sx={{ gap: '6px', alignItems: 'center' }}>
                    {items.map((f, i) => (
                        <React.Fragment key={f.key + i}>
                            {caret === i && <Box className="qe4-caret" />}
                            <Box data-zi={i} draggable
                                onDragStart={e => { DRAG_CTX = { zone: zoneId, index: i, item: f, consumed: false }; e.dataTransfer.setData('text/plain', JSON.stringify({ __zone: zoneId, index: i })); e.dataTransfer.effectAllowed = 'move'; dragGhost(e, f.label, accent); }}
                                onDragEnd={() => { if (DRAG_CTX && DRAG_CTX.zone === zoneId && !DRAG_CTX.consumed) onChange(items.filter((_, x) => x !== i)); DRAG_CTX = null; }}
                                onDragOver={e => { e.preventDefault(); e.stopPropagation(); setOver(acceptable() ? 'ok' : 'no'); setCaret(-1); }}
                                onDrop={e => handleDrop(e, i)}
                                sx={{ display: 'inline-flex', cursor: 'grab', '&:active': { cursor: 'grabbing' } }}>
                                {renderItem ? renderItem(f, i, () => onChange(items.filter((_, x) => x !== i)))
                                            : <ZoneChip field={f} onRemove={() => onChange(items.filter((_, x) => x !== i))} />}
                            </Box>
                        </React.Fragment>
                    ))}
                    {caret === items.length && <Box className="qe4-caret" />}
                </Stack>
            ) : (
                <Stack direction="row" spacing={0.75} alignItems="center" sx={{ py: 0.4, opacity: 0.6 }}>
                    <Plus size={13} color={T.td3} />
                    <Typography sx={{ fontSize: 11.5, color: T.td3 }}>{emptyText}</Typography>
                </Stack>
            )}
        </Paper>
    );
};

/* ═══ MINI DATE-RANGE PICKER — calendar popover, no native input UI ═══
   Keeps the YYYY-MM-DD string contract (sd/ed) the rest of the screen relies on,
   and is timezone-safe (builds the string from local Y/M/D, never toISOString). */
const MDP_MONTHS = ['January','February','March','April','May','June','July','August','September','October','November','December'];
const mdpFmt = (s) => {
    if (!s) return '';
    const [y, m, d] = s.split('-').map(Number);
    if (!y || !m || !d) return s;
    return new Date(y, m - 1, d).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
};
const mdpStr = (date) => {
    const y = date.getFullYear(), m = String(date.getMonth() + 1).padStart(2, '0'), d = String(date.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
};
const MiniDatePicker = ({ startDate, endDate, onChange }) => {
    const [open, setOpen] = useState(false);
    const [selectingStart, setSelectingStart] = useState(true);
    const [month, setMonth] = useState(() => {
        const anchor = startDate || endDate;
        if (anchor) { const [y, m] = anchor.split('-').map(Number); return new Date(y, (m || 1) - 1, 1); }
        return new Date();
    });
    const ref = useRef(null);
    useEffect(() => {
        const h = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
        document.addEventListener('mousedown', h);
        return () => document.removeEventListener('mousedown', h);
    }, []);

    const openFor = (start) => { setSelectingStart(start); const anchor = (start ? startDate : endDate) || startDate || endDate; if (anchor) { const [y, m] = anchor.split('-').map(Number); setMonth(new Date(y, (m || 1) - 1, 1)); } setOpen(true); };
    const days = (() => {
        const y = month.getFullYear(), mo = month.getMonth();
        const count = new Date(y, mo + 1, 0).getDate();
        const lead = new Date(y, mo, 1).getDay();
        const out = [];
        for (let i = 0; i < lead; i++) out.push(null);
        for (let i = 1; i <= count; i++) out.push(new Date(y, mo, i));
        return out;
    })();
    const isSel = (date) => { if (!date) return false; const s = mdpStr(date); return s === startDate || s === endDate; };
    const inRange = (date) => { if (!date || !startDate || !endDate) return false; const s = mdpStr(date); return s > startDate && s < endDate; };
    const pick = (date) => {
        if (!date) return;
        const s = mdpStr(date);
        if (selectingStart) {
            if (endDate && s > endDate) { onChange(s, ''); setSelectingStart(false); }
            else { onChange(s, endDate); setSelectingStart(false); }
        } else {
            if (startDate && s < startDate) { onChange(s, ''); setSelectingStart(false); }
            else { onChange(startDate, s); setOpen(false); }
        }
    };
    const clearAll = (e) => { e.stopPropagation(); onChange('', ''); setSelectingStart(true); };
    const trigSx = (active, filled) => ({ display: 'flex', alignItems: 'center', gap: 0.75, height: 30, px: 1.25, borderRadius: '7px', cursor: 'pointer', bgcolor: '#f8f9fb', border: `1px solid ${active ? T.primary : T.cardBorder}`, boxShadow: active ? `0 0 0 3px ${T.primary}1f` : 'none', fontSize: 11.5, fontWeight: 600, color: filled ? T.td : T.td3, whiteSpace: 'nowrap', transition: 'border-color 0.15s, box-shadow 0.15s', '&:hover': { borderColor: T.primary } });

    return (
        <Box ref={ref} sx={{ position: 'relative' }}>
            <Stack direction="row" spacing={0.5} alignItems="center">
                <Box onClick={() => openFor(true)} sx={trigSx(open && selectingStart, !!startDate)}>
                    <Calendar size={12} color={T.td3} />
                    <span>{startDate ? mdpFmt(startDate) : 'From'}</span>
                </Box>
                <ArrowRight size={12} color={T.td3} />
                <Box onClick={() => openFor(false)} sx={trigSx(open && !selectingStart, !!endDate)}>
                    <span>{endDate ? mdpFmt(endDate) : 'To'}</span>
                    {endDate
                        ? <Box component="span" onClick={clearAll} sx={{ display: 'inline-flex', color: T.td3, borderRadius: '999px', '&:hover': { color: T.rose } }}><X size={12} /></Box>
                        : <Calendar size={12} color={T.td3} />}
                </Box>
            </Stack>
            {open && (
                <Paper elevation={8} sx={{ position: 'absolute', top: '110%', left: 0, mt: 0.5, zIndex: 9999, p: 1.5, width: 268, borderRadius: '12px', border: `1px solid ${T.cardBorder}`, boxShadow: T.shadowLg }}>
                    <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1 }}>
                        <IconButton size="small" onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() - 1, 1))} sx={{ color: T.td2 }}><ChevronLeft size={16} /></IconButton>
                        <Typography sx={{ fontSize: 12.5, fontWeight: 700, color: T.td }}>{MDP_MONTHS[month.getMonth()]} {month.getFullYear()}</Typography>
                        <IconButton size="small" onClick={() => setMonth(new Date(month.getFullYear(), month.getMonth() + 1, 1))} sx={{ color: T.td2 }}><ChevronRight size={16} /></IconButton>
                    </Stack>
                    <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: '3px', mb: 0.5 }}>
                        {['Su','Mo','Tu','We','Th','Fr','Sa'].map(d => <Box key={d} sx={{ textAlign: 'center', fontSize: 9.5, fontWeight: 800, color: T.td3, py: 0.25 }}>{d}</Box>)}
                    </Box>
                    <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: '3px' }}>
                        {days.map((date, i) => {
                            if (!date) return <Box key={i} />;
                            const sel = isSel(date), rng = inRange(date);
                            return (
                                <Box key={i} onClick={() => pick(date)} sx={{ height: 30, display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 11.5, fontWeight: sel ? 800 : 500, cursor: 'pointer', color: sel ? '#fff' : T.td, bgcolor: sel ? T.primary : rng ? `${T.primary}14` : 'transparent', borderRadius: sel ? '7px' : rng ? 0 : '7px', transition: 'background 0.12s', '&:hover': { bgcolor: sel ? T.primaryDark : `${T.primary}10` } }}>
                                    {date.getDate()}
                                </Box>
                            );
                        })}
                    </Box>
                    <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mt: 1, pt: 1, borderTop: `1px solid ${T.cardBorder}` }}>
                        <Typography sx={{ fontSize: 10.5, fontWeight: 600, color: T.td3 }}>{selectingStart ? 'Selecting: From' : 'Selecting: To'}</Typography>
                        <Typography onClick={() => setOpen(false)} sx={{ fontSize: 11, fontWeight: 700, color: T.primary, cursor: 'pointer', '&:hover': { textDecoration: 'underline' } }}>Done</Typography>
                    </Stack>
                </Paper>
            )}
        </Box>
    );
};

const KpiCard = ({ label, value, color, icon: Icon, delay = 0, sub = null }) => (
    <Fade in timeout={600} style={{ transitionDelay: `${delay}ms` }}>
        {/* Register aesthetic: flat surface, hairline border, single directional
           accent rule, restrained box-shadow-only hover (no glow blob / gradient fill). */}
        <Card elevation={0} className="qe4-kpi" sx={{ minWidth: 168, borderRadius: `${T.radius + 4}px`, border: `1px solid ${T.cardBorder}`, overflow: 'hidden', position: 'relative', bgcolor: T.card, '&:hover': { boxShadow: T.shadowMd, borderColor: `${color}33` } }}>
            <Box sx={{ position: 'absolute', top: 0, left: 0, bottom: 0, width: 3, bgcolor: color }} />
            <CardContent sx={{ p: '15px 18px !important', display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 1, position: 'relative' }}>
                <Box sx={{ minWidth: 0 }}>
                    <Typography sx={{ fontSize: 10, fontWeight: 800, color: T.td3, textTransform: 'uppercase', letterSpacing: 0.8, mb: 0.5, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 150 }}>{label}</Typography>
                    <Typography sx={{ fontSize: 25, fontWeight: 800, color: T.td, letterSpacing: -0.5, lineHeight: 1.05, fontVariantNumeric: 'tabular-nums' }}>{value}</Typography>
                    {sub != null && <Typography sx={{ fontSize: 11, fontWeight: 700, color: T.td3, mt: 0.4 }}>{sub}</Typography>}
                </Box>
                {Icon && <Box sx={{ width: 38, height: 38, flexShrink: 0, borderRadius: '11px', display: 'flex', alignItems: 'center', justifyContent: 'center', bgcolor: `${color}12`, border: `1px solid ${color}22` }}><Icon size={18} color={color} /></Box>}
            </CardContent>
        </Card>
    </Fade>
);

const ChartTip = ({ active, payload, label }) => {
    if (!active || !payload?.length) return null;
    return (
        <Paper elevation={8} sx={{ px: 2, py: 1.5, borderRadius: `${T.radius}px`, bgcolor: 'rgba(10,14,26,0.95)', color: 'white', backdropFilter: 'blur(16px)', border: '1px solid rgba(255,255,255,0.08)' }}>
            <Typography sx={{ fontSize: 11, fontWeight: 700, opacity: 0.5, mb: 0.5, pb: 0.5, borderBottom: '1px solid rgba(255,255,255,0.08)' }}>{label}</Typography>
            {payload.map((p, i) => (
                <Stack key={i} direction="row" alignItems="center" spacing={1} sx={{ py: 0.3 }}>
                    <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: p.color, boxShadow: `0 0 8px ${p.color}80` }} />
                    <Typography sx={{ fontSize: 11, opacity: 0.55, flex: 1 }}>{p.name}</Typography>
                    <Typography sx={{ fontSize: 12, fontWeight: 800, fontVariantNumeric: 'tabular-nums' }}>{chartFmt(p.value, p.dataKey, false)}</Typography>
                </Stack>
            ))}
        </Paper>
    );
};

const ASSOC_ORDER = { selected: 0, possible: 1, excluded: 2 };
const FilterBox = ({ title, items, loading, selected, onToggle, onClear, color }) => {
    const [q, setQ] = useState('');
    const SEL = '#009845';
    const has = selected.length > 0;
    const filtered = items ? items.filter(it => !q || String(it.value ?? '').toLowerCase().includes(q.toLowerCase())) : [];
    const sortedList = [...filtered].sort((a, b) => (ASSOC_ORDER[a.state] - ASSOC_ORDER[b.state]) || String(a.value).localeCompare(String(b.value)));
    const shown = sortedList.slice(0, 200);
    const possibleCount = items ? items.filter(it => it.state !== 'excluded').length : 0;
    return (
        <Paper elevation={0} sx={{ borderRadius: `${T.radius}px`, overflow: 'hidden', width: 220, border: `1px solid ${has ? `${SEL}28` : T.cardBorder}`, transition: 'border-color 0.2s' }}>
            <Box sx={{ px: 1.5, py: 0.75, bgcolor: has ? `${SEL}06` : '#fafbfc', borderBottom: `1px solid ${T.cardBorder}`, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <Stack direction="row" spacing={0.75} alignItems="center">
                    <Box sx={{ width: 3, height: 12, borderRadius: 3, bgcolor: has ? SEL : color, opacity: 0.6 }} />
                    <Typography sx={{ fontSize: 12, fontWeight: 700, color: T.td }}>{title}</Typography>
                    {has && <Chip size="small" label={selected.length} sx={{ height: 16, fontSize: 9, fontWeight: 800, bgcolor: SEL, color: 'white', '& .MuiChip-label': { px: 0.5 } }} />}
                </Stack>
                {has && <Typography onClick={onClear} sx={{ fontSize: 10, fontWeight: 700, color: T.td3, cursor: 'pointer', '&:hover': { color: T.rose } }}>Clear</Typography>}
            </Box>
            <Box sx={{ px: 1, py: 0.5 }}>
                <TextField fullWidth size="small" placeholder="Search…" value={q} onChange={e => setQ(e.target.value)}
                    InputProps={{ startAdornment: <InputAdornment position="start"><Search size={12} color={T.td3} /></InputAdornment>, sx: { fontSize: 11, height: 28, borderRadius: '6px', '& fieldset': { borderColor: 'rgba(0,0,0,0.06)' } } }} />
            </Box>
            <Box className="qe4-scroll" sx={{ maxHeight: 180, overflowY: 'auto' }}>
                {loading ? <Box sx={{ display: 'flex', justifyContent: 'center', py: 2 }}><CircularProgress size={16} sx={{ color: T.primary }} /></Box>
                    : !shown.length ? <Typography sx={{ fontSize: 11, color: T.td3, textAlign: 'center', py: 1.5 }}>No values</Typography>
                    : shown.map(it => {
                        const v = it.value;
                        const sel = it.state === 'selected';
                        const excluded = it.state === 'excluded';
                        return (
                            <Box key={String(v)} onClick={() => onToggle(v)} title={excluded ? 'Excluded by current selections — click to select anyway' : undefined}
                                sx={{ display: 'flex', alignItems: 'center', gap: 0.75, px: 1.25, py: 0.45, cursor: 'pointer', opacity: excluded ? 0.4 : 1, bgcolor: sel ? `${SEL}08` : 'transparent', borderLeft: `3px solid ${sel ? SEL : 'transparent'}`, '&:hover': { bgcolor: sel ? `${SEL}12` : 'rgba(0,0,0,0.02)' }, transition: 'all 0.1s' }}>
                                <Checkbox size="small" checked={sel} readOnly sx={{ p: 0, '& .MuiSvgIcon-root': { fontSize: 16 }, color: sel ? SEL : T.td3, '&.Mui-checked': { color: SEL } }} />
                                <Typography sx={{ fontSize: 12, color: sel ? T.td : excluded ? T.td3 : T.td2, fontWeight: sel ? 600 : 400, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{(v === '' || v == null) ? '(empty)' : v}</Typography>
                            </Box>
                        );
                    })}
            </Box>
            {items && (
                <Box sx={{ px: 1.25, py: 0.4, borderTop: `1px solid ${T.cardBorder}`, display: 'flex', justifyContent: 'space-between' }}>
                    <Typography sx={{ fontSize: 9.5, color: T.td3 }}>{possibleCount} available</Typography>
                    {has && <Typography sx={{ fontSize: 9.5, fontWeight: 700, color: SEL }}>{selected.length} selected</Typography>}
                </Box>
            )}
        </Paper>
    );
};

/* ═══ CHART RENDERER — supports 12 types ═══ */
const ChartRenderer = ({ type, data, measureKeys, palette, onChartClick, height = 360, measFmt = {}, refLines = false, trend = false, rawRows = null, dimKeys = [] }) => {
    if (!data?.length || !measureKeys?.length) return null;
    CHART_FMT = measFmt || {};
    const mK = measureKeys.slice(0, 3);

    // Reference lines (avg / max / min of the first measure) — toggled from the toolbar.
    let refEls = null;
    if (refLines && data.length > 1) {
        const vals = data.map(d => Number(d[mK[0]]) || 0);
        const avg = vals.reduce((s, v) => s + v, 0) / vals.length;
        refEls = [
            <ReferenceLine key="ref-avg" y={avg} stroke={T.td3} strokeDasharray="5 4" label={{ value: `avg ${chartFmt(avg, mK[0], true)}`, position: 'insideTopRight', fontSize: 10, fill: T.td3 }} />,
            <ReferenceLine key="ref-max" y={Math.max(...vals)} stroke={T.green} strokeDasharray="2 4" strokeOpacity={0.5} />,
            <ReferenceLine key="ref-min" y={Math.min(...vals)} stroke={T.rose} strokeDasharray="2 4" strokeOpacity={0.5} />,
        ];
    }
    // Linear trend over the first measure — enrich the series with a __trend value.
    if (trend && data.length > 2) {
        const n = data.length, ys = data.map(d => Number(d[mK[0]]) || 0);
        const sx = (n - 1) * n / 2;
        const sxx = ys.reduce((s, _, i) => s + i * i, 0);
        const sy = ys.reduce((s, v) => s + v, 0);
        const sxy = ys.reduce((s, v, i) => s + i * v, 0);
        const denom = n * sxx - sx * sx || 1;
        const slope = (n * sxy - sx * sy) / denom, icpt = (sy - slope * sx) / n;
        data = data.map((d, i) => ({ ...d, __trend: icpt + slope * i }));
    }

    const common = { margin: { top: 10, right: 20, bottom: 5, left: 0 } };
    const axisX = { dataKey: 'name', tick: { fontSize: 11, fill: T.td3, fontWeight: 500 }, axisLine: false, tickLine: false };
    const axisY = { tick: { fontSize: 11, fill: T.td3 }, axisLine: false, tickLine: false, tickFormatter: (v) => chartFmt(v, mK[0], true), width: 55 };
    const grid = <CartesianGrid strokeDasharray="3 3" stroke="rgba(0,0,0,0.04)" vertical={false} />;

    switch (type) {
        case 'bar':
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <BarChart data={data} {...common} onClick={d => d?.activePayload?.[0] && onChartClick?.(d.activePayload[0])}>
                        {refEls}
                        <defs>{mK.map((k, i) => (<linearGradient key={k} id={`bg${i}`} x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor={palette[i]} stopOpacity={0.9}/><stop offset="100%" stopColor={palette[i]} stopOpacity={0.55}/></linearGradient>))}</defs>
                        {grid}<XAxis {...axisX} angle={data.length > 10 ? -20 : 0} textAnchor={data.length > 10 ? 'end' : 'middle'} height={data.length > 10 ? 55 : 35} /><YAxis {...axisY} />
                        <RTooltip content={<ChartTip />} cursor={{ fill: `${T.primary}06` }} /><Legend wrapperStyle={{ fontSize: 12, paddingTop: 12 }} />
                        {mK.map((k, i) => <Bar key={k} dataKey={k} fill={`url(#bg${i})`} radius={[6,6,0,0]} name={k.replace(/_/g,' ')} style={{ cursor: 'pointer' }} />)}
                    </BarChart>
                </ResponsiveContainer>
            );

        case 'stackedBar':
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <BarChart data={data} {...common}>
                        {grid}<XAxis {...axisX} /><YAxis {...axisY} />
                        <RTooltip content={<ChartTip />} /><Legend wrapperStyle={{ fontSize: 12, paddingTop: 12 }} />
                        {mK.map((k, i) => <Bar key={k} dataKey={k} stackId="s" fill={palette[i]} name={k.replace(/_/g,' ')} />)}
                    </BarChart>
                </ResponsiveContainer>
            );

        case 'hbar':
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <BarChart data={data} layout="vertical" {...common} margin={{ left: 10, right: 30 }}>
                        {React.cloneElement(grid, { horizontal: false, vertical: true })}
                        <XAxis type="number" {...axisY} /><YAxis dataKey="name" type="category" width={130} tick={{ fontSize: 12, fill: T.td2 }} axisLine={false} tickLine={false} />
                        <RTooltip content={<ChartTip />} /><Legend wrapperStyle={{ fontSize: 12, paddingTop: 12 }} />
                        {mK.map((k, i) => <Bar key={k} dataKey={k} fill={palette[i]} radius={[0,6,6,0]} barSize={22} name={k.replace(/_/g,' ')} />)}
                    </BarChart>
                </ResponsiveContainer>
            );

        case 'line':
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <LineChart data={data} {...common} onClick={d => d?.activePayload?.[0] && onChartClick?.(d.activePayload[0])}>
                        {refEls}
                        {grid}<XAxis {...axisX} /><YAxis {...axisY} />
                        <RTooltip content={<ChartTip />} /><Legend wrapperStyle={{ fontSize: 12, paddingTop: 12 }} />
                        {mK.map((k, i) => <Line key={k} dataKey={k} stroke={palette[i]} strokeWidth={2.5} dot={{ r: 4, fill: 'white', stroke: palette[i], strokeWidth: 2.5 }} activeDot={{ r: 6, stroke: 'white', strokeWidth: 2.5 }} name={k.replace(/_/g,' ')} />)}
                        {trend && <Line dataKey="__trend" stroke={T.td2} strokeDasharray="7 5" strokeWidth={1.75} dot={false} activeDot={false} name="Trend" isAnimationActive={false} />}
                    </LineChart>
                </ResponsiveContainer>
            );

        case 'area':
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <AreaChart data={data} {...common}>
                        {refEls}
                        <defs>{mK.map((k, i) => (<linearGradient key={k} id={`ag${i}`} x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor={palette[i]} stopOpacity={0.25}/><stop offset="100%" stopColor={palette[i]} stopOpacity={0.02}/></linearGradient>))}</defs>
                        {grid}<XAxis {...axisX} /><YAxis {...axisY} />
                        <RTooltip content={<ChartTip />} /><Legend wrapperStyle={{ fontSize: 12, paddingTop: 12 }} />
                        {mK.map((k, i) => <Area key={k} dataKey={k} fill={`url(#ag${i})`} stroke={palette[i]} strokeWidth={2} name={k.replace(/_/g,' ')} />)}
                    </AreaChart>
                </ResponsiveContainer>
            );

        case 'pie':
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <RPieChart>
                        <Pie data={data} dataKey={mK[0]} nameKey="name" cx="50%" cy="50%" outerRadius={140} innerRadius={60}
                            label={({ name, percent }) => percent > 0.04 ? `${name} ${(percent * 100).toFixed(0)}%` : ''} labelLine={false} stroke="white" strokeWidth={2.5}
                            onClick={d => d && onChartClick?.(d)} style={{ cursor: 'pointer' }}>
                            {data.map((_, i) => <Cell key={i} fill={palette[i % palette.length]} />)}
                        </Pie>
                        <RTooltip content={<ChartTip />} /><Legend wrapperStyle={{ fontSize: 12 }} />
                    </RPieChart>
                </ResponsiveContainer>
            );

        case 'scatter':
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <ScatterChart {...common}>
                        {grid}
                        <XAxis dataKey={mK[0]} name={mK[0]?.replace(/_/g,' ')} tick={{ fontSize: 11, fill: T.td3 }} axisLine={false} tickLine={false} tickFormatter={fmtK} />
                        <YAxis dataKey={mK[1] || mK[0]} name={(mK[1] || mK[0])?.replace(/_/g,' ')} tick={{ fontSize: 11, fill: T.td3 }} axisLine={false} tickLine={false} tickFormatter={fmtK} width={55} />
                        {mK[2] && <ZAxis dataKey={mK[2]} range={[40, 400]} name={mK[2]?.replace(/_/g,' ')} />}
                        <RTooltip content={<ChartTip />} cursor={{ strokeDasharray: '3 3' }} />
                        <Scatter data={data} fill={palette[0]} fillOpacity={0.7} name="Data Points">
                            {data.map((_, i) => <Cell key={i} fill={palette[i % palette.length]} />)}
                        </Scatter>
                    </ScatterChart>
                </ResponsiveContainer>
            );

        case 'radar':
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <RadarChart data={data} cx="50%" cy="50%" outerRadius="80%">
                        <PolarGrid stroke="rgba(0,0,0,0.06)" />
                        <PolarAngleAxis dataKey="name" tick={{ fontSize: 11, fill: T.td2 }} />
                        <PolarRadiusAxis tick={{ fontSize: 10, fill: T.td3 }} axisLine={false} />
                        {mK.map((k, i) => <RadarShape key={k} dataKey={k} stroke={palette[i]} fill={palette[i]} fillOpacity={0.15} strokeWidth={2} name={k.replace(/_/g,' ')} />)}
                        <RTooltip content={<ChartTip />} /><Legend wrapperStyle={{ fontSize: 12 }} />
                    </RadarChart>
                </ResponsiveContainer>
            );

        case 'combo':
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <ComposedChart data={data} {...common}>
                        {grid}<XAxis {...axisX} /><YAxis {...axisY} yAxisId="left" /><YAxis {...axisY} yAxisId="right" orientation="right" />
                        <RTooltip content={<ChartTip />} /><Legend wrapperStyle={{ fontSize: 12, paddingTop: 12 }} />
                        {mK[0] && <Bar dataKey={mK[0]} fill={palette[0]} fillOpacity={0.7} radius={[4,4,0,0]} yAxisId="left" name={mK[0].replace(/_/g,' ')} />}
                        {mK[1] && <Line dataKey={mK[1]} stroke={palette[1]} strokeWidth={2.5} dot={{ r: 4, fill: 'white', stroke: palette[1], strokeWidth: 2 }} yAxisId="right" name={mK[1].replace(/_/g,' ')} />}
                        {mK[2] && <Area dataKey={mK[2]} fill={`${palette[2]}20`} stroke={palette[2]} strokeWidth={1.5} yAxisId="left" name={mK[2].replace(/_/g,' ')} />}
                    </ComposedChart>
                </ResponsiveContainer>
            );

        case 'treemap': {
            const tmData = data.map((d, i) => ({ name: d.name, size: d[mK[0]] || 0, fill: palette[i % palette.length] }));
            const TreeContent = ({ x, y, width, height: h, name, size, fill }) => {
                if (width < 40 || h < 25) return null;
                return (
                    <g><rect x={x} y={y} width={width} height={h} rx={4} fill={fill} fillOpacity={0.85} stroke="white" strokeWidth={2} />
                        {width > 60 && <text x={x + width / 2} y={y + h / 2 - 6} textAnchor="middle" fill="white" fontSize={11} fontWeight={700}>{name?.substring(0, Math.floor(width / 8))}</text>}
                        {width > 60 && h > 40 && <text x={x + width / 2} y={y + h / 2 + 10} textAnchor="middle" fill="rgba(255,255,255,0.7)" fontSize={10}>{fmtK(size)}</text>}
                    </g>
                );
            };
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <Treemap data={tmData} dataKey="size" nameKey="name" stroke="white" content={<TreeContent />}>
                        <RTooltip content={<ChartTip />} />
                    </Treemap>
                </ResponsiveContainer>
            );
        }

        case 'gauge': {
            const total = data.reduce((s, d) => s + (d[mK[0]] || 0), 0);
            const gaugeData = data.slice(0, 6).map((d, i) => ({ name: d.name, value: d[mK[0]] || 0, fill: palette[i] }));
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <RadialBarChart innerRadius="30%" outerRadius="90%" data={gaugeData} startAngle={180} endAngle={0} cx="50%" cy="70%">
                        <RadialBar background clockWise dataKey="value" cornerRadius={6}>
                            {gaugeData.map((_, i) => <Cell key={i} fill={palette[i]} />)}
                        </RadialBar>
                        <RTooltip content={<ChartTip />} /><Legend wrapperStyle={{ fontSize: 11 }} />
                    </RadialBarChart>
                </ResponsiveContainer>
            );
        }

        case 'waterfall': {
            const wfData = data.map((d, i) => {
                const val = d[mK[0]] || 0;
                const prev = i === 0 ? 0 : data.slice(0, i).reduce((s, r) => s + (r[mK[0]] || 0), 0);
                return { name: d.name, value: val, base: val >= 0 ? prev : prev + val, fill: val >= 0 ? palette[0] : palette[3] || T.rose };
            });
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <BarChart data={wfData} {...common}>
                        {grid}<XAxis {...axisX} /><YAxis {...axisY} />
                        <RTooltip content={<ChartTip />} />
                        <Bar dataKey="base" stackId="wf" fill="transparent" />
                        <Bar dataKey="value" stackId="wf" radius={[4,4,0,0]}>
                            {wfData.map((d, i) => <Cell key={i} fill={d.fill} />)}
                        </Bar>
                    </BarChart>
                </ResponsiveContainer>
            );
        }

        case 'kpi': {
            const totals = mK.map((k, i) => ({ key: k, label: k.replace(/_/g, ' '), color: palette[i], total: data.reduce((s, d) => s + (Number(d[k]) || 0), 0) }));
            return (
                <Box sx={{ height, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 3, flexWrap: 'wrap' }}>
                    {totals.map(t => (
                        <Box key={t.key} sx={{ textAlign: 'center', minWidth: 150 }}>
                            <Typography sx={{ fontSize: 11, fontWeight: 800, color: T.td3, textTransform: 'uppercase', letterSpacing: 1.2, mb: 0.75 }}>{t.label}</Typography>
                            <Typography sx={{ fontSize: height > 220 ? 44 : 30, fontWeight: 800, color: t.color, letterSpacing: '-0.03em', lineHeight: 1, fontVariantNumeric: 'tabular-nums' }}>{chartFmt(t.total, t.key, true)}</Typography>
                            <Typography sx={{ fontSize: 10.5, color: T.td3, mt: 0.75 }}>{data.length} groups</Typography>
                        </Box>
                    ))}
                </Box>
            );
        }

        case 'funnel': {
            const fData = [...data].sort((a, b) => (b[mK[0]] || 0) - (a[mK[0]] || 0)).slice(0, 8)
                .map((d, i) => ({ name: d.name, value: Number(d[mK[0]]) || 0, fill: palette[i % palette.length] }));
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <FunnelChart>
                        <RTooltip content={<ChartTip />} />
                        <Funnel dataKey="value" data={fData} isAnimationActive onClick={d => d && onChartClick?.(d)} style={{ cursor: 'pointer' }}>
                            <LabelList position="right" fill={T.td} stroke="none" dataKey="name" fontSize={11} fontWeight={700} />
                        </Funnel>
                    </FunnelChart>
                </ResponsiveContainer>
            );
        }

        case 'bullet': {
            const bData = data.slice(0, 12);
            const bVals = bData.map(d => Number(d[mK[0]]) || 0);
            const bAvg = bVals.reduce((s, v) => s + v, 0) / (bVals.length || 1);
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <BarChart data={bData} layout="vertical" {...common} margin={{ left: 10, right: 30 }}>
                        {React.cloneElement(grid, { horizontal: false, vertical: true })}
                        <XAxis type="number" {...axisY} />
                        <YAxis dataKey="name" type="category" width={130} tick={{ fontSize: 12, fill: T.td2 }} axisLine={false} tickLine={false} />
                        <RTooltip content={<ChartTip />} />
                        <ReferenceLine x={bAvg} stroke={T.amber} strokeWidth={2} strokeDasharray="6 4" label={{ value: `target ${chartFmt(bAvg, mK[0], true)}`, position: 'insideTopRight', fontSize: 10, fill: T.amber }} />
                        <Bar dataKey={mK[0]} barSize={14} radius={[0, 6, 6, 0]} name={mK[0].replace(/_/g, ' ')} style={{ cursor: 'pointer' }} onClick={d => d && onChartClick?.(d)}>
                            {bData.map((d, i) => <Cell key={i} fill={(Number(d[mK[0]]) || 0) >= bAvg ? T.green : palette[0]} />)}
                        </Bar>
                    </BarChart>
                </ResponsiveContainer>
            );
        }

        case 'heatmap': {
            if (!rawRows?.length || dimKeys.length < 2) {
                return <Box sx={{ height, display: 'flex', alignItems: 'center', justifyContent: 'center', color: T.td3, fontSize: 12.5, fontWeight: 600 }}>Heatmap needs two dimensions — add a second field to the Dimensions zone.</Box>;
            }
            const [dk1, dk2] = dimKeys;
            const rSet = [], cSet = [], cellMap = {};
            rawRows.forEach(r => {
                const rv = String(r[dk1] ?? '—'), cv = String(r[dk2] ?? '—');
                if (!rSet.includes(rv) && rSet.length < 20) rSet.push(rv);
                if (!cSet.includes(cv) && cSet.length < 14) cSet.push(cv);
                if (rSet.includes(rv) && cSet.includes(cv)) cellMap[rv + '\u0000' + cv] = (cellMap[rv + '\u0000' + cv] || 0) + (Number(r[mK[0]]) || 0);
            });
            const allVals = Object.values(cellMap);
            const vMax = Math.max(...allVals, 1), vMin = Math.min(...allVals, 0);
            const base = palette[0];
            return (
                <Box className="qe4-scroll" sx={{ height, overflow: 'auto' }}>
                    <table style={{ borderCollapse: 'separate', borderSpacing: 3, width: '100%' }}>
                        <thead><tr>
                            <th style={{ position: 'sticky', left: 0, background: 'white', zIndex: 1 }} />
                            {cSet.map(c => <th key={c} style={{ padding: '4px 6px', fontSize: 10, fontWeight: 800, color: T.td3, textTransform: 'uppercase', letterSpacing: 0.5, whiteSpace: 'nowrap', maxWidth: 90, overflow: 'hidden', textOverflow: 'ellipsis' }}>{c}</th>)}
                        </tr></thead>
                        <tbody>{rSet.map(rv => (
                            <tr key={rv}>
                                <td style={{ padding: '4px 8px', fontSize: 11.5, fontWeight: 700, color: T.td, whiteSpace: 'nowrap', position: 'sticky', left: 0, background: 'white', zIndex: 1, maxWidth: 150, overflow: 'hidden', textOverflow: 'ellipsis' }}>{rv}</td>
                                {cSet.map(cv => {
                                    const v = cellMap[rv + '\u0000' + cv];
                                    const t = v == null ? 0 : (vMax === vMin ? 1 : (v - vMin) / (vMax - vMin));
                                    const alpha = v == null ? 0 : 0.12 + t * 0.78;
                                    return (
                                        <td key={cv} onClick={() => v != null && onChartClick?.({ name: rv })} title={v == null ? '—' : `${rv} × ${cv}: ${chartFmt(v, mK[0], true)}`}
                                            style={{ minWidth: 52, height: 30, borderRadius: 6, textAlign: 'center', fontSize: 10.5, fontWeight: 700, cursor: v != null ? 'pointer' : 'default', background: v == null ? 'rgba(15,23,42,0.03)' : `${base}${Math.round(alpha * 255).toString(16).padStart(2, '0')}`, color: t > 0.55 ? '#fff' : T.td2, fontVariantNumeric: 'tabular-nums' }}>
                                            {v == null ? '' : chartFmt(v, mK[0], true)}
                                        </td>
                                    );
                                })}
                            </tr>
                        ))}</tbody>
                    </table>
                </Box>
            );
        }

        case 'sankey': {
            if (!rawRows?.length || dimKeys.length < 2) {
                return <Box sx={{ height, display: 'flex', alignItems: 'center', justifyContent: 'center', color: T.td3, fontSize: 12.5, fontWeight: 600 }}>Sankey needs two dimensions — add a second field to the Dimensions zone.</Box>;
            }
            const [dk1, dk2] = dimKeys;
            const agg = {};
            rawRows.forEach(r => {
                const a = String(r[dk1] ?? '—'), b = String(r[dk2] ?? '—');
                agg[a + '\u0000' + b] = (agg[a + '\u0000' + b] || 0) + (Number(r[mK[0]]) || 0);
            });
            const leftTot = {}, rightTot = {};
            Object.entries(agg).forEach(([k, v]) => { const [a, b] = k.split('\u0000'); leftTot[a] = (leftTot[a] || 0) + v; rightTot[b] = (rightTot[b] || 0) + v; });
            const topL = Object.entries(leftTot).sort((x, y) => y[1] - x[1]).slice(0, 8).map(x => x[0]);
            const topR = Object.entries(rightTot).sort((x, y) => y[1] - x[1]).slice(0, 8).map(x => x[0]);
            const nodes = [...topL.map(n => ({ name: n })), ...topR.map(n => ({ name: n }))];
            const links = [];
            Object.entries(agg).forEach(([k, v]) => {
                const [a, b] = k.split('\u0000');
                const si = topL.indexOf(a), ti = topR.indexOf(b);
                if (si >= 0 && ti >= 0 && v > 0) links.push({ source: si, target: topL.length + ti, value: v });
            });
            if (!links.length) return <Box sx={{ height, display: 'flex', alignItems: 'center', justifyContent: 'center', color: T.td3, fontSize: 12.5 }}>No flow data for current selections.</Box>;
            const SankeyNode = ({ x, y, width: w, height: h, index, payload }) => (
                <Layer key={`sn-${index}`}>
                    <Rectangle x={x} y={y} width={w} height={h} fill={palette[index % palette.length]} fillOpacity={0.9} radius={2} />
                    <text x={index < topL.length ? x - 6 : x + w + 6} y={y + h / 2} textAnchor={index < topL.length ? 'end' : 'start'} dominantBaseline="middle" fontSize={11} fontWeight={700} fill={T.td2}>{payload.name?.substring(0, 16)}</text>
                </Layer>
            );
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <Sankey data={{ nodes, links }} node={<SankeyNode />} nodePadding={18} margin={{ top: 10, right: 110, bottom: 10, left: 110 }}
                        link={{ stroke: palette[0], strokeOpacity: 0.25 }}>
                        <RTooltip content={<ChartTip />} />
                    </Sankey>
                </ResponsiveContainer>
            );
        }

        default: return null;
    }
};

/* ═══════════════════════════════════════════════════════
   MAIN COMPONENT
   ═══════════════════════════════════════════════════════ */
/* ═══ PIVOT TABLE — dim1 rows × dim2 columns × first measure, with totals ═══ */
const PivotTable = ({ rows, dims, measureKey, fmtVal, onCellClick }) => {
    const pv = useMemo(() => {
        if (!rows?.length || dims.length < 2 || !measureKey) return null;
        const dk1 = dims[0].key, dk2 = dims[1].key;
        const rSet = [], cSet = [], cell = {};
        rows.forEach(r => {
            const rv = String(r[dk1] ?? '—'), cv = String(r[dk2] ?? '—');
            if (!rSet.includes(rv) && rSet.length < 60) rSet.push(rv);
            if (!cSet.includes(cv) && cSet.length < 24) cSet.push(cv);
            if (rSet.includes(rv) && cSet.includes(cv)) cell[rv + '\u0000' + cv] = (cell[rv + '\u0000' + cv] || 0) + (Number(r[measureKey]) || 0);
        });
        const rowTot = {}, colTot = {}; let grand = 0;
        rSet.forEach(rv => cSet.forEach(cv => { const v = cell[rv + '\u0000' + cv] || 0; rowTot[rv] = (rowTot[rv] || 0) + v; colTot[cv] = (colTot[cv] || 0) + v; grand += v; }));
        rSet.sort((a, b) => (rowTot[b] || 0) - (rowTot[a] || 0));
        cSet.sort((a, b) => (colTot[b] || 0) - (colTot[a] || 0));
        return { rSet, cSet, cell, rowTot, colTot, grand, dk1, dk2 };
    }, [rows, dims, measureKey]);

    if (dims.length < 2) return (
        <Box sx={{ py: 6, textAlign: 'center', color: T.td3, fontSize: 12.5, fontWeight: 600 }}>Pivot needs two dimensions — add a second field to the Dimensions zone.</Box>
    );
    if (!pv) return <Box sx={{ py: 6, textAlign: 'center', color: T.td3, fontSize: 12.5 }}>No data.</Box>;

    const th = { padding: '9px 12px', fontSize: 10, fontWeight: 800, textTransform: 'uppercase', letterSpacing: 0.7, color: T.td3, borderBottom: '2px solid rgba(0,0,0,0.07)', background: '#fafbfc', whiteSpace: 'nowrap', position: 'sticky', top: 0, zIndex: 2 };
    const tdBase = { padding: '7px 12px', fontSize: 12, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap', borderBottom: '1px solid rgba(0,0,0,0.035)', textAlign: 'right' };
    return (
        <Box className="qe4-scroll" sx={{ overflow: 'auto', maxHeight: 520 }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead><tr>
                    <th style={{ ...th, textAlign: 'left', left: 0, zIndex: 3, position: 'sticky' }}>{dims[0].label} / {dims[1].label}</th>
                    {pv.cSet.map(c => <th key={c} style={{ ...th, textAlign: 'right', maxWidth: 120, overflow: 'hidden', textOverflow: 'ellipsis' }}>{c}</th>)}
                    <th style={{ ...th, textAlign: 'right', color: T.primary }}>Total</th>
                </tr></thead>
                <tbody>
                    {pv.rSet.map(rv => (
                        <tr key={rv} className="qe4-row">
                            <td onClick={() => onCellClick?.(rv)} style={{ ...tdBase, textAlign: 'left', fontWeight: 700, color: T.td, position: 'sticky', left: 0, background: 'white', zIndex: 1, cursor: 'pointer', maxWidth: 190, overflow: 'hidden', textOverflow: 'ellipsis' }}>{rv}</td>
                            {pv.cSet.map(cv => { const v = pv.cell[rv + '\u0000' + cv]; return <td key={cv} style={{ ...tdBase, color: v ? T.td2 : 'rgba(15,23,42,0.18)' }}>{v ? fmtVal(v, measureKey) : '·'}</td>; })}
                            <td style={{ ...tdBase, fontWeight: 800, color: T.primary, background: `${T.primary}05` }}>{fmtVal(pv.rowTot[rv] || 0, measureKey)}</td>
                        </tr>
                    ))}
                    <tr>
                        <td style={{ ...tdBase, textAlign: 'left', fontWeight: 800, color: T.primary, position: 'sticky', left: 0, background: `${T.primary}06`, zIndex: 1, borderTop: '2px solid rgba(0,0,0,0.08)' }}>Total</td>
                        {pv.cSet.map(cv => <td key={cv} style={{ ...tdBase, fontWeight: 800, color: T.primary, background: `${T.primary}05`, borderTop: '2px solid rgba(0,0,0,0.08)' }}>{fmtVal(pv.colTot[cv] || 0, measureKey)}</td>)}
                        <td style={{ ...tdBase, fontWeight: 800, color: '#fff', background: T.primary, borderTop: '2px solid rgba(0,0,0,0.08)' }}>{fmtVal(pv.grand, measureKey)}</td>
                    </tr>
                </tbody>
            </table>
        </Box>
    );
};

export default function DataExplorer() {
    const { tenantVersion } = useAuth();
    const [catalog, setCatalog] = useState({ merchantFields: [], transactionFields: [], measures: [] });
    const [loading, setLoading] = useState(false);
    const [qLoad, setQLoad] = useState(false);
    const [rows, setRows] = useState([]);
    const [dims, setDims] = useState([]);
    const [meas, setMeas] = useState([]);
    const [filters, setFilters] = useState({});
    const [sd, setSd] = useState('');
    const [ed, setEd] = useState('');
    const [chartType, setChartType] = useState('bar');
    const [viewMode, setViewMode] = useState('both');
    const [sideOpen, setSideOpen] = useState(true);
    const [views, setViews] = useState([]);
    const [saveDlg, setSaveDlg] = useState(false);
    const [vName, setVName] = useState('');
    const [vDef, setVDef] = useState(false);
    const [vShare, setVShare] = useState(false);
    const [activeView, setActiveView] = useState(null);
    const [dcache, setDcache] = useState({});
    const [fldSearch, setFldSearch] = useState('');
    const [expCats, setExpCats] = useState({});
    const [qTime, setQTime] = useState(null);
    const [sTab, setSTab] = useState('fields');
    const [sortCol, setSortCol] = useState(null);
    const [sortDir, setSortDir] = useState('desc');
    const [selPanel, setSelPanel] = useState(false);
    const [openBoxes, setOpenBoxes] = useState([]);
    const [theme, setTheme] = useState('default');
    const [chartMenu, setChartMenu] = useState(null);
    const [selHistory, setSelHistory] = useState([]);
    const [tplDlg, setTplDlg] = useState(false);
    const [tplName, setTplName] = useState('');
    const [tplDesc, setTplDesc] = useState('');
    const [tplShared, setTplShared] = useState(false);
    const [assoc, setAssoc] = useState({}); // fieldKey -> { values: [{value,state}], selectedCount, possibleCount }
    const [sheet, setSheet] = useState([]);         // pinned objects: [{ id, title, vizType, dims, meas }]
    const [sheetData, setSheetData] = useState({}); // id -> { rows, qLoad, qTime }
    const [sheetLayout, setSheetLayout] = useState([]); // RGL layout: [{ i, x, y, w, h }]
    const [copied, setCopied] = useState(false);
    const [drill, setDrill] = useState([]);          // drill-down path: [{ key, value, label }]
    const [refLinesOn, setRefLinesOn] = useState(false);
    const [trendOn, setTrendOn] = useState(false);
    const [condFmt, setCondFmt] = useState('off');   // 'off' | 'heat' | 'bars'
    const [chartDropOver, setChartDropOver] = useState(false);
    const [calcMeasures, setCalcMeasures] = useState([]); // [{ key, label, formula, calc:true }]
    const [calcDlg, setCalcDlg] = useState(false);
    const [cmName, setCmName] = useState('');
    const [cmFormula, setCmFormula] = useState('');
    const [cmPublish, setCmPublish] = useState(false);
    const [calcErr, setCalcErr] = useState('');
    // Measure Studio (custom aggregation + calculated measures, with formatting)
    const [studioOpen, setStudioOpen] = useState(false);
    const [stTab, setStTab] = useState('agg');
    const [stEdit, setStEdit] = useState(null);
    const [stName, setStName] = useState('');
    const [stCol, setStCol] = useState('');
    const [stAgg, setStAgg] = useState('');
    const [stFormula, setStFormula] = useState('');
    const [stFmt, setStFmt] = useState({ unit: 'number', decimals: 2, scale: 'none', prefix: '', suffix: '' });
    const [stPublish, setStPublish] = useState(false);
    const [stErr, setStErr] = useState('');
    const [stPrev, setStPrev] = useState(null);
    const [stCondField, setStCondField] = useState('');
    const [stCondVals, setStCondVals] = useState([]);
    const [stBase, setStBase] = useState('');
    const [stCompare, setStCompare] = useState('YOY');
    const [stMode, setStMode] = useState('growth');
    const [stPrevLoad, setStPrevLoad] = useState(false);
    const [masterItems, setMasterItems] = useState([]);
    const [alerts, setAlerts] = useState([]);
    const [alertDlg, setAlertDlg] = useState(false);
    const [alertMsg, setAlertMsg] = useState('');
    const [alForm, setAlForm] = useState({ name: '', measureKey: '', operator: '>', threshold: '', windowDays: 1, severity: 'WARNING', recipients: '' });
    const [schedOn, setSchedOn] = useState(false);
    const [schedFreq, setSchedFreq] = useState('DAILY');
    const [schedFormat, setSchedFormat] = useState('EXCEL');
    const [schedDelivery, setSchedDelivery] = useState('DOWNLOAD_ONLY');
    const [schedRecipients, setSchedRecipients] = useState('');

    const palette = THEMES[theme]?.colors || THEMES.default.colors;

    useEffect(() => {
        (async () => {
            setLoading(true);
            try { setCatalog((await explorerApi.getFields()).data); } catch (e) {}
            try { setViews((await savedViewsApi.list('DATA_EXPLORER')).data); } catch (e) {}
            try {
                const mi = (await explorerApi.listMaster()).data || [];
                setMasterItems(mi);
                const calcMi = mi.filter(x => x.itemType === 'CALC')
                    .map(x => ({ key: x.itemKey, label: x.label, formula: x.definition, kind: 'calc', calc: true, shared: true, masterId: x.id }));
                const aggMi = mi.filter(x => x.itemType === 'AGG').map(x => {
                    let spec = {}; try { spec = JSON.parse(x.definition || '{}'); } catch (e) {}
                    return { key: x.itemKey, label: x.label, kind: 'agg', column: spec.column, agg: spec.agg, format: spec.format, filterField: spec.filterField, filterValues: spec.filterValues, calc: true, shared: true, masterId: x.id };
                });
                const timeMi = mi.filter(x => x.itemType === 'TIME').map(x => {
                    let spec = {}; try { spec = JSON.parse(x.definition || '{}'); } catch (e) {}
                    return { key: x.itemKey, label: x.label, kind: 'time', base: spec.base, comparison: spec.comparison, mode: spec.mode, format: spec.format, calc: true, shared: true, masterId: x.id };
                });
                const allMi = [...calcMi, ...aggMi, ...timeMi];
                if (allMi.length) setCalcMeasures(p => {
                    const have = new Set(p.map(c => c.key));
                    return [...p, ...allMi.filter(c => !have.has(c.key))];
                });
            } catch (e) {}
            try { setAlerts((await explorerApi.listAlerts()).data || []); } catch (e) {}
            setLoading(false);
        })();
    }, [tenantVersion]);

    const loadDist = async k => { if (dcache[k]) return; setDcache(p => ({ ...p, [k]: null })); try { const r = await explorerApi.getDistinct(k); setDcache(p => ({ ...p, [k]: r.data })); } catch (e) { setDcache(p => ({ ...p, [k]: [] })); } };

    // Associative state — green/white/gray for the open filter boxes, recomputed
    // whenever selections or the date window change.
    const loadAssoc = useCallback(async (fieldKeys) => {
        if (!fieldKeys.length) return;
        try {
            const r = await explorerApi.associative({
                fields: fieldKeys,
                filters: Object.fromEntries(Object.entries(filters).filter(([, v]) => v?.length)),
                startDate: sd, endDate: ed,
            });
            setAssoc(prev => ({ ...prev, ...(r.data?.states || {}) }));
        } catch (e) {}
    }, [filters, sd, ed]);
    useEffect(() => { if (openBoxes.length) loadAssoc(openBoxes); }, [openBoxes, loadAssoc]);

    // Values to render in a filter box: associative states if available, else the
    // plain distinct list (everything 'possible') until the associative call returns.
    const boxItems = (fk) => {
        if (assoc[fk]?.values) return assoc[fk].values;
        const vals = dcache[fk];
        if (vals == null) return null;
        const sel = filters[fk] || [];
        return vals.map(v => ({ value: v, state: sel.includes(v) ? 'selected' : 'possible' }));
    };

    const buildFilters = () => Object.fromEntries(Object.entries(filters).filter(([, v]) => v?.length));
    const splitCustom = (arr) => ({
        calc: (arr || []).filter(m => (m.kind || (m.formula ? 'calc' : '')) === 'calc').map(m => ({ key: m.key, label: m.label, formula: m.formula })),
        agg: (arr || []).filter(m => m.kind === 'agg').map(m => ({ key: m.key, label: m.label, column: m.column, agg: m.agg, filterField: m.filterField, filterValues: m.filterValues })),
        time: (arr || []).filter(m => m.kind === 'time').map(m => ({ key: m.key, label: m.label, base: m.base, comparison: m.comparison, mode: m.mode })),
    });
    const execQuery = useCallback(async (dimsArr, measArr) => {
        const hasCustom = measArr.some(m => m.calc);
        const { calc, agg, time } = splitCustom(calcMeasures);
        const p = { dimensions: dimsArr.map(d => d.key), measures: measArr.length ? measArr.map(m => m.key) : ['txn_count', 'total_volume', 'total_msf'], calcMeasures: calc, aggMeasures: agg, timeMeasures: time, filters: buildFilters(), startDate: sd, endDate: ed, limit: 1000 };
        const moOnly = !hasCustom && dimsArr.every(d => catalog.merchantFields.some(f => f.key === d.key));
        const r = moOnly ? await explorerApi.queryMerchants(p) : await explorerApi.query(p);
        return r.data.data || [];
    }, [filters, sd, ed, catalog, calcMeasures]);
    // Drill-down: the drilled dimensions are pinned by filters; query the remainder.
    const activeDims = useMemo(() => (drill.length && drill.length < dims.length) ? dims.slice(drill.length) : dims, [dims, drill]);
    const run = useCallback(async () => {
        if (!dims.length) return;
        setQLoad(true); const t0 = Date.now();
        try { setRows(await execQuery(activeDims, meas)); } catch (e) { setRows([]); }
        setQTime(Date.now() - t0); setQLoad(false);
    }, [dims, activeDims, meas, execQuery]);

    // Per-measure formatting lookups (custom measures carry .format; base measures use BASE_FMT).
    const measFmt = useMemo(() => {
        const m = {};
        calcMeasures.forEach(c => { if (c.format) m[c.key] = c.format; });
        return m;
    }, [calcMeasures]);
    const fmtVal = useCallback((val, key) => {
        if (val == null) return '—';
        if (typeof val !== 'number') return val;
        return formatMeasure(val, measFmt[key] || BASE_FMT[key] || { unit: 'number', decimals: 2 });
    }, [measFmt]);
    const fmtKpi = useCallback((val, key) => {
        const f = measFmt[key] || BASE_FMT[key] || { unit: 'number' };
        return formatMeasure(val, { ...f, scale: (f.scale && f.scale !== 'none') ? f.scale : 'auto' });
    }, [measFmt]);

    // ── Sheet (pinned objects) ──────────────────────────────────────
    const newId = () => `obj_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
    const pinCurrent = () => {
        if (!dims.length) return;
        const id = newId();
        const title = `${meas[0]?.label || 'Count'} by ${dims.map(d => d.label).join(' / ')}`;
        setSheet(p => [...p, { id, title, vizType: chartType, dims: [...dims], meas: [...meas] }]);
        setSheetData(p => ({ ...p, [id]: { rows: [...rows], qLoad: false, qTime } }));
    };
    const removeObj = id => { setSheet(p => p.filter(o => o.id !== id)); setSheetData(p => { const n = { ...p }; delete n[id]; return n; }); };
    const duplicateObj = id => { const o = sheet.find(x => x.id === id); if (!o) return; const nid = newId(); setSheet(p => [...p, { ...o, id: nid, title: o.title + ' (copy)' }]); setSheetData(p => ({ ...p, [nid]: p[id] ? { ...p[id] } : { rows: [] } })); };
    const editObj = id => { const o = sheet.find(x => x.id === id); if (!o) return; setDims([...o.dims]); setMeas([...o.meas]); setChartType(o.vizType); setViewMode('both'); };
    const renameObj = (id, title) => setSheet(p => p.map(o => o.id === id ? { ...o, title } : o));
    const addCalcMeasure = async () => {
        const name = cmName.trim(); const formula = cmFormula.trim();
        if (!name || !formula) return;
        setCalcErr('');
        let base = 'calc_' + name.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
        if (!base || base === 'calc_') base = 'calc_measure';
        const exists = k => calcMeasures.some(c => c.key === k) || (catalog.measures || []).some(m => m.key === k);
        let key = base, n = 2;
        while (exists(key)) { key = base + '_' + n++; }
        let entry = { key, label: name, formula, calc: true };
        if (cmPublish) {
            try {
                const res = await explorerApi.createMaster({ itemType: 'CALC', itemKey: key, label: name, definition: formula });
                entry = { ...entry, shared: true, masterId: res.data?.id };
                setMasterItems(p => [...p, res.data]);
            } catch (e) { setCalcErr(e.response?.data?.error || 'Could not publish — admin only.'); return; }
        }
        setCalcMeasures(p => [...p, entry]);
        setCalcDlg(false); setCmName(''); setCmFormula(''); setCmPublish(false); setCalcErr('');
    };
    const STUDIO_BLANK = { unit: 'number', decimals: 2, scale: 'none', prefix: '', suffix: '' };
    const openStudio = (m) => {
        setStErr(''); setStPrev(null);
        if (m) {
            setStEdit(m.key); setStName(m.label || '');
            setStTab(m.kind === 'agg' ? 'agg' : m.kind === 'time' ? 'time' : 'calc');
            setStCol(m.column || ''); setStAgg(m.agg || ''); setStFormula(m.formula || '');
            setStCondField(m.filterField || ''); setStCondVals(m.filterValues || []);
            setStBase(m.base || ''); setStCompare(m.comparison || 'YOY'); setStMode(m.mode || 'growth');
            if (m.filterField) loadDist(m.filterField);
            setStFmt({ ...STUDIO_BLANK, ...(m.format || {}) }); setStPublish(!!m.shared);
        } else {
            setStEdit(null); setStName(''); setStTab('agg');
            setStCol(''); setStAgg(''); setStFormula(''); setStCondField(''); setStCondVals([]);
            setStBase(''); setStCompare('YOY'); setStMode('growth');
            setStFmt({ ...STUDIO_BLANK }); setStPublish(false);
        }
        setStudioOpen(true);
    };
    const closeStudio = () => { setStudioOpen(false); setStErr(''); setStPrev(null); };
    const studioValid = stTab === 'agg' ? !!(stCol && stAgg) : stTab === 'time' ? !!stBase : !!stFormula.trim();
    const stColKind = (catalog.measureColumns || []).find(c => c.key === stCol)?.kind;
    const stAggOpts = stColKind ? (catalog.aggsByKind?.[stColKind] || []) : [];
    const stSample = formatMeasure(1234567.891, stFmt);
    const stLabelSx = { fontSize: 10.5, fontWeight: 800, color: T.td3, letterSpacing: 0.5, mb: 0.75 };
    const stFtLabel = { fontSize: 11.5, fontWeight: 700, color: T.td3, minWidth: 60 };
    const stSeg = (val, label, current, onClick, color) => (
        <Box key={val} onClick={onClick} sx={{ cursor: 'pointer', px: 1.1, py: 0.5, borderRadius: '7px', fontSize: 11.5, fontWeight: 800, border: `1px solid ${current === val ? color : T.cardBorder}`, bgcolor: current === val ? `${color}12` : '#fff', color: current === val ? color : T.td2, transition: 'all .15s', '&:hover': { borderColor: color } }}>{label}</Box>
    );
    const saveStudioMeasure = async () => {
        setStErr('');
        const name = stName.trim();
        if (!name) { setStErr('Give the measure a name'); return; }
        const kind = stTab;
        let entry;
        if (kind === 'agg') {
            if (!stCol || !stAgg) { setStErr('Pick a column and an aggregation'); return; }
            entry = { label: name, kind: 'agg', column: stCol, agg: stAgg, calc: true, format: { ...stFmt } };
            if (stCondField && stCondVals.length) { entry.filterField = stCondField; entry.filterValues = [...stCondVals]; }
        } else if (kind === 'time') {
            if (!stBase) { setStErr('Pick a base measure to compare'); return; }
            entry = { label: name, kind: 'time', base: stBase, comparison: stCompare, mode: stMode, calc: true, format: { ...stFmt } };
        } else {
            const formula = stFormula.trim();
            if (!formula) { setStErr('Enter a formula'); return; }
            entry = { label: name, kind: 'calc', formula, calc: true, format: { ...stFmt } };
        }
        if (stEdit) entry.key = stEdit;
        else {
            let base = 'm_' + name.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
            if (!base || base === 'm_') base = 'm_measure';
            const exists = k => calcMeasures.some(c => c.key === k) || (catalog.measures || []).some(mm => mm.key === k);
            let key = base, n = 2; while (exists(key)) key = base + '_' + n++;
            entry.key = key;
        }
        if (stPublish) {
            try {
                let definition;
                if (kind === 'agg') definition = JSON.stringify({ column: stCol, agg: stAgg, format: stFmt, filterField: (stCondField && stCondVals.length) ? stCondField : undefined, filterValues: (stCondField && stCondVals.length) ? stCondVals : undefined });
                else if (kind === 'time') definition = JSON.stringify({ base: stBase, comparison: stCompare, mode: stMode, format: stFmt });
                else definition = stFormula.trim();
                const itemType = kind === 'agg' ? 'AGG' : kind === 'time' ? 'TIME' : 'CALC';
                const res = await explorerApi.createMaster({ itemType, itemKey: entry.key, label: name, definition });
                entry = { ...entry, shared: true, masterId: res.data?.id };
                setMasterItems(p => [...p.filter(x => x.id !== res.data?.id), res.data]);
            } catch (e) { setStErr(e.response?.data?.error || 'Could not publish — admin only.'); return; }
        }
        setCalcMeasures(p => stEdit ? p.map(c => c.key === stEdit ? { ...c, ...entry } : c) : [...p, entry]);
        closeStudio();
    };
    // Live preview value for the builder (debounced single-aggregate query over current filters).
    useEffect(() => {
        if (!studioOpen || !studioValid) { setStPrev(null); return; }
        const tkey = '__studio_preview__';
        const { calc: exCalc, agg: exAgg } = splitCustom(calcMeasures);
        const payload = { dimensions: [], measures: [tkey], filters: buildFilters(), startDate: sd, endDate: ed, limit: 1, calcMeasures: exCalc, aggMeasures: exAgg };
        if (stTab === 'agg') payload.aggMeasures = [...exAgg, { key: tkey, label: 'preview', column: stCol, agg: stAgg, filterField: (stCondField && stCondVals.length) ? stCondField : undefined, filterValues: (stCondField && stCondVals.length) ? stCondVals : undefined }];
        else if (stTab === 'time') payload.timeMeasures = [{ key: tkey, label: 'preview', base: stBase, comparison: stCompare, mode: stMode }];
        else payload.calcMeasures = [...exCalc, { key: tkey, label: 'preview', formula: stFormula.trim() }];
        let cancel = false; setStPrevLoad(true);
        const h = setTimeout(async () => {
            try {
                const r = await explorerApi.query(payload);
                const row = (r.data.data || [])[0];
                if (!cancel) { setStPrev(row ? Number(row[tkey]) : null); setStErr(''); }
            } catch (e) { if (!cancel) { setStPrev(null); setStErr(e.response?.data?.error || ''); } }
            finally { if (!cancel) setStPrevLoad(false); }
        }, 500);
        return () => { cancel = true; clearTimeout(h); };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [studioOpen, stTab, stCol, stAgg, stFormula, stCondField, stCondVals, stBase, stCompare, stMode, sd, ed, filters]);
    const reloadAlerts = async () => { try { setAlerts((await explorerApi.listAlerts()).data || []); } catch (e) {} };
    const createAlertFn = async () => {
        const mk = alForm.measureKey; if (!mk) return;
        const cDef = calcMeasures.find(c => c.key === mk);
        const cPayload = cDef ? [cDef.kind === 'agg'
            ? { key: cDef.key, label: cDef.label, column: cDef.column, agg: cDef.agg, filterField: cDef.filterField, filterValues: cDef.filterValues }
            : { key: cDef.key, label: cDef.label, formula: cDef.formula }] : undefined;
        try {
            await explorerApi.createAlert({
                name: (alForm.name || '').trim() || `${mk} ${alForm.operator} ${alForm.threshold}`,
                measureKey: mk, operator: alForm.operator,
                threshold: Number(alForm.threshold) || 0,
                windowDays: Number(alForm.windowDays) || 1,
                severity: alForm.severity, recipients: alForm.recipients,
                filters: buildFilters(),
                calcMeasures: cPayload,
            });
            setAlForm(f => ({ ...f, name: '', threshold: '' })); setAlertMsg('Alert created'); reloadAlerts();
        } catch (e) { setAlertMsg(e.response?.data?.error || 'Could not create alert'); }
    };
    const toggleAlert = async (a) => { try { await explorerApi.updateAlert(a.id, { isEnabled: !a.isEnabled }); reloadAlerts(); } catch (e) {} };
    const runAlertNow = async (a) => { try { const r = await explorerApi.runAlert(a.id); setAlertMsg(`${a.name}: ${Number(r.data.value).toLocaleString()} ${r.data.breached ? '— BREACH' : '— ok'}`); reloadAlerts(); } catch (e) { setAlertMsg('Run failed'); } };
    const delAlert = async (a) => { try { await explorerApi.deleteAlert(a.id); reloadAlerts(); } catch (e) {} };
    const objMeasureKeys = (rowsArr, dimsArr, measArr) => measArr.length ? measArr.map(m => m.key) : (rowsArr?.length ? Object.keys(rowsArr[0]).filter(k => !dimsArr.some(d => d.key === k)) : []);
    const toChartData = (rowsArr, dimsArr, measArr) => {
        const keys = measArr.length ? measArr.map(m => m.key) : ['txn_count', 'total_volume', 'total_msf'];
        // Sort by the first measure descending, then top 25 — pinned sheet tiles
        // show the biggest contributors, matching the main chart's behaviour.
        const primary = keys[0];
        const ranked = [...(rowsArr || [])].sort((a, b) => (Number(b[primary]) || 0) - (Number(a[primary]) || 0));
        return ranked.slice(0, 25).map((r, i) => {
            const e = { name: dimsArr.length ? String(r[dimsArr[0].key] ?? `Row ${i + 1}`).substring(0, 18) : `Row ${i + 1}` };
            keys.forEach(k => { e[k] = Number(r[k]) || 0; });
            return e;
        });
    };
    const sheetSig = useMemo(() => JSON.stringify(sheet.map(o => ({ i: o.id, d: o.dims.map(x => x.key), m: o.meas.map(x => x.key) }))), [sheet]);
    // Drag/resize grid layout for the sheet tiles. Missing tiles get a sensible default slot.
    const gridLayout = useMemo(() => sheet.map((o, i) => {
        const ex = sheetLayout.find(l => l.i === o.id);
        return ex ? { i: o.id, x: ex.x, y: ex.y, w: ex.w, h: ex.h, minW: 3, minH: 5 }
                  : { i: o.id, x: (i % 2) * 6, y: Math.floor(i / 2) * 8, w: 6, h: 8, minW: 3, minH: 5 };
    }), [sheet, sheetLayout]);
    const tileChartH = (id) => { const l = gridLayout.find(g => g.i === id); const h = l ? l.h : 8; return Math.max(140, h * 30 + (h - 1) * 16 - 107); };
    // Re-run every pinned object whenever the global selections / date / sheet structure change.
    useEffect(() => {
        if (!sheet.length) return;
        let cancelled = false;
        sheet.forEach(async (o) => {
            setSheetData(p => ({ ...p, [o.id]: { ...(p[o.id] || {}), qLoad: true } }));
            const t0 = Date.now();
            try {
                const data = await execQuery(o.dims, o.meas);
                if (!cancelled) setSheetData(p => ({ ...p, [o.id]: { rows: data, qLoad: false, qTime: Date.now() - t0 } }));
            } catch {
                if (!cancelled) setSheetData(p => ({ ...p, [o.id]: { rows: [], qLoad: false, qTime: Date.now() - t0 } }));
            }
        });
        return () => { cancelled = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [filters, sd, ed, sheetSig]);

    // Deep-link: copy current sheet+selections to URL; restore from ?state= on load.
    const encodeState = () => btoa(encodeURIComponent(JSON.stringify({
        sheet: sheet.map(o => ({ t: o.title, v: o.vizType, d: o.dims.map(x => x.key), m: o.meas.map(x => x.key) })),
        sl: sheet.map(o => { const l = gridLayout.find(g => g.i === o.id) || {}; return { x: l.x, y: l.y, w: l.w, h: l.h }; }),
        c: calcMeasures, f: buildFilters(), sd, ed,
    })));
    const copyLink = () => {
        try {
            const url = `${window.location.origin}${window.location.pathname}?state=${encodeState()}`;
            navigator.clipboard?.writeText(url);
            setCopied(true); setTimeout(() => setCopied(false), 1600);
        } catch (e) {}
    };
    const restoredRef = useRef(false);
    useEffect(() => {
        if (restoredRef.current) return;
        if (!(catalog.measures?.length || catalog.merchantFields?.length)) return;
        restoredRef.current = true;
        try {
            const st = new URLSearchParams(window.location.search).get('state');
            if (!st) return;
            const payload = JSON.parse(decodeURIComponent(atob(st)));
            const af = [...(catalog.merchantFields || []), ...(catalog.transactionFields || [])];
            const rD = ks => (ks || []).map(k => af.find(f => f.key === k)).filter(Boolean);
            const rM = ks => (ks || []).map(k => (catalog.measures || []).find(m => m.key === k)).filter(Boolean);
            if (payload.f) setFilters(payload.f);
            if (payload.sd) setSd(payload.sd);
            if (payload.ed) setEd(payload.ed);
            if (Array.isArray(payload.c)) setCalcMeasures(payload.c);
            if (Array.isArray(payload.sheet)) {
                const bp = payload.sheet.map((o, i) => ({ obj: { id: newId(), title: o.t, vizType: o.v || 'bar', dims: rD(o.d), meas: rM(o.m) }, sl: (payload.sl || [])[i] })).filter(p => p.obj.dims.length);
                setSheet(bp.map(p => p.obj));
                setSheetLayout(bp.map((p, i) => { const l = p.sl || {}; return { i: p.obj.id, x: l.x ?? (i % 2) * 6, y: l.y ?? Math.floor(i / 2) * 8, w: l.w ?? 6, h: l.h ?? 8 }; }));
            }
        } catch (e) {}
    }, [catalog]);

    const prevF = useRef(filters);
    useEffect(() => { if (prevF.current !== filters && dims.length > 0) { const t = setTimeout(run, 350); prevF.current = filters; return () => clearTimeout(t); } }, [filters, dims, run]);

    const addDim = f => { if (!dims.find(d => d.key === f.key) && f.source !== 'measure') setDims(p => [...p, f]); };
    const rmDim = i => setDims(p => p.filter((_, x) => x !== i));
    const addMeas = f => { if (!meas.find(m => m.key === f.key)) setMeas(p => [...p, f]); };
    const rmMeas = i => setMeas(p => p.filter((_, x) => x !== i));

    // Double-click-to-add bridge for the sidebar chips (module-level, like CHART_FMT).
    useEffect(() => {
        QE4_ADD = { dim: f => addDim(f), meas: m => addMeas({ ...m, source: 'measure' }) };
        return () => { QE4_ADD = null; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [dims, meas]);

    // Drill housekeeping: reset when the dim set shrinks below the path, or a view loads.
    useEffect(() => { if (drill.length && drill.length >= dims.length) setDrill([]); }, [dims, drill]);
    useEffect(() => { setDrill([]); }, [activeView]);
    // Climb back up the drill path: pop entries >= i and release their filter values.
    const drillTo = (i) => {
        const popped = drill.slice(i);
        setSelHistory(h => [...h, { ...filters }]);
        setFilters(p => {
            const o = { ...p };
            popped.forEach(d => {
                const cur = (o[d.key] || []).filter(v => v !== d.value);
                cur.length ? o[d.key] = cur : delete o[d.key];
            });
            return o;
        });
        setDrill(drill.slice(0, i));
    };

    // Drop a sidebar field/measure onto a pinned sheet tile → add it to that object.
    const dropOnTile = (id, p) => {
        if (!p || !p.key) return;
        setSheet(prev => prev.map(o => {
            if (o.id !== id) return o;
            if (p.source === 'measure' || p.calc) {
                if (o.meas.some(m => m.key === p.key)) return o;
                return { ...o, meas: [...o.meas, p] };
            }
            if (o.dims.some(d => d.key === p.key)) return o;
            return { ...o, dims: [...o.dims, p] };
        }));
    };
    const toggleFV = (k, v) => {
        setSelHistory(h => [...h, { ...filters }]);
        setFilters(p => { const c = p[k] || []; const h = c.includes(v); const n = h ? c.filter(x => x !== v) : [...c, v]; const o = { ...p }; n.length ? o[k] = n : delete o[k]; return o; });
    };
    const clearFK = k => { setSelHistory(h => [...h, { ...filters }]); setFilters(p => { const o = { ...p }; delete o[k]; return o; }); };
    const stepBack = () => { if (selHistory.length) { const prev = selHistory[selHistory.length - 1]; setFilters(prev); setSelHistory(h => h.slice(0, -1)); } };
    const clearAll = () => { setDims([]); setMeas([]); setFilters({}); setSd(''); setEd(''); setRows([]); setActiveView(null); setQTime(null); setOpenBoxes([]); setSelHistory([]); setDrill([]); };

    // Chart click: with >1 remaining dimension, select AND descend (Qlik drill);
    // on the last level it just cross-filters as before.
    const onChartClick = d => {
        if (!d || !activeDims.length) return;
        const val = d.name || d?.payload?.name;
        if (!val) return;
        const cur = activeDims[0];
        toggleFV(cur.key, String(val));
        if (activeDims.length > 1 && !(filters[cur.key] || []).includes(String(val))) {
            setDrill(p => [...p, { key: cur.key, value: String(val), label: cur.label }]);
        }
    };

    const saveV = async () => { if (!vName.trim()) return; try { await savedViewsApi.create({ name: vName, dashboardType: 'DATA_EXPLORER', filterJson: JSON.stringify({ dimensions: dims.map(d => d.key), measures: meas.map(m => m.key), filters, startDate: sd, endDate: ed, chartType, viewMode, theme, calc: calcMeasures, sheet: sheet.map(o => ({ t: o.title, v: o.vizType, d: o.dims.map(x => x.key), m: o.meas.map(x => x.key) })), sheetLayout: sheet.map(o => { const l = gridLayout.find(g => g.i === o.id) || {}; return { x: l.x, y: l.y, w: l.w, h: l.h }; }) }), isDefault: vDef, isShared: vShare }); setSaveDlg(false); setVName(''); setViews((await savedViewsApi.list('DATA_EXPLORER')).data); } catch (e) {} };
    const loadV = v => { try { const s = JSON.parse(v.filterJson); const af = [...(catalog.merchantFields || []), ...(catalog.transactionFields || [])]; setDims((s.dimensions || []).map(k => af.find(f => f.key === k)).filter(Boolean)); setMeas((s.measures || []).map(k => (catalog.measures || []).find(m => m.key === k)).filter(Boolean)); setFilters(s.filters || {}); setSd(s.startDate || ''); setEd(s.endDate || ''); setChartType(s.chartType || 'bar'); setViewMode(s.viewMode || 'both'); if (s.theme) setTheme(s.theme); if (Array.isArray(s.calc)) setCalcMeasures(s.calc); if (Array.isArray(s.sheet)) { const bp = s.sheet.map((o, i) => ({ obj: { id: `obj_${Date.now()}_${Math.random().toString(36).slice(2, 7)}_${i}`, title: o.t, vizType: o.v || 'bar', dims: (o.d || []).map(k => af.find(f => f.key === k)).filter(Boolean), meas: (o.m || []).map(k => (catalog.measures || []).find(m => m.key === k)).filter(Boolean) }, sl: (s.sheetLayout || [])[i] })).filter(p => p.obj.dims.length); setSheet(bp.map(p => p.obj)); setSheetLayout(bp.map((p, i) => { const l = p.sl || {}; return { i: p.obj.id, x: l.x ?? (i % 2) * 6, y: l.y ?? Math.floor(i / 2) * 8, w: l.w ?? 6, h: l.h ?? 8 }; })); } setActiveView(v.id); } catch (e) {} };
    const delV = async id => { try { await savedViewsApi.remove(id); setViews((await savedViewsApi.list('DATA_EXPLORER')).data); if (activeView === id) setActiveView(null); } catch (e) {} };
    const exportCSV = () => { if (!rows.length) return; const h = Object.keys(rows[0]); const csv = [h.join(','), ...rows.map(r => h.map(k => `"${r[k] ?? ''}"`).join(','))].join('\n'); const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' })); a.download = 'explorer.csv'; a.click(); };
    const exportExcel = async () => { if (!rows.length) return; try { const res = await reportApi.exportExcel({ reportName: 'Data Explorer Report', data: rows }); const a = document.createElement('a'); a.href = URL.createObjectURL(res.data); a.download = 'explorer_report.xlsx'; a.click(); } catch (e) {} };
    const saveTemplate = async () => {
        if (!tplName.trim()) return;
        try {
            const res = await reportApi.createTemplate({ name: tplName, description: tplDesc, isShared: tplShared, userId: 0, configJson: JSON.stringify({ dimensions: dims.map(d => d.key), measures: meas.map(m => m.key), calc: calcMeasures, filters, startDate: sd, endDate: ed, chartType, viewMode }) });
            const tplId = res.data?.id;
            if (schedOn && tplId) {
                const cron = schedFreq === 'WEEKLY' ? '0 0 8 * * MON' : schedFreq === 'MONTHLY' ? '0 0 8 1 * *' : '0 0 8 * * *';
                const label = schedFreq === 'WEEKLY' ? 'Weekly (Mon 08:00)' : schedFreq === 'MONTHLY' ? 'Monthly (1st 08:00)' : 'Daily (08:00)';
                try { await reportApi.createSchedule(tplId, { cronExpression: cron, frequencyLabel: label, exportFormat: schedFormat, deliveryMethod: schedDelivery, recipientEmails: schedRecipients, isEnabled: true }); } catch (e) {}
            }
            setTplDlg(false); setTplName(''); setTplDesc(''); setSchedOn(false); setSchedRecipients('');
        } catch (e) {}
    };
    const quickStart = k => { const af = [...(catalog.merchantFields || []), ...(catalog.transactionFields || [])]; const f = af.find(x => x.key === k); if (f) setDims([f]); };
    const toggleBox = async k => { if (openBoxes.includes(k)) { setOpenBoxes(p => p.filter(x => x !== k)); } else { setOpenBoxes(p => [...p, k]); await loadDist(k); } };

    // Derived
    const togCat = c => setExpCats(p => ({ ...p, [c]: !p[c] }));
    const grpCat = fs => { const g = {}; (fs || []).forEach(f => { (g[f.category || 'other'] = g[f.category || 'other'] || []).push(f); }); return g; };
    const mG = grpCat(catalog.merchantFields);
    const tG = grpCat(catalog.transactionFields);
    const nF = Object.values(filters).flat().length;
    const mK = meas.length ? meas.map(m => m.key) : (rows.length ? Object.keys(rows[0]).filter(k => !dims.some(d => d.key === k)) : []);
    const sorted = useMemo(() => {
        if (!sortCol || !rows.length) return rows;
        return [...rows].sort((a, b) => { const av = a[sortCol], bv = b[sortCol]; const c = typeof av === 'number' ? av - bv : String(av || '').localeCompare(String(bv || '')); return sortDir === 'asc' ? c : -c; });
    }, [rows, sortCol, sortDir]);
    // Per-column min/max for conditional formatting (heat cells / data bars).
    const colStats = useMemo(() => {
        const s = {};
        if (!rows.length) return s;
        Object.keys(rows[0]).forEach(k => {
            if (typeof rows[0][k] !== 'number') return;
            let mn = Infinity, mx = -Infinity;
            rows.forEach(r => { const v = Number(r[k]); if (!isNaN(v)) { if (v < mn) mn = v; if (v > mx) mx = v; } });
            s[k] = { mn, mx };
        });
        return s;
    }, [rows]);
    // Chart data: sort by the FIRST measure descending, then take the top 25.
    // The table (`sorted`) keeps the user's chosen column sort; the chart always
    // shows the top-N by primary measure so high-cardinality dimensions (merchants,
    // MCC, city) render the biggest contributors instead of an arbitrary first slice.
    const cData = (() => {
        const mKeys = meas.length ? meas.map(m => m.key) : ['txn_count', 'total_volume', 'total_msf'];
        const primary = mKeys[0];
        const ranked = [...rows].sort((a, b) => (Number(b[primary]) || 0) - (Number(a[primary]) || 0));
        return ranked.slice(0, 25).map((r, i) => {
            const e = { name: activeDims.length ? String(r[activeDims[0].key] || `Row ${i + 1}`).substring(0, 18) : `Row ${i + 1}` };
            mKeys.forEach(k => { e[k] = Number(r[k]) || 0; });
            return e;
        });
    })();
    const filterFlds = fs => !fldSearch ? fs : fs.filter(f => f.label.toLowerCase().includes(fldSearch.toLowerCase()));
    const filterableFields = useMemo(() =>
        [...(catalog.merchantFields || []), ...(catalog.transactionFields || [])]
            .filter(f => ['classification','card','status','organization','location','flags','terminal','people','dates'].includes(f.category)),
        [catalog]);
    const SEL = '#009845';

    if (loading) return (
        <SectionLoader label="Loading Explorer" minHeight="80vh" framed={false} />
    );

    const renderCat = (ck, fields, pfx) => {
        const c = gc(ck); const I = c.icon;
        const fl = filterFlds(fields); if (!fl.length && fldSearch) return null;
        const open = expCats[`${pfx}_${ck}`] !== false;
        return (
            <Box key={`${pfx}_${ck}`}>
                <Box onClick={() => togCat(`${pfx}_${ck}`)} sx={{ display: 'flex', alignItems: 'center', gap: 0.75, pl: 1, pr: 1.25, py: 0.6, mx: 0.75, borderRadius: '7px', cursor: 'pointer', '&:hover': { bgcolor: T.sidebarHover }, transition: 'all 0.12s' }}>
                    <Box sx={{ width: 3, height: 14, borderRadius: 3, bgcolor: c.color, opacity: 0.5 }} />
                    {open ? <ChevronDown size={12} color={T.tw3} /> : <ChevronRight size={12} color={T.tw3} />}
                    <I size={12} color={c.color} style={{ opacity: 0.75 }} />
                    <Typography sx={{ fontSize: 11.5, fontWeight: 600, color: T.tw2, flex: 1, textTransform: 'capitalize' }}>{ck}</Typography>
                    <Typography sx={{ fontSize: 9, fontWeight: 700, color: T.tw3 }}>{fl.length}</Typography>
                </Box>
                <Collapse in={open} timeout={180}>
                    <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: '5px', pl: 4.25, pr: 1, py: 0.5 }}>
                        {fl.map(f => <SideChip key={f.key} field={f} source={pfx} active={dims.some(d => d.key === f.key) || !!(filters[f.key] && filters[f.key].length)} />)}
                    </Box>
                </Collapse>
            </Box>
        );
    };

    const TogBtn = ({ icon: I, active, onClick, tip }) => (
        <Tooltip title={tip} arrow><Box onClick={onClick} sx={{ width: 32, height: 30, display: 'flex', alignItems: 'center', justifyContent: 'center', borderRadius: '7px', cursor: 'pointer', bgcolor: active ? 'white' : 'transparent', color: active ? T.primary : T.td3, boxShadow: active ? T.shadow : 'none', transition: 'all 0.15s', '&:hover': { color: active ? T.primary : T.td2 } }}><I size={14} /></Box></Tooltip>
    );

    return (
        <Box className="qe4" sx={{ display: 'flex', height: '100vh', overflow: 'hidden', bgcolor: T.workspace }}>

            {/* ████ SIDEBAR ████ */}
            <Collapse in={sideOpen} orientation="horizontal" timeout={250}>
                <Box sx={{ width: 272, height: '100%', display: 'flex', flexDirection: 'column', bgcolor: T.sidebar, borderRight: '1px solid rgba(255,255,255,0.04)', backgroundImage: 'radial-gradient(ellipse at 30% 0%, rgba(67,97,238,0.06) 0%, transparent 60%)' }}>
                    <Box sx={{ px: 1.75, pt: 1.75, pb: 1 }}>
                        <Stack direction="row" spacing={0.75} alignItems="center" sx={{ mb: 1.5 }}>
                            <Avatar sx={{ width: 28, height: 28, borderRadius: '8px', background: `linear-gradient(135deg, ${T.primary}, #00b37e)`, boxShadow: `0 2px 10px ${T.primary}40` }}>
                                <Compass size={14} color="white" />
                            </Avatar>
                            <Typography sx={{ fontSize: 14.5, fontWeight: 800, color: T.tw, letterSpacing: '-0.02em' }}>Data Explorer</Typography>
                            <Box sx={{ flex: 1 }} />
                            <Chip size="small" label="12 charts" sx={{ height: 20, fontSize: 9, fontWeight: 700, bgcolor: `${T.primary}20`, color: T.primaryLight, '& .MuiChip-label': { px: 0.75 } }} />
                        </Stack>
                        <TextField fullWidth size="small" placeholder="Search fields…" value={fldSearch} onChange={e => setFldSearch(e.target.value)}
                            InputProps={{ startAdornment: <InputAdornment position="start"><Search size={13} color={T.tw3} /></InputAdornment>,
                                sx: { fontSize: 12, color: T.tw, height: 34, bgcolor: 'rgba(255,255,255,0.04)', borderRadius: '8px', '& fieldset': { border: '1px solid rgba(255,255,255,0.06)' }, '&:hover fieldset': { borderColor: 'rgba(255,255,255,0.12) !important' }, '&.Mui-focused fieldset': { borderColor: `${T.primary}60 !important` }, '& input::placeholder': { color: T.tw3, opacity: 1 } } }} />
                    </Box>
                    <Stack direction="row" sx={{ mx: 1.5, mb: 0.75, bgcolor: 'rgba(255,255,255,0.03)', borderRadius: '7px', p: '2.5px' }}>
                        {[{ k: 'fields', l: 'Fields', i: Database }, { k: 'views', l: 'Views', i: Bookmark }].map(({ k, l, i: I }) => (
                            <Box key={k} onClick={() => setSTab(k)} sx={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 0.5, py: 0.5, borderRadius: '5px', cursor: 'pointer', bgcolor: sTab === k ? 'rgba(255,255,255,0.06)' : 'transparent', color: sTab === k ? T.tw : T.tw3, transition: 'all 0.15s', fontSize: 11.5, fontWeight: 700 }}>
                                <I size={12} />{l}
                            </Box>
                        ))}
                    </Stack>
                    <Box className="qe4-dark-scroll" sx={{ flex: 1, overflowY: 'auto', pb: 1 }}>
                        {sTab === 'fields' ? (<>
                            <Typography sx={{ fontSize: 9, fontWeight: 800, color: T.tw3, letterSpacing: 2, px: 2, pt: 1, pb: 0.5 }}>MERCHANT</Typography>
                            {Object.entries(mG).map(([c, fs]) => renderCat(c, fs, 'merchant'))}
                            <Divider sx={{ bgcolor: 'rgba(255,255,255,0.04)', my: 1, mx: 2 }} />
                            <Typography sx={{ fontSize: 9, fontWeight: 800, color: T.tw3, letterSpacing: 2, px: 2, pb: 0.5 }}>TRANSACTION</Typography>
                            {Object.entries(tG).map(([c, fs]) => renderCat(c, fs, 'transaction'))}
                            <Divider sx={{ bgcolor: 'rgba(255,255,255,0.04)', my: 1, mx: 2 }} />
                            <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ px: 2, pb: 0.5 }}>
                                <Stack direction="row" alignItems="center" spacing={0.75}>
                                    <Typography sx={{ fontSize: 9, fontWeight: 800, color: T.tw3, letterSpacing: 2 }}>MEASURES</Typography>
                                    {calcMeasures.length > 0 && <Box sx={{ fontSize: 8.5, fontWeight: 800, px: 0.6, py: '1px', borderRadius: '5px', color: '#c084fc', bgcolor: 'rgba(192,132,252,0.12)' }}>{calcMeasures.length} custom</Box>}
                                </Stack>
                                <Box onClick={() => openStudio()} title="New measure (aggregation or calculated)"
                                    sx={{ display: 'flex', alignItems: 'center', gap: 0.25, cursor: 'pointer', color: '#c084fc', '&:hover': { opacity: 0.8 } }}>
                                    <Sparkles size={11} /><Typography sx={{ fontSize: 9, fontWeight: 800, letterSpacing: 0.5 }}>NEW</Typography>
                                </Box>
                            </Stack>
                            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: '5px', px: 1.5, pb: 1 }}>
                                {(catalog.measures || []).map(m => <SideMeasChip key={m.key} measure={m} />)}
                                {calcMeasures.map(m => (
                                    <Box key={m.key} draggable
                                        onDragStart={e => { DRAG_CTX = { zone: null, item: { ...m, source: 'measure', calc: true }, consumed: false }; e.dataTransfer.setData('text/plain', JSON.stringify({ ...m, source: 'measure', calc: true })); dragGhost(e, m.label, m.kind === 'agg' ? '#38bdf8' : m.kind === 'time' ? '#f59e0b' : '#c084fc'); }}
                                        onDragEnd={() => { DRAG_CTX = null; }}
                                        onClick={() => openStudio(m)}
                                        title={m.kind === 'agg' ? `${AGG_LABELS[m.agg] || m.agg} of ${m.column}${m.filterField ? ` where ${m.filterField} ∈ (${(m.filterValues || []).length})` : ''} · click to edit` : m.kind === 'time' ? `${m.base} · ${m.comparison} · ${m.mode} · click to edit` : `${m.formula || ''} · click to edit`}
                                        sx={{ display: 'inline-flex', alignItems: 'center', gap: '5px', px: '9px', py: '4.5px', borderRadius: '6px', fontSize: 11.5, fontWeight: 600, cursor: 'pointer', userSelect: 'none', whiteSpace: 'nowrap', color: m.kind === 'agg' ? '#38bdf8' : m.kind === 'time' ? '#f59e0b' : '#c084fc', bgcolor: m.kind === 'agg' ? 'rgba(56,189,248,0.08)' : m.kind === 'time' ? 'rgba(245,158,11,0.08)' : 'rgba(192,132,252,0.08)', border: `1px solid ${m.kind === 'agg' ? 'rgba(56,189,248,0.22)' : m.kind === 'time' ? 'rgba(245,158,11,0.24)' : 'rgba(192,132,252,0.20)'}`, '&:hover': { filter: 'brightness(1.06)' } }}>
                                        {m.kind === 'agg' ? <Sigma size={11} style={{ opacity: 0.8 }} /> : m.kind === 'time' ? <Clock size={11} style={{ opacity: 0.85 }} /> : <span style={{ opacity: 0.75, fontStyle: 'italic', fontWeight: 800 }}>ƒ</span>}{m.label}
                                        <Box component="span" onClick={e => { e.stopPropagation(); if (m.shared && m.masterId) { explorerApi.deleteMaster(m.masterId).then(() => setMasterItems(p => p.filter(x => x.id !== m.masterId))).catch(() => {}); } setCalcMeasures(p => p.filter(x => x.key !== m.key)); }} sx={{ display: 'flex', opacity: 0.45, '&:hover': { opacity: 1 } }}><X size={10} /></Box>
                                    </Box>
                                ))}
                            </Box>
                        </>) : (
                            <Box sx={{ px: 1 }}>
                                {!views.length ? <Box sx={{ textAlign: 'center', py: 5 }}><Bookmark size={24} color={T.tw3} style={{ opacity: 0.3 }} /><Typography sx={{ fontSize: 12, color: T.tw3, mt: 1 }}>No saved views</Typography></Box>
                                    : views.map(v => (
                                        <Box key={v.id} onClick={() => loadV(v)} sx={{ display: 'flex', alignItems: 'center', gap: 0.75, px: 1, py: 0.65, borderRadius: '7px', cursor: 'pointer', mb: '2px', bgcolor: activeView === v.id ? T.sidebarActive : 'transparent', border: `1px solid ${activeView === v.id ? `${T.primary}25` : 'transparent'}`, '&:hover': { bgcolor: T.sidebarHover }, transition: 'all 0.12s' }}>
                                            {v.isDefault ? <Star size={11} color="#f59e0b" fill="#f59e0b" /> : <Bookmark size={11} color={T.tw3} />}
                                            <Typography sx={{ fontSize: 12, fontWeight: activeView === v.id ? 700 : 500, color: activeView === v.id ? T.tw : T.tw2, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v.name}</Typography>
                                            <Box component="span" onClick={e => { e.stopPropagation(); delV(v.id); }} sx={{ display: 'flex', opacity: 0.2, '&:hover': { opacity: 1 }, cursor: 'pointer' }}><X size={11} color={T.rose} /></Box>
                                        </Box>
                                    ))}
                            </Box>
                        )}
                    </Box>
                </Box>
            </Collapse>

            {/* ████ WORKSPACE ████ */}
            <Box sx={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
                {/* TOOLBAR */}
                <Paper elevation={0} sx={{ px: 2, py: 0.75, borderRadius: 0, borderBottom: `1px solid ${T.cardBorder}`, display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
                    <Tooltip title={sideOpen ? 'Collapse' : 'Expand'} arrow>
                        <IconButton size="small" onClick={() => setSideOpen(p => !p)} sx={{ borderRadius: '7px', bgcolor: '#f4f5f7' }}>
                            {sideOpen ? <Minimize2 size={14} color={T.td2} /> : <Maximize2 size={14} color={T.td2} />}
                        </IconButton>
                    </Tooltip>
                    <Divider orientation="vertical" flexItem sx={{ mx: 0.25 }} />
                    <MiniDatePicker startDate={sd} endDate={ed} onChange={(s, e) => { setSd(s); setEd(e); }} />

                    <Button size="small" onClick={() => setSelPanel(p => !p)} startIcon={<Filter size={12} />}
                        sx={{ textTransform: 'none', fontWeight: 700, fontSize: 11.5, height: 30, borderRadius: '7px', px: 1.5, bgcolor: nF > 0 ? `${SEL}08` : '#f4f5f7', color: nF > 0 ? SEL : T.td2, border: `1px solid ${nF > 0 ? `${SEL}20` : 'transparent'}`, '&:hover': { bgcolor: nF > 0 ? `${SEL}12` : '#eaebef' } }}>
                        Selections{nF > 0 && <Chip size="small" label={nF} sx={{ ml: 0.5, height: 18, fontSize: 9, fontWeight: 800, bgcolor: SEL, color: 'white', '& .MuiChip-label': { px: 0.5 } }} />}
                    </Button>

                    {selHistory.length > 0 && (
                        <Tooltip title="Step Back" arrow><IconButton size="small" onClick={stepBack} sx={{ color: T.td3, '&:hover': { color: T.primary } }}><ChevronLeft size={14} /></IconButton></Tooltip>
                    )}
                    <Tooltip title="Clear All" arrow><IconButton size="small" onClick={clearAll} sx={{ color: T.td3 }}><RotateCcw size={13} /></IconButton></Tooltip>

                    <Box sx={{ flex: 1 }} />

                    {/* Chart Type Picker — dropdown with groups */}
                    <Button size="small" onClick={e => setChartMenu(e.currentTarget)} endIcon={<ChevronDown size={12} />}
                        sx={{ textTransform: 'none', fontWeight: 700, fontSize: 11.5, height: 30, borderRadius: '7px', px: 1.5, bgcolor: '#f4f5f7', color: T.td2, border: '1px solid transparent', '&:hover': { bgcolor: '#eaebef' } }}>
                        {(() => { const ct = CHART_TYPES.find(c => c.key === chartType); const I = ct?.icon || BarChart3; return <><I size={13} style={{ marginRight: 4 }} />{ct?.label || 'Chart'}</>; })()}
                    </Button>
                    <Menu anchorEl={chartMenu} open={!!chartMenu} onClose={() => setChartMenu(null)}
                        PaperProps={{ sx: { borderRadius: '12px', mt: 0.5, minWidth: 200, boxShadow: T.shadowLg, border: `1px solid ${T.cardBorder}` } }}>
                        <Typography sx={{ px: 2, py: 0.5, fontSize: 10, fontWeight: 800, color: T.td3, textTransform: 'uppercase', letterSpacing: 1 }}>Basic</Typography>
                        {CHART_TYPES.filter(c => c.group === 'basic').map(ct => {
                            const I = ct.icon;
                            return <MenuItem key={ct.key} onClick={() => { setChartType(ct.key); setChartMenu(null); }} selected={chartType === ct.key}
                                sx={{ fontSize: 13, fontWeight: chartType === ct.key ? 700 : 500, color: chartType === ct.key ? T.primary : T.td, borderRadius: '6px', mx: 0.5, '&.Mui-selected': { bgcolor: `${T.primary}08` } }}>
                                <I size={14} style={{ marginRight: 8, opacity: 0.6 }} />{ct.label}
                            </MenuItem>;
                        })}
                        <Divider sx={{ my: 0.5 }} />
                        <Typography sx={{ px: 2, py: 0.5, fontSize: 10, fontWeight: 800, color: T.td3, textTransform: 'uppercase', letterSpacing: 1 }}>Advanced</Typography>
                        {CHART_TYPES.filter(c => c.group === 'advanced').map(ct => {
                            const I = ct.icon;
                            return <MenuItem key={ct.key} onClick={() => { setChartType(ct.key); setChartMenu(null); }} selected={chartType === ct.key}
                                sx={{ fontSize: 13, fontWeight: chartType === ct.key ? 700 : 500, color: chartType === ct.key ? T.primary : T.td, borderRadius: '6px', mx: 0.5, '&.Mui-selected': { bgcolor: `${T.primary}08` } }}>
                                <I size={14} style={{ marginRight: 8, opacity: 0.6 }} />{ct.label}
                            </MenuItem>;
                        })}
                    </Menu>

                    {/* Color Theme Picker */}
                    <Tooltip title="Color Theme" arrow>
                        <Box sx={{ position: 'relative' }}>
                            <IconButton size="small" onClick={e => e.currentTarget.nextSibling?.classList.toggle('qe4-theme-open')} sx={{ color: T.td3, '&:hover': { color: T.purple } }}>
                                <Palette size={15} />
                            </IconButton>
                            <Paper className="qe4-theme-panel" elevation={8} sx={{ position: 'absolute', right: 0, top: '110%', p: 1.5, borderRadius: '12px', width: 240, zIndex: 100, display: 'none', '&.qe4-theme-open': { display: 'block' }, border: `1px solid ${T.cardBorder}` }}>
                                <Typography sx={{ fontSize: 11, fontWeight: 800, color: T.td3, textTransform: 'uppercase', letterSpacing: 1, mb: 1 }}>Color Theme</Typography>
                                <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 0.75 }}>
                                    {Object.entries(THEMES).map(([k, t]) => (
                                        <Box key={k} onClick={() => setTheme(k)} sx={{ p: 0.75, borderRadius: '8px', cursor: 'pointer', border: `2px solid ${theme === k ? T.primary : 'transparent'}`, bgcolor: theme === k ? `${T.primary}06` : 'transparent', '&:hover': { bgcolor: '#f4f5f7' }, transition: 'all 0.15s' }}>
                                            <Stack direction="row" spacing={0.25} sx={{ mb: 0.5 }}>
                                                {t.colors.slice(0, 4).map((c, i) => <Box key={i} sx={{ width: 12, height: 12, borderRadius: '50%', bgcolor: c }} />)}
                                            </Stack>
                                            <Typography sx={{ fontSize: 9.5, fontWeight: 700, color: theme === k ? T.primary : T.td2 }}>{t.name}</Typography>
                                        </Box>
                                    ))}
                                </Box>
                            </Paper>
                        </Box>
                    </Tooltip>

                    <Paper elevation={0} sx={{ display: 'flex', bgcolor: '#f4f5f7', borderRadius: '8px', p: '2.5px', gap: '1px' }}>
                        {[{ v: 'both', i: Eye, t: 'Both' }, { v: 'chart', i: BarChart3, t: 'Chart' }, { v: 'table', i: Table2, t: 'Table' }, { v: 'pivot', i: Columns, t: 'Pivot (2 dims)' }].map(({ v, i, t }) =>
                            <TogBtn key={v} icon={i} active={viewMode === v} onClick={() => setViewMode(v)} tip={t} />
                        )}
                    </Paper>
                    <Paper elevation={0} sx={{ display: 'flex', bgcolor: '#f4f5f7', borderRadius: '8px', p: '2.5px', gap: '1px' }}>
                        <TogBtn icon={Crosshair} active={refLinesOn} onClick={() => setRefLinesOn(p => !p)} tip="Reference lines (avg / max / min)" />
                        <TogBtn icon={TrendingUp} active={trendOn} onClick={() => setTrendOn(p => !p)} tip="Trend line (line chart)" />
                        <TogBtn icon={Sparkles} active={condFmt !== 'off'} onClick={() => setCondFmt(p => p === 'off' ? 'heat' : p === 'heat' ? 'bars' : 'off')} tip={`Table formatting: ${condFmt === 'off' ? 'off → heat cells' : condFmt === 'heat' ? 'heat → data bars' : 'bars → off'}`} />
                    </Paper>

                    <Button size="small" onClick={run} disabled={!dims.length || qLoad} variant="contained" disableElevation
                        startIcon={qLoad ? <CircularProgress size={12} sx={{ color: 'white' }} /> : <Play size={12} fill="white" />}
                        sx={{ textTransform: 'none', fontWeight: 800, fontSize: 12, height: 34, borderRadius: '9px', px: 2.5, bgcolor: T.primary, boxShadow: `0 2px 6px -2px ${T.primary}55`, transition: 'background-color 0.15s ease, box-shadow 0.15s ease', '&:hover': { bgcolor: T.primaryDark, boxShadow: `0 4px 10px -3px ${T.primary}66` }, '&:disabled': { bgcolor: '#e2e8f0', color: '#94a3b8', boxShadow: 'none' } }}>
                        Run
                    </Button>
                    <Tooltip title="Threshold alerts" arrow><IconButton size="small" onClick={() => { setAlForm(f => ({ ...f, measureKey: (meas[0]?.key || catalog.measures?.[0]?.key || 'total_volume') })); setAlertMsg(''); setAlertDlg(true); }} sx={{ color: T.td3, '&:hover': { color: T.primary } }}><Bell size={15} /></IconButton></Tooltip>
                    <Tooltip title={dims.length ? 'Pin to sheet' : 'Build a viz first'} arrow><span><IconButton size="small" onClick={pinCurrent} disabled={!dims.length} sx={{ color: T.td3, '&:hover': { color: T.green } }}><LayoutGrid size={15} /></IconButton></span></Tooltip>
                    <Tooltip title={copied ? 'Link copied!' : 'Copy share link'} arrow><IconButton size="small" onClick={copyLink} sx={{ color: copied ? T.green : T.td3, '&:hover': { color: T.primary } }}><Share2 size={15} /></IconButton></Tooltip>
                    <Tooltip title="Save View" arrow><IconButton size="small" onClick={() => setSaveDlg(true)} sx={{ color: T.td3, '&:hover': { color: T.primary } }}><Save size={15} /></IconButton></Tooltip>
                    <Tooltip title="Save as Template" arrow><IconButton size="small" onClick={() => setTplDlg(true)} sx={{ color: T.td3, '&:hover': { color: T.purple } }}><Bookmark size={15} /></IconButton></Tooltip>
                    <Tooltip title="Export CSV" arrow><IconButton size="small" onClick={exportCSV} disabled={!rows.length} sx={{ color: T.td3 }}><Download size={15} /></IconButton></Tooltip>
                    <Tooltip title="Export Excel" arrow><IconButton size="small" onClick={exportExcel} disabled={!rows.length} sx={{ color: T.td3 }}><Table2 size={15} /></IconButton></Tooltip>
                </Paper>

                {/* DROP ZONES */}
                <Box sx={{ px: 2, py: 1, bgcolor: 'white', borderBottom: `1px solid ${T.cardBorder}`, display: 'flex', gap: 1.5 }}>
                    <Box sx={{ flex: 2 }}><DropZone zoneId="dims" label="Dimensions" items={dims} onChange={setDims} validate={p => (p && p.key && p.source !== 'measure' && !p.calc) ? p : null} accent={T.primary} emptyText="Drag fields here · double-click a field to add" /></Box>
                    <Box sx={{ flex: 1 }}><DropZone zoneId="meas" label="Measures" items={meas} onChange={setMeas} validate={p => (p && p.key && (p.source === 'measure' || p.calc)) ? p : null} accent={T.green} emptyText="Auto: Count, Vol, MSF"
                        renderItem={(f, i, rm) => <ZoneMeasChip key={f.key + i} measure={f} onRemove={rm} />} /></Box>
                </Box>

                {/* SELECTION PANEL */}
                <Collapse in={selPanel}>
                    <Box sx={{ px: 2, py: 1.5, bgcolor: '#fafbfc', borderBottom: `1px solid ${T.cardBorder}` }}>
                        {nF > 0 && (
                            <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap sx={{ mb: 1.5 }}>
                                {Object.entries(filters).flatMap(([fk, vs]) => vs.map(v =>
                                    <Chip key={`${fk}-${v}`} size="small" label={<><span style={{ opacity: 0.5 }}>{fk.replace(/_/g,' ')}: </span>{v}</>}
                                        onDelete={() => toggleFV(fk, v)} sx={{ height: 26, fontSize: 11, fontWeight: 600, bgcolor: `${SEL}08`, color: SEL, border: `1px solid ${SEL}18`, '& .MuiChip-deleteIcon': { color: SEL, fontSize: 14 } }} />
                                ))}
                            </Stack>
                        )}
                        <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                            {filterableFields.map(f => {
                                const on = openBoxes.includes(f.key); const has = (filters[f.key] || []).length > 0;
                                return <Chip key={f.key} size="small" label={f.label} clickable onClick={() => toggleBox(f.key)}
                                    sx={{ height: 26, fontSize: 11, fontWeight: 600, bgcolor: on ? T.primary : has ? `${SEL}08` : 'white', color: on ? 'white' : has ? SEL : T.td2, border: `1px solid ${on ? T.primary : has ? `${SEL}20` : T.cardBorder}`, '&:hover': { bgcolor: on ? T.primaryLight : has ? `${SEL}12` : '#f4f5f7' } }} />;
                            })}
                        </Stack>
                        {openBoxes.length > 0 && (
                            <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mt: 1.25, mb: 0.25 }}>
                                <Typography sx={{ fontSize: 9.5, fontWeight: 800, color: T.td3, letterSpacing: 0.5 }}>ASSOCIATIVE</Typography>
                                <Stack direction="row" spacing={0.5} alignItems="center"><Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: '#009845' }} /><Typography sx={{ fontSize: 9.5, color: T.td3 }}>Selected</Typography></Stack>
                                <Stack direction="row" spacing={0.5} alignItems="center"><Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: '#fff', border: '1px solid #cbd5e1' }} /><Typography sx={{ fontSize: 9.5, color: T.td3 }}>Available</Typography></Stack>
                                <Stack direction="row" spacing={0.5} alignItems="center"><Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: '#d1d5db' }} /><Typography sx={{ fontSize: 9.5, color: T.td3 }}>Excluded</Typography></Stack>
                            </Stack>
                        )}
                        {openBoxes.length > 0 && (
                            <Stack direction="row" spacing={1.5} sx={{ mt: 1.5, overflowX: 'auto', pb: 0.5 }} className="qe4-scroll">
                                {openBoxes.map(fk => {
                                    const field = filterableFields.find(f => f.key === fk); if (!field) return null;
                                    return <FilterBox key={fk} title={field.label} items={boxItems(fk)} loading={boxItems(fk) === null} selected={filters[fk] || []} onToggle={v => toggleFV(fk, v)} onClear={() => clearFK(fk)} color={gc(field.category).color} />;
                                })}
                            </Stack>
                        )}
                    </Box>
                </Collapse>

                {qLoad && <LinearProgress sx={{ height: 2.5, '& .MuiLinearProgress-bar': { background: `linear-gradient(90deg, ${T.primary}, ${T.green})` } }} />}

                {/* ═══ RESULTS ═══ */}
                <Box className="qe4-scroll" sx={{ flex: 1, overflow: 'auto', p: 2.5 }}>

                    {/* Empty State */}
                    {!rows.length && !qLoad && (
                        <Fade in timeout={500}>
                            <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: '62%', py: 4 }}>
                                <Box className="qe4-rise" sx={{ width: 58, height: 58, borderRadius: '16px', mb: 2.25, display: 'flex', alignItems: 'center', justifyContent: 'center', bgcolor: T.primaryGhost, border: `1px solid ${T.primaryBorder}`, color: T.primary }}>
                                    <Sparkles size={25} />
                                </Box>
                                <Typography className="qe4-rise" sx={{ fontSize: 22, fontWeight: 800, color: T.td, letterSpacing: '-0.02em', mb: 0.75 }}>Build your analysis</Typography>
                                <Typography className="qe4-rise" sx={{ fontSize: 13, color: T.td2, mb: 3.25, maxWidth: 360, textAlign: 'center', lineHeight: 1.6 }}>Drop fields into the wells above, then run — or start from a pattern below.</Typography>
                                {/* Step flow — the real three-step sequence */}
                                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" justifyContent="center" useFlexGap className="qe4-rise" sx={{ mb: 3.5 }}>
                                    {[
                                        { n: '1', label: 'Add dimensions', color: T.primary },
                                        { n: '2', label: 'Add measures', color: T.green },
                                        { n: '3', label: 'Run', color: T.primary },
                                    ].map((s, i, arr) => (
                                        <React.Fragment key={s.n}>
                                            <Stack direction="row" spacing={0.75} alignItems="center" sx={{ pl: 0.5, pr: 1.25, py: 0.5, borderRadius: '999px', bgcolor: T.surfaceMuted, border: `1px solid ${T.cardBorder}` }}>
                                                <Box sx={{ width: 17, height: 17, borderRadius: '50%', bgcolor: s.color, color: '#fff', fontSize: 9.5, fontWeight: 800, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{s.n}</Box>
                                                <Typography sx={{ fontSize: 12, fontWeight: 700, color: T.td2 }}>{s.label}</Typography>
                                            </Stack>
                                            {i < arr.length - 1 && <Box sx={{ width: 14, height: 1.5, borderRadius: 2, bgcolor: T.cardBorder }} />}
                                        </React.Fragment>
                                    ))}
                                </Stack>
                                <Typography sx={{ fontSize: 10, fontWeight: 800, color: T.td3, letterSpacing: 1.8, mb: 1.5 }}>QUICK START</Typography>
                                {/* Quick-start cards — clean, precise */}
                                <Stack direction="row" spacing={1.75} flexWrap="wrap" justifyContent="center" useFlexGap>
                                    {[
                                        { key: 'city', icon: MapPin, title: 'Volume by city', sub: 'Geographic breakdown', color: T.primary },
                                        { key: 'card_scheme', icon: CreditCard, title: 'Card scheme mix', sub: 'Visa, Mastercard, Amex', color: T.green },
                                        { key: 'payment_month', icon: TrendingUp, title: 'Monthly trend', sub: 'Volume over time', color: T.amber },
                                    ].map(({ key, icon: I, title, sub, color }, i) => (
                                        <Card key={key} elevation={0} onClick={() => quickStart(key)} className="qe4-rise qe4-tile"
                                            sx={{ width: 192, cursor: 'pointer', borderRadius: `${T.radius + 1}px`, border: `1px solid ${T.cardBorder}`, bgcolor: '#fff', animationDelay: `${i * 70}ms`, '&:hover .qs-arrow': { opacity: 1, transform: 'translateX(0)' } }}>
                                            <CardContent sx={{ p: '18px !important', position: 'relative' }}>
                                                <Box sx={{ width: 38, height: 38, borderRadius: '11px', display: 'flex', alignItems: 'center', justifyContent: 'center', bgcolor: `${color}12`, color, mb: 1.5 }}><I size={19} /></Box>
                                                <Typography sx={{ fontSize: 13.5, fontWeight: 700, color: T.td, mb: 0.25 }}>{title}</Typography>
                                                <Typography sx={{ fontSize: 11.5, color: T.td3, lineHeight: 1.4 }}>{sub}</Typography>
                                                <Box className="qs-arrow" sx={{ position: 'absolute', right: 14, top: 16, opacity: 0, transform: 'translateX(-5px)', transition: 'all 0.2s ease', color: T.td3, display: 'flex' }}><ArrowRight size={15} /></Box>
                                            </CardContent>
                                        </Card>
                                    ))}
                                </Stack>
                            </Box>
                        </Fade>
                    )}

                    {/* Results */}
                    {rows.length > 0 && !qLoad && (
                        <Stack spacing={2.5} className="qe4-fade">
                            {/* KPIs */}
                            <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap alignItems="center">
                                <KpiCard label="Results" value={rows.length.toLocaleString()} color={T.primary} icon={Database} delay={0} />
                                {mK.slice(0, 4).map((k, i) => (
                                    <KpiCard key={k} label={(meas.find(mm => mm.key === k)?.label) || k.replace(/_/g, ' ')} value={fmtKpi(rows.reduce((s, r) => s + (Number(r[k]) || 0), 0), k)} color={palette[i + 1]} delay={(i + 1) * 60} icon={[TrendingUp, DollarSign, BarChart3, Activity][i]} />
                                ))}
                                {qTime != null && <Stack direction="row" spacing={0.4} alignItems="center" sx={{ color: T.td3, ml: 1 }}><Zap size={12} /><Typography sx={{ fontSize: 11, fontWeight: 700 }}>{(qTime / 1000).toFixed(2)}s</Typography></Stack>}
                            </Stack>

                            {/* DRILL BREADCRUMB */}
                            {drill.length > 0 && (
                                <Stack direction="row" spacing={0.5} alignItems="center" flexWrap="wrap" useFlexGap sx={{ gap: 0.5 }}>
                                    <Chip size="small" label="All" clickable onClick={() => drillTo(0)}
                                        sx={{ height: 24, fontSize: 11, fontWeight: 700, bgcolor: '#f4f5f7', color: T.td2, '&:hover': { bgcolor: `${T.primary}10`, color: T.primary } }} />
                                    {drill.map((d, i) => (
                                        <React.Fragment key={d.key + d.value}>
                                            <ChevronRight size={13} color={T.td3} />
                                            <Chip size="small" label={`${d.label}: ${d.value}`} clickable onClick={() => drillTo(i + 1)}
                                                sx={{ height: 24, fontSize: 11, fontWeight: 700, bgcolor: i === drill.length - 1 ? `${T.primary}10` : '#f4f5f7', color: i === drill.length - 1 ? T.primary : T.td2, border: i === drill.length - 1 ? `1px solid ${T.primary}25` : '1px solid transparent' }} />
                                        </React.Fragment>
                                    ))}
                                    <ChevronRight size={13} color={T.td3} />
                                    <Typography sx={{ fontSize: 11.5, fontWeight: 800, color: T.td }}>{activeDims[0]?.label}</Typography>
                                </Stack>
                            )}

                            {/* CHART — also a drop target: drop any field/measure straight onto it */}
                            {(viewMode === 'chart' || viewMode === 'both') && cData.length > 0 && (
                                <Card elevation={0} className={`qe4-tile ${chartDropOver ? 'qe4-droptarget' : ''}`}
                                    onDragOver={e => { e.preventDefault(); setChartDropOver(true); }}
                                    onDragLeave={() => setChartDropOver(false)}
                                    onDrop={e => { e.preventDefault(); setChartDropOver(false); if (DRAG_CTX) DRAG_CTX.consumed = true; const p = DRAG_CTX?.item; if (!p || DRAG_CTX?.zone) return; (p.source === 'measure' || p.calc) ? addMeas(p) : addDim(p); }}
                                    sx={{ borderRadius: `${T.radius + 4}px`, border: `1px solid ${T.cardBorder}`, overflow: 'hidden' }}>
                                    <CardContent sx={{ p: '24px !important' }}>
                                        <ChartRenderer type={chartType} data={cData} measureKeys={mK} palette={palette} onChartClick={onChartClick} height={360} measFmt={measFmt} refLines={refLinesOn} trend={trendOn} rawRows={sorted} dimKeys={activeDims.map(d => d.key)} />
                                        <Typography sx={{ fontSize: 10.5, color: T.td3, textAlign: 'center', mt: 1.5, fontStyle: 'italic' }}>{activeDims.length > 1 ? 'Click to drill down · drop fields here to add' : 'Click chart elements to cross-filter · drop fields here to add'}</Typography>
                                    </CardContent>
                                </Card>
                            )}

                            {/* PIVOT */}
                            {viewMode === 'pivot' && (
                                <Card elevation={0} className="qe4-tile" sx={{ borderRadius: `${T.radius + 4}px`, border: `1px solid ${T.cardBorder}`, overflow: 'hidden' }}>
                                    <Box sx={{ px: 2, py: 1.25, borderBottom: `1px solid ${T.cardBorder}`, display: 'flex', alignItems: 'center', gap: 1 }}>
                                        <Columns size={14} color={T.primary} />
                                        <Typography sx={{ fontSize: 13, fontWeight: 800, color: T.td }}>Pivot — {activeDims[0]?.label || '…'} × {activeDims[1]?.label || 'add 2nd dimension'}</Typography>
                                        <Box sx={{ flex: 1 }} />
                                        <Typography sx={{ fontSize: 10.5, color: T.td3 }}>{mK[0]?.replace(/_/g, ' ')}</Typography>
                                    </Box>
                                    <PivotTable rows={sorted} dims={activeDims} measureKey={mK[0]} fmtVal={fmtVal} onCellClick={v => activeDims[0] && toggleFV(activeDims[0].key, String(v))} />
                                </Card>
                            )}

                            {/* TABLE */}
                            {(viewMode === 'table' || viewMode === 'both') && (
                                <Card elevation={0} className="qe4-tile" sx={{ borderRadius: `${T.radius + 4}px`, border: `1px solid ${T.cardBorder}`, overflow: 'hidden' }}>
                                    <Box className="qe4-scroll" sx={{ overflowX: 'auto', maxHeight: 480 }}>
                                        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                                            <thead>
                                                <tr>{Object.keys(rows[0] || {}).map((col, ci) => (
                                                    <th key={col} onClick={() => { setSortCol(col); setSortDir(p => sortCol === col ? (p === 'asc' ? 'desc' : 'asc') : 'desc'); }}
                                                        style={{ padding: '12px 16px', textAlign: ci < activeDims.length ? 'left' : 'right', fontSize: 10.5, fontWeight: 800, textTransform: 'uppercase', letterSpacing: 0.8, color: sortCol === col ? T.primary : T.td3, borderBottom: `2px solid ${sortCol === col ? T.primary : 'rgba(0,0,0,0.06)'}`, background: '#fafbfc', whiteSpace: 'nowrap', position: 'sticky', top: 0, zIndex: 1, cursor: 'pointer', userSelect: 'none' }}>
                                                        <Stack direction="row" spacing={0.5} alignItems="center" justifyContent={ci < activeDims.length ? 'flex-start' : 'flex-end'}>
                                                            <span>{col.replace(/_/g, ' ')}</span>
                                                            {sortCol === col && <span>{sortDir === 'asc' ? '↑' : '↓'}</span>}
                                                        </Stack>
                                                    </th>
                                                ))}</tr>
                                            </thead>
                                            <tbody>
                                                {sorted.map((row, ri) => (
                                                    <tr key={ri} className="qe4-row" onClick={() => { if (activeDims.length) { const v = row[activeDims[0].key]; if (v != null) onChartClick({ name: String(v) }); } }} style={{ cursor: activeDims.length ? 'pointer' : 'default' }}>
                                                        {Object.entries(row).map(([k, val], ci) => {
                                                            const isSel = activeDims.length && ci === 0 && filters[activeDims[0].key]?.includes(String(val));
                                                            const isNum = typeof val === 'number' && ci >= activeDims.length;
                                                            const st = isNum ? colStats[k] : null;
                                                            const frac = st && st.mx > st.mn ? (Number(val) - st.mn) / (st.mx - st.mn) : (st ? 1 : 0);
                                                            let cellBg = isSel ? `${SEL}05` : 'transparent';
                                                            if (isNum && condFmt === 'heat') cellBg = `${T.primary}${Math.round((0.04 + frac * 0.30) * 255).toString(16).padStart(2, '0')}`;
                                                            const barBg = isNum && condFmt === 'bars' ? `linear-gradient(to left, ${T.primary}2e ${Math.round(frac * 100)}%, transparent ${Math.round(frac * 100)}%)` : null;
                                                            return (
                                                                <td key={ci} style={{ padding: '9px 16px', whiteSpace: 'nowrap', borderBottom: '1px solid rgba(0,0,0,0.03)', fontSize: 12.5, fontVariantNumeric: 'tabular-nums', fontWeight: ci < activeDims.length ? 600 : (isNum && condFmt === 'heat' && frac > 0.75 ? 700 : 400), color: isSel ? SEL : ci < activeDims.length ? T.td : T.td2, textAlign: ci < activeDims.length ? 'left' : 'right', background: barBg || cellBg, borderLeft: isSel ? `3px solid ${SEL}` : '3px solid transparent' }}>
                                                                    {typeof val === 'number' ? fmtVal(val, k) : (val ?? '—')}
                                                                </td>
                                                            );
                                                        })}
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                    </Box>
                                    <Box sx={{ px: 2, py: 1, borderTop: `1px solid ${T.cardBorder}`, display: 'flex', justifyContent: 'space-between' }}>
                                        <Typography sx={{ fontSize: 11, color: T.td3, fontWeight: 600 }}>
                                            {sorted.length.toLocaleString()} rows
                                            {nF > 0 && <span style={{ color: SEL, marginLeft: 8 }}>● {nF} filter{nF > 1 ? 's' : ''} active</span>}
                                        </Typography>
                                        <Typography sx={{ fontSize: 11, color: T.td3, fontStyle: 'italic' }}>Click rows to cross-filter</Typography>
                                    </Box>
                                </Card>
                            )}
                        </Stack>
                    )}

                    {/* ═══ SHEET (pinned objects) ═══ */}
                    {sheet.length > 0 && (
                        <Box sx={{ mt: 3 }}>
                            <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1.5 }}>
                                <LayoutGrid size={15} color={T.primary} />
                                <Typography sx={{ fontSize: 14, fontWeight: 800, color: T.td }}>Sheet</Typography>
                                <Chip size="small" label={`${sheet.length} object${sheet.length > 1 ? 's' : ''}`} sx={{ height: 20, fontSize: 10, fontWeight: 700, bgcolor: `${T.primary}12`, color: T.primary }} />
                                <Box sx={{ flex: 1 }} />
                                <Typography sx={{ fontSize: 11, color: T.td3, fontStyle: 'italic' }}>All objects react to the selections above</Typography>
                            </Stack>
                            <SheetGrid className="layout" layout={gridLayout} cols={12} rowHeight={30} margin={[16, 16]} containerPadding={[0, 0]} isDraggable isResizable draggableHandle=".qe4-drag" onLayoutChange={l => setSheetLayout(l)} compactType="vertical" useCSSTransforms>
                                {sheet.map(o => {
                                    const sd2 = sheetData[o.id] || {};
                                    const rws = sd2.rows || [];
                                    const mk = objMeasureKeys(rws, o.dims, o.meas);
                                    const cd = toChartData(rws, o.dims, o.meas);
                                    const CTIcon = (CHART_TYPES.find(c => c.key === o.vizType)?.icon) || BarChart3;
                                    const chH = tileChartH(o.id);
                                    return (
                                        <div key={o.id}>
                                            <Card elevation={0} className="qe4-tile" sx={{ height: '100%', display: 'flex', flexDirection: 'column', borderRadius: `${T.radius + 4}px`, border: `1px solid ${T.cardBorder}`, overflow: 'hidden' }}
                                                onDragOver={e => { if (DRAG_CTX && !DRAG_CTX.zone) e.preventDefault(); }}
                                                onDrop={e => { e.preventDefault(); if (DRAG_CTX && !DRAG_CTX.zone) { DRAG_CTX.consumed = true; dropOnTile(o.id, DRAG_CTX.item); } }}>
                                                <Box sx={{ px: 1.5, py: 1.25, borderBottom: `1px solid ${T.cardBorder}`, display: 'flex', alignItems: 'center', gap: 0.75 }}>
                                                    <Box className="qe4-drag" sx={{ display: 'flex', alignItems: 'center', color: T.td3, '&:hover': { color: T.td2 } }}><GripVertical size={14} /></Box>
                                                    <CTIcon size={14} color={T.primary} />
                                                    <TextField variant="standard" value={o.title} onChange={e => renameObj(o.id, e.target.value)} InputProps={{ disableUnderline: true, sx: { fontSize: 13, fontWeight: 700, color: T.td } }} sx={{ flex: 1 }} />
                                                    {sd2.qLoad && <CircularProgress size={13} sx={{ color: T.primary }} />}
                                                    <Tooltip title="Edit in composer" arrow><IconButton size="small" onClick={() => editObj(o.id)} sx={{ color: T.td3 }}><Settings size={13} /></IconButton></Tooltip>
                                                    <Tooltip title="Duplicate" arrow><IconButton size="small" onClick={() => duplicateObj(o.id)} sx={{ color: T.td3 }}><Copy size={13} /></IconButton></Tooltip>
                                                    <Tooltip title="Remove" arrow><IconButton size="small" onClick={() => removeObj(o.id)} sx={{ color: T.td3, '&:hover': { color: T.rose } }}><Trash2 size={13} /></IconButton></Tooltip>
                                                </Box>
                                                <CardContent sx={{ p: '14px !important', flex: 1, minHeight: 0 }}>
                                                    {rws.length && cd.length ? (
                                                        <ChartRenderer type={o.vizType} data={cd} measureKeys={mk} palette={palette} onChartClick={dd => { if (dd) { const val = dd.name || dd?.payload?.name; if (val && o.dims[0]) toggleFV(o.dims[0].key, String(val)); } }} height={chH} measFmt={measFmt} rawRows={rws} dimKeys={o.dims.map(x => x.key)} />
                                                    ) : (
                                                        <Box sx={{ height: chH, display: 'flex', alignItems: 'center', justifyContent: 'center', color: T.td3, fontSize: 12 }}>{sd2.qLoad ? 'Loading…' : 'No data for current selections'}</Box>
                                                    )}
                                                </CardContent>
                                                <Box sx={{ px: 2, py: 0.75, borderTop: `1px solid ${T.cardBorder}`, display: 'flex', justifyContent: 'space-between' }}>
                                                    <Typography sx={{ fontSize: 10.5, color: T.td3 }}>{rws.length.toLocaleString()} rows</Typography>
                                                    <Typography sx={{ fontSize: 10.5, color: T.td3, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: '60%' }}>{o.dims.map(x => x.label).join(' / ')}</Typography>
                                                </Box>
                                            </Card>
                                        </div>
                                    );
                                })}
                            </SheetGrid>
                        </Box>
                    )}
                </Box>
            </Box>

            {/* SAVE VIEW DIALOG */}
            <Dialog open={saveDlg} onClose={() => setSaveDlg(false)} maxWidth="xs" fullWidth PaperProps={{ sx: { borderRadius: '14px', overflow: 'hidden' } }}>
                <Box sx={{ height: 3, background: `linear-gradient(90deg, ${T.primary}, ${T.green})` }} />
                <DialogTitle sx={{ fontWeight: 800, fontSize: 18, pb: 0 }}>Save View</DialogTitle>
                <DialogContent>
                    <Typography sx={{ fontSize: 12.5, color: T.td3, mb: 2.5 }}>Save your current analysis configuration</Typography>
                    <TextField fullWidth label="View Name" value={vName} onChange={e => setVName(e.target.value)} size="small" autoFocus sx={{ mb: 2, '& .MuiInputBase-root': { borderRadius: '8px' } }} />
                    <Stack spacing={0.5}>
                        <FormControlLabel control={<Checkbox checked={vDef} onChange={e => setVDef(e.target.checked)} size="small" sx={{ color: T.amber, '&.Mui-checked': { color: T.amber } }} />} label={<Typography sx={{ fontSize: 12.5 }}>Default view</Typography>} />
                        <FormControlLabel control={<Checkbox checked={vShare} onChange={e => setVShare(e.target.checked)} size="small" sx={{ color: T.purple, '&.Mui-checked': { color: T.purple } }} />} label={<Typography sx={{ fontSize: 12.5 }}>Share with team</Typography>} />
                    </Stack>
                </DialogContent>
                <DialogActions sx={{ px: 3, pb: 2.5 }}>
                    <Button onClick={() => setSaveDlg(false)} sx={{ textTransform: 'none', color: T.td3 }}>Cancel</Button>
                    <Button onClick={saveV} disabled={!vName.trim()} variant="contained" disableElevation sx={{ textTransform: 'none', fontWeight: 800, borderRadius: '8px', px: 3, bgcolor: T.primary, '&:disabled': { bgcolor: '#e2e8f0' } }}>Save</Button>
                </DialogActions>
            </Dialog>

            {/* SAVE TEMPLATE DIALOG */}
            <Dialog open={tplDlg} onClose={() => setTplDlg(false)} maxWidth="xs" fullWidth PaperProps={{ sx: { borderRadius: '14px', overflow: 'hidden' } }}>
                <Box sx={{ p: 0.5, background: `linear-gradient(135deg, ${T.purple}22, ${T.primary}22)` }} />
                <DialogTitle sx={{ fontWeight: 800, fontSize: 18, pb: 0 }}>Save as Report Template</DialogTitle>
                <DialogContent sx={{ pt: 2 }}>
                    <Typography sx={{ fontSize: 12.5, color: T.td3, mb: 2.5 }}>Save this analysis as a reusable report template</Typography>
                    <TextField fullWidth label="Template Name" value={tplName} onChange={e => setTplName(e.target.value)} size="small" sx={{ mb: 2 }} />
                    <TextField fullWidth label="Description" value={tplDesc} onChange={e => setTplDesc(e.target.value)} size="small" multiline rows={2} sx={{ mb: 2 }} />
                    <FormControlLabel control={<Checkbox checked={tplShared} onChange={e => setTplShared(e.target.checked)} size="small" sx={{ color: T.purple, '&.Mui-checked': { color: T.purple } }} />} label={<Typography sx={{ fontSize: 12.5 }}>Share with team</Typography>} />
                    <Divider sx={{ my: 1.5 }} />
                    <FormControlLabel control={<Checkbox checked={schedOn} onChange={e => setSchedOn(e.target.checked)} size="small" sx={{ color: T.primary, '&.Mui-checked': { color: T.primary } }} />} label={<Stack direction="row" alignItems="center" spacing={0.75}><AlarmClock size={15} /><Typography sx={{ fontSize: 12.5, fontWeight: 700 }}>Schedule recurring export</Typography></Stack>} />
                    <Collapse in={schedOn}>
                        <Stack spacing={1.5} sx={{ mt: 1, pl: 0.5 }}>
                            <TextField select fullWidth size="small" label="Frequency" value={schedFreq} onChange={e => setSchedFreq(e.target.value)}>
                                <MenuItem value="DAILY">Daily — 08:00</MenuItem>
                                <MenuItem value="WEEKLY">Weekly — Monday 08:00</MenuItem>
                                <MenuItem value="MONTHLY">Monthly — 1st, 08:00</MenuItem>
                            </TextField>
                            <TextField select fullWidth size="small" label="Format" value={schedFormat} onChange={e => setSchedFormat(e.target.value)}>
                                <MenuItem value="EXCEL">Excel (.xlsx)</MenuItem>
                                <MenuItem value="CSV">CSV (.csv)</MenuItem>
                                <MenuItem value="PDF">PDF (.pdf)</MenuItem>
                            </TextField>
                            <TextField select fullWidth size="small" label="Delivery" value={schedDelivery} onChange={e => setSchedDelivery(e.target.value)}>
                                <MenuItem value="DOWNLOAD_ONLY">Save to server</MenuItem>
                                <MenuItem value="EMAIL">Email recipients</MenuItem>
                            </TextField>
                            {schedDelivery === 'EMAIL' && <TextField fullWidth size="small" label="Recipient emails (comma-separated)" value={schedRecipients} onChange={e => setSchedRecipients(e.target.value)} />}
                            <Typography sx={{ fontSize: 10.5, color: T.td3, fontStyle: 'italic' }}>Runs server-side from this template. Email delivery uses your tenant's SMTP settings; a copy is also saved on the server.</Typography>
                        </Stack>
                    </Collapse>
                </DialogContent>
                <DialogActions sx={{ px: 3, pb: 2.5 }}>
                    <Button onClick={() => setTplDlg(false)} sx={{ textTransform: 'none', color: T.td3 }}>Cancel</Button>
                    <Button onClick={saveTemplate} disabled={!tplName.trim()} variant="contained" disableElevation sx={{ textTransform: 'none', fontWeight: 800, borderRadius: '8px', px: 3, bgcolor: T.purple, '&:disabled': { bgcolor: '#e2e8f0' } }}>Save Template</Button>
                </DialogActions>
            </Dialog>

            {/* ═══ MEASURE STUDIO ═══ */}
            <Dialog open={studioOpen} onClose={closeStudio} maxWidth="sm" fullWidth PaperProps={{ sx: { borderRadius: '18px', overflow: 'hidden' } }}>
                <Box sx={{ position: 'relative', px: 3, pt: 2.5, pb: 2, background: `linear-gradient(135deg, ${T.purple} 0%, ${T.primary} 100%)`, color: '#fff' }}>
                    <IconButton onClick={closeStudio} size="small" sx={{ position: 'absolute', top: 10, right: 10, color: 'rgba(255,255,255,0.85)' }}><X size={18} /></IconButton>
                    <Stack direction="row" spacing={1.25} alignItems="center">
                        <Box sx={{ width: 38, height: 38, borderRadius: '11px', bgcolor: 'rgba(255,255,255,0.18)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Wand2 size={20} /></Box>
                        <Box>
                            <Typography sx={{ fontWeight: 900, fontSize: 18, lineHeight: 1.1 }}>{stEdit ? 'Edit Measure' : 'Measure Studio'}</Typography>
                            <Typography sx={{ fontSize: 12, opacity: 0.85 }}>Build a custom measure and choose how it’s displayed</Typography>
                        </Box>
                    </Stack>
                    <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
                        {[['agg', 'Aggregation', Sigma], ['calc', 'Calculated', Calculator], ['time', 'Time', Clock]].map(([k, label, Icon]) => (
                            <Box key={k} onClick={() => setStTab(k)} sx={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 0.75, px: 1.75, py: 0.8, borderRadius: '999px', fontSize: 12.5, fontWeight: 800, bgcolor: stTab === k ? '#fff' : 'rgba(255,255,255,0.16)', color: stTab === k ? T.primary : '#fff', transition: 'all .15s' }}>
                                <Icon size={14} />{label}
                            </Box>
                        ))}
                    </Stack>
                </Box>

                <DialogContent sx={{ pt: 2.5 }}>
                    <TextField fullWidth label="Measure name" value={stName} onChange={e => setStName(e.target.value)} size="small" sx={{ mb: 2 }} autoFocus />

                    {stTab === 'agg' ? (
                        <>
                            <Typography sx={stLabelSx}>COLUMN</Typography>
                            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75, mb: 2 }}>
                                {(catalog.measureColumns || []).map(c => {
                                    const on = stCol === c.key;
                                    return <Box key={c.key} onClick={() => { setStCol(c.key); const opts = catalog.aggsByKind?.[c.kind] || []; setStAgg(opts.includes(stAgg) ? stAgg : (opts[0] || '')); }}
                                        sx={{ cursor: 'pointer', px: 1.25, py: 0.6, borderRadius: '8px', fontSize: 12, fontWeight: 700, border: `1px solid ${on ? T.primary : T.cardBorder}`, bgcolor: on ? `${T.primary}0e` : '#fff', color: on ? T.primary : T.td2, transition: 'all .15s', '&:hover': { borderColor: T.primary } }}>{c.label}</Box>;
                                })}
                            </Box>
                            <Typography sx={stLabelSx}>AGGREGATION</Typography>
                            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75, mb: 1 }}>
                                {stAggOpts.length ? stAggOpts.map(a => {
                                    const on = stAgg === a;
                                    return <Box key={a} onClick={() => setStAgg(a)} sx={{ cursor: 'pointer', px: 1.25, py: 0.6, borderRadius: '8px', fontSize: 12, fontWeight: 700, border: `1px solid ${on ? T.purple : T.cardBorder}`, bgcolor: on ? `${T.purple}10` : '#fff', color: on ? T.purple : T.td2, transition: 'all .15s', '&:hover': { borderColor: T.purple } }}>{AGG_LABELS[a] || a}</Box>;
                                }) : <Typography sx={{ fontSize: 12, color: T.td3 }}>Pick a column first.</Typography>}
                            </Box>
                            <Typography sx={stLabelSx}>ONLY WHERE (optional)</Typography>
                            <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
                                <TextField select size="small" label="Condition field" value={stCondField}
                                    onChange={e => { setStCondField(e.target.value); setStCondVals([]); if (e.target.value) loadDist(e.target.value); }}
                                    sx={{ minWidth: 190 }} SelectProps={{ MenuProps: { PaperProps: { sx: { maxHeight: 320 } } } }}>
                                    <MenuItem value=""><em>No condition</em></MenuItem>
                                    {[...(catalog.merchantFields || []), ...(catalog.transactionFields || [])].map(fl => <MenuItem key={fl.key} value={fl.key} sx={{ fontSize: 13 }}>{fl.label}</MenuItem>)}
                                </TextField>
                                {stCondField && <Typography sx={{ fontSize: 11.5, fontWeight: 700, color: stCondVals.length ? T.green : T.td3 }}>{stCondVals.length ? `${stCondVals.length} value${stCondVals.length > 1 ? 's' : ''}` : 'pick value(s)'}</Typography>}
                            </Stack>
                            {stCondField && (
                                <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, maxHeight: 116, overflowY: 'auto', mb: 1 }} className="qe4-scroll">
                                    {dcache[stCondField] == null ? <CircularProgress size={14} /> : (dcache[stCondField] || []).map(v => {
                                        const sv = String(v); const on = stCondVals.includes(sv);
                                        return <Box key={sv} onClick={() => setStCondVals(p => on ? p.filter(x => x !== sv) : [...p, sv])}
                                            sx={{ cursor: 'pointer', px: 1, py: 0.4, borderRadius: '6px', fontSize: 11.5, fontWeight: 700, border: `1px solid ${on ? T.green : T.cardBorder}`, bgcolor: on ? T.greenGhost : '#fff', color: on ? T.green : T.td2, '&:hover': { borderColor: T.green } }}>{sv}</Box>;
                                    })}
                                </Box>
                            )}
                            {stCondField && dcache[stCondField] != null && !((dcache[stCondField] || []).length) && <Typography sx={{ fontSize: 11.5, color: T.td3, mb: 1 }}>No values found for this field in range.</Typography>}
                        </>
                    ) : stTab === 'time' ? (
                        <>
                            <Typography sx={stLabelSx}>BASE MEASURE</Typography>
                            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75, mb: 2 }}>
                                {[...(catalog.measures || []), ...calcMeasures.filter(m => m.kind !== 'time' && m.key !== stEdit)].map(bm => {
                                    const on = stBase === bm.key;
                                    return <Box key={bm.key} onClick={() => setStBase(bm.key)}
                                        sx={{ cursor: 'pointer', px: 1.25, py: 0.6, borderRadius: '8px', fontSize: 12, fontWeight: 700, border: `1px solid ${on ? T.amber : T.cardBorder}`, bgcolor: on ? `${T.amber}14` : '#fff', color: on ? T.amber : T.td2, transition: 'all .15s', '&:hover': { borderColor: T.amber } }}>{bm.label}</Box>;
                                })}
                            </Box>
                            <Typography sx={stLabelSx}>COMPARE TO</Typography>
                            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75, mb: 2 }}>
                                {[['YOY', 'Year over year'], ['MOM', 'Month over month'], ['PREV', 'Previous period']].map(([k, l]) => stSeg(k, l, stCompare, () => setStCompare(k), T.primary))}
                            </Box>
                            <Typography sx={stLabelSx}>SHOW AS</Typography>
                            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75, mb: 1 }}>
                                {[['growth', '% Growth'], ['delta', 'Δ Change'], ['prior', 'Prior value']].map(([k, l]) => stSeg(k, l, stMode, () => { setStMode(k); setStFmt(f => ({ ...f, unit: k === 'growth' ? 'percent' : (f.unit === 'percent' ? 'number' : f.unit) })); }, T.amber))}
                            </Box>
                            <Typography sx={{ fontSize: 11.5, color: T.td3, mb: 1 }}>Needs a date range selected. Compares the base measure for the current range against the {stCompare === 'YOY' ? 'same range last year' : stCompare === 'MOM' ? 'same range last month' : 'preceding equal-length period'}.</Typography>
                        </>
                    ) : (
                        <>
                            <TextField fullWidth label="Formula" value={stFormula} onChange={e => setStFormula(e.target.value)} size="small" placeholder="total_msf / total_volume * 100" sx={{ mb: 1.5 }} />
                            <Typography sx={{ fontSize: 12.5, color: T.td3, mb: 0.5 }}>Arithmetic over existing measures with + − × ÷ and parentheses. Click to insert:</Typography>
                            <Typography sx={{ fontSize: 11, color: T.td3, mb: 1 }}>Functions: <code>ROUND</code>, <code>ABS</code>, <code>COALESCE</code>, <code>LEAST</code>, <code>GREATEST</code>.</Typography>
                            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, maxHeight: 96, overflowY: 'auto', mb: 1 }} className="qe4-scroll">
                                {(catalog.measures || []).map(m => (
                                    <Chip key={m.key} size="small" label={m.key} onClick={() => setStFormula(f => (f ? f + ' ' : '') + m.key)}
                                        sx={{ height: 22, fontSize: 10.5, cursor: 'pointer', bgcolor: T.greenGhost, color: T.green, border: `1px solid ${T.greenBorder}`, '& .MuiChip-label': { px: 1 } }} />
                                ))}
                            </Box>
                        </>
                    )}

                    <Box sx={{ mt: 1, mb: 2, p: 1.5, borderRadius: '12px', border: `1px solid ${T.cardBorder}`, bgcolor: '#fafbfc', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                        <Stack direction="row" spacing={1} alignItems="center"><Eye size={15} color={T.td3} /><Typography sx={{ fontSize: 12, fontWeight: 700, color: T.td3 }}>Live preview</Typography></Stack>
                        <Box sx={{ minHeight: 24, display: 'flex', alignItems: 'center' }}>
                            {stPrevLoad ? <CircularProgress size={15} /> : <Typography sx={{ fontSize: 18, fontWeight: 900, color: T.td, fontVariantNumeric: 'tabular-nums' }}>{stPrev != null ? formatMeasure(stPrev, stFmt) : '—'}</Typography>}
                        </Box>
                    </Box>

                    <Typography sx={stLabelSx}>FORMAT</Typography>
                    <Stack spacing={1.25} sx={{ mb: 1 }}>
                        <Stack direction="row" spacing={1} alignItems="center">
                            <Typography sx={stFtLabel}>Unit</Typography>
                            {[['number', 'Number'], ['currency', 'Currency'], ['percent', 'Percent']].map(([k, l]) => stSeg(k, l, stFmt.unit, () => setStFmt(f => ({ ...f, unit: k })), T.primary))}
                        </Stack>
                        <Stack direction="row" spacing={1} alignItems="center">
                            <Typography sx={stFtLabel}>Scale</Typography>
                            {[['none', 'None'], ['K', 'K'], ['M', 'M'], ['B', 'B'], ['auto', 'Auto']].map(([k, l]) => stSeg(k, l, stFmt.scale, () => setStFmt(f => ({ ...f, scale: k })), T.purple))}
                        </Stack>
                        <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
                            <Stack direction="row" spacing={1} alignItems="center"><Typography sx={stFtLabel}>Decimals</Typography>
                                <TextField type="number" size="small" value={stFmt.decimals} onChange={e => setStFmt(f => ({ ...f, decimals: Math.max(0, Math.min(6, Number(e.target.value) || 0)) }))} sx={{ width: 74 }} inputProps={{ min: 0, max: 6 }} /></Stack>
                            <TextField label="Prefix" size="small" value={stFmt.prefix} onChange={e => setStFmt(f => ({ ...f, prefix: e.target.value }))} sx={{ width: 96 }} />
                            <TextField label="Suffix" size="small" value={stFmt.suffix} onChange={e => setStFmt(f => ({ ...f, suffix: e.target.value }))} sx={{ width: 96 }} />
                        </Stack>
                        <Stack direction="row" spacing={1} alignItems="center">
                            <Typography sx={stFtLabel}>Sample</Typography>
                            <Typography sx={{ fontSize: 13, fontWeight: 800, color: T.primary, fontVariantNumeric: 'tabular-nums' }}>{stSample}</Typography>
                        </Stack>
                    </Stack>

                    <FormControlLabel sx={{ mt: 0.5 }} control={<Checkbox checked={stPublish} onChange={e => setStPublish(e.target.checked)} size="small" sx={{ color: T.purple, '&.Mui-checked': { color: T.purple } }} />} label={<Typography sx={{ fontSize: 12 }}>Publish to shared library (admins)</Typography>} />
                    {stErr && <Typography sx={{ fontSize: 11.5, color: T.rose, mt: 0.5 }}>{stErr}</Typography>}
                </DialogContent>
                <DialogActions sx={{ px: 3, pb: 2.5 }}>
                    <Button onClick={closeStudio} sx={{ textTransform: 'none', color: T.td3 }}>Cancel</Button>
                    <Button onClick={saveStudioMeasure} disabled={!studioValid || !stName.trim()} variant="contained" disableElevation startIcon={<Sparkles size={15} />} sx={{ textTransform: 'none', fontWeight: 800, borderRadius: '10px', px: 3, background: `linear-gradient(135deg, ${T.purple}, ${T.primary})`, '&:disabled': { background: '#e2e8f0' } }}>{stEdit ? 'Save changes' : 'Add measure'}</Button>
                </DialogActions>
            </Dialog>

            {/* THRESHOLD ALERT DIALOG */}
            <Dialog open={alertDlg} onClose={() => setAlertDlg(false)} maxWidth="sm" fullWidth PaperProps={{ sx: { borderRadius: '14px', overflow: 'hidden' } }}>
                <Box sx={{ height: 3, background: `linear-gradient(90deg, ${T.primary}, ${T.purple})` }} />
                <DialogTitle sx={{ fontWeight: 800, fontSize: 18, pb: 0, display: 'flex', alignItems: 'center', gap: 1 }}><Bell size={17} color={T.primary} /> Threshold Alerts</DialogTitle>
                <DialogContent sx={{ pt: 2 }}>
                    <Typography sx={{ fontSize: 12.5, color: T.td3, mb: 2 }}>Get alerted when a measure crosses a threshold. Evaluated server-side on a schedule; breaches appear in the Alerts screen.</Typography>
                    <Stack spacing={1.5}>
                        <TextField fullWidth size="small" label="Alert name" value={alForm.name} onChange={e => setAlForm(f => ({ ...f, name: e.target.value }))} placeholder="e.g. MSF % dropped" />
                        <Stack direction="row" spacing={1.5}>
                            <TextField select fullWidth size="small" label="Measure" value={alForm.measureKey} onChange={e => setAlForm(f => ({ ...f, measureKey: e.target.value }))}>
                                {[...(catalog.measures || []), ...calcMeasures.filter(m => m.kind !== 'time')].map(m => <MenuItem key={m.key} value={m.key}>{m.label}</MenuItem>)}
                            </TextField>
                            <TextField select size="small" label="Op" value={alForm.operator} onChange={e => setAlForm(f => ({ ...f, operator: e.target.value }))} sx={{ minWidth: 84 }}>
                                {['>', '>=', '<', '<=', '==', '!='].map(o => <MenuItem key={o} value={o}>{o}</MenuItem>)}
                            </TextField>
                            <TextField size="small" label="Threshold" type="number" value={alForm.threshold} onChange={e => setAlForm(f => ({ ...f, threshold: e.target.value }))} sx={{ minWidth: 120 }} />
                        </Stack>
                        <Stack direction="row" spacing={1.5}>
                            <TextField size="small" label="Window (days)" type="number" value={alForm.windowDays} onChange={e => setAlForm(f => ({ ...f, windowDays: e.target.value }))} sx={{ width: 140 }} />
                            <TextField select size="small" label="Severity" value={alForm.severity} onChange={e => setAlForm(f => ({ ...f, severity: e.target.value }))} sx={{ width: 150 }}>
                                {['INFO', 'WARNING', 'CRITICAL'].map(s => <MenuItem key={s} value={s}>{s}</MenuItem>)}
                            </TextField>
                            <TextField fullWidth size="small" label="Recipients (optional)" value={alForm.recipients} onChange={e => setAlForm(f => ({ ...f, recipients: e.target.value }))} />
                        </Stack>
                        {nF > 0 && <Typography sx={{ fontSize: 11, color: T.td3 }}>Current selections ({nF}) will be saved as this alert's filter.</Typography>}
                        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                            <Button onClick={createAlertFn} disabled={!alForm.measureKey || alForm.threshold === ''} variant="contained" disableElevation startIcon={<Plus size={15} />} sx={{ textTransform: 'none', fontWeight: 800, borderRadius: '8px', bgcolor: T.primary }}>Create alert</Button>
                            {alertMsg && <Typography sx={{ fontSize: 12, color: T.td3 }}>{alertMsg}</Typography>}
                        </Box>
                    </Stack>
                    <Divider sx={{ my: 2 }} />
                    <Typography sx={{ fontSize: 10.5, fontWeight: 800, color: T.td3, letterSpacing: 0.5, mb: 1 }}>EXISTING ALERTS ({alerts.length})</Typography>
                    <Stack spacing={1} sx={{ maxHeight: 220, overflowY: 'auto' }} className="qe4-scroll">
                        {!alerts.length && <Typography sx={{ fontSize: 12, color: T.td3, fontStyle: 'italic' }}>No alerts yet.</Typography>}
                        {alerts.map(a => (
                            <Box key={a.id} sx={{ display: 'flex', alignItems: 'center', gap: 1, p: 1, borderRadius: '8px', border: `1px solid ${T.cardBorder}` }}>
                                <Box sx={{ flex: 1, minWidth: 0 }}>
                                    <Typography sx={{ fontSize: 12.5, fontWeight: 700, color: T.td, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{a.name}</Typography>
                                    <Typography sx={{ fontSize: 11, color: T.td3 }}>{a.measureKey} {a.operator} {a.threshold}{a.lastValue != null ? ` · last ${Number(a.lastValue).toLocaleString()}` : ''}</Typography>
                                </Box>
                                <Chip size="small" label={a.severity} sx={{ height: 18, fontSize: 9.5, fontWeight: 700 }} />
                                <Tooltip title="Run now" arrow><IconButton size="small" onClick={() => runAlertNow(a)} sx={{ color: T.td3 }}><Play size={13} /></IconButton></Tooltip>
                                <Tooltip title={a.isEnabled ? 'Enabled — click to pause' : 'Paused — click to enable'} arrow><IconButton size="small" onClick={() => toggleAlert(a)} sx={{ color: a.isEnabled ? T.green : T.td3 }}><Bell size={13} /></IconButton></Tooltip>
                                <Tooltip title="Delete" arrow><IconButton size="small" onClick={() => delAlert(a)} sx={{ color: T.td3, '&:hover': { color: T.rose } }}><Trash2 size={13} /></IconButton></Tooltip>
                            </Box>
                        ))}
                    </Stack>
                </DialogContent>
                <DialogActions sx={{ px: 3, pb: 2.5 }}>
                    <Button onClick={() => setAlertDlg(false)} sx={{ textTransform: 'none', color: T.td3 }}>Close</Button>
                </DialogActions>
            </Dialog>
        </Box>
    );
}
