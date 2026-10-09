"""Audit the actual candidate JAR; output belongs outside the deployment server."""

import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("jar", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--forbid-text", action="append", default=[], help="Case-insensitive text forbidden in any JAR entry or payload")
    args = parser.parse_args()
    violations = []
    with ZipFile(args.jar) as jar:
        names = jar.namelist()
        classes = [name for name in names if name.endswith(".class")]
        # Runtime capabilities are public; server-owned artwork and deployment inputs are not.
        for name in names:
            if name.startswith(("assets/", "resourcepack/", "integrations/")):
                violations.append(f"{name}: server asset/deployment resource")
            if name.lower().endswith((".png", ".jpg", ".jpeg", ".webp", ".ogg", ".wav", ".bbmodel")):
                violations.append(f"{name}: artwork/media is not part of this plugin distribution")
            for forbidden in args.forbid_text:
                if forbidden.lower() in name.lower() or (not name.endswith("/") and forbidden.lower().encode("utf-8") in jar.read(name).lower()):
                    violations.append(f"{name}: forbidden text {forbidden!r}")
        for prefix in ("pers/neige/", "github/saukiya/", "kotlin/", "kotlinx/", "taboolib/", "dev/keystone/", "dev/itemloom/probe/",
                       "net/milkbowl/vault/", "io/lumine/mythic/", "me/clip/placeholderapi/",
                       "dev/lone/itemsadder/", "io/th0rgal/oraxen/", "pku/yim/magicgem/"):
            violations.extend(name for name in names if name.startswith(prefix))
        for name in classes:
            data = jar.read(name)
            if name.startswith("dev/itemloom/core/"):
                for dependency in (b"org/bukkit/", b"net/minecraft/", b"dev/itemloom/compat/", b"dev/itemloom/paper/"):
                    if dependency in data:
                        violations.append(f"{name}: core dependency on {dependency.decode()}")
            # Aliases deliberately use dotted script names; JVM type descriptors must not.
            if name.startswith("dev/itemloom/") and b"pers/neige/neigeitems/" in data:
                violations.append(f"{name}: direct NI type reference")
            if name.startswith("dev/itemloom/") and b"github/saukiya/" in data:
                violations.append(f"{name}: direct SX type reference")
        descriptor = jar.read("plugin.yml").decode("utf-8")
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
        if "main: dev.itemloom.paper.ItemLoomPlugin" not in descriptor:
            violations.append("Wrong entry point")
        if "paperweight-mappings-namespace: mojang" not in manifest:
            violations.append("Missing Mojang mapping namespace")
        if "org/openjdk/nashorn/api/scripting/NashornScriptEngineFactory.class" not in names:
            violations.append("Missing script engine")
        if not any(name.startswith("dev/itemloom/internal/keystone/") for name in classes):
            violations.append("Missing relocated Keystone")
        for notice in ("LICENSE", "NOTICE.md"):
            if notice not in names:
                violations.append(f"Missing source license/provenance notice: {notice}")
    with args.jar.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    report = {
        "jar": str(args.jar.resolve()),
        "bytes": args.jar.stat().st_size,
        "sha256": digest,
        "classes": len(classes),
        "descriptor": descriptor,
        "violations": violations,
        "passed": not violations,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("bytes", "sha256", "classes", "violations", "passed")}))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
