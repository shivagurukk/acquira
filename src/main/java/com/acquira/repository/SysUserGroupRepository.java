package com.acquira.repository;

import com.acquira.model.SysUserGroup;
import com.acquira.model.SysMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SysUserGroupRepository extends JpaRepository<SysUserGroup, Long> {

}
