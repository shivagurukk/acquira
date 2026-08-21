package com.acquira.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;

@Entity
@Table(name = "dim_store", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "tenant_id", "internal_id" })
})
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Store {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_id")
    @EqualsAndHashCode.Include
    private Long storeId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "internal_id")
    private String internalId;

    @Column(name = "merchant_id")
    private Long merchantId;

    private String sid;
    private String name;

    @Column(name = "legal_name")
    private String legalName;

    private String address;
    private String city;
    private String state;
    private String mcc;
    private String status;

    private Double latitude;
    private Double longitude;

    @Column(name = "created_date")
    private java.time.LocalDateTime createdDate;
}
