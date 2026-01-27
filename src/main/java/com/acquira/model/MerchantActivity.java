package com.acquira.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Entity
@Table(name = "merchant_activity")
@Data
public class MerchantActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long activityId;

    @Column(name = "tenant_id")
    private Integer tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    private LocalDate lastTxnDate;
    private Integer daysSinceLastTxn;
    private String status; // ACTIVE, DORMANT, CHURNED
}
