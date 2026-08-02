import React, { useCallback, useRef, useState } from 'react';
import { AlertTriangle, Trash2, Info } from 'lucide-react';
import Modal from './Modal';
import Button from './Button';
import { ConfirmContext } from './confirmContext';

/**
 * Replacement for window.confirm — themed, focus-trapped, and awaitable.
 *
 * Mount <ConfirmProvider> once (App.jsx), then anywhere:
 *
 *   const confirm = useConfirm();
 *   if (!await confirm({ title: 'Delete script?', message: '…', tone: 'danger' })) return;
 *
 * Resolves true on confirm, false on cancel/escape/overlay.
 */

const ICONS = { danger: Trash2, warning: AlertTriangle, info: Info };

export function ConfirmProvider({ children }) {
  const [state, setState] = useState(null);
  const resolverRef = useRef(null);

  const confirm = useCallback((options) => {
    const opts = typeof options === 'string' ? { message: options } : options || {};
    setState({
      title: 'Are you sure?',
      message: '',
      confirmLabel: 'Confirm',
      cancelLabel: 'Cancel',
      tone: 'warning',
      ...opts,
    });
    return new Promise((resolve) => {
      resolverRef.current = resolve;
    });
  }, []);

  const settle = useCallback((result) => {
    resolverRef.current?.(result);
    resolverRef.current = null;
    setState(null);
  }, []);

  const Icon = state ? ICONS[state.tone] || AlertTriangle : null;

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      <Modal
        open={!!state}
        onClose={() => settle(false)}
        size="sm"
        showClose={false}
        footer={
          <>
            <Button onClick={() => settle(false)}>{state?.cancelLabel}</Button>
            <Button
              variant={state?.tone === 'danger' ? 'danger' : 'primary'}
              onClick={() => settle(true)}
            >
              {state?.confirmLabel}
            </Button>
          </>
        }
      >
        {state && (
          <div className="ui-confirm__body">
            <div className={`ui-confirm__icon ui-confirm__icon--${state.tone}`}>
              <Icon size={19} strokeWidth={2} />
            </div>
            <div style={{ minWidth: 0 }}>
              <h2 className="ui-modal__title" style={{ marginBottom: 6 }}>
                {state.title}
              </h2>
              {state.message && <p className="ui-confirm__message">{state.message}</p>}
            </div>
          </div>
        )}
      </Modal>
    </ConfirmContext.Provider>
  );
}
