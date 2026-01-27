package com.acquira.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stg_trnx_raw")
@Data
public class StagingTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long rawId;

    private Long fileId;
    private Long tenantId; // Multi-tenant support
    private LocalDateTime loadTime; // Default now()
    private String rowHash;
    private String status;
    private String errorMessage;

    // 31 Columns from Excel
    private String entityName;
    private String aggregatorInternalId;
    private String aggregatorName;
    private String aggregatorCode;
    private String mid;
    private String merchantInternalId;
    private String merchantName;
    private String sid;
    private String merchantStoreInternalId;
    private String cmmMerchantStoreInternalId;
    private String merchantStoreLegalName;
    private String storeName;
    private String tid; // mapped to 'TerminalID' in excel
    private String arn;
    private String rrnNumber;
    private String cardNumber; // Sensitive, needs masking
    private String authCode;

    private LocalDateTime paymentDate; // Critical for partitioning/deleting
    private LocalDateTime transactionDate;

    private String batchNumber;
    private String transactionType;
    private String cardScheme;
    private String cardType;
    private Boolean dcc;
    private String txnCurrency;
    private BigDecimal txnCurrencyAmount;
    private String storeBaseCurrency;
    private BigDecimal storeBaseCurrencyAmount;
    private BigDecimal msf;
    private BigDecimal vat;
    private BigDecimal totalAmountSettled;
    private BigDecimal interchangeFee;

    private String destination;

    // Additional fields from schema if missed
    private String terminalDeviceNumber; // Not in used sample but good to have if in schema? No, not in stg_trnx_raw
                                         // schema check above.

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

    public String getEntityName() {
        return entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
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

    public String getMid() {
        return mid;
    }

    public void setMid(String mid) {
        this.mid = mid;
    }

    public String getMerchantInternalId() {
        return merchantInternalId;
    }

    public void setMerchantInternalId(String merchantInternalId) {
        this.merchantInternalId = merchantInternalId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public String getSid() {
        return sid;
    }

    public void setSid(String sid) {
        this.sid = sid;
    }

    public String getMerchantStoreInternalId() {
        return merchantStoreInternalId;
    }

    public void setMerchantStoreInternalId(String merchantStoreInternalId) {
        this.merchantStoreInternalId = merchantStoreInternalId;
    }

    public String getCmmMerchantStoreInternalId() {
        return cmmMerchantStoreInternalId;
    }

    public void setCmmMerchantStoreInternalId(String cmmMerchantStoreInternalId) {
        this.cmmMerchantStoreInternalId = cmmMerchantStoreInternalId;
    }

    public String getMerchantStoreLegalName() {
        return merchantStoreLegalName;
    }

    public void setMerchantStoreLegalName(String merchantStoreLegalName) {
        this.merchantStoreLegalName = merchantStoreLegalName;
    }

    public String getStoreName() {
        return storeName;
    }

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public String getTid() {
        return tid;
    }

    public void setTid(String tid) {
        this.tid = tid;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getRrnNumber() {
        return rrnNumber;
    }

    public void setRrnNumber(String rrnNumber) {
        this.rrnNumber = rrnNumber;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public String getAuthCode() {
        return authCode;
    }

    public void setAuthCode(String authCode) {
        this.authCode = authCode;
    }

    public LocalDateTime getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(LocalDateTime paymentDate) {
        this.paymentDate = paymentDate;
    }

    public LocalDateTime getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(LocalDateTime transactionDate) {
        this.transactionDate = transactionDate;
    }

    public String getBatchNumber() {
        return batchNumber;
    }

    public void setBatchNumber(String batchNumber) {
        this.batchNumber = batchNumber;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    public String getCardScheme() {
        return cardScheme;
    }

    public void setCardScheme(String cardScheme) {
        this.cardScheme = cardScheme;
    }

    public Boolean getDcc() {
        return dcc;
    }

    public void setDcc(Boolean dcc) {
        this.dcc = dcc;
    }

    public String getTxnCurrency() {
        return txnCurrency;
    }

    public void setTxnCurrency(String txnCurrency) {
        this.txnCurrency = txnCurrency;
    }

    public BigDecimal getTxnCurrencyAmount() {
        return txnCurrencyAmount;
    }

    public void setTxnCurrencyAmount(BigDecimal txnCurrencyAmount) {
        this.txnCurrencyAmount = txnCurrencyAmount;
    }

    public String getStoreBaseCurrency() {
        return storeBaseCurrency;
    }

    public void setStoreBaseCurrency(String storeBaseCurrency) {
        this.storeBaseCurrency = storeBaseCurrency;
    }

    public BigDecimal getStoreBaseCurrencyAmount() {
        return storeBaseCurrencyAmount;
    }

    public void setStoreBaseCurrencyAmount(BigDecimal storeBaseCurrencyAmount) {
        this.storeBaseCurrencyAmount = storeBaseCurrencyAmount;
    }

    public BigDecimal getMsf() {
        return msf;
    }

    public void setMsf(BigDecimal msf) {
        this.msf = msf;
    }

    public BigDecimal getVat() {
        return vat;
    }

    public void setVat(BigDecimal vat) {
        this.vat = vat;
    }

    public BigDecimal getTotalAmountSettled() {
        return totalAmountSettled;
    }

    public void setTotalAmountSettled(BigDecimal totalAmountSettled) {
        this.totalAmountSettled = totalAmountSettled;
    }

    public BigDecimal getInterchangeFee() {
        return interchangeFee;
    }

    public void setInterchangeFee(BigDecimal interchangeFee) {
        this.interchangeFee = interchangeFee;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getTerminalDeviceNumber() {
        return terminalDeviceNumber;
    }

    public void setTerminalDeviceNumber(String terminalDeviceNumber) {
        this.terminalDeviceNumber = terminalDeviceNumber;
    }
}
