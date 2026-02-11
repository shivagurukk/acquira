package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "region")
@Data
public class Region {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer regionId;

    @Column(nullable = false, unique = true)
    private String regionName;
}
