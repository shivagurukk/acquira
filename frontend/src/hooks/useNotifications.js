import { useState, useEffect, useRef, useCallback } from 'react';
import api from '../api/axios';

const POLL_INTERVAL_MS = 2000;        // Poll every 2 seconds
const MAX_POLL_DURATION_MS = 30 * 60 * 1000;  // Stop polling after 30 minutes
const MAX_CONSECUTIVE_ERRORS = 5;     // Surface the issue after 5 in a row

const useNotifications = () => {
    const [isConnected, setIsConnected] = useState(false);
    const [uploadProgress, setUploadProgress] = useState(null);
    // Kept in the return for back-compat with any consumer that destructures it.
    // Currently never set by this hook — if/when report-progress polling lands,
    // it should mirror the same termination semantics as subscribeToJob below.
    const [reportProgress] = useState(null);
    const pollingRef = useRef(null);
    const startedAtRef = useRef(null);
    const errorCountRef = useRef(0);

    // Subscribe to a specific job's progress via REST polling.
    // The poll terminates on:
    //   - Spring Batch terminal status (COMPLETED, FAILED, STOPPED, ABANDONED)
    //   - 30-minute wall-clock cap (prevents zombie polls if a job hangs)
    //   - 5 consecutive transient errors (surfaces network/auth issues to the UI)
    const subscribeToJob = useCallback((jobId) => {
        // Clear any existing polling
        if (pollingRef.current) {
            clearInterval(pollingRef.current);
            pollingRef.current = null;
        }

        if (!jobId) return;
        setIsConnected(true);
        startedAtRef.current = Date.now();
        errorCountRef.current = 0;

        const stopPolling = (finalState) => {
            if (pollingRef.current) {
                clearInterval(pollingRef.current);
                pollingRef.current = null;
            }
            setIsConnected(false);
            if (finalState) setUploadProgress(prev => ({ ...(prev || {}), ...finalState }));
        };

        const pollJobStatus = async () => {
            // Wall-clock timeout. The 30-minute cap matches the typical Postgres
            // idle_in_transaction_session_timeout — if the job hasn't finished
            // by then, something is wedged and the user should look in Batch
            // Monitoring rather than wait on this hook.
            if (startedAtRef.current && Date.now() - startedAtRef.current > MAX_POLL_DURATION_MS) {
                stopPolling({
                    status: 'TIMEOUT',
                    error: 'Polling timed out after 30 minutes. Check Batch Monitoring for the actual job status.',
                });
                return;
            }

            try {
                const res = await api.get(`/batch/jobs/${jobId}/status`);
                const data = res.data;
                errorCountRef.current = 0;  // reset on any successful response

                if (data.error) {
                    console.warn('Job poll error:', data.error);
                    return;
                }

                setUploadProgress(data);

                const s = (data.status || '').toUpperCase();
                if (s === 'COMPLETED' || s === 'FAILED' || s === 'STOPPED' || s === 'ABANDONED') {
                    stopPolling();
                }
            } catch (err) {
                errorCountRef.current += 1;
                console.error(`Error polling job status (${errorCountRef.current}/${MAX_CONSECUTIVE_ERRORS}):`, err);
                if (errorCountRef.current >= MAX_CONSECUTIVE_ERRORS) {
                    stopPolling({
                        status: 'POLL_ERROR',
                        error: 'Lost connection to batch service. Check Batch Monitoring for actual status.',
                    });
                }
                // Otherwise: keep polling — transient blips are common
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
