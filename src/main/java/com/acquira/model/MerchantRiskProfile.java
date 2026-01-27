package com.acquira.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Entity
@Table(name = "merchant_risk_profile")
@Data
public class MerchantRiskProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "profile_id")
    private Long profileId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "risk_score")
    private Integer riskScore;

    @Column(name = "compliance_status")
    private String complianceStatus;

    @Column(name = "kyc_status")
    private String kycStatus;

    @Column(name = "aml_checks_passed")
    private Boolean amlChecksPassed;

    @Column(name = "last_review_date")
    private LocalDate lastReviewDate;

    private String notes;
}
