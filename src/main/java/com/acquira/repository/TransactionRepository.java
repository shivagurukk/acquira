package com.acquira.repository;

import com.acquira.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long>, JpaSpecificationExecutor<Transaction> {

        List<Transaction> findByPaymentDateBetween(LocalDateTime startDate, LocalDateTime endDate);

        void deleteByPaymentDateBetween(LocalDateTime startDate, LocalDateTime endDate);

        /**
         * Override findAll with EntityGraph to eagerly fetch relationships in a single
         * JOIN query, avoiding N+1 with LAZY fetch type.
         */
        @Override
        @EntityGraph(attributePaths = { "merchant", "store", "terminal" })
        Page<Transaction> findAll(Specification<Transaction> spec, Pageable pageable);

        @Query("SELECT t.merchantId, m.mid, m.name, EXTRACT(DAY FROM t.paymentDate), SUM(t.totalAmountSettled) "
                        + "FROM Transaction t JOIN t.merchant m "
                        + "WHERE t.tenantId = :tenantId AND t.paymentDate BETWEEN :startDate AND :endDate "
                        + "GROUP BY t.merchantId, m.mid, m.name, EXTRACT(DAY FROM t.paymentDate)")
        List<Object[]> findDailyVolumesByDateRange(Long tenantId, LocalDateTime startDate, LocalDateTime endDate);
}
