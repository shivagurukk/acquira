import { describe, it, expect } from 'vitest';
import { weekRules } from '../utils/weekRules';

/* August 2026 is a convenient month for these: the 1st is a Saturday, so both
   week layouts have a non-trivial lead-in and the weekends fall differently. */
const AUG = (d) => `2026-08-${String(d).padStart(2, '0')}`;

describe('weekRules', () => {
    it('gives the UAE a Saturday+Sunday weekend on a Monday-first week', () => {
        const w = weekRules('AE');
        expect(w.weekendDays).toEqual([6, 0]);
        expect(w.firstDay).toBe(1);
        expect(w.label).toBe('Sa Su');
        expect(w.longLabel).toBe('Saturday & Sunday');
    });

    it('gives Bahrain, Oman and Egypt a Friday+Saturday weekend on a Sunday-first week', () => {
        ['BH', 'OM', 'EG'].forEach(cc => {
            const w = weekRules(cc);
            expect(w.weekendDays).toEqual([5, 6]);
            expect(w.firstDay).toBe(0);
            expect(w.label).toBe('Fr Sa');
        });
    });

    it('puts the weekend in the last two calendar columns for both layouts', () => {
        ['AE', 'BH'].forEach(cc => {
            const cols = weekRules(cc).headers;
            expect(cols).toHaveLength(7);
            expect(cols.slice(0, 5).every(c => !c.weekend)).toBe(true);
            expect(cols.slice(5).every(c => c.weekend)).toBe(true);
        });
        expect(weekRules('AE').headers.map(c => c.label))
            .toEqual(['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su']);
        expect(weekRules('BH').headers.map(c => c.label))
            .toEqual(['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa']);
    });

    it('classifies the same date differently for the UAE and Bahrain', () => {
        const ae = weekRules('AE'), bh = weekRules('BH');
        // 2026-08-07 is a Friday, 08 a Saturday, 09 a Sunday.
        expect(ae.isWeekend(AUG(7))).toBe(false);   // Friday is a working day in the UAE
        expect(bh.isWeekend(AUG(7))).toBe(true);
        expect(ae.isWeekend(AUG(8))).toBe(true);    // Saturday is a weekend in both
        expect(bh.isWeekend(AUG(8))).toBe(true);
        expect(ae.isWeekend(AUG(9))).toBe(true);    // Sunday opens the week in Bahrain
        expect(bh.isWeekend(AUG(9))).toBe(false);
    });

    it('counts a different number of weekend days per country in the same month', () => {
        // Aug 2026 opens on a Saturday: 5 Saturdays + 5 Sundays for the UAE,
        // 4 Fridays + 5 Saturdays for Bahrain.
        const days = Array.from({ length: 31 }, (_, i) => AUG(i + 1));
        expect(days.filter(weekRules('AE').isWeekend)).toHaveLength(10);
        expect(days.filter(weekRules('BH').isWeekend)).toHaveLength(9);
    });

    it('offsets the month grid by the country first day', () => {
        // 1 Aug 2026 is a Saturday -> getDay() === 6.
        expect(weekRules('BH').leadBlanks(6)).toBe(6); // Sunday-first: six blanks
        expect(weekRules('AE').leadBlanks(6)).toBe(5); // Monday-first: five blanks
        // A month starting on a Sunday.
        expect(weekRules('BH').leadBlanks(0)).toBe(0);
        expect(weekRules('AE').leadBlanks(0)).toBe(6);
    });

    it('falls back to the Gulf week for an unknown or missing country', () => {
        [null, undefined, '', 'ZZ'].forEach(cc => {
            expect(weekRules(cc).weekendDays).toEqual([5, 6]);
        });
    });

    it('is case-insensitive about the country code', () => {
        expect(weekRules('ae').weekendDays).toEqual([6, 0]);
    });

    it('returns false rather than throwing on a malformed date', () => {
        expect(weekRules('AE').isWeekend('not-a-date')).toBe(false);
        expect(weekRules('AE').isWeekend(null)).toBe(false);
    });
});
