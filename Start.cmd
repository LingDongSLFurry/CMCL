@echo off
setlocal
chcp 65001 >nul
where java >nul 2>nul
if errorlevel 1 (
  echo Please install Java 17 or newer, then run this script again.
  pause
  exit /b 1
)
pushd "%~dp0"
java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -jar "%~dp0cairn.jar" %*
set "cairn_exit=%errorlevel%"
popd
if not "%cairn_exit%"=="0" (
  echo.
  echo Launcher exited with code %cairn_exit%. Java 17 or newer is required.
  if "%~1"=="" pause
)
exit /b %cairn_exit%
