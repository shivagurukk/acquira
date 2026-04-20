@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM  Acquira — Start Backend (acquira-core)
REM  Port 8081 — Auth, Analytics, PDF, Batch, Admin, S3
REM
REM  What this script does:
REM    1. Checks Java + Maven are installed
REM    2. Creates runtime directories (reports, logs, data, uploads)
REM    3. Sets S3 encryption key environment variable
REM    4. Runs mvn clean install -DskipTests (refreshes .m2 cache)
REM    5. Starts acquira-core on port 8081
REM
REM  S3 Settings are configured via the UI at:
REM    http://localhost:5173/admin/s3-settings
REM  Credentials are encrypted with APP_ENCRYPTION_KEY before
REM  being stored in the database. Change the key below in prod.
REM ============================================================

cd /d "%~dp0.."
set "ROOT=%CD%"

echo.
echo  ============================================================
echo   Acquira Backend Startup
echo  ============================================================
echo   Root   : %ROOT%
echo   Module : acquira-core  (port 8081)
echo  ============================================================
echo.

REM ── Step 1: Verify Java ─────────────────────────────────────
java -version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo  [ERROR] Java not found. Please install Java 21+
    echo          Download: https://adoptium.net
    pause & exit /b 1
)

REM ── Step 2: Verify Maven ────────────────────────────────────
mvn -version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo  [ERROR] Maven not found. Please install Maven 3.9+
    echo          Download: https://maven.apache.org/download.cgi
    pause & exit /b 1
)

REM ── Step 3: Create required runtime directories ─────────────
echo  [1/4] Creating runtime directories...
if not exist "%ROOT%\reports"  mkdir "%ROOT%\reports"
if not exist "%ROOT%\logs"     mkdir "%ROOT%\logs"
if not exist "%ROOT%\data"     mkdir "%ROOT%\data"
if not exist "%ROOT%\uploads"  mkdir "%ROOT%\uploads"
echo        reports\  logs\  data\  uploads\ — OK
echo.

REM ── Step 4: Set S3 encryption key ───────────────────────────
REM  This key encrypts S3 credentials stored in the database.
REM  MUST be exactly 32 characters for AES-256.
REM  Change this to a strong random value in production!
REM  You can also set it as a system environment variable
REM  instead of hardcoding it here.
echo  [2/4] Setting S3 encryption key...
if "%APP_ENCRYPTION_KEY%"=="" (
    set "APP_ENCRYPTION_KEY=AcquiraDefaultEncryptKey32Chars!!"
    echo        Using default key — CHANGE THIS IN PRODUCTION
) else (
    echo        Using key from environment variable
)
echo.

REM ── Step 5: Full clean install ──────────────────────────────
REM  This rebuilds all modules and refreshes the .m2 cache.
REM  Prevents "S3Uploader.class not found" startup errors
REM  caused by stale JARs from previous builds.
echo  [3/4] Building all modules (mvn clean install -DskipTests)...
echo        acquira-common → acquira-pdf → acquira-batch → acquira-core
echo.
call mvn clean install -DskipTests
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo  ============================================================
    echo   [ERROR] Build failed — fix errors above before starting
    echo  ============================================================
    pause & exit /b %ERRORLEVEL%
)
echo.
echo  [3/4] Build successful
echo.

REM ── Step 6: Launch Spring Boot ──────────────────────────────
echo  [4/4] Starting acquira-core on http://localhost:8081
echo.
echo   Features enabled:
echo    - PDF generation  (Playwright)
echo    - S3 archiving    (configure at /admin/s3-settings)
echo    - Email sending   (configure SMTP at /admin/smtp-settings)
echo    - Encryption key  : APP_ENCRYPTION_KEY is set
echo.

cd "%ROOT%\acquira-core"
mvn spring-boot:run ^
    -Dspring-boot.run.jvmArguments="-Xmx2048m -Dspring.context.expression.maxLength=500000 -DAPP_ENCRYPTION_KEY=%APP_ENCRYPTION_KEY%"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo  ============================================================
    echo   [ERROR] Backend failed to start
    echo   Check: %ROOT%\logs\core.log
    echo  ============================================================
    pause & exit /b %ERRORLEVEL%
)

pause
