import React from 'react';

/**
 * Page — the standard page shell. Fixes max width, padding, and the
 * title/subtitle/actions header so every admin screen lines up.
 *
 *   <Page title="Tenant management" subtitle="Financial institutions and jurisdictions"
 *         icon={Building2} actions={<Button variant="primary" icon={Plus}>Add</Button>}>
 *     …content…
 *   </Page>
 *
 * width: 'default' (1440) | 'narrow' (960) | 'wide' (no cap)
 * Use `flush` when the page is embedded inside another shell (e.g. SettingsHub).
 */
export default function Page({
  title,
  subtitle,
  icon: Icon,
  actions,
  width = 'default',
  flush = false,
  className = '',
  children,
}) {
  const classes = [
    'ui-page',
    width !== 'default' && `ui-page--${width}`,
    flush && 'ui-page--flush',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div className={classes}>
      {(title || actions) && (
        <header className="ui-page__header">
          <div className="ui-page__heading">
            {Icon && (
              <div className="ui-page__icon">
                <Icon size={19} strokeWidth={1.9} />
              </div>
            )}
            <div style={{ minWidth: 0 }}>
              {title && <h1 className="ui-page__title">{title}</h1>}
              {subtitle && <p className="ui-page__subtitle">{subtitle}</p>}
            </div>
          </div>
          {actions && <div className="ui-page__actions">{actions}</div>}
        </header>
      )}
      {children}
    </div>
  );
}

/** Stack — vertical rhythm between page sections. */
export function Stack({ gap = 'md', className = '', children, ...rest }) {
  const suffix = gap === 'sm' ? ' ui-stack--sm' : gap === 'lg' ? ' ui-stack--lg' : '';
  return (
    <div className={`ui-stack${suffix} ${className}`} {...rest}>
      {children}
    </div>
  );
}

/** Row — horizontal group with consistent gap. */
export function Row({ between = false, className = '', children, ...rest }) {
  return (
    <div className={`ui-row${between ? ' ui-row--between' : ''} ${className}`} {...rest}>
      {children}
    </div>
  );
}
