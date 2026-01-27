package com.acquira.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "merchant_contact")
@Data
public class MerchantContact {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "contact_id")
    private Long contactId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "store_id")
    private Long storeId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "contact_name")
    private String contactName;

    private String role;
    private String email;
    private String phone;

    @Column(name = "is_primary")
    private Boolean isPrimary;
}
