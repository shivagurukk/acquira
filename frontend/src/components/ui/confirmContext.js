import { createContext, useContext } from 'react';

/**
 * Split out from ConfirmDialog.jsx so that file exports only a component —
 * mixing component and non-component exports breaks Fast Refresh.
 */
export const ConfirmContext = createContext(null);

/**
 * Awaitable replacement for window.confirm. Resolves true on confirm,
 * false on cancel/escape/overlay dismiss.
 *
 *   const confirm = useConfirm();
 *   if (!await confirm({ title: 'Delete?', tone: 'danger' })) return;
 */
export function useConfirm() {
  const ctx = useContext(ConfirmContext);
  if (!ctx) throw new Error('useConfirm must be used inside <ConfirmProvider>');
  return ctx;
}
