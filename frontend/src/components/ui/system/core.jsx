/* Props listed only to keep them off the DOM are named _x and dropped via a rest sibling. */
/* eslint no-unused-vars: ["error", { "varsIgnorePattern": "^[A-Z_]", "argsIgnorePattern": "^_", "ignoreRestSiblings": true, "caughtErrors": "none" }] */
/* eslint-disable react-refresh/only-export-components -- component library module: also exports hooks and factory-built components */
// In-house component layer that replaced @mui/material.
//
// Scope is exactly what the app uses: each component implements the props the
// pages pass and keeps the Mui* class names pages target from `sx`. Looks are
// defined in system.css; one-off styling goes through `sx` (see sx.js).
import React, {
    Children, cloneElement, createContext, forwardRef, isValidElement,
    useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState,
} from 'react';
import { createPortal } from 'react-dom';
import { sxClass, splitSystemProps, cx, BREAKPOINTS } from './sx';

export { theme } from './sx';
import { theme as systemTheme } from './sx';

/** Static theme object (palette values are CSS variables, so it never changes with the colour scheme). */
export const useTheme = () => systemTheme;

const cap = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : '');
const setRef = (ref, value) => { if (typeof ref === 'function') ref(value); else if (ref) ref.current = value; };
const useForkRef = (...refs) => useCallback((node) => refs.forEach((r) => setRef(r, node)), refs); // eslint-disable-line react-hooks/exhaustive-deps

const SvgIcon = ({ d, className, ...p }) => (
    <svg className={cx('MuiSvgIcon-root', className)} focusable="false" aria-hidden="true" viewBox="0 0 24 24" {...p}><path d={d} /></svg>
);
const ICON = {
    arrowDown: 'M7 10l5 5 5-5z',
    close: 'M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
    cancel: 'M12 2C6.47 2 2 6.47 2 12s4.47 10 10 10 10-4.47 10-10S17.53 2 12 2zm5 13.59L15.59 17 12 13.41 8.41 17 7 15.59 10.59 12 7 8.41 8.41 7 12 10.59 15.59 7 17 8.41 13.41 12 17 15.59z',
    boxOff: 'M19 5v14H5V5h14m0-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z',
    boxOn: 'M19 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.11 0 2-.9 2-2V5c0-1.1-.89-2-2-2zm-9 14l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z',
    boxMixed: 'M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-2 10H7v-2h10v2z',
    success: 'M20,12A8,8 0 0,1 12,20A8,8 0 0,1 4,12A8,8 0 0,1 12,4C12.76,4 13.5,4.11 14.2,4.31L15.77,2.74C14.61,2.26 13.34,2 12,2A10,10 0 0,0 2,12A10,10 0 0,0 12,22A10,10 0 0,0 22,12M7.91,10.08L6.5,11.5L11,16L21,6L19.59,4.58L11,13.17L7.91,10.08Z',
    info: 'M11,9H13V7H11M12,20C7.59,20 4,16.41 4,12C4,7.59 7.59,4 12,4C16.41,4 20,7.59 20,12C20,16.41 16.41,20 12,20M12,2A10,10 0 0,0 2,12A10,10 0 0,0 12,22A10,10 0 0,0 22,12A10,10 0 0,0 12,2M11,17H13V11H11V17Z',
    warning: 'M12 5.99L19.53 19H4.47L12 5.99M12 2L1 21h22L12 2zm1 14h-2v2h2v-2zm0-6h-2v4h2v-4z',
    error: 'M11 15h2v2h-2zm0-8h2v6h-2zm.99-5C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z',
};

// ── layout primitives ───────────────────────────────────────────────────────

export const Box = forwardRef(function Box({ component: C = 'div', sx, className, ...props }, ref) {
    const [system, rest] = splitSystemProps(props);
    return <C ref={ref} className={cx('MuiBox-root', className, sxClass(system ? [system, sx] : sx))} {...rest} />;
});

const VARIANT_TAG = { h1: 'h1', h2: 'h2', h3: 'h3', h4: 'h4', h5: 'h5', h6: 'h6', subtitle1: 'h6', subtitle2: 'h6', body1: 'p', body2: 'p', inherit: 'p' };
export const Typography = forwardRef(function Typography(
    { variant = 'body1', component, align, noWrap, gutterBottom, paragraph, sx, className, ...props }, ref,
) {
    const [system, rest] = splitSystemProps(props);
    const C = component || (paragraph ? 'p' : VARIANT_TAG[variant]) || 'span';
    const extra = align ? { textAlign: align } : null;
    return (
        <C
            ref={ref}
            className={cx('MuiTypography-root', `MuiTypography-${variant}`, noWrap && 'MuiTypography-noWrap',
                gutterBottom && 'MuiTypography-gutterBottom', className, sxClass([extra, system, sx]))}
            {...rest}
        />
    );
});

export const Stack = forwardRef(function Stack(
    { component: C = 'div', direction = 'column', spacing = 0, useFlexGap, divider, sx, className, children, ...props }, ref,
) {
    const [system, rest] = splitSystemProps(props);
    const isRow = typeof direction === 'string' && direction.startsWith('row');
    let gapStyle = null;
    if (spacing) {
        // Without useFlexGap the original spaced children with sibling margins
        // (so a wrapped row gets no row gap); keep that exact behaviour.
        gapStyle = useFlexGap || typeof direction !== 'string'
            ? { gap: spacing }
            : {
                '& > :not(style):not(style)': { margin: 0 },
                '& > :not(style) ~ :not(style)': { [isRow ? (direction === 'row-reverse' ? 'marginRight' : 'marginLeft') : (direction === 'column-reverse' ? 'marginBottom' : 'marginTop')]: spacing },
            };
    }
    const kids = divider
        ? Children.toArray(children).filter(Boolean).flatMap((c, i) => (i ? [cloneElement(divider, { key: `d${i}` }), c] : [c]))
        : children;
    return (
        <C ref={ref} className={cx('MuiStack-root', className, sxClass([{ flexDirection: direction }, gapStyle, system, sx]))} {...rest}>
            {kids}
        </C>
    );
});

export const Container = forwardRef(function Container({ maxWidth = 'lg', disableGutters, sx, className, ...rest }, ref) {
    return (
        <div ref={ref}
            className={cx('MuiContainer-root', disableGutters && 'MuiContainer-disableGutters', className,
                sxClass([maxWidth ? { maxWidth: { [maxWidth]: BREAKPOINTS[maxWidth] } } : null, sx]))}
            {...rest} />
    );
});

// Grid keeps the behaviour the pages had on the previous library version:
// a container is a wrapping flex row spaced by `spacing`; an item is sized
// only by an explicit `size`. The legacy item/xs/sm/md props were already
// inert there, so they are accepted and ignored here too.
export const Grid = forwardRef(function Grid(
    { container, spacing, rowSpacing, columnSpacing, size, columns = 12, sx, className,
        item: _item, xs: _xs, sm: _sm, md: _md, lg: _lg, xl: _xl, zeroMinWidth: _z, ...props }, ref,
) {
    const [system, rest] = splitSystemProps(props);
    const width = (n) => (n === 'grow' ? { flexGrow: 1, flexBasis: 0, maxWidth: '100%' }
        : n === 'auto' ? { flex: '0 0 auto', width: 'auto' }
            : { flexGrow: 0, flexBasis: 'auto', width: `calc(100% * ${n} / ${columns})` });
    let sizing = null;
    if (size !== undefined) {
        if (size && typeof size === 'object') {
            sizing = {};
            for (const bp of Object.keys(BREAKPOINTS)) {
                if (size[bp] === undefined) continue;
                const w = width(size[bp]);
                if (BREAKPOINTS[bp] === 0) Object.assign(sizing, w);
                else sizing[`@media (min-width:${BREAKPOINTS[bp]}px)`] = w;
            }
        } else sizing = width(size);
    }
    const gap = container && (spacing !== undefined || rowSpacing !== undefined || columnSpacing !== undefined)
        ? { rowGap: rowSpacing ?? spacing ?? 0, columnGap: columnSpacing ?? spacing ?? 0 } : null;
    return (
        <div ref={ref} className={cx('MuiGrid-root', container && 'MuiGrid-container', className, sxClass([gap, sizing, system, sx]))} {...rest} />
    );
});

export const Divider = forwardRef(function Divider({ orientation = 'horizontal', flexItem, component, sx, className, ...rest }, ref) {
    const C = component || (orientation === 'vertical' ? 'div' : 'hr');
    return <C ref={ref} className={cx('MuiDivider-root', orientation === 'vertical' && 'MuiDivider-vertical', flexItem && 'MuiDivider-flexItem', className, sxClass(sx))} {...rest} />;
});

// ── surfaces ────────────────────────────────────────────────────────────────

export const Paper = forwardRef(function Paper({ component: C = 'div', elevation = 0, variant = 'elevation', square, sx, className, ...rest }, ref) {
    return (
        <C ref={ref}
            className={cx('MuiPaper-root', variant === 'outlined' ? 'MuiPaper-outlined' : `MuiPaper-elevation MuiPaper-elevation${elevation}`,
                !square && 'MuiPaper-rounded', className, sxClass(sx))}
            {...rest} />
    );
});

export const Card = forwardRef(function Card({ className, ...rest }, ref) {
    return <Paper ref={ref} className={cx('MuiCard-root', className)} {...rest} />;
});

export const CardContent = forwardRef(function CardContent({ component: C = 'div', sx, className, ...rest }, ref) {
    return <C ref={ref} className={cx('MuiCardContent-root', className, sxClass(sx))} {...rest} />;
});

// ── buttons ─────────────────────────────────────────────────────────────────

export const Button = forwardRef(function Button(
    { variant = 'text', color = 'primary', size = 'medium', startIcon, endIcon, disabled, fullWidth, disableElevation: _de,
        disableRipple: _dr, component, href, type, sx, className, children, ...rest }, ref,
) {
    const C = component || (href ? 'a' : 'button');
    const extra = C === 'button' ? { type: type || 'button', disabled } : { 'aria-disabled': disabled || undefined, href };
    return (
        <C ref={ref}
            className={cx('MuiButtonBase-root MuiButton-root', `MuiButton-${variant}`, `MuiButton-color${cap(color)}`,
                `MuiButton-size${cap(size)}`, fullWidth && 'MuiButton-fullWidth', disabled && 'Mui-disabled', className, sxClass(sx))}
            {...extra} {...rest}>
            {startIcon && <span className="MuiButton-icon MuiButton-startIcon">{startIcon}</span>}
            {children}
            {endIcon && <span className="MuiButton-icon MuiButton-endIcon">{endIcon}</span>}
        </C>
    );
});

export const IconButton = forwardRef(function IconButton(
    { size = 'medium', color = 'default', disabled, edge: _edge, disableRipple: _dr, component: C = 'button', type, sx, className, ...rest }, ref,
) {
    const extra = C === 'button' ? { type: type || 'button', disabled } : {};
    return (
        <C ref={ref}
            className={cx('MuiButtonBase-root MuiIconButton-root', `MuiIconButton-size${cap(size)}`, color !== 'default' && `MuiIconButton-color${cap(color)}`,
                disabled && 'Mui-disabled', className, sxClass(sx))}
            {...extra} {...rest} />
    );
});

const ToggleGroupContext = createContext(null);

export const ToggleButtonGroup = forwardRef(function ToggleButtonGroup(
    { value, exclusive, onChange, size = 'medium', fullWidth, disabled, sx, className, children, ...rest }, ref,
) {
    const ctx = useMemo(() => ({ value, exclusive, onChange, size, disabled }), [value, exclusive, onChange, size, disabled]);
    return (
        <ToggleGroupContext.Provider value={ctx}>
            <div ref={ref} role="group" className={cx('MuiToggleButtonGroup-root', fullWidth && 'MuiToggleButtonGroup-fullWidth', className, sxClass(sx))} {...rest}>
                {children}
            </div>
        </ToggleGroupContext.Provider>
    );
});

export const ToggleButton = forwardRef(function ToggleButton(
    { value, selected, onChange, onClick, size, disabled, sx, className, ...rest }, ref,
) {
    const group = useContext(ToggleGroupContext);
    const isSelected = selected ?? (group
        ? (group.exclusive ? group.value === value : Array.isArray(group.value) && group.value.includes(value))
        : false);
    const isDisabled = disabled ?? group?.disabled;
    const handle = (e) => {
        onClick?.(e, value);
        if (e.defaultPrevented) return;
        onChange?.(e, value);
        if (!group?.onChange) return;
        if (group.exclusive) group.onChange(e, group.value === value ? null : value);
        else {
            const cur = Array.isArray(group.value) ? group.value : [];
            group.onChange(e, cur.includes(value) ? cur.filter((v) => v !== value) : [...cur, value]);
        }
    };
    return (
        <button ref={ref} type="button" value={value} aria-pressed={isSelected} disabled={isDisabled} onClick={handle}
            className={cx('MuiButtonBase-root MuiToggleButton-root', `MuiToggleButton-size${cap(size || group?.size || 'medium')}`,
                isSelected && 'Mui-selected', isDisabled && 'Mui-disabled', className, sxClass(sx))}
            {...rest} />
    );
});

// ── chip ────────────────────────────────────────────────────────────────────

export const Chip = forwardRef(function Chip(
    { label, size = 'medium', variant = 'filled', color = 'default', icon, deleteIcon, onDelete, onClick, clickable, disabled,
        component, sx, className, ...rest }, ref,
) {
    const isClickable = clickable ?? !!onClick;
    const C = component || 'div';
    const del = onDelete ? (
        isValidElement(deleteIcon)
            ? cloneElement(deleteIcon, { className: cx(deleteIcon.props.className, 'MuiChip-deleteIcon'), onClick: (e) => { e.stopPropagation(); onDelete(e); } })
            : <SvgIcon d={ICON.cancel} className="MuiChip-deleteIcon" onClick={(e) => { e.stopPropagation(); onDelete(e); }} />
    ) : null;
    return (
        <C ref={ref} role={isClickable ? 'button' : undefined} tabIndex={isClickable || onDelete ? 0 : undefined}
            onClick={onClick}
            onKeyDown={(e) => {
                if (isClickable && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); onClick?.(e); }
                if (onDelete && (e.key === 'Backspace' || e.key === 'Delete')) onDelete(e);
            }}
            className={cx(isClickable && 'MuiButtonBase-root', 'MuiChip-root', `MuiChip-size${cap(size)}`, `MuiChip-${variant}`, color !== 'default' && `MuiChip-color${cap(color)}`,
                isClickable && 'MuiChip-clickable', onDelete && 'MuiChip-deletable', disabled && 'Mui-disabled', className, sxClass(sx))}
            {...rest}>
            {isValidElement(icon) ? cloneElement(icon, { className: cx(icon.props.className, 'MuiChip-icon') }) : null}
            <span className="MuiChip-label">{label}</span>
            {del}
        </C>
    );
});

// ── inputs ──────────────────────────────────────────────────────────────────

const FormControlContext = createContext(null);
const DATE_TYPES = new Set(['date', 'time', 'datetime-local', 'month', 'week']);

export const FormControl = forwardRef(function FormControl(
    { size = 'medium', variant = 'outlined', fullWidth, disabled, error, required, component: C = 'div', sx, className, children, ...rest }, ref,
) {
    const [focused, setFocused] = useState(false);
    const [filled, setFilled] = useState(false);
    const ctx = useMemo(() => ({ size, variant, fullWidth, disabled, error, required, focused, setFocused, filled, setFilled }),
        [size, variant, fullWidth, disabled, error, required, focused, filled]);
    return (
        <FormControlContext.Provider value={ctx}>
            <C ref={ref} className={cx('MuiFormControl-root', fullWidth && 'MuiFormControl-fullWidth', className, sxClass(sx))} {...rest}>{children}</C>
        </FormControlContext.Provider>
    );
});

export const InputLabel = forwardRef(function InputLabel(
    { shrink, variant, size, focused, error, disabled, required, sx, className, children, ...rest }, ref,
) {
    const fc = useContext(FormControlContext);
    const v = variant || fc?.variant || 'outlined';
    const s = size || fc?.size || 'medium';
    const isFocused = focused ?? fc?.focused;
    const isShrunk = shrink ?? (isFocused || fc?.filled);
    return (
        <label ref={ref} data-shrink={!!isShrunk}
            className={cx('MuiFormLabel-root MuiInputLabel-root', `MuiInputLabel-${v}`, s === 'small' && 'MuiInputLabel-sizeSmall',
                isShrunk && 'MuiInputLabel-shrink', isFocused && 'Mui-focused', (error ?? fc?.error) && 'Mui-error',
                (disabled ?? fc?.disabled) && 'Mui-disabled', className, sxClass(sx))}
            {...rest}>
            {children}{(required ?? fc?.required) && <span aria-hidden className="MuiInputLabel-asterisk">&thinsp;*</span>}
        </label>
    );
});

export const InputAdornment = forwardRef(function InputAdornment({ position, component: C = 'div', sx, className, children, ...rest }, ref) {
    return (
        <C ref={ref} className={cx('MuiInputAdornment-root', `MuiInputAdornment-position${cap(position)}`, className, sxClass(sx))} {...rest}>
            {/* zero-width text keeps the adornment one text line tall, so icon-only adornments sit on the input's baseline */}
            {position === 'start' && <span className="notranslate" aria-hidden>&#8203;</span>}
            {typeof children === 'string' ? <Typography color="text.secondary">{children}</Typography> : children}
        </C>
    );
});

// Shared shell for text inputs and selects: outline / underline, adornments.
const InputRoot = forwardRef(function InputRoot(
    { variant, size, fullWidth, multiline, focused, error, disabled, label, notched, startAdornment, endAdornment,
        disableUnderline, hidePlaceholder, className, sx, children, ...rest }, ref,
) {
    const outlined = variant !== 'standard' && variant !== 'filled';
    return (
        <div ref={ref}
            className={cx('MuiInputBase-root', outlined ? 'MuiOutlinedInput-root' : 'MuiInput-root', !outlined && !disableUnderline && 'MuiInput-underline',
                'MuiInputBase-colorPrimary', size === 'small' && 'MuiInputBase-sizeSmall', fullWidth && 'MuiInputBase-fullWidth',
                multiline && 'MuiInputBase-multiline', focused && 'Mui-focused', error && 'Mui-error', disabled && 'Mui-disabled',
                startAdornment && 'MuiInputBase-adornedStart', endAdornment && 'MuiInputBase-adornedEnd',
                outlined && (label ? notched && 'MuiOutlinedInput-notched' : 'MuiOutlinedInput-noLabel'),
                hidePlaceholder && 'MuiInputBase-hidePlaceholder', className, sxClass(sx))}
            {...rest}>
            {startAdornment}
            {children}
            {endAdornment}
            {outlined && (
                <fieldset aria-hidden className="MuiOutlinedInput-notchedOutline">
                    <legend><span>{label}</span></legend>
                </fieldset>
            )}
        </div>
    );
});

const SelectContext = createContext(null);

const flattenChildren = (children, out = []) => {
    Children.forEach(children, (c) => {
        if (!isValidElement(c)) return;
        if (c.type === React.Fragment) flattenChildren(c.props.children, out);
        else out.push(c);
    });
    return out;
};

const SelectField = forwardRef(function SelectField(
    { value, onChange, children, displayEmpty, renderValue, MenuProps = {}, disabled, name, id, inputRootProps, onOpen, onClose,
        open: openProp, onFocus, onBlur, autoFocus, IconComponent, inputProps: _ip, labelId: _labelId, multiple: _multiple, native: _native,
        defaultValue: _dv, ...rest }, ref,
) {
    const anchor = useRef(null);
    const [openState, setOpenState] = useState(false);
    const open = openProp ?? openState;
    const items = flattenChildren(children);
    const selected = items.find((c) => c.props.value !== undefined && String(c.props.value) === String(value ?? ''));
    const isEmpty = value === undefined || value === null || value === '';
    let display;
    if (renderValue && (!isEmpty || displayEmpty)) display = renderValue(value);
    else if (selected && (!isEmpty || displayEmpty)) display = selected.props.children;
    else display = null;

    const setOpen = (next, e) => {
        if (disabled) return;
        setOpenState(next);
        if (next) onOpen?.(e); else onClose?.(e);
    };
    const select = (v, e, child) => {
        setOpen(false, e);
        if (String(v) !== String(value ?? '')) onChange?.({ ...e, target: { value: v, name }, currentTarget: { value: v, name }, type: 'change' }, child);
    };
    const ctx = useMemo(() => ({ value, select }), [value, onChange, name]); // eslint-disable-line react-hooks/exhaustive-deps
    const handleRef = useForkRef(anchor, ref);
    const Icon = IconComponent;
    return (
        <>
            <InputRoot ref={handleRef} {...inputRootProps} {...rest} disabled={disabled}
                onClick={(e) => { rest.onClick?.(e); setOpen(!open, e); }}>
                <div id={id} role="combobox" tabIndex={disabled ? -1 : 0} aria-expanded={open} aria-haspopup="listbox" aria-disabled={disabled || undefined}
                    autoFocus={autoFocus}
                    className={cx('MuiSelect-select MuiInputBase-input', inputRootProps.variant !== 'standard' ? 'MuiOutlinedInput-input' : 'MuiInput-input')}
                    onFocus={onFocus} onBlur={onBlur}
                    onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); setOpen(true, e); }
                    }}>
                    {display ?? <span className="notranslate" aria-hidden>&#8203;</span>}
                </div>
                <input type="hidden" name={name} value={Array.isArray(value) ? value.join(',') : (value ?? '')} readOnly aria-hidden tabIndex={-1} />
                {Icon ? <Icon className={cx('MuiSelect-icon', open && 'MuiSelect-iconOpen')} />
                    : <SvgIcon d={ICON.arrowDown} className={cx('MuiSelect-icon', open && 'MuiSelect-iconOpen')} />}
            </InputRoot>
            <Menu open={open} anchorEl={anchor.current} onClose={(e) => setOpen(false, e)} matchAnchorWidth {...MenuProps}>
                <SelectContext.Provider value={ctx}>{children}</SelectContext.Provider>
            </Menu>
        </>
    );
});

export const Select = forwardRef(function Select(
    { label, size, variant, fullWidth, error, disabled, startAdornment, endAdornment, disableUnderline, sx, className, onFocus, onBlur, value, ...rest }, ref,
) {
    const fc = useContext(FormControlContext);
    const [focused, setFocused] = useState(false);
    const filled = !(value === undefined || value === null || value === '') || !!rest.displayEmpty || !!startAdornment;
    const setFcFilled = fc?.setFilled;
    const setFcFocused = fc?.setFocused;
    useEffect(() => { setFcFilled?.(filled); }, [filled, setFcFilled]);
    const v = variant || fc?.variant || 'outlined';
    return (
        <SelectField ref={ref} value={value} {...rest} disabled={disabled ?? fc?.disabled}
            onFocus={(e) => { setFocused(true); setFcFocused?.(true); onFocus?.(e); }}
            onBlur={(e) => { setFocused(false); setFcFocused?.(false); onBlur?.(e); }}
            inputRootProps={{
                variant: v, size: size || fc?.size || 'medium', fullWidth: fullWidth ?? fc?.fullWidth, focused, error: error ?? fc?.error,
                label, notched: focused || filled, startAdornment, endAdornment, disableUnderline,
                className: cx('MuiSelect-root', className), sx,
            }} />
    );
});

export const MenuItem = forwardRef(function MenuItem(
    { value, selected, disabled, onClick, component: C = 'li', dense: _dense, divider: _divider, sx, className, children, ...rest }, ref,
) {
    const sel = useContext(SelectContext);
    const isSelected = selected ?? (sel && value !== undefined ? String(sel.value ?? '') === String(value) : false);
    return (
        <C ref={ref} role={sel ? 'option' : 'menuitem'} aria-selected={sel ? isSelected : undefined} aria-disabled={disabled || undefined}
            tabIndex={disabled ? -1 : 0} data-value={value}
            className={cx('MuiButtonBase-root MuiMenuItem-root', isSelected && 'Mui-selected', disabled && 'Mui-disabled', className, sxClass(sx))}
            onClick={(e) => { if (disabled) return; onClick?.(e); if (sel && value !== undefined) sel.select(value, e, { props: { value, children } }); }}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); e.currentTarget.click(); } }}
            {...rest}>
            {children}
        </C>
    );
});

export const TextField = forwardRef(function TextField(props, ref) {
    const {
        label, value, defaultValue, onChange, placeholder, type = 'text', size = 'small', variant = 'outlined', fullWidth, multiline, rows, minRows,
        maxRows, select, SelectProps, InputProps = {}, inputProps = {}, InputLabelProps = {}, slotProps, helperText, error, disabled, required,
        autoFocus, name, id, onKeyDown, onKeyUp, onFocus, onBlur, inputRef, autoComplete, sx, className, children, margin: _m, color: _c, ...rest
    } = props;
    const [focused, setFocused] = useState(false);
    const [inner, setInner] = useState(defaultValue ?? '');
    const htmlValue = (slotProps?.htmlInput || inputProps).value ?? InputProps.inputProps?.value;
    const controlled = value !== undefined || htmlValue !== undefined;
    const val = value !== undefined ? value : htmlValue !== undefined ? htmlValue : inner;
    const filled = val !== null && val !== undefined && String(val) !== '';
    const inputEl = useRef(null);
    const htmlInput = { ...inputProps, ...(slotProps?.htmlInput || {}) };
    const ip = { ...InputProps, ...(slotProps?.input || {}) };
    const lp = { ...InputLabelProps, ...(slotProps?.inputLabel || {}) };
    const {
        startAdornment, endAdornment, sx: inputSx, className: inputClassName, disableUnderline, ref: inputRootRef,
        readOnly, inputProps: nestedInputProps, ...inputRootRest
    } = ip;
    const shrink = lp.shrink ?? (focused || filled || !!startAdornment || DATE_TYPES.has(type) || (select && !!SelectProps?.displayEmpty));
    const { ref: htmlRef, className: htmlClassName, onChange: htmlOnChange, onFocus: htmlOnFocus, onBlur: htmlOnBlur, onKeyDown: htmlOnKeyDown, value: _hv, ...htmlRest } =
        { ...nestedInputProps, ...htmlInput };
    const forkInput = useForkRef(inputEl, inputRef, htmlRef);

    // textarea auto-grow between minRows and maxRows
    useLayoutEffect(() => {
        const el = inputEl.current;
        if (!multiline || rows || !el) return;
        el.style.height = 'auto';
        const line = parseFloat(getComputedStyle(el).lineHeight) || 20;
        const max = maxRows ? line * maxRows : Infinity;
        const min = line * (minRows || 1);
        el.style.height = `${Math.max(min, Math.min(el.scrollHeight, max))}px`;
        el.style.overflowY = el.scrollHeight > max ? 'auto' : 'hidden';
    }, [val, multiline, rows, minRows, maxRows]);

    const common = {
        id, name, disabled, required, autoFocus, placeholder, readOnly, autoComplete,
        'aria-invalid': error || undefined,
        onFocus: (e) => { setFocused(true); htmlOnFocus?.(e); onFocus?.(e); },
        onBlur: (e) => { setFocused(false); htmlOnBlur?.(e); onBlur?.(e); },
        onKeyDown: (e) => { htmlOnKeyDown?.(e); onKeyDown?.(e); },
        onKeyUp,
        onChange: (e) => { if (!controlled) setInner(e.target.value); htmlOnChange?.(e); onChange?.(e); },
    };
    const rootProps = {
        variant, size, fullWidth, multiline, focused, error, disabled, label: label ? <>{label}{required ? ' *' : ''}</> : null, notched: shrink,
        startAdornment, endAdornment, disableUnderline, hidePlaceholder: !!label && !shrink,
        className: inputClassName, sx: inputSx, ...inputRootRest,
    };
    const inputCls = cx('MuiInputBase-input', variant === 'standard' ? 'MuiInput-input' : 'MuiOutlinedInput-input', htmlClassName);

    let control;
    if (select) {
        control = (
            <SelectField ref={inputRootRef} value={val} onChange={common.onChange} name={name} id={id} disabled={disabled}
                onFocus={() => setFocused(true)} onBlur={() => setFocused(false)} inputRootProps={rootProps} {...SelectProps}>
                {children}
            </SelectField>
        );
    } else {
        control = (
            <InputRoot ref={inputRootRef} {...rootProps} onClick={(e) => { inputRootRest.onClick?.(e); if (e.target === e.currentTarget) inputEl.current?.focus(); }}>
                {multiline
                    ? <textarea ref={forkInput} rows={rows || minRows || 1} className={inputCls} {...common} {...htmlRest} value={controlled ? (val ?? '') : undefined} defaultValue={controlled ? undefined : defaultValue} />
                    : <input ref={forkInput} type={type} className={inputCls} {...common} {...htmlRest} value={controlled ? (val ?? '') : undefined} defaultValue={controlled ? undefined : defaultValue} />}
            </InputRoot>
        );
    }
    return (
        <div ref={ref} className={cx('MuiFormControl-root MuiTextField-root', fullWidth && 'MuiFormControl-fullWidth', className, sxClass(sx))} {...rest}>
            {label && (
                <InputLabel htmlFor={id} variant={variant === 'standard' ? 'standard' : 'outlined'} size={size} shrink={!!shrink} focused={focused}
                    error={error} disabled={disabled} required={required} {...lp}>{label}</InputLabel>
            )}
            {control}
            {helperText && <p className={cx('MuiFormHelperText-root', error && 'Mui-error')}>{helperText}</p>}
        </div>
    );
});

// ── portals: popover / menu / dialog / drawer / tooltip / snackbar ──────────

const Portal = ({ children }) => (typeof document === 'undefined' ? null : createPortal(children, document.body));

const useEscape = (active, onEscape) => {
    const cb = useRef(onEscape);
    cb.current = onEscape;
    useEffect(() => {
        if (!active) return undefined;
        const h = (e) => { if (e.key === 'Escape') { e.stopPropagation(); cb.current?.(e, 'escapeKeyDown'); } };
        document.addEventListener('keydown', h);
        return () => document.removeEventListener('keydown', h);
    }, [active]);
};

const useScrollLock = (active) => {
    useEffect(() => {
        if (!active) return undefined;
        const prev = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
        return () => { document.body.style.overflow = prev; };
    }, [active]);
};

const originOffset = (rect, o) => ({
    x: o.horizontal === 'center' ? rect.width / 2 : o.horizontal === 'right' ? rect.width : typeof o.horizontal === 'number' ? o.horizontal : 0,
    y: o.vertical === 'center' ? rect.height / 2 : o.vertical === 'bottom' ? rect.height : typeof o.vertical === 'number' ? o.vertical : 0,
});

export const Popover = forwardRef(function Popover(
    { open, anchorEl, onClose, anchorOrigin = { vertical: 'top', horizontal: 'left' }, transformOrigin = { vertical: 'top', horizontal: 'left' },
        anchorReference = 'anchorEl', anchorPosition, PaperProps = {}, slotProps, elevation = 8, matchAnchorWidth, marginThreshold = 16,
        keepMounted: _km, disableScrollLock: _dsl, sx, className, children, ...rest }, ref,
) {
    const paperRef = useRef(null);
    const [pos, setPos] = useState(null);
    const paperProps = { ...PaperProps, ...(slotProps?.paper || {}) };
    const { sx: paperSx, className: paperClassName, style: paperStyle, ...paperRest } = paperProps;
    useEscape(open, onClose);

    const place = useCallback(() => {
        const paper = paperRef.current;
        if (!paper) return;
        const el = typeof anchorEl === 'function' ? anchorEl() : anchorEl;
        let ax = 0; let ay = 0; let aw = 0;
        if (anchorReference === 'anchorPosition' && anchorPosition) { ax = anchorPosition.left; ay = anchorPosition.top; }
        else if (el && el.getBoundingClientRect) {
            const r = el.getBoundingClientRect();
            const off = originOffset(r, anchorOrigin);
            ax = r.left + off.x; ay = r.top + off.y; aw = r.width;
        }
        if (matchAnchorWidth && aw) paper.style.minWidth = `${aw}px`;
        const pr = { width: paper.offsetWidth, height: paper.offsetHeight }; // layout size: unaffected by the enter animation's scale
        const t = originOffset(pr, transformOrigin);
        let left = ax - t.x; let top = ay - t.y;
        left = Math.max(marginThreshold, Math.min(left, window.innerWidth - pr.width - marginThreshold));
        top = Math.max(marginThreshold, Math.min(top, window.innerHeight - pr.height - marginThreshold));
        setPos({ left, top });
    }, [anchorEl, anchorReference, anchorPosition, anchorOrigin.vertical, anchorOrigin.horizontal, transformOrigin.vertical, transformOrigin.horizontal, matchAnchorWidth, marginThreshold]); // eslint-disable-line react-hooks/exhaustive-deps

    useLayoutEffect(() => {
        if (!open) { setPos(null); return undefined; }
        place();
        window.addEventListener('resize', place);
        return () => window.removeEventListener('resize', place);
    }, [open, place]);

    if (!open) return null;
    return (
        <Portal>
            <div ref={ref} role="presentation" className={cx('MuiPopover-root MuiModal-root', className, sxClass(sx))} {...rest}>
                <div className="MuiPopover-backdrop MuiBackdrop-invisible" onMouseDown={(e) => onClose?.(e, 'backdropClick')} />
                <Paper ref={paperRef} elevation={elevation} tabIndex={-1} sx={paperSx}
                    className={cx('MuiPopover-paper', paperClassName)}
                    style={{ ...paperStyle, ...(pos || { visibility: 'hidden', left: 0, top: 0 }) }} {...paperRest}>
                    {children}
                </Paper>
            </div>
        </Portal>
    );
});

export const Menu = forwardRef(function Menu(
    { children, MenuListProps = {}, PaperProps = {}, slotProps, className, anchorOrigin = { vertical: 'bottom', horizontal: 'left' },
        transformOrigin = { vertical: 'top', horizontal: 'left' }, autoFocus: _af, variant: _v, ...rest }, ref,
) {
    const { className: listCls, sx: listSx, ...listRest } = { ...MenuListProps, ...(slotProps?.list || {}) };
    const paper = { ...PaperProps, ...(slotProps?.paper || {}) };
    const listRef = useRef(null);
    // Arrow-key navigation across the items.
    const onKeyDown = (e) => {
        if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
        e.preventDefault();
        const items = Array.from(listRef.current?.querySelectorAll('[role="menuitem"]:not([aria-disabled]),[role="option"]:not([aria-disabled])') || []);
        if (!items.length) return;
        const i = items.indexOf(document.activeElement);
        items[(i + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length].focus();
    };
    useEffect(() => {
        if (!rest.open) return;
        const t = setTimeout(() => {
            const root = listRef.current;
            (root?.querySelector('.Mui-selected') || root?.querySelector('[role="menuitem"],[role="option"]'))?.focus?.({ preventScroll: true });
        }, 0);
        return () => clearTimeout(t);
    }, [rest.open]);
    return (
        <Popover ref={ref} className={cx('MuiMenu-root', className)} anchorOrigin={anchorOrigin} transformOrigin={transformOrigin}
            PaperProps={{ ...paper, className: cx('MuiMenu-paper', paper.className) }} {...rest}>
            <ul ref={listRef} role={listRest.role || 'menu'} className={cx('MuiList-root MuiMenu-list', listCls, sxClass(listSx))} onKeyDown={onKeyDown} {...listRest}>
                {children}
            </ul>
        </Popover>
    );
});

export const Dialog = forwardRef(function Dialog(
    { open, onClose, maxWidth = 'sm', fullWidth, fullScreen, PaperProps = {}, slotProps, scroll: _s, keepMounted: _km, sx, className, children,
        'aria-labelledby': labelledBy, ...rest }, ref,
) {
    useEscape(open, onClose);
    useScrollLock(open);
    if (!open) return null;
    const { sx: paperSx, className: paperClassName, ...paperRest } = { ...PaperProps, ...(slotProps?.paper || {}) };
    return (
        <Portal>
            <div ref={ref} role="presentation" className={cx('MuiDialog-root MuiModal-root', className, sxClass(sx))} {...rest}>
                <div className="MuiBackdrop-root" aria-hidden />
                <div className="MuiDialog-container" role="presentation" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose?.(e, 'backdropClick'); }}>
                    <Paper elevation={24} role="dialog" aria-modal="true" aria-labelledby={labelledBy} sx={paperSx}
                        className={cx('MuiDialog-paper', maxWidth && `MuiDialog-paperWidth${cap(maxWidth)}`, fullWidth && 'MuiDialog-paperFullWidth',
                            fullScreen && 'MuiDialog-paperFullScreen', paperClassName)}
                        {...paperRest}>
                        {children}
                    </Paper>
                </div>
            </div>
        </Portal>
    );
});

export const DialogTitle = forwardRef(function DialogTitle({ component: C = 'h2', sx, className, ...rest }, ref) {
    return <C ref={ref} className={cx('MuiTypography-root MuiDialogTitle-root', className, sxClass(sx))} {...rest} />;
});
export const DialogContent = forwardRef(function DialogContent({ dividers: _d, sx, className, ...rest }, ref) {
    return <div ref={ref} className={cx('MuiDialogContent-root', className, sxClass(sx))} {...rest} />;
});
export const DialogActions = forwardRef(function DialogActions({ sx, className, ...rest }, ref) {
    return <div ref={ref} className={cx('MuiDialogActions-root', className, sxClass(sx))} {...rest} />;
});

export const Drawer = forwardRef(function Drawer(
    { variant = 'temporary', anchor = 'left', open, onClose, PaperProps = {}, slotProps, ModalProps: _mp, elevation = 16, sx, className, children, ...rest }, ref,
) {
    const modal = variant === 'temporary';
    useEscape(modal && !!open, onClose);
    useScrollLock(modal && !!open);
    const { sx: paperSx, className: paperClassName, ...paperRest } = { ...PaperProps, ...(slotProps?.paper || {}) };
    const paper = (
        <Paper square elevation={modal ? elevation : 0} sx={paperSx}
            className={cx('MuiDrawer-paper', `MuiDrawer-paperAnchor${cap(anchor)}`, !modal && `MuiDrawer-paperAnchorDocked${cap(anchor)}`, paperClassName)}
            {...paperRest}>
            {children}
        </Paper>
    );
    if (!modal) {
        if (variant === 'persistent' && !open) return null;
        return <div ref={ref} className={cx('MuiDrawer-root MuiDrawer-docked', className, sxClass(sx))} {...rest}>{paper}</div>;
    }
    if (!open) return null;
    return (
        <Portal>
            <div ref={ref} role="presentation" className={cx('MuiDrawer-root MuiDrawer-modal MuiModal-root', className, sxClass(sx))} {...rest}>
                <div className="MuiBackdrop-root" aria-hidden onMouseDown={(e) => onClose?.(e, 'backdropClick')} />
                {paper}
            </div>
        </Portal>
    );
});

export function Tooltip({ title, children, placement = 'bottom', arrow, enterDelay = 100 }) {
    const [anchor, setAnchor] = useState(null);
    const timer = useRef(null);
    useEffect(() => () => clearTimeout(timer.current), []);
    const child = isValidElement(children) ? children : <span>{children}</span>;
    const hasTitle = title !== undefined && title !== null && title !== '' && title !== false;

    const show = (e) => {
        const el = e.currentTarget;
        clearTimeout(timer.current);
        timer.current = setTimeout(() => setAnchor(el.getBoundingClientRect()), enterDelay);
    };
    const hide = () => { clearTimeout(timer.current); setAnchor(null); };
    const merge = (name, fn) => (e) => { child.props[name]?.(e); fn(e); };

    const trigger = cloneElement(child, {
        'aria-label': child.props['aria-label'] ?? (typeof title === 'string' && hasTitle ? title : undefined),
        ...(hasTitle ? {
            onMouseEnter: merge('onMouseEnter', show), onMouseLeave: merge('onMouseLeave', hide),
            onFocus: merge('onFocus', show), onBlur: merge('onBlur', hide),
        } : {}),
    });

    let tip = null;
    if (anchor && hasTitle) {
        const GAP = 14;
        const side = placement.split('-')[0];
        const style = side === 'top' ? { left: anchor.left + anchor.width / 2, top: anchor.top - GAP, transform: 'translate(-50%, -100%)' }
            : side === 'left' ? { left: anchor.left - GAP, top: anchor.top + anchor.height / 2, transform: 'translate(-100%, -50%)' }
                : side === 'right' ? { left: anchor.right + GAP, top: anchor.top + anchor.height / 2, transform: 'translate(0, -50%)' }
                    : { left: anchor.left + anchor.width / 2, top: anchor.bottom + GAP, transform: 'translate(-50%, 0)' };
        const arrowStyle = side === 'top' ? { bottom: -4, left: 'calc(50% - 4px)' }
            : side === 'left' ? { right: -4, top: 'calc(50% - 4px)' }
                : side === 'right' ? { left: -4, top: 'calc(50% - 4px)' }
                    : { top: -4, left: 'calc(50% - 4px)' };
        tip = (
            <Portal>
                <div role="tooltip" className="MuiTooltip-popper" style={style}>
                    <div className={cx('MuiTooltip-tooltip', arrow && 'MuiTooltip-tooltipArrow', `MuiTooltip-tooltipPlacement${cap(side)}`)}>
                        {title}
                        {arrow && <span className="MuiTooltip-arrow" style={arrowStyle} />}
                    </div>
                </div>
            </Portal>
        );
    }
    return <>{trigger}{tip}</>;
}

export function Snackbar({ open, autoHideDuration, onClose, anchorOrigin = { vertical: 'bottom', horizontal: 'left' }, message, action, sx, className, children }) {
    const cb = useRef(onClose);
    cb.current = onClose;
    useEffect(() => {
        if (!open || !autoHideDuration) return undefined;
        const t = setTimeout(() => cb.current?.(null, 'timeout'), autoHideDuration);
        return () => clearTimeout(t);
    }, [open, autoHideDuration]);
    if (!open) return null;
    return (
        <Portal>
            <div role="presentation"
                className={cx('MuiSnackbar-root', `MuiSnackbar-anchorOrigin${cap(anchorOrigin.vertical)}`, `MuiSnackbar-anchorOrigin${cap(anchorOrigin.horizontal)}`, className, sxClass(sx))}>
                {children || <Paper elevation={6} sx={{ px: 2, py: 0.75, display: 'flex', alignItems: 'center', gap: 2, bgcolor: 'text.primary', color: 'background.paper' }}>{message}{action}</Paper>}
            </div>
        </Portal>
    );
}

// ── feedback ────────────────────────────────────────────────────────────────

export const Alert = forwardRef(function Alert(
    { severity = 'success', variant = 'standard', icon, action, onClose, color, sx, className, children, ...rest }, ref,
) {
    const tone = color || severity;
    return (
        <Paper ref={ref} role="alert" elevation={0}
            className={cx('MuiAlert-root', `MuiAlert-color${cap(tone)}`, `MuiAlert-${variant}`, className, sxClass(sx))}
            {...rest}>
            {icon !== false && <div className="MuiAlert-icon">{icon || <SvgIcon d={ICON[severity] || ICON.info} style={{ fontSize: 'inherit' }} />}</div>}
            <div className="MuiAlert-message">{children}</div>
            {(action || onClose) && (
                <div className="MuiAlert-action">
                    {action || <IconButton size="small" aria-label="Close" color="inherit" onClick={onClose}><SvgIcon d={ICON.close} style={{ fontSize: 20 }} /></IconButton>}
                </div>
            )}
        </Paper>
    );
});

export const CircularProgress = forwardRef(function CircularProgress({ size = 40, thickness = 3.6, color = 'primary', sx, className, style, ...rest }, ref) {
    return (
        <span ref={ref} role="progressbar"
            className={cx('MuiCircularProgress-root MuiCircularProgress-indeterminate', `MuiCircularProgress-color${cap(color)}`, className, sxClass(sx))}
            style={{ width: size, height: size, ...style }} {...rest}>
            <svg className="MuiCircularProgress-svg" viewBox="22 22 44 44">
                <circle className="MuiCircularProgress-circle" cx="44" cy="44" r={(44 - thickness) / 2} fill="none" strokeWidth={thickness} />
            </svg>
        </span>
    );
});

export const LinearProgress = forwardRef(function LinearProgress({ variant = 'indeterminate', value = 0, sx, className, ...rest }, ref) {
    const determinate = variant === 'determinate';
    return (
        <span ref={ref} role="progressbar" aria-valuenow={determinate ? Math.round(value) : undefined} aria-valuemin={0} aria-valuemax={100}
            className={cx('MuiLinearProgress-root', `MuiLinearProgress-${variant}`, className, sxClass(sx))} {...rest}>
            <span className="MuiLinearProgress-bar MuiLinearProgress-bar1"
                style={determinate ? { transform: `translateX(${Math.max(0, Math.min(100, value)) - 100}%)` } : undefined} />
        </span>
    );
});

export const Avatar = forwardRef(function Avatar({ variant = 'circular', src, alt, component: C = 'div', sx, className, children, ...rest }, ref) {
    return (
        <C ref={ref} className={cx('MuiAvatar-root', `MuiAvatar-${variant}`, className, sxClass(sx))} {...rest}>
            {src ? <img src={src} alt={alt} className="MuiAvatar-img" /> : children}
        </C>
    );
});

// ── selection controls ──────────────────────────────────────────────────────

export const Checkbox = forwardRef(function Checkbox(
    { checked, defaultChecked, indeterminate, onChange, disabled, readOnly, size = 'medium', color: _color, name, value, id, inputProps, sx, className, ...rest }, ref,
) {
    const [inner, setInner] = useState(!!defaultChecked);
    const isChecked = checked ?? inner;
    return (
        <span ref={ref}
            className={cx('MuiButtonBase-root MuiCheckbox-root', `MuiCheckbox-size${cap(size)}`, isChecked && 'Mui-checked',
                indeterminate && 'MuiCheckbox-indeterminate', disabled && 'Mui-disabled', className, sxClass(sx))}
            {...rest}>
            <input type="checkbox" id={id} name={name} value={value} checked={isChecked} disabled={disabled} readOnly={readOnly}
                onChange={(e) => { if (readOnly) return; if (checked === undefined) setInner(e.target.checked); onChange?.(e, e.target.checked); }}
                {...inputProps} />
            <SvgIcon d={indeterminate ? ICON.boxMixed : isChecked ? ICON.boxOn : ICON.boxOff} />
        </span>
    );
});

export const Switch = forwardRef(function Switch(
    { checked, defaultChecked, onChange, disabled, size = 'medium', color = 'primary', name, id, inputProps, sx, className, ...rest }, ref,
) {
    const [inner, setInner] = useState(!!defaultChecked);
    const isChecked = checked ?? inner;
    return (
        <span ref={ref} className={cx('MuiSwitch-root', `MuiSwitch-size${cap(size)}`, `MuiSwitch-color${cap(color)}`, className, sxClass(sx))} {...rest}>
            <span className={cx('MuiButtonBase-root MuiSwitch-switchBase', isChecked && 'Mui-checked', disabled && 'Mui-disabled')}>
                <input type="checkbox" role="switch" className="MuiSwitch-input" id={id} name={name} checked={isChecked} disabled={disabled}
                    onChange={(e) => { if (checked === undefined) setInner(e.target.checked); onChange?.(e, e.target.checked); }} {...inputProps} />
                <span className="MuiSwitch-thumb" />
            </span>
            <span className="MuiSwitch-track" />
        </span>
    );
});

export const FormControlLabel = forwardRef(function FormControlLabel({ control, label, disabled, labelPlacement: _lp, sx, className, ...rest }, ref) {
    const isDisabled = disabled ?? control?.props?.disabled;
    return (
        <label ref={ref} className={cx('MuiFormControlLabel-root', isDisabled && 'Mui-disabled', className, sxClass(sx))} {...rest}>
            {isValidElement(control) ? cloneElement(control, { disabled: isDisabled }) : control}
            {typeof label === 'string' || typeof label === 'number'
                ? <Typography component="span" className={cx('MuiFormControlLabel-label', isDisabled && 'Mui-disabled')}>{label}</Typography>
                : label}
        </label>
    );
});

// ── transitions ─────────────────────────────────────────────────────────────

const ms = (timeout, fallback) => (typeof timeout === 'number' ? timeout : typeof timeout === 'object' && timeout ? (timeout.enter ?? fallback) : fallback);

export const Collapse = forwardRef(function Collapse(
    { in: inProp, timeout = 300, orientation = 'vertical', unmountOnExit, mountOnEnter, collapsedSize = 0, component: C = 'div', sx, className, style, children, ...rest }, ref,
) {
    const duration = ms(timeout, 300);
    const dim = orientation === 'horizontal' ? 'width' : 'height';
    const wrapper = useRef(null);
    const first = useRef(true);
    // entered | exited rest states; entering | exiting carry an explicit pixel size to animate.
    const [phase, setPhase] = useState(inProp ? 'entered' : 'exited');
    const [size, setSize] = useState(null);
    const [mounted, setMounted] = useState(!!inProp || !(unmountOnExit || mountOnEnter));

    useLayoutEffect(() => {
        if (first.current) { first.current = false; return undefined; }
        const measure = () => (wrapper.current ? (dim === 'width' ? wrapper.current.offsetWidth : wrapper.current.offsetHeight) : 0);
        let raf; let timer;
        if (inProp) {
            setMounted(true);
            setPhase('entering');
            setSize(collapsedSize);
            raf = setTimeout(() => { setSize(measure()); }, 16);
            timer = setTimeout(() => { setPhase('entered'); setSize(null); }, duration + 46);
        } else {
            setPhase('exiting');
            setSize(measure());
            raf = setTimeout(() => { setSize(collapsedSize); }, 16);
            timer = setTimeout(() => { setPhase('exited'); setSize(null); if (unmountOnExit) setMounted(false); }, duration + 46);
        }
        return () => { clearTimeout(raf); clearTimeout(timer); };
    }, [inProp]); // eslint-disable-line react-hooks/exhaustive-deps

    if (!mounted) return null;
    const moving = phase === 'entering' || phase === 'exiting';
    const inline = moving
        ? { [dim]: size ?? collapsedSize, overflow: 'hidden', transition: `${dim} ${duration}ms cubic-bezier(0.4, 0, 0.2, 1)` }
        : phase === 'exited' && collapsedSize ? { [dim]: collapsedSize } : null;
    return (
        <C ref={ref}
            className={cx('MuiCollapse-root', `MuiCollapse-${orientation}`, phase === 'entered' && 'MuiCollapse-entered',
                phase === 'exited' && !collapsedSize && 'MuiCollapse-hidden', className, sxClass(sx))}
            style={{ minHeight: dim === 'height' && collapsedSize ? collapsedSize : undefined, ...inline, ...style }} {...rest}>
            <div ref={wrapper} className="MuiCollapse-wrapper"><div className="MuiCollapse-wrapperInner">{children}</div></div>
        </C>
    );
});

export function Fade({ in: inProp, timeout = 225, style, unmountOnExit, children }) {
    const duration = ms(timeout, 225);
    const [shown, setShown] = useState(false);
    useEffect(() => {
        if (!inProp) { setShown(false); return undefined; }
        // A timer, not an animation frame: frames do not fire in a background tab, which left content invisible.
        const id = setTimeout(() => setShown(true), 16);
        return () => clearTimeout(id);
    }, [inProp]);
    if (!inProp && unmountOnExit) return null;
    if (!isValidElement(children)) return children ?? null;
    return cloneElement(children, {
        style: { opacity: shown ? 1 : 0, transition: `opacity ${duration}ms cubic-bezier(0.4, 0, 0.2, 1)`, visibility: !inProp && !shown ? 'hidden' : undefined, ...style, ...children.props.style },
    });
}

// ── navigation ──────────────────────────────────────────────────────────────

const TabsContext = createContext(null);

export const Tabs = forwardRef(function Tabs(
    { value, onChange, variant: _variant, scrollButtons: _sb, allowScrollButtonsMobile: _a, centered, TabIndicatorProps, sx, className, children, ...rest }, ref,
) {
    const listRef = useRef(null);
    const [indicator, setIndicator] = useState({ left: 0, width: 0 });
    const measure = useCallback(() => {
        const el = listRef.current?.querySelector('.Mui-selected');
        const next = el ? { left: el.offsetLeft, width: el.offsetWidth } : { left: 0, width: 0 };
        // Runs after every render: only commit a real change, or it loops.
        setIndicator((prev) => (prev.left === next.left && prev.width === next.width ? prev : next));
    }, []);
    useLayoutEffect(() => { measure(); });
    useEffect(() => {
        window.addEventListener('resize', measure);
        return () => window.removeEventListener('resize', measure);
    }, [measure]);
    let index = 0;
    const kids = Children.map(children, (child) => {
        if (!isValidElement(child)) return child;
        const v = child.props.value === undefined ? index : child.props.value;
        index += 1;
        return cloneElement(child, { __value: v });
    });
    const ctx = useMemo(() => ({ value, onChange }), [value, onChange]);
    return (
        <TabsContext.Provider value={ctx}>
            <div ref={ref} className={cx('MuiTabs-root', className, sxClass(sx))} {...rest}>
                <div className="MuiTabs-scroller MuiTabs-hideScrollbar MuiTabs-scrollableX">
                    <div ref={listRef} role="tablist" className={cx('MuiTabs-list MuiTabs-flexContainer', centered && 'MuiTabs-centered')}
                        style={centered ? { justifyContent: 'center' } : undefined}>
                        {kids}
                    </div>
                    <span className={cx('MuiTabs-indicator', TabIndicatorProps?.className)} style={{ ...indicator, ...TabIndicatorProps?.style }} />
                </div>
            </div>
        </TabsContext.Provider>
    );
});

export const Tab = forwardRef(function Tab(
    { label, icon, iconPosition = 'top', value: _value, __value, disabled, onClick, wrapped: _w, sx, className, ...rest }, ref,
) {
    const tabs = useContext(TabsContext);
    const selected = tabs ? tabs.value === __value : false;
    const iconEl = isValidElement(icon) ? cloneElement(icon, { className: cx(icon.props.className, 'MuiTab-icon') }) : icon;
    return (
        <button ref={ref} type="button" role="tab" aria-selected={selected} disabled={disabled} tabIndex={selected ? 0 : -1}
            onClick={(e) => { onClick?.(e); if (!selected) tabs?.onChange?.(e, __value); }}
            className={cx('MuiButtonBase-root MuiTab-root', icon && label && 'MuiTab-labelIcon', icon && `MuiTab-icon${cap(iconPosition)}`,
                selected && 'Mui-selected', disabled && 'Mui-disabled', className, sxClass(sx))}
            {...rest}>
            {(iconPosition === 'top' || iconPosition === 'start') && iconEl}
            {label}
            {(iconPosition === 'bottom' || iconPosition === 'end') && iconEl}
        </button>
    );
});

export const Breadcrumbs = forwardRef(function Breadcrumbs({ separator = '/', sx, className, children, ...rest }, ref) {
    const items = Children.toArray(children).filter(isValidElement);
    return (
        <nav ref={ref} className={cx('MuiTypography-root MuiTypography-body1 MuiBreadcrumbs-root', className, sxClass(sx))} {...rest}>
            <ol className="MuiBreadcrumbs-ol">
                {items.map((c, i) => (
                    <React.Fragment key={c.key ?? i}>
                        {i > 0 && <li aria-hidden className="MuiBreadcrumbs-separator">{separator}</li>}
                        <li className="MuiBreadcrumbs-li">{c}</li>
                    </React.Fragment>
                ))}
            </ol>
        </nav>
    );
});

export const Link = forwardRef(function Link({ component: C = 'a', underline = 'always', color, variant = 'inherit', sx, className, ...rest }, ref) {
    return (
        <C ref={ref}
            className={cx('MuiTypography-root', `MuiTypography-${variant}`, 'MuiLink-root', `MuiLink-underline${cap(underline)}`, className,
                sxClass([color ? { color } : null, sx]))}
            {...rest} />
    );
});

// ── table ───────────────────────────────────────────────────────────────────

const el = (Tag, cls) => forwardRef(function TableEl({ component, sx, className, size: _size, stickyHeader: _sh, hover: _h, align, padding: _p, ...rest }, ref) {
    const C = component || Tag;
    return <C ref={ref} className={cx(cls, className, sxClass([align ? { textAlign: align } : null, sx]))} {...rest} />;
});
export const Table = el('table', 'MuiTable-root');
export const TableHead = el('thead', 'MuiTableHead-root');
export const TableBody = el('tbody', 'MuiTableBody-root');
export const TableRow = el('tr', 'MuiTableRow-root');
export const TableCell = el('td', 'MuiTableCell-root');
export const TableContainer = el('div', 'MuiTableContainer-root');

// ── hooks ───────────────────────────────────────────────────────────────────

export function useMediaQuery(query) {
    const q = typeof query === 'function' ? query(null) : String(query).replace(/^@media\s*/i, '');
    const get = () => (typeof window !== 'undefined' && window.matchMedia ? window.matchMedia(q).matches : false);
    const [matches, setMatches] = useState(get);
    useEffect(() => {
        if (typeof window === 'undefined' || !window.matchMedia) return undefined;
        const mql = window.matchMedia(q);
        const h = () => setMatches(mql.matches);
        h();
        mql.addEventListener('change', h);
        return () => mql.removeEventListener('change', h);
    }, [q]);
    return matches;
}


// ── badge ───────────────────────────────────────────────────────────────────

export const Badge = forwardRef(function Badge({ badgeContent, color = 'default', invisible, max = 99, showZero, sx, className, children, ...rest }, ref) {
    const hidden = invisible || badgeContent === undefined || badgeContent === null || (badgeContent === 0 && !showZero);
    const shown = typeof badgeContent === 'number' && badgeContent > max ? `${max}+` : badgeContent;
    return (
        <span ref={ref} className={cx('MuiBadge-root', className, sxClass([{ position: 'relative', display: 'inline-flex', verticalAlign: 'middle', flexShrink: 0 }, sx]))} {...rest}>
            {children}
            {!hidden && (
                <span className={cx('MuiBadge-badge', `MuiBadge-color${cap(color)}`)}
                    style={{
                        position: 'absolute', top: 0, right: 0, transform: 'translate(50%, -50%)', minWidth: 20, height: 20, padding: '0 6px', borderRadius: 10,
                        display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 12, fontWeight: 500, lineHeight: 1,
                        backgroundColor: color === 'default' ? 'transparent' : `var(--sys-${color})`, color: color === 'default' ? 'inherit' : `var(--sys-${color}-contrast)`,
                    }}>
                    {shown}
                </span>
            )}
        </span>
    );
});