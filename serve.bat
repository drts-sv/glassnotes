@echo off
rem Glass Notes - локальный сервер для проверки PWA
cd /d "%~dp0"
echo Glass Notes: http://localhost:8013/
python -m http.server 8013 --bind 0.0.0.0
