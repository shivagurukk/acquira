import React from 'react';
import { Link } from 'react-router-dom';

/**
 * Button — the only button in the admin surface.
 *
 * variant: 'default' | 'primary' | 'danger' | 'ghost' | 'subtle' | 'danger-ghost'
 * size:    'sm' | 'md' | 'lg'
 *
 * Pass `icon` for a leading lucide icon, `iconOnly` for a square icon button
 * (an aria-label is then required), `loading` to show a spinner and disable.
 * Pass `to` to render a react-router Link, `href` for an anchor.
 */
const Button = React.forwardRef(function Button(
  {
    variant = 'default',
    size = 'md',
    icon: Icon,
    iconRight: IconRight,
    iconOnly = false,
    loading = false,
    block = false,
    disabled,
    className = '',
    children,
    to,
    href,
    type = 'button',
    ...rest
  },
  ref
) {
  const iconSize = size === 'sm' ? 13 : size === 'lg' ? 17 : 15;

  const classes = [
    'ui-btn',
    variant !== 'default' && `ui-btn--${variant}`,
    size !== 'md' && `ui-btn--${size}`,
    iconOnly && 'ui-btn--icon',
    block && 'ui-btn--block',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  const content = (
    <>
      {loading ? (
        <span className="ui-btn__spinner" />
      ) : (
        Icon && <Icon size={iconSize} strokeWidth={2} />
      )}
      {!iconOnly && children}
      {IconRight && !loading && <IconRight size={iconSize} strokeWidth={2} />}
    </>
  );

  if (to) {
    return (
      <Link ref={ref} to={to} className={classes} {...rest}>
        {content}
      </Link>
    );
  }

  if (href) {
    return (
      <a ref={ref} href={href} className={classes} {...rest}>
        {content}
      </a>
    );
  }

  return (
    <button
      ref={ref}
      type={type}
      className={classes}
      disabled={disabled || loading}
      {...rest}
    >
      {content}
    </button>
  );
});

export default Button;
