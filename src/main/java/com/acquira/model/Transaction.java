package com.acquira.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "fact_transaction")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transaction_id")
    @EqualsAndHashCode.Include
    private Long transactionId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", insertable = false, updatable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Merchant merchant;

    @Column(name = "store_id")
    private Long storeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", insertable = false, updatable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Store store;

    @Column(name = "terminal_id")
    private Long terminalId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "terminal_id", insertable = false, updatable = false)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
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
}
