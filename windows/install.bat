@echo off
setlocal
rem Bambu Widget installer: copies the app to %LOCALAPPDATA%\BambuWidget, installs dependencies, creates shortcuts.
set "SRC=%~dp0"
set "APPDIR=%LOCALAPPDATA%\BambuWidget"
set "NODEDIR=%ProgramFiles%\nodejs"

if not exist "%NODEDIR%\npm.cmd" (
    echo Installing Node.js via winget...
    winget install --id OpenJS.NodeJS.LTS -e --accept-source-agreements --accept-package-agreements
)
if not exist "%NODEDIR%\npm.cmd" (
    echo Node.js not found. Install it from https://nodejs.org and run this script again.
    pause & exit /b 1
)
set "PATH=%NODEDIR%;%PATH%"

echo Closing a running Bambu Widget, if any ...
powershell -NoProfile -Command "Get-Process electron -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '%APPDIR%\*' } | Stop-Process -Force"
echo Copying files to %APPDIR% ...
robocopy "%SRC%." "%APPDIR%" /E /XD node_modules /XF install.bat /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (echo Copy failed. & pause & exit /b 1)

cd /d "%APPDIR%"
echo Installing dependencies (downloads Electron, about 100 MB) ...
call npm install --no-audit --no-fund || (echo npm install failed. & pause & exit /b 1)

set "EXE=%APPDIR%\node_modules\electron\dist\electron.exe"
if not exist "%EXE%" (
    echo Downloading Electron binary ...
    "%NODEDIR%\node.exe" "%APPDIR%\node_modules\electron\install.js"
)
if not exist "%EXE%" (echo Electron download failed. Run this script again. & pause & exit /b 1)

echo Creating shortcuts ...
cscript //nologo "%APPDIR%\shortcuts.vbs" || echo Creating shortcuts failed.

echo.
echo Done. Close this window, then start the widget with the "Bambu Widget" shortcut on the desktop.
pause
