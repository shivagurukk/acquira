package com.acquira.common.repository;

import com.acquira.common.model.MerchantSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MerchantSegmentRepository extends JpaRepository<MerchantSegment, Long> {

    /**
     * Latest segment per merchant for a tenant (one row per merchant, most recent
     * calc_date). Native correlated-MAX so it is dialect-agnostic.
     */
    @Query(value =
        "SELECT s.* FROM merchant_segment s " +
        "WHERE s.tenant_id = :tenantId " +
        "  AND s.calc_date = (SELECT MAX(s2.calc_date) FROM merchant_segment s2 " +
        "                     WHERE s2.tenant_id = s.tenant_id AND s2.merchant_id = s.merchant_id) " +
        "ORDER BY s.segment_score DESC",
        nativeQuery = true)
    List<MerchantSegment> findLatestByTenant(@Param("tenantId") Long tenantId);

    /** Segment-mix counts for the latest calc_date of a tenant. */
    @Query(value =
        "SELECT s.primary_segment AS segment, COUNT(*) AS cnt " +
        "FROM merchant_segment s " +
        "WHERE s.tenant_id = :tenantId " +
        "  AND s.calc_date = (SELECT MAX(s3.calc_date) FROM merchant_segment s3 WHERE s3.tenant_id = :tenantId) " +
        "GROUP BY s.primary_segment",
        nativeQuery = true)
    List<Object[]> segmentMix(@Param("tenantId") Long tenantId);
}
