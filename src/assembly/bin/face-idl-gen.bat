@echo off
:: face-idl-gen.bat — Windows launcher for the FACE IDL Generator
::
:: Layout expected (relative to this script):
::   ..\lib\face-idl-gen-*.jar
::   ..\templates\            (IDL template roots)
::   ..\face-idl\             (FACE framework IDL files)

setlocal

:: Resolve install root from this script's location
set "FACE_IDL_GEN_HOME=%~dp0.."

:: Find the fat JAR (there should be exactly one)
for %%F in ("%FACE_IDL_GEN_HOME%\lib\face-idl-gen-*.jar") do set "FACE_JAR=%%F"

if not defined FACE_JAR (
    echo ERROR: could not locate face-idl-gen jar in %FACE_IDL_GEN_HOME%\lib\
    exit /b 1
)

java -jar "%FACE_JAR%" %*

endlocal
