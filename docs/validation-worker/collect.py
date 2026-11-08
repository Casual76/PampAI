"""Archive final synthetic validation and tie it to unchanged source inputs. No user stores read."""
from pathlib import Path
import datetime
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BUILD = Path("C:/Users/casua/AppData/Local/PampAI-worker-build")
QA = Path("C:/Users/casua/AppData/Local/PampAI-qa/worker-final")
OUT = ROOT / "docs/validation-worker/final"
OUT.mkdir(parents=True, exist_ok=True)

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

inputs = json.loads((ROOT / "docs/validation-worker/final-inputs.json").read_text(encoding="utf-8"))
changed = [name for name, sha in inputs.items() if not (ROOT / name).is_file() or digest(ROOT / name) != sha]
counts = {}
cases = {}
for module in ("core-assistant", "feature-assistant", "engine-ai", "engine-ai-bridge"):
    dest = OUT / module
    dest.mkdir(exist_ok=True)
    totals = dict(tests=0, failures=0, errors=0, skipped=0, screenshots=0)
    module_cases = []
    for path in (BUILD / module / "test-results/testDebugUnitTest").glob("*.xml"):
        shutil.copy2(path, dest / path.name)
        suite = ET.parse(path).getroot()
        for key in ("tests", "failures", "errors", "skipped"):
            totals[key] += int(suite.get(key, 0))
        for case in suite.findall("testcase"):
            name = f"{case.get('classname')}.{case.get('name')}"
            module_cases.append(name)
            if "Screenshots" in name:
                totals["screenshots"] += 1
    counts[module] = totals
    cases[module] = sorted(module_cases)

lint = {}
for module in ("core-assistant", "feature-assistant", "app"):
    path = BUILD / module / "reports/lint-results-debug.xml"
    shutil.copy2(path, OUT / f"{module}-lint.xml")
    issues = ET.parse(path).getroot().findall("issue")
    lint[module] = {"errors": sum(x.get("severity") in ("Error", "Fatal") for x in issues),
                    "warnings": sum(x.get("severity") == "Warning" for x in issues)}

baseline = json.loads((ROOT / "docs/validation-profile-ui/frozen-inputs.json").read_text(encoding="utf-8-sig"))
baseline_changed = [name for name, sha in baseline.items() if digest(ROOT / name) != sha]
baseline_cases = set()
for path in (ROOT / "docs/validation-research-consumer/release-core").glob("*.xml"):
    for case in ET.parse(path).getroot().findall("testcase"):
        baseline_cases.add(f"{case.get('classname')}.{case.get('name')}")

apk = QA / "PampAI-worker-debug.apk"
shutil.copy2(BUILD / "app/outputs/apk/debug/app-debug.apk", apk)
manifest_paths = list((BUILD / "app/intermediates/merged_manifests").rglob("AndroidManifest.xml"))
if not manifest_paths:
    manifest_paths = list((BUILD / "app/intermediates/merged_manifest").rglob("AndroidManifest.xml"))
manifest = next(p for p in manifest_paths if "debug" in str(p).lower())
shutil.copy2(manifest, OUT / "AndroidManifest.xml")
manifest_text = manifest.read_text(encoding="utf-8")
start = datetime.datetime.fromisoformat(json.loads((ROOT / "docs/validation-worker/final-start.json").read_text())["utc"]).timestamp()
pngs = {str(p): {"sha256": digest(p), "bytes": p.stat().st_size, "fresh": p.stat().st_mtime >= start} for p in QA.glob("*.png")}
logs = {str(p.relative_to(ROOT)): digest(p) for p in (ROOT / "docs").glob("aria-worker-*.log")}
results = {
    "source_inputs": len(inputs), "source_changes_during_final_run": changed,
    "baseline_compared": len(baseline), "baseline_changed_authorized_files": baseline_changed,
    "missing_prerequisite_core_cases": sorted(baseline_cases - set(cases["core-assistant"])),
    "counts": counts, "lint": lint,
    "apk": {"path": str(apk), "bytes": apk.stat().st_size, "sha256": digest(apk)},
    "manifest": {"sha256": digest(manifest), "default_work_initializer_removed": "androidx.work.WorkManagerInitializer" not in manifest_text,
                 "application": "dev.pampa.pampai.PampaiApplication" in manifest_text},
    "pngs": pngs, "logs": logs, "cases": cases,
    "evidence_hashes": {str(p.relative_to(ROOT)): digest(p) for p in OUT.rglob("*") if p.is_file()},
    "runtime_limitations": "Synthetic sources/models only; no device, live provider, authenticated Binder/native or OS process-death acceptance.",
}
(ROOT / "docs/validation-worker/results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
print(json.dumps({k: v for k, v in results.items() if k in ("source_inputs", "source_changes_during_final_run", "counts", "lint", "apk", "manifest", "missing_prerequisite_core_cases")}, indent=2))
print("PNG captures", len(pngs), "fresh", sum(v["fresh"] for v in pngs.values()))
