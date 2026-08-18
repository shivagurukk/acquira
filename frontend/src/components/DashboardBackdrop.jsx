import React from 'react';

/**
 * Ambient animated backdrop for the app shell.
 *
 * Three slow-drifting blurred orbs, a drifting technical grid and a single
 * scan sweep, all at very low opacity so the data stays the brightest thing
 * on screen. Purely decorative: fixed, pointer-events:none, GPU-composited
 * transforms only, and every animation is disabled under
 * `prefers-reduced-motion` (see .dx-backdrop rules in index.css).
 */
const DashboardBackdrop = () => (
    <div className="dx-backdrop" aria-hidden="true">
        <div className="dx-backdrop__grid" />
        <div className="dx-backdrop__orb dx-backdrop__orb--a" />
        <div className="dx-backdrop__orb dx-backdrop__orb--b" />
        <div className="dx-backdrop__orb dx-backdrop__orb--c" />
        <div className="dx-backdrop__scan" />
    </div>
);

export default DashboardBackdrop;
