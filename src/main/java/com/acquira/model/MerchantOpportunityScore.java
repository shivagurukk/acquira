package com.acquira.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.EqualsAndHashCode;
import java.time.LocalDate;

@Entity
@Table(name = "merchant_opportunity_score")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class MerchantOpportunityScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "score_id")
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    private Integer score;

    @Column(name = "reason_tags", length = 500)
    private String reasonTags;

    @Column(name = "calc_date")
    private LocalDate calcDate;
}
