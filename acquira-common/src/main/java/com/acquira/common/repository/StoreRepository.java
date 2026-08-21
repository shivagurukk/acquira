package com.acquira.common.repository;

import com.acquira.common.model.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StoreRepository
        extends JpaRepository<Store, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Store> {
    // NOTE: unscoped findByMerchantId was removed — merchant_id is a global
    // sequence, so every read must also match tenant_id or a guessed id leaks
    // another tenant's stores (IDOR).
    List<Store> findByTenantIdAndMerchantId(Long tenantId, Long merchantId);

    List<Store> findByTenantId(Long tenantId);
}
