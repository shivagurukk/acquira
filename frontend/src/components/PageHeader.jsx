import React from 'react';
import { Box, Typography, Stack, Breadcrumbs, Link as MuiLink } from '@mui/material';
import { useLocation, Link } from 'react-router-dom';

// Page header — hairline bottom border, no icon chip (icons are a
// sidebar-only affordance in this design system). The `icon` prop is
// still accepted from existing callers but intentionally unused.
const PageHeader = ({ title, subtitle, actions, breadcrumbs, icon: _icon, noBorder = false }) => {
    const location = useLocation();

    const autoBreadcrumbs = React.useMemo(() => {
        if (breadcrumbs) return breadcrumbs;
        const segments = location.pathname.split('/').filter(Boolean);
        if (segments.length <= 1) return [];
        return segments.slice(0, -1).map((seg, i) => ({
            label: seg.charAt(0).toUpperCase() + seg.slice(1).replace(/-/g, ' '),
            path: '/' + segments.slice(0, i + 1).join('/'),
        }));
    }, [location.pathname, breadcrumbs]);

    return (
        <Box sx={{
            px: 'var(--space-page)',
            pt: '16px',
            pb: '12px',
            borderBottom: noBorder ? 'none' : '1px solid var(--border)',
            background: 'var(--bg-card)',
            position: 'sticky',
            top: 0,
            zIndex: 10,
        }}>
            {/* Breadcrumbs */}
            {autoBreadcrumbs.length > 0 && (
                <Breadcrumbs
                    separator={<span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>/</span>}
                    sx={{ mb: 0.75 }}
                >
                    <MuiLink component={Link} to="/dashboard" underline="none"
                        sx={{
                            fontSize: '12px', color: 'var(--text-muted)',
                            '&:hover': { color: 'var(--primary)' },
                            transition: 'color 150ms',
                        }}>
                        Home
                    </MuiLink>
                    {autoBreadcrumbs.map((crumb, i) => (
                        <MuiLink key={i} component={Link} to={crumb.path} underline="none"
                            sx={{
                                fontSize: '12px', color: 'var(--text-muted)',
                                '&:hover': { color: 'var(--primary)' },
                                transition: 'color 150ms',
                            }}>
                            {crumb.label}
                        </MuiLink>
                    ))}
                    <Typography sx={{ fontSize: '12px', color: 'var(--text)', fontWeight: 500 }}>
                        {title}
                    </Typography>
                </Breadcrumbs>
            )}

            {/* Title Row */}
            <Stack
                direction={{ xs: 'column', sm: 'row' }}
                justifyContent="space-between"
                alignItems={{ xs: 'flex-start', sm: 'center' }}
                spacing={1.5}
            >
                <Box sx={{ minWidth: 0 }}>
                    <Typography sx={{
                        fontWeight: 600,
                        fontSize: '20px',
                        letterSpacing: '-0.01em',
                        color: 'var(--text)',
                        lineHeight: 1.25,
                        whiteSpace: 'nowrap',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                    }}>
                        {title}
                    </Typography>
                    {subtitle && (
                        <Typography sx={{
                            fontSize: '13px',
                            color: 'var(--text-muted)',
                            mt: 0.3,
                            lineHeight: 1.4,
                        }}>
                            {subtitle}
                        </Typography>
                    )}
                </Box>

                {actions && (
                    <Stack direction="row" spacing={0.75} alignItems="center" sx={{ flexShrink: 0 }}>
                        {actions}
                    </Stack>
                )}
            </Stack>
        </Box>
    );
};

export default PageHeader;
