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
            bgcolor: 'var(--bg-card, #fff)',
            borderRadius: 'var(--radius-lg, 14px)',
            border: '1px solid var(--border, #e5e7eb)',
            padding: padding || 'var(--space-card, 24px)',
            cursor: onClick ? 'pointer' : 'default',
            transition: hover ? 'all 0.2s ease' : undefined,
            '&:hover': hover ? {
                boxShadow: 'var(--shadow-hover)',
                transform: 'translateY(-2px)',
                borderColor: 'var(--border, #d1d5db)',
            } : {},
            ...sx,
        }}
    >
        {children}
    </Box>
);

export default CardWrapper;
