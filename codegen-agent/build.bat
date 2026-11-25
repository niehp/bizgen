@echo off
setlocal
REM JDT / SQLite 依存を持つため、ビルドは Maven に統一する。
REM 初回取得後は mvn -o package でオフラインビルドも可能。
set "ROOT=%~dp0"
where mvn >nul 2>&1
if errorlevel 1 (
  echo [BUILD FAILED] Maven ga mitsukarimasen. JDT / SQLite no dependency kaiketsu ni Maven ga hitsuyou desu.
  exit /b 1
)
pushd "%ROOT%"
call mvn -q -DskipTests package
if errorlevel 1 (
  popd
  echo [BUILD FAILED] mvn package error
  exit /b 1
)
popd
set "JAR=%ROOT%target\bizgen-agent.jar"
if not exist "%JAR%" (
  echo [BUILD FAILED] %JAR% ga sakusei saremashita
  exit /b 1
)
echo [BUILD OK] %JAR%
echo [RUN] java -jar "%JAR%"
endlocal
