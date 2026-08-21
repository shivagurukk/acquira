package com.acquira.common.repository;

import com.acquira.common.model.DataSourceConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DataSourceConfigRepository extends JpaRepository<DataSourceConfig, Long> {
    List<DataSourceConfig> findByIsActiveTrue();
}
