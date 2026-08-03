package com.acquira.common.repository;

import com.acquira.common.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long>,
                org.springframework.data.jpa.repository.JpaSpecificationExecutor<Transaction> {
        // NOTE: the derived findByPaymentDateBetween / deleteByPaymentDateBetween
        // finders were removed — neither carried a tenant predicate, and the
        // delete was a cross-tenant destructive write waiting for a caller.

        // Aggregation for Manual Ingestion
        // Volume is derived from store_base_currency_amount (settlement-currency GROSS,
        // already divided by decimal_notation_value at ingest). total_amount_settled is
        // intentionally NOT used here: the upstream feed exports it inconsistently
        // (same txn seen as 54989.03 and 539.03). All charts/PDF/dashboards use store base.
        @org.springframework.data.jpa.repository.Query("SELECT t.merchantId, m.mid, m.name, EXTRACT(DAY FROM t.paymentDate), SUM(t.storeBaseCurrencyAmount) "
                        +
                        "FROM Transaction t JOIN t.merchant m " +
                        "WHERE t.tenantId = :tenantId AND t.paymentDate BETWEEN :startDate AND :endDate " +
                        "GROUP BY t.merchantId, m.mid, m.name, EXTRACT(DAY FROM t.paymentDate)")
        List<Object[]> findDailyVolumesByDateRange(Long tenantId, LocalDateTime startDate, LocalDateTime endDate);

        // ============================================================
        // KEYSET (cursor) pagination for the Transaction List screen.
        // ------------------------------------------------------------
        // WHY: the screen previously used Spring Data's Page<Transaction>, which
        // issues a SELECT COUNT(*) over the whole filtered fact set on every page
        // load to compute total pages. On a multi-billion-row fact_transaction,
        // with no date filter, that COUNT scans hundreds of millions to billions
        // of rows — seconds to minutes, or a statement timeout — even though only
        // 20 rows are displayed.
        //
        // Keyset pagination removes the COUNT entirely. Rows are ordered by
        // (payment_date DESC, transaction_id DESC) — a total order, since
        // transaction_id is unique — and each page is fetched relative to the last
        // row of the previous page (the "cursor"). The query reads only `limit`
        // rows via the (tenant_id, payment_date) index + partition pruning, so page
        // time is constant regardless of table size.
        //
        // CURSOR SEMANTICS:
        //   First page  -> cursorPaymentDate = NULL, cursorTxnId = NULL (the OR-block
        //                  short-circuits to TRUE, so ordering alone drives the first
        //                  `limit` rows).
        //   Next page   -> pass the previous page's last row's paymentDate + txnId.
        //                  The (a,b) < (cursor) comparison, expanded for SQL
        //                  portability, returns strictly older rows with no overlap
        //                  and no gap.
        //
        // The controller fetches limit+1 rows to detect "has more" without a count,
        // then trims to `limit` for the response.
        //
        // NOTE: this method covers the date-filter case (the common one). MID/SID/TID
        // filters resolve to merchant/store/terminal id lists in the controller and
        // are applied via the existing Specification path; for those, the controller
        // may still use the spec executor but should request a bounded date window.
        // ============================================================
        @Query("SELECT t FROM Transaction t " +
               "WHERE t.tenantId = :tenantId " +
               "AND (:paymentDateFrom IS NULL OR t.paymentDate >= :paymentDateFrom) " +
               "AND (:paymentDateTo IS NULL OR t.paymentDate <= :paymentDateTo) " +
               "AND ( :cursorPaymentDate IS NULL " +
               "      OR t.paymentDate < :cursorPaymentDate " +
               "      OR (t.paymentDate = :cursorPaymentDate AND t.transactionId < :cursorTxnId) ) " +
               "ORDER BY t.paymentDate DESC, t.transactionId DESC")
        List<Transaction> findKeyset(
               @Param("tenantId") Long tenantId,
               @Param("paymentDateFrom") LocalDateTime paymentDateFrom,
               @Param("paymentDateTo") LocalDateTime paymentDateTo,
               @Param("cursorPaymentDate") LocalDateTime cursorPaymentDate,
               @Param("cursorTxnId") Long cursorTxnId,
               org.springframework.data.domain.Pageable pageable);
}
