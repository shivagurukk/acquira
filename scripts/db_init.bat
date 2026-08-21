@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM  Acquira — Database Initialisation
REM
REM  Runs in order:
REM    1. schema.sql          — full DDL (drop + create all tables)
REM    2. data.sql            — seed data + ALL menus incl. S3
REM    3. V2026_03_27_01      — S3 tenant_setting index + ENCRYPTED type
REM    4. V2026_03_27_02      — S3 nav menu RBAC grants
REM    5. Inline SQL fix      — guarantee S3 menu + group access exists
REM
REM  NOTE: data.sql also runs automatically every Spring Boot
REM  startup (spring.sql.init.mode=always), so S3 menu is always
REM  guaranteed even without running this script manually.
REM
REM  Usage:
REM    db_init.bat                        (uses defaults below)
REM    db_init.bat mydb myuser mypass     (override db/user/pass)
REM    db_init.bat mydb myuser mypass localhost 5432
REM
REM  Requires: psql on PATH (PostgreSQL client tools)
REM ============================================================

cd /d "%~dp0.."
set "ROOT=%CD%"
set "SQL_DIR=%ROOT%\acquira-core\src\main\resources"
set "MIGRATION_DIR=%SQL_DIR%\db\migration"

REM ── Connection defaults ──────────────────────────────────────
set "PGDATABASE=%~1"
if "%PGDATABASE%"=="" set "PGDATABASE=postgres"
set "PGUSER=%~2"
if "%PGUSER%"=="" set "PGUSER=postgres"
set "PGPASSWORD=%~3"
if "%PGPASSWORD%"=="" set "PGPASSWORD=postgres"
set "PGHOST=%~4"
if "%PGHOST%"=="" set "PGHOST=127.0.0.1"
set "PGPORT=%~5"
if "%PGPORT%"=="" set "PGPORT=5433"

echo.
echo  ============================================================
echo   Acquira — Database Initialisation
echo  ============================================================
echo   Host     : %PGHOST%:%PGPORT%
echo   Database : %PGDATABASE%
echo   User     : %PGUSER%
echo  ============================================================
echo.

REM ── Verify psql ─────────────────────────────────────────────
psql --version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo  [ERROR] psql not found on PATH.
    echo          Install PostgreSQL client tools or add to PATH.
    pause & exit /b 1
)

goto :run_scripts

:exec_sql
    echo  Running: %~1
    psql -h %PGHOST% -p %PGPORT% -U %PGUSER% -d %PGDATABASE% -f "%~2" -v ON_ERROR_STOP=0 --echo-errors
    if %ERRORLEVEL% NEQ 0 (
        echo  [WARN] %~1 completed with warnings
    ) else (
        echo  [OK]   %~1
    )
    echo.
    goto :eof

:exec_inline
    echo  Running inline SQL: %~1
    psql -h %PGHOST% -p %PGPORT% -U %PGUSER% -d %PGDATABASE% -c "%~2" -v ON_ERROR_STOP=0
    if %ERRORLEVEL% NEQ 0 (
        echo  [WARN] inline SQL had warnings
    ) else (
        echo  [OK]   %~1
    )
    echo.
    goto :eof

:run_scripts

REM ── Step 1: schema.sql ──────────────────────────────────────
echo  [1/5] schema.sql (drop + create all tables)...
call :exec_sql "schema.sql" "%SQL_DIR%\schema.sql"

REM ── Step 2: data.sql ────────────────────────────────────────
echo  [2/5] data.sql (seed data + all menus incl. S3)...
call :exec_sql "data.sql" "%SQL_DIR%\data.sql"

REM ── Step 3: S3 tenant_setting migration ─────────────────────
echo  [3/5] V2026_03_27_01 (S3 index + ENCRYPTED type)...
if exist "%MIGRATION_DIR%\V2026_03_27_01__s3_tenant_settings.sql" (
    call :exec_sql "V2026_03_27_01" "%MIGRATION_DIR%\V2026_03_27_01__s3_tenant_settings.sql"
) else (
    echo  [SKIP] File not found
    echo.
)

REM ── Step 4: S3 menu migration ───────────────────────────────
echo  [4/5] V2026_03_27_02 (S3 menu + RBAC)...
if exist "%MIGRATION_DIR%\V2026_03_27_02__s3_settings_menu.sql" (
    call :exec_sql "V2026_03_27_02" "%MIGRATION_DIR%\V2026_03_27_02__s3_settings_menu.sql"
) else (
    echo  [SKIP] File not found
    echo.
)

REM ── Step 5: Inline SQL — guarantee S3 menu exists ───────────
echo  [5/5] Inline fix: ensuring S3 menu + RBAC grants exist...

psql -h %PGHOST% -p %PGPORT% -U %PGUSER% -d %PGDATABASE% -v ON_ERROR_STOP=0 -c ^
"INSERT INTO sys_menu (menu_name, path, icon_key, category, display_order) VALUES ('S3 Report Storage', '/admin/s3-settings', 'Cloud', 'ADMINISTRATION', 7) ON CONFLICT (path) DO UPDATE SET menu_name='S3 Report Storage', icon_key='Cloud', category='ADMINISTRATION', display_order=7;"

psql -h %PGHOST% -p %PGPORT% -U %PGUSER% -d %PGDATABASE% -v ON_ERROR_STOP=0 -c ^
"INSERT INTO sys_group_menu (group_id, menu_id) SELECT g.group_id, m.menu_id FROM sys_user_group g, sys_menu m WHERE g.group_name = 'Super Admin' AND m.path = '/admin/s3-settings' ON CONFLICT DO NOTHING;"

psql -h %PGHOST% -p %PGPORT% -U %PGUSER% -d %PGDATABASE% -v ON_ERROR_STOP=0 -c ^
"INSERT INTO sys_group_menu (group_id, menu_id) SELECT g.group_id, m.menu_id FROM sys_user_group g, sys_menu m WHERE g.group_name = 'Bank Admin' AND m.path = '/admin/s3-settings' ON CONFLICT DO NOTHING;"

echo  [OK] S3 menu guaranteed
echo.

REM ── Verify ──────────────────────────────────────────────────
echo  Verifying S3 menu...
psql -h %PGHOST% -p %PGPORT% -U %PGUSER% -d %PGDATABASE% -c ^
"SELECT m.menu_name, m.path, m.category, m.display_order, string_agg(g.group_name, ', ') AS groups FROM sys_menu m LEFT JOIN sys_group_menu gm ON gm.menu_id = m.menu_id LEFT JOIN sys_user_group g ON g.group_id = gm.group_id WHERE m.path = '/admin/s3-settings' GROUP BY m.menu_name, m.path, m.category, m.display_order;"

echo.
echo  ============================================================
echo   DATABASE INIT COMPLETE
echo  ============================================================
echo.
echo   Login : http://localhost:5173
echo   User  : admin  /  Password: admin123
echo   S3    : http://localhost:5173/admin/s3-settings
echo.
pause
