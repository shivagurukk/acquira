import React, { useEffect, useRef, useState } from 'react';

/* ============================================================================
   Rotating statement deck for the sign-in brand panel.
   Four slides, each pairing a claim with a small purpose-built visual — and
   none of them a chart. Each visual is an instrument from the acquiring
   trade itself: an authorisation route, a merchant activity punch-card, a
   pricing rate card, and a settlement lifecycle. All hand-drawn SVG/CSS in
   the panel's blueprint voice, annotated in mono.

   Transitions are CSS, driven by remounting the slide on its key: the entry
   animation on .nx-slide and the ones inside each visual replay together on
   every rotation. The progress bar is a CSS animation too, so a slide costs
   exactly one React render rather than one per frame.
   ========================================================================== */

const SLIDE_MS = 6500;

/* ------------------------------ visuals -------------------------------- */

/* Slide 1 — the anatomy of one authorisation.
   The route a card payment takes, then the fee split of that single
   transaction: the product's whole promise in one drawing. */
const FEES = [
    { label: 'Interchange', amount: '− 0.800' },
    { label: 'Scheme fees', amount: '− 0.110' },
    { label: 'Net MSF margin', amount: '+ 0.520', lead: true },
];

const TransactionAnatomy = () => (
    <figure className="nx-viz">
        <figcaption className="nx-viz__head">
            <span className="nx-viz__label">One authorisation</span>
            <span className="nx-viz__figure">BHD 100.000</span>
        </figcaption>
        <svg className="nx-route" viewBox="0 0 320 66" aria-hidden="true" focusable="false">
            {/* Dashed route segments between the four parties. */}
            <line className="nx-route__seg" x1="50" y1="28" x2="105" y2="28" style={{ animationDelay: '120ms' }} />
            <line className="nx-route__seg" x1="131" y1="28" x2="184" y2="28" style={{ animationDelay: '280ms' }} />
            <line className="nx-route__seg" x1="221" y1="28" x2="272" y2="28" style={{ animationDelay: '440ms' }} />

            {/* Card */}
            <g className="nx-route__node" style={{ animationDelay: '40ms' }}>
                <rect x="22" y="19" width="26" height="18" rx="3" />
                <line x1="22" y1="25" x2="48" y2="25" />
            </g>
            {/* POS terminal */}
            <g className="nx-route__node" style={{ animationDelay: '200ms' }}>
                <rect x="109" y="16" width="19" height="24" rx="3" />
                <rect x="113" y="20" width="11" height="7" rx="1" />
            </g>
            {/* Scheme — interlocked pair */}
            <g className="nx-route__node is-lead" style={{ animationDelay: '360ms' }}>
                <circle cx="196" cy="28" r="9" />
                <circle cx="208" cy="28" r="9" />
            </g>
            {/* Issuer bank */}
            <g className="nx-route__node" style={{ animationDelay: '520ms' }}>
                <path d="M276 22 L286 14 L296 22" />
                <line x1="279" y1="24" x2="279" y2="36" />
                <line x1="286" y1="24" x2="286" y2="36" />
                <line x1="293" y1="24" x2="293" y2="36" />
                <line x1="275" y1="38" x2="297" y2="38" />
            </g>

            <text x="35" y="58">CARD</text>
            <text x="118" y="58">POS</text>
            <text x="202" y="58">SCHEME</text>
            <text x="286" y="58">ISSUER</text>
        </svg>
        <ul className="nx-fees">
            {FEES.map((f, i) => (
                <li key={f.label} className={f.lead ? 'is-lead' : ''} style={{ animationDelay: `${600 + i * 110}ms` }}>
                    <span>{f.label}</span>
                    <span className="nx-fees__leader" aria-hidden="true" />
                    <span className="nx-fees__amt">{f.amount}</span>
                </li>
            ))}
        </ul>
        <div className="nx-viz__foot">
            <span>Per transaction, not per estimate</span>
            <span>MSF 1.43%</span>
        </div>
    </figure>
);

/* Slide 2 — merchant pulse.
   Fourteen days of card activity per merchant, punch-card style. Attrition
   is visible as a row going quiet — no chart needed. */
const PULSE = [
    { ref: 'MRC-0112', days: [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1], status: 'Active' },
    { ref: 'MRC-0447', days: [1, 1, 0, 1, 1, 0, 1, 0, 1, 0, 0, 1, 0, 0], status: 'Slowing' },
    { ref: 'MRC-0731', days: [1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0], status: 'At risk', flag: true },
];

const MerchantPulse = () => (
    <figure className="nx-viz">
        <figcaption className="nx-viz__head">
            <span className="nx-viz__label">Merchant pulse · 14 days</span>
            <span className="nx-viz__figure">37 flagged</span>
        </figcaption>
        <div className="nx-pulse">
            {PULSE.map((m, r) => (
                <div key={m.ref} className={`nx-pulse__row${m.flag ? ' is-flag' : ''}`} style={{ animationDelay: `${r * 120}ms` }}>
                    <span className="nx-pulse__ref">{m.ref}</span>
                    <span className="nx-pulse__days" aria-hidden="true">
                        {m.days.map((on, d) => (
                            <span key={d} className={`nx-pulse__day${on ? '' : ' is-off'}`}
                                style={{ animationDelay: `${r * 120 + d * 30}ms` }} />
                        ))}
                    </span>
                    <span className="nx-pulse__status">{m.status}</span>
                </div>
            ))}
        </div>
        <div className="nx-viz__foot">
            <span>Daily card-present activity</span>
            <span>Baseline: complete months</span>
        </div>
    </figure>
);

/* Slide 3 — the rate card.
   Agreed vs effective MSF by segment, the way a pricing schedule reads —
   drift is a number in a column, and one row is flagged. */
const RATES = [
    { seg: 'Retail POS', agreed: '1.60', eff: '1.58', drift: '−2' },
    { seg: 'E-commerce', agreed: '1.85', eff: '1.83', drift: '−2' },
    { seg: 'QSR chains', agreed: '1.40', eff: '1.21', drift: '−19', flag: true },
];

const RateCard = () => (
    <figure className="nx-viz">
        <figcaption className="nx-viz__head">
            <span className="nx-viz__label">Rate card · MSF %</span>
            <span className="nx-viz__figure">3 segments</span>
        </figcaption>
        <div className="nx-rate">
            <div className="nx-rate__row nx-rate__row--head" aria-hidden="true">
                <span>Segment</span><span>Agreed</span><span>Effective</span><span>bps</span>
            </div>
            {RATES.map((r, i) => (
                <div key={r.seg} className={`nx-rate__row${r.flag ? ' is-flag' : ''}`} style={{ animationDelay: `${i * 110}ms` }}>
                    <span className="nx-rate__seg">{r.seg}</span>
                    <span className="nx-rate__num">{r.agreed}</span>
                    <span className="nx-rate__num">{r.eff}</span>
                    <span className="nx-rate__num">{r.drift}</span>
                </div>
            ))}
        </div>
        <div className="nx-viz__foot">
            <span>Interchange normalised</span>
            <span>Repricing value BHD 84K / yr</span>
        </div>
    </figure>
);

/* Slide 4 — the settlement lifecycle.
   One batch, three stages, the same amount at every stage. Books that
   agree, shown literally. */
const STAGES = [
    { label: 'Authorised', time: '12 Aug 09:14' },
    { label: 'Cleared', time: '12 Aug 23:40' },
    { label: 'Settled', time: 'T+1 06:00', lead: true },
];

const SettlementLifecycle = () => (
    <figure className="nx-viz">
        <figcaption className="nx-viz__head">
            <span className="nx-viz__label">Batch STL-4473</span>
            <span className="nx-viz__figure">BHD</span>
        </figcaption>
        <div className="nx-flow">
            {STAGES.map((s, i) => (
                <div key={s.label} className={`nx-flow__stage${s.lead ? ' is-lead' : ''}`} style={{ animationDelay: `${i * 160}ms` }}>
                    <span className="nx-flow__label">{s.label}</span>
                    <span className="nx-flow__time">{s.time}</span>
                    <span className="nx-flow__amt">204,608.750</span>
                </div>
            ))}
        </div>
        <div className="nx-viz__foot">
            <span>Matched to acquirer file</span>
            <span>0 breaks</span>
        </div>
    </figure>
);

/* ------------------------------- slides -------------------------------- */

const SLIDES = [
    {
        id: 'anatomy',
        eyebrow: 'Transaction intelligence',
        headline: <>Every authorisation, <em>priced and understood</em></>,
        support: 'Interchange, scheme fees and MSF resolved transaction by transaction — so margin on every card, brand and channel is a number, not an estimate.',
        Visual: TransactionAnatomy,
    },
    {
        id: 'pulse',
        eyebrow: 'Portfolio monitoring',
        headline: <>Know which merchants are <em>slipping away</em></>,
        support: 'Attrition is classified against complete months of data, so a merchant that has gone quiet surfaces while there is still time to act on it.',
        Visual: MerchantPulse,
    },
    {
        id: 'ratecard',
        eyebrow: 'Revenue optimisation',
        headline: <>Find the margin <em>hiding in your portfolio</em></>,
        support: 'Compare effective rates across segments on a normalised basis and see exactly where pricing has drifted from the schedule you agreed.',
        Visual: RateCard,
    },
    {
        id: 'settlement',
        eyebrow: 'Settlement & reconciliation',
        headline: <>Books that agree, <em>down to the fils</em></>,
        support: 'Every batch reconciled against the acquirer file, with a complete audit trail behind each adjustment and the people who approved it.',
        Visual: SettlementLifecycle,
    },
];

/* ------------------------------- deck ---------------------------------- */

const reduceMotion = () =>
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches;

const BrandDeck = () => {
    const [index, setIndex] = useState(0);
    const [paused, setPaused] = useState(false);
    // Read once: a viewer who has asked for less motion gets a static panel,
    // and flipping the setting mid-session is not worth a listener here.
    const [still] = useState(reduceMotion);

    // Pausing has to hold the slide where it is rather than restart it, so the
    // unspent time is carried across the pause and the timer resumes with it.
    const remaining = useRef(SLIDE_MS);
    const startedAt = useRef(0);

    useEffect(() => { remaining.current = SLIDE_MS; }, [index]);

    useEffect(() => {
        if (still || paused) return undefined;
        startedAt.current = Date.now();
        const id = setTimeout(
            () => setIndex(i => (i + 1) % SLIDES.length),
            remaining.current,
        );
        return () => {
            clearTimeout(id);
            remaining.current = Math.max(0, remaining.current - (Date.now() - startedAt.current));
        };
    }, [index, paused, still]);

    const slide = SLIDES[index];
    const { Visual } = slide;

    return (
        <div
            className="nx-deck"
            onMouseEnter={() => setPaused(true)}
            onMouseLeave={() => setPaused(false)}
            onFocusCapture={() => setPaused(true)}
            onBlurCapture={() => setPaused(false)}
        >
            {/* Slides carry live copy, so announce changes politely rather than
                leaving a screen reader on whichever slide happened to load. */}
            <div className="nx-deck__stage" aria-live="polite" aria-atomic="true">
                <div className="nx-slide" key={slide.id}>
                    <p className="nx-eyebrow">{slide.eyebrow}</p>
                    <h1 className="nx-headline" id="nx-headline">{slide.headline}</h1>
                    <p className="nx-support">{slide.support}</p>
                    <Visual />
                </div>
            </div>

            <div className="nx-deck__nav">
                {SLIDES.map((s, i) => (
                    <button
                        key={s.id}
                        type="button"
                        className={`nx-deck__dot${i === index ? ' is-active' : ''}`}
                        aria-label={`Show ${s.eyebrow}`}
                        aria-current={i === index ? 'true' : undefined}
                        onClick={() => setIndex(i)}
                    >
                        <span
                            className="nx-deck__fill"
                            /* Remounted per slide so the fill animation restarts;
                               past slides stay full, future ones stay empty. */
                            key={index}
                            data-state={i < index ? 'done' : i > index ? 'todo' : 'live'}
                            style={i === index && !still
                                ? { animationDuration: `${SLIDE_MS}ms`, animationPlayState: paused ? 'paused' : 'running' }
                                : undefined}
                        />
                    </button>
                ))}
            </div>
        </div>
    );
};

export default BrandDeck;
