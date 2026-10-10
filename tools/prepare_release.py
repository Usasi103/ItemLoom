"""Stage only the plugin and its checksum; refuse private artwork/deployment inputs."""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import sys
from zipfile import ZipFile

PROJECT = Path(__file__).resolve().parents[1]
MEDIA = {'.png', '.jpg', '.jpeg', '.webp', '.ogg', '.wav', '.bbmodel'}
PRIVATE_PATHS = ('integrations/loot-bags/resourcepack/', 'integrations/loot-bags/ItemLoom/',
                 'private-assets/', 'tools/prepare_loot_bags.py')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    jar, output = args.jar.resolve(strict=True), args.output.resolve()
    if output.exists() or output.is_relative_to(PROJECT) or any(p.name.lower() == 'test_server' for p in (output, *output.parents)):
        raise ValueError('Use a new staging directory outside the project and deployment server')
    files = subprocess.check_output(['git', '-C', str(PROJECT), 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], text=True).split('\x00')
    forbidden = [name for name in files if name and (PROJECT / name).is_file()
                 and (name.startswith(PRIVATE_PATHS) or Path(name).suffix.lower() in MEDIA)]
    if forbidden:
        raise ValueError('Private/artwork source inputs cannot be distributed: ' + ', '.join(forbidden))
    with ZipFile(jar) as archive:
        descriptor = archive.read('plugin.yml').decode('utf-8')
    match = re.search(r'''(?m)^version:\s*['"]?(\d+\.\d+\.\d+)['"]?\s*$''', descriptor)
    if not match:
        raise ValueError('Expected a formal numeric plugin version')
    version = match.group(1)
    current = re.search(r'(?m)^version=(.+)$', (PROJECT / 'gradle.properties').read_text(encoding='utf-8'))
    if not current or current.group(1).strip() != version:
        raise ValueError('JAR version does not match the current source version')
    subprocess.run([sys.executable, '-B', str(PROJECT / 'tools/verify_source_review.py')], check=True)
    report = output.with_name(output.name + '-artifact-check.json')
    subprocess.run([sys.executable, '-B', str(PROJECT / 'tools/verify_artifact.py'), str(jar), '--out', str(report)], check=True)
    output.mkdir(parents=True)
    target = output / f'ItemLoom-{version}.jar'
    shutil.copyfile(jar, target)
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
        raise ValueError('Staged artifact differs from the verified JAR')
    (output / 'SHA256SUMS.txt').write_text(f'{digest}  {target.name}\n', encoding='utf-8')
    assert {p.name for p in output.iterdir()} == {target.name, 'SHA256SUMS.txt'}
    print(f'Prepared {target.name} and SHA256SUMS.txt; verification remains outside the assets directory')


if __name__ == '__main__':
    main()
