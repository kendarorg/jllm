@echo off
rem see run-eval.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-eval.ps1" %*
exit /b %errorlevel%
