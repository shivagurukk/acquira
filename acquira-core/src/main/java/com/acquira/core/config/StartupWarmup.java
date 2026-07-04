package com.acquira.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Startup warm-up: eliminates the "first login after restart is slow / blank
 * screen" symptom.
 *
 * ROOT CAUSE this addresses:
 *   The very first request to hit a freshly-started acquira-core pays the full
 *   cold-start cost all at once — Hikari opening its first physical DB
 *   connection, Hibernate/JPA initializing its first query, the JIT compiling
 *   the auth path, and BCrypt spinning up. For a user that first request is the
 *   login page's /api/sso/microsoft/config call (or the login POST itself), so
 *   they see a multi-second stall / blank page.
 *
 * WHAT THIS DOES:
 *   After the Spring context is fully up (ApplicationRunner runs post-refresh),
 *   we proactively exercise the two hot paths ONCE, on a background thread, so
 *   the pool is filled and the JITted code is warm before any real user arrives:
 *     1. Borrow a JDBC connection from Hikari and run `SELECT 1` — this forces
 *        Hikari to open physical connections up to minimum-idle and validates
 *        the DB is reachable.
 *     2. Run one throwaway password hash via the app's DelegatingPasswordEncoder
 *        — the auth path's most expensive step, otherwise cold on first login.
 *
 * WHY A BACKGROUND THREAD:
 *   Warm-up must never delay the application becoming ready to serve, and must
 *   never crash startup if the DB is briefly unavailable at boot. Everything
 *   here is best-effort: failures are logged and swallowed.
 *
 * @Order(Ordered.LOWEST_PRECEDENCE) so any other ApplicationRunner (e.g. data
 * seeding) runs first; we only want to warm what already exists.
 */
@Component
@Order(Integer.MAX_VALUE)
public class StartupWarmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupWarmup.class);

    private final DataSource dataSource;

    public StartupWarmup(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Off the startup thread entirely — readiness is never gated on warm-up.
        Thread t = new Thread(this::warm, "acquira-startup-warmup");
        t.setDaemon(true);
        t.start();
    }

    private void warm() {
        long t0 = System.currentTimeMillis();

        // 1. Warm the Hikari pool + validate DB reachability.
        try (Connection conn = dataSource.getConnection();
                Statement st = conn.createStatement()) {
            st.execute("SELECT 1");
            log.info("[warmup] DB connection pool warmed in {} ms", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            // DB not ready at boot is not fatal — the pool will fill lazily on the
            // first real request as before. Just log it.
            log.warn("[warmup] DB warm-up skipped: {}", e.getMessage());
        }

        // 2. Warm the password-encoder / auth path with one throwaway hash. The app
        // uses a DelegatingPasswordEncoder (PasswordEncoderFactories), so warm the
        // SAME encoder rather than a raw BCrypt instance — this exercises the exact
        // code path a real login takes. The first hash pays class-loading + JIT cost
        // here instead of on the first user login.
        try {
            long b0 = System.currentTimeMillis();
            PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
            encoder.encode("warmup-not-a-real-password");
            log.info("[warmup] Auth (password-encoder) path warmed in {} ms", System.currentTimeMillis() - b0);
        } catch (Exception e) {
            log.warn("[warmup] Password-encoder warm-up skipped: {}", e.getMessage());
        }

        log.info("[warmup] Startup warm-up complete in {} ms total", System.currentTimeMillis() - t0);
    }
}
