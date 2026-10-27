@echo off
rem Starts the web UI from any working directory. Extra JVM flags: JAVA_OPTS. Extra arguments are passed on.
setlocal
set "JAR=%~dp0..\..\build\libs\casc-generator.jar"
if not exist "%JAR%" (
    echo Jar not found: %JAR% ^(run gradlew.bat jar first^) 1>&2
    exit /b 2
)
java %JAVA_OPTS% -jar "%JAR%" web %*
exit /b %ERRORLEVEL%
