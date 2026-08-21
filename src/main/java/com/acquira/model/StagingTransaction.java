package com.acquira.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stg_trnx_raw")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class StagingTransaction {
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
    private String tid;
    private String arn;
    private String rrnNumber;
    private String cardNumber;
    private String authCode;

    private LocalDateTime paymentDate;
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
    private String terminalDeviceNumber;
}
