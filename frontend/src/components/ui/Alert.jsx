import React from 'react';
import { Info, CheckCircle2, AlertTriangle, XCircle } from 'lucide-react';

const ICONS = {
  info: Info,
  success: CheckCircle2,
  warning: AlertTriangle,
  danger: XCircle,
};

/**
 * Alert — inline banner for page-level context, warnings and errors.
 * For transient feedback use the toast (`useToast`) instead.
 */
export default function Alert({
  tone = 'info',
  title,
  icon: IconOverride,
  actions,
  className = '',
  children,
}) {
  const Icon = IconOverride ?? ICONS[tone] ?? Info;

  return (
    <div className={`ui-alert ui-alert--${tone} ${className}`}>
      <Icon size={16} strokeWidth={2} className="ui-alert__icon" />
      <div className="ui-alert__body">
        {title && <p className="ui-alert__title">{title}</p>}
        {children}
      </div>
      {actions && <div className="ui-row">{actions}</div>}
    </div>
  );
}
