package com.acquira.batch;

import com.acquira.dto.MerchantInsightsDTO;
import com.acquira.model.Merchant;
import com.acquira.repository.MerchantRepository;
import com.acquira.service.MerchantInsightService;
import com.acquira.service.PdfGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.Map;

@Configuration
@EnableBatchProcessing
@RequiredArgsConstructor
@Slf4j
public class MerchantReportJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final MerchantRepository merchantRepository;
    private final MerchantInsightService insightService;
    private final PdfGenerationService pdfService;

    @Bean
    public Job merchantReportJob() {
        return new JobBuilder("merchantReportJob", jobRepository)
                .start(reportGenerationStep())
                .build();
    }

    @Bean
    public Step reportGenerationStep() {
        return new StepBuilder("reportGenerationStep", jobRepository)
                .<Merchant, ReportData>chunk(10, transactionManager)
                .reader(merchantReader())
                .processor(merchantProcessor())
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
                .pageSize(10)
                .build();
    }

    @Bean
    public ItemProcessor<Merchant, ReportData> merchantProcessor() {
        return merchant -> {
            try {
                // Target: Previous Month
                YearMonth target = YearMonth.now().minusMonths(1);

                log.info("Generating report for Merchant: {} Month: {}", merchant.getName(), target);

                MerchantInsightsDTO insights = insightService.getInsights(merchant.getMerchantId(), target.getYear(),
                        target.getMonthValue());
                byte[] pdfBytes = pdfService.generateMerchantInsightPdf(insights, merchant.getName(),
                        target.toString(), String.valueOf(merchant.getMerchantId()));

                return new ReportData(merchant, pdfBytes, target);
            } catch (Exception e) {
                log.error("Failed to generate report for merchant {}", merchant.getMerchantId(), e);
                return null; // Skip this item
            }
        };
    }

    @Bean
    public ItemWriter<ReportData> reportWriter() {
        return items -> {
            for (ReportData item : items) {
                try {
                    String folder = "reports/" + item.target.toString();
                    Files.createDirectories(Paths.get(folder));

                    String filename = folder + "/Merchant_Insight_" + item.merchant.getMid() + ".pdf";
                    try (FileOutputStream fos = new FileOutputStream(filename)) {
                        fos.write(item.pdfBytes);
                    }
                    log.info("Saved report: {}", filename);
                } catch (IOException e) {
                    log.error("Failed to save report for {}", item.merchant.getName(), e);
                }
            }
        };
    }

    // Helper Class to pass data from Processor to Writer
    record ReportData(Merchant merchant, byte[] pdfBytes, YearMonth target) {
    }
}
