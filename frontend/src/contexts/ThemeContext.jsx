import React, { createContext, useContext, useState, useEffect, useCallback, useMemo } from 'react';
import { ThemeProvider as MuiThemeProvider, useColorScheme } from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';
import { buildTheme } from '../theme';

/**
 * Dark Mode — single source of truth for theming.
 *
 * Responsibilities:
 *   1. Tracks light/dark preference (localStorage + OS preference).
 *   2. Toggles the 'dark' class on <html> — this drives Tailwind, the
 *      index.css token sheet, AND the MUI colour scheme (the theme is
 *      built with cssVariables + colorSchemeSelector: 'class', so both
 *      schemes are emitted as CSS variables and the class picks one).
 *   3. Publishes CSS custom properties for inline-style components.
 *
 * The ledger palette lives in exactly two places: index.css (:root /
 * html.dark) and theme.js TOKENS. The values below mirror those files.
 */
const ThemeContext = createContext(null);

const LIGHT = {
  mode: 'light',
  bg: '#F4F7FB', bgCard: '#FFFFFF', bgSidebar: '#0F1F3D',
  bgSubtle: '#F4F7FB', bgHover: '#EAF1FB',
  text: '#102A56', textSecondary: '#4E6078',
  border: '#D9E2EF', borderLight: '#E8EDF4',
  accent: '#2F5EA8', accentLight: '#EAF1FB',
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

// One theme carrying both colour schemes — never rebuilt on toggle.
const muiTheme = buildTheme();

// Keeps MUI's internal colour-scheme state in step with our isDark flag,
// so MUI and the html.dark class never disagree about the active scheme.
const SyncMuiMode = ({ isDark }) => {
  const { mode, setMode } = useColorScheme();
  useEffect(() => {
    const want = isDark ? 'dark' : 'light';
    if (mode !== want) setMode(want);
  }, [isDark, mode, setMode]);
  return null;
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
      <MuiThemeProvider theme={muiTheme} defaultMode={isDark ? 'dark' : 'light'}>
        <SyncMuiMode isDark={isDark} />
        <CssBaseline />
        {children}
      </MuiThemeProvider>
    </ThemeContext.Provider>
  );
};

export const useTheme = () => {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme must be used within ThemeProvider');
  return ctx;
};

export default ThemeContext;
