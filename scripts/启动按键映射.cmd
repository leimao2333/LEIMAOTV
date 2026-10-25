@echo off
chcp 65001 >nul
rem 双击运行：把小键盘映射成遥控器按键（4/8/2/6 方向，5=OK，0=菜单，Esc=返回）
rem 关掉这个黑窗口就停止映射。
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0emu-keymap.ps1" -LogKeys
pause
