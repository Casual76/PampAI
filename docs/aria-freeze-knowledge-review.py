"""Create immutable review copies after the root's final text-only validation."""
import hashlib
import json
import pathlib
import shutil
import subprocess
import xml.etree.ElementTree as ET

REPO = pathlib.Path(__file__).resolve().parents[1]
QA = pathlib.Path('C:/Users/casua/AppData/Local/PampAI-qa')
BUILD = pathlib.Path('C:/Users/casua/AppData/Local/PampAI-build')
FLUIDIFY = REPO.parent / 'Fluidify'
CV = REPO.parent / 'classeviva-expressive'
ROOT_FINAL = QA / 'knowledge-root-final'
REVIEW = QA / 'review-K1'

def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest().upper()

def read(p):
    return json.loads(p.read_text(encoding='utf-8-sig'))

if REVIEW.exists():
    raise SystemExit('review-K1 exists; inspect before replacing any snapshot')
ROOT_FINAL.mkdir(parents=True, exist_ok=True)
root_apk = ROOT_FINAL / 'PampAI-knowledge-debug.apk'
shutil.copy2(BUILD / 'app/outputs/apk/debug/app-debug.apk', root_apk)
for p in (QA / 'worker-final').glob('*.png'):
    if not (ROOT_FINAL / p.name).exists():
        shutil.copy2(p, ROOT_FINAL / p.name)

sources = {}
def add(p):
    p = pathlib.Path(p).resolve()
    if not p.is_file():
        raise RuntimeError('Missing review input: ' + str(p))
    if p.name.lower() in {'local.properties', 'key.properties', 'keystore.properties'} or p.suffix.lower() in {'.jks', '.keystore'}:
        raise RuntimeError('Credential/config input forbidden: ' + str(p))
    sources[str(p).casefold()] = p

worker = read(REPO / 'docs/validation-worker/results.json')
inputs = read(REPO / 'docs/validation-worker/final-inputs.json')
changed = [s for s, h in inputs.items() if sha(REPO / s).lower() != h.lower()]
expected = ['feature-assistant\\src\\main\\kotlin\\dev\\pampa\\pampai\\feature\\assistant\\profile\\UpdatesRoute.kt']
if changed != expected:
    raise RuntimeError('Unexpected validated-input changes: ' + str(changed))
for s in inputs:
    add(REPO / s)
for key in ['evidence_hashes', 'logs', 'documentation_hashes']:
    for s in worker[key]:
        add(REPO / s)
for p in (REPO / 'docs/validation-worker').glob('*.json'):
    add(p)
for name in ['aria-knowledge-acceptance.md', 'aria-knowledge-review-brief.md', 'aria-next-phase-contracts.md',
             'aria-source-proof-contract.md', 'aria-proof-renewal-integration.md',
             'aria-provider-lifecycle.md', 'aria-provider-lifecycle-report.md',
             'aria-durable-foundation.md', 'aria-durable-foundation-report.md',
             'aria-runtime-budget.md', 'aria-runtime-budget-report.md',
             'aria-research-consumer.md', 'aria-research-consumer-report.md',
             'aria-profile-ui.md', 'aria-profile-ui-validation.md',
             'aria-workmanager.md', 'aria-workmanager-report.md',
             'aria-knowledge-root-final.log', 'aria-root-profile-visual-check.md']:
    add(REPO / 'docs' / name)
for p in ROOT_FINAL.glob('*'):
    if p.is_file():
        add(p)
for module in ['feature-assistant', 'app']:
    add(BUILD / module / 'reports/lint-results-debug.xml')
for p in (BUILD / 'feature-assistant/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    add(p)

root_counts = dict(tests=0, failures=0, errors=0, skipped=0)
for p in (BUILD / 'feature-assistant/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    n = ET.parse(p).getroot()
    for key in root_counts:
        root_counts[key] += int(n.get(key, '0'))
if root_counts != dict(tests=2, failures=0, errors=0, skipped=0):
    raise RuntimeError('Root targeted screenshot validation not complete: ' + str(root_counts))

# Scope copies for source-proof producer/typed data boundaries, excluding unrelated AirPods/native/UI edits.
patterns = {
    FLUIDIFY: ['app/src/main/AndroidManifest.xml', 'app/src/main/java/dev/lelonio/square/bridge/**/*.kt',
               'app/src/test/java/dev/lelonio/square/bridge/**/*.kt',
               'app/src/main/java/dev/lelonio/square/data/Listening*.kt',
               'engine/engine-ai-bridge/src/**/*.kt', 'engine/engine-ai-bridge/src/**/*.xml', 'engine/engine-ai/src/main/kotlin/**/tools/*.kt',
               'engine/ENGINE_VERSION', 'docs/validation-source-proof/**/*'],
    CV: ['AGENTS.md', 'android/core/core-assistant/src/**/*.kt', 'android/core/core-assistant/build.gradle',
         'android/core/core-domain/src/main/kotlin/**/SchoolDataAccess.kt',
         'android/core/core-domain/src/main/kotlin/**/Repositories.kt',
         'android/core/core-datastore/src/main/kotlin/**/*.kt',
         'android/core/core-data/src/main/kotlin/**/ScopedSchoolDataAccess.kt',
         'android/core/core-data/src/main/kotlin/**/SchoolDataPaging.kt',
         'android/core/core-network/src/main/kotlin/**/ScopedSchoolRead.kt',
         'android/core/core-network/src/main/kotlin/**/NetworkParsers.kt',
         'android/core/core-assistant/src/main/AndroidManifest.xml',
         'android/engine/engine-ai-bridge/src/**/*.kt', 'android/engine/engine-ai-bridge/src/**/*.xml',
         'android/engine/engine-ai/src/main/kotlin/**/tools/*.kt',
         'android/engine/ENGINE_VERSION', 'docs/validation-source-proof/**/*'],
}
for base, globs in patterns.items():
    for pattern in globs:
        for p in base.glob(pattern):
            if p.is_file():
                add(p)

# Include a source APK only if it still matches its recorded successful proof build.
artifact_notes = []
for rel, meta in read(CV / 'docs/validation-source-proof/source-artifact-manifest.json').items():
    p = CV / rel
    if p.suffix == '.apk':
        if p.is_file() and sha(p).lower() == meta['sha256'].lower():
            add(p)
            artifact_notes.append('CV APK matches source-proof validation manifest')
        else:
            artifact_notes.append('CV current APK drifted/missing; historical successful manifest retained, no current APK claim')

roots = [('pampai', REPO), ('fluidify', FLUIDIFY), ('classeviva', CV), ('qa', QA), ('build-evidence', BUILD)]
rows = []
for p in sorted(sources.values(), key=lambda x: str(x).casefold()):
    for label, base in roots:
        try:
            rel = p.relative_to(base.resolve())
            break
        except ValueError:
            continue
    else:
        raise RuntimeError('Unexpected review root: ' + str(p))
    target = REVIEW / 'snapshot' / label / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(p, target)
    digest = sha(target)
    if digest != sha(p):
        raise RuntimeError('Copy drift: ' + str(p))
    rows.append(dict(path=str(target), originalPath=str(p), relativePath=str(pathlib.Path(label) / rel),
                     sha256=digest, size=target.stat().st_size))
manifest = REVIEW / 'manifest.json'
manifest.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
heads = {}
for label, base in roots[:3]:
    heads[label] = subprocess.check_output(['git', '-C', str(base), 'rev-parse', 'HEAD'], text=True).strip()
for label, base in [('pampaiEngine', REPO / 'engine'), ('fluidifyEngine', FLUIDIFY / 'engine'), ('classevivaEngine', CV / 'android/engine')]:
    heads[label] = subprocess.check_output(['git', '-C', str(base), 'rev-parse', 'HEAD'], text=True).strip()
info = dict(phase='CONNECTED KNOWLEDGE INTEGRATION', snapshot='K1', round='R0 initial',
            entries=len(rows), manifestSha256=sha(manifest), heads=heads,
            apkSha256=sha(root_apk), apkBytes=root_apk.stat().st_size,
            workerCounts=worker['counts'], rootTargetedCounts=root_counts,
            changedAfterWorker=changed, sourceArtifactNotes=artifact_notes,
            validation='Worker full231/91/264/25 then root text-only UI2; do not sum repeats. Root final lint/assembly log retained.',
            scope='Immutable copied sources/evidence only. Source proof producers included; native/history broad source review and real device/auth/billing remain outside evidence.')
(REVIEW / 'snapshot-info.json').write_text(json.dumps(info, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(info, ensure_ascii=False, indent=2))
