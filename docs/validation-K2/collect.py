"""Archive the integrated candidate; counts describe exactly one retained invocation."""
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
QA = Path('C:/Users/casua/AppData/Local/PampAI-qa/knowledge-K2')

def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def text(p):
    raw = p.read_bytes()
    return raw.decode('utf-16') if raw.startswith((b'\xff\xfe', b'\xfe\xff')) else raw.decode('utf-8-sig')

def save(p, data):
    p.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

run = sys.argv[1] if len(sys.argv) > 1 else 'full-4'
log = text(OUT / f'{run}.log')
before = json.loads((OUT / f'{run}-inputs-before.json').read_text(encoding='utf-8'))
changed = [r['path'] for r in before if not (ROOT / r['path']).is_file() or sha(ROOT / r['path']) != r['sha256']]
counts, cases, lint, execution = {}, {}, {}, {}
archive = OUT / 'final'
for module in ['core-assistant', 'feature-assistant', 'engine-ai', 'engine-ai-bridge']:
    dest = archive / module
    dest.mkdir(parents=True, exist_ok=True)
    totals = dict(tests=0, failures=0, errors=0, skipped=0, screenshots=0, xml=0)
    methods = []
    for p in sorted((BUILD / module / 'test-results/testDebugUnitTest').glob('TEST-*.xml')):
        suite = ET.parse(p).getroot()
        for key in ['tests', 'failures', 'errors', 'skipped']:
            totals[key] += int(suite.get(key, 0))
        totals['xml'] += 1
        for case in suite.findall('testcase'):
            name = case.get('classname', '') + '.' + case.get('name', '')
            methods.append(name)
            if 'Screenshots' in case.get('classname', ''):
                totals['screenshots'] += 1
        shutil.copy2(p, dest / p.name)
    counts[module], cases[module] = totals, methods
    task = f'> Task :{module}:testDebugUnitTest'
    module_log = text(OUT / 'full-3.log') if module == 'core-assistant' else log
    execution[module] = [line.strip() for line in module_log.splitlines() if line.strip().startswith(task)]
for module in ['core-assistant', 'feature-assistant', 'app']:
    p = BUILD / module / 'reports/lint-results-debug.xml'
    issues = ET.parse(p).getroot().findall('issue')
    lint[module] = dict(Counter(i.get('severity', 'Unknown') for i in issues))
    dest = archive / module
    dest.mkdir(parents=True, exist_ok=True)
    shutil.copy2(p, dest / 'lint-results-debug.xml')
apk = BUILD / 'app/outputs/apk/debug/app-debug.apk'
copy = QA / 'PampAI-knowledge-K2-debug.apk'
shutil.copy2(apk, copy)
pngs = [{'path': str(p), 'sha256': sha(p), 'size': p.stat().st_size} for p in sorted((QA / 'screenshots').glob('*.png'))]
core_before = json.loads((OUT / 'full-3-inputs-before.json').read_text(encoding='utf-8'))
delta = [r['path'] for r in core_before if sha(ROOT / r['path']) != r['sha256']]
assert delta == ['feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/profile/SourceFormContractTest.kt'], delta
result = dict(counts=counts, taskExecution=execution, lint=lint, inputCount=len(before), changedInputs=changed,
    validationRuns={'core-assistant': 'full-3', 'feature/engine/bridge/lint/app': run}, changedAfterCorePass=delta,
    apk=dict(path=str(copy), sha256=sha(copy), size=copy.stat().st_size), pngs=pngs,
    buildSuccessful='BUILD SUCCESSFUL' in log,
    provenance=f'Core passed in full-3; only a feature test fixture changed afterwards. Remaining final checks in {run}. Earlier failures retained; no sum across runs.')
save(OUT / 'results.json', result)
save(OUT / 'cases.json', cases)
save(OUT / 'artifact-manifest.json', [{'path': str(p.relative_to(ROOT)), 'sha256': sha(p), 'size': p.stat().st_size}
    for p in sorted(archive.rglob('*')) if p.is_file()])
print(json.dumps({k: v for k, v in result.items() if k != 'pngs'}, indent=2))
print('Synthetic PNGs:', len(pngs))
assert not changed, changed
assert result['buildSuccessful']
assert all(c['tests'] and not any(c[k] for k in ['failures', 'errors', 'skipped']) for c in counts.values()), counts
assert all(not any(c.get(k, 0) for k in ['Error', 'Fatal']) for c in lint.values()), lint
