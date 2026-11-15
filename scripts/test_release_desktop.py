# SPDX-License-Identifier: Apache-2.0
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('release_desktop', Path(__file__).with_name('release-desktop.py'))
desktop = importlib.util.module_from_spec(spec)
spec.loader.exec_module(desktop)


def package(path, target):
    """A minimal package of [target]'s layout: what `ostrom ui` opens."""
    if target.startswith('macos-'):
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('Ostrom.app/Contents/MacOS/Ostrom', b'#!/bin/sh\n')
            archive.writestr('Ostrom.app/Contents/app/resources/ostrom-desktop-version', b'1.0.0\n')
    elif target.startswith('windows-'):
        path.write_bytes(desktop.OLE + b'\0' * 504)
    else:
        with tarfile.open(path, 'w:gz') as archive:
            for name, data in (('Ostrom/bin/Ostrom', b'#!/bin/sh\n'), ('Ostrom/lib/app/resources/ostrom-desktop-version', b'1.0.0\n')):
                info = tarfile.TarInfo(name)
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))
    return path


class ReleaseDesktopTest(unittest.TestCase):
    revision = '1.0.0'
    source_commit = 'b' * 40

    def staged(self, root):
        inputs = root / 'staged'
        for target in desktop.TARGETS:
            built = package(root / f'built-{target}', target)
            desktop.stage(built, target, self.revision, self.source_commit, inputs)
        return inputs

    def test_names_match_the_cli_desktop_release_format(self):
        self.assertEqual('ostrom-desktop-1.0.0-macos-arm64.zip', desktop.file_name('1.0.0', 'macos-arm64'))
        self.assertEqual('ostrom-desktop-1.0.0-windows-x64.msi', desktop.file_name('1.0.0', 'windows-x64'))
        self.assertEqual('ostrom-desktop-1.0.0-linux-arm64.tar.gz', desktop.file_name('1.0.0', 'linux-arm64'))
        with self.assertRaises(ValueError):
            desktop.file_name('v1.0.0', 'linux-x64')

    def test_assembled_manifest_names_the_desktop_product_and_every_target(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = desktop.assemble(self.staged(root), self.revision, self.source_commit, root / 'dist')
            written = json.loads((root / 'dist' / desktop.MANIFEST).read_text())
            self.assertEqual(manifest, written)
            self.assertEqual({'schemaVersion', 'product', 'version', 'tag', 'sourceCommit', 'assets'}, set(written))
            self.assertEqual('desktop', written['product'])
            self.assertEqual(set(desktop.TARGETS), set(written['assets']))
            for target, asset in written['assets'].items():
                self.assertEqual({'fileName', 'sha256', 'size'}, set(asset))
                self.assertEqual(desktop.digest(root / 'staged' / asset['fileName']), asset['sha256'])
            self.assertLessEqual(len((root / 'dist' / desktop.MANIFEST).read_bytes()), 16 * 1024)

    def test_a_package_changed_after_staging_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inputs = self.staged(root)
            path = inputs / desktop.file_name(self.revision, 'windows-x64')
            data = bytearray(path.read_bytes())
            data[-1] ^= 1
            path.write_bytes(bytes(data))
            with self.assertRaisesRegex(ValueError, 'integrity'):
                desktop.assemble(inputs, self.revision, self.source_commit, root / 'dist')

    def test_a_missing_target_or_a_stray_file_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inputs = self.staged(root)
            (inputs / 'stray.txt').write_text('x')
            with self.assertRaisesRegex(ValueError, 'Unexpected'):
                desktop.assemble(inputs, self.revision, self.source_commit, root / 'dist')
            (inputs / 'stray.txt').unlink()
            (inputs / 'desktop-linux-arm64.json').unlink()
            with self.assertRaises(FileNotFoundError):
                desktop.assemble(inputs, self.revision, self.source_commit, root / 'dist2')

    def test_wrong_layouts_are_refused_before_staging(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            loose = root / 'loose.zip'
            with zipfile.ZipFile(loose, 'w') as archive:
                archive.writestr('Ostrom.app/Contents/MacOS/Ostrom', b'x')
                archive.writestr('other.txt', b'x')
            with self.assertRaisesRegex(ValueError, 'Ostrom.app only'):
                desktop.stage(loose, 'macos-arm64', self.revision, self.source_commit, root / 'out')
            not_msi = root / 'x.msi'
            not_msi.write_bytes(b'MZ' + b'\0' * 100)
            with self.assertRaisesRegex(ValueError, 'installer database'):
                desktop.stage(not_msi, 'windows-x64', self.revision, self.source_commit, root / 'out')
            zipped = package(root / 'app.zip', 'macos-arm64')
            with self.assertRaisesRegex(ValueError, 'gzip'):
                desktop.stage(zipped, 'linux-x64', self.revision, self.source_commit, root / 'out')
            self.assertFalse((root / 'out').exists())


if __name__ == '__main__':
    unittest.main()
