/**
 * Net Spread Dashboard — render contract for the visual layer.
 *
 * The browser pane cannot log in, so this pins the replica page's additions
 * over the Daily Merchant layout: the hero Net Spread tile, the equation
 * tiles (+DCC / +Rentals), the extended spread ribbon with the ancillary
 * segments, the rescue band's counts, the rescued row badge, the three new
 * table columns and the drilldown's line-by-line spread equation. All data
 * is mocked at the API boundary.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';

vi.mock('react-router-dom', () => ({
    useNavigate: () => vi.fn(),
    // Deep-link params (?month=&dates=&q=) — empty here, so the page falls
    // back to the calendar's latest date exactly as before.
    useSearchParams: () => [new URLSearchParams(), vi.fn()],
}));
vi.mock('../contexts/AuthContext', () => ({
    useAuth: () => ({
        currencySymbol: 'AED', currencyCode: 'AED', currencyDecimals: 2,
        tenantVersion: 1, homeCountryCode: 'AE',
    }),
}));
vi.mock('../contexts/ToastContext', () => ({ showToast: vi.fn() }));
vi.mock('@mui/material', () => ({
    Drawer: ({ open, children }) => (open ? <div data-testid="drawer">{children}</div> : null),
    IconButton: ({ children, ...p }) => <button {...p}>{children}</button>,
}));

const day = (d, volume, nm, dcc, rental) => ({
    date: `2026-08-${String(d).padStart(2, '0')}`,
    volume, count: Math.round(volume / 120), nm, dcc, rental,
    spread: nm + dcc + rental,
    msf: volume * 0.02, icf: volume * 0.01, sf: volume * 0.002, pg: volume * 0.001,
});
const TREND = [
    day(17, 90000, 400, 40, 0), day(18, 110000, 650, 55, 0), day(19, 95000, -120, 30, 60),
    day(20, 120000, 800, 60, 0), day(21, 130000, 900, 70, 100),
];
const ROWS = [
    { merchantId: 1, mid: 'M1', name: 'Alpha Foods', volume: 50000, count: 400,
      msf: 1000, icf: 500, sf: 100, pg: 50, nm: 350,
      dcc: 40, dccMerchant: 25, rental: 60, spread: 450, rescued: false },
    { merchantId: 2, mid: 'M2', name: 'Beta Motors', volume: 40000, count: 300,
      msf: 800, icf: 400, sf: 80, pg: 40, nm: 280,
      dcc: 0, dccMerchant: 0, rental: 0, spread: 280, rescued: false },
    // The page's whole point: negative on margin, positive on spread.
    { merchantId: 3, mid: 'M3', name: 'Gamma Rescueme', volume: 40000, count: 300,
      msf: 800, icf: 900, sf: 80, pg: 40, nm: -220,
      dcc: 130, dccMerchant: 70, rental: 150, spread: 60, rescued: true },
];
const PAYLOAD = {
    content: ROWS, totalElements: 3, page: 0, size: 50,
    totals: {
        volume: 130000, count: 1000, msf: 2600, icf: 1800, sf: 260, pg: 130,
        nm: 410, dcc: 170, dccMerchant: 95, rental: 210, spread: 790,
        lossOnMargin: 1, rescued: 1, lossOnSpread: 0, merchants: 3,
    },
    trend: TREND,
    businessDate: '2026-08-21', dates: ['2026-08-21'], month: null, selection: '21 Aug 2026',
};

const get = vi.fn();
const post = vi.fn();
vi.mock('../api/axios', () => ({ default: { get: (...a) => get(...a), post: (...a) => post(...a) } }));

import NetSpreadDashboard from '../pages/executive/NetSpreadDashboard';

beforeEach(() => {
    get.mockImplementation((url, cfg) => {
        if (cfg?.params?.month) return Promise.resolve({ data: { dates: TREND.map(t => t.date) } });
        return Promise.resolve({ data: { months: ['2026-08'], latest: '2026-08-21' } });
    });
    post.mockImplementation(() => Promise.resolve({ data: PAYLOAD }));
});

describe('Net Spread Dashboard — visual layer', () => {
    it('renders the ledger with the hero Net Spread tile and equation tiles', async () => {
        const { container } = render(<NetSpreadDashboard />);
        expect(screen.getByLabelText('Loading the dashboard')).toBeInTheDocument();

        await waitFor(() => expect(screen.getByText('Alpha Foods')).toBeInTheDocument());
        const hero = container.querySelector('.edm-tile-hero');
        expect(hero).toBeTruthy();
        expect(hero).toHaveClass('edm-tile-success');
        expect(hero.textContent).toContain('Net spread');

        // The equation reads across the strip: NM, +DCC, +Rentals.
        const tiles = [...container.querySelectorAll('.edm-tile')].map(t => t.textContent);
        expect(tiles.some(t => t.includes('Net margin'))).toBe(true);
        expect(tiles.some(t => t.includes('+ DCC (acquirer)'))).toBe(true);
        expect(tiles.some(t => t.includes('+ Rentals'))).toBe(true);
        // The merchant share is stated but never added.
        expect(tiles.some(t => t.includes('(not added)'))).toBe(true);
    });

    it('extends the ribbon with the ancillary segments', async () => {
        const { container } = render(<NetSpreadDashboard />);
        await waitFor(() => expect(screen.getByText('Alpha Foods')).toBeInTheDocument());

        const legend = [...container.querySelectorAll('.edm-panel')]
            .find(p => p.textContent.includes('From MSF to Net Spread'));
        expect(legend).toBeTruthy();
        expect(legend.textContent).toContain('DCC (Acquirer Share)');
        expect(legend.textContent).toContain('Rental Income');
        // Pool = 1800+260+130+410+170+210 = 2980; DCC 170 and rental 210 both
        // exist, so the ribbon draws six segments.
        const ribbon = legend.querySelector('.edm-ribbon');
        expect(ribbon.querySelectorAll('.edm-ribbon-seg').length).toBe(6);
    });

    it('states the rescue story in numbers and badges the rescued row', async () => {
        const { container } = render(<NetSpreadDashboard />);
        await waitFor(() => expect(screen.getByText('Gamma Rescueme')).toBeInTheDocument());

        const band = container.querySelector('.edm-rescue-band');
        expect(band).toBeTruthy();
        expect(band.textContent).toContain('1 merchant negative');
        expect(band.textContent).toContain('1 turns positive');
        expect(band.textContent).toContain('0 still');

        const badges = container.querySelectorAll('tbody .edm-rescued');
        expect(badges.length).toBe(1);
        expect(badges[0].closest('tr').textContent).toContain('Gamma Rescueme');
    });

    it('carries the three new columns and totals them', async () => {
        const { container } = render(<NetSpreadDashboard />);
        await waitFor(() => expect(screen.getByText('Alpha Foods')).toBeInTheDocument());

        const heads = [...container.querySelectorAll('thead th')].map(h => h.textContent);
        expect(heads).toContain('DCC (Acquirer)');
        expect(heads).toContain('Rental');
        expect(heads).toContain('Net Spread');
        // 12 columns, no SID column at merchant grain.
        expect(heads.length).toBe(12);
        expect(heads).not.toContain('SID');

        const totalRow = container.querySelector('.edm-total-row');
        expect(totalRow.textContent).toContain('170.00');  // DCC acquirer total
        expect(totalRow.textContent).toContain('210.00');  // rental total
        expect(totalRow.textContent).toContain('790.00');  // net spread total
    });

    it('tints spread cells and marks the loss row on spread, not margin', async () => {
        const { container } = render(<NetSpreadDashboard />);
        await waitFor(() => expect(screen.getByText('Gamma Rescueme')).toBeInTheDocument());

        // All three rows are spread-positive, so no loss tint anywhere —
        // even though Gamma is margin-negative.
        expect(container.querySelectorAll('tbody .edm-nm-loss').length).toBe(0);
        expect(container.querySelectorAll('tbody .edm-nm-cell').length).toBe(3);
    });

    it('opens the drilldown with the line-by-line spread equation', async () => {
        const { container } = render(<NetSpreadDashboard />);
        await waitFor(() => expect(screen.getByText('Gamma Rescueme')).toBeInTheDocument());
        fireEvent.click(screen.getByText('Gamma Rescueme'));
        await waitFor(() => expect(screen.getByTestId('drawer')).toBeInTheDocument());

        const drawer = screen.getByTestId('drawer');
        expect(drawer.textContent).toContain('RESCUED');
        expect(drawer.textContent).toContain('+ DCC (acquirer share)');
        expect(drawer.textContent).toContain('+ Rental income');
        expect(drawer.textContent).toContain('Net spread');
        // The footnote names the merchant share as excluded.
        expect(drawer.textContent).toContain("merchant's money");
    });
});
