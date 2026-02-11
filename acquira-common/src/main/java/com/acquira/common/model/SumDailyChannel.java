package com.acquira.common.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "sum_daily_channel")
@Data
public class SumDailyChannel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "summary_id")
    private Long summaryId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "business_date")
    private LocalDate businessDate;

    @Column(name = "channel")
    private String channel;

    @Column(name = "total_txns")
    private Long totalTxns;

    @Column(name = "total_volume")
    private BigDecimal totalVolume;

    @Column(name = "total_msf")
    private BigDecimal totalMsf;

    @Column(name = "total_interchange")
    private BigDecimal totalInterchange;

    @Column(name = "total_scheme_fee")
    private BigDecimal totalSchemeFee;

    @Column(name = "total_net_revenue")
    private BigDecimal totalNetRevenue;

    public Long getSummaryId() {
        return summaryId;
    }

    public void setSummaryId(Long summaryId) {
        this.summaryId = summaryId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(LocalDate businessDate) {
        this.businessDate = businessDate;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public Long getTotalTxns() {
        return totalTxns;
    }

    public void setTotalTxns(Long totalTxns) {
        this.totalTxns = totalTxns;
    }

    public BigDecimal getTotalVolume() {
        return totalVolume;
    }

    public void setTotalVolume(BigDecimal totalVolume) {
        this.totalVolume = totalVolume;
    }

    public BigDecimal getTotalMsf() {
        return totalMsf;
    }

    public void setTotalMsf(BigDecimal totalMsf) {
        this.totalMsf = totalMsf;
    }

    public BigDecimal getTotalInterchange() {
        return totalInterchange;
    }

    public void setTotalInterchange(BigDecimal totalInterchange) {
        this.totalInterchange = totalInterchange;
    }

    public BigDecimal getTotalSchemeFee() {
        return totalSchemeFee;
    }

    public void setTotalSchemeFee(BigDecimal totalSchemeFee) {
        this.totalSchemeFee = totalSchemeFee;
    }

    public BigDecimal getTotalNetRevenue() {
        return totalNetRevenue;
    }

    public void setTotalNetRevenue(BigDecimal totalNetRevenue) {
        this.totalNetRevenue = totalNetRevenue;
    }
}
