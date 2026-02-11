package com.acquira.common.repository;

import com.acquira.common.model.ReportQueryConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReportQueryConfigRepository extends JpaRepository<ReportQueryConfig, Long> {
    List<ReportQueryConfig> findByIsActiveTrue();

    List<ReportQueryConfig> findByDataSourceIdAndIsActiveTrue(Long dataSourceId);
}
