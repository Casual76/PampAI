"""Archive one complete final-source K4 execution; never combine repeated cases."""
from pathlib import Path
from collections import Counter
import hashlib
import json
import shutil
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
BUILD = Path('C:/Users/casua/AppData/Local/PampAI-qa-build-full')
QA = Path('C:/Users/casua/AppData/Local/PampAI-qa/knowledge-K4')
tag = sys.argv[1]
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,v): p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def logtext(p):
    b=p.read_bytes()
    return b.decode('utf-16') if b.startswith((b'\xff\xfe',b'\xfe\xff')) else b.decode('utf-8-sig')
log=logtext(OUT/(tag+'.log'));assert 'BUILD SUCCESSFUL' in log
inputs=read(OUT/(tag+'-inputs-before.json'))
changed=[r['path'] for r in inputs if sha(ROOT/r['path'])!=r['sha256']]
assert not changed,changed
destroot=OUT/'final';assert not destroot.exists(), 'Refuse replacement of final evidence'
counts,cases,lint,tasks={},{},{},{}
for module in ['core-assistant','feature-assistant','engine-ai','engine-ai-bridge']:
    fresh=module in ['core-assistant','feature-assistant']
    source=BUILD/module/'test-results/testDebugUnitTest' if fresh else ROOT/'docs/validation-K3/privacy-final'/module
    dest=destroot/module;dest.mkdir(parents=True)
    total=dict(tests=0,failures=0,errors=0,skipped=0,screenshots=0,xml=0);methods=[]
    for p in sorted(source.glob('TEST-*.xml')):
        suite=ET.parse(p).getroot();total['xml']+=1
        for key in ['tests','failures','errors','skipped']:total[key]+=int(suite.get(key,0))
        for c in suite.findall('testcase'):
            methods.append(c.get('classname','')+'.'+c.get('name',''))
            if 'Screenshots' in c.get('classname',''):total['screenshots']+=1
        shutil.copy2(p,dest/p.name)
    assert total['tests'] and not any(total[k] for k in ['failures','errors','skipped']),total
    counts[module],cases[module]=total,methods
    tasks[module]=([l.strip() for l in log.splitlines() if l.strip().startswith(f'> Task :{module}:testDebugUnitTest')]
        if fresh else ['Retained unchanged K2 full-4 execution, copied through K3; not rerun in K4'])
    if fresh:assert tasks[module]==[f'> Task :{module}:testDebugUnitTest'],tasks[module]
for module in ['core-assistant','feature-assistant','app']:
    p=BUILD/module/'reports/lint-results-debug.xml'
    lint[module]=dict(Counter(i.get('severity','Unknown') for i in ET.parse(p).getroot().findall('issue')))
    assert not any(lint[module].get(k,0) for k in ['Error','Fatal']),lint
    dest=destroot/module;dest.mkdir(exist_ok=True);shutil.copy2(p,dest/p.name)
apk=QA/'PampAI-knowledge-K4-debug.apk';apk.parent.mkdir(parents=True,exist_ok=True)
shutil.copy2(BUILD/'app/outputs/apk/debug/app-debug.apk',apk)
pngs=[dict(path=str(p),size=p.stat().st_size,sha256=sha(p)) for p in sorted((QA/'screenshots').glob('*.png'))]
baseline={r['path']:r for r in read(ROOT/'docs/validation-K3/privacy-7-inputs-before.json')}
delta=[dict(path=r['path'],old=baseline.get(r['path'],{}).get('sha256'),new=r['sha256'])
    for r in inputs if baseline.get(r['path'],{}).get('sha256')!=r['sha256']]
assert not any(r['path'].startswith('engine/') for r in delta)
save(OUT/'correction-source-delta.json',delta)
result=dict(buildSuccessful=True,counts=counts,taskExecution=tasks,lint=lint,inputCount=len(inputs),changedInputs=changed,
    finalInputsManifest=tag+'-inputs-before.json',finalLog=tag+'.log',pngs=pngs,
    apk=dict(path=str(apk),size=apk.stat().st_size,sha256=sha(apk)),
    validation=f'Fresh full core/feature in {tag}; unchanged engine264/bridge25 retained K2. No summed repeated cases or device/source runtime claim.')
save(OUT/'results.json',result);save(OUT/'cases.json',cases)
save(OUT/'artifact-manifest.json',[dict(path=str(p.relative_to(ROOT)),size=p.stat().st_size,sha256=sha(p)) for p in sorted(destroot.rglob('*')) if p.is_file()])
print(json.dumps({k:v for k,v in result.items() if k!='pngs'},indent=2));print('PNG:',len(pngs))
