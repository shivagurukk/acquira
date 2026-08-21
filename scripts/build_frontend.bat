@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM  Acquira — Build Frontend
REM  Runs: npm install + npm run build
REM ============================================================

cd /d "%~dp0..\frontend"
set "FRONTEND=%CD%"

echo.
echo  ============================================================
echo   Acquira Frontend Build
echo  ============================================================
echo   Dir : %FRONTEND%
echo  ============================================================
echo.

REM ── Verify Node ─────────────────────────────────────────────
node -v >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo  [ERROR] Node.js not found. Install Node 18+
    pause & exit /b 1
)

echo  [1/2] Installing npm dependencies...
call npm install
if %ERRORLEVEL% NEQ 0 (
    echo  [ERROR] npm install failed
    pause & exit /b %ERRORLEVEL%
)

echo  [2/2] Building production bundle...
call npm run build
if %ERRORLEVEL% NEQ 0 (
    echo  [ERROR] Frontend build failed
    pause & exit /b %ERRORLEVEL%
)

echo.
echo  ============================================================
echo   FRONTEND BUILD SUCCESSFUL
echo   Output: frontend\dist\
echo  ============================================================
echo.
pause
