import { describe, it, expect } from 'vitest';
import { cellStyle, fmtDuration, parseDay } from '../pages/ops/IngestTrust';

/* The coverage calendar's cell classification is the part of this screen most
   likely to be wrong in a way nobody notices: if a weekend renders as a gap,
   every Gulf tenant shows two red cells a week and people learn to ignore the
   whole board. These are unit tests rather than a rendered check because the
   browser preview pane serves no animation frames when hidden, so visual
   verification there proves nothing about logic. */

const loaded = { rows_fact: 10000, fee_priced_rows: 10000, load_count: 1 };

describe('coverage cell classification', () => {
    it('a fully loaded, fully priced day is green', () => {
        expect(cellStyle(loaded, false).background).toBe('var(--success-bg)');
    });

    it('a missing working day is red', () => {
        expect(cellStyle(undefined, false).background).toBe('var(--danger-bg)');
        expect(cellStyle({ rows_fact: 0 }, false).background).toBe('var(--danger-bg)');
    });

    it('THE ONE THAT MATTERS: an empty weekend day is inert, not a gap', () => {
        const cell = cellStyle(undefined, true);
        expect(cell.background).toBe('var(--bg-subtle)');
        expect(cell.background).not.toBe('var(--danger-bg)');
    });

    it('a weekend day that DOES carry data is still shown as loaded', () => {
        // Some tenants do settle on their weekend; that is data, not an anomaly.
        expect(cellStyle(loaded, true).background).toBe('var(--success-bg)');
    });

    it('a migration-backfilled day is muted, neither green nor red', () => {
        // Pre-ledger days know their summary count but not their fact count or
        // fee coverage. Green would assert a reconciliation that never happened;
        // red would paint years of good history as missing on day one.
        const preLedger = { rows_fact: null, rows_summary: 8000, load_count: 1 };
        expect(cellStyle(preLedger, false).background).toBe('var(--bg-muted)');
        expect(cellStyle(preLedger, false).background).not.toBe('var(--danger-bg)');
        expect(cellStyle(preLedger, false).background).not.toBe('var(--success-bg)');
    });

    it('a pre-ledger day is not mistaken for a fee-coverage problem', () => {
        // rows_fact null means fee coverage is unknown, not zero.
        expect(cellStyle({ rows_fact: null, rows_summary: 8000, fee_priced_rows: null }, false).background)
            .toBe('var(--bg-muted)');
    });

    it('a day loaded more than once is flagged — that is how a wipe is spotted', () => {
        expect(cellStyle({ ...loaded, load_count: 4 }, false).background).toBe('var(--warning-bg)');
    });

    it('a day whose fees priced on almost nothing is flagged', () => {
        // The dead-rate-card signature: rows landed, MSF did not.
        expect(cellStyle({ rows_fact: 10000, fee_priced_rows: 0, load_count: 1 }, false).background)
            .toBe('var(--warning-bg)');
        expect(cellStyle({ rows_fact: 10000, fee_priced_rows: 9400, load_count: 1 }, false).background)
            .toBe('var(--warning-bg)');
        expect(cellStyle({ rows_fact: 10000, fee_priced_rows: 9600, load_count: 1 }, false).background)
            .toBe('var(--success-bg)');
    });
});

describe('duration formatting', () => {
    it('renders the units an operator reads at a glance', () => {
        expect(fmtDuration(4200)).toBe('4s');
        expect(fmtDuration(65000)).toBe('1m 5s');
        expect(fmtDuration(6_120_000)).toBe('1h 42m');   // the 1.7h fee pass
        expect(fmtDuration(null)).toBe('—');
        expect(fmtDuration(undefined)).toBe('—');
    });
});

describe('date parsing', () => {
    it('parses component-wise so the day does not slide across time zones', () => {
        const d = parseDay('2026-08-28');
        expect(d.getFullYear()).toBe(2026);
        expect(d.getMonth()).toBe(7);      // zero-based August
        expect(d.getDate()).toBe(28);
    });

    it('tolerates a full timestamp and rejects junk', () => {
        expect(parseDay('2026-08-28T00:00:00').getDate()).toBe(28);
        expect(parseDay('not a date')).toBeNull();
        expect(parseDay(null)).toBeNull();
    });
});
