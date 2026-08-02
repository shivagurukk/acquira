package com.acquira.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Stamps the PostgreSQL session variable {@code app.current_tenant} onto EVERY
 * connection the pool hands out, derived from {@link TenantContext}. This makes
 * Row-Level Security ({@code get_current_tenant()}) see the correct tenant no
 * matter how the query reaches the DB.
 *
 * <h3>Why this is needed</h3>
 * {@link TenantAspect} sets the GUC by running {@code set_config(...)} on the
 * EntityManager's Hibernate session connection, but its pointcut only advises
 * {@code com.acquira..service..*} / {@code com.acquira..repository..*}. Several
 * endpoints run raw {@code JdbcTemplate} / {@code EntityManager} queries
 * DIRECTLY from the controller and are therefore NOT advised:
 * DataExplorerController, AnalyticsExplorerController, CrossFilterController,
 * GroupAnalyticsController, AiQueryService's guarded-execution callback, and
 * ApiKeyController. On those paths the GUC was never set, so if
 * {@code FORCE ROW LEVEL SECURITY} were applied, {@code get_current_tenant()}
 * would be null and every one of those endpoints would return ZERO rows.
 *
 * Even for advised paths the aspect is not airtight: a {@code JdbcTemplate}
 * statement outside a transaction checks out its OWN pooled connection, which
 * may not be the one the aspect stamped via the EntityManager session. Setting
 * the value at the point of connection checkout removes that ambiguity — the
 * GUC is guaranteed to be present on the exact connection the query runs on.
 *
 * <h3>Correctness under pooling</h3>
 * The value is (re)written on EVERY {@code getConnection()} checkout: to the
 * current tenant id when present, or blanked ({@code ''}) when there is none.
 * Because it is overwritten on every checkout, a value can never leak from one
 * checkout to the next, so no reset-on-close proxy is required. The empty-string
 * "no tenant" marker must be treated as NULL by {@code get_current_tenant()} —
 * pair this with:
 * <pre>
 *   CREATE OR REPLACE FUNCTION get_current_tenant() RETURNS bigint AS $$
 *     SELECT NULLIF(current_setting('app.current_tenant', true), '')::bigint;
 *   $$ LANGUAGE sql STABLE;
 * </pre>
 * ({@code current_setting(..., true)} already returns NULL when the setting was
 * never set on a fresh connection; the {@code NULLIF} covers our blanked case.)
 *
 * <h3>What this does NOT change</h3>
 * It does not replace the application-level {@code WHERE tenant_id = ?} guards
 * (still the primary isolation today, since RLS is not yet forced) nor
 * {@link TenantAspect} (kept as-is; the redundant double-set on service/repo
 * paths is harmless — same value). It is the connection-level backstop that
 * makes turning on forced RLS safe. External-DB pulls in IntegrationPullService
 * are unaffected: they open their own {@code DriverManager} connections, not
 * this pooled {@code DataSource}.
 */
@Configuration
public class TenantConnectionDataSourceConfig {

    /**
     * Wraps the auto-configured {@link DataSource} bean without reconfiguring the
     * pool. Declared {@code static} so the {@link BeanPostProcessor} is created
     * early enough to intercept the DataSource, per Spring's recommendation.
     */
    @Bean
    public static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(ds);
                }
                return bean;
            }
        };
    }

    /**
     * {@link DelegatingDataSource} that sets {@code app.current_tenant} on each
     * connection as it is checked out of the pool.
     */
    static final class TenantAwareDataSource extends DelegatingDataSource {

        private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSource.class);

        /**
         * One round-trip sets both session GUCs: the tenant marker and a
         * statement_timeout scoped to the caller's thread type.
         *
         * WHY the timeout lives here: hikari.connection-init-sql sets
         * statement_timeout=0 for the batch/ingest path, but web requests share
         * the same 30-connection pool — one runaway report query per connection
         * and the whole app deadlocks with nothing to reap it. Stamping at
         * checkout lets web threads get a finite ceiling while batch threads
         * (no request context) keep 0. Like the tenant GUC, it is overwritten
         * on EVERY checkout, so a value can never leak between borrowers.
         */
        private static final String SET_SESSION_SQL =
                "SELECT set_config('app.current_tenant', ?, false), set_config('statement_timeout', ?, false)";

        /** Override with -Dacquira.web.statement.timeout.ms=NNNN (0 disables). */
        private static final String WEB_STATEMENT_TIMEOUT_MS =
                System.getProperty("acquira.web.statement.timeout.ms", "30000");

        TenantAwareDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return applySessionState(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return applySessionState(super.getConnection(username, password));
        }

        /**
         * Set app.current_tenant on the freshly-checked-out connection from the
         * current {@link TenantContext}. Blank ({@code ''}) when no tenant is in
         * context (startup, schema init, framework metadata writes, non-tenant
         * threads) — every subsequent checkout overwrites it, so it never leaks.
         *
         * Additionally sets statement_timeout: finite on servlet-request threads
         * (report/API reads must not pin a pooled connection indefinitely),
         * '0' (unlimited) on batch/scheduler/startup threads, whose long-running
         * ingest statements are legitimate.
         *
         * Failure is non-fatal: we log and return the connection, since the
         * application-level {@code WHERE tenant_id = ?} clauses still enforce
         * isolation and RLS (when forced) fails closed rather than leaking.
         */
        private Connection applySessionState(Connection conn) {
            Long tenantId = TenantContext.getCurrentTenant();
            boolean webThread = org.springframework.web.context.request.RequestContextHolder
                    .getRequestAttributes() != null;
            try (PreparedStatement ps = conn.prepareStatement(SET_SESSION_SQL)) {
                ps.setString(1, tenantId != null ? String.valueOf(tenantId.longValue()) : "");
                ps.setString(2, webThread ? WEB_STATEMENT_TIMEOUT_MS : "0");
                ps.execute();
            } catch (SQLException e) {
                log.warn("Could not set session state on connection checkout: {}", e.getMessage());
            }
            return conn;
        }
    }
}
