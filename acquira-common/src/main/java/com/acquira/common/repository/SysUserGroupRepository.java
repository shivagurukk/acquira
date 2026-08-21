package com.acquira.common.repository;

import com.acquira.common.model.SysUserGroup;
import com.acquira.common.model.SysMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SysUserGroupRepository extends JpaRepository<SysUserGroup, Long> {

    java.util.Optional<SysUserGroup> findByGroupName(String groupName);
}
