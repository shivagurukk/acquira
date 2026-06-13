import React from 'react';
import { Button, Tooltip } from '@mui/material';
import { Download } from 'lucide-react';
import { exportRowsToCsv } from '../utils/exportExcel';

/**
 * Drop-in "Export to CSV" button. (Despite the legacy file name, this exports
 * a dependency-free .csv — there is no Excel/SheetJS dependency anymore.)
 *
 *   <ExportExcelButton
 *      rows={merchants}
 *      fileName="merchant-summary"
 *      sheetName="Merchants"
 *      columns={[
 *        { key: 'name',   header: 'Merchant' },
 *        { key: 'volume', header: 'Volume', format: 'currency' },
 *      ]}
 *   />
 *
 * `columns` is optional — without it, every key of the first row is exported.
 * The button auto-disables when there are no rows.
 */
const ExportExcelButton = ({
    rows = [],
    columns,
    fileName = 'export',
    sheetName = 'Sheet1',
    label = 'Export CSV',
    size = 'small',
    variant = 'outlined',
    onExported,
    ...buttonProps
}) => {
    const empty = !Array.isArray(rows) || rows.length === 0;

    const handleClick = () => {
        const ok = exportRowsToCsv(rows, { fileName, columns });
        if (ok && typeof onExported === 'function') onExported(rows.length);
    };

    const btn = (
        <span>
            <Button
                size={size}
                variant={variant}
                startIcon={<Download size={16} />}
                onClick={handleClick}
                disabled={empty}
                {...buttonProps}
            >
                {label}
            </Button>
        </span>
    );

    return empty
        ? <Tooltip title="No data to export">{btn}</Tooltip>
        : btn;
};

export default ExportExcelButton;
