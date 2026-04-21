import React from 'react';
import { Box, Typography, Stack, Breadcrumbs, Link as MuiLink } from '@mui/material';
import { useLocation, Link } from 'react-router-dom';
import { ChevronRight, Home } from 'lucide-react';

const PageHeader = ({ title, subtitle, actions, breadcrumbs, icon: Icon, noBorder = false }) => {
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
            px: { xs: '20px', md: '28px' },
            pt: { xs: '16px', md: '20px' },
            pb: '16px',
            borderBottom: noBorder ? 'none' : '1px solid var(--border, #e5e7eb)',
            background: 'var(--bg-card, #fff)',
            position: 'sticky',
            top: 0,
            zIndex: 10,
        }}>
            {/* Breadcrumbs */}
            {autoBreadcrumbs.length > 0 && (
                <Breadcrumbs
                    separator={<ChevronRight size={10} style={{ color: 'var(--text-muted, #9ca3af)' }} />}
                    sx={{ mb: 1 }}
                >
                    <MuiLink component={Link} to="/dashboard" underline="none"
                        sx={{
                            display: 'flex', alignItems: 'center', gap: 0.4,
                            fontSize: '0.72rem', color: 'var(--text-muted, #9ca3af)',
                            '&:hover': { color: 'var(--brand, #2563eb)' },
                            transition: 'color 0.15s',
                        }}>
                        <Home size={10} /> Home
                    </MuiLink>
                    {autoBreadcrumbs.map((crumb, i) => (
                        <MuiLink key={i} component={Link} to={crumb.path} underline="none"
                            sx={{
                                fontSize: '0.72rem', color: 'var(--text-muted, #9ca3af)',
                                '&:hover': { color: 'var(--brand, #2563eb)' },
                                transition: 'color 0.15s',
                            }}>
                            {crumb.label}
                        </MuiLink>
                    ))}
                    <Typography sx={{ fontSize: '0.72rem', color: 'var(--text, #111827)', fontWeight: 500 }}>
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
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, minWidth: 0 }}>
                    {Icon && (
                        <Box sx={{
                            width: 38, height: 38,
                            borderRadius: '12px',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            background: 'var(--brand-50, #eff6ff)',
                            border: '1px solid rgba(37,99,235,0.1)',
                            flexShrink: 0,
                        }}>
                            <Icon size={18} style={{ color: 'var(--brand, #2563eb)' }} strokeWidth={1.8} />
                        </Box>
                    )}
                    <Box sx={{ minWidth: 0 }}>
                        <Typography sx={{
                            fontWeight: 700,
                            fontSize: { xs: '1.1rem', md: '1.2rem' },
                            letterSpacing: '-0.025em',
                            color: 'var(--text, #111827)',
                            lineHeight: 1.25,
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                        }}>
                            {title}
                        </Typography>
                        {subtitle && (
                            <Typography sx={{
                                fontSize: '0.82rem',
                                color: 'var(--text-muted, #9ca3af)',
                                mt: 0.3,
                                lineHeight: 1.4,
                            }}>
                                {subtitle}
                            </Typography>
                        )}
                    </Box>
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
