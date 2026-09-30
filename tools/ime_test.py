#!/usr/bin/env python3
"""Регрессионный тест 1.0.1: клавиатура на шагах с фразой не выбрасывает на экран блокировки.
usage: ime_test.py [--stop-after-step N] [--choice btnExtra15|none] [--tag api30]
Проходит поток выхода; на каждом шаге с фразой: реальный тап по полю -> ждёт клавиатуру ->
проверяет (сразу и через 6 с), что ExitActivity на переднем плане и клавиатура показана ->
печатает символы тапами по экранной клавиатуре -> скриншот -> вводит фразу (debug-приёмник) -> подтверждает."""
import subprocess, sys, re, time, os, argparse, xml.etree.ElementTree as ET
ap=argparse.ArgumentParser(); ap.add_argument('--stop-after-step',type=int,default=99); ap.add_argument('--choice',default='btnExtra15')
ap.add_argument('--tag',default='api30'); ap.add_argument('--no-exit-tap',action='store_true'); args=ap.parse_args()
SER=os.environ.get('SER','emulator-5554')
A=['/home/box/Android/Sdk/platform-tools/adb','-s',SER]
SHOTS='/workspace/ScreenLimit/screenshots'
def sh(*a): return subprocess.run([*A,'shell',*a],capture_output=True,text=True,timeout=300).stdout
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
def byid(r,i):
    for n,b in nodes(r):
        if n.get('resource-id','').endswith(':id/'+i): return n,b
    return None,None
def tap(b): sh('input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))
def shot(name): subprocess.run(' '.join(A)+f' exec-out screencap -p > {SHOTS}/{name}.png',shell=True); print('  screenshot', f'{SHOTS}/{name}.png')
def resumed():
    m=re.search(r'mResumedActivity: ActivityRecord\{\S+ \S+ (\S+)',sh('dumpsys','activity','activities')); return m[1] if m else '?'
def ime_shown():
    o=sh('dumpsys','input_method'); m=re.search(r'mInputShown=(\w+)',o); return m and m[1]=='true'
def ime_window():
    o=sh('dumpsys','window','windows'); return 'InputMethod' in o and bool(re.search(r'Window\{\S+ u0 InputMethod\}[\s\S]{0,4000}?isVisible=true|mHasSurface=true',o))
fails=[]
def check(label):
    act=resumed(); shown=ime_shown()
    ok = act.endswith('.ExitActivity') and shown
    print(f'  [{label}] resumed={act} keyboardShown={shown} -> {"OK" if ok else "FAIL"}')
    if not ok: fails.append(f'{label}: resumed={act} kb={shown}')
    return ok
if not args.no_exit_tap:
    r=dump(); _,b=byid(r,'btnExit')
    if b: tap(b); print('tapped «Выйти из блокировки»'); time.sleep(4)
last=-1; phrase_steps=0
for _ in range(80):
    r=dump(); c,cb=byid(r,'stepCounter')
    if c is None:
        n,bb=byid(r,args.choice) if args.choice!='none' else (None,None)
        if n is not None: shot(f'ime_{args.tag}_exit_choice'); tap(bb); print('choice tapped', args.choice); break
        print('  no step on screen; resumed =', resumed()); time.sleep(2); continue
    idx=int(re.search(r'(\d+) из',c.get('text'))[1])
    if idx!=last:
        last=idx; print(f'step {idx}')
        ph,pb=byid(r,'stepPhrase')
        if ph is not None:
            phrase_steps+=1
            want=None
            for n,_b in nodes(r):
                m=re.search(r'«(.+)»',n.get('text') or '')
                if m and 'дословно' in (n.get('text') or ''): want=m[1]
            tap(pb); print('  tapped phrase field'); time.sleep(3)
            check(f'step{idx} +3s after tap')
            shot(f'ime_{args.tag}_step{idx:02d}_keyboard')
            time.sleep(6); check(f'step{idx} +9s after tap')
            # печать тапами по экранной клавиатуре (верхний ряд: q w e)
            kb_top=None
            for n,b in nodes(dump()):
                pass
            W,H=1080,1920
            for x in (54,162,270): sh('input','tap',str(x),str(int(H*0.66)))
            time.sleep(3); r2=dump(); p2,_=byid(r2,'stepPhrase')
            typed=p2.get('text') if p2 is not None else None
            print(f'  after tapping soft keys the field contains: {typed!r}')
            if not typed or typed=='Фраза': fails.append(f'step{idx}: soft-key typing produced nothing ({typed!r})')
            check(f'step{idx} after typing')
            shot(f'ime_{args.tag}_step{idx:02d}_typed')
            if idx>=args.stop_after_step: print('stop requested'); break
            sh('am','broadcast','-n','com.example.screenlimit/.DebugReceiver','--es','type',want.replace(' ','\\ ').replace(',','\\,'))
            print(f'  phrase set «{want}»'); time.sleep(1.5)
        elif idx>args.stop_after_step: print('stop requested'); break
    btn,bb=byid(dump(),'btnConfirm')
    while btn is not None and btn.get('enabled')!='true':
        time.sleep(1); btn,bb=byid(dump(),'btnConfirm')
    if btn is None: continue
    tap(bb); print(f'  confirmed ({btn.get("text")})'); time.sleep(2)
print('phrase steps checked:', phrase_steps)
print('RESULT:', 'PASS' if not fails else 'FAIL '+'; '.join(fails))
