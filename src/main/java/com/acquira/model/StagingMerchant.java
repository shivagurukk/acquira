package com.acquira.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stg_merchant_master_raw")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class StagingMerchant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long rawId;

    private Long fileId;
    private Long tenantId;
    private LocalDateTime loadTime;
    private String rowHash;
    private String status;
    private String errorMessage;

    private String institutionCode;
    private String institutionName;
    private String entityInternalId;
    private String entityName;
    private String entityCode;
    private String aggregatorInternalId;
    private String aggregatorName;
    private String aggregatorCode;
    private String merchantInternalId;
    private String mid;
    private String merchantName;
    private String merchantStatus;
    private String merchantStoreInternalId;
    private String sid;
    private String storeLegalName;
    private String storeName;
    private String storeStatus;
    private String businessType;
    private String businessMcc;
    private String vatNumber;
    private String primaryContactPerson;
    private String primaryContactNumber;
    private String primaryContactEmail;
    private String primaryContactDesignation;
    private String secondaryContactPerson;
    private String secondaryContactEmail;
    private String secondaryContactNumber;
    private String secondaryContactDesignation;

    @Column(columnDefinition = "TEXT")
    private String address;

    private String city;
    private String state;
    private String postalCode;

    @Column(columnDefinition = "TEXT")
    private String storeDesc;

    private String industryType;
    private String customerType;
    private String sourceOfFund;
    private BigDecimal expectedVolume;
    private Boolean regulatedActivity;

    @Column(columnDefinition = "TEXT")
    private String regulatedActivityDesc;

    private String auditorName;
    private Boolean isPep;

    @Column(columnDefinition = "TEXT")
    private String pepReason;

    private Boolean highRiskAdverseMedia;
    private Boolean highRiskSourceOfWealth;
    private String riskLevel;
    private Boolean riskLevelHigh;
    private Boolean riskLevelProhibited;
    private Boolean riskLevelRestricted;
    private String product;

    private LocalDateTime dateOfOnboarding;
    private LocalDateTime reviewedDate;
    private LocalDateTime nextReviewedDate;

    private String salesUserEmail;
    private String salesUserId;
    private String referralPartner;
    private LocalDateTime createdDate;

    private String terminalInternalId;
    private String tid;
    private String terminalName;
    private String terminalStatus;
    private String terminalDeviceNumber;
    private String terminalType;

    @Column(columnDefinition = "TEXT")
    private String terminalDescription;

    private String bankName;
    private String bankAccountName;
    private String bankAccountNumber;
    private String swiftCode;
    private String ibanNumber;

    private LocalDateTime merchantCreatedDate;
    private LocalDateTime merchantStoreCreatedDate;
    private LocalDateTime terminalCreatedDate;
}
