"""Archive ROOT's localized K4 review follow-up without replacing the reviewed checkpoint."""
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
tag = sys.argv[1]
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p, v): p.write_text(json.dumps(v, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
b = (OUT/(tag+'.log')).read_bytes()
log = b.decode('utf-16') if b.startswith((b'\xff\xfe', b'\xfe\xff')) else b.decode('utf-8-sig')
assert 'BUILD SUCCESSFUL' in log
inputs = read(OUT/(tag+'-inputs-before.json'))
assert all(sha(ROOT/r['path']) == r['sha256'] for r in inputs), 'Build inputs changed'
destroot = OUT/tag
assert not destroot.exists(), 'Preserve prior evidence'
counts, cases = {}, {}
for module in ['core-assistant', 'feature-assistant', 'engine-ai', 'engine-ai-bridge']:
    fresh = module == 'core-assistant'
    source = BUILD/module/'test-results/testDebugUnitTest' if fresh else ROOT/'docs/validation-K4/final'/module
    dest = destroot/module
    dest.mkdir(parents=True)
    count = dict(tests=0, failures=0, errors=0, skipped=0, xml=0)
    methods = []
    for p in sorted(source.glob('TEST-*.xml')):
        suite = ET.parse(p).getroot()
        count['xml'] += 1
        for key in ['tests', 'failures', 'errors', 'skipped']: count[key] += int(suite.get(key, 0))
        methods.extend(c.get('classname', '')+'.'+c.get('name', '') for c in suite.findall('testcase'))
        shutil.copy2(p, dest/p.name)
    assert count['tests'] and not any(count[k] for k in ['failures', 'errors', 'skipped']), count
    origin = 'fresh '+tag if fresh else ('retained K4 full-5 execution; feature unchanged, not rerun' if module == 'feature-assistant' else 'retained K2 full-4 execution, not rerun')
    counts[module] = dict(**count, execution=origin)
    cases[module] = methods
assert [l.strip() for l in log.splitlines() if l.strip().startswith('> Task :core-assistant:testDebugUnitTest')] == ['> Task :core-assistant:testDebugUnitTest']
lint = {}
for module in ['core-assistant', 'feature-assistant', 'app']:
    fresh = module != 'feature-assistant'
    source = BUILD/module/'reports/lint-results-debug.xml' if fresh else ROOT/'docs/validation-K4/final'/module/'lint-results-debug.xml'
    count = dict(Counter(i.get('severity', 'Unknown') for i in ET.parse(source).getroot().findall('issue')))
    assert not any(count.get(k, 0) for k in ['Error', 'Fatal'])
    lint[module] = dict(counts=count, execution='current invocation' if fresh else 'retained K4; feature inputs unchanged')
    dest = destroot/module
    dest.mkdir(exist_ok=True)
    shutil.copy2(source, dest/source.name)
apk = Path('C:/Users/casua/AppData/Local/PampAI-qa/knowledge-K4-finalization')/f'PampAI-knowledge-{tag}.apk'
apk.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(BUILD/'app/outputs/apk/debug/app-debug.apk', apk)
baseline = {r['path']: r for r in read(ROOT/'docs/validation-K4/full-5-inputs-before.json')}
delta = [dict(path=r['path'], old=baseline.get(r['path'], {}).get('sha256'), new=r['sha256']) for r in inputs if baseline.get(r['path'], {}).get('sha256') != r['sha256']]
assert all(r['path'].startswith('core-assistant/src/') for r in delta), delta
for row in delta:
    target = destroot/'sources'/row['path']
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT/row['path'], target)
    assert sha(target) == row['new']
result = dict(buildSuccessful=True, counts=counts, lint=lint, inputCount=len(inputs), changedDuringBuild=[],
    inputManifest=tag+'-inputs-before.json', finalLog=tag+'.log', sourceDelta=delta,
    apk=dict(path=str(apk), size=apk.stat().st_size, sha256=sha(apk)),
    limits='ROOT localized follow-up; not independently rereviewed, device installed or runtime approved. No autonomous area enabled.')
save(OUT/'results.json', result)
save(OUT/'cases.json', cases)
save(OUT/'artifact-manifest.json', [dict(path=str(p.relative_to(ROOT)), size=p.stat().st_size, sha256=sha(p)) for p in sorted(destroot.rglob('*')) if p.is_file()])
print(json.dumps(result, indent=2))
