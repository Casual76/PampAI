"""Archive final K3 integration; retain preceding candidate evidence untouched."""
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
QA = Path('C:/Users/casua/AppData/Local/PampAI-qa/knowledge-K3')
tag = sys.argv[1]
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p, data): p.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
def logtext(p):
    data = p.read_bytes()
    return data.decode('utf-16') if data.startswith((b'\xff\xfe', b'\xfe\xff')) else data.decode('utf-8-sig')

log = logtext(OUT/(tag+'.log'))
assert 'BUILD SUCCESSFUL' in log
inputs = read(OUT/(tag+'-inputs-before.json'))
changed = [r['path'] for r in inputs if sha(ROOT/r['path']) != r['sha256']]
assert not changed, changed
counts, cases, lint, tasks = {}, {}, {}, {}
destroot = OUT/'privacy-final'
assert not destroot.exists(), 'Refuse replacement of final archive'
for module in ['core-assistant', 'feature-assistant', 'engine-ai', 'engine-ai-bridge']:
    fresh = module in ['core-assistant', 'feature-assistant']
    source = BUILD/module/'test-results/testDebugUnitTest' if fresh else ROOT/'docs/validation-K2/final'/module
    dest = destroot/module; dest.mkdir(parents=True)
    totals = dict(tests=0, failures=0, errors=0, skipped=0, screenshots=0, xml=0)
    methods = []
    for p in sorted(source.glob('TEST-*.xml')):
        suite = ET.parse(p).getroot()
        for key in ['tests','failures','errors','skipped']: totals[key] += int(suite.get(key, 0))
        totals['xml'] += 1
        for c in suite.findall('testcase'):
            methods.append(c.get('classname','')+'.'+c.get('name',''))
            if 'Screenshots' in c.get('classname',''): totals['screenshots'] += 1
        shutil.copy2(p, dest/p.name)
    assert totals['tests'] and not any(totals[k] for k in ['failures','errors','skipped']), totals
    counts[module], cases[module] = totals, methods
    tasks[module] = ([l.strip() for l in log.splitlines() if l.strip().startswith(f'> Task :{module}:testDebugUnitTest')]
        if fresh else ['Retained K2 full-4; unchanged engine sources; not rerun in K3'])
    if fresh: assert tasks[module] == [f'> Task :{module}:testDebugUnitTest'], tasks[module]
for module in ['core-assistant','feature-assistant','app']:
    p = BUILD/module/'reports/lint-results-debug.xml'
    lint[module] = dict(Counter(x.get('severity','Unknown') for x in ET.parse(p).getroot().findall('issue')))
    assert not any(lint[module].get(k,0) for k in ['Error','Fatal']), lint[module]
    dest=destroot/module; dest.mkdir(exist_ok=True)
    shutil.copy2(p,dest/'lint-results-debug.xml')
apk = QA/'PampAI-knowledge-K3-privacy-debug.apk'
shutil.copy2(BUILD/'app/outputs/apk/debug/app-debug.apk', apk)
pngs = [dict(path=str(p),size=p.stat().st_size,sha256=sha(p)) for p in sorted((QA/'screenshots').glob('*.png'))]
result = dict(buildSuccessful=True, counts=counts, taskExecution=tasks, lint=lint, inputCount=len(inputs), changedInputs=changed,
    finalInputsManifest=tag+'-inputs-before.json', finalLog=tag+'.log', pngs=pngs,
    apk=dict(path=str(apk),size=apk.stat().st_size,sha256=sha(apk)),
    validation=f'Core/feature freshly executed in {tag}; engine264/bridge25 retained from K2 full-4. No summed repeats. Earlier candidate and failed logs remain separate.')
save(OUT/'privacy-results.json',result);save(OUT/'privacy-cases.json',cases)
save(OUT/'privacy-artifact-manifest.json',[dict(path=str(p.relative_to(ROOT)),size=p.stat().st_size,sha256=sha(p)) for p in sorted(destroot.rglob('*')) if p.is_file()])
print(json.dumps({k:v for k,v in result.items() if k!='pngs'},indent=2));print('PNG:',len(pngs))
