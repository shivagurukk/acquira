import React, { useState, useEffect } from 'react';
import { FileText, Play, CheckCircle, AlertCircle, Loader2, AlertTriangle, RefreshCw } from 'lucide-react';

const MerchantReportManager = () => {
    const [merchants, setMerchants] = useState([]);
    const [status, setStatus] = useState('idle'); // idle, checking, confirming, running, completed
    const [progress, setProgress] = useState({ current: 0, total: 0, success: 0, failed: 0 });
    const [logs, setLogs] = useState([]);
    const [existingReportCount, setExistingReportCount] = useState(0);

    useEffect(() => {
        fetchMerchants();
    }, []);

    const fetchMerchants = async () => {
        try {
            const token = localStorage.getItem('token');
            const tenantId = localStorage.getItem('tenantId');
            const res = await fetch('/api/merchants', {
                headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
            });
            if (res.ok) {
                const data = await res.json();
                const list = data.content || data;
                setMerchants(list);
                setProgress(prev => ({ ...prev, total: list.length }));
            }
        } catch (error) {
            console.error("Failed to fetch merchants", error);
        }
    };

    const handleStartClick = async () => {
        setStatus('checking');
        try {
            const token = localStorage.getItem('token');
            const res = await fetch('/api/business/insights/check-status', {
                headers: { 'Authorization': `Bearer ${token}` }
            });
            if (res.ok) {
                const data = await res.json();
                if (data.exists) {
                    setExistingReportCount(data.count);
                    setStatus('confirming');
                    return;
                }
            }
        } catch (e) {
            console.error("Check status failed", e);
        }
        startBatch(); // If no existing reports or check fails, start immediately
    };

    const startBatch = async () => {
        setStatus('running');
        setLogs([]);
        setProgress({ current: 0, total: merchants.length, success: 0, failed: 0 });

        const token = localStorage.getItem('token');
        const tenantId = localStorage.getItem('tenantId');

        for (let i = 0; i < merchants.length; i++) {
            const merchant = merchants[i];

            try {
                // Generate Report for existing merchant
                const res = await fetch(`/api/business/insights/generate/${merchant.merchantId}`, {
                    method: 'POST',
                    headers: { 'Authorization': `Bearer ${token}`, 'X-Tenant-Id': tenantId }
                });

                if (res.ok) {
                    setProgress(prev => ({ ...prev, current: prev.current + 1, success: prev.success + 1 }));
                } else {
                    throw new Error("Failed");
                }
            } catch (err) {
                setProgress(prev => ({ ...prev, current: prev.current + 1, failed: prev.failed + 1 }));
                setLogs(prev => [...prev, `Failed: ${merchant.name || merchant.merchantId}`]);
            }
        }
        setStatus('completed');
    };

    const pct = progress.total > 0 ? Math.round((progress.current / progress.total) * 100) : 0;

    return (
        <div className="p-8 bg-slate-50 min-h-screen font-sans flex flex-col items-center justify-center">

            <div className="w-full max-w-2xl bg-white rounded-2xl shadow-xl border border-slate-200 overflow-hidden">
                <div className="bg-slate-900 px-8 py-6 border-b border-slate-800 flex justify-between items-center">
                    <div>
                        <h2 className="text-xl font-bold text-white flex items-center gap-3">
                            <FileText size={24} className="text-indigo-400" />
                            Merchant Report Manager
                        </h2>
                        <p className="text-slate-400 text-sm mt-1">Batch Generation System</p>
                    </div>
                    {status === 'idle' && (
                        <div className="text-right">
                            <div className="text-2xl font-bold text-indigo-400">{merchants.length}</div>
                            <div className="text-xs text-slate-400 uppercase tracking-wider font-bold">Merchants Ready</div>
                        </div>
                    )}
                </div>

                <div className="p-10 flex flex-col items-center text-center">

                    {status === 'idle' && (
                        <div className="space-y-6">
                            <div className="mx-auto w-20 h-20 bg-indigo-50 rounded-full flex items-center justify-center mb-4">
                                <Play size={40} className="text-indigo-600 ml-1" />
                            </div>
                            <div>
                                <h3 className="text-lg font-bold text-slate-800">Ready to Generate</h3>
                                <p className="text-slate-500 mt-2 max-w-md mx-auto">
                                    This process will generate PDF insight reports for all {merchants.length} active merchants.
                                    Expected time: ~{(merchants.length * 1.5 / 60).toFixed(1)} minutes.
                                </p>
                            </div>
                            <button
                                onClick={handleStartClick}
                                className="px-8 py-3 bg-indigo-600 hover:bg-indigo-700 text-white font-bold rounded-lg shadow-lg hover:shadow-indigo-200 transition-all transform active:scale-95 flex items-center gap-2 mx-auto"
                            >
                                <Play size={18} fill="currentColor" /> START BATCH PROCESS
                            </button>
                        </div>
                    )}

                    {status === 'confirming' && (
                        <div className="space-y-6 animate-in fade-in zoom-in duration-300">
                            <div className="mx-auto w-20 h-20 bg-amber-50 rounded-full flex items-center justify-center mb-4 border border-amber-100">
                                <AlertTriangle size={40} className="text-amber-500" />
                            </div>
                            <div>
                                <h3 className="text-lg font-bold text-slate-800">Reports Already Exist</h3>
                                <p className="text-slate-500 mt-2 max-w-md mx-auto">
                                    It looks like <strong className="text-slate-900">{existingReportCount}</strong> reports have already been generated for this period.
                                    <br />Do you want to re-run the batch and overwrite them?
                                </p>
                            </div>
                            <div className="flex gap-4 justify-center">
                                <button
                                    onClick={() => setStatus('idle')}
                                    className="px-6 py-2.5 bg-white border border-slate-200 text-slate-600 font-bold rounded-lg hover:bg-slate-50"
                                >
                                    Cancel
                                </button>
                                <button
                                    onClick={startBatch}
                                    className="px-6 py-2.5 bg-amber-500 hover:bg-amber-600 text-white font-bold rounded-lg shadow-md flex items-center gap-2"
                                >
                                    <RefreshCw size={18} /> Yes, Re-run Batch
                                </button>
                            </div>
                        </div>
                    )}

                    {(status === 'running' || status === 'completed') && (
                        <div className="w-full space-y-8 animate-in fade-in duration-500">
                            <div className="relative pt-4">
                                <div className="flex justify-between mb-2">
                                    <span className="text-xs font-bold text-slate-500 uppercase tracking-wider">
                                        {status === 'running' ? 'Processing...' : 'Batch Completed'}
                                    </span>
                                    <span className="text-xs font-bold text-indigo-600">{pct}%</span>
                                </div>
                                <div className="w-full bg-slate-100 rounded-full h-4 overflow-hidden shadow-inner">
                                    <div
                                        className={`h-full transition-all duration-300 ease-out ${status === 'completed' ? 'bg-green-500' : 'bg-indigo-600 relative overflow-hidden'}`}
                                        style={{ width: `${pct}%` }}
                                    >
                                        {status === 'running' && (
                                            <div className="absolute inset-0 bg-white/30 animate-[shimmer_2s_infinite] skew-x-12"></div>
                                        )}
                                    </div>
                                </div>
                            </div>

                            <div className="grid grid-cols-2 gap-4">
                                <div className="p-4 bg-green-50 rounded-lg border border-green-100">
                                    <div className="text-2xl font-bold text-green-600">{progress.success}</div>
                                    <div className="text-xs text-green-700 font-bold uppercase">Success</div>
                                </div>
                                <div className="p-4 bg-red-50 rounded-lg border border-red-100">
                                    <div className="text-2xl font-bold text-red-600">{progress.failed}</div>
                                    <div className="text-xs text-red-700 font-bold uppercase">Failed</div>
                                </div>
                            </div>

                            {status === 'completed' && (
                                <button
                                    onClick={() => setStatus('idle')}
                                    className="px-6 py-2 bg-slate-100 hover:bg-slate-200 text-slate-600 font-bold rounded-lg transition-colors text-sm"
                                >
                                    Done
                                </button>
                            )}
                        </div>
                    )}

                </div>
            </div>

            {/* Logs Area */}
            {logs.length > 0 && (
                <div className="mt-8 w-full max-w-2xl">
                    <div className="text-xs font-bold text-slate-400 uppercase tracking-widest mb-2 px-2">Error Logs</div>
                    <div className="bg-slate-900 rounded-lg p-4 font-mono text-xs text-red-400 max-h-40 overflow-y-auto shadow-inner">
                        {logs.map((L, i) => <div key={i}>&gt; {L}</div>)}
                    </div>
                </div>
            )}

            <style>{`
                @keyframes shimmer {
                    0% { transform: translateX(-100%) skewX(-15deg); }
                    100% { transform: translateX(200%) skewX(-15deg); }
                }
            `}</style>
        </div>
    );
};

export default MerchantReportManager;
