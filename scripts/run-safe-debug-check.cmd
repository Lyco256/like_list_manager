@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-safe-debug-check.ps1" %*
exit /b %ERRORLEVEL%
