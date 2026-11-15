@echo off
cd /d "%~dp0"

echo ============================================
echo   Template Studio
echo ============================================

netstat -ano | findstr ":3001" | findstr "LISTENING" >nul 2>&1
if %errorlevel%==0 (
  echo Server is already running on port 3001.
  start "" http://localhost:3001
  echo Browser opened: http://localhost:3001
  timeout /t 3 >nul
  exit /b 0
)

if not exist "node_modules\" (
  echo [1/3] Installing dependencies ...
  call npm install || goto :fail
)
if not exist "dist\index.html" (
  echo [2/3] Building frontend ...
  call npm run build || goto :fail
)

echo [3/3] Starting server ...
start "" cmd /c "timeout /t 3 /nobreak >nul && start http://localhost:3001"
echo.
echo   URL  : http://localhost:3001
echo   Stop : press Ctrl+C in this window
echo.
call npm start
goto :eof

:fail
echo.
echo Setup failed. Please check the error message above.
pause
