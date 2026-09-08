package com.acquira.core.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Buffers API-key usage telemetry off the request thread.
 *
 * WHY: ApiKeyAuthFilter used to do TWO synchronous statements per request — an
 * INSERT into api_request_log and
 * {@code UPDATE api_key SET request_count = request_count + 1 WHERE key_id = ?}.
 * Beyond the two round trips, that UPDATE takes a row lock on the key's row, so
 * every concurrent request presenting the SAME key serialized behind it: a
 * single integration could not exceed one in-flight write at a time no matter
 * how much hardware sat underneath.
 *
 * NOW: the request thread only touches in-memory structures (a counter merge
 * and a queue offer). A flusher collapses N requests per key into ONE update
 * carrying the accumulated delta, and writes the log rows as a single JDBC
 * batch every {@link #FLUSH_INTERVAL_MS}.
 *
 * OWN SCHEDULER, NOT @Scheduled: the merged core process has ONE shared
 * TaskScheduler serving every @Scheduled bean, including multi-minute jobs
 * (the 2AM DB pull, churn retrain, DB maintenance). A starved shared thread
 * would stop this flush exactly when traffic is heaviest and the queue is
 * filling — the moment an auditor would want api_request_log complete. A
 * dedicated daemon thread cannot be starved by them.
 *
 * ACCEPTED TRADE-OFFS (this data is telemetry, and the original call site was
 * already documented "best-effort; never fails the request"):
 *   - api_key.last_used / request_count lag by up to one flush interval, and
 *     last_used_ip is last-writer-wins among the interval's callers.
 *   - A hard kill (SIGKILL / pod eviction) loses at most one interval of rows;
 *     @PreDestroy covers ordinary shutdown.
 *   - The MAX_QUEUED bound is enforced approximately under concurrency (the
 *     count can transiently overshoot by the number of in-flight requests);
 *     a DB stall still cannot exhaust heap.
 *
 * Single-replica safe by construction; when the app is split into multiple
 * replicas each one flushes its own deltas, which stays correct because the
 * UPDATE applies a relative delta rather than an absolute count.
 */
@Component
public class ApiUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(ApiUsageRecorder.class);

    private static final long FLUSH_INTERVAL_MS = 30_000;
    /** Ceiling on buffered log rows; oldest are dropped past this. */
    private static final int MAX_QUEUED = 20_000;

    private final JdbcTemplate jdbc;

    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "api-usage-flush");
        t.setDaemon(true);
        return t;
    });

    public ApiUsageRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        flusher.scheduleWithFixedDelay(this::flush,
                FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Accumulated per-key counters between flushes. */
    private static final class KeyUsage {
        final AtomicLong count = new AtomicLong();
        volatile String lastIp;
        /** Consecutive flushes seen with no traffic — reaped at 2. */
        int idleFlushes;
        /** Set by the flusher just before removal from the map — see record(). */
        volatile boolean retired;
    }

    /** One buffered api_request_log row. */
    record LogRow(Long tenantId, Long keyId, String method, String endpoint,
                  int status, String clientIp, int latencyMs) {}

    private final Map<Long, KeyUsage> keyUsage = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<LogRow> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingSize = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();

    /**
     * Called on the request thread. Must never block or throw.
     */
    public void record(Long tenantId, Long keyId, String method, String endpoint,
                       int status, String clientIp, long latencyMs) {
        try {
            // Reserve the slot BEFORE adding so pendingSize is never behind the
            // real queue length; shed oldest when over the cap.
            if (pendingSize.incrementAndGet() > MAX_QUEUED) {
                if (pending.poll() != null) {
                    pendingSize.decrementAndGet();
                    dropped.incrementAndGet();
                } else {
                    pendingSize.decrementAndGet();
                }
            }
            pending.add(new LogRow(tenantId, keyId, method, endpoint, status, clientIp, (int) latencyMs));

            if (keyId != null) {
                bumpKey(keyId, clientIp);
            }
        } catch (RuntimeException e) {
            log.debug("[API-AUTH] usage buffering skipped: {}", e.getMessage());
        }
    }

    private void bumpKey(Long keyId, String clientIp) {
        KeyUsage u = keyUsage.computeIfAbsent(keyId, k -> new KeyUsage());
        u.count.incrementAndGet();
        u.lastIp = clientIp;
        // Reaper race: the flusher may have marked this instance retired and
        // removed it from the map between our computeIfAbsent and increment.
        // If so, move our contribution onto a live instance — without this the
        // increment lands on an unreachable object and request_count undercounts.
        if (u.retired) {
            long stranded = u.count.getAndSet(0);
            if (stranded > 0) {
                KeyUsage live = keyUsage.computeIfAbsent(keyId, k -> new KeyUsage());
                live.count.addAndGet(stranded);
                live.lastIp = clientIp;
            }
        }
    }

    void flush() {
        try {
            flushLogRows();
            flushKeyCounters();
        } catch (RuntimeException e) {
            // Telemetry must never escalate into an application error.
            log.warn("[API-AUTH] usage flush failed: {}", e.getMessage());
        }
        long lost = dropped.getAndSet(0);
        if (lost > 0) {
            log.warn("[API-AUTH] dropped {} buffered api_request_log rows (queue full at {})", lost, MAX_QUEUED);
        }
    }

    private void flushLogRows() {
        List<LogRow> batch = new ArrayList<>();
        LogRow row;
        while ((row = pending.poll()) != null) {
            pendingSize.decrementAndGet();
            batch.add(row);
        }
        if (batch.isEmpty()) return;

        jdbc.batchUpdate(
            "INSERT INTO api_request_log (tenant_id, key_id, method, endpoint, status, client_ip, latency_ms) " +
            "VALUES (?,?,?,?,?,?,?)",
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    LogRow r = batch.get(i);
                    if (r.tenantId() == null) ps.setNull(1, java.sql.Types.BIGINT);
                    else ps.setLong(1, r.tenantId());
                    if (r.keyId() == null) ps.setNull(2, java.sql.Types.BIGINT);
                    else ps.setLong(2, r.keyId());
                    ps.setString(3, r.method());
                    ps.setString(4, r.endpoint());
                    ps.setInt(5, r.status());
                    ps.setString(6, r.clientIp());
                    ps.setInt(7, r.latencyMs());
                }

                @Override
                public int getBatchSize() {
                    return batch.size();
                }
            });
    }

    private void flushKeyCounters() {
        for (Map.Entry<Long, KeyUsage> e : keyUsage.entrySet()) {
            Long keyId = e.getKey();
            KeyUsage u = e.getValue();
            long delta = u.count.getAndSet(0);
            if (delta == 0) {
                // Reap only after TWO consecutive idle flushes, and mark the
                // instance retired BEFORE removing it so a racing request can
                // detect the removal and re-home its count (see bumpKey).
                if (++u.idleFlushes >= 2) {
                    u.retired = true;
                    keyUsage.remove(keyId, u);
                    long stranded = u.count.getAndSet(0);
                    if (stranded > 0) {
                        keyUsage.computeIfAbsent(keyId, k -> new KeyUsage()).count.addAndGet(stranded);
                    }
                }
                continue;
            }
            u.idleFlushes = 0;
            try {
                jdbc.update(
                    "UPDATE api_key SET last_used = CURRENT_TIMESTAMP, last_used_ip = ?, " +
                    "request_count = COALESCE(request_count,0) + ? WHERE key_id = ?",
                    u.lastIp, delta, keyId);
            } catch (RuntimeException ex) {
                // Put the delta back so it is retried on the next flush.
                u.count.addAndGet(delta);
                log.debug("[API-AUTH] counter flush skipped for key {}: {}", keyId, ex.getMessage());
            }
        }
    }

    /** Ordinary shutdown: drain what is buffered rather than discarding it. */
    @PreDestroy
    public void shutdown() {
        flusher.shutdown();
        flush();
    }
}
