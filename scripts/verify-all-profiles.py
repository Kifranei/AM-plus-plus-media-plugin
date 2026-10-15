"""Verify every shipped host profile against its APK, when that APK is available.

Usage:
    python scripts/verify-all-profiles.py [--apk-dir DIR ...] [--apk VERSION_CODE=PATH ...] [--require-all]

APKs (apk, apkm, xapk, apks) are located by version: every file under the given directories is read
for its own manifest tuple, so file names do not matter. Without an APK a profile is reported SKIP
(CI has no Apple Music binaries); --require-all turns SKIP into a failure. Exit status is non-zero if
any available APK fails verification.
"""
import argparse
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "media-plugin/src/main/assets"


def manifest_identity(path):
    spec = importlib.util.spec_from_file_location("host_profile", ROOT / "scripts/verify-host-profile.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    with zipfile.ZipFile(path) as package:
        names = package.namelist()
        base = next((n for n in names if n.endswith(".apk") and "config" not in n), None)
        apk = zipfile.ZipFile(io.BytesIO(package.read(base))) if base else package
        package_name, version_name, version_code = module._manifest_module.manifest_identity(apk.read("AndroidManifest.xml"))
    return package_name, version_name, int(version_code)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk-dir", type=Path, action="append", default=[])
    parser.add_argument("--apk", action="append", default=[], metavar="CODE=PATH")
    parser.add_argument("--require-all", action="store_true")
    args = parser.parse_args()

    index = json.loads((ASSETS / "profiles.json").read_text(encoding="utf-8"))
    found = {int(code): Path(path) for code, path in (item.split("=", 1) for item in args.apk)}
    for directory in args.apk_dir:
        for path in sorted(directory.rglob("*")):
            if path.suffix.lower() not in (".apk", ".apkm", ".xapk", ".apks") or not path.is_file():
                continue
            try:
                package_name, version_name, version_code = manifest_identity(path)
            except Exception:
                continue
            if package_name == index["packageName"]:
                found.setdefault(version_code, path)

    failed = skipped = 0
    for entry in index["profiles"]:
        code = entry["versionCode"]
        path = found.get(code)
        if path is None:
            print(f"SKIP {entry['versionName']} ({code}): no APK available")
            skipped += 1
            continue
        result = subprocess.run([sys.executable, "-B", str(ROOT / "scripts/verify-host-profile.py"), str(path), "--glass",
                                 "--profile", str(ASSETS / entry["asset"])], capture_output=True, text=True)
        print(f"{'PASS' if result.returncode == 0 else 'FAIL'} {entry['versionName']} ({code}): {path.name}")
        print("\n".join("    " + line for line in result.stdout.strip().splitlines()[-4:]))
        if result.returncode:
            failed += 1
            print(result.stderr.strip())
    if failed or (args.require_all and skipped):
        raise SystemExit(f"{failed} profile(s) failed, {skipped} skipped")
    print(f"OK: {len(index['profiles']) - skipped} verified, {skipped} skipped")


if __name__ == "__main__":
    main()
