/**
 * #25: Frontend tests — AuthContext (currency formatting)
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { AuthProvider, useAuth } from '../contexts/AuthContext';

// Mock axios.
// get() must return a Promise: AuthContext fetches /users/me/locale on login
// and chains .then() on it. With a bare vi.fn() (returning undefined) that
// effect threw and failed every test that logs in — a pre-existing harness bug.
vi.mock('../api/axios', () => ({
  default: {
    post: vi.fn(() => Promise.resolve({ data: {} })),
    get: vi.fn(() => Promise.resolve({ data: {} })),
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

  // Helper: log in with a single tenant so activeTenant resolves.
  const loginWithTenant = (result, tenant) => {
    act(() => {
      result.current.login({
        jwt: 'tok', refreshToken: 'r', username: 'u', userRole: 'ROLE_USER', roles: [],
        allowedTenants: [{ tenantId: 1, bankName: 'T', ...tenant }],
        defaultTenantId: 1, menus: [],
      });
    });
  };

  it('renders no currency at all when no tenant is loaded', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    // There is deliberately NO fallback code: inventing one (AED or BHD) would
    // mislabel money, and BHD vs AED differ by a factor of ten in minor units.
    expect(result.current.currencyCode).toBe(null);
    expect(result.current.currencySymbol).toBe(null);
    // No symbol is prefixed; the number still renders.
    expect(result.current.formatCurrency(1234)).toBe('1,234.00');
  });

  it('formats BHD at 3 decimals (1 BHD = 1000 fils)', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    loginWithTenant(result, { baseCurrency: 'BHD', currencySymbol: 'BHD', currencyDecimals: 3 });
    expect(result.current.currencyCode).toBe('BHD');
    expect(result.current.currencyDecimals).toBe(3);
    // Was '1,234' (0dp) — that silently dropped the fils.
    expect(result.current.formatCurrency(1234)).toBe('BHD 1,234.000');
    expect(result.current.formatCurrency(1234.567)).toBe('BHD 1,234.567');
    // Uniform precision: never jagged 2-or-3dp within one column.
    expect(result.current.formatCurrency(1234.5)).toBe('BHD 1,234.500');
    expect(result.current.formatCurrency(0)).toBe('BHD 0.000');
    expect(result.current.formatCurrency(null)).toBe('BHD 0.000');
  });

  it('formats EGP at 2 decimals', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    loginWithTenant(result, { baseCurrency: 'EGP', currencySymbol: 'EGP', currencyDecimals: 2 });
    expect(result.current.currencyCode).toBe('EGP');
    expect(result.current.currencyDecimals).toBe(2);
    expect(result.current.formatCurrency(1234)).toBe('EGP 1,234.00');
    expect(result.current.formatCurrency(1234.567)).toBe('EGP 1,234.57');
  });

  it('treats a missing currencyDecimals as unknown, not as 2', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    loginWithTenant(result, { baseCurrency: 'BHD', currencySymbol: 'BHD' }); // no currencyDecimals
    expect(result.current.currencyDecimals).toBe(null);
    // Falls back to ISO 4217 minor units for the code — NOT a blanket 2.
    expect(result.current.formatCurrency(1234)).toBe('BHD 1,234.000');
  });

  it('formatCurrency still honours an explicit decimals override', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    loginWithTenant(result, { baseCurrency: 'BHD', currencySymbol: 'BHD', currencyDecimals: 3 });
    expect(result.current.formatCurrency(1234.56, { decimals: 2 })).toBe('BHD 1,234.56');
    expect(result.current.formatCurrency(1234.56, { decimals: 0 })).toBe('BHD 1,235');
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
        allowedTenants: [{ tenantId: 1, bankName: 'Test Bank', baseCurrency: 'USD', currencySymbol: '$', currencyDecimals: 2 }],
        defaultTenantId: 1,
        menus: [],
      });
    });
    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.username).toBe('admin');
    expect(result.current.isSuperAdmin).toBe(true);
    expect(result.current.currencyCode).toBe('USD');
    expect(result.current.currencySymbol).toBe('$');
    expect(result.current.formatCurrency(500)).toBe('$ 500.00');
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
