import os
import sys

bom = b'\xef\xbb\xbf'
files = [
    'app/src/main/java/com/gpsquest96/emulator/ControlModeManager.java',
    'app/src/main/java/com/gpsquest96/emulator/EmulatorBridge.java',
    'app/src/main/java/com/gpsquest96/emulator/GameEngine.java',
    'app/src/main/java/com/gpsquest96/gps/LocationService.java',
    'app/src/main/java/com/gpsquest96/ui/MainActivity.java'
]

project_root = 'C:/Dev/gps-quest-96'
fixed = 0
ok = 0

for rel_path in files:
    full_path = os.path.join(project_root, rel_path)
    try:
        with open(full_path, 'rb') as f:
            data = f.read()
        if data.startswith(bom):
            data = data[3:]  # Remove BOM
            with open(full_path, 'wb') as f:
                f.write(data)
            print('FIXED: ' + rel_path + ' (removed BOM, now ' + str(len(data)) + ' bytes)')
            fixed += 1
        else:
            print('OK: ' + rel_path + ' (no BOM, ' + str(len(data)) + ' bytes)')
            ok += 1
    except FileNotFoundError:
        print('MISSING: ' + rel_path)

print()
print('Summary: ' + str(fixed) + ' fixed, ' + str(ok) + ' OK, ' + str(len(files) - fixed - ok) + ' missing')