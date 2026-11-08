"""Capture final R3 build inputs after all source owners release; excludes signing configuration."""
from pathlib import Path
import hashlib
import json
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
baseline = ROOT/'docs/validation-K3/privacy-7-inputs-before.json'
paths = {r['path'] for r in json.loads(baseline.read_text(encoding='utf-8-sig'))}
for module in ['app','core-assistant','feature-assistant']:
    paths.update(str(p.relative_to(ROOT)).replace('\\','/') for p in (ROOT/module/'src').rglob('*') if p.is_file())
rows = [dict(path=p,size=(ROOT/p).stat().st_size,sha256=hashlib.sha256((ROOT/p).read_bytes()).hexdigest()) for p in sorted(paths)]
(OUT/sys.argv[1]).write_text(json.dumps(rows,indent=2)+'\n',encoding='utf-8')
print(f'{len(rows)} declared build inputs captured')
