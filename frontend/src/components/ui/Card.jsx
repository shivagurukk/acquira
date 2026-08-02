import React from 'react';

/**
 * Card — the standard surface. Compose with CardHeader / CardBody / CardFooter,
 * or pass `title`/`subtitle`/`actions` for the common header shape.
 *
 * Use `pad` when dropping arbitrary content straight in; leave it off when the
 * child is a DataTable (which manages its own edge-to-edge padding).
 */
export function CardHeader({ title, subtitle, actions, children, className = '' }) {
  if (children) return <div className={`ui-card__header ${className}`}>{children}</div>;
  return (
    <div className={`ui-card__header ${className}`}>
      <div style={{ minWidth: 0 }}>
        {title && <h3 className="ui-card__title">{title}</h3>}
        {subtitle && <p className="ui-card__subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="ui-row">{actions}</div>}
    </div>
  );
}

export function CardBody({ children, className = '', ...rest }) {
  return (
    <div className={`ui-card__body ${className}`} {...rest}>
      {children}
    </div>
  );
}

export function CardFooter({ children, className = '' }) {
  return <div className={`ui-card__footer ${className}`}>{children}</div>;
}

export default function Card({
  title,
  subtitle,
  actions,
  footer,
  pad = false,
  flat = false,
  className = '',
  children,
  ...rest
}) {
  const hasHeader = title || subtitle || actions;
  const classes = ['ui-card', flat && 'ui-card--flat', pad && !hasHeader && 'ui-card--pad', className]
    .filter(Boolean)
    .join(' ');

  return (
    <div className={classes} {...rest}>
      {hasHeader && <CardHeader title={title} subtitle={subtitle} actions={actions} />}
      {hasHeader && pad ? <CardBody>{children}</CardBody> : children}
      {footer && <CardFooter>{footer}</CardFooter>}
    </div>
  );
}
