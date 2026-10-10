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
    project = Path(__file__).resolve().parents[1]
    dependency_manifest = json.loads((project / "provenance/runtime-dependencies.json").read_text(encoding="utf-8"))
    with ZipFile(args.jar) as jar:
        names = jar.namelist()
        classes = [name for name in names if name.endswith(".class")]
        if 'compat-ni/action-library.js' in names:
            violations.append('Obsolete upstream action helper implementation is still packaged')
        # Runtime capabilities are public; server-owned artwork and deployment inputs are not.
        for name in names:
            if name.startswith(('dev/itemloom/internal/itembridge/hook/NeigeItemsProvider',
                                'dev/itemloom/internal/itembridge/hook/SXItemProvider')):
                violations.append(f'{name}: excluded ItemBridge provider')
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
            if name.startswith("dev/itemloom/paper/sx/") and any(
                    marker in data for marker in (b"fromLegacy", b"org/bukkit/material/MaterialData", b"CraftLegacy")):
                violations.append(f"{name}: runtime legacy initialization path returned")
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
        if 'dev/itemloom/internal/itembridge/core/BukkitItemBridge.class' not in names:
            violations.append('Missing relocated ItemBridge')
        if any(name.startswith('cn/gtemc/itembridge/') for name in names):
            violations.append('Unrelocated ItemBridge')
        for notice in ("LICENSE", "NOTICE.md", "META-INF/licenses/itembridge-MIT.txt",
                       "META-INF/licenses/itembridge-modifications.md",
                       "META-INF/licenses/runtime-dependencies.json",
                       "compat-sx/legacy-materials-26.2.tsv"):
            if notice not in names:
                violations.append(f"Missing source license/provenance notice: {notice}")
        for name, row in dependency_manifest["packaged_notices"].items():
            if name not in names:
                violations.append(f"Missing complete dependency notice: {name}")
            elif hashlib.sha256(jar.read(name).decode("utf-8").replace("\r\n", "\n").encode("utf-8")).hexdigest() != row["sha256_lf"]:
                violations.append(f"Dependency notice content differs: {name}")
        embedded_manifest = "META-INF/licenses/runtime-dependencies.json"
        if embedded_manifest in names and json.loads(jar.read(embedded_manifest)) != dependency_manifest:
            violations.append("Packaged dependency inventory differs from reviewed inputs")
        material_table = "compat-sx/legacy-materials-26.2.tsv"
        if material_table in names:
            expected_table = (project / "paper26/src/main/resources" / material_table).read_text(encoding="utf-8")
            if jar.read(material_table).decode("utf-8").replace("\r\n", "\n") != expected_table:
                violations.append("Packaged legacy material observations differ from reviewed resource")
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
