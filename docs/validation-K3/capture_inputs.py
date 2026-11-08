"""Capture declared build inputs without reading local signing or credential configuration."""
from pathlib import Path
import hashlib
import json
import sys

root = Path(__file__).resolve().parents[2]
out = Path(__file__).resolve().parent
paths = {row['path'] for row in json.loads((out/'final-3-inputs-before.json').read_text(encoding='utf-8-sig'))}
for module in ['app', 'core-assistant', 'feature-assistant']:
    paths.update(str(p.relative_to(root)).replace('\\', '/') for p in (root/module/'src').rglob('*') if p.is_file())
rows = [dict(path=p, size=(root/p).stat().st_size, sha256=hashlib.sha256((root/p).read_bytes()).hexdigest()) for p in sorted(paths)]
(out/sys.argv[1]).write_text(json.dumps(rows, indent=2)+'\n', encoding='utf-8')
print(f'{len(rows)} declared inputs captured')
