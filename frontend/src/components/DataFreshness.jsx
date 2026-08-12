import React, { useMemo } from 'react';
import { Tooltip } from '@mui/material';
import { useAuth } from '../contexts/AuthContext';
import { useDataBounds } from '../hooks/useDataBounds';

/**
 * <DataFreshness> — the sidebar's data-freshness stamp.
 *
 * Shows the last successful rollup date of the sum_daily_* aggregate
 * tables (surfaced through /business/data-bounds), preceded by a status
 * dot: teal while the rollup ran within its expected daily window,
 * brass once it is stale. The collapsed rail shows the dot alone.
 *
 * A daily batch closes the books at end of day, so the stamp renders as
 * "as of 09 Aug 23:59". Stale = latest rollup more than STALE_AFTER_DAYS
 * behind today (one full missed batch window).
 */
const STALE_AFTER_DAYS = 2;
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const DataFreshness = ({ collapsed = false }) => {
    const { tenantVersion } = useAuth();
    const { latest, boundsLoaded } = useDataBounds(tenantVersion);

    const { label, fresh } = useMemo(() => {
        if (!latest) return { label: 'no rollup', fresh: false };
        const d = new Date(latest);
        if (Number.isNaN(d.getTime())) return { label: 'no rollup', fresh: false };
        const ageDays = (Date.now() - d.getTime()) / 86400000;
        return {
            label: `as of ${String(d.getDate()).padStart(2, '0')} ${MONTHS[d.getMonth()]} 23:59`,
            fresh: ageDays <= STALE_AFTER_DAYS,
        };
    }, [latest]);

    if (!boundsLoaded) return null;

    const tip = fresh
        ? `sum_daily_* aggregates — last rollup ${label.replace('as of ', '')}`
        : latest
            ? `sum_daily_* aggregates — rollup stale, last ran ${label.replace('as of ', '')}`
            : 'sum_daily_* aggregates — no successful rollup found';

    return (
        <Tooltip title={tip} placement="right" arrow>
            <div className={`sb__fresh${collapsed ? ' sb__fresh--collapsed' : ''}`} aria-label={tip}>
                <span className={`sb__fresh-dot${fresh ? '' : ' sb__fresh-dot--stale'}`} />
                {!collapsed && <span className="sb__fresh-label">{label}</span>}
            </div>
        </Tooltip>
    );
};

export default DataFreshness;
