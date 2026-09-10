@echo off
:: face-idl-binder.bat — Windows launcher for the FACE IDL Binder
::
:: Layout expected (relative to this script):
::   ..\lib\face-idl-binder-*.jar
::   ..\templates\languages\  (language-binding Velocity templates)
::   ..\face-idl\             (FACE framework IDL files)

setlocal

:: Resolve install root from this script's location
set "FACE_IDL_BINDER_HOME=%~dp0.."

:: Find the fat JAR (there should be exactly one)
for %%F in ("%FACE_IDL_BINDER_HOME%\lib\face-idl-binder-*.jar") do set "FACE_JAR=%%F"

if not defined FACE_JAR (
    echo ERROR: could not locate face-idl-binder jar in %FACE_IDL_BINDER_HOME%\lib\
    exit /b 1
)

java -jar "%FACE_JAR%" %*

endlocal
