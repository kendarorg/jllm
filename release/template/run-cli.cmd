@echo off
rem Terminal chat. First argument (optional): the configuration, cliSafe (default) or unsafe (development and testing only).
cd /d "%~dp0"
set "CFG=cliSafe"
set "FIRST=%~1"
if defined FIRST if not "%FIRST:~0,1%"=="-" (set "CFG=%FIRST%" & shift)
if not exist "configs\%CFG%\settings.json" (echo unknown configuration "%CFG%" - see configs: cliSafe, unsafe & exit /b 1)
if "%CFG%"=="unsafe" echo WARNING: unsafe configuration: no sandbox, the model can read, change or destroy anything you can. Development and testing only. 1>&2
java -version 2>&1 | findstr /r /c:"version \"2[1-9]" /c:"version \"[3-9][0-9]" >nul || (echo Java 21 or newer is required & exit /b 1)
set "EXTRA="
if "%CFG%"=="cliSafe" set "EXTRA=--mode default --sandbox docker"
rem GraalJS (run_js) comes from lib-ext; an empty lib-ext starts normally and run_js reports why it is off
java -cp "jllm-cli.jar;lib-ext\*" org.kendar.llm.cli.Main --plugin-dir .\plugins --settings ".\configs\%CFG%\settings.json" %EXTRA% %1 %2 %3 %4 %5 %6 %7 %8 %9
