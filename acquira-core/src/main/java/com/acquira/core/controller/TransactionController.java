package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.Transaction;
import com.acquira.common.model.Merchant;
import com.acquira.common.model.Store;
import com.acquira.common.model.Terminal;
import com.acquira.common.repository.MerchantRepository;
import com.acquira.common.repository.StoreRepository;
import com.acquira.common.repository.TerminalRepository;
import com.acquira.common.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private TerminalRepository terminalRepository;

    /** Tenant currency + decimal precision for the export path. */
    @Autowired
    private CurrencyMeta currencyMeta;

    @GetMapping
    public ResponseEntity<Page<Transaction>> getTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String mid,
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String tid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDateTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate transactionDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate transactionDateTo) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null)
            return ResponseEntity.status(403).build();

        Specification<Transaction> spec = createSpecification(tenantId, mid, sid, tid, paymentDateFrom, paymentDateTo,
                transactionDateFrom, transactionDateTo);

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "paymentDate"));
        Page<Transaction> result = transactionRepository.findAll(spec, pageable);
        return ResponseEntity.ok(result);
    }

    // ============================================================
    // KEYSET (cursor) pagination — the scalable Transaction List path.
    // ------------------------------------------------------------
    // Replaces the offset/Page approach for large fact_transaction. Never
    // issues COUNT(*); returns a forward cursor instead of a total/page count.
    //
    // Response shape:
    //   {
    //     content:        [ Transaction, ... up to `size` ],
    //     hasMore:        boolean,                 // is there a next page?
    //     nextCursorDate: ISO-8601 timestamp|null, // pass back as cursorPaymentDate
    //     nextCursorId:   long|null                // pass back as cursorTxnId
    //   }
    //
    // First page: omit cursorPaymentDate / cursorTxnId.
    // Next page:  send the nextCursorDate + nextCursorId from the previous response.
    //
    // MID/SID/TID filters: when provided, they resolve to id lists. With keyset we
    // keep the date-window path fast; id-list filtering is applied in-memory on the
    // fetched page is NOT acceptable (would break "hasMore"), so when an id filter is
    // present we fall back to the bounded spec query but STILL avoid the global count
    // by using a slice (limit+1) rather than Page. Date filters remain the primary,
    // index-friendly predicate.
    // ============================================================
    @GetMapping("/keyset")
    public ResponseEntity<Map<String, Object>> getTransactionsKeyset(
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String mid,
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String tid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDateTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate transactionDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate transactionDateTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursorPaymentDate,
            @RequestParam(required = false) Long cursorTxnId) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null)
            return ResponseEntity.status(403).build();

        // Clamp page size to a sane bound.
        int pageSize = Math.max(1, Math.min(size, 200));

        // ── Why there is no longer a "fast path" JPQL branch ──
        // This endpoint used to call TransactionRepository.findKeyset(...) when no
        // MID/SID/TID or transaction-date filter was present. That @Query encoded
        // every optional filter as a null-guard on a bind parameter:
        //     AND (:paymentDateFrom IS NULL OR t.paymentDate >= :paymentDateFrom)
        //     AND ( :cursorPaymentDate IS NULL OR ... )
        // Hibernate emits those guards as bare positional placeholders, so Postgres
        // sees `$2 IS NULL` with the parameter compared to nothing but NULL and has
        // no way to infer its type. Every call — including the plain, unfiltered
        // `GET /api/transactions/keyset?size=20` the Transaction List screen issues
        // on load — died with:
        //     ERROR: could not determine data type of parameter $2
        // i.e. the screen was a hard 500 for every tenant.
        //
        // Casting each parameter (CAST(:paymentDateFrom AS timestamp)) would silence
        // the error, but it keeps sending placeholders for filters the caller never
        // supplied and leaves the planner with an un-sargable OR-chain. Building the
        // predicate dynamically is strictly better: an absent filter contributes NO
        // predicate and NO parameter, so the emitted SQL for the no-filter case is
        // just `WHERE tenant_id = ?` — index-friendly and partition-prunable.
        //
        // The Specification path below already did exactly that for the filtered
        // case, so both cases now share it. It is still a true SLICE (limit+1 via
        // the fluent API, never findAll(spec, PageRequest)), so no COUNT(*) is
        // issued and the reason keyset paging exists is preserved.
        Specification<Transaction> spec = createSpecification(tenantId, mid, sid, tid,
                paymentDateFrom, paymentDateTo, transactionDateFrom, transactionDateTo)
                .and(keysetSpec(cursorPaymentDate, cursorTxnId));
        List<Transaction> rows = transactionRepository.findBy(spec, q -> q
                .sortBy(Sort.by(Sort.Direction.DESC, "paymentDate")
                        .and(Sort.by(Sort.Direction.DESC, "transactionId")))
                .limit(pageSize + 1)
                .all());

        boolean hasMore = rows.size() > pageSize;
        List<Transaction> content = hasMore ? rows.subList(0, pageSize) : rows;

        // ── DTO mapping ──
        // The endpoint used to serialize the ENTITY: (a) Jackson touched the
        // three lazy @ManyToOnes per row → up to 3×pageSize extra queries under
        // open-in-view, and (b) card_number went out RAW — the CSV path masked
        // it but the JSON path did not. Names are now resolved with three
        // batched lookups and the PAN is masked here.
        Map<Long, Merchant> merchantsById = new HashMap<>();
        Map<Long, Store> storesById = new HashMap<>();
        Map<Long, Terminal> terminalsById = new HashMap<>();
        merchantRepository.findAllById(content.stream().map(Transaction::getMerchantId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet()))
                .forEach(m -> merchantsById.put(m.getMerchantId(), m));
        storeRepository.findAllById(content.stream().map(Transaction::getStoreId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet()))
                .forEach(s -> storesById.put(s.getStoreId(), s));
        terminalRepository.findAllById(content.stream().map(Transaction::getTerminalId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet()))
                .forEach(t -> terminalsById.put(t.getTerminalId(), t));

        List<Map<String, Object>> dtos = new ArrayList<>(content.size());
        for (Transaction t : content) {
            Merchant m = t.getMerchantId() != null ? merchantsById.get(t.getMerchantId()) : null;
            Store s = t.getStoreId() != null ? storesById.get(t.getStoreId()) : null;
            Terminal term = t.getTerminalId() != null ? terminalsById.get(t.getTerminalId()) : null;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("transactionId", t.getTransactionId());
            d.put("paymentDate", t.getPaymentDate());
            d.put("transactionDate", t.getTransactionDate());
            d.put("arn", t.getArn());
            d.put("merchantId", t.getMerchantId());
            d.put("storeId", t.getStoreId());
            d.put("terminalId", t.getTerminalId());
            d.put("merchant", m != null ? Map.of("mid", nzs(m.getMid()), "name", nzs(m.getName())) : null);
            d.put("store", s != null ? Map.of("sid", nzs(s.getSid()), "name", nzs(s.getName())) : null);
            d.put("terminal", term != null ? Map.of("tid", nzs(term.getTid())) : null);
            d.put("cardNumber", maskCardNumber(t.getCardNumber()));
            d.put("txnCurrency", t.getTxnCurrency());
            d.put("txnCurrencyAmount", t.getTxnCurrencyAmount());
            d.put("msf", t.getMsf());
            d.put("dcc", t.getDcc());
            d.put("transactionType", t.getTransactionType());
            d.put("destination", t.getDestination());
            dtos.add(d);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("content", dtos);
        body.put("hasMore", hasMore);
        if (!content.isEmpty()) {
            Transaction last = content.get(content.size() - 1);
            body.put("nextCursorDate", last.getPaymentDate());
            body.put("nextCursorId", last.getTransactionId());
        } else {
            body.put("nextCursorDate", null);
            body.put("nextCursorId", null);
        }
        return ResponseEntity.ok(body);
    }

    private static String nzs(String s) { return s == null ? "" : s; }

    /** Keyset predicate: rows strictly older than (cursorDate, cursorId) in DESC order. */
    private Specification<Transaction> keysetSpec(LocalDateTime cursorDate, Long cursorId) {
        return (root, query, cb) -> {
            if (cursorDate == null) {
                return cb.conjunction(); // first page — no cursor constraint
            }
            // payment_date < cursorDate OR (payment_date = cursorDate AND transaction_id < cursorId)
            var olderDate = cb.lessThan(root.get("paymentDate"), cursorDate);
            if (cursorId == null) {
                return olderDate;
            }
            var sameDateLowerId = cb.and(
                    cb.equal(root.get("paymentDate"), cursorDate),
                    cb.lessThan(root.get("transactionId"), cursorId));
            return cb.or(olderDate, sameDateLowerId);
        };
    }

    @GetMapping("/export/csv")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public void exportTransactionsCsv(
            @RequestParam(required = false) String mid,
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String tid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDateTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate transactionDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate transactionDateTo,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            response.sendError(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN, "Tenant context missing");
            return;
        }

        Specification<Transaction> spec = createSpecification(tenantId, mid, sid, tid, paymentDateFrom, paymentDateTo,
                transactionDateFrom, transactionDateTo);
        org.springframework.data.domain.Pageable exportLimit = PageRequest.of(0, 100_000, Sort.by(Sort.Direction.DESC, "paymentDate"));
        List<Transaction> transactions = transactionRepository.findAll(spec, exportLimit).getContent();

        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=\"transactions.csv\"");

        // Currency of the tenant's own books. The Amount column holds
        // txn_currency_amount, which is denominated in the ROW's txn_currency —
        // usually the tenant's currency, but not for DCC/foreign-currency rows.
        String tenantCurrency = currencyMeta.codeOrNull(tenantId);
        // -1 = unresolved. Deliberately NOT 2: defaulting to 2 is exactly what
        // truncated BHD's third decimal here in the first place.
        int tenantDecimals = currencyMeta.decimalsOr(tenantId, -1);

        try (java.io.PrintWriter writer = response.getWriter()) {
            // Header — "Currency" was missing entirely, so every exported amount was
            // a bare number the recipient had to guess the denomination of, even
            // though fact_transaction carries txn_currency per row.
            writer.println(
                    "TransactionID,Payment Date,Transaction Date,Currency,Amount,Type,Card Scheme,Card Number,MID,Store ID,Terminal ID");

            // Data
            for (Transaction t : transactions) {
                // The old "%.2f" hardcoded two decimals for every tenant. For Bahrain
                // that silently destroyed the third decimal on EVERY exported row:
                // BHD 12.345 left the system as "12.35" (0.005 gone, and irrecoverable
                // from the file). Amounts are now rendered at the tenant's real scale.
                writer.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                        t.getTransactionId(),
                        t.getPaymentDate(),
                        t.getTransactionDate(),
                        nzs(t.getTxnCurrency()),
                        exportAmount(t.getTxnCurrencyAmount(), t.getTxnCurrency(), tenantCurrency, tenantDecimals),
                        nzs(t.getTransactionType()),
                        nzs(t.getCardScheme()),
                        maskCardNumber(t.getCardNumber()),
                        t.getMerchantId(),
                        t.getStoreId(),
                        t.getTerminalId());
            }
        }
    }

    /**
     * Render a transaction amount for CSV at the correct scale.
     *
     * <p>Rounds to the tenant's decimal places only when the row is actually in the
     * tenant's currency. A foreign-currency (DCC) row is written at its FULL stored
     * precision rather than being forced into the tenant's scale — rounding a JPY or
     * KWD figure to the local currency's decimals would be a fabricated number.
     * When the tenant currency cannot be resolved we also emit full precision instead
     * of guessing a scale.
     */
    private static String exportAmount(java.math.BigDecimal amount, String rowCurrency,
            String tenantCurrency, int tenantDecimals) {
        if (amount == null) {
            return "";
        }
        boolean sameCurrency = rowCurrency != null && tenantCurrency != null
                && rowCurrency.trim().equalsIgnoreCase(tenantCurrency.trim());
        if (tenantDecimals >= 0 && sameCurrency) {
            return CurrencyMeta.formatAmount(amount, tenantDecimals);
        }
        return amount.stripTrailingZeros().toPlainString();
    }

    private Specification<Transaction> createSpecification(Long tenantId, String mid, String sid, String tid,
            LocalDate paymentDateFrom, LocalDate paymentDateTo,
            LocalDate transactionDateFrom, LocalDate transactionDateTo) {
        // SECURITY: every transaction query is scoped to the caller's tenant.
        // Without this base predicate the endpoint returned fact_transaction
        // rows across ALL tenants.
        Specification<Transaction> spec = (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);

        // Date Filters
        if (paymentDateFrom != null) {
            LocalDateTime start = paymentDateFrom.atStartOfDay();
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("paymentDate"), start));
        }
        if (paymentDateTo != null) {
            LocalDateTime end = paymentDateTo.atTime(23, 59, 59);
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("paymentDate"), end));
        }

        if (transactionDateFrom != null) {
            LocalDateTime start = transactionDateFrom.atStartOfDay();
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("transactionDate"), start));
        }
        if (transactionDateTo != null) {
            LocalDateTime end = transactionDateTo.atTime(23, 59, 59);
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("transactionDate"), end));
        }

        // ID Filters (MID, SID, TID)
        if (mid != null && !mid.isBlank()) {
            List<Long> merchantIds = resolveMerchantIds(tenantId, mid);
            if (merchantIds.isEmpty())
                return (root, query, cb) -> cb.disjunction(); // Return empty if no match
            spec = spec.and((root, query, cb) -> root.get("merchantId").in(merchantIds));
        }

        if (sid != null && !sid.isBlank()) {
            List<Long> storeIds = resolveStoreIds(tenantId, sid);
            if (storeIds.isEmpty())
                return (root, query, cb) -> cb.disjunction();
            spec = spec.and((root, query, cb) -> root.get("storeId").in(storeIds));
        }

        if (tid != null && !tid.isBlank()) {
            List<Long> terminalIds = resolveTerminalIds(tenantId, tid);
            if (terminalIds.isEmpty())
                return (root, query, cb) -> cb.disjunction();
            spec = spec.and((root, query, cb) -> root.get("terminalId").in(terminalIds));
        }

        return spec;
    }

    // Resolution cap: a one-character filter used to expand to EVERY matching
    // dim row and drop thousands of literals into an IN (...) clause. 1000 ids
    // is far beyond any real lookup; past it the filter is too vague to be
    // meaningful anyway.
    private static final int ID_RESOLUTION_CAP = 1000;

    private List<Long> resolveMerchantIds(Long tenantId, String mid) {
        // Find merchants where MID contains the search string — scoped to tenant.
        Specification<Merchant> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("tenantId"), tenantId),
                cb.like(root.get("mid"), "%" + mid + "%"));
        return merchantRepository.findBy(spec, q -> q.limit(ID_RESOLUTION_CAP).all())
                .stream().map(Merchant::getMerchantId).collect(Collectors.toList());
    }

    private List<Long> resolveStoreIds(Long tenantId, String sid) {
        // Find stores where SID contains search string — scoped to tenant.
        Specification<Store> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("tenantId"), tenantId),
                cb.like(root.get("sid"), "%" + sid + "%"));
        return storeRepository.findBy(spec, q -> q.limit(ID_RESOLUTION_CAP).all())
                .stream().map(Store::getStoreId).collect(Collectors.toList());
    }

    /** Mask card number to show only last 4 digits (PCI-DSS compliant) */
    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) return "****";
        // If already masked (contains *), return as-is
        if (cardNumber.contains("*")) return cardNumber;
        return "****" + cardNumber.substring(cardNumber.length() - 4);
    }

    private List<Long> resolveTerminalIds(Long tenantId, String tid) {
        // Find terminals where TID contains search string — scoped to tenant.
        Specification<Terminal> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("tenantId"), tenantId),
                cb.like(root.get("tid"), "%" + tid + "%"));
        return terminalRepository.findBy(spec, q -> q.limit(ID_RESOLUTION_CAP).all())
                .stream().map(Terminal::getTerminalId).collect(Collectors.toList());
    }
}
