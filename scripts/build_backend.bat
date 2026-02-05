@echo off
echo Building Acquira Backend (Clean Install)...
cd ..
mvn clean install
if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    pause
    exit /b %ERRORLEVEL%
)
echo Build successful!
pause
