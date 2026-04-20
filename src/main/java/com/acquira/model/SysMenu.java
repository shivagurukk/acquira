package com.acquira.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;

@Entity
@Table(name = "sys_menu")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SysMenu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_id")
    @EqualsAndHashCode.Include
    private Long menuId;

    @Column(name = "menu_name", nullable = false)
    private String menuName;

    private String path;

    @Column(name = "icon_key")
    private String iconKey;

    private String category;

    @Column(name = "display_order")
    private Integer displayOrder;
}
