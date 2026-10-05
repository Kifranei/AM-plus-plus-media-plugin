"""Verify the pinned compile-only SDK and public renderer references."""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    manifest = json.loads((ROOT / "lib/sdk-manifest.json").read_text(encoding="utf-8"))
    for artifact in manifest["artifacts"]:
        path = ROOT / "lib" / artifact["file"]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == artifact["sha256"], path.name
        with zipfile.ZipFile(path) as jar:
            classes = [name for name in jar.namelist() if name.endswith(".class")]
            assert classes and all(name.startswith(artifact["classPrefix"]) for name in classes), path.name
        print(f"PASS: {path.name}; {len(classes)} public reference classes")


if __name__ == "__main__":
    main()
