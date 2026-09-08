"""Launch/navigation smoke test on an emulator; exports actual Android screenshots."""
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

OUT = Path('smoke-output'); OUT.mkdir(exist_ok=True)
PKG = 'com.g10blelab.app'

def adb(*args, binary=False):
    return subprocess.check_output(['adb', *args], timeout=45, text=not binary)

def capture(name):
    # Dump after layout settles. Native views are used for reliable navigation.
    time.sleep(.7)
    adb('shell', 'uiautomator', 'dump', '/sdcard/g10-ui.xml')
    raw = adb('shell', 'cat', '/sdcard/g10-ui.xml')
    (OUT / (name + '.xml')).write_text(raw)
    (OUT / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    return ET.fromstring(raw)

def tap(tree, text):
    nodes = [n for n in tree.iter('node') if n.get('text') == text and n.get('enabled') == 'true']
    assert nodes, 'Missing control: ' + text
    x1,y1,x2,y2 = map(int,re.findall(r'\d+',nodes[-1].get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))

def contains(tree, text):
    return any(text in n.get('text','') for n in tree.iter('node'))

adb('shell','wm','size','1080x2400')
adb('shell','wm','density','480')  # 360 dp phone
adb('install','-r','-g','app/build/outputs/apk/debug/app-debug.apk')
adb('shell','am','start','-W','-n',PKG+'/.MainActivity')
time.sleep(2)
try:
    home = capture('01-dashboard')
    assert contains(home,'ОСТАЛОСЬ ПРИМЕРНО') and contains(home,'— км'), 'Offline range must be unknown'
    for label in ['ПРИБОРЫ','МАРШРУТ','ПОЕЗДКИ','ЕЩЁ']:
        assert contains(home,label), 'Navigation missing: '+label
    tap(home,'ЕЩЁ'); more = capture('02-more')
    tap(more,'БАТАРЕЯ И ОБУЧЕНИЕ'); battery = capture('03-battery')
    assert contains(battery,'БАТАРЕ') or contains(battery,'BATTERY'), 'Battery screen missing'
    tap(battery,'ЕЩЁ'); more = capture('04-more')
    tap(more,'ЛАБОРАТОРИЯ BLE'); lab = capture('05-lab')
    assert contains(lab,'LAB'), 'LAB missing'
    tap(lab,'КОМАНДЫ И ИСТОЧНИКИ'); reference = capture('06-commands')
    assert contains(reference,'Команды G10'), 'Reference dialog missing'
    tap(reference,'ЗАКРЫТЬ'); lab = capture('07-lab')
    tap(lab,'ПОЕЗДКИ'); trips = capture('08-trips')
    tap(trips,'МАРШРУТ'); route = capture('09-route')
    assert contains(route,'МАРШРУТ И ЗАПАС ХОДА'), 'Route screen missing'
    tap(route,'ПРИБОРЫ')
    adb('shell','settings','put','system','font_scale','1.3')
    time.sleep(1)
    large = capture('10-dashboard-large-font')
    assert contains(large,'ПРИБОРЫ') and contains(large,'ЕЩЁ'), 'Navigation inaccessible with large font'
    assert adb('shell','pidof',PKG).strip(), 'Application process died'
    print('Android launch/navigation smoke: OK (360 dp, normal and 1.3 font scale)')
finally:
    (OUT/'logcat.txt').write_text(adb('logcat','-d','-t','1500'))
    adb('shell','settings','put','system','font_scale','1.0')
