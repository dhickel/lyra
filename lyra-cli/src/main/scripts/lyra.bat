@echo off
setlocal

rem The CLI is compiled for Java 25. Set LYRA_JAVA_OPTS=--enable-preview only
rem when starting a preview-requiring artifact.
set "SCRIPT_DIR=%~dp0"
set "CLI_JAR="
if exist "%SCRIPT_DIR%lyra-cli-1.0-SNAPSHOT.jar" set "CLI_JAR=%SCRIPT_DIR%lyra-cli-1.0-SNAPSHOT.jar"
if not defined CLI_JAR if exist "%SCRIPT_DIR%..\lyra-cli-1.0-SNAPSHOT.jar" set "CLI_JAR=%SCRIPT_DIR%..\lyra-cli-1.0-SNAPSHOT.jar"
if not defined CLI_JAR if exist "%SCRIPT_DIR%..\lib\lyra-cli-1.0-SNAPSHOT.jar" set "CLI_JAR=%SCRIPT_DIR%..\lib\lyra-cli-1.0-SNAPSHOT.jar"
if not defined CLI_JAR if exist "%SCRIPT_DIR%..\target\lyra-cli-1.0-SNAPSHOT.jar" set "CLI_JAR=%SCRIPT_DIR%..\target\lyra-cli-1.0-SNAPSHOT.jar"
if not defined CLI_JAR if exist "%SCRIPT_DIR%..\..\..\target\lyra-cli-1.0-SNAPSHOT.jar" set "CLI_JAR=%SCRIPT_DIR%..\..\..\target\lyra-cli-1.0-SNAPSHOT.jar"
if not defined CLI_JAR if exist "%SCRIPT_DIR%..\..\lyra-cli\target\lyra-cli-1.0-SNAPSHOT.jar" set "CLI_JAR=%SCRIPT_DIR%..\..\lyra-cli\target\lyra-cli-1.0-SNAPSHOT.jar"

if not defined CLI_JAR (
    >&2 echo lyra: cannot locate lyra-cli-1.0-SNAPSHOT.jar
    exit /b 2
)

if not defined JAVA_COMMAND set "JAVA_COMMAND=java"
if defined LYRA_JAVA_OPTS (
    "%JAVA_COMMAND%" %LYRA_JAVA_OPTS% -jar "%CLI_JAR%" %*
) else (
    "%JAVA_COMMAND%" -jar "%CLI_JAR%" %*
)
exit /b %ERRORLEVEL%
