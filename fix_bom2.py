bom = b'\xef\xbb\xbf'
paths = [
    r'C:\Dev\gps-quest-96\emulator\ControlModeManager.java',
    r'C:\Dev\gps-quest-96\emulator\EmulatorBridge.java',
    r'C:\Dev\gps-quest-96\emulator\GameEngine.java',
    r'C:\Dev\gps-quest-96\gps\LocationService.java',
    r'C:\Dev\gps-quest-96\ui\MainActivity.java'
]
for path in paths:
    try:
        with open(path, 'rb') as f:
            data = f.read()
        if data[:3] == bom:
            with open(path, 'wb') as f:
                f.write(data[3:])
            print(f'Fixed: {path}')
        else:
            print(f'OK: {path}')
    except FileNotFoundError:
        print(f'Missing: {path}')