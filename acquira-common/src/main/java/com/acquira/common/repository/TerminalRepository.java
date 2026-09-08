package com.acquira.common.repository;

import com.acquira.common.model.Terminal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TerminalRepository extends JpaRepository<Terminal, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Terminal> {

    List<Terminal> findByTenantId(Long tenantId);

    // Tenant-isolation: store_id is a GLOBAL sequence, so a store-id-only finder
    // returns another tenant's terminals for a guessed id. Every finder that
    // takes a store id must also pin the tenant. The unscoped findByStoreId /
    // findByStoreIdIn variants were removed to stop a future caller reintroducing
    // the leak — pass the caller's tenant explicitly.
    List<Terminal> findByTenantIdAndStoreId(Long tenantId, Long storeId);

    List<Terminal> findByTenantIdAndStoreIdIn(Long tenantId, List<Long> storeIds);
}
