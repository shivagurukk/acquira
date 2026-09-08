package com.acquira.core.service;

import com.acquira.common.service.ChannelSql;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChannelSql routes the executive merchant-day reads: ALL must stay the
 * byte-identical sum_daily_merchant path, and the POS/ECOM derived relation
 * must expose the full sum_daily_merchant column contract with the agreed
 * ancillary attribution (DCC + rental -> POS, ecom FX -> ECOM).
 */
class ChannelSqlTest {

    @Test
    void normalizeRoutesAnythingUnknownToAll() {
        assertEquals("ALL", ChannelSql.normalize(null));
        assertEquals("ALL", ChannelSql.normalize(""));
        assertEquals("ALL", ChannelSql.normalize("all"));
        assertEquals("ALL", ChannelSql.normalize("BENEFIT PG"));
        assertEquals("ALL", ChannelSql.normalize("' OR 1=1 --"));
        assertEquals("POS", ChannelSql.normalize(" pos "));
        assertEquals("ECOM", ChannelSql.normalize("Ecom"));
    }

    @Test
    void allIsThePlainTable() {
        assertEquals("sum_daily_merchant", ChannelSql.merchantDay(null));
        assertEquals("sum_daily_merchant", ChannelSql.merchantDay("ALL"));
        assertEquals("sum_daily_merchant", ChannelSql.merchantDay("anything"));
    }

    @Test
    void derivedRelationExposesTheFullColumnContract() {
        for (String ch : new String[]{"POS", "ECOM"}) {
            String rel = ChannelSql.merchantDay(ch);
            for (String col : new String[]{
                    "total_txns", "total_volume", "total_base_volume", "total_msf",
                    "total_interchange", "total_scheme_fee", "total_ecom_fee",
                    "total_margin", "dcc_acquirer", "dcc_merchant", "rental_amount",
                    "fx_revenue"}) {
                assertTrue(rel.contains("AS " + col), ch + " relation must alias " + col);
            }
            assertTrue(rel.contains("channel_class = '" + ch + "'"));
            assertTrue(rel.startsWith("(") && rel.endsWith(")"), "must be FROM-able as a subquery");
        }
    }

    @Test
    void ancillaryAttributionSplitsByChannel() {
        String pos = ChannelSql.merchantDay("POS");
        String ecom = ChannelSql.merchantDay("ECOM");
        // POS ancillary branch carries DCC + rental and zero FX.
        assertTrue(pos.contains("COALESCE(a.dcc_acquirer,0), COALESCE(a.dcc_merchant,0), COALESCE(a.rental_amount,0), 0"));
        // ECOM ancillary branch carries FX only.
        assertTrue(ecom.contains("0, 0, 0, COALESCE(a.fx_revenue,0)"));
        assertFalse(ecom.contains("COALESCE(a.rental_amount,0), 0"));
    }

    @Test
    void onlyFixedLiteralsAreEverInterpolated() {
        // The only channel text that can reach SQL is one of the two constants;
        // merchantDay(normalize(userInput)) is therefore injection-safe.
        assertEquals(ChannelSql.merchantDay("POS"),
                ChannelSql.merchantDay(ChannelSql.normalize("pos")));
        assertEquals("sum_daily_merchant",
                ChannelSql.merchantDay(ChannelSql.normalize("'; DROP TABLE x; --")));
    }
}
