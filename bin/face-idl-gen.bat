@echo off
java -Djava.util.logging.config.file="%~dp0..\logging.properties" ^
     -jar "%~dp0..\target\face-idl-gen-0.1.0-SNAPSHOT.jar" %*
