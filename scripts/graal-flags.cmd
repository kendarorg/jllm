@echo off
rem see graal-flags.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0graal-flags.ps1" %*
exit /b %errorlevel%
