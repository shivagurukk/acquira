import { useState } from 'react';
import axios from 'axios';

const useExcelExport = () => {
    const [isExporting, setIsExporting] = useState(false);
    const [error, setError] = useState(null);

    const exportExcel = async (type, optionsOrStartDate = null, endDate = null) => {
        setIsExporting(true);
        setError(null);
        try {
            const token = localStorage.getItem('token');
            const params = { type };

            if (optionsOrStartDate && typeof optionsOrStartDate === 'object') {
                // New signature: (type, options)
                Object.assign(params, optionsOrStartDate);
            } else {
                // Legacy signature: (type, startDate, endDate)
                if (optionsOrStartDate) params.startDate = optionsOrStartDate;
                if (endDate) params.endDate = endDate;
            }

            const response = await axios.get('/api/export/excel', {
                params,
                headers: { 'Authorization': `Bearer ${token}` },
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
