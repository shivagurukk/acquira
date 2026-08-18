import React from 'react';
import { gradientId } from '../theme/chartPalette';

/**
 * Reusable Recharts <defs>. Drop inside any chart and reference a fill as
 * `fill={`url(#${gradientId('volume')})`}`.
 *
 * `series` is a map of key → colour (usually a slice of SERIES from
 * theme/chartPalette). Each entry produces a vertical fade from the colour
 * at `from` opacity down to `to` opacity — the soft gradient body that gives
 * the bars and areas their depth.
 */
const ChartGradients = ({ series, from = 0.92, to = 0.38, direction = 'vertical' }) => {
    const coords = direction === 'vertical'
        ? { x1: '0', y1: '0', x2: '0', y2: '1' }
        : { x1: '0', y1: '0', x2: '1', y2: '0' };

    return (
        <defs>
            {Object.entries(series).map(([key, color]) => (
                <linearGradient key={key} id={gradientId(key)} {...coords}>
                    <stop offset="0%" stopColor={color} stopOpacity={from} />
                    <stop offset="100%" stopColor={color} stopOpacity={to} />
                </linearGradient>
            ))}
        </defs>
    );
};

export default ChartGradients;
