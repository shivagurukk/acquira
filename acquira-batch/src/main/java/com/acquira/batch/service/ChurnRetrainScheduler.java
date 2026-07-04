package com.acquira.batch.service;

import com.acquira.common.model.Tenant;
import com.acquira.common.repository.TenantRepository;
import com.acquira.common.service.ChurnScoringService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/**
 * Weekly churn-model retrainer — decoupled from ingestion.
 *
 * WHY separate from the batch: scoring runs on every upload (cheap), but TRAINING
 * only needs to happen periodically, and running it inside the ingestion path would
 * add an extra sum_daily_merchant scan + gradient-descent pass to every upload. This
 * moves training entirely off the ingestion path so uploads are never slowed: the
 * batch scoreMlStep keeps scoring with the latest features against the last-trained
 * weights; this job refreshes those weights once a week.
 *
 * Schedule: Saturday 21:00 (9 PM), server-local time. Chosen to sit outside the
 * usual weekday ingestion / statement / campaign windows.
 *
 * Single-replica safety: replicas: 1 is deliberate for this platform, but this job
 * additionally takes a Postgres advisory lock so that even under an accidental second
 * process only ONE retrain runs. The lock, all work, and the unlock run on ONE held
 * JDBC connection (pg advisory locks are session-scoped — acquiring and releasing on
 * different pooled connections would leak the lock). Per-tenant work is independently
 * try/caught so one tenant's failure never aborts the rest. retrain() is itself
 * exception-safe and leaves the existing model untouched on failure.
 *
 * Toggle: acquira.ml.churn.retrain-enabled (default true); cron overridable via
 * acquira.ml.churn.retrain-cron — both without a code change.
 */
@Component
@Slf4j
public class ChurnRetrainScheduler {

    private final TenantRepository tenantRepo;
    private final ChurnScoringService churnScoringService;
    private final DataSource dataSource;

    @Value("${acquira.ml.churn.retrain-enabled:true}")
    private boolean enabled;

    // Advisory-lock key dedicated to churn retraining (distinct from the
    // 11_000_000 + tenantId space used by populateSummary).
    private static final long RETRAIN_LOCK_KEY = 22_000_001L;

    public ChurnRetrainScheduler(TenantRepository tenantRepo,
                                 ChurnScoringService churnScoringService,
                                 DataSource dataSource) {
        this.tenantRepo = tenantRepo;
        this.churnScoringService = churnScoringService;
        this.dataSource = dataSource;
    }

    /**
     * Weekly retrain — Saturday 21:00 server-local. Cron overridable via
     * acquira.ml.churn.retrain-cron.
     */
    @Scheduled(cron = "${acquira.ml.churn.retrain-cron:0 0 21 * * SAT}")
    public void weeklyRetrain() {
        if (!enabled) {
            log.info("[ChurnRetrain] Disabled via acquira.ml.churn.retrain-enabled=false — skipping");
            return;
        }
        runRetrainAllTenants("SCHEDULED");
    }

    /**
     * Manual trigger (e.g. from an admin action or ops). Same body as the weekly
     * run; safe to call any time. Returns the number of tenants for which a model
     * was (re)produced.
     */
    public int retrainNow() {
        return runRetrainAllTenants("MANUAL");
    }

    private int runRetrainAllTenants(String trigger) {
        long start = System.currentTimeMillis();

        Connection lockConn = null;
        boolean locked = false;
        try {
            lockConn = dataSource.getConnection();
            lockConn.setAutoCommit(true);
            locked = tryAdvisoryLock(lockConn);
            if (!locked) {
                log.info("[ChurnRetrain] Another retrain holds the lock — skipping this {} run", trigger);
                return 0;
            }

            int trained = 0, tenantsSeen = 0;
            List<Tenant> tenants;
            try {
                tenants = tenantRepo.findAll();
            } catch (Exception e) {
                log.warn("[ChurnRetrain] Could not list tenants (non-fatal): {}", e.getMessage());
                return 0;
            }

            for (Tenant t : tenants) {
                Long tenantId = t.getTenantId();
                if (tenantId == null) continue;
                // Skip inactive tenants — nothing to retrain, avoids wasted scans.
                if (t.getStatus() != null && "INACTIVE".equalsIgnoreCase(t.getStatus())) continue;
                tenantsSeen++;
                try {
                    boolean ok = churnScoringService.retrain(tenantId);
                    if (ok) trained++;
                    log.info("[ChurnRetrain] tenant {} retrain: {}", tenantId,
                            ok ? "MODEL updated" : "kept prior/heuristic (insufficient data)");
                } catch (Exception e) {
                    // One tenant's failure must never abort the rest.
                    log.warn("[ChurnRetrain] tenant {} retrain failed (non-fatal): {}", tenantId, e.toString());
                }
            }
            log.info("[ChurnRetrain] {} run complete — {}/{} tenants produced a model in {}s",
                    trigger, trained, tenantsSeen,
                    String.format("%.1f", (System.currentTimeMillis() - start) / 1000.0));
            return trained;
        } catch (Exception e) {
            log.warn("[ChurnRetrain] {} run failed to start (non-fatal): {}", trigger, e.toString());
            return 0;
        } finally {
            if (lockConn != null) {
                if (locked) releaseAdvisoryLock(lockConn);
                try { lockConn.close(); } catch (Exception ignore) { /* return to pool */ }
            }
        }
    }

    // ── advisory lock helpers — same connection for lock + unlock ──

    private boolean tryAdvisoryLock(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, RETRAIN_LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (Exception e) {
            // If the lock mechanism itself errors, proceed rather than never running.
            log.warn("[ChurnRetrain] advisory lock acquire failed (proceeding without lock): {}", e.getMessage());
            return true;
        }
    }

    private void releaseAdvisoryLock(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, RETRAIN_LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) rs.getBoolean(1); }
        } catch (Exception e) {
            log.warn("[ChurnRetrain] advisory unlock failed (non-fatal): {}", e.getMessage());
        }
    }
}
