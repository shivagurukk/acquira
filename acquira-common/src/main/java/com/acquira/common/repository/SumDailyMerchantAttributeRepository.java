package com.acquira.common.repository;

import com.acquira.common.model.SumDailyMerchantAttribute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface SumDailyMerchantAttributeRepository extends JpaRepository<SumDailyMerchantAttribute, Long> {

    @Query("SELECT s FROM SumDailyMerchantAttribute s WHERE s.merchantId = :merchantId AND s.businessDate BETWEEN :startDate AND :endDate")
    List<SumDailyMerchantAttribute> findByMerchantAndDateRange(
            @Param("merchantId") Long merchantId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT s FROM SumDailyMerchantAttribute s WHERE s.merchantId = :merchantId AND s.businessDate BETWEEN :startDate AND :endDate AND s.attributeType = :type")
    List<SumDailyMerchantAttribute> findByMerchantDateAndType(
            @Param("merchantId") Long merchantId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("type") String type);
}
