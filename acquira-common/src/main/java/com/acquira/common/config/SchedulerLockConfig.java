package com.acquira.common.config;

import jakarta.annotation.PreDestroy;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cross-replica locking for scheduled work (ShedLock, table {@code shedlock}).
 *
 * Every replica still fires its own timers; a job annotated with
 * {@code @SchedulerLock} (or run through {@code LockingTaskExecutor}) first
 * takes a named row lock and SKIPS the run if another replica holds it. That
 * is what makes it safe to run more than one core or batch pod.
 *
 * HOW THE LOCK TIMES WORK HERE
 *  - lockAtMostFor is only the crash safety net: {@link KeepAliveLockProvider}
 *    keeps extending the lock while the job is still running, so a multi-hour
 *    pull stays locked, and a pod that dies frees its locks within that time.
 *    (KeepAlive needs lockAtMostFor >= 30s.)
 *  - lockAtLeastFor keeps the lock for a while after a FAST run, so the other
 *    replica's timer — which fires at a different offset — does not run the
 *    same job again a moment later. For fixed-delay jobs set it just under the
 *    interval to keep the original cadence.
 *  - DB time is used, so replica clock skew does not matter.
 *
 * FAIL-OPEN: if the lock table is unusable (migration V2026_10_03_02 not
 * applied) the job runs WITHOUT a lock and this is logged — identical to the
 * behaviour before ShedLock, so a single-replica install never loses its
 * schedulers over a missing table. Do not scale out until the warning is gone.
 *
 * Jobs that must run on EVERY replica (local temp-file cleanup, per-pod
 * buffers) are deliberately NOT locked.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulerLockConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLockConfig.class);

    private final ScheduledExecutorService keepAliveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "shedlock-keepalive");
        t.setDaemon(true);
        return t;
    });

    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        JdbcTemplateLockProvider jdbc = new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbcTemplate)
                        .usingDbTime()
                        .build());
        return new FailOpenLockProvider(new KeepAliveLockProvider(jdbc, keepAliveExecutor));
    }

    @PreDestroy
    public void stop() {
        keepAliveExecutor.shutdownNow();
    }

    /** Runs the job unlocked (as before ShedLock) when the lock store itself fails. */
    static final class FailOpenLockProvider implements LockProvider {

        private static final SimpleLock NO_LOCK = () -> { };

        private final LockProvider delegate;
        private final AtomicBoolean warned = new AtomicBoolean();

        FailOpenLockProvider(LockProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<SimpleLock> lock(LockConfiguration config) {
            try {
                Optional<SimpleLock> lock = delegate.lock(config);
                warned.set(false);
                return lock;
            } catch (RuntimeException e) {
                if (warned.compareAndSet(false, true)) {
                    log.warn("Scheduler lock store unavailable — jobs run UNLOCKED until it recovers "
                            + "(safe for one replica only; is migration V2026_10_03_02 applied?): {}", e.toString());
                } else {
                    log.debug("Scheduler lock '{}' unavailable: {}", config.getName(), e.toString());
                }
                return Optional.of(NO_LOCK);
            }
        }
    }
}
