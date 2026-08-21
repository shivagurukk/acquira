package com.acquira.common.repository;

import com.acquira.common.model.TenantSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TenantSettingRepository extends JpaRepository<TenantSetting, Integer> {
    List<TenantSetting> findByTenant_TenantId(Long tenantId);

    Optional<TenantSetting> findByTenant_TenantIdAndKey(Long tenantId, String key);
}
