@echo off
cd /d "%~dp0"
set "JAR=%~dp0target\postgres-router.jar"

if not exist "%JAR%" (
    echo [ERROR] JAR not found: %JAR%
    echo Run: mvn package -DskipTests
    pause
    exit /b 1
)

taskkill /fi "WINDOWTITLE eq MCP-Server" /f >nul 2>&1
taskkill /fi "WINDOWTITLE eq MCP-GUI" /f >nul 2>&1

netstat -ano | find ":18880 " | find "LISTENING" >nul 2>&1
if %errorlevel% neq 0 (
    start "MCP-Server" javaw -Dfile.encoding=UTF-8 -jar "%JAR%" --server
    for /L %%i in (1,1,30) do (
        timeout /t 1 /nobreak >nul
        netstat -ano | find ":18880 " | find "LISTENING" >nul 2>&1
        if not errorlevel 1 goto launch
    )
)

:launch
start "MCP-GUI" javaw -Dfile.encoding=UTF-8 -jar "%JAR%" --gui
