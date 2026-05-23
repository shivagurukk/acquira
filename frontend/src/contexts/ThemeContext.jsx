import React, { createContext, useContext, useState, useEffect, useCallback, useMemo } from 'react';
import { ThemeProvider as MuiThemeProvider } from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';
import { createAppTheme } from '../theme';

/**
 * Dark Mode — single source of truth for theming.
 *
 * Responsibilities:
 *   1. Tracks light/dark preference (localStorage + OS preference).
 *   2. Toggles the 'dark' class on <html> for Tailwind.
 *   3. Publishes CSS custom properties for inline-style components.
 *   4. Builds the matching MUI theme and provides it via MUI's
 *      ThemeProvider — so MUI components (DataGrid, Dialog, Paper…)
 *      switch with dark mode instead of staying permanently light.
 */
const ThemeContext = createContext(null);

const LIGHT = {
  mode: 'light',
  bg: '#F9FAFB', bgCard: '#FFFFFF', bgSidebar: '#0F172A',
  bgSubtle: '#F3F4F6', bgHover: '#F9FAFB',
  text: '#111827', textSecondary: '#6B7280',
  border: '#E5E7EB', borderLight: '#F3F4F6',
  accent: '#1E3A8A', accentLight: '#EFF6FF',
};
const DARK = {
  mode: 'dark',
  bg: '#0F172A', bgCard: '#1E293B', bgSidebar: '#020617',
  bgSubtle: '#0F172A', bgHover: '#243049',
  text: '#F1F5F9', textSecondary: '#94A3B8',
  border: '#334155', borderLight: '#1E293B',
  accent: '#3B82F6', accentLight: '#1E3A5C',
};

export const ThemeProvider = ({ children }) => {
  const [isDark, setIsDark] = useState(() => {
    const saved = localStorage.getItem('theme');
    if (saved) return saved === 'dark';
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches || false;
  });

  const theme = isDark ? DARK : LIGHT;

  // MUI theme rebuilt only when the mode changes.
  const muiTheme = useMemo(() => createAppTheme(isDark ? 'dark' : 'light'), [isDark]);

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

  return (
    <ThemeContext.Provider value={{ isDark, theme, toggleTheme }}>
      <MuiThemeProvider theme={muiTheme}>
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
