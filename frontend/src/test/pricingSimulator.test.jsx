/**
 * Pricing Simulator v2 — render contract for the redesigned page.
 *
 * The browser pane cannot log in, so this pins the pricing-desk layout:
 * tabbed workspaces (Segment matrix default, Blended what-if second), the
 * KPI strip with the below-cost drag tile, margin-gauge matrix cells, the
 * card-type coverage badge, the untyped column with disabled levers, the
 * segment panel → repricing worklist flow, and the tenant-disabled notice.
 * All data is mocked at the API boundary.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';

vi.mock('../utils/formatters', () => ({
  formatCompactCurrency: (v) => `BHD ${Number(v).toFixed(0)}`,
}));

const apiGet = vi.fn();
const apiPost = vi.fn();
vi.mock('../api/axios', () => ({ default: { get: (...a) => apiGet(...a), post: (...a) => apiPost(...a) } }));
vi.mock('../api/explorer', () => ({
  explorerApi: {
    getDistinct: vi.fn(() => Promise.resolve({ data: [] })),
    query: vi.fn(() => Promise.resolve({ data: { data: [] } })),
  },
}));

import PricingSimulator from '../pages/business/PricingSimulator';

const matrixPayload = {
  enabled: true,
  windowStart: '2026-05-27',
  windowEnd: '2026-08-26',
  cardTypeCoveragePct: 87.5,
  medianNetBps: 55.0,
  totals: { txns: 43, volume: 80000, msf: 1350, cost: 1000, netRevenue: 350, msfBps: 168.8, costBps: 125.0, netBps: 43.8 },
  schemeCoverage: [
    { scheme: 'VISA', volume: 40000, unknownSharePct: 25.0, lowCoverage: true },
    { scheme: 'MASTERCARD', volume: 40000, unknownSharePct: 0.0, lowCoverage: false },
  ],
  segments: [
    { scheme: 'VISA', cardType: 'CREDIT', destination: 'INTERNATIONAL', txns: 10, volume: 10000, msf: 100, interchange: 120, schemeFee: 25, ecomFee: 5, netRevenue: -50, merchants: 3, cost: 150, msfBps: 100.0, costBps: 150.0, netBps: -50.0, belowCost: true, compressed: false, hasCardType: true },
    { scheme: 'VISA', cardType: 'CREDIT', destination: 'DOMESTIC', txns: 20, volume: 20000, msf: 300, interchange: 150, schemeFee: 25, ecomFee: 5, netRevenue: 120, merchants: 5, cost: 180, msfBps: 150.0, costBps: 90.0, netBps: 60.0, belowCost: false, compressed: false, hasCardType: true },
    { scheme: 'VISA', cardType: 'UNSPECIFIED', destination: 'DOMESTIC', txns: 5, volume: 10000, msf: 150, interchange: 80, schemeFee: 10, ecomFee: 0, netRevenue: 60, merchants: 2, cost: 90, msfBps: 150.0, costBps: 90.0, netBps: 60.0, belowCost: false, compressed: false, hasCardType: false },
    { scheme: 'MASTERCARD', cardType: 'DEBIT', destination: 'DOMESTIC', txns: 8, volume: 40000, msf: 800, interchange: 500, schemeFee: 60, ecomFee: 20, netRevenue: 220, merchants: 4, cost: 580, msfBps: 200.0, costBps: 145.0, netBps: 55.0, belowCost: false, compressed: false, hasCardType: true },
  ],
};

const kpiPayload = {
  startDate: '2026-05-27', endDate: '2026-08-26',
  totalVolume: 80000, totalMsf: 1350, msfRateBps: 168.8, netTakeRateBps: 43.8,
  dccEligibleVolume: 5000, dccOptinVolume: 1000, dccMissedVolume: 4000,
  dccOptinRatePct: 20, dccPenetrationPct: 6,
};

function wireApis({ enabled = true } = {}) {
  apiGet.mockImplementation((url) => {
    if (url.includes('/pricing-simulator/config')) {
      return Promise.resolve({ data: { enabled, bounds: { earliest: '2026-01-01', latest: '2026-08-26' } } });
    }
    return Promise.resolve({ data: {} });
  });
  apiPost.mockImplementation((url) => {
    if (url.includes('/segment-matrix')) return Promise.resolve({ data: matrixPayload });
    if (url.includes('/segment-merchants')) {
      return Promise.resolve({ data: { merchants: [
        { mid: 'M001', name: 'Al Noor Trading', txns: 5, volume: 6000, msf: 60, cost: 90, netRevenue: -30, msfBps: 100.0, costBps: 150.0, netBps: -50.0 },
      ], p25MsfBps: 100.0, medianMsfBps: 110.0 } });
    }
    if (url.includes('/merchant-matrix')) {
      return Promise.resolve({ data: { mid: 'M001', windowStart: '2026-05-27', windowEnd: '2026-08-26',
        totals: { volume: 6000, msfBps: 100.0, netBps: -50.0 },
        segments: [{ scheme: 'VISA', cardType: 'CREDIT', destination: 'INTERNATIONAL', txns: 5, volume: 6000, msf: 60, cost: 90, netRevenue: -30, msfBps: 100.0, costBps: 150.0, netBps: -50.0, belowCost: true, hasCardType: true }] } });
    }
    if (url.includes('/revenue-kpis')) return Promise.resolve({ data: kpiPayload });
    return Promise.resolve({ data: {} });
  });
}

beforeEach(() => {
  apiGet.mockReset();
  apiPost.mockReset();
});

describe('Pricing Simulator — pricing desk layout', () => {
  it('renders KPI strip, coverage badge and gauge cells on the default tab', async () => {
    wireApis();
    render(<PricingSimulator />);

    // tabs present, matrix first
    await waitFor(() => expect(screen.getByRole('tab', { name: /segment matrix/i })).toBeTruthy());
    expect(screen.getByRole('tab', { name: /blended what-if/i })).toBeTruthy();

    // KPI strip
    await waitFor(() => expect(screen.getByText(/below-cost drag/i)).toBeTruthy());
    expect(screen.getByText(/blended msf/i)).toBeTruthy();
    expect(screen.getByText(/cost stack/i)).toBeTruthy();

    // coverage badge + per-scheme untyped warning
    expect(screen.getByText(/card type known · 87\.5%/i)).toBeTruthy();
    expect(screen.getByText(/25\.0% untyped/)).toBeTruthy();

    // matrix cells: below-cost VISA intl shows -50 net bps
    expect(screen.getAllByText('-50').length).toBeGreaterThan(0);
    // untyped column header
    expect(screen.getByText(/no card type/i)).toBeTruthy();
  });

  it('segment click opens the panel and the repricing worklist drills to a merchant', async () => {
    wireApis();
    render(<PricingSimulator />);
    await waitFor(() => expect(screen.getByText(/margin by segment/i)).toBeTruthy());

    // click the below-cost VISA · CREDIT · Intl cell
    fireEvent.click(screen.getByTitle('VISA · CREDIT · Intl'));
    expect(await screen.findByText(/selected segment/i)).toBeTruthy();
    expect(screen.getByText(/raise msf by/i)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /view repricing worklist/i }));
    expect(await screen.findByText('Al Noor Trading')).toBeTruthy();

    // click the merchant row → MID panel with vs-peers column
    fireEvent.click(screen.getByText('Al Noor Trading'));
    expect(await screen.findByText(/merchant repricing/i)).toBeTruthy();
    expect(screen.getAllByText(/vs peers/i).length).toBeGreaterThan(0);
  });

  it('untyped cell disables repricing', async () => {
    wireApis();
    render(<PricingSimulator />);
    await waitFor(() => expect(screen.getByText(/margin by segment/i)).toBeTruthy());

    fireEvent.click(screen.getByTitle('VISA · UNSPECIFIED · Local'));
    expect(await screen.findByText(/repricing is disabled/i)).toBeTruthy();
    expect(screen.queryByText(/raise msf by/i)).toBeNull();
  });

  it('shows the tenant-disabled notice and runs no calculations when the flag is off', async () => {
    wireApis({ enabled: false });
    render(<PricingSimulator />);

    expect(await screen.findByText(/pricing calculations are disabled for this bank/i)).toBeTruthy();
    // no data calls fired — only config
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('switches to the blended what-if workspace', async () => {
    wireApis();
    render(<PricingSimulator />);
    await waitFor(() => expect(screen.getByRole('tab', { name: /blended what-if/i })).toBeTruthy());

    fireEvent.click(screen.getByRole('tab', { name: /blended what-if/i }));
    await waitFor(() => expect(screen.getByText(/levers/i)).toBeTruthy());
    expect(screen.getByText(/msf rate change/i)).toBeTruthy();
  });
});
