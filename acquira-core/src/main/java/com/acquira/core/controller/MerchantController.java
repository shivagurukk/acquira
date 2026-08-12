package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.*;
import com.acquira.common.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Set;
import java.time.LocalDate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import com.acquira.common.dto.MerchantHierarchyDTO;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/merchants")
public class MerchantController {

    private final MerchantRepository merchantRepository;
    private final StoreRepository storeRepository;
    private final TerminalRepository terminalRepository;
    private final MerchantContactRepository contactRepository;
    private final MerchantDocumentRepository documentRepository;
    private final MerchantRiskProfileRepository riskRepository;
    private final MerchantSalesAssignmentHistoryRepository salesHistoryRepository;
    private final JdbcTemplate jdbcTemplate;
    /** Stamps the tenant's currency onto every money-bearing response. */
    private final CurrencyMeta currencyMeta;

    public MerchantController(MerchantRepository merchantRepository,
            StoreRepository storeRepository,
            TerminalRepository terminalRepository,
            MerchantContactRepository contactRepository,
            MerchantDocumentRepository documentRepository,
            MerchantRiskProfileRepository riskRepository,
            MerchantSalesAssignmentHistoryRepository salesHistoryRepository,
            JdbcTemplate jdbcTemplate,
            CurrencyMeta currencyMeta) {
        this.merchantRepository = merchantRepository;
        this.storeRepository = storeRepository;
        this.terminalRepository = terminalRepository;
        this.contactRepository = contactRepository;
        this.documentRepository = documentRepository;
        this.riskRepository = riskRepository;
        this.salesHistoryRepository = salesHistoryRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.currencyMeta = currencyMeta;
    }

    @GetMapping
    public ResponseEntity<org.springframework.data.domain.Page<Merchant>> getAllMerchants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long tenantId = TenantContext.getCurrentTenant();
        // System.out.println("Processing /api/merchants request (Tenant: " + tenantId +
        // ")");

        if (tenantId == null) {
            return ResponseEntity.status(403).build();
        }

        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return ResponseEntity.ok(merchantRepository.findAllByTenantId(tenantId, pageable));
    }

    /**
     * Tenant-guarded merchant lookup. merchant_id is a global sequence, so a raw
     * findById lets any authenticated user read another tenant's merchant by
     * guessing ids (IDOR); a cross-tenant id must behave exactly like a missing one.
     */
    private java.util.Optional<Merchant> findOwnMerchant(Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return java.util.Optional.empty();
        return merchantRepository.findById(id)
                .filter(m -> tenantId.equals(m.getTenantId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Merchant> getMerchant(@PathVariable Long id) {
        return findOwnMerchant(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/360")
    public ResponseEntity<com.acquira.common.dto.Merchant360DTO> getMerchant360(@PathVariable Long id) {
        return findOwnMerchant(id).map(merchant -> {
            com.acquira.common.dto.Merchant360DTO dto = new com.acquira.common.dto.Merchant360DTO();
            dto.setMerchant(merchant);

            List<Store> stores = storeRepository.findByMerchantId(id);
            dto.setStores(stores);

            if (!stores.isEmpty()) {
                List<Long> storeIds = stores.stream().map(Store::getStoreId)
                        .collect(java.util.stream.Collectors.toList());
                dto.setTerminals(terminalRepository.findByStoreIdIn(storeIds));
            } else {
                dto.setTerminals(java.util.Collections.<Terminal>emptyList());
            }

            dto.setContacts(contactRepository.findByMerchantId(id));
            dto.setDocuments(documentRepository.findByMerchantId(id));
            dto.setRiskProfile(riskRepository.findByMerchantId(id).orElse(null));
            return ResponseEntity.ok(dto);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/stores")
    public ResponseEntity<List<Store>> getMerchantStores(@PathVariable Long id) {
        if (findOwnMerchant(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(storeRepository.findByMerchantId(id));
    }

    @GetMapping("/{id}/terminals")
    public ResponseEntity<List<Terminal>> getMerchantTerminals(@PathVariable Long id) {
        if (findOwnMerchant(id).isEmpty()) return ResponseEntity.notFound().build();
        List<Store> stores = storeRepository.findByMerchantId(id);
        if (stores.isEmpty()) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }
        List<Long> storeIds = stores.stream().map(Store::getStoreId).collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(terminalRepository.findByStoreIdIn(storeIds));
    }

    @GetMapping("/{id}/contacts")
    public ResponseEntity<List<MerchantContact>> getMerchantContacts(@PathVariable Long id) {
        if (findOwnMerchant(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(contactRepository.findByMerchantId(id));
    }

    /**
     * Who this merchant's sales agent has been, newest change first.
     *
     * Each row carries the previous and new agent, when the change happened, what
     * caused it (UPLOAD / MANUAL / API), and — for an upload — which file and batch
     * run performed it. The merchant's FIRST agent is not listed: it was assigned as
     * part of creating the merchant, so there is no previous holder to audit.
     */
    @GetMapping("/{id}/assignment-history")
    public ResponseEntity<List<MerchantSalesAssignmentHistory>> getSalesAssignmentHistory(@PathVariable Long id) {
        if (findOwnMerchant(id).isEmpty()) return ResponseEntity.notFound().build();
        Long tenantId = TenantContext.getCurrentTenant();
        return ResponseEntity.ok(
                salesHistoryRepository.findByTenantIdAndMerchantIdOrderByChangedAtDesc(tenantId, id));
    }

    // ── Merchant Comparison Endpoint ──────────────────────────────────────────
    @PostMapping("/compare")
    public ResponseEntity<Map<String, Object>> compareMerchants(@RequestBody Map<String, Object> request) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();

        @SuppressWarnings("unchecked")
        List<Number> merchantIds = (List<Number>) request.get("merchantIds");
        String startDate = (String) request.getOrDefault("startDate", LocalDate.now().minusDays(30).toString());
        String endDate = (String) request.getOrDefault("endDate", LocalDate.now().toString());

        if (merchantIds == null || merchantIds.size() < 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "At least 2 merchants required"));
        }
        if (merchantIds.size() > 10) {
            return ResponseEntity.badRequest().body(Map.of("error", "Maximum 10 merchants can be compared at once"));
        }

        List<Long> ids = merchantIds.stream().map(Number::longValue).collect(Collectors.toList());
        // Safety: ensure all IDs are positive (defense-in-depth — Long.toString() is inherently safe)
        if (ids.stream().anyMatch(id -> id <= 0)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid merchant ID"));
        }
        String inClause = ids.stream().map(String::valueOf).collect(Collectors.joining(","));

        // Aggregate KPIs per merchant
        String kpiSql = """
            SELECT m.merchant_id, m.name, m.mid, m.status, m.city,
                   COALESCE(SUM(s.total_volume), 0) as total_volume,
                   COALESCE(SUM(s.total_txns), 0) as total_txns,
                   COALESCE(SUM(s.total_msf), 0) as total_margin,
                   CASE WHEN SUM(s.total_txns) > 0 THEN SUM(s.total_volume) / SUM(s.total_txns) ELSE 0 END as avg_txn_value,
                   COALESCE(SUM(CASE WHEN s.is_opt_in THEN s.total_volume ELSE 0 END), 0) as dcc_optin_vol,
                   COALESCE(SUM(s.total_volume), 0) as total_vol_for_rate
            FROM dim_merchant m
            LEFT JOIN sum_daily_insight s ON s.merchant_id = m.merchant_id AND s.tenant_id = m.tenant_id
                AND s.business_date BETWEEN CAST(? AS DATE) AND CAST(? AS DATE)
            WHERE m.merchant_id IN (%s) AND m.tenant_id = ?
            GROUP BY m.merchant_id, m.name, m.mid, m.status, m.city
            """.formatted(inClause);

        // Monthly trend per merchant
        String trendSql = """
            SELECT m.merchant_id,
                   TO_CHAR(s.business_date, 'YYYY-MM') as month,
                   COALESCE(SUM(s.total_volume), 0) as volume,
                   COALESCE(SUM(s.total_txns), 0) as txns
            FROM dim_merchant m
            JOIN sum_daily_insight s ON s.merchant_id = m.merchant_id AND s.tenant_id = m.tenant_id
                AND s.business_date BETWEEN CAST(? AS DATE) AND CAST(? AS DATE)
            WHERE m.merchant_id IN (%s) AND m.tenant_id = ?
            GROUP BY m.merchant_id, TO_CHAR(s.business_date, 'YYYY-MM')
            ORDER BY m.merchant_id, month
            """.formatted(inClause);

        // Scheme breakdown per merchant
        String schemeSql = """
            SELECT m.merchant_id, COALESCE(s.card_scheme, 'OTHER') as name,
                   COALESCE(SUM(s.total_volume), 0) as volume
            FROM dim_merchant m
            JOIN sum_daily_insight s ON s.merchant_id = m.merchant_id AND s.tenant_id = m.tenant_id
                AND s.business_date BETWEEN CAST(? AS DATE) AND CAST(? AS DATE)
            WHERE m.merchant_id IN (%s) AND m.tenant_id = ?
            GROUP BY m.merchant_id, s.card_scheme
            """.formatted(inClause);

        // Card type breakdown
        String cardTypeSql = """
            SELECT m.merchant_id, COALESCE(s.card_type, 'OTHER') as name,
                   COALESCE(SUM(s.total_volume), 0) as volume
            FROM dim_merchant m
            JOIN sum_daily_insight s ON s.merchant_id = m.merchant_id AND s.tenant_id = m.tenant_id
                AND s.business_date BETWEEN CAST(? AS DATE) AND CAST(? AS DATE)
            WHERE m.merchant_id IN (%s) AND m.tenant_id = ?
            GROUP BY m.merchant_id, s.card_type
            """.formatted(inClause);

        // Execute queries
        var kpiRows = jdbcTemplate.queryForList(kpiSql, startDate, endDate, tenantId);
        var trendRows = jdbcTemplate.queryForList(trendSql, startDate, endDate, tenantId);
        var schemeRows = jdbcTemplate.queryForList(schemeSql, startDate, endDate, tenantId);
        var cardTypeRows = jdbcTemplate.queryForList(cardTypeSql, startDate, endDate, tenantId);

        // Build per-merchant response
        List<Map<String, Object>> merchants = new ArrayList<>();
        Map<String, Object> leaders = new HashMap<>();
        Map<String, Object> deltas = new HashMap<>();

        double maxVol = 0, maxTxns = 0, maxMargin = 0, maxAvg = 0;
        Long volLeader = null, txnLeader = null, marginLeader = null, avgLeader = null;

        for (var row : kpiRows) {
            Long mid = ((Number) row.get("merchant_id")).longValue();
            double vol = ((Number) row.get("total_volume")).doubleValue();
            double txns = ((Number) row.get("total_txns")).doubleValue();
            double margin = ((Number) row.get("total_margin")).doubleValue();
            double avg = ((Number) row.get("avg_txn_value")).doubleValue();
            double dccVol = ((Number) row.get("dcc_optin_vol")).doubleValue();
            double totalForRate = ((Number) row.get("total_vol_for_rate")).doubleValue();

            if (vol > maxVol) { maxVol = vol; volLeader = mid; }
            if (txns > maxTxns) { maxTxns = txns; txnLeader = mid; }
            if (margin > maxMargin) { maxMargin = margin; marginLeader = mid; }
            if (avg > maxAvg) { maxAvg = avg; avgLeader = mid; }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("merchantId", mid);
            m.put("name", row.get("name"));
            m.put("mid", row.get("mid"));
            m.put("status", row.get("status"));
            // city now comes from dim_merchant.city (previously hard-coded blank,
            // which rendered as an empty chip on the comparison card).
            m.put("city", row.get("city") != null ? row.get("city") : "");
            m.put("totalVolume", vol);
            m.put("totalTxns", (long) txns);
            m.put("avgTxnValue", avg);
            m.put("totalMargin", margin);
            m.put("dccOptinRate", totalForRate > 0 ? (dccVol / totalForRate) * 100 : 0);
            m.put("volatilityIndex", 0.0);
            m.put("stabilityLabel", "Stable");

            // Attach trend data
            List<Map<String, Object>> trend = trendRows.stream()
                .filter(t -> ((Number) t.get("merchant_id")).longValue() == mid)
                .map(t -> { Map<String, Object> tp = new HashMap<>(); tp.put("month", t.get("month")); tp.put("volume", t.get("volume")); tp.put("txns", t.get("txns")); return tp; })
                .collect(Collectors.toList());
            m.put("monthlyTrend", trend);

            // Scheme breakdown
            List<Map<String, Object>> schemes = schemeRows.stream()
                .filter(s -> ((Number) s.get("merchant_id")).longValue() == mid)
                .map(s -> { Map<String, Object> sp = new HashMap<>(); sp.put("name", s.get("name")); sp.put("volume", s.get("volume")); return sp; })
                .collect(Collectors.toList());
            m.put("cardSchemeBreakdown", schemes);

            // Card type breakdown
            List<Map<String, Object>> cardTypes = cardTypeRows.stream()
                .filter(c -> ((Number) c.get("merchant_id")).longValue() == mid)
                .map(c -> { Map<String, Object> cp = new HashMap<>(); cp.put("name", c.get("name")); cp.put("volume", c.get("volume")); return cp; })
                .collect(Collectors.toList());
            m.put("cardTypeBreakdown", cardTypes);

            merchants.add(m);
        }

        leaders.put("totalVolume", volLeader);
        leaders.put("totalTxns", txnLeader);
        leaders.put("totalMargin", marginLeader);
        leaders.put("avgTxnValue", avgLeader);

        // Compute deltas between leader and runner-up
        if (merchants.size() >= 2) {
            for (String kpi : List.of("totalVolume", "totalTxns", "avgTxnValue", "totalMargin")) {
                double best = 0, second = 0;
                for (var m : merchants) {
                    double v = ((Number) m.get(kpi)).doubleValue();
                    if (v > best) { second = best; best = v; }
                    else if (v > second) { second = v; }
                }
                deltas.put(kpi, second > 0 ? ((best - second) / second) * 100 : 0);
            }
        }

        Map<String, Object> comparison = new HashMap<>();
        comparison.put("leaders", leaders);
        comparison.put("deltas", deltas);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merchants", merchants);
        result.put("comparison", comparison);
        return ResponseEntity.ok(currencyMeta.attach(result, tenantId));
    }

    @GetMapping("/hierarchy")
    public ResponseEntity<org.springframework.data.domain.Page<MerchantHierarchyDTO>> getHierarchy(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String tid,
            @RequestParam(required = false) String storeName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate mFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate mTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null)
            return ResponseEntity.status(403).build();

        // 1. Fetch Merchants (Optimized with Subqueries)
        Specification<Merchant> mSpec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("tenantId"), tenantId));

            // General Search (Merchant Level)
            if (search != null && !search.trim().isEmpty()) {
                String likePattern = "%" + search.toLowerCase() + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(root.get("name")), likePattern),
                        cb.like(cb.lower(root.get("mid")), likePattern)));
            }

            // Merchant Date Filters
            if (mFrom != null)
                preds.add(cb.greaterThanOrEqualTo(root.get("createdDate"), mFrom.atStartOfDay()));
            if (mTo != null)
                preds.add(cb.lessThanOrEqualTo(root.get("createdDate"), mTo.atTime(23, 59, 59)));

            // Store Level Filters (Subquery)
            if ((sid != null && !sid.trim().isEmpty()) || (storeName != null && !storeName.trim().isEmpty())) {
                Subquery<Long> sub = query.subquery(Long.class);
                Root<Store> subRoot = sub.from(Store.class);
                sub.select(subRoot.get("merchantId")).distinct(true);

                List<Predicate> subPreds = new ArrayList<>();
                if (sid != null && !sid.trim().isEmpty())
                    subPreds.add(cb.like(cb.lower(subRoot.get("sid")), "%" + sid.toLowerCase() + "%"));
                if (storeName != null && !storeName.trim().isEmpty())
                    subPreds.add(cb.like(cb.lower(subRoot.get("name")), "%" + storeName.toLowerCase() + "%"));

                sub.where(cb.and(subPreds.toArray(new Predicate[0])));
                preds.add(root.get("merchantId").in(sub));
            }

            // Store created-date window. These params were read but applied only
            // to the CHILD fetch below, so "Store created in Jan" returned the
            // same unfiltered merchant page (mostly with empty store arrays).
            if (sFrom != null || sTo != null) {
                Subquery<Long> sub = query.subquery(Long.class);
                Root<Store> subRoot = sub.from(Store.class);
                sub.select(subRoot.get("merchantId")).distinct(true);
                List<Predicate> subPreds = new ArrayList<>();
                if (sFrom != null)
                    subPreds.add(cb.greaterThanOrEqualTo(subRoot.get("createdDate"), sFrom.atStartOfDay()));
                if (sTo != null)
                    subPreds.add(cb.lessThanOrEqualTo(subRoot.get("createdDate"), sTo.atTime(23, 59, 59)));
                sub.where(cb.and(subPreds.toArray(new Predicate[0])));
                preds.add(root.get("merchantId").in(sub));
            }

            // Terminal Level Filters (Subquery) — TID and/or created-date window.
            boolean tidSet = tid != null && !tid.trim().isEmpty();
            if (tidSet || tFrom != null || tTo != null) {
                Subquery<Long> sub = query.subquery(Long.class);
                Root<Terminal> tRoot = sub.from(Terminal.class);
                Root<Store> sRoot = sub.from(Store.class); // Join needed to get merchantId

                // Join condition: Terminal.storeId = Store.storeId
                sub.select(sRoot.get("merchantId")).distinct(true);
                List<Predicate> subPreds = new ArrayList<>();
                subPreds.add(cb.equal(tRoot.get("storeId"), sRoot.get("storeId")));
                if (tidSet)
                    subPreds.add(cb.like(cb.lower(tRoot.get("tid")), "%" + tid.toLowerCase() + "%"));
                if (tFrom != null)
                    subPreds.add(cb.greaterThanOrEqualTo(tRoot.get("createdDate"), tFrom.atStartOfDay()));
                if (tTo != null)
                    subPreds.add(cb.lessThanOrEqualTo(tRoot.get("createdDate"), tTo.atTime(23, 59, 59)));
                sub.where(cb.and(subPreds.toArray(new Predicate[0])));
                preds.add(root.get("merchantId").in(sub));
            }

            return cb.and(preds.toArray(new Predicate[0]));
        };

        // Fetch Page of Merchants
        org.springframework.data.domain.Page<Merchant> merchantPage = merchantRepository
                .findAll(mSpec, org.springframework.data.domain.PageRequest.of(page, size));

        List<Merchant> merchants = merchantPage.getContent();
        if (merchants.isEmpty())
            // Keep the REAL total: Page.empty() reported totalPages=0, which made
            // the frontend hide the pager entirely — stranding the user on an
            // out-of-range page with no way back.
            return ResponseEntity.ok(new org.springframework.data.domain.PageImpl<>(
                    java.util.List.of(),
                    org.springframework.data.domain.PageRequest.of(page, size),
                    merchantPage.getTotalElements()));

        // Optimize: Only fetch children if child-specific filters are active
        Map<Long, List<Store>> storesByMerchantId = new HashMap<>();
        Map<Long, List<Terminal>> terminalsByStoreId = new HashMap<>();

        // 2. Bulk Fetch Stores (Only if child-specific filters are active)
        boolean hasChildFilters = (sid != null && !sid.trim().isEmpty()) ||
                (storeName != null && !storeName.trim().isEmpty()) ||
                (tid != null && !tid.trim().isEmpty()) ||
                sFrom != null || sTo != null ||
                tFrom != null || tTo != null;

        if (hasChildFilters) {
            List<Long> merchantIds = merchants.stream().map(Merchant::getMerchantId).collect(Collectors.toList());
            if (!merchantIds.isEmpty()) {

                Specification<Store> sSpec = (root, query, cb) -> {
                    List<Predicate> preds = new ArrayList<>();
                    preds.add(root.get("merchantId").in(merchantIds));

                    if (sFrom != null)
                        preds.add(cb.greaterThanOrEqualTo(root.get("createdDate"), sFrom.atStartOfDay()));
                    if (sTo != null)
                        preds.add(cb.lessThanOrEqualTo(root.get("createdDate"), sTo.atTime(23, 59, 59)));

                    if (sid != null && !sid.trim().isEmpty())
                        preds.add(cb.like(cb.lower(root.get("sid")), "%" + sid.toLowerCase() + "%"));
                    if (storeName != null && !storeName.trim().isEmpty())
                        preds.add(cb.like(cb.lower(root.get("name")), "%" + storeName.toLowerCase() + "%"));

                    return cb.and(preds.toArray(new Predicate[0]));
                };
                List<Store> allStores = storeRepository.findAll(sSpec);
                List<Long> storeIds = allStores.stream().map(Store::getStoreId).collect(Collectors.toList());
                storesByMerchantId = allStores.stream().collect(Collectors.groupingBy(Store::getMerchantId));

                // 3. Bulk Fetch Terminals
                if (!storeIds.isEmpty()) {
                    Specification<Terminal> tSpec = (root, query, cb) -> {
                        List<Predicate> preds = new ArrayList<>();
                        preds.add(root.get("storeId").in(storeIds));

                        if (tFrom != null)
                            preds.add(cb.greaterThanOrEqualTo(root.get("createdDate"), tFrom.atStartOfDay()));
                        if (tTo != null)
                            preds.add(cb.lessThanOrEqualTo(root.get("createdDate"), tTo.atTime(23, 59, 59)));

                        if (tid != null && !tid.trim().isEmpty())
                            preds.add(cb.like(cb.lower(root.get("tid")), "%" + tid.toLowerCase() + "%"));

                        return cb.and(preds.toArray(new Predicate[0]));
                    };
                    List<Terminal> allTerminals = terminalRepository.findAll(tSpec);
                    terminalsByStoreId = allTerminals.stream().collect(Collectors.groupingBy(Terminal::getStoreId));
                }
            }
        }

        // 4. Assemble Hierarchy
        final Map<Long, List<Store>> finalStoresByMerchantId = storesByMerchantId;
        final Map<Long, List<Terminal>> finalTerminalsByStoreId = terminalsByStoreId;

        List<MerchantHierarchyDTO> hierarchy = merchants.stream().map(m -> {
            MerchantHierarchyDTO dto = new MerchantHierarchyDTO();
            dto.setMerchantId(m.getMerchantId());
            dto.setName(m.getName());
            dto.setMid(m.getMid());
            dto.setStatus(m.getStatus());
            dto.setCreatedDate(m.getCreatedDate());

            // If we have fetched stores (filtered mode), attach them. Else leave
            // null/empty.
            // If hasChildFilters is false, this will be empty, triggering Frontend lazy
            // load on expand.
            List<Store> merchantStores = finalStoresByMerchantId.getOrDefault(m.getMerchantId(),
                    Collections.emptyList());

            List<MerchantHierarchyDTO.StoreHierarchyDTO> storeDtos = merchantStores.stream().map(s -> {
                MerchantHierarchyDTO.StoreHierarchyDTO sDto = new MerchantHierarchyDTO.StoreHierarchyDTO();
                sDto.setStoreId(s.getStoreId());
                sDto.setName(s.getName());
                sDto.setSid(s.getSid());
                sDto.setStatus(s.getStatus());
                sDto.setCreatedDate(s.getCreatedDate());

                List<Terminal> storeTerminals = finalTerminalsByStoreId.getOrDefault(s.getStoreId(),
                        Collections.emptyList());
                List<MerchantHierarchyDTO.TerminalHierarchyDTO> tDtos = storeTerminals.stream().map(t -> {
                    MerchantHierarchyDTO.TerminalHierarchyDTO tDto = new MerchantHierarchyDTO.TerminalHierarchyDTO();
                    tDto.setTerminalId(t.getTerminalId());
                    tDto.setTid(t.getTid());
                    tDto.setDeviceNumber(t.getDeviceNumber());
                    tDto.setType(t.getType());
                    tDto.setStatus(t.getStatus());
                    tDto.setCreatedDate(t.getCreatedDate());
                    return tDto;
                }).collect(Collectors.toList());

                sDto.setTerminals(tDtos);
                return sDto;
            }).collect(Collectors.toList());

            dto.setStores(storeDtos);
            return dto;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(new org.springframework.data.domain.PageImpl<>(hierarchy,
                org.springframework.data.domain.PageRequest.of(page, size), merchantPage.getTotalElements()));
    }
}
