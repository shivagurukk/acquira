// Multi/single select with type-ahead, free text and custom tag/option
// rendering. Implements the props the app's filter bars and the merchant
// comparison picker use; the render-prop contracts (renderInput params,
// getTagProps, renderOption props) match what those callers spread.
import React, { forwardRef, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Chip, IconButton, Paper } from './core';
import { sxClass, cx } from './sx';

const Svg = ({ d, size = 20 }) => (
    <svg className="MuiSvgIcon-root" focusable="false" aria-hidden="true" viewBox="0 0 24 24" style={{ fontSize: size }}><path d={d} /></svg>
);
const D_CLOSE = 'M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z';
const D_ARROW = 'M7 10l5 5 5-5z';

const defaultLabel = (o) => (o == null ? '' : typeof o === 'string' ? o : (o.label ?? String(o)));

const Autocomplete = forwardRef(function Autocomplete(
    {
        multiple, freeSolo, options = [], value, onChange, renderInput, renderTags, renderOption,
        getOptionLabel = defaultLabel, isOptionEqualToValue = (a, b) => a === b, filterSelectedOptions, filterOptions,
        loading, loadingText = 'Loading…', noOptionsText = 'No options', onInputChange, inputValue: inputValueProp,
        size = 'medium', disabled, fullWidth = false, PaperComponent = Paper, disableClearable, id, sx, className, ...rest
    }, ref,
) {
    const [inputState, setInputState] = useState('');
    const inputValue = inputValueProp ?? inputState;
    const [open, setOpen] = useState(false);
    const [focused, setFocused] = useState(false);
    const [active, setActive] = useState(-1);
    const [box, setBox] = useState(null);
    const anchorRef = useRef(null);
    const inputRef = useRef(null);

    const selected = multiple ? (Array.isArray(value) ? value : []) : (value == null ? [] : [value]);
    const isSelected = (o) => selected.some((v) => isOptionEqualToValue(o, v));

    const setInput = (e, v, reason) => {
        if (inputValueProp === undefined) setInputState(v);
        onInputChange?.(e, v, reason);
    };

    let shown = filterSelectedOptions ? options.filter((o) => !isSelected(o)) : options;
    if (filterOptions) shown = filterOptions(shown, { inputValue, getOptionLabel });
    else if (inputValue) {
        const q = inputValue.trim().toLowerCase();
        shown = shown.filter((o) => getOptionLabel(o).toLowerCase().includes(q));
    }

    const commit = (e, next, reason, detail) => onChange?.(e, next, reason, detail);
    const choose = (e, option) => {
        if (multiple) {
            if (isSelected(option)) commit(e, selected.filter((v) => !isOptionEqualToValue(option, v)), 'removeOption', { option });
            else commit(e, [...selected, option], 'selectOption', { option });
            setInput(e, '', 'reset');
        } else {
            commit(e, option, 'selectOption', { option });
            setInput(e, getOptionLabel(option), 'reset');
            setOpen(false);
        }
        setActive(-1);
    };
    const removeAt = (e, index) => commit(e, selected.filter((_, i) => i !== index), 'removeOption', { option: selected[index] });

    const place = useCallback(() => {
        const r = anchorRef.current?.getBoundingClientRect();
        if (r) setBox({ left: r.left, top: r.bottom, width: r.width });
    }, []);
    useLayoutEffect(() => {
        if (!open) return undefined;
        place();
        window.addEventListener('resize', place);
        window.addEventListener('scroll', place, true);
        return () => { window.removeEventListener('resize', place); window.removeEventListener('scroll', place, true); };
    }, [open, place, selected.length]);
    useEffect(() => { if (active >= shown.length) setActive(shown.length - 1); }, [shown.length, active]);

    const onKeyDown = (e) => {
        if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault();
            if (!open) { setOpen(true); return; }
            if (!shown.length) return;
            setActive((i) => (i + (e.key === 'ArrowDown' ? 1 : -1) + shown.length) % shown.length);
        } else if (e.key === 'Enter') {
            if (open && active >= 0 && shown[active] !== undefined) { e.preventDefault(); choose(e, shown[active]); }
            else if (freeSolo && inputValue.trim() !== '') {
                e.preventDefault();
                const text = inputValue.trim();
                if (multiple) { if (!selected.some((v) => getOptionLabel(v) === text)) commit(e, [...selected, text], 'createOption', { option: text }); }
                else commit(e, text, 'createOption', { option: text });
                setInput(e, '', 'reset');
            }
        } else if (e.key === 'Backspace' && multiple && inputValue === '' && selected.length) {
            removeAt(e, selected.length - 1);
        } else if (e.key === 'Escape' && open) {
            e.stopPropagation();
            setOpen(false);
        }
    };

    const getTagProps = ({ index }) => ({
        key: index, 'data-tag-index': index, tabIndex: -1, className: 'MuiAutocomplete-tag', disabled,
        onDelete: (e) => removeAt(e, index),
    });
    let tags;
    if (multiple && selected.length) {
        tags = renderTags
            ? renderTags(selected, getTagProps, { focused })
            : selected.map((o, index) => { const { key, ...p } = getTagProps({ index }); return <Chip key={key} label={getOptionLabel(o)} size={size} {...p} />; });
    }

    const hasValue = selected.length > 0 || inputValue !== '';
    const endAdornment = (
        <div className="MuiAutocomplete-endAdornment">
            {!disableClearable && hasValue && !disabled && (
                <IconButton tabIndex={-1} aria-label="Clear" title="Clear" className="MuiAutocomplete-clearIndicator"
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={(e) => { commit(e, multiple ? [] : null, 'clear'); setInput(e, '', 'clear'); inputRef.current?.focus(); }}>
                    <Svg d={D_CLOSE} size={20} />
                </IconButton>
            )}
            {!freeSolo && (
                <IconButton tabIndex={-1} aria-label={open ? 'Close' : 'Open'} disabled={disabled}
                    className={cx('MuiAutocomplete-popupIndicator', open && 'MuiAutocomplete-popupIndicatorOpen')}
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={() => { setOpen((o) => !o); inputRef.current?.focus(); }}>
                    <Svg d={D_ARROW} size={24} />
                </IconButton>
            )}
        </div>
    );

    const params = {
        id, disabled, fullWidth: true, size,
        InputLabelProps: {},
        InputProps: {
            ref: anchorRef,
            className: 'MuiAutocomplete-inputRoot',
            startAdornment: tags,
            endAdornment,
            onClick: () => { if (!disabled) { inputRef.current?.focus(); setOpen(true); } },
        },
        inputProps: {
            ref: inputRef,
            className: 'MuiAutocomplete-input',
            value: inputValue,
            disabled,
            autoComplete: 'off', autoCapitalize: 'none', spellCheck: 'false',
            role: 'combobox', 'aria-expanded': open, 'aria-autocomplete': 'list',
            onChange: (e) => { setInput(e, e.target.value, 'input'); setOpen(true); setActive(-1); },
            onFocus: () => { setFocused(true); setOpen(true); },
            onBlur: (e) => {
                setFocused(false); setOpen(false); setActive(-1);
                if (!freeSolo && multiple && inputValue !== '') setInput(e, '', 'blur');
            },
            onKeyDown,
        },
    };

    const showPopup = open && !disabled && (shown.length > 0 || loading || (!freeSolo && inputValue !== ''));
    const optionProps = (option, index) => ({
        key: index, role: 'option', tabIndex: -1, 'data-option-index': index, 'aria-selected': isSelected(option),
        className: cx('MuiAutocomplete-option', index === active && 'Mui-focused'),
        onMouseEnter: () => setActive(index),
        onMouseDown: (e) => e.preventDefault(), // keep focus in the input
        onClick: (e) => choose(e, option),
    });

    return (
        <div ref={ref}
            className={cx('MuiAutocomplete-root', fullWidth && 'MuiAutocomplete-fullWidth', focused && 'Mui-focused',
                !freeSolo && 'MuiAutocomplete-hasPopupIcon', !disableClearable && hasValue && !disabled && 'MuiAutocomplete-hasClearIcon', className, sxClass(sx))}
            {...rest}>
            {renderInput(params)}
            {showPopup && box && createPortal(
                <div className="MuiAutocomplete-popper" role="presentation" style={{ left: box.left, top: box.top, width: box.width }}>
                    <PaperComponent className="MuiPaper-root MuiPaper-elevation MuiPaper-elevation8 MuiPaper-rounded MuiAutocomplete-paper">
                        {loading && shown.length === 0 ? <div className="MuiAutocomplete-loading">{loadingText}</div>
                            : shown.length === 0 ? <div className="MuiAutocomplete-noOptions">{noOptionsText}</div>
                                : (
                                    <ul role="listbox" className="MuiAutocomplete-listbox">
                                        {shown.map((option, index) => {
                                            const p = optionProps(option, index);
                                            if (renderOption) return renderOption(p, option, { selected: p['aria-selected'], index, inputValue });
                                            const { key, ...liProps } = p;
                                            return <li key={key} {...liProps}>{getOptionLabel(option)}</li>;
                                        })}
                                    </ul>
                                )}
                    </PaperComponent>
                </div>,
                document.body,
            )}
        </div>
    );
});

export default Autocomplete;
