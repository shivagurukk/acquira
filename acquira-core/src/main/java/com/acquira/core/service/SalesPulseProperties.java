package com.acquira.core.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunable thresholds for the Executive Sales Pulse classifier.
 *
 * <p>These are judgement calls about what "accelerating" and "needs attention"
 * mean for a given book of business, and they will be wrong for somebody. They
 * live in configuration so a tenant's thresholds can be adjusted from
 * {@code application-core.properties} without a code change:
 *
 * <pre>
 * acquira.sales-pulse.accelerating-growth-pct=15
 * acquira.sales-pulse.attention-ratio=0.70
 * acquira.sales-pulse.high-dependency-pct=50
 * </pre>
 *
 * <p>The defaults are the values in the product spec. Changing them changes how
 * people are labelled on an executive screen — treat that as a business decision,
 * not a tuning knob.
 */
@Component
@ConfigurationProperties(prefix = "acquira.sales-pulse")
@Data
public class SalesPulseProperties {

    /** How many complete months of history the momentum window spans. */
    private int momentumMonths = 6;

    /**
     * Minimum months of history before momentum is classified at all. Below this
     * an agent is NEW — an agent with one month has no trend, and pretending
     * otherwise labels every new hire "Attention" in their second month.
     */
    private int minMonthsForMomentum = 3;

    /** Growth at or above this (%) — with an upward trend — is ACCELERATING. */
    private double acceleratingGrowthPct = 15.0;

    /** Current sales at or above recentAverage x this is STRONG. */
    private double strongRatio = 1.10;

    /** Within +/- this fraction of the recent average is STABLE. */
    private double stableBandPct = 10.0;

    /** Growth below this (%) is SLOWING. */
    private double slowingGrowthPct = -10.0;

    /** Consecutive declining months that trigger SLOWING. */
    private int slowingDeclineStreak = 2;

    /** Current sales below recentAverage x this is ATTENTION. */
    private double attentionRatio = 0.70;

    /** Consecutive declining months that trigger ATTENTION. */
    private int attentionDeclineStreak = 3;

    /** Below recentAverage x this earns the "Below Personal Average" signal. */
    private double belowAverageRatio = 0.90;

    /** Team contribution (%) above which a team is MODERATE dependency. */
    private double moderateDependencyPct = 40.0;

    /** Team contribution (%) above which a team is HIGH dependency. */
    private double highDependencyPct = 50.0;

    /**
     * Target attainment (%) below which an agent WITH a target is pushed to
     * ATTENTION. Only ever applied when a target exists — a missing target must
     * never influence classification.
     */
    private double attentionAttainmentPct = 60.0;
}
