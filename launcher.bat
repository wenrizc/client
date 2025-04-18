@echo off

set JAR_PATH=gamehall-client-1.0.0.jar

if not exist "%JAR_PATH%" (
    echo error: can't find %JAR_PATH% 
    pause
    exit /b 1
)

java --add-opens java.base/java.lang.reflect=ALL-UNNAMED -jar %JAR_PATH%

if %ERRORLEVEL% neq 0 (
    echo.
    echo error
    pause
)