import { useState, useEffect, useRef, useCallback } from 'react';
import api from '../api/axios';

const POLL_INTERVAL_MS = 2000; // Poll every 2 seconds

const useNotifications = () => {
    const [isConnected, setIsConnected] = useState(false);
    const [uploadProgress, setUploadProgress] = useState(null);
    const [reportProgress, setReportProgress] = useState(null);
    const pollingRef = useRef(null);

    // Subscribe to a specific job's progress via REST polling
    const subscribeToJob = useCallback((jobId) => {
        // Clear any existing polling
        if (pollingRef.current) {
            clearInterval(pollingRef.current);
            pollingRef.current = null;
        }

        if (!jobId) return;
        setIsConnected(true);

        const pollJobStatus = async () => {
            try {
                const res = await api.get(`/batch/jobs/${jobId}/status`);
                const data = res.data;

                if (data.error) {
                    console.warn('Job poll error:', data.error);
                    return;
                }

                setUploadProgress(data);

                const s = (data.status || '').toUpperCase();
                if (s === 'COMPLETED' || s === 'FAILED' || s === 'STOPPED' || s === 'ABANDONED') {
                    // Job is done — stop polling
                    if (pollingRef.current) {
                        clearInterval(pollingRef.current);
                        pollingRef.current = null;
                    }
                    setIsConnected(false);
                }
            } catch (err) {
                console.error('Error polling job status:', err);
                // Don't stop polling on transient errors
            }
        };

        // Poll immediately, then at intervals
        pollJobStatus();
        pollingRef.current = setInterval(pollJobStatus, POLL_INTERVAL_MS);
    }, []);

    // Cleanup on unmount
    useEffect(() => {
        return () => {
            if (pollingRef.current) {
                clearInterval(pollingRef.current);
            }
        };
    }, []);

    return { isConnected, uploadProgress, reportProgress, subscribeToJob };
};

export default useNotifications;
