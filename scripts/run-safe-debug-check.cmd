@echo off
setlocal
set "ADB_MDNS_AUTO_CONNECT=0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-safe-debug-check.ps1" %*
exit /b %ERRORLEVEL%
