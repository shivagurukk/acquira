/* ════════════════════════════════════════════════════════════════════
   The working week is a LOCAL fact, not a regional one.

   The UAE moved its weekend to Saturday + Sunday in January 2022;
   Bahrain, Oman, Egypt and the rest of the Gulf/Levant acquirers on this
   platform keep Friday + Saturday. Anything that says "weekend", starts a
   calendar, or splits weekday-vs-weekend traffic has to ask the TENANT's
   country (tenant.home_country_code, exposed as homeCountryCode on the
   auth context) rather than assume one of them.

   getDay(): 0 = Sunday … 6 = Saturday.
   ════════════════════════════════════════════════════════════════════ */

export const DAY_ABBR = ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'];
export const DAY_NAMES = ['Sunday', 'Monday', 'Tuesday', 'Wednesday',
    'Thursday', 'Friday', 'Saturday'];

const WEEK_FRI_SAT = { weekendDays: [5, 6], firstDay: 0 }; // week opens Sunday
const WEEK_SAT_SUN = { weekendDays: [6, 0], firstDay: 1 }; // week opens Monday

const WEEK_RULES = {
    AE: WEEK_SAT_SUN,
    BH: WEEK_FRI_SAT, OM: WEEK_FRI_SAT, EG: WEEK_FRI_SAT,
    SA: WEEK_FRI_SAT, KW: WEEK_FRI_SAT, QA: WEEK_FRI_SAT, JO: WEEK_FRI_SAT,
};

/* An unknown country keeps the Fri+Sat default rather than falling back to a
   Mon–Fri Western week: every tenant onboarded so far is a Gulf or Levant
   acquirer, so that is the safer wrong answer. */
const DEFAULT_WEEK = WEEK_FRI_SAT;

/* Parse 'YYYY-MM-DD' component-wise — new Date(iso) is UTC midnight and slides
   a day in some zones (the same trap documented in PremiumReportHeader). */
export const parseDay = (iso) => {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || '');
    return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null;
};

/**
 * Resolve one country code into everything the UI needs to draw and reason
 * about that country's week.
 *
 * @param {string|null} countryCode ISO-3166 alpha-2, e.g. 'AE' or 'BH'
 */
export const weekRules = (countryCode) => {
    const base = WEEK_RULES[String(countryCode || '').toUpperCase()] || DEFAULT_WEEK;
    const { weekendDays, firstDay } = base;
    // Column order for a calendar header, rotated so firstDay leads and the
    // weekend therefore lands in the last two columns.
    const order = Array.from({ length: 7 }, (_, i) => (firstDay + i) % 7);
    return {
        weekendDays,
        firstDay,
        order,
        headers: order.map(i => ({
            key: i, label: DAY_ABBR[i], weekend: weekendDays.includes(i),
        })),
        /* 'Fr Sa' — the compact legend form. */
        label: weekendDays.map(i => DAY_ABBR[i]).join(' '),
        /* 'Friday & Saturday' — the spelled-out form for tooltips. */
        longLabel: weekendDays.map(i => DAY_NAMES[i]).join(' & '),
        isWeekend: (iso) => {
            const d = parseDay(iso);
            return d ? weekendDays.includes(d.getDay()) : false;
        },
        /* How many blank cells lead the month, given the 1st's getDay(). */
        leadBlanks: (jsDay) => (jsDay - firstDay + 7) % 7,
    };
};

export default weekRules;
