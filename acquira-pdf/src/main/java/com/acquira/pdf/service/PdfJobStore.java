package com.acquira.pdf.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DB copy of the PDF pod's long-running job status (report batches and S3
 * uploads), table {@code pdf_job_status}.
 *
 * The jobs themselves run on threads inside ONE pdf pod and keep their live
 * status in memory there. The UI polls for it, and with more than one pdf
 * replica — or after that pod restarts — the poll lands on a JVM that has
 * never heard of the job. The owning pod therefore writes a snapshot here
 * every few seconds (a heartbeat), and every replica answers status polls
 * from this table when the job is not its own.
 *
 * What this gives:
 *  - any replica can report progress, list jobs and request a cancel;
 *  - a job whose owner died is reported as INTERRUPTED once its heartbeat is
 *    {@link #STALE_SECONDS} old, instead of "RUNNING" forever or a 404.
 * What it does NOT do: resume an interrupted batch. The PDFs already written
 * are on the shared reports volume; re-running the batch regenerates the rest.
 *
 * Never throws: status is advisory, so a DB problem (e.g. migration
 * V2026_10_03_03 not applied) is logged once and the pod falls back to the
 * old in-memory-only behaviour.
 */
@Service
public class PdfJobStore {

    private static final Logger log = LoggerFactory.getLogger(PdfJobStore.class);

    public static final String KIND_BATCH = "BATCH";
    public static final String KIND_S3_UPLOAD = "S3_UPLOAD";

    /** Owner heartbeats every few seconds; this many seconds of silence = owner is gone. */
    static final int STALE_SECONDS = 45;
    private static final int RETENTION_DAYS = 7;

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean warned = new AtomicBoolean();

    public PdfJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert or refresh a job's snapshot (also its heartbeat). */
    public void save(String jobId, String kind, Long tenantId, Map<String, Object> status, boolean finished) {
        try {
            jdbc.update(
                    "INSERT INTO pdf_job_status (job_id, kind, tenant_id, status_json, finished, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                    "ON CONFLICT (job_id) DO UPDATE SET status_json = EXCLUDED.status_json, " +
                    "finished = EXCLUDED.finished, updated_at = CURRENT_TIMESTAMP",
                    jobId, kind, tenantId, mapper.writeValueAsString(status), finished);
            warned.set(false);
        } catch (Exception e) {
            unavailable("save", e);
        }
    }

    /**
     * A job's last snapshot, visible only to the tenant that started it. An
     * unfinished job with a stale heartbeat comes back with phase INTERRUPTED.
     */
    public Optional<Map<String, Object>> find(String jobId, String kind, Long tenantId) {
        if (tenantId == null) return Optional.empty();
        try {
            List<Map<String, Object>> rows = jdbc.query(
                    "SELECT status_json, finished, cancel_requested, " +
                    "(NOT finished AND updated_at < CURRENT_TIMESTAMP - make_interval(secs => ?)) AS stale " +
                    "FROM pdf_job_status WHERE job_id = ? AND kind = ? AND tenant_id = ?",
                    (rs, i) -> view(rs.getString(1), rs.getBoolean(3), rs.getBoolean(4)),
                    STALE_SECONDS, jobId, kind, tenantId);
            return rows.stream().findFirst();
        } catch (Exception e) {
            unavailable("find", e);
            return Optional.empty();
        }
    }

    /** The tenant's unfinished jobs plus those finished in the last {@code recentMinutes}. */
    public List<Map<String, Object>> list(String kind, Long tenantId, int recentMinutes) {
        if (tenantId == null) return List.of();
        try {
            return jdbc.query(
                    "SELECT status_json, finished, cancel_requested, " +
                    "(NOT finished AND updated_at < CURRENT_TIMESTAMP - make_interval(secs => ?)) AS stale " +
                    "FROM pdf_job_status WHERE kind = ? AND tenant_id = ? " +
                    "AND updated_at > CURRENT_TIMESTAMP - make_interval(mins => ?) ORDER BY started_at DESC",
                    (rs, i) -> view(rs.getString(1), rs.getBoolean(3), rs.getBoolean(4)),
                    STALE_SECONDS, kind, tenantId, recentMinutes);
        } catch (Exception e) {
            unavailable("list", e);
            return new ArrayList<>();
        }
    }

    /** Ask the owning replica to stop the job (it picks the flag up on its next heartbeat). */
    public boolean requestCancel(String jobId, Long tenantId) {
        if (tenantId == null) return false;
        try {
            return jdbc.update("UPDATE pdf_job_status SET cancel_requested = TRUE " +
                    "WHERE job_id = ? AND tenant_id = ? AND NOT finished", jobId, tenantId) > 0;
        } catch (Exception e) {
            unavailable("requestCancel", e);
            return false;
        }
    }

    /** Which of these (locally running) jobs has a cancel request pending. */
    public Set<String> cancelRequested(Collection<String> jobIds) {
        Set<String> out = new HashSet<>();
        if (jobIds.isEmpty()) return out;
        try {
            String marks = String.join(",", jobIds.stream().map(j -> "?").toList());
            jdbc.query("SELECT job_id FROM pdf_job_status WHERE cancel_requested AND job_id IN (" + marks + ")",
                    rs -> { out.add(rs.getString(1)); }, jobIds.toArray());
        } catch (Exception e) {
            unavailable("cancelRequested", e);
        }
        return out;
    }

    public void purgeOld() {
        try {
            jdbc.update("DELETE FROM pdf_job_status WHERE updated_at < CURRENT_TIMESTAMP - make_interval(days => ?)",
                    RETENTION_DAYS);
        } catch (Exception e) {
            unavailable("purge", e);
        }
    }

    private Map<String, Object> view(String json, boolean cancelRequested, boolean stale) {
        Map<String, Object> m;
        try {
            m = mapper.readValue(json, MAP);
        } catch (Exception e) {
            m = new LinkedHashMap<>();
            m.put("phase", "UNKNOWN");
        }
        if (cancelRequested) m.put("cancelled", true);
        if (stale) {
            m.put("phase", "INTERRUPTED");
            m.put("message", "The PDF service restarted while this job was running. "
                    + "Files already produced are kept; run it again to finish the rest.");
        }
        return m;
    }

    private void unavailable(String op, Exception e) {
        if (warned.compareAndSet(false, true)) {
            log.warn("pdf_job_status {} failed — PDF job status is in-memory only until this recovers "
                    + "(is migration V2026_10_03_03 applied?): {}", op, e.toString());
        } else {
            log.debug("pdf_job_status {} failed: {}", op, e.toString());
        }
    }
}
