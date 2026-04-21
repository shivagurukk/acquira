import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';

/**
 * #27: Dark Mode — Theme toggle context.
 * Stores preference in localStorage. Applies 'dark' class to <html> for Tailwind.
 * Also sets CSS custom properties for inline-style components.
 */
const ThemeContext = createContext(null);

const LIGHT = {
  mode: 'light',
  bg: '#F9FAFB', bgCard: '#FFFFFF', bgSidebar: '#0F172A',
  text: '#111827', textSecondary: '#6B7280',
  border: '#E5E7EB', borderLight: '#F3F4F6',
  accent: '#1E3A8A', accentLight: '#EFF6FF',
};
const DARK = {
  mode: 'dark',
  bg: '#0F172A', bgCard: '#1E293B', bgSidebar: '#020617',
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

  useEffect(() => {
    const root = document.documentElement;
    if (isDark) {
      root.classList.add('dark');
    } else {
      root.classList.remove('dark');
    }
    localStorage.setItem('theme', isDark ? 'dark' : 'light');

    // Set CSS custom properties for inline-style components
    const t = isDark ? DARK : LIGHT;
    root.style.setProperty('--bg', t.bg);
    root.style.setProperty('--bg-card', t.bgCard);
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
