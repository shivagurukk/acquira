import React from 'react';
import { SectionLoader } from './Loaders';

/**
 * Legacy entry point — now renders the shared Acquira data-pulse loader
 * from Loaders.jsx. Kept so existing imports keep working.
 */
const FinancialLoader = ({ label = 'Processing financial data' }) => (
    <SectionLoader label={label} framed={false} minHeight="200px" />
);

export default FinancialLoader;
