package com.acquira.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Entity
@Table(name = "dim_merchant", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "tenant_id", "internal_id" })
})
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Merchant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "merchant_id")
    @EqualsAndHashCode.Include
    private Long merchantId;

    @Column(name = "internal_id")
    private String internalId;

    private String mid;
    private String name;
    private String status;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @Column(name = "sales_user_id")
    private String salesUserId;

    @Column(name = "sales_email")
    private String salesEmail;

    @Column(name = "referral_partner")
    private String referralPartner;

    @Column(name = "risk_level")
    private String riskLevel;

    @Column(name = "tenant_id")
    private Long tenantId;

    private String industry;
    private String mcc;
    private String location;
    private String city;
}
