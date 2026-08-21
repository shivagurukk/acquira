package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "dim_merchant", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "tenant_id", "internal_id" })
})
@Data
public class Merchant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "merchant_id")
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

    @Column(name = "contact_email")
    private String contactEmail;

    /**
     * PDF generate flag. 1 = generate this merchant's report, 0 = skip.
     * Defaults to 1 (set in DB via DatabaseFixer and on upload); set to 0 to
     * exclude a merchant from PDF generation.
     */
    @Column(name = "generate_report_flag")
    private Integer generateReportFlag = 1;

    public Integer getGenerateReportFlag() {
        return generateReportFlag;
    }

    public void setGenerateReportFlag(Integer generateReportFlag) {
        this.generateReportFlag = generateReportFlag;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public String getInternalId() {
        return internalId;
    }

    public void setInternalId(String internalId) {
        this.internalId = internalId;
    }

    public String getMid() {
        return mid;
    }

    public void setMid(String mid) {
        this.mid = mid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDateTime createdDate) {
        this.createdDate = createdDate;
    }

    @Column(name = "sales_email")
    private String salesEmail;

    public String getSalesUserId() {
        return salesUserId;
    }

    public void setSalesUserId(String salesUserId) {
        this.salesUserId = salesUserId;
    }

    public String getSalesEmail() {
        return salesEmail;
    }

    public void setSalesEmail(String salesEmail) {
        this.salesEmail = salesEmail;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }
}
