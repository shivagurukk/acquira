import React, { useEffect, useRef, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import Button from './Button';

const FOCUSABLE =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Modal — portalled dialog with focus trap, Escape-to-close, scroll lock and
 * focus restoration. Renders nothing when `open` is false.
 *
 * size: 'sm' | 'md' | 'lg' | 'xl'
 *
 *   <Modal open={open} onClose={close} title="New entity"
 *          footer={<><Button onClick={close}>Cancel</Button>
 *                   <Button variant="primary" onClick={save}>Save</Button></>}>
 *     …fields…
 *   </Modal>
 *
 * Pass `as="form"` with `onSubmit` to make the footer's submit button work.
 */
export default function Modal({
  open,
  onClose,
  title,
  subtitle,
  footer,
  size = 'md',
  closeOnOverlay = true,
  showClose = true,
  as = 'div',
  className = '',
  children,
  ...rest
}) {
  const Element = as;
  const dialogRef = useRef(null);
  const restoreRef = useRef(null);

  // Escape to close
  useEffect(() => {
    if (!open) return;
    const onKey = (e) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose?.();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  // Lock background scroll
  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open]);

  // Move focus in on open, restore it on close
  useEffect(() => {
    if (!open) return;
    restoreRef.current = document.activeElement;
    const node = dialogRef.current;
    const first = node?.querySelector(FOCUSABLE);
    (first || node)?.focus();
    return () => {
      const el = restoreRef.current;
      if (el && typeof el.focus === 'function') el.focus();
    };
  }, [open]);

  // Trap Tab inside the dialog
  const onKeyDown = useCallback((e) => {
    if (e.key !== 'Tab') return;
    const nodes = Array.from(dialogRef.current?.querySelectorAll(FOCUSABLE) || []).filter(
      (n) => n.offsetParent !== null
    );
    if (nodes.length === 0) return;
    const first = nodes[0];
    const last = nodes[nodes.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  }, []);

  if (!open) return null;

  const labelId = title ? 'ui-modal-title' : undefined;

  return createPortal(
    <div
      className="ui-modal-overlay"
      onMouseDown={(e) => {
        if (closeOnOverlay && e.target === e.currentTarget) onClose?.();
      }}
    >
      <Element
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={labelId}
        tabIndex={-1}
        onKeyDown={onKeyDown}
        className={`ui-modal ${size !== 'md' ? `ui-modal--${size}` : ''} ${className}`}
        {...rest}
      >
        {(title || showClose) && (
          <div className="ui-modal__header">
            <div style={{ minWidth: 0 }}>
              {title && (
                <h2 className="ui-modal__title" id={labelId}>
                  {title}
                </h2>
              )}
              {subtitle && <p className="ui-modal__subtitle">{subtitle}</p>}
            </div>
            {showClose && (
              <Button variant="ghost" size="sm" iconOnly icon={X} onClick={onClose} aria-label="Close dialog" />
            )}
          </div>
        )}

        <div className="ui-modal__body">{children}</div>

        {footer && <div className="ui-modal__footer">{footer}</div>}
      </Element>
    </div>,
    document.body
  );
}
