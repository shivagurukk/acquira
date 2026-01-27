package com.acquira.controller;

import com.acquira.model.Transaction;
import com.acquira.model.Merchant;
import com.acquira.model.Store;
import com.acquira.model.Terminal;
import com.acquira.repository.MerchantRepository;
import com.acquira.repository.StoreRepository;
import com.acquira.repository.TerminalRepository;
import com.acquira.repository.TransactionRepository;
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
import java.util.Collections;
import java.util.List;
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

        Specification<Transaction> spec = createSpecification(mid, sid, tid, paymentDateFrom, paymentDateTo,
                transactionDateFrom, transactionDateTo);

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "paymentDate"));
        Page<Transaction> result = transactionRepository.findAll(spec, pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/export/csv")
    public void exportTransactionsCsv(
            @RequestParam(required = false) String mid,
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) String tid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDateTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate transactionDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate transactionDateTo,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {

        Specification<Transaction> spec = createSpecification(mid, sid, tid, paymentDateFrom, paymentDateTo,
                transactionDateFrom, transactionDateTo);
        List<Transaction> transactions = transactionRepository.findAll(spec,
                Sort.by(Sort.Direction.DESC, "paymentDate"));

        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=\"transactions.csv\"");

        try (java.io.PrintWriter writer = response.getWriter()) {
            // Header
            writer.println(
                    "TransactionID,Payment Date,Transaction Date,Amount,Type,Card Scheme,Card Number,MID,Store ID,Terminal ID");

            // Data
            for (Transaction t : transactions) {
                writer.printf("%s,%s,%s,%.2f,%s,%s,%s,%s,%s,%s%n",
                        t.getTransactionId(),
                        t.getPaymentDate(),
                        t.getTransactionDate(),
                        t.getTxnCurrencyAmount() != null ? t.getTxnCurrencyAmount() : 0.0,
                        t.getTransactionType(),
                        t.getCardScheme(),
                        t.getCardNumber(), // Mask if necessary, assuming already masked or internal use
                        t.getMerchantId(),
                        t.getStoreId(),
                        t.getTerminalId());
            }
        }
    }

    private Specification<Transaction> createSpecification(String mid, String sid, String tid,
            LocalDate paymentDateFrom, LocalDate paymentDateTo,
            LocalDate transactionDateFrom, LocalDate transactionDateTo) {
        Specification<Transaction> spec = Specification.where(null);

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
            List<Long> merchantIds = resolveMerchantIds(mid);
            if (merchantIds.isEmpty())
                return (root, query, cb) -> cb.disjunction(); // Return empty if no match
            spec = spec.and((root, query, cb) -> root.get("merchantId").in(merchantIds));
        }

        if (sid != null && !sid.isBlank()) {
            List<Long> storeIds = resolveStoreIds(sid);
            if (storeIds.isEmpty())
                return (root, query, cb) -> cb.disjunction();
            spec = spec.and((root, query, cb) -> root.get("storeId").in(storeIds));
        }

        if (tid != null && !tid.isBlank()) {
            List<Long> terminalIds = resolveTerminalIds(tid);
            if (terminalIds.isEmpty())
                return (root, query, cb) -> cb.disjunction();
            spec = spec.and((root, query, cb) -> root.get("terminalId").in(terminalIds));
        }

        return spec;
    }

    private List<Long> resolveMerchantIds(String mid) {
        // Find merchants where MID contains the search string
        Specification<Merchant> spec = (root, query, cb) -> cb.like(root.get("mid"), "%" + mid + "%");
        List<Merchant> merchants = merchantRepository.findAll(spec);
        return merchants.stream().map(Merchant::getMerchantId).collect(Collectors.toList());
    }

    private List<Long> resolveStoreIds(String sid) {
        // Find stores where SID contains search string
        Specification<Store> spec = (root, query, cb) -> cb.like(root.get("sid"), "%" + sid + "%");
        List<Store> stores = storeRepository.findAll(spec);
        return stores.stream().map(Store::getStoreId).collect(Collectors.toList());
    }

    private List<Long> resolveTerminalIds(String tid) {
        // Find terminals where TID contains search string
        Specification<Terminal> spec = (root, query, cb) -> cb.like(root.get("tid"), "%" + tid + "%");
        List<Terminal> terminals = terminalRepository.findAll(spec);
        return terminals.stream().map(Terminal::getTerminalId).collect(Collectors.toList());
    }
}
