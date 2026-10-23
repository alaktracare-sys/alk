@echo off
title Alaktra Music Server Launcher
color 0B

echo ============================================================
echo   ALAKTRA MUSIC SERVER - 1-CLICK LAUNCHER FOR WINDOWS
echo ============================================================
echo.

:: 1. Check for Python installation
where python >nul 2>nul
if %errorlevel% neq 0 (
    where py >nul 2>nul
    if %errorlevel% neq 0 (
        color 0C
        echo [ERROR] Python was not found on your system!
        echo.
        echo Please download and install Python from:
        echo   https://www.python.org/downloads/
        echo.
        echo IMPORTANT: Check the box "Add Python to PATH" during installation.
        echo.
        pause
        exit /b 1
    ) else (
        set PY_CMD=py
    )
) else (
    set PY_CMD=python
)

echo [1/3] Checking Python installation...
%PY_CMD% --version
if %errorlevel% neq 0 (
    echo [ERROR] Failed to run Python.
    pause
    exit /b 1
)

echo.
echo [2/3] Verifying and installing required packages...
%PY_CMD% -m pip install -r requirements.txt --quiet
if %errorlevel% neq 0 (
    echo [WARNING] Pip install had issues, attempting to run launcher anyway...
)

echo.
echo [3/3] Launching Alaktra Server Launcher with Tailscale support...
echo.

%PY_CMD% launcher.py

if %errorlevel% neq 0 (
    echo.
    echo Server process exited with code %errorlevel%.
    pause
)
