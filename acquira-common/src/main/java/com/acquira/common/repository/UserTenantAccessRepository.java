package com.acquira.common.repository;

import com.acquira.common.model.UserTenantAccess;
import com.acquira.common.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserTenantAccessRepository extends JpaRepository<UserTenantAccess, Integer> {
    List<UserTenantAccess> findByUser(User user);

    List<UserTenantAccess> findByUser_Id(Long userId);

    java.util.Optional<UserTenantAccess> findByUserAndTenant_TenantId(User user, Long tenantId);

    default List<UserTenantAccess> findAllByUser(User user) {
        return findByUser(user);
    }

    // GAP-15: Batch fetch all access records for a list of users (eliminates N+1)
    @org.springframework.data.jpa.repository.Query(
        "SELECT a FROM UserTenantAccess a JOIN FETCH a.tenant LEFT JOIN FETCH a.sysUserGroup WHERE a.user IN :users")
    List<UserTenantAccess> findAllByUserIn(@org.springframework.data.repository.query.Param("users") List<User> users);
}
