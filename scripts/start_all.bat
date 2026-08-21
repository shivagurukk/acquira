@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM  Acquira — Start Everything
REM  Launches backend in one window, frontend in another
REM ============================================================

cd /d "%~dp0"
set "SCRIPTS=%CD%"

echo.
echo  ============================================================
echo   Acquira — Full Stack Startup
echo  ============================================================
echo   Backend  : http://localhost:8081
echo   Frontend : http://localhost:5173
echo  ============================================================
echo.

REM ── Launch backend in new window ────────────────────────────
echo  [1/2] Launching backend (acquira-core)...
start "Acquira Backend" cmd /k "cd /d %SCRIPTS% && call start_backend.bat"

REM ── Small pause so backend window opens first ───────────────
timeout /t 3 /nobreak >nul

REM ── Launch frontend in new window ───────────────────────────
echo  [2/2] Launching frontend (Vite)...
start "Acquira Frontend" cmd /k "cd /d %SCRIPTS% && call start_frontend.bat"

echo.
echo  Both processes started in separate windows.
echo  Close those windows to stop each service.
echo.
pause
