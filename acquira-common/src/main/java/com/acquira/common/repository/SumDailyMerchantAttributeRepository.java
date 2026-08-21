package com.acquira.common.repository;

import com.acquira.common.model.SumDailyMerchantAttribute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * [TENANCY] merchant_id is a global BIGSERIAL — unique, but not tenant-unique.
 * Every finder here therefore pins s.tenantId as well, so a merchant id from
 * another tenant returns nothing rather than that tenant's attribute rows.
 * Callers must pass the tenant from the CALLER's context (TenantContext / job
 * parameter), never one derived from the merchant record itself.
 */
@Repository
public interface SumDailyMerchantAttributeRepository extends JpaRepository<SumDailyMerchantAttribute, Long> {

    @Query("SELECT s FROM SumDailyMerchantAttribute s WHERE s.tenantId = :tenantId AND s.merchantId = :merchantId AND s.businessDate BETWEEN :startDate AND :endDate")
    List<SumDailyMerchantAttribute> findByMerchantAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("merchantId") Long merchantId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT s FROM SumDailyMerchantAttribute s WHERE s.tenantId = :tenantId AND s.merchantId = :merchantId AND s.businessDate BETWEEN :startDate AND :endDate AND s.attributeType = :type")
    List<SumDailyMerchantAttribute> findByMerchantDateAndType(
            @Param("tenantId") Long tenantId,
            @Param("merchantId") Long merchantId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("type") String type);

    // BULK: fetch attributes for all merchants in one query
    @Query("SELECT s FROM SumDailyMerchantAttribute s WHERE s.tenantId = :tenantId AND s.merchantId IN :merchantIds AND s.businessDate BETWEEN :startDate AND :endDate")
    List<SumDailyMerchantAttribute> findByMerchantsAndDateRange(
            @Param("tenantId") Long tenantId,
            @Param("merchantIds") java.util.List<Long> merchantIds,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}
