package com.acquira.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "fact_transaction")
@Data
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "merchant_id", insertable = false, updatable = false)
    private Merchant merchant;

    @Column(name = "store_id")
    private Long storeId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "store_id", insertable = false, updatable = false)
    private Store store;

    @Column(name = "terminal_id")
    private Long terminalId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "terminal_id", insertable = false, updatable = false)
    private Terminal terminal;

    private String arn;

    @Column(name = "rrn_number")
    private String rrnNumber;

    @Column(name = "card_number")
    private String cardNumber;

    @Column(name = "auth_code")
    private String authCode;

    @Column(name = "payment_date")
    private LocalDateTime paymentDate;

    @Column(name = "transaction_date")
    private LocalDateTime transactionDate;

    @Column(name = "batch_number")
    private String batchNumber;

    @Column(name = "transaction_type")
    private String transactionType;

    @Column(name = "card_scheme")
    private String cardScheme;

    @Column(name = "card_type")
    private String cardType;

    private Boolean dcc;

    @Column(name = "txn_currency")
    private String txnCurrency;

    @Column(name = "txn_currency_amount")
    private BigDecimal txnCurrencyAmount;

    @Column(name = "store_base_currency")
    private String storeBaseCurrency;

    @Column(name = "store_base_currency_amount")
    private BigDecimal storeBaseCurrencyAmount;

    private BigDecimal msf;
    private BigDecimal vat;

    @Column(name = "total_amount_settled")
    private BigDecimal totalAmountSettled;

    @Column(name = "interchange_fee")
    private BigDecimal interchangeFee;

    @Column(name = "destination")
    private String destination;

    @Column(name = "issuer_bank")
    private String issuerBank;

    @Column(name = "issuer_country")
    private String issuerCountry;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(Long transactionId) {
        this.transactionId = transactionId;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public Merchant getMerchant() {
        return merchant;
    }

    public void setMerchant(Merchant merchant) {
        this.merchant = merchant;
    }

    public Long getStoreId() {
        return storeId;
    }

    public void setStoreId(Long storeId) {
        this.storeId = storeId;
    }

    public Store getStore() {
        return store;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    public Long getTerminalId() {
        return terminalId;
    }

    public void setTerminalId(Long terminalId) {
        this.terminalId = terminalId;
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public void setTerminal(Terminal terminal) {
        this.terminal = terminal;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
