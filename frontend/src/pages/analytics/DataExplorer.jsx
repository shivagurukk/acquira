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
    LayoutGrid, ArrowUpDown, Copy, ChevronLeft, BarChart2, Columns, Crosshair
} from 'lucide-react';
import {
    BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as RTooltip,
    ResponsiveContainer, PieChart as RPieChart, Pie, Cell, Legend,
    LineChart, Line, AreaChart, Area, ScatterChart, Scatter, ZAxis,
    RadarChart, Radar as RadarShape, PolarGrid, PolarAngleAxis, PolarRadiusAxis,
    ComposedChart, Treemap, RadialBarChart, RadialBar
} from 'recharts';
import { explorerApi, reportApi, savedViewsApi } from '../../api/explorer';

/* ═══════════════════════════════════════════════════════
   DESIGN TOKENS
   ═══════════════════════════════════════════════════════ */
const T = {
    primary: '#4361ee', primaryDark: '#3a56d4', primaryLight: '#6980f2',
    primaryGhost: 'rgba(67,97,238,0.06)', primaryBorder: 'rgba(67,97,238,0.18)',
    green: '#00b37e', greenGhost: 'rgba(0,179,126,0.06)', greenBorder: 'rgba(0,179,126,0.18)',
    amber: '#ff9f1c', rose: '#ef476f', cyan: '#06d6a0', purple: '#7209b7',
    sidebar: '#0a0e1a', sidebarHover: 'rgba(255,255,255,0.04)', sidebarActive: 'rgba(67,97,238,0.12)',
    workspace: '#f0f2f5', card: '#ffffff', cardBorder: 'rgba(0,0,0,0.06)',
    tw: 'rgba(255,255,255,0.9)', tw2: 'rgba(255,255,255,0.5)', tw3: 'rgba(255,255,255,0.25)',
    td: '#111827', td2: '#6b7280', td3: '#9ca3af',
    radius: 10, shadow: '0 1px 3px rgba(0,0,0,0.06), 0 1px 2px rgba(0,0,0,0.04)',
    shadowMd: '0 4px 14px rgba(0,0,0,0.06)', shadowLg: '0 10px 30px rgba(0,0,0,0.08)',
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
        .qe4-tile { transition: box-shadow 0.2s, transform 0.2s; }
        .qe4-tile:hover { box-shadow: 0 6px 20px rgba(0,0,0,0.08); }
    `;
    document.head.appendChild(s);
}

/* ═══════════════════════════════════════════════════════
   SUB COMPONENTS
   ═══════════════════════════════════════════════════════ */
const SideChip = ({ field, source }) => {
    const c = gc(field.category); const I = c.icon;
    return (
        <Box className="qe4-pill" draggable
            onDragStart={e => { e.dataTransfer.setData('text/plain', JSON.stringify({ ...field, source })); e.dataTransfer.effectAllowed = 'copy'; }}
            sx={{ display: 'inline-flex', alignItems: 'center', gap: '5px', px: '9px', py: '4.5px', borderRadius: '6px', fontSize: 11.5, fontWeight: 600, letterSpacing: '-0.01em', cursor: 'grab', userSelect: 'none', whiteSpace: 'nowrap', color: 'rgba(255,255,255,0.75)', bgcolor: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.06)', transition: 'all 0.2s ease', '&:hover': { bgcolor: 'rgba(255,255,255,0.08)', borderColor: `${c.color}55`, transform: 'translateY(-1px)', boxShadow: `0 4px 12px ${c.color}20` } }}>
            <I size={11} style={{ opacity: 0.6 }} />{field.label}
        </Box>
    );
};

const SideMeasChip = ({ measure }) => (
    <Box className="qe4-pill" draggable
        onDragStart={e => { e.dataTransfer.setData('text/plain', JSON.stringify({ ...measure, source: 'measure' })); }}
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

const DropZone = ({ label, items, onDrop, onRemove, accent, emptyText, renderItem }) => {
    const [over, setOver] = useState(false);
    return (
        <Paper elevation={0}
            onDragOver={e => { e.preventDefault(); setOver(true); }} onDragLeave={() => setOver(false)}
            onDrop={e => { e.preventDefault(); setOver(false); try { onDrop(JSON.parse(e.dataTransfer.getData('text/plain'))); } catch {} }}
            sx={{ px: 1.75, py: 1.25, borderRadius: `${T.radius}px`, minHeight: 54, bgcolor: over ? `${accent}05` : '#fafbfc', border: `1.5px dashed ${over ? accent : 'rgba(0,0,0,0.1)'}`, transition: 'all 0.25s ease', ...(over && { boxShadow: `0 0 0 3px ${accent}12` }) }}>
            <Stack direction="row" spacing={0.75} alignItems="center" sx={{ mb: items.length ? 0.75 : 0 }}>
                <Box sx={{ width: 3, height: 14, borderRadius: 3, bgcolor: accent, opacity: over ? 1 : 0.5 }} />
                <Typography variant="caption" sx={{ fontWeight: 800, color: T.td3, textTransform: 'uppercase', letterSpacing: 1.5, fontSize: 9.5 }}>{label}</Typography>
                {items.length > 0 && <Chip size="small" label={items.length} sx={{ height: 18, fontSize: 10, fontWeight: 800, bgcolor: `${accent}10`, color: accent, '& .MuiChip-label': { px: 0.6 } }} />}
            </Stack>
            {items.length > 0 ? (
                <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>
                    {items.map((f, i) => renderItem ? renderItem(f, i) : <ZoneChip key={f.key + i} field={f} onRemove={() => onRemove(i)} />)}
                </Stack>
            ) : (
                <Typography sx={{ fontSize: 11.5, color: 'rgba(0,0,0,0.2)', textAlign: 'center', py: 0.25 }}>{emptyText}</Typography>
            )}
        </Paper>
    );
};

const KpiCard = ({ label, value, color, icon: Icon, delay = 0 }) => (
    <Fade in timeout={600} style={{ transitionDelay: `${delay}ms` }}>
        <Card elevation={0} sx={{ minWidth: 155, borderRadius: `${T.radius}px`, border: `1px solid ${T.cardBorder}`, overflow: 'visible', position: 'relative', transition: 'all 0.25s ease', '&:hover': { boxShadow: T.shadowMd, transform: 'translateY(-2px)', borderColor: `${color}25` } }}>
            <Box sx={{ position: 'absolute', top: 0, left: 0, right: 0, height: 3, borderRadius: `${T.radius}px ${T.radius}px 0 0`, background: `linear-gradient(90deg, ${color}, ${color}80)` }} />
            <CardContent sx={{ p: '14px 16px !important', display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 1 }}>
                <Box>
                    <Typography sx={{ fontSize: 10.5, fontWeight: 700, color: T.td3, textTransform: 'uppercase', letterSpacing: 0.6, mb: 0.3 }}>{label}</Typography>
                    <Typography sx={{ fontSize: 24, fontWeight: 800, color: T.td, letterSpacing: -1, lineHeight: 1.1, fontVariantNumeric: 'tabular-nums' }}>{value}</Typography>
                </Box>
                {Icon && <Avatar sx={{ width: 36, height: 36, bgcolor: `${color}0a`, borderRadius: '10px' }}><Icon size={18} color={color} /></Avatar>}
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
                    <Typography sx={{ fontSize: 12, fontWeight: 800, fontVariantNumeric: 'tabular-nums' }}>{fmt(p.value)}</Typography>
                </Stack>
            ))}
        </Paper>
    );
};

const FilterBox = ({ title, fieldKey, values, loading, selected, onToggle, onClear, color }) => {
    const [q, setQ] = useState('');
    const list = values ? values.filter(v => !q || v?.toLowerCase().includes(q.toLowerCase())).slice(0, 80) : [];
    const has = selected.length > 0;
    const SEL = '#009845';
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
                    : !list.length ? <Typography sx={{ fontSize: 11, color: T.td3, textAlign: 'center', py: 1.5 }}>No values</Typography>
                    : list.map(v => {
                        const sel = selected.includes(v);
                        return (
                            <Box key={v} onClick={() => onToggle(v)} sx={{ display: 'flex', alignItems: 'center', gap: 0.75, px: 1.25, py: 0.45, cursor: 'pointer', bgcolor: sel ? `${SEL}08` : 'transparent', borderLeft: `3px solid ${sel ? SEL : 'transparent'}`, '&:hover': { bgcolor: sel ? `${SEL}12` : 'rgba(0,0,0,0.02)' }, transition: 'all 0.1s' }}>
                                <Checkbox size="small" checked={sel} readOnly sx={{ p: 0, '& .MuiSvgIcon-root': { fontSize: 16 }, color: sel ? SEL : T.td3, '&.Mui-checked': { color: SEL } }} />
                                <Typography sx={{ fontSize: 12, color: sel ? T.td : T.td2, fontWeight: sel ? 600 : 400, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v || '(empty)'}</Typography>
                            </Box>
                        );
                    })}
            </Box>
        </Paper>
    );
};

/* ═══ CHART RENDERER — supports 12 types ═══ */
const ChartRenderer = ({ type, data, measureKeys, palette, onChartClick, height = 360 }) => {
    if (!data?.length || !measureKeys?.length) return null;
    const mK = measureKeys.slice(0, 3);

    const common = { margin: { top: 10, right: 20, bottom: 5, left: 0 } };
    const axisX = { dataKey: 'name', tick: { fontSize: 11, fill: T.td3, fontWeight: 500 }, axisLine: false, tickLine: false };
    const axisY = { tick: { fontSize: 11, fill: T.td3 }, axisLine: false, tickLine: false, tickFormatter: fmtK, width: 55 };
    const grid = <CartesianGrid strokeDasharray="3 3" stroke="rgba(0,0,0,0.04)" vertical={false} />;

    switch (type) {
        case 'bar':
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <BarChart data={data} {...common} onClick={d => d?.activePayload?.[0] && onChartClick?.(d.activePayload[0])}>
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
                        {grid}<XAxis {...axisX} /><YAxis {...axisY} />
                        <RTooltip content={<ChartTip />} /><Legend wrapperStyle={{ fontSize: 12, paddingTop: 12 }} />
                        {mK.map((k, i) => <Line key={k} dataKey={k} stroke={palette[i]} strokeWidth={2.5} dot={{ r: 4, fill: 'white', stroke: palette[i], strokeWidth: 2.5 }} activeDot={{ r: 6, stroke: 'white', strokeWidth: 2.5 }} name={k.replace(/_/g,' ')} />)}
                    </LineChart>
                </ResponsiveContainer>
            );

        case 'area':
            return (
                <ResponsiveContainer width="100%" height={height}>
                    <AreaChart data={data} {...common}>
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

        default: return null;
    }
};

/* ═══════════════════════════════════════════════════════
   MAIN COMPONENT
   ═══════════════════════════════════════════════════════ */
export default function DataExplorer() {
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

    const palette = THEMES[theme]?.colors || THEMES.default.colors;

    useEffect(() => {
        (async () => {
            setLoading(true);
            try { setCatalog((await explorerApi.getFields()).data); } catch (e) {}
            try { setViews((await savedViewsApi.list('DATA_EXPLORER')).data); } catch (e) {}
            setLoading(false);
        })();
    }, []);

    const loadDist = async k => { if (dcache[k]) return; setDcache(p => ({ ...p, [k]: null })); try { const r = await explorerApi.getDistinct(k); setDcache(p => ({ ...p, [k]: r.data })); } catch (e) { setDcache(p => ({ ...p, [k]: [] })); } };

    const run = useCallback(async () => {
        if (!dims.length) return;
        setQLoad(true); const t0 = Date.now();
        try {
            const p = { dimensions: dims.map(d => d.key), measures: meas.length ? meas.map(m => m.key) : ['txn_count', 'total_volume', 'total_msf'], filters: Object.fromEntries(Object.entries(filters).filter(([, v]) => v?.length)), startDate: sd, endDate: ed, limit: 1000 };
            const moOnly = dims.every(d => catalog.merchantFields.some(f => f.key === d.key));
            const r = moOnly ? await explorerApi.queryMerchants(p) : await explorerApi.query(p);
            setRows(r.data.data || []);
        } catch (e) { setRows([]); }
        setQTime(Date.now() - t0); setQLoad(false);
    }, [dims, meas, filters, sd, ed, catalog]);

    const prevF = useRef(filters);
    useEffect(() => { if (prevF.current !== filters && dims.length > 0) { const t = setTimeout(run, 350); prevF.current = filters; return () => clearTimeout(t); } }, [filters, dims, run]);

    const addDim = f => { if (!dims.find(d => d.key === f.key) && f.source !== 'measure') setDims(p => [...p, f]); };
    const rmDim = i => setDims(p => p.filter((_, x) => x !== i));
    const addMeas = f => { if (!meas.find(m => m.key === f.key)) setMeas(p => [...p, f]); };
    const rmMeas = i => setMeas(p => p.filter((_, x) => x !== i));
    const toggleFV = (k, v) => {
        setSelHistory(h => [...h, { ...filters }]);
        setFilters(p => { const c = p[k] || []; const h = c.includes(v); const n = h ? c.filter(x => x !== v) : [...c, v]; const o = { ...p }; n.length ? o[k] = n : delete o[k]; return o; });
    };
    const clearFK = k => { setSelHistory(h => [...h, { ...filters }]); setFilters(p => { const o = { ...p }; delete o[k]; return o; }); };
    const stepBack = () => { if (selHistory.length) { const prev = selHistory[selHistory.length - 1]; setFilters(prev); setSelHistory(h => h.slice(0, -1)); } };
    const clearAll = () => { setDims([]); setMeas([]); setFilters({}); setSd(''); setEd(''); setRows([]); setActiveView(null); setQTime(null); setOpenBoxes([]); setSelHistory([]); };

    const onChartClick = d => { if (!d || !dims.length) return; const val = d.name || d?.payload?.name; if (val) toggleFV(dims[0].key, String(val)); };

    const saveV = async () => { if (!vName.trim()) return; try { await savedViewsApi.create({ name: vName, dashboardType: 'DATA_EXPLORER', filterJson: JSON.stringify({ dimensions: dims.map(d => d.key), measures: meas.map(m => m.key), filters, startDate: sd, endDate: ed, chartType, viewMode, theme }), isDefault: vDef, isShared: vShare }); setSaveDlg(false); setVName(''); setViews((await savedViewsApi.list('DATA_EXPLORER')).data); } catch (e) {} };
    const loadV = v => { try { const s = JSON.parse(v.filterJson); const af = [...(catalog.merchantFields || []), ...(catalog.transactionFields || [])]; setDims((s.dimensions || []).map(k => af.find(f => f.key === k)).filter(Boolean)); setMeas((s.measures || []).map(k => (catalog.measures || []).find(m => m.key === k)).filter(Boolean)); setFilters(s.filters || {}); setSd(s.startDate || ''); setEd(s.endDate || ''); setChartType(s.chartType || 'bar'); setViewMode(s.viewMode || 'both'); if (s.theme) setTheme(s.theme); setActiveView(v.id); } catch (e) {} };
    const delV = async id => { try { await savedViewsApi.remove(id); setViews((await savedViewsApi.list('DATA_EXPLORER')).data); if (activeView === id) setActiveView(null); } catch (e) {} };
    const exportCSV = () => { if (!rows.length) return; const h = Object.keys(rows[0]); const csv = [h.join(','), ...rows.map(r => h.map(k => `"${r[k] ?? ''}"`).join(','))].join('\n'); const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' })); a.download = 'explorer.csv'; a.click(); };
    const exportExcel = async () => { if (!rows.length) return; try { const res = await reportApi.exportExcel({ reportName: 'Data Explorer Report', data: rows }); const a = document.createElement('a'); a.href = URL.createObjectURL(res.data); a.download = 'explorer_report.xlsx'; a.click(); } catch (e) {} };
    const saveTemplate = async () => { if (!tplName.trim()) return; try { await reportApi.createTemplate({ name: tplName, description: tplDesc, isShared: tplShared, userId: 0, configJson: JSON.stringify({ dimensions: dims.map(d => d.key), measures: meas.map(m => m.key), filters, startDate: sd, endDate: ed, chartType, viewMode }) }); setTplDlg(false); setTplName(''); setTplDesc(''); } catch (e) {} };
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
    const cData = sorted.slice(0, 25).map((r, i) => {
        const e = { name: dims.length ? String(r[dims[0].key] || `Row ${i + 1}`).substring(0, 18) : `Row ${i + 1}` };
        (meas.length ? meas.map(m => m.key) : ['txn_count', 'total_volume', 'total_msf']).forEach(k => { e[k] = Number(r[k]) || 0; });
        return e;
    });
    const filterFlds = fs => !fldSearch ? fs : fs.filter(f => f.label.toLowerCase().includes(fldSearch.toLowerCase()));
    const filterableFields = useMemo(() =>
        [...(catalog.merchantFields || []), ...(catalog.transactionFields || [])]
            .filter(f => ['classification','card','status','organization','location','flags','terminal','people','dates'].includes(f.category)),
        [catalog]);
    const SEL = '#009845';

    if (loading) return (
        <Box sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '80vh', flexDirection: 'column', gap: 2 }}>
            <CircularProgress size={40} thickness={3} sx={{ color: T.primary }} />
            <Typography sx={{ fontSize: 14, color: T.td3, fontWeight: 600 }}>Loading Explorer…</Typography>
        </Box>
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
                        {fl.map(f => <SideChip key={f.key} field={f} source={pfx} />)}
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
                            <Typography sx={{ fontSize: 9, fontWeight: 800, color: T.tw3, letterSpacing: 2, px: 2, pb: 0.5 }}>MEASURES</Typography>
                            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: '5px', px: 1.5, pb: 1 }}>
                                {(catalog.measures || []).map(m => <SideMeasChip key={m.key} measure={m} />)}
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
                    <Stack direction="row" spacing={0.5} alignItems="center">
                        <Calendar size={13} color={T.td3} />
                        <TextField size="small" type="date" value={sd} onChange={e => setSd(e.target.value)} sx={{ width: 132, '& .MuiInputBase-root': { fontSize: 11.5, height: 30, borderRadius: '7px', bgcolor: '#f8f9fb' } }} />
                        <ArrowRight size={12} color={T.td3} />
                        <TextField size="small" type="date" value={ed} onChange={e => setEd(e.target.value)} sx={{ width: 132, '& .MuiInputBase-root': { fontSize: 11.5, height: 30, borderRadius: '7px', bgcolor: '#f8f9fb' } }} />
                    </Stack>

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
                        {[{ v: 'both', i: Eye, t: 'Both' }, { v: 'chart', i: BarChart3, t: 'Chart' }, { v: 'table', i: Table2, t: 'Table' }].map(({ v, i, t }) =>
                            <TogBtn key={v} icon={i} active={viewMode === v} onClick={() => setViewMode(v)} tip={t} />
                        )}
                    </Paper>

                    <Button size="small" onClick={run} disabled={!dims.length || qLoad} variant="contained" disableElevation
                        startIcon={qLoad ? <CircularProgress size={12} sx={{ color: 'white' }} /> : <Play size={12} fill="white" />}
                        sx={{ textTransform: 'none', fontWeight: 800, fontSize: 12, height: 34, borderRadius: '8px', px: 2.5, bgcolor: T.primary, '&:hover': { bgcolor: T.primaryDark }, '&:disabled': { bgcolor: '#e2e8f0', color: '#94a3b8' } }}>
                        Run
                    </Button>
                    <Tooltip title="Save View" arrow><IconButton size="small" onClick={() => setSaveDlg(true)} sx={{ color: T.td3, '&:hover': { color: T.primary } }}><Save size={15} /></IconButton></Tooltip>
                    <Tooltip title="Save as Template" arrow><IconButton size="small" onClick={() => setTplDlg(true)} sx={{ color: T.td3, '&:hover': { color: T.purple } }}><Bookmark size={15} /></IconButton></Tooltip>
                    <Tooltip title="Export CSV" arrow><IconButton size="small" onClick={exportCSV} disabled={!rows.length} sx={{ color: T.td3 }}><Download size={15} /></IconButton></Tooltip>
                    <Tooltip title="Export Excel" arrow><IconButton size="small" onClick={exportExcel} disabled={!rows.length} sx={{ color: T.td3 }}><Table2 size={15} /></IconButton></Tooltip>
                </Paper>

                {/* DROP ZONES */}
                <Box sx={{ px: 2, py: 1, bgcolor: 'white', borderBottom: `1px solid ${T.cardBorder}`, display: 'flex', gap: 1.5 }}>
                    <Box sx={{ flex: 2 }}><DropZone label="Dimensions" items={dims} onDrop={addDim} onRemove={rmDim} accent={T.primary} emptyText="Drag fields here" /></Box>
                    <Box sx={{ flex: 1 }}><DropZone label="Measures" items={meas} onDrop={addMeas} onRemove={rmMeas} accent={T.green} emptyText="Auto: Count, Vol, MSF"
                        renderItem={(f, i) => <ZoneMeasChip key={f.key + i} measure={f} onRemove={() => rmMeas(i)} />} /></Box>
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
                            <Stack direction="row" spacing={1.5} sx={{ mt: 1.5, overflowX: 'auto', pb: 0.5 }} className="qe4-scroll">
                                {openBoxes.map(fk => {
                                    const field = filterableFields.find(f => f.key === fk); if (!field) return null;
                                    return <FilterBox key={fk} title={field.label} fieldKey={fk} values={dcache[fk]} loading={dcache[fk] === null} selected={filters[fk] || []} onToggle={v => toggleFV(fk, v)} onClear={() => clearFK(fk)} color={gc(field.category).color} />;
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
                            <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '55%' }}>
                                <Box sx={{ width: 88, height: 88, borderRadius: '24px', mb: 3, background: `linear-gradient(135deg, ${T.primaryGhost}, rgba(0,179,126,0.05))`, display: 'flex', alignItems: 'center', justifyContent: 'center', animation: 'qe4Float 5s ease-in-out infinite' }}>
                                    <Sparkles size={36} color={T.primary} />
                                </Box>
                                <Typography sx={{ fontSize: 24, fontWeight: 800, color: T.td, letterSpacing: '-0.03em', mb: 0.5 }}>Build Your Analysis</Typography>
                                <Typography sx={{ fontSize: 14, color: T.td3, maxWidth: 420, textAlign: 'center', lineHeight: 1.7, mb: 3.5 }}>
                                    Drag fields into <b style={{ color: T.primary }}>Dimensions</b>, add <b style={{ color: T.green }}>Measures</b>, then hit Run — or try a quick-start:
                                </Typography>
                                <Stack direction="row" spacing={2}>
                                    {[
                                        { key: 'city', icon: MapPin, title: 'Volume by City', sub: 'Geographic breakdown', color: T.green },
                                        { key: 'card_scheme', icon: CreditCard, title: 'Card Scheme', sub: 'Visa, Mastercard…', color: T.rose },
                                        { key: 'payment_month', icon: TrendingUp, title: 'Monthly Trends', sub: 'Time-series view', color: T.amber },
                                    ].map(({ key, icon: I, title, sub, color }) => (
                                        <Card key={key} elevation={0} onClick={() => quickStart(key)}
                                            sx={{ width: 180, cursor: 'pointer', borderRadius: `${T.radius + 2}px`, border: `1px solid ${T.cardBorder}`, transition: 'all 0.25s ease', '&:hover': { transform: 'translateY(-4px)', boxShadow: `0 12px 30px ${color}15`, borderColor: `${color}30` } }}>
                                            <CardContent sx={{ p: '20px !important' }}>
                                                <Avatar sx={{ width: 40, height: 40, borderRadius: '10px', bgcolor: `${color}0a`, mb: 1.5 }}><I size={20} color={color} /></Avatar>
                                                <Typography sx={{ fontSize: 14, fontWeight: 700, color: T.td, mb: 0.25 }}>{title}</Typography>
                                                <Typography sx={{ fontSize: 11.5, color: T.td3, lineHeight: 1.4 }}>{sub}</Typography>
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
                                    <KpiCard key={k} label={k.replace(/_/g, ' ')} value={fmtK(rows.reduce((s, r) => s + (Number(r[k]) || 0), 0))} color={palette[i + 1]} delay={(i + 1) * 60} icon={[TrendingUp, DollarSign, BarChart3, Activity][i]} />
                                ))}
                                {qTime != null && <Stack direction="row" spacing={0.4} alignItems="center" sx={{ color: T.td3, ml: 1 }}><Zap size={12} /><Typography sx={{ fontSize: 11, fontWeight: 700 }}>{(qTime / 1000).toFixed(2)}s</Typography></Stack>}
                            </Stack>

                            {/* CHART */}
                            {(viewMode === 'chart' || viewMode === 'both') && cData.length > 0 && (
                                <Card elevation={0} className="qe4-tile" sx={{ borderRadius: `${T.radius + 4}px`, border: `1px solid ${T.cardBorder}`, overflow: 'hidden' }}>
                                    <CardContent sx={{ p: '24px !important' }}>
                                        <ChartRenderer type={chartType} data={cData} measureKeys={mK} palette={palette} onChartClick={onChartClick} height={360} />
                                        <Typography sx={{ fontSize: 10.5, color: T.td3, textAlign: 'center', mt: 1.5, fontStyle: 'italic' }}>Click chart elements to cross-filter</Typography>
                                    </CardContent>
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
                                                        style={{ padding: '12px 16px', textAlign: ci < dims.length ? 'left' : 'right', fontSize: 10.5, fontWeight: 800, textTransform: 'uppercase', letterSpacing: 0.8, color: sortCol === col ? T.primary : T.td3, borderBottom: `2px solid ${sortCol === col ? T.primary : 'rgba(0,0,0,0.06)'}`, background: '#fafbfc', whiteSpace: 'nowrap', position: 'sticky', top: 0, zIndex: 1, cursor: 'pointer', userSelect: 'none' }}>
                                                        <Stack direction="row" spacing={0.5} alignItems="center" justifyContent={ci < dims.length ? 'flex-start' : 'flex-end'}>
                                                            <span>{col.replace(/_/g, ' ')}</span>
                                                            {sortCol === col && <span>{sortDir === 'asc' ? '↑' : '↓'}</span>}
                                                        </Stack>
                                                    </th>
                                                ))}</tr>
                                            </thead>
                                            <tbody>
                                                {sorted.map((row, ri) => (
                                                    <tr key={ri} className="qe4-row" onClick={() => { if (dims.length) { const dk = dims[0].key; const v = row[dk]; if (v != null) toggleFV(dk, String(v)); } }} style={{ cursor: dims.length ? 'pointer' : 'default' }}>
                                                        {Object.entries(row).map(([k, val], ci) => {
                                                            const isSel = dims.length && ci === 0 && filters[dims[0].key]?.includes(String(val));
                                                            return (
                                                                <td key={ci} style={{ padding: '9px 16px', whiteSpace: 'nowrap', borderBottom: '1px solid rgba(0,0,0,0.03)', fontSize: 12.5, fontVariantNumeric: 'tabular-nums', fontWeight: ci < dims.length ? 600 : 400, color: isSel ? SEL : ci < dims.length ? T.td : T.td2, textAlign: ci < dims.length ? 'left' : 'right', background: isSel ? `${SEL}05` : 'transparent', borderLeft: isSel ? `3px solid ${SEL}` : '3px solid transparent' }}>
                                                                    {typeof val === 'number' ? fmt(val) : (val ?? '—')}
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
                </DialogContent>
                <DialogActions sx={{ px: 3, pb: 2.5 }}>
                    <Button onClick={() => setTplDlg(false)} sx={{ textTransform: 'none', color: T.td3 }}>Cancel</Button>
                    <Button onClick={saveTemplate} disabled={!tplName.trim()} variant="contained" disableElevation sx={{ textTransform: 'none', fontWeight: 800, borderRadius: '8px', px: 3, bgcolor: T.purple, '&:disabled': { bgcolor: '#e2e8f0' } }}>Save Template</Button>
                </DialogActions>
            </Dialog>
        </Box>
    );
}
