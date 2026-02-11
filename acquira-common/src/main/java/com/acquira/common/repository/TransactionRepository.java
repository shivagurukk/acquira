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
        @org.springframework.data.jpa.repository.Query("SELECT t.merchantId, m.mid, m.name, EXTRACT(DAY FROM t.paymentDate), SUM(t.totalAmountSettled) "
                        +
                        "FROM Transaction t JOIN t.merchant m " +
                        "WHERE t.tenantId = :tenantId AND t.paymentDate BETWEEN :startDate AND :endDate " +
                        "GROUP BY t.merchantId, m.mid, m.name, EXTRACT(DAY FROM t.paymentDate)")
        List<Object[]> findDailyVolumesByDateRange(Long tenantId, LocalDateTime startDate, LocalDateTime endDate);
}
