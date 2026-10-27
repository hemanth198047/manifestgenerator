@echo off
rem Runs any casc-generator command and returns its exit code.
setlocal
set "JAR=%~dp0..\..\build\libs\casc-generator.jar"
if not exist "%JAR%" (
    echo Jar not found: %JAR% ^(run gradlew.bat jar first^) 1>&2
    exit /b 2
)
java %JAVA_OPTS% -jar "%JAR%" %*
exit /b %ERRORLEVEL%
