/**
 * Login MFA (email OTP second factor) — component-level flow.
 *
 * Covers the branch that the browser preview cannot exercise: the login card
 * swapping to the code step when the backend answers with mfaRequired instead
 * of a session, and the code being exchanged for one.
 *
 * framer-motion is stubbed to plain elements. AnimatePresence with mode="wait"
 * only mounts the next child once the previous child's exit animation has
 * finished, which never happens under jsdom (no rAF), so without the stub every
 * assertion after the first transition would time out.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';

vi.mock('framer-motion', () => {
    const passthrough = (tag) => ({ children, ...props }) => {
        const clean = { ...props };
        ['initial', 'animate', 'exit', 'transition', 'variants', 'whileHover', 'whileTap', 'layout']
            .forEach(k => delete clean[k]);
        const El = tag;
        return <El {...clean}>{children}</El>;
    };
    return {
        motion: new Proxy({}, { get: (_, tag) => passthrough(tag) }),
        AnimatePresence: ({ children }) => <>{children}</>,
    };
});

// The page pulls in auth context + router; stub them to the surface it uses.
const navigateSpy = vi.fn();
vi.mock('react-router-dom', () => ({ useNavigate: () => navigateSpy }));

const loginSpy = vi.fn();
vi.mock('../contexts/AuthContext', () => ({
    useAuth: () => ({ login: loginSpy, switchTenant: vi.fn() }),
}));

vi.mock('../pages/LoginBackdrop', () => ({ default: () => null }));
vi.mock('../components/AfsLogo', () => ({ AfsMark: () => null }));

import LoginPage from '../pages/LoginPage';

const SESSION = {
    jwt: 'header.payload.sig',
    allowedTenants: [{ tenantId: 1, bankName: 'Acquira Bank' }],
    defaultTenantId: 1,
    menus: [],
    username: 'mfatest',
};

/** Route fetch by URL so each test only declares what it cares about. */
function mockFetch(routes) {
    return vi.fn((url, opts) => {
        const handler = Object.entries(routes).find(([k]) => String(url).includes(k));
        if (!handler) return Promise.resolve({ ok: true, status: 200, json: async () => ({}) });
        const res = handler[1](opts ? JSON.parse(opts.body || '{}') : {});
        return Promise.resolve({ ok: res.status < 400, status: res.status, json: async () => res.body });
    });
}

const type = (el, value) => fireEvent.change(el, { target: { value } });

const signIn = async () => {
    type(screen.getByLabelText(/username/i), 'mfatest');
    type(screen.getByLabelText(/^password$/i), 'NewPass@2026x');
    await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /sign in securely/i }));
    });
};

beforeEach(() => {
    navigateSpy.mockClear();
    loginSpy.mockClear();
});
afterEach(() => { vi.unstubAllGlobals(); });

describe('login MFA step', () => {
    it('shows the code step instead of signing in when the backend demands MFA', async () => {
        vi.stubGlobal('fetch', mockFetch({
            '/api/sso/microsoft/config': () => ({ status: 200, body: { enabled: false } }),
            '/api/auth/login': () => ({
                status: 200,
                body: { mfaRequired: true, mfaTicket: 'ticket-1', expiresInMinutes: 5, emailHint: 'm***@acquira.com' },
            }),
        }));
        render(<LoginPage />);
        await signIn();

        await waitFor(() => expect(screen.getByText(/two-factor verification/i)).toBeInTheDocument());
        // The masked hint tells the user which inbox to check without disclosing the address.
        expect(screen.getByText(/m\*\*\*@acquira\.com/)).toBeInTheDocument();
        expect(screen.getByLabelText(/verification code/i)).toBeInTheDocument();
        // Critically: no session was established on the MFA response.
        expect(loginSpy).not.toHaveBeenCalled();
        expect(navigateSpy).not.toHaveBeenCalled();
    });

    it('exchanges a correct code for a session and lands on the dashboard', async () => {
        vi.stubGlobal('fetch', mockFetch({
            '/api/sso/microsoft/config': () => ({ status: 200, body: { enabled: false } }),
            '/api/auth/login/verify-mfa': (body) => (body.otp === '123456'
                ? { status: 200, body: SESSION }
                : { status: 401, body: { error: 'Invalid or expired verification code.' } }),
            '/api/auth/login': () => ({
                status: 200,
                body: { mfaRequired: true, mfaTicket: 'ticket-1', expiresInMinutes: 5, emailHint: 'm***@acquira.com' },
            }),
        }));
        render(<LoginPage />);
        await signIn();
        await screen.findByText(/two-factor verification/i);

        type(screen.getByLabelText(/verification code/i), '123456');
        await act(async () => { fireEvent.click(screen.getByRole('button', { name: /verify and sign in/i })); });

        await waitFor(() => expect(loginSpy).toHaveBeenCalledWith(expect.objectContaining({ jwt: SESSION.jwt })));
        expect(navigateSpy).toHaveBeenCalledWith('/dashboard');
    });

    it('keeps the user on the code step and reports a wrong code', async () => {
        vi.stubGlobal('fetch', mockFetch({
            '/api/sso/microsoft/config': () => ({ status: 200, body: { enabled: false } }),
            '/api/auth/login/verify-mfa': () => ({ status: 401, body: { error: 'Invalid or expired verification code.' } }),
            '/api/auth/login': () => ({
                status: 200,
                body: { mfaRequired: true, mfaTicket: 'ticket-1', expiresInMinutes: 5, emailHint: 'm***@acquira.com' },
            }),
        }));
        render(<LoginPage />);
        await signIn();
        await screen.findByText(/two-factor verification/i);

        type(screen.getByLabelText(/verification code/i), '000000');
        await act(async () => { fireEvent.click(screen.getByRole('button', { name: /verify and sign in/i })); });

        await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/invalid or expired/i));
        // Regression guard: the wrong-code message contains the word "expired",
        // and an earlier version pattern-matched on it and threw the user back to
        // the password form after a single typo. The ticket is still live here.
        expect(screen.getByLabelText(/verification code/i)).toBeInTheDocument();
        expect(screen.queryByRole('button', { name: /sign in securely/i })).not.toBeInTheDocument();
        expect(loginSpy).not.toHaveBeenCalled();
    });

    it('lets the user retry after a wrong code without re-entering the password', async () => {
        let attempt = 0;
        vi.stubGlobal('fetch', mockFetch({
            '/api/sso/microsoft/config': () => ({ status: 200, body: { enabled: false } }),
            '/api/auth/login/verify-mfa': () => (++attempt === 1
                ? { status: 401, body: { error: 'Invalid or expired verification code.' } }
                : { status: 200, body: SESSION }),
            '/api/auth/login': () => ({
                status: 200,
                body: { mfaRequired: true, mfaTicket: 'ticket-1', expiresInMinutes: 5, emailHint: 'm***@acquira.com' },
            }),
        }));
        render(<LoginPage />);
        await signIn();
        await screen.findByText(/two-factor verification/i);

        type(screen.getByLabelText(/verification code/i), '000000');
        await act(async () => { fireEvent.click(screen.getByRole('button', { name: /verify and sign in/i })); });
        await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());

        type(screen.getByLabelText(/verification code/i), '123456');
        await act(async () => { fireEvent.click(screen.getByRole('button', { name: /verify and sign in/i })); });

        await waitFor(() => expect(loginSpy).toHaveBeenCalledWith(expect.objectContaining({ jwt: SESSION.jwt })));
        expect(navigateSpy).toHaveBeenCalledWith('/dashboard');
    });

    it('drops back to the password form when the challenge is burned', async () => {
        vi.stubGlobal('fetch', mockFetch({
            '/api/sso/microsoft/config': () => ({ status: 200, body: { enabled: false } }),
            '/api/auth/login/verify-mfa': () => ({
                status: 429,
                body: {
                    error: 'Too many incorrect codes. Please sign in again to get a new one.',
                    challengeDead: true,
                },
            }),
            '/api/auth/login': () => ({
                status: 200,
                body: { mfaRequired: true, mfaTicket: 'ticket-1', expiresInMinutes: 5, emailHint: 'm***@acquira.com' },
            }),
        }));
        render(<LoginPage />);
        await signIn();
        await screen.findByText(/two-factor verification/i);

        type(screen.getByLabelText(/verification code/i), '000000');
        await act(async () => { fireEvent.click(screen.getByRole('button', { name: /verify and sign in/i })); });

        // A dead ticket can never be answered, so leaving the code box on screen
        // would strand the user typing into nothing.
        await waitFor(() => expect(screen.getByRole('button', { name: /sign in securely/i })).toBeInTheDocument());
        expect(screen.queryByLabelText(/verification code/i)).not.toBeInTheDocument();
    });

    it('signs straight in when the tenant does not require MFA', async () => {
        vi.stubGlobal('fetch', mockFetch({
            '/api/sso/microsoft/config': () => ({ status: 200, body: { enabled: false } }),
            '/api/auth/login': () => ({ status: 200, body: SESSION }),
        }));
        render(<LoginPage />);
        await signIn();

        await waitFor(() => expect(loginSpy).toHaveBeenCalled());
        expect(navigateSpy).toHaveBeenCalledWith('/dashboard');
        expect(screen.queryByText(/two-factor verification/i)).not.toBeInTheDocument();
    });
});
