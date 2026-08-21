import React from 'react';

/**
 * Badge — status pill.
 * tone: 'neutral' | 'success' | 'warning' | 'danger' | 'info' | 'brand'
 */
export default function Badge({
  tone = 'neutral',
  dot = false,
  mono = false,
  icon: Icon,
  className = '',
  children,
  ...rest
}) {
  const classes = [
    'ui-badge',
    `ui-badge--${tone}`,
    dot && 'ui-badge--dot',
    mono && 'ui-badge--mono',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <span className={classes} {...rest}>
      {Icon && <Icon size={11} strokeWidth={2.4} />}
      {children}
    </span>
  );
}

/** Maps common backend status strings to a tone, so pages stop re-deriving it. */
const STATUS_TONES = {
  SUCCESS: 'success', COMPLETED: 'success', ACTIVE: 'success', ENABLED: 'success',
  PASSED: 'success', APPLIED: 'success', HEALTHY: 'success', CONNECTED: 'success',
  YES: 'success', TRUE: 'success', ONLINE: 'success', SENT: 'success', APPROVED: 'success',
  FAILED: 'danger', ERROR: 'danger', INACTIVE: 'danger', DISABLED: 'danger',
  REJECTED: 'danger', OFFLINE: 'danger', EXPIRED: 'danger', NO: 'danger', FALSE: 'danger',
  PENDING: 'warning', RUNNING: 'warning', IN_PROGRESS: 'warning', WARNING: 'warning',
  PARTIAL: 'warning', QUEUED: 'warning', PAUSED: 'warning', DRAFT: 'warning',
  INFO: 'info', SCHEDULED: 'info', NEW: 'info',
};

export function StatusBadge({ status, dot = true, ...rest }) {
  const key = String(status ?? '').toUpperCase().replace(/[\s-]/g, '_');
  return (
    <Badge tone={STATUS_TONES[key] || 'neutral'} dot={dot} {...rest}>
      {status ?? '—'}
    </Badge>
  );
}
