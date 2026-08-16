import React, { useId } from 'react';

/**
 * AFS lettermark.
 *
 * PLACEHOLDER — this is set from type, not the official Arab Financial
 * Services artwork. To drop in the real logo, replace the contents of the
 * <svg> below (keep the 52×52 viewBox and let the artwork bleed to its
 * edges). Every call site sizes the mark through the `size` prop, so no
 * other file needs to change when the official asset arrives.
 */
export const AfsMark = ({ size = 52, className = '' }) => {
    const gradId = useId();
    return (
        <svg
            width={size} height={size} viewBox="0 0 52 52"
            className={className} role="img" aria-label="AFS"
        >
            <defs>
                <linearGradient id={gradId} x1="0" y1="0" x2="1" y2="1">
                    <stop offset="0%" stopColor="#1E3A8A" />
                    <stop offset="38%" stopColor="#1D4ED8" />
                    <stop offset="72%" stopColor="#2563EB" />
                    <stop offset="100%" stopColor="#60A5FA" />
                </linearGradient>
            </defs>
            <rect width="52" height="52" rx="15" fill={`url(#${gradId})`} />
            <text
                x="26" y="27" textAnchor="middle" dominantBaseline="central"
                fill="#FFFFFF" fontFamily="inherit" fontSize="15.5"
                fontWeight="700" letterSpacing="-0.5"
            >
                AFS
            </text>
        </svg>
    );
};

export default AfsMark;
