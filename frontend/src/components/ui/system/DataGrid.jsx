/* Props listed only to keep them off the DOM are named _x and dropped via a rest sibling. */
/* eslint no-unused-vars: ["error", { "varsIgnorePattern": "^[A-Z_]", "argsIgnorePattern": "^_", "ignoreRestSiblings": true, "caughtErrors": "none" }] */
// In-house data grid that replaced @mui/x-data-grid.
//
// Implements the feature set the report pages use: column defs (flex/width,
// align, renderCell, valueGetter, valueFormatter, sortComparator), client
// sorting, client or server pagination, column groups, row click/class hooks,
// loading / empty overlays and a toolbar (columns, filter, density, CSV
// export, quick filter). Class names keep the MuiDataGrid-* contract because
// the shared styles in theme/dataGridStyles.js target them.
import React, { createContext, forwardRef, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { Button, CircularProgress, IconButton, MenuItem, Popover, Select, TextField } from './core';
import { sxClass, cx } from './sx';

const GridContext = createContext(null);

const Icon = ({ d, size = 18, className }) => (
    <svg className={cx('MuiSvgIcon-root', className)} focusable="false" aria-hidden="true" viewBox="0 0 24 24" style={{ fontSize: size }}><path d={d} /></svg>
);
const D = {
    up: 'M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z',
    down: 'M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z',
    columns: 'M6 5H3c-.55 0-1 .45-1 1v12c0 .55.45 1 1 1h3c.55 0 1-.45 1-1V6c0-.55-.45-1-1-1zm14 0h-3c-.55 0-1 .45-1 1v12c0 .55.45 1 1 1h3c.55 0 1-.45 1-1V6c0-.55-.45-1-1-1zm-7 0h-3c-.55 0-1 .45-1 1v12c0 .55.45 1 1 1h3c.55 0 1-.45 1-1V6c0-.55-.45-1-1-1z',
    filter: 'M10 18h4v-2h-4v2zM3 6v2h18V6H3zm3 7h12v-2H6v2z',
    density: 'M21 8H3V4h18v4zm0 2H3v4h18v-4zm0 6H3v4h18v-4z',
    export: 'M19 12v7H5v-7H3v7c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2v-7h-2zm-6 .67l2.59-2.58L17 11.5l-5 5-5-5 1.41-1.41L11 12.67V3h2z',
    search: 'M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
    prev: 'M15.41 16.09l-4.58-4.59 4.58-4.59L14 5.5l-6 6 6 6z',
    next: 'M8.59 16.34l4.58-4.59-4.58-4.59L10 5.75l6 6-6 6z',
};

const DENSITY = { compact: 0.7, standard: 1, comfortable: 1.3 };
const isBlank = (v) => v === null || v === undefined || v === '';

const rawValue = (col, row) => (col.valueGetter ? col.valueGetter(row[col.field], row, col, null) : row[col.field]);
const textValue = (col, row) => {
    const v = rawValue(col, row);
    if (col.valueFormatter) return col.valueFormatter(v, row, col, null);
    if (col.type === 'number' && typeof v === 'number') return v.toLocaleString();
    return v;
};

const defaultCompare = (a, b) => {
    if (isBlank(a) && isBlank(b)) return 0;
    if (isBlank(a)) return -1;
    if (isBlank(b)) return 1;
    if (typeof a === 'number' && typeof b === 'number') return a - b;
    if (a instanceof Date && b instanceof Date) return a - b;
    return String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: 'base' });
};

const NUMBER_OPS = ['=', '!=', '>', '>=', '<', '<='];
const TEXT_OPS = ['contains', 'equals', 'starts with', 'ends with', 'is empty', 'is not empty'];
const matchFilter = (col, row, f) => {
    const v = rawValue(col, row);
    if (f.operator === 'is empty') return isBlank(v);
    if (f.operator === 'is not empty') return !isBlank(v);
    if (isBlank(f.value)) return true;
    if (NUMBER_OPS.includes(f.operator)) {
        const n = Number(v); const q = Number(f.value);
        if (Number.isNaN(q)) return true;
        return f.operator === '=' ? n === q : f.operator === '!=' ? n !== q : f.operator === '>' ? n > q
            : f.operator === '>=' ? n >= q : f.operator === '<' ? n < q : n <= q;
    }
    const s = String(textValue(col, row) ?? v ?? '').toLowerCase(); const q = String(f.value).toLowerCase();
    return f.operator === 'equals' ? s === q : f.operator === 'starts with' ? s.startsWith(q) : f.operator === 'ends with' ? s.endsWith(q) : s.includes(q);
};

const csvCell = (v) => {
    const s = v === null || v === undefined ? '' : String(v);
    return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
};

export const DataGrid = forwardRef(function DataGrid(
    {
        rows = [], columns = [], loading, getRowId, rowHeight = 52, columnHeaderHeight = 56, getRowHeight, getRowClassName, onRowClick,
        disableRowSelectionOnClick, disableColumnSorting, hideFooter, pageSizeOptions = [25, 50, 100], initialState,
        paginationMode = 'client', paginationModel: pmProp, onPaginationModelChange, rowCount,
        sortModel: sortProp, onSortModelChange, columnGroupingModel, slots = {}, slotProps = {}, columnVisibilityModel: cvmProp,
        onColumnVisibilityModelChange, density: densityProp = 'standard', autoHeight, showToolbar, sx, className,
        // accepted for API compatibility; no effect here
        experimentalFeatures: _ef, disableColumnMenu: _dcm, disableColumnFilter: _dcf, checkboxSelection: _cs, localeText: _lt,
        sortingMode: _sm, filterMode: _fm, rowSelectionModel: _rsm, onRowSelectionModelChange: _orsm, keepNonExistentRowsSelected: _k,
        ...rest
    }, ref,
) {
    const server = paginationMode === 'server';
    const [pmState, setPmState] = useState(() => ({ page: 0, pageSize: 100, ...(initialState?.pagination?.paginationModel || {}) }));
    const pm = pmProp || pmState;
    const setPm = (next) => { if (!pmProp) setPmState(next); onPaginationModelChange?.(next); };

    const [sortState, setSortState] = useState(() => initialState?.sorting?.sortModel || []);
    const sortModel = sortProp || sortState;
    const setSort = (next) => { if (!sortProp) setSortState(next); onSortModelChange?.(next); };

    const [hiddenState, setHiddenState] = useState(() => ({ ...(initialState?.columns?.columnVisibilityModel || {}) }));
    const visibility = cvmProp || hiddenState;
    const setVisibility = (next) => { if (!cvmProp) setHiddenState(next); onColumnVisibilityModelChange?.(next); };

    const [density, setDensity] = useState(densityProp);
    const [quick, setQuick] = useState('');
    const [filter, setFilter] = useState(null);
    const [selectedId, setSelectedId] = useState(null);

    const idOf = (row) => (getRowId ? getRowId(row) : row.id);
    const cols = useMemo(() => columns.filter((c) => visibility[c.field] !== false), [columns, visibility]);

    // filter -> sort -> paginate (client mode only; server mode shows rows as given)
    const processed = useMemo(() => {
        let out = rows;
        if (filter && filter.field) {
            const col = columns.find((c) => c.field === filter.field);
            if (col) out = out.filter((r) => matchFilter(col, r, filter));
        }
        const tokens = quick.trim().toLowerCase().split(/\s+/).filter(Boolean);
        if (tokens.length) {
            out = out.filter((r) => {
                const hay = cols.map((c) => { const t = textValue(c, r); return t === null || t === undefined ? '' : String(t); }).join('\u0001').toLowerCase();
                return tokens.every((t) => hay.includes(t));
            });
        }
        const s = sortModel[0];
        if (s && s.sort && !server) {
            const col = columns.find((c) => c.field === s.field);
            if (col) {
                const cmp = col.sortComparator || defaultCompare;
                const dir = s.sort === 'desc' ? -1 : 1;
                out = out.map((r, i) => [r, i]).sort((a, b) => dir * cmp(rawValue(col, a[0]), rawValue(col, b[0])) || a[1] - b[1]).map((p) => p[0]);
            }
        }
        return out;
    }, [rows, columns, cols, filter, quick, sortModel, server]);

    const total = server ? (rowCount ?? rows.length) : processed.length;
    const paged = hideFooter || server ? processed : processed.slice(pm.page * pm.pageSize, (pm.page + 1) * pm.pageSize);
    const pageCount = Math.max(1, Math.ceil(total / pm.pageSize));
    useEffect(() => {
        if (!server && !hideFooter && pm.page > 0 && pm.page >= pageCount) setPm({ ...pm, page: pageCount - 1 });
    }); // keep the page in range when filtering shrinks the result

    const factor = DENSITY[density] ?? 1;
    const rowH = Math.round(rowHeight * factor);
    const headH = Math.round(columnHeaderHeight * factor);

    const template = cols.map((c) => (c.flex ? `minmax(${c.minWidth ?? 50}px, ${c.flex}fr)` : `${c.width ?? Math.max(c.minWidth ?? 0, 100)}px`)).join(' ');
    const minTotal = cols.reduce((n, c) => n + (c.flex ? (c.minWidth ?? 50) : (c.width ?? Math.max(c.minWidth ?? 0, 100))), 0);

    // column groups: one extra header row of spanning cells
    const groupRow = useMemo(() => {
        if (!columnGroupingModel?.length) return null;
        const owner = new Map();
        const walk = (g, top) => (g.children || []).forEach((ch) => (ch.field ? owner.set(ch.field, top) : walk(ch, top)));
        columnGroupingModel.forEach((g) => walk(g, g));
        const cells = [];
        cols.forEach((c) => {
            const g = owner.get(c.field) || null;
            const last = cells[cells.length - 1];
            if (last && last.group === g && g) last.span += 1;
            else cells.push({ group: g, span: 1 });
        });
        return cells;
    }, [columnGroupingModel, cols]);

    const toggleSort = (col) => {
        if (disableColumnSorting || col.sortable === false) return;
        const cur = sortModel[0]?.field === col.field ? sortModel[0].sort : null;
        const next = cur === 'asc' ? 'desc' : cur === 'desc' ? null : 'asc';
        setSort(next ? [{ field: col.field, sort: next }] : []);
    };

    const exportCsv = (fileName = document.title || 'export') => {
        const lines = [cols.map((c) => csvCell(c.headerName ?? c.field)).join(',')];
        processed.forEach((r) => lines.push(cols.map((c) => csvCell(textValue(c, r))).join(',')));
        const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = `${fileName}.csv`;
        document.body.appendChild(a); a.click(); a.remove();
        setTimeout(() => URL.revokeObjectURL(a.href), 1000);
    };

    const ctx = { columns, visibility, setVisibility, density, setDensity, quick, setQuick, filter, setFilter, exportCsv };

    // Same contract as the grid this replaced: a toolbar slot is rendered only
    // when showToolbar is set. Pages that pass slots.toolbar without it have
    // had no toolbar on screen, and keep it that way.
    const Toolbar = showToolbar ? (slots.toolbar || GridToolbar) : null;
    const NoRows = slots.noRowsOverlay;
    const skeleton = slotProps.loadingOverlay?.variant === 'skeleton';
    const showSkeleton = loading && skeleton && paged.length === 0;
    const autoRow = (row) => getRowHeight && getRowHeight({ id: idOf(row), model: row }) === 'auto';

    return (
        <GridContext.Provider value={ctx}>
            <div ref={ref} role="grid" aria-rowcount={total} aria-colcount={cols.length} aria-busy={loading || undefined}
                className={cx('MuiDataGrid-root', `MuiDataGrid-root--density${density.charAt(0).toUpperCase()}${density.slice(1)}`,
                    autoHeight && 'MuiDataGrid-autoHeight', 'MuiDataGrid-withBorderColor', className, sxClass(sx))}
                {...rest}>
                {Toolbar && <Toolbar {...(slotProps.toolbar || {})} />}
                <div className="MuiDataGrid-main">
                    <div className="MuiDataGrid-virtualScroller">
                        <div className="MuiDataGrid-table" role="presentation" style={{ gridTemplateColumns: template, minWidth: minTotal }}>
                            <div className="MuiDataGrid-columnHeaders" role="rowgroup">
                                {groupRow && (
                                    <div className="MuiDataGrid-columnHeaderRow" role="row" style={{ height: headH }}>
                                        {groupRow.map((g, i) => (
                                            <div key={i} role="columnheader" title={g.group?.description}
                                                className={cx('MuiDataGrid-columnHeader', g.group ? 'MuiDataGrid-columnHeader--group MuiDataGrid-columnHeader--filledGroup' : 'MuiDataGrid-columnHeader--emptyGroup', g.group?.headerClassName)}
                                                style={{ gridColumn: `span ${g.span}`, justifyContent: g.group?.headerAlign === 'left' ? 'flex-start' : g.group?.headerAlign === 'right' ? 'flex-end' : undefined }}>
                                                {g.group && (
                                                    <div className="MuiDataGrid-columnHeaderTitleContainer">
                                                        {g.group.renderHeaderGroup ? g.group.renderHeaderGroup({ groupId: g.group.groupId, headerName: g.group.headerName })
                                                            : <span className="MuiDataGrid-columnHeaderTitle">{g.group.headerName ?? g.group.groupId}</span>}
                                                    </div>
                                                )}
                                            </div>
                                        ))}
                                    </div>
                                )}
                                <div className="MuiDataGrid-columnHeaderRow" role="row" style={{ height: headH }}>
                                    {cols.map((c) => {
                                        const align = c.headerAlign || (c.type === 'number' ? 'right' : 'left');
                                        const sorted = sortModel[0]?.field === c.field ? sortModel[0].sort : null;
                                        const sortable = !disableColumnSorting && c.sortable !== false;
                                        return (
                                            <div key={c.field} role="columnheader" data-field={c.field} title={c.description}
                                                aria-sort={sorted === 'asc' ? 'ascending' : sorted === 'desc' ? 'descending' : 'none'}
                                                tabIndex={sortable ? 0 : -1}
                                                onClick={() => toggleSort(c)}
                                                onKeyDown={(e) => { if (e.key === 'Enter') toggleSort(c); }}
                                                className={cx('MuiDataGrid-columnHeader', `MuiDataGrid-columnHeader--align${align.charAt(0).toUpperCase()}${align.slice(1)}`,
                                                    sortable && 'MuiDataGrid-columnHeader--sortable', sorted && 'MuiDataGrid-columnHeader--sorted',
                                                    c.type === 'number' && 'MuiDataGrid-columnHeader--numeric', c.headerClassName)}>
                                                <div className="MuiDataGrid-columnHeaderTitleContainer">
                                                    {c.renderHeader ? c.renderHeader({ field: c.field, colDef: c })
                                                        : <span className="MuiDataGrid-columnHeaderTitle">{c.headerName ?? c.field}</span>}
                                                    {sortable && <Icon d={sorted === 'desc' ? D.down : D.up} size={16} className="MuiDataGrid-sortIcon" />}
                                                </div>
                                            </div>
                                        );
                                    })}
                                </div>
                            </div>

                            {showSkeleton && Array.from({ length: 8 }).map((_, r) => (
                                <div key={`sk${r}`} className="MuiDataGrid-row" role="row" style={{ height: rowH }}>
                                    {cols.map((c) => (
                                        <div key={c.field} className="MuiDataGrid-cell" style={{ display: 'flex', alignItems: 'center' }}>
                                            <span className="MuiDataGrid-skeletonCell" style={{ width: `${45 + ((r * 7 + c.field.length * 13) % 45)}%` }} />
                                        </div>
                                    ))}
                                </div>
                            ))}

                            {paged.map((row, index) => {
                                const id = idOf(row);
                                const auto = autoRow(row);
                                return (
                                    <div key={id ?? index} role="row" data-id={id} aria-selected={selectedId === id || undefined}
                                        onClick={(e) => {
                                            if (!disableRowSelectionOnClick) setSelectedId(selectedId === id ? null : id);
                                            onRowClick?.({ id, row, columns: cols }, e);
                                        }}
                                        className={cx('MuiDataGrid-row', onRowClick && 'MuiDataGrid-row--clickable', selectedId === id && 'Mui-selected',
                                            index === 0 && 'MuiDataGrid-row--firstVisible', index === paged.length - 1 && 'MuiDataGrid-row--lastVisible',
                                            getRowClassName?.({ id, row, indexRelativeToCurrentPage: index, isFirstVisible: index === 0, isLastVisible: index === paged.length - 1 }))}
                                        style={auto ? { minHeight: rowH } : { height: rowH, '--height': `${rowH}px` }}>
                                        {cols.map((c) => {
                                            const value = rawValue(c, row);
                                            const formattedValue = textValue(c, row);
                                            const params = { id, field: c.field, row, value, formattedValue, colDef: c, hasFocus: false, tabIndex: -1, api: null };
                                            const align = c.align || (c.type === 'number' ? 'right' : 'left');
                                            const extra = typeof c.cellClassName === 'function' ? c.cellClassName(params) : c.cellClassName;
                                            const plain = formattedValue === null || formattedValue === undefined ? '' : formattedValue;
                                            return (
                                                <div key={c.field} role="gridcell" data-field={c.field}
                                                    title={!c.renderCell && typeof plain !== 'object' && plain !== '' ? String(plain) : undefined}
                                                    className={cx('MuiDataGrid-cell', `MuiDataGrid-cell--text${align.charAt(0).toUpperCase()}${align.slice(1)}`, extra)}
                                                    style={auto ? undefined : { lineHeight: `${rowH - 1}px` }}>
                                                    {c.renderCell ? c.renderCell(params) : (typeof plain === 'object' ? String(plain) : plain)}
                                                </div>
                                            );
                                        })}
                                    </div>
                                );
                            })}

                            {/* spacer: an empty grid is two rows tall, as before */}
                            {paged.length === 0 && !showSkeleton && <div className="MuiDataGrid-overlayWrapper" style={{ minHeight: rowH * 2 }} />}
                        </div>
                    </div>
                    {/* message sits over the visible area, not the (possibly much wider) scrolled table */}
                    {paged.length === 0 && !showSkeleton && !loading && (
                        <div className="MuiDataGrid-overlay" style={{ top: headH * (groupRow ? 2 : 1) }}>
                            {NoRows ? <NoRows /> : (rows.length ? 'No results found.' : 'No rows')}
                        </div>
                    )}
                    {loading && !showSkeleton && (
                        <div className="MuiDataGrid-overlay MuiDataGrid-overlay--loading"><CircularProgress size={32} /></div>
                    )}
                </div>

                {!hideFooter && (
                    <div className="MuiDataGrid-footerContainer">
                        <div className="MuiTablePagination-root">
                            <span className="MuiTablePagination-selectLabel">Rows per page:</span>
                            <select className="MuiTablePagination-select" aria-label="Rows per page" value={pm.pageSize}
                                onChange={(e) => setPm({ page: 0, pageSize: Number(e.target.value) })}>
                                {Array.from(new Set([...pageSizeOptions.map((o) => (typeof o === 'object' ? o.value : o)), pm.pageSize])).sort((a, b) => a - b)
                                    .map((n) => <option key={n} value={n}>{n}</option>)}
                            </select>
                            <span className="MuiTablePagination-displayedRows">
                                {total === 0 ? '0–0 of 0' : `${pm.page * pm.pageSize + 1}–${Math.min(total, (pm.page + 1) * pm.pageSize)} of ${total}`}
                            </span>
                            <div className="MuiTablePagination-actions">
                                <IconButton size="small" aria-label="Go to previous page" disabled={pm.page === 0} onClick={() => setPm({ ...pm, page: pm.page - 1 })}><Icon d={D.prev} size={22} /></IconButton>
                                <IconButton size="small" aria-label="Go to next page" disabled={pm.page >= pageCount - 1} onClick={() => setPm({ ...pm, page: pm.page + 1 })}><Icon d={D.next} size={22} /></IconButton>
                            </div>
                        </div>
                    </div>
                )}
            </div>
        </GridContext.Provider>
    );
});

const ToolbarButton = ({ icon, children, ...p }) => (
    <Button size="small" startIcon={<Icon d={icon} />} sx={{ textTransform: 'none' }} {...p}>{children}</Button>
);

export function GridToolbar({ showQuickFilter, quickFilterProps = {}, csvOptions = {}, printOptions: _po, sx, className }) {
    const g = useContext(GridContext);
    const [panel, setPanel] = useState(null); // { type, anchor }
    const [text, setText] = useState(g?.quick ?? '');
    const timer = useRef(null);
    useEffect(() => () => clearTimeout(timer.current), []);
    if (!g) return null;

    const open = (type) => (e) => setPanel({ type, anchor: e.currentTarget });
    const onQuick = (e) => {
        const v = e.target.value;
        setText(v);
        clearTimeout(timer.current);
        timer.current = setTimeout(() => g.setQuick(v), quickFilterProps.debounceMs ?? 150);
    };
    const filterable = g.columns.filter((c) => c.filterable !== false);
    const f = g.filter || { field: filterable[0]?.field ?? '', operator: 'contains', value: '' };
    const fCol = g.columns.find((c) => c.field === f.field);
    const ops = fCol?.type === 'number' ? [...NUMBER_OPS, 'is empty', 'is not empty'] : TEXT_OPS;
    const setF = (patch) => {
        const next = { ...f, ...patch };
        const col = g.columns.find((c) => c.field === next.field);
        const allowed = col?.type === 'number' ? [...NUMBER_OPS, 'is empty', 'is not empty'] : TEXT_OPS;
        if (!allowed.includes(next.operator)) next.operator = allowed[0];
        g.setFilter(next);
    };
    const filterOn = !!g.filter && (g.filter.operator.startsWith('is ') || !isBlank(g.filter.value));

    return (
        <div className={cx('MuiDataGrid-toolbarContainer', className, sxClass(sx))}>
            <ToolbarButton icon={D.columns} onClick={open('columns')}>Columns</ToolbarButton>
            <ToolbarButton icon={D.filter} onClick={open('filter')}>{filterOn ? 'Filters (1)' : 'Filters'}</ToolbarButton>
            <ToolbarButton icon={D.density} onClick={open('density')}>Density</ToolbarButton>
            {!csvOptions.disableToolbarButton && <ToolbarButton icon={D.export} onClick={() => g.exportCsv(csvOptions.fileName)}>Export</ToolbarButton>}
            {showQuickFilter && (
                <TextField variant="standard" size="small" placeholder="Search…" value={text} onChange={onQuick} className="MuiDataGrid-toolbarQuickFilter"
                    inputProps={{ 'aria-label': 'Search' }}
                    InputProps={{ startAdornment: <Icon d={D.search} size={18} />, sx: { gap: 0.75 } }} sx={{ width: 200, mr: 1 }} />
            )}

            <Popover open={panel?.type === 'columns'} anchorEl={panel?.anchor} onClose={() => setPanel(null)}
                anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}>
                <div className="MuiDataGrid-panel">
                    {g.columns.filter((c) => c.hideable !== false).map((c) => (
                        <label key={c.field}>
                            <input type="checkbox" checked={g.visibility[c.field] !== false}
                                onChange={(e) => g.setVisibility({ ...g.visibility, [c.field]: e.target.checked })} />
                            {c.headerName ?? c.field}
                        </label>
                    ))}
                    <Button size="small" sx={{ alignSelf: 'flex-start', mt: 0.5 }} onClick={() => g.setVisibility({})}>Show all</Button>
                </div>
            </Popover>

            <Popover open={panel?.type === 'filter'} anchorEl={panel?.anchor} onClose={() => setPanel(null)}
                anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}>
                <div className="MuiDataGrid-panel" style={{ flexDirection: 'row', alignItems: 'center', gap: 8, paddingTop: 14, paddingBottom: 14 }}>
                    <Select size="small" value={f.field} onChange={(e) => setF({ field: e.target.value })} sx={{ minWidth: 150 }}>
                        {filterable.map((c) => <MenuItem key={c.field} value={c.field}>{c.headerName ?? c.field}</MenuItem>)}
                    </Select>
                    <Select size="small" value={f.operator} onChange={(e) => setF({ operator: e.target.value })} sx={{ minWidth: 120 }}>
                        {ops.map((o) => <MenuItem key={o} value={o}>{o}</MenuItem>)}
                    </Select>
                    {!f.operator.startsWith('is ') && (
                        <TextField size="small" placeholder="Filter value" value={f.value ?? ''} autoFocus
                            type={fCol?.type === 'number' ? 'number' : 'text'} onChange={(e) => setF({ value: e.target.value })} sx={{ width: 150 }} />
                    )}
                    <Button size="small" onClick={() => g.setFilter(null)}>Clear</Button>
                </div>
            </Popover>

            <Popover open={panel?.type === 'density'} anchorEl={panel?.anchor} onClose={() => setPanel(null)}
                anchorOrigin={{ vertical: 'bottom', horizontal: 'left' }}>
                <ul className="MuiList-root MuiMenu-list" role="menu">
                    {Object.keys(DENSITY).map((d) => (
                        <MenuItem key={d} selected={g.density === d} onClick={() => { g.setDensity(d); setPanel(null); }}>
                            {d.charAt(0).toUpperCase() + d.slice(1)}
                        </MenuItem>
                    ))}
                </ul>
            </Popover>
        </div>
    );
}
