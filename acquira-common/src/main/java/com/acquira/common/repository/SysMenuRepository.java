package com.acquira.common.repository;

import com.acquira.common.model.SysMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SysMenuRepository extends JpaRepository<SysMenu, Long> {

    List<SysMenu> findAllByOrderByDisplayOrderAsc();

    /**
     * Direct native SQL — hits sys_group_menu join table fresh every call.
     * Bypasses JPA entity cache completely. Any menu inserted into
     * sys_menu + sys_group_menu shows up immediately on next request.
     */
    @Query(value =
        "SELECT m.* FROM sys_menu m " +
        "INNER JOIN sys_group_menu gm ON gm.menu_id = m.menu_id " +
        "WHERE gm.group_id = :groupId " +
        "ORDER BY COALESCE(m.display_order, 999) ASC",
        nativeQuery = true)
    List<SysMenu> findMenusByGroupId(@Param("groupId") Long groupId);
}
