"""Check the reviewed production source inventory, not legal authorship or originality."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys


PROJECT = Path(__file__).resolve().parents[1]
MODULES = ("core", "compat-ni", "compat-sx", "paper26", "vendor/keystone", "vendor/itembridge")


def support_inputs() -> dict[str, Path]:
    paths = set()
    for module in MODULES:
        resources = PROJECT / module / "src/main/resources"
        paths.update(path for path in resources.rglob("*") if path.is_file())
        paths.update((PROJECT / module).glob("*.gradle.kts"))
    for folder in ("licenses", "gradle", "tools"):
        paths.update(path for path in (PROJECT / folder).rglob("*")
                     if path.is_file() and "__pycache__" not in path.parts)
    for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                 "gradlew", "gradlew.bat", "LICENSE", "NOTICE.md",
                 "provenance/runtime-dependencies.json", "provenance/snakeyaml-fork.md",
                 "vendor/keystone/LICENSE", "vendor/keystone/README.md", "vendor/keystone/upstream.json",
                 "vendor/itembridge/README.md", "vendor/itembridge/LICENSE"):
        paths.add(PROJECT / name)
    return {path.relative_to(PROJECT).as_posix(): path for path in paths}


def digest(path: Path) -> str:
    data = path.read_bytes() if path.suffix == ".jar" else path.read_text(encoding="utf-8").encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def verify() -> list[str]:
    manifest = json.loads((PROJECT / "provenance/source-inventory.json").read_text(encoding="utf-8"))
    version = next(line.partition("=")[2].strip() for line in
                   (PROJECT / "gradle.properties").read_text(encoding="utf-8").splitlines()
                   if line.startswith("version="))
    failures = []
    if manifest.get("version") != version:
        failures.append("Source review version differs from the project version")
    reviewed = {**manifest["files"], **manifest.get("support_files", {})}
    actual = {path.relative_to(PROJECT).as_posix(): path
              for module in MODULES
              for path in (PROJECT / module / "src/main/java").rglob("*.java")}
    actual.update(support_inputs())
    for name in sorted(set(actual) - set(reviewed)):
        failures.append(f"Unreviewed production input: {name}")
    for name in sorted(set(reviewed) - set(actual)):
        failures.append(f"Reviewed input was removed: {name}")
    for name in sorted(set(actual) & set(reviewed)):
        row = reviewed[name]
        # Git may check text out with CRLF on Windows. The manifest hashes UTF-8 text
        # with LF newlines so the public source archive and both checkouts agree.
        if digest(actual[name]) != row.get("sha256_lf", row.get("sha256")):
            failures.append(f"Source changed after review: {name}")
        if not row.get("classification") or not row.get("reason"):
            failures.append(f"Missing review decision: {name}")
    print(f"Production source/build/resource inventory: {len(actual)} files; {len(failures)} problem(s)")
    for failure in failures:
        print(failure, file=sys.stderr)
    return failures


if __name__ == "__main__":
    raise SystemExit(bool(verify()))
