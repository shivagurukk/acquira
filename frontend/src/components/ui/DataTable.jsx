import React, { useMemo, useState } from 'react';
import { ChevronUp, ChevronDown, ChevronsUpDown, ChevronLeft, ChevronRight, Search } from 'lucide-react';
import EmptyState from '../EmptyState';

/**
 * DataTable — the standard table for admin surfaces. Owns sorting, the loading
 * skeleton, and the empty state so pages stop re-implementing them.
 *
 *   <DataTable
 *     columns={[
 *       { key: 'name',   header: 'Name', sortable: true, bold: true },
 *       { key: 'status', header: 'Status', render: r => <StatusBadge status={r.status} /> },
 *       { key: 'amount', header: 'Amount', align: 'right', numeric: true },
 *       { key: '_a', header: '', align: 'right', render: r => <Button …/> },
 *     ]}
 *     rows={rows}
 *     rowKey={r => r.id}
 *     loading={loading}
 *     search={{ value: q, onChange: setQ, placeholder: 'Search users…' }}
 *   />
 *
 * Column options:
 *   key       — property on the row, also the React key
 *   header    — column heading (omit for action columns)
 *   render    — (row, index) => node; falls back to row[key]
 *   sortValue — (row) => comparable; falls back to row[key]
 *   sortable  — enable click-to-sort on the header
 *   align     — 'left' | 'right' | 'center'
 *   numeric   — tabular figures
 *   mono      — monospace
 *   muted     — secondary text colour
 *   nowrap    — prevent wrapping
 *   width     — fixed column width
 *
 * `rowClassName` takes (row, index) and lets a page mark rows visually — e.g.
 * `row => row.revoked && 'ui-tr--dimmed'` for retired records.
 */
export default function DataTable({
  columns = [],
  rows = [],
  rowKey,
  rowClassName,
  loading = false,
  skeletonRows = 5,
  empty,
  emptyVariant = 'table',
  onRowClick,
  search,
  toolbarLeft,
  toolbarRight,
  compact = false,
  hover = true,
  stickyHeader = false,
  defaultSort,
  pageSize,
  className = '',
}) {
  const [sort, setSort] = useState(defaultSort ?? null); // { key, dir: 'asc' | 'desc' }
  const [page, setPage] = useState(0);

  const sorted = useMemo(() => {
    if (!sort) return rows;
    const col = columns.find((c) => c.key === sort.key);
    if (!col) return rows;
    const get = col.sortValue || ((r) => r[col.key]);
    const factor = sort.dir === 'asc' ? 1 : -1;

    return [...rows].sort((a, b) => {
      const av = get(a);
      const bv = get(b);
      if (av == null && bv == null) return 0;
      if (av == null) return 1;   // nulls sink regardless of direction
      if (bv == null) return -1;
      if (typeof av === 'number' && typeof bv === 'number') return (av - bv) * factor;
      return String(av).localeCompare(String(bv), undefined, { numeric: true }) * factor;
    });
  }, [rows, sort, columns]);

  const toggleSort = (key) => {
    setPage(0);
    setSort((prev) =>
      prev?.key === key
        ? prev.dir === 'asc'
          ? { key, dir: 'desc' }
          : null
        : { key, dir: 'asc' }
    );
  };

  // Pagination is opt-in via `pageSize`. Clamp the page so filtering down to a
  // shorter list can never strand the view on a page that no longer exists.
  const pageCount = pageSize ? Math.max(1, Math.ceil(sorted.length / pageSize)) : 1;
  const safePage = Math.min(page, pageCount - 1);
  const visible = pageSize
    ? sorted.slice(safePage * pageSize, safePage * pageSize + pageSize)
    : sorted;

  const tableClasses = [
    'ui-table',
    hover && !loading && 'ui-table--hover',
    onRowClick && 'ui-table--clickable',
    compact && 'ui-table--compact',
    stickyHeader && 'ui-table--sticky',
  ]
    .filter(Boolean)
    .join(' ');

  const cellClasses = (col) =>
    [
      col.align === 'right' && 'ui-td--right',
      col.align === 'center' && 'ui-td--center',
      col.numeric && 'ui-td--num',
      col.mono && 'ui-td--mono',
      col.muted && 'ui-td--muted',
      col.nowrap && 'ui-td--nowrap',
      col.className,
    ]
      .filter(Boolean)
      .join(' ');

  const hasToolbar = search || toolbarLeft || toolbarRight;

  return (
    <div className={className}>
      {hasToolbar && (
        <div className="ui-table-toolbar">
          <div className="ui-table-toolbar__left">
            {search && (
              <div className="ui-table-search">
                <Search size={14} />
                <input
                  className="ui-input"
                  type="search"
                  value={search.value}
                  onChange={(e) => search.onChange(e.target.value)}
                  placeholder={search.placeholder || 'Search…'}
                  aria-label={search.placeholder || 'Search'}
                />
              </div>
            )}
            {toolbarLeft}
          </div>
          {toolbarRight && <div className="ui-table-toolbar__right">{toolbarRight}</div>}
        </div>
      )}

      <div className="ui-table-wrap">
        <table className={tableClasses}>
          <thead>
            <tr>
              {columns.map((col) => {
                const isSorted = sort?.key === col.key;
                const Arrow = !isSorted ? ChevronsUpDown : sort.dir === 'asc' ? ChevronUp : ChevronDown;
                return (
                  <th
                    key={col.key}
                    style={col.width ? { width: col.width } : undefined}
                    aria-sort={isSorted ? (sort.dir === 'asc' ? 'ascending' : 'descending') : undefined}
                    className={[
                      col.sortable && 'ui-th--sortable',
                      isSorted && 'ui-th--sorted',
                      col.align === 'right' && 'ui-th--right',
                      col.align === 'center' && 'ui-th--center',
                    ]
                      .filter(Boolean)
                      .join(' ')}
                    onClick={col.sortable ? () => toggleSort(col.key) : undefined}
                  >
                    {col.sortable ? (
                      <span className="ui-th__inner">
                        {col.header}
                        <Arrow size={12} className="ui-th__arrow" strokeWidth={2.5} />
                      </span>
                    ) : (
                      col.header
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>

          <tbody>
            {loading &&
              Array.from({ length: skeletonRows }).map((_, i) => (
                <tr key={`skel-${i}`}>
                  {columns.map((col) => (
                    <td key={col.key}>
                      <div className="ui-skel" style={{ width: `${55 + ((i * 7 + col.key.length * 5) % 40)}%` }} />
                    </td>
                  ))}
                </tr>
              ))}

            {!loading &&
              visible.map((row, i) => (
                <tr
                  key={rowKey ? rowKey(row, i) : (row.id ?? i)}
                  className={rowClassName ? rowClassName(row, i) || undefined : undefined}
                  onClick={onRowClick ? () => onRowClick(row) : undefined}
                >
                  {columns.map((col) => (
                    <td key={col.key} className={cellClasses(col)}>
                      {col.render ? col.render(row, i) : row[col.key]}
                    </td>
                  ))}
                </tr>
              ))}

            {!loading && sorted.length === 0 && (
              <tr>
                <td colSpan={columns.length} className="ui-table__empty">
                  {empty ?? <EmptyState variant={emptyVariant} compact />}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {pageSize && !loading && sorted.length > pageSize && (
        <div className="ui-table-pager">
          <span className="ui-table-pager__count">
            {safePage * pageSize + 1}–{Math.min((safePage + 1) * pageSize, sorted.length)} of{' '}
            {sorted.length}
          </span>
          <div className="ui-row">
            <button
              type="button"
              className="ui-btn ui-btn--sm"
              onClick={() => setPage(safePage - 1)}
              disabled={safePage === 0}
            >
              <ChevronLeft size={13} strokeWidth={2} />
              Previous
            </button>
            <span className="ui-table-pager__page">
              Page {safePage + 1} of {pageCount}
            </span>
            <button
              type="button"
              className="ui-btn ui-btn--sm"
              onClick={() => setPage(safePage + 1)}
              disabled={safePage >= pageCount - 1}
            >
              Next
              <ChevronRight size={13} strokeWidth={2} />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
