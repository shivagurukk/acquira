@echo off
REM ============================================================
REM  Fix S3 Report Storage not showing in sidebar
REM  Run this once to add the menu to the database.
REM ============================================================

set "PGHOST=127.0.0.1"
set "PGPORT=5433"
set "PGDATABASE=postgres"
set "PGUSER=postgres"
set "PGPASSWORD=postgres"

cd /d "%~dp0"

echo.
echo  Fixing S3 Report Storage sidebar menu...
echo.

psql -h %PGHOST% -p %PGPORT% -U %PGUSER% -d %PGDATABASE% -f "%~dp0fix_s3_menu.sql"

echo.
echo  Done. Refresh the browser to see the S3 menu in the sidebar.
echo  If it still doesn't appear, log out and log back in.
echo.
pause
