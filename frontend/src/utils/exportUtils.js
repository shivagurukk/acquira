/**
 * Export rows to CSV.
 *
 * `columns` is optional: an array of { label, key?, getter? } that controls
 * the header names AND which fields ship, in order. `getter(row)` wins over
 * `key`. Without it, every raw object key exports under its raw name
 * (legacy behavior — most pages still rely on this).
 */
export const exportToCSV = (data, filename, columns) => {
    if (!data || !data.length) {
        alert("No data to export");
        return;
    }

    const esc = (val) => {
        if (val === null || val === undefined) return '';
        val = val.toString();
        if (/[",\n]/.test(val)) val = `"${val.replace(/"/g, '""')}"`;
        return val;
    };

    const spec = columns?.length
        ? columns
        : Object.keys(data[0]).map(k => ({ label: k, key: k }));

    const csvContent = [
        spec.map(c => esc(c.label)).join(','),
        ...data.map(row => spec.map(c =>
            esc(c.getter ? c.getter(row) : row[c.key])
        ).join(','))
    ].join('\n');

    // Create Blob and Link
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.setAttribute("href", url);
    link.setAttribute("download", `${filename}.csv`);
    link.style.visibility = 'hidden';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
};
