package com.acquira.core.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * Serves a hand-maintained OpenAPI 3.0 description of the external API surface
 * ({@code /api/v1} data products + {@code /api/external/reports} PDF endpoints).
 *
 * Served at {@code GET /api/v1/openapi.json}. The spec itself is not sensitive
 * (it leaks no tenant data), so ApiKeyAuthFilter allows this one path through
 * WITHOUT a key — integrators can read the contract before they hold credentials.
 *
 * Kept as a plain map (no springdoc dependency) so it stays in lockstep with the
 * two controllers by hand and adds no build-time weight. When endpoints change,
 * update this file alongside them.
 */
@RestController
@RequestMapping("/api/v1")
public class OpenApiController {

    @GetMapping(value = "/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> spec() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("openapi", "3.0.3");
        root.put("info", Map.of(
                "title", "Acquira External API",
                "version", "1.0.0",
                "description", "Read-only merchant analytics + statement API. Authenticate with the X-API-Key header. "
                        + "Each endpoint requires the scope shown; a key only accesses scopes it was granted."));
        root.put("servers", List.of(Map.of("url", "/", "description", "Same origin as the Acquira deployment")));

        Map<String, Object> apiKeyScheme = new LinkedHashMap<>();
        apiKeyScheme.put("type", "apiKey");
        apiKeyScheme.put("in", "header");
        apiKeyScheme.put("name", "X-API-Key");
        root.put("components", Map.of("securitySchemes", Map.of("ApiKeyAuth", apiKeyScheme)));
        root.put("security", List.of(Map.of("ApiKeyAuth", List.of())));

        Map<String, Object> paths = new LinkedHashMap<>();

        paths.put("/api/v1/merchants", jsonOp(
                "List merchants", "read:merchants", List.of(
                param("status", "query", false, "string", "Filter by merchant status"),
                param("search", "query", false, "string", "Name/MID contains"),
                param("page", "query", false, "integer", "0-based page (default 0)"),
                param("size", "query", false, "integer", "Page size (default 50, max 500)"))));

        paths.put("/api/v1/merchants/{mid}/summary", jsonOp(
                "Per-merchant settlement totals", "read:merchants", List.of(
                param("mid", "path", true, "string", "Merchant MID or internal id"),
                param("startDate", "query", false, "string", "YYYY-MM-DD (default month start)"),
                param("endDate", "query", false, "string", "YYYY-MM-DD (default today)"))));

        paths.put("/api/v1/transactions", jsonOp(
                "List raw transactions (date range required, max 92 days)", "read:transactions", List.of(
                param("startDate", "query", true, "string", "YYYY-MM-DD"),
                param("endDate", "query", true, "string", "YYYY-MM-DD"),
                param("mid", "query", false, "string", "Filter by merchant MID"),
                param("sid", "query", false, "string", "Filter by store SID"),
                param("page", "query", false, "integer", "0-based page"),
                param("size", "query", false, "integer", "Page size (default 100, max 500)"))));

        paths.put("/api/v1/analytics/volume", jsonOp(
                "Volume trend", "read:analytics", List.of(
                param("startDate", "query", false, "string", "YYYY-MM-DD"),
                param("endDate", "query", false, "string", "YYYY-MM-DD"),
                param("groupBy", "query", false, "string", "day | month | scheme (default day)"))));

        paths.put("/api/v1/analytics/scheme-breakdown", jsonOp(
                "Scheme x card-type breakdown", "read:analytics", List.of(
                param("startDate", "query", false, "string", "YYYY-MM-DD"),
                param("endDate", "query", false, "string", "YYYY-MM-DD"))));

        paths.put("/api/v1/finance/summary", jsonOp(
                "Financial summary (MSF, interchange, scheme fee, VAT, net margin)", "read:finance", List.of(
                param("startDate", "query", false, "string", "YYYY-MM-DD"),
                param("endDate", "query", false, "string", "YYYY-MM-DD"))));

        paths.put("/api/external/reports/list", jsonOp(
                "List statement PDFs for a month", "read:reports", List.of(
                param("year", "query", false, "integer", "e.g. 2026 (default last month)"),
                param("month", "query", false, "integer", "1-12"))));

        paths.put("/api/external/reports/status", jsonOp(
                "Report count + total size for a month", "read:reports", List.of(
                param("year", "query", false, "integer", ""),
                param("month", "query", false, "integer", ""))));

        paths.put("/api/external/reports/download", binaryOp(
                "Download a single statement PDF", "read:reports", List.of(
                param("file", "query", true, "string", "Exact filename ending in .pdf"),
                param("year", "query", false, "integer", ""),
                param("month", "query", false, "integer", ""))));

        paths.put("/api/external/reports/merchant/{mid}", binaryOp(
                "Download a merchant's statement PDF", "read:reports", List.of(
                param("mid", "path", true, "string", "Merchant MID"),
                param("year", "query", false, "integer", ""),
                param("month", "query", false, "integer", ""))));

        paths.put("/api/external/reports/download-all", binaryOp(
                "Download all statements for a month as a ZIP", "read:reports", List.of(
                param("year", "query", false, "integer", ""),
                param("month", "query", false, "integer", ""))));

        root.put("paths", paths);
        return ResponseEntity.ok(root);
    }

    // ─── builders ──────────────────────────────────────────────────────

    private Map<String, Object> jsonOp(String summary, String scope, List<Map<String, Object>> params) {
        return pathItem(summary, scope, "application/json", params);
    }

    private Map<String, Object> binaryOp(String summary, String scope, List<Map<String, Object>> params) {
        return pathItem(summary, scope, "application/octet-stream", params);
    }

    private Map<String, Object> pathItem(String summary, String scope, String contentType, List<Map<String, Object>> params) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("summary", summary);
        op.put("description", "Required scope: `" + scope + "`.");
        op.put("parameters", params);
        op.put("responses", Map.of(
                "200", Map.of("description", "Success", "content", Map.of(contentType, Map.of())),
                "401", Map.of("description", "Missing/invalid/expired API key"),
                "403", Map.of("description", "Key lacks the required scope, or wrong tenant / source IP"),
                "429", Map.of("description", "Rate limit exceeded (see Retry-After header)")));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("get", op);
        return item;
    }

    private Map<String, Object> param(String name, String in, boolean required, String type, String desc) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("in", in);
        p.put("required", required || "path".equals(in));
        p.put("schema", Map.of("type", type));
        if (desc != null && !desc.isBlank()) p.put("description", desc);
        return p;
    }
}
