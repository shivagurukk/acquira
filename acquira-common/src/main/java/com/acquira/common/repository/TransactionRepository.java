package com.acquira.common.repository;

import com.acquira.common.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long>,
                org.springframework.data.jpa.repository.JpaSpecificationExecutor<Transaction> {
        List<Transaction> findByPaymentDateBetween(LocalDateTime startDate, LocalDateTime endDate);

        void deleteByPaymentDateBetween(LocalDateTime startDate, LocalDateTime endDate);

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
}
