package com.acquira.core.service;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.model.TenantSetting;
import com.acquira.common.repository.PricingSegmentMatrixRepository;
import com.acquira.common.repository.TenantSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pure-math verification of PricingSimulatorService.segmentMatrix over a
 * stubbed repository: bps derivation, the card-type coverage split
 * ("has card type or not"), below-cost flagging, default window resolution
 * and the tenant enable flag semantics (only an explicit 'false' disables).
 */
class PricingSimulatorServiceTest {

    private PricingSegmentMatrixRepository matrixRepo;
    private TenantSettingRepository settingRepo;
    private PricingSimulatorService service;

    private static Map<String, Object> seg(String scheme, String ct, String dest,
                                           long txns, String vol, String msf,
                                           String ic, String sf, String ef, String net, long merch) {
        Map<String, Object> m = new HashMap<>();
        m.put("scheme", scheme);
        m.put("cardType", ct);
        m.put("destination", dest);
        m.put("txns", txns);
        m.put("volume", new BigDecimal(vol));
        m.put("msf", new BigDecimal(msf));
        m.put("interchange", new BigDecimal(ic));
        m.put("schemeFee", new BigDecimal(sf));
        m.put("ecomFee", new BigDecimal(ef));
        m.put("netRevenue", new BigDecimal(net));
        m.put("merchants", merch);
        return m;
    }

    @BeforeEach
    void setUp() {
        matrixRepo = Mockito.mock(PricingSegmentMatrixRepository.class);
        settingRepo = Mockito.mock(TenantSettingRepository.class);
        service = new PricingSimulatorService(matrixRepo, settingRepo);

        Map<String, Object> bounds = new HashMap<>();
        bounds.put("earliest", "2026-01-01");
        bounds.put("latest", "2026-08-26");
        when(matrixRepo.getBounds(anyLong())).thenReturn(bounds);
    }

    private static TenantSetting setting(String value) {
        TenantSetting s = new TenantSetting();
        s.setKey(PricingSimulatorService.ENABLED_KEY);
        s.setValue(value);
        return s;
    }

    // ── enable flag ─────────────────────────────────────────────────────

    @Test
    void enabledWhenFlagAbsent() {
        when(settingRepo.findByTenant_TenantIdAndKey(8L, PricingSimulatorService.ENABLED_KEY))
                .thenReturn(Optional.empty());
        assertTrue(service.isEnabled(8L));
    }

    @Test
    void disabledOnlyOnExplicitFalse() {
        when(settingRepo.findByTenant_TenantIdAndKey(8L, PricingSimulatorService.ENABLED_KEY))
                .thenReturn(Optional.of(setting("false")));
        assertFalse(service.isEnabled(8L));

        when(settingRepo.findByTenant_TenantIdAndKey(8L, PricingSimulatorService.ENABLED_KEY))
                .thenReturn(Optional.of(setting("true")));
        assertTrue(service.isEnabled(8L));

        // junk value must not disable
        when(settingRepo.findByTenant_TenantIdAndKey(8L, PricingSimulatorService.ENABLED_KEY))
                .thenReturn(Optional.of(setting("banana")));
        assertTrue(service.isEnabled(8L));
    }

    @Test
    void nullTenantIsDisabled() {
        assertFalse(service.isEnabled(null));
    }

    // ── matrix math ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void ratesCoverageAndFlags() {
        List<Map<String, Object>> segs = new ArrayList<>();
        // VISA CREDIT INTL: below cost (msf 100 on 10000 vol = 100bps; net -50)
        segs.add(seg("VISA", "CREDIT", "INTERNATIONAL", 10, "10000", "100", "120", "25", "5", "-50", 3));
        // VISA CREDIT DOM: healthy 60bps net
        segs.add(seg("VISA", "CREDIT", "DOMESTIC", 20, "20000", "300", "150", "25", "5", "120", 5));
        // VISA untyped volume — must land in coverage as unknown
        segs.add(seg("VISA", "UNSPECIFIED", "DOMESTIC", 5, "10000", "150", "80", "10", "0", "60", 2));
        // MC DEBIT DOM
        segs.add(seg("MASTERCARD", "DEBIT", "DOMESTIC", 8, "40000", "800", "500", "60", "20", "220", 4));

        when(matrixRepo.getSegmentMatrix(any(), eq(8L))).thenReturn(segs);

        VolumeRevenueFilterDTO f = new VolumeRevenueFilterDTO();
        Map<String, Object> out = service.segmentMatrix(f, 8L);

        // default window: 3 months back from the summary's own latest date
        assertEquals("2026-08-26", out.get("windowEnd"));
        assertEquals(LocalDate.parse("2026-08-26").minusMonths(3).plusDays(1).toString(), out.get("windowStart"));

        List<Map<String, Object>> rs = (List<Map<String, Object>>) out.get("segments");
        Map<String, Object> visaIntl = rs.stream()
                .filter(s -> "VISA".equals(s.get("scheme")) && "INTERNATIONAL".equals(s.get("destination")))
                .findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("100.0").compareTo((BigDecimal) visaIntl.get("msfBps")));
        assertEquals(0, new BigDecimal("150.0").compareTo((BigDecimal) visaIntl.get("costBps"))); // 120+25+5 on 10000
        assertEquals(0, new BigDecimal("-50.0").compareTo((BigDecimal) visaIntl.get("netBps")));
        assertEquals(Boolean.TRUE, visaIntl.get("belowCost"));
        assertEquals(Boolean.TRUE, visaIntl.get("hasCardType"));

        // untyped segment recognised
        Map<String, Object> untyped = rs.stream()
                .filter(s -> "UNSPECIFIED".equals(s.get("cardType"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, untyped.get("hasCardType"));

        // VISA coverage: 10000 untyped of 40000 = 25% unknown → lowCoverage
        List<Map<String, Object>> cov = (List<Map<String, Object>>) out.get("schemeCoverage");
        Map<String, Object> visaCov = cov.stream()
                .filter(c -> "VISA".equals(c.get("scheme"))).findFirst().orElseThrow();
        assertEquals(25.0, ((Number) visaCov.get("unknownSharePct")).doubleValue(), 0.01);
        assertEquals(Boolean.TRUE, visaCov.get("lowCoverage"));

        Map<String, Object> mcCov = cov.stream()
                .filter(c -> "MASTERCARD".equals(c.get("scheme"))).findFirst().orElseThrow();
        assertEquals(0.0, ((Number) mcCov.get("unknownSharePct")).doubleValue(), 0.01);
        assertEquals(Boolean.FALSE, mcCov.get("lowCoverage"));

        // portfolio coverage: typed 70000 of 80000 = 87.5%
        assertEquals(87.5, ((Number) out.get("cardTypeCoveragePct")).doubleValue(), 0.01);

        // totals reconcile: vol 80000, msf 1350, net -50+120+60+220=350
        Map<String, Object> totals = (Map<String, Object>) out.get("totals");
        assertEquals(0, new BigDecimal("80000").compareTo((BigDecimal) totals.get("volume")));
        assertEquals(0, new BigDecimal("350").compareTo((BigDecimal) totals.get("netRevenue")));

        // median net bps over TYPED segments only: -50, 60, 55 → sorted -50,55,60 → median 55
        assertEquals(0, new BigDecimal("55.0").compareTo((BigDecimal) out.get("medianNetBps")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void merchantMatrixDerivesRatesAndTotals() {
        List<Map<String, Object>> segs = new ArrayList<>();
        segs.add(seg("VISA", "CREDIT", "DOMESTIC", 10, "10000", "200", "120", "20", "10", "50", 1));
        segs.add(seg("VISA", "CREDIT", "INTERNATIONAL", 5, "5000", "80", "90", "15", "5", "-30", 1));
        when(matrixRepo.getMerchantSegmentMatrix(any(), eq(8L), eq("M1"))).thenReturn(segs);

        Map<String, Object> out = service.merchantMatrix(new VolumeRevenueFilterDTO(), 8L, "M1");
        assertEquals("M1", out.get("mid"));

        List<Map<String, Object>> rs = (List<Map<String, Object>>) out.get("segments");
        Map<String, Object> intl = rs.stream()
                .filter(s -> "INTERNATIONAL".equals(s.get("destination"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("160.0").compareTo((BigDecimal) intl.get("msfBps"))); // 80/5000
        assertEquals(0, new BigDecimal("220.0").compareTo((BigDecimal) intl.get("costBps"))); // 110/5000
        assertEquals(Boolean.TRUE, intl.get("belowCost"));

        Map<String, Object> totals = (Map<String, Object>) out.get("totals");
        assertEquals(0, new BigDecimal("15000").compareTo((BigDecimal) totals.get("volume")));
        // blended net: (50-30)/15000 = 13.3 bps
        assertEquals(0, new BigDecimal("13.3").compareTo((BigDecimal) totals.get("netBps")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void merchantDrillDerivesRates() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> r = new HashMap<>();
        r.put("mid", "M1"); r.put("name", "Shop");
        r.put("txns", 5L);
        r.put("volume", new BigDecimal("10000"));
        r.put("msf", new BigDecimal("150"));
        r.put("cost", new BigDecimal("100"));
        r.put("netRevenue", new BigDecimal("50"));
        rows.add(r);
        when(matrixRepo.getSegmentMerchants(any(), eq(8L), eq("VISA"), eq("CREDIT"), eq("DOMESTIC"), eq(20)))
                .thenReturn(rows);

        Map<String, Object> out = service.segmentMerchants(
                new VolumeRevenueFilterDTO(), 8L, "VISA", "CREDIT", "DOMESTIC", 20);
        List<Map<String, Object>> ms = (List<Map<String, Object>>) out.get("merchants");
        assertEquals(0, new BigDecimal("150.0").compareTo((BigDecimal) ms.get(0).get("msfBps")));
        assertEquals(0, new BigDecimal("50.0").compareTo((BigDecimal) ms.get(0).get("netBps")));
    }
}
