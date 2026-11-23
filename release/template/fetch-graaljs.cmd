@echo off
rem Usage: fetch-graaljs.cmd community|oracle [-AcceptOracleTerms]   (see fetch-graaljs.ps1)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0fetch-graaljs.ps1" %*
exit /b %errorlevel%
