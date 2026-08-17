import React, { useEffect, useRef, useState } from 'react';

/**
 * LoginBackdrop — the animated "payment intelligence" scene behind the
 * sign-in card: perspective grid floor, drifting particles, the acquiring
 * rail network (POS/ECOM → switch → schemes → settlement/MIS) with packets
 * travelling the rails, four holographic telemetry panels, and the top
 * environment strip. Pure decoration: everything is aria-hidden and
 * pointer-events:none, and all figures are simulated.
 */

const MERCHANTS = ['LULU HYPER BH', 'JASHANMAL', 'ALOSRA MKT', 'CITY CTR CINEMA', 'TALABAT', 'GULF AIR', 'AL ZAIN JWL', 'SEEF PHCY', 'ALMOAYYED MOT', 'NASS FOODS'];
const SCHEMES = ['VISA', 'MC', 'AMEX', 'DEBIT'];

const fmtN = (v) => Math.round(v).toLocaleString('en-US');

// Ease-out cubic count-up shared by the KPI tiles and radial legend.
const useCountUp = (target, dur, fmt = fmtN) => {
    const [text, setText] = useState(fmt(0));
    useEffect(() => {
        let raf;
        const t0 = performance.now();
        const frame = (t) => {
            const k = Math.min(1, (t - t0) / dur);
            const e = 1 - Math.pow(1 - k, 3);
            setText(fmt(target * e));
            if (k < 1) raf = requestAnimationFrame(frame);
        };
        raf = requestAnimationFrame(frame);
        return () => cancelAnimationFrame(raf);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [target, dur]);
    return text;
};

const Particles = () => {
    const ref = useRef(null);
    useEffect(() => {
        const cv = ref.current;
        const cx = cv.getContext('2d');
        let W, H, raf;
        const size = () => { W = cv.width = window.innerWidth; H = cv.height = window.innerHeight; };
        size();
        window.addEventListener('resize', size);
        const pts = Array.from({ length: 70 }, () => ({
            x: Math.random() * W, y: Math.random() * H,
            vx: (Math.random() - 0.5) * 0.22, vy: (Math.random() - 0.5) * 0.22,
            r: Math.random() * 1.5 + 0.4, a: Math.random() * 0.5 + 0.15,
        }));
        const tick = () => {
            cx.clearRect(0, 0, W, H);
            for (const p of pts) {
                p.x += p.vx; p.y += p.vy;
                if (p.x < 0) p.x = W; if (p.x > W) p.x = 0;
                if (p.y < 0) p.y = H; if (p.y > H) p.y = 0;
                cx.beginPath();
                cx.arc(p.x, p.y, p.r, 0, 7);
                cx.fillStyle = `rgba(0,212,255,${p.a})`;
                cx.fill();
            }
            raf = requestAnimationFrame(tick);
        };
        raf = requestAnimationFrame(tick);
        return () => { cancelAnimationFrame(raf); window.removeEventListener('resize', size); };
    }, []);
    return <canvas ref={ref} className="nxl-particles" aria-hidden="true" />;
};

const Rails = () => (
    <svg className="nxl-rails" viewBox="0 0 1600 900" preserveAspectRatio="xMidYMid slice" aria-hidden="true" focusable="false">
        <defs>
            <linearGradient id="nxlRailGrad" x1="0" y1="0" x2="1" y2="0">
                <stop offset="0" stopColor="#2f6bff" stopOpacity=".9" />
                <stop offset="1" stopColor="#00d4ff" stopOpacity=".9" />
            </linearGradient>
            <radialGradient id="nxlPk" cx=".5" cy=".5" r=".5">
                <stop offset="0" stopColor="#bfe9ff" />
                <stop offset=".5" stopColor="#00d4ff" />
                <stop offset="1" stopColor="#00d4ff" stopOpacity="0" />
            </radialGradient>
        </defs>

        {/* rails: POS → SWITCH → SCHEME → SETTLEMENT ; ECOM joins at switch */}
        <path id="nxl-r1" className="nxl-rail" d="M 120,700 C 330,640 420,520 520,470" />
        <path id="nxl-r2" className="nxl-rail slow" d="M 150,240 C 320,300 430,400 520,462" />
        <path id="nxl-r3" className="nxl-rail" d="M 540,466 C 720,430 880,430 1060,462" />
        <path id="nxl-r4" className="nxl-rail slow" d="M 1080,468 C 1230,510 1330,600 1470,660" />
        <path id="nxl-r5" className="nxl-rail slow" d="M 1080,458 C 1220,410 1330,320 1450,250" />

        {/* nodes */}
        <g transform="translate(120,700)">
            <circle className="nxl-node-ring" r="14" /><circle className="nxl-node-core" r="7" />
            <text className="nxl-node-label" x="-16" y="34">POS RAIL</text>
            <text className="nxl-node-sub" x="-16" y="48">TERMINAL FLEET</text>
        </g>
        <g transform="translate(150,240)">
            <circle className="nxl-node-ring" r="14" style={{ animationDelay: '.8s' }} /><circle className="nxl-node-core" r="7" />
            <text className="nxl-node-label" x="-18" y="-22">ECOM RAIL</text>
            <text className="nxl-node-sub" x="-18" y="-9">GATEWAY 3DS</text>
        </g>
        <g transform="translate(530,465)">
            <circle className="nxl-node-ring" r="18" style={{ animationDelay: '.4s' }} /><circle className="nxl-node-core" r="9" />
            <text className="nxl-node-label" x="-30" y="42">ACQ SWITCH</text>
            <text className="nxl-node-sub" x="-30" y="56">AUTH · 42 MS</text>
        </g>
        <g transform="translate(1070,464)">
            <circle className="nxl-node-ring" r="18" style={{ animationDelay: '1.3s' }} /><circle className="nxl-node-core" r="9" />
            <text className="nxl-node-label" x="-28" y="42">SCHEMES</text>
            <text className="nxl-node-sub" x="-28" y="56">VISA · MC · AMEX</text>
        </g>
        <g transform="translate(1470,660)">
            <circle className="nxl-node-ring" r="14" style={{ animationDelay: '2s' }} /><circle className="nxl-node-core" r="7" />
            <text className="nxl-node-label" x="-70" y="34">SETTLEMENT</text>
            <text className="nxl-node-sub" x="-70" y="48">T+1 · BHD</text>
        </g>
        <g transform="translate(1450,250)">
            <circle className="nxl-node-ring" r="14" style={{ animationDelay: '1.6s' }} /><circle className="nxl-node-core" r="7" />
            <text className="nxl-node-label" x="-56" y="-20">MIS CORE</text>
            <text className="nxl-node-sub" x="-56" y="-7">WAREHOUSE SYNC</text>
        </g>

        {/* packets travelling the rails */}
        <circle className="nxl-packet" r="4" fill="url(#nxlPk)"><animateMotion dur="2.8s" repeatCount="indefinite"><mpath href="#nxl-r1" /></animateMotion></circle>
        <circle className="nxl-packet" r="3.4" fill="url(#nxlPk)"><animateMotion dur="3.6s" begin="1.1s" repeatCount="indefinite"><mpath href="#nxl-r2" /></animateMotion></circle>
        <circle className="nxl-packet" r="4" fill="url(#nxlPk)"><animateMotion dur="2.4s" begin=".4s" repeatCount="indefinite"><mpath href="#nxl-r3" /></animateMotion></circle>
        <circle className="nxl-packet" r="3.4" fill="url(#nxlPk)"><animateMotion dur="2.4s" begin="1.6s" repeatCount="indefinite"><mpath href="#nxl-r3" /></animateMotion></circle>
        <circle className="nxl-packet" r="3.6" fill="url(#nxlPk)"><animateMotion dur="3.1s" begin=".9s" repeatCount="indefinite"><mpath href="#nxl-r4" /></animateMotion></circle>
        <circle className="nxl-packet" r="3.2" fill="url(#nxlPk)"><animateMotion dur="3.4s" begin="2s" repeatCount="indefinite"><mpath href="#nxl-r5" /></animateMotion></circle>
    </svg>
);

const Topbar = () => {
    const [lat, setLat] = useState(42);
    const [tps, setTps] = useState(318);
    useEffect(() => {
        const iv = setInterval(() => {
            setLat(38 + Math.floor(Math.random() * 12));
            setTps(290 + Math.floor(Math.random() * 70));
        }, 2200);
        return () => clearInterval(iv);
    }, []);
    return (
        <div className="nxl-topbar" aria-hidden="true">
            <div>AFS NEXUS · ENTERPRISE PAYMENT INTELLIGENCE</div>
            <div className="live">
                <span>RAILS <b className="g">● ONLINE</b></span>
                <span>LATENCY <b>{lat} MS</b></span>
                <span>TPS <b>{tps}</b></span>
                <span>REGION <b>BH-1</b></span>
            </div>
        </div>
    );
};

const SettlePanel = () => (
    <div className="nxl-holo nxl-p-settle">
        <div className="hd"><span className="dot" />SETTLEMENT&nbsp;&amp;&nbsp;RECONCILIATION</div>
        <div className="bd">
            <div className="batch-row"><span className="batch-id">BATCH STL-4473</span><span className="batch-ccy">BHD</span></div>
            <div className="stages">
                <div className="stage done"><div className="tick" /><div className="nm">AUTHORISED</div><div className="tm">12 AUG 09:14</div><div className="amt">204,608.750</div></div>
                <div className="stage done"><div className="tick" /><div className="nm">CLEARED</div><div className="tm">12 AUG 23:40</div><div className="amt">204,608.750</div></div>
                <div className="stage done"><div className="tick" /><div className="nm">SETTLED</div><div className="tm">T+1 06:00</div><div className="amt">204,608.750</div></div>
            </div>
            <div className="recon-bar"><i /></div>
            <div className="settle-foot"><span className="m">✓ MATCHED TO ACQUIRER FILE</span><span className="b">0 BREAKS</span></div>
        </div>
    </div>
);

const StreamPanel = () => {
    const [rows, setRows] = useState([]);
    useEffect(() => {
        let id = 0;
        const makeTxn = () => {
            const t = new Date();
            const pad = (n) => String(n).padStart(2, '0');
            return {
                id: id++,
                time: `${pad(t.getHours())}:${pad(t.getMinutes())}:${pad(t.getSeconds())}`,
                scheme: SCHEMES[Math.floor(Math.random() * SCHEMES.length)],
                merchant: MERCHANTS[Math.floor(Math.random() * MERCHANTS.length)],
                amt: (Math.random() * 420 + 3).toFixed(3),
            };
        };
        setRows(Array.from({ length: 6 }, makeTxn));
        const iv = setInterval(() => setRows(prev => [...prev, makeTxn()].slice(-6)), 1400);
        return () => clearInterval(iv);
    }, []);
    return (
        <div className="nxl-holo nxl-p-stream">
            <div className="hd"><span className="dot" />LIVE AUTHORISATION FEED</div>
            <div className="bd">
                <div className="stream">
                    {rows.map(r => (
                        <div className="txn" key={r.id}>
                            <span className="t">{r.time}</span>
                            <span className="s">{r.scheme}</span>
                            <span>{r.merchant}</span>
                            <span className="a">{r.amt} <span className="ok">✓</span></span>
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
};

const Sparkline = () => {
    const [d, setD] = useState({ line: '', fill: '' });
    useEffect(() => {
        const n = 36, pts = [];
        let v = 28;
        for (let i = 0; i < n; i++) {
            v += Math.sin(i / 4.2) * 3 + (Math.random() - 0.42) * 4;
            v = Math.max(10, Math.min(50, v));
            pts.push(v);
        }
        const step = 280 / (n - 1);
        const line = pts.map((p, i) => `${i ? 'L' : 'M'}${(i * step).toFixed(1)},${(54 - p * 0.92).toFixed(1)}`).join(' ');
        setD({ line, fill: `${line} L280,56 L0,56 Z` });
    }, []);
    return (
        <svg viewBox="0 0 280 56" preserveAspectRatio="none">
            <defs>
                <linearGradient id="nxlSg" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0" stopColor="#00d4ff" stopOpacity=".45" />
                    <stop offset="1" stopColor="#00d4ff" stopOpacity="0" />
                </linearGradient>
            </defs>
            <path fill="url(#nxlSg)" d={d.fill} />
            <path fill="none" stroke="#00d4ff" strokeWidth="1.6" style={{ filter: 'drop-shadow(0 0 5px rgba(0,212,255,.7))' }} d={d.line} />
        </svg>
    );
};

const KpiPanel = () => {
    const vol = useCountUp(4820000, 1800, v => `BD ${(v / 1e6).toFixed(2)}M`);
    const txn = useCountUp(186240, 1800);
    const msf = useCountUp(68400, 1800, v => `BD ${fmtN(v)}`);
    const mid = useCountUp(9873, 1800);
    return (
        <div className="nxl-holo nxl-p-kpi">
            <div className="hd"><span className="dot" />MIS · TODAY</div>
            <div className="bd">
                <div className="kpis">
                    <div className="kpi"><div className="l">Volume</div><div className="v">{vol}</div><div className="d up">▲ 6.2% vs LW</div></div>
                    <div className="kpi"><div className="l">Txn Count</div><div className="v">{txn}</div><div className="d up">▲ 3.8%</div></div>
                    <div className="kpi"><div className="l">Net MSF</div><div className="v">{msf}</div><div className="d up">▲ 4.4%</div></div>
                    <div className="kpi"><div className="l">Active MIDs</div><div className="v">{mid}</div><div className="d flat">— 12,408 total</div></div>
                </div>
                <div className="spark">
                    <div className="lbl"><span>VOLUME · 24H</span><span>BHD ’000</span></div>
                    <Sparkline />
                </div>
            </div>
        </div>
    );
};

const RadialPanel = () => {
    const approved = useCountUp(178400, 2000);
    const declined = useCountUp(6210, 2000);
    const chall = useCountUp(1630, 2000);
    const [pct, setPct] = useState(0);
    useEffect(() => {
        let v = 0;
        const iv = setInterval(() => {
            v += 1.7;
            if (v >= 95.8) { v = 95.8; clearInterval(iv); }
            setPct(v);
        }, 34);
        return () => clearInterval(iv);
    }, []);
    return (
        <div className="nxl-holo nxl-p-radial">
            <div className="hd"><span className="dot" />AUTHORISATION HEALTH</div>
            <div className="bd">
                <div className="radial-wrap">
                    <div className="r-col">
                        <svg className="ring-svg" viewBox="0 0 96 96">
                            <defs>
                                <linearGradient id="nxlRingGrad" x1="0" y1="0" x2="1" y2="1">
                                    <stop offset="0" stopColor="#2f6bff" /><stop offset="1" stopColor="#00d4ff" />
                                </linearGradient>
                            </defs>
                            <circle className="ring-bg" cx="48" cy="48" r="42" />
                            <circle className="ring-fg" cx="48" cy="48" r="42" />
                        </svg>
                        <div className="ring-num">{pct.toFixed(1)}%</div>
                    </div>
                    <div className="r-legend">
                        APPROVED <b className="up">{approved}</b><br />
                        DECLINED <b style={{ color: '#f87171' }}>{declined}</b><br />
                        3DS CHALL. <b>{chall}</b>
                    </div>
                </div>
            </div>
        </div>
    );
};

const LoginBackdrop = () => (
    <div className="nxl-backdrop" aria-hidden="true">
        <div className="nxl-grid-floor" />
        <div className="nxl-grid-glow" />
        <Particles />
        <Rails />
        <Topbar />
        <SettlePanel />
        <StreamPanel />
        <KpiPanel />
        <RadialPanel />
    </div>
);

export default LoginBackdrop;
