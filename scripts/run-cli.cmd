@echo off
rem see run-cli.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-cli.ps1" %*
exit /b %errorlevel%
