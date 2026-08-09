/**
 * Auth storage helper.
 *
 * Logout and the 401 handler previously called `localStorage.clear()`,
 * which also wiped unrelated keys such as the user's `theme` preference.
 * clearAuthStorage() removes only the auth-related keys and leaves
 * everything else (theme, UI preferences) intact.
 */
const AUTH_KEYS = [
  'token',
  'refreshToken',
  'username',
  'userRole',
  'defaultTenantId',
  'menus',
  'allowedTenants',
  'roles',
  'sessionTimeoutMinutes',
  'mustChangePassword',
];

export function clearAuthStorage() {
  AUTH_KEYS.forEach((key) => localStorage.removeItem(key));
}

export default clearAuthStorage;
