"""Verify that the artifact auditor rejects altered notices and stale descriptors."""

import argparse
from copy import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import warnings
from zipfile import ZipFile, ZIP_DEFLATED


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    if output.is_relative_to(project) or any(part.lower() == "test_server" for part in output.parts):
        raise ValueError("Guard-test output must be outside the source and deployment server")
    source = args.jar.resolve(strict=True)
    output.mkdir(parents=True, exist_ok=True)
    auditor = project / "tools/verify_artifact.py"

    def audit(jar, name):
        report = output / (name + ".json")
        run = subprocess.run([sys.executable, "-B", str(auditor), str(jar), "--out", str(report)],
                             capture_output=True, text=True, encoding="utf-8")
        if not report.exists():
            raise AssertionError(f"Auditor failed to produce evidence: {run.stderr}")
        return run.returncode, json.loads(report.read_text(encoding="utf-8"))

    code, baseline = audit(source, "unmodified")
    if code or not baseline["passed"]:
        raise AssertionError("The unmodified candidate must pass before negative checks")
    scenarios = {
        "root-license": ("LICENSE", b"incorrect license\n", "content differs"),
        "source-notice": ("NOTICE.md", b"incorrect source attribution\n", "content differs"),
        "itembridge-license": ("META-INF/licenses/itembridge-MIT.txt", b"incorrect license\n", "content differs"),
        "itembridge-modifications": ("META-INF/licenses/itembridge-modifications.md", b"stale modifications\n", "content differs"),
        "invalid-notice-encoding": ("NOTICE.md", b"\xff\xfe", "Invalid UTF-8"),
        "stale-descriptor": ("plugin.yml", b"name: ItemLoom\nversion: '0.0.0'\nmain: dev.itemloom.paper.ItemLoomPlugin\n", "descriptor differs"),
        "duplicate-notice": ("NOTICE.md", None, "Duplicate archive entry"),
    }
    results = {}
    with tempfile.TemporaryDirectory(prefix="artifact-guards-", dir=output) as scratch:
        altered = Path(scratch) / "altered.jar"
        for name, (entry, payload, expected) in scenarios.items():
            with ZipFile(source) as original, ZipFile(altered, "w", ZIP_DEFLATED) as candidate:
                for info in original.infolist():
                    data = payload if info.filename == entry and payload is not None else original.read(info)
                    candidate.writestr(copy(info), data)
                if payload is None:
                    with warnings.catch_warnings():
                        warnings.simplefilter("ignore", UserWarning)
                        candidate.writestr(entry, original.read(entry))
            code, report = audit(altered, name)
            rejected = code != 0 and not report["passed"] and any(expected in v for v in report["violations"])
            if not rejected:
                raise AssertionError(f"Invalid candidate was not correctly rejected: {name}")
            results[name] = "rejected"
    summary = {"passed": True, "candidateSha256": baseline["sha256"], "negativeChecks": results}
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
