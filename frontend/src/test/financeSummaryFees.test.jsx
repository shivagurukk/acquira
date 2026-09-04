/**
 * Finance Summary — the fee stack's render contract.
 *
 * The browser pane cannot log in, so this pins what the screen does with the
 * three fee figures the API returns: interchange, scheme fee and PG (gateway)
 * fee, plus the net margin derived from them. All data is mocked at the API
 * boundary.
 *
 * The distinction that matters here: a period with NO fee rows built must
 * render em-dashes and say so, and must NOT be confusable with a period whose
 * fees are genuinely zero. Both look identical if you only check for "0.00".
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';

vi.mock('../contexts/AuthContext', () => ({
    useAuth: () => ({
        currencySymbol: 'AED', currencyCode: 'AED', currencyDecimals: 2, tenantVersion: 1,
    }),
}));

const cachedGet = vi.fn();
const apiGet = vi.fn();
vi.mock('../api/apiCache', () => ({ cachedGet: (...a) => cachedGet(...a) }));
vi.mock('../api/axios', () => ({ default: { get: (...a) => apiGet(...a) } }));

import FinanceSummary from '../pages/finance/FinanceSummary';

/** One MONTH row as FinanceSummaryService returns it. */
const monthRow = (over = {}) => ({
    row_label: '2026-08', month_label: '2026-08', sort_date: '2026-08-01',
    dom_debit_cnt: 10, dom_debit_vol: 1000, dom_debit_msf: 20, dom_debit_optin: 0,
    dom_debit_ic: 4, dom_debit_sf: 1,
    dom_credit_cnt: 5, dom_credit_vol: 500, dom_credit_msf: 15, dom_credit_optin: 0,
    dom_credit_ic: 3, dom_credit_sf: 1,
    int_cnt: 2, int_vol: 200, int_msf: 10, int_optin: 0,
    int_ic: 2, int_sf: 1,
    total_vol: 1700, total_msf: 45,
    total_ic: 9, total_sf: 3, total_pg: 2, fee_basis_msf: 45,
    fees_available: true,
    ...over,
});

const cellTexts = (row) => within(row).getAllByRole('cell').map(c => c.textContent);

/** The trailing "Total & fee stack" group: volume, MSF, IC, SF, PG, margin, take rate. */
const feeStack = (row) => {
    const [volume, msf, ic, sf, pg, margin, take] = cellTexts(row).slice(-7);
    return { volume, msf, ic, sf, pg, margin, take };
};

beforeEach(() => {
    cachedGet.mockReset();
    apiGet.mockReset();
});

describe('Finance Summary — fee stack', () => {
    it('shows interchange, scheme fee and PG fee, and nets margin against all three', async () => {
        cachedGet.mockResolvedValue({ data: [monthRow()] });
        render(<FinanceSummary />);

        // Each fee is named twice: once as a take-rail leg, once as a column
        // header. Both matter — the rail is what you see without scrolling the
        // wide table sideways.
        await waitFor(() => expect(screen.getAllByText('Interchange').length).toBe(2));
        expect(screen.getAllByText('Scheme fee').length).toBe(2);
        expect(screen.getAllByText('PG fee').length).toBe(2);

        // The data row carries the three figures and the derived margin:
        // fee_basis_msf 45 - ic 9 - sf 3 - pg 2 = 31.
        const stack = feeStack(screen.getByText('2026-08').closest('tr'));
        expect(stack.ic).toBe('AED 9.00');
        expect(stack.sf).toBe('AED 3.00');
        expect(stack.pg).toBe('AED 2.00');
        expect(stack.margin).toBe('AED 31.00');
    });

    it('renders em-dashes and explains itself when no fee rows are built', async () => {
        cachedGet.mockResolvedValue({
            data: [monthRow({
                total_ic: 0, total_sf: 0, total_pg: 0, fee_basis_msf: 0, fees_available: false,
            })],
        });
        render(<FinanceSummary />);

        const row = await waitFor(() => screen.getByText('2026-08').closest('tr'));
        // Interchange, scheme, PG and net margin are all dashed — not zeroes,
        // which would read as "we kept nothing this month".
        expect(cellTexts(row).filter(t => t === '—').length).toBe(4);
        expect(screen.getByText(/have not been built for this period yet/)).toBeTruthy();
    });

    it('treats a missing total_pg (older backend) as zero rather than crashing the table', async () => {
        const { total_pg, ...noPg } = monthRow();
        expect(total_pg).toBe(2);
        cachedGet.mockResolvedValue({ data: [noPg] });
        render(<FinanceSummary />);

        const row = await waitFor(() => screen.getByText('2026-08').closest('tr'));
        const stack = feeStack(row);
        expect(stack.ic).toBe('AED 9.00');       // interchange still renders
        expect(stack.sf).toBe('AED 3.00');       // scheme fee still renders
        expect(stack.pg).toBe('AED 0.00');       // PG fee degrades to zero
        expect(stack.margin).toBe('AED 33.00');  // margin = 45 - 9 - 3 - 0
    });
});
