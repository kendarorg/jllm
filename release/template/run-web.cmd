@echo off
cd /d "%~dp0"
java -version 2>&1 | findstr /r /c:"version \"2[1-9]" /c:"version \"[3-9][0-9]" >nul || (echo Java 21 or newer is required & exit /b 1)
echo Open http://localhost:8080  (admin/admin, poweru/poweru, guest/guest)
rem GraalJS (run_js) comes from lib-ext, the SQL store from lib-ext\sql, through the loader path; an empty lib-ext starts normally
rem (MySQL only: fetch-sql-driver.cmd puts its driver into .downloaded-libs, see application.properties)
java -Dloader.path=lib-ext,lib-ext/sql -jar jllm-web.jar --jllm.plugin-dir=.\plugins --jllm.settings=.\settings\settings.json --jllm.webauth-dir=.\webauth %*
