package com.acquira.common.repository;

import com.acquira.common.model.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StoreRepository
        extends JpaRepository<Store, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Store> {
    List<Store> findByMerchantId(Long merchantId);

    List<Store> findByTenantId(Long tenantId);
}
