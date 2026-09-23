package com.acquira.core.service;

import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.repository.VolumeRevenueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Routing contract of {@link FinanceSummaryService#getSummary}: MONTH/DAY are
 * served from the tenant-day rollup when it has rows, and fall through to the
 * pivot + fee overlay otherwise; MERCHANT never touches the rollup.
 */
class FinanceSummaryServiceTest {

    private VolumeRevenueRepository repo;
    private FinanceSummaryService service;

    private static final Long TENANT = 7L;
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 22);

    @BeforeEach
    void setUp() {
        repo = mock(VolumeRevenueRepository.class);
        service = new FinanceSummaryService(repo);
    }

    private static Map<String, Object> rollupRow(String label) {
        Map<String, Object> m = new HashMap<>();
        m.put("row_label", label);
        m.put("sort_date", label + "-01");
        m.put("total_vol", new BigDecimal("100.00"));
        m.put("total_ic", new BigDecimal("1.50"));
        m.put("fees_available", true);
        return m;
    }

    private static Map<String, Object> pivotRow(String label) {
        Map<String, Object> m = new HashMap<>();
        m.put("row_label", label);
        m.put("sort_date", label + "-01");
        m.put("total_vol", new BigDecimal("100.00"));
        m.put("merchant_name", null);
        return m;
    }

    @Test
    @DisplayName("MONTH grain is served from the rollup and never runs the detail queries")
    void monthUsesRollup() {
        when(repo.getFinanceSummaryFromRollup(START, END, "MONTH", TENANT))
                .thenReturn(new ArrayList<>(List.of(rollupRow("2026-08"), rollupRow("2026-07"))));

        List<Map<String, Object>> out = service.getSummary(TENANT, "MONTH", START, END);

        assertEquals(2, out.size());
        assertEquals("2026-08", out.get(0).get("month_label"), "frontend keys rows on month_label");
        assertEquals(new BigDecimal("1.50"), out.get(0).get("total_ic"));
        assertEquals(Boolean.TRUE, out.get(0).get("fees_available"));
        verify(repo, never()).getPerformanceDashboardData(any(), anyString(), any(), any(), any());
        verify(repo, never()).getFinanceFeeOverlay(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("Empty rollup falls through to pivot + fee overlay (un-seeded tenant stays correct)")
    void emptyRollupFallsBack() {
        when(repo.getFinanceSummaryFromRollup(START, END, "MONTH", TENANT)).thenReturn(new ArrayList<>());
        when(repo.getPerformanceDashboardData(any(VolumeRevenueFilterDTO.class), eq("MONTH"), isNull(), isNull(), eq(TENANT)))
                .thenReturn(List.of(pivotRow("2026-08")));
        Map<String, Map<String, Object>> fees = new HashMap<>();
        fees.put("2026-08", Map.of("total_ic", new BigDecimal("2.25")));
        when(repo.getFinanceFeeOverlay(START, END, "MONTH", TENANT)).thenReturn(fees);

        List<Map<String, Object>> out = service.getSummary(TENANT, "MONTH", START, END);

        assertEquals(1, out.size());
        assertEquals("2026-08", out.get(0).get("month_label"));
        assertEquals(new BigDecimal("2.25"), out.get(0).get("total_ic"));
        assertEquals(BigDecimal.ZERO, out.get(0).get("total_sf"), "missing overlay columns are zeroed");
        assertEquals(Boolean.TRUE, out.get(0).get("fees_available"));
    }

    @Test
    @DisplayName("A rollup read failure (table missing) degrades to the detail path, not a 500")
    void rollupFailureFallsBack() {
        when(repo.getFinanceSummaryFromRollup(any(), any(), anyString(), any()))
                .thenThrow(new RuntimeException("relation \"sum_daily_finance_rollup\" does not exist"));
        when(repo.getPerformanceDashboardData(any(), eq("DAY"), isNull(), isNull(), eq(TENANT)))
                .thenReturn(List.of(pivotRow("2026-08-01")));
        when(repo.getFinanceFeeOverlay(any(), any(), anyString(), any())).thenReturn(new HashMap<>());

        List<Map<String, Object>> out = service.getSummary(TENANT, "DAY", START, END);

        assertEquals(1, out.size());
        assertEquals(Boolean.FALSE, out.get(0).get("fees_available"));
    }

    @Test
    @DisplayName("MERCHANT grain never consults the rollup and keeps the name (MID) label contract")
    void merchantSkipsRollup() {
        Map<String, Object> row = pivotRow("M001");
        row.put("merchant_name", "Corner Shop");
        when(repo.getPerformanceDashboardData(any(), eq("MERCHANT"), isNull(), isNull(), eq(TENANT)))
                .thenReturn(List.of(row));
        when(repo.getFinanceFeeOverlay(any(), any(), anyString(), any())).thenReturn(new HashMap<>());

        List<Map<String, Object>> out = service.getSummary(TENANT, "MERCHANT", END, END);

        verify(repo, never()).getFinanceSummaryFromRollup(any(), any(), anyString(), any());
        assertEquals("Corner Shop (M001)", out.get(0).get("month_label"));
        assertEquals("M001", out.get(0).get("merchant_id"));
    }
}
