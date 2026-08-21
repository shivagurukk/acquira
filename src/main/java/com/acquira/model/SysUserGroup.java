package com.acquira.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import java.util.Set;

@Entity
@Table(name = "sys_user_group")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SysUserGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "group_id")
    @EqualsAndHashCode.Include
    private Long groupId;

    @Column(name = "group_name", nullable = false, unique = true)
    private String groupName;

    private String description;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "sys_group_menu",
            joinColumns = @JoinColumn(name = "group_id"),
            inverseJoinColumns = @JoinColumn(name = "menu_id"))
    private Set<SysMenu> menus;
}
