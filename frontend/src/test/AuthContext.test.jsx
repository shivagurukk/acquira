/**
 * #25: Frontend tests — AuthContext (currency formatting)
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { AuthProvider, useAuth } from '../contexts/AuthContext';

// Mock axios
vi.mock('../api/axios', () => ({
  default: {
    post: vi.fn(),
    get: vi.fn(),
    interceptors: {
      request: { use: vi.fn() },
      response: { use: vi.fn() },
    },
  },
}));

// Clear localStorage before each test
beforeEach(() => {
  localStorage.clear();
});

describe('AuthContext', () => {
  const wrapper = ({ children }) => <AuthProvider>{children}</AuthProvider>;

  it('provides default auth state', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.username).toBe('');
  });

  it('provides currency formatting', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    // Default currency (no tenant loaded)
    expect(result.current.currencyCode).toBe('BHD');
    expect(result.current.formatCurrency(1234)).toBe('BHD 1,234');
    expect(result.current.formatCurrency(0)).toBe('BHD 0');
    expect(result.current.formatCurrency(null)).toBe('BHD 0');
  });

  it('formatCurrency supports decimal options', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    expect(result.current.formatCurrency(1234.56, { decimals: 2 })).toBe('BHD 1,234.56');
  });

  it('login sets auth state', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    act(() => {
      result.current.login({
        jwt: 'test-token',
        refreshToken: 'test-refresh',
        username: 'admin',
        userRole: 'ROLE_SUPER_ADMIN',
        roles: [],
        allowedTenants: [{ tenantId: 1, bankName: 'Test Bank', baseCurrency: 'USD', currencySymbol: '$' }],
        defaultTenantId: 1,
        menus: [],
      });
    });
    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.username).toBe('admin');
    expect(result.current.isSuperAdmin).toBe(true);
    expect(result.current.currencyCode).toBe('USD');
    expect(result.current.currencySymbol).toBe('$');
    expect(result.current.formatCurrency(500)).toBe('$ 500');
  });

  it('logout clears auth state', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    act(() => {
      result.current.login({ jwt: 'tok', username: 'u', allowedTenants: [], menus: [] });
    });
    expect(result.current.isAuthenticated).toBe(true);
    act(() => { result.current.logout(); });
    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.token).toBe(null);
  });
});
