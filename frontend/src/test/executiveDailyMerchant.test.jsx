/**
 * Executive Daily Merchant Dashboard — render contract for the visual layer.
 *
 * The browser pane cannot log in, so this pins the things the cosmetic pass
 * added on top of the ledger: layout-mirroring skeleton, the hero Net Margin
 * tile, period-over-period delta chips, per-tile sparklines, in-segment fee
 * ribbon labels, ranked mix strips, the freshness strip in the masthead, the
 * selection chips, the margin-tinted table cells and the chart hover card.
 * All data is mocked at the API boundary.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';

vi.mock('react-router-dom', () => ({ useNavigate: () => vi.fn() }));
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

const day = (d, volume, nm) => ({
    date: `2026-08-${String(d).padStart(2, '0')}`,
    volume, count: Math.round(volume / 120), nm,
    msf: volume * 0.02, icf: volume * 0.01, sf: volume * 0.002, pg: volume * 0.001,
});
const TREND = [
    day(17, 90000, 400), day(18, 110000, 650), day(19, 95000, -120),
    day(20, 120000, 800), day(21, 130000, 900),
];
const ROWS = [
    { sid: 'S1', mid: 'M1', merchantName: 'Alpha Foods', volume: 50000, count: 400, msf: 1000, icf: 500, sf: 100, pg: 50, nm: 350, merchantId: 1 },
    { sid: 'S2', mid: 'M2', merchantName: 'Beta Motors', volume: 40000, count: 300, msf: 800, icf: 400, sf: 80, pg: 40, nm: 280, merchantId: 2 },
    { sid: 'S3', mid: 'M3', merchantName: 'Gamma Loss', volume: 40000, count: 300, msf: 800, icf: 900, sf: 80, pg: 40, nm: -220, merchantId: 3 },
];
const PAYLOAD = {
    content: ROWS, totalElements: 3, page: 0, size: 50,
    totals: { volume: 130000, count: 1000, msf: 2600, icf: 1800, sf: 260, pg: 130, nm: 410 },
    trend: TREND,
    mix: {
        scheme: [{ label: 'VISA', volume: 80000 }, { label: 'MASTERCARD', volume: 50000 }],
        cardType: [{ label: 'Debit', volume: 70000 }, { label: 'Credit', volume: 60000 }],
        destination: [{ label: 'Domestic', volume: 100000 }, { label: 'International', volume: 30000 }],
    },
    businessDate: '2026-08-21', dates: ['2026-08-21'], month: null, selection: '21 Aug 2026',
};

const get = vi.fn();
const post = vi.fn();
vi.mock('../api/axios', () => ({ default: { get: (...a) => get(...a), post: (...a) => post(...a) } }));
vi.mock('../api/apiCache', () => ({ cachedGet: () => Promise.resolve({ data: {} }) }));

import DailyMerchantDashboard from '../pages/executive/DailyMerchantDashboard';

beforeEach(() => {
    get.mockImplementation((url, cfg) => {
        if (cfg?.params?.month) return Promise.resolve({ data: { dates: TREND.map(t => t.date) } });
        return Promise.resolve({ data: { months: ['2026-08'], latest: '2026-08-21' } });
    });
    post.mockImplementation((url) => {
        if (url.endsWith('/breakdown')) return Promise.resolve({ data: { mix: PAYLOAD.mix } });
        return Promise.resolve({ data: PAYLOAD });
    });
});

describe('Executive Daily Merchant Dashboard — visual layer', () => {
    it('opens on a layout-mirroring skeleton, then renders the ledger', async () => {
        const { container } = render(<DailyMerchantDashboard />);
        expect(screen.getByLabelText('Loading the dashboard')).toBeInTheDocument();
        expect(container.querySelectorAll('.edm-bone').length).toBeGreaterThan(20);

        await waitFor(() => expect(screen.getByText('Alpha Foods')).toBeInTheDocument());
        expect(screen.queryByLabelText('Loading the dashboard')).not.toBeInTheDocument();
    });

    it('leads with a hero Net Margin tile carrying a delta chip and a sparkline', async () => {
        const { container } = render(<DailyMerchantDashboard />);
        await waitFor(() => expect(screen.getByText('Alpha Foods')).toBeInTheDocument());

        const hero = container.querySelector('.edm-tile-hero');
        expect(hero).toBeTruthy();
        expect(hero).toHaveClass('edm-tile-success');
        expect(hero.textContent).toContain('Net margin');

        // 21 Aug selected -> compared to the one loaded day before it (20 Aug).
        await waitFor(() => expect(container.querySelectorAll('.edm-delta').length).toBe(5));
        const chips = [...container.querySelectorAll('.edm-delta')];
        expect(chips.every(c => c.textContent.includes('vs prior 1 day'))).toBe(true);
        // Net margin 900 vs 800 = +12.5% and is good news.
        expect(hero.querySelector('.edm-delta')).toHaveClass('edm-delta-good');
        expect(hero.querySelector('.edm-delta').textContent).toContain('12.5%');
        // Cost of sale up is BAD news — the chip inverts.
        const cost = [...container.querySelectorAll('.edm-tile')].find(t => t.textContent.includes('Cost of sale'));
        expect(cost.querySelector('.edm-delta')).toHaveClass('edm-delta-bad');

        // Every headline tile draws the month's shape.
        expect(container.querySelectorAll('.edm-tile .edm-spark').length).toBe(5);
    });

    it('labels fee ribbon segments inline and ranks the mix strips', async () => {
        const { container } = render(<DailyMerchantDashboard />);
        await waitFor(() => expect(screen.getByText('Alpha Foods')).toBeInTheDocument());

        const ribbon = container.querySelector('.edm-ribbon');
        expect(ribbon).toBeTruthy();
        // Interchange is 1800 / 2600 of the pool — wide enough for its own label.
        expect([...ribbon.querySelectorAll('.edm-ribbon-lbl')].map(l => l.textContent)).toContain('69%');

        const ranks = [...container.querySelectorAll('.edm-rank')].map(r => r.textContent);
        expect(ranks).toContain('01');
        expect(ranks).toContain('02');
        expect(container.querySelectorAll('.edm-mix-fill').length).toBeGreaterThanOrEqual(8);
    });

    it('states freshness in the masthead and shows the picked day as a toned chip', async () => {
        const { container } = render(<DailyMerchantDashboard />);
        await waitFor(() => expect(screen.getByText('Alpha Foods')).toBeInTheDocument());

        const ctx = container.querySelector('.edm-mast-ctx');
        expect(ctx.textContent).toContain('Latest loaded');
        expect(ctx.textContent).toContain('Aug 21, 2026');
        await waitFor(() => expect(ctx.textContent).toMatch(/5 of 31 days loaded/));
        expect(ctx.textContent).toContain('Refreshed');

        const chip = container.querySelector('.edm-daychip');
        expect(chip).toBeTruthy();
        expect(chip).toHaveClass('edm-daychip-good');
        expect(chip.textContent).toContain('Aug 21');
    });

    it('tints net margin cells on a diverging scale', async () => {
        const { container } = render(<DailyMerchantDashboard />);
        await waitFor(() => expect(screen.getByText('Gamma Loss')).toBeInTheDocument());

        const heat = container.querySelectorAll('tbody .edm-nm-cell');
        const loss = container.querySelectorAll('tbody .edm-nm-loss');
        expect(heat.length).toBe(2);
        expect(loss.length).toBe(1);
        // Strongest row on the page takes the deepest tint (4 + 14).
        expect(heat[0].style.getPropertyValue('--heat')).toBe('18');
        expect(container.querySelectorAll('tbody .edm-row-in').length).toBe(3);
    });

    it('lifts a hover card over a month-shape bar', async () => {
        const { container } = render(<DailyMerchantDashboard />);
        await waitFor(() => expect(screen.getByText('Alpha Foods')).toBeInTheDocument());

        const bars = container.querySelectorAll('.edm-bar');
        expect(bars.length).toBe(31);
        fireEvent.mouseEnter(bars[19]);            // 20 Aug
        const tip = container.querySelector('.edm-tip');
        expect(tip).toBeTruthy();
        expect(tip.textContent).toContain('Aug 20, 2026');
        expect(tip.textContent).toContain('Transactions');
        fireEvent.mouseEnter(bars[2]);             // 03 Aug — nothing loaded
        expect(container.querySelector('.edm-tip').textContent).toContain('No transactions loaded');
    });

    it('opens the drilldown with the navy head and MID/SID badges', async () => {
        const { container } = render(<DailyMerchantDashboard />);
        await waitFor(() => expect(screen.getByText('Alpha Foods')).toBeInTheDocument());
        fireEvent.click(screen.getByText('Alpha Foods'));
        await waitFor(() => expect(screen.getByTestId('drawer')).toBeInTheDocument());
        const head = container.querySelector('.edm-drawer-head');
        expect(head.textContent).toContain('Alpha Foods');
        expect([...head.querySelectorAll('.edm-badge')].map(b => b.textContent)).toEqual(['MIDM1', 'SIDS1']);
    });
});
