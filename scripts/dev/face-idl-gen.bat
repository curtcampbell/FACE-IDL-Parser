@echo off
:: face-idl-gen.bat -- DEV launcher: runs the tool straight out of the build tree.
:: Not part of the distribution.  Run 'mvn package' first.

setlocal

set "REPO_ROOT=%~dp0..\.."

set "FACE_JAR="
for %%F in ("%REPO_ROOT%\target\face-idl-gen-*.jar") do set "FACE_JAR=%%F"

if not defined FACE_JAR (
    echo ERROR: no face-idl-gen jar in %REPO_ROOT%\target\ 1>&2
    endlocal
    exit /b 1
)

java -Djava.util.logging.config.file="%REPO_ROOT%\logging.properties" -jar "%FACE_JAR%" %*

endlocal & exit /b %ERRORLEVEL%
