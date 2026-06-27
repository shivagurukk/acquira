@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM  Acquira — Full Setup Script (Fresh Install)
REM
REM  Runs everything in order:
REM    1. Checks prerequisites (Java, Maven, Node, psql)
REM    2. Creates runtime directories
REM    3. DB init (schema + seed + S3 migrations + inline S3 fix)
REM    4. Builds all Maven modules
REM    5. Installs frontend npm packages
REM    6. Starts backend  (new window, port 8081)
REM    7. Starts frontend (new window, port 5173)
REM
REM  Usage:
REM    setup.bat
REM    setup.bat mydb myuser mypass
REM    setup.bat mydb myuser mypass 127.0.0.1 5433
REM ============================================================

cd /d "%~dp0.."
set "ROOT=%CD%"
set "SCRIPTS=%ROOT%\scripts"

set "DB_NAME=%~1"
set "DB_USER=%~2"
set "DB_PASS=%~3"
set "DB_HOST=%~4"
set "DB_PORT=%~5"
if "%DB_NAME%"=="" set "DB_NAME=postgres"
if "%DB_USER%"=="" set "DB_USER=postgres"
if "%DB_PASS%"=="" set "DB_PASS=postgres"
if "%DB_HOST%"=="" set "DB_HOST=127.0.0.1"
if "%DB_PORT%"=="" set "DB_PORT=5433"

if "%APP_ENCRYPTION_KEY%"=="" set "APP_ENCRYPTION_KEY=AcquiraDefaultEncryptKey32Chars!!"

echo.
echo  ============================================================
echo   Acquira CMS — Full Setup
echo  ============================================================
echo   Root     : %ROOT%
echo   DB       : %DB_NAME% @ %DB_HOST%:%DB_PORT%
echo   Backend  : http://localhost:8081
echo   Frontend : http://localhost:5173
echo   S3 UI    : http://localhost:5173/admin/s3-settings
echo  ============================================================
echo.

REM ════════════════════════════════════════════════════════════
REM  1 — Prerequisites
REM ════════════════════════════════════════════════════════════
echo  [1/7] Checking prerequisites...

java -version >nul 2>&1
if %ERRORLEVEL% NEQ 0 ( echo   [FAIL] Java not found & pause & exit /b 1 )
echo   [OK] Java

mvn -version >nul 2>&1
if %ERRORLEVEL% NEQ 0 ( echo   [FAIL] Maven not found & pause & exit /b 1 )
echo   [OK] Maven

node -v >nul 2>&1
if %ERRORLEVEL% NEQ 0 ( echo   [FAIL] Node.js not found & pause & exit /b 1 )
echo   [OK] Node.js

psql --version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo   [WARN] psql not found — skipping DB init
    set "SKIP_DB=true"
) else (
    echo   [OK] psql
    set "SKIP_DB=false"
)
echo.

REM ════════════════════════════════════════════════════════════
REM  2 — Runtime directories
REM ════════════════════════════════════════════════════════════
echo  [2/7] Creating runtime directories...
if not exist "%ROOT%\reports" mkdir "%ROOT%\reports"
if not exist "%ROOT%\logs"    mkdir "%ROOT%\logs"
if not exist "%ROOT%\data"    mkdir "%ROOT%\data"
if not exist "%ROOT%\uploads" mkdir "%ROOT%\uploads"
echo   [OK] reports\ logs\ data\ uploads\
echo.

REM ════════════════════════════════════════════════════════════
REM  3 — Database init (schema + data + S3 migrations + fix)
REM ════════════════════════════════════════════════════════════
echo  [3/7] Initialising database...
if "%SKIP_DB%"=="true" (
    echo   [SKIP] psql not available
) else (
    REM Run full db_init (includes S3 menu migration + inline fix)
    call "%SCRIPTS%\db_init.bat" %DB_NAME% %DB_USER% %DB_PASS% %DB_HOST% %DB_PORT%

    REM Extra inline guarantee: insert S3 menu + grant to Super Admin
    REM (runs even if db_init had partial failures)
    set "PGPASSWORD=%DB_PASS%"
    echo   Extra guarantee: S3 menu + RBAC...
    psql -h %DB_HOST% -p %DB_PORT% -U %DB_USER% -d %DB_NAME% -v ON_ERROR_STOP=0 -c ^
    "INSERT INTO sys_menu (menu_name,path,icon_key,category,display_order) VALUES ('S3 Report Storage','/admin/s3-settings','Cloud','ADMINISTRATION',7) ON CONFLICT (path) DO UPDATE SET menu_name='S3 Report Storage',icon_key='Cloud',category='ADMINISTRATION',display_order=7;" >nul 2>&1
    psql -h %DB_HOST% -p %DB_PORT% -U %DB_USER% -d %DB_NAME% -v ON_ERROR_STOP=0 -c ^
    "INSERT INTO sys_group_menu(group_id,menu_id) SELECT g.group_id,m.menu_id FROM sys_user_group g,sys_menu m WHERE g.group_name='Super Admin' AND m.path='/admin/s3-settings' ON CONFLICT DO NOTHING;" >nul 2>&1
    psql -h %DB_HOST% -p %DB_PORT% -U %DB_USER% -d %DB_NAME% -v ON_ERROR_STOP=0 -c ^
    "INSERT INTO sys_group_menu(group_id,menu_id) SELECT g.group_id,m.menu_id FROM sys_user_group g,sys_menu m WHERE g.group_name='Super Admin' ON CONFLICT DO NOTHING;" >nul 2>&1
    echo   [OK] S3 menu guaranteed in database
)
echo.

REM ════════════════════════════════════════════════════════════
REM  4 — Maven build
REM ════════════════════════════════════════════════════════════
echo  [4/7] Building all Maven modules...
cd "%ROOT%"
call mvn clean install -DskipTests
if %ERRORLEVEL% NEQ 0 (
    echo   [FAIL] Maven build failed
    pause & exit /b %ERRORLEVEL%
)
echo   [OK] Build complete
echo.

REM ════════════════════════════════════════════════════════════
REM  5 — npm install
REM ════════════════════════════════════════════════════════════
echo  [5/7] Installing frontend dependencies...
cd "%ROOT%\frontend"
call npm install
if %ERRORLEVEL% NEQ 0 (
    echo   [FAIL] npm install failed
    pause & exit /b %ERRORLEVEL%
)
echo   [OK] npm done
echo.

REM ════════════════════════════════════════════════════════════
REM  6 — Start backend (with S3 encryption key)
REM ════════════════════════════════════════════════════════════
echo  [6/7] Starting backend (port 8081)...
cd "%ROOT%\acquira-core"
start "Acquira Backend [8081]" cmd /k ^
    "set APP_ENCRYPTION_KEY=%APP_ENCRYPTION_KEY% && mvn spring-boot:run -Dspring-boot.run.jvmArguments=""-Xmx2048m -Dspring.context.expression.maxLength=500000 -DAPP_ENCRYPTION_KEY=%APP_ENCRYPTION_KEY%"""
echo   [OK] Backend starting in new window
echo.

timeout /t 4 /nobreak >nul

REM ════════════════════════════════════════════════════════════
REM  7 — Start frontend
REM ════════════════════════════════════════════════════════════
echo  [7/7] Starting frontend (port 5173)...
cd "%ROOT%\frontend"
start "Acquira Frontend [5173]" cmd /k "npm run dev"
echo   [OK] Frontend starting in new window
echo.

REM ════════════════════════════════════════════════════════════
REM  Done
REM ════════════════════════════════════════════════════════════
echo  ============================================================
echo   Setup Complete!
echo  ============================================================
echo   Frontend : http://localhost:5173
echo   Backend  : http://localhost:8081
echo   Login    : admin / admin123
echo   S3 Setup : http://localhost:5173/admin/s3-settings
echo   SMTP     : http://localhost:5173/admin/smtp-settings
echo  ============================================================
echo.
echo   Wait ~30s for backend to fully start, then open the frontend.
echo.
pause
