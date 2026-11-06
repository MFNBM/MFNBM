#!/bin/sh
# Run from a PC with USB debugging on (Pixel, Android 11+).
# Sets the call/voice volume stream (0) to 0. Pass a number to use another level.
LEVEL="${1:-0}"
adb shell media volume --stream 0 --set "$LEVEL"
adb shell media volume --stream 0 --get
