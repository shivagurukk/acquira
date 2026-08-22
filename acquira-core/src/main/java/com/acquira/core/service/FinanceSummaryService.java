package com.acquira.core.service;

import com.acquira.common.config.ReportCacheConfig;
import com.acquira.common.dto.VolumeRevenueFilterDTO;
import com.acquira.common.repository.VolumeRevenueRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finance Summary report assembly (GET /api/finance/summary).
 *
 * TWO THINGS LIVE HERE, BOTH OF WHICH USED TO BE MISSING
 * ------------------------------------------------------
 * 1. THE FEE STACK. The pivot in {@link VolumeRevenueRepository} reads
 *    sum_daily_insight / sum_monthly_insight, neither of which carries
 *    interchange or scheme fee. Those come from sum_daily_full as a strictly
 *    ADDITIVE overlay merged onto the pivot rows by label — every count,
 *    volume and MSF figure the pivot produces is passed through untouched, so
 *    the report's existing numbers are bit-for-bit what they were before.
 *    A row that has no matching overlay row simply gets zeros.
 *
 * 2. CACHING. The controller called the repository straight through, so every
 *    open of the screen — and every month/day expander — re-ran the same
 *    aggregation against Postgres. The report only changes when a batch ingest
 *    lands, and {@code CacheEvictionJobListener} already clears these caches
 *    when one does, so repeat reads are served from memory instead. This is
 *    the single biggest contributor to the screen's load time: the first open
 *    of a range pays for the query, every subsequent one does not.
 *
 * CACHE SAFETY: the key includes tenantId (per the contract on
 * {@link ReportCacheConfig}). The tenant is resolved by the CALLER and passed
 * in as a parameter — never read from TenantContext inside a cached method,
 * which would let one tenant's rows be served to another under the same key.
 */
@Service
public class FinanceSummaryService {

    private final VolumeRevenueRepository volumeRevenueRepository;

    public FinanceSummaryService(VolumeRevenueRepository volumeRevenueRepository) {
        this.volumeRevenueRepository = volumeRevenueRepository;
    }

    /** Fee columns merged onto every row, zeroed when the overlay has no match. */
    private static final String[] FEE_COLS = {
            "dom_debit_ic", "dom_debit_sf", "dom_credit_ic", "dom_credit_sf",
            "int_ic", "int_sf", "total_ic", "total_sf", "fee_basis_msf"
    };

    /**
     * One grain of the report, fee-enriched and cached.
     *
     * @param groupBy MONTH (top level), DAY (month drill-down), MERCHANT (day drill-down)
     */
    @Cacheable(cacheNames = ReportCacheConfig.CACHE_REPORT_DATA,
            key = "'financeSummary:' + #tenantId + ':' + #groupBy + ':' + #start + ':' + #end",
            unless = "#result == null")
    public List<Map<String, Object>> getSummary(Long tenantId, String groupBy, LocalDate start, LocalDate end) {
        VolumeRevenueFilterDTO filter = new VolumeRevenueFilterDTO();
        filter.setStartDate(start);
        filter.setEndDate(end);

        List<Map<String, Object>> rawData = volumeRevenueRepository.getPerformanceDashboardData(
                filter, groupBy, null, null, tenantId);

        // Overlay failures must never take the whole report down with them: the
        // fee columns are an addition to a screen that worked without them, and
        // sum_daily_full may legitimately be empty on a tenant that has not been
        // rebuilt yet. Degrade to zeroed fees rather than a 500.
        Map<String, Map<String, Object>> fees;
        try {
            fees = volumeRevenueRepository.getFinanceFeeOverlay(start, end, groupBy, tenantId);
        } catch (RuntimeException e) {
            fees = new HashMap<>();
        }

        List<Map<String, Object>> result = new ArrayList<>(rawData.size());
        for (Map<String, Object> row : rawData) {
            Map<String, Object> mapped = new HashMap<>(row);
            Object label = row.get("row_label");
            Map<String, Object> fee = label == null ? null : fees.get(label.toString());
            for (String col : FEE_COLS) {
                Object v = fee == null ? null : fee.get(col);
                mapped.put(col, v == null ? java.math.BigDecimal.ZERO : v);
            }
            // True when the overlay actually had a row for this label. The UI
            // uses it to distinguish "this period has no fees" from "fee data
            // has not been built for this period", which look identical
            // otherwise and read as a broken report.
            mapped.put("fees_available", fee != null);

            // Existing frontend contract: rows are keyed on month_label at every
            // grain, and MERCHANT rows carry a human name plus the raw MID.
            mapped.put("month_label", row.get("row_label"));
            if ("MERCHANT".equals(groupBy)) {
                String name = row.get("merchant_name") != null ? row.get("merchant_name").toString() : "";
                String mid = row.get("row_label") != null ? row.get("row_label").toString() : "";
                mapped.put("month_label", name.isBlank() ? mid : name + " (" + mid + ")");
                mapped.put("merchant_id", mid);
            }
            result.add(mapped);
        }
        return result;
    }
}
