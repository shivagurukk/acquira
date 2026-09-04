@echo off
REM ============================================================================
REM  apply_migrations.bat — Acquira consolidated migration runner
REM
REM  Applies db/migration/ALL_MIGRATIONS_CONSOLIDATED.sql to the target
REM  Postgres database, ONE SOURCE FILE AT A TIME, skipping any file whose
REM  name is already present in schema_migration_log. This is the real
REM  "don't duplicate" mechanism: unlike psql -f on the whole bundle (which
REM  re-runs every statement every time — safe, but wasteful on a large re-run),
REM  this script only executes the statements for a file that hasn't been
REM  logged yet on THIS database.
REM
REM  Also works as a plain full-bundle runner: pass /full to skip the
REM  per-file check and just run the whole consolidated file once, inside a
REM  single transaction (still safe to repeat — see the file's own header).
REM
REM  USAGE
REM  -----
REM    apply_migrations.bat                  Dev defaults (127.0.0.1:5433/postgres)
REM    apply_migrations.bat /full             Run the whole bundle in one shot
REM    set DB_HOST=...&set DB_PORT=...&set DB_NAME=...&set DB_USER=...&apply_migrations.bat
REM        (override any connection param via env var before running)
REM    set PGPASSWORD=...&apply_migrations.bat
REM        (psql reads the password from PGPASSWORD; omit to be prompted)
REM
REM  REQUIRES: psql on PATH (PostgreSQL client tools). Confirm with `where psql`.
REM ============================================================================

setlocal EnableDelayedExpansion

REM ── Connection defaults (matches project's dev DB) — override via env vars ──
if not defined DB_HOST set DB_HOST=127.0.0.1
if not defined DB_PORT set DB_PORT=5433
if not defined DB_NAME set DB_NAME=postgres
if not defined DB_USER set DB_USER=postgres

set SCRIPT_DIR=%~dp0
set MIGRATION_DIR=%SCRIPT_DIR%
set BUNDLE_FILE=%MIGRATION_DIR%ALL_MIGRATIONS_CONSOLIDATED.sql

echo ============================================================
echo  Acquira migration runner
echo  Target: %DB_USER%@%DB_HOST%:%DB_PORT%/%DB_NAME%
echo ============================================================

where psql >nul 2>nul
if errorlevel 1 (
    echo ERROR: psql not found on PATH. Install PostgreSQL client tools first.
    exit /b 1
)

if not exist "%BUNDLE_FILE%" (
    echo ERROR: %BUNDLE_FILE% not found.
    exit /b 1
)

REM ── /full mode: run the entire consolidated file once, in one transaction ──
if /I "%~1"=="/full" (
    echo Running full bundle in a single transaction...
    psql -h %DB_HOST% -p %DB_PORT% -d %DB_NAME% -U %DB_USER% ^
         -v ON_ERROR_STOP=1 --single-transaction -f "%BUNDLE_FILE%"
    if errorlevel 1 (
        echo.
        echo FAILED - transaction rolled back, nothing was applied this run.
        exit /b 1
    )
    echo.
    echo Done. Full bundle applied ^(re-applying is safe if run again^).
    exit /b 0
)

REM ── Tracked mode: ensure the log table exists, then apply each source file
REM    only if it is not already recorded in schema_migration_log. ──────────
echo Ensuring schema_migration_log exists...
psql -h %DB_HOST% -p %DB_PORT% -d %DB_NAME% -U %DB_USER% -v ON_ERROR_STOP=1 -c ^
  "CREATE TABLE IF NOT EXISTS schema_migration_log (filename VARCHAR(200) PRIMARY KEY, applied_at TIMESTAMP NOT NULL DEFAULT NOW());"
if errorlevel 1 (
    echo ERROR: could not reach the database or create schema_migration_log.
    exit /b 1
)

set FILES[0]=V2026_02_28_01__new_screens_security_alerts_api.sql
set FILES[1]=V2026_05_07_01__performance_indexes.sql
set FILES[2]=V2026_06_25_01__ref_country_missing_currencies.sql
set FILES[3]=V2026_06_25_02__ref_card_scheme_upi_jcb.sql
set FILES[4]=V2026_06_26_01__db_maintenance.sql
set FILES[5]=V2026_06_26_02__db_maintenance_menu.sql
set FILES[6]=V2026_06_27_02__explorer_master_alert.sql
set FILES[7]=V2026_07_02_01__budget_targets_menu.sql
set FILES[8]=V2026_07_04_01__api_management_foundation.sql
set FILES[9]=V2026_07_04_02__user_account_expiry.sql
set FILES[10]=V2026_07_05_01__interchange_scheme_fees.sql
set FILES[11]=V2026_07_05_02__sum_daily_terminal_fees_ceo_menu.sql
set FILES[12]=V2026_07_05_03__bank_base_volume.sql
set FILES[13]=V2026_07_05_04__loss_making_menu.sql
set FILES[14]=V2026_07_07_01__mcc_rate_card_uae.sql
set FILES[15]=V2026_07_07_03__fact_card_product_code.sql
set FILES[16]=V2026_07_07_04__intl_debit_interchange.sql
set FILES[17]=V2026_07_07_05__domestic_pos_scheme_fee.sql
set FILES[18]=V2026_07_10_01__ref_mcc_category.sql
set FILES[19]=V2026_07_10_02__menu_finance_summary_to_business_drop_perf.sql
set FILES[20]=V2026_07_10_03__sum_daily_merchant_destination.sql
set FILES[21]=V2026_07_10_04__email_queue_missing_columns.sql
set FILES[22]=V2026_07_10_05__sales_menu.sql
set FILES[23]=V2026_07_11_01__password_reset_otp.sql
set FILES[24]=V2026_07_11_02__settings_hub_menu.sql

REM V2026_07_07_04 must apply after V2026_07_07_01 (index 14 before 16) —
REM preserved by the fixed order above; do not reorder the list.

set APPLIED_COUNT=0
set SKIPPED_COUNT=0
set FAILED=0

for /L %%i in (0,1,24) do (
    set "F=!FILES[%%i]!"
    set "FPATH=%MIGRATION_DIR%!F!"

    if not exist "!FPATH!" (
        echo   [MISSING] !F! - file not found on disk, skipping ^(check the repo^)
    ) else (
        REM Check whether this filename is already logged. psql -tAc returns
        REM the row count as plain text; "1" means already applied.
        for /f %%c in ('psql -h %DB_HOST% -p %DB_PORT% -d %DB_NAME% -U %DB_USER% -tAc "SELECT COUNT(*) FROM schema_migration_log WHERE filename = '!F!'"') do set "ALREADY=%%c"

        if "!ALREADY!"=="1" (
            echo   [SKIP]    !F! - already applied
            set /a SKIPPED_COUNT+=1
        ) else (
            echo   [APPLY]   !F! ...
            psql -h %DB_HOST% -p %DB_PORT% -d %DB_NAME% -U %DB_USER% -v ON_ERROR_STOP=1 --single-transaction -f "!FPATH!"
            if errorlevel 1 (
                echo   [FAILED]  !F! - stopping here, nothing further applied.
                set FAILED=1
                goto :summary
            ) else (
                psql -h %DB_HOST% -p %DB_PORT% -d %DB_NAME% -U %DB_USER% -v ON_ERROR_STOP=1 -c ^
                  "INSERT INTO schema_migration_log (filename) VALUES ('!F!') ON CONFLICT (filename) DO NOTHING;" >nul
                set /a APPLIED_COUNT+=1
            )
        )
    )
)

:summary
echo.
echo ============================================================
if "%FAILED%"=="1" (
    echo  MIGRATION RUN FAILED
    echo  Applied this run: %APPLIED_COUNT%   Skipped ^(already applied^): %SKIPPED_COUNT%
    echo  Fix the failing script and re-run — already-applied files will be
    echo  skipped automatically, so it resumes from where it stopped.
    exit /b 1
) else (
    echo  MIGRATION RUN COMPLETE
    echo  Applied this run: %APPLIED_COUNT%   Skipped ^(already applied^): %SKIPPED_COUNT%
    echo  Total tracked: 25
)
echo ============================================================

endlocal
exit /b 0
