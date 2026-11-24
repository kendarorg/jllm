@echo off
rem see run-web.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-web.ps1" %*
exit /b %errorlevel%
