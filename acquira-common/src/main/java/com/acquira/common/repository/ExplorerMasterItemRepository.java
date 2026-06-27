package com.acquira.common.repository;

import com.acquira.common.model.ExplorerMasterItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ExplorerMasterItemRepository extends JpaRepository<ExplorerMasterItem, Long> {
    List<ExplorerMasterItem> findByTenantIdOrderByItemTypeAscLabelAsc(Long tenantId);
}
