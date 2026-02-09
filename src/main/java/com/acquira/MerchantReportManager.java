package com.acquira;

import com.acquira.dto.MerchantInsightsDTO;
import com.acquira.model.Merchant;
import com.acquira.repository.MerchantRepository;
import com.acquira.service.MerchantInsightService;
import com.acquira.service.PlaywrightPdfService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Scanner;

@Component
@RequiredArgsConstructor
@Slf4j
@Profile("report-cli") // Use this profile to run this CLI
public class MerchantReportManager implements CommandLineRunner {

    private final MerchantRepository merchantRepository;
    private final MerchantInsightService merchantInsightService;
    private final PlaywrightPdfService playwrightPdfService;

    @Override
    public void run(String... args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        String reportsDir = "reports";
        Files.createDirectories(Paths.get(reportsDir));

        System.out.println("=========================================");
        System.out.println("   MERCHANT REPORT MANAGER (PDF 2.0)   ");
        System.out.println("=========================================");

        // AUTO RUN MODE from Command Line Logic
        if (args.length > 0 && "auto".equalsIgnoreCase(args[0])) {
            System.out.println("AUTO MODE DETECTED. Starting Sequence...");
            try {
                installPlaywright();
                generateAllReports(reportsDir);
            } catch (Exception e) {
                e.printStackTrace();
            }
            System.out.println("Auto Sequence Complete. Exiting.");
            System.exit(0);
        }

        while (true) {
            System.out.println("\nMENU:");
            System.out.println("1. Generate Report for Single Merchant");
            System.out.println("2. Generate All Reports");
            System.out.println("3. Install Playwright Browsers");
            System.out.println("4. Exit");
            System.out.print("Enter choice: ");

            String choice = scanner.nextLine();

            switch (choice) {
                case "1":
                    generateSingleReport(scanner, reportsDir);
                    break;
                case "2":
                    generateAllReports(reportsDir);
                    break;
                case "3":
                    installPlaywright();
                    break;
                case "4":
                    System.out.println("Exiting...");
                    System.exit(0);
                default:
                    System.out.println("Invalid choice.");
            }
        }
    }

    private void generateSingleReport(Scanner scanner, String listDir) {
        System.out.print("Enter Merchant MID: ");
        String mid = scanner.nextLine();

        System.out.print("Enter Month (YYYY-MM) [Press Enter for Last Month]: ");
        String monthStr = scanner.nextLine();
        if (monthStr.trim().isEmpty()) {
            monthStr = YearMonth.now().minusMonths(1).toString();
        }

        try {
            Merchant merchant = merchantRepository.findByMid(mid)
                    .orElseThrow(() -> new RuntimeException("Merchant not found with MID: " + mid));

            YearMonth ym = YearMonth.parse(monthStr);
            System.out.println("Fetching data for " + merchant.getName() + " (" + mid + ") for " + monthStr + "...");

            MerchantInsightsDTO data = merchantInsightService.getInsights(merchant.getMerchantId(), ym.getYear(),
                    ym.getMonthValue());

            System.out.println("Generating PDF...");
            byte[] pdfBytes = playwrightPdfService.generatePdf(data, merchant.getName(), monthStr);

            String filename = String.format("%s_%s_Report_%s.pdf",
                    merchant.getName().replaceAll("[^a-zA-Z0-9.-]", "_"),
                    mid,
                    monthStr);
            Path path = Paths.get(listDir, filename);
            Files.write(path, pdfBytes);

            System.out.println("SUCCESS: Report saved to " + path.toAbsolutePath());

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void generateAllReports(String baseDir) {
        System.out.println("Fetch all merchants...");
        List<Merchant> merchants = merchantRepository.findAll();
        System.out.println("Found " + merchants.size() + " merchants.");

        // Default to last month
        YearMonth ym = YearMonth.now().minusMonths(1);
        String monthStr = ym.toString();

        Path monthDir = Paths.get(baseDir, monthStr);
        try {
            Files.createDirectories(monthDir);
            System.out.println("Created directory: " + monthDir.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("Failed to create directory: " + e.getMessage());
            return;
        }

        System.out.println("Generating reports for period: " + monthStr);

        java.util.concurrent.atomic.AtomicInteger success = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger(0);
        int total = merchants.size();
        long startTime = System.currentTimeMillis();

        // Parallel processing with 4 threads (matches browser pool size)
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        for (Merchant m : merchants) {
            final String ms = monthStr;
            futures.add(executor.submit(() -> {
                try {
                    MerchantInsightsDTO data = merchantInsightService.getInsights(m.getMerchantId(), ym.getYear(),
                            ym.getMonthValue());
                    byte[] pdfBytes = playwrightPdfService.generatePdf(data, m.getName(), ms);

                    String filename = String.format("%s_%s_Report_%s.pdf",
                            m.getName().replaceAll("[^a-zA-Z0-9.-]", "_"),
                            m.getMid(),
                            ms);
                    Path path = monthDir.resolve(filename);
                    Files.write(path, pdfBytes);
                    int done = success.incrementAndGet();
                    if (done % 100 == 0) {
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        double rate = done / Math.max(elapsed, 1.0);
                        System.out.printf("Progress: %d/%d (%.1f/sec, %ds elapsed)%n", done, total, rate, elapsed);
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    System.err.println("FAILED " + m.getMid() + ": " + e.getMessage());
                }
            }));
        }

        // Wait for all to complete
        for (java.util.concurrent.Future<?> f : futures) {
            try { f.get(); } catch (Exception e) { /* already counted */ }
        }
        executor.shutdown();

        long totalTime = (System.currentTimeMillis() - startTime) / 1000;
        System.out.printf("%nBATCH COMPLETE. Success: %d, Failed: %d, Time: %ds (%.1f reports/sec)%n",
                success.get(), failed.get(), totalTime, success.get() / Math.max(totalTime, 1.0));
        System.out.println("Reports saved to: " + monthDir.toAbsolutePath());
    }

    private void installPlaywright() {
        System.out.println("Installing Playwright browsers...");
        try {
            com.microsoft.playwright.CLI.main(new String[] { "install" });
            System.out.println("Installation Complete.");
        } catch (Exception e) {
            System.err.println("Installation Failed: " + e.getMessage());
        }
    }
}
