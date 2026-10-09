set -x
mkdir -p shots
adb shell wm size 1080x1240
adb shell wm density 420
adb shell settings put system screen_off_timeout 600000
adb install -r tool/build/outputs/apk/debug/*.apk
adb shell monkey -p com.gios.brightsudoku -c android.intent.category.LAUNCHER 1
sleep 12
adb exec-out screencap -p > shots/01-home.png
python3 tools/tap.py "New puzzle"
sleep 20
adb exec-out screencap -p > shots/02-puzzle.png
python3 tools/tap.py "‹ Home" || true
sleep 4
python3 tools/tap.py "Settings"
sleep 4
adb exec-out screencap -p > shots/03-settings.png
adb logcat -d | grep -i -E "brightsudoku|AndroidRuntime|lightsdk" | tail -80 > shots/log.txt
