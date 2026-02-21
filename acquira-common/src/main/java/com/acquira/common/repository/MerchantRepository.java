package com.acquira.common.repository;

import com.acquira.common.model.Merchant;
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

        public interface SalesUserProjection {
                String getSalesUserId();

                String getSalesEmail();
        }

        @org.springframework.data.jpa.repository.Query("SELECT DISTINCT m.salesUserId as salesUserId, m.salesEmail as salesEmail FROM Merchant m WHERE m.tenantId = :tenantId AND m.salesUserId IS NOT NULL")
        java.util.List<SalesUserProjection> findDistinctSalesUserInfoByTenantId(
                        @org.springframework.data.repository.query.Param("tenantId") Long tenantId);

        @org.springframework.data.jpa.repository.Query("SELECT DISTINCT m.salesUserId FROM Merchant m WHERE m.tenantId = :tenantId AND m.salesUserId IS NOT NULL")
        java.util.List<String> findDistinctSalesUserIdsByTenantId(
                        @org.springframework.data.repository.query.Param("tenantId") Long tenantId);

        @org.springframework.data.jpa.repository.Query(value = "SELECT m.sales_user_id AS sales_user_id, COUNT(*) AS merchant_count FROM dim_merchant m WHERE m.tenant_id = :tenantId AND m.sales_user_id IS NOT NULL GROUP BY m.sales_user_id", nativeQuery = true)
        java.util.List<java.util.Map<String, Object>> countMerchantsBySalesUser(
                        @org.springframework.data.repository.query.Param("tenantId") Long tenantId);
}
