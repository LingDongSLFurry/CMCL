@echo off
setlocal
set "cairn_dir=%~dp0"
for /f "tokens=2 delims=:" %%P in ('chcp') do set "cairn_old_cp=%%P"
chcp 65001 >nul
set "NO_COLOR=1"
if not exist "%cairn_dir%cairn.jar" (
  echo Cannot find cairn.jar. Put this script next to cairn.jar.
  pause
  if defined cairn_old_cp chcp %cairn_old_cp% >nul
  exit /b 1
)
where java >nul 2>nul
if errorlevel 1 (
  echo Please install Java 17 or newer, then run this script again.
  pause
  if defined cairn_old_cp chcp %cairn_old_cp% >nul
  exit /b 1
)
pushd "%cairn_dir%"
java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dcairn.console.encoding=UTF-8 -jar "%cairn_dir%cairn.jar" --plain %*
set "cairn_exit=%errorlevel%"
popd
if defined cairn_old_cp chcp %cairn_old_cp% >nul
if not "%cairn_exit%"=="0" (
  echo.
  echo Launcher exited with code %cairn_exit%. Java 17 or newer is required.
  if "%~1"=="" pause
)
exit /b %cairn_exit%
