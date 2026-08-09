package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "tenant")
@Data
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long tenantId;

    @Column(nullable = false, unique = true)
    private String institutionId; // Logical ID, e.g., "BANK001"

    @Column(nullable = false)
    private String bankName;

    @Column(nullable = false, unique = true)
    private String bankShortCode;

    @Column(name = "base_currency")
    private String baseCurrency; // e.g. USD

    private String country; // e.g. USA
    private String currencyName; // e.g. US Dollar
    private String currencySymbol; // e.g. $

    // ISO 3166-1 alpha-2 code (FK -> ref_country). THE switch the fee engine
    // keys every rate lookup on (interchange_rate_local / scheme_fee_rate /
    // mcc_sector_map / ecom_flat_fee all match country_code =
    // COALESCE(home_country_code,'AE') in TransactionJobConfig). The column
    // has existed since V2026_07_15_01 with DEFAULT 'AE', but no code could
    // ever WRITE it — so every tenant silently priced off the UAE card.
    // Set from the Jurisdiction dropdown in Tenant Management.
    @Column(name = "home_country_code")
    private String homeCountryCode = "AE";

    // Amount format of this tenant's transaction feed (files AND scheduled pulls):
    //   CMM (default) = amounts arrive in minor units -> divide by the currency's
    //                   decimal_notation_value at ingest (legacy behaviour).
    //   AMS           = amounts arrive as final decimals -> NO division.
    // Replaces the fragile 'AMS_' filename-prefix detection (kept only as an
    // explicit per-file override in FileUploadService.inputTypeForTenant).
    @Column(name = "input_format")
    private String inputFormat = "CMM";

    // WHERE the card product/type for this tenant's transactions should come
    // from (V2026_08_08_06). CONFIG ONLY for now — no ingestion/fee logic
    // reads it yet; wiring is a later deliberate phase.
    //   FILE (default) = the card type/product columns in the uploaded
    //                    transaction file (today's behaviour).
    //   BIN            = the ref_bin 8-digit mapping (Super Admin >
    //                    BIN Management).
    @Column(name = "card_type_source")
    private String cardTypeSource = "FILE";

    @ManyToOne
    @JoinColumn(name = "region_id")
    private Region region;

    private String status;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getInstitutionId() {
        return institutionId;
    }

    public void setInstitutionId(String institutionId) {
        this.institutionId = institutionId;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getBankShortCode() {
        return bankShortCode;
    }

    public void setBankShortCode(String bankShortCode) {
        this.bankShortCode = bankShortCode;
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public void setBaseCurrency(String baseCurrency) {
        this.baseCurrency = baseCurrency;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getCurrencyName() {
        return currencyName;
    }

    public void setCurrencyName(String currencyName) {
        this.currencyName = currencyName;
    }

    public String getCurrencySymbol() {
        return currencySymbol;
    }

    public String getHomeCountryCode() {
        return homeCountryCode;
    }

    public void setHomeCountryCode(String homeCountryCode) {
        this.homeCountryCode = homeCountryCode;
    }

    public String getInputFormat() {
        return inputFormat;
    }

    public void setInputFormat(String inputFormat) {
        this.inputFormat = inputFormat;
    }

    public String getCardTypeSource() {
        return cardTypeSource;
    }

    public void setCardTypeSource(String cardTypeSource) {
        this.cardTypeSource = cardTypeSource;
    }

    public void setCurrencySymbol(String currencySymbol) {
        this.currencySymbol = currencySymbol;
    }

    public Region getRegion() {
        return region;
    }

    public void setRegion(Region region) {
        this.region = region;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
