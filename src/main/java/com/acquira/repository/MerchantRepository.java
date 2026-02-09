package com.acquira.repository;

import com.acquira.model.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MerchantRepository extends JpaRepository<Merchant, Long>,
                org.springframework.data.jpa.repository.JpaSpecificationExecutor<Merchant> {
        Optional<Merchant> findByInternalId(String internalId);

        Optional<Merchant> findByMid(String mid);

        java.util.List<Merchant> findAllByTenantId(Long tenantId);

        org.springframework.data.domain.Page<Merchant> findAllByTenantId(Long tenantId,
                        org.springframework.data.domain.Pageable pageable);

        long countByTenantIdAndStatus(Long tenantId, String status);

        Optional<Merchant> findByName(String name);
}
