@echo off
setlocal
chcp 65001 >nul 2>&1
cd /d "%~dp0"

where node >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Node.js not found. Please install Node.js 18 or later.
  echo         https://nodejs.org/
  exit /b 1
)

node "%~dp0cli\generate-insert.mjs" %*
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%
