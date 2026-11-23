@echo off
rem see fetch-sql-driver.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0fetch-sql-driver.ps1" %*
exit /b %errorlevel%
