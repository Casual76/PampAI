from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
r = json.loads((OUT/'results.json').read_text(encoding='utf-8'))
apk = r['apk']
path = apk['path'].replace('\\', '/')
sha = apk['sha256'].upper()
assert r['counts']['core-assistant']['tests'] == 307 and len(r['sourceDelta']) == 7
p = ROOT/'docs/aria-delivery-status.md'
t = p.read_text(encoding='utf-8')
a = t.index('This is a resumable checkpoint')
b = t.index('## Implemented and reviewable')
t = t[:a] + f'''This is an implementation checkpoint, **not runtime or independent final phase approval**. Updated2026-11-08 after both final K4 R3/3 reviews and localized ROOT closure of all four concrete findings: pasted-data refusal, legacy-memory dispatch, ordinary publication after Room commit, and queued Ricorda safety waiting. Their real Engine/Room/tool/orchestrator/gate regressions pass locally. Later ROOT changes have not been independently rereviewed; no additional delegated review cycle was started. [Both provisional reviews and C1-C7 adjudication](aria-K4-review.md). All runtime NV stays open and no autonomous area is enabled.

Latest execution: **307 core tests**, zero failures/errors/skips; core/app lint0errors and APK assembly passed in3m35s. The **104 feature**, engine264 and bridge25 cases are distinct retained executions, not rerun or added. All688 declared inputs stayed fixed; exactly seven core source/test files differ from frozen K4. Current APK: `{path}`,{apk['size']}bytes,SHA256 `{sha}`. Not installed. [Final follow-up evidence and limits](aria-K4-finalization.md). Reviewed K4 and earlier I5/S4 histories remain separate; advancement is user-authorized, not a gate pass. Profile, source setup and finite silent research are implemented; autonomous execution requires explicit setup and remains off/unconfigured by default.

''' + t[b:]
lines = t.splitlines()
for i, line in enumerate(lines):
    if line.startswith('| PampAI |'):
        lines[i] = '| PampAI | Final ROOT follow-up fresh core307, zero failures/errors/skips; core/app lint0errors (24/39warnings); APKassembly pass3m35s | Feature104/feature lint retained K4 full-5; engine264/bridge25 retained K2. APK063D6B3A...1C07B2D,105260029bytes; no follow-up independent/device approval |'
        break
else:
    raise AssertionError('Missing PampAI validation row')
p.write_text('\n'.join(lines)+'\n', encoding='utf-8')
entry = f'''

## K4 localized ROOT closure complete - 2026-11-08

Both final independent Sol xhigh reports retained with provisional C1-C7 table in aria-K4-review.md. All four concrete findings addressed locally; no additional delegate cycle or independent approval attributed to later code. Final-5 freshly307core/47XML, zero failures/errors/skips; core/app lint0errors24/39warnings, app1hint; assembleDebug pass3m35s.688inputs stable, seven core-only source/test deltas archived byte-for-byte. Feature104 retained K4full-5, engine264/bridge25 retained K2, not rerun or summed. APK{sha},{apk['size']}bytes; not installed. Prior failed fixture runs/XML and passing one-case targeted run retained, not added. All eight ROOT compiler sessions exited; sole runner released. Profile/source setup/finite silent work implementation delivered; areas remain off until explicit setup. User-authorized advancement remains valid. All runtime NV open; no driver bypass, commits, push or publication.
'''
with (ROOT/'docs/aria-workflow.md').open('ab') as f:
    f.write(entry.encode('ascii'))
print('Delivery status and workflow updated from final artifact evidence')
