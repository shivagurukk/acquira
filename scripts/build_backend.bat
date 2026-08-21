@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM  Acquira — Build Backend (all modules)
REM  Runs: mvn clean install -DskipTests from project root
REM ============================================================

cd /d "%~dp0.."
set "ROOT=%CD%"

echo.
echo  ============================================================
echo   Acquira Backend Build
echo  ============================================================
echo   Root : %ROOT%
echo   Goal : mvn clean install (all modules)
echo  ============================================================
echo.

REM ── Verify Maven ────────────────────────────────────────────
mvn -version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo  [ERROR] Maven not found. Please install Maven 3.9+
    pause & exit /b 1
)

REM ── Verify Java ─────────────────────────────────────────────
java -version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo  [ERROR] Java 21+ not found.
    pause & exit /b 1
)

echo  Building all modules:
echo    acquira-common  (shared models + repos)
echo    acquira-batch   (upload + ingestion)
echo    acquira-pdf     (Playwright PDF engine)
echo    acquira-ai      (AI assistant)
echo    acquira-core    (main app - port 8081)
echo.

call mvn clean install -DskipTests

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo  ============================================================
    echo   BUILD FAILED
    echo   Tip: Run with -e for error details
    echo        mvn clean install -DskipTests -e
    echo  ============================================================
    pause & exit /b %ERRORLEVEL%
)

echo.
echo  ============================================================
echo   BUILD SUCCESSFUL
echo   JAR: acquira-core\target\acquira-core-*.jar
echo  ============================================================
echo.
echo  Next: run start_backend.bat or start_all.bat
echo.
pause
