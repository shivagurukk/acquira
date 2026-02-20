package com.acquira.pdf.service;

import com.acquira.common.dto.MerchantInsightsDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Margin;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *  PDF GENERATION ENGINE v5.0
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 *  v5: route-based font serving for glyph subsetting (small PDFs)
 *  + persistent context with pre-registered routes (from v4)
 *  + pre-compiled regex, pre-computed replacements
 *
 *  v4 had base64 fonts → Chromium fully embeds = 11MB PDFs / 7s pdf()
 *  v5 uses route fonts → Chromium subsets glyphs = ~2MB PDFs / ~1s pdf()
 */
@Service
@Slf4j
public class PlaywrightPdfService {

    private final SpringTemplateEngine templateEngine;
    private final ObjectMapper objectMapper;

    @Value("${pdf.pool.size:2}")
    private int configuredPoolSize;

    @Value("${pdf.chart.wait.ms:300}")
    private int chartWaitMs;

    @Value("${pdf.batch.data.threads:8}")
    private int dataFetchThreads;

    private int POOL_SIZE;
    private BlockingQueue<BrowserSlot> browserPool;

    // Pre-cached resources
    private String cachedCss;
    private String cachedChartJs;
    private String cachedChartJsDatalabels;
    private final Map<String, byte[]> cachedFonts = new ConcurrentHashMap<>();

    // Pre-cached logo data URIs (base64 embedded — no filesystem path needed)
    private String cachedLogoWhiteDataUri;
    private String cachedLogoBlackDataUri;
    private String cachedLogoColorDataUri;

    // Pre-compiled regex patterns
    private Pattern patternCssLink;
    private Pattern patternChartJsLocal;
    private Pattern patternChartJsCdn;
    private Pattern patternDatalabelsLocal;
    private Pattern patternDatalabelsCdn;
    private Pattern patternGoogleFonts;

    // Pre-computed replacement strings
    private String replacementCss;
    private String replacementChartJs;
    private String replacementDatalabels;

    private static final Page.PdfOptions PDF_OPTIONS = new Page.PdfOptions()
            .setFormat("A4").setLandscape(false).setPrintBackground(true)
            .setPreferCSSPageSize(true)
            .setMargin(new Margin().setTop("0mm").setRight("0mm").setBottom("0mm").setLeft("0mm"));

    /**
     * PDF print overrides v5.3 — vector-only output.
     * Replaces ALL gradients/shadows/filters with matching flat colors.
     * Proven: 3.2MB PDFs, 1.8s pdf() (vs 11MB/14s without overrides).
     */
    private static final String PDF_PRINT_OVERRIDES = """

        /* === GLOBAL: kill ALL bitmap-producing properties === */
        *, *::before, *::after {
            box-shadow: none !important;
            text-shadow: none !important;
            backdrop-filter: none !important;
            -webkit-backdrop-filter: none !important;
            filter: none !important;
        }

        /* === ALL pseudo-overlays: KILL === */
        .page::before, .page::after,
        #page-cover::before, #page-cover::after,
        #page-toc::before, #page-toc::after,
        #page-closing::before, #page-closing::after,
        .report-header::before, .report-header::after,
        .report-footer::before, .report-footer::after,
        .card::before,
        .exec-kpi::before,
        .exec-kpi.blue::before, .exec-kpi.green::before,
        .exec-kpi.amber::before, .exec-kpi.purple::before, .exec-kpi.red::before,
        .insight-card::before {
            content: none !important; display: none !important;
        }

        /* === PAGES === */
        .page { background: #FFFFFF !important; }
        #page-cover { background: #0F2042 !important; }
        #page-toc { background: #FFFFFF !important; }
        #page-closing { background: #0F2042 !important; }

        /* === HEADER / FOOTER: flat dark blue with gold accent === */
        .report-header { background: #0F2042 !important; border-bottom: 2px solid #C9A962 !important; }
        .report-footer { background: #0B1628 !important; border-top: 2px solid #C9A962 !important; }

        /* === CARDS: flat with colored top border === */
        .card { background: #FFFFFF !important; border: 1px solid #E2E8F0 !important; }
        .card-accent-blue   { background: #F0F4FF !important; border-top: 3px solid #2563EB !important; }
        .card-accent-green  { background: #F0FDF9 !important; border-top: 3px solid #0D9488 !important; }
        .card-accent-amber  { background: #FFFBF0 !important; border-top: 3px solid #C9A962 !important; }
        .card-accent-purple { background: #F5F3FF !important; border-top: 3px solid #6D28D9 !important; }
        .card-accent-red    { background: #FFF5F5 !important; border-top: 3px solid #DC2626 !important; }

        /* === SECTION TITLE accent bar — NOT killed, re-enable === */
        .section-title::before { content: '' !important; display: block !important; position: absolute !important; left: 0 !important; top: 0 !important; bottom: 0 !important; width: 3.5px !important; background: #C9A962 !important; border-radius: 2px !important; }

        /* === EXEC KPI === */
        .exec-kpi { background: #FAFBFC !important; }

        /* === DATA TABLES === */
        .data-table-wrapper { background: #FFFFFF !important; }
        .data-table thead { background: #F8FAFC !important; }

        /* === ICON CIRCLES === */
        .icon-circle-blue   { background: #DBEAFE !important; }
        .icon-circle-green  { background: #D1FAE5 !important; }
        .icon-circle-amber  { background: #FEF3C7 !important; }
        .icon-circle-purple { background: #EDE9FE !important; }
        .icon-circle-red    { background: #FEE2E2 !important; }

        /* === BARS === */
        .h-bar-track { background: #F1F5F9 !important; }
        .progress-bar-track { background: #F1F5F9 !important; }

        /* === INSIGHT / METRIC CARDS === */
        .insight-card { background: #F8FAFC !important; }
        .metric-card { background: #FAFBFC !important; }
        .metric-detail-card { background: #FAFBFC !important; }

        /* === MISC === */
        .action-signal-header { background: #EFF6FF !important; }
        .luxe-hr { background: #C9A962 !important; }

        """;

    private static final List<String> BROWSER_ARGS = List.of(
            "--disable-gpu", "--disable-dev-shm-usage", "--no-sandbox",
            "--disable-extensions", "--disable-background-networking",
            "--disable-default-apps", "--disable-sync", "--disable-translate",
            "--metrics-recording-only", "--no-first-run",
            "--safebrowsing-disable-auto-update", "--disable-component-update",
            "--disable-background-timer-throttling",
            "--disable-backgrounding-occluded-windows",
            "--disable-renderer-backgrounding",
            "--disable-ipc-flooding-protection",
            "--js-flags=--max-old-space-size=256"
    );

    private final ConcurrentHashMap<String, BatchJobStatus> activeJobs = new ConcurrentHashMap<>();

    /**
     * Browser slot with persistent context + font route pre-registered.
     * Route is set ONCE on the context — all pages inherit it.
     * Chromium loads fonts via route → subsets glyphs → small PDFs.
     */
    private class BrowserSlot {
        Playwright playwright;
        Browser browser;
        BrowserContext persistentCtx;
        final int id;
        long totalRendered;
        long totalRenderTimeMs;

        BrowserSlot(int id) {
            this.id = id;
            this.playwright = Playwright.create();
            this.browser = this.playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true).setArgs(BROWSER_ARGS));
            initContext();
        }

        void initContext() {
            this.persistentCtx = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(794, 1123)
                    .setDeviceScaleFactor(1.0)
                    .setJavaScriptEnabled(true));

            // Register font route ONCE on context — all pages inherit it.
            // This serves fonts from memory cache. Chromium loads them as
            // real font resources → subsets only the glyphs used → small PDFs.
            this.persistentCtx.route("**/assets/fonts/**", route -> {
                String url = route.request().url();
                String fontFile = url.substring(url.lastIndexOf('/') + 1);
                byte[] fontBytes = cachedFonts.get(fontFile);
                if (fontBytes != null) {
                    route.fulfill(new Route.FulfillOptions()
                            .setContentType("font/ttf")
                            .setBodyBytes(fontBytes));
                } else {
                    route.abort();
                }
            });

            // Block all other external requests (zero network)
            this.persistentCtx.route(url -> {
                String s = url.toString();
                return !s.contains("/assets/fonts/") && !s.startsWith("data:");
            }, route -> {
                // Allow data: URIs and about:blank, block everything else
                String url = route.request().url();
                if (url.startsWith("data:") || url.startsWith("about:")) {
                    route.resume();
                } else {
                    route.abort();
                }
            });
        }

        boolean isHealthy() { return browser != null && browser.isConnected(); }

        void reset() {
            try { if (persistentCtx != null) persistentCtx.close(); } catch (Exception ignored) {}
            try { browser.close(); } catch (Exception ignored) {}
            try { playwright.close(); } catch (Exception ignored) {}
            this.playwright = Playwright.create();
            this.browser = this.playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true).setArgs(BROWSER_ARGS));
            initContext();
        }

        void destroy() {
            try { if (persistentCtx != null) persistentCtx.close(); } catch (Exception ignored) {}
            try { browser.close(); } catch (Exception ignored) {}
            try { playwright.close(); } catch (Exception ignored) {}
        }

        double avgRenderMs() {
            return totalRendered == 0 ? 0 : (double) totalRenderTimeMs / totalRendered;
        }
    }

    public static class BatchJobStatus {
        public final String jobId;
        public final Instant startTime;
        public volatile Instant endTime;
        public final int totalMerchants;
        public final AtomicInteger completed = new AtomicInteger(0);
        public final AtomicInteger succeeded = new AtomicInteger(0);
        public final AtomicInteger failed = new AtomicInteger(0);
        public final AtomicLong totalDataFetchMs = new AtomicLong(0);
        public final AtomicLong totalRenderMs = new AtomicLong(0);
        public final AtomicLong totalWriteMs = new AtomicLong(0);
        public final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        public volatile String phase = "INITIALIZING";
        public volatile boolean cancelled = false;

        public BatchJobStatus(String jobId, int totalMerchants) {
            this.jobId = jobId; this.startTime = Instant.now(); this.totalMerchants = totalMerchants;
        }
        public double progressPercent() { return totalMerchants == 0 ? 100 : (completed.get() * 100.0 / totalMerchants); }
        public long elapsedMs() { return Duration.between(startTime, endTime != null ? endTime : Instant.now()).toMillis(); }
        public double estimatedRemainingMs() { int d = completed.get(); return d == 0 ? -1 : ((double) elapsedMs() / d) * (totalMerchants - d); }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", jobId); m.put("phase", phase); m.put("totalMerchants", totalMerchants);
            m.put("completed", completed.get()); m.put("succeeded", succeeded.get()); m.put("failed", failed.get());
            m.put("progressPercent", Math.round(progressPercent() * 10.0) / 10.0);
            m.put("elapsedSeconds", elapsedMs() / 1000.0);
            m.put("estimatedRemainingSeconds", estimatedRemainingMs() > 0 ? Math.round(estimatedRemainingMs() / 100.0) / 10.0 : "calculating...");
            m.put("avgRenderMs", completed.get() > 0 ? totalRenderMs.get() / completed.get() : 0);
            m.put("errors", errors.size() > 20 ? errors.subList(0, 20) : errors);
            m.put("errorCount", errors.size()); m.put("cancelled", cancelled);
            if (endTime != null) m.put("totalSeconds", elapsedMs() / 1000.0);
            return m;
        }
    }

    public PlaywrightPdfService(@Qualifier("pdfTemplateEngine") SpringTemplateEngine templateEngine,
                                ObjectMapper objectMapper) {
        this.templateEngine = templateEngine;
        this.objectMapper = objectMapper;
    }

    private boolean engineReady = false;
    public boolean isEngineReady() { return engineReady; }

    @PostConstruct
    public void init() {
        POOL_SIZE = Math.min(Math.max(configuredPoolSize, 1), 4);
        log.info("PDF Engine v5 starting — pool size: {}, chart wait: {}ms", POOL_SIZE, chartWaitMs);
        try {
            initInternal();
            engineReady = true;
        } catch (Exception e) {
            log.warn("⚠ PDF Engine failed to initialize: {}", e.getMessage());
            engineReady = false;
        }
    }

    private void initInternal() {
        // 1. CSS + AGGRESSIVE PDF overrides
        //    Chromium rasterizes every CSS gradient as a full-page bitmap in PDF.
        //    58 gradients × 15 pages = 11MB PDFs and 8-22s in page.pdf().
        //    Fix: replace ALL gradients with their dominant flat color.
        //    Same color palette, visually near-identical, but vector → ~1MB, ~1s.
        cachedCss = loadClasspathResource("static/assets/report-theme.css");
        if (cachedCss != null) {
            cachedCss += PDF_PRINT_OVERRIDES;
        }

        // 2. Chart.js
        cachedChartJs = loadClasspathResource("static/assets/js/chart.js");
        cachedChartJsDatalabels = loadClasspathResource("static/assets/js/chartjs-plugin-datalabels.min.js");

        // 3. Font bytes → cached in memory, served via context route
        preloadFontCache();

        // 3b. Logo images → cached as base64 data URIs (immune to route blocking)
        preloadLogoCache();

        // 4. Pre-compile regex patterns
        patternCssLink = Pattern.compile("<link[^>]*href=\"/assets/report-theme\\.css\"[^>]*/?>", Pattern.DOTALL);
        patternChartJsLocal = Pattern.compile("<script[^>]*src=\"/assets/js/chart\\.js\"[^>]*>\\s*</script>", Pattern.DOTALL);
        patternChartJsCdn = Pattern.compile("<script[^>]*src=\"https://cdn\\.jsdelivr\\.net/npm/chart\\.js\"[^>]*>\\s*</script>", Pattern.DOTALL);
        patternDatalabelsLocal = Pattern.compile("<script[^>]*src=\"/assets/js/chartjs-plugin-datalabels[^\"]*\"[^>]*>\\s*</script>", Pattern.DOTALL);
        patternDatalabelsCdn = Pattern.compile("<script[^>]*src=\"https://cdn\\.jsdelivr\\.net/npm/chartjs-plugin-datalabels[^\"]*\"[^>]*>\\s*</script>", Pattern.DOTALL);
        patternGoogleFonts = Pattern.compile("<link[^>]*fonts\\.googleapis\\.com[^>]*>", Pattern.DOTALL);

        // Pre-compute replacement strings (quoteReplacement on 200KB done once, not 20K times)
        if (cachedCss != null) replacementCss = java.util.regex.Matcher.quoteReplacement("<style>\n" + cachedCss + "\n</style>");
        if (cachedChartJs != null) replacementChartJs = java.util.regex.Matcher.quoteReplacement("<script>\n" + cachedChartJs + "\n</script>");
        if (cachedChartJsDatalabels != null) replacementDatalabels = java.util.regex.Matcher.quoteReplacement("<script>\n" + cachedChartJsDatalabels + "\n</script>");

        // 5. Browser pool — each slot has persistent context with font routes
        browserPool = new ArrayBlockingQueue<>(POOL_SIZE);
        int ok = 0;
        for (int i = 0; i < POOL_SIZE; i++) {
            try { browserPool.offer(new BrowserSlot(i)); ok++; }
            catch (Exception e) { log.error("Slot {} init failed: {}", i, e.getMessage()); }
        }
        POOL_SIZE = ok;
        if (POOL_SIZE == 0) throw new RuntimeException("No browser slots initialized");
        log.info("✓ PDF Engine v5 ready — {} slots, fonts served via context route (glyph subsetting enabled)", POOL_SIZE);
    }

    private void preloadLogoCache() {
        cachedLogoWhiteDataUri = loadClasspathImageAsDataUri("static/images/AFS_Logo_White.png", "image/png");
        cachedLogoBlackDataUri = loadClasspathImageAsDataUri("static/images/AFS_Logo_Black.png", "image/png");
        cachedLogoColorDataUri = loadClasspathImageAsDataUri("static/images/AFS_Logo_Color.png", "image/png");
        log.info("✓ Logos cached: white={} black={} color={}",
                cachedLogoWhiteDataUri != null ? "ok" : "missing",
                cachedLogoBlackDataUri != null ? "ok" : "missing",
                cachedLogoColorDataUri != null ? "ok" : "missing");
    }

    private String loadClasspathImageAsDataUri(String path, String mimeType) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                log.warn("Logo not found on classpath: {}", path);
                return null;
            }
            byte[] bytes = resource.getInputStream().readAllBytes();
            String b64 = Base64.getEncoder().encodeToString(bytes);
            return "data:" + mimeType + ";base64," + b64;
        } catch (Exception e) {
            log.warn("Could not cache logo {}: {}", path, e.getMessage());
            return null;
        }
    }

    private void preloadFontCache() {
        String[] fontFiles = {
            "Inter-Regular.ttf", "Inter-Medium.ttf", "Inter-SemiBold.ttf", "Inter-Bold.ttf",
            "PlayfairDisplay-Regular.ttf", "PlayfairDisplay-Bold.ttf"
        };
        int loaded = 0; long totalBytes = 0;
        for (String fontFile : fontFiles) {
            try {
                ClassPathResource res = new ClassPathResource("static/assets/fonts/" + fontFile);
                if (res.exists()) {
                    byte[] bytes = res.getInputStream().readAllBytes();
                    cachedFonts.put(fontFile, bytes);
                    totalBytes += bytes.length;
                    loaded++;
                }
            } catch (Exception e) { log.warn("Could not cache font {}: {}", fontFile, e.getMessage()); }
        }
        log.info("✓ Cached {} fonts ({} KB) for route-based serving", loaded, totalBytes / 1024);
    }

    @PreDestroy
    public void cleanup() {
        activeJobs.values().forEach(j -> j.cancelled = true);
        if (browserPool != null) browserPool.forEach(BrowserSlot::destroy);
        log.info("PDF Engine shut down");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  SINGLE REPORT
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static final int MAX_RETRIES = 3;

    public byte[] generatePdf(MerchantInsightsDTO data, String merchantName, String monthYear) {
        long tStart = System.nanoTime();
        String generatedDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"));
        String htmlContent = renderHtml(data, merchantName, monthYear, generatedDate);
        long tHtml = System.nanoTime();
        htmlContent = inlineResources(htmlContent);
        long tInline = System.nanoTime();
        log.info("Prep for {}: thymeleaf={}ms inline={}ms htmlSize={}KB",
                merchantName, (tHtml - tStart) / 1_000_000, (tInline - tHtml) / 1_000_000, htmlContent.length() / 1024);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            BrowserSlot slot = null;
            try {
                slot = browserPool.poll(30, TimeUnit.SECONDS);
                if (slot == null) throw new RuntimeException("No browser slot available within 30s");
                if (!slot.isHealthy()) { log.warn("Slot {} unhealthy, resetting", slot.id); slot.reset(); }
                return renderPdfInSlot(slot, htmlContent);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted", e);
            } catch (PlaywrightException e) {
                log.warn("Render error for {} (attempt {}/{}): {}", merchantName, attempt, MAX_RETRIES, truncate(e.getMessage(), 120));
                if (slot != null) { try { slot.reset(); } catch (Exception ignored) {} }
                if (attempt == MAX_RETRIES) throw new RuntimeException("PDF failed after " + MAX_RETRIES + " retries", e);
            } catch (Exception e) {
                throw new RuntimeException("PDF failed for " + merchantName, e);
            } finally {
                if (slot != null) browserPool.offer(slot);
            }
        }
        throw new RuntimeException("PDF failed for " + merchantName);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  CORE RENDER
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * v5 render:
     * - Persistent context with font routes pre-registered (font subsetting!)
     * - CSS + JS inlined, fonts loaded via route → Chromium subsets glyphs
     * - No per-render route setup, no navigate, no context creation
     * - setContent directly → fonts.ready → chart poll → pdf()
     */
    private byte[] renderPdfInSlot(BrowserSlot slot, String htmlContent) {
        Page page = null;
        try {
            long t0 = System.nanoTime();
            page = slot.persistentCtx.newPage();
            long tPage = System.nanoTime();

            page.setContent(htmlContent, new Page.SetContentOptions()
                    .setWaitUntil(WaitUntilState.LOAD));
            long tContent = System.nanoTime();

            // Wait for fonts loaded via route
            page.evaluate("() => document.fonts.ready");
            long tFonts = System.nanoTime();

            // Wait for charts (poll, max chartWaitMs)
            try {
                page.waitForFunction(
                    "() => { const c = document.querySelectorAll('canvas');"
                    + " if (!c.length) return true;"
                    + " return Array.from(c).every(x => x.width > 0 && x.height > 0); }",
                    new Page.WaitForFunctionOptions().setTimeout(chartWaitMs)
                );
            } catch (Exception e) {
                log.debug("Chart wait timeout ({}ms)", chartWaitMs);
            }
            long tChart = System.nanoTime();

            byte[] pdf = page.pdf(PDF_OPTIONS);
            long tPdf = System.nanoTime();

            if (slot.totalRendered < 5 || slot.totalRendered % 100 == 0) {
                log.info("Slot {} render #{}: newPage={}ms setContent={}ms fonts={}ms chartWait={}ms pdf={}ms TOTAL={}ms html={}KB pdf={}KB",
                    slot.id, slot.totalRendered + 1,
                    (tPage - t0) / 1_000_000, (tContent - tPage) / 1_000_000,
                    (tFonts - tContent) / 1_000_000, (tChart - tFonts) / 1_000_000,
                    (tPdf - tChart) / 1_000_000, (tPdf - t0) / 1_000_000,
                    htmlContent.length() / 1024, pdf.length / 1024);
            }

            return pdf;
        } finally {
            if (page != null) { try { page.close(); } catch (Exception ignored) {} }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  HTML RENDERING
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String renderHtml(MerchantInsightsDTO data, String merchantName, String monthYear, String generatedDate) {
        try {
            String jsonData = objectMapper.writeValueAsString(data);
            Context context = new Context();
            context.setVariable("jsonData", jsonData);
            context.setVariable("merchantName", merchantName);
            context.setVariable("reportPeriod", monthYear);
            context.setVariable("dto", data);
            context.setVariable("generatedDate", generatedDate);
            // CRITICAL: Do NOT pass base64 data URIs through Thymeleaf/SpEL!
            // Spring 6.1.1 has a HARDCODED 10,000-char SpEL expression limit.
            // Pass short placeholders instead — even if old cached templates with
            // th:src="${afsLogoWhite}" are loaded, SpEL evaluates to the short
            // placeholder string (safe). New templates use src="__AFS_LOGO_WHITE__"
            // directly and skip SpEL entirely. Either way, the post-processing
            // below replaces the placeholder with the real base64 data URI.
            context.setVariable("afsLogoWhite", "__AFS_LOGO_WHITE__");
            context.setVariable("afsLogoBlack", "__AFS_LOGO_BLACK__");
            context.setVariable("afsLogoColor", "__AFS_LOGO_COLOR__");
            String html = templateEngine.process("basic-report", context);

            // Post-process: inject logo data URIs (bypasses SpEL entirely)
            if (cachedLogoWhiteDataUri != null) {
                html = html.replace("__AFS_LOGO_WHITE__", cachedLogoWhiteDataUri);
            }
            if (cachedLogoBlackDataUri != null) {
                html = html.replace("__AFS_LOGO_BLACK__", cachedLogoBlackDataUri);
            }
            if (cachedLogoColorDataUri != null) {
                html = html.replace("__AFS_LOGO_COLOR__", cachedLogoColorDataUri);
            }
            return html;
        } catch (Exception e) {
            throw new RuntimeException("HTML rendering failed for " + merchantName, e);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  RESOURCE INLINING — CSS + JS inlined, fonts kept as URL for route
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private String inlineResources(String html) {
        // 0. Force 1x DPR
        html = html.replace("<head>",
                "<head>\n<script>Object.defineProperty(window,'devicePixelRatio',{value:1});</script>");

        // 1. Inline CSS (font-face @url paths stay as-is → served by context route)
        if (replacementCss != null) {
            html = patternCssLink.matcher(html).replaceFirst(replacementCss);
        }

        // 2. Inline Chart.js
        if (replacementChartJs != null) {
            html = patternChartJsLocal.matcher(html).replaceFirst(replacementChartJs);
            html = patternChartJsCdn.matcher(html).replaceFirst(replacementChartJs);
        }

        // 3. Inline Datalabels
        if (replacementDatalabels != null) {
            html = patternDatalabelsLocal.matcher(html).replaceFirst(replacementDatalabels);
            html = patternDatalabelsCdn.matcher(html).replaceFirst(replacementDatalabels);
        }

        // 4. Strip Google Fonts CDN
        html = html.replace("<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">", "");
        html = html.replace("<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>", "");
        html = patternGoogleFonts.matcher(html).replaceAll("");

        // 5. @font-face with url('/assets/fonts/X.ttf') stays in template <style>
        //    → Chromium loads them via the persistent context route
        //    → Chromium subsets only used glyphs → small PDFs

        return html;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  BATCH GENERATION
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public BatchJobStatus generateBatch(
            List<long[]> merchantIdList, List<String> merchantNames,
            java.util.function.BiFunction<Long, long[], MerchantInsightsDTO> dataFetcher,
            String targetFolder, String monthYear, String targetYearMonth) {

        String jobId = "batch-" + System.currentTimeMillis();
        int total = merchantIdList.size();
        BatchJobStatus status = new BatchJobStatus(jobId, total);
        activeJobs.put(jobId, status);

        Thread pipelineThread = new Thread(() -> {
            try {
                Files.createDirectories(Paths.get(targetFolder));
                String generatedDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"));

                ExecutorService dataPool = Executors.newFixedThreadPool(Math.min(dataFetchThreads, 12),
                        r -> { Thread t = new Thread(r, "pdf-data"); t.setDaemon(true); return t; });
                ExecutorService renderPool = Executors.newFixedThreadPool(POOL_SIZE,
                        r -> { Thread t = new Thread(r, "pdf-render"); t.setDaemon(true); return t; });
                ExecutorService writePool = Executors.newFixedThreadPool(4,
                        r -> { Thread t = new Thread(r, "pdf-write"); t.setDaemon(true); return t; });

                status.phase = "GENERATING";
                List<CompletableFuture<Void>> allFutures = new ArrayList<>(total);

                for (int i = 0; i < total; i++) {
                    if (status.cancelled) break;
                    final long merchantId = merchantIdList.get(i)[0];
                    final long[] idContext = merchantIdList.get(i);
                    final String merchantName = merchantNames.get(i);

                    CompletableFuture<Void> future = CompletableFuture
                        .supplyAsync(() -> {
                            if (status.cancelled) return null;
                            long t0 = System.nanoTime();
                            try {
                                MerchantInsightsDTO dto = dataFetcher.apply(merchantId, idContext);
                                status.totalDataFetchMs.addAndGet((System.nanoTime() - t0) / 1_000_000);
                                return dto;
                            } catch (Exception e) {
                                status.failed.incrementAndGet(); status.completed.incrementAndGet();
                                status.errors.add(merchantName + " [data]: " + truncate(e.getMessage(), 80));
                                return null;
                            }
                        }, dataPool)
                        .thenApplyAsync(dto -> {
                            if (dto == null || status.cancelled) return null;
                            try {
                                String html = renderHtml(dto, merchantName, monthYear, generatedDate);
                                return inlineResources(html);
                            } catch (Exception e) {
                                status.failed.incrementAndGet(); status.completed.incrementAndGet();
                                status.errors.add(merchantName + " [html]: " + truncate(e.getMessage(), 80));
                                return null;
                            }
                        }, dataPool)
                        .thenApplyAsync(html -> {
                            if (html == null || status.cancelled) return null;
                            BrowserSlot slot = null;
                            try {
                                slot = browserPool.poll(120, TimeUnit.SECONDS);
                                if (slot == null) throw new RuntimeException("No slot available");
                                if (!slot.isHealthy()) slot.reset();
                                long t0 = System.nanoTime();
                                byte[] pdf = renderPdfInSlot(slot, html);
                                long ms = (System.nanoTime() - t0) / 1_000_000;
                                slot.totalRendered++; slot.totalRenderTimeMs += ms;
                                status.totalRenderMs.addAndGet(ms);
                                return pdf;
                            } catch (Exception e) {
                                if (slot != null) { try { slot.reset(); } catch (Exception ignored) {} }
                                status.failed.incrementAndGet(); status.completed.incrementAndGet();
                                status.errors.add(merchantName + " [pdf]: " + truncate(e.getMessage(), 80));
                                return null;
                            } finally {
                                if (slot != null) browserPool.offer(slot);
                            }
                        }, renderPool)
                        .thenAcceptAsync(pdfBytes -> {
                            if (pdfBytes == null || status.cancelled) return;
                            long t0 = System.nanoTime();
                            try {
                                String safeName = merchantName.replaceAll("[^a-zA-Z0-9.\\-]", "_");
                                Path path = Paths.get(targetFolder, "Insight_" + safeName + "_" + targetYearMonth + ".pdf");
                                Files.write(path, pdfBytes);
                                status.succeeded.incrementAndGet();
                                status.totalWriteMs.addAndGet((System.nanoTime() - t0) / 1_000_000);
                            } catch (Exception e) {
                                status.failed.incrementAndGet();
                                status.errors.add(merchantName + " [write]: " + truncate(e.getMessage(), 80));
                            } finally {
                                int done = status.completed.incrementAndGet();
                                if (done % 500 == 0 || done == total) {
                                    log.info("PDF Batch: {}/{} ({})% — {}s — avg {}ms/render — {} errors",
                                        done, total, String.format("%.1f", status.progressPercent()),
                                        String.format("%.1f", status.elapsedMs() / 1000.0),
                                        status.succeeded.get() > 0 ? status.totalRenderMs.get() / status.succeeded.get() : 0,
                                        status.failed.get());
                                }
                            }
                        }, writePool);

                    allFutures.add(future);
                }

                CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0])).join();
                dataPool.shutdown(); renderPool.shutdown(); writePool.shutdown();
                status.phase = "COMPLETED"; status.endTime = Instant.now();

                double sec = status.elapsedMs() / 1000.0;
                log.info("━━━━━━ PDF BATCH COMPLETE ━━━━━━");
                log.info("  Total: {} | Success: {} | Failed: {}", total, status.succeeded.get(), status.failed.get());
                log.info("  Time: {}s ({} min)", String.format("%.1f", sec), String.format("%.1f", sec / 60));
                log.info("  Avg render: {}ms/report", status.succeeded.get() > 0 ? status.totalRenderMs.get() / status.succeeded.get() : 0);
                log.info("  Throughput: {} reports/sec", String.format("%.1f", status.succeeded.get() / Math.max(1.0, sec)));
                browserPool.forEach(s -> log.info("  Slot {}: {} renders, avg {}ms", s.id, s.totalRendered, String.format("%.0f", s.avgRenderMs())));
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            } catch (Exception e) {
                status.phase = "FAILED"; status.endTime = Instant.now();
                log.error("Batch pipeline failed", e); status.errors.add("CRITICAL: " + e.getMessage());
            } finally {
                CompletableFuture.delayedExecutor(10, TimeUnit.MINUTES).execute(() -> activeJobs.remove(jobId));
            }
        }, "pdf-batch-pipeline");
        pipelineThread.setDaemon(true);
        pipelineThread.start();
        return status;
    }

    public BatchJobStatus getJobStatus(String jobId) { return activeJobs.get(jobId); }
    public Map<String, BatchJobStatus> getActiveJobs() { return Collections.unmodifiableMap(activeJobs); }
    public boolean cancelJob(String jobId) {
        BatchJobStatus j = activeJobs.get(jobId);
        if (j != null) { j.cancelled = true; j.phase = "CANCELLED"; return true; }
        return false;
    }

    public Map<String, Object> getEngineStats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("version", "v5"); s.put("poolSize", POOL_SIZE);
        s.put("availableSlots", browserPool.size()); s.put("busySlots", POOL_SIZE - browserPool.size());
        s.put("chartWaitMs", chartWaitMs); s.put("activeJobs", activeJobs.size());
        s.put("fontsCached", cachedFonts.size());
        List<Map<String, Object>> slots = new ArrayList<>();
        for (BrowserSlot sl : browserPool) {
            slots.add(Map.of("id", sl.id, "healthy", sl.isHealthy(),
                    "totalRendered", sl.totalRendered, "avgRenderMs", Math.round(sl.avgRenderMs())));
        }
        s.put("slots", slots);
        return s;
    }

    private String loadClasspathResource(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream is = resource.getInputStream()) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                log.info("✓ Cached: {} ({} bytes)", path, content.length());
                return content;
            }
        } catch (Exception e) {
            log.warn("✗ Could not cache {}: {}", path, e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int maxLen) {
        return s == null ? "" : s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
