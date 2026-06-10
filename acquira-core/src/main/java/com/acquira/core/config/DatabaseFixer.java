package com.acquira.core.config;

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
        fixUsersTable();
        fixDimMerchantTable();
    }

    /**
     * Ensure dim_merchant has the PDF generate-flag column.
     * generate_report_flag = 1 -> include in PDF generation, 0 -> skip.
     * Defaults to 1 so every merchant (existing rows + new uploads) generates by
     * default; set to 0 manually when a merchant should be excluded.
     */
    private void fixDimMerchantTable() {
        try {
            logger.info("Checking dim_merchant columns...");
            jdbcTemplate.execute(
                    "ALTER TABLE dim_merchant ADD COLUMN IF NOT EXISTS generate_report_flag INTEGER DEFAULT 1");
            logger.info("\u2713 Ensured column generate_report_flag exists in dim_merchant");

            // Backfill any existing rows that predate the column (NULL -> 1).
            int updated = jdbcTemplate.update(
                    "UPDATE dim_merchant SET generate_report_flag = 1 WHERE generate_report_flag IS NULL");
            if (updated > 0) {
                logger.info("\u2713 Defaulted {} dim_merchant rows to generate_report_flag = 1", updated);
            }
            logger.info("dim_merchant table check completed.");
        } catch (Exception e) {
            logger.error("fixDimMerchantTable failed", e);
        }
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

    /**
     * Fix missing columns in 'users' table that the User entity requires.
     * Older schema.sql versions didn't include SSO/approval/display columns.
     */
    private void fixUsersTable() {
        try {
            logger.info("Checking users table columns...");

            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS sso_provider VARCHAR(50)");
            logger.info("✓ Ensured column sso_provider exists in users");

            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS sso_id VARCHAR(255)");
            logger.info("✓ Ensured column sso_id exists in users");

            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS approval_status VARCHAR(20) DEFAULT 'APPROVED'");
            logger.info("✓ Ensured column approval_status exists in users");

            jdbcTemplate.execute(
                    "ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name VARCHAR(100)");
            logger.info("✓ Ensured column display_name exists in users");

            // Make sure any existing users without approval_status get it set to APPROVED
            int updated = jdbcTemplate.update(
                    "UPDATE users SET approval_status = 'APPROVED' WHERE approval_status IS NULL");
            if (updated > 0) {
                logger.info("✓ Updated {} users to approval_status = APPROVED", updated);
            }

            logger.info("users table check completed.");
        } catch (Exception e) {
            logger.error("fixUsersTable failed", e);
        }
    }
}
