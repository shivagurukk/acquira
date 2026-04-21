import React from 'react';
import { Sun, Moon } from 'lucide-react';
import { useTheme } from '../contexts/ThemeContext';

const ThemeToggle = ({ collapsed = false }) => {
  const { isDark, toggleTheme } = useTheme();

  return (
    <button
      onClick={toggleTheme}
      title={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: collapsed ? 'center' : 'flex-start',
        gap: 10,
        width: '100%',
        padding: collapsed ? '8px 0' : '8px 12px',
        border: 'none',
        borderRadius: 8,
        background: 'rgba(255,255,255,0.06)',
        color: 'rgba(255,255,255,0.65)',
        cursor: 'pointer',
        fontSize: 12,
        fontWeight: 500,
        transition: 'all 0.15s',
      }}
      onMouseEnter={e => { e.currentTarget.style.background = 'rgba(255,255,255,0.12)'; e.currentTarget.style.color = 'white'; }}
      onMouseLeave={e => { e.currentTarget.style.background = 'rgba(255,255,255,0.06)'; e.currentTarget.style.color = 'rgba(255,255,255,0.65)'; }}
    >
      {isDark ? <Sun size={16} /> : <Moon size={16} />}
      {!collapsed && (isDark ? 'Light' : 'Dark')}
    </button>
  );
};

export default ThemeToggle;
