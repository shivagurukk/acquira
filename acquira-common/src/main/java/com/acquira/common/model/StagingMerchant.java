package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stg_merchant_master_raw")
@Data
public class StagingMerchant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long rawId;

    private Long fileId;
    private Long tenantId; // Multi-tenant support
    private LocalDateTime loadTime;
    private String rowHash;
    private String status;
    private String errorMessage;

    // Full Mapping matching stg_merchant_master_raw schema
    private String institutionCode; // Institution Code
    private String institutionName; // Institution Name
    private String entityInternalId; // EntityInternalId
    private String entityName; // Entity Name
    private String entityCode; // Entity Code
    private String aggregatorInternalId; // AggregatorInternalId
    private String aggregatorName; // Aggregator Name
    private String aggregatorCode; // Aggregator Code
    private String merchantInternalId; // MerchantInternalId
    private String mid; // MID
    private String merchantName; // MerchantName
    private String merchantStatus; // MerchantStatus
    private String merchantStoreInternalId; // MerchantStoreInternalId
    private String sid; // SID
    private String storeLegalName; // StoreLegalName
    private String storeName; // StoreName
    private String storeStatus; // StoreStatus
    private String businessType; // Business Type
    private String businessMcc; // Business MCC
    private String vatNumber; // VATNumber
    private String primaryContactPerson; // PrimaryContactPerson
    private String primaryContactNumber; // PrimaryContactNumber
    private String primaryContactEmail; // PrimaryContactEmail
    private String primaryContactDesignation; // PrimaryContactDesignation
    private String secondaryContactPerson; // SecondaryContactPerson
    private String secondaryContactEmail; // SecondaryContactEmail
    private String secondaryContactNumber; // SecondaryContactNumber
    private String secondaryContactDesignation; // SecondaryContactDesignation

    @Column(columnDefinition = "TEXT")
    private String address; // Address

    private String city; // City
    private String state; // State
    private String postalCode; // PostalCode

    @Column(columnDefinition = "TEXT")
    private String storeDesc; // Store Desc

    private String industryType; // Industry Type
    private String customerType; // Customer Type
    private String sourceOfFund; // SourceOffund

    private BigDecimal expectedVolume; // Expected Volume (Parsed from String if needed, assuming reader handles or we
                                       // parse in mapper)

    private Boolean regulatedActivity; // regulatedActivity

    @Column(columnDefinition = "TEXT")
    private String regulatedActivityDesc; // regulatedActivityDescription (Mismatch field name check: schema says
                                          // regulated_activity_desc)

    private String auditorName; // auditorName
    private Boolean isPep; // isPEP

    @Column(columnDefinition = "TEXT")
    private String pepReason; // PEPReason

    private Boolean highRiskAdverseMedia; // highRiskAdverseMedia
    private Boolean highRiskSourceOfWealth; // highRiskSourceOfWealth
    private String riskLevel; // RiskLevel
    private Boolean riskLevelHigh; // Risk Level High
    private Boolean riskLevelProhibited; // Risk Level Prohibited
    private Boolean riskLevelRestricted; // Risk Level Restricted
    private String product; // Product

    // Dates (String in Excel, parsed to LocalDateTime/Timestamp?)
    // Reader will read as string if we use getCellValue.
    // Ideally we should use correct types here and parse in Mapper.
    // For simplicity with JdbcBatchItemWriter's bean mapping, let's keep as
    // specific types and handle parsing in Mapper.
    private LocalDateTime dateOfOnboarding; // Date of Onboarding
    private LocalDateTime reviewedDate; // Reviewed Date
    private LocalDateTime nextReviewedDate; // Next Reviewed Date

    private String salesUserEmail; // Sales User Email
    private String salesUserId; // Sales User Id
    private String referralPartner; // Referral Partner
    private LocalDateTime createdDate; // CreatedDate

    private String terminalInternalId; // TerminalInternalId
    private String tid; // TID
    private String terminalName; // Terminal Name
    private String terminalStatus; // Terminal Status
    private String terminalDeviceNumber; // Terminal Device Number
    private String terminalType; // Terminal Type

    @Column(columnDefinition = "TEXT")
    private String terminalDescription; // Terminal Description

    private String bankName; // BankName
    private String bankAccountName; // BankAccountName
    private String bankAccountNumber; // BankAccountNumber
    private String swiftCode; // SwiftCode
    private String ibanNumber; // IBANNumber

    private LocalDateTime merchantCreatedDate; // Merchant CreatedDate
    private LocalDateTime merchantStoreCreatedDate; // MerchantStore CreatedDate
    private LocalDateTime terminalCreatedDate; // Terminal CreatedDate

    // Manual Getters/Setters
    public Long getRawId() {
        return rawId;
    }

    public void setRawId(Long rawId) {
        this.rawId = rawId;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public LocalDateTime getLoadTime() {
        return loadTime;
    }

    public void setLoadTime(LocalDateTime loadTime) {
        this.loadTime = loadTime;
    }

    public String getRowHash() {
        return rowHash;
    }

    public void setRowHash(String rowHash) {
        this.rowHash = rowHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getInstitutionCode() {
        return institutionCode;
    }

    public void setInstitutionCode(String institutionCode) {
        this.institutionCode = institutionCode;
    }

    public String getInstitutionName() {
        return institutionName;
    }

    public void setInstitutionName(String institutionName) {
        this.institutionName = institutionName;
    }

    public String getEntityInternalId() {
        return entityInternalId;
    }

    public void setEntityInternalId(String entityInternalId) {
        this.entityInternalId = entityInternalId;
    }

    public String getEntityName() {
        return entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public String getEntityCode() {
        return entityCode;
    }

    public void setEntityCode(String entityCode) {
        this.entityCode = entityCode;
    }

    public String getAggregatorInternalId() {
        return aggregatorInternalId;
    }

    public void setAggregatorInternalId(String aggregatorInternalId) {
        this.aggregatorInternalId = aggregatorInternalId;
    }

    public String getAggregatorName() {
        return aggregatorName;
    }

    public void setAggregatorName(String aggregatorName) {
        this.aggregatorName = aggregatorName;
    }

    public String getAggregatorCode() {
        return aggregatorCode;
    }

    public void setAggregatorCode(String aggregatorCode) {
        this.aggregatorCode = aggregatorCode;
    }

    public String getMerchantInternalId() {
        return merchantInternalId;
    }

    public void setMerchantInternalId(String merchantInternalId) {
        this.merchantInternalId = merchantInternalId;
    }

    public String getMid() {
        return mid;
    }

    public void setMid(String mid) {
        this.mid = mid;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public String getMerchantStatus() {
        return merchantStatus;
    }

    public void setMerchantStatus(String merchantStatus) {
        this.merchantStatus = merchantStatus;
    }

    public String getMerchantStoreInternalId() {
        return merchantStoreInternalId;
    }

    public void setMerchantStoreInternalId(String merchantStoreInternalId) {
        this.merchantStoreInternalId = merchantStoreInternalId;
    }

    public String getSid() {
        return sid;
    }

    public void setSid(String sid) {
        this.sid = sid;
    }

    public String getStoreLegalName() {
        return storeLegalName;
    }

    public void setStoreLegalName(String storeLegalName) {
        this.storeLegalName = storeLegalName;
    }

    public String getStoreName() {
        return storeName;
    }

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public String getStoreStatus() {
        return storeStatus;
    }

    public void setStoreStatus(String storeStatus) {
        this.storeStatus = storeStatus;
    }

    public String getBusinessType() {
        return businessType;
    }

    public void setBusinessType(String businessType) {
        this.businessType = businessType;
    }

    public String getBusinessMcc() {
        return businessMcc;
    }

    public void setBusinessMcc(String businessMcc) {
        this.businessMcc = businessMcc;
    }

    public String getVatNumber() {
        return vatNumber;
    }

    public void setVatNumber(String vatNumber) {
        this.vatNumber = vatNumber;
    }

    public String getPrimaryContactPerson() {
        return primaryContactPerson;
    }

    public void setPrimaryContactPerson(String primaryContactPerson) {
        this.primaryContactPerson = primaryContactPerson;
    }

    public String getPrimaryContactNumber() {
        return primaryContactNumber;
    }

    public void setPrimaryContactNumber(String primaryContactNumber) {
        this.primaryContactNumber = primaryContactNumber;
    }

    public String getPrimaryContactEmail() {
        return primaryContactEmail;
    }

    public void setPrimaryContactEmail(String primaryContactEmail) {
        this.primaryContactEmail = primaryContactEmail;
    }

    public String getPrimaryContactDesignation() {
        return primaryContactDesignation;
    }

    public void setPrimaryContactDesignation(String primaryContactDesignation) {
        this.primaryContactDesignation = primaryContactDesignation;
    }

    public String getSecondaryContactPerson() {
        return secondaryContactPerson;
    }

    public void setSecondaryContactPerson(String secondaryContactPerson) {
        this.secondaryContactPerson = secondaryContactPerson;
    }

    public String getSecondaryContactEmail() {
        return secondaryContactEmail;
    }

    public void setSecondaryContactEmail(String secondaryContactEmail) {
        this.secondaryContactEmail = secondaryContactEmail;
    }

    public String getSecondaryContactNumber() {
        return secondaryContactNumber;
    }

    public void setSecondaryContactNumber(String secondaryContactNumber) {
        this.secondaryContactNumber = secondaryContactNumber;
    }

    public String getSecondaryContactDesignation() {
        return secondaryContactDesignation;
    }

    public void setSecondaryContactDesignation(String secondaryContactDesignation) {
        this.secondaryContactDesignation = secondaryContactDesignation;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getStoreDesc() {
        return storeDesc;
    }

    public void setStoreDesc(String storeDesc) {
        this.storeDesc = storeDesc;
    }

    public String getIndustryType() {
        return industryType;
    }

    public void setIndustryType(String industryType) {
        this.industryType = industryType;
    }

    public String getCustomerType() {
        return customerType;
    }

    public void setCustomerType(String customerType) {
        this.customerType = customerType;
    }

    public String getSourceOfFund() {
        return sourceOfFund;
    }

    public void setSourceOfFund(String sourceOfFund) {
        this.sourceOfFund = sourceOfFund;
    }

    public BigDecimal getExpectedVolume() {
        return expectedVolume;
    }

    public void setExpectedVolume(BigDecimal expectedVolume) {
        this.expectedVolume = expectedVolume;
    }

    public Boolean getRegulatedActivity() {
        return regulatedActivity;
    }

    public void setRegulatedActivity(Boolean regulatedActivity) {
        this.regulatedActivity = regulatedActivity;
    }

    public String getRegulatedActivityDesc() {
        return regulatedActivityDesc;
    }

    public void setRegulatedActivityDesc(String regulatedActivityDesc) {
        this.regulatedActivityDesc = regulatedActivityDesc;
    }

    public String getAuditorName() {
        return auditorName;
    }

    public void setAuditorName(String auditorName) {
        this.auditorName = auditorName;
    }

    public Boolean getIsPep() {
        return isPep;
    }

    public void setIsPep(Boolean isPep) {
        this.isPep = isPep;
    }

    public String getPepReason() {
        return pepReason;
    }

    public void setPepReason(String pepReason) {
        this.pepReason = pepReason;
    }

    public Boolean getHighRiskAdverseMedia() {
        return highRiskAdverseMedia;
    }

    public void setHighRiskAdverseMedia(Boolean highRiskAdverseMedia) {
        this.highRiskAdverseMedia = highRiskAdverseMedia;
    }

    public Boolean getHighRiskSourceOfWealth() {
        return highRiskSourceOfWealth;
    }

    public void setHighRiskSourceOfWealth(Boolean highRiskSourceOfWealth) {
        this.highRiskSourceOfWealth = highRiskSourceOfWealth;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public Boolean getRiskLevelHigh() {
        return riskLevelHigh;
    }

    public void setRiskLevelHigh(Boolean riskLevelHigh) {
        this.riskLevelHigh = riskLevelHigh;
    }

    public Boolean getRiskLevelProhibited() {
        return riskLevelProhibited;
    }

    public void setRiskLevelProhibited(Boolean riskLevelProhibited) {
        this.riskLevelProhibited = riskLevelProhibited;
    }

    public Boolean getRiskLevelRestricted() {
        return riskLevelRestricted;
    }

    public void setRiskLevelRestricted(Boolean riskLevelRestricted) {
        this.riskLevelRestricted = riskLevelRestricted;
    }

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public LocalDateTime getDateOfOnboarding() {
        return dateOfOnboarding;
    }

    public void setDateOfOnboarding(LocalDateTime dateOfOnboarding) {
        this.dateOfOnboarding = dateOfOnboarding;
    }

    public LocalDateTime getReviewedDate() {
        return reviewedDate;
    }

    public void setReviewedDate(LocalDateTime reviewedDate) {
        this.reviewedDate = reviewedDate;
    }

    public LocalDateTime getNextReviewedDate() {
        return nextReviewedDate;
    }

    public void setNextReviewedDate(LocalDateTime nextReviewedDate) {
        this.nextReviewedDate = nextReviewedDate;
    }

    public String getSalesUserEmail() {
        return salesUserEmail;
    }

    public void setSalesUserEmail(String salesUserEmail) {
        this.salesUserEmail = salesUserEmail;
    }

    public String getSalesUserId() {
        return salesUserId;
    }

    public void setSalesUserId(String salesUserId) {
        this.salesUserId = salesUserId;
    }

    public String getReferralPartner() {
        return referralPartner;
    }

    public void setReferralPartner(String referralPartner) {
        this.referralPartner = referralPartner;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public String getTerminalInternalId() {
        return terminalInternalId;
    }

    public void setTerminalInternalId(String terminalInternalId) {
        this.terminalInternalId = terminalInternalId;
    }

    public String getTid() {
        return tid;
    }

    public void setTid(String tid) {
        this.tid = tid;
    }

    public String getTerminalName() {
        return terminalName;
    }

    public void setTerminalName(String terminalName) {
        this.terminalName = terminalName;
    }

    public String getTerminalStatus() {
        return terminalStatus;
    }

    public void setTerminalStatus(String terminalStatus) {
        this.terminalStatus = terminalStatus;
    }

    public String getTerminalDeviceNumber() {
        return terminalDeviceNumber;
    }

    public void setTerminalDeviceNumber(String terminalDeviceNumber) {
        this.terminalDeviceNumber = terminalDeviceNumber;
    }

    public String getTerminalType() {
        return terminalType;
    }

    public void setTerminalType(String terminalType) {
        this.terminalType = terminalType;
    }

    public String getTerminalDescription() {
        return terminalDescription;
    }

    public void setTerminalDescription(String terminalDescription) {
        this.terminalDescription = terminalDescription;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getBankAccountName() {
        return bankAccountName;
    }

    public void setBankAccountName(String bankAccountName) {
        this.bankAccountName = bankAccountName;
    }

    public String getBankAccountNumber() {
        return bankAccountNumber;
    }

    public void setBankAccountNumber(String bankAccountNumber) {
        this.bankAccountNumber = bankAccountNumber;
    }

    public String getSwiftCode() {
        return swiftCode;
    }

    public void setSwiftCode(String swiftCode) {
        this.swiftCode = swiftCode;
    }

    public String getIbanNumber() {
        return ibanNumber;
    }

    public void setIbanNumber(String ibanNumber) {
        this.ibanNumber = ibanNumber;
    }

    public LocalDateTime getMerchantCreatedDate() {
        return merchantCreatedDate;
    }

    public void setMerchantCreatedDate(LocalDateTime merchantCreatedDate) {
        this.merchantCreatedDate = merchantCreatedDate;
    }

    public LocalDateTime getMerchantStoreCreatedDate() {
        return merchantStoreCreatedDate;
    }

    public void setMerchantStoreCreatedDate(LocalDateTime merchantStoreCreatedDate) {
        this.merchantStoreCreatedDate = merchantStoreCreatedDate;
    }

    public LocalDateTime getTerminalCreatedDate() {
        return terminalCreatedDate;
    }

    public void setTerminalCreatedDate(LocalDateTime terminalCreatedDate) {
        this.terminalCreatedDate = terminalCreatedDate;
    }
}
