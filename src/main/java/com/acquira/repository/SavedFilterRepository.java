package com.acquira.repository;

import com.acquira.model.SavedFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SavedFilterRepository extends JpaRepository<SavedFilter, Long> {

    @Query("SELECT sf FROM SavedFilter sf WHERE sf.tenantId = :tenantId " +
           "AND sf.dashboardType = :dashboardType " +
           "AND (sf.userId = :userId OR sf.isShared = true) " +
           "ORDER BY sf.isDefault DESC, sf.name ASC")
    List<SavedFilter> findAccessibleViews(
        @Param("tenantId") Long tenantId,
        @Param("userId") Long userId,
        @Param("dashboardType") String dashboardType);

    Optional<SavedFilter> findByIdAndUserId(Long id, Long userId);

    @Modifying
    @Query("UPDATE SavedFilter sf SET sf.isDefault = false " +
           "WHERE sf.userId = :userId AND sf.tenantId = :tenantId " +
           "AND sf.dashboardType = :dashboardType")
    void clearDefaults(@Param("userId") Long userId,
                       @Param("tenantId") Long tenantId,
                       @Param("dashboardType") String dashboardType);

    long countByUserIdAndTenantId(Long userId, Long tenantId);
}
