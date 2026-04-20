package com.acquira.pdf.job;

import com.acquira.common.dto.MerchantInsightsDTO;
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
import org.springframework.batch.item.ItemProcessor;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;

/**
 * Spring Batch job for scheduled merchant PDF report generation.
 *
 * Flow:
 *  1. Read all merchants from DB (one page at a time)
 *  2. For each merchant: fetch insights → generate PDF bytes
 *  3. Write PDF to local disk under reports/{bankShortCode}/{YYYY-MM}/
 *
 * S3 archiving (if enabled per tenant) happens AFTER email sending,
 * not here — see PdfController.generateAllReports() email thread.
 * This job only writes locally; S3 upload is a post-email concern.
 */
@Configuration
@EnableBatchProcessing
@RequiredArgsConstructor
@Slf4j
public class MerchantReportJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final MerchantRepository merchantRepository;
    private final MerchantInsightService insightService;
    private final PlaywrightPdfService playwrightPdfService;
    private final TenantRepository tenantRepository;

    @org.springframework.beans.factory.annotation.Value("${pdf.reports.dir:reports}")
    private String reportsBaseDir;

    @Bean
    public Job merchantReportJob() {
        return new JobBuilder("merchantReportJob", jobRepository)
                .start(reportGenerationStep())
                .build();
    }

    @Bean
    public Step reportGenerationStep() {
        return new StepBuilder("reportGenerationStep", jobRepository)
                .<com.acquira.common.model.Merchant, ReportData>chunk(10, transactionManager)
                .reader(merchantReader())
                .processor(merchantProcessor())
                .writer(reportWriter())
                .build();
    }

    @Bean
    public RepositoryItemReader<com.acquira.common.model.Merchant> merchantReader() {
        return new RepositoryItemReaderBuilder<com.acquira.common.model.Merchant>()
                .name("merchantReader")
                .repository(merchantRepository)
                .methodName("findAll")
                .sorts(Collections.singletonMap("merchantId", Sort.Direction.ASC))
                .pageSize(10)
                .build();
    }

    @Bean
    public ItemProcessor<com.acquira.common.model.Merchant, ReportData> merchantProcessor() {
        return merchant -> {
            try {
                YearMonth target = YearMonth.now().minusMonths(1);
                Long tenantId = merchant.getTenantId();
                if (tenantId != null) {
                    TenantContext.setCurrentTenant(tenantId);
                }
                log.info("[JOB] Generating report for Merchant: {} (tenant:{}) Month: {}",
                        merchant.getName(), tenantId, target);

                MerchantInsightsDTO insights = insightService.getInsights(
                        merchant.getMerchantId(), target.getYear(), target.getMonthValue());

                byte[] pdfBytes = playwrightPdfService.generatePdf(
                        insights, merchant.getName(), target.toString());

                return new ReportData(merchant, pdfBytes, target, tenantId);
            } catch (Exception e) {
                log.error("[JOB] Failed to generate report for merchant {} (tenant:{})",
                        merchant.getMerchantId(), merchant.getTenantId(), e);
                return null; // null items are skipped by Spring Batch writer
            } finally {
                TenantContext.clear();
            }
        };
    }

    @Bean
    public ItemWriter<ReportData> reportWriter() {
        return items -> {
            for (ReportData item : items) {
                if (item == null) continue;
                try {
                    // Tenant-aware folder: reports/{bankShortCode}/{YYYY-MM}/
                    String bankCode = resolveBankCode(item.tenantId());
                    Path folder = bankCode != null
                        ? Paths.get(reportsBaseDir).resolve(bankCode).resolve(item.target().toString())
                        : Paths.get(reportsBaseDir).resolve(item.target().toString());

                    Files.createDirectories(folder);

                    String rawName = item.merchant().getName() != null
                            ? item.merchant().getName()
                            : item.merchant().getMid();
                    String safeName = (rawName != null ? rawName : "merchant_" + item.merchant().getMerchantId())
                            .replaceAll("[^a-zA-Z0-9.\\-]", "_");

                    String filename = "Insight_" + safeName + "_" + item.target() + ".pdf";
                    Path filePath = folder.resolve(filename);

                    try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                        fos.write(item.pdfBytes());
                    }

                    log.info("[JOB] Saved report: {} ({} KB, tenant:{})",
                            filePath, item.pdfBytes().length / 1024, item.tenantId());

                    // NOTE: S3 upload is intentionally NOT done here.
                    // S3 archiving happens in PdfController after each email is sent successfully.
                    // This separation ensures:
                    //   1. Local file is always available for email attachment
                    //   2. S3 upload only occurs when user has opted in (per-tenant setting)
                    //   3. Upload is tied to email confirmation, not just generation

                } catch (IOException e) {
                    log.error("[JOB] Failed to save report for {} (tenant:{})",
                            item.merchant().getName(), item.tenantId(), e);
                }
            }
        };
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

    /**
     * Immutable record holding all data needed to write one merchant PDF report.
     */
    record ReportData(
        com.acquira.common.model.Merchant merchant,
        byte[] pdfBytes,
        YearMonth target,
        Long tenantId
    ) {}
}
