"""Read-only compatibility census; evidence is not a claim of runtime support."""
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


def config_inventory(root: Path) -> dict:
    counts, fields, types, inline = Counter(), Counter(), Counter(), Counter()
    definitions, files, imports, errors = [], [], [], []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in (".yml", ".yaml", ".js"):
            continue
        relative = path.relative_to(root).as_posix()
        category = relative.split("/")[0] if "/" in relative else "root"
        source = text(path)
        counts[category] += 1
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
        if category == "Items" and isinstance(data, dict):
            for identifier, definition in data.items():
                if isinstance(definition, dict):
                    definitions.append({"id": str(identifier), "file": relative})
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


def source_inventory(root: Path) -> dict:
    parsers = {}
    for relative in ("action/node/impl", "section/impl"):
        found = []
        base = root / "src/main/java/pers/neige/neigeitems" / relative
        for path in sorted(base.glob("*.java")):
            source = text(path)
            ids = re.findall(r"String getId\(\)\s*\{\s*return\s+\"([^\"]+)\"", source)
            for identifier in ids:
                found.append({"id": identifier, "file": path.relative_to(root).as_posix()})
        parsers[relative] = found
    base = root / "src/main/java/pers/neige/neigeitems"
    manager = text(base / "manager/BaseActionManager.java")
    actions = []
    for match in re.finditer(r'(?:addConsumer|addFunction|addAction)\(\s*("[^"]+"|Arrays\.asList\([^)]*\))', manager):
        actions.extend(re.findall(r'"([^"]+)"', match.group(1)))
    editors = re.findall(r'(?:addItemEditor|addBasicItemEditor)\(\s*"([^"]+)"',
                         text(base / "manager/ItemEditorManager.java"))
    return {"parsers": parsers, "actions": actions, "editors": editors,
            "action_implementations": [p.stem for p in sorted((base / "action/impl").glob("*.java"))],
            "note": "Lexical inventory only; dynamic registrations and reflection need manual review."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    for source in (args.reference.resolve(), args.config.resolve()):
        if output.is_relative_to(source):
            raise SystemExit("Report output must be outside the read-only inputs")
    report = {"source": source_inventory(args.reference),
              "shipped": config_inventory(args.reference / "src/main/resources"),
              "server": config_inventory(args.config)}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: {n: v[n] for n in ("counts", "item_count", "item_fields", "configured_types", "inline_nodes", "parse_errors")}
                      for k, v in report.items() if k != "source"}, ensure_ascii=False, indent=2))
    print("Source parser counts:", {k: len(v) for k, v in report["source"]["parsers"].items()})
    print("Actions:", len(report["source"]["actions"]), "Editors:", len(report["source"]["editors"]))
    print("Report:", output)


if __name__ == "__main__":
    main()
