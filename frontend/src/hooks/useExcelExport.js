import { useState } from 'react';
import api from '../api/axios';

const useExcelExport = () => {
    const [isExporting, setIsExporting] = useState(false);
    const [error, setError] = useState(null);

    const exportExcel = async (type, optionsOrStartDate = null, endDate = null) => {
        setIsExporting(true);
        setError(null);
        try {
            const params = { type };

            if (optionsOrStartDate && typeof optionsOrStartDate === 'object') {
                // New signature: (type, options)
                Object.assign(params, optionsOrStartDate);
            } else {
                // Legacy signature: (type, startDate, endDate)
                if (optionsOrStartDate) params.startDate = optionsOrStartDate;
                if (endDate) params.endDate = endDate;
            }

            // Shared client, not bare axios: the interceptor attaches X-Tenant-Id.
            // Without it the download silently used the user's DEFAULT tenant, so
            // after a tenant switch the on-screen table and its export disagreed.
            // baseURL is already '/api'.
            const response = await api.get('/export/excel', {
                params,
                responseType: 'blob', // Important for binary data
            });

            // Create a link to download the file
            const url = window.URL.createObjectURL(new Blob([response.data]));
            const link = document.createElement('a');
            link.href = url;

            // Extract filename from header if available, or default
            let filename = `${type}_Export.xlsx`;
            const contentDisposition = response.headers['content-disposition'];
            if (contentDisposition) {
                const filenameMatch = contentDisposition.match(/filename="?([^"]+)"?/);
                if (filenameMatch && filenameMatch.length === 2) {
                    filename = filenameMatch[1];
                }
            }

            link.setAttribute('download', filename);
            document.body.appendChild(link);
            link.click();
            link.remove();
            window.URL.revokeObjectURL(url);

        } catch (err) {
            console.error("Export failed", err);
            setError(err.message || "Failed to export Excel file");
        } finally {
            setIsExporting(false);
        }
    };

    return { exportExcel, isExporting, error };
};

export default useExcelExport;
