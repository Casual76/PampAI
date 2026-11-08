"""Freeze the integrated K2 correction; never replace an earlier review copy."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
QA = Path('C:/Users/casua/AppData/Local/PampAI-qa')
REVIEW = QA / 'review-K2'
RESULTS = ROOT / 'docs/validation-K2'

def read(p):
    return json.loads(p.read_text(encoding='utf-8-sig'))

def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest().upper()

if REVIEW.exists():
    raise SystemExit('review-K2 already exists; no snapshot replacement is permitted')
results = read(RESULTS / 'results.json')
assert results['buildSuccessful'] and not results['changedInputs'], results
sources = {}

def add(p):
    p = Path(p).resolve()
    assert p.is_file(), p
    assert p.name.lower() not in {'local.properties', 'key.properties', 'keystore.properties'}, p
    assert p.suffix.lower() not in {'.jks', '.keystore'}, p
    sources[str(p).casefold()] = p

inputs = read(RESULTS / results['finalInputsManifest'])
for entry in inputs:
    p = ROOT / entry['path']
    assert sha(p).lower() == entry['sha256'].lower(), p
    add(p)

# The provider proof boundaries were not changed by this PampAI-only correction.
# Require exact parity with the earlier frozen/validated producer files, not just old claims.
old = read(QA / 'review-K1/manifest.json')
provider_count = 0
producer_deltas = []
android = '{http://schemas.android.com/apk/res/android}'
def manifest_signature(node, omit_airpods=False):
    allowed = (node.tag == 'uses-permission' and node.get(android+'name') in {
        'android.permission.READ_PHONE_STATE', 'android.permission.ANSWER_PHONE_CALLS'}) or (
        node.tag == 'uses-feature' and node.get(android+'name') == 'android.hardware.telephony' and node.get(android+'required') == 'false')
    if omit_airpods and allowed:
        return None
    return [node.tag, sorted(node.attrib.items()), (node.text or '').strip(),
        [v for child in node if (v := manifest_signature(child, omit_airpods)) is not None]]
for row in old:
    relative = Path(row['relativePath'])
    if relative.parts[0] in {'fluidify', 'classeviva'}:
        p = Path(row['originalPath'])
        assert p.is_file(), ('Missing producer/evidence', p)
        if sha(p).lower() != row['sha256'].lower():
            assert p == (ROOT.parent / 'Fluidify/app/src/main/AndroidManifest.xml').resolve(), ('Producer/evidence drift', p)
            prior = ET.parse(Path(row['path'])).getroot()
            current = ET.parse(p).getroot()
            assert manifest_signature(prior) == manifest_signature(current, True), 'Unexpected manifest change beyond inspected AirPods declarations'
            producer_deltas.append(dict(path=str(p), previousSha256=row['sha256'], currentSha256=sha(p),
                reason='Concurrent AirPods work adds two phone permissions and optional telephony feature. All bridge/provider/application declarations unchanged. No fresh source app build claimed.'))
        add(p)
        provider_count += 1

docs = ['aria-knowledge-acceptance.md', 'aria-knowledge-K2-review-brief.md', 'aria-K2-integration-report.md',
    'aria-next-phase-contracts.md', 'aria-source-proof-contract.md', 'aria-proof-renewal-integration.md',
    'aria-provider-lifecycle.md', 'aria-durable-foundation.md', 'aria-runtime-budget.md',
    'aria-research-consumer.md', 'aria-profile-ui.md', 'aria-workmanager.md',
    'aria-K1-exclusions.md', 'aria-K1-routing-resume.md', 'aria-K1-corrections-root.md']
for name in docs:
    add(ROOT / 'docs' / name)
for p in RESULTS.rglob('*'):
    if p.is_file():
        add(p)
for p in (ROOT / 'docs/validation-K1-root-source').glob('*.json'):
    add(p)
for p in (QA / 'knowledge-K2').rglob('*'):
    if p.is_file():
        add(p)

roots = [('pampai', ROOT), ('fluidify', ROOT.parent / 'Fluidify'),
    ('classeviva', ROOT.parent / 'classeviva-expressive'), ('qa', QA)]
rows = []
for p in sorted(sources.values(), key=lambda p: str(p).casefold()):
    for label, base in roots:
        try:
            relative = p.relative_to(base.resolve())
            break
        except ValueError:
            pass
    else:
        raise RuntimeError('Unmapped source root: ' + str(p))
    target = REVIEW / 'snapshot' / label / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(p, target)
    assert sha(p) == sha(target), p
    rows.append(dict(path=str(target), originalPath=str(p), relativePath=str(Path(label) / relative),
        sha256=sha(target), size=target.stat().st_size))
manifest = REVIEW / 'manifest.json'
manifest.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
heads = {label: subprocess.check_output(['git', '-C', str(base), 'rev-parse', 'HEAD'], text=True).strip()
    for label, base in roots[:3]}
for label, base in [('pampaiEngine', ROOT / 'engine'), ('fluidifyEngine', ROOT.parent / 'Fluidify/engine'),
    ('classevivaEngine', ROOT.parent / 'classeviva-expressive/android/engine')]:
    heads[label] = subprocess.check_output(['git', '-C', str(base), 'rev-parse', 'HEAD'], text=True).strip()
apk = results['apk']
info = dict(phase='CONNECTED KNOWLEDGE INTEGRATION', snapshot='K2', round='Correction R1/3',
    entries=len(rows), manifestSha256=sha(manifest), heads=heads, producerEntriesCompared=provider_count,
    producerEntriesUnchanged=provider_count-len(producer_deltas), producerDeltas=producer_deltas,
    counts=results['counts'], taskExecution=results['taskExecution'], lint=results['lint'],
    trackedInputs=results['inputCount'], changedDuringValidation=results['changedInputs'],
    apkSha256=apk['sha256'].upper(), apkBytes=apk['size'], pngs=len(results['pngs']),
    validation='Core249 final-6; feature98/engine264/bridge25 full-4; six repeated targeted music cases and feature/app lint final-5. Final APK/core lint final-6. Explicit four source/test deltas; no summed repeats.',
    scope='Copied sources/evidence only. Source proof producers unchanged; actual device/auth/billing/native gates remain NV.')
(REVIEW / 'snapshot-info.json').write_text(json.dumps(info, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(info, ensure_ascii=False, indent=2))
