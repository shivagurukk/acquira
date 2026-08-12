package com.acquira.core.service;

import com.acquira.common.service.CryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes the {@code email_queue} table — asynchronous, retryable email
 * delivery.
 *
 * Polls for PENDING rows and sends each via the relevant tenant's active SMTP
 * config ({@code email_smtp_config}). On failure a row's retry_count is
 * incremented; once it reaches MAX_RETRIES the row is marked FAILED.
 *
 * THROUGHPUT: a cycle claims up to {@link #BATCH_SIZE} rows and sends them on
 * {@link #CONCURRENCY} thread(s), paced to at most {@link #RATE_PER_SECOND}
 * messages so we stay under the provider's quota.
 *
 * SIZING FOR MERCHANT STATEMENTS: the statement PDFs run ~2 MB each, which
 * base64-encodes to ~2.7 MB on the wire, and every send opens its own SMTP
 * connection (TLS handshake + upload). Single-threaded that lands around
 * 2-3 msg/sec, so an 11k statement run takes roughly 1-2 hours and the
 * RATE_PER_SECOND cap never actually binds — it only exists to stop a future
 * CONCURRENCY increase from blowing the SES quota. Expect the observed msg/sec
 * in the cycle summary to sit well below the cap; that is the connection and
 * upload cost per message, not a queue problem.
 *
 * These are compile-time constants by design — delivery rate is tied to the SES
 * quota and the pod's CPU limit, not to per-environment config.
 *
 * SINGLE INSTANCE ONLY: rows are selected, not claimed, so two replicas would
 * both pick up the same batch and deliver every email twice. acquira-core is
 * pinned to replicas: 1 for this reason (see deploy/k8s/05-core.yaml). Before
 * scaling out, this SELECT needs a FOR UPDATE SKIP LOCKED claim.
 *
 * SMTP RESOLUTION: the sender is built per row from {@code email_smtp_config}
 * (the active config for the row's tenant). The stored SMTP password is
 * AES-256-GCM encrypted and is decrypted here via {@link CryptoService}. This
 * keeps the queue consistent with EmailService / CampaignExecutionService —
 * all three now use the same encryption-aware, per-tenant SMTP source.
 *
 * Senders are cached per tenant for the duration of one poll cycle so a batch
 * of emails for the same tenant doesn't rebuild the sender each time.
 */
@Service
public class EmailQueueProcessor {

    private static final Logger log = LoggerFactory.getLogger(EmailQueueProcessor.class);
    private static final int MAX_RETRIES = 3;

    /** Rows claimed per poll cycle. */
    private static final int BATCH_SIZE = 500;

    /**
     * Threads sending concurrently within a cycle. Pinned to 1: this pod shares
     * a 2-core limit with Chromium (PDF rendering) and the batch jobs, so extra
     * sender threads would take CPU from generation rather than add throughput.
     * Raise only if the CPU limit in deploy/k8s/05-core.yaml goes up.
     */
    private static final int CONCURRENCY = 1;

    /**
     * Global ceiling on messages handed to SMTP per second, across all threads.
     * Amazon SES production allows ~14/sec; 12 leaves headroom for retries.
     * SES sandbox is 1/sec (and verified recipients only) — drop this to 1 and
     * CONCURRENCY to 1 if this deployment is still in the sandbox.
     */
    private static final double RATE_PER_SECOND = 12.0;

    /** Gap between poll cycles. A cycle blocks until drained, so cycles never overlap. */
    private static final long POLL_INTERVAL_MS = 10_000L;

    private final JdbcTemplate jdbc;
    private final CryptoService cryptoService;

    /** Lazily created on the first non-empty cycle; shut down with the context. */
    private volatile ExecutorService senderPool;

    /** Guards {@link #nextSendAtNanos} — the global send pacer. */
    private final Object paceLock = new Object();
    private long nextSendAtNanos = 0L;

    public EmailQueueProcessor(JdbcTemplate jdbcTemplate, CryptoService cryptoService) {
        this.jdbc = jdbcTemplate;
        this.cryptoService = cryptoService;
    }

    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void processQueue() {
        List<Map<String, Object>> pending;
        try {
            pending = jdbc.queryForList(
                "SELECT id, recipient, subject, body, is_html, attachment_path, retry_count, tenant_id " +
                "FROM email_queue WHERE status = 'PENDING' AND retry_count < ? " +
                "ORDER BY created_at LIMIT ?",
                MAX_RETRIES, BATCH_SIZE);
        } catch (Exception e) {
            // Table missing or DB unavailable — log once and skip this cycle.
            log.debug("[EMAIL-QUEUE] Skipped — could not read email_queue: {}", e.getMessage());
            return;
        }

        if (pending.isEmpty()) return;

        long t0 = System.currentTimeMillis();
        log.info("[EMAIL-QUEUE] Processing {} pending emails ({} threads, {}/sec cap)",
                pending.size(), CONCURRENCY, RATE_PER_SECOND);

        // One sender per tenant, shared across the cycle's threads. Optional-wrapped
        // because a tenant with no active SMTP config resolves to null, and a
        // ConcurrentHashMap cannot store a null value (it would rebuild every row).
        Map<Long, Optional<SmtpContext>> senderCache = new ConcurrentHashMap<>();
        AtomicInteger sent = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        ExecutorService pool = senderPool();
        CountDownLatch done = new CountDownLatch(pending.size());
        for (Map<String, Object> row : pending) {
            pool.execute(() -> {
                try {
                    if (deliver(row, senderCache)) sent.incrementAndGet();
                    else failed.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            // Block until the cycle finishes so fixedDelay can't overlap cycles and
            // re-select rows that are still in flight (which would double-send them).
            done.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[EMAIL-QUEUE] Interrupted while draining cycle");
            return;
        }

        long secs = Math.max(1, (System.currentTimeMillis() - t0) / 1000);
        log.info("[EMAIL-QUEUE] Cycle done — sent:{} failed:{} in {}s ({} msg/sec)",
                sent.get(), failed.get(), secs, (sent.get() + failed.get()) / secs);
    }

    /** Send one queue row. Returns true if delivered, false if failed/retried. */
    private boolean deliver(Map<String, Object> row, Map<Long, Optional<SmtpContext>> senderCache) {
        Long id = ((Number) row.get("id")).longValue();
        String recipient = (String) row.get("recipient");
        String subject = (String) row.get("subject");
        String body = (String) row.get("body");
        String attachmentPath = (String) row.get("attachment_path");
        int retryCount = ((Number) row.get("retry_count")).intValue();
        boolean isHtml = !Boolean.FALSE.equals(row.get("is_html"));
        Long tenantId = row.get("tenant_id") != null
                ? ((Number) row.get("tenant_id")).longValue() : null;

        try {
            SmtpContext smtp = resolveSender(senderCache, tenantId);
            if (smtp == null) {
                // No SMTP configured for this tenant — fail the row with a clear reason.
                markFailedOrRetry(id, retryCount, "No active SMTP config for tenant " + tenantId);
                return false;
            }

            File file = null;
            if (attachmentPath != null) {
                file = new File(attachmentPath);
                if (!file.exists()) {
                    // The body promises an attached PDF — never deliver it without one.
                    // Treat a missing attachment as a retryable failure rather than send an
                    // empty "please find attached" email and (worse) mark it SENT.
                    log.warn("[EMAIL-QUEUE] Attachment missing for email #{} ({}) — not sending, will retry",
                            id, attachmentPath);
                    markFailedOrRetry(id, retryCount, "Attachment file not found: " + attachmentPath);
                    return false;
                }
            }

            MimeMessage message = smtp.sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, file != null, "UTF-8");
            helper.setTo(recipient);
            if (smtp.fromAddress != null) helper.setFrom(smtp.fromAddress);
            helper.setSubject(subject != null ? subject : "Notification");
            helper.setText(body != null ? body : "", isHtml);
            if (file != null) helper.addAttachment(file.getName(), file);

            // Pace AFTER the message is built (reading the PDF off disk costs no quota)
            // and immediately before the connection, so the cap tracks real send rate.
            awaitSendSlot();
            smtp.sender.send(message);

            jdbc.update("UPDATE email_queue SET status = 'SENT', sent_at = NOW(), error_message = NULL WHERE id = ?", id);
            log.debug("[EMAIL-QUEUE] Sent email #{} to {}{}", id, recipient,
                    tenantId != null ? " (tenant=" + tenantId + ")" : "");
            return true;

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("[EMAIL-QUEUE] Failed email #{} to {}: {}", id, recipient, e.getMessage());
            markFailedOrRetry(id, retryCount, e.getMessage());
            return false;
        }
    }

    /** Cached per-tenant sender lookup. Null tenant never resolves — see buildSmtpContext. */
    private SmtpContext resolveSender(Map<Long, Optional<SmtpContext>> cache, Long tenantId) {
        if (tenantId == null) {
            // ConcurrentHashMap rejects null keys, and a null tenant has no config anyway.
            return buildSmtpContext(null);
        }
        return cache.computeIfAbsent(tenantId, t -> Optional.ofNullable(buildSmtpContext(t)))
                    .orElse(null);
    }

    /**
     * Block until this thread's turn under the global rate cap. Slots are handed
     * out on a shared timeline so N threads together never exceed the configured
     * messages/second, no matter how fast any one send returns.
     */
    private void awaitSendSlot() throws InterruptedException {
        long intervalNanos = (long) (1_000_000_000L / RATE_PER_SECOND);
        long waitNanos;
        synchronized (paceLock) {
            long now = System.nanoTime();
            if (nextSendAtNanos < now) nextSendAtNanos = now;
            waitNanos = nextSendAtNanos - now;
            nextSendAtNanos += intervalNanos;
        }
        if (waitNanos > 0) TimeUnit.NANOSECONDS.sleep(waitNanos);
    }

    private ExecutorService senderPool() {
        ExecutorService pool = senderPool;
        if (pool == null) {
            synchronized (this) {
                pool = senderPool;
                if (pool == null) {
                    pool = Executors.newFixedThreadPool(CONCURRENCY, r -> {
                        Thread t = new Thread(r, "email-queue-sender");
                        t.setDaemon(true);
                        return t;
                    });
                    senderPool = pool;
                }
            }
        }
        return pool;
    }

    @PreDestroy
    void shutdown() {
        ExecutorService pool = senderPool;
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) pool.shutdownNow();
            } catch (InterruptedException ie) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Increment retry_count; mark FAILED once MAX_RETRIES is reached, else leave PENDING. */
    private void markFailedOrRetry(Long id, int currentRetry, String error) {
        int next = currentRetry + 1;
        jdbc.update(
            "UPDATE email_queue SET retry_count = ?, error_message = ?, " +
            "status = CASE WHEN ? >= ? THEN 'FAILED' ELSE 'PENDING' END WHERE id = ?",
            next, error, next, MAX_RETRIES, id);
    }

    /** Holds a built sender plus the from-address for a tenant. */
    private static class SmtpContext {
        final JavaMailSenderImpl sender;
        final String fromAddress;
        SmtpContext(JavaMailSenderImpl sender, String fromAddress) {
            this.sender = sender;
            this.fromAddress = fromAddress;
        }
    }

    /**
     * Build an SMTP sender from the active email_smtp_config for a tenant.
     * Returns null if the tenant has no active config. The stored password is
     * AES-256-GCM encrypted and is decrypted here.
     */
    private SmtpContext buildSmtpContext(Long tenantId) {
        if (tenantId == null) {
            // No tenant on the row → do NOT fall back to "any active config": that would
            // let a queued email be delivered through an arbitrary tenant's SMTP server.
            // Every enqueue path tags tenant_id, so a null here is a bug to surface, not
            // paper over. The row is failed/retried with a clear reason by the caller.
            log.warn("[EMAIL-QUEUE] Queue row has no tenant_id — refusing to pick an arbitrary SMTP config");
            return null;
        }
        try {
            String sql = "SELECT host, port, username, password, auth_enabled, starttls_enabled, " +
                    "ssl_enabled, from_address, connection_timeout, read_timeout, write_timeout " +
                    "FROM email_smtp_config WHERE is_active = true AND tenant_id = ? LIMIT 1";
            Map<String, Object> cfg = jdbc.queryForMap(sql, tenantId);

            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost((String) cfg.get("host"));
            sender.setPort(((Number) cfg.get("port")).intValue());
            sender.setDefaultEncoding("UTF-8");

            String username = (String) cfg.get("username");
            if (username != null && !username.isEmpty()) {
                sender.setUsername(username);
                // Stored encrypted ("enc:v1:" token) — decrypt before use.
                sender.setPassword(cryptoService.decrypt((String) cfg.get("password")));
            }

            Properties props = sender.getJavaMailProperties();
            props.put("mail.transport.protocol", "smtp");
            props.put("mail.smtp.auth", String.valueOf(cfg.get("auth_enabled")));
            props.put("mail.smtp.starttls.enable", String.valueOf(cfg.get("starttls_enabled")));
            if (Boolean.TRUE.equals(cfg.get("ssl_enabled"))) {
                props.put("mail.smtp.ssl.enable", "true");
            }
            if (cfg.get("connection_timeout") != null)
                props.put("mail.smtp.connectiontimeout", cfg.get("connection_timeout").toString());
            if (cfg.get("read_timeout") != null)
                props.put("mail.smtp.timeout", cfg.get("read_timeout").toString());
            if (cfg.get("write_timeout") != null)
                props.put("mail.smtp.writetimeout", cfg.get("write_timeout").toString());

            String from = (String) cfg.get("from_address");
            return new SmtpContext(sender, from);
        } catch (org.springframework.dao.EmptyResultDataAccessException none) {
            log.warn("[EMAIL-QUEUE] No active SMTP config for tenant {}", tenantId);
            return null;
        } catch (Exception e) {
            log.error("[EMAIL-QUEUE] Failed to build SMTP sender for tenant {}: {}", tenantId, e.getMessage());
            return null;
        }
    }
}
