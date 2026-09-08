/**
 * Daily Digest admin screen — render contract for the redesigned page.
 *
 * Pins the status strip (last sent / waiting / failed), the scheduled
 * send-time field, and the run-for-a-day flow: readiness chips for a chosen
 * date, sending, and the already-sent → "Resend anyway" hand-off.
 * All data is mocked at the API boundary.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';

vi.mock('../contexts/AuthContext', () => ({ useAuth: () => ({ tenantVersion: 1 }) }));
const toasts = vi.fn();
vi.mock('../contexts/ToastContext', () => ({ showToast: (...a) => toasts(...a) }));

const apiGet = vi.fn();
const apiPost = vi.fn();
const apiPut = vi.fn();
vi.mock('../api/axios', () => ({
  default: { get: (...a) => apiGet(...a), post: (...a) => apiPost(...a), put: (...a) => apiPut(...a) },
}));

import DailyDigest from '../pages/ops/DailyDigest';

const config = {
  enabled: true, recipients: 'cfo@bank.com', quiet_minutes: 15,
  require_merchant: true, require_trx: true, require_dcc: true, require_rental: true,
  backfill_window_days: 3, send_not_before: '08:00',
};
const dispatches = [
  { id: 3, business_date: '2026-09-04', status: 'PENDING', waiting_on: 'DCC+SCHEDULE', attempts: 0 },
  { id: 2, business_date: '2026-09-03', status: 'SENT', sent_at: '2026-09-04T08:05:00', recipients_sent: 'cfo@bank.com' },
  { id: 1, business_date: '2026-09-02', status: 'FAILED', error_message: 'SMTP delivery failed for all recipients' },
];

function wire({ alreadySent = false } = {}) {
  apiGet.mockImplementation((url) => {
    if (url.includes('/config')) return Promise.resolve({ data: config });
    if (url.includes('/dispatches')) return Promise.resolve({ data: dispatches });
    if (url.includes('/day-status')) {
      return Promise.resolve({ data: {
        date: '2026-09-04', merchant: true, trx: true, dcc: false, rental: true, running: false,
        dispatch: { status: 'PENDING', waiting_on: 'DCC', attempts: 0 },
      } });
    }
    if (url.includes('/preview')) return Promise.resolve({ data: '<p>preview</p>' });
    return Promise.resolve({ data: {} });
  });
  apiPost.mockImplementation((url) => {
    if (url.includes('/run')) {
      return alreadySent
        ? Promise.resolve({ data: { alreadySent: true, date: '2026-09-04' } })
        : Promise.resolve({ data: { status: 'SENT', date: '2026-09-04', recipients_sent: 'cfo@bank.com' } });
    }
    return Promise.resolve({ data: {} });
  });
  apiPut.mockResolvedValue({ data: config });
}

beforeEach(() => { apiGet.mockReset(); apiPost.mockReset(); apiPut.mockReset(); toasts.mockReset(); });

describe('Daily Digest — redesigned admin screen', () => {
  it('renders the status strip and the scheduled send time', async () => {
    wire();
    render(<DailyDigest />);

    await waitFor(() => expect(screen.getByText(/sends after 08:00/i)).toBeTruthy());
    expect(screen.getAllByText('2026-09-03').length).toBeGreaterThan(0); // last sent tile + history row
    expect(screen.getByText(/day\(s\) held by the feed gate/i)).toBeTruthy();
    expect(screen.getByText(/check smtp settings/i)).toBeTruthy();      // failed tile
    expect(screen.getAllByText(/scheduled send time/i).length).toBeGreaterThan(0);
    // pending row explains SCHEDULE in plain words
    expect(screen.getByText(/dcc feed \+ scheduled send time/i)).toBeTruthy();
  });

  it('checks readiness for a day and shows per-feed chips', async () => {
    wire();
    render(<DailyDigest />);
    await waitFor(() => expect(screen.getByText(/run for a day/i)).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: /check readiness/i }));
    await waitFor(() => expect(screen.getByText(/✗/)).toBeTruthy());
    expect(screen.getByText('Merchants')).toBeTruthy();
    expect(screen.getByText('Transactions')).toBeTruthy();
    expect(screen.getByText('DCC')).toBeTruthy();
    expect(screen.getByText('Rentals')).toBeTruthy();
    expect(apiGet).toHaveBeenCalledWith('/ops/digest/day-status', expect.anything());
  });

  it('sends the digest for the chosen day', async () => {
    wire();
    render(<DailyDigest />);
    await waitFor(() => expect(screen.getByRole('button', { name: /send digest now/i })).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: /send digest now/i }));
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/ops/digest/run',
      expect.objectContaining({ force: false })));
    await waitFor(() => expect(toasts).toHaveBeenCalledWith(expect.stringMatching(/sent to cfo@bank.com/i), 'success'));
  });

  it('hands off to Resend anyway when the day was already sent', async () => {
    wire({ alreadySent: true });
    render(<DailyDigest />);
    await waitFor(() => expect(screen.getByRole('button', { name: /send digest now/i })).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: /send digest now/i }));
    await waitFor(() => expect(screen.getByRole('button', { name: /resend anyway/i })).toBeTruthy());
    expect(toasts).toHaveBeenCalledWith(expect.stringMatching(/already sent/i), 'warning');
  });

  it('saves the schedule field with the config', async () => {
    wire();
    render(<DailyDigest />);
    await waitFor(() => expect(screen.getByRole('button', { name: /save settings/i })).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: /save settings/i }));
    await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/ops/digest/config',
      expect.objectContaining({ sendNotBefore: '08:00', requireMerchant: true })));
  });
});
