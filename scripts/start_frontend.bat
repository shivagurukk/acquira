@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM  Acquira — Start Frontend (Vite / React)
REM  Port 5173
REM ============================================================

cd /d "%~dp0..\frontend"
set "FRONTEND=%CD%"

echo.
echo  ============================================================
echo   Acquira Frontend Startup
echo  ============================================================
echo   Dir  : %FRONTEND%
echo   Port : 5173 (Vite dev server)
echo  ============================================================
echo.

REM ── Verify Node ─────────────────────────────────────────────
node -v >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo  [ERROR] Node.js not found. Install Node 18+
    pause & exit /b 1
)

REM ── Install deps if node_modules missing ────────────────────
if not exist "node_modules" (
    echo  [1/2] node_modules not found — running npm install...
    call npm install
    if %ERRORLEVEL% NEQ 0 (
        echo  [ERROR] npm install failed
        pause & exit /b %ERRORLEVEL%
    )
) else (
    echo  [1/2] node_modules found — skipping install
)

REM ── Start Vite ──────────────────────────────────────────────
echo  [2/2] Starting Vite dev server on http://localhost:5173
echo.
call npm run dev

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo  [ERROR] Frontend failed to start
    pause & exit /b %ERRORLEVEL%
)

pause
