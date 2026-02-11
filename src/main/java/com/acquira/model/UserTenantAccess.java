package com.acquira.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "user_tenant_access")
@Data
public class UserTenantAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer accessId;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne
    @JoinColumn(name = "group_id")
    private SysUserGroup sysUserGroup;

    @Column(name = "role_in_tenant")
    private String roleInTenant; // ROLE_ADMIN, ROLE_VIEWER, etc.

    @Column(name = "is_default_tenant")
    private Boolean isDefaultTenant = false;

    public Integer getAccessId() {
        return accessId;
    }

    public void setAccessId(Integer accessId) {
        this.accessId = accessId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public SysUserGroup getSysUserGroup() {
        return sysUserGroup;
    }

    public void setSysUserGroup(SysUserGroup sysUserGroup) {
        this.sysUserGroup = sysUserGroup;
    }

    public String getRoleInTenant() {
        return roleInTenant;
    }

    public void setRoleInTenant(String roleInTenant) {
        this.roleInTenant = roleInTenant;
    }

    public Boolean getIsDefaultTenant() {
        return isDefaultTenant;
    }

    public void setIsDefaultTenant(Boolean isDefaultTenant) {
        this.isDefaultTenant = isDefaultTenant;
    }
}
