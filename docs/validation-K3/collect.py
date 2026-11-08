"""Archive the R2 candidate. Retained engine evidence is explicitly not a fresh run."""
from pathlib import Path
from collections import Counter
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
BUILD = Path('C:/Users/casua/AppData/Local/PampAI-qa-build-full')
QA = Path('C:/Users/casua/AppData/Local/PampAI-qa/knowledge-K3')

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p, value): p.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
def text(p):
    data = p.read_bytes()
    return data.decode('utf-16') if data.startswith((b'\xff\xfe', b'\xfe\xff')) else data.decode('utf-8-sig')

assert 'BUILD SUCCESSFUL' in text(OUT/'full-2.log')
assert 'BUILD SUCCESSFUL' in text(OUT/'final-3.log')
inputs = read(OUT/'final-3-inputs-before.json')
changed = [r['path'] for r in inputs if sha(ROOT/r['path']) != r['sha256']]
assert not changed, changed
prior = read(OUT/'full-2-inputs-before.json')
delta = [r['path'] for r in prior if sha(ROOT/r['path']) != r['sha256']]
assert set(delta) == {
    'core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchQuestionPlanner.kt',
    'core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/research/ResearchConsumerTest.kt'}, delta
counts, cases, lint, execution = {}, {}, {}, {}
for module in ['core-assistant', 'feature-assistant', 'engine-ai', 'engine-ai-bridge']:
    dest = OUT/'final'/module
    dest.mkdir(parents=True, exist_ok=True)
    source = (BUILD/module/'test-results/testDebugUnitTest' if module == 'core-assistant' else
        OUT/'full-2'/module if module == 'feature-assistant' else ROOT/'docs/validation-K2/final'/module)
    totals = dict(tests=0, failures=0, errors=0, skipped=0, screenshots=0, xml=0)
    methods = []
    for p in sorted(source.glob('TEST-*.xml')):
        suite = ET.parse(p).getroot()
        for key in ['tests', 'failures', 'errors', 'skipped']: totals[key] += int(suite.get(key, 0))
        totals['xml'] += 1
        for c in suite.findall('testcase'):
            methods.append(c.get('classname','')+'.'+c.get('name',''))
            if 'Screenshots' in c.get('classname',''): totals['screenshots'] += 1
        shutil.copy2(p, dest/p.name)
    counts[module], cases[module] = totals, methods
    assert totals['tests'] and not any(totals[k] for k in ['failures','errors','skipped']), totals
    if module in ['core-assistant','feature-assistant']:
        log = text(OUT/('final-3.log' if module == 'core-assistant' else 'full-2.log'))
        execution[module] = [line.strip() for line in log.splitlines() if line.strip().startswith(f'> Task :{module}:testDebugUnitTest')]
    else:
        execution[module] = ['Retained K2 full-4; engine sources unchanged; not rerun in K3']
for module in ['core-assistant','feature-assistant','app']:
    p = BUILD/module/'reports/lint-results-debug.xml' if module == 'core-assistant' else OUT/'full-2'/module/'lint-results-debug.xml'
    lint[module] = dict(Counter(x.get('severity','Unknown') for x in ET.parse(p).getroot().findall('issue')))
    assert not any(lint[module].get(k,0) for k in ['Error','Fatal']), lint
    dest=OUT/'final'/module;dest.mkdir(exist_ok=True)
    shutil.copy2(p,dest/'lint-results-debug.xml')
apk=QA/'PampAI-knowledge-K3-debug.apk'
shutil.copy2(BUILD/'app/outputs/apk/debug/app-debug.apk',apk)
pngs=[dict(path=str(p),size=p.stat().st_size,sha256=sha(p)) for p in sorted((QA/'screenshots').glob('*.png'))]
assert len(pngs)==34,len(pngs)
result=dict(counts=counts, taskExecution=execution, lint=lint, inputCount=len(inputs), changedInputs=changed,
    postFullSuiteDelta=delta, finalInputsManifest='final-3-inputs-before.json', pngs=pngs,
    apk=dict(path=str(apk),size=apk.stat().st_size,sha256=sha(apk)),
    validation='Core252 final-3; feature104 full-2; unchanged engine264/bridge25 retained from K2 full-4, not rerun. Final APK/core lint final-3; feature/app lint full-2. No summed repeats.')
save(OUT/'results.json',result);save(OUT/'cases.json',cases)
save(OUT/'artifact-manifest.json',[dict(path=str(p.relative_to(ROOT)),size=p.stat().st_size,sha256=sha(p)) for p in sorted((OUT/'final').rglob('*')) if p.is_file()])
print(json.dumps({k:v for k,v in result.items() if k!='pngs'},indent=2))
print('Synthetic PNGs:',len(pngs))
