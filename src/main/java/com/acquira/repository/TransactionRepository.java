package com.acquira.repository;

import com.acquira.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Transaction> {
    List<Transaction> findByPaymentDateBetween(LocalDateTime startDate, LocalDateTime endDate);

    void deleteByPaymentDateBetween(LocalDateTime startDate, LocalDateTime endDate);
}
