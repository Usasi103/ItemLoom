"""Rebuild the pinned Apache-2.0 fork and compare its classes with an ItemLoom JAR.

Inputs are downloaded separately from the URLs in runtime-dependencies.json.
This verifies source/binary correspondence, not copyright ownership or legal risk.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from zipfile import ZipFile


PROJECT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pinned(path, expected):
    if sha(path) != expected:
        raise ValueError(f"Unreviewed input: {path.name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--fork-jar', type=Path, required=True)
    parser.add_argument('--plugin', type=Path, required=True)
    parser.add_argument('--asm', type=Path, required=True)
    parser.add_argument('--asm-commons', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    args = parser.parse_args()
    work = args.work.resolve()
    if work.is_relative_to(PROJECT) or any(p.name.lower() == 'test_server' for p in (work, *work.parents)):
        raise ValueError('Use a work directory outside the repository and deployment server')
    work.mkdir(parents=True, exist_ok=True)
    # A failed rerun must not leave an earlier successful result looking current.
    (work / 'verification.json').write_text('{"passed": false, "status": "incomplete"}\n', encoding='utf-8')
    manifest = json.loads((PROJECT / 'provenance/runtime-dependencies.json').read_text(encoding='utf-8'))
    record = manifest['embedded_snakeyaml']
    pinned(args.source, record['source_archive_sha256'])
    pinned(args.fork_jar, record['sha256_before_upstream_shading'])
    runtime = {row['coordinate']: row['sha256'] for row in manifest['runtime_artifacts']}
    pinned(args.asm, runtime['org.ow2.asm:asm:7.3.1'])
    pinned(args.asm_commons, runtime['org.ow2.asm:asm-commons:7.3.1'])
    suffix = '.exe' if os.name == 'nt' else ''
    javac = args.java_home / 'bin' / ('javac' + suffix)
    java = args.java_home / 'bin' / ('java' + suffix)
    compiler = subprocess.check_output([str(javac), '-version'], text=True).strip()
    if not compiler.startswith('javac 25'):
        raise ValueError('Use JDK 25, matching the original fork compiler major version')
    with tempfile.TemporaryDirectory(prefix='fork-check-', dir=work) as temp:
        temporary = Path(temp)
        source = temporary / 'source'
        classes = temporary / 'classes'
        classes.mkdir()
        with ZipFile(args.source) as archive:
            prefix = f"snakeyaml-engine-{record['source_commit']}/src/main/java/"
            sources = []
            for name in archive.namelist():
                if not name.startswith(prefix) or not name.endswith('.java'):
                    continue
                target = (source / name[len(prefix):]).resolve()
                if not target.is_relative_to(source.resolve()):
                    raise ValueError('Source archive contains an invalid path')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(name))
                sources.append(target)
            if len(sources) != record['main_java_files']:
                raise ValueError('Source inventory differs')
            root = prefix.partition('/')[0] + '/'
            with ZipFile(args.fork_jar) as binary:
                embedded = binary.read('META-INF/maven/org.snakeyaml/snakeyaml-engine/pom.xml')
                if embedded.replace(b'\r\n', b'\n') != archive.read(root + 'pom.xml').replace(b'\r\n', b'\n'):
                    raise ValueError('Fork POM differs from source')
        options = ['-source', '11', '-target', '11', '-g', '-encoding', 'UTF-8',
                   '-d', classes.as_posix(), *[p.as_posix() for p in sorted(sources)]]
        argfile = temporary / 'javac.args'
        if any('"' in value or '\n' in value or '\r' in value for value in options):
            raise ValueError('Unsupported character in compiler path')
        argfile.write_text('\n'.join('"' + value + '"' for value in options), encoding='utf-8')
        result = subprocess.run([str(javac), '@' + str(argfile)], capture_output=True)
        (work / 'compiler.log').write_bytes(result.stdout + result.stderr)
        result.check_returncode()
        classpath = os.pathsep.join(str(p.resolve()) for p in (args.asm, args.asm_commons))
        output = subprocess.check_output([
            str(java), '--class-path', classpath, str(PROJECT / 'tools/VerifyForkClasses.java'),
            str(args.fork_jar.resolve()), str(classes), str(args.plugin.resolve()),
            str(record['runtime_classes'])], text=True)
        report = json.loads(output)
    report.update(passed=True, compiler=compiler, source_commit=record['source_commit'],
                  source_archive_sha256=sha(args.source), fork_sha256=sha(args.fork_jar),
                  plugin_sha256=sha(args.plugin), main_java_files=len(sources),
                  normalization='ASM 7.3.1; remove source/line/local-variable debug metadata; undo package relocation',
                  excluded='module-info.class: Maven module metadata; excluded by ItemLoom packaging')
    (work / 'verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
