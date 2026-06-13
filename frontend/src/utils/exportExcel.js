// Client-side CSV export helpers — dependency-free (no SheetJS/xlsx).
//
// This module used to build .xlsx workbooks via SheetJS. It now produces
// plain CSV so the app carries no spreadsheet dependency. The original
// function names (exportRowsToExcel / exportSheetsToExcel) are kept as
// aliases so existing imports keep working; they just emit .csv now.
//
// Usage:
//   import { exportRowsToCsv } from '../utils/exportExcel';
//   exportRowsToCsv(rows, {
//     fileName: 'merchant-summary',
//     columns: [
//       { key: 'name',   header: 'Merchant' },
//       { key: 'volume', header: 'Volume', format: 'currency' },
//     ],
//   });
//
// If `columns` is omitted, every key on the first row becomes a column.

function timestamp() {
    const d = new Date();
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}`;
}

function applyFormat(value, format) {
    if (value === null || value === undefined) return '';
    if (typeof format === 'function') return String(format(value));
    switch (format) {
        case 'number':
        case 'currency':
        case 'percent':
            // Keep the raw value so spreadsheets can still parse it as a number.
            return value === '' ? '' : String(value);
        case 'date': {
            const d = value instanceof Date ? value : new Date(value);
            return isNaN(d) ? String(value) : d.toISOString().slice(0, 10);
        }
        default:
            return String(value);
    }
}

// Quote a cell only when it needs it (comma, quote, newline, or edge spaces),
// doubling embedded quotes per RFC 4180.
function csvCell(raw) {
    const v = raw === null || raw === undefined ? '' : String(raw);
    if (/[",\n\r]/.test(v) || /^\s|\s$/.test(v)) {
        return `"${v.replace(/"/g, '""')}"`;
    }
    return v;
}

function buildCsv(rows, columns) {
    const cols = columns && columns.length
        ? columns
        : Object.keys(rows[0] || {}).map((k) => ({ key: k, header: k }));

    const header = cols.map((c) => csvCell(c.header ?? c.key)).join(',');
    const body = rows.map((row) =>
        cols.map((c) => csvCell(applyFormat(row[c.key], c.format))).join(',')
    );
    return [header, ...body].join('\r\n');
}

function downloadCsv(csv, fileName) {
    // Prepend a UTF-8 BOM so Excel opens accented characters correctly.
    const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    a.style.visibility = 'hidden';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}

/**
 * Export a single array of objects to a .csv file.
 * Returns false (and no-ops) if there is nothing to export.
 */
export function exportRowsToCsv(rows, opts = {}) {
    const { fileName = 'export', columns } = opts;
    if (!Array.isArray(rows) || rows.length === 0) return false;
    downloadCsv(buildCsv(rows, columns), `${fileName}_${timestamp()}.csv`);
    return true;
}

/**
 * Export multiple "sheets" to a single CSV. CSV has no sheet concept, so each
 * section is separated by a blank line and a `# <name>` marker.
 *   sheets: [{ name, rows, columns }]
 */
export function exportSheetsToCsv(sheets, opts = {}) {
    const { fileName = 'export' } = opts;
    const valid = (sheets || []).filter((s) => Array.isArray(s.rows) && s.rows.length);
    if (valid.length === 0) return false;
    const parts = valid.map((s) => `# ${s.name || 'Sheet'}\r\n${buildCsv(s.rows, s.columns)}`);
    downloadCsv(parts.join('\r\n\r\n'), `${fileName}_${timestamp()}.csv`);
    return true;
}

// Backward-compatible aliases — older call sites import the *Excel names.
export const exportRowsToExcel = exportRowsToCsv;
export const exportSheetsToExcel = exportSheetsToCsv;

export default exportRowsToCsv;
