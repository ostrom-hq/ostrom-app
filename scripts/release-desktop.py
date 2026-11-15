#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Stage Ostrom Desktop packages and assemble the payload of their signed manifest.

Ported from TeamCode's `scripts/release-desktop.py`. The packages `ostrom ui` installs (one per target, listed in
`desktop-manifest.json` with `product: desktop`): the `.app` zipped with `ditto` on macOS, the per-user `.msi` on
Windows, the app's folder as a `.tar.gz` on Linux. Their names follow `DesktopReleaseFormat.fileName` of the CLI. The
other packages (`.dmg`, `.deb`, `.rpm`) are for people downloading by hand: attached to the release and listed in its
checksums, not in the manifest. The manifest is signed afterwards with the CLI's release key (`sign-manifest.sh` of
ostrom-cli: Ed25519 over the raw bytes, base64 in `desktop-manifest.json.sig`), see release-desktop.yml.

    release-desktop.py stage --package FILE --target T --version V --commit SHA --output DIR
    release-desktop.py assemble --inputs DIR --version V --commit SHA --output DIR
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tarfile
import zipfile

TARGETS = ('macos-arm64', 'linux-x64', 'linux-arm64', 'windows-x64')
NUMBER = r'(?:0|[1-9][0-9]*)'
PRERELEASE_PART = rf'(?:{NUMBER}|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)'
VERSION = re.compile(rf'{NUMBER}\.{NUMBER}\.{NUMBER}(?:-{PRERELEASE_PART}(?:\.{PRERELEASE_PART})*)?')
MAX_PACKAGE = 512 * 1024 * 1024
MANIFEST, SIGNATURE = 'desktop-manifest.json', 'desktop-manifest.json.sig'
OLE = bytes.fromhex('d0cf11e0a1b11ae1')


def version(value):
    if len(value) > 80 or not VERSION.fullmatch(value):
        raise ValueError('Use a bounded semantic version without a tag prefix.')
    return value


def commit(value):
    if not re.fullmatch(r'[0-9a-f]{40}', value):
        raise ValueError('Use the complete lowercase source commit SHA.')
    return value


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def file_name(revision, target):
    version(revision)
    if target not in TARGETS:
        raise ValueError('Unsupported desktop target.')
    extension = 'zip' if target.startswith('macos-') else 'msi' if target.startswith('windows-') else 'tar.gz'
    return f'ostrom-desktop-{revision}-{target}.{extension}'


def check_layout(path, target):
    """The package's format, and for archives a single top-level app that `ostrom ui` expects."""
    with path.open('rb') as source:
        head = source.read(8)
    if target.startswith('windows-'):
        if head != OLE:
            raise ValueError('The Windows package is not an installer database.')
        return
    if target.startswith('macos-'):
        if not zipfile.is_zipfile(path):
            raise ValueError('The macOS package is not a zip archive.')
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
        top = {name.split('/', 1)[0] for name in names}
        if top != {'Ostrom.app'} or 'Ostrom.app/Contents/MacOS/Ostrom' not in names:
            raise ValueError('The macOS package must hold Ostrom.app only.')
        return
    if head[:2] != b'\x1f\x8b':
        raise ValueError('The Linux package is not a gzip archive.')
    with tarfile.open(path, 'r:gz') as archive:
        members = archive.getmembers()
    top = {member.name.lstrip('./').split('/', 1)[0] for member in members if member.name.lstrip('./')}
    executable = [member for member in members if member.name.lstrip('./') == 'Ostrom/bin/Ostrom' and member.isfile()]
    if top != {'Ostrom'} or not executable or any(member.issym() and member.linkname.startswith('/') for member in members):
        raise ValueError('The Linux package must hold the Ostrom folder only, with bin/Ostrom.')


def stage(package, target, revision, source_commit, output):
    version(revision)
    commit(source_commit)
    package = package.resolve(strict=True)
    size = package.stat().st_size
    if package.is_symlink() or not 1 <= size <= MAX_PACKAGE:
        raise ValueError('The desktop package is missing or exceeds its size bound.')
    check_layout(package, target)
    output.mkdir(parents=True, exist_ok=True)
    name = file_name(revision, target)
    destination, record_path = output / name, output / f'desktop-{target}.json'
    if destination.exists() or record_path.exists():
        raise ValueError('Refusing to replace an existing staged package.')
    shutil.copyfile(package, destination)
    record = {'target': target, 'version': revision, 'sourceCommit': source_commit,
              'fileName': name, 'sha256': digest(destination), 'size': size}
    record_path.write_text(json.dumps(record, sort_keys=True) + '\n')
    return record


def assemble(inputs, revision, source_commit, output):
    """`desktop-manifest.json` for all four targets, from staged records whose files still match."""
    version(revision)
    commit(source_commit)
    assets = {}
    for target in TARGETS:
        path = inputs / f'desktop-{target}.json'
        if path.stat().st_size > 4096:
            raise ValueError('Package metadata exceeds the size limit.')
        metadata = json.loads(path.read_text())
        name = file_name(revision, target)
        package = inputs / name
        if (metadata.get('target') != target or metadata.get('version') != revision or
                metadata.get('sourceCommit') != source_commit or metadata.get('fileName') != name):
            raise ValueError('Mixed target, version, source commit or package name.')
        size = package.stat().st_size
        if (package.is_symlink() or not 1 <= size <= MAX_PACKAGE or type(metadata.get('size')) is not int or
                metadata['size'] != size or metadata.get('sha256') != digest(package)):
            raise ValueError('Desktop package integrity check failed.')
        check_layout(package, target)
        assets[target] = {key: metadata[key] for key in ('fileName', 'sha256', 'size')}
    expected = {f'desktop-{target}.json' for target in TARGETS} | {file_name(revision, target) for target in TARGETS}
    if {path.name for path in inputs.iterdir()} != expected:
        raise ValueError('Unexpected or missing files among the desktop packages.')
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError('Assemble into an empty output directory.')
    manifest = {'schemaVersion': 1, 'product': 'desktop', 'version': revision, 'tag': revision,
                'sourceCommit': source_commit, 'assets': assets}
    (output / MANIFEST).write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    staged = sub.add_parser('stage')
    staged.add_argument('--package', type=Path, required=True)
    staged.add_argument('--target', choices=TARGETS, required=True)
    combined = sub.add_parser('assemble')
    combined.add_argument('--inputs', type=Path, required=True)
    for command_parser in (staged, combined):
        command_parser.add_argument('--version', required=True)
        command_parser.add_argument('--commit', required=True)
        command_parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'stage':
        stage(args.package, args.target, args.version, args.commit, args.output)
    else:
        assemble(args.inputs, args.version, args.commit, args.output)
    print('Desktop release: ' + args.command)


if __name__ == '__main__':
    main()
