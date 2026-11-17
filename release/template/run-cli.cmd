@echo off
cd /d "%~dp0"
java -version 2>&1 | findstr /r /c:"version \"2[1-9]" /c:"version \"[3-9][0-9]" >nul || (echo Java 21 or newer is required & exit /b 1)
java -jar jllm-cli.jar --plugin-dir .\plugins --settings .\settings\settings.json %*
