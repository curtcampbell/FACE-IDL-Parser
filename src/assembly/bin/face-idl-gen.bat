@echo off
:: face-idl-gen.bat -- Windows launcher for the FACE IDL tools.
::
:: Layout expected (relative to this script):
::   ..\lib\face-idl-gen-*.jar
::   ..\templates\           (Velocity template roots)
::   ..\face-idl\            (FACE framework IDL files)
::   ..\conf\logging.properties
::
:: Resolves the install root from this script's own location, so the
:: distribution can be unzipped anywhere.

setlocal

set "INSTALL_ROOT=%~dp0.."

:: Locate the fat JAR by glob -- never by a hardcoded version.
set "FACE_JAR="
for %%F in ("%INSTALL_ROOT%\lib\face-idl-gen-*.jar") do set "FACE_JAR=%%F"

if not defined FACE_JAR (
    echo ERROR: could not locate face-idl-gen jar in %INSTALL_ROOT%\lib\ 1>&2
    endlocal
    exit /b 1
)

if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java"
)

:: Logging configuration; override with FACE_IDL_TOOLS_LOGGING.
if defined FACE_IDL_TOOLS_LOGGING (
    set "LOG_CONF=%FACE_IDL_TOOLS_LOGGING%"
) else (
    set "LOG_CONF=%INSTALL_ROOT%\conf\logging.properties"
)

if exist "%LOG_CONF%" (
    "%JAVA_EXE%" -Djava.util.logging.config.file="%LOG_CONF%" -jar "%FACE_JAR%" %*
) else (
    "%JAVA_EXE%" -jar "%FACE_JAR%" %*
)

endlocal & exit /b %ERRORLEVEL%
