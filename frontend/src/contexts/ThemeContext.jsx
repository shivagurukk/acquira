import React, { createContext, useContext, useState, useEffect, useCallback, useMemo } from 'react';
import '../components/ui/system/sx'; // mounts the component layer's base styles

/**
 * Dark Mode — single source of truth for theming.
 *
 * Responsibilities:
 *   1. Tracks light/dark preference (localStorage + OS preference).
 *   2. Toggles the 'dark' class on <html> — this drives Tailwind, the
 *      index.css token sheet, AND the in-house component layer (its
 *      palette is CSS variables declared for :root and html.dark in
 *      components/ui/system/system.css, so the class picks the scheme).
 *   3. Publishes CSS custom properties for inline-style components.
 *
 * The ledger palette lives in exactly two places: index.css (:root /
 * html.dark) and theme.js TOKENS. The values below mirror those files.
 */
const ThemeContext = createContext(null);

const LIGHT = {
  mode: 'light',
  bg: '#F1F7FF', bgCard: '#EAF1FA', bgSidebar: '#0E1B33',
  bgSubtle: '#EFF4FB', bgHover: '#E3ECF8',
  text: '#14295E', textSecondary: '#51618C',
  border: '#E4E7EC', borderLight: '#EBEDF0',
  accent: '#3F63B0', accentLight: '#DCE8F7',
};
const DARK = {
  mode: 'dark',
  // Graphite dark scheme — mirrors html.dark in index.css / theme.js.
  bg: '#0E1116', bgCard: '#141B26', bgSidebar: '#0A1426',
  bgSubtle: '#12161C', bgHover: '#1C222B',
  text: '#E7EAEF', textSecondary: '#98A2AF',
  border: '#272E38', borderLight: '#272E38',
  accent: '#5E82D2', accentLight: '#1C2637',
};

export const ThemeProvider = ({ children }) => {
  const [isDark, setIsDark] = useState(() => {
    const saved = localStorage.getItem('theme');
    if (saved) return saved === 'dark';
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches || false;
  });

  const theme = isDark ? DARK : LIGHT;

  useEffect(() => {
    const root = document.documentElement;
    root.classList.toggle('dark', isDark);
    localStorage.setItem('theme', isDark ? 'dark' : 'light');

    // Publish CSS custom properties for inline-style components.
    const t = isDark ? DARK : LIGHT;
    root.style.setProperty('--bg', t.bg);
    root.style.setProperty('--bg-card', t.bgCard);
    root.style.setProperty('--bg-subtle', t.bgSubtle);
    root.style.setProperty('--bg-hover', t.bgHover);
    root.style.setProperty('--text', t.text);
    root.style.setProperty('--text-secondary', t.textSecondary);
    root.style.setProperty('--border', t.border);
    root.style.setProperty('--border-light', t.borderLight);
    root.style.setProperty('--accent', t.accent);
    root.style.setProperty('--accent-light', t.accentLight);
  }, [isDark]);

  const toggleTheme = useCallback(() => setIsDark(prev => !prev), []);

  const ctx = useMemo(() => ({ isDark, theme, toggleTheme }), [isDark, theme, toggleTheme]);

  return (
    <ThemeContext.Provider value={ctx}>
      {children}
    </ThemeContext.Provider>
  );
};

export const useTheme = () => {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme must be used within ThemeProvider');
  return ctx;
};

export default ThemeContext;
