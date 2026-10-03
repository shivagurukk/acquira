package com.acquira.core.config;

import com.acquira.common.config.LoadInRoles;
import com.acquira.common.config.PodRoleFilter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Readiness check for the tables the pods coordinate through
 * ({@code report_cache_version} for ReportCacheSync, {@code event_outbox} for
 * EventOutbox/EventOutboxRelay — migration V2026_10_03_01; {@code shedlock}
 * for the scheduler locks — V2026_10_03_02).
 *
 * Without them a split deployment degrades SILENTLY: batch ingests stop
 * clearing core/pdf report caches and failure-alert emails / webhooks vanish,
 * each with a single WARN line. Failing readiness turns that into a pod that
 * never comes up, which is visible. In role "all" (one JVM) the tables are not
 * needed — the in-process fallbacks are correct — so the check always passes.
 *
 * Wired into the readiness group via
 * {@code management.endpoint.health.group.readiness.include} in
 * application.properties; the bean name is "crossPodSchema". Lives in core
 * (actuator is on core's classpath only) and opts into the other roles.
 */
@LoadInRoles({"pdf", "batch"})
@Component("crossPodSchema")
public class CrossPodSchemaHealthIndicator implements HealthIndicator {

    private static final List<String> TABLES = List.of("report_cache_version", "event_outbox", "shedlock");

    private final JdbcTemplate jdbc;
    private final String role;

    public CrossPodSchemaHealthIndicator(JdbcTemplate jdbc,
            @Value("${" + PodRoleFilter.ROLE_PROPERTY + ":" + PodRoleFilter.ROLE_ALL + "}") String role) {
        this.jdbc = jdbc;
        this.role = role.trim().toLowerCase();
    }

    @Override
    public Health health() {
        if (PodRoleFilter.ROLE_ALL.equals(role)) {
            return Health.up().withDetail("role", role).withDetail("note", "single JVM, tables not required").build();
        }
        List<String> missing = new ArrayList<>();
        try {
            for (String table : TABLES) {
                Boolean exists = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table);
                if (!Boolean.TRUE.equals(exists)) missing.add(table);
            }
        } catch (Exception e) {
            return Health.down(e).withDetail("role", role).build();
        }
        if (missing.isEmpty()) return Health.up().withDetail("role", role).build();
        return Health.down()
                .withDetail("role", role)
                .withDetail("missingTables", missing)
                .withDetail("fix", "apply db/migration V2026_10_03_01 (cache version + event outbox) and V2026_10_03_02 (shedlock)")
                .build();
    }
}
