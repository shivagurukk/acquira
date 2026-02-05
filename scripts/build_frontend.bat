@echo off
echo Installing and Building Acquira Frontend...
cd ../frontend
call npm install
call npm run build
if %ERRORLEVEL% NEQ 0 (
    echo Frontend Build failed!
    pause
    exit /b %ERRORLEVEL%
)
echo Frontend Build successful!
pause
