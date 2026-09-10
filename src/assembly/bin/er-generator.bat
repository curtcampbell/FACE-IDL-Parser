@echo off
:: er-generator.bat — Windows launcher for the Entity Reactor IDL Generator
::
:: Layout expected (relative to this script):
::   ..\lib\er-generator-*.jar
::   ..\templates\           (default IDL template root)

setlocal

:: Resolve install root from this script's location
set "ER_GENERATOR_HOME=%~dp0.."

:: Find the fat JAR (there should be exactly one)
for %%F in ("%ER_GENERATOR_HOME%\lib\er-generator-*.jar") do set "ER_JAR=%%F"

if not defined ER_JAR (
    echo ERROR: could not locate er-generator jar in %ER_GENERATOR_HOME%\lib\
    exit /b 1
)

java -jar "%ER_JAR%" %*

endlocal
