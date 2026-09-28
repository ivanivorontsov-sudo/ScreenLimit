#!/usr/bin/env python3
"""Проходит 10 подтверждений на эмуляторе. usage: exit_flow.py [choice-label]"""
import subprocess, sys, re, time, os, xml.etree.ElementTree as ET
SER=os.environ.get('SER','emulator-5556')
A=['/home/box/Android/Sdk/platform-tools/adb','-s',SER]
def sh(*a): return subprocess.run([*A,'shell',*a],capture_output=True,text=True,timeout=180).stdout
def dump():
    for _ in range(5):
        sh('uiautomator','dump','/sdcard/ui.xml'); x=sh('cat','/sdcard/ui.xml')
        if x.startswith('<?xml'): return ET.fromstring(x)
        time.sleep(1)
    raise SystemExit('dump failed')
def nodes(r):
    for n in r.iter('node'):
        b=[int(v) for v in re.findall(r'\d+',n.get('bounds',''))]
        if len(b)==4: yield n,b
def byid(r,i):
    for n,b in nodes(r):
        if n.get('resource-id','').endswith(':id/'+i): return n,b
    return None,None
def tap(b): sh('input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))
def shot(name): subprocess.run(' '.join(A)+f' exec-out screencap -p > /workspace/ScreenLimit/screenshots/{name}.png',shell=True)
choice=sys.argv[1] if len(sys.argv)>1 else 'btnExtra15'
r=dump(); _,b=byid(r,'btnExit')
if b: tap(b); time.sleep(3)
done=0; early_rejected=False; last=-1
for _ in range(60):
    r=dump()
    c,cb=byid(r,'stepCounter')
    if c is None:
        n,bb=byid(r,choice)
        if n is not None:
            shot('09_exit_choice'); tap(bb); print('choice tapped', choice, 'after', done, 'confirmations'); break
        time.sleep(1); continue
    idx=int(re.search(r'(\d+) из',c.get('text'))[1])
    btn,bb=byid(r,'btnConfirm')
    if idx!=last:
        last=idx
        if idx==1 and not early_rejected and btn.get('enabled')=='false':
            tap(bb); time.sleep(0.3); r2=dump(); c2,_=byid(r2,'stepCounter')
            early_rejected = c2 is not None and ' 1 из' in ' '+c2.get('text'); print('early tap rejected:', early_rejected)
            shot('08_confirm_step1')
        ph,_=byid(r,'stepPhrase')
        if ph is not None:
            want=None
            for n,_b in nodes(r):
                m=re.search(r'«(.+)»',n.get('text') or '')
                if m and 'дословно' in n.get('text'): want=m[1]
            sh('am','broadcast','-n','com.example.screenlimit/.DebugReceiver','--es','type',want.replace(' ','\\ ').replace(',','\\,'))
            print(f'step {idx}: typed «{want}»'); time.sleep(1)
            if idx==3: shot('08b_confirm_typed_step')
    btn,bb=byid(dump(),'btnConfirm')
    while btn is not None and btn.get('enabled')!='true':
        time.sleep(0.7); btn,bb=byid(dump(),'btnConfirm')
    if btn is None: continue
    tap(bb); done+=1; print(f'step {idx}: confirmed ({btn.get("text")})'); time.sleep(1.5)
