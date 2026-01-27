package com.acquira.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "merchant_activity_summary")
@Data
public class MerchantActivitySummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "summary_id")
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "first_txn_date")
    private LocalDate firstTxnDate;

    @Column(name = "last_txn_date")
    private LocalDate lastTxnDate;

    // Rolling counts/values
    @Column(name = "last_7d_cnt")
    private Long last7dCount;

    @Column(name = "last_7d_value")
    private BigDecimal last7dValue;

    @Column(name = "last_30d_cnt")
    private Long last30dCount;

    @Column(name = "last_30d_value")
    private BigDecimal last30dValue;

    @Column(name = "prev_30d_cnt")
    private Long prev30dCount;

    @Column(name = "prev_30d_value")
    private BigDecimal prev30dValue;

    // Status: ONBOARDED, ACTIVATED, ACTIVE, DORMANT, REACTIVATED
    private String status;

    @Column(name = "status_change_date")
    private LocalDate statusChangeDate;

    @Column(name = "calc_date")
    private LocalDate calcDate;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public LocalDate getFirstTxnDate() {
        return firstTxnDate;
    }

    public void setFirstTxnDate(LocalDate firstTxnDate) {
        this.firstTxnDate = firstTxnDate;
    }

    public LocalDate getLastTxnDate() {
        return lastTxnDate;
    }

    public void setLastTxnDate(LocalDate lastTxnDate) {
        this.lastTxnDate = lastTxnDate;
    }

    public Long getLast7dCount() {
        return last7dCount;
    }

    public void setLast7dCount(Long last7dCount) {
        this.last7dCount = last7dCount;
    }

    public BigDecimal getLast7dValue() {
        return last7dValue;
    }

    public void setLast7dValue(BigDecimal last7dValue) {
        this.last7dValue = last7dValue;
    }

    public Long getLast30dCount() {
        return last30dCount;
    }

    public void setLast30dCount(Long last30dCount) {
        this.last30dCount = last30dCount;
    }

    public BigDecimal getLast30dValue() {
        return last30dValue;
    }

    public void setLast30dValue(BigDecimal last30dValue) {
        this.last30dValue = last30dValue;
    }

    public Long getPrev30dCount() {
        return prev30dCount;
    }

    public void setPrev30dCount(Long prev30dCount) {
        this.prev30dCount = prev30dCount;
    }

    public BigDecimal getPrev30dValue() {
        return prev30dValue;
    }

    public void setPrev30dValue(BigDecimal prev30dValue) {
        this.prev30dValue = prev30dValue;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getStatusChangeDate() {
        return statusChangeDate;
    }

    public void setStatusChangeDate(LocalDate statusChangeDate) {
        this.statusChangeDate = statusChangeDate;
    }

    public LocalDate getCalcDate() {
        return calcDate;
    }

    public void setCalcDate(LocalDate calcDate) {
        this.calcDate = calcDate;
    }
}
