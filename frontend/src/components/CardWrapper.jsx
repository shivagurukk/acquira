import React from 'react';
import { Box } from '@mui/material';

/**
 * CardWrapper — Consistent card container used across all pages.
 * Modern minimal: no accent borders, clean edges, subtle depth.
 */
const CardWrapper = ({ children, padding, hover = false, accent, onClick, sx = {} }) => (
    <Box
        onClick={onClick}
        sx={{
            // Slow steel border sweep — same two-layer technique as
            // .dx-card in index.css (which registers --dxa + keyframes).
            background: `                radial-gradient(140% 90% at 50% 0%,
                  color-mix(in srgb, var(--primary) var(--dxg, 6%), transparent) 0%,
                  transparent 60%) padding-box,
                var(--dx-card-grid),
                conic-gradient(from var(--dxa),
                  var(--border) 0deg, var(--border) 280deg,
      color-mix(in srgb, var(--primary) 40%, var(--border)) 310deg,
      var(--primary) 332deg,
      var(--border) 352deg) border-box`,
            borderRadius: 'var(--radius-lg)',
            border: '2px solid transparent',
            boxShadow: 'var(--shadow-card)',
            animation: 'dxBorderSweep 6s linear infinite, dxGridPulse 5s ease-in-out infinite',
            padding: padding || 'var(--space-card, 24px)',
            cursor: onClick ? 'pointer' : 'default',
            transition: hover ? 'box-shadow 220ms ease, transform 220ms ease' : undefined,
            '&:hover': hover ? {
                boxShadow: 'var(--shadow-hover)',
                transform: 'translateY(-2px)',
            } : {},
            '@media (prefers-reduced-motion: reduce)': {
                transition: 'none',
                animation: 'none',
                '&:hover': hover ? { transform: 'none' } : {},
            },
            ...sx,
        }}
    >
        {children}
    </Box>
);

export default CardWrapper;
