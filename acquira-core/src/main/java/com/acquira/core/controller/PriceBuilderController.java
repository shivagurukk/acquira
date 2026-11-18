package com.acquira.core.controller;

import com.acquira.common.service.AuditService;
import com.acquira.core.service.pricebuilder.PriceBuilderCalculator;
import com.acquira.core.service.pricebuilder.PriceBuilderCalculator.Inputs;
import com.acquira.core.service.pricebuilder.PriceBuilderCalculator.MixRow;
import com.acquira.core.service.pricebuilder.PriceBuilderExporter;
import com.acquira.core.service.pricebuilder.PriceBuilderRates;
import com.acquira.core.service.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Price Builder API (/mis/price-builder).
 *
 * A pre-sale pricing calculator: it quotes a merchant that does not exist yet,
 * from the MCC, channel, volume and scheme mix the salesperson is proposing.
 * Nothing here reads the transaction warehouse — that is what makes it
 * different from /business/pricing-simulator, which re-prices the live book.
 *
 * Gated by the same menu grant that puts the screen in the sidebar, so a user
 * who cannot see the screen cannot call the API either.
 */
@RestController
@RequestMapping("/api/mis/price-builder")
@PreAuthorize("@menuAccess.canAccess('/mis/price-builder')")
public class PriceBuilderController {

    private final JdbcTemplate jdbc;
    private final TenantService tenantService;
    private final PriceBuilderRates rates;
    private final PriceBuilderCalculator calculator;
    private final PriceBuilderExporter exporter;
    private final AuditService auditService;
    private final ObjectMapper json = new ObjectMapper();

    public PriceBuilderController(JdbcTemplate jdbc, TenantService tenantService,
                                  PriceBuilderRates rates, PriceBuilderCalculator calculator,
                                  PriceBuilderExporter exporter, AuditService auditService) {
        this.jdbc = jdbc;
        this.tenantService = tenantService;
        this.rates = rates;
        this.calculator = calculator;
        this.exporter = exporter;
        this.auditService = auditService;
    }

    private Long requireTenant() {
        Long tenantId = tenantService.getCurrentTenantId();
        if (tenantId == null) throw new IllegalStateException("Tenant context not resolved");
        return tenantId;
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? "system" : a.getName();
    }

    // ── Reference data ─────────────────────────────────────────────────────

    /**
     * Everything the screen needs to render before a single figure is typed:
     * the country list, and — once a country is chosen — its currency, scheme
     * rows, MCC catalog and gateway options.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config(
            @RequestParam(required = false) String country) {
        requireTenant();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("countries", rates.countries());
        out.put("gateways",  rates.gateways());

        if (country != null && !country.isBlank()) {
            PriceBuilderRates.CountryConfig cfg = rates.config(country);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("countryCode",   cfg.countryCode());
            c.put("countryName",   cfg.countryName());
            c.put("currencyCode",  cfg.currencyCode());
            c.put("usdRate",       cfg.usdRate());
            // Percent numbers for display; the engine keeps the fractions.
            c.put("targetMarginPct",  cfg.targetMargin() * 100);
            c.put("ecomIcUpliftPct",  cfg.ecomIcUplift() * 100);
            c.put("intlMsfPosPct",    cfg.intlMsfPos() * 100);
            c.put("intlMsfEcomPct",   cfg.intlMsfEcom() * 100);
            c.put("debitIntlTxnFee",  cfg.debitIntlTxnFee());
            c.put("active",           cfg.active());
            out.put("country", c);

            List<Map<String, Object>> schemeRows = new ArrayList<>();
            for (PriceBuilderRates.SchemeRow r : rates.schemeRows(country)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("schemeKey", r.schemeKey());
                m.put("label",     r.label());
                m.put("scheme",    r.scheme());
                m.put("locality",  r.locality());
                m.put("tier",      r.tier());
                m.put("cardType",  r.cardType());
                schemeRows.add(m);
            }
            out.put("schemeRows", schemeRows);

            List<Map<String, Object>> mccs = new ArrayList<>();
            for (PriceBuilderRates.Mcc m : rates.mccs(country)) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("mcc",         m.mcc());
                x.put("sector",      m.sector());
                x.put("description", m.description());
                x.put("mccType",     m.mccType());
                mccs.add(x);
            }
            out.put("mccs", mccs);
            out.put("splits", rates.ticketSplits(country));
        }
        return ResponseEntity.ok(out);
    }

    // ── Pricing ────────────────────────────────────────────────────────────

    /** Prices a quote. Stateless — nothing is written until it is saved. */
    @PostMapping("/calculate")
    public ResponseEntity<?> calculate(@RequestBody Map<String, Object> body) {
        requireTenant();
        try {
            return ResponseEntity.ok(calculator.calculate(inputsFrom(body)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Reads the request body into engine inputs.
     *
     * The UI speaks percent numbers and local currency; the engine speaks
     * fractions and USD. The conversion happens once, here and in the engine,
     * never twice — which is the failure this whole module is shaped to avoid.
     */
    @SuppressWarnings("unchecked")
    private Inputs inputsFrom(Map<String, Object> b) {
        String country = str(b.get("countryCode"), "BH");
        String channel = PriceBuilderRates.channel(str(b.get("channel"), "POS"));

        List<MixRow> mix = new ArrayList<>();
        Object rawRows = b.get("mixRows");
        if (rawRows instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> r = (Map<String, Object>) o;
                mix.add(new MixRow(
                        str(r.get("schemeKey"), ""),
                        num(r.get("volumePct")),
                        num(r.get("txnPct")),
                        num(r.get("msfPct"))));
            }
        }

        return new Inputs(
                country, channel, str(b.get("mcc"), null),
                num(b.get("annualVolume")), (long) num(b.get("totalTxns")),
                mix,
                num(b.get("gatewayCostUsd")),
                num(b.get("posUnits")), num(b.get("posRateMonthly")),
                num(b.get("ecomSetupFee")), num(b.get("ecomAnnualFee")),
                num(b.get("settlementFeePerTxnUsd")),
                num(b.get("dccOptInPct")), num(b.get("dccMsfPct")),
                num(b.get("dccMerchantRebatePct")), num(b.get("dccVendorRebatePct")),
                nullableNum(b.get("debitSmallTxnPct")),
                nullableNum(b.get("creditSmallTxnPct")));
    }

    // ── Saved quotes ───────────────────────────────────────────────────────

    @GetMapping("/quotes")
    public ResponseEntity<List<Map<String, Object>>> quotes(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status) {
        Long tenantId = requireTenant();

        StringBuilder sql = new StringBuilder(
                "SELECT quote_id, quote_ref, merchant_name, country_code, channel, mcc, " +
                "       annual_volume, total_txns, effective_msf, blended_ic, blended_sf, " +
                "       net_spread_usd, margin_per_txn, status, created_by, created_at " +
                "FROM pb_quote WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (q != null && !q.isBlank()) {
            sql.append(" AND (LOWER(merchant_name) LIKE ? OR LOWER(quote_ref) LIKE ?)");
            String like = "%" + q.trim().toLowerCase() + "%";
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
        sql.append(" ORDER BY created_at DESC LIMIT 500");

        return ResponseEntity.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    @GetMapping("/quotes/{id}")
    public ResponseEntity<?> quote(@PathVariable Long id) {
        Long tenantId = requireTenant();
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT * FROM pb_quote WHERE tenant_id = ? AND quote_id = ?", tenantId, id);
        if (found.isEmpty()) return ResponseEntity.notFound().build();

        Map<String, Object> row = new LinkedHashMap<>(found.get(0));
        // inputs/results are stored as JSON text; hand them back as objects so
        // the screen can reopen the quote without parsing a string twice.
        row.put("inputs",  readJson(row.get("inputs")));
        row.put("results", readJson(row.get("results")));
        return ResponseEntity.ok(row);
    }

    /** Saves a quote, pricing it server-side so the stored figures are ours. */
    @PostMapping("/quotes")
    @Transactional
    public ResponseEntity<?> saveQuote(@RequestBody Map<String, Object> body) {
        Long tenantId = requireTenant();
        String merchantName = str(body.get("merchantName"), "").trim();
        if (merchantName.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Merchant name is required"));

        Inputs in;
        Map<String, Object> result;
        try {
            in = inputsFrom(body);
            // Re-priced here rather than trusting whatever the browser last
            // showed: a saved quote is a commercial record.
            result = calculator.calculate(in);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        Map<String, Object> totals = asMap(result.get("totals"));
        String ref = nextRef(tenantId);

        jdbc.update(
                "INSERT INTO pb_quote (tenant_id, quote_ref, merchant_name, country_code, channel, " +
                " mcc, annual_volume, total_txns, effective_msf, blended_ic, blended_sf, " +
                " net_spread_usd, margin_per_txn, status, inputs, results, created_by) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, ref, merchantName, in.countryCode(), in.channel(), in.mcc(),
                in.annualVolume(), in.totalTxns(),
                pct(totals.get("effectiveMsfPct")), pct(totals.get("blendedIcPct")),
                pct(totals.get("blendedSfPct")),
                totals.get("netSpreadUsd"), totals.get("marginPerTxnUsd"),
                str(body.get("status"), "DRAFT").toUpperCase(),
                writeJson(body), writeJson(result), currentUser());

        try {
            auditService.log("PRICE_BUILDER_QUOTE_SAVED",
                    "Saved quote " + ref + " for " + merchantName);
        } catch (Exception ignored) { }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quoteRef", ref);
        out.put("results",  result);
        return ResponseEntity.ok(out);
    }

    @PutMapping("/quotes/{id}/status")
    @Transactional
    public ResponseEntity<?> setStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long tenantId = requireTenant();
        String status = str(body.get("status"), "").trim().toUpperCase();
        if (!List.of("DRAFT", "SUBMITTED", "WON", "LOST").contains(status))
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown status: " + status));

        int n = jdbc.update(
                "UPDATE pb_quote SET status = ?, updated_at = CURRENT_TIMESTAMP " +
                "WHERE tenant_id = ? AND quote_id = ?", status, tenantId, id);
        if (n == 0) return ResponseEntity.notFound().build();

        try { auditService.log("PRICE_BUILDER_QUOTE_STATUS", "Quote " + id + " -> " + status); }
        catch (Exception ignored) { }
        return ResponseEntity.ok(Map.of("message", "Status updated"));
    }

    @DeleteMapping("/quotes/{id}")
    @Transactional
    public ResponseEntity<?> deleteQuote(@PathVariable Long id) {
        Long tenantId = requireTenant();
        int n = jdbc.update("DELETE FROM pb_quote WHERE tenant_id = ? AND quote_id = ?", tenantId, id);
        if (n == 0) return ResponseEntity.notFound().build();
        try { auditService.log("PRICE_BUILDER_QUOTE_DELETED", "Deleted quote " + id); }
        catch (Exception ignored) { }
        return ResponseEntity.ok(Map.of("message", "Quote deleted"));
    }

    // ── Exports ────────────────────────────────────────────────────────────

    /** The quote as a workbook: inputs, the priced rows, and the totals. */
    @PostMapping("/export/excel")
    public ResponseEntity<byte[]> exportExcel(@RequestBody Map<String, Object> body) {
        requireTenant();
        Inputs in = inputsFrom(body);
        Map<String, Object> result = calculator.calculate(in);
        String name = str(body.get("merchantName"), "quote").trim();
        byte[] bytes = exporter.toWorkbook(name, in, result);
        return file(bytes, fileName(name, "xlsx"),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    /** The quote as a one-page term sheet. */
    @PostMapping("/export/pdf")
    public ResponseEntity<byte[]> exportPdf(@RequestBody Map<String, Object> body) {
        requireTenant();
        Inputs in = inputsFrom(body);
        Map<String, Object> result = calculator.calculate(in);
        String name = str(body.get("merchantName"), "quote").trim();
        byte[] bytes = exporter.toPdf(name, in, result);
        return file(bytes, fileName(name, "pdf"), MediaType.APPLICATION_PDF_VALUE);
    }

    private static ResponseEntity<byte[]> file(byte[] bytes, String name, String contentType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(bytes);
    }

    private static String fileName(String merchant, String ext) {
        String safe = merchant.replaceAll("[^A-Za-z0-9 _-]", "").trim().replaceAll("\\s+", "-");
        if (safe.isEmpty()) safe = "quote";
        return "price-builder-" + safe.toLowerCase() + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "." + ext;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Next reference for the tenant, as PB-00001.
     *
     * Derived from the tenant's own highest reference rather than a shared
     * sequence, so two tenants never see gaps in their numbering that only
     * make sense from the other side of the wall.
     */
    private String nextRef(Long tenantId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(CAST(SUBSTRING(quote_ref FROM 4) AS INT)), 0) " +
                "FROM pb_quote WHERE tenant_id = ? AND quote_ref ~ '^PB-[0-9]+$'",
                Integer.class, tenantId);
        return String.format("PB-%05d", (max == null ? 0 : max) + 1);
    }

    private static String str(Object v, String dflt) {
        if (v == null) return dflt;
        String s = String.valueOf(v);
        return s.isBlank() ? dflt : s;
    }

    private static double num(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    /** Null stays null: "no ticket split given" is not the same as zero. */
    private static Double nullableNum(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { return null; }
    }

    private static Double pct(Object v) {
        // Totals carry percent numbers; the column stores a fraction.
        return v == null ? null : num(v) / 100.0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    private String writeJson(Object v) {
        try { return json.writeValueAsString(v); }
        catch (Exception e) { return "{}"; }
    }

    private Object readJson(Object v) {
        if (v == null) return Map.of();
        try { return json.readValue(String.valueOf(v), Object.class); }
        catch (Exception e) { return Map.of(); }
    }
}
