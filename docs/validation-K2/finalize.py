"""Tie the full suite, scoped UI clarification and last privacy check to the final APK."""
from pathlib import Path
from collections import Counter
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
BUILD = Path('C:/Users/casua/AppData/Local/PampAI-qa-build-full')
QA = Path('C:/Users/casua/AppData/Local/PampAI-qa/knowledge-K2')

def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def load(p):
    return json.loads(p.read_text(encoding='utf-8-sig'))

def save(p, data):
    p.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def text(p):
    raw = p.read_bytes()
    return raw.decode('utf-16') if raw.startswith((b'\xff\xfe', b'\xfe\xff')) else raw.decode('utf-8-sig')

result = load(OUT / 'results.json')
result.pop('changedAfterCorePass', None)
assert 'final-6' not in result.get('validationRuns', {}).values(), 'Already finalized'
assert 'BUILD SUCCESSFUL' in text(OUT / 'final-5.log')
assert 'BUILD SUCCESSFUL' in text(OUT / 'final-6.log')
inputs = load(OUT / 'final-6-inputs-before.json')
changed = [r['path'] for r in inputs if sha(ROOT / r['path']) != r['sha256']]
assert not changed, changed
suite_inputs = load(OUT / 'full-4-inputs-before.json')
delta = [r['path'] for r in suite_inputs if sha(ROOT / r['path']) != r['sha256']]
expected = {
    'core-assistant/src/main/kotlin/dev/pampa/pampai/core/assistant/research/ResearchQuestionPlanner.kt',
    'core-assistant/src/test/kotlin/dev/pampa/pampai/core/assistant/research/ResearchConsumerTest.kt',
    'feature-assistant/src/main/kotlin/dev/pampa/pampai/feature/assistant/profile/MusicFactPresentation.kt',
    'feature-assistant/src/test/kotlin/dev/pampa/pampai/feature/assistant/profile/MusicFactPresentationTest.kt',
}
assert set(delta) == expected, delta
totals = dict(tests=0, failures=0, errors=0, skipped=0, screenshots=0, xml=0)
core_cases = []
for p in sorted((BUILD / 'core-assistant/test-results/testDebugUnitTest').glob('TEST-*.xml')):
    suite = ET.parse(p).getroot()
    for k in ['tests', 'failures', 'errors', 'skipped']:
        totals[k] += int(suite.get(k, 0))
    totals['xml'] += 1
    core_cases += [c.get('classname', '') + '.' + c.get('name', '') for c in suite.findall('testcase')]
    shutil.copy2(p, OUT / 'final/core-assistant' / p.name)
assert totals['tests'] == 249 and not any(totals[k] for k in ['failures', 'errors', 'skipped']), totals
assert load(OUT / 'targeted-final-counts.json') == dict(tests=6, failures=0, errors=0, skipped=0)
old_apk = result['apk']
apk = QA / 'PampAI-knowledge-K2-debug.apk'
shutil.copy2(BUILD / 'app/outputs/apk/debug/app-debug.apk', apk)
result['fullSuiteApkHistorical'] = dict(sha256=old_apk['sha256'], size=old_apk['size'], binaryRetained=False)
result['apk'] = dict(path=str(apk), sha256=sha(apk), size=apk.stat().st_size)
result['counts']['core-assistant'] = totals
result['validationRuns'] = {'core-assistant': 'final-6', 'feature/engine/bridge/full-lint/app': 'full-4',
    'music-presentation-6-repeated-cases/feature-app-lint/app': 'final-5', 'final-core-lint/APK': 'final-6'}
result['taskExecution']['core-assistant'] = [line.strip() for line in text(OUT / 'final-6.log').splitlines()
    if line.strip().startswith('> Task :core-assistant:testDebugUnitTest')]
result['targetedRepeatedCounts'] = load(OUT / 'targeted-final-counts.json')
for module in ['core-assistant', 'feature-assistant', 'app']:
    path = BUILD / module / 'reports/lint-results-debug.xml' if module == 'core-assistant' else OUT / 'targeted-final' / f'{module}-lint.xml'
    result['lint'][module] = dict(Counter(x.get('severity', 'Unknown') for x in ET.parse(path).getroot().findall('issue')))
    shutil.copy2(path, OUT / 'final' / module / 'lint-results-debug.xml')
    assert not any(result['lint'][module].get(k, 0) for k in ['Error', 'Fatal'])
prior_png = {Path(p['path']).name: p['sha256'] for p in result['pngs']}
pngs = [dict(path=str(p), sha256=sha(p), size=p.stat().st_size) for p in sorted((QA / 'screenshots').glob('*.png'))]
changed_pngs = [Path(p['path']).name for p in pngs if prior_png[Path(p['path']).name] != p['sha256']]
assert set(changed_pngs) == {'profile-music-observation-dark.png', 'profile-provenance-dark.png'}, changed_pngs
result.update(pngs=pngs, changedAfterFullSuite=delta, changedPngsAfterFullSuite=changed_pngs,
    finalInputsManifest='final-6-inputs-before.json', inputCount=len(inputs), changedInputs=changed,
    provenance='Core249 final-6; feature98/engine264/bridge25 full-4; six repeated music cases final-5. No sum across runs. Four explicit source/test deltas after full suite, all covered by scoped or full checks.')
save(OUT / 'results.json', result)
cases = load(OUT / 'cases.json')
cases['core-assistant'] = core_cases
save(OUT / 'cases.json', cases)
save(OUT / 'artifact-manifest.json', [dict(path=str(p.relative_to(ROOT)), sha256=sha(p), size=p.stat().st_size)
    for directory in ['final', 'targeted-final'] for p in sorted((OUT / directory).rglob('*')) if p.is_file()])
print(json.dumps({k:v for k,v in result.items() if k not in ['pngs', 'taskExecution']}, indent=2))
