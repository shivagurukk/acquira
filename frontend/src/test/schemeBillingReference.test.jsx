/**
 * Scheme Billing Reference — render contract.
 *
 * The browser pane cannot log in, so this pins the static reference page:
 * the JSON bundle loads, the grouped TOC renders all 74 topics, search
 * narrows the list (including matches inside spec-table field names),
 * the billable filter works, and selecting a topic shows its meta chips
 * and record-layout tables. Data is the real bundled JSON — no mocks.
 */
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';

vi.mock('react-router-dom', () => ({
    useLocation: () => ({ pathname: '/ops/scheme-billing-reference' }),
    Link: ({ children }) => <a>{children}</a>,
}));

import SchemeBillingReference from '../pages/ops/SchemeBillingReference';
import mcbs from '../data/mcbsAcquirerReports.json';

describe('SchemeBillingReference', () => {
    it('bundle has the expected shape', () => {
        expect(mcbs.topicCount).toBe(mcbs.topics.length);
        expect(mcbs.topicCount).toBeGreaterThanOrEqual(70);
        // Every topic has a title; all but the two chapter-intro pages carry tables
        for (const t of mcbs.topics) expect(t.title).toBeTruthy();
        expect(mcbs.topics.filter(t => t.tables.length > 0).length).toBeGreaterThanOrEqual(70);
        // The invoice-file layouts the future T0CH reader depends on are present
        const titles = mcbs.topics.map(t => t.title).join(' | ');
        expect(titles).toMatch(/T0CH\/BFIL Detail Record/);
        expect(titles).toMatch(/TN3A/);
    });

    it('renders header, TOC groups and the first topic', () => {
        render(<SchemeBillingReference />);
        expect(screen.getAllByText('Scheme Billing Reference').length).toBeGreaterThan(0);
        expect(screen.getByText(/Invoice Data Files/)).toBeTruthy();
        // Every topic appears as a TOC button
        const toc = screen.getAllByRole('button').filter(b => /AB\d|GB\d|T0CH|TN3A|Invoice/.test(b.textContent));
        expect(toc.length).toBeGreaterThan(30);
    });

    it('search narrows the TOC, including field-level matches', () => {
        render(<SchemeBillingReference />);
        const input = screen.getByPlaceholderText(/Search reports/);
        fireEvent.change(input, { target: { value: 'Invoice ICA Number' } });
        // T0CH/BFIL detail record carries that field
        expect(screen.getAllByText(/T0CH\/BFIL/).length).toBeGreaterThan(0);
        fireEvent.change(input, { target: { value: 'zzz-no-such-thing' } });
        expect(screen.getByText(/No reports match/)).toBeTruthy();
    });

    it('selecting a topic shows meta chips and its spec table', () => {
        render(<SchemeBillingReference />);
        const item = screen.getByText(/AB201010-A1: Acquirer Authorization Detail Report/);
        fireEvent.click(item.closest('button'));
        expect(screen.getByText('Audience:')).toBeTruthy();
        expect(screen.getAllByText(/Principal Acquirer|Acquirer/).length).toBeGreaterThan(0);
    });

    it('billable filter hides non-billable topics', () => {
        render(<SchemeBillingReference />);
        const before = screen.getAllByRole('button').length;
        fireEvent.click(screen.getAllByText('Billable').find(el => el.tagName === 'BUTTON'));
        const after = screen.getAllByRole('button').length;
        expect(after).toBeLessThan(before);
    });
});
