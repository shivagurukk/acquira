package com.acquira.common.model;

import jakarta.persistence.*;

@Entity
@Table(name = "user_institution_map")
public class UserInstitutionMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "map_id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "institution_id")
    private Integer institutionId;

    // Getters Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getInstitutionId() {
        return institutionId;
    }

    public void setInstitutionId(Integer institutionId) {
        this.institutionId = institutionId;
    }
}
