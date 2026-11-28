@echo off
rem Web app on http://localhost:8080. First argument (optional): the configuration, webSafe (default) or unsafe (development and testing only).
cd /d "%~dp0"
set "CFG=webSafe"
set "FIRST=%~1"
if defined FIRST if not "%FIRST:~0,1%"=="-" (set "CFG=%FIRST%" & shift)
if not exist "configs\%CFG%\config\settings.json" (echo unknown configuration "%CFG%" - see configs: webSafe, unsafe & exit /b 1)
if not exist "configs\%CFG%\webauth" (echo unknown configuration "%CFG%" - see configs: webSafe, unsafe & exit /b 1)
java -version 2>&1 | findstr /r /c:"version \"2[1-9]" /c:"version \"[3-9][0-9]" >nul || (echo Java 21 or newer is required & exit /b 1)
if "%CFG%"=="unsafe" (
  echo WARNING: unsafe configuration: local shell with full host access, default passwords. Development and testing only. 1>&2
  echo Open http://localhost:8080  ^(admin/admin, poweru/poweru, guest/guest^)
) else (
  echo Open http://localhost:8080  ^(the administrator password is in the startup log, shown once^)
)
rem GraalJS (run_js) comes from lib-ext, the SQL store from lib-ext\sql, through the loader path; an empty lib-ext starts normally
rem (MySQL only: fetch-sql-driver.cmd puts its driver into .downloaded-libs, see configs\<name>\application.properties)
java -Dloader.path=lib-ext,lib-ext/sql -jar jllm-web.jar --spring.config.additional-location=file:./configs/%CFG%/ --jllm.plugin-dir=.\plugins --jllm.config-dir=.\configs\%CFG%\config --jllm.webauth-dir=.\configs\%CFG%\webauth --jllm.chats-dir=.\chats\%CFG% %1 %2 %3 %4 %5 %6 %7 %8 %9
