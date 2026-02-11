package com.acquira.core.controller;

import com.acquira.common.dto.MerchantInsightsDTO;
import com.acquira.common.service.MerchantInsightService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;

/**
 * Core data endpoint for merchant insights.
 * PDF generation endpoints are in acquira-pdf module (PdfController).
 */
@RestController
@RequestMapping("/api/business/insights")
public class MerchantInsightController {

    private final MerchantInsightService insightService;

    public MerchantInsightController(MerchantInsightService insightService) {
        this.insightService = insightService;
    }

    @GetMapping("/overview")
    public ResponseEntity<MerchantInsightsDTO> getInsights(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        if (merchantId == null) merchantId = 1L;
        YearMonth targetMonth = resolveTargetMonth(year, month);
        return ResponseEntity.ok(insightService.getInsights(merchantId, targetMonth.getYear(), targetMonth.getMonthValue()));
    }

    private YearMonth resolveTargetMonth(Integer year, Integer month) {
        if (year != null && month != null) return YearMonth.of(year, month);
        return YearMonth.now().minusMonths(1);
    }
}