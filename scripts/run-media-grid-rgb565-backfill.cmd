@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-media-grid-rgb565-backfill.ps1" %*
exit /b %ERRORLEVEL%

