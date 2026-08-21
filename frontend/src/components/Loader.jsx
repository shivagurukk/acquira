import React from 'react';
import { PageLoader } from './Loaders';

/**
 * Legacy entry point — now renders the shared Acquira data-pulse loader
 * from Loaders.jsx so every screen shows the same branded visual.
 * Kept so existing imports (`import Loader from '.../Loader'`) keep working.
 */
const Loader = ({ fullScreen = true, label = 'Loading' }) => (
    <PageLoader overlay={fullScreen} label={label} />
);

export default Loader;
