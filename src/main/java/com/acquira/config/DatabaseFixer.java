package com.acquira.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class DatabaseFixer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseFixer.class);
    private final JdbcTemplate jdbcTemplate;

    public DatabaseFixer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        fixUserTenantAccessAndSalesTeamMapping();
    }

    private void fixUserTenantAccessAndSalesTeamMapping() {
        try {
            logger.info("Checking and fixing database schema...");

            // Fix 1: user_tenant_access missing is_default_tenant and role_in_tenant
            try {
                jdbcTemplate.execute(
                        "ALTER TABLE user_tenant_access ADD COLUMN IF NOT EXISTS is_default_tenant BOOLEAN DEFAULT FALSE");
                logger.info("✓ Ensured column is_default_tenant exists in user_tenant_access");

                jdbcTemplate
                        .execute("ALTER TABLE user_tenant_access ADD COLUMN IF NOT EXISTS role_in_tenant VARCHAR(255)");
                logger.info("✓ Ensured column role_in_tenant exists in user_tenant_access");
            } catch (Exception e) {
                logger.warn("Could not alter user_tenant_access: " + e.getMessage());
            }

            // Fix 2: sales_team_mapping missing is_default (just in case, similar pattern)
            try {
                // Check if sales_team_mapping exists first to avoid error if ddl-auto didn't
                // create it yet?
                // Usually ddl-auto runs before CommandLineRunner.
                // We will assume table exists, or catch exception.
                jdbcTemplate.execute(
                        "ALTER TABLE sales_team_mapping ADD COLUMN IF NOT EXISTS is_default BOOLEAN DEFAULT FALSE");
                logger.info("✓ Ensured column is_default exists in sales_team_mapping");
            } catch (Exception e) {
                // Table might not exist yet if ddl-auto failed completely, but usually ok
                logger.warn("Could not alter sales_team_mapping: " + e.getMessage());
            }

            logger.info("Database schema check completed.");
        } catch (Exception e) {
            logger.error("DatabaseFixer failed", e);
        }
    }
}
