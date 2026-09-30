#!/usr/bin/env python3
"""Помощник для ручных/скриптовых тестов 1.0.1 на эмуляторе (SER=emulator-5554).
emu.py top | shot NAME | tap X Y | tapt "text" | tapid ID | dump | key CODE | state
ВНИМАНИЕ: dump/tapt/tapid используют uiautomator — он временно отключает AccessibilityService приложения
(сервис переподключается через ~1–2 с). Для проверок «возврата» используйте top (dumpsys) и скриншоты."""
import subprocess, sys, re, time, os, xml.etree.ElementTree as ET
SER=os.environ.get('SER','emulator-5554')
A=['/home/box/Android/Sdk/platform-tools/adb','-s',SER]
SHOTS='/workspace/ScreenLimit/screenshots'
def sh(*a, t=240): return subprocess.run([*A,'shell',*a],capture_output=True,text=True,timeout=t).stdout
def top():
    o=sh('dumpsys','activity','activities')
    m=re.search(r'mResumedActivity: ActivityRecord\{\S+ \S+ (\S+)',o) or re.search(r'topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)',o)
    return m[1] if m else '?'
def dump():
    for _ in range(6):
        sh('uiautomator','dump','/sdcard/ui.xml'); x=sh('cat','/sdcard/ui.xml')
        if x.startswith('<?xml'): return ET.fromstring(x)
        time.sleep(1)
    raise SystemExit('dump failed')
def nodes(r):
    for n in r.iter('node'):
        b=[int(v) for v in re.findall(r'\d+',n.get('bounds',''))]
        if len(b)==4: yield n,b
def find_text(t, r=None):
    r=r if r is not None else dump()
    for n,b in nodes(r):
        if t in (n.get('text') or '') or t in (n.get('content-desc') or ''): return n,b
    return None,None
def find_id(i, r=None):
    r=r if r is not None else dump()
    for n,b in nodes(r):
        if n.get('resource-id','').endswith(':id/'+i): return n,b
    return None,None
def tapb(b): sh('input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))
def shot(name):
    subprocess.run(' '.join(A)+f' exec-out screencap -p > {SHOTS}/{name}.png',shell=True); return f'{SHOTS}/{name}.png'
def state(): return sh('am','broadcast','-n','com.example.screenlimit/.DebugReceiver','--ez','state','true')
if __name__=='__main__':
    c=sys.argv[1]
    if c=='top': print(top())
    elif c=='shot': print(shot(sys.argv[2]))
    elif c=='tap': sh('input','tap',sys.argv[2],sys.argv[3])
    elif c=='tapt':
        n,b=find_text(sys.argv[2]); print('tap',b) if b else sys.exit('NOT FOUND'); tapb(b)
    elif c=='tapid':
        n,b=find_id(sys.argv[2]); print('tap',b) if b else sys.exit('NOT FOUND'); tapb(b)
    elif c=='dump':
        for n,b in nodes(dump()):
            t=n.get('text') or n.get('content-desc')
            if t or n.get('resource-id'): print(b, n.get('resource-id','').split('/')[-1], repr(t))
    elif c=='key': sh('input','keyevent',sys.argv[2])
    elif c=='state': print(state())
