import React from 'react';
import './FinancialLoader.css';

const FinancialLoader = () => {
    return (
        <div className="financial-loader-container">
            <div className="financial-loader">
                <div className="coin"></div>
                <div className="coin"></div>
                <div className="coin"></div>
                <div className="bar-graph">
                    <div className="bar"></div>
                    <div className="bar"></div>
                    <div className="bar"></div>
                    <div className="bar"></div>
                </div>
            </div>
            <div className="loader-text">Processing Financial Data...</div>
        </div>
    );
};

export default FinancialLoader;
