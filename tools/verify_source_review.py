"""Check the reviewed production source inventory, not legal authorship or originality."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys


PROJECT = Path(__file__).resolve().parents[1]
MODULES = ("core", "compat-ni", "compat-sx", "paper26", "vendor/keystone", "vendor/itembridge")


def verify() -> list[str]:
    manifest = json.loads((PROJECT / "provenance/source-inventory.json").read_text(encoding="utf-8"))
    version = next(line.partition("=")[2].strip() for line in
                   (PROJECT / "gradle.properties").read_text(encoding="utf-8").splitlines()
                   if line.startswith("version="))
    failures = []
    if manifest.get("version") != version:
        failures.append("Source review version differs from the project version")
    reviewed = manifest["files"]
    actual = {path.relative_to(PROJECT).as_posix(): path
              for module in MODULES
              for path in (PROJECT / module / "src/main/java").rglob("*.java")}
    for name in sorted(set(actual) - set(reviewed)):
        failures.append(f"Unreviewed production input: {name}")
    for name in sorted(set(reviewed) - set(actual)):
        failures.append(f"Reviewed input was removed: {name}")
    for name in sorted(set(actual) & set(reviewed)):
        row = reviewed[name]
        # Git may check text out with CRLF on Windows. The manifest hashes UTF-8 text
        # with LF newlines so the public source archive and both checkouts agree.
        source = actual[name].read_text(encoding="utf-8").encode("utf-8")
        if hashlib.sha256(source).hexdigest() != row.get("sha256_lf"):
            failures.append(f"Source changed after review: {name}")
        if not row.get("classification") or not row.get("reason"):
            failures.append(f"Missing review decision: {name}")
    print(f"Production source inventory: {len(actual)} files; {len(failures)} problem(s)")
    for failure in failures:
        print(failure, file=sys.stderr)
    return failures


if __name__ == "__main__":
    raise SystemExit(bool(verify()))
