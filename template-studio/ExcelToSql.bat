REM Excel + spec mapping -> sql
REM --mapping <保存名|ファイルID|JSONパス> --excel <Excelパス> [オプション]
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

node "%~dp0cli\ExcelToSql.mjs" %*
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%
