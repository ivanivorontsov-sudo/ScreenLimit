#!/usr/bin/env python3
"""Tiny UI helper: ui.py dump | tap "text" [n] | find "text" | type "text" | key CODE | shot name"""
import subprocess, sys, re, time, xml.etree.ElementTree as ET
import os
A=['/home/box/Android/Sdk/platform-tools/adb','-s',os.environ.get('SER','emulator-5556')]
def sh(*a): return subprocess.run([*A,'shell',*a],capture_output=True,text=True,timeout=180).stdout
def dump():
    for _ in range(4):
        out=sh('uiautomator','dump','/sdcard/ui.xml')
        x=sh('cat','/sdcard/ui.xml')
        if x.startswith('<?xml'): return ET.fromstring(x)
        time.sleep(2)
    raise SystemExit('dump failed: '+out)
def nodes(root):
    for n in root.iter('node'):
        b=re.findall(r'\d+',n.get('bounds','')); 
        if len(b)==4: yield n,[int(v) for v in b]
def find(t, exact=False):
    r=dump(); res=[]
    for n,b in nodes(r):
        s=(n.get('text') or '')+'|'+(n.get('content-desc') or '')
        if (t==n.get('text') if exact else t in s): res.append((n,b))
    return res
cmd=sys.argv[1]
if cmd=='dump':
    for n,b in nodes(dump()):
        t=n.get('text') or n.get('content-desc')
        if t or n.get('clickable')=='true' and n.get('class','').endswith('EditText'): print(b, n.get('class','').split('.')[-1], repr(t), 'focused' if n.get('focused')=='true' else '')
elif cmd in('tap','tapx'):
    idx=int(sys.argv[3]) if len(sys.argv)>3 else 0
    r=find(sys.argv[2], exact=(cmd=='tapx'))
    if len(r)<=idx: raise SystemExit('NOT FOUND: '+sys.argv[2])
    b=r[idx][1]; x=(b[0]+b[2])//2; y=(b[1]+b[3])//2; sh('input','tap',str(x),str(y)); print('tapped',sys.argv[2],x,y)
elif cmd=='find':
    for n,b in find(sys.argv[2]): print(b, repr(n.get('text')))
elif cmd=='type':
    sh('input','text',sys.argv[2].replace(' ','%s'))
elif cmd=='key': sh('input','keyevent',sys.argv[2])
elif cmd=='shot':
    subprocess.run(' '.join(A)+f' exec-out screencap -p > /workspace/ScreenLimit/screenshots/{sys.argv[2]}.png',shell=True)
if cmd=='setedit':
    idx=int(sys.argv[2]); val=sys.argv[3]
    eds=[(n,b) for n,b in nodes(dump()) if n.get('class','').endswith('EditText')]
    n,b=eds[idx]; sh('input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2)); time.sleep(2)
    sh('input','keyevent','KEYCODE_MOVE_END'); sh('input','keyevent',*(['KEYCODE_DEL']*12))
    sh('input','text',val.replace(' ','%s')); time.sleep(1); sh('input','keyevent','KEYCODE_BACK'); time.sleep(2)
    eds=[(n,b) for n,b in nodes(dump()) if n.get('class','').endswith('EditText')]
    print('now:', [e[0].get('text') for e in eds])
