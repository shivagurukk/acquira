package com.acquira.core.service;

import com.acquira.common.service.NetSpreadSql;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the ONE shared definition of net margin / net spread that every
 * executive page now reads (BusinessController, TopPerformers,
 * SalesPortfolio, SalesPulse, Leaderboard). A change here changes what
 * "Net Margin" means on six screens at once — the test exists so that
 * change is deliberate.
 */
class NetSpreadSqlTest {

    @Test
    void marginPrefersBatchColumnWithThreeLegFallback() {
        String m = NetSpreadSql.margin("s");
        assertEquals("COALESCE(s.total_margin, COALESCE(s.total_msf,0) - COALESCE(s.total_interchange,0)"
                + " - COALESCE(s.total_scheme_fee,0))", m);
    }

    @Test
    void ancillaryIsDccAcquirerPlusRentalOnly() {
        String a = NetSpreadSql.ancillary("sdm");
        assertEquals("(COALESCE(sdm.dcc_acquirer,0) + COALESCE(sdm.rental_amount,0))", a);
        // The merchant's DCC share is informational and must never enter the spread.
        assertFalse(a.contains("dcc_merchant"));
    }

    @Test
    void spreadIsMarginPlusAncillary() {
        assertEquals("(" + NetSpreadSql.margin("x") + " + " + NetSpreadSql.ancillary("x") + ")",
                NetSpreadSql.spread("x"));
    }

    @Test
    void aggregatesAreZeroDefaultedSums() {
        assertEquals("COALESCE(SUM(" + NetSpreadSql.margin("t") + "), 0)", NetSpreadSql.sumMargin("t"));
        assertEquals("COALESCE(SUM(" + NetSpreadSql.spread("t") + "), 0)", NetSpreadSql.sumSpread("t"));
        assertTrue(NetSpreadSql.sumAncillary("t").startsWith("COALESCE(SUM("));
    }
}
