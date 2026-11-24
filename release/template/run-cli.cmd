@echo off
cd /d "%~dp0"
java -version 2>&1 | findstr /r /c:"version \"2[1-9]" /c:"version \"[3-9][0-9]" >nul || (echo Java 21 or newer is required & exit /b 1)
rem GraalJS (run_js) comes from lib-ext; an empty lib-ext starts normally and run_js reports why it is off
java -cp "jllm-cli.jar;lib-ext\*" org.kendar.llm.cli.Main --plugin-dir .\plugins --settings .\settings\settings.json %*
