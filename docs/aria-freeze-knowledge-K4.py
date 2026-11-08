"""Freeze final R3 integration once; preserve all previous review copies."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[1]
QA=Path('C:/Users/casua/AppData/Local/PampAI-qa')
REVIEW=QA/'review-K4'
RESULTS=ROOT/'docs/validation-K4'
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest().upper()
assert not REVIEW.exists(),'Do not replace an immutable review'
r=read(RESULTS/'results.json');assert r['buildSuccessful'] and not r['changedInputs']
sources={}
def add(p,logical=None,origin=None):
    p=Path(p).resolve();assert p.is_file(),p
    assert p.name.lower() not in {'local.properties','key.properties','keystore.properties'}
    assert p.suffix.lower() not in {'.jks','.keystore'}
    if logical is None:
        for label,base in [('pampai',ROOT),('qa',QA)]:
            try:logical=str(Path(label)/p.relative_to(base.resolve()));break
            except ValueError:pass
        else:raise AssertionError(p)
    assert not Path(logical).is_absolute() and '..' not in Path(logical).parts
    key=logical.casefold();assert key not in sources or sources[key][0]==p
    sources[key]=(p,logical,origin)
for row in read(RESULTS/r['finalInputsManifest']):
    p=ROOT/row['path'];assert sha(p).lower()==row['sha256'].lower(),p;add(p)
old=read(QA/'review-K3/manifest.json');producer=0
for row in old:
    if Path(row['relativePath']).parts[0] in {'fluidify','classeviva'}:
        p=Path(row['path']);assert sha(p).lower()==row['sha256'].lower(),p
        add(p,row['relativePath'],row.get('historicalProducerOriginalPath',row['originalPath']));producer+=1
for name in ['aria-knowledge-acceptance.md','aria-knowledge-K4-review-brief.md','aria-K4-integration-report.md',
    'aria-next-phase-contracts.md','aria-source-proof-contract.md','aria-provider-lifecycle.md','aria-durable-foundation.md',
    'aria-runtime-budget.md','aria-research-consumer.md','aria-profile-ui.md','aria-workmanager.md',
    'aria-K3-research-acceptance.md','aria-K3-memory-classification.md']:
    add(ROOT/'docs'/name)
for p in RESULTS.rglob('*'):
    if p.is_file():add(p)
for p in (ROOT/'docs/validation-K1-root-source').glob('*.json'):add(p)
for p in (QA/'knowledge-K4/screenshots').glob('*.png'):add(p)
add(r['apk']['path'])
rows=[]
for p,logical,origin in sorted(sources.values(),key=lambda x:x[1].casefold()):
    target=REVIEW/'snapshot'/logical;target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(p,target);assert sha(p)==sha(target)
    row=dict(path=str(target),originalPath=str(p),relativePath=logical,sha256=sha(target),size=target.stat().st_size)
    if origin:row['historicalProducerOriginalPath']=origin
    rows.append(row)
manifest=REVIEW/'manifest.json';manifest.write_text(json.dumps(rows,indent=2)+'\n',encoding='utf-8')
heads={label:subprocess.check_output(['git','-C',str(base),'rev-parse','HEAD'],text=True).strip()
    for label,base in [('pampai',ROOT),('pampaiEngine',ROOT/'engine')]}
info=dict(phase='CONNECTED KNOWLEDGE INTEGRATION',snapshot='K4',round='Final correction R3/3',entries=len(rows),
    manifestSha256=sha(manifest),heads=heads,counts=r['counts'],taskExecution=r['taskExecution'],lint=r['lint'],
    trackedInputs=r['inputCount'],changedDuringValidation=r['changedInputs'],apkSha256=r['apk']['sha256'].upper(),
    apkBytes=r['apk']['size'],pngs=len(r['pngs']),validation=r['validation'],
    producerContract=dict(origin='Immutable K3 copy of the K2 producer contract/evidence',entries=producer,
        sourceManifestSha256=sha(QA/'review-K3/manifest.json'),
        historicalHeads=read(QA/'review-K3/snapshot-info.json')['producerContract']['historicalHeads'],
        note='No current source-app build, source APK or runtime verification claim.'))
(REVIEW/'snapshot-info.json').write_text(json.dumps(info,indent=2)+'\n',encoding='utf-8')
print(json.dumps(info,indent=2))
