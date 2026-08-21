import React, { useId } from 'react';

/**
 * Form primitives — label + control + hint/error, wired for accessibility.
 *
 * Typical use:
 *   <FormField label="Entity name" required hint="Shown across all reports">
 *     <Input value={name} onChange={e => setName(e.target.value)} />
 *   </FormField>
 *
 * FormField injects id / aria-describedby / aria-invalid into its child, so
 * the control does not need them spelled out.
 */
export function FormField({
  label,
  required = false,
  hint,
  error,
  htmlFor,
  className = '',
  children,
}) {
  const autoId = useId();
  const id = htmlFor || autoId;
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [errorId, hintId].filter(Boolean).join(' ') || undefined;

  const child = React.isValidElement(children)
    ? React.cloneElement(children, {
        id: children.props.id || id,
        'aria-describedby': children.props['aria-describedby'] || describedBy,
        'aria-invalid': error ? true : children.props['aria-invalid'],
        invalid: error ? true : children.props.invalid,
      })
    : children;

  return (
    <div className={`ui-field ${className}`}>
      {label && (
        <label className="ui-field__label" htmlFor={id}>
          {label}
          {required && <span className="ui-field__required" aria-hidden="true">*</span>}
        </label>
      )}
      {child}
      {error && (
        <span className="ui-field__error" id={errorId} role="alert">
          {error}
        </span>
      )}
      {hint && !error && (
        <span className="ui-field__hint" id={hintId}>
          {hint}
        </span>
      )}
    </div>
  );
}

const controlClasses = (invalid, mono, className) =>
  ['ui-input', invalid && 'ui-input--invalid', mono && 'ui-input--mono', className]
    .filter(Boolean)
    .join(' ');

export const Input = React.forwardRef(function Input(
  { invalid = false, mono = false, className = '', ...rest },
  ref
) {
  return <input ref={ref} className={controlClasses(invalid, mono, className)} {...rest} />;
});

export const Textarea = React.forwardRef(function Textarea(
  { invalid = false, mono = false, rows = 5, className = '', ...rest },
  ref
) {
  return (
    <textarea ref={ref} rows={rows} className={controlClasses(invalid, mono, className)} {...rest} />
  );
});

/**
 * Select — pass `options` as [{value, label}] or plain strings, or supply
 * <option> children directly. `placeholder` renders a leading empty option.
 */
export const Select = React.forwardRef(function Select(
  { invalid = false, className = '', options, placeholder, children, ...rest },
  ref
) {
  return (
    <select ref={ref} className={controlClasses(invalid, false, className)} {...rest}>
      {placeholder && <option value="">{placeholder}</option>}
      {options
        ? options.map((o) => {
            const value = typeof o === 'object' ? o.value : o;
            const label = typeof o === 'object' ? o.label : o;
            return (
              <option key={value} value={value}>
                {label}
              </option>
            );
          })
        : children}
    </select>
  );
});

export function Checkbox({ label, hint, disabled = false, className = '', ...rest }) {
  return (
    <label className={`ui-check ${disabled ? 'ui-check--disabled' : ''} ${className}`}>
      <input type="checkbox" disabled={disabled} {...rest} />
      <span className="ui-check__text">
        {label}
        {hint && <span className="ui-check__hint">{hint}</span>}
      </span>
    </label>
  );
}

/** Switch — for settings that apply immediately. Use Checkbox inside forms. */
export function Switch({ checked, onChange, label, disabled = false, className = '', ...rest }) {
  return (
    <label className={`ui-switch ${className}`}>
      <input
        type="checkbox"
        role="switch"
        checked={!!checked}
        onChange={onChange}
        disabled={disabled}
        {...rest}
      />
      <span className="ui-switch__track">
        <span className="ui-switch__thumb" />
      </span>
      {label && <span className="ui-switch__label">{label}</span>}
    </label>
  );
}

/** FormGrid — responsive column layout for field groups. cols: 1 | 2 | 3 | 4 */
export function FormGrid({ cols = 2, className = '', children, ...rest }) {
  return (
    <div className={`ui-form-grid ui-form-grid--${cols} ${className}`} {...rest}>
      {children}
    </div>
  );
}
