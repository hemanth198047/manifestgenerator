@echo off
rem Starts the web application from any working directory. Extra JVM flags: JAVA_OPTS.
setlocal
set "JAR=%~dp0..\..\build\libs\casc-generator.jar"
if not exist "%JAR%" (
    echo Jar not found: %JAR% ^(run gradlew.bat bootJar first^) 1>&2
    exit /b 2
)
java %JAVA_OPTS% -jar "%JAR%" %*
exit /b %ERRORLEVEL%
