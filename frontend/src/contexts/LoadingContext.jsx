import React, {
  createContext, useContext, useState, useRef, useCallback, useEffect,
} from 'react';

/**
 * Global API loading indicator.
 *
 * Every page in Acquira fetches through the shared axios instance
 * (src/api/axios.js). Its interceptors call startLoading() when a request
 * goes out and stopLoading() when it settles. This component turns that
 * in-flight count into a thin top progress bar — so EVERY page gets a
 * loader automatically, with no per-page code.
 *
 * Mirrors the module-level pattern used by ToastContext.showToast: the
 * start/stop functions can be called from outside React (the interceptors).
 *
 * Behaviour, matching the NProgress UX people expect:
 *   - 150ms grace period: requests that finish faster never flash the bar.
 *   - Trickles up toward ~90% while requests are pending, then snaps to
 *     100% and fades out once the in-flight count hits zero.
 *   - A counter (not a boolean) so overlapping requests don't end early.
 */
const LoadingContext = createContext(null);

let _start = null;
let _stop = null;

/** Called by the axios request interceptor. Safe outside React. */
export function startLoading() {
  if (_start) _start();
}
/** Called by the axios response/error interceptors. Safe outside React. */
export function stopLoading() {
  if (_stop) _stop();
}

export const LoadingProvider = ({ children }) => {
  const [progress, setProgress] = useState(0); // 0 = hidden
  const [visible, setVisible] = useState(false);

  const inflight = useRef(0);
  const trickleTimer = useRef(null);
  const graceTimer = useRef(null);
  const doneTimer = useRef(null);

  const clearTrickle = () => {
    if (trickleTimer.current) { clearInterval(trickleTimer.current); trickleTimer.current = null; }
  };

  const begin = useCallback(() => {
    setVisible(true);
    setProgress(p => (p < 8 ? 8 : p));
    clearTrickle();
    // Creep toward 90% — never reaches 100 until requests actually finish.
    trickleTimer.current = setInterval(() => {
      setProgress(p => {
        if (p >= 90) return p;
        const step = p < 40 ? 6 : p < 70 ? 3 : 1.2;
        return Math.min(90, p + step);
      });
    }, 220);
  }, []);

  const finish = useCallback(() => {
    clearTrickle();
    setProgress(100);
    doneTimer.current = setTimeout(() => {
      setVisible(false);
      setProgress(0);
    }, 320); // let the 100% + fade play out
  }, []);

  const start = useCallback(() => {
    inflight.current += 1;
    if (inflight.current === 1) {
      // Grace period — don't show the bar for very fast calls.
      if (doneTimer.current) { clearTimeout(doneTimer.current); doneTimer.current = null; }
      graceTimer.current = setTimeout(() => { begin(); }, 150);
    }
  }, [begin]);

  const stop = useCallback(() => {
    inflight.current = Math.max(0, inflight.current - 1);
    if (inflight.current === 0) {
      if (graceTimer.current) { clearTimeout(graceTimer.current); graceTimer.current = null; }
      // Only animate completion if the bar was actually shown.
      setVisible(v => {
        if (v) finish();
        return v;
      });
    }
  }, [finish]);

  // Expose to the axios interceptors (outside React).
  _start = start;
  _stop = stop;

  useEffect(() => () => {
    clearTrickle();
    if (graceTimer.current) clearTimeout(graceTimer.current);
    if (doneTimer.current) clearTimeout(doneTimer.current);
  }, []);

  return (
    <LoadingContext.Provider value={{ visible, progress }}>
      {/* Fixed top bar — sits above everything, independent of layout. */}
      <div
        aria-hidden="true"
        style={{
          position: 'fixed',
          top: 0, left: 0,
          height: 3,
          width: `${progress}%`,
          zIndex: 10000,
          background: 'linear-gradient(90deg, var(--accent, #1E3A8A), color-mix(in srgb, var(--accent, #1E3A8A) 55%, #ffffff))',
          boxShadow: '0 0 8px color-mix(in srgb, var(--accent, #1E3A8A) 70%, transparent)',
          opacity: visible ? 1 : 0,
          transition: 'width 0.22s ease, opacity 0.32s ease',
          pointerEvents: 'none',
          borderTopRightRadius: 2,
          borderBottomRightRadius: 2,
        }}
      />
      {children}
    </LoadingContext.Provider>
  );
};

export const useLoading = () => {
  const ctx = useContext(LoadingContext);
  if (!ctx) throw new Error('useLoading must be used within LoadingProvider');
  return ctx;
};

export default LoadingContext;
