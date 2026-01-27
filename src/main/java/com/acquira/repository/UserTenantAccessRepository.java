package com.acquira.repository;

import com.acquira.model.UserTenantAccess;
import com.acquira.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserTenantAccessRepository extends JpaRepository<UserTenantAccess, Integer> {
    List<UserTenantAccess> findByUser(User user);

    List<UserTenantAccess> findByUser_Id(Long userId);

    java.util.Optional<UserTenantAccess> findByUserAndTenant_TenantId(User user, Long tenantId);
}
