import re, subprocess, sys
want = sys.argv[1]
xml = subprocess.run(["adb", "exec-out", "uiautomator", "dump", "/dev/tty"], capture_output=True, text=True).stdout
for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    if m.group(1).startswith(want):
        x = (int(m.group(2)) + int(m.group(4))) // 2
        y = (int(m.group(3)) + int(m.group(5))) // 2
        subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
        print("tapped", want, x, y)
        sys.exit(0)
print("NOT FOUND", want)
print(re.findall(r'text="([^"]+)"', xml))
