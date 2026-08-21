package com.acquira.core.service;

import com.acquira.common.model.EmailSmtpConfig;
import com.acquira.common.repository.EmailSmtpConfigRepository;
import com.acquira.common.service.CryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Manages per-tenant {@link EmailSmtpConfig} records: CRUD, at-rest password
 * encryption, single-active enforcement, building a JavaMailSender from a
 * config, and connection testing.
 *
 * Encryption: the SMTP password is encrypted with {@link CryptoService}
 * (AES-256-GCM) before persistence and decrypted only in-memory when a
 * JavaMailSender is built. It is NEVER returned to the API client \u2014
 * {@link #maskSecrets(EmailSmtpConfig)} returns a DETACHED copy with the
 * password replaced by a sentinel on every read path.
 *
 * CRITICAL BUG FIX (2026-07 — "test succeeds before save, 535 after save"):
 * The previous stripSecrets() mutated the password field on the MANAGED JPA
 * entity returned from create()/update()/activate(). Because those methods are
 * @Transactional, Hibernate dirty-checking then flushed the sentinel
 * ("__UNCHANGED__") to the DB on commit — overwriting the freshly-encrypted
 * password. On the next send/test the stored value "__UNCHANGED__" was returned
 * verbatim by decrypt() (no enc: prefix) and handed to SES as the password →
 * 535. The pre-save test never persisted, so it passed, producing the exact
 * "works before save, fails after" symptom. The fix: NEVER mutate the managed
 * entity — mask onto a detached copy ({@link #maskSecrets}).
 *
 * DIAGNOSTIC LOGGING (SES 535): buildMailSender()/runTest() log host/port/TLS
 * mode, username, and a NON-REVERSIBLE fingerprint (length + first/last char) of
 * the decrypted password. The actual password is NEVER logged.
 *
 * PRE-SAVE TEST: {@link #testRawConfig(Long, EmailSmtpConfig)} validates form
 * credentials before persisting, without writing anything.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SmtpConfigService {

    private final EmailSmtpConfigRepository repository;
    private final CryptoService cryptoService;

    /** Sentinel the API layer sends back when the user did NOT change the password. */
    public static final String UNCHANGED_PASSWORD_TOKEN = "__UNCHANGED__";

    // ── Read ────────────────────────────────────────────────────────────────

    /** All configs for a tenant, with passwords masked (detached copies) for safe client display. */
    public List<EmailSmtpConfig> listForTenant(Long tenantId) {
        List<EmailSmtpConfig> configs = repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        List<EmailSmtpConfig> out = new ArrayList<>(configs.size());
        for (EmailSmtpConfig c : configs) out.add(maskSecrets(c));
        return out;
    }

    public Optional<EmailSmtpConfig> getActiveRaw(Long tenantId) {
        return repository.findByTenantIdAndIsActiveTrue(tenantId);
    }

    // ── Create / Update ─────────────────────────────────────────────────────

    @Transactional
    public EmailSmtpConfig create(Long tenantId, EmailSmtpConfig incoming) {
        incoming.setId(null);
        incoming.setTenantId(tenantId);

        String rawPw = incoming.getPassword();
        boolean hasPw = rawPw != null && !rawPw.isBlank() && !UNCHANGED_PASSWORD_TOKEN.equals(rawPw);
        // On create there is nothing stored to keep — a sentinel here means the
        // caller sent no real password, so treat it as empty rather than storing
        // the literal "__UNCHANGED__".
        incoming.setPassword(hasPw ? encryptIfPresent(rawPw) : null);

        // A brand-new config is never auto-activated; activation is explicit.
        incoming.setIsActive(false);
        EmailSmtpConfig saved = repository.save(incoming);
        log.info("[SMTP] Created config '{}' (id={}) for tenant {} — password {}",
                saved.getConfigName(), saved.getId(), tenantId,
                hasPw ? "SET (fingerprint " + fingerprint(rawPw) + ")" : "EMPTY");
        if (!hasPw && Boolean.TRUE.equals(saved.getAuthEnabled())) {
            log.warn("[SMTP] Config id={} created with authEnabled=true but NO password — "
                    + "sending will 535 until a password is set.", saved.getId());
        }
        // Return a DETACHED masked copy — must not mutate the managed entity.
        return maskSecrets(saved);
    }

    @Transactional
    public EmailSmtpConfig update(Long tenantId, Long id, EmailSmtpConfig incoming) {
        EmailSmtpConfig existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("SMTP config not found: " + id));
        assertTenant(existing, tenantId);

        existing.setConfigName(incoming.getConfigName());
        existing.setHost(incoming.getHost());
        existing.setPort(incoming.getPort());
        existing.setUsername(incoming.getUsername());
        existing.setAuthEnabled(incoming.getAuthEnabled());
        existing.setStarttlsEnabled(incoming.getStarttlsEnabled());
        existing.setSslEnabled(incoming.getSslEnabled());
        existing.setFromAddress(incoming.getFromAddress());
        existing.setFromName(incoming.getFromName());
        existing.setReplyTo(incoming.getReplyTo());
        if (incoming.getConnectionTimeout() != null) existing.setConnectionTimeout(incoming.getConnectionTimeout());
        if (incoming.getReadTimeout() != null) existing.setReadTimeout(incoming.getReadTimeout());
        if (incoming.getWriteTimeout() != null) existing.setWriteTimeout(incoming.getWriteTimeout());
        if (incoming.getRateLimitMs() != null) existing.setRateLimitMs(incoming.getRateLimitMs());
        if (incoming.getMaxRetries() != null) existing.setMaxRetries(incoming.getMaxRetries());
        if (incoming.getAutoSendAfterBatch() != null) existing.setAutoSendAfterBatch(incoming.getAutoSendAfterBatch());

        // Password handling: the client never receives the stored password, so
        // on edit it sends back either a NEW password, or the UNCHANGED token
        // (or blank) meaning "keep what's stored". Only re-encrypt on a real change.
        String incomingPw = incoming.getPassword();
        boolean realNewPw = incomingPw != null && !incomingPw.isBlank()
                && !UNCHANGED_PASSWORD_TOKEN.equals(incomingPw);
        boolean existingPwBlank = existing.getPassword() == null || existing.getPassword().isBlank();

        if (realNewPw) {
            existing.setPassword(encryptIfPresent(incomingPw));
            log.info("[SMTP] Password UPDATED for config id={} (new pw fingerprint {})",
                    id, fingerprint(incomingPw));
        } else {
            log.info("[SMTP] Password KEPT (unchanged sentinel) for config id={}. Stored password {}.",
                    id, existingPwBlank ? "is EMPTY" : "present");
            if (existingPwBlank && Boolean.TRUE.equals(existing.getAuthEnabled())) {
                log.warn("[SMTP] Config id={} saved with authEnabled=true but the stored password "
                        + "is EMPTY and no new password was provided. Re-open the config and TYPE "
                        + "the SMTP password before saving, or sending will 535.", id);
            }
        }

        EmailSmtpConfig saved = repository.save(existing);
        // CRITICAL: return a DETACHED masked copy. Masking the managed entity here
        // would be flushed to the DB on commit, clobbering the password.
        return maskSecrets(saved);
    }

    @Transactional
    public void delete(Long tenantId, Long id) {
        EmailSmtpConfig existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("SMTP config not found: " + id));
        assertTenant(existing, tenantId);
        repository.delete(existing);
        log.info("[SMTP] Deleted config id={} for tenant {}", id, tenantId);
    }

    /**
     * Make one config the active one for the tenant. Clears isActive on every
     * other config first so the "at most one active" invariant always holds.
     */
    @Transactional
    public EmailSmtpConfig activate(Long tenantId, Long id) {
        EmailSmtpConfig target = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("SMTP config not found: " + id));
        assertTenant(target, tenantId);

        for (EmailSmtpConfig other : repository.findAllByTenantIdAndIsActiveTrue(tenantId)) {
            if (!other.getId().equals(id)) {
                other.setIsActive(false);
                repository.save(other);
            }
        }
        target.setIsActive(true);
        EmailSmtpConfig saved = repository.save(target);
        log.info("[SMTP] Config id={} is now ACTIVE for tenant {}", id, tenantId);
        // DETACHED masked copy — never mutate the managed entity.
        return maskSecrets(saved);
    }

    // ── Mail sender construction ────────────────────────────────────────────

    /**
     * Build a JavaMailSender from a stored config. The password is decrypted
     * here and only here, held in memory for the lifetime of the send.
     */
    public JavaMailSenderImpl buildMailSender(EmailSmtpConfig cfg) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        int port = cfg.getPort() != null ? cfg.getPort() : 587;
        sender.setHost(cfg.getHost());
        sender.setPort(port);
        sender.setUsername(cfg.getUsername());

        String decryptedPw;
        try {
            decryptedPw = decryptIfPresent(cfg.getPassword());
        } catch (Exception de) {
            log.error("[SMTP] Password DECRYPTION FAILED for config id={} tenant={} "
                    + "(stored ciphertext len={}). This usually means the config was saved "
                    + "under a different encryption key than the one now running. Re-enter the "
                    + "SMTP password on the SMTP Settings page to re-encrypt it. Cause: {}",
                    cfg.getId(), cfg.getTenantId(),
                    cfg.getPassword() != null ? cfg.getPassword().length() : 0, de.getMessage());
            decryptedPw = null;
        }
        sender.setPassword(decryptedPw);
        sender.setDefaultEncoding("UTF-8");

        boolean starttls = Boolean.TRUE.equals(cfg.getStarttlsEnabled());
        boolean ssl = Boolean.TRUE.equals(cfg.getSslEnabled());
        boolean auth = Boolean.TRUE.equals(cfg.getAuthEnabled());

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(auth));
        props.put("mail.smtp.starttls.enable", String.valueOf(starttls));
        if (starttls) {
            props.put("mail.smtp.starttls.required", "true");
        }
        if (ssl) {
            props.put("mail.smtp.ssl.enable", "true");
        }
        props.put("mail.smtp.connectiontimeout",
                String.valueOf(cfg.getConnectionTimeout() != null ? cfg.getConnectionTimeout() : 10000));
        props.put("mail.smtp.timeout",
                String.valueOf(cfg.getReadTimeout() != null ? cfg.getReadTimeout() : 10000));
        props.put("mail.smtp.writetimeout",
                String.valueOf(cfg.getWriteTimeout() != null ? cfg.getWriteTimeout() : 10000));

        log.info("[SMTP] Built sender for config id={} tenant='{}' name='{}': "
                        + "host={} port={} auth={} starttls={} ssl={} "
                        + "username='{}' password[{}] from='{}'",
                cfg.getId(), cfg.getTenantId(), cfg.getConfigName(),
                cfg.getHost(), port, auth, starttls, ssl,
                cfg.getUsername(), fingerprint(decryptedPw), cfg.getFromAddress());

        warnOnLikelySesMisconfig(cfg, port, starttls, ssl, auth, decryptedPw);

        return sender;
    }

    /**
     * Emit targeted warnings for the classic SES 535 traps. Pure logging — does
     * not alter the sender. Only fires hints when the host looks like an SES
     * endpoint (or the shape is obviously wrong), so non-SES providers stay quiet.
     */
    private void warnOnLikelySesMisconfig(EmailSmtpConfig cfg, int port,
                                          boolean starttls, boolean ssl, boolean auth,
                                          String decryptedPw) {
        String host = cfg.getHost() != null ? cfg.getHost().toLowerCase() : "";
        boolean isSes = host.contains("amazonaws.com") && host.contains("email-smtp");

        if (!auth) {
            log.warn("[SMTP] authEnabled=false — SES REQUIRES SMTP AUTH. A 535/authentication "
                    + "error is expected until you enable auth for config id={}.", cfg.getId());
        }
        if (port == 587 && ssl && !starttls) {
            log.warn("[SMTP] port 587 with sslEnabled=true and starttls=false is a mismatch. "
                    + "Use STARTTLS on 587 (starttls=true, ssl=false) OR implicit SSL on 465 "
                    + "(ssl=true, starttls=false). config id={}", cfg.getId());
        }
        if (port == 465 && !ssl) {
            log.warn("[SMTP] port 465 typically needs sslEnabled=true (implicit TLS). config id={}",
                    cfg.getId());
        }
        if (decryptedPw == null || decryptedPw.isBlank()) {
            log.warn("[SMTP] Resolved password is EMPTY for config id={} — this WILL 535. "
                    + "Re-enter the SMTP password on the SMTP Settings page.", cfg.getId());
        } else if (UNCHANGED_PASSWORD_TOKEN.equals(decryptedPw)) {
            // Belt-and-braces: if a legacy row still holds the sentinel from the
            // old mutation bug, name it explicitly so the cause is unmistakable.
            log.error("[SMTP] Stored password for config id={} is the literal sentinel "
                    + "'__UNCHANGED__' — a legacy row corrupted by the pre-fix stripSecrets bug. "
                    + "Re-open the config, TYPE the real SMTP password, and Save to overwrite it.",
                    cfg.getId());
        } else if (isSes) {
            int len = decryptedPw.length();
            boolean looksLikeSesSmtpPw = len >= 43 && len <= 45;
            if (!looksLikeSesSmtpPw) {
                log.warn("[SMTP] Password length={} does not match the expected Amazon SES SMTP "
                        + "password shape (~44 chars, base64). If you pasted an IAM *secret access "
                        + "key* (40 chars) this is your 535 — SES SMTP passwords are DERIVED from "
                        + "the IAM secret, not the secret itself. config id={}", len, cfg.getId());
            }
            String region = extractSesRegion(host);
            if (region != null) {
                log.info("[SMTP] SES endpoint region resolved as '{}'. SMTP credentials are "
                        + "REGION-SPECIFIC — they must have been created in '{}' or auth will 535. "
                        + "config id={}", region, region, cfg.getId());
            }
        }
    }

    /** Pull the region out of an SES host like email-smtp.ap-south-1.amazonaws.com. */
    private String extractSesRegion(String host) {
        try {
            String[] parts = host.split("\\.");
            if (parts.length >= 3 && parts[0].startsWith("email-smtp")) {
                return parts[1];
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Test connectivity for a stored config by opening (and closing) an SMTP
     * transport connection. Does NOT send a message. Returns a human-readable
     * result; never throws.
     */
    public TestResult testConnection(Long tenantId, Long id) {
        EmailSmtpConfig cfg = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("SMTP config not found: " + id));
        assertTenant(cfg, tenantId);
        // Test on a detached copy so nothing about the managed entity can change.
        return runTest(copyForTest(cfg), "config id=" + id);
    }

    /**
     * PRE-SAVE test: validate an UNSAVED config coming straight from the admin
     * form, without persisting anything. Nothing is written. Never throws.
     */
    public TestResult testRawConfig(Long tenantId, EmailSmtpConfig incoming) {
        if (incoming == null) {
            return new TestResult("FAILED", "No configuration supplied");
        }
        if (incoming.getHost() == null || incoming.getHost().isBlank()) {
            return new TestResult("FAILED", "SMTP host is required");
        }

        EmailSmtpConfig probe = copyForTest(incoming);
        probe.setTenantId(tenantId);

        String pw = incoming.getPassword();
        boolean unchanged = pw == null || pw.isBlank() || UNCHANGED_PASSWORD_TOKEN.equals(pw);
        if (unchanged && incoming.getId() != null) {
            EmailSmtpConfig stored = repository.findById(incoming.getId()).orElse(null);
            if (stored != null) {
                assertTenant(stored, tenantId);
                if (stored.getPassword() == null || stored.getPassword().isBlank()) {
                    return new TestResult("FAILED",
                            "No password is stored for this config yet — type the SMTP password "
                            + "in the field above, then Test (and Save).");
                }
                probe.setPassword(stored.getPassword()); // encrypted; buildMailSender decrypts
            } else {
                probe.setPassword(null);
            }
        } else if (unchanged) {
            return new TestResult("FAILED", "Enter the SMTP password before testing.");
        }
        // else: probe already carries the raw plaintext password from the form.

        String label = incoming.getId() != null
                ? "unsaved edit of config id=" + incoming.getId()
                : "unsaved new config '" + incoming.getConfigName() + "'";
        return runTest(probe, label);
    }

    /** Detached shallow copy of the fields needed to build a sender / mask a response. */
    private EmailSmtpConfig copyForTest(EmailSmtpConfig src) {
        EmailSmtpConfig c = new EmailSmtpConfig();
        c.setId(src.getId());
        c.setTenantId(src.getTenantId());
        c.setConfigName(src.getConfigName());
        c.setHost(src.getHost());
        c.setPort(src.getPort());
        c.setUsername(src.getUsername());
        c.setPassword(src.getPassword());
        c.setAuthEnabled(src.getAuthEnabled());
        c.setStarttlsEnabled(src.getStarttlsEnabled());
        c.setSslEnabled(src.getSslEnabled());
        c.setFromAddress(src.getFromAddress());
        c.setFromName(src.getFromName());
        c.setReplyTo(src.getReplyTo());
        c.setConnectionTimeout(src.getConnectionTimeout());
        c.setReadTimeout(src.getReadTimeout());
        c.setWriteTimeout(src.getWriteTimeout());
        c.setRateLimitMs(src.getRateLimitMs());
        c.setMaxRetries(src.getMaxRetries());
        c.setIsActive(src.getIsActive());
        c.setAutoSendAfterBatch(src.getAutoSendAfterBatch());
        c.setCreatedAt(src.getCreatedAt());
        c.setUpdatedAt(src.getUpdatedAt());
        return c;
    }

    /** Shared test runner used by both testConnection and testRawConfig. */
    private TestResult runTest(EmailSmtpConfig cfg, String label) {
        long t0 = System.currentTimeMillis();
        try {
            JavaMailSenderImpl sender = buildMailSender(cfg);
            log.info("[SMTP] Testing connection ({}) → {}:{} (username='{}')",
                    label, cfg.getHost(), sender.getPort(), cfg.getUsername());
            sender.testConnection();
            long ms = System.currentTimeMillis() - t0;
            log.info("[SMTP] Test connection OK ({}) in {} ms", label, ms);
            return new TestResult("SUCCESS", "Connected to " + cfg.getHost() + ":" + cfg.getPort());
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            String interp = interpretSmtpError(e);
            log.error("[SMTP] Test connection FAILED ({}) after {} ms. "
                            + "host={}:{} username='{}' authEnabled={} starttls={} ssl={}. "
                            + "{} Exception: {}: {}",
                    label, ms, cfg.getHost(), cfg.getPort(), cfg.getUsername(),
                    cfg.getAuthEnabled(), cfg.getStarttlsEnabled(), cfg.getSslEnabled(),
                    interp, e.getClass().getName(), rootCauseMessage(e), e);
            return new TestResult("FAILED", rootCauseMessage(e)
                    + (interp.isEmpty() ? "" : "  " + interp));
        }
    }

    /** Map a common SMTP failure to an actionable hint for the log & UI. */
    private String interpretSmtpError(Throwable e) {
        String msg = rootCauseMessage(e).toLowerCase();
        if (msg.contains("535") || msg.contains("authentication credentials invalid")
                || msg.contains("authentication failed")) {
            return "HINT: 535 = SES rejected the username/password. Verify you are using SES SMTP "
                    + "credentials (not IAM access key/secret), created in THIS endpoint's region, "
                    + "and that the from-address is a verified SES identity.";
        }
        if (msg.contains("530")) {
            return "HINT: 530 = authentication required but not offered — enable SMTP AUTH and TLS.";
        }
        if (msg.contains("connect") && (msg.contains("timed out") || msg.contains("timeout"))) {
            return "HINT: connection timed out — check egress/security-group to the SES SMTP port.";
        }
        if (msg.contains("could not connect") || msg.contains("connection refused")) {
            return "HINT: cannot reach host:port — verify SES endpoint host and port (587/465).";
        }
        return "";
    }

    public record TestResult(String status, String message) {}

    // ── Internals ───────────────────────────────────────────────────────────

    private String encryptIfPresent(String value) {
        return (value == null || value.isBlank()) ? value : cryptoService.encrypt(value);
    }

    private String decryptIfPresent(String value) {
        return (value == null || value.isBlank()) ? value : cryptoService.decrypt(value);
    }

    /**
     * Non-reversible fingerprint of a secret for logs: length + first/last char
     * only. Never reveals the secret.
     */
    private static String fingerprint(String secret) {
        if (secret == null) return "null";
        if (secret.isBlank()) return "blank";
        int n = secret.length();
        char first = secret.charAt(0);
        char last = secret.charAt(n - 1);
        return "len=" + n + " " + first + "\u2026" + last;
    }

    /** Deepest cause message, so nested MessagingException/AuthenticationFailed surfaces. */
    private static String rootCauseMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return m != null ? m : c.getClass().getSimpleName();
    }

    /**
     * Return a DETACHED copy of the config with the password replaced by the
     * UNCHANGED sentinel (so a set password is signalled without revealing it).
     *
     * CRITICAL: this must operate on a copy, NOT the managed JPA entity. Masking
     * the managed entity inside a @Transactional method causes Hibernate to flush
     * the sentinel to the DB on commit, destroying the stored password (the
     * "works before save, 535 after save" bug).
     */
    private EmailSmtpConfig maskSecrets(EmailSmtpConfig managed) {
        if (managed == null) return null;
        EmailSmtpConfig copy = copyForTest(managed);
        if (copy.getPassword() != null && !copy.getPassword().isBlank()) {
            copy.setPassword(UNCHANGED_PASSWORD_TOKEN);
        }
        return copy;
    }

    private void assertTenant(EmailSmtpConfig cfg, Long tenantId) {
        if (!cfg.getTenantId().equals(tenantId)) {
            throw new IllegalArgumentException("SMTP config does not belong to this tenant");
        }
    }
}
