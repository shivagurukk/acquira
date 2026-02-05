package com.acquira.repository;

import com.acquira.model.ReportQueryConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReportQueryConfigRepository extends JpaRepository<ReportQueryConfig, Long> {
    List<ReportQueryConfig> findByIsActiveTrue();

    List<ReportQueryConfig> findByDataSourceIdAndIsActiveTrue(Long dataSourceId);
}
