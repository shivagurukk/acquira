package com.acquira.pdf.job;

import com.acquira.common.dto.MerchantInsightsDTO;
import com.acquira.common.model.Merchant;
import com.acquira.common.repository.MerchantRepository;
import com.acquira.common.service.MerchantInsightService;
import com.acquira.pdf.service.PlaywrightPdfService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.Tenant;
import com.acquira.common.repository.TenantRepository;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spring Batch job for scheduled merchant PDF report generation.
 *
 * PERFORMANCE FIX (the reason a 10k-merchant run sat on one SELECT for an hour):
 *   The previous version used an ItemProcessor that called
 *   insightService.getInsights(merchantId) ONE MERCHANT AT A TIME. Each call
 *   fired ~7 data queries + 2 PK lookups, so 10,000 merchants = ~90,000
 *   sequential round-trips. On large summary tables (sum_daily_merchant_attribute,
 *   sum_monthly_card) that is what made "the select" appear to run forever.
 *
 *   This version reads merchants in chunks and uses the existing BULK API
 *   {@link MerchantInsightService#getBulkInsights(List, int, int)} which fetches
 *   data for the whole chunk in ~6 queries and partitions in memory. For a chunk
 *   of {@value #CHUNK_SIZE} that is ~6 queries per chunk instead of ~700 — two to
 *   three orders of magnitude fewer DB round-trips.
 *
 * Flow:
 *  1. Read merchants from DB, CHUNK_SIZE at a time.
 *  2. For each chunk: group by tenant, BULK-fetch insights, render each PDF,
 *     write it to reports/{bankShortCode}/{YYYY-MM}/.
 *
 * NOTE: PDF rendering itself (Playwright/Chromium, pool size from
 * pdf.pool.size) is the next bottleneck after this fix — rendering 10k PDFs
 * through a 2-browser pool is inherently slow. That is a separate, CPU/browser
 * bound concern; this change only removes the database explosion.
 */
@Configuration
@EnableBatchProcessing
@RequiredArgsConstructor
@Slf4j
public class MerchantReportJobConfig {

    /**
     * Merchants processed per chunk. Each chunk triggers ONE bulk insight fetch
     * (per tenant within the chunk). Bigger = fewer DB round-trips but more memory
     * held transiently (daily + attribute + card rows for the whole chunk) and a
     * longer-lived chunk transaction while its PDFs render. 100 is a safe balance;
     * tune down if memory-constrained, up if the box is roomy and rendering is fast.
     */
    private static final int CHUNK_SIZE = 100;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final MerchantRepository merchantRepository;
    private final MerchantInsightService insightService;
    private final PlaywrightPdfService playwrightPdfService;
    private final TenantRepository tenantRepository;

    @org.springframework.beans.factory.annotation.Value("${pdf.reports.dir:reports}")
    private String reportsBaseDir;

    /**
     * PDF generate flag check. dim_merchant.generate_report_flag = 1 -> generate,
     * anything else (0 / null) -> skip. Set a merchant's flag to 0 to exclude it.
     */
    private boolean isFlagOk(Merchant m) {
        return m.getGenerateReportFlag() != null && m.getGenerateReportFlag() == 1;
    }

    @Bean
    public Job merchantReportJob() {
        return new JobBuilder("merchantReportJob", jobRepository)
                .start(reportGenerationStep())
                .build();
    }

    @Bean
    public Step reportGenerationStep() {
        // Chunk-oriented step with NO per-item processor. All work (bulk fetch +
        // render + write) happens in the writer, which receives the whole chunk
        // so it can fetch insights for many merchants in one shot.
        return new StepBuilder("reportGenerationStep", jobRepository)
                .<Merchant, Merchant>chunk(CHUNK_SIZE, transactionManager)
                .reader(merchantReader())
                .writer(reportWriter())
                .build();
    }

    @Bean
    public RepositoryItemReader<Merchant> merchantReader() {
        return new RepositoryItemReaderBuilder<Merchant>()
                .name("merchantReader")
                .repository(merchantRepository)
                .methodName("findAll")
                .sorts(Collections.singletonMap("merchantId", Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    /**
     * Bulk writer: receives a chunk of merchants, groups them by tenant, and for
     * each tenant group does ONE bulk insight fetch, then renders + writes each PDF.
     */
    @Bean
    public ItemWriter<Merchant> reportWriter() {
        return chunk -> {
            YearMonth target = YearMonth.now().minusMonths(1);

            // Group the chunk by tenant. findAll() is ordered by merchantId, so a
            // single chunk can span multiple tenants — bulk-fetch per tenant.
            Map<Long, List<Merchant>> byTenant = new LinkedHashMap<>();
            for (Merchant m : chunk.getItems()) {
                byTenant.computeIfAbsent(m.getTenantId(), k -> new ArrayList<>()).add(m);
            }

            for (Map.Entry<Long, List<Merchant>> entry : byTenant.entrySet()) {
                Long tenantId = entry.getKey();
                List<Merchant> allInTenant = entry.getValue();

                // Flag check: only generate for merchants whose generate_report_flag = 1.
                // Filtered out merchants are never fetched or rendered.
                List<Merchant> merchants = allInTenant.stream()
                        .filter(this::isFlagOk)
                        .collect(Collectors.toList());
                int skipped = allInTenant.size() - merchants.size();
                if (skipped > 0) {
                    log.info("[JOB] Skipped {} merchant(s) in tenant {} with generate_report_flag != 1",
                            skipped, tenantId);
                }
                if (merchants.isEmpty()) {
                    continue; // nothing flagged-OK in this tenant's slice of the chunk
                }
                try {
                    if (tenantId != null) {
                        TenantContext.setCurrentTenant(tenantId);
                    }

                    List<Long> merchantIds = merchants.stream()
                            .map(Merchant::getMerchantId)
                            .collect(Collectors.toList());

                    long t0 = System.currentTimeMillis();
                    Map<Long, MerchantInsightsDTO> insightsById =
                            insightService.getBulkInsights(merchantIds, target.getYear(), target.getMonthValue());
                    log.info("[JOB] Bulk insights fetched for {} merchants (tenant:{}) in {}ms",
                            merchantIds.size(), tenantId, System.currentTimeMillis() - t0);

                    String bankCode = resolveBankCode(tenantId);

                    for (Merchant merchant : merchants) {
                        MerchantInsightsDTO insights = insightsById.get(merchant.getMerchantId());
                        if (insights == null) {
                            log.warn("[JOB] No insights for merchant {} (tenant:{}) — skipping",
                                    merchant.getMerchantId(), tenantId);
                            continue;
                        }
                        try {
                            byte[] pdfBytes = playwrightPdfService.generatePdf(
                                    insights, merchant.getName(), target.toString());
                            writeReport(bankCode, target, merchant, pdfBytes, tenantId);
                        } catch (Exception e) {
                            log.error("[JOB] Failed to render/save report for merchant {} (tenant:{})",
                                    merchant.getMerchantId(), tenantId, e);
                        }
                    }
                } finally {
                    TenantContext.clear();
                }
            }
        };
    }

    /**
     * Write one merchant PDF to reports/{bankShortCode}/{YYYY-MM}/.
     *
     * NOTE: S3 upload is intentionally NOT done here. S3 archiving happens in
     * PdfController after each email is sent successfully, so the local file is
     * always available for the email attachment and upload is tied to email
     * confirmation, not just generation.
     */
    private void writeReport(String bankCode, YearMonth target, Merchant merchant,
                             byte[] pdfBytes, Long tenantId) throws java.io.IOException {
        // Never write into the shared reports/{YYYY-MM} root: it mixes tenants
        // (same-named merchants overwrite each other) and download endpoints must
        // not serve it. Fall back to a tenant-discriminated folder instead.
        String folderCode = (bankCode != null && !bankCode.isBlank())
                ? bankCode
                : (tenantId != null ? "tenant-" + tenantId : null);
        if (folderCode == null) {
            log.warn("[JOB] Skipping report for merchant {} — no bank code and no tenant id, "
                    + "refusing to write into the shared reports folder", merchant.getMerchantId());
            return;
        }
        Path folder = Paths.get(reportsBaseDir).resolve(folderCode).resolve(target.toString());
        Files.createDirectories(folder);

        String rawName = merchant.getName() != null ? merchant.getName() : merchant.getMid();
        String safeName = (rawName != null ? rawName : "merchant_" + merchant.getMerchantId())
                .replaceAll("[^a-zA-Z0-9.\\-]", "_");

        String filename = "Insight_" + safeName + "_" + target + ".pdf";
        Path filePath = folder.resolve(filename);

        try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
            fos.write(pdfBytes);
        }
        log.info("[JOB] Saved report: {} ({} KB, tenant:{})",
                filePath, pdfBytes.length / 1024, tenantId);
    }

    private String resolveBankCode(Long tenantId) {
        if (tenantId == null) return null;
        try {
            return tenantRepository.findById(tenantId)
                .map(Tenant::getBankShortCode)
                .orElse(null);
        } catch (Exception e) {
            log.debug("[JOB] Could not resolve bankCode for tenant {}: {}", tenantId, e.getMessage());
            return null;
        }
    }
}
