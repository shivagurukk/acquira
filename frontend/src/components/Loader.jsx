import React from 'react';
import './Loader.css';

const Loader = ({ fullScreen = true }) => {
    return (
        <div className={`loader-container ${fullScreen ? 'fullscreen' : ''}`}>
            <div className="orbital-spinner">
                <div className="loader-ring loader-ring-1"></div>
                <div className="loader-ring loader-ring-2"></div>
                <div className="loader-ring loader-ring-3"></div>
                <div className="core"></div>
            </div>
            <div className="loader-text">Loading System...</div>
        </div>
    );
};

export default Loader;
