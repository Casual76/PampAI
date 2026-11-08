"""Freeze K3 without replacing earlier reviews or claiming mutable source-app validation."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
QA = Path('C:/Users/casua/AppData/Local/PampAI-qa')
REVIEW = QA/'review-K3'
RESULTS = ROOT/'docs/validation-K3'
def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest().upper()
assert not REVIEW.exists(), 'Do not replace an immutable review'
results = read(RESULTS/'privacy-results.json')
assert results['buildSuccessful'] and not results['changedInputs']
sources = {}
def add(p, logical=None, origin=None):
    p=Path(p).resolve(); assert p.is_file(), p
    assert p.name.lower() not in {'local.properties','key.properties','keystore.properties'}
    assert p.suffix.lower() not in {'.jks','.keystore'}
    if logical is None:
        for label, base in [('pampai',ROOT),('qa',QA)]:
            try: logical=str(Path(label)/p.relative_to(base.resolve())); break
            except ValueError: pass
        else: raise AssertionError(p)
    assert not Path(logical).is_absolute() and '..' not in Path(logical).parts
    key=str(logical).casefold()
    assert key not in sources or sources[key][0] == p
    sources[key]=(p,logical,origin)
for row in read(RESULTS/results['finalInputsManifest']):
    p=ROOT/row['path']; assert sha(p).lower()==row['sha256'].lower(),p
    add(p)
# Use the immutable producer contract reviewed in K2. Unrelated source-app sessions continue;
# no mutable producer file or newer APK is silently substituted into this app-only review.
old=read(QA/'review-K2/manifest.json')
producer_count=0
for row in old:
    if Path(row['relativePath']).parts[0] in {'fluidify','classeviva'}:
        p=Path(row['path']); assert sha(p).lower()==row['sha256'].lower(),p
        add(p,row['relativePath'],row['originalPath']);producer_count+=1
for name in ['aria-knowledge-acceptance.md','aria-knowledge-K3-review-brief.md','aria-K3-integration-report.md',
    'aria-next-phase-contracts.md','aria-source-proof-contract.md','aria-provider-lifecycle.md',
    'aria-durable-foundation.md','aria-runtime-budget.md','aria-research-consumer.md','aria-profile-ui.md',
    'aria-workmanager.md','aria-K2-chat-privacy.md','aria-K2-R2-corrections.md']:
    add(ROOT/'docs'/name)
for p in RESULTS.rglob('*'):
    if p.is_file():add(p)
for p in (ROOT/'docs/validation-K1-root-source').glob('*.json'):add(p)
for p in (QA/'knowledge-K3/screenshots').glob('*.png'):add(p)
add(results['apk']['path'])
rows=[]
for p,logical,origin in sorted(sources.values(),key=lambda row:row[1].casefold()):
    target=REVIEW/'snapshot'/logical;target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(p,target);assert sha(p)==sha(target)
    row=dict(path=str(target),originalPath=str(p),relativePath=logical,sha256=sha(target),size=target.stat().st_size)
    if origin: row['historicalProducerOriginalPath']=origin
    rows.append(row)
manifest=REVIEW/'manifest.json';manifest.write_text(json.dumps(rows,indent=2)+'\n',encoding='utf-8')
heads={label:subprocess.check_output(['git','-C',str(base),'rev-parse','HEAD'],text=True).strip()
    for label,base in [('pampai',ROOT),('pampaiEngine',ROOT/'engine')]}
oldinfo=read(QA/'review-K2/snapshot-info.json')
info=dict(phase='CONNECTED KNOWLEDGE INTEGRATION',snapshot='K3',round='Correction R2/3',entries=len(rows),
    manifestSha256=sha(manifest),heads=heads,counts=results['counts'],taskExecution=results['taskExecution'],lint=results['lint'],
    trackedInputs=results['inputCount'],changedDuringValidation=results['changedInputs'],
    apkSha256=results['apk']['sha256'].upper(),apkBytes=results['apk']['size'],pngs=len(results['pngs']),
    validation=results['validation'],producerContract=dict(origin='Immutable review-K2 snapshot',entries=producer_count,
    manifestSha256=sha(QA/'review-K2/manifest.json'),historicalHeads=oldinfo['heads'],
    note='Copied producer contracts/evidence only; no current source-app build or runtime verification.'))
(REVIEW/'snapshot-info.json').write_text(json.dumps(info,indent=2)+'\n',encoding='utf-8')
print(json.dumps(info,indent=2))
