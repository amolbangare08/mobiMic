@echo off
rem Starts the mobiMic receiver into VB-CABLE. Double-click it, or run it from a
rem terminal. Close the window or press Ctrl+C to stop.
rem
rem --target-ms 50 is what this PC's VB-CABLE needed for a glitch-free stream.
rem Drop it and the receiver will learn the figure itself, at the cost of about a
rem minute of occasional glitches on the first run.

cd /d "%~dp0"
title mobiMic receiver
python -u pc\receiver.py --target-ms 50 %*

echo.
echo Receiver stopped.
pause
