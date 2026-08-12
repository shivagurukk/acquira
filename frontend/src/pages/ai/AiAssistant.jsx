import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
    Box, Typography, TextField, IconButton, Tooltip, Fade, Collapse,
    Chip, Stack, Select, MenuItem, FormControl, LinearProgress
} from '@mui/material';
import {
    Sparkles, Code2, Table2, BarChart3, Copy, Check, Trash2, Zap,
    ChevronDown, ChevronUp, Wifi, WifiOff, BrainCircuit, TrendingUp,
    DollarSign, Cpu, Layers, Activity, ArrowUp, Globe, ShieldCheck,
    CreditCard, MapPin, Database, RotateCcw, Terminal, ChevronRight,
    MessageSquare, Lightbulb, History, Clock
} from 'lucide-react';
import {
    BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip as RTooltip,
    ResponsiveContainer, PieChart, Pie, Cell, Legend, AreaChart, Area
} from 'recharts';
import { aiApi } from '../../api/ai';
import { formatCompactCurrency } from '../../utils/formatters';

/* ═══════════════════════════════════════════════════════════════
   DESIGN: "Luma" — Clean premium dark, inspired by Linear/Vercel
   Navy base, ice-blue accent, minimal glass, generous whitespace
   ═══════════════════════════════════════════════════════════════ */
const P = {
    bg:      '#0c111b',  surface: '#131926',  card:    '#171e2e',
    raised:  '#1c2538',  border:  'rgba(255,255,255,0.055)',
    bdrHvr:  'rgba(255,255,255,0.09)',
    // accents
    accent:  '#4f8ef7', accent2: '#818cf8', teal: '#2dd4bf',
    green:   '#22c55e', amber:  '#facc15', rose: '#f43f5e',
    purple:  '#a78bfa', cyan:   '#22d3ee',
    // text
    t1: '#eef2ff', t2: 'rgba(238,242,255,0.55)', t3: 'rgba(238,242,255,0.28)', t4: 'rgba(238,242,255,0.08)',
    // gradients
    gAccent: 'linear-gradient(135deg,#4f8ef7,#818cf8)',
    gTeal:   'linear-gradient(135deg,#2dd4bf,#22d3ee)',
    gPurple: 'linear-gradient(135deg,#818cf8,#c084fc)',
};
const COLORS = ['#4f8ef7','#2dd4bf','#22c55e','#facc15','#a78bfa','#f43f5e','#22d3ee','#fb923c'];

/* The assistant returns arbitrary SQL result columns, so money is identified by
   column name. Anything matching this list is rendered WITH the tenant currency
   and at the tenant's precision; everything else stays a plain number. Rates,
   percentages, bps and counts are deliberately excluded. */
const MONEY_COL = /(^|_)(volume|amount|msf|revenue|fee|fees|interchange|vat|settled|settlement|ticket|spend|value|balance|charge|cost)(_|$)/i;
const NON_MONEY_COL = /(rate|pct|percent|bps|ratio|count|txns|transactions|_id$|^id$)/i;
const isMoneyKey = (key) => !!key && !NON_MONEY_COL.test(key) && MONEY_COL.test(key);

const fmtNum = (v, money = false) => {
    if (v==null) return '—';
    if (typeof v !== 'number') return String(v);
    if (money) return formatCompactCurrency(v);
    const a = Math.abs(v);
    if (a >= 1e9) return (v/1e9).toFixed(2)+'B';
    if (a >= 1e6) return (v/1e6).toFixed(2)+'M';
    if (a >= 1e4) return (v/1e3).toFixed(1)+'K';
    return v.toLocaleString('en-US',{maximumFractionDigits:2});
};

/* ═══ CSS ═══ */
const _S = 'luma-ai-css';
if (typeof document!=='undefined' && !document.getElementById(_S)){
    const el=document.createElement('style'); el.id=_S;
    el.textContent=`
@import url('https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800;900&family=JetBrains+Mono:wght@400;500;600&display=swap');
.lu{font-family:'Inter',-apple-system,BlinkMacSystemFont,sans-serif!important;-webkit-font-smoothing:antialiased}
.lu *{font-family:inherit!important;box-sizing:border-box}
.lu-code{font-family:'JetBrains Mono',monospace!important}
.lu::-webkit-scrollbar,.lu *::-webkit-scrollbar{width:5px;height:5px}
.lu::-webkit-scrollbar-track,.lu *::-webkit-scrollbar-track{background:transparent}
.lu::-webkit-scrollbar-thumb,.lu *::-webkit-scrollbar-thumb{background:rgba(255,255,255,0.06);border-radius:8px}
.lu::-webkit-scrollbar-thumb:hover,.lu *::-webkit-scrollbar-thumb:hover{background:rgba(255,255,255,0.1)}

@keyframes luSlideIn{from{opacity:0;transform:translateY(14px)}to{opacity:1;transform:translateY(0)}}
@keyframes luFade{from{opacity:0}to{opacity:1}}
@keyframes luFloat{0%,100%{transform:translateY(0)}50%{transform:translateY(-10px)}}
@keyframes luSpin{to{transform:rotate(360deg)}}
@keyframes luDot{0%,80%,100%{transform:scale(0.6);opacity:0.3}40%{transform:scale(1);opacity:1}}
@keyframes luShimmer{0%{background-position:-200% 0}100%{background-position:200% 0}}
@keyframes luPing{75%,100%{transform:scale(2);opacity:0}}
@keyframes luOrb{0%{transform:translate(0,0)}33%{transform:translate(50px,-30px)}66%{transform:translate(-30px,20px)}100%{transform:translate(0,0)}}

.lu-msg{animation:luSlideIn .45s cubic-bezier(.16,1,.3,1) both}
.lu-hover{transition:all .2s cubic-bezier(.16,1,.3,1)}
.lu-hover:hover{transform:translateY(-2px);box-shadow:0 8px 30px rgba(0,0,0,.25)}
.lu-tr{transition:background .15s}
.lu-tr:hover{background:rgba(255,255,255,.015)!important}
.lu-glow:focus-within{box-shadow:0 0 0 1.5px rgba(79,142,247,.35),0 0 40px rgba(79,142,247,.06)!important;border-color:rgba(79,142,247,.2)!important}
`;
    document.head.appendChild(el);
}

/* ═══════════════════════════════════════
   SUGGESTION DATA
   ═══════════════════════════════════════ */
const SUGGESTIONS = [
    { q:'Total transaction volume by card scheme', icon:BarChart3, color:P.accent, cat:'Volume' },
    { q:'Top 10 merchants by volume',              icon:TrendingUp, color:P.green, cat:'Ranking' },
    { q:'Monthly volume trend last 6 months',      icon:Activity, color:P.cyan, cat:'Trends' },
    { q:'Total volume and revenue this month',     icon:DollarSign, color:P.amber, cat:'Revenue' },
    { q:'Active merchants by city',                icon:MapPin, color:P.purple, cat:'Geography' },
    { q:'Card type breakdown',                     icon:CreditCard, color:P.rose, cat:'Cards' },
    { q:'Local vs international volume',            icon:Globe, color:P.teal, cat:'Destination' },
    { q:'Daily volume last 30 days',               icon:Layers, color:P.accent2, cat:'Daily' },
];

/* ═══════════════════════════════════════
   FOLLOW-UP SUGGESTIONS
   ═══════════════════════════════════════ */
const getFollowUps = (question) => {
    const q = (question||'').toLowerCase();
    if (q.includes('card scheme'))   return ['Net margin by scheme this year','Card type breakdown','Volume by card scheme'];
    if (q.includes('merchant'))      return ['High risk merchants','Top sales reps by volume','How many merchants by status'];
    if (q.includes('revenue')||q.includes('msf')) return ['Net margin by scheme this year','Monthly volume trend last 6 months','Volume by card scheme'];
    if (q.includes('volume'))        return ['Card type breakdown','Local vs international volume','Top 10 merchants by volume'];
    if (q.includes('city'))          return ['Active merchants by city','How many merchants by status','Top 10 merchants by volume'];
    if (q.includes('month')||q.includes('trend')) return ['Daily volume last 30 days','Volume by card scheme','Total volume and revenue this month'];
    return ['Total transaction volume by card scheme','Top 10 merchants by volume','Monthly volume trend last 6 months'];
};

/* ═══════════════════════════════════════════════════════════════
   SQL BLOCK
   ═══════════════════════════════════════════════════════════════ */
const SqlBlock = ({ sql }) => {
    const [copied,setCopied] = useState(false);
    const [expanded,setExpanded] = useState(false);
    const lines = sql.split('\n').length;
    const copy = () => { navigator.clipboard.writeText(sql); setCopied(true); setTimeout(()=>setCopied(false),2000); };

    const KW = new Set(['SELECT','FROM','WHERE','AND','OR','GROUP BY','ORDER BY','LIMIT','LEFT JOIN','INNER JOIN','RIGHT JOIN','ON','AS','HAVING','COALESCE','SUM','COUNT','AVG','MIN','MAX','DISTINCT','CASE','WHEN','THEN','ELSE','END','IN','BETWEEN','LIKE','IS NOT NULL','IS NULL','NOT','DESC','ASC','CAST','DATE_TRUNC','TO_CHAR','CURRENT_DATE','INTERVAL','TRUE','FALSE','NULL','BY']);

    const highlight = (text) => text.split(/(\b(?:SELECT|FROM|WHERE|AND|OR|GROUP\s+BY|ORDER\s+BY|LIMIT|LEFT\s+JOIN|INNER\s+JOIN|RIGHT\s+JOIN|ON|AS|HAVING|COALESCE|SUM|COUNT|AVG|MIN|MAX|DISTINCT|CASE|WHEN|THEN|ELSE|END|IN|BETWEEN|LIKE|IS\s+NOT\s+NULL|IS\s+NULL|NOT|DESC|ASC|CAST|DATE_TRUNC|TO_CHAR|CURRENT_DATE|INTERVAL|TRUE|FALSE|NULL)\b|'[^']*'|\d+)/gi).map((p,i)=>{
        const u=p.toUpperCase().trim().replace(/\s+/g,' ');
        if(KW.has(u)) return <span key={i} style={{color:'#93c5fd',fontWeight:600}}>{p}</span>;
        if(p.match(/^'.*'$/)) return <span key={i} style={{color:'#86efac'}}>{p}</span>;
        if(p.match(/^\d+$/)) return <span key={i} style={{color:'#fde68a'}}>{p}</span>;
        return <span key={i}>{p}</span>;
    });

    return (
        <Box sx={{borderRadius:'12px',overflow:'hidden',border:`1px solid ${P.border}`,mt:2,bgcolor:'#0a0e18'}}>
            <Box sx={{height:1.5,background:P.gAccent,opacity:0.5}}/>
            <Box sx={{px:2,py:0.65,display:'flex',alignItems:'center',justifyContent:'space-between',borderBottom:`1px solid ${P.border}`,bgcolor:'rgba(0,0,0,0.2)'}}>
                <Stack direction="row" spacing={0.75} alignItems="center">
                    <Terminal size={10} color={P.accent}/>
                    <Typography sx={{fontSize:9.5,fontWeight:700,color:P.t3,textTransform:'uppercase',letterSpacing:1.5}}>SQL</Typography>
                </Stack>
                <Stack direction="row" spacing={0}>
                    {lines>5&&<IconButton size="small" onClick={()=>setExpanded(p=>!p)} sx={{color:P.t3,p:0.3,'&:hover':{color:P.t2}}}>{expanded?<ChevronUp size={11}/>:<ChevronDown size={11}/>}</IconButton>}
                    <IconButton size="small" onClick={copy} sx={{color:copied?P.green:P.t3,p:0.3,'&:hover':{color:P.t2}}}>{copied?<Check size={11}/>:<Copy size={11}/>}</IconButton>
                </Stack>
            </Box>
            <Box className="lu-code" sx={{px:2.5,py:1.5,fontSize:12,lineHeight:2,color:'#bdc8de',overflowX:'auto',whiteSpace:'pre-wrap',wordBreak:'break-word',maxHeight:expanded||lines<=5?'none':120,transition:'max-height .3s'}}>
                {highlight(sql)}
            </Box>
        </Box>
    );
};

/* ═══════════════════════════════════════════════════════════════
   KPI CARDS — extracted from result data
   ═══════════════════════════════════════════════════════════════ */
const KpiCards = ({ data, columns }) => {
    if(!data?.length) return null;
    const numCols = columns.filter(c=>data.some(r=>typeof r[c]==='number'));
    if(!numCols.length) return null;
    const cards = numCols.slice(0,4).map((c,i) => ({
        key: c,
        label: c.replace(/_/g,' '),
        value: data.reduce((s,r)=>s+(typeof r[c]==='number'?r[c]:0),0),
        color: COLORS[i%8],
    }));
    const icons = [TrendingUp, DollarSign, BarChart3, Activity];
    return (
        <Box sx={{display:'grid',gridTemplateColumns:`repeat(${Math.min(cards.length,4)},1fr)`,gap:1.25,mt:2}}>
            {cards.map((c,i) => {
                const I = icons[i%4];
                return (
                    <Box key={i} className="lu-msg" sx={{p:2,borderRadius:'12px',bgcolor:P.card,border:`1px solid ${P.border}`,position:'relative',overflow:'hidden',animationDelay:`${i*60}ms`}}>
                        <Box sx={{position:'absolute',top:0,left:0,right:0,height:2,background:`linear-gradient(90deg,${c.color},transparent 80%)`}}/>
                        <Stack direction="row" spacing={0.75} alignItems="center" sx={{mb:1}}>
                            <Box sx={{width:24,height:24,borderRadius:'7px',bgcolor:`${c.color}12`,display:'flex',alignItems:'center',justifyContent:'center'}}>
                                <I size={11} color={c.color}/>
                            </Box>
                            <Typography sx={{fontSize:9,fontWeight:700,color:P.t3,textTransform:'uppercase',letterSpacing:1.2,lineHeight:1}}>{c.label}</Typography>
                        </Stack>
                        <Typography sx={{fontSize:22,fontWeight:800,color:P.t1,fontVariantNumeric:'tabular-nums',letterSpacing:'-0.03em',lineHeight:1}}>{fmtNum(c.value, isMoneyKey(c.key))}</Typography>
                    </Box>
                );
            })}
        </Box>
    );
};

/* ═══════════════════════════════════════════════════════════════
   TABLE
   ═══════════════════════════════════════════════════════════════ */
const ResultTable = ({data,columns}) => {
    if(!data?.length) return null;
    return (
        <Box sx={{borderRadius:'12px',overflow:'hidden',border:`1px solid ${P.border}`,mt:2,bgcolor:P.surface}}>
            <Box sx={{overflowX:'auto',maxHeight:360}}>
                <table style={{width:'100%',borderCollapse:'collapse'}}>
                    <thead><tr>{columns.map(c=>(
                        <th key={c} style={{padding:'10px 14px',textAlign:'left',fontSize:9.5,fontWeight:700,textTransform:'uppercase',letterSpacing:1.2,color:P.t3,borderBottom:`1px solid ${P.border}`,background:P.card,position:'sticky',top:0,zIndex:1,whiteSpace:'nowrap'}}>{c.replace(/_/g,' ')}</th>
                    ))}</tr></thead>
                    <tbody>{data.slice(0,100).map((row,ri)=>(
                        <tr key={ri} className="lu-tr" style={{borderBottom:`1px solid ${P.t4}`}}>{columns.map((c,ci)=>{
                            const v=row[c]; const isN=typeof v==='number';
                            return <td key={ci} style={{padding:'9px 14px',fontSize:12.5,whiteSpace:'nowrap',fontVariantNumeric:'tabular-nums',fontWeight:ci===0?600:400,color:ci===0?P.t1:isN?P.cyan:P.t2}}>{fmtNum(v, isN && isMoneyKey(c))}</td>;
                        })}</tr>
                    ))}</tbody>
                </table>
            </Box>
            <Box sx={{px:2,py:0.65,borderTop:`1px solid ${P.border}`,display:'flex',justifyContent:'space-between',bgcolor:'rgba(0,0,0,0.1)'}}>
                <Typography sx={{fontSize:10,color:P.t3,fontWeight:600}}>{data.length} rows</Typography>
                {data.length>100&&<Typography sx={{fontSize:10,color:P.amber,fontWeight:600}}>First 100 shown</Typography>}
            </Box>
        </Box>
    );
};

/* ═══════════════════════════════════════════════════════════════
   CHART — backend chartHint drives the choice; column heuristic is
   the fallback when no hint is present (older responses).
   ═══════════════════════════════════════════════════════════════ */
const AutoChart = ({data,columns,hint}) => {
    if(!data?.length||columns?.length<2) return null;
    const numC = columns.filter(c=>data.some(r=>typeof r[c]==='number'));
    const strC = columns.filter(c=>!numC.includes(c));
    if(!numC.length||!strC.length) return null;
    const dim=strC[0], meas=numC.slice(0,3);
    const cd=data.slice(0,20).map(r=>({name:String(r[dim]||'').substring(0,16),...Object.fromEntries(meas.map(c=>[c,Number(r[c])||0]))}));

    // Prefer the backend's chartHint; fall back to the column-name heuristic.
    const heuristicTrend = dim.includes('month')||dim.includes('date')||dim.includes('day')||dim.includes('week');
    const isTrend = hint ? hint==='timeseries' : heuristicTrend;
    // Pie only makes sense for a single measure across few slices, and only
    // when the backend didn't explicitly ask for a time series.
    const isPie = !isTrend && data.length<=7 && meas.length===1 && (!hint || hint==='bar');
    const tip={background:P.card,border:`1px solid ${P.border}`,borderRadius:10,fontSize:12,color:P.t1,boxShadow:'0 12px 40px rgba(0,0,0,.5)'};

    return (
        <Box sx={{borderRadius:'12px',border:`1px solid ${P.border}`,mt:2,p:2.5,bgcolor:P.surface,position:'relative'}}>
            <ResponsiveContainer width="100%" height={280}>
                {isPie?(
                    <PieChart>
                        <defs>{cd.map((_,i)=><linearGradient key={i} id={`pc${i}`} x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stopColor={COLORS[i%8]} stopOpacity={1}/><stop offset="100%" stopColor={COLORS[i%8]} stopOpacity={0.55}/></linearGradient>)}</defs>
                        <Pie data={cd} dataKey={meas[0]} nameKey="name" cx="50%" cy="50%" outerRadius={110} innerRadius={55} label={({name,percent})=>percent>0.04?`${name} ${(percent*100).toFixed(0)}%`:''} labelLine={false} stroke={P.bg} strokeWidth={3} paddingAngle={2}>{cd.map((_,i)=><Cell key={i} fill={`url(#pc${i})`}/>)}</Pie>
                        <RTooltip contentStyle={tip} formatter={(v,n)=>[fmtNum(v, isMoneyKey(String(n).replace(/ /g,"_"))), n]}/><Legend wrapperStyle={{fontSize:11,color:P.t2}}/>
                    </PieChart>
                ):isTrend?(
                    <AreaChart data={cd} margin={{top:5,right:5,bottom:5,left:-15}}>
                        <defs>{meas.map((k,i)=><linearGradient key={k} id={`ac${i}`} x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor={COLORS[i]} stopOpacity={0.25}/><stop offset="100%" stopColor={COLORS[i]} stopOpacity={0.01}/></linearGradient>)}</defs>
                        <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,.03)" vertical={false}/>
                        <XAxis dataKey="name" tick={{fontSize:10,fill:P.t3}} axisLine={false} tickLine={false}/>
                        <YAxis tick={{fontSize:10,fill:P.t3}} axisLine={false} tickLine={false} width={70} tickFormatter={(v)=>fmtNum(v, isMoneyKey(meas[0]))}/>
                        <RTooltip contentStyle={tip} formatter={(v,n)=>[fmtNum(v, isMoneyKey(String(n).replace(/ /g,"_"))), n]}/><Legend wrapperStyle={{fontSize:11,color:P.t2}}/>
                        {meas.map((k,i)=><Area key={k} type="monotone" dataKey={k} stroke={COLORS[i]} strokeWidth={2} fill={`url(#ac${i})`} name={k.replace(/_/g,' ')} dot={{r:3,fill:COLORS[i],stroke:P.bg,strokeWidth:2}} activeDot={{r:5}}/>)}
                    </AreaChart>
                ):(
                    <BarChart data={cd} margin={{top:5,right:5,bottom:5,left:-15}}>
                        <defs>{meas.map((k,i)=><linearGradient key={k} id={`bc${i}`} x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor={COLORS[i]} stopOpacity={0.9}/><stop offset="100%" stopColor={COLORS[i]} stopOpacity={0.35}/></linearGradient>)}</defs>
                        <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,.03)" vertical={false}/>
                        <XAxis dataKey="name" tick={{fontSize:10,fill:P.t3}} axisLine={false} tickLine={false}/>
                        <YAxis tick={{fontSize:10,fill:P.t3}} axisLine={false} tickLine={false} width={70} tickFormatter={(v)=>fmtNum(v, isMoneyKey(meas[0]))}/>
                        <RTooltip contentStyle={tip} formatter={(v,n)=>[fmtNum(v, isMoneyKey(String(n).replace(/ /g,"_"))), n]}/><Legend wrapperStyle={{fontSize:11,color:P.t2}}/>
                        {meas.map((k,i)=><Bar key={k} dataKey={k} fill={`url(#bc${i})`} radius={[5,5,0,0]} name={k.replace(/_/g,' ')}/>)}
                    </BarChart>
                )}
            </ResponsiveContainer>
        </Box>
    );
};

/* ═══════════════════════════════════════════════════════════════
   THINKING STATE
   ═══════════════════════════════════════════════════════════════ */
const Thinking = () => (
    <Box className="lu-msg" sx={{display:'flex',gap:2,alignItems:'center',py:2}}>
        <Box sx={{position:'relative',width:36,height:36,flexShrink:0}}>
            <Box sx={{position:'absolute',inset:0,borderRadius:'50%',border:'2px solid transparent',borderTopColor:P.accent,borderRightColor:P.accent2,animation:'luSpin 1s linear infinite',opacity:.6}}/>
            <Box sx={{position:'absolute',inset:5,borderRadius:'50%',border:'1.5px solid transparent',borderBottomColor:P.teal,animation:'luSpin 1.5s linear infinite reverse',opacity:.4}}/>
            <BrainCircuit size={13} color={P.accent} style={{position:'absolute',top:'50%',left:'50%',transform:'translate(-50%,-50%)'}}/>
        </Box>
        <Box>
            <Typography sx={{fontSize:13,fontWeight:500,color:P.t2,background:`linear-gradient(90deg,${P.t2},${P.t3},${P.t2})`,backgroundSize:'200%',backgroundClip:'text',WebkitBackgroundClip:'text',WebkitTextFillColor:'transparent',animation:'luShimmer 2s linear infinite'}}>
                Analyzing & generating SQL…
            </Typography>
            <Stack direction="row" spacing={0.6} sx={{mt:.5}}>
                {[0,1,2,3].map(i=><Box key={i} sx={{width:4,height:4,borderRadius:'50%',bgcolor:P.accent,animation:`luDot 1.4s ${i*.2}s infinite`}}/>)}
            </Stack>
        </Box>
    </Box>
);

/* ═══════════════════════════════════════════════════════════════
   CHAT MESSAGE
   ═══════════════════════════════════════════════════════════════ */
const ChatMessage = ({ msg, onSend, onRetry }) => {
    const isUser = msg.role === 'user';
    const [showSql,setShowSql] = useState(false);
    const [showTable,setShowTable] = useState(true);
    const [showChart,setShowChart] = useState(true);

    if (msg.loading) return <Thinking/>;

    // A kpi-hinted single-row result has no meaningful chart — hide the chart toggle.
    const chartable = msg.data?.length>0 && msg.columns?.length>=2 && msg.chartHint!=='kpi' && msg.chartHint!=='table';

    return (
        <Box className="lu-msg" sx={{display:'flex',gap:1.5,mb:4,flexDirection:isUser?'row-reverse':'row',alignItems:'flex-start'}}>
            {/* Avatar */}
            {!isUser && (
                <Box sx={{width:34,height:34,borderRadius:'10px',flexShrink:0,background:P.gAccent,display:'flex',alignItems:'center',justifyContent:'center',boxShadow:`0 4px 20px ${P.accent}18`}}>
                    <BrainCircuit size={15} color="white"/>
                </Box>
            )}
            {isUser && (
                <Box sx={{width:34,height:34,borderRadius:'10px',flexShrink:0,bgcolor:P.raised,display:'flex',alignItems:'center',justifyContent:'center',border:`1px solid ${P.border}`}}>
                    <MessageSquare size={14} color={P.t2}/>
                </Box>
            )}

            <Box sx={{maxWidth:isUser?'60%':'90%',minWidth:200}}>
                {/* USER */}
                {isUser && (
                    <Box sx={{px:2.5,py:1.5,borderRadius:'16px 16px 4px 16px',bgcolor:P.raised,border:`1px solid ${P.border}`}}>
                        <Typography sx={{fontSize:14,lineHeight:1.7,color:P.t1,fontWeight:400}}>{msg.content}</Typography>
                    </Box>
                )}

                {/* AI */}
                {!isUser && (<Box>
                    {/* Error */}
                    {msg.error && (
                        <Box sx={{px:2.5,py:1.5,borderRadius:'12px',bgcolor:'rgba(244,63,94,.06)',border:`1px solid rgba(244,63,94,.12)`}}>
                            <Typography sx={{fontSize:13,color:'#fca5a5',lineHeight:1.6}}>{msg.error}</Typography>
                            <Chip icon={<RotateCcw size={10}/>} label="Retry" size="small" clickable onClick={()=>onRetry?.(msg.question)}
                                sx={{mt:1,height:24,fontSize:10,fontWeight:600,bgcolor:'rgba(244,63,94,.08)',color:P.rose,border:`1px solid rgba(244,63,94,.15)`,'& .MuiChip-icon':{color:P.rose}}}/>
                        </Box>
                    )}

                    {/* Summary */}
                    {msg.summary && (
                        <Box>
                            <Typography sx={{fontSize:14.5,lineHeight:1.85,color:P.t1,fontWeight:400}}>{msg.summary}</Typography>
                            <Stack direction="row" spacing={0.6} flexWrap="wrap" sx={{mt:1.5,gap:.5}}>
                                {msg.duration!=null && <Chip icon={<Zap size={9}/>} label={`${(msg.duration/1000).toFixed(1)}s`} size="small" sx={{height:22,fontSize:9.5,fontWeight:700,bgcolor:`${P.accent}0c`,color:P.accent,border:`1px solid ${P.accent}15`,'& .MuiChip-icon':{color:P.accent}}}/>}
                                {msg.rowCount!=null && <Chip icon={<Database size={9}/>} label={`${msg.rowCount} rows`} size="small" sx={{height:22,fontSize:9.5,fontWeight:700,bgcolor:`${P.green}0c`,color:P.green,border:`1px solid ${P.green}15`,'& .MuiChip-icon':{color:P.green}}}/>}
                                <Chip icon={<ShieldCheck size={9}/>} label="Safe" size="small" sx={{height:22,fontSize:9.5,fontWeight:700,bgcolor:`${P.purple}08`,color:P.purple,border:`1px solid ${P.purple}12`,'& .MuiChip-icon':{color:P.purple}}}/>
                            </Stack>
                        </Box>
                    )}

                    {/* KPI Cards */}
                    {msg.data?.length>0 && <KpiCards data={msg.data} columns={msg.columns}/>}

                    {/* Toggles */}
                    {(msg.generatedSql||msg.data) && (
                        <Stack direction="row" spacing={.5} sx={{mt:2}}>
                            {msg.generatedSql && <ToggleChip on={showSql} toggle={()=>setShowSql(p=>!p)} icon={Code2} label="SQL" color={P.accent}/>}
                            {chartable && <ToggleChip on={showChart} toggle={()=>setShowChart(p=>!p)} icon={BarChart3} label="Chart" color={P.cyan}/>}
                            {msg.data?.length>0 && <ToggleChip on={showTable} toggle={()=>setShowTable(p=>!p)} icon={Table2} label="Table" color={P.green}/>}
                        </Stack>
                    )}

                    <Collapse in={showSql} timeout={250}>{msg.generatedSql && <SqlBlock sql={msg.generatedSql}/>}</Collapse>
                    <Collapse in={showChart} timeout={250}>{chartable && <AutoChart data={msg.data} columns={msg.columns} hint={msg.chartHint}/>}</Collapse>
                    <Collapse in={showTable} timeout={250}>{msg.data?.length>0 && <ResultTable data={msg.data} columns={msg.columns}/>}</Collapse>

                    {/* Follow-up suggestions */}
                    {msg.summary && !msg.error && (
                        <Stack direction="row" spacing={.75} flexWrap="wrap" sx={{mt:2.5,gap:.5}}>
                            <Lightbulb size={12} color={P.t3} style={{marginTop:4}}/>
                            {getFollowUps(msg.question).map(fq=>(
                                <Chip key={fq} label={fq} size="small" clickable onClick={()=>onSend?.(fq)}
                                    icon={<ChevronRight size={10}/>}
                                    sx={{height:26,fontSize:11,fontWeight:500,bgcolor:'transparent',color:P.t2,border:`1px solid ${P.border}`,'& .MuiChip-icon':{color:P.t3},transition:'all .2s','&:hover':{bgcolor:`${P.accent}08`,borderColor:`${P.accent}20`,color:P.accent,'& .MuiChip-icon':{color:P.accent}}}}/>
                            ))}
                        </Stack>
                    )}
                </Box>)}
            </Box>
        </Box>
    );
};

const ToggleChip = ({on,toggle,icon:I,label,color}) => (
    <Chip size="small" icon={<I size={11}/>} label={on?`Hide ${label}`:label} clickable onClick={toggle}
        sx={{height:26,fontSize:10.5,fontWeight:600,px:.25,bgcolor:on?`${color}10`:'transparent',color:on?color:P.t3,border:`1px solid ${on?`${color}20`:P.border}`,'& .MuiChip-icon':{color:'inherit'},transition:'all .2s','&:hover':{bgcolor:`${color}0a`,color}}}/>
);

/* ═══════════════════════════════════════════════════════════════
   MAIN
   ═══════════════════════════════════════════════════════════════ */
export default function AiAssistant() {
    const [messages,setMessages] = useState([]);
    const [input,setInput] = useState('');
    const [sending,setSending] = useState(false);
    const [health,setHealth] = useState(null);
    const [models,setModels] = useState([]);
    const [selModel,setSelModel] = useState('');
    const [recent,setRecent] = useState([]);
    const chatRef = useRef(null);
    const inputRef = useRef(null);

    const loadRecent = useCallback(async()=>{
        try {
            const rows = (await aiApi.history(12)).data || [];
            // De-duplicate by question text, keep newest, cap at 6.
            const seen = new Set(); const out = [];
            for (const r of rows) {
                const q = r.question?.trim();
                if (!q || seen.has(q.toLowerCase())) continue;
                seen.add(q.toLowerCase()); out.push(r);
                if (out.length >= 6) break;
            }
            setRecent(out);
        } catch { setRecent([]); }
    },[]);

    useEffect(()=>{
        (async()=>{
            let h=null;
            try { h=(await aiApi.health()).data; setHealth(h); } catch { setHealth({status:'disconnected'}); }
            try {
                const ms=(await aiApi.models()).data||[];
                setModels(ms);
                // Model names are provider-qualified ("ollama/llama3.2",
                // "anthropic/claude-sonnet-4-5"). Default to the active
                // provider's default model when present, else the first entry.
                const want=h?`${h.provider}/${h.model}`:null;
                const match=ms.find(m=>m.name===want)||ms[0];
                if(match) setSelModel(match.name);
                else if(h?.model) setSelModel(h.model);
            } catch { if(h?.model) setSelModel(h.model); }
        })();
        loadRecent();
    },[loadRecent]);

    useEffect(()=>{ chatRef.current?.scrollTo({top:chatRef.current.scrollHeight,behavior:'smooth'}); },[messages]);

    const send = useCallback(async(question)=>{
        const q = (question||input).trim();
        if(!q||sending) return;
        setInput(''); setSending(true);
        const uId=Date.now(), aId=uId+1;
        setMessages(p=>[...p,{id:uId,role:'user',content:q},{id:aId,role:'ai',loading:true,question:q}]);
        try {
            const res = (await aiApi.ask(q,selModel||undefined)).data;
            setMessages(p=>p.map(m=>m.id===aId?{
                ...m,loading:false,question:q,
                summary:res.summary||(res.error?null:`Query returned ${res.rowCount||0} results.`),
                error:res.error||null, generatedSql:res.generatedSql||null,
                data:res.data||null, columns:res.columns||null,
                rowCount:res.rowCount, duration:res.duration, chartHint:res.chartHint||null,
            }:m));
        } catch(e) {
            setMessages(p=>p.map(m=>m.id===aId?{...m,loading:false,question:q,error:e.response?.data?.error||'Connection failed. Is Ollama running?'}:m));
        }
        setSending(false);
        loadRecent(); // refresh the recent strip with this new question
        setTimeout(()=>inputRef.current?.querySelector('textarea')?.focus(),100);
    },[input,sending,selModel,loadRecent]);

    const connected = health?.status==='connected';

    return (
        <Box className="lu" sx={{display:'flex',flexDirection:'column',height:'var(--vh100, 100vh)',bgcolor:P.bg,position:'relative',overflow:'hidden'}}>

            {/* ═══ AMBIENT ═══ */}
            <Box sx={{position:'absolute',inset:0,pointerEvents:'none',zIndex:0}}>
                <Box sx={{position:'absolute',top:'-10%',right:'-8%',width:600,height:600,borderRadius:'50%',background:`radial-gradient(circle,${P.accent}05,transparent 70%)`,animation:'luOrb 30s ease-in-out infinite'}}/>
                <Box sx={{position:'absolute',bottom:'-15%',left:'-10%',width:700,height:700,borderRadius:'50%',background:`radial-gradient(circle,${P.purple}04,transparent 70%)`,animation:'luOrb 40s ease-in-out infinite reverse'}}/>
            </Box>

            {/* ═══ HEADER ═══ */}
            <Box sx={{px:2.5,py:1.25,display:'flex',alignItems:'center',gap:1.5,zIndex:5,borderBottom:`1px solid ${P.border}`,bgcolor:'rgba(12,17,27,0.85)',backdropFilter:'blur(20px)'}}>
                <Box sx={{width:36,height:36,borderRadius:'10px',background:P.gAccent,display:'flex',alignItems:'center',justifyContent:'center',boxShadow:`0 3px 16px ${P.accent}20`,position:'relative'}}>
                    <BrainCircuit size={17} color="white"/>
                    {connected && <>
                        <Box sx={{position:'absolute',bottom:-2,right:-2,width:10,height:10,borderRadius:'50%',bgcolor:P.green,border:`2px solid ${P.bg}`}}/>
                        <Box sx={{position:'absolute',bottom:-2,right:-2,width:10,height:10,borderRadius:'50%',bgcolor:P.green,animation:'luPing 1.5s infinite'}}/>
                    </>}
                </Box>
                <Box sx={{flex:1}}>
                    <Stack direction="row" spacing={.75} alignItems="center">
                        <Typography sx={{fontSize:15,fontWeight:700,color:P.t1,letterSpacing:'-0.02em'}}>AI Assistant</Typography>
                        <Box sx={{px:.6,py:.1,borderRadius:'5px',background:P.gPurple}}><Typography sx={{fontSize:7.5,fontWeight:800,color:'white',letterSpacing:1,textTransform:'uppercase'}}>Beta</Typography></Box>
                    </Stack>
                    <Typography sx={{fontSize:10.5,color:P.t3,mt:-.2}}>Natural language → SQL → Insights</Typography>
                </Box>

                {models.length>0 && (
                    <Box sx={{display:'flex',alignItems:'center',gap:.6,px:1.25,py:.4,borderRadius:'8px',bgcolor:P.card,border:`1px solid ${P.border}`}}>
                        <Cpu size={11} color={P.t3}/>
                        <FormControl size="small" variant="standard">
                            <Select value={selModel} onChange={e=>setSelModel(e.target.value)} disableUnderline
                                sx={{fontSize:12,fontWeight:600,color:P.t1,'& .MuiSelect-icon':{color:P.t3},minWidth:100}}
                                MenuProps={{PaperProps:{sx:{bgcolor:P.card,border:`1px solid ${P.border}`,borderRadius:'10px',boxShadow:'0 16px 48px rgba(0,0,0,.5)','& .MuiMenuItem-root':{fontSize:12,color:P.t1,'&:hover':{bgcolor:P.raised}}}}}}>
                                {models.map(m=><MenuItem key={m.name} value={m.name}>{m.name}</MenuItem>)}
                            </Select>
                        </FormControl>
                    </Box>
                )}

                <Tooltip title={connected?'Connected':'Offline — run: ollama serve'} arrow>
                    <Box sx={{width:34,height:34,borderRadius:'8px',display:'flex',alignItems:'center',justifyContent:'center',bgcolor:connected?`${P.green}08`:`${P.rose}08`,border:`1px solid ${connected?`${P.green}12`:`${P.rose}12`}`}}>
                        {connected?<Wifi size={13} color={P.green}/>:<WifiOff size={13} color={P.rose}/>}
                    </Box>
                </Tooltip>

                {messages.length>0 && (
                    <Tooltip title="Clear chat" arrow>
                        <IconButton size="small" onClick={()=>setMessages([])} sx={{color:P.t3,'&:hover':{color:P.rose,bgcolor:`${P.rose}08`}}}>
                            <Trash2 size={14}/>
                        </IconButton>
                    </Tooltip>
                )}
            </Box>

            {/* ═══ CHAT AREA ═══ */}
            <Box ref={chatRef} sx={{flex:1,overflow:'auto',px:3,py:3,zIndex:1}}>
                <Box sx={{maxWidth:860,mx:'auto'}}>

                    {/* ═══ EMPTY STATE ═══ */}
                    {messages.length===0 && (
                        <Fade in timeout={700}>
                            <Box sx={{display:'flex',flexDirection:'column',alignItems:'center',justifyContent:'center',minHeight:'55vh',pt:4}}>
                                {/* Logo orb */}
                                <Box sx={{position:'relative',mb:4}}>
                                    <Box sx={{width:96,height:96,borderRadius:'26px',background:`linear-gradient(135deg,${P.accent}0a,${P.purple}0a,${P.teal}08)`,display:'flex',alignItems:'center',justifyContent:'center',animation:'luFloat 5s ease-in-out infinite',border:`1px solid ${P.border}`,backdropFilter:'blur(10px)'}}>
                                        <Sparkles size={38} color={P.accent} style={{opacity:.6}}/>
                                    </Box>
                                    <Box sx={{position:'absolute',top:-5,right:-5,width:24,height:24,borderRadius:'8px',background:P.gAccent,display:'flex',alignItems:'center',justifyContent:'center',boxShadow:`0 3px 12px ${P.accent}35`}}>
                                        <BrainCircuit size={11} color="white"/>
                                    </Box>
                                    <Box sx={{position:'absolute',bottom:-3,left:-3,width:10,height:10,borderRadius:'50%',bgcolor:P.green,boxShadow:`0 0 10px ${P.green}50`}}/>
                                </Box>

                                <Typography sx={{fontSize:28,fontWeight:800,color:P.t1,letterSpacing:'-0.04em',mb:.75,textAlign:'center',lineHeight:1.2}}>
                                    What do you want to know?
                                </Typography>
                                <Typography sx={{fontSize:14,color:P.t2,maxWidth:400,textAlign:'center',lineHeight:1.7,mb:1.5,fontWeight:300}}>
                                    Ask about your merchants and transactions in plain English.
                                </Typography>

                                <Stack direction="row" spacing={.5} alignItems="center" sx={{mb:4,px:1.25,py:.4,borderRadius:'16px',bgcolor:`${P.green}06`,border:`1px solid ${P.green}0c`}}>
                                    <ShieldCheck size={11} color={P.green}/>
                                    <Typography sx={{fontSize:10,color:P.green,fontWeight:600}}>Read-only · Tenant-isolated · Safe</Typography>
                                </Stack>

                                {!connected && (
                                    <Box sx={{px:2.5,py:1.5,borderRadius:'14px',bgcolor:`${P.rose}06`,border:`1px solid ${P.rose}0c`,mb:4,maxWidth:460}}>
                                        <Typography sx={{fontSize:12.5,color:'#fca5a5',lineHeight:1.7}}>
                                            <b>Ollama offline.</b> Start with:
                                        </Typography>
                                        <Box className="lu-code" sx={{mt:.75,px:1.5,py:.75,borderRadius:'8px',bgcolor:'rgba(0,0,0,.25)',fontSize:11.5,color:P.cyan}}>
                                            $ ollama serve && ollama pull llama3.2
                                        </Box>
                                    </Box>
                                )}

                                {/* Recent questions — pulled from ai_chat_history */}
                                {recent.length>0 && (
                                    <Box sx={{width:'100%',maxWidth:600,mb:2.5}}>
                                        <Stack direction="row" spacing={.6} alignItems="center" sx={{mb:1,px:.5}}>
                                            <History size={11} color={P.t3}/>
                                            <Typography sx={{fontSize:9.5,fontWeight:700,color:P.t3,textTransform:'uppercase',letterSpacing:1.2}}>Recent</Typography>
                                        </Stack>
                                        <Stack direction="row" spacing={.6} flexWrap="wrap" sx={{gap:.5}}>
                                            {recent.map(r=>(
                                                <Chip key={r.chatId} label={r.question} size="small" clickable onClick={()=>send(r.question)}
                                                    icon={r.isError?<RotateCcw size={10}/>:<Clock size={10}/>}
                                                    sx={{maxWidth:280,height:26,fontSize:11,fontWeight:500,bgcolor:'transparent',color:P.t2,border:`1px solid ${P.border}`,'& .MuiChip-label':{overflow:'hidden',textOverflow:'ellipsis'},'& .MuiChip-icon':{color:r.isError?P.rose:P.t3},transition:'all .2s','&:hover':{bgcolor:`${P.accent}08`,borderColor:`${P.accent}20`,color:P.accent,'& .MuiChip-icon':{color:P.accent}}}}/>
                                            ))}
                                        </Stack>
                                    </Box>
                                )}

                                {/* Suggestions — 2-column cards */}
                                <Box sx={{display:'grid',gridTemplateColumns:{xs:'1fr',sm:'repeat(2,1fr)'},gap:1,maxWidth:600,width:'100%'}}>
                                    {SUGGESTIONS.map(({q,icon:I,color,cat},idx)=>(
                                        <Box key={q} onClick={()=>send(q)} className="lu-hover"
                                            sx={{px:2,py:1.5,borderRadius:'12px',cursor:'pointer',bgcolor:P.card,border:`1px solid ${P.border}`,position:'relative',overflow:'hidden',animation:`luSlideIn .4s ${idx*50}ms both`,'&:hover':{borderColor:`${color}20`,bgcolor:P.raised},'&:hover .lu-arr':{opacity:1,transform:'translateX(0)'}}}>
                                            <Box sx={{position:'absolute',top:0,left:0,right:0,height:1.5,background:`linear-gradient(90deg,${color}50,transparent 70%)`,opacity:0,transition:'.3s','.lu-hover:hover &':{opacity:1}}}/>
                                            <Stack direction="row" spacing={1} alignItems="center">
                                                <Box sx={{width:30,height:30,borderRadius:'8px',bgcolor:`${color}0c`,display:'flex',alignItems:'center',justifyContent:'center',flexShrink:0,transition:'transform .25s'}}>
                                                    <I size={13} color={color}/>
                                                </Box>
                                                <Box sx={{flex:1,minWidth:0}}>
                                                    <Typography sx={{fontSize:12.5,color:P.t1,fontWeight:500,lineHeight:1.35}}>{q}</Typography>
                                                    <Typography sx={{fontSize:9.5,color:P.t3,fontWeight:600,textTransform:'uppercase',letterSpacing:1,mt:.15}}>{cat}</Typography>
                                                </Box>
                                                <ChevronRight className="lu-arr" size={14} color={color} style={{opacity:0,transform:'translateX(-4px)',transition:'all .25s'}}/>
                                            </Stack>
                                        </Box>
                                    ))}
                                </Box>

                                {/* Capabilities */}
                                <Stack direction="row" spacing={2} sx={{mt:4,opacity:.35}}>
                                    {[{i:BarChart3,t:'Auto Charts'},{i:Layers,t:'KPI Cards'},{i:Code2,t:'SQL Preview'},{i:Table2,t:'Data Tables'}].map(({i:I,t})=>(
                                        <Stack key={t} direction="row" spacing={.4} alignItems="center"><I size={10} color={P.t3}/><Typography sx={{fontSize:10,color:P.t3,fontWeight:600}}>{t}</Typography></Stack>
                                    ))}
                                </Stack>
                            </Box>
                        </Fade>
                    )}

                    {messages.map(m=><ChatMessage key={m.id} msg={m} onSend={send} onRetry={send}/>)}
                </Box>
            </Box>

            {/* ═══ INPUT BAR ═══ */}
            <Box sx={{position:'relative',zIndex:10,px:2.5,pb:2,pt:.5}}>
                <Box sx={{position:'absolute',top:-50,left:0,right:0,height:50,background:`linear-gradient(to bottom,transparent,${P.bg})`,pointerEvents:'none'}}/>
                <Box sx={{maxWidth:780,mx:'auto'}}>
                    <Box className="lu-glow" sx={{display:'flex',gap:1,alignItems:'flex-end',px:2,py:1.25,borderRadius:'16px',bgcolor:P.surface,border:`1px solid ${P.border}`,transition:'all .35s cubic-bezier(.16,1,.3,1)'}}>
                        <Sparkles size={15} color={P.accent} style={{opacity:.25,marginBottom:4}}/>
                        <TextField ref={inputRef} fullWidth multiline maxRows={4}
                            placeholder={connected?"Ask anything about your data…":"Ollama offline…"}
                            value={input} onChange={e=>setInput(e.target.value)}
                            onKeyDown={e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send();}}}
                            disabled={!connected||sending} variant="standard"
                            InputProps={{disableUnderline:true,sx:{fontSize:14,color:P.t1,fontWeight:400,lineHeight:1.6,'& textarea::placeholder':{color:P.t3,opacity:1}}}}
                            sx={{'& .MuiInputBase-root':{p:0}}}/>
                        <Tooltip title="Send (Enter)" arrow>
                            <span>
                                <IconButton onClick={()=>send()} disabled={!input.trim()||!connected||sending}
                                    sx={{width:38,height:38,borderRadius:'10px',mb:-.25,background:input.trim()&&connected&&!sending?P.gAccent:P.card,color:'white',transition:'all .25s','&:hover':{boxShadow:`0 4px 20px ${P.accent}30`,transform:'scale(1.04)'},'&.Mui-disabled':{opacity:.15,color:P.t3}}}>
                                    {sending?<Box sx={{width:15,height:15,borderRadius:'50%',border:'2px solid rgba(255,255,255,.8)',borderTopColor:'transparent',animation:'luSpin .7s linear infinite'}}/>:<ArrowUp size={16}/>}
                                </IconButton>
                            </span>
                        </Tooltip>
                    </Box>
                    <Stack direction="row" spacing={.4} justifyContent="center" alignItems="center" sx={{mt:.75,opacity:.3}}>
                        <Cpu size={9} color={P.t3}/>
                        <Typography sx={{fontSize:9.5,color:P.t3,fontWeight:500}}>{selModel||'No model'} · Enter to send</Typography>
                    </Stack>
                </Box>
            </Box>
        </Box>
    );
}
