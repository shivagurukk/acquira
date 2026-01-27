package com.acquira.model;

import jakarta.persistence.*;
import java.util.Set;

@Entity
@Table(name = "sys_user_group")
public class SysUserGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "group_name", nullable = false, unique = true)
    private String groupName;

    private String description;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "sys_group_menu", joinColumns = @JoinColumn(name = "group_id"), inverseJoinColumns = @JoinColumn(name = "menu_id"))
    private Set<SysMenu> menus;

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
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

    public Set<SysMenu> getMenus() {
        return menus;
    }

    public void setMenus(Set<SysMenu> menus) {
        this.menus = menus;
    }
}
