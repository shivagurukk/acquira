package com.acquira.common.repository;

import com.acquira.common.model.Terminal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TerminalRepository extends JpaRepository<Terminal, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Terminal> {

    List<Terminal> findByStoreId(Long storeId);

    List<Terminal> findByTenantId(Long tenantId);

    List<Terminal> findByStoreIdIn(List<Long> storeIds);
}
