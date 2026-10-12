"""Read-only NI/SX configuration census; never reads upstream implementation source."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

import yaml


def text(path: Path) -> str:
    if getattr(path.stat(), "st_file_attributes", 0) & 0x400000:
        raise RuntimeError(f"Cloud-only input: {path}")
    return path.read_text(encoding="utf-8-sig")


def walk(value, path=""):
    yield path, value
    if isinstance(value, dict):
        for key, child in value.items():
            yield from walk(child, f"{path}.{key}" if path else str(key))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from walk(child, f"{path}[{index}]")


def config_inventory(root: Path, language="auto") -> dict:
    counts, fields, types, inline = Counter(), Counter(), Counter(), Counter()
    definitions, files, imports, errors = [], [], [], []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in (".yml", ".yaml", ".js"):
            continue
        relative = path.relative_to(root).as_posix()
        parts = relative.split("/")
        syntax = language
        if parts[0] == "SX-Item":
            syntax, parts = "sx", parts[1:]
        elif syntax == "auto":
            syntax = "sx" if parts[0] in ("Item", "RandomString") or (root / "Item").is_dir() else "ni"
        if syntax == "sx" and any(part.startswith("NoLoad") for part in parts):
            continue
        category = parts[0] if len(parts) > 1 else "root"
        source = text(path)
        counts[f"{syntax}/{category}"] += 1
        files.append({"file": relative, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        for match in re.finditer(r"(?:Packages\.)?(pers\.neige\.[\w.$]+)", source):
            # May be a comment, qualified member, or an actual import: retain evidence.
            imports.append({"file": relative, "line": source.count("\n", 0, match.start()) + 1,
                            "reference": match.group(1)})
        if path.suffix.lower() == ".js":
            continue
        try:
            data = yaml.safe_load(source)
        except yaml.YAMLError as error:
            errors.append({"file": relative, "error": str(error)})
            continue
        if category == ("Item" if syntax == "sx" else "Items") and isinstance(data, dict):
            for identifier, definition in data.items():
                if syntax == "sx" and str(identifier).startswith("NoLoad"):
                    continue
                if isinstance(definition, dict) or (syntax == "sx" and isinstance(definition, str)):
                    row = {"id": str(identifier), "file": relative, "format": syntax}
                    if isinstance(definition, str):
                        row["alias"] = definition
                    definitions.append(row)
                if isinstance(definition, dict):
                    fields.update(map(str, definition.keys()))
        for node, value in walk(data):
            if isinstance(value, dict) and isinstance(value.get("type"), str):
                types[value["type"]] += 1
            if isinstance(value, str):
                for kind in re.findall(r"<([\w.-]+)::", value):
                    inline[kind] += 1
    return {"counts": dict(counts), "item_count": len(definitions), "item_fields": dict(fields),
            "configured_types": dict(types), "inline_nodes": dict(inline), "items": definitions,
            "files": files, "java_references": imports, "parse_errors": errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, action="append", required=True)
    parser.add_argument("--format", choices=("auto", "ni", "sx"), default="auto")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if any(output.is_relative_to(path.resolve()) for path in args.config):
        raise SystemExit("Report output must be outside the read-only inputs")
    for path in args.config:
        if not path.is_dir():
            raise SystemExit(f"Configuration root is not a directory: {path}")
    report = {"schema_version": 2,
              "note": "Configuration-only census; not a runtime-support test or authorship proof. Scripts are not executed.",
              "inputs": [config_inventory(path.resolve(), args.format) for path in args.config]}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps([{key: value[key] for key in ("counts", "item_count", "parse_errors")}
                      for value in report["inputs"]], ensure_ascii=False, indent=2))
    print("Report:", output)


if __name__ == "__main__":
    main()
