package com.acquira.common.repository;

import com.acquira.common.model.SysMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SysMenuRepository extends JpaRepository<SysMenu, Long> {
    List<SysMenu> findAllByOrderByDisplayOrderAsc();
}
