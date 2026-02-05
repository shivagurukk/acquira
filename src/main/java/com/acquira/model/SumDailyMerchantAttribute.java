package com.acquira.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "sum_daily_merchant_attribute", indexes = {
        @Index(name = "idx_sdma_merchant_date", columnList = "merchant_id, business_date"),
        @Index(name = "idx_sdma_attr_type", columnList = "attribute_type")
}, uniqueConstraints = {
        @UniqueConstraint(columnNames = { "tenant_id", "merchant_id", "business_date", "attribute_type",
                "attribute_value" })
})
@Data
public class SumDailyMerchantAttribute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    /**
     * Type of Attribute: 'HOUR', 'CARD_SCHEME', 'Design', 'CARD_TYPE',
     * 'IS_CONTACTLESS', 'ISSUER_COUNTRY'
     */
    @Column(name = "attribute_type", nullable = false, length = 50)
    private String attributeType;

    /**
     * Value of Attribute: '07' (Hour), 'VISA' (Scheme), 'CREDIT' (Type), 'TRUE'
     * (Contactless)
     */
    @Column(name = "attribute_value", nullable = false, length = 100)
    private String attributeValue;

    @Column(name = "metric_count")
    private Long metricCount = 0L;

    @Column(name = "metric_volume")
    private BigDecimal metricVolume = BigDecimal.ZERO;

    // Optional: Add average or other stats if needed, but Count/Volume covers most
    // charts

    @Version
    private Long version;
}
