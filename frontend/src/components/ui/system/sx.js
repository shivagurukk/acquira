// Style engine for the in-house component layer that replaced MUI.
//
// Pages still describe one-off styling with an `sx` object (spacing shorthands,
// palette paths, responsive values, nested selectors). This turns such an
// object into a generated class and injects its rules into one <style> tag,
// so no styling library is needed. Semantics follow what the pages were
// written against: 8px spacing unit, 10px radius unit, 0/600/900/1200/1536
// breakpoints.

import baseCss from './system.css?inline';

const SPACING = 8;
const RADIUS = 10;
export const BREAKPOINTS = { xs: 0, sm: 600, md: 900, lg: 1200, xl: 1536 };
const BP_KEYS = Object.keys(BREAKPOINTS);

export const SHADOWS = ['none',
    '0px 2px 1px -1px rgba(0,0,0,0.2),0px 1px 1px 0px rgba(0,0,0,0.14),0px 1px 3px 0px rgba(0,0,0,0.12)',
    '0px 3px 1px -2px rgba(0,0,0,0.2),0px 2px 2px 0px rgba(0,0,0,0.14),0px 1px 5px 0px rgba(0,0,0,0.12)',
    '0px 3px 3px -2px rgba(0,0,0,0.2),0px 3px 4px 0px rgba(0,0,0,0.14),0px 1px 8px 0px rgba(0,0,0,0.12)',
    '0px 2px 4px -1px rgba(0,0,0,0.2),0px 4px 5px 0px rgba(0,0,0,0.14),0px 1px 10px 0px rgba(0,0,0,0.12)',
    '0px 3px 5px -1px rgba(0,0,0,0.2),0px 5px 8px 0px rgba(0,0,0,0.14),0px 1px 14px 0px rgba(0,0,0,0.12)',
    '0px 3px 5px -1px rgba(0,0,0,0.2),0px 6px 10px 0px rgba(0,0,0,0.14),0px 1px 18px 0px rgba(0,0,0,0.12)',
    '0px 4px 5px -2px rgba(0,0,0,0.2),0px 7px 10px 1px rgba(0,0,0,0.14),0px 2px 16px 1px rgba(0,0,0,0.12)',
    '0px 5px 5px -3px rgba(0,0,0,0.2),0px 8px 10px 1px rgba(0,0,0,0.14),0px 3px 14px 2px rgba(0,0,0,0.12)',
    '0px 5px 6px -3px rgba(0,0,0,0.2),0px 9px 12px 1px rgba(0,0,0,0.14),0px 3px 16px 2px rgba(0,0,0,0.12)',
    '0px 6px 6px -3px rgba(0,0,0,0.2),0px 10px 14px 1px rgba(0,0,0,0.14),0px 4px 18px 3px rgba(0,0,0,0.12)',
    '0px 6px 7px -4px rgba(0,0,0,0.2),0px 11px 15px 1px rgba(0,0,0,0.14),0px 4px 20px 3px rgba(0,0,0,0.12)',
    '0px 7px 8px -4px rgba(0,0,0,0.2),0px 12px 17px 2px rgba(0,0,0,0.14),0px 5px 22px 4px rgba(0,0,0,0.12)',
    '0px 7px 8px -4px rgba(0,0,0,0.2),0px 13px 19px 2px rgba(0,0,0,0.14),0px 5px 24px 4px rgba(0,0,0,0.12)',
    '0px 7px 9px -4px rgba(0,0,0,0.2),0px 14px 21px 2px rgba(0,0,0,0.14),0px 5px 26px 4px rgba(0,0,0,0.12)',
    '0px 8px 9px -5px rgba(0,0,0,0.2),0px 15px 22px 2px rgba(0,0,0,0.14),0px 6px 28px 5px rgba(0,0,0,0.12)',
    '0px 8px 10px -5px rgba(0,0,0,0.2),0px 16px 24px 2px rgba(0,0,0,0.14),0px 6px 30px 5px rgba(0,0,0,0.12)',
    '0px 8px 11px -5px rgba(0,0,0,0.2),0px 17px 26px 2px rgba(0,0,0,0.14),0px 6px 32px 5px rgba(0,0,0,0.12)',
    '0px 9px 11px -5px rgba(0,0,0,0.2),0px 18px 28px 2px rgba(0,0,0,0.14),0px 7px 34px 6px rgba(0,0,0,0.12)',
    '0px 9px 12px -6px rgba(0,0,0,0.2),0px 19px 29px 2px rgba(0,0,0,0.14),0px 7px 36px 6px rgba(0,0,0,0.12)',
    '0px 10px 13px -6px rgba(0,0,0,0.2),0px 20px 31px 3px rgba(0,0,0,0.14),0px 8px 38px 7px rgba(0,0,0,0.12)',
    '0px 10px 13px -6px rgba(0,0,0,0.2),0px 21px 33px 3px rgba(0,0,0,0.14),0px 8px 40px 7px rgba(0,0,0,0.12)',
    '0px 10px 14px -6px rgba(0,0,0,0.2),0px 22px 35px 3px rgba(0,0,0,0.14),0px 8px 42px 7px rgba(0,0,0,0.12)',
    '0px 11px 14px -7px rgba(0,0,0,0.2),0px 23px 36px 3px rgba(0,0,0,0.14),0px 9px 44px 8px rgba(0,0,0,0.12)',
    '0px 11px 15px -7px rgba(0,0,0,0.2),0px 24px 38px 3px rgba(0,0,0,0.14),0px 9px 46px 8px rgba(0,0,0,0.12)',
];
export const Z_INDEX = { mobileStepper: 1000, fab: 1050, speedDial: 1050, appBar: 1100, drawer: 1200, modal: 1300, snackbar: 1400, tooltip: 1500 };

const GREY = { 50: '#fafafa', 100: '#f5f5f5', 200: '#eeeeee', 300: '#e0e0e0', 400: '#bdbdbd', 500: '#9e9e9e', 600: '#757575', 700: '#616161', 800: '#424242', 900: '#212121', A100: '#f5f5f5', A200: '#eeeeee', A400: '#bdbdbd', A700: '#616161' };

// Palette paths resolve to the --sys-* custom properties declared in
// system.css (light on :root, dark on html.dark), so a generated class never
// has to be rebuilt when the colour scheme flips.
const tone = (name) => ({
    main: `var(--sys-${name})`,
    light: `var(--sys-${name}-light)`,
    dark: `var(--sys-${name}-dark)`,
    contrastText: `var(--sys-${name}-contrast)`,
});
export const palette = {
    get mode() { return typeof document !== 'undefined' && document.documentElement.classList.contains('dark') ? 'dark' : 'light'; },
    primary: tone('primary'),
    secondary: tone('secondary'),
    success: tone('success'),
    error: tone('error'),
    warning: tone('warning'),
    info: tone('info'),
    text: { primary: 'var(--sys-text-primary)', secondary: 'var(--sys-text-secondary)', disabled: 'var(--sys-text-disabled)' },
    background: { default: 'var(--sys-bg-default)', paper: 'var(--sys-bg-paper)' },
    divider: 'var(--sys-divider)',
    action: {
        active: 'var(--sys-action-active)', hover: 'var(--sys-action-hover)', selected: 'var(--sys-action-selected)',
        disabled: 'var(--sys-action-disabled)', disabledBackground: 'var(--sys-action-disabled-bg)', focus: 'var(--sys-action-focus)',
    },
    grey: GREY,
    common: { black: '#000', white: '#fff' },
};

const spacing = (...args) => args.map((a) => (typeof a === 'number' ? `${a * SPACING}px` : a)).join(' ');

export const theme = {
    palette,
    vars: { palette },
    spacing,
    shape: { borderRadius: RADIUS },
    shadows: SHADOWS,
    zIndex: Z_INDEX,
    breakpoints: {
        values: BREAKPOINTS,
        up: (k) => `@media (min-width:${BREAKPOINTS[k] ?? k}px)`,
        down: (k) => `@media (max-width:${(BREAKPOINTS[k] ?? k) - 0.05}px)`,
        between: (a, b) => `@media (min-width:${BREAKPOINTS[a] ?? a}px) and (max-width:${(BREAKPOINTS[b] ?? b) - 0.05}px)`,
    },
    typography: {
        fontFamily: "'Public Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
        fontFamilyMono: "'IBM Plex Mono', ui-monospace, 'SFMono-Regular', Menlo, monospace",
    },
};

// ── value transforms ────────────────────────────────────────────────────────

const lookupPalette = (v) => {
    if (typeof v !== 'string' || v.indexOf('.') === -1 && !(v in palette)) return v;
    const parts = v.split('.');
    let cur = palette;
    for (const p of parts) {
        if (cur == null || typeof cur !== 'object' || !(p in cur)) return v;
        cur = cur[p];
    }
    if (typeof cur === 'string') return cur;
    if (cur && typeof cur === 'object' && typeof cur.main === 'string') return cur.main;
    return v;
};

const space = (v) => {
    if (typeof v === 'number') return `${v * SPACING}px`;
    return v;
};
const size = (v) => {
    if (typeof v === 'number') return v > 0 && v <= 1 ? `${v * 100}%` : `${v}px`;
    return v;
};
const px = (v) => (typeof v === 'number' ? `${v}px` : v);
const borderVal = (v) => (typeof v === 'number' ? `${v}px solid` : v);

const SPACE_PROPS = {
    m: ['margin'], mt: ['margin-top'], mr: ['margin-right'], mb: ['margin-bottom'], ml: ['margin-left'],
    mx: ['margin-left', 'margin-right'], my: ['margin-top', 'margin-bottom'],
    p: ['padding'], pt: ['padding-top'], pr: ['padding-right'], pb: ['padding-bottom'], pl: ['padding-left'],
    px: ['padding-left', 'padding-right'], py: ['padding-top', 'padding-bottom'],
    margin: ['margin'], marginTop: ['margin-top'], marginRight: ['margin-right'], marginBottom: ['margin-bottom'], marginLeft: ['margin-left'],
    marginX: ['margin-left', 'margin-right'], marginY: ['margin-top', 'margin-bottom'],
    padding: ['padding'], paddingTop: ['padding-top'], paddingRight: ['padding-right'], paddingBottom: ['padding-bottom'], paddingLeft: ['padding-left'],
    paddingX: ['padding-left', 'padding-right'], paddingY: ['padding-top', 'padding-bottom'],
    gap: ['gap'], rowGap: ['row-gap'], columnGap: ['column-gap'],
};
const SIZE_PROPS = new Set(['width', 'maxWidth', 'minWidth', 'height', 'maxHeight', 'minHeight']);
const COLOR_PROPS = new Set(['color', 'backgroundColor', 'borderColor', 'borderTopColor', 'borderRightColor', 'borderBottomColor', 'borderLeftColor', 'outlineColor']);
const BORDER_PROPS = new Set(['border', 'borderTop', 'borderRight', 'borderBottom', 'borderLeft']);
// Same set React treats as unitless for inline styles.
const UNITLESS = new Set(['animationIterationCount', 'aspectRatio', 'columnCount', 'flex', 'flexGrow', 'flexShrink', 'fontWeight', 'gridArea', 'gridColumn', 'gridColumnEnd', 'gridColumnStart', 'gridRow', 'gridRowEnd', 'gridRowStart', 'lineHeight', 'opacity', 'order', 'orphans', 'scale', 'tabSize', 'widows', 'zIndex', 'zoom', 'fillOpacity', 'strokeOpacity', 'lineClamp', 'WebkitLineClamp']);
const FONT_WEIGHTS = { fontWeightLight: 300, fontWeightRegular: 400, fontWeightMedium: 500, fontWeightBold: 700 };

export const TYPOGRAPHY = {
    h1: { fontSize: '28px', fontWeight: 600, letterSpacing: '-0.01em', lineHeight: 1.2 },
    h2: { fontSize: '20px', fontWeight: 600, letterSpacing: '-0.01em', lineHeight: 1.25 },
    h3: { fontSize: '16px', fontWeight: 600, lineHeight: 1.3 },
    h4: { fontSize: '16px', fontWeight: 600, lineHeight: 1.35 },
    h5: { fontSize: '14px', fontWeight: 600, lineHeight: 1.4 },
    h6: { fontSize: '13px', fontWeight: 600, letterSpacing: '0.02em', lineHeight: 1.5 },
    subtitle1: { fontSize: '1rem', fontWeight: 400, lineHeight: 1.75 },
    subtitle2: { fontSize: '0.875rem', fontWeight: 500, lineHeight: 1.57 },
    body1: { fontSize: '14px', fontWeight: 400, lineHeight: 1.5 },
    body2: { fontSize: '13px', fontWeight: 400, lineHeight: 1.5 },
    caption: { fontSize: '12px', fontWeight: 400, lineHeight: 1.4 },
    overline: { fontSize: '0.75rem', fontWeight: 400, lineHeight: 2.66, textTransform: 'uppercase' },
    button: { fontSize: '13px', fontWeight: 500, lineHeight: 1.75, textTransform: 'none' },
    mono: { fontFamily: theme.typography.fontFamilyMono, fontVariantNumeric: 'tabular-nums' },
};

const kebab = (prop) => {
    if (prop.startsWith('--')) return prop;
    const k = prop.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());
    // WebkitFoo / MozFoo -> -webkit-foo; msFoo -> -ms-foo
    return /^(webkit|moz|o)-/.test(k) ? '-' + k : k.startsWith('ms-') ? '-' + k : k;
};

const isBreakpointMap = (v) => {
    if (!v || typeof v !== 'object' || Array.isArray(v)) return false;
    const ks = Object.keys(v);
    return ks.length > 0 && ks.every((k) => BP_KEYS.includes(k));
};

// One property/value pair -> array of [cssProp, cssValue].
function declarations(prop, raw) {
    let v = typeof raw === 'function' ? raw(theme) : raw;
    if (v === null || v === undefined || v === false) return [];
    if (prop === 'typography') {
        const t = TYPOGRAPHY[v];
        return t ? Object.entries(t).flatMap(([k, x]) => declarations(k, x)) : [];
    }
    if (SPACE_PROPS[prop]) return SPACE_PROPS[prop].map((p) => [p, space(v)]);
    if (prop === 'bgcolor') return [['background-color', lookupPalette(v)]];
    if (COLOR_PROPS.has(prop)) return [[kebab(prop), lookupPalette(v)]];
    if (SIZE_PROPS.has(prop)) {
        if (prop === 'maxWidth' && typeof v === 'string' && v in BREAKPOINTS) return [['max-width', `${BREAKPOINTS[v]}px`]];
        return [[kebab(prop), size(v)]];
    }
    if (prop === 'borderRadius') return [['border-radius', typeof v === 'number' ? `${v * RADIUS}px` : v]];
    if (BORDER_PROPS.has(prop)) return [[kebab(prop), borderVal(v)]];
    if (prop === 'boxShadow') return [['box-shadow', typeof v === 'number' ? (SHADOWS[v] ?? 'none') : v]];
    if (prop === 'zIndex') return [['z-index', typeof v === 'string' && v in Z_INDEX ? Z_INDEX[v] : v]];
    if (prop === 'fontWeight') return [['font-weight', FONT_WEIGHTS[v] ?? v]];
    if (prop === 'displayPrint') return [];
    if (typeof v === 'number' && !UNITLESS.has(prop) && !prop.startsWith('--')) return [[kebab(prop), px(v)]];
    return [[kebab(prop), v]];
}

const joinSelector = (parent, key) => {
    // Each comma-separated part is resolved against the parent on its own.
    return key.split(',').map((part) => {
        const p = part.trim();
        return p.includes('&') ? p.replace(/&/g, parent) : `${parent} ${p}`;
    }).join(', ');
};

/**
 * Compile a style object into flat rules.
 * @returns {Array<{media:string[], selector:string, body:string}>}
 */
function compile(style, selector, media, out) {
    if (!style) return;
    if (typeof style === 'function') style = style(theme);
    if (Array.isArray(style)) { style.forEach((s) => compile(s, selector, media, out)); return; }
    if (typeof style !== 'object') return;

    let body = '';
    const flush = () => { if (body) { out.push({ media, selector, body }); body = ''; } };
    const nested = [];

    for (const key of Object.keys(style)) {
        let v = style[key];
        if (typeof v === 'function' && !key.startsWith('&')) v = v(theme);
        if (v === null || v === undefined || v === false) continue;

        if (key.startsWith('@')) {            // @media / @container / @supports
            nested.push(() => compile(v, selector, [...media, key], out));
        } else if (isBreakpointMap(v)) {      // responsive value { xs, md, ... }
            for (const bp of BP_KEYS) {
                if (!(bp in v)) continue;
                const decl = declarations(key, v[bp]).map(([p, x]) => `${p}:${x};`).join('');
                if (!decl) continue;
                if (BREAKPOINTS[bp] === 0) body += decl;
                else nested.push(() => out.push({ media: [...media, `@media (min-width:${BREAKPOINTS[bp]}px)`], selector, body: decl }));
            }
        } else if (Array.isArray(v)) {        // responsive value [xs, sm, md, ...]
            v.forEach((x, i) => {
                const bp = BP_KEYS[i];
                const decl = declarations(key, x).map(([p, y]) => `${p}:${y};`).join('');
                if (!decl) return;
                if (i === 0) body += decl;
                else nested.push(() => out.push({ media: [...media, `@media (min-width:${BREAKPOINTS[bp]}px)`], selector, body: decl }));
            });
        } else if (typeof v === 'object') {   // nested selector
            nested.push(() => compile(v, joinSelector(selector, key), media, out));
        } else {
            body += declarations(key, v).map(([p, x]) => `${p}:${x};`).join('');
        }
    }
    flush();
    nested.forEach((fn) => fn());
}

// ── injection ───────────────────────────────────────────────────────────────

// Two <style> tags, kept as the LAST children of <head>, in this order:
//   1. base  - system.css (component looks)
//   2. sx    - generated one-off classes
// Being last is what lets a single-class component or sx rule win over a
// page's own <style> block of equal specificity (several pages ship one, and
// React hoists those into <head> when the page mounts) - the cascade position
// the previous styling library had. An observer re-asserts it whenever
// something else is appended.
let baseEl = null;
let sheetEl = null;
const injected = new Set();
const ruleLog = [];

const insert = (css) => {
    try { sheetEl.sheet.insertRule(css, sheetEl.sheet.cssRules.length); } catch { /* invalid one-off rule: skip, never break render */ }
};

function keepLast() {
    const head = document.head;
    if (head.lastElementChild === sheetEl && sheetEl.previousElementSibling === baseEl) return;
    head.append(baseEl, sheetEl);
    // Re-attaching a <style> resets rules added through the CSSOM: replay them.
    ruleLog.forEach(insert);
}

function ensureSheet() {
    if (sheetEl || typeof document === 'undefined') return sheetEl;
    baseEl = document.createElement('style');
    baseEl.setAttribute('data-sys-base', '');
    // Not under the test runner: jsdom does no layout, and parsing the sheet in every test file made the suite time out.
    baseEl.textContent = import.meta.env.MODE === 'test' ? '' : baseCss;
    sheetEl = document.createElement('style');
    sheetEl.setAttribute('data-sys-sx', '');
    document.head.append(baseEl, sheetEl);
    // Only a newly added stylesheet can change the cascade order. Skipped under
    // the test runner: jsdom has no cascade to protect and re-parsing the base
    // sheet on every injected test stylesheet made the suite time out.
    if (typeof MutationObserver !== 'undefined' && import.meta.env.MODE !== 'test') {
        new MutationObserver((records) => {
            const styleAdded = records.some((r) => [...r.addedNodes].some((n) => n !== baseEl && n !== sheetEl
                && (n.nodeName === 'STYLE' || (n.nodeName === 'LINK' && /stylesheet/i.test(n.rel || '')))));
            if (styleAdded) keepLast();
        }).observe(document.head, { childList: true });
    }
    return sheetEl;
}
ensureSheet();

// djb2 — short, stable class names for identical style text.
const hash = (str) => {
    let h = 5381;
    for (let i = 0; i < str.length; i++) h = ((h << 5) + h) ^ str.charCodeAt(i);
    return (h >>> 0).toString(36);
};

/**
 * Turn an sx value (object | function | array | falsy) into a class name.
 * Returns '' when there is nothing to style.
 */
export function sxClass(sx) {
    if (!sx) return '';
    const rules = [];
    compile(sx, '.\u0001', [], rules);
    if (!rules.length) return '';
    const text = rules.map((r) => `${r.media.join('')}|${r.selector}{${r.body}}`).join('');
    const cls = 'sx-' + hash(text);
    if (!injected.has(cls)) {
        injected.add(cls);
        const el = ensureSheet();
        if (el && el.sheet) {
            for (const r of rules) {
                let css = `${r.selector.split('.\u0001').join('.' + cls)}{${r.body}}`;
                for (let i = r.media.length - 1; i >= 0; i--) css = `${r.media[i]}{${css}}`;
                ruleLog.push(css);
                insert(css);
            }
        }
    }
    return cls;
}

// Props that Box / Typography / Stack / Grid accept directly as style shorthands.
const SYSTEM_PROPS = new Set([
    ...Object.keys(SPACE_PROPS), ...SIZE_PROPS, ...BORDER_PROPS,
    'bgcolor', 'color', 'borderColor', 'borderRadius', 'boxShadow', 'display', 'overflow', 'overflowX', 'overflowY',
    'position', 'top', 'right', 'bottom', 'left', 'zIndex', 'flex', 'flexDirection', 'flexWrap', 'flexGrow', 'flexShrink', 'flexBasis',
    'alignItems', 'alignContent', 'alignSelf', 'justifyContent', 'justifyItems', 'justifySelf', 'order',
    'gridTemplateColumns', 'gridTemplateRows', 'gridTemplateAreas', 'gridColumn', 'gridRow', 'gridArea', 'gridAutoFlow', 'gridAutoColumns', 'gridAutoRows',
    'fontFamily', 'fontSize', 'fontStyle', 'fontWeight', 'letterSpacing', 'lineHeight', 'textAlign', 'textTransform', 'typography',
    'visibility', 'whiteSpace', 'textOverflow',
]);

/** Split system style props out of a props bag. */
export function splitSystemProps(props, skip) {
    const system = {};
    const rest = {};
    let has = false;
    for (const k in props) {
        if (SYSTEM_PROPS.has(k) && !(skip && skip.has(k))) { system[k] = props[k]; has = true; }
        else rest[k] = props[k];
    }
    return [has ? system : null, rest];
}

export const cx = (...parts) => parts.filter(Boolean).join(' ');
export { lookupPalette };
