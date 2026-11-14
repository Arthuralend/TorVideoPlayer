#!/bin/bash
# Runs inside the Android emulator job: plays an MKV with two embedded
# subtitle tracks and checks from logcat that both reach the screen.
set -u
APK="$1"
MKV="$2"
PKG=com.arthuralend.mediaplayer
DIR=/sdcard/Android/data/$PKG/files

adb install -r "$APK"
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity
sleep 8
adb shell mkdir -p $DIR
adb push "$MKV" $DIR/test.mkv
adb shell am start -W -a android.intent.action.VIEW -t video/x-matroska -d "file://$DIR/test.mkv" -n $PKG/.MainActivity
sleep 40
adb logcat -d -v brief > logcat.txt
grep -E "MediaPlayer|AndroidRuntime|libvlc|VLC" logcat.txt | grep -vE "chatty" | tail -n 120 > app-log.txt
cat app-log.txt
adb exec-out screencap -p > screen.png || true

ok=1
grep -q "overlay primary=[^n]" app-log.txt || ok=0
grep -q "secondary=[^n]" app-log.txt || ok=0
if [ $ok = 1 ]; then echo "SUBTITLES OK"; else echo "SUBTITLES MISSING"; fi
