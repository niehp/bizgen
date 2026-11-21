@echo off
setlocal enabledelayedexpansion
REM Maven が無い環境でもビルドできる、JDK のみのビルドスクリプト。
set "ROOT=%~dp0"
set "SRC=%ROOT%src\main\java"
set "OUT=%ROOT%target\classes"
set "JAR=%ROOT%target\bizgen-agent.jar"

if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%" 2>nul
if not exist "%ROOT%target" mkdir "%ROOT%target" 2>nul

dir /s /b "%SRC%\*.java" > "%ROOT%target\sources.txt"
javac -encoding UTF-8 -source 1.8 -target 1.8 -d "%OUT%" @"%ROOT%target\sources.txt"
if errorlevel 1 (
  echo [BUILD FAILED] javac error
  exit /b 1
)

jar cfe "%JAR%" com.walk_nie.bizgen.agent.AgentApplication -C "%OUT%" .
if errorlevel 1 (
  echo [BUILD FAILED] jar error
  exit /b 1
)
echo [BUILD OK] %JAR%
echo [RUN] java -jar "%JAR%"
endlocal
