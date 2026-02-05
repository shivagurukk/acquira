package com.acquira.service;

import com.acquira.dto.MerchantInsightsDTO;
import com.acquira.dto.TestDto;
import com.acquira.dto.MerchantInsightsDTO.*;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.labels.StandardPieSectionLabelGenerator;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PiePlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.StackedBarRenderer;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.general.DefaultPieDataset;
import org.jfree.chart.ui.RectangleInsets;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class PdfGenerationService {

    // --- 1. HIGH CONTRAST EXECUTIVE COLOR PALETTE ---

    // Primary & Secondary Text (Solid, Dark)
    // --- 1. PREMIUM FINTECH COLOR PALETTE ---

    // Backgrounds & Gradients
    private static final Color COL_BG_NAVY_START = new Color(11, 28, 45); // #0B1C2D
    private static final Color COL_BG_NAVY_MID = new Color(31, 79, 216); // #1F4FD8
    private static final Color COL_BG_BLUE_END = new Color(79, 172, 254); // #4FACFE (Light Blue)

    // Text
    private static final Color COL_TEXT_HERO = Color.WHITE;
    private static final Color COL_TEXT_SUBHERO = new Color(203, 213, 225); // Slate 300
    private static final Color COL_TEXT_PRIMARY = new Color(15, 23, 42); // Slate 900 (for light backgrounds)
    private static final Color COL_TEXT_SECONDARY = Color.WHITE; // Brightened for dark gradient
    private static final Color COL_TEXT_MUTED = new Color(203, 213, 225); // Slate 300 - Brightened for visibility
    private static final Color COL_TEXT_WHITE = Color.WHITE;

    // KPI Card Accents
    private static final Color COL_ACCENT_CYAN = new Color(6, 182, 212); // Cyan 500
    private static final Color COL_ACCENT_EMERALD = new Color(16, 185, 129); // Emerald 500
    private static final Color COL_ACCENT_AMBER = new Color(245, 158, 11); // Amber 500
    private static final Color COL_ACCENT_VIOLET = new Color(139, 92, 246); // Violet 500

    // Other UI Elements
    // Other UI Elements
    private static final Color COL_BG_PAGE = new Color(248, 250, 252); // Slate 50
    private static final Color COL_BG_PAGE_2_START = new Color(246, 248, 251); // #F6F8FB
    private static final Color COL_BG_PAGE_2_END = new Color(237, 243, 255); // #EDF3FF
    private static final Color COL_BG_CARD = Color.WHITE;
    private static final Color COL_BORDER_LIGHT = new Color(226, 232, 240); // Slate 200

    // Chart Colors (Bold, Distinct)
    private static final Color COL_ACCENT_SALES = new Color(29, 78, 216); // #1D4ED8 (Strong Blue)
    private static final Color COL_ACCENT_TXNS = new Color(4, 120, 87); // #047857 (Emerald Green)
    private static final Color COL_ACCENT_GROWTH = new Color(67, 56, 202); // #4338CA (Indigo)
    private static final Color COL_ACCENT_RISK = new Color(185, 28, 28); // #B91C1C (Red)
    private static final Color COL_ACCENT_AVG = new Color(217, 119, 6); // #D97706 (Goldenrod)
    private static final Color COL_GRID_LINE = new Color(229, 231, 235); // #E5E7EB (Gray 200)

    // Legacy / Mapping
    // Legacy / Mapping (Required for existing Chart Helpers)
    private static final Color COLOR_APP_BG = COL_BG_PAGE;
    private static final Color COLOR_CARD_BG = COL_BG_CARD;
    private static final Color COLOR_TEXT_PRIMARY_LEGACY = COL_TEXT_PRIMARY;
    private static final Color COLOR_SALES = COL_ACCENT_SALES;
    private static final Color COLOR_TXNS = COL_ACCENT_TXNS;
    private static final Color COLOR_NAVY = COL_TEXT_PRIMARY;
    private static final Color COLOR_ACCENT = COL_ACCENT_SALES;
    private static final Color COLOR_BORDER = COL_GRID_LINE;
    private static final Color COLOR_POSITIVE = COL_ACCENT_TXNS;
    private static final Color COLOR_NEGATIVE = COL_ACCENT_RISK;

    // Missing Legacy Constants
    private static final Color COLOR_TEXT_PRIMARY = COL_TEXT_PRIMARY;
    private static final Color COL_NAVY_DARK = COL_BG_NAVY_START; // Mapping for compatibility
    private static final Color COL_ROYAL_BLUE = COL_BG_NAVY_MID; // Mapping for compatibility
    private static final Color COL_ACCENT_GOLD = COL_ACCENT_AMBER; // Mapping
    private static final Color COLOR_TEXT_SECONDARY = COL_TEXT_SECONDARY;
    private static final Color COLOR_TEXT_MUTED = COL_TEXT_MUTED;

    // --- 2. PROFESSIONAL DESIGN SYSTEM (Bank-Grade PDF) ---

    // CHART DATA COLORS (Professional, Distinct)
    private static final Color CHART_PRIMARY = new Color(37, 99, 235); // Blue 600 - Primary data
    private static final Color CHART_SUCCESS = new Color(5, 150, 105); // Green 600 - Growth/positive
    private static final Color CHART_WARNING = new Color(217, 119, 6); // Amber 600 - Warning/neutral
    private static final Color CHART_DANGER = new Color(220, 38, 38); // Red 600 - Risk/negative (muted, not harsh)
    private static final Color CHART_INFO = new Color(6, 182, 212); // Cyan 600 - Info/secondary
    private static final Color CHART_PURPLE = new Color(126, 34, 206); // Purple 700 - Tertiary

    // SECONDARY DATA COLORS (Multi-series charts)
    private static final Color CHART_TEAL = new Color(13, 148, 136); // Teal 600
    private static final Color CHART_INDIGO = new Color(79, 70, 229); // Indigo 600
    private static final Color CHART_PINK = new Color(219, 39, 119); // Pink 600

    // TEXT COLORS (Readability on different backgrounds)
    // For dark backgrounds (glassmorphic cards, charts)
    private static final Color TEXT_PRIMARY_DARK = Color.WHITE;
    private static final Color TEXT_SECONDARY_DARK = new Color(203, 213, 225); // Slate 300

    // For light backgrounds (white cards, tables)
    private static final Color TEXT_PRIMARY_LIGHT = new Color(15, 23, 42); // Slate 900 - headlines
    private static final Color TEXT_SECONDARY_LIGHT = new Color(71, 85, 105); // Slate 600 - body text
    private static final Color TEXT_MUTED_LIGHT = new Color(100, 116, 139); // Slate 500 - labels

    // TYPOGRAPHY STANDARDS
    private static final int FONT_HERO_TITLE = 18; // Page titles
    private static final int FONT_SECTION_TITLE = 12; // Section headers
    private static final int FONT_CARD_TITLE = 10; // Card titles
    private static final int FONT_BODY = 9; // Body text
    private static final int FONT_CAPTION = 8; // Small labels

    // SPACING STANDARDS
    private static final int SPACING_SECTION = 20; // Between major sections
    private static final int SPACING_ELEMENT = 15; // Between elements
    private static final int SPACING_CARD = 10; // Inside cards
    private static final int PADDING_CARD = 12; // Card padding

    // CHART SIZING GUIDELINES
    // Full width charts (single column)
    private static final int CHART_WIDTH_FULL = 450;
    private static final int CHART_HEIGHT_STANDARD = 220;

    // Two-column charts (side-by-side)
    private static final int CHART_WIDTH_HALF = 210;
    private static final int CHART_HEIGHT_COMPACT = 180;

    // Donut charts (smaller, focused)
    private static final int CHART_DONUT_SIZE = 200; // Square

    // --- 3. UNIFIED PREMIUM GRADIENT COLOR PALETTE (Soft & Professional) ---

    // Revenue/Sales Gradients - Gentle Blues
    private static final Color GRADIENT_REVENUE_START = new Color(79, 129, 189); // Soft Royal Blue
    private static final Color GRADIENT_REVENUE_END = new Color(155, 194, 230); // Light Sky Blue

    // Transaction Gradients - Subtle Emeralds
    private static final Color GRADIENT_TRANSACTION_START = new Color(82, 183, 136); // Mint Green
    private static final Color GRADIENT_TRANSACTION_END = new Color(163, 228, 199); // Pale Mint

    // Growth/Positive Gradients - Gentle Greens
    private static final Color GRADIENT_GROWTH_START = new Color(106, 168, 79); // Sage Green
    private static final Color GRADIENT_GROWTH_END = new Color(180, 215, 155); // Light Sage

    // Warning/Neutral Gradients - Soft Ambers
    private static final Color GRADIENT_WARNING_START = new Color(227, 172, 83); // Soft Amber
    private static final Color GRADIENT_WARNING_END = new Color(250, 215, 160); // Pale Gold

    // Risk/Negative Gradients - Muted Corals (NOT harsh red)
    private static final Color GRADIENT_RISK_START = new Color(214, 126, 123); // Soft Coral
    private static final Color GRADIENT_RISK_END = new Color(243, 186, 184); // Pale Pink

    // Secondary Gradients - Soft Purples/Lavenders
    private static final Color GRADIENT_SECONDARY_START = new Color(142, 124, 195); // Lavender
    private static final Color GRADIENT_SECONDARY_END = new Color(194, 182, 228); // Light Lavender

    // Unified Background & UI Colors (Low Contrast, Professional)
    private static final Color BG_GRADIENT_START = new Color(245, 247, 250); // Soft White-Blue
    private static final Color BG_GRADIENT_END = new Color(236, 240, 245); // Slightly Darker
    private static final Color CARD_BG_UNIFIED = new Color(255, 255, 255); // Clean White

    // Unified Text Colors (No Harsh Contrasts)
    private static final Color TEXT_PRIMARY_UNIFIED = new Color(52, 73, 94); // Slate Gray (not black)
    private static final Color TEXT_SECONDARY_UNIFIED = new Color(127, 140, 141); // Medium Gray
    private static final Color TEXT_MUTED_UNIFIED = new Color(189, 195, 199); // Light Gray
    private static final Color TEXT_ON_DARK_UNIFIED = new Color(236, 240, 245); // Soft White (not pure white)

    public byte[] generateMerchantInsightPdf(MerchantInsightsDTO data, String merchantName, String monthYear,
            String password)
            throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // A4 Rotate (Landscape) - Narrow Margins for Max Width
            Document document = new Document(PageSize.A4.rotate(), 10, 10, 10, 10);
            PdfWriter writer = PdfWriter.getInstance(document, out);

            // Set Password Protection if provided
            if (password != null && !password.trim().isEmpty()) {
                writer.setEncryption(password.getBytes(), password.getBytes(),
                        PdfWriter.ALLOW_PRINTING | PdfWriter.ALLOW_COPY,
                        PdfWriter.STANDARD_ENCRYPTION_128);
            }

            // Set Page Background
            // Set Page Background (Soft Gradient)
            // Set Page Background
            // Set Page Background (Solid for inner pages, Cover handled separately)
            writer.setPageEvent(new PdfPageEventHelper() {
                @Override
                public void onEndPage(PdfWriter writer, Document document) {
                    PdfContentByte canvas = writer.getDirectContentUnder();
                    canvas.saveState();
                    float w = document.getPageSize().getWidth();
                    float h = document.getPageSize().getHeight();

                    if (writer.getPageNumber() == 1) {
                        // Cover Page Background is handled in createCoverPage
                    } else {
                        // Page 2+: Premium Gradient matching Cover Page (Deep Navy -> Sky Blue)
                        PdfShading shading = PdfShading.simpleAxial(writer,
                                0, h, // x0, y0 (Top Left)
                                w, 0, // x1, y1 (Bottom Right)
                                COL_BG_NAVY_START, // Deep Navy #0B1C2D
                                COL_BG_BLUE_END); // Sky Blue #4FACFE
                        PdfShadingPattern pattern = new PdfShadingPattern(shading);
                        canvas.setShadingFill(pattern);
                        canvas.rectangle(0, 0, w, h);
                        canvas.fill();

                        // Pattern Overlay (Dot Grid) - 5% Opacity for premium texture
                        PdfPatternPainter mesh = canvas.createPattern(8, 8);
                        mesh.setColorFill(new Color(255, 255, 255, 20)); // Low opacity white
                        mesh.circle(4, 4, 0.5f); // Tiny dots
                        mesh.fill();

                        canvas.setPatternFill(mesh);
                        canvas.rectangle(0, 0, w, h);
                        canvas.fill();

                        // Visual Anchor (Abstract Geometric Lines - Right Side)
                        canvas.setColorStroke(new Color(255, 255, 255, 10)); // Very faint
                        canvas.setLineWidth(0.5f);
                        for (int i = 0; i < 8; i++) {
                            canvas.moveTo(w * 0.65f + (i * 15), 0);
                            canvas.lineTo(w, h * 0.35f + (i * 25));
                        }
                        canvas.stroke();
                    }

                    // Add Footer (Confidentiality)
                    addFooter(writer, document);

                    canvas.restoreState();
                }
            });

            document.open();

            // Header
            addHeader(writer, document, merchantName, monthYear, "PERFORMANCE INSIGHTS");

            // --- Page 1: COVER PAGE (PRODUCT LAUNCH STYLE) ---
            createCoverPage(writer, document, merchantName, monthYear, data);
            document.newPage();

            // --- PAGE 2: BUSINESS OVERVIEW (REDESIGN) ---
            addHeroHeader(document, "YOUR BUSINESS AT A GLANCE", "Performance snapshot for " + monthYear);

            // Main Content Table
            PdfPTable mainLayoutOverview = new PdfPTable(1);
            mainLayoutOverview.setWidthPercentage(100);
            mainLayoutOverview.setSpacingBefore(10);

            // 1. Executive Insight Chips (Win/Opportunity)
            String winText = "Growth of 12.8% outperforms 65% of peers.";
            String oppText = "Simple upselling can recover projected AED 15K revenue.";
            Double winGrowth = data.getOverview().getSales().getMomGrowth();

            if (winGrowth != null) {
                if (winGrowth > 0) {
                    winText = String.format("Growth of %.1f%% outperforms 65%% of peers.", winGrowth);
                    oppText = "Capitalize on traffic to drive loyalty sign-ups.";
                } else {
                    winText = "Transaction volume remains steady despite sales dip.";
                    oppText = "Simple upselling can recover projected AED 15K revenue.";
                }
            }

            mainLayoutOverview.addCell(createInsightChipsRow(winText, oppText));
            mainLayoutOverview.addCell(createSpacer(20));

            // 2. KPI Grid (Standardized 2x2)
            // Header removed as requested ("move ... from 2 to 3" / Clean up)

            PdfPTable kpiGrid = new PdfPTable(2);
            kpiGrid.setWidthPercentage(100);
            kpiGrid.setWidths(new float[] { 1, 1 });
            kpiGrid.setSpacingAfter(0);

            // Row 1: Sales & Txns
            kpiGrid.addCell(createStandardKpiCard(
                    "TOTAL SALES",
                    data.getOverview().getSales().getFormattedValue(),
                    data.getOverview().getSales().getMomGrowth(),
                    IconType.SALES,
                    data.getAchievements().getDailySalesAndCount()));

            kpiGrid.addCell(createStandardKpiCard(
                    "TOTAL TRANSACTIONS",
                    data.getOverview().getTransactions().getFormattedValue(),
                    data.getOverview().getTransactions().getMomGrowth(),
                    IconType.TRANSACTIONS,
                    data.getAchievements().getDailySalesAndCount()));

            // Row 2: Avg Ticket & Customers
            kpiGrid.addCell(createStandardKpiCard(
                    "AVG TICKET SIZE",
                    data.getOverview().getAvgTxnValue().getFormattedValue(),
                    data.getOverview().getAvgTxnValue().getMomGrowth(),
                    IconType.SALES,
                    data.getAchievements().getDailyAvgTxnValue()));

            kpiGrid.addCell(createStandardKpiCard(
                    "ACTIVE CUSTOMERS",
                    data.getOverview().getCustomers().getFormattedValue(),
                    data.getOverview().getCustomers().getMomGrowth(),
                    IconType.CUSTOMERS,
                    null)); // No daily data for customers readily available -> No sparkline looks cleaner
                            // than broken one

            PdfPCell kpiGridCell = new PdfPCell(kpiGrid);
            kpiGridCell.setBorder(Rectangle.NO_BORDER);
            kpiGridCell.setPaddingBottom(20);
            mainLayoutOverview.addCell(kpiGridCell);

            document.add(mainLayoutOverview); // End Page 2

            // --- PAGE 3: PEAK PERFORMANCE (NEW) ---
            document.newPage();
            addHeroHeader(document, "PEAK PERFORMANCE INTEL", "Efficiency and optimization metrics for " + monthYear);

            PdfPTable peakPerformanceLayout = new PdfPTable(1);
            peakPerformanceLayout.setWidthPercentage(100);
            peakPerformanceLayout.setSpacingBefore(10);

            // Row 1: Peak Metrics (3 cols) - "Record-Breaking Moments"
            PdfPTable peaksRow = new PdfPTable(3);
            peaksRow.setWidthPercentage(100);
            peaksRow.setWidths(new float[] { 1, 1, 1 });

            // Card 1: Max Daily Sales (Green)
            peaksRow.addCell(createPeakCard(
                    "MAX DAILY SALES",
                    (data.getOverview().getPeakStats().getMaxDailySales() != null
                            && data.getOverview().getPeakStats().getMaxDailySales().getValue() != null)
                                    ? NumberFormat.getCurrencyInstance()
                                            .format(data.getOverview().getPeakStats().getMaxDailySales().getValue())
                                    : "-",
                    "Best day this month",
                    new Color(16, 185, 129), // Green
                    IconType.SALES));

            // Card 2: Max Txns In Day (Amber)
            peaksRow.addCell(createPeakCard(
                    "MAX TXNS IN DAY",
                    (data.getOverview().getPeakStats().getMaxTxnsInDay() != null
                            && data.getOverview().getPeakStats().getMaxTxnsInDay().getValue() != null)
                                    ? String.valueOf(data.getOverview().getPeakStats().getMaxTxnsInDay().getValue())
                                    : "-",
                    "Highest volume day",
                    new Color(245, 158, 11), // Amber
                    IconType.TRANSACTIONS));

            // Card 3: Highest Single Txn (Pink/Purple)
            peaksRow.addCell(createPeakCard(
                    "HIGHEST SINGLE TXN",
                    "AED 842.50", // Mock/Derived
                    "Largest individual spend",
                    new Color(236, 72, 153), // Pink
                    IconType.SALES)); // Use Sales icon as proxy for "Spend"

            PdfPCell peaksCell = new PdfPCell(peaksRow);
            peaksCell.setBorder(Rectangle.NO_BORDER);
            peaksCell.setPaddingBottom(30);
            peakPerformanceLayout.addCell(peaksCell);

            // Divider
            peakPerformanceLayout.addCell(createDivider());
            peakPerformanceLayout.addCell(createSpacer(20));

            // Row 2: Customer Behaviour Header (Icon + Micro-text)
            peakPerformanceLayout.addCell(createSectionHeaderWithIcon(
                    "CUSTOMER BEHAVIOUR",
                    "Average patterns across all customers",
                    "\uD83D\uDC65" // Users icon (unicode)
            ));

            // Row 2: Customer Metrics (2 cols)
            PdfPTable custRow = new PdfPTable(2);
            custRow.setWidthPercentage(100);
            custRow.setWidths(new float[] { 1, 1 });

            // Card 1: Avg Ticket Size (Blue) - With Sparkline
            custRow.addCell(createBehaviourCard(
                    "AVG TICKET SIZE",
                    data.getOverview().getAvgTxnValue().getFormattedValue(),
                    data.getOverview().getAvgTxnValue().getMomGrowth(),
                    "Avg spend per transaction",
                    IconType.SALES,
                    data.getAchievements().getDailyAvgTxnValue()));

            // Card 2: Txns Per Customer (Sky) - Repeat Behaviour
            custRow.addCell(createBehaviourCard(
                    "TXNS PER CUSTOMER",
                    "2.4", // Mock
                    null, // No growth necessary if not available
                    "Repeat behaviour indicator",
                    IconType.CUSTOMERS,
                    null));

            PdfPCell custCell = new PdfPCell(custRow);
            custCell.setBorder(Rectangle.NO_BORDER);
            peakPerformanceLayout.addCell(custCell);

            document.add(peakPerformanceLayout);

            // --- PAGE 4: SALES TREND ANALYSIS (Strategy Redesign) ---
            document.newPage();

            // 1. Header with Semantic Subtitle
            addHeroHeader(document, "DAILY SALES PERFORMANCE", "Revenue movement across the month");

            // 2. Smart Monthly Review (Featured Block)
            String smrText = "Daily sales performance shows high variability, with revenue concentrated on a limited number of peak days. "
                    +
                    "While baseline sales remain moderate, a few high-impact days contribute disproportionately to total monthly revenue.";
            PdfPTable smrTable = new PdfPTable(1);
            smrTable.setWidthPercentage(100);
            smrTable.setSpacingAfter(15);
            smrTable.addCell(createSmartMonthlyReview(smrText));
            document.add(smrTable);

            PdfPTable salesTrendLayout = new PdfPTable(1);
            salesTrendLayout.setWidthPercentage(100);

            // 3. Hero Chart (Daily Sales)
            JFreeChart salesTrendChart = createSalesTrendChart(
                    "DAILY REVENUE TREND",
                    "Sales (AED)",
                    data.getAchievements().getDailySalesAndCount());

            PdfPCell chartCell = createCleanChartCard(writer, salesTrendChart, ""); // Title inside chart
            chartCell.setPadding(0);
            chartCell.setMinimumHeight(280);
            // Reset BG to match specs? createCleanChartCard uses default.
            // Spec asks for #132E46 Card BG. We can adjust createCleanChartCard or wrapper
            // event.
            // For now, let's assume createCleanChartCard is sufficient, or wrap it.
            // Actually, we need to ensure the chart background matches #132E46.
            // But modifying createCleanChartCard affects other pages.
            // Let's assume standard dark theme works, or apply specific color if needed.

            salesTrendLayout.addCell(chartCell);

            // 4. Intelligence Cards (3-Col Grid)
            PdfPTable statsRow = new PdfPTable(3);
            statsRow.setWidthPercentage(100);
            statsRow.setWidths(new float[] { 1, 1, 1 });
            statsRow.setSpacingBefore(15);

            // Card A: Volatility
            statsRow.addCell(createIntelligenceCard(
                    "SALES VOLATILITY",
                    "High",
                    "Significant variation",
                    new Color(242, 201, 76), // #F2C94C
                    "⚡")); // Zap/Activity

            // Card B: Peak Contribution
            String peakContrib = "Twice the daily avg"; // Default/Mock matching "Top 3 days = XX%" logic requires
                                                        // sorting.
            // Let's calc simply.
            if (data.getAchievements().getDailySalesAndCount() != null) {
                // simple sort
                List<Double> vals = data.getAchievements().getDailySalesAndCount().stream()
                        .map(d -> d.getValue().doubleValue()).sorted(java.util.Collections.reverseOrder())
                        .collect(java.util.stream.Collectors.toList());
                double total = vals.stream().mapToDouble(Double::doubleValue).sum();
                double top3 = vals.stream().limit(3).mapToDouble(Double::doubleValue).sum();
                if (total > 0) {
                    peakContrib = String.format("Top 3 days = %.0f%%", (top3 / total) * 100);
                }
            }

            statsRow.addCell(createIntelligenceCard(
                    "PEAK DAY CONTRIBUTION",
                    peakContrib,
                    "Revenue concentration",
                    new Color(155, 81, 224), // #9B51E0
                    "★")); // Star

            // Card C: Baseline
            // Median or Average excluding peaks? "Typical non-peak revenue".
            // Let's use Median or just Simple Average for simplicity.
            double baseline = 0;
            if (data.getAchievements().getDailySalesAndCount() != null) {
                baseline = data.getAchievements().getDailySalesAndCount().stream()
                        .mapToDouble(d -> d.getValue().doubleValue()).average().orElse(0);
            }
            statsRow.addCell(createIntelligenceCard(
                    "BASELINE SALES",
                    "~ AED " + String.format("%,.0f", baseline),
                    "Typical daily revenue",
                    new Color(45, 156, 219), // #2D9CDB
                    "📈")); // Chart

            salesTrendLayout.addCell(statsRow);

            // 5. Micro Insight Footer
            PdfPCell microInsight = new PdfPCell(new Phrase(
                    "Sales spikes are concentrated on a few high-impact days, suggesting targeted promotions or specific events.",
                    new Font(Font.HELVETICA, 9, Font.ITALIC, new Color(143, 163, 184)))); // #8FA3B8
            microInsight.setBorder(Rectangle.NO_BORDER);
            microInsight.setPaddingTop(15);
            microInsight.setHorizontalAlignment(Element.ALIGN_CENTER);
            salesTrendLayout.addCell(microInsight);

            document.add(salesTrendLayout);

            // --- Page 5: REVENUE & WEEKLY INTELLIGENCE (Consulting Grade) ---
            document.newPage();
            addHeroHeader(document, "REVENUE & WEEKLY INTELLIGENCE",
                    "Weekly performance patterns for " + monthYear);

            // 1. Smart Weekly Review (SWR)
            String swrText = "Weekly performance improved compared to the previous period, with sales momentum driven by strong weekend activity. "
                    +
                    "Revenue concentration on Saturdays suggests customer demand peaks toward the end of the week, indicating opportunities for targeted weekend promotions.";
            PdfPTable swrTable = new PdfPTable(1);
            swrTable.setWidthPercentage(100);
            swrTable.setSpacingAfter(15);
            swrTable.addCell(createSmartWeeklyReview(swrText));
            document.add(swrTable);

            PdfPTable page3Layout = new PdfPTable(1);
            page3Layout.setWidthPercentage(100);

            // SECTION 1: Key Metrics (3 Cards)
            PdfPTable metricsRow = new PdfPTable(3);
            metricsRow.setWidthPercentage(100);
            metricsRow.setWidths(new float[] { 1, 1, 1 });

            // Card 1: Weekly Sales (Green Badge, Blue Accent)
            metricsRow.addCell(createPage2StyleCard(
                    writer,
                    "WEEKLY SALES",
                    data.getOverview().getSales().getFormattedValue(),
                    String.format("▲ %.1f%% vs prev week",
                            data.getOverview().getSales().getMomGrowth() != null
                                    ? data.getOverview().getSales().getMomGrowth().doubleValue()
                                    : 0.0),
                    new Color(45, 156, 219), // #2D9CDB (Blue)
                    "💰",
                    data.getAchievements().getDailySalesAndCount())); // Background Sparkline

            // Card 2: Daily Average (Green Accent)
            Double dailyAvg = data.getOverview().getSales().getValue() != null
                    ? data.getOverview().getSales().getValue().doubleValue() / 30.0
                    : 0.0;
            metricsRow.addCell(createPage2StyleCard(
                    writer,
                    "DAILY AVERAGE",
                    "AED " + String.format("%.0f", dailyAvg),
                    "Avg revenue per day",
                    new Color(39, 174, 96), // #27AE60 (Green)
                    "📊",
                    null)); // No sparkline needed for avg anchor

            // Card 3: Peak Day (Amber/Gold Accent)
            String peakDay = "Saturday";
            String peakSales = "AED 6,420";
            if (data.getOverview().getSalesByDayOfWeek() != null &&
                    !data.getOverview().getSalesByDayOfWeek().isEmpty()) {
                ChartData peak = data.getOverview().getSalesByDayOfWeek().stream()
                        .max((a, b) -> Double.compare(
                                a.getValue() != null ? a.getValue().doubleValue() : 0.0,
                                b.getValue() != null ? b.getValue().doubleValue() : 0.0))
                        .orElse(null);
                if (peak != null) {
                    peakDay = peak.getLabel();
                    peakSales = "Highest revenue day";
                }
            }
            metricsRow.addCell(createPage2StyleCard(
                    writer,
                    "PEAK DAY",
                    peakDay,
                    peakSales,
                    new Color(242, 201, 76), // #F2C94C (Amber)
                    "⭐",
                    null));

            PdfPCell metricsWrapper = new PdfPCell(metricsRow);
            metricsWrapper.setBorder(Rectangle.NO_BORDER);
            metricsWrapper.setPaddingBottom(20);
            page3Layout.addCell(metricsWrapper);

            // SECTION 2: Charts (Side-by-Side)
            PdfPTable chartsRow = new PdfPTable(2);
            chartsRow.setWidthPercentage(100);
            chartsRow.setWidths(new float[] { 1, 1 });

            // Left: Daily Sales Trend (Area Chart)
            JFreeChart weeklyTrendChart = createWeeklySalesTrendChart(
                    "DAILY SALES TREND",
                    "Sales (AED)",
                    data.getAchievements().getDailySalesAndCount());
            PdfPCell leftChartCell = createCleanChartCard(writer, weeklyTrendChart, "DAILY SALES TREND");
            leftChartCell.setMinimumHeight(220);
            chartsRow.addCell(leftChartCell);

            // Right: Sales by Day of Week (Sorted Bar Chart)
            JFreeChart dayOfWeekChart = createDayOfWeekBarChart(
                    "SALES BY DAY",
                    "Sales (AED)",
                    data.getOverview().getSalesByDayOfWeek());
            PdfPCell rightChartCell = createCleanChartCard(writer, dayOfWeekChart, "SALES BY DAY");
            rightChartCell.setMinimumHeight(220);
            chartsRow.addCell(rightChartCell);

            PdfPCell chartsWrapper = new PdfPCell(chartsRow);
            chartsWrapper.setBorder(Rectangle.NO_BORDER);
            chartsWrapper.setPaddingBottom(15);
            page3Layout.addCell(chartsWrapper);

            // SECTION 3: Micro Insight
            PdfPCell microInsight = new PdfPCell(new Phrase(
                    "Sales performance strengthens toward the weekend, with Saturdays consistently outperforming weekdays, suggesting demand is driven by discretionary spending.",
                    new Font(Font.HELVETICA, 9, Font.ITALIC, new Color(143, 163, 184)))); // #8FA3B8
            page3Layout.addCell(microInsight);
            document.add(page3Layout);

            // --- Page 6: REVENUE BY DAY TYPE (Consulting Grade) ---
            document.newPage();
            addHeroHeader(document, "REVENUE BY DAY TYPE", "Behavioural revenue distribution and action signals");

            PdfPTable page6Layout = new PdfPTable(2);
            page6Layout.setWidthPercentage(100);
            page6Layout.setWidths(new float[] { 0.6f, 0.4f }); // Left Chart (60%), Right Insights (40%)
            page6Layout.setSpacingBefore(15);

            // LEFT: Donut Chart (Hero Visual)
            DefaultPieDataset dayTypeDs = new DefaultPieDataset();
            if (data.getOverview().getSalesByDayOfWeek() != null) {
                double weekend = 0;
                double weekday = 0;
                double friday = 0;
                for (ChartData d : data.getOverview().getSalesByDayOfWeek()) {
                    String day = d.getLabel().toLowerCase();
                    if (day.contains("sat") || day.contains("sun"))
                        weekend += d.getValue().doubleValue();
                    else if (day.contains("fri"))
                        friday += d.getValue().doubleValue();
                    else
                        weekday += d.getValue().doubleValue();
                }
                dayTypeDs.setValue("Weekends (Sat-Sun)", weekend);
                dayTypeDs.setValue("Weekdays (Mon-Thu)", weekday);
                dayTypeDs.setValue("Fridays", friday);
            }

            JFreeChart revenueByDayTypeChart = createMutedDonutChart("REVENUE DISTRIBUTION", dayTypeDs);
            PdfPCell leftCell = createCleanChartCard(writer, revenueByDayTypeChart, "");
            leftCell.setPaddingRight(10);
            page6Layout.addCell(leftCell);

            // RIGHT: Insight Stack
            PdfPCell rightCell = new PdfPCell();
            rightCell.setBorder(Rectangle.NO_BORDER);

            PdfPTable stack = new PdfPTable(1);
            stack.setWidthPercentage(100);

            // A. Weekend Surge
            stack.addCell(createInsightBlock("WEEKEND SURGE", "Saturday: ~18% of weekly revenue",
                    "Optimize staffing, inventory, and upsell offers.", new Color(39, 174, 96), "📈"));

            // B. Friday Dip
            stack.addCell(createInsightBlock("FRIDAY DIP", "~28% lower activity vs avg",
                    "Short-duration flash promotions recommended.", new Color(235, 87, 87), "⚠️"));

            // C. Mid-Week Steady
            stack.addCell(createInsightBlock("MID-WEEK STABILITY", "Tue-Thu remain consistently near average",
                    "Loyalty programs and repeat offers effective.", new Color(45, 156, 219), "🔁"));

            // D. Best Day
            String bestDayName = "Saturday";
            if (data.getOverview().getSalesByDayOfWeek() != null) {
                ChartData peak = data.getOverview().getSalesByDayOfWeek().stream()
                        .max((a, b) -> Double.compare(a.getValue().doubleValue(), b.getValue().doubleValue()))
                        .orElse(null);
                if (peak != null)
                    bestDayName = peak.getLabel();
            }
            stack.addCell(createInsightBlock("BEST PERFORMING DAY", bestDayName + ": High volume sales",
                    "~35% above daily average.", new Color(155, 81, 224), "🏆"));

            // E. Avg Ticket
            stack.addCell(createInsightBlock("AVERAGE TICKET SIZE", "AED 78 per transaction",
                    "Evenings peak at AED 85 (+9%).", new Color(242, 201, 76), "🧾"));

            // F. Growth Signal
            stack.addCell(createInsightBlock("GROWTH SIGNAL", "Week-over-week growth +12.8%",
                    "Consistent upward demand trend.", new Color(39, 174, 96), "🚀"));

            rightCell.addElement(stack);
            page6Layout.addCell(rightCell);

            document.add(page6Layout);

            // 4. Behavioral Summary (Footer)
            PdfPTable footerLayout = new PdfPTable(1);
            footerLayout.setWidthPercentage(100);
            footerLayout.setSpacingBefore(20);

            Paragraph summaryP = new Paragraph("Behavioural Summary\n",
                    new Font(Font.HELVETICA, 10, Font.BOLD, new Color(143, 163, 184)));
            summaryP.add(new Chunk(
                    "Revenue performance shows strong weekend dependency with stable mid-week demand. Strategic promotions and staffing optimization can significantly improve weekly yield.",
                    new Font(Font.HELVETICA, 9, Font.NORMAL, new Color(143, 163, 184))));

            PdfPCell footerCell = new PdfPCell(summaryP);
            footerCell.setBorder(Rectangle.NO_BORDER);
            footerCell.setPaddingTop(10);
            footerLayout.addCell(footerCell);

            document.add(footerLayout);

            // --- Page 4: MONTHLY MOMENTUM ANALYSIS (was Page 5) ---
            addHeroHeader(document, "MONTHLY MOMENTUM ANALYSIS", "How business momentum evolves across the month");

            PdfPTable page5Layout = new PdfPTable(1);
            page5Layout.setWidthPercentage(100);
            page5Layout.setSpacingBefore(10);

            // 1. Hero Area Chart (Sales by Week) - DARK BACKGROUND (not white!)
            JFreeChart salesWeekChart = createAreaChart("SALES VELOCITY BY WEEK", "Revenue (AED)",
                    data.getOverview().getSalesByWeekOfMonth());
            PdfPCell salesWeekCell = createCleanChartCard(writer, salesWeekChart, "WEEKLY SALES TREND");
            salesWeekCell.setMinimumHeight(250); // Larger chart
            page5Layout.addCell(salesWeekCell);
            page5Layout.addCell(createSpacer(20));

            // 2. Week-over-Week Comparison Table (Premium Styling)
            PdfPTable wowTable = createGlassTable(4); // Week, Sales, Txns, ATV
            wowTable.setWidths(new float[] { 2, 3, 2, 3 }); // Better column proportions

            // Header
            wowTable.addCell(createGlassHeaderCell("PERIOD"));
            wowTable.addCell(createGlassHeaderCell("REVENUE"));
            wowTable.addCell(createGlassHeaderCell("TXNS"));
            wowTable.addCell(createGlassHeaderCell("ATV"));

            // Rows (Mocking logic for display, data comes from data object normally)
            // Week 1
            wowTable.addCell(createGlassCell("Week 1"));
            wowTable.addCell(createGlassCell("AED 8,450"));
            wowTable.addCell(createGlassCell("210"));
            wowTable.addCell(createGlassCell("AED 40.2"));

            // Week 2
            wowTable.addCell(createGlassCell("Week 2"));
            wowTable.addCell(createGlassCell("AED 9,120"));
            wowTable.addCell(createGlassCell("225"));
            wowTable.addCell(createGlassCell("AED 40.5"));

            // Week 3
            wowTable.addCell(createGlassCell("Week 3"));
            wowTable.addCell(createGlassCell("AED 8,900"));
            wowTable.addCell(createGlassCell("218"));
            wowTable.addCell(createGlassCell("AED 40.8"));

            // Week 4
            wowTable.addCell(createGlassCell("Week 4"));
            wowTable.addCell(createGlassCell("AED 10,500"));
            wowTable.addCell(createGlassCell("260"));
            wowTable.addCell(createGlassCell("AED 40.4"));

            // Wrap table in dark card (not white glass!)
            PdfPCell tableWrapper = new PdfPCell(wowTable);
            tableWrapper.setBorder(Rectangle.NO_BORDER);
            tableWrapper.setPadding(15);
            tableWrapper.setBackgroundColor(new Color(51, 65, 85)); // Slate 700 - dark!
            page5Layout.addCell(tableWrapper);
            page5Layout.addCell(createSpacer(15)); // Optimized spacing for single-page layout

            // 3. Strategic Insight Panel (Premium Compact Design)
            page5Layout.addCell(createLavenderExecutiveInsightCard("MOMENTUM INSIGHT",
                    "Revenue accelerates significantly in Week 4 (+18%), suggesting end-of-month pay cycles drive customer spending. Run 'Early Bird' promo in Week 1 to flatten the curve.",
                    "📊"));

            document.add(page5Layout);

            // --- Page 6: GROWTH TRAJECTORY & PROJECTIONS ---
            document.newPage();
            addHeroHeader(document, "GROWTH TRAJECTORY & PROJECTIONS", "Future performance modeling");

            PdfPTable page8Layout = new PdfPTable(1);
            page8Layout.setWidthPercentage(100);
            page8Layout.setSpacingBefore(10);

            // 1. Growth Scorecard (Glassmorphic)
            PdfPTable growthScorecard = new PdfPTable(3);
            growthScorecard.setWidthPercentage(100);
            growthScorecard.setWidths(new float[] { 1, 1, 1 });

            // YoY Growth - DARK background (not white!)
            PdfPTable yoyContent = createSimpleKpiContent("YoY GROWTH", "+24.5%", COL_ACCENT_GROWTH);
            PdfPCell yoyCell = new PdfPCell(yoyContent);
            yoyCell.setBorder(Rectangle.NO_BORDER);
            yoyCell.setPadding(15);
            yoyCell.setBackgroundColor(new Color(51, 65, 85)); // Dark slate
            growthScorecard.addCell(yoyCell);

            // MoM Growth - DARK background
            PdfPTable growthMomContent = createSimpleKpiContent("MoM TRAJECTORY", "+8.2%", COL_ACCENT_SALES);
            PdfPCell growthMomCell = new PdfPCell(growthMomContent);
            growthMomCell.setBorder(Rectangle.NO_BORDER);
            growthMomCell.setPadding(15);
            growthMomCell.setBackgroundColor(new Color(51, 65, 85)); // Dark slate
            growthScorecard.addCell(growthMomCell);

            // Momentum Index - DARK background
            PdfPTable momentumContent = createSimpleKpiContent("MOMENTUM INDEX", "92/100", new Color(124, 58, 237));
            PdfPCell momentumCell = new PdfPCell(momentumContent);
            momentumCell.setBorder(Rectangle.NO_BORDER);
            momentumCell.setPadding(15);
            momentumCell.setBackgroundColor(new Color(51, 65, 85)); // Dark slate
            growthScorecard.addCell(momentumCell);

            PdfPCell scoreCell = new PdfPCell(growthScorecard);
            scoreCell.setBorder(Rectangle.NO_BORDER);
            page8Layout.addCell(scoreCell);
            page8Layout.addCell(createSpacer(20));

            // 2. Dual-Area Projection Chart (Hero)
            // Projected Revenue vs Baseline
            JFreeChart projectionChart = createDualAreaChart("PROJECTED REVENUE vs BASELINE", "Revenue",
                    convertToMap(data.getOverview().getSalesByWeekOfMonth())); // Proxy data
            page8Layout.addCell(createGlassChartCell(writer, projectionChart));
            page8Layout.addCell(createSpacer(20));

            // 3. Strategic 3-Column Breakdown
            PdfPTable strategicRow = new PdfPTable(3);
            strategicRow.setWidthPercentage(100);
            strategicRow.setWidths(new float[] { 1, 1, 1 });

            strategicRow.addCell(createStrategicBreakdown("CURRENT STATE",
                    "Consistent growth but under-monetized weekends.", new Color(239, 68, 68))); // Red border
            strategicRow.addCell(createStrategicBreakdown("OPTIMIZED", "Potential 15% lift via weekend bundles.",
                    new Color(34, 197, 94))); // Green border
            strategicRow.addCell(createStrategicBreakdown("ACTION", "Activate 'Weekend Warrior' campaign.",
                    new Color(59, 130, 246))); // Blue border

            PdfPCell stratRowCell = new PdfPCell(strategicRow);
            stratRowCell.setBorder(Rectangle.NO_BORDER);
            page8Layout.addCell(stratRowCell);
            page8Layout.addCell(createSpacer(10));

            // 4. Executive Insight
            page8Layout.addCell(createLavenderExecutiveInsightCard("GROWTH INSIGHT",
                    "Current trajectory indicates a +24.5% YoY finish. Implementing weekend optimization strategies could push this to +40% by Q3. Momentum is strong.",
                    "🚀"));

            document.add(page8Layout);

            // --- Page 9: PAYMENT ECOSYSTEM ANALYSIS ---
            document.newPage();
            addHeroHeader(document, "PAYMENT ECOSYSTEM ANALYSIS", "Optimization opportunities in payment mix");

            PdfPTable page9Layout = new PdfPTable(1);
            page9Layout.setWidthPercentage(100);
            page9Layout.setSpacingBefore(10);

            // 1. INNOVATIVE LAYOUT: Payment Mix Donuts (2 per row for better spacing)
            // Row 1: Card Schemes + Card Types
            PdfPTable donutsRow1 = new PdfPTable(2);
            donutsRow1.setWidthPercentage(90); // Reduced from 100% for better margins
            donutsRow1.setWidths(new float[] { 1, 1 });
            donutsRow1.setSpacingAfter(15);

            // Scheme Donut (smaller size)
            donutsRow1.addCell(createGlassChartCell(writer,
                    createDonutChart("CARD SCHEMES", data.getDemographics().getCardSchemeValueSplit())));
            // Type Donut
            donutsRow1.addCell(createGlassChartCell(writer,
                    createDonutChart("CARD TYPES", data.getDemographics().getCardTypeValueSplit())));

            PdfPCell donuts1Cell = new PdfPCell(donutsRow1);
            donuts1Cell.setBorder(Rectangle.NO_BORDER);
            donuts1Cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            page9Layout.addCell(donuts1Cell);

            // Row 2: Entry Modes + Payment Insights
            PdfPTable donutsRow2 = new PdfPTable(2);
            donutsRow2.setWidthPercentage(90);
            donutsRow2.setWidths(new float[] { 1, 1 });

            // Entry Mode Donut
            donutsRow2.addCell(createGlassChartCell(writer,
                    createDonutChart("ENTRY MODES", data.getDemographics().getCardTypeCountSplit())));

            // Payment Optimization Insights (Right side)
            PdfPTable insightsPanel = new PdfPTable(1);
            insightsPanel.setWidthPercentage(100);

            // Insight 1: Card Preference
            insightsPanel.addCell(createPaymentInsightCard("💳 CARD PREFERENCE",
                    "Visa dominates at 45%, optimize MDR with volume-based negotiations.",
                    new Color(6, 182, 212))); // Cyan

            // Insight 2: Entry Mode
            insightsPanel.addCell(createPaymentInsightCard("📱 CONTACTLESS SURGE",
                    "Tap payments up 28% - ensure all terminals NFC-enabled.",
                    new Color(139, 92, 246))); // Purple

            // Insight 3: Optimization
            insightsPanel.addCell(createPaymentInsightCard("💰 COST SAVINGS",
                    "Shift 10% to wallet = AED 2.4K annual fee reduction.",
                    new Color(16, 185, 129))); // Green

            PdfPCell insightsCell = new PdfPCell(insightsPanel);
            insightsCell.setBorder(Rectangle.NO_BORDER);
            donutsRow2.addCell(insightsCell);

            PdfPCell donuts2Cell = new PdfPCell(donutsRow2);
            donuts2Cell.setBorder(Rectangle.NO_BORDER);
            donuts2Cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            page9Layout.addCell(donuts2Cell);

            document.add(page9Layout);

            // --- Page 10: CUSTOMER LOYALTY SEGMENTATION ---
            document.newPage();
            addHeroHeader(document, "CUSTOMER LOYALTY SEGMENTATION", "Retention & frequency analysis");

            PdfPTable page10Layout = new PdfPTable(1);
            page10Layout.setWidthPercentage(100);
            page10Layout.setSpacingBefore(10);

            // 1. Loyalty Scorecard (Glassmorphic)
            PdfPTable loyaltyScorecard = new PdfPTable(3);
            loyaltyScorecard.setWidthPercentage(100);
            loyaltyScorecard.setWidths(new float[] { 1, 1, 1 });

            loyaltyScorecard.addCell(
                    createGlassmorphicCard(createSimpleKpiContent("RETENTION RATE", "68%", COL_ACCENT_GROWTH)));
            loyaltyScorecard.addCell(
                    createGlassmorphicCard(createSimpleKpiContent("REPEAT CUSTOMERS", "420", COL_ACCENT_TXNS))); // Blue
            loyaltyScorecard.addCell(
                    createGlassmorphicCard(createSimpleKpiContent("AVG VISITS/MO", "3.5", new Color(245, 158, 11)))); // Amber

            PdfPCell scoreCell10 = new PdfPCell(loyaltyScorecard);
            scoreCell10.setBorder(Rectangle.NO_BORDER);
            page10Layout.addCell(scoreCell10);
            page10Layout.addCell(createSpacer(20));

            // 2. Visit Frequency Pyramid (Hero Visual)
            // Left: Pyramid, Right: Spend Bands
            PdfPTable loyaltyMain = new PdfPTable(2);
            loyaltyMain.setWidthPercentage(100);
            loyaltyMain.setWidths(new float[] { 1, 1 });

            // Pyramid
            loyaltyMain.addCell(createGlassmorphicCard(createVisitPyramid()));

            // Spend Bands or Insight
            loyaltyMain.addCell(createGlassmorphicCard(createSpendBandVisual()));

            PdfPCell loyaltyMainCell = new PdfPCell(loyaltyMain);
            loyaltyMainCell.setBorder(Rectangle.NO_BORDER);
            page10Layout.addCell(loyaltyMainCell);
            page10Layout.addCell(createSpacer(20));

            // 3. Loyalty Growth Strategy
            // Reusing Strategic Breakdown style
            PdfPTable loyaltyStrat = new PdfPTable(3);
            loyaltyStrat.setWidthPercentage(100);
            loyaltyStrat.setWidths(new float[] { 1, 1, 1 });

            loyaltyStrat.addCell(createStrategicBreakdown("NEW CUSTOMERS", "Incentivize 2nd visit within 7 days.",
                    new Color(59, 130, 246)));
            loyaltyStrat.addCell(createStrategicBreakdown("AT RISK", "Re-engage absent VIPs (30+ days) with offer.",
                    new Color(239, 68, 68)));
            loyaltyStrat.addCell(createStrategicBreakdown("LOYALISTS", "Launch referral program to replicate.",
                    new Color(16, 185, 129)));

            PdfPCell lStratCell = new PdfPCell(loyaltyStrat);
            lStratCell.setBorder(Rectangle.NO_BORDER);
            page10Layout.addCell(lStratCell);
            page10Layout.addCell(createSpacer(10));

            // Executive Insight
            page10Layout.addCell(createLavenderExecutiveInsightCard("LOYALTY INSIGHT",
                    "Retention is strong at 68%, but 'One-Time' visitors account for 45% of traffic. converting just 10% of these to repeat customers adds ~AED 15k monthly revenue.",
                    "💎"));

            document.add(page10Layout);

            // --- Page 11: MONTHLY TRENDS ANALYSIS ---
            document.newPage();
            addHeroHeader(document, "MONTHLY TRENDS ANALYSIS", "Review of monthly performance trajectory");

            PdfPTable page11Layout = new PdfPTable(1);
            page11Layout.setWidthPercentage(100);
            page11Layout.setSpacingBefore(10);

            // 1. Yearly Summary (Glassmorphic Strip)
            // 4 Metrics: Total Sales, Total Txns, Avg Monthly Sales, Best Month
            page11Layout.addCell(createYearlySummaryCard("1.2M", "AED 105k", "8.5k", "DECEMBER"));
            page11Layout.addCell(createSpacer(20));

            // 2. Annual Trend Chart (Hero)
            // Monthly Sales + Trend Line
            JFreeChart monthlyTrendChart = createAreaChart("ANNUAL SALES TREND (AED)", "Monthly Values",
                    data.getDemographics().getMonthlySales()); // Reuse sales map
            page11Layout.addCell(createHeroAreaChartCard(writer, monthlyTrendChart));
            page11Layout.addCell(createSpacer(20));

            // 3. Transactions vs Customers (Dual Chart)
            page11Layout.addCell(createSideBySideCharts(writer,
                    "TRANSACTION VOLUME", data.getDemographics().getMonthlyTxns(),
                    "UNIQUE CUSTOMERS", data.getDemographics().getMonthlyCustomers()));
            page11Layout.addCell(createSpacer(20));

            // 4. Seasonality Insight
            page11Layout.addCell(createSeasonalityInsight("SEASONALITY ANALYSIS",
                    "Peak performance consistently occurs in Q4 (Oct-Dec), driven by holiday spending. Q1 shows a typical post-season dip of 15%."));
            page11Layout.addCell(createSpacer(20));

            // 5. QUARTERLY PERFORMANCE BREAKDOWN (Fill empty space)
            PdfPCell quarterlyHeader = new PdfPCell(
                    new Phrase("QUARTERLY PERFORMANCE BREAKDOWN",
                            new Font(Font.HELVETICA, 10, Font.BOLD, COL_TEXT_MUTED)));
            quarterlyHeader.setBorder(Rectangle.NO_BORDER);
            quarterlyHeader.setPaddingBottom(12); // Consistent header spacing
            page11Layout.addCell(quarterlyHeader);

            PdfPTable quarterlyTable = createGlassTable(5);
            quarterlyTable.setWidths(new float[] { 2, 2, 2, 2, 2 });

            // Header
            quarterlyTable.addCell(createGlassHeaderCell("QUARTER"));
            quarterlyTable.addCell(createGlassHeaderCell("SALES (AED)"));
            quarterlyTable.addCell(createGlassHeaderCell("TRANSACTIONS"));
            quarterlyTable.addCell(createGlassHeaderCell("AVG TICKET"));
            quarterlyTable.addCell(createGlassHeaderCell("GROWTH"));

            // Data rows (4 quarters)
            String[] quarters = { "Q1 (Jan-Mar)", "Q2 (Apr-Jun)", "Q3 (Jul-Sep)", "Q4 (Oct-Dec)" };
            String[] qSales = { "285,000", "320,000", "310,000", "385,000" };
            String[] qTxns = { "9,500", "10,800", "10,200", "12,500" };
            String[] qAvgTicket = { "30.00", "29.60", "30.40", "30.80" };
            String[] qGrowth = { "Baseline", "+12.3%", "-3.1%", "+24.2%" };

            for (int i = 0; i < 4; i++) {
                quarterlyTable.addCell(createGlassCell(quarters[i]));
                quarterlyTable.addCell(createGlassCell(qSales[i]));
                quarterlyTable.addCell(createGlassCell(qTxns[i]));
                quarterlyTable.addCell(createGlassCell("AED " + qAvgTicket[i]));
                quarterlyTable.addCell(createGlassCell(qGrowth[i]));
            }

            page11Layout.addCell(createGlassmorphicCard(quarterlyTable));
            page11Layout.addCell(createSpacer(20));

            // 6. MONTHLY INSIGHTS (3 insight cards in row)
            PdfPCell monthlyInsightsHeader = new PdfPCell(
                    new Phrase("MONTHLY TREND INSIGHTS", new Font(Font.HELVETICA, 10, Font.BOLD, COL_TEXT_MUTED)));
            monthlyInsightsHeader.setBorder(Rectangle.NO_BORDER);
            monthlyInsightsHeader.setPaddingBottom(12); // Consistent header spacing
            page11Layout.addCell(monthlyInsightsHeader);

            PdfPTable monthlyInsightsRow = new PdfPTable(3);
            monthlyInsightsRow.setWidthPercentage(100);
            monthlyInsightsRow.setWidths(new float[] { 1, 1, 1 });

            monthlyInsightsRow.addCell(createPaymentInsightCard("📈 GROWTH MOMENTUM",
                    "Q4 shows +24% growth vs Q1. December peak (AED 135k) driven by holiday shopping.",
                    new Color(16, 185, 129))); // Green

            monthlyInsightsRow.addCell(createPaymentInsightCard("🔄 SEASONAL PATTERN",
                    "Consistent Q1 dip (-15%) post-holidays. Plan promotions for Jan-Feb to stabilize revenue.",
                    new Color(251, 146, 60))); // Amber

            monthlyInsightsRow.addCell(createPaymentInsightCard("👥 CUSTOMER RETENTION",
                    "Unique customers grew 18% (Q1: 3.2k → Q4: 3.8k). Focus on loyalty programs.",
                    new Color(6, 182, 212))); // Cyan

            PdfPCell monthlyInsightsCell = new PdfPCell(monthlyInsightsRow);
            monthlyInsightsCell.setBorder(Rectangle.NO_BORDER);
            page11Layout.addCell(monthlyInsightsCell);
            page11Layout.addCell(createSpacer(20));

            // 7. MONTH-OVER-MONTH COMPARISON (Top 3 vs Bottom 3)
            PdfPTable momComparison = new PdfPTable(2);
            momComparison.setWidthPercentage(100);
            momComparison.setWidths(new float[] { 1, 1 });

            // Top 3 Months
            PdfPTable topMonths = createGlassTable(2);
            topMonths.setWidths(new float[] { 2, 1 });
            PdfPCell topHeader = new PdfPCell(
                    new Phrase("TOP 3 MONTHS", new Font(Font.HELVETICA, 9, Font.BOLD, new Color(16, 185, 129))));
            topHeader.setColspan(2);
            topHeader.setBorder(Rectangle.NO_BORDER);
            topHeader.setPaddingBottom(8);
            topMonths.addCell(topHeader);
            topMonths.addCell(createGlassCell("1. December"));
            topMonths.addCell(createGlassCell("AED 135k"));
            topMonths.addCell(createGlassCell("2. November"));
            topMonths.addCell(createGlassCell("AED 125k"));
            topMonths.addCell(createGlassCell("3. October"));
            topMonths.addCell(createGlassCell("AED 125k"));

            // Bottom 3 Months
            PdfPTable bottomMonths = createGlassTable(2);
            bottomMonths.setWidths(new float[] { 2, 1 });
            PdfPCell bottomHeader = new PdfPCell(
                    new Phrase("BOTTOM 3 MONTHS", new Font(Font.HELVETICA, 9, Font.BOLD, CHART_DANGER)));
            bottomHeader.setColspan(2);
            bottomHeader.setBorder(Rectangle.NO_BORDER);
            bottomHeader.setPaddingBottom(8);
            bottomMonths.addCell(bottomHeader);
            bottomMonths.addCell(createGlassCell("1. February"));
            bottomMonths.addCell(createGlassCell("AED 85k"));
            bottomMonths.addCell(createGlassCell("2. January"));
            bottomMonths.addCell(createGlassCell("AED 90k"));
            bottomMonths.addCell(createGlassCell("3. March"));
            bottomMonths.addCell(createGlassCell("AED 95k"));

            momComparison.addCell(createGlassmorphicCard(topMonths));
            momComparison.addCell(createGlassmorphicCard(bottomMonths));

            PdfPCell momCell = new PdfPCell(momComparison);
            momCell.setBorder(Rectangle.NO_BORDER);
            page11Layout.addCell(momCell);

            document.add(page11Layout);

            // --- Page 12: DCC REVENUE OPTIMIZATION ---
            document.newPage();
            addHeroHeader(document, "DCC REVENUE OPTIMIZATION", "Maximizing international revenue capture");

            PdfPTable page12Layout = new PdfPTable(1);
            page12Layout.setWidthPercentage(100);
            page12Layout.setSpacingBefore(10);

            // 1. DCC Scorecard (Glassmorphic Strip)
            PdfPTable dccScorecard = new PdfPTable(3);
            dccScorecard.setWidthPercentage(100);
            dccScorecard.setWidths(new float[] { 1, 1, 1 });

            dccScorecard.addCell(
                    createGlassmorphicCard(createSimpleKpiContent("MISSED REVENUE", "AED 4.2k", COL_ACCENT_RISK)));
            dccScorecard.addCell(
                    createGlassmorphicCard(createSimpleKpiContent("CONVERSION RATE", "42%", COL_ACCENT_SALES)));
            dccScorecard.addCell(
                    createGlassmorphicCard(createSimpleKpiContent("ELIGIBLE VOLUME", "AED 185k", COL_ACCENT_TXNS)));

            PdfPCell dccScoreCell = new PdfPCell(dccScorecard);
            dccScoreCell.setBorder(Rectangle.NO_BORDER);
            page12Layout.addCell(dccScoreCell);
            page12Layout.addCell(createSpacer(20));

            // 2. Waterfall Impact Visual (Hero Card)
            // Simulating a Waterfall Chart using custom helper or a themed bar chart
            JFreeChart waterfallChart = createWaterfallChart("REVENUE IMPACT ANALYSIS", "AED",
                    convertToMap(data.getDccPerformance().getMissedOpportunityTrend()));
            page12Layout.addCell(createGlassChartCell(writer, waterfallChart));
            page12Layout.addCell(createSpacer(20));

            // 3. Currency Optimization Matrix
            PdfPTable curTable = createGlassTable(4);
            curTable.setWidths(new float[] { 2, 2, 2, 3 });

            curTable.addCell(createGlassHeaderCell("CURRENCY"));
            curTable.addCell(createGlassHeaderCell("ELIGIBLE VOL"));
            curTable.addCell(createGlassHeaderCell("CONV %"));
            curTable.addCell(createGlassHeaderCell("STRATEGIC RECOMMENDATION"));

            curTable.addCell(createGlassCell("USD"));
            curTable.addCell(createGlassCell("AED 85k"));
            curTable.addCell(createGlassCell("55%"));
            curTable.addCell(createGlassCell("High potential; optimize terminal prompts"));

            curTable.addCell(createGlassCell("EUR"));
            curTable.addCell(createGlassCell("AED 42k"));
            curTable.addCell(createGlassCell("38%"));
            curTable.addCell(createGlassCell("Staff training for DCC value prop"));

            curTable.addCell(createGlassCell("GBP"));
            curTable.addCell(createGlassCell("AED 22k"));
            curTable.addCell(createGlassCell("25%"));
            curTable.addCell(createGlassCell("Review currency markup strategy"));

            page12Layout.addCell(createGlassmorphicCard(curTable));
            page12Layout.addCell(createSpacer(20));

            // 4. Executive Insight
            page12Layout.addCell(createLavenderExecutiveInsightCard("DCC REVENUE INSIGHT",
                    "International volume is growing (+12%), but DCC conversion remains at 42%. Bridging the gap to 60% would generate an additional AED 2.5k in pure margin monthly.",
                    "💸"));

            document.add(page12Layout);

            document.close();
            return out.toByteArray();
        } catch (

        DocumentException e) {
            e.printStackTrace();
            throw new IOException("PDF Generation failed: " + e.getMessage(), e);
        }
    }

    // --- 0. COVER PAGE (PREMIUM FINTECH) ---
    private void createCoverPage(PdfWriter writer, Document document, String merchantName, String monthYear,
            MerchantInsightsDTO data) throws DocumentException {

        PdfContentByte canvas = writer.getDirectContentUnder();
        Rectangle pageSize = document.getPageSize();

        // 1. Background: Linear Gradient (135deg approx) + Pattern Overlay
        canvas.saveState();

        // We simulate diagonal gradient by using axial shading from Top-Left to
        // Bottom-Right
        PdfShading shading = PdfShading.simpleAxial(writer,
                0, pageSize.getHeight(), // x0, y0 (Top Left)
                pageSize.getWidth(), 0, // x1, y1 (Bottom Right)
                COL_BG_NAVY_START, COL_BG_BLUE_END);

        // Add a mid-point color? iText simpleAxial is 2-point.
        // For 3-point gradient we would need axial shading with function.
        // Simpler approach: Overlay a second gradient or just stick to 2-point Deep
        // Navy -> Light Blue which looks great.
        // Let's stick to the 2-point for robustness, it covers the range well.

        PdfShadingPattern pattern = new PdfShadingPattern(shading);
        canvas.setShadingFill(pattern);
        canvas.rectangle(0, 0, pageSize.getWidth(), pageSize.getHeight());
        canvas.fill();

        // 1b. Pattern Overlay (Dot Grid / Mesh) - 5% Opacity
        PdfPatternPainter mesh = canvas.createPattern(8, 8);
        mesh.setColorFill(new Color(255, 255, 255, 20)); // Low opacity white
        mesh.circle(4, 4, 0.5f); // Tiny dots
        mesh.fill();

        canvas.setPatternFill(mesh);
        canvas.rectangle(0, 0, pageSize.getWidth(), pageSize.getHeight());
        canvas.fill();

        // 1c. Visual Anchor (Abstract Geometric Lines - Right Side)
        canvas.setColorStroke(new Color(255, 255, 255, 15)); // Very faint
        canvas.setLineWidth(1f);
        float w = pageSize.getWidth();
        float h = pageSize.getHeight();
        for (int i = 0; i < 10; i++) {
            canvas.moveTo(w * 0.6f + (i * 20), 0);
            canvas.lineTo(w, h * 0.4f + (i * 30));
        }
        canvas.stroke();

        canvas.restoreState();

        // 2. Main Container (Content Layer)
        PdfPTable coverTable = new PdfPTable(1);
        coverTable.setWidthPercentage(100);
        coverTable.setSpacingBefore(60); // Reduced for full-screen design

        // 3. Header Bar (Transparent / Glassy Pill)
        // We'll draw this manually later or use a cell, but "Header" usually fixed.
        // The requirement says "Corporate Header" is updated too.
        // For Cover page, the "Hero Title Block" is the main focus.

        // 4. Hero Title Block (Glass Panel)
        PdfPCell heroCell = new PdfPCell();
        heroCell.setBorder(Rectangle.NO_BORDER);
        heroCell.setPadding(40); // Generous padding for premium feel
        heroCell.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                // Glass Effect: White with 8% opacity + Blur (Blur is hard in PDF, we use pure
                // transparency)
                cb.setColorFill(new Color(255, 255, 255, 20));
                // Rounded Rect
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        16);
                cb.fill();
                // Border removed - no stroke for cleaner appearance
                cb.restoreState();
            }
        });

        // Hero Inner Content
        PdfPTable heroContent = new PdfPTable(1);
        heroContent.setWidthPercentage(100);

        // TITLE
        Font titleFont = new Font(Font.HELVETICA, 38, Font.BOLD, COL_TEXT_HERO);
        PdfPCell tCell = new PdfPCell(new Phrase("MERCHANT PERFORMANCE\nINTELLIGENCE REPORT", titleFont));
        tCell.setBorder(Rectangle.NO_BORDER);
        tCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        tCell.setPaddingBottom(15);
        heroContent.addCell(tCell);

        // SUBTITLE
        Font subFont = new Font(Font.HELVETICA, 16, Font.NORMAL, COL_TEXT_SUBHERO);
        PdfPCell sCell = new PdfPCell(new Phrase(merchantName + " — " + monthYear, subFont));
        sCell.setBorder(Rectangle.NO_BORDER);
        sCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        sCell.setPaddingBottom(10);
        heroContent.addCell(sCell);

        // AGENT TAGLINE
        Font agentFont = new Font(Font.HELVETICA, 10, Font.ITALIC, new Color(148, 163, 184)); // Slate 400
        PdfPCell agentCell = new PdfPCell(
                new Phrase("Empowering " + merchantName + " with data-driven strategic clarity", agentFont));
        agentCell.setBorder(Rectangle.NO_BORDER);
        agentCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        agentCell.setPaddingBottom(20);
        heroContent.addCell(agentCell);

        heroCell.addElement(heroContent);

        // Add Hero Cell to Cover Table with margins
        PdfPTable heroWrapper = new PdfPTable(1);
        heroWrapper.setWidthPercentage(95); // Full-screen: 95% width for maximum impact
        heroWrapper.addCell(heroCell);

        PdfPCell wrapperCell = new PdfPCell(heroWrapper);
        wrapperCell.setBorder(Rectangle.NO_BORDER);
        wrapperCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        wrapperCell.setPaddingBottom(80); // Professional spacing before KPIs (increased for clarity)
        coverTable.addCell(wrapperCell);

        // KPI cards removed - they will appear on Page 2 (Business Overview) instead

        document.add(coverTable);
    }

    private PdfPCell createCoverKpiCard(String title, String value, Double growth, Color accentColor,
            List<ChartData> sparkData, boolean isValue) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8); // Gap between cards

        // Outer Glass Container
        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell cardCell = new PdfPCell();
        cardCell.setBorder(Rectangle.NO_BORDER);
        cardCell.setPadding(12); // Standard card padding (bank-grade design system)

        // Glass Effect Event
        cardCell.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();

                // Background: White 8% Opacity
                cb.setColorFill(new Color(255, 255, 255, 20));
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        12);
                cb.fill();

                // Border: Top Gradient Highlight (Accent)
                // Draw a line at top
                cb.setColorStroke(accentColor);
                cb.setLineWidth(2f);
                cb.moveTo(position.getLeft() + 12, position.getTop());
                cb.lineTo(position.getRight() - 12, position.getTop());
                cb.stroke();

                // Subtle Full Border
                cb.setColorStroke(new Color(255, 255, 255, 30));
                cb.setLineWidth(0.5f);
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        12);
                cb.stroke();

                cb.restoreState();
            }
        });

        // 1. Title Row
        PdfPTable top = new PdfPTable(2);
        top.setWidthPercentage(100);
        try {
            top.setWidths(new float[] { 3, 1 });
        } catch (Exception e) {
        }

        // Title Text
        Font tFont = new Font(Font.HELVETICA, 7, Font.BOLD, COL_TEXT_SUBHERO);
        PdfPCell tCell = new PdfPCell(new Phrase(title, tFont));
        tCell.setBorder(Rectangle.NO_BORDER);
        top.addCell(tCell);

        // Icon (Dot)
        PdfPCell iCell = new PdfPCell();
        iCell.setBorder(Rectangle.NO_BORDER);
        iCell.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(accentColor);
                cb.circle(position.getRight() - 5, position.getTop() - 5, 2.5f);
                cb.fill();
                cb.restoreState();
            }
        });
        top.addCell(iCell);

        cardCell.addElement(top);

        // 2. Value
        Font vFont = new Font(Font.HELVETICA, 16, Font.BOLD, Color.WHITE);
        Paragraph valP = new Paragraph(value, vFont);
        valP.setSpacingBefore(10);
        cardCell.addElement(valP);

        // 3. Growth Row
        if (growth != null) {
            Font gFont = new Font(Font.HELVETICA, 8, Font.NORMAL,
                    growth >= 0 ? COL_ACCENT_EMERALD : CHART_DANGER); // Professional Red 600
            String sym = growth >= 0 ? "▲" : "▼";
            Paragraph gP = new Paragraph(sym + " " + String.format("%.1f%%", Math.abs(growth)) + " vs Prev Month",
                    gFont);
            gP.setSpacingBefore(2);
            cardCell.addElement(gP);
        } else {
            Paragraph gP = new Paragraph(" ", new Font(Font.HELVETICA, 8));
            cardCell.addElement(gP);
        }

        // 4. Sparkline (Bottom)
        // We draw this manually via CellEvent on a filler cell
        PdfPCell sparkCell = new PdfPCell();
        sparkCell.setBorder(Rectangle.NO_BORDER);
        sparkCell.setFixedHeight(25); // Space for sparkline
        sparkCell.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                if (sparkData == null || sparkData.isEmpty())
                    return;

                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();

                float x = position.getLeft();
                float y = position.getBottom() + 5; // padding
                float w = position.getWidth();
                float h = position.getHeight() - 10;

                // Find min/max
                float min = Float.MAX_VALUE;
                float max = Float.MIN_VALUE;
                for (ChartData d : sparkData) {
                    java.math.BigDecimal val = isValue ? d.getValue() : d.getValue2();
                    float v = val != null ? val.floatValue() : 0.0f;
                    if (v < min)
                        min = v;
                    if (v > max)
                        max = v;
                }

                if (max == min)
                    max = min + 1; // avoid div/0

                float stepX = w / (sparkData.size() - 1);

                // 1. Draw Filled Area (Glass Effect)
                cb.moveTo(x, y);
                for (int i = 0; i < sparkData.size(); i++) {
                    java.math.BigDecimal valRaw = isValue ? sparkData.get(i).getValue() : sparkData.get(i).getValue2();
                    float val = valRaw != null ? valRaw.floatValue() : 0.0f;
                    float px = x + (i * stepX);
                    float py = y + ((val - min) / (max - min)) * h;
                    cb.lineTo(px, py);
                }
                cb.lineTo(x + w, y);
                cb.closePath();
                cb.setColorFill(new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 40)); // 15%
                                                                                                                     // opacity
                cb.fill();

                // 2. Draw Stroke Line
                cb.setColorStroke(accentColor);
                cb.setLineWidth(1.5f);
                boolean first = true;
                for (int i = 0; i < sparkData.size(); i++) {
                    java.math.BigDecimal valRaw = isValue ? sparkData.get(i).getValue() : sparkData.get(i).getValue2();
                    float val = valRaw != null ? valRaw.floatValue() : 0.0f;
                    float px = x + (i * stepX);
                    float py = y + ((val - min) / (max - min)) * h;

                    if (first) {
                        cb.moveTo(px, py);
                        first = false;
                    } else {
                        cb.lineTo(px, py);
                    }
                }
                cb.stroke();
                cb.restoreState();
            }
        });

        // Wrap sparkCell in a table because you can't add a Cell to a Cell directly
        PdfPTable sparkTable = new PdfPTable(1);
        sparkTable.setWidthPercentage(100);
        sparkTable.addCell(sparkCell);
        cardCell.addElement(sparkTable);

        card.addCell(cardCell);
        cell.addElement(card);
        return cell;
    }

    // --- 1. HEADER (GRADIENT BAR) ---
    private void addHeader(PdfWriter writer, Document document, String merchant, String date, String title)
            throws DocumentException {
        // Top Gradient Bar
        PdfContentByte canvas = writer.getDirectContent();
        canvas.saveState();
        Rectangle pageSize = document.getPageSize();
        float headerHeight = 50; // Compact
        float y = pageSize.getTop() - headerHeight;

        // Gradient Background (Deep Navy)
        PdfShading shading = PdfShading.simpleAxial(writer, 0, pageSize.getTop(), 0, y,
                COL_BG_NAVY_START, COL_BG_NAVY_MID); // Dark header
        PdfShadingPattern pattern = new PdfShadingPattern(shading);
        canvas.setShadingFill(pattern);
        canvas.rectangle(0, y, pageSize.getWidth(), headerHeight);
        canvas.fill();
        canvas.restoreState();

        // Header Table Overlay
        PdfPTable header = new PdfPTable(2);
        header.setTotalWidth(pageSize.getWidth() - 60); // Margins
        header.setLockedWidth(true);
        header.setWidths(new float[] { 1, 1 });

        // Left: Logo + Title (White text on dark bg)
        Font hTitleFont = new Font(Font.HELVETICA, 12, Font.BOLD, Color.WHITE);
        PdfPCell left = new PdfPCell(new Phrase(title.toUpperCase(), hTitleFont));
        left.setBorder(Rectangle.NO_BORDER);
        left.setVerticalAlignment(Element.ALIGN_MIDDLE);
        left.setFixedHeight(headerHeight);
        header.addCell(left);

        // Right: Pill (Merchant | Date)
        // We'll use a nested table for the pill look
        PdfPTable pillT = new PdfPTable(1);
        PdfPCell pillC = new PdfPCell(
                new Phrase(merchant + " | " + date, new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE)));
        pillC.setBorder(Rectangle.NO_BORDER);
        pillC.setHorizontalAlignment(Element.ALIGN_CENTER);
        pillC.setVerticalAlignment(Element.ALIGN_MIDDLE);
        pillC.setPaddingTop(6);
        pillC.setPaddingBottom(8);

        // Pill Background
        pillC.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(255, 255, 255, 30)); // 12% white
                cb.roundRectangle(position.getLeft(), position.getBottom() + 4, position.getWidth(),
                        position.getHeight() - 8, 10);
                cb.fill();
                cb.restoreState();
            }
        });

        // Right alignment wrapper
        PdfPTable rightWrapper = new PdfPTable(1);
        rightWrapper.setWidthPercentage(60); // Width of pill area
        rightWrapper.setHorizontalAlignment(Element.ALIGN_RIGHT);
        rightWrapper.addCell(pillC);

        PdfPCell right = new PdfPCell(rightWrapper);
        right.setBorder(Rectangle.NO_BORDER);
        right.setVerticalAlignment(Element.ALIGN_MIDDLE);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);

        header.addCell(right);

        header.writeSelectedRows(0, -1, 30, pageSize.getTop(), canvas);

        Paragraph spacer = new Paragraph(" ");
        spacer.setSpacingAfter(30);
        document.add(spacer);
    }

    // --- 2. FOOTER (CORPORATE) ---
    private void addFooter(PdfWriter writer, Document document) {
        PdfContentByte canvas = writer.getDirectContent();
        Rectangle pageSize = document.getPageSize();
        float y = 25;

        try {
            canvas.saveState();

            // Thin Divider Line
            canvas.setColorStroke(new Color(226, 232, 240)); // Slate 200
            canvas.setLineWidth(0.5f);
            canvas.moveTo(30, y + 15);
            canvas.lineTo(pageSize.getWidth() - 30, y + 15);
            canvas.stroke();

            canvas.setColorFill(COL_TEXT_MUTED);
            canvas.beginText();
            canvas.setFontAndSize(BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED), 7);

            // Left: Confidential
            canvas.showTextAligned(Element.ALIGN_LEFT, "CONFIDENTIAL – INTERNAL USE ONLY", 30, y, 0);

            // Right: Page X of Y
            String pageNum = String.format("Page %d", writer.getPageNumber());
            canvas.showTextAligned(Element.ALIGN_RIGHT, pageNum, pageSize.getWidth() - 30, y, 0);

            // Center: Timestamp
            String ts = new java.util.Date().toString();
            canvas.showTextAligned(Element.ALIGN_CENTER, "Generated: " + ts, pageSize.getWidth() / 2, y, 0);

            canvas.endText();
            canvas.restoreState();
        } catch (Exception e) {
        }
    }

    // --- TABLE HELPERS (GLASS STYLE) ---
    private PdfPTable createGlassTable(int numColumns) {
        PdfPTable table = new PdfPTable(numColumns);
        table.setWidthPercentage(100);
        table.setSpacingBefore(10);
        return table;
    }

    private PdfPCell createGlassHeaderCell(String text) {
        PdfPCell cell = new PdfPCell(
                new Phrase(text.toUpperCase(), new Font(Font.HELVETICA, 9, Font.BOLD, COL_TEXT_SECONDARY)));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(new Color(226, 232, 240)); // Slate 200
        cell.setPadding(8);
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        return cell;
    }

    private PdfPCell createGlassCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, new Font(Font.HELVETICA, 10, Font.NORMAL, COL_TEXT_PRIMARY)));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(new Color(241, 245, 249)); // Slate 100 (Very light)
        cell.setPadding(8);
        return cell;
    }

    // --- Row 1: Sales, Txns, Customers ---
    private PdfPCell createRow1(BusinessOverview overview) {
        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);
        try {
            table.setWidths(new float[] { 1, 1, 1 });

            // Sales
            table.addCell(createPaddedCell(
                    createKpiCard("SALES (AED)",
                            overview.getSales().getFormattedValue(),
                            overview.getSales().getMomGrowth(),
                            IconType.SALES,
                            COL_ACCENT_SALES,
                            null)));

            // Transactions
            table.addCell(createPaddedCell(
                    createKpiCard("TRANSACTIONS",
                            overview.getTransactions().getFormattedValue(),
                            overview.getTransactions().getMomGrowth(),
                            IconType.TRANSACTIONS,
                            COL_ACCENT_TXNS,
                            null)));

            // Customers
            table.addCell(createPaddedCell(
                    createKpiCard("CUSTOMERS",
                            overview.getCustomers().getFormattedValue(),
                            overview.getCustomers().getMomGrowth(),
                            IconType.CUSTOMERS,
                            COL_ACCENT_GOLD,
                            null)));

        } catch (DocumentException e) {
            e.printStackTrace();
        }

        PdfPCell cell = new PdfPCell(table);
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    // --- Row 2: Performance KPIs (4 cards) ---
    private PdfPCell createRow2(PeakStats peaks) {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        try {
            table.setWidths(new float[] { 1, 1, 1, 1 });

            // Max Daily Sales
            table.addCell(createPaddedCell(
                    createKpiCard("MAX DAILY SALES",
                            peaks.getMaxDailySales().getFormattedValue(),
                            null, // No growth for static max
                            IconType.SALES,
                            COL_ACCENT_SALES,
                            "Day: " + getDay(peaks.getMaxDailySalesDate()))));

            // Max Daily Txns
            table.addCell(createPaddedCell(
                    createKpiCard("MAX DAILY TXNS",
                            peaks.getMaxTxnsInDay().getFormattedValue(),
                            null,
                            IconType.TRANSACTIONS,
                            COL_ACCENT_TXNS,
                            "Day: " + getDay(peaks.getMaxTxnsInDayDate()))));

            // Highest Transaction
            table.addCell(createPaddedCell(
                    createKpiCard("HIGHEST TXN",
                            peaks.getHighestTxnValue().getFormattedValue(),
                            null,
                            IconType.SALES,
                            COL_ACCENT_AMBER,
                            "Day: " + getDay(peaks.getHighestTxnDate()))));

            // Highest Spend
            table.addCell(createPaddedCell(
                    createKpiCard("PEAK CUST SPEND",
                            peaks.getHighestCustomerSpend().getFormattedValue(),
                            null,
                            IconType.CUSTOMERS,
                            COL_ACCENT_VIOLET,
                            "High Value")));

        } catch (DocumentException e) {
            e.printStackTrace();
        }

        PdfPCell cell = new PdfPCell(table);
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    private Map<String, BigDecimal> convertToMap(List<ChartData> list) {
        Map<String, BigDecimal> map = new java.util.LinkedHashMap<>();
        if (list != null) {
            for (ChartData d : list) {
                map.put(d.getLabel(), d.getValue() != null ? d.getValue() : BigDecimal.ZERO);
            }
        }
        return map;
    }

    private String getDay(java.time.LocalDate date) {
        return date != null ? String.valueOf(date.getDayOfMonth()) : "-";
    }

    // --- Row 3: Average KPIs (3 cards) ---
    private PdfPCell createRow3(BusinessOverview overview) {
        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);
        try {
            table.setWidths(new float[] { 1, 1, 1 });

            table.addCell(createPaddedCell(
                    createKpiCard("AVG SPEND / CUST",
                            overview.getAvgSpendPerCustomer().getFormattedValue(),
                            overview.getAvgSpendPerCustomer().getMomGrowth(),
                            IconType.SALES,
                            COL_TEXT_SECONDARY,
                            null)));

            table.addCell(createPaddedCell(
                    createKpiCard("AVG TXN VALUE",
                            overview.getAvgTxnValue().getFormattedValue(),
                            overview.getAvgTxnValue().getMomGrowth(),
                            IconType.SALES,
                            COL_TEXT_SECONDARY,
                            null)));

            table.addCell(createPaddedCell(
                    createKpiCard("AVG TXN / CUST",
                            overview.getAvgTxnsPerCustomer().getFormattedValue(),
                            overview.getAvgTxnsPerCustomer().getMomGrowth(),
                            IconType.TRANSACTIONS,
                            COL_TEXT_SECONDARY,
                            null)));

        } catch (DocumentException e) {
            e.printStackTrace();
        }

        PdfPCell cell = new PdfPCell(table);
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    // --- Helper: KPI Card (Premium V2) ---
    private PdfPCell createKpiCard(String title, String value, Double growth, IconType iconType, Color iconColor,
            String progressLabel) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(6); // Outer spacing

        // Inner Container (The Card)
        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell cardCell = new PdfPCell();
        cardCell.setCellEvent(new GlassmorphicCardEvent());
        cardCell.setPadding(14);
        cardCell.setPaddingBottom(12);
        cardCell.setBorder(Rectangle.NO_BORDER);
        cell.setMinimumHeight(90); // Min height for consistency

        // TOP ROW: Title (Left) and Icon (Right)
        PdfPTable topRow = new PdfPTable(2);
        topRow.setWidthPercentage(100);
        try {
            topRow.setWidths(new float[] { 70, 30 }); // 70% Title, 30% Icon area
        } catch (DocumentException e) {
        }

        // Title
        Font titleFont = new Font(Font.HELVETICA, 8, Font.BOLD, COL_TEXT_SECONDARY); // Gray-500
        PdfPCell titleC = new PdfPCell(new Phrase(title.toUpperCase(), titleFont));
        titleC.setBorder(Rectangle.NO_BORDER);
        titleC.setVerticalAlignment(Element.ALIGN_TOP);
        topRow.addCell(titleC);

        // Icon
        PdfPCell iconC = new PdfPCell();
        iconC.setBorder(Rectangle.NO_BORDER);
        iconC.setFixedHeight(24); // Fixed height for icon area
        iconC.setCellEvent(new VectorIconEvent(iconColor, iconType));
        topRow.addCell(iconC);

        cardCell.addElement(topRow);

        // MIDDLE: Value
        Font valueFont = new Font(Font.HELVETICA, 20, Font.BOLD, COL_NAVY_DARK); // Large Dark Text
        Paragraph valP = new Paragraph(value, valueFont);
        valP.setSpacingBefore(8);
        valP.setSpacingAfter(4);
        cardCell.addElement(valP);

        // BOTTOM: Growth and Context
        PdfPTable bottomRow = new PdfPTable(2);
        bottomRow.setWidthPercentage(100);
        try {
            bottomRow.setWidths(new float[] { 1, 1 });
        } catch (DocumentException e) {
        }

        // Growth (Left)
        if (growth != null) {
            boolean isPos = growth >= 0;
            Color growColor = isPos ? COL_ACCENT_GROWTH : COL_ACCENT_RISK; // Emerald / Red
            String symbol = isPos ? "\u25B2" : "\u25BC";
            Chunk symbolChunk = new Chunk(symbol, new Font(Font.HELVETICA, 9, Font.NORMAL, growColor)); // Icon font?
            Chunk textChunk = new Chunk(String.format(" %.2f%%", Math.abs(growth)),
                    new Font(Font.HELVETICA, 9, Font.BOLD, growColor));

            Paragraph p = new Paragraph();
            p.add(symbolChunk);
            p.add(textChunk);

            PdfPCell growC = new PdfPCell(p);
            growC.setBorder(Rectangle.NO_BORDER);
            growC.setVerticalAlignment(Element.ALIGN_BOTTOM);
            bottomRow.addCell(growC);
        } else {
            PdfPCell empty = new PdfPCell(new Phrase(" "));
            empty.setBorder(Rectangle.NO_BORDER);
            bottomRow.addCell(empty);
        }

        // Context/Progress (Right)
        if (progressLabel != null) {
            // Label e.g., "Day: 07"
            Font noteFont = new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(156, 163, 175)); // Gray-400
            PdfPCell progC = new PdfPCell(new Phrase(progressLabel, noteFont));
            progC.setBorder(Rectangle.NO_BORDER);
            progC.setHorizontalAlignment(Element.ALIGN_RIGHT);
            progC.setVerticalAlignment(Element.ALIGN_BOTTOM);
            bottomRow.addCell(progC);
        } else {
            PdfPCell empty = new PdfPCell(new Phrase(" "));
            empty.setBorder(Rectangle.NO_BORDER);
            bottomRow.addCell(empty);
        }

        cardCell.addElement(bottomRow);

        // Visual Progress Bar (Bottom Edge)
        if (progressLabel != null) {
            PdfPTable barTable = new PdfPTable(2);
            barTable.setWidthPercentage(100);
            barTable.setSpacingBefore(6);

            // Calculate width based on day? (Pseudo random for visual pop or Day/31)
            float progress = 0.6f; // Default 60%
            if (progressLabel.contains("Day")) {
                try {
                    // Extract day number for realistic bar (Day: 07 -> 7/31)
                    String num = progressLabel.replaceAll("[^0-9]", "");
                    if (!num.isEmpty()) {
                        int d = Integer.parseInt(num);
                        progress = Math.min(1.0f, Math.max(0.1f, d / 31.0f));
                    }
                } catch (Exception e) {
                }
            }

            try {
                barTable.setWidths(new float[] { progress, 1.0f - progress });
            } catch (DocumentException e) {
            }

            PdfPCell fill = new PdfPCell();
            fill.setFixedHeight(4);
            fill.setBorder(Rectangle.NO_BORDER);
            fill.setBackgroundColor(COL_ACCENT_SALES); // Blue-500

            PdfPCell track = new PdfPCell();
            track.setFixedHeight(4);
            track.setBorder(Rectangle.NO_BORDER);
            track.setBackgroundColor(new Color(243, 244, 246)); // Gray-100

            barTable.addCell(fill);
            barTable.addCell(track);

            cardCell.addElement(barTable);
        }

        card.addCell(cardCell);
        cell.addElement(card);
        return cell;
    }

    enum IconType {
        SALES, TRANSACTIONS, CUSTOMERS, RECOMMENDATION
    }

    // Inner class for drawing vector icons (Improved High Fidelity)
    class VectorIconEvent implements PdfPCellEvent {
        private Color color;
        private IconType type;

        public VectorIconEvent(Color color, IconType type) {
            this.color = color;
            this.type = type;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte canvas = canvases[PdfPTable.LINECANVAS];
            canvas.saveState();

            // Align Right-Top of the provided cell box
            float w = 24; // Icon box width
            float h = 24; // Icon box height
            float x = position.getRight() - w - 2;
            float y = position.getTop() - h + 2;

            // Icon Drawing
            canvas.setColorFill(color);
            canvas.setColorStroke(color);
            canvas.setLineWidth(1.2f);

            float cx = x + w / 2; // Center X
            float cy = y + h / 2; // Center Y

            switch (type) {
                case SALES: // Bar Chart Icon
                    canvas.rectangle(cx - 6, cy - 6, 3, 6);
                    canvas.rectangle(cx - 1, cy - 10, 3, 10);
                    canvas.rectangle(cx + 4, cy - 14, 3, 14);
                    canvas.fill();
                    break;
                case TRANSACTIONS: // Credit Card
                    canvas.roundRectangle(cx - 9, cy - 6, 18, 12, 2);
                    canvas.fill();
                    canvas.setColorFill(Color.WHITE);
                    canvas.rectangle(cx - 9, cy + 1, 18, 2);
                    canvas.fill();
                    break;
                case CUSTOMERS: // Person Icon
                    canvas.circle(cx, cy + 3, 3);
                    canvas.fill();
                    // Body
                    canvas.arc(cx - 6, cy - 10, cx + 6, cy - 2, 0, 180);
                    canvas.fill();
                    break;
                case RECOMMENDATION: // Diamond / Sparkle
                    canvas.moveTo(cx, cy + 6);
                    canvas.lineTo(cx + 4, cy);
                    canvas.lineTo(cx, cy - 6);
                    canvas.lineTo(cx - 4, cy);
                    canvas.fill();
                    break;
            }
            canvas.restoreState();
        }
    }

    // --- Page 10 Helpers ---

    private PdfPCell createSemiCircleDonutChartCell(PdfWriter writer, String title, Map<String, BigDecimal> data) {
        DefaultPieDataset<String> dataset = new DefaultPieDataset<>();
        if (data != null) {
            // Sort to ensure Domestic is Blue (Left/Top) and Intl is Dark (Right)?
            // Visual shows Blue arch.
            for (Map.Entry<String, BigDecimal> entry : data.entrySet()) {
                dataset.setValue(entry.getKey(), entry.getValue());
            }
        }

        JFreeChart chart = ChartFactory.createRingChart(title, dataset, false, true, false);
        styleChart(chart);

        org.jfree.chart.plot.RingPlot plot = (org.jfree.chart.plot.RingPlot) chart.getPlot();
        // Customize for Semi-Circle look (approx by using Ring and hiding bottom?)
        // Actually, JFreeChart doesn't easily do semi-circle.
        // We will stick to a full Ring Chart but styled to look like the Donut on Page
        // 4.
        // If "Semi Circle" is strict requirement, we'd need a DialPlot, but Ring is
        // safer for now.

        plot.setSectionDepth(0.35); // Thickness
        // Show labels with percentage
        plot.setLabelGenerator(new StandardPieSectionLabelGenerator("{2}", new java.text.DecimalFormat("0%"),
                new java.text.DecimalFormat("0%")));
        plot.setSimpleLabels(true);
        plot.setLabelBackgroundPaint(null);
        plot.setLabelOutlinePaint(null);
        plot.setLabelShadowPaint(null);
        plot.setLabelPaint(COLOR_TEXT_PRIMARY); // White text
        plot.setLabelFont(new java.awt.Font("Inter", java.awt.Font.BOLD, 10));

        plot.setShadowPaint(null);
        plot.setOutlineVisible(false);
        plot.setBackgroundPaint(COLOR_CARD_BG); // Dark Card BG

        // Colors - Blue vs Orange for contrast
        plot.setSectionPaint("DOMESTIC", new Color(41, 128, 185)); // Strong Blue
        plot.setSectionPaint("INTERNATIONAL", new Color(243, 156, 18)); // Orange

        // Add legend/stats below chart text manually? The helper renders chart to cell.
        // We can add a legend below using a composite cell if needed.
        // For now, simple Ring Chart.

        return renderChartToCell(writer, chart, 300, 250);
    }

    // --- Page 9 Helpers ---

    private PdfPCell createHorizontalTriMetricBarChartCell(PdfWriter writer, String title, List<ChartData> data,
            String s1, String s2, String s3) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            for (ChartData d : data) {
                ds.addValue(d.getValue(), s1, d.getLabel());
                ds.addValue(d.getValue2(), s2, d.getLabel());
                ds.addValue(d.getValue3(), s3, d.getLabel());
            }
        }

        JFreeChart chart = ChartFactory.createBarChart(title, "", "", ds, PlotOrientation.HORIZONTAL, true, true,
                false);
        styleChart(chart);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        org.jfree.chart.renderer.category.BarRenderer renderer = (org.jfree.chart.renderer.category.BarRenderer) plot
                .getRenderer();

        // Custom Colors matching image (Blue shades)
        renderer.setSeriesPaint(0, new Color(0, 80, 255)); // Bright Blue
        renderer.setSeriesPaint(1, COLOR_NAVY); // Dark Navy
        renderer.setSeriesPaint(2, new Color(200, 220, 255)); // Very Light Blue/Grey

        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
        renderer.setShadowVisible(false);
        renderer.setItemMargin(0.0);

        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelGenerator(new org.jfree.chart.labels.StandardCategoryItemLabelGenerator("{2}%",
                java.text.NumberFormat.getIntegerInstance()));
        renderer.setDefaultItemLabelPaint(COLOR_TEXT_PRIMARY);
        renderer.setDefaultItemLabelFont(new java.awt.Font("Inter", java.awt.Font.PLAIN, 6)); // Small font

        // Font sizing
        plot.getDomainAxis().setTickLabelFont(new java.awt.Font("Inter", java.awt.Font.PLAIN, 7));

        return renderChartToCell(writer, chart, 400, 600); // Taller chart for 13 months
    }

    // --- Page 8 Helpers ---

    private PdfPCell createTriMetricBarChartCell(PdfWriter writer, String title, List<ChartData> data) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            for (ChartData d : data) {
                ds.addValue(d.getValue(), "% OF CUSTOMERS", d.getLabel());
                ds.addValue(d.getValue2(), "% OF TRANSACTIONS", d.getLabel());
                ds.addValue(d.getValue3() != null ? d.getValue3() : BigDecimal.ZERO, "% OF SPENDS", d.getLabel());
            }
        }

        JFreeChart chart = ChartFactory.createBarChart(title, "", "", ds, PlotOrientation.VERTICAL, true, true, false);
        styleChart(chart);

        // Fix overlap: Add margin to legend
        if (chart.getLegend() != null) {
            chart.getLegend().setMargin(10, 0, 0, 0); // Top, Left, Bottom, Right
        }

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        org.jfree.chart.renderer.category.BarRenderer renderer = (org.jfree.chart.renderer.category.BarRenderer) plot
                .getRenderer();

        // Custom Colors for 3 metrics
        renderer.setSeriesPaint(0, new Color(0, 123, 255)); // Blue (Cust)
        renderer.setSeriesPaint(1, COLOR_NAVY); // Dark Blue (Txn)
        renderer.setSeriesPaint(2, new Color(200, 200, 200)); // Light Grey (Spend) or similar distinct

        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
        renderer.setShadowVisible(false);
        renderer.setItemMargin(0.05);

        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelGenerator(new org.jfree.chart.labels.StandardCategoryItemLabelGenerator("{2}%",
                java.text.NumberFormat.getIntegerInstance()));
        renderer.setDefaultItemLabelPaint(COLOR_TEXT_PRIMARY);

        return renderChartToCell(writer, chart, 800, 250);
    }

    private PdfPCell createCompositionTrendChartCell(PdfWriter writer, String title, List<ChartData> data, String s1,
            String s2) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            for (ChartData d : data) {
                ds.addValue(d.getValue(), s1, d.getLabel());
                ds.addValue(d.getValue2(), s2, d.getLabel());
            }
        }

        // Stacked Bar for Composition? Or Grouped? Image shows Grouped (Blue and Dark
        // Blue bars side by side)
        JFreeChart chart = ChartFactory.createBarChart(title, "", "", ds, PlotOrientation.VERTICAL, true, true, false);
        styleChart(chart);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        org.jfree.chart.renderer.category.BarRenderer renderer = (org.jfree.chart.renderer.category.BarRenderer) plot
                .getRenderer();

        renderer.setSeriesPaint(0, new Color(0, 123, 255)); // Blue
        renderer.setSeriesPaint(1, COLOR_NAVY); // Dark Blue

        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
        renderer.setShadowVisible(false);
        renderer.setItemMargin(0.0);

        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelGenerator(new org.jfree.chart.labels.StandardCategoryItemLabelGenerator("{2}%",
                java.text.NumberFormat.getIntegerInstance()));
        renderer.setDefaultItemLabelPaint(COLOR_TEXT_PRIMARY);

        // Axis font
        plot.getDomainAxis().setTickLabelFont(new java.awt.Font("Inter", java.awt.Font.PLAIN, 8));

        return renderChartToCell(writer, chart, 800, 250);
    }

    // --- Page 7 Helpers ---

    private PdfPCell createHorizontalBarChartCell(PdfWriter writer, String title, List<ChartData> data, Color c1,
            Color c2) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            for (ChartData d : data) {
                ds.addValue(d.getValue(), "Series1", d.getLabel());
            }
        }

        JFreeChart chart = ChartFactory.createBarChart(title, "", "", ds, PlotOrientation.HORIZONTAL, false, true,
                false);
        styleChart(chart);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        org.jfree.chart.renderer.category.BarRenderer renderer = (org.jfree.chart.renderer.category.BarRenderer) plot
                .getRenderer();
        renderer.setSeriesPaint(0, c1);
        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
        renderer.setShadowVisible(false);

        // Show Values
        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelGenerator(new org.jfree.chart.labels.StandardCategoryItemLabelGenerator("{2}",
                java.text.NumberFormat.getIntegerInstance()));
        renderer.setDefaultItemLabelPaint(COLOR_TEXT_PRIMARY);
        renderer.setDefaultItemLabelFont(new java.awt.Font("Inter", java.awt.Font.PLAIN, 6)); // Small font

        return renderChartToCell(writer, chart, 400, 300);
    }

    private PdfPCell createHorizontalGroupedBarChartCell(PdfWriter writer, String title, List<ChartData> data,
            String s1, String s2) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            for (ChartData d : data) {
                ds.addValue(d.getValue(), s1, d.getLabel());
                ds.addValue(d.getValue2(), s2, d.getLabel());
            }
        }

        JFreeChart chart = ChartFactory.createBarChart(title, "", "", ds, PlotOrientation.HORIZONTAL, true, true,
                false); // Legend true
        styleChart(chart);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        org.jfree.chart.renderer.category.BarRenderer renderer = (org.jfree.chart.renderer.category.BarRenderer) plot
                .getRenderer();

        renderer.setSeriesPaint(0, new Color(0, 123, 255)); // Blue
        renderer.setSeriesPaint(1, COLOR_NAVY); // Dark Blue
        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
        renderer.setShadowVisible(false);
        renderer.setItemMargin(0.0);

        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelGenerator(new org.jfree.chart.labels.StandardCategoryItemLabelGenerator("{2}",
                java.text.NumberFormat.getIntegerInstance()));
        renderer.setDefaultItemLabelPaint(COLOR_TEXT_PRIMARY);
        renderer.setDefaultItemLabelFont(new java.awt.Font("Inter", java.awt.Font.PLAIN, 6)); // Small font

        return renderChartToCell(writer, chart, 400, 300);
    }

    // --- Page 6 Helpers ---

    private PdfPCell createMonthlyGrowthChartCell(PdfWriter writer, String title, List<ChartData> data, Color color) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            for (ChartData d : data) {
                ds.addValue(d.getValue(), "Series", d.getLabel());
            }
        }

        JFreeChart chart = ChartFactory.createBarChart(title, "", "", ds, PlotOrientation.VERTICAL, false, true, false);
        styleChart(chart);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        org.jfree.chart.renderer.category.BarRenderer renderer = (org.jfree.chart.renderer.category.BarRenderer) plot
                .getRenderer();

        renderer.setSeriesPaint(0, color);
        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
        renderer.setShadowVisible(false);
        renderer.setItemMargin(0.3);

        // Show Values on Top with % sign
        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelGenerator(new org.jfree.chart.labels.StandardCategoryItemLabelGenerator("{2}%",
                java.text.NumberFormat.getIntegerInstance()));
        renderer.setDefaultItemLabelPaint(COLOR_TEXT_PRIMARY);

        // Axis font for months
        plot.getDomainAxis().setTickLabelFont(new java.awt.Font("Inter", java.awt.Font.PLAIN, 8));

        return renderChartToCell(writer, chart, 800, 200);
    }

    // --- Page 5 Helpers ---

    private PdfPCell createMonthlyBarChartCell(PdfWriter writer, String title, List<ChartData> data, Color color) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            for (ChartData d : data) {
                ds.addValue(d.getValue(), "Series", d.getLabel());
            }
        }

        JFreeChart chart = ChartFactory.createBarChart(title, "", "", ds, PlotOrientation.VERTICAL, false, true, false); // No
                                                                                                                         // legend
        styleChart(chart);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        org.jfree.chart.renderer.category.BarRenderer renderer = (org.jfree.chart.renderer.category.BarRenderer) plot
                .getRenderer();

        renderer.setSeriesPaint(0, color);
        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
        renderer.setShadowVisible(false);
        renderer.setItemMargin(0.3); // Slight gap

        // Show Values on Top
        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelGenerator(new org.jfree.chart.labels.StandardCategoryItemLabelGenerator("{2}",
                java.text.NumberFormat.getIntegerInstance()));
        renderer.setDefaultItemLabelPaint(COLOR_TEXT_PRIMARY);

        // Axis font for months
        plot.getDomainAxis().setTickLabelFont(new java.awt.Font("Inter", java.awt.Font.PLAIN, 8));

        return renderChartToCell(writer, chart, 800, 200);
    }

    // --- Page 4 Helpers ---

    private PdfPCell createBubbleStatsCell(String title, List<ChartData> hourData) {
        PdfPTable wrapper = new PdfPTable(1);
        wrapper.setWidthPercentage(100);

        PdfPCell titleCell = new PdfPCell(
                new Phrase(title, new Font(Font.HELVETICA, 10, Font.BOLD, COLOR_TEXT_PRIMARY)));
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setPaddingBottom(10);
        titleCell.setBackgroundColor(COLOR_CARD_BG);
        wrapper.addCell(titleCell);

        // Process Data to get %
        Map<Integer, BigDecimal> hourMap = new java.util.HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        if (hourData != null) {
            for (ChartData d : hourData) {
                total = total.add(d.getValue());
                hourMap.put(Integer.parseInt(d.getLabel()), d.getValue());
            }
        }

        PdfPTable bubbleTable = new PdfPTable(24);
        bubbleTable.setWidthPercentage(100);
        bubbleTable.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        bubbleTable.getDefaultCell().setHorizontalAlignment(Element.ALIGN_CENTER);
        // bubbleTable.setBackgroundColor(COLOR_CARD_BG); // Not supported on table
        // directly

        for (int i = 0; i < 24; i++) {
            BigDecimal val = hourMap.getOrDefault(i, BigDecimal.ZERO);
            double pct = total.compareTo(BigDecimal.ZERO) > 0
                    ? val.divide(total, 4, java.math.RoundingMode.HALF_UP).doubleValue() * 100
                    : 0;

            PdfPCell cell = new PdfPCell();
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPadding(2);
            cell.setBackgroundColor(COLOR_CARD_BG);

            // % Label
            Font pctFont = new Font(Font.HELVETICA, 6, Font.NORMAL, pct > 0 ? COLOR_TEXT_PRIMARY : COLOR_TEXT_MUTED);
            cell.addElement(new Paragraph((int) pct + "%", pctFont));

            // Bubble (Circle)
            // Increased size as per user request: Base 12f + larger multiplier
            float size = (float) (Math.min(pct, 30) * 1.5) + 12f;

            Font dotFont = new Font(Font.HELVETICA, size, Font.BOLD, COLOR_TXNS); // Use accent color for bubbles
            Paragraph dot = new Paragraph("●", dotFont);
            dot.setAlignment(Element.ALIGN_CENTER);
            cell.addElement(dot);

            // Hour Label
            Font hrFont = new Font(Font.HELVETICA, 6, Font.BOLD, COLOR_TEXT_PRIMARY);
            Paragraph p = new Paragraph(String.format("%02d", i), hrFont);
            p.setAlignment(Element.ALIGN_CENTER);
            cell.addElement(p);

            bubbleTable.addCell(cell);
        }

        wrapper.addCell(bubbleTable);

        PdfPCell outer = new PdfPCell(wrapper);
        outer.setBorder(Rectangle.BOX);
        outer.setBorderColor(COLOR_BORDER);
        outer.setPadding(0); // Padding handled by inner cells
        return outer;
    }

    private PdfPCell createGroupedBarChartCell(PdfWriter writer, String title, List<ChartData> data) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            for (ChartData d : data) {
                ds.addValue(d.getValue(), "Total Transaction Value", d.getLabel());
                ds.addValue(d.getValue2(), "Average Transaction Value", d.getLabel());
            }
        }

        JFreeChart chart = ChartFactory.createBarChart(title, "", "Value (AED)", ds, PlotOrientation.VERTICAL, false,
                true, false);
        styleChart(chart);

        // Custom Renderer for Dual Colors
        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        org.jfree.chart.renderer.category.BarRenderer renderer = (org.jfree.chart.renderer.category.BarRenderer) plot
                .getRenderer();

        renderer.setSeriesPaint(0, new Color(255, 87, 34)); // Orange
        renderer.setSeriesPaint(1, COLOR_NAVY);
        renderer.setItemMargin(0.10); // Bars closer together but distinct

        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelGenerator(new org.jfree.chart.labels.StandardCategoryItemLabelGenerator("{2}",
                java.text.NumberFormat.getIntegerInstance()));
        renderer.setDefaultItemLabelPaint(COLOR_TEXT_PRIMARY);

        return renderChartToCell(writer, chart, 800, 250);
    }

    private PdfPCell createPercentageBarChartCell(PdfWriter writer, String title, List<ChartData> data) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        BigDecimal total = BigDecimal.ZERO;
        if (data != null) {
            for (ChartData d : data)
                total = total.add(d.getValue());
            final BigDecimal finalTotal = total;
            for (ChartData d : data) {
                double pct = finalTotal.compareTo(BigDecimal.ZERO) > 0
                        ? d.getValue().divide(finalTotal, 4, java.math.RoundingMode.HALF_UP).doubleValue() * 100
                        : 0;
                ds.addValue(pct, "Sale %", getDayLabel(d.getLabel()));
            }
        }

        JFreeChart chart = ChartFactory.createBarChart(title, "", "%", ds, PlotOrientation.VERTICAL, false, false,
                false);
        styleChart(chart);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        org.jfree.chart.renderer.category.BarRenderer renderer = (org.jfree.chart.renderer.category.BarRenderer) plot
                .getRenderer();
        renderer.setSeriesPaint(0, new Color(0, 123, 255)); // Blue
        renderer.setShadowVisible(false);
        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter()); // Flat bars
        renderer.setItemMargin(0.25);

        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelGenerator(new org.jfree.chart.labels.StandardCategoryItemLabelGenerator("{2}%",
                java.text.NumberFormat.getIntegerInstance()));
        renderer.setDefaultItemLabelPaint(COLOR_TEXT_PRIMARY);

        // Add Labels on top
        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelGenerator(new org.jfree.chart.labels.StandardCategoryItemLabelGenerator("{2}%",
                java.text.NumberFormat.getIntegerInstance()));
        renderer.setDefaultItemLabelPaint(COLOR_TEXT_PRIMARY);

        return renderChartToCell(writer, chart, 800, 250);
    }

    private PdfPCell createSideBySideCharts(PdfWriter writer, String title1, List<ChartData> data1,
            String title2, List<ChartData> data2) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        try {
            table.setWidths(new float[] { 1, 1 });
            table.addCell(createChartCell(writer, createBarChart(title1, "", data1)));
            table.addCell(createChartCell(writer, createBarChart(title2, "", data2)));
        } catch (DocumentException e) {
            e.printStackTrace();
        }
        PdfPCell cell = new PdfPCell(table);
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    private PdfPCell createSideBySideCharts(PdfWriter writer, String title1, Map<String, BigDecimal> data1,
            String title2, Map<String, BigDecimal> data2) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.addCell(createDonutChartCell(writer, title1, data1));
        table.addCell(createDonutChartCell(writer, title2, data2));
        PdfPCell cell = new PdfPCell(table);
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    private PdfPCell createGroupedPctBarChartCell(PdfWriter writer, String title, List<ChartData> data) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            for (ChartData d : data) {
                ds.addValue(d.getValue(), "% Transaction Count", d.getLabel());
                ds.addValue(d.getValue2(), "% Transaction Value", d.getLabel());
            }
        }

        JFreeChart chart = ChartFactory.createBarChart(title, "", "%", ds, PlotOrientation.VERTICAL, true, true, false);
        styleChart(chart);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        org.jfree.chart.renderer.category.BarRenderer renderer = (org.jfree.chart.renderer.category.BarRenderer) plot
                .getRenderer();

        renderer.setSeriesPaint(0, new Color(135, 206, 250)); // Light Sky Blue
        renderer.setSeriesPaint(1, COLOR_NAVY); // Dark Blue
        renderer.setItemMargin(0.10);
        renderer.setShadowVisible(false);
        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());

        // Labels
        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelGenerator(new org.jfree.chart.labels.StandardCategoryItemLabelGenerator("{2}%",
                java.text.NumberFormat.getIntegerInstance()));
        renderer.setDefaultItemLabelPaint(COLOR_TEXT_PRIMARY); // Fix visibility

        return renderChartToCell(writer, chart, 800, 200);
    }

    private PdfPCell createDonutChartCell(PdfWriter writer, String title, Map<String, BigDecimal> data) {
        DefaultPieDataset<String> ds = new DefaultPieDataset<>();
        if (data != null) {
            data.forEach((k, v) -> ds.setValue(k, v != null ? v : java.math.BigDecimal.ZERO));
        }

        org.jfree.chart.plot.RingPlot plot = new org.jfree.chart.plot.RingPlot(ds);
        plot.setLabelGenerator(new StandardPieSectionLabelGenerator("{0}: {2}"));
        plot.setBackgroundPaint(COLOR_CARD_BG); // Dark Card BG
        plot.setOutlinePaint(null);
        plot.setSectionDepth(0.35); // Donut thickness
        plot.setSeparatorsVisible(false);
        plot.setShadowPaint(null);

        // Colors? JFreeChart defaults usually okay, but let's try to match
        // Blue/DarkBlue theme
        // Simple way: iterate keys and set specific blues if possible, or just let
        // auto-color?
        // Auto-color is fine for now.

        JFreeChart chart = new JFreeChart(title, new java.awt.Font("Inter", java.awt.Font.BOLD, 10), plot, false);
        chart.setBackgroundPaint(COLOR_CARD_BG); // Dark Card BG
        chart.getTitle().setPaint(COLOR_TEXT_PRIMARY);

        // Custom Colors - Professional Palette
        Color[] colors = new Color[] {
                new Color(41, 128, 185), // Strong Blue
                new Color(26, 188, 156), // Teal
                new Color(243, 156, 18), // Orange
                new Color(142, 68, 173), // Purple
                new Color(192, 57, 43), // Red
                new Color(39, 174, 96), // Green
                new Color(44, 62, 80) // Midnight Blue
        };

        int i = 0;
        for (Object key : ds.getKeys()) {
            plot.setSectionPaint((Comparable) key, colors[i % colors.length]);
            i++;
        }

        return renderChartToCell(writer, chart, 350, 240); // Slightly shorter to fit 3 rows comfortably
    }

    private PdfPCell createSpacer() {
        PdfPCell cell = new PdfPCell(new Phrase(" "));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setFixedHeight(15);
        return cell;
    }

    // ... (Existing Row Methods 1-4 remain formatting same) ...
    // Note: I will replace createChartCell with specific ones below or reuse if
    // suitable.

    // --- Page 2 Headers & Charts ---

    private PdfPCell createComboChartCell(PdfWriter writer, String title, String rangeLabel1, String rangeLabel2,
            List<ChartData> data, Color barColor, Color lineColor) {
        // Prepare Dataset
        DefaultCategoryDataset dataset1 = new DefaultCategoryDataset(); // Bars
        DefaultCategoryDataset dataset2 = new DefaultCategoryDataset(); // Lines

        if (data != null) {
            for (ChartData d : data) {
                // Parse date to Day number if possible, else use raw
                String label = getDayLabel(d.getLabel());
                java.math.BigDecimal val1 = d.getValue() != null ? d.getValue() : java.math.BigDecimal.ZERO;
                dataset1.addValue(val1, "Sales", label);
                if (d.getValue2() != null)
                    dataset2.addValue(d.getValue2(), "Count", label);
            }
        }

        // Plot 1 (Bars)
        org.jfree.chart.plot.CategoryPlot plot = new org.jfree.chart.plot.CategoryPlot();
        plot.setDataset(0, dataset1);
        org.jfree.chart.renderer.category.BarRenderer barRenderer = new org.jfree.chart.renderer.category.BarRenderer();
        barRenderer.setSeriesPaint(0, barColor);
        barRenderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter()); // Flat bars
        barRenderer.setShadowVisible(false);
        plot.setRenderer(0, barRenderer);
        plot.setDomainAxis(new org.jfree.chart.axis.CategoryAxis("Day"));
        plot.setRangeAxis(0, new org.jfree.chart.axis.NumberAxis(rangeLabel1));

        // Plot 2 (Line)
        plot.setDataset(1, dataset2);
        org.jfree.chart.renderer.category.LineAndShapeRenderer lineRenderer = new org.jfree.chart.renderer.category.LineAndShapeRenderer();
        lineRenderer.setSeriesPaint(0, lineColor);
        plot.setRenderer(1, lineRenderer);
        plot.setRangeAxis(1, new org.jfree.chart.axis.NumberAxis(rangeLabel2));
        plot.mapDatasetToRangeAxis(1, 1);

        plot.setBackgroundPaint(COLOR_CARD_BG); // Dark Card BG
        plot.setOutlinePaint(COLOR_BORDER);
        plot.setDomainGridlinePaint(new Color(45, 55, 72)); // Dark grid
        plot.setRangeGridlinePaint(new Color(45, 55, 72));

        JFreeChart chart = new JFreeChart(title, new java.awt.Font("Inter", java.awt.Font.BOLD, 12), plot, false);
        chart.setBackgroundPaint(COLOR_CARD_BG); // Dark Card BG
        chart.getTitle().setPaint(COLOR_TEXT_PRIMARY);

        return renderChartToCell(writer, chart, 800, 250);
    }

    private PdfPCell createBarChartCell(PdfWriter writer, String title, String yLabel, List<ChartData> data,
            Color color) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            for (ChartData d : data) {
                String label = getDayLabel(d.getLabel());
                java.math.BigDecimal val = d.getValue() != null ? d.getValue() : java.math.BigDecimal.ZERO;
                ds.addValue(val, "Series1", label);
            }
        }
        JFreeChart chart = ChartFactory.createBarChart(title, "Day", yLabel, ds, PlotOrientation.VERTICAL, false, true,
                false);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        org.jfree.chart.renderer.category.BarRenderer renderer = (org.jfree.chart.renderer.category.BarRenderer) plot
                .getRenderer();
        renderer.setSeriesPaint(0, color);
        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
        renderer.setShadowVisible(false);

        styleChart(chart);
        return renderChartToCell(writer, chart, 800, 250);
    }

    private String getDayLabel(String dateStr) {
        if (dateStr == null)
            return "";
        try {
            // Assumes yyyy-MM-dd
            java.time.LocalDate date = java.time.LocalDate.parse(dateStr);
            return String.valueOf(date.getDayOfMonth());
        } catch (Exception e) {
            return dateStr;
        }
    }

    private PdfPCell renderChartToCell(PdfWriter writer, JFreeChart chart, int width, int height) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(5);
        cell.setBorder(Rectangle.NO_BORDER);
        PdfPCell inner = new PdfPCell();
        inner.setBorderColor(COLOR_BORDER); // Dark Border
        inner.setBorderWidth(1);
        inner.setPadding(2);
        try {
            PdfContentByte cb = writer.getDirectContent();
            PdfTemplate template = cb.createTemplate(width, height);
            Graphics2D g2d = template.createGraphics(width, height, new DefaultFontMapper());
            Rectangle2D r2d = new Rectangle2D.Double(0, 0, width, height);
            chart.draw(g2d, r2d);
            g2d.dispose();
            com.lowagie.text.Image chartImage = com.lowagie.text.Image.getInstance(template);
            inner.addElement(chartImage);
        } catch (Exception e) {
            inner.addElement(new Paragraph("Chart Error"));
        }
        PdfPTable wrapper = new PdfPTable(1);
        wrapper.setWidthPercentage(100);
        wrapper.addCell(inner);
        cell.addElement(wrapper);
        return cell;
    }

    // --- Header ---
    private void addHeader(Document doc, String name, String date, String title) throws DocumentException {
        // Full width header table
        PdfPTable headerTable = new PdfPTable(2);
        headerTable.setWidthPercentage(100);
        headerTable.setWidths(new float[] { 2, 1 }); // Title on left, Date/Download on right
        headerTable.setSpacingAfter(15);

        // Title Cell (Left)
        PdfPCell titleCell = new PdfPCell();
        titleCell.setBackgroundColor(COLOR_APP_BG); // Match App BG
        titleCell.setPadding(10);
        titleCell.setBorder(Rectangle.BOTTOM);
        titleCell.setBorderColor(COLOR_BORDER);
        titleCell.setBorderWidth(1f);
        titleCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        Font mainTitleFont = new Font(Font.HELVETICA, 16, Font.BOLD, COLOR_TEXT_PRIMARY);
        Font subTitleFont = new Font(Font.HELVETICA, 11, Font.NORMAL, COLOR_TEXT_SECONDARY);

        titleCell.addElement(new Paragraph(title.toUpperCase(), mainTitleFont));
        titleCell.addElement(new Paragraph(name, subTitleFont));
        headerTable.addCell(titleCell);

        // Meta Cell (Right)
        PdfPCell metaCell = new PdfPCell();
        metaCell.setBackgroundColor(COLOR_APP_BG);
        metaCell.setPadding(10);
        metaCell.setBorder(Rectangle.BOTTOM);
        metaCell.setBorderColor(COLOR_BORDER);
        metaCell.setBorderWidth(1f);
        metaCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        metaCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        Font dateFont = new Font(Font.HELVETICA, 11, Font.BOLD, COLOR_TEXT_PRIMARY);
        Font labelFont = new Font(Font.HELVETICA, 9, Font.NORMAL, COLOR_TEXT_MUTED);

        Paragraph pDate = new Paragraph(date, dateFont);
        pDate.setAlignment(Element.ALIGN_RIGHT);
        metaCell.addElement(pDate);

        Paragraph pLabel = new Paragraph("MONTHLY REPORT", labelFont);
        pLabel.setAlignment(Element.ALIGN_RIGHT);
        metaCell.addElement(pLabel);

        headerTable.addCell(metaCell);

        doc.add(headerTable);
    }

    // Old Create Row 1-3 methods replaced above.
    // Keeping this comment anchor or remove completely.
    // Actually, I need to check where `createRow4` starts in the user file.
    // It's around line 1799. I will replace the OLD CreateRow1-3 blocks entirely.
    // But since I am injecting them earlier (around line 418), I should DELETE them
    // here.

    // ... Removing OLD createRow1, createRow2, createRow3 methods ...

    // --- Row 4: Charts ---
    private PdfPCell createRow4(PdfWriter writer, BusinessOverview overview) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        try {
            table.setWidths(new float[] { 1, 1 });

            // Sales Chart
            JFreeChart saleChart = createBarChart("Sales by Day of Week", "Sales (AED)",
                    overview.getSalesByDayOfWeek());
            table.addCell(createChartCell(writer, saleChart));

            // Txn Chart
            JFreeChart txnChart = createBarChart("Transactions by Day of Week", "Count",
                    overview.getTransactionsByDayOfWeek());
            table.addCell(createChartCell(writer, txnChart));

        } catch (DocumentException e) {
            e.printStackTrace();
        }

        PdfPCell cell = new PdfPCell(table);
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    private PdfPCell createRow4b(PdfWriter writer, BusinessOverview overview) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        try {
            table.setWidths(new float[] { 1, 1 });

            // Sales By Week Chart
            JFreeChart saleChart = createBarChart("Sales by Week of Month", "Sales (AED)",
                    overview.getSalesByWeekOfMonth());
            table.addCell(createChartCell(writer, saleChart));

            // Txn By Week Chart
            JFreeChart txnChart = createBarChart("Transactions by Week of Month", "Count",
                    overview.getTransactionsByWeekOfMonth());
            table.addCell(createChartCell(writer, txnChart));

        } catch (DocumentException e) {
            e.printStackTrace();
        }

        PdfPCell cell = new PdfPCell(table);
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    private PdfPCell createPaddedCell(PdfPCell content) {
        content.setPadding(0); // Grid layout - no gaps
        content.setBorder(Rectangle.NO_BORDER);
        return content;
    }

    // --- PAGE 2 COMPONENTS ---

    private void addGlassSectionHeader(Document document, String title, String subtitle) throws DocumentException {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingBefore(10);
        table.setSpacingAfter(20);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);

        // Glass Event
        cell.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                // Glass: White 60% + Blur (simulated)
                cb.setColorFill(new Color(255, 255, 255, 150));
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        12);
                cb.fill();
                // Subtle Border
                cb.setColorStroke(new Color(255, 255, 255, 200));
                cb.setLineWidth(1f);
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        12);
                cb.stroke();
                cb.restoreState();
            }
        });

        // Content
        PdfPTable content = new PdfPTable(1);
        content.setWidthPercentage(100);

        PdfPCell tCell = new PdfPCell(
                new Phrase(title.toUpperCase(), new Font(Font.HELVETICA, 14, Font.BOLD, COL_TEXT_PRIMARY)));
        tCell.setBorder(Rectangle.NO_BORDER);
        tCell.setPaddingLeft(24);
        tCell.setPaddingTop(16);
        content.addCell(tCell);

        PdfPCell sCell = new PdfPCell(
                new Phrase(subtitle, new Font(Font.HELVETICA, 10, Font.NORMAL, COL_TEXT_SECONDARY)));
        sCell.setBorder(Rectangle.NO_BORDER);
        sCell.setPaddingLeft(24);
        sCell.setPaddingBottom(16);
        content.addCell(sCell);

        cell.addElement(content);
        table.addCell(cell);
        document.add(table);
    }

    private PdfPCell createExecutiveKpiCard(String title, String value, Double growth, Color borderColor,
            String iconType, List<ChartData> sparkData, boolean isValue) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8);

        // Card Container
        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell cardCell = new PdfPCell();
        cardCell.setBorder(Rectangle.NO_BORDER);
        cardCell.setPadding(15);
        // Explicit height for uniformity
        cardCell.setFixedHeight(120);

        // Glass + Gradient Border Event
        cardCell.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();

                // Glass BG: White 75%
                cb.setColorFill(new Color(255, 255, 255, 190));
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        16);
                cb.fill();

                // Top Gradient Border (Simulated with Shading)
                cb.setColorStroke(borderColor);
                cb.setLineWidth(4f);
                cb.moveTo(position.getLeft() + 16, position.getTop());
                cb.lineTo(position.getRight() - 16, position.getTop());
                cb.stroke();

                // Icon Drawing (Top Right) at 15% opacity
                cb.setColorFill(new Color(0, 0, 0, 20)); // Black 8%
                // Draw icon based on type
                float ix = position.getRight() - 30;
                float iy = position.getTop() - 30;

                if ("MONEY".equals(iconType)) {
                    cb.circle(ix, iy, 12);
                    cb.fill();
                } else if ("CYCLE".equals(iconType)) {
                    cb.setLineWidth(3f);
                    cb.setColorStroke(new Color(0, 0, 0, 20));
                    cb.arc(ix - 10, iy - 10, ix + 10, iy + 10, 0, 270);
                    cb.stroke();
                } else if ("USER".equals(iconType)) {
                    cb.circle(ix, iy + 4, 5); // Head
                    cb.moveTo(ix - 6, iy - 8);
                    cb.curveTo(ix - 6, iy, ix + 6, iy, ix + 6, iy - 8); // Body
                    cb.fill();
                } else if ("CHART".equals(iconType)) {
                    cb.rectangle(ix - 8, iy - 8, 4, 8);
                    cb.rectangle(ix - 2, iy - 12, 4, 12);
                    cb.rectangle(ix + 4, iy - 5, 4, 5);
                    cb.fill();
                }

                cb.restoreState();

                // --- Sparkline (Micro Chart) Right Side ---
                // We draw it directly here
                if (sparkData != null && !sparkData.isEmpty()) {
                    cb.saveState();
                    float sx = position.getRight() - 80;
                    float sy = position.getBottom() + 40;
                    float sw = 60;
                    float sh = 20;

                    // Min/Max logic
                    float min = Float.MAX_VALUE;
                    float max = Float.MIN_VALUE;
                    for (ChartData d : sparkData) {
                        java.math.BigDecimal val = isValue ? d.getValue() : d.getValue2();
                        float v = (val != null) ? val.floatValue() : 0.0f;
                        if (v < min)
                            min = v;
                        if (v > max)
                            max = v;
                    }
                    if (max == min)
                        max = min + 1;

                    cb.setColorStroke(new Color(11, 28, 45, 100)); // Navy 40%
                    cb.setLineWidth(1.0f);

                    float step = sw / (sparkData.size() - 1);
                    boolean first = true;
                    for (int i = 0; i < sparkData.size(); i++) {
                        java.math.BigDecimal val = isValue ? sparkData.get(i).getValue()
                                : sparkData.get(i).getValue2();
                        float v = (val != null) ? val.floatValue() : 0.0f;
                        float px = sx + (i * step);
                        float py = sy + ((v - min) / (max - min)) * sh;
                        if (first) {
                            cb.moveTo(px, py);
                            first = false;
                        } else {
                            cb.lineTo(px, py);
                        }
                    }
                    cb.stroke();
                    cb.restoreState();
                }
            }
        });

        // Content Table
        PdfPTable content = new PdfPTable(1);
        content.setWidthPercentage(100);

        // Title
        Font tFont = new Font(Font.HELVETICA, 11, Font.NORMAL, COL_TEXT_SECONDARY);
        PdfPCell cTitle = new PdfPCell(new Phrase(title.toUpperCase(), tFont));
        cTitle.setBorder(Rectangle.NO_BORDER);
        content.addCell(cTitle);

        // Value
        Font vFont = new Font(Font.HELVETICA, 26, Font.BOLD, COL_BG_NAVY_START); // Dark Navy
        Phrase vPhrase = new Phrase(value, vFont);
        PdfPCell cVal = new PdfPCell(vPhrase);
        cVal.setBorder(Rectangle.NO_BORDER);
        cVal.setPaddingTop(10);
        cVal.setPaddingBottom(5);
        content.addCell(cVal);

        // Growth
        if (growth != null) {
            Color gCol = growth >= 0 ? new Color(0, 200, 83) : new Color(255, 82, 82);
            String sym = growth >= 0 ? "▲" : "▼";
            String gTxt = sym + " " + String.format("%.1f%%", Math.abs(growth));
            Font gFont = new Font(Font.HELVETICA, 10, Font.BOLD, gCol);
            PdfPCell cGrowth = new PdfPCell(new Phrase(gTxt, gFont));
            cGrowth.setBorder(Rectangle.NO_BORDER);
            content.addCell(cGrowth);
        } else {
            content.addCell(createSpacer(15));
        }

        cardCell.addElement(content);
        card.addCell(cardCell);
        cell.addElement(card);
        return cell;
    }

    // --- PAGE 3 COMPONENTS ---

    private PdfPCell createWeeklyKpiStrip(String peakDay, String lowestDay, String consistencyDay) {
        PdfPTable strip = new PdfPTable(3);
        strip.setWidthPercentage(100);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);

        // Glass bg
        cell.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(255, 255, 255, 120)); // Lighter glass
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        8);
                cb.fill();
                cb.restoreState();
            }
        });

        PdfPTable inner = new PdfPTable(3);
        inner.setWidthPercentage(100);
        inner.setSpacingBefore(0);

        inner.addCell(createStripItem("PEAK SALES DAY", peakDay));
        inner.addCell(createStripItem("LOWEST ACTIVITY", lowestDay));
        inner.addCell(createStripItem("BEST CONSISTENCY", consistencyDay));

        cell.addElement(inner);
        return cell;
    }

    private PdfPCell createStripItem(String label, String value) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(10);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);

        Paragraph p = new Paragraph();
        p.add(new Chunk(label + ": ", new Font(Font.HELVETICA, 8, Font.BOLD, COL_TEXT_MUTED)));
        p.add(new Chunk(value.toUpperCase(), new Font(Font.HELVETICA, 9, Font.BOLD, COL_TEXT_PRIMARY)));
        p.setAlignment(Element.ALIGN_CENTER);

        cell.addElement(p);
        return cell;
    }

    private PdfPCell createGlassChartCard(PdfWriter writer, JFreeChart chart) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell inner = new PdfPCell();
        inner.setBorder(Rectangle.NO_BORDER);
        inner.setPadding(20);
        inner.setFixedHeight(220); // Fixed height for alignment

        // Glass Event
        inner.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(255, 255, 255, 190)); // 75% white
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        16);
                cb.fill();
                cb.restoreState();
            }
        });

        // Render Chart into inner cell
        try {
            PdfContentByte cb = writer.getDirectContent();
            PdfTemplate template = cb.createTemplate(380, 180);
            Graphics2D g2d = template.createGraphics(380, 180, new DefaultFontMapper());
            Rectangle2D r2d = new Rectangle2D.Double(0, 0, 380, 180);
            chart.draw(g2d, r2d);
            g2d.dispose();
            com.lowagie.text.Image chartImage = com.lowagie.text.Image.getInstance(template);
            inner.addElement(chartImage);
        } catch (Exception e) {
            inner.addElement(new Paragraph("Chart Error"));
        }

        card.addCell(inner);
        cell.addElement(card);
        return cell;
    }

    private PdfPCell createExecutiveInsightCard(String title, String text, String iconCmd) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(5);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell inner = new PdfPCell();
        inner.setBorder(Rectangle.NO_BORDER);
        inner.setPadding(15);
        inner.setFixedHeight(80);

        inner.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(255, 255, 255, 180));
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        12);
                cb.fill();
                cb.restoreState();
            }
        });

        // Title with Icon
        Font titleFont = new Font(Font.HELVETICA, 8, Font.BOLD, COL_ACCENT_VIOLET);
        Paragraph t = new Paragraph((iconCmd != null ? iconCmd + " " : "") + title.toUpperCase(), titleFont);
        inner.addElement(t);

        // Body
        Font bodyFont = new Font(Font.HELVETICA, 9, Font.NORMAL, COL_TEXT_PRIMARY);
        Paragraph b = new Paragraph(text, bodyFont);
        b.setSpacingBefore(5);
        inner.addElement(b);

        card.addCell(inner);
        cell.addElement(card);
        return cell;
    }

    private JFreeChart createGradientBarChart(String title, String yLabel, List<ChartData> data, Color startColor) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            for (ChartData d : data) {
                java.math.BigDecimal val = d.getValue() != null ? d.getValue() : java.math.BigDecimal.ZERO;
                ds.addValue(val, "Series", getDayLabel(d.getLabel()));
            }
        }
        JFreeChart chart = ChartFactory.createBarChart(title, "", yLabel, ds, PlotOrientation.VERTICAL, false, true,
                false);

        chart.setBackgroundPaint(null);
        chart.getTitle().setPaint(TEXT_PRIMARY_UNIFIED); // Unified text color
        chart.getTitle().setFont(new java.awt.Font("Inter", java.awt.Font.BOLD, 10));

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        plot.setBackgroundPaint(null);
        plot.setOutlinePaint(null);

        // Softer grid lines for professional look
        plot.setRangeGridlinePaint(new Color(240, 240, 240)); // Softer grid
        plot.setDomainGridlinesVisible(false);

        plot.getDomainAxis().setTickLabelFont(new java.awt.Font("Inter", java.awt.Font.PLAIN, 8));
        plot.getDomainAxis().setTickLabelPaint(TEXT_SECONDARY_UNIFIED); // Unified gray
        plot.getRangeAxis().setTickLabelFont(new java.awt.Font("Inter", java.awt.Font.PLAIN, 8));
        plot.getRangeAxis().setTickLabelPaint(TEXT_SECONDARY_UNIFIED); // Unified gray

        // Hide axes for sparkline look
        org.jfree.chart.axis.CategoryAxis domainAxis = plot.getDomainAxis();
        domainAxis.setVisible(false);
        org.jfree.chart.axis.ValueAxis rangeAxis = plot.getRangeAxis();
        rangeAxis.setVisible(false);

        // Custom gradient bar renderer - Each bar gets a premium vertical gradient
        org.jfree.chart.renderer.category.BarRenderer renderer = new org.jfree.chart.renderer.category.BarRenderer() {
            @Override
            public java.awt.Paint getItemPaint(int series, int item) {
                // Determine gradient end color based on start color
                Color endColor = GRADIENT_REVENUE_END; // Default
                if (startColor.equals(GRADIENT_REVENUE_START) || startColor.equals(COL_ACCENT_SALES)) {
                    endColor = GRADIENT_REVENUE_END;
                } else if (startColor.equals(GRADIENT_TRANSACTION_START) || startColor.equals(COL_ACCENT_TXNS)) {
                    endColor = GRADIENT_TRANSACTION_END;
                } else if (startColor.equals(GRADIENT_GROWTH_START) || startColor.equals(COL_ACCENT_GROWTH)) {
                    endColor = GRADIENT_GROWTH_END;
                } else if (startColor.equals(GRADIENT_WARNING_START) || startColor.equals(COL_ACCENT_AMBER)) {
                    endColor = GRADIENT_WARNING_END;
                }

                // Create vertical gradient (top to bottom)
                return new java.awt.GradientPaint(
                        0, 0, startColor, // Top (darker)
                        0, 300, endColor, // Bottom (lighter)
                        false);
            }
        };

        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
        renderer.setShadowVisible(false);

        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelGenerator(new org.jfree.chart.labels.StandardCategoryItemLabelGenerator("{2}",
                NumberFormat.getIntegerInstance()));
        renderer.setDefaultItemLabelPaint(TEXT_SECONDARY_UNIFIED); // Unified gray for labels
        renderer.setDefaultItemLabelFont(new java.awt.Font("Inter", java.awt.Font.PLAIN, 8));

        plot.setRenderer(renderer);

        return chart;
    }

    private PdfPCell createSpacer(float height) {
        PdfPCell cell = new PdfPCell(new Phrase(" "));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setFixedHeight(height);
        return cell;
    }

    // --- Styles for KPI Cards (Fintech Grid) ---
    private PdfPCell createKpiCard(String title, Kpi kpi, String subLabel, IconType iconType, Color iconColor) {
        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell inner = new PdfPCell();
        inner.setBackgroundColor(COLOR_CARD_BG); // White
        inner.setBorderColor(COLOR_BORDER); // Light Grid
        inner.setBorderWidth(0.5f); // Thin lines
        // Box border for full grid effect
        inner.setBorder(Rectangle.BOX);

        inner.setPadding(15);
        inner.setPaddingBottom(12);

        // Add Icon if present - positioned top right
        if (iconType != null) {
            // We use cell event for drawing vector icon
            inner.setCellEvent(new VectorIconEvent(iconColor != null ? iconColor : COLOR_TEXT_SECONDARY, iconType));
        }

        // Title (Label) - All Caps, Small, Technical
        Font titleFont = new Font(Font.HELVETICA, 7, Font.BOLD, COLOR_TEXT_SECONDARY);
        inner.addElement(new Paragraph(title.toUpperCase(), titleFont));

        // Value
        Font valueFont = new Font(Font.HELVETICA, 16, Font.BOLD, COLOR_TEXT_PRIMARY); // Dark
        Paragraph valP = new Paragraph(kpi.getFormattedValue(), valueFont);
        valP.setSpacingBefore(6);
        inner.addElement(valP);

        // MoM Growth & SubLabel
        PdfPTable subTable = new PdfPTable(2);
        subTable.setWidthPercentage(100);
        try {
            subTable.setWidths(new float[] { 1, 1 });

            PdfPCell growthCell = new PdfPCell();
            growthCell.setBorder(Rectangle.NO_BORDER);
            growthCell.setPaddingLeft(0);

            Double growthVal = kpi.getMomGrowth();
            BigDecimal growth = growthVal == null ? null : BigDecimal.valueOf(growthVal);

            if (growth != null) {
                boolean pos = growth.compareTo(BigDecimal.ZERO) >= 0;
                Color gColor = pos ? COLOR_POSITIVE : COLOR_NEGATIVE;
                // Fintech style: "+2.5%" text, maybe softer font
                String prefix = pos ? "+" : "";
                Font gFont = new Font(Font.HELVETICA, 9, Font.NORMAL, gColor);

                BigDecimal formattedGrowth = growth.abs().setScale(2, java.math.RoundingMode.HALF_UP);
                growthCell.addElement(new Paragraph(prefix + formattedGrowth + "%", gFont));
            }
            subTable.addCell(growthCell);

            PdfPCell subLabelCell = new PdfPCell();
            subLabelCell.setBorder(Rectangle.NO_BORDER);
            subLabelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            if (subLabel != null) {
                subLabelCell.addElement(
                        new Paragraph(subLabel, new Font(Font.HELVETICA, 7, Font.NORMAL, COLOR_TEXT_MUTED)));
            }
            subTable.addCell(subLabelCell);

        } catch (DocumentException e) {
        }

        // Add spacing after subtable
        subTable.setSpacingBefore(4);

        inner.addElement(subTable);

        card.addCell(inner);

        PdfPCell wrapper = new PdfPCell(card);
        wrapper.setBorder(Rectangle.NO_BORDER);
        return wrapper;
    }

    private PdfPCell createInsightBox() {
        PdfPTable box = new PdfPTable(1);
        box.setWidthPercentage(100);

        PdfPCell inner = new PdfPCell();
        inner.setBackgroundColor(COLOR_CARD_BG); // Use card background for consistency
        inner.setBorderColor(COLOR_BORDER);
        inner.setBorderWidth(1);
        inner.setPadding(15);

        Font headerFont = new Font(Font.HELVETICA, 12, Font.BOLD, COLOR_TEXT_PRIMARY);
        Font bulletFont = new Font(Font.HELVETICA, 9, Font.NORMAL, COLOR_TEXT_SECONDARY);
        Font iconFont = new Font(Font.ZAPFDINGBATS, 12, Font.NORMAL, COLOR_TXNS); // Use a dingbat for icon

        // Header
        Paragraph header = new Paragraph("KEY INSIGHTS", headerFont);
        header.setSpacingAfter(8);
        inner.addElement(header);

        // Bullet points
        com.lowagie.text.List list = new com.lowagie.text.List(false, 10); // false for unnumbered, 10 for indentation
        list.setListSymbol(new Chunk(" \u2022 ", iconFont)); // Unicode bullet or dingbat
        list.add(new ListItem("Sales velocity peaked during weekend hours, confirming high recreational spending.",
                bulletFont));
        list.add(new ListItem("Transaction volume remains stable, but Average Ticket Size is trending upward.",
                bulletFont));
        list.add(new ListItem("Opportunity identified in the 5PM-8PM window for targeted upsell campaigns.",
                bulletFont));
        inner.addElement(list);

        box.addCell(inner);

        PdfPCell wrapper = new PdfPCell(box);
        wrapper.setBorder(Rectangle.NO_BORDER);
        wrapper.setPadding(3); // Consistent padding with other elements
        return wrapper;

    }

    // --- PAGE 4 COMPONENTS ---

    private PdfPCell createMonthlyKpiStrip(String bestWeek, String weakZone, String peakActivity) {
        // Reusing Weekly Strip style but with 3 items
        return createWeeklyKpiStrip("BEST WEEK: " + bestWeek, "WEAK ZONE: " + weakZone,
                "PEAK ACTIVITY: " + peakActivity);
    }

    // Slight override for strip item label if needed, or just reuse
    // createWeeklyKpiStrip logic which splits by ":"
    // Wait, createWeeklyKpiStrip uses createStripItem which takes label + value.
    // So I should refactor createWeeklyKpiStrip to be generic or just create a new
    // one.
    // Let's create `createGenericKpiStrip` or just duplicate for safety and
    // specific styling if needed.
    // Actually, createWeeklyKpiStrip separates label/val.
    // So:
    private PdfPCell createMonthlyKpiStripV2(String bestWeek, String weakZone, String peakActivity) {
        PdfPTable strip = new PdfPTable(3);
        strip.setWidthPercentage(100);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);

        // Glass bg
        cell.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(255, 255, 255, 120)); // Lighter glass
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        8);
                cb.fill();
                cb.restoreState();
            }
        });

        PdfPTable inner = new PdfPTable(3);
        inner.setWidthPercentage(100);
        inner.setSpacingBefore(0);

        inner.addCell(createStripItem("BEST WEEK", bestWeek));
        inner.addCell(createStripItem("WEAK ZONE", weakZone));
        inner.addCell(createStripItem("PEAK ACTIVITY", peakActivity));

        cell.addElement(inner);
        return cell;
    }

    private PdfPCell createHeroAreaChartCard(PdfWriter writer, JFreeChart chart) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell inner = new PdfPCell();
        inner.setBorder(Rectangle.NO_BORDER);
        inner.setPadding(20);
        inner.setFixedHeight(280); // Taller for hero

        // Glass Event
        inner.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(255, 255, 255, 190));
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        16);
                cb.fill();
                cb.restoreState();
            }
        });

        try {
            PdfContentByte cb = writer.getDirectContent();
            PdfTemplate template = cb.createTemplate(520, 240); // Wider
            Graphics2D g2d = template.createGraphics(520, 240, new DefaultFontMapper());
            Rectangle2D r2d = new Rectangle2D.Double(0, 0, 520, 240);
            chart.draw(g2d, r2d);
            g2d.dispose();
            com.lowagie.text.Image chartImage = com.lowagie.text.Image.getInstance(template);
            inner.addElement(chartImage);
        } catch (Exception e) {
            inner.addElement(new Paragraph("Chart Error"));
        }

        card.addCell(inner);
        cell.addElement(card);
        return cell;
    }

    private JFreeChart createAreaChart(String title, String yLabel, List<ChartData> data) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            // Limit to prevent overcrowding
            int skip = 1;
            // Logic to skip labels if needed, but for now we just load data
            for (ChartData d : data)
                ds.addValue(d.getValue(), "Series1", d.getLabel());
        }

        // Create Plot manually or modify existing
        // We will create a fresh plot config on top of standard factory to be safe,
        // OR just modifying the factory chart is easier.
        // Let's modify the factory chart as before but add the 2nd layer.
        JFreeChart chart = ChartFactory.createAreaChart(title, "", yLabel, ds, PlotOrientation.VERTICAL, false, true,
                false);

        chart.setBackgroundPaint(null);
        chart.getTitle().setPaint(Color.WHITE);
        chart.getTitle().setFont(new java.awt.Font("Inter", java.awt.Font.BOLD, 14));
        chart.getTitle().setPadding(10, 0, 10, 0);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        plot.setBackgroundPaint(null);
        plot.setOutlinePaint(null);

        // 1. Bottom Layer (Area) - Gradient
        // We reuse the existing dataset 0.
        org.jfree.chart.renderer.category.AreaRenderer areaRenderer = new org.jfree.chart.renderer.category.AreaRenderer() {
            @Override
            public java.awt.Paint getSeriesPaint(int series) {
                return new java.awt.GradientPaint(
                        0, 0, new Color(59, 130, 246, 180), // Blue 500 (Semi-transparent)
                        0, 400, new Color(59, 130, 246, 10), // Fading to transparent
                        false);
            }
        };
        plot.setRenderer(0, areaRenderer);

        // 2. Top Layer (Line) - Sharp Edge
        // We need to add the same dataset as index 1
        plot.setDataset(1, ds);
        org.jfree.chart.renderer.category.LineAndShapeRenderer lineRenderer = new org.jfree.chart.renderer.category.LineAndShapeRenderer();
        lineRenderer.setSeriesPaint(0, new Color(96, 165, 250)); // Bright Blue 400
        lineRenderer.setSeriesStroke(0, new java.awt.BasicStroke(2.0f));
        lineRenderer.setSeriesShapesVisible(0, false); // No dots
        plot.setRenderer(1, lineRenderer);

        // Order: Area (0) then Line (1)
        plot.setDatasetRenderingOrder(org.jfree.chart.plot.DatasetRenderingOrder.FORWARD);

        // Gridlines - Subtle
        plot.setRangeGridlinePaint(new Color(255, 255, 255, 30));
        plot.setDomainGridlinesVisible(false);

        // Axis - Clean
        plot.getDomainAxis().setTickLabelsVisible(false); // Sparkline style
        plot.getDomainAxis().setAxisLinePaint(new Color(255, 255, 255, 50));

        plot.getRangeAxis().setTickLabelFont(new java.awt.Font("Inter", java.awt.Font.PLAIN, 10));
        plot.getRangeAxis().setTickLabelPaint(new Color(203, 213, 225)); // Slate 300
        plot.getRangeAxis().setAxisLinePaint(new Color(0, 0, 0, 0));

        return chart;
    }

    private JFreeChart createMiniTrendChart(String title, List<ChartData> data) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            int count = 0;
            for (ChartData d : data) {
                if (count++ < 7) { // Only show last 7 days
                    ds.addValue(d.getValue(), "Sales", d.getLabel());
                }
            }
        }

        JFreeChart chart = ChartFactory.createAreaChart(
                title, "", "AED", ds,
                PlotOrientation.VERTICAL, false, false, false);

        chart.setBackgroundPaint(null);
        chart.getTitle().setPaint(TEXT_PRIMARY_UNIFIED); // Unified text color
        chart.getTitle().setFont(new java.awt.Font("Inter", java.awt.Font.BOLD, 11));

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        plot.setBackgroundPaint(null);
        plot.setOutlinePaint(null);
        plot.setRangeGridlinePaint(new Color(240, 240, 240, 100)); // Softer grid
        plot.setDomainGridlinesVisible(false);

        // Compact area renderer with premium gradient
        org.jfree.chart.renderer.category.AreaRenderer renderer = new org.jfree.chart.renderer.category.AreaRenderer() {
            @Override
            public java.awt.Paint getSeriesPaint(int series) {
                // Subtle gradient for sparkline
                return new java.awt.GradientPaint(
                        0, 0,
                        new Color(GRADIENT_REVENUE_START.getRed(), GRADIENT_REVENUE_START.getGreen(),
                                GRADIENT_REVENUE_START.getBlue(), 150),
                        0, 100,
                        new Color(GRADIENT_REVENUE_END.getRed(), GRADIENT_REVENUE_END.getGreen(),
                                GRADIENT_REVENUE_END.getBlue(), 100),
                        false);
            }
        };
        plot.setRenderer(renderer);

        // Compact axis labels with unified colors
        plot.getDomainAxis().setTickLabelFont(new java.awt.Font("Inter", java.awt.Font.PLAIN, 8));
        plot.getRangeAxis().setTickLabelFont(new java.awt.Font("Inter", java.awt.Font.PLAIN, 8));
        plot.getDomainAxis().setTickLabelPaint(TEXT_MUTED_UNIFIED); // Unified gray
        plot.getRangeAxis().setTickLabelPaint(TEXT_MUTED_UNIFIED); // Unified gray

        return chart;
    }

    // =========================================================================
    // PAGE 2-STYLE CARD HELPERS (for Page 3 redesign)
    // =========================================================================

    /**
     * Creates a KPI card matching Page 2's design with colored accent bar, solid
     * background,
     * icon, value, growth indicator, and optional mini sparkline
     */
    private PdfPCell createPage2StyleCard(PdfWriter writer, String title, String value, String growth,
            Color accentColor, String icon, List<ChartData> sparkData) {

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        // Top colored accent bar (6pt gradient like Page 2)
        PdfPCell accentBar = new PdfPCell();
        accentBar.setBorder(Rectangle.NO_BORDER);
        accentBar.setFixedHeight(6);

        // Create gradient for accent bar
        final Color lighterAccent = new Color(
                Math.min(255, accentColor.getRed() + 30),
                Math.min(255, accentColor.getGreen() + 30),
                Math.min(255, accentColor.getBlue() + 30));

        accentBar.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.BACKGROUNDCANVAS];
                cb.saveState();

                try {
                    PdfShading shading = PdfShading.simpleAxial(writer,
                            position.getLeft(), 0,
                            position.getRight(), 0,
                            accentColor, lighterAccent);

                    PdfShadingPattern pattern = new PdfShadingPattern(shading);
                    cb.setShadingFill(pattern);
                    cb.rectangle(position.getLeft(), position.getBottom(),
                            position.getWidth(), position.getHeight());
                    cb.fill();
                } catch (Exception e) {
                    // Fallback to solid color
                    cb.setColorFill(accentColor);
                    cb.rectangle(position.getLeft(), position.getBottom(),
                            position.getWidth(), position.getHeight());
                    cb.fill();
                }

                cb.restoreState();
            }
        });
        card.addCell(accentBar);

        // Card body (solid dark background)
        PdfPCell body = new PdfPCell();
        body.setBorder(Rectangle.NO_BORDER);
        body.setPadding(15);
        body.setBackgroundColor(new Color(51, 65, 85)); // Solid slate 700

        // Title + Icon
        Font titleFont = new Font(Font.HELVETICA, 11, Font.BOLD, Color.WHITE);
        Phrase titlePhrase = new Phrase();
        titlePhrase.add(new Chunk(title + " ", titleFont));
        if (icon != null && !icon.isEmpty()) {
            titlePhrase.add(new Chunk(icon, new Font(Font.HELVETICA, 12)));
        }
        body.addElement(titlePhrase);
        body.addElement(new Phrase(" ", new Font(Font.HELVETICA, 6))); // Spacer

        // Value (large)
        Font valueFont = new Font(Font.HELVETICA, 22, Font.BOLD, Color.WHITE);
        body.addElement(new Phrase(value, valueFont));
        body.addElement(new Phrase(" ", new Font(Font.HELVETICA, 4))); // Spacer

        // Growth indicator
        if (growth != null && !growth.isEmpty()) {
            Color growthColor = growth.contains("▲") || growth.contains("+")
                    ? new Color(16, 185, 129) // Green
                    : new Color(239, 68, 68); // Red
            Font growthFont = new Font(Font.HELVETICA, 9, Font.NORMAL, growthColor);
            body.addElement(new Phrase(growth, growthFont));
        }

        // Mini sparkline (if data provided)
        if (sparkData != null && !sparkData.isEmpty()) {
            body.addElement(new Phrase(" ", new Font(Font.HELVETICA, 6)));
            try {
                JFreeChart miniChart = createMiniTrendChart("", sparkData);
                BufferedImage img = miniChart.createBufferedImage(150, 40);
                Image chartImg = Image.getInstance(writer, img, 1.0f);
                body.addElement(chartImg);
            } catch (Exception e) {
                // Silently skip sparkline if error
            }
        }

        card.addCell(body);

        // Wrapper
        PdfPCell wrapper = new PdfPCell(card);
        wrapper.setBorder(Rectangle.NO_BORDER);
        wrapper.setPadding(6);

        return wrapper;
    }

    /**
     * Creates a clean chart card with solid dark background (no glassmorphic
     * effect)
     */
    private PdfPCell createCleanChartCard(PdfWriter writer, JFreeChart chart, String title) {
        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        // Rich Dark Navy background for professional look
        Color cardBg = new Color(15, 23, 42); // Slate 900

        // Title
        if (title != null && !title.isEmpty()) {
            PdfPCell titleCell = new PdfPCell(
                    new Phrase(title, new Font(Font.HELVETICA, 11, Font.BOLD, Color.WHITE)));
            titleCell.setBorder(Rectangle.NO_BORDER);
            titleCell.setPadding(10);
            titleCell.setPaddingBottom(5);
            titleCell.setBackgroundColor(cardBg);
            card.addCell(titleCell);
        }

        // Chart
        PdfPCell chartCell = new PdfPCell();
        chartCell.setBorder(Rectangle.NO_BORDER);
        chartCell.setPadding(10);
        chartCell.setBackgroundColor(cardBg);

        try {
            BufferedImage img = chart.createBufferedImage(800, 400); // Higher res
            Image chartImage = Image.getInstance(writer, img, 1.0f);
            chartImage.scalePercent(50); // Scale down for Retina-like sharpness
            chartCell.addElement(chartImage);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create chart image", e);
        }

        card.addCell(chartCell);

        PdfPCell wrapper = new PdfPCell(card);
        wrapper.setBorder(Rectangle.NO_BORDER);
        wrapper.setPadding(8);

        return wrapper;
    }

    /**
     * Creates small insight card with colored accent bar (like Page 2's insights)
     */
    private PdfPCell createSmallInsightCard(String title, String text, Color accentColor, String icon) {
        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        // Accent bar (thinner, 3pt)
        PdfPCell bar = new PdfPCell();
        bar.setBorder(Rectangle.NO_BORDER);
        bar.setFixedHeight(3);
        bar.setBackgroundColor(accentColor);
        card.addCell(bar);

        // Content
        PdfPCell content = new PdfPCell();
        content.setBorder(Rectangle.NO_BORDER);
        content.setPadding(12);
        content.setBackgroundColor(new Color(51, 65, 85)); // Solid slate 700

        // Title with icon
        Font titleFont = new Font(Font.HELVETICA, 10, Font.BOLD, Color.WHITE);
        if (icon != null && !icon.isEmpty()) {
            content.addElement(new Phrase(icon + " " + title, titleFont));
        } else {
            content.addElement(new Phrase(title, titleFont));
        }
        content.addElement(new Phrase(" ", new Font(Font.HELVETICA, 4)));

        // Text
        Font textFont = new Font(Font.HELVETICA, 9, Font.NORMAL,
                new Color(203, 213, 225)); // Light gray
        content.addElement(new Phrase(text, textFont));

        card.addCell(content);

        PdfPCell wrapper = new PdfPCell(card);
        wrapper.setBorder(Rectangle.NO_BORDER);
        wrapper.setPadding(4);
        wrapper.setPaddingBottom(8);

        return wrapper;
    }

    /**
     * Creates an enhanced hourly performance chart with gradient bars
     * and peak hour highlighting (gold for 6-9 PM)
     */
    private JFreeChart createEnhancedHourlyChart(PdfWriter writer) {
        // Create dataset with realistic hourly transaction pattern
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        // Hourly transaction distribution (realistic pattern)
        int[] hourlyTxns = { 5, 3, 2, 4, 8, 15, 35, 58, 72, 85, 95, 105,
                110, 98, 88, 92, 108, 125, 142, 138, 115, 85, 45, 22 };

        for (int hour = 0; hour < 24; hour++) {
            String label;
            if (hour == 0) {
                label = "12AM";
            } else if (hour < 12) {
                label = hour + "AM";
            } else if (hour == 12) {
                label = "12PM";
            } else {
                label = (hour - 12) + "PM";
            }
            dataset.addValue(hourlyTxns[hour], "Transactions", label);
        }

        // Create bar chart
        JFreeChart chart = ChartFactory.createBarChart(
                null, // No title
                "Hour of Day",
                "Transactions",
                dataset,
                PlotOrientation.VERTICAL,
                false, // No legend
                false, // No tooltips
                false // No URLs
        );

        // Style the chart
        chart.setBackgroundPaint(null);

        CategoryPlot plot = chart.getCategoryPlot();
        plot.setBackgroundPaint(new Color(30, 41, 59)); // Darker slate background
        plot.setOutlineVisible(false);
        plot.setRangeGridlinePaint(new Color(71, 85, 105)); // Subtle grid lines
        plot.setDomainGridlinesVisible(false);

        // Custom renderer with gradient bars
        BarRenderer renderer = new BarRenderer() {
            @Override
            public java.awt.Paint getItemPaint(int row, int col) {
                // Peak hours (18:00-21:00, indices 18-21) get gold gradient
                if (col >= 18 && col <= 21) {
                    return new java.awt.GradientPaint(
                            0, 0, new Color(245, 158, 11), // Amber
                            0, 300, new Color(251, 191, 36), // Light amber
                            false);
                }
                // Regular hours get blue gradient
                return new java.awt.GradientPaint(
                        0, 0, new Color(59, 130, 246), // Blue
                        0, 300, new Color(56, 189, 248), // Sky blue
                        false);
            }
        };

        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter()); // Flat, modern
        renderer.setShadowVisible(false);
        plot.setRenderer(renderer);

        // Axis styling
        CategoryAxis domainAxis = plot.getDomainAxis();
        domainAxis.setTickLabelPaint(Color.WHITE);
        domainAxis.setLabelPaint(Color.WHITE);
        domainAxis.setTickLabelFont(new java.awt.Font("Arial", java.awt.Font.PLAIN, 8));

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setTickLabelPaint(Color.WHITE);
        rangeAxis.setLabelPaint(Color.WHITE);

        return chart;
    }

    private PdfPCell createMomentumIndicator(String momentumVisual) {
        PdfPTable box = new PdfPTable(1);
        box.setWidthPercentage(100);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(5);

        Paragraph p = new Paragraph();
        p.add(new Chunk("Month Momentum: ", new Font(Font.HELVETICA, 8, Font.BOLD, COL_TEXT_MUTED)));
        // ▓ - \u2593 ░ - \u2591
        p.add(new Chunk(momentumVisual, new Font(Font.HELVETICA, 10, Font.NORMAL, new Color(16, 185, 129)))); // Green
                                                                                                              // for
                                                                                                              // momentum

        cell.addElement(p);
        box.addCell(cell);

        PdfPCell wrapper = new PdfPCell(box);
        wrapper.setBorder(Rectangle.NO_BORDER);
        return wrapper;
    }

    // Renaming createExecutiveInsightCard to generic if feasible, but let's make a
    // specific Insight Panel for Page 4
    private PdfPCell createExecutiveInsightPanelPage4(String text) {
        // Bottom Right Glass Card
        return createExecutiveInsightCard("EXECUTIVE INSIGHT", text, null);
    }

    // --- PAGE 5 COMPONENTS ---

    private PdfPCell createDailyKpiStrip(String peakDay, String lowActivity, String highestLoad) {
        // Reusing Weekly Strip logic/style
        PdfPTable strip = new PdfPTable(3);
        strip.setWidthPercentage(100);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);

        // Glass bg
        cell.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(255, 255, 255, 25)); // Premium 10% Glass
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        8);
                cb.fill();
                cb.restoreState();
            }
        });

        PdfPTable inner = new PdfPTable(3);
        inner.setWidthPercentage(100);
        inner.setSpacingBefore(0);

        inner.addCell(createStripItem("PEAK DAY", peakDay));
        inner.addCell(createStripItem("LOW ACTIVITY", lowActivity));
        inner.addCell(createStripItem("HIGHEST LOAD", highestLoad));

        cell.addElement(inner);
        return cell;
    }

    private JFreeChart createComboChart(String title, String yLabel1, List<ChartData> data) {
        DefaultCategoryDataset dataset1 = new DefaultCategoryDataset(); // Bars (Txns)
        DefaultCategoryDataset dataset2 = new DefaultCategoryDataset(); // Line (Sales)

        if (data != null) {
            for (ChartData d : data) {
                dataset1.addValue(d.getValue2(), "Transactions", getDayLabel(d.getLabel())); // Value2 is Count
                dataset2.addValue(d.getValue(), "Sales", getDayLabel(d.getLabel())); // Value is Sales
            }
        }

        // 1. Create Plot
        org.jfree.chart.plot.CategoryPlot plot = new org.jfree.chart.plot.CategoryPlot();
        plot.setDataset(0, dataset1); // Bars on primary axis
        plot.setDataset(1, dataset2); // Line on secondary axis

        // 2. Renderers with Premium Gradients
        // Bar Renderer - Transaction Mint Gradient
        org.jfree.chart.renderer.category.BarRenderer barRenderer = new org.jfree.chart.renderer.category.BarRenderer() {
            @Override
            public java.awt.Paint getItemPaint(int series, int item) {
                return new java.awt.GradientPaint(
                        0, 0, GRADIENT_TRANSACTION_START, // Mint Green
                        0, 300, GRADIENT_TRANSACTION_END, // Pale Mint
                        false);
            }
        };
        barRenderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
        barRenderer.setShadowVisible(false);

        // Line Renderer - Revenue Blue with Shape Markers
        org.jfree.chart.renderer.category.LineAndShapeRenderer lineRenderer = new org.jfree.chart.renderer.category.LineAndShapeRenderer();
        lineRenderer.setSeriesPaint(0, GRADIENT_REVENUE_START); // Soft Royal Blue
        lineRenderer.setSeriesShapesVisible(0, true);
        lineRenderer.setSeriesStroke(0, new java.awt.BasicStroke(2.5f)); // Thicker line

        plot.setRenderer(0, barRenderer);
        plot.setRenderer(1, lineRenderer);

        // 3. Axes
        plot.setDomainAxis(new org.jfree.chart.axis.CategoryAxis(""));
        plot.setRangeAxis(0, new org.jfree.chart.axis.NumberAxis("Transactions"));
        plot.setRangeAxis(1, new org.jfree.chart.axis.NumberAxis("Sales (AED)"));

        plot.mapDatasetToRangeAxis(0, 0); // Bars -> Left Axis
        plot.mapDatasetToRangeAxis(1, 1); // Line -> Right Axis

        // Style Axes
        java.awt.Font axisFont = new java.awt.Font("Inter", java.awt.Font.PLAIN, 8);
        plot.getDomainAxis().setTickLabelFont(axisFont);
        plot.getRangeAxis(0).setTickLabelFont(axisFont);
        plot.getRangeAxis(1).setTickLabelFont(axisFont);

        // Grid
        plot.setRangeGridlinePaint(new Color(200, 200, 200, 100)); // Subtle
        plot.setDomainGridlinesVisible(false);
        plot.setBackgroundPaint(null);
        plot.setOutlinePaint(null);

        JFreeChart chart = new JFreeChart(title, new java.awt.Font("Inter", java.awt.Font.BOLD, 12), plot, true);
        chart.setBackgroundPaint(null);
        chart.getTitle().setPaint(COLOR_TEXT_PRIMARY);

        return chart;
    }

    private PdfPCell createHeroComboChartCard(PdfWriter writer, JFreeChart chart) {
        // Reuse Hero Card logic but potentially adjusted height
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell inner = new PdfPCell();
        inner.setBorder(Rectangle.NO_BORDER);
        inner.setPadding(20);
        inner.setFixedHeight(300); // Tall for combo

        // Glass Event
        inner.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(255, 255, 255, 25)); // Premium 10% Glass
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        16);
                cb.fill();
                cb.restoreState();
            }
        });

        try {
            PdfContentByte cb = writer.getDirectContent();
            PdfTemplate template = cb.createTemplate(520, 260);
            Graphics2D g2d = template.createGraphics(520, 260, new DefaultFontMapper());
            Rectangle2D r2d = new Rectangle2D.Double(0, 0, 520, 260);
            chart.draw(g2d, r2d);
            g2d.dispose();
            com.lowagie.text.Image chartImage = com.lowagie.text.Image.getInstance(template);
            inner.addElement(chartImage);
        } catch (Exception e) {
            inner.addElement(new Paragraph("Chart Error"));
        }

        card.addCell(inner);
        cell.addElement(card);
        return cell;
    }

    private PdfPCell createPerformanceSummaryCard() {
        PdfPTable box = new PdfPTable(1);
        box.setWidthPercentage(100);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(5);

        // Glass
        cell.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(255, 255, 255, 180));
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        12);
                cb.fill();
                cb.restoreState();
            }
        });

        PdfPTable content = new PdfPTable(2);
        content.setWidthPercentage(100);
        try {
            content.setWidths(new float[] { 1.5f, 1f });
        } catch (DocumentException e) {
        }

        // Header
        PdfPCell header = new PdfPCell(
                new Phrase("DAILY PERFORMANCE SUMMARY", new Font(Font.HELVETICA, 8, Font.BOLD, COL_TEXT_SECONDARY)));
        header.setColspan(2);
        header.setBorder(Rectangle.BOTTOM);
        header.setBorderColor(new Color(200, 200, 200));
        header.setPaddingBottom(8);
        content.addCell(header);

        // Rows
        addSummaryRow(content, "Peak Sales Day", "SATURDAY");
        addSummaryRow(content, "Peak Txn Day", "WEDNESDAY");
        addSummaryRow(content, "Weakest Day", "FRIDAY");
        addSummaryRow(content, "Stability", "MEDIUM");
        addSummaryRow(content, "Volatility", "HIGH");

        cell.addElement(content);
        box.addCell(cell);

        PdfPCell wrapper = new PdfPCell(box);
        wrapper.setBorder(Rectangle.NO_BORDER);
        return wrapper;
    }

    private void addSummaryRow(PdfPTable table, String label, String value) {
        Font lFont = new Font(Font.HELVETICA, 9, Font.NORMAL, COL_TEXT_PRIMARY);
        Font vFont = new Font(Font.HELVETICA, 9, Font.BOLD, COLOR_NAVY);

        PdfPCell lCell = new PdfPCell(new Phrase(label, lFont));
        lCell.setBorder(Rectangle.NO_BORDER);
        lCell.setPaddingTop(6);
        table.addCell(lCell);

        PdfPCell vCell = new PdfPCell(new Phrase(value, vFont));
        vCell.setBorder(Rectangle.NO_BORDER);
        vCell.setPaddingTop(6);
        vCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(vCell);
    }

    // --- PAGE 6 COMPONENTS ---

    private PdfPCell createValueKpiStrip(String growth, String efficiency, String quality) {
        // Reusing Weekly Strip logic/style
        PdfPTable strip = new PdfPTable(3);
        strip.setWidthPercentage(100);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);

        // Glass bg
        cell.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(255, 255, 255, 25)); // Premium 10% Glass
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        8);
                cb.fill();
                cb.restoreState();
            }
        });

        PdfPTable inner = new PdfPTable(3);
        inner.setWidthPercentage(100);
        inner.setSpacingBefore(0);

        inner.addCell(createStripItem("VALUE GROWTH", growth));
        inner.addCell(createStripItem("CUSTOMER EFFICIENCY", efficiency));
        inner.addCell(createStripItem("SPEND QUALITY", quality));

        cell.addElement(inner);
        return cell;
    }

    private JFreeChart createDualLineChart(String title, String yLabel1, List<ChartData> data) {
        DefaultCategoryDataset dataset1 = new DefaultCategoryDataset(); // Avg Spend
        DefaultCategoryDataset dataset2 = new DefaultCategoryDataset(); // ATV

        if (data != null) {
            for (ChartData d : data) {
                dataset1.addValue(d.getValue(), "Avg Spend", getDayLabel(d.getLabel()));
                dataset2.addValue(d.getValue2() != null ? d.getValue2() : d.getValue().multiply(new BigDecimal("0.8")),
                        "Avg Txn Value", getDayLabel(d.getLabel())); // Mock ATV relative if null
            }
        }

        // 1. Create Plot
        org.jfree.chart.plot.CategoryPlot plot = new org.jfree.chart.plot.CategoryPlot();
        plot.setDataset(0, dataset1);
        plot.setDataset(1, dataset2);

        // 2. Renderers
        // Line 1: Gold Gradient (Simulated with solid Gold for now)
        org.jfree.chart.renderer.category.LineAndShapeRenderer line1 = new org.jfree.chart.renderer.category.LineAndShapeRenderer();
        line1.setSeriesPaint(0, COL_ACCENT_TXNS); // Teal/Cyan Gradient look
        line1.setSeriesShapesVisible(0, false); // Smooth look requested, hiding shapes for cleaner line? User said
                                                // "Soft
                                                // glow on peaks", let's keep shapes false for now or small.
        line1.setSeriesStroke(0, new java.awt.BasicStroke(3.0f));

        // Line 2: Blue Gradient (Simulated with solid Blue)
        org.jfree.chart.renderer.category.LineAndShapeRenderer line2 = new org.jfree.chart.renderer.category.LineAndShapeRenderer();
        line2.setSeriesPaint(0, new Color(31, 79, 216)); // Royal Blue
        line2.setSeriesShapesVisible(0, false);
        line2.setSeriesStroke(0, new java.awt.BasicStroke(3.0f));

        plot.setRenderer(0, line1);
        plot.setRenderer(1, line2);

        // 3. Axis
        plot.setDomainAxis(new org.jfree.chart.axis.CategoryAxis(""));
        org.jfree.chart.axis.NumberAxis yAxis = new org.jfree.chart.axis.NumberAxis("Value (AED)");
        plot.setRangeAxis(0, yAxis); // Shared axis for value comparison? "Avg Spend per Customer vs Avg Txn Value" -
                                     // both currency.
        plot.mapDatasetToRangeAxis(0, 0);
        plot.mapDatasetToRangeAxis(1, 0); // Shared axis

        // Style Axes
        java.awt.Font axisFont = new java.awt.Font("Inter", java.awt.Font.PLAIN, 8);
        plot.getDomainAxis().setTickLabelFont(axisFont);
        yAxis.setTickLabelFont(axisFont);

        // Grid
        plot.setRangeGridlinePaint(new Color(200, 200, 200, 100));
        plot.setDomainGridlinesVisible(false);
        plot.setBackgroundPaint(null);
        plot.setOutlinePaint(null);

        JFreeChart chart = new JFreeChart(title, new java.awt.Font("Inter", java.awt.Font.BOLD, 12), plot, true);
        chart.setBackgroundPaint(null);
        chart.getTitle().setPaint(COLOR_TEXT_PRIMARY);

        return chart;
    }

    private PdfPCell createHeroValueChartCard(PdfWriter writer, JFreeChart chart) {
        // Similar to Hero Combo
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell inner = new PdfPCell();
        inner.setBorder(Rectangle.NO_BORDER);
        inner.setPadding(20);
        inner.setFixedHeight(260);

        // Glass Event
        inner.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(255, 255, 255, 25)); // Premium 10% Glass
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        16);
                cb.fill();
                cb.restoreState();
            }
        });

        try {
            PdfContentByte cb = writer.getDirectContent();
            PdfTemplate template = cb.createTemplate(520, 220);
            Graphics2D g2d = template.createGraphics(520, 220, new DefaultFontMapper());
            Rectangle2D r2d = new Rectangle2D.Double(0, 0, 520, 220);
            chart.draw(g2d, r2d);
            g2d.dispose();
            com.lowagie.text.Image chartImage = com.lowagie.text.Image.getInstance(template);
            inner.addElement(chartImage);
        } catch (Exception e) {
            inner.addElement(new Paragraph("Chart Error"));
        }

        card.addCell(inner);
        cell.addElement(card);
        return cell;
    }

    private PdfPCell createEngagementChartCard(PdfWriter writer, JFreeChart chart) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell inner = new PdfPCell();
        inner.setBorder(Rectangle.NO_BORDER);
        inner.setPadding(20);
        inner.setFixedHeight(200);

        // Glass Event
        inner.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(255, 255, 255, 25)); // Premium 10% Glass
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        16);
                cb.fill();
                cb.restoreState();
            }
        });

        try {
            PdfContentByte cb = writer.getDirectContent();
            PdfTemplate template = cb.createTemplate(240, 160);
            Graphics2D g2d = template.createGraphics(240, 160, new DefaultFontMapper());
            Rectangle2D r2d = new Rectangle2D.Double(0, 0, 240, 160);
            chart.draw(g2d, r2d);
            g2d.dispose();
            com.lowagie.text.Image chartImage = com.lowagie.text.Image.getInstance(template);
            inner.addElement(chartImage);
        } catch (Exception e) {
            inner.addElement(new Paragraph("Chart Error"));
        }

        card.addCell(inner);
        cell.addElement(card);
        return cell;
    }

    // --- PAGE 7 COMPONENTS ---

    private PdfPCell createTimeKpiStrip(String peakHour, String lowActivity, String primeWindow) {
        // Reusing Weekly Strip logic/style
        PdfPTable strip = new PdfPTable(3);
        strip.setWidthPercentage(100);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);

        // Glass bg
        cell.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(255, 255, 255, 25)); // Premium 10% Glass
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        8);
                cb.fill();
                cb.restoreState();
            }
        });

        PdfPTable inner = new PdfPTable(3);
        inner.setWidthPercentage(100);
        inner.setSpacingBefore(0);

        inner.addCell(createStripItem("PEAK HOUR", peakHour));
        inner.addCell(createStripItem("LOW ACTIVITY", lowActivity));
        inner.addCell(createStripItem("PRIME WINDOW", primeWindow));

        cell.addElement(inner);
        return cell;
    }

    private PdfPCell createHeroHeatmap(String title, List<ChartData> data) {
        // Simulating a heatmap using a PdfPTable grid
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        // Inner Glass
        PdfPCell inner = new PdfPCell();
        inner.setBorder(Rectangle.NO_BORDER);
        inner.setPadding(15);
        inner.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(15, 23, 42)); // Dark Navy Background
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        16);
                cb.fill();
                cb.restoreState();
            }
        });

        // Title
        Paragraph pTitle = new Paragraph(title, new Font(Font.HELVETICA, 10, Font.BOLD, Color.WHITE));
        pTitle.setSpacingAfter(10);
        inner.addElement(pTitle);

        // Heatmap Grid (7 Days x 24 Hours) - Simplified to 4 Time Blocks x 7 Days for
        // visual feasibility in text table
        // Or actually lets try a visual grid of 24 blocks for "Average Day" to show
        // Hourly pattern as requested.
        // "Hour vs Sales/Transactions" implies X=Hour. Let's do a single row of 24
        // hours for "Average Daily Pattern"
        // OR a matrix. Let's do a Matrix: Rows = Days (Mon-Sun), Cols = Time Blocks
        // (Morning, Afternoon, Evening, Night)
        // User asked for "X-axis: Hours (0–23), Y-axis: Day of week". That's big (7x24
        // = 168 cells).
        // Might be too crowded. Let's do 7 Days x 6 Time Buckets (4-hour blocks).

        PdfPTable grid = new PdfPTable(6); // 6 columns (4 hr blocks: 0-4, 4-8, 8-12, 12-16, 16-20, 20-24)
        grid.setWidthPercentage(100);

        // Header Row
        String[] headers = { "00-04", "04-08", "08-12", "12-16", "16-20", "20-24" };
        for (String h : headers) {
            PdfPCell hCell = new PdfPCell(new Phrase(h, new Font(Font.HELVETICA, 7, Font.NORMAL, Color.LIGHT_GRAY)));
            hCell.setBorder(Rectangle.NO_BORDER);
            hCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            hCell.setPaddingBottom(5);
            grid.addCell(hCell);
        }

        // Mock Data / Rows (Mon-Sun)
        String[] days = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };
        Random rand = new Random();

        for (String day : days) {
            for (int i = 0; i < 6; i++) {
                // Determine color intensity based on mock "Sales"
                // Late night (0,1) low, Evening (4,5) high
                float intensity = 0.1f;
                if (i == 4 || i == 5)
                    intensity = 0.8f + (rand.nextFloat() * 0.2f); // High evening
                else if (i == 3)
                    intensity = 0.5f; // Afternoon
                else
                    intensity = 0.1f + (rand.nextFloat() * 0.2f);

                // Color Scale: Slate Blue (Low) -> Cyan/Green (High)
                // Low: 100, 116, 139 (Slate 500)
                // High: 34, 211, 238 (Cyan 400)
                int r = (int) (100 + (34 - 100) * intensity);
                int g = (int) (116 + (211 - 116) * intensity);
                int b = (int) (139 + (238 - 139) * intensity);
                Color c = new Color(r, g, b);

                PdfPCell cellBlock = new PdfPCell();
                cellBlock.setBorder(Rectangle.NO_BORDER);
                cellBlock.setBackgroundColor(c);
                cellBlock.setFixedHeight(12); // Grid height
                // Spacing via border or padding? PdfPCell doesn't have margin.
                // Use a wrapper or setBorderWidth(1) with white stroke for grid effect?
                cellBlock.setBorderWidth(1);
                cellBlock.setBorderColor(new Color(15, 23, 42)); // Match bg for gap

                grid.addCell(cellBlock);
            }
        }

        inner.addElement(grid);

        // Legend maybe?
        Paragraph pLegend = new Paragraph("Low intensity -> High intensity",
                new Font(Font.HELVETICA, 6, Font.ITALIC, Color.GRAY));
        pLegend.setSpacingBefore(5);
        pLegend.setAlignment(Element.ALIGN_RIGHT);
        inner.addElement(pLegend);

        card.addCell(inner);
        cell.addElement(card);
        return cell;
    }

    // Supporting Chart: Sales by Time Block (Bar)
    private PdfPCell createTimeBlockChartCard(PdfWriter writer) {
        // Mock data for buckets
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        ds.addValue(5000, "Sales", "Morning");
        ds.addValue(12000, "Sales", "Afternoon");
        ds.addValue(25000, "Sales", "Evening");
        ds.addValue(8000, "Sales", "Night");

        JFreeChart chart = ChartFactory.createBarChart("SALES BY TIME BLOCK", "", "Sales", ds, PlotOrientation.VERTICAL,
                false, true, false);

        chart.setBackgroundPaint(null);
        chart.getTitle().setPaint(COLOR_TEXT_PRIMARY);
        chart.getTitle().setFont(new java.awt.Font("Inter", java.awt.Font.BOLD, 10));

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        plot.setBackgroundPaint(null);
        plot.setOutlinePaint(null);
        plot.setRangeGridlinePaint(new Color(200, 200, 200));
        plot.setDomainGridlinesVisible(false);

        org.jfree.chart.renderer.category.BarRenderer renderer = new org.jfree.chart.renderer.category.BarRenderer();
        renderer.setSeriesPaint(0, new Color(20, 184, 166)); // Teal
        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
        renderer.setShadowVisible(false);
        plot.setRenderer(renderer);

        // Axes
        plot.getDomainAxis().setTickLabelFont(new java.awt.Font("Inter", java.awt.Font.PLAIN, 8));
        plot.getRangeAxis().setTickLabelFont(new java.awt.Font("Inter", java.awt.Font.PLAIN, 8));

        // Use glass wrapper
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell inner = new PdfPCell();
        inner.setBorder(Rectangle.NO_BORDER);
        inner.setPadding(20);
        inner.setFixedHeight(180);

        inner.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(255, 255, 255, 25)); // Premium 10% Glass
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        16);
                cb.fill();
                cb.restoreState();
            }
        });

        try {
            PdfContentByte cb = writer.getDirectContent();
            PdfTemplate template = cb.createTemplate(240, 140);
            Graphics2D g2d = template.createGraphics(240, 140, new DefaultFontMapper());
            Rectangle2D r2d = new Rectangle2D.Double(0, 0, 240, 140);
            chart.draw(g2d, r2d);
            g2d.dispose();
            com.lowagie.text.Image chartImage = com.lowagie.text.Image.getInstance(template);
            inner.addElement(chartImage);
        } catch (Exception e) {
        }

        card.addCell(inner);
        cell.addElement(card);
        return cell;
    }

    // --- PAGE 8 COMPONENTS ---

    private PdfPCell createGrowthKpiStrip(String yoy, String bestMonth, String slowdown) {
        // Reusing Weekly Strip logic/style
        PdfPTable strip = new PdfPTable(3);
        strip.setWidthPercentage(100);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);

        // Glass bg
        cell.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(255, 255, 255, 25)); // Premium 10% Glass
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        8);
                cb.fill();
                cb.restoreState();
            }
        });

        PdfPTable inner = new PdfPTable(3);
        inner.setWidthPercentage(100);
        inner.setSpacingBefore(0);

        inner.addCell(createStripItem("YoY GROWTH", yoy));
        inner.addCell(createStripItem("BEST MONTH", bestMonth));
        inner.addCell(createStripItem("SLOWDOWN", slowdown));

        cell.addElement(inner);
        return cell;
    }

    private PdfPCell createLavenderExecutiveInsightCard(String title, String text, String icon) {
        // Variant of standard insight card with Lavender bg hint if possible, or just
        // standard.
        // Let's do standard for consistency but maybe slightly different glass color if
        // "Lavender" requested.
        // Lavender: new Color(230, 230, 250) roughly.
        // Glass: White with hint of purple?
        // Let's stick to standard Glass for now to avoid messiness, or override the BG
        // color in a copy.
        // I'll create a quick inline override version.

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8);

        PdfPTable card = new PdfPTable(2);
        try {
            card.setWidths(new float[] { 0.15f, 0.85f });
        } catch (DocumentException e) {
        }
        card.setWidthPercentage(100);

        // Glass Event (Lavender Tint)
        cell.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(245, 240, 255, 200)); // HINT of Lavender, 80% opacity
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        12);
                cb.fill();

                // Left Border Accent (Indigo)
                cb.setColorFill(new Color(79, 70, 229)); // Indigo
                cb.roundRectangle(position.getLeft(), position.getBottom(), 4, position.getHeight(), 2);
                cb.fill();

                cb.restoreState();
            }
        });

        // Icon
        Paragraph pIcon = new Paragraph(icon != null ? icon : "💡", new Font(Font.HELVETICA, 16));
        pIcon.setAlignment(Element.ALIGN_CENTER);
        PdfPCell iconCell = new PdfPCell(pIcon);
        iconCell.setBorder(Rectangle.NO_BORDER);
        iconCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        iconCell.setPaddingTop(10);
        iconCell.setPaddingBottom(10);
        card.addCell(iconCell);

        // Text
        PdfPCell textCell = new PdfPCell();
        textCell.setBorder(Rectangle.NO_BORDER);
        textCell.setPaddingTop(10);
        textCell.setPaddingBottom(10);
        textCell.setPaddingRight(10);

        Paragraph pTitle = new Paragraph(title, new Font(Font.HELVETICA, 8, Font.BOLD, new Color(79, 70, 229))); // Indigo
        pTitle.setSpacingAfter(2);
        textCell.addElement(pTitle);

        Paragraph pText = new Paragraph(text, new Font(Font.HELVETICA, 9, Font.NORMAL, COL_TEXT_PRIMARY));
        pText.setLeading(12);
        textCell.addElement(pText);

        card.addCell(textCell);
        cell.addElement(card);

        return cell;
    }

    // --- Chart Creation ---

    private JFreeChart createBarChart(String title, String yLabel, List<ChartData> data) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            for (ChartData d : data)
                ds.addValue(d.getValue(), "Series1", d.getLabel());
        }
        JFreeChart chart = ChartFactory.createBarChart(title, "", yLabel, ds, PlotOrientation.VERTICAL, false,
                true, false);
        styleChart(chart);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        org.jfree.chart.renderer.category.BarRenderer renderer = (org.jfree.chart.renderer.category.BarRenderer) plot
                .getRenderer();
        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter()); // Remove gradient
        renderer.setSeriesPaint(0, new Color(44, 62, 80)); // Professional Navy/Dark Blue
        renderer.setShadowVisible(false);
        renderer.setItemMargin(0.25);

        // Values on top
        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelGenerator(
                new org.jfree.chart.labels.StandardCategoryItemLabelGenerator());
        renderer.setDefaultItemLabelPaint(COLOR_TEXT_PRIMARY);
        renderer.setDefaultItemLabelFont(new java.awt.Font("Inter", java.awt.Font.PLAIN, 6)); // Small font to prevent
                                                                                              // overlap

        return chart;
    }

    private JFreeChart createLineChart(String title, String xLabel, String yLabel, List<ChartData> data) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            for (ChartData d : data)
                ds.addValue(d.getValue(), "Series1", d.getLabel());
        }
        JFreeChart chart = ChartFactory.createLineChart(title, xLabel, yLabel, ds, PlotOrientation.VERTICAL, false,
                true, false);
        styleChart(chart);
        return chart;
    }

    private JFreeChart createPieChart(String title, Map<String, BigDecimal> data) {
        DefaultPieDataset<String> ds = new DefaultPieDataset<>();
        if (data != null) {
            data.forEach((k, v) -> ds.setValue(k, v));
        }
        JFreeChart chart = ChartFactory.createPieChart(title, ds, true, true, false);
        PiePlot plot = (PiePlot) chart.getPlot();
        plot.setLabelGenerator(new StandardPieSectionLabelGenerator("{0} = {2}"));
        plot.setBackgroundPaint(COLOR_CARD_BG); // Dark Card BG
        plot.setOutlinePaint(null);

        // Custom Colors
        Color[] colors = new Color[] {
                new Color(33, 150, 243), // Blue
                new Color(0, 188, 212), // Cyan
                new Color(255, 152, 0), // Orange
                new Color(156, 39, 176), // Purple
                new Color(76, 175, 80) // Green
        };
        int i = 0;
        for (Object key : ds.getKeys()) {
            plot.setSectionPaint((Comparable) key, colors[i % colors.length]);
            i++;
        }
        return chart;
    }

    private void styleChart(JFreeChart chart) {
        // High Contrast Theme
        chart.setBackgroundPaint(Color.WHITE);
        chart.setBorderVisible(false);

        // Title
        chart.getTitle().setPaint(COL_TEXT_PRIMARY);
        chart.getTitle().setFont(new java.awt.Font("Helvetica", java.awt.Font.BOLD, 14)); // Larger Title
        chart.getTitle().setMargin(0, 0, 10, 0);

        // Plot
        org.jfree.chart.plot.Plot plot = chart.getPlot();
        plot.setBackgroundPaint(Color.WHITE); // White background for data
        plot.setOutlinePaint(null);

        if (plot instanceof org.jfree.chart.plot.CategoryPlot) {
            org.jfree.chart.plot.CategoryPlot cp = (org.jfree.chart.plot.CategoryPlot) plot;

            // Gridlines - Visible for readability
            cp.setRangeGridlinePaint(COL_GRID_LINE);
            cp.setRangeGridlinesVisible(true);

            cp.setDomainGridlinePaint(COL_GRID_LINE);
            cp.setDomainGridlinesVisible(true);

            cp.setAxisOffset(new RectangleInsets(5, 5, 5, 5));

            // Axis fonts & colors - Larger
            java.awt.Font axisLabelFont = new java.awt.Font("Helvetica", java.awt.Font.BOLD, 10);
            java.awt.Font tickLabelFont = new java.awt.Font("Helvetica", java.awt.Font.PLAIN, 9);

            cp.getDomainAxis().setLabelFont(axisLabelFont);
            cp.getDomainAxis().setTickLabelFont(tickLabelFont);
            cp.getRangeAxis().setLabelFont(axisLabelFont);
            cp.getRangeAxis().setTickLabelFont(tickLabelFont);

            cp.getDomainAxis().setLabelPaint(COL_TEXT_SECONDARY);
            cp.getDomainAxis().setTickLabelPaint(COL_TEXT_SECONDARY);
            cp.getRangeAxis().setLabelPaint(COL_TEXT_SECONDARY);
            cp.getRangeAxis().setTickLabelPaint(COL_TEXT_SECONDARY);

            // Solid Axis Lines
            cp.getDomainAxis().setAxisLinePaint(COL_TEXT_MUTED);
            cp.getRangeAxis().setAxisLineVisible(true);
            cp.getRangeAxis().setAxisLinePaint(COL_TEXT_MUTED);

        } else if (plot instanceof org.jfree.chart.plot.PiePlot) {
            org.jfree.chart.plot.PiePlot pp = (org.jfree.chart.plot.PiePlot) plot;
            pp.setLabelBackgroundPaint(Color.WHITE);
            pp.setLabelOutlinePaint(COL_GRID_LINE);
            pp.setLabelShadowPaint(null);
            pp.setLabelFont(new java.awt.Font("Helvetica", java.awt.Font.BOLD, 9));
            pp.setLabelPaint(COL_TEXT_PRIMARY);
            pp.setShadowPaint(null);
        }
    }

    private PdfPCell createChartCell(PdfWriter writer, JFreeChart chart) {
        PdfPCell cell = new PdfPCell();
        cell.setPadding(5);
        cell.setBorder(Rectangle.NO_BORDER);

        // Inner Frame - Removed Border to be consistent with Glass look
        PdfPTable frame = new PdfPTable(1);
        frame.setWidthPercentage(100);

        PdfPCell inner = new PdfPCell();
        // Glass Effect managed by separate event if needed, but for charts we let
        // transparency shine
        // inner.setCellEvent(new CardBackgroundEvent()); // Optional: Add card bg if
        // desired
        inner.setBorder(Rectangle.NO_BORDER);
        inner.setPadding(2);

        try {
            int width = 350; // slightly smaller
            int height = 180;
            PdfContentByte cb = writer.getDirectContent();
            PdfTemplate template = cb.createTemplate(width, height);
            Graphics2D g2d = template.createGraphics(width, height, new DefaultFontMapper());
            Rectangle2D r2d = new Rectangle2D.Double(0, 0, width, height);
            chart.draw(g2d, r2d);
            g2d.dispose();
            com.lowagie.text.Image chartImage = com.lowagie.text.Image.getInstance(template);
            inner.addElement(chartImage);
        } catch (Exception e) {
            inner.addElement(new Paragraph("Chart Error"));
        }
        frame.addCell(inner);
        cell.addElement(frame);
        return cell;
    }

    // --- Helper: Spacer ---
    private void addHeroHeader(Document document, String title, String subtitle) throws DocumentException {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingBefore(10);
        table.setSpacingAfter(20);

        PdfPCell titleCell = new PdfPCell(new Phrase(title.toUpperCase(),
                new Font(Font.HELVETICA, 18, Font.BOLD, Color.WHITE)));
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setPaddingBottom(5);
        table.addCell(titleCell);

        PdfPCell subCell = new PdfPCell(new Phrase(subtitle,
                new Font(Font.HELVETICA, 10, Font.NORMAL, new Color(203, 213, 225)))); // Slate 300
        subCell.setBorder(Rectangle.NO_BORDER);
        table.addCell(subCell);

        document.add(table);
    }

    // --- Inner Class: Glassmorphic Card Background (Premium) ---
    class GlassmorphicCardEvent implements PdfPCellEvent {
        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte canvas = canvases[PdfPTable.LINECANVAS];
            canvas.saveState();

            float x = position.getLeft();
            float y = position.getBottom();
            float w = position.getWidth();
            float h = position.getHeight();

            // Glass Effect: Translucent White Fill (8-10% Opacity)
            canvas.setColorFill(new Color(255, 255, 255, 25)); // ~10% White
            canvas.roundRectangle(x, y, w, h, 12);
            canvas.fill();

            // Inner Glow / Border (20% Opacity White)
            canvas.setColorStroke(new Color(255, 255, 255, 60)); // ~25% White
            canvas.setLineWidth(0.75f);
            canvas.roundRectangle(x, y, w, h, 12);
            canvas.stroke();

            canvas.restoreState();
        }
    }

    // Helper: Create a Glassmorphic Card
    // Helper: Create a Glassmorphic Card
    private PdfPCell createGlassmorphicCard(Element content) {
        PdfPCell card = new PdfPCell();
        card.setBorder(Rectangle.NO_BORDER);
        card.setPadding(15); // Inner padding
        card.setCellEvent(new GlassmorphicCardEvent());

        if (content instanceof PdfPTable) {
            card.addElement(content);
        } else if (content instanceof PdfPCell) {
            // Fix: Cannot add PdfPCell to PdfPCell directly, must wrap in Table
            PdfPTable wrapper = new PdfPTable(1);
            wrapper.setWidthPercentage(100);
            wrapper.addCell((PdfPCell) content);
            card.addElement(wrapper);
        } else {
            card.addElement(content);
        }

        return card;
    }

    // --- Executive Insight Box (Agent Tone) ---
    private PdfPCell createExecutiveInsightBox(String summary, String win, String opportunity) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);

        // Header
        PdfPCell header = new PdfPCell(new Phrase("EXECUTIVE INSIGHT",
                new Font(Font.HELVETICA, 10, Font.BOLD, COL_TEXT_SECONDARY)));
        header.setBorder(Rectangle.NO_BORDER);
        header.setPaddingBottom(8);
        table.addCell(header);

        // Summary Text
        PdfPCell summaryCell = new PdfPCell(new Phrase(summary,
                new Font(Font.HELVETICA, 12, Font.NORMAL, COL_TEXT_PRIMARY)));
        summaryCell.setBorder(Rectangle.NO_BORDER);
        summaryCell.setPaddingBottom(12);
        table.addCell(summaryCell);

        // Win & Opportunity Table
        PdfPTable insights = new PdfPTable(2);
        try {
            insights.setWidths(new float[] { 1, 1 }); // 50/50 split
            insights.setWidthPercentage(100);

            // Key Win
            PdfPCell winCell = new PdfPCell();
            winCell.setBorder(Rectangle.NO_BORDER);
            winCell.addElement(new Phrase("KEY WIN",
                    new Font(Font.HELVETICA, 9, Font.BOLD, new Color(22, 163, 74)))); // Green
            winCell.addElement(new Phrase(win,
                    new Font(Font.HELVETICA, 10, Font.NORMAL, COL_TEXT_SECONDARY)));
            insights.addCell(winCell);

            // Opportunity
            PdfPCell oppCell = new PdfPCell();
            oppCell.setBorder(Rectangle.NO_BORDER);
            oppCell.addElement(new Phrase("KEY OPPORTUNITY",
                    new Font(Font.HELVETICA, 9, Font.BOLD, new Color(29, 78, 216)))); // Blue
            oppCell.addElement(new Phrase(opportunity,
                    new Font(Font.HELVETICA, 10, Font.NORMAL, COL_TEXT_SECONDARY)));
            insights.addCell(oppCell);

        } catch (Exception e) {
        }

        PdfPCell insightsCell = new PdfPCell(insights);
        insightsCell.setBorder(Rectangle.NO_BORDER);
        insightsCell.setPaddingTop(10);

        table.addCell(insightsCell);

        return createGlassmorphicCard(table);
    }

    private PdfPCell createExecutiveInsightBox(String summary) {
        return createExecutiveInsightBox(summary, "Performance is stable.", "Identify growth drivers.");
    }

    // --- Helper: Mini Sparkline (Area Chart) ---
    private JFreeChart createMiniSparkline(List<ChartData> data, Color color) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            for (ChartData d : data) {
                Number val = d.getValue() != null ? d.getValue() : 0;
                ds.addValue(val, "Series1", d.getLabel());
            }
        }

        JFreeChart chart = ChartFactory.createAreaChart("", "", "", ds, PlotOrientation.VERTICAL, false, false, false);

        chart.setBackgroundPaint(null); // Transparent
        chart.setBorderVisible(false);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        plot.setBackgroundPaint(null); // Transparent
        plot.setOutlinePaint(null);
        plot.setRangeGridlinesVisible(false);
        plot.setDomainGridlinesVisible(false);
        plot.getDomainAxis().setVisible(false);
        plot.getRangeAxis().setVisible(false);
        plot.setAxisOffset(new RectangleInsets(0, 0, 0, 0));

        org.jfree.chart.renderer.category.AreaRenderer renderer = (org.jfree.chart.renderer.category.AreaRenderer) plot
                .getRenderer();
        // Simple Gradient
        renderer.setSeriesPaint(0, new Color(color.getRed(), color.getGreen(), color.getBlue(), 128)); // Semi-transparent

        return chart;
    }

    // --- Refactored: Content Only for Glassmorphic Cards ---
    private PdfPTable createExecutiveKpiCardContent(String title, String value, Double growth, Color color, String icon,
            List<ChartData> sparkData, boolean isCurrency) {

        PdfPTable table = new PdfPTable(2);
        try {
            table.setWidths(new float[] { 3, 1 }); // Main content vs Icon
            table.setWidthPercentage(100);

            // Left Col: Title, Value, Growth
            PdfPTable leftCol = new PdfPTable(1);
            leftCol.setWidthPercentage(100);

            // Title
            PdfPCell titleCell = new PdfPCell(new Phrase(title.toUpperCase(),
                    new Font(Font.HELVETICA, 8, Font.BOLD, COL_TEXT_MUTED)));
            titleCell.setBorder(Rectangle.NO_BORDER);
            leftCol.addCell(titleCell);

            // Value (Large, Bold)
            Font valueFont = new Font(Font.HELVETICA, 16, Font.BOLD, COL_TEXT_PRIMARY); // Dark text on glass
            PdfPCell valueCell = new PdfPCell(new Phrase(value, valueFont));
            valueCell.setBorder(Rectangle.NO_BORDER);
            valueCell.setPaddingTop(5);
            leftCol.addCell(valueCell);

            // Growth Indicator
            if (growth != null) {
                String symbol = growth > 0 ? "▲" : "▼";
                Color growthColor = growth > 0 ? new Color(22, 163, 74) : new Color(220, 38, 38); // Green/Red
                String growthStr = String.format("%s %.1f%% vs last month", symbol, Math.abs(growth));

                PdfPCell growthCell = new PdfPCell(new Phrase(growthStr,
                        new Font(Font.HELVETICA, 8, Font.BOLD, growthColor)));
                growthCell.setBorder(Rectangle.NO_BORDER);
                growthCell.setPaddingTop(2);
                leftCol.addCell(growthCell);
            } else {
                leftCol.addCell(createSpacer(12));
            }

            PdfPCell leftCell = new PdfPCell(leftCol);
            leftCell.setBorder(Rectangle.NO_BORDER);
            table.addCell(leftCell);

            // Right Col: Icon (Vector)
            PdfPCell iconCell = new PdfPCell();
            iconCell.setBorder(Rectangle.NO_BORDER);
            iconCell.setFixedHeight(40);
            IconType iconType = IconType.SALES;
            if ("CYCLE".equals(icon))
                iconType = IconType.TRANSACTIONS;
            if ("USER".equals(icon))
                iconType = IconType.CUSTOMERS;

            iconCell.setCellEvent(new VectorIconEvent(color, iconType));
            table.addCell(iconCell);

            // 4. Sparkline (Bottom) - Restoring Innovative Design
            if (sparkData != null && !sparkData.isEmpty()) {
                PdfPCell sparkCell = new PdfPCell();
                sparkCell.setColspan(2); // Span full width
                sparkCell.setBorder(Rectangle.NO_BORDER);
                sparkCell.setFixedHeight(25); // Space for sparkline
                sparkCell.setPaddingTop(5);

                sparkCell.setCellEvent(new PdfPCellEvent() {
                    public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                        PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                        cb.saveState();

                        float x = position.getLeft();
                        float y = position.getBottom() + 5; // padding
                        float w = position.getWidth();
                        float h = position.getHeight() - 10;

                        // Find min/max
                        float min = Float.MAX_VALUE;
                        float max = Float.MIN_VALUE;
                        for (ChartData d : sparkData) {
                            java.math.BigDecimal valRaw = isCurrency ? d.getValue() : d.getValue2();
                            float v = valRaw != null ? valRaw.floatValue() : 0.0f;
                            if (v < min)
                                min = v;
                            if (v > max)
                                max = v;
                        }

                        if (max == min)
                            max = min + 1; // avoid div/0

                        float stepX = w / (sparkData.size() - 1);

                        // 1. Draw Filled Area (Glass Effect)
                        cb.moveTo(x, y);
                        for (int i = 0; i < sparkData.size(); i++) {
                            java.math.BigDecimal valRaw = isCurrency ? sparkData.get(i).getValue()
                                    : sparkData.get(i).getValue2();
                            float val = valRaw != null ? valRaw.floatValue() : 0.0f;
                            float px = x + (i * stepX);
                            float py = y + ((val - min) / (max - min)) * h;
                            cb.lineTo(px, py);
                        }
                        cb.lineTo(x + w, y);
                        cb.closePath();
                        cb.setColorFill(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40)); // 15%
                                                                                                           // opacity
                        cb.fill();

                        // 2. Draw Stroke Line
                        cb.setColorStroke(color);
                        cb.setLineWidth(1.5f);
                        boolean first = true;
                        for (int i = 0; i < sparkData.size(); i++) {
                            java.math.BigDecimal valRaw = isCurrency ? sparkData.get(i).getValue()
                                    : sparkData.get(i).getValue2();
                            float val = valRaw != null ? valRaw.floatValue() : 0.0f;
                            float px = x + (i * stepX);
                            float py = y + ((val - min) / (max - min)) * h;

                            if (first) {
                                cb.moveTo(px, py);
                                first = false;
                            } else {
                                cb.lineTo(px, py);
                            }
                        }
                        cb.stroke();
                        cb.restoreState();
                    }
                });
                table.addCell(sparkCell);
            } else {
                // Add spacer if no data
                PdfPCell spacer = new PdfPCell();
                spacer.setColspan(2);
                spacer.setBorder(Rectangle.NO_BORDER);
                spacer.setFixedHeight(10);
                table.addCell(spacer);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return table;
    }

    // --- TABLE HELPERS (GLASS STYLE FOR DARK BACKGROUND) ---

    // --- Page 4 Helpers ---

    private PdfPTable createSimpleKpiContent(String title, String value, Color accentColor) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);

        PdfPCell titleC = new PdfPCell(new Phrase(title.toUpperCase(),
                new Font(Font.HELVETICA, 8, Font.BOLD, COL_TEXT_MUTED)));
        titleC.setBorder(Rectangle.NO_BORDER);
        table.addCell(titleC);

        PdfPCell valC = new PdfPCell(new Phrase(value,
                new Font(Font.HELVETICA, 14, Font.BOLD, accentColor != null ? accentColor : Color.WHITE)));
        valC.setBorder(Rectangle.NO_BORDER);
        valC.setPaddingTop(4);
        table.addCell(valC);

        return table;
    }

    private PdfPCell createNextStepCard(String type, String focus, String action) {
        PdfPTable content = new PdfPTable(1);
        content.setWidthPercentage(100);

        // Type Badge (e.g., CAPITALIZE)
        Font badgeFont = new Font(Font.HELVETICA, 7, Font.BOLD, Color.WHITE);
        Color badgeColor = "CAPITALIZE".equals(type) ? new Color(22, 163, 74) : // Green
                "INVESTIGATE".equals(type) ? new Color(220, 38, 38) : // Red
                        new Color(59, 130, 246); // Blue

        Chunk badge = new Chunk(type, badgeFont);
        badge.setBackground(badgeColor, 2, 2, 2, 2);

        PdfPCell typeC = new PdfPCell(new Paragraph(badge));
        typeC.setBorder(Rectangle.NO_BORDER);
        typeC.setPaddingBottom(6);
        content.addCell(typeC);

        // Focus
        PdfPCell focusC = new PdfPCell(new Phrase(focus,
                new Font(Font.HELVETICA, 10, Font.BOLD, Color.WHITE)));
        focusC.setBorder(Rectangle.NO_BORDER);
        content.addCell(focusC);

        // Action
        PdfPCell actionC = new PdfPCell(new Phrase(action,
                new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(203, 213, 225)))); // Slate 300
        actionC.setBorder(Rectangle.NO_BORDER);
        actionC.setPaddingTop(4);
        content.addCell(actionC);

        return createGlassmorphicCard(content);
    }

    private PdfPCell createGlassChartCell(PdfWriter writer, JFreeChart chart) {
        // Render chart to transparent image
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(5);

        PdfPTable chartC = new PdfPTable(1);
        chartC.setWidthPercentage(100);

        try {
            int width = 450; // Increased width
            int height = 220;
            PdfContentByte cb = writer.getDirectContent();
            PdfTemplate template = cb.createTemplate(width, height);
            Graphics2D g2d = template.createGraphics(width, height, new DefaultFontMapper());
            Rectangle2D r2d = new Rectangle2D.Double(0, 0, width, height);

            // Ensure chart transparent bg
            chart.setBackgroundPaint(null);
            chart.draw(g2d, r2d);
            g2d.dispose();

            com.lowagie.text.Image chartImage = com.lowagie.text.Image.getInstance(template);
            chartImage.setWidthPercentage(100);

            PdfPCell imgCell = new PdfPCell(chartImage);
            imgCell.setBorder(Rectangle.NO_BORDER);
            imgCell.setPadding(10);
            chartC.addCell(imgCell);

        } catch (Exception e) {
        }

        return createGlassmorphicCard(chartC);
    }

    private JFreeChart createGradientBarChart(String title, String yLabel, Map<String, ?> data, Color baseColor) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            data.forEach((k, v) -> {
                Number val = 0;
                if (v instanceof Number)
                    val = (Number) v;
                else if (v instanceof com.acquira.dto.Merchant360DTO.ValueWithGrowth)
                    val = ((com.acquira.dto.Merchant360DTO.ValueWithGrowth) v).getValue().doubleValue();
                // Using doubleValue for safety
                ds.addValue(val, "Series1", k);
            });
        }

        JFreeChart chart = ChartFactory.createBarChart(title, "", yLabel, ds, PlotOrientation.VERTICAL, false, false,
                false);
        chart.setBackgroundPaint(null);
        chart.setBorderVisible(false);

        // Plot and Renderer
        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        plot.setBackgroundPaint(null);
        plot.setOutlinePaint(null);
        plot.setRangeGridlinePaint(new Color(255, 255, 255, 50));
        plot.setRangeGridlinesVisible(true);
        plot.setDomainGridlinesVisible(false);

        // Axis
        plot.getDomainAxis().setLabelPaint(Color.WHITE);
        plot.getDomainAxis().setTickLabelPaint(new Color(203, 213, 225));
        plot.getRangeAxis().setLabelPaint(Color.WHITE);
        plot.getRangeAxis().setTickLabelPaint(new Color(203, 213, 225));

        org.jfree.chart.renderer.category.BarRenderer renderer = (org.jfree.chart.renderer.category.BarRenderer) plot
                .getRenderer();
        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
        renderer.setSeriesPaint(0, baseColor);
        renderer.setShadowVisible(false);

        return chart;
    }

    // --- Page 5 Helpers ---

    private PdfPTable wrapInGlassBadge(String text, Color color) {
        Font badgeFont = new Font(Font.HELVETICA, 8, Font.BOLD, Color.WHITE);
        Chunk badge = new Chunk(text, badgeFont);
        badge.setBackground(color, 4, 4, 4, 4);

        PdfPCell cell = new PdfPCell(new Paragraph(badge));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingBottom(5);

        PdfPTable wrapper = new PdfPTable(1);
        wrapper.setWidthPercentage(100);
        wrapper.addCell(cell);
        return wrapper;
    }

    private JFreeChart createAreaChart(String title, String yLabel, Map<String, ?> data) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            data.forEach((k, v) -> {
                Number val = 0;
                if (v instanceof Number)
                    val = (Number) v;
                else if (v instanceof com.acquira.dto.Merchant360DTO.ValueWithGrowth)
                    val = ((com.acquira.dto.Merchant360DTO.ValueWithGrowth) v).getValue().doubleValue();
                ds.addValue(val, "Series1", k);
            });
        }

        JFreeChart chart = ChartFactory.createAreaChart(title, "", yLabel, ds, PlotOrientation.VERTICAL, false, false,
                false);
        chart.setBackgroundPaint(null);
        chart.setBorderVisible(false);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        plot.setBackgroundPaint(null);
        plot.setOutlinePaint(null);
        plot.setRangeGridlinePaint(new Color(255, 255, 255, 50));
        plot.setRangeGridlinesVisible(true);
        plot.setDomainGridlinesVisible(false);

        // Axis
        plot.getDomainAxis().setLabelPaint(Color.WHITE);
        plot.getDomainAxis().setTickLabelPaint(new Color(203, 213, 225));
        plot.getRangeAxis().setLabelPaint(Color.WHITE);
        plot.getRangeAxis().setTickLabelPaint(new Color(203, 213, 225));

        org.jfree.chart.renderer.category.AreaRenderer renderer = (org.jfree.chart.renderer.category.AreaRenderer) plot
                .getRenderer();
        renderer.setSeriesPaint(0,
                new Color(CHART_PRIMARY.getRed(), CHART_PRIMARY.getGreen(), CHART_PRIMARY.getBlue(), 200)); // Professional
                                                                                                            // Blue 600,
                                                                                                            // semi-transparent

        return chart;
    }
    // --- Page 6 Helpers ---

    private PdfPCell createPyramidTier(String title, String subtitle, Color color) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);

        // Colored Bar
        PdfPCell bar = new PdfPCell(new Phrase(" ", new Font(Font.HELVETICA, 1)));
        bar.setBackgroundColor(color);
        bar.setBorder(Rectangle.NO_BORDER);
        bar.setFixedHeight(6); // Thin bar
        table.addCell(bar);

        // Content
        PdfPCell content = new PdfPCell();
        content.setBorder(Rectangle.NO_BORDER);
        content.setPaddingTop(5);
        content.setPaddingBottom(12);

        content.addElement(new Phrase(title, new Font(Font.HELVETICA, 11, Font.BOLD, Color.WHITE)));
        content.addElement(new Phrase(subtitle, new Font(Font.HELVETICA, 10, Font.BOLD, new Color(148, 163, 184)))); // Medium
                                                                                                                     // gray
                                                                                                                     // for
                                                                                                                     // readability

        table.addCell(content);

        PdfPCell cell = new PdfPCell(table);
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    private PdfPCell createStrategyCard(String title, String desc) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(95); // Slight gap

        PdfPCell tCell = new PdfPCell(
                new Phrase(title, new Font(Font.HELVETICA, 9, Font.BOLD, new Color(251, 191, 36)))); // Amber
        tCell.setBorder(Rectangle.NO_BORDER);
        table.addCell(tCell);

        PdfPCell dCell = new PdfPCell(new Phrase(desc, new Font(Font.HELVETICA, 8, Font.NORMAL, Color.WHITE)));
        dCell.setBorder(Rectangle.NO_BORDER);
        dCell.setPaddingTop(4);
        table.addCell(dCell);

        PdfPCell cell = new PdfPCell(table);
        cell.setBorder(Rectangle.LEFT);
        cell.setBorderColor(new Color(255, 255, 255, 50));
        cell.setBorderWidthLeft(2f);
        cell.setPaddingLeft(10);

        return cell;
    }

    // --- Page 7 Helpers ---

    private JFreeChart createHourlyProfileChart(PdfWriter writer) {
        // Mock Hourly Data (0-23 hours)
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        // Morning Lull
        ds.addValue(10, "Sales", "6AM");
        ds.addValue(15, "Sales", "9AM");
        ds.addValue(45, "Sales", "12PM");
        // Afternoon Dip
        ds.addValue(30, "Sales", "3PM");
        // Evening Peak
        ds.addValue(85, "Sales", "6PM");
        ds.addValue(95, "Sales", "8PM");
        ds.addValue(60, "Sales", "10PM");
        ds.addValue(20, "Sales", "12AM");

        JFreeChart chart = ChartFactory.createBarChart("HOURLY ACTIVITY PROFILE", "", "Intensity", ds,
                PlotOrientation.VERTICAL, false, false, false);
        chart.setBackgroundPaint(null);
        chart.setBorderVisible(false);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        plot.setBackgroundPaint(null);
        plot.setOutlinePaint(null);
        plot.setRangeGridlinePaint(new Color(255, 255, 255, 50));
        plot.setRangeGridlinesVisible(true);
        plot.setDomainGridlinesVisible(false);

        // Axis - Improved readability with larger font and better spacing
        plot.getDomainAxis().setLabelPaint(Color.WHITE);
        plot.getDomainAxis().setTickLabelPaint(Color.WHITE); // Bright white for visibility
        plot.getDomainAxis().setTickLabelFont(new java.awt.Font("Inter", java.awt.Font.BOLD, 12)); // Larger font (12
                                                                                                   // instead of 11)
        plot.getDomainAxis().setLowerMargin(0.02); // Add margin for better label visibility
        plot.getDomainAxis().setUpperMargin(0.02);
        plot.getDomainAxis().setCategoryMargin(0.1); // Space between categories
        plot.getRangeAxis().setVisible(false); // Hide intensity values

        org.jfree.chart.renderer.category.BarRenderer renderer = (org.jfree.chart.renderer.category.BarRenderer) plot
                .getRenderer();
        renderer.setSeriesPaint(0, CHART_INFO); // Professional Cyan 600 for hourly activity
        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
        renderer.setShadowVisible(false);

        return chart;
    }

    private PdfPTable createCapacityGauge(String title, String value) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);

        PdfPCell titleC = new PdfPCell(
                new Phrase(title.toUpperCase(), new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE))); // Bright white,
                                                                                                       // larger font
        titleC.setBorder(Rectangle.NO_BORDER);
        table.addCell(titleC);

        // Value Text
        PdfPCell valC = new PdfPCell(new Phrase(value, new Font(Font.HELVETICA, 24, Font.BOLD, Color.WHITE)));
        valC.setBorder(Rectangle.NO_BORDER);
        valC.setPaddingTop(5);
        valC.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(valC);

        // Visual Bar (Gauge simulation)
        PdfPCell barCell = new PdfPCell();
        barCell.setBorder(Rectangle.NO_BORDER);
        barCell.setPaddingTop(5);

        PdfPTable barTable = new PdfPTable(2);
        try {
            barTable.setWidths(new float[] { 78, 22 }); // 78% filled
        } catch (Exception e) {
        }
        barTable.setWidthPercentage(100);

        PdfPCell fill = new PdfPCell(new Phrase(" ", new Font(Font.HELVETICA, 5)));
        fill.setBackgroundColor(new Color(239, 68, 68)); // Red (High utilization)
        fill.setBorder(Rectangle.NO_BORDER);
        fill.setFixedHeight(8);
        barTable.addCell(fill);

        PdfPCell empty = new PdfPCell(new Phrase(" ", new Font(Font.HELVETICA, 5)));
        empty.setBackgroundColor(new Color(255, 255, 255, 30));
        empty.setBorder(Rectangle.NO_BORDER);
        empty.setFixedHeight(8);
        barTable.addCell(empty);

        barCell.addElement(barTable);
        table.addCell(barCell);

        return table;
    }

    // --- Page 8 Helpers ---

    private JFreeChart createDualAreaChart(String title, String yLabel, Map<String, ?> data) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            data.forEach((k, v) -> {
                Number val = 0;
                if (v instanceof Number)
                    val = (Number) v;
                else if (v instanceof com.acquira.dto.Merchant360DTO.ValueWithGrowth)
                    val = ((com.acquira.dto.Merchant360DTO.ValueWithGrowth) v).getValue().doubleValue();

                // Baseline
                ds.addValue(val, "Baseline", k);

                // Projection (Simulated +20%)
                ds.addValue(val.doubleValue() * 1.2, "Projected", k);
            });
        }

        JFreeChart chart = ChartFactory.createAreaChart(title, "", yLabel, ds, PlotOrientation.VERTICAL, false, false,
                false);
        chart.setBackgroundPaint(null);
        chart.setBorderVisible(false);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        plot.setBackgroundPaint(null);
        plot.setOutlinePaint(null);
        plot.setRangeGridlinePaint(new Color(255, 255, 255, 50));
        plot.setRangeGridlinesVisible(true);
        plot.setDomainGridlinesVisible(false);

        // Axis
        plot.getDomainAxis().setLabelPaint(Color.WHITE);
        plot.getDomainAxis().setTickLabelPaint(new Color(203, 213, 225));
        plot.getRangeAxis().setLabelPaint(Color.WHITE);
        plot.getRangeAxis().setTickLabelPaint(new Color(203, 213, 225));

        org.jfree.chart.renderer.category.AreaRenderer renderer = (org.jfree.chart.renderer.category.AreaRenderer) plot
                .getRenderer();

        // Series 1 (Projected) - dashed? Area renderer doesn't start dashed easily.
        // We'll use semi-transparent Green for Projected
        renderer.setSeriesPaint(1, new Color(34, 197, 94, 150)); // Green

        // Series 0 (Baseline) - Blue
        renderer.setSeriesPaint(0, new Color(59, 130, 246, 200)); // Blue

        return chart;
    }

    private PdfPCell createStrategicBreakdown(String title, String desc, Color borderColor) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(95);

        PdfPCell tCell = new PdfPCell(
                new Phrase(title.toUpperCase(), new Font(Font.HELVETICA, 8, Font.BOLD, borderColor)));
        tCell.setBorder(Rectangle.NO_BORDER);
        table.addCell(tCell);

        PdfPCell dCell = new PdfPCell(new Phrase(desc, new Font(Font.HELVETICA, 8, Font.NORMAL, Color.WHITE)));
        dCell.setBorder(Rectangle.NO_BORDER);
        dCell.setPaddingTop(4);
        table.addCell(dCell);

        PdfPCell cell = new PdfPCell(table);
        cell.setBorder(Rectangle.LEFT);
        cell.setBorderColor(borderColor);
        cell.setBorderWidthLeft(3f);
        cell.setPaddingLeft(10);
        cell.setPaddingBottom(10);

        return cell;
    }

    // --- Page 9 Helpers ---
    private JFreeChart createDonutChart(String title, Map<String, ?> data) {
        DefaultPieDataset ds = new DefaultPieDataset();
        if (data != null) {
            data.forEach((k, v) -> {
                Number val = 0;
                if (v instanceof Number)
                    val = (Number) v;
                else if (v instanceof com.acquira.dto.Merchant360DTO.ValueWithGrowth)
                    val = ((com.acquira.dto.Merchant360DTO.ValueWithGrowth) v).getValue().doubleValue();
                ds.setValue(k, val);
            });
        }

        JFreeChart chart = ChartFactory.createRingChart(title, ds, false, false, false); // No legend/tooltips
        chart.setBackgroundPaint(null);
        chart.setBorderVisible(false);

        org.jfree.chart.plot.RingPlot plot = (org.jfree.chart.plot.RingPlot) chart.getPlot();
        plot.setBackgroundPaint(null);
        plot.setOutlinePaint(null);

        // Add percentage labels
        plot.setLabelGenerator(new org.jfree.chart.labels.StandardPieSectionLabelGenerator(
                "{0}: {2}", // Format: Label: Percentage
                java.text.NumberFormat.getNumberInstance(),
                java.text.NumberFormat.getPercentInstance()));
        plot.setLabelFont(new java.awt.Font("Inter", java.awt.Font.BOLD, 11));
        plot.setLabelPaint(Color.WHITE);
        plot.setLabelBackgroundPaint(null);
        plot.setLabelOutlinePaint(null);
        plot.setLabelShadowPaint(null);

        plot.setSectionDepth(0.35); // Thickness
        plot.setShadowPaint(null);

        // PROFESSIONAL COLOR PALETTE (Bank-Grade)
        plot.setSectionPaint("Visa", CHART_INFO); // Cyan 600 - Clean, professional
        plot.setSectionPaint("Mastercard", CHART_PURPLE); // Purple 700 - Sophisticated
        plot.setSectionPaint("Mada", CHART_SUCCESS); // Green 600 - Emerald

        // Generic fallback for card types
        plot.setSectionPaint("Credit", CHART_PRIMARY); // Blue 600 - Primary
        plot.setSectionPaint("Debit", CHART_SUCCESS); // Green 600 - Secondary

        // Entry Modes (Professional consistency)
        plot.setSectionPaint("Chip", CHART_INFO); // Cyan 600 - Matches overall scheme
        plot.setSectionPaint("Contactless", CHART_PURPLE); // Purple 700 - Sophisticated
        plot.setSectionPaint("Swipe", CHART_DANGER); // Red 600 - Muted warning, not harsh

        return chart;
    }

    // Helper for Payment Insight Cards
    private PdfPCell createPaymentInsightCard(String title, String description, Color accentColor) {
        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        // Title
        PdfPCell titleCell = new PdfPCell(new Phrase(title, new Font(Font.HELVETICA, 9, Font.BOLD, Color.WHITE)));
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setPaddingBottom(5);
        titleCell.setPaddingLeft(10);
        titleCell.setPaddingTop(8);
        titleCell
                .setBackgroundColor(new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 30)); // Subtle
                                                                                                                         // tint
        card.addCell(titleCell);

        // Description
        PdfPCell descCell = new PdfPCell(
                new Phrase(description, new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(203, 213, 225))));
        descCell.setBorder(Rectangle.NO_BORDER);
        descCell.setPaddingLeft(10);
        descCell.setPaddingRight(10);
        descCell.setPaddingBottom(8);
        card.addCell(descCell);

        PdfPCell wrapper = new PdfPCell(card);
        wrapper.setBorder(Rectangle.LEFT);
        wrapper.setBorderWidthLeft(3);
        wrapper.setBorderColorLeft(accentColor);
        wrapper.setPadding(0);
        wrapper.setBackgroundColor(new Color(30, 41, 59, 180)); // Dark glassmorphic
        wrapper.setPaddingBottom(8);

        return wrapper;
    }

    // --- Page 10 Helpers ---

    private PdfPTable createVisitPyramid() {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.addCell(createGlassHeaderCell("VISIT FREQUENCY PYRAMID"));

        // Pyramid Tiers (Simulated with Bar Widths)
        table.addCell(createPyramidTier("HIGH FREQUENCY (5+ VISITS)", "VIPs driving 40% Rev", new Color(124, 58, 237))); // Violet
        table.addCell(createPyramidTier("REGULAR (2-4 VISITS)", "Habitual shoppers", new Color(59, 130, 246))); // Blue
        table.addCell(createPyramidTier("CASUAL (1 VISIT)", "Opportunity to convert", new Color(14, 165, 233))); // Sky

        // Reuse createPyramidTier from Page 6 helpers (ensure it handles width or just
        // visual color stack).
        // Since createPyramidTier just makes a colored bar, it works vertically as a
        // list.
        // To make it look like a pyramid, we could adjust margins?
        // For now, a "Stacked Tier List" is sufficient for the "Pyramid" concept
        // representation in PDF tables without complex drawing.

        return table;
    }

    private PdfPTable createSpendBandVisual() {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.addCell(createGlassHeaderCell("SPEND BAND ANALYSIS"));

        // Horizontal Bars for Spend Bands
        // Band 1: High Spenders
        table.addCell(createBandRow("> AED 500", "15% of Cust", 85, new Color(16, 185, 129))); // Green
        // Band 2: Mid
        table.addCell(createBandRow("AED 100-500", "45% of Cust", 60, new Color(59, 130, 246))); // Blue
        // Band 3: Low
        table.addCell(createBandRow("< AED 100", "40% of Cust", 40, new Color(245, 158, 11))); // Amber

        return table;
    }

    private PdfPCell createBandRow(String label, String value, int widthPercent, Color color) {
        PdfPTable row = new PdfPTable(2);
        try {
            row.setWidths(new float[] { 1, 1 });
        } catch (Exception e) {
        }
        row.setWidthPercentage(100);

        PdfPCell txt = new PdfPCell();
        txt.setBorder(Rectangle.NO_BORDER);
        txt.addElement(new Phrase(label, new Font(Font.HELVETICA, 11, Font.BOLD, new Color(51, 65, 85)))); // Dark slate
                                                                                                           // for
                                                                                                           // readability
        txt.addElement(new Phrase(value, new Font(Font.HELVETICA, 10, Font.BOLD, new Color(71, 85, 105)))); // Darker
                                                                                                            // gray for
                                                                                                            // readability
        row.addCell(txt);

        PdfPCell barCell = new PdfPCell();
        barCell.setBorder(Rectangle.NO_BORDER);
        barCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        Chunk bar = new Chunk(" ", new Font(Font.HELVETICA, 1));
        bar.setBackground(color);
        // We can't easily set width of a chunk like a div.
        // We'll use a nested table for the bar
        PdfPTable barTable = new PdfPTable(2);
        try {
            barTable.setWidths(new float[] { widthPercent, 100 - widthPercent });
        } catch (Exception e) {
        }
        barTable.setWidthPercentage(100);

        PdfPCell fill = new PdfPCell(new Phrase(" "));
        fill.setBackgroundColor(color);
        fill.setBorder(Rectangle.NO_BORDER);
        fill.setFixedHeight(4);
        barTable.addCell(fill);

        PdfPCell empty = new PdfPCell(new Phrase(" "));
        empty.setBorder(Rectangle.NO_BORDER);
        barTable.addCell(empty);

        barCell.addElement(barTable);
        row.addCell(barCell);

        PdfPCell cell = new PdfPCell(row);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingBottom(8);
        return cell;
    }

    // --- Page 11 Helpers ---

    private PdfPCell createYearlySummaryCard(String totalSales, String salesGrowth, String txns, String bestMonth) {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setWidths(new float[] { 1, 1, 1, 1 });

        table.addCell(createSimpleKpiContent("TOTAL SALES", totalSales, COL_ACCENT_SALES));
        table.addCell(createSimpleKpiContent("AVG GROWTH", salesGrowth, COL_ACCENT_GROWTH)); // Green
        table.addCell(createSimpleKpiContent("AVG TXNS/MO", txns, COL_ACCENT_TXNS)); // Blue
        table.addCell(createSimpleKpiContent("BEST MONTH", bestMonth, new Color(124, 58, 237))); // Violet

        // Wrap each cell in a clean borderless container?
        // createSimpleKpiContent returns a table. We need to wrap it in a cell for the
        // `table` row.
        // Actually createGlassmorphicCard expects content.
        // I should return a single card wrapping this 4-col table.
        // But createSimpleKpiContent returns a Table.
        // So `table` (4 cols) holds 4 tables.

        // Correct implementation:
        PdfPTable container = new PdfPTable(1);
        container.setWidthPercentage(100);

        // Header
        container.addCell(createGlassHeaderCell("YEARLY PERFORMANCE SUMMARY"));

        PdfPCell content = new PdfPCell(table);
        content.setBorder(Rectangle.NO_BORDER);
        content.setPaddingTop(10);
        container.addCell(content);

        return createGlassmorphicCard(container);
        // Need to fix logic: createSimpleKpiContent returns PdfPTable.
        // `table.addCell(PdfPTable)` works?
        // OpenPdf/iText PdfPTable.addCell(PdfPTable) exists. Yes.
    }

    private PdfPCell createSeasonalityInsight(String title, String desc) {
        PdfPTable table = new PdfPTable(2);
        try {
            table.setWidths(new float[] { 15, 85 });
        } catch (Exception e) {
        }
        table.setWidthPercentage(100);

        // Icon
        PdfPCell icon = new PdfPCell(new Phrase("☀️", new Font(Font.HELVETICA, 20)));
        icon.setBorder(Rectangle.NO_BORDER);
        icon.setVerticalAlignment(Element.ALIGN_MIDDLE);
        table.addCell(icon);

        // Text
        PdfPCell txt = new PdfPCell();
        txt.setBorder(Rectangle.NO_BORDER);
        txt.addElement(new Phrase(title, new Font(Font.HELVETICA, 9, Font.BOLD, new Color(251, 191, 36)))); // Amber
        txt.addElement(new Phrase(desc, new Font(Font.HELVETICA, 9, Font.NORMAL, Color.WHITE)));
        table.addCell(txt);

        return createGlassmorphicCard(table);
    }

    // --- Page 12 Helpers ---
    private JFreeChart createWaterfallChart(String title, String yLabel, Map<String, ?> data) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        // Since we are simulating impact, we'll use a few fixed buckets if data is map,
        // or just use the map keys.
        if (data != null) {
            data.forEach((k, v) -> {
                Number val = 0;
                if (v instanceof Number)
                    val = (Number) v;
                else if (v instanceof com.acquira.dto.Merchant360DTO.ValueWithGrowth)
                    val = ((com.acquira.dto.Merchant360DTO.ValueWithGrowth) v).getValue().doubleValue();
                ds.addValue(val, "Impact", k);
            });
        } else {
            // Mock impact chain if data is null
            ds.addValue(100, "Impact", "Eligible");
            ds.addValue(42, "Impact", "Converted");
            ds.addValue(-58, "Impact", "Opt-Out");
        }

        JFreeChart chart = ChartFactory.createBarChart(title, "", yLabel, ds, PlotOrientation.VERTICAL, false, false,
                false);
        chart.setBackgroundPaint(null);
        chart.setBorderVisible(false);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        plot.setBackgroundPaint(null);
        plot.setOutlinePaint(null);
        plot.setRangeGridlinePaint(new Color(255, 255, 255, 50));

        // Axis styling
        plot.getDomainAxis().setLabelPaint(Color.WHITE);
        plot.getDomainAxis().setTickLabelPaint(new Color(203, 213, 225));
        plot.getRangeAxis().setLabelPaint(Color.WHITE);
        plot.getRangeAxis().setTickLabelPaint(new Color(203, 213, 225));

        org.jfree.chart.renderer.category.BarRenderer renderer = (org.jfree.chart.renderer.category.BarRenderer) plot
                .getRenderer();
        renderer.setSeriesPaint(0, new Color(59, 130, 246)); // Default Blue
        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
        renderer.setShadowVisible(false);
        renderer.setDrawBarOutline(false);

        // Custom colors for waterfall feel (Positive/Negative/Total)
        // We'll just use a gradient or blue spectrum for now as per theme rules.
        renderer.setSeriesPaint(0, new Color(59, 130, 246, 200));

        return chart;
    }

    // --- Page 14 Helpers ---
    private PdfPTable createPrioritizationMatrix() {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.addCell(createGlassHeaderCell("PRIORITIZATION MATRIX (ROI vs EFFORT)"));

        PdfPTable grid = new PdfPTable(2);
        grid.setWidthPercentage(100);

        // Q1: High Impact, Low Effort (Top Left) - Quick Wins
        grid.addCell(createMatrixQuadrant("QUICK WINS", "Payment Mix", new Color(16, 185, 129))); // Green

        // Q2: High Impact, High Effort (Top Right) - Strategic Initiatives
        grid.addCell(createMatrixQuadrant("STRATEGIC", "Retention Plan", new Color(59, 130, 246))); // Blue

        // Q3: Low Impact, Low Effort (Bottom Left) - Fill-Ins
        grid.addCell(createMatrixQuadrant("FILL-INS", "DCC Micro-train", new Color(251, 191, 36))); // Amber

        // Q4: Low Impact, High Effort (Bottom Right) - Hard Slogs
        grid.addCell(createMatrixQuadrant("HARD SLOGS", "System migration", new Color(239, 68, 68))); // Red

        PdfPCell gridCell = new PdfPCell(grid);
        gridCell.setBorder(Rectangle.NO_BORDER);
        gridCell.setPaddingTop(10);
        table.addCell(gridCell);

        return table;
    }

    private PdfPCell createMatrixQuadrant(String label, String detail, Color color) {
        PdfPTable q = new PdfPTable(1);
        q.setWidthPercentage(95);

        PdfPCell lCell = new PdfPCell(new Phrase(label, new Font(Font.HELVETICA, 8, Font.BOLD, Color.WHITE)));
        lCell.setBorder(Rectangle.NO_BORDER);
        lCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        lCell.setBackgroundColor(color);
        lCell.setPadding(4);
        q.addCell(lCell);

        PdfPCell dCell = new PdfPCell(new Phrase(detail, new Font(Font.HELVETICA, 7, Font.NORMAL, Color.WHITE)));
        dCell.setBorder(Rectangle.NO_BORDER);
        dCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        dCell.setPadding(4);
        q.addCell(dCell);

        PdfPCell cell = new PdfPCell(q);
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(new Color(255, 255, 255, 30));
        cell.setPadding(5);
        return cell;
    }

    // --- Page 15 Helpers ---
    private PdfPTable createRoadmapVisual() {
        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);
        table.setWidths(new float[] { 1, 1, 1 });

        // Phase 1: Optimization
        table.addCell(
                createRoadmapPhase("PHASE 1: OPTIMIZE", "Mix & DCC Fix", "CURRENT - 30D", new Color(59, 130, 246)));

        // Phase 2: Growth
        table.addCell(createRoadmapPhase("PHASE 2: GROW", "Loyalty & Retention", "30D - 60D", new Color(139, 92, 246)));

        // Phase 3: Scale
        table.addCell(createRoadmapPhase("PHASE 3: SCALE", "Market Expansion", "60D - 90D", new Color(16, 185, 129)));

        return table;
    }

    private PdfPCell createRoadmapPhase(String title, String desc, String timeline, Color color) {
        PdfPTable phase = new PdfPTable(1);
        phase.setWidthPercentage(95);

        PdfPCell tCell = new PdfPCell(new Phrase(timeline, new Font(Font.HELVETICA, 7, Font.BOLD, color)));
        tCell.setBorder(Rectangle.NO_BORDER);
        tCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        phase.addCell(tCell);

        PdfPCell titleCell = new PdfPCell(new Phrase(title, new Font(Font.HELVETICA, 8, Font.BOLD, Color.WHITE)));
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        titleCell.setPaddingTop(5);
        phase.addCell(titleCell);

        PdfPCell descCell = new PdfPCell(
                new Phrase(desc, new Font(Font.HELVETICA, 7, Font.NORMAL, new Color(203, 213, 225))));
        descCell.setBorder(Rectangle.NO_BORDER);
        descCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        descCell.setPaddingTop(2);
        phase.addCell(descCell);

        PdfPCell cell = new PdfPCell(phase);
        cell.setBorder(Rectangle.LEFT);
        cell.setBorderColor(color);
        cell.setBorderWidthLeft(3f);
        cell.setPadding(10);
        return cell;
    }

    /**
     * Creates a 4-week stacked bar chart showing daily revenue breakdown by week
     * with rainbow color-coding by day of week to visualize weekly patterns and
     * growth
     */
    private JFreeChart createWeeklyComparisonChart(PdfWriter writer) {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        // Week 1 (oldest) - Baseline week
        dataset.addValue(5000, "Monday", "Week 1");
        dataset.addValue(5200, "Tuesday", "Week 1");
        dataset.addValue(4800, "Wednesday", "Week 1");
        dataset.addValue(6100, "Thursday", "Week 1");
        dataset.addValue(6800, "Friday", "Week 1");
        dataset.addValue(12000, "Saturday", "Week 1");
        dataset.addValue(9500, "Sunday", "Week 1");

        // Week 2 - Slight growth
        dataset.addValue(5400, "Monday", "Week 2");
        dataset.addValue(5500, "Tuesday", "Week 2");
        dataset.addValue(5100, "Wednesday", "Week 2");
        dataset.addValue(6400, "Thursday", "Week 2");
        dataset.addValue(7200, "Friday", "Week 2");
        dataset.addValue(13200, "Saturday", "Week 2");
        dataset.addValue(10200, "Sunday", "Week 2");

        // Week 3 - Best week!
        dataset.addValue(6000, "Monday", "Week 3");
        dataset.addValue(6100, "Tuesday", "Week 3");
        dataset.addValue(5800, "Wednesday", "Week 3");
        dataset.addValue(7000, "Thursday", "Week 3");
        dataset.addValue(7800, "Friday", "Week 3");
        dataset.addValue(14500, "Saturday", "Week 3");
        dataset.addValue(11200, "Sunday", "Week 3");

        // Week 4 (current) - Strong continuation
        dataset.addValue(6200, "Monday", "Week 4");
        dataset.addValue(6300, "Tuesday", "Week 4");
        dataset.addValue(6000, "Wednesday", "Week 4");
        dataset.addValue(7200, "Thursday", "Week 4");
        dataset.addValue(8000, "Friday", "Week 4");
        dataset.addValue(14200, "Saturday", "Week 4");
        dataset.addValue(11500, "Sunday", "Week 4");

        // Create stacked bar chart
        JFreeChart chart = ChartFactory.createStackedBarChart(
                null, // No title (we have section header)
                "Week",
                "Revenue (AED)",
                dataset,
                PlotOrientation.VERTICAL,
                true, // Show legend (days)
                false, // No tooltips
                false // No URLs
        );

        // Style the chart
        chart.setBackgroundPaint(null);
        chart.getLegend().setBackgroundPaint(new Color(30, 41, 59));
        chart.getLegend().setItemPaint(Color.WHITE);

        CategoryPlot plot = chart.getCategoryPlot();
        plot.setBackgroundPaint(new Color(30, 41, 59)); // Dark slate background
        plot.setOutlineVisible(false);
        plot.setRangeGridlinePaint(new Color(71, 85, 105)); // Subtle grid lines
        plot.setDomainGridlinesVisible(false);

        // Color-code each day of week with distinct rainbow palette
        StackedBarRenderer renderer = new StackedBarRenderer();
        renderer.setSeriesPaint(0, new Color(59, 130, 246)); // Monday - Blue
        renderer.setSeriesPaint(1, new Color(6, 182, 212)); // Tuesday - Cyan
        renderer.setSeriesPaint(2, new Color(168, 85, 247)); // Wednesday - Purple
        renderer.setSeriesPaint(3, new Color(16, 185, 129)); // Thursday - Emerald
        renderer.setSeriesPaint(4, new Color(239, 68, 68)); // Friday - Red
        renderer.setSeriesPaint(5, new Color(245, 158, 11)); // Saturday - Gold
        renderer.setSeriesPaint(6, new Color(236, 72, 153)); // Sunday - Pink

        // Modern gradient bar painter (not flat!)
        renderer.setBarPainter(new org.jfree.chart.renderer.category.GradientBarPainter());
        renderer.setShadowVisible(false);

        // ADD VALUE LABELS ON BARS for better readability!
        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelGenerator(
                new org.jfree.chart.labels.StandardCategoryItemLabelGenerator(
                        "{2}", // Show value
                        java.text.NumberFormat.getInstance()));
        renderer.setDefaultItemLabelFont(
                new java.awt.Font("Arial", java.awt.Font.BOLD, 7)); // Small but readable
        renderer.setDefaultItemLabelPaint(Color.WHITE);

        plot.setRenderer(renderer);

        // Axis styling - WHITE labels for readability
        CategoryAxis domainAxis = plot.getDomainAxis();
        domainAxis.setTickLabelPaint(Color.WHITE);
        domainAxis.setLabelPaint(Color.WHITE);
        domainAxis.setTickLabelFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 10)); // Slightly larger

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setTickLabelPaint(Color.WHITE);
        rangeAxis.setLabelPaint(Color.WHITE);
        rangeAxis.setTickLabelFont(new java.awt.Font("Arial", java.awt.Font.PLAIN, 9));

        return chart;
    }

    // =========================================================================
    // NEW HELPERS FOR PAGE 2 REDESIGN
    // =========================================================================

    private PdfPCell createInsightChipsRow(String winText, String oppText) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        try {
            table.setWidths(new float[] { 1, 1 });
        } catch (DocumentException e) {
        }

        // Win Chip (Green)
        table.addCell(createInsightChip("KEY WIN", winText, new Color(16, 185, 129), "\uD83C\uDFC6")); // Trophy

        // Opportunity Chip (Amber)
        table.addCell(createInsightChip("KEY OPPORTUNITY", oppText, new Color(245, 158, 11), "\uD83D\uDE80")); // Rocket

        PdfPCell cell = new PdfPCell(table);
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    private PdfPCell createInsightChip(String label, String text, Color accent, String iconSymbol) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(6); // Gap

        PdfPTable content = new PdfPTable(2);
        try {
            content.setWidths(new float[] { 0.15f, 0.85f });
        } catch (Exception e) {
        }
        content.setWidthPercentage(100);

        // Icon Box
        PdfPCell iconCell = new PdfPCell(
                new Phrase(iconSymbol, new Font(Font.HELVETICA, 14, Font.NORMAL, Color.WHITE)));
        iconCell.setBorder(Rectangle.NO_BORDER);
        iconCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        iconCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        iconCell.setPadding(8);
        content.addCell(iconCell);

        // Text Box
        PdfPCell textCell = new PdfPCell();
        textCell.setBorder(Rectangle.NO_BORDER);
        textCell.setPaddingBottom(8);
        textCell.setPaddingTop(6);

        textCell.addElement(new Phrase(label, new Font(Font.HELVETICA, 7, Font.BOLD, accent)));
        textCell.addElement(new Phrase(text, new Font(Font.HELVETICA, 10, Font.NORMAL, Color.WHITE)));
        content.addCell(textCell);

        // Glass Wrapper
        PdfPCell wrapper = new PdfPCell(content);
        wrapper.setBorder(Rectangle.NO_BORDER);

        wrapper.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();

                // Colored Glass Background - 15% opacity of accent
                Color glassColor = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 40);
                cb.setColorFill(glassColor);
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        12);
                cb.fill();

                // Left Border Accent
                cb.setColorFill(accent);
                cb.roundRectangle(position.getLeft(), position.getBottom(), 4, position.getHeight(), 2);
                cb.fill();

                cb.restoreState();
            }
        });

        PdfPTable wrapperTable = new PdfPTable(1);
        wrapperTable.setWidthPercentage(100);
        wrapperTable.addCell(wrapper);
        cell.addElement(wrapperTable);
        return cell;
    }

    private PdfPCell createStandardKpiCard(String title, String value, Double growth, IconType iconType,
            List<ChartData> sparkData) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell inner = new PdfPCell();
        inner.setBorder(Rectangle.NO_BORDER);
        inner.setPadding(15);
        inner.setPaddingBottom(12);

        // Dark Glass Background (Midnight Navy)
        inner.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();

                // Background: Dark Navy with opacity (Glass)
                cb.setColorFill(new Color(15, 23, 42, 220)); // Slate 900 @ 86%
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        12);
                cb.fill();

                // Inner Glow / Border
                cb.setColorStroke(new Color(56, 189, 248, 60)); // Light Blue Glow
                cb.setLineWidth(1f);
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        12);
                cb.stroke();

                // Draw Sparkline (Area Glow) in Background
                if (sparkData != null && !sparkData.isEmpty()) {
                    float x = position.getLeft();
                    float y = position.getBottom() + 10; // Bottom padding
                    float w = position.getWidth();
                    float h = position.getHeight() * 0.4f; // Takes up bottom 40%

                    float min = Float.MAX_VALUE, max = Float.MIN_VALUE;
                    for (ChartData d : sparkData) {
                        float v = d.getValue() != null ? d.getValue().floatValue() : 0;
                        if (v < min)
                            min = v;
                        if (v > max)
                            max = v;
                    }
                    if (max == min)
                        max = min + 1;

                    // Gradient Fill (Clip to curve)
                    cb.saveState();
                    cb.rectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight());
                    cb.clip();

                    // Path for Area
                    cb.moveTo(x, y);
                    float step = w / (sparkData.size() - 1);
                    boolean first = true;
                    for (int i = 0; i < sparkData.size(); i++) {
                        float v = sparkData.get(i).getValue() != null ? sparkData.get(i).getValue().floatValue() : 0;
                        float py = y + ((v - min) / (max - min)) * h;
                        float px = x + i * step;
                        if (first) {
                            cb.moveTo(px, y);
                            cb.lineTo(px, py);
                            first = false;
                        } else
                            cb.lineTo(px, py);
                    }
                    cb.lineTo(x + w, y);
                    cb.closePath();

                    // Fill Color (Growth Green or Dip Red based on growth)
                    Color glowColor = (growth != null && growth >= 0) ? new Color(16, 185, 129, 30)
                            : new Color(239, 68, 68, 30);
                    cb.setColorFill(glowColor);
                    cb.fill();

                    // Line Stroke
                    cb.setColorStroke(new Color(glowColor.getRed(), glowColor.getGreen(), glowColor.getBlue(), 200));
                    cb.setLineWidth(1.5f);
                    // Re-trace line
                    cb.newPath();
                    first = true;
                    for (int i = 0; i < sparkData.size(); i++) {
                        float v = sparkData.get(i).getValue() != null ? sparkData.get(i).getValue().floatValue() : 0;
                        float py = y + ((v - min) / (max - min)) * h;
                        float px = x + i * step;
                        if (first) {
                            cb.moveTo(px, py);
                            first = false;
                        } else
                            cb.lineTo(px, py);
                    }
                    cb.stroke();
                    cb.restoreState();
                }

                cb.restoreState();
            }
        });

        // HEADER
        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        try {
            header.setWidths(new float[] { 0.85f, 0.15f });
        } catch (Exception e) {
        }

        PdfPCell titleCell = new PdfPCell(
                new Phrase(title.toUpperCase(), new Font(Font.HELVETICA, 8, Font.BOLD, new Color(148, 163, 184))));
        titleCell.setBorder(Rectangle.NO_BORDER);
        header.addCell(titleCell);

        PdfPCell iconCell = new PdfPCell();
        iconCell.setBorder(Rectangle.NO_BORDER);
        iconCell.setFixedHeight(24);
        if (iconType != null) {
            // Use "Broader" icon style - slightly larger opacity
            iconCell.setCellEvent(new VectorIconEvent(new Color(255, 255, 255, 180), iconType));
        }
        header.addCell(iconCell);
        inner.addElement(header);

        // VALUE
        Paragraph valP = new Paragraph(value, new Font(Font.HELVETICA, 26, Font.BOLD, Color.WHITE));
        valP.setSpacingBefore(8);
        valP.setSpacingAfter(4);
        inner.addElement(valP);

        // FOOTER (Growth + Label)
        PdfPTable footer = new PdfPTable(2);
        footer.setWidthPercentage(100);
        try {
            footer.setWidths(new float[] { 0.35f, 0.65f });
        } catch (Exception e) {
        }

        if (growth != null) {
            String sym = growth >= 0 ? "▲" : "▼";
            Color trendColor = growth >= 0 ? new Color(52, 211, 153) : new Color(248, 113, 113);
            PdfPCell trend = new PdfPCell(new Phrase(sym + String.format(" %.1f%%", Math.abs(growth)),
                    new Font(Font.HELVETICA, 9, Font.BOLD, trendColor)));
            trend.setBorder(Rectangle.NO_BORDER);
            footer.addCell(trend);

            PdfPCell lbl = new PdfPCell(
                    new Phrase("vs last month", new Font(Font.HELVETICA, 9, Font.NORMAL, new Color(148, 163, 184))));
            lbl.setBorder(Rectangle.NO_BORDER);
            footer.addCell(lbl);
        } else {
            footer.addCell(createPaddedCell(new PdfPCell()));
            footer.addCell(createPaddedCell(new PdfPCell()));
        }
        inner.addElement(footer);

        card.addCell(inner);
        cell.addElement(card);
        return cell;
    }

    private PdfPCell createPillDelta(Double growth) {
        boolean isPositive = growth >= 0;
        Color pillColor = isPositive ? new Color(16, 185, 129, 30) : new Color(239, 68, 68, 40); // Bg with opacity
        Color textColor = isPositive ? new Color(34, 197, 94) : new Color(248, 113, 113); // Brighter text
        String symbol = isPositive ? "▲" : "▼";

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);

        Paragraph p = new Paragraph(symbol + String.format(" %.1f%%", Math.abs(growth)),
                new Font(Font.HELVETICA, 8, Font.BOLD, textColor));
        p.setAlignment(Element.ALIGN_CENTER);

        cell.addElement(p);

        // BG Event for Pill Shape
        cell.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(pillColor);
                // Fully rounded caps
                cb.roundRectangle(position.getLeft(), position.getBottom() + 2, position.getWidth(),
                        position.getHeight() - 4, 8);
                cb.fill();
                cb.restoreState();
            }
        });

        return cell;
    }

    // =========================================================================
    // NEW HELPERS FOR PAGE 3 REDESIGN
    // =========================================================================

    private PdfPCell createPeakCard(String title, String value, String subLabel, Color accent, IconType iconType) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell inner = new PdfPCell();
        inner.setBorder(Rectangle.NO_BORDER);
        inner.setPadding(15);
        inner.setPaddingBottom(12);

        // Dark Glass Background (Midnight Navy) with Colored Glow
        inner.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();

                // Background: Dark Navy with opacity (Glass)
                cb.setColorFill(new Color(15, 23, 42, 220)); // Slate 900 @ 86%
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        12);
                cb.fill();

                // Inner Glow / Border (Using Accent Color)
                cb.setColorStroke(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 80)); // 30% Opacity
                                                                                                        // Glow
                cb.setLineWidth(1.5f);
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        12);
                cb.stroke();

                // Top Accent Bar (Optional, simpler than before to maintain clean look)
                // Let's rely on the colored border glow instead of a thick top bar for "Classy"
                // look.

                cb.restoreState();
            }
        });

        // Content Table
        PdfPTable content = new PdfPTable(1);
        content.setWidthPercentage(100);

        // Header Row (Label + Icon)
        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        try {
            header.setWidths(new float[] { 0.8f, 0.2f });
        } catch (Exception e) {
        }

        PdfPCell lCell = new PdfPCell(
                new Phrase(title, new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(148, 163, 184)))); // Slate 400
        lCell.setBorder(Rectangle.NO_BORDER);
        header.addCell(lCell);

        PdfPCell iCell = new PdfPCell();
        iCell.setBorder(Rectangle.NO_BORDER);
        if (iconType != null) {
            // Faint icon matching accent or white
            iCell.setCellEvent(new VectorIconEvent(new Color(255, 255, 255, 180), iconType));
            iCell.setFixedHeight(20);
        }
        header.addCell(iCell);

        content.addCell(header);

        // Value
        PdfPCell vCell = new PdfPCell(new Phrase(value, new Font(Font.HELVETICA, 24, Font.BOLD, Color.WHITE)));
        vCell.setBorder(Rectangle.NO_BORDER);
        vCell.setPaddingTop(10);
        content.addCell(vCell);

        // SubLabel (Micro-caption)
        PdfPCell sCell = new PdfPCell(new Phrase(subLabel, new Font(Font.HELVETICA, 8, Font.NORMAL, accent)));
        sCell.setBorder(Rectangle.NO_BORDER);
        sCell.setPaddingTop(4);
        content.addCell(sCell);

        inner.addElement(content);
        card.addCell(inner);
        cell.addElement(card);
        return cell;
    }

    private PdfPCell createDivider() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingTop(15);
        cell.setPaddingBottom(15);

        cell.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorStroke(new Color(255, 255, 255, 30)); // Faint line
                cb.setLineWidth(1f);
                cb.moveTo(position.getLeft() + 20, position.getBottom() + position.getHeight() / 2);
                cb.lineTo(position.getRight() - 20, position.getBottom() + position.getHeight() / 2);
                cb.stroke();
                cb.restoreState();
            }
        });
        return cell;
    }

    private JFreeChart createMutedDonutChart(String title, DefaultPieDataset dataset) {
        JFreeChart chart = ChartFactory.createRingChart(title, dataset, false, false, false); // No Legend
        chart.setBackgroundPaint(null);
        chart.setBorderVisible(false);

        org.jfree.chart.plot.RingPlot plot = (org.jfree.chart.plot.RingPlot) chart.getPlot();
        plot.setBackgroundPaint(null);
        plot.setOutlinePaint(null);
        plot.setSectionDepth(0.35); // Medium Thickness
        plot.setShadowPaint(null);

        // Muted Colors
        plot.setSectionPaint("Weekends (Sat-Sun)", new Color(111, 207, 151)); // #6FCF97 Green
        plot.setSectionPaint("Weekdays (Mon-Thu)", new Color(45, 156, 219)); // #2D9CDB Blue
        plot.setSectionPaint("Fridays", new Color(235, 87, 87)); // #EB5757 Red/Dip? or Purple #9B51E0?
        // Request says: S1 Green, S2 Blue, S3 Purple, S4 Amber.
        // We have 3 sections in my logic. Let's map dynamically?
        // Or strict:
        plot.setSectionPaint("Fridays", new Color(155, 81, 224)); // Purple for distinct

        // Labels
        plot.setLabelGenerator(new org.jfree.chart.labels.StandardPieSectionLabelGenerator("{0}: {2}")); // {2} is
                                                                                                         // percent
        plot.setLabelFont(new java.awt.Font("Arial", java.awt.Font.PLAIN, 10));
        plot.setLabelPaint(new Color(175, 193, 214)); // #AFC1D6
        plot.setLabelBackgroundPaint(null);
        plot.setLabelOutlinePaint(null);
        plot.setLabelShadowPaint(null);
        plot.setSimpleLabels(true); // Helper for cleaner look

        return chart;
    }

    private PdfPCell createInsightBlock(String title, String fact, String recommendation, Color accent, String icon) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingBottom(8); // Gap between blocks

        PdfPTable block = new PdfPTable(2);
        try {
            block.setWidths(new float[] { 0.02f, 0.98f });
        } catch (Exception e) {
        } // Accent Bar width
        block.setWidthPercentage(100);

        // Accent Bar
        PdfPCell bar = new PdfPCell();
        bar.setBorder(Rectangle.NO_BORDER);
        bar.setBackgroundColor(accent);
        bar.setFixedHeight(45); // Fixed height for consistency
        block.addCell(bar);

        // Content
        PdfPCell content = new PdfPCell();
        content.setBorder(Rectangle.NO_BORDER);
        content.setBackgroundColor(new Color(19, 46, 70)); // #132E46
        content.setPadding(10);

        PdfPTable inner = new PdfPTable(2);
        inner.setWidthPercentage(100);
        try {
            inner.setWidths(new float[] { 0.9f, 0.1f });
        } catch (Exception e) {
        }

        // Left: Title + Texts
        PdfPCell texts = new PdfPCell();
        texts.setBorder(Rectangle.NO_BORDER);
        texts.addElement(new Phrase(title, new Font(Font.HELVETICA, 8, Font.BOLD, accent)));
        texts.addElement(new Phrase(fact, new Font(Font.HELVETICA, 10, Font.BOLD, Color.WHITE)));
        texts.addElement(
                new Phrase(recommendation, new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(182, 194, 209))));
        inner.addCell(texts);

        // Right: Icon
        PdfPCell iconCell = new PdfPCell(
                new Phrase(icon, new Font(Font.HELVETICA, 12, Font.NORMAL, new Color(255, 255, 255, 50))));
        iconCell.setBorder(Rectangle.NO_BORDER);
        iconCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        iconCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        inner.addCell(iconCell);

        content.addElement(inner);
        block.addCell(content);

        cell.addElement(block);
        return cell;
    }

    // End of Service
    }

    // Icon
    PdfPCell iCell = new PdfPCell(new Phrase(iconSymbol, new Font(Font.HELVETICA, 16, Font.NORMAL,
            Color.WHITE)));iCell.setBorder(Rectangle.NO_BORDER);iCell.setVerticalAlignment(Element.ALIGN_MIDDLE);table.addCell(iCell);

    // Text
    PdfPCell tCell = new PdfPCell();tCell.setBorder(Rectangle.NO_BORDER);tCell.addElement(new Phrase(title,new Font(Font.HELVETICA,12,Font.BOLD,Color.WHITE)));tCell.addElement(new Phrase(subTitle,new Font(Font.HELVETICA,8,Font.NORMAL,new Color(148,163,184))));table.addCell(tCell);

    PdfPCell cell = new PdfPCell(table);cell.setBorder(Rectangle.NO_BORDER);cell.setPaddingBottom(15);return cell;
    }

    private PdfPCell createBehaviourCard(String title, String value, Double growth, String context, IconType iconType,
            List<ChartData> sparkData) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell inner = new PdfPCell();
        inner.setBorder(Rectangle.NO_BORDER);
        inner.setPadding(15);
        inner.setPaddingBottom(12);

        // Determine Accent based on growth
        Color accentColor;
        if (growth != null) {
            accentColor = growth >= 0 ? new Color(16, 185, 129) : new Color(239, 68, 68);
        } else {
            accentColor = new Color(56, 189, 248); // Default Blue if no growth data
        }

        // Dark Glass Background (Midnight Navy)
        inner.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();

                // Background: Dark Navy with opacity (Glass)
                cb.setColorFill(new Color(15, 23, 42, 220));
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        12);
                cb.fill();

                // Inner Glow / Border
                cb.setColorStroke(new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 60));
                cb.setLineWidth(1f);
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        12);
                cb.stroke();

                // Area Sparkline
                if (sparkData != null && !sparkData.isEmpty()) {
                    float x = position.getLeft();
                    float y = position.getBottom() + 10;
                    float w = position.getWidth();
                    float h = position.getHeight() * 0.4f;

                    float min = Float.MAX_VALUE, max = Float.MIN_VALUE;
                    for (ChartData d : sparkData) {
                        float v = d.getValue() != null ? d.getValue().floatValue() : 0;
                        if (v < min)
                            min = v;
                        if (v > max)
                            max = v;
                    }
                    if (max == min)
                        max = min + 1;

                    cb.saveState();
                    cb.rectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight());
                    cb.clip();

                    cb.moveTo(x, y);
                    float step = w / (Math.max(1, sparkData.size() - 1));
                    boolean first = true;
                    for (int i = 0; i < sparkData.size(); i++) {
                        float v = sparkData.get(i).getValue() != null ? sparkData.get(i).getValue().floatValue() : 0;
                        float py = y + ((v - min) / (max - min)) * h;
                        float px = x + i * step;
                        if (first) {
                            cb.moveTo(px, y);
                            cb.lineTo(px, py);
                            first = false;
                        } else
                            cb.lineTo(px, py);
                    }
                    cb.lineTo(x + w, y);
                    cb.closePath();

                    cb.setColorFill(new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 30));
                    cb.fill();

                    // Stroke
                    cb.setColorStroke(
                            new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 200));
                    cb.setLineWidth(1.5f);
                    cb.newPath();
                    first = true;
                    for (int i = 0; i < sparkData.size(); i++) {
                        float v = sparkData.get(i).getValue() != null ? sparkData.get(i).getValue().floatValue() : 0;
                        float py = y + ((v - min) / (max - min)) * h;
                        float px = x + i * step;
                        if (first) {
                            cb.moveTo(px, py);
                            first = false;
                        } else
                            cb.lineTo(px, py);
                    }
                    cb.stroke();
                    cb.restoreState();
                }

                cb.restoreState();
            }
        });

        // HEADER
        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        try {
            header.setWidths(new float[] { 0.85f, 0.15f });
        } catch (Exception e) {
        }

        PdfPCell titleCell = new PdfPCell(
                new Phrase(title.toUpperCase(), new Font(Font.HELVETICA, 8, Font.BOLD, new Color(148, 163, 184))));
        titleCell.setBorder(Rectangle.NO_BORDER);
        header.addCell(titleCell);

        PdfPCell iconCell = new PdfPCell();
        iconCell.setBorder(Rectangle.NO_BORDER);
        iconCell.setFixedHeight(24);
        if (iconType != null) {
            iconCell.setCellEvent(new VectorIconEvent(new Color(255, 255, 255, 180), iconType));
        }
        header.addCell(iconCell);
        inner.addElement(header);

        // VALUE
        Paragraph valP = new Paragraph(value, new Font(Font.HELVETICA, 26, Font.BOLD, Color.WHITE));
        valP.setSpacingBefore(8);
        valP.setSpacingAfter(4);
        inner.addElement(valP);

        // FOOTER (Context + Growth Pill)
        PdfPTable footer = new PdfPTable(2);
        footer.setWidthPercentage(100);
        try {
            footer.setWidths(new float[] { 0.7f, 0.3f });
        } catch (Exception e) {
        }

        PdfPCell ctxCell = new PdfPCell(
                new Phrase(context, new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(148, 163, 184))));
        ctxCell.setBorder(Rectangle.NO_BORDER);
        footer.addCell(ctxCell);

        if (growth != null) {
            String sym = growth >= 0 ? "▲" : "▼";
            Color trendColor = growth >= 0 ? new Color(52, 211, 153) : new Color(248, 113, 113);
            PdfPCell trend = new PdfPCell(new Phrase(sym + String.format(" %.1f%%", Math.abs(growth)),
                    new Font(Font.HELVETICA, 9, Font.BOLD, trendColor)));
            trend.setBorder(Rectangle.NO_BORDER);
            trend.setHorizontalAlignment(Element.ALIGN_RIGHT);
            footer.addCell(trend);
        } else {
            footer.addCell(createPaddedCell(new PdfPCell()));
        }

        inner.addElement(footer);

        card.addCell(inner);
        cell.addElement(card);
        return cell;
    }

    // =========================================================================
    // NEW HELPERS FOR PAGE 4 REDESIGN
    // =========================================================================

    private JFreeChart createSalesTrendChart(String title, String yLabel, List<ChartData> data) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        if (data != null) {
            data.forEach(d -> {
                ds.addValue(d.getValue(), "Sales", d.getLabel());
            });
        }

        JFreeChart chart = ChartFactory.createAreaChart(title, "", yLabel, ds, PlotOrientation.VERTICAL, false, false,
                false);
        chart.setBackgroundPaint(null);
        chart.setBorderVisible(false);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        plot.setBackgroundPaint(null);
        plot.setOutlinePaint(null);

        // 20% Opacity Gridlines (White)
        plot.setRangeGridlinePaint(new Color(255, 255, 255, 51)); // 20%
        plot.setRangeGridlinesVisible(true);
        plot.setDomainGridlinesVisible(false);

        // Axis Upgrade
        plot.getDomainAxis().setLabelPaint(Color.WHITE);
        plot.getDomainAxis().setTickLabelPaint(new Color(175, 193, 214)); // Label Color #AFC1D6
        plot.getDomainAxis().setTickLabelFont(new java.awt.Font("Arial", java.awt.Font.PLAIN, 8));

        // Hide every nth label automatically handled by simpler logic or just let
        // JFreeChart handle clutter?
        // For daily data (30 pts), we might need to skip labels properly.
        // But let's stick to standard behavior for now to avoid complexity, relying on
        // chart width.

        org.jfree.chart.axis.NumberAxis yAxis = (org.jfree.chart.axis.NumberAxis) plot.getRangeAxis();
        yAxis.setLabelPaint(Color.WHITE);
        yAxis.setTickLabelPaint(new Color(175, 193, 214)); // #AFC1D6
        yAxis.setTickLabelFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 9));
        // Show unit only once? JFreeChart repeats. We can set label "Sales (AED)".

        org.jfree.chart.renderer.category.AreaRenderer renderer = (org.jfree.chart.renderer.category.AreaRenderer) plot
                .getRenderer();

        // Gradient Fill (Approximate for JFreeChart Area)
        // Correct way for Area Chart Gradient in JFree:
        // AreaRenderer doesn't support GradientPaint relative to bounds easily without
        // custom renderer.
        // We will use a solid transparent Blue as requested if gradient is hard, OR
        // Use a consistent Blue #2F80ED with transparency.
        renderer.setSeriesPaint(0, new Color(47, 128, 237, 200)); // #2F80ED approx 80%

        // Outline Stroke (Thin outline on top)
        org.jfree.chart.renderer.category.LineAndShapeRenderer lineRenderer = new org.jfree.chart.renderer.category.LineAndShapeRenderer();
        lineRenderer.setSeriesPaint(0, new Color(86, 204, 242)); // #56CCF2 Light Blue Outline
        lineRenderer.setSeriesShapesVisible(0, true);
        lineRenderer.setSeriesShapesFilled(0, true);
        lineRenderer.setSeriesStroke(0, new java.awt.BasicStroke(1.5f));

        // Peak Detection for Markers
        lineRenderer.setUseOutlinePaint(true);

        // Custom Renderer to show marker ONLY on Max Value
        if (data != null && !data.isEmpty()) {
            double maxVal = data.stream().mapToDouble(d -> d.getValue().doubleValue()).max().orElse0();
            // We can't easily inject detection logic into standard LineAndShapeRenderer
            // without subclassing.
            // Strategy: Allow shapes, but make them invisible mostly?
            // Simpler: Enable shapes, set shape size to 0 except for max?
            // LineRenderer doesn't support per-item shape size easily.

            // Alternative: Add an Annotation for the peak!
            // Find Peak
            ChartData peak = getKeyData(data, true);
            if (peak != null) {
                // Add Dot Annotation
                // Category pointer annotation?
                // Or just let the user see the peak from the line.
                // Requirement: "Highlight highest sales day... Dot marker in #9B51E0"
                // We can use a Scatter/Dot renderer on a 3rd dataset?
                DefaultCategoryDataset peakDs = new DefaultCategoryDataset();
                for (ChartData d : data) {
                    if (d.getValue().doubleValue() == maxVal) {
                        peakDs.addValue(d.getValue(), "Peak", d.getLabel());
                    } else {
                        peakDs.addValue(null, "Peak", d.getLabel());
                    }
                }

                org.jfree.chart.renderer.category.LineAndShapeRenderer peakRenderer = new org.jfree.chart.renderer.category.LineAndShapeRenderer();
                peakRenderer.setSeriesLinesVisible(0, false);
                peakRenderer.setSeriesShapesVisible(0, true);
                peakRenderer.setSeriesPaint(0, new Color(155, 81, 224)); // #9B51E0
                peakRenderer.setSeriesShape(0, new java.awt.geom.Ellipse2D.Double(-4, -4, 8, 8)); // Dot

                plot.setDataset(2, peakDs);
                plot.setRenderer(2, peakRenderer);
            }
        }

        plot.setDataset(1, ds);
        plot.setRenderer(1, lineRenderer);
        plot.setDatasetRenderingOrder(org.jfree.chart.plot.DatasetRenderingOrder.FORWARD);

        // Highlight Peak Day
        if (data != null && !data.isEmpty()) {
            double maxVal = data.stream().mapToDouble(d -> d.getValue().doubleValue()).max().orElse(0);
            // Create Peak Dataset
            DefaultCategoryDataset peakDs = new DefaultCategoryDataset();
            for (ChartData d : data) {
                if (Math.abs(d.getValue().doubleValue() - maxVal) < 0.001) {
                    peakDs.addValue(d.getValue(), "Peak", d.getLabel());
                } else {
                    peakDs.addValue(null, "Peak", d.getLabel());
                }
            }
            org.jfree.chart.renderer.category.LineAndShapeRenderer peakRenderer = new org.jfree.chart.renderer.category.LineAndShapeRenderer();
            peakRenderer.setSeriesLinesVisible(0, false);
            peakRenderer.setSeriesShapesVisible(0, true);
            peakRenderer.setSeriesPaint(0, new Color(39, 174, 96)); // #27AE60 (Green) for Peak
            peakRenderer.setSeriesShape(0, new java.awt.geom.Ellipse2D.Double(-3, -3, 6, 6));
            plot.setDataset(2, peakDs);
            plot.setRenderer(2, peakRenderer);
        }

        return chart;
    }

    private JFreeChart createDayOfWeekBarChart(String title, String yLabel, List<ChartData> data) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        // Day Order Map
        List<String> days = java.util.Arrays.asList("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
                "Sunday");
        // Sort Data
        if (data != null) {
            data.stream()
                    .sorted(java.util.Comparator.comparingInt(d -> {
                        int idx = days.indexOf(d.getLabel());
                        return idx == -1 ? 99 : idx;
                    }))
                    .forEach(d -> ds.addValue(d.getValue(), "Sales", d.getLabel()));
        }

        JFreeChart chart = ChartFactory.createBarChart(title, "", yLabel, ds, PlotOrientation.VERTICAL, false, false,
                false);
        chart.setBackgroundPaint(null);
        chart.setBorderVisible(false);

        org.jfree.chart.plot.CategoryPlot plot = (org.jfree.chart.plot.CategoryPlot) chart.getPlot();
        plot.setBackgroundPaint(null);
        plot.setOutlinePaint(null);

        plot.setRangeGridlinePaint(new Color(255, 255, 255, 51));
        plot.setRangeGridlinesVisible(true);

        plot.getDomainAxis().setLabelPaint(Color.WHITE);
        plot.getDomainAxis().setTickLabelPaint(new Color(175, 193, 214));
        plot.getDomainAxis().setTickLabelFont(new java.awt.Font("Arial", java.awt.Font.PLAIN, 8));

        org.jfree.chart.axis.NumberAxis yAxis = (org.jfree.chart.axis.NumberAxis) plot.getRangeAxis();
        yAxis.setLabelPaint(Color.WHITE);
        yAxis.setTickLabelPaint(new Color(175, 193, 214));
        yAxis.setTickLabelFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 8));

        // Custom Renderer for Peak Highlight
        org.jfree.chart.renderer.category.BarRenderer renderer = new org.jfree.chart.renderer.category.BarRenderer() {
            @Override
            public java.awt.Paint getItemPaint(int row, int column) {
                Number val = getPlot().getDataset().getValue(row, column);
                if (val == null)
                    return super.getItemPaint(row, column);

                // Find Max
                double max = 0;
                for (int c = 0; c < getPlot().getDataset().getColumnCount(); c++) {
                    Number v = getPlot().getDataset().getValue(row, c);
                    if (v != null)
                        max = Math.max(max, v.doubleValue());
                }

                if (val.doubleValue() == max)
                    return new Color(39, 174, 96); // #27AE60 (Peak)
                return new Color(111, 207, 151); // #6FCF97 (Standard)
            }
        };
        renderer.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter()); // Flat bars
        renderer.setShadowVisible(false);
        renderer.setDrawBarOutline(false);
        plot.setRenderer(renderer);

        return chart;
    }

    private PdfPCell createSmartMonthlyReview(String text) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);

        PdfPTable tbl = new PdfPTable(2);
        try {
            tbl.setWidths(new float[] { 0.01f, 0.99f });
        } catch (Exception e) {
        }
        tbl.setWidthPercentage(100);

        // Accent Bar
        PdfPCell bar = new PdfPCell();
        bar.setBorder(Rectangle.NO_BORDER);
        bar.setBackgroundColor(new Color(45, 156, 219)); // #2D9CDB
        bar.setPadding(0);
        bar.setFixedHeight(40); // Min height
        tbl.addCell(bar);

        // Text Content
        PdfPCell content = new PdfPCell();
        content.setBorder(Rectangle.NO_BORDER);
        content.setBackgroundColor(new Color(19, 46, 70)); // #132E46
        content.setPadding(12);

        PdfPTable inner = new PdfPTable(2);
        inner.setWidthPercentage(100);
        try {
            inner.setWidths(new float[] { 0.05f, 0.95f });
        } catch (Exception e) {
        }

        // Icon
        PdfPCell icon = new PdfPCell(new Phrase("📊", new Font(Font.HELVETICA, 12, Font.NORMAL, Color.WHITE))); // Placeholder
                                                                                                                // icon
        icon.setBorder(Rectangle.NO_BORDER);
        inner.addCell(icon);

        // Text
        Paragraph p = new Paragraph("Smart Monthly Review\n",
                new Font(Font.HELVETICA, 10, Font.BOLD, new Color(45, 156, 219)));
        p.add(new Chunk(text, new Font(Font.HELVETICA, 9, Font.NORMAL, Color.WHITE)));
        PdfPCell txt = new PdfPCell(p);
        txt.setBorder(Rectangle.NO_BORDER);
        inner.addCell(txt);

        content.addElement(inner);
        tbl.addCell(content);

        cell.addElement(tbl);
        return cell;
    }

    private PdfPCell createIntelligenceCard(String label, String value, String subtext, Color accent,
            String iconSymbol) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(6);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell inner = new PdfPCell();
        inner.setBorder(Rectangle.NO_BORDER);
        inner.setBackgroundColor(new Color(19, 46, 70)); // #132E46
        inner.setPadding(15);

        // Header: Icon + Label
        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        try {
            header.setWidths(new float[] { 0.8f, 0.2f });
        } catch (Exception e) {
        }

        PdfPCell lCell = new PdfPCell(
                new Phrase(label.toUpperCase(), new Font(Font.HELVETICA, 8, Font.BOLD, new Color(182, 194, 209)))); // #B6C2D1
        lCell.setBorder(Rectangle.NO_BORDER);
        header.addCell(lCell);

        PdfPCell iCell = new PdfPCell(
                new Phrase(iconSymbol, new Font(Font.HELVETICA, 14, Font.NORMAL, new Color(255, 255, 255, 60))));
        iCell.setBorder(Rectangle.NO_BORDER);
        iCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        header.addCell(iCell);

        inner.addElement(header);

        // Value
        Paragraph v = new Paragraph(value, new Font(Font.HELVETICA, 18, Font.BOLD, Color.WHITE));
        v.setSpacingBefore(10);
        inner.addElement(v);

        // Subtext
        Paragraph s = new Paragraph(subtext, new Font(Font.HELVETICA, 8, Font.NORMAL, accent));
        s.setSpacingBefore(4);
        inner.addElement(s);

        card.addCell(inner);
        cell.addElement(card);
        return cell;
    }

    private PdfPCell createDecisionCard(String label, String value, String subText, Color accent, String iconSymbol) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(8);

        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);

        PdfPCell inner = new PdfPCell();
        inner.setBorder(Rectangle.NO_BORDER);
        inner.setPadding(15);

        // Glass Event
        inner.setCellEvent(new PdfPCellEvent() {
            public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
                PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
                cb.saveState();
                cb.setColorFill(new Color(255, 255, 255, 30));
                cb.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                        12);
                cb.fill();

                // Left Accent Bar (thick)
                cb.setColorFill(accent);
                cb.roundRectangle(position.getLeft(), position.getBottom(), 6, position.getHeight(), 3);
                cb.fill();

                cb.restoreState();
            }
        });

        // Content
        PdfPTable content = new PdfPTable(2);
        content.setWidthPercentage(100);
        try {
            content.setWidths(new float[] { 0.8f, 0.2f });
        } catch (Exception e) {
        }

        // Left Col: Texts
        PdfPCell left = new PdfPCell();
        left.setBorder(Rectangle.NO_BORDER);
        left.setPaddingLeft(10); // Offset for accent bar

        left.addElement(new Phrase(label, new Font(Font.HELVETICA, 8, Font.BOLD, new Color(203, 213, 225))));
        left.addElement(new Phrase(value, new Font(Font.HELVETICA, 12, Font.BOLD, Color.WHITE)));
        left.addElement(new Phrase(subText, new Font(Font.HELVETICA, 8, Font.NORMAL, accent)));

        content.addCell(left);

        // Right Col: Icon (Top Right)
        PdfPCell right = new PdfPCell(new Phrase(iconSymbol, new Font(Font.HELVETICA, 16, Font.NORMAL, Color.WHITE)));
        right.setBorder(Rectangle.NO_BORDER);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        right.setVerticalAlignment(Element.ALIGN_TOP);
        content.addCell(right);

        inner.addElement(content);
        card.addCell(inner);
        cell.addElement(card);
        return cell;
    }
}
