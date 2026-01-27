package com.acquira.repository;

import com.acquira.model.SumDailyTerminal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SumDailyTerminalRepository extends JpaRepository<SumDailyTerminal, Long> {

    // Check existence for basic validation or deduplication if needed
    boolean existsByTenantIdAndTerminalIdAndBusinessDate(Long tenantId, Long terminalId, LocalDate businessDate);
}
