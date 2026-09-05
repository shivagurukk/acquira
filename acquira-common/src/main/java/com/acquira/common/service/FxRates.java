package com.acquira.common.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Server-side twin of frontend/src/config/fxRates.js — USD per 1 unit of local
 * currency, used by the daily digest email so its converted figures match the
 * executive screens' USD toggle exactly. If one side changes, change both
 * (same contract as WorkingWeekResolver / weekRules.js).
 *
 * The Gulf currencies are hard-pegged to the dollar, so hardcoding is a
 * product decision, not laziness; EGP floats and its entry is an indicative
 * snapshot stamped with {@link #AS_OF}.
 */
public final class FxRates {

    private FxRates() {}

    /** Stamp shown next to converted figures so nobody mistakes an indicative
     *  conversion for a booked rate. */
    public static final String AS_OF = "2026-08-28";

    private static final Map<String, BigDecimal> USD_PER_UNIT = Map.of(
            "BHD", new BigDecimal("2.65252"),
            "AED", new BigDecimal("0.27229"),
            "OMR", new BigDecimal("2.60078"),
            "SAR", new BigDecimal("0.26667"),
            "QAR", new BigDecimal("0.27473"),
            "KWD", new BigDecimal("3.25733"),   // managed basket, near-peg — indicative
            "EGP", new BigDecimal("0.02070"));  // FLOATING — indicative snapshot

    /** USD-per-unit rate, or null when unknown / already USD ("do not convert"). */
    public static BigDecimal usdPerUnit(String currencyCode) {
        if (currencyCode == null) return null;
        String code = currencyCode.trim().toUpperCase();
        if ("USD".equals(code)) return null;
        return USD_PER_UNIT.get(code);
    }

    /** Convert to USD at 2dp, or null when no rate is known. */
    public static BigDecimal toUsd(BigDecimal amount, String currencyCode) {
        BigDecimal rate = usdPerUnit(currencyCode);
        if (rate == null || amount == null) return null;
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
