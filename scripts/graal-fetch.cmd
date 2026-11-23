@echo off
rem see graal-fetch.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0graal-fetch.ps1" %*
exit /b %errorlevel%
