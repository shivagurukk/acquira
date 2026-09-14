-- Integration Hub v2: failure alerting + health/forensics query support.
--
-- 1. Per-schedule failure alerts: when a pull's final attempt fails, an email
--    goes to alert_emails through the tenant's own SMTP config. Empty/NULL
--    recipients = no alert; alert_on_failure lets an operator mute a schedule
--    without deleting the address list.
ALTER TABLE integration_schedule ADD COLUMN IF NOT EXISTS alert_emails TEXT;
ALTER TABLE integration_schedule ADD COLUMN IF NOT EXISTS alert_on_failure BOOLEAN NOT NULL DEFAULT TRUE;

-- 2. The schedules list and the new /health endpoint read "recent runs per
--    schedule/report" on every page load; both were sequential scans.
CREATE INDEX IF NOT EXISTS idx_int_run_log_schedule_time
    ON integration_run_log (schedule_id, start_time DESC);
CREATE INDEX IF NOT EXISTS idx_int_run_log_report_time
    ON integration_run_log (report_id, start_time DESC);
