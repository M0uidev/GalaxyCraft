@echo off
rem Super Minecraft Galaxy: abre el lanzador ya instalado con este build de prueba de CI
rem (game.json y sus archivos, al lado de este .cmd) en vez del ultimo release de GitHub.
set "GXL_GAME_MANIFEST=%~dp0game.json"
set "EXE=%LOCALAPPDATA%\Programs\super-minecraft-galaxy-launcher\Super Minecraft Galaxy Launcher.exe"
if not exist "%EXE%" set "EXE=%LOCALAPPDATA%\Programs\Super Minecraft Galaxy Launcher\Super Minecraft Galaxy Launcher.exe"
if not exist "%EXE%" set "EXE=%ProgramFiles%\Super Minecraft Galaxy Launcher\Super Minecraft Galaxy Launcher.exe"
if not exist "%EXE%" (
  echo No encuentro el lanzador instalado. Instala primero SuperMinecraftGalaxy-Launcher-*-win-x64.exe
  echo ^(artefacto launcher-Windows de CI^), o abre el lanzador con GXL_GAME_MANIFEST=%~dp0game.json.
  pause
  exit /b 1
)
start "" "%EXE%"
