package com.acquira.controller;

import com.acquira.model.SysUserGroup;
import com.acquira.model.SysMenu;
import com.acquira.repository.SysUserGroupRepository;
import com.acquira.repository.SysMenuRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.HashSet;

@RestController
@RequestMapping("/api/admin/rbac")
@org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
public class RbacController {

    private final SysUserGroupRepository groupRepository;
    private final SysMenuRepository menuRepository;

    public RbacController(SysUserGroupRepository groupRepository, SysMenuRepository menuRepository) {
        this.groupRepository = groupRepository;
        this.menuRepository = menuRepository;
    }

    // --- Groups ---

    @GetMapping("/groups")
    public List<SysUserGroup> getAllGroups() {
        return groupRepository.findAll();
    }

    @PostMapping("/groups")
    @Transactional
    public ResponseEntity<SysUserGroup> createOrUpdateGroup(@RequestBody GroupDto groupDto) {
        SysUserGroup group;
        if (groupDto.getId() != null) {
            group = groupRepository.findById(groupDto.getId())
                    .orElseThrow(() -> new RuntimeException("Group not found"));
        } else {
            group = new SysUserGroup();
        }

        group.setGroupName(groupDto.getGroupName());
        group.setDescription(groupDto.getDescription());

        if (groupDto.getMenuIds() != null) {
            List<SysMenu> menus = menuRepository.findAllById(groupDto.getMenuIds());
            group.setMenus(new HashSet<>(menus));
        }

        return ResponseEntity.ok(groupRepository.save(group));
    }

    // --- Menus ---

    @GetMapping("/menus")
    public List<SysMenu> getAllMenus() {
        return menuRepository.findAllByOrderByDisplayOrderAsc();
    }

    // --- DTO ---

    public static class GroupDto {
        private Long id;
        private String groupName;
        private String description;
        private List<Long> menuIds;

        // Getters and Setters
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getGroupName() {
            return groupName;
        }

        public void setGroupName(String groupName) {
            this.groupName = groupName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<Long> getMenuIds() {
            return menuIds;
        }

        public void setMenuIds(List<Long> menuIds) {
            this.menuIds = menuIds;
        }
    }
}
