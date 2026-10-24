#!/usr/bin/env python3
"""Validate the official site, optionally stage reviewed firmware in a static distribution.

The default run has no dependencies and never invents download URLs. Installable
artifacts stay outside the repository; APK updates require authorization in the vehicle app.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / 'site'
ASSETS = ('api.js', 'site.css', 'site.js', 'theme.js', 'logo-light.png', 'logo-dark.png', 'app-icon.png')
LANGUAGES = ('en', 'ar', 'ru', 'uk', 'es', 'zh-Hans')


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def stage_file(source, target, link):
    # Only replace this task's named artifacts, and never modify the original file.
    if target.exists() and os.path.samefile(source, target):
        return
    temporary = target.with_suffix(target.suffix + '.staging')
    if temporary.exists():
        temporary.unlink()
    try:
        if link:
            os.link(source, temporary)
        else:
            shutil.copyfile(source, temporary)
        temporary.replace(target)
    finally:
        if temporary.exists():
            temporary.unlink()


def build(args):
    if args.app_apk:
        raise ValueError("Public APK staging is disabled; distribute the first compatible client through a controlled channel.")
    for name in ('index.html', 'downloads.json'):
        if not (SITE / name).is_file():
            raise ValueError(f'Missing site source: {name}')
    for name in ASSETS:
        if not (SITE / 'assets' / name).is_file():
            raise ValueError(f'Missing site asset: {name}')
    catalog = json.loads((SITE / 'downloads.json').read_text(encoding='utf-8'))
    if catalog.get('schema') != 1:
        raise ValueError('Unsupported download catalog schema.')
    output = Path(args.output).resolve() if args.output else SITE
    if (args.app_apk or args.firmware_zip) and (output == ROOT or ROOT in output.parents):
        raise ValueError('Stage installable artifacts outside the Git repository with --output.')
    if args.output:
        output.mkdir(parents=True, exist_ok=True)
        (output / 'assets').mkdir(exist_ok=True)
        shutil.copyfile(SITE / 'index.html', output / 'index.html')
        for name in ASSETS:
            shutil.copyfile(SITE / 'assets' / name, output / 'assets' / name)
    if args.app_apk or args.firmware_zip:
        (output / 'downloads').mkdir(exist_ok=True)
    if args.firmware_zip:
        archive = Path(args.firmware_zip).resolve(strict=True)
        firmware = catalog.get('firmware')
        if not firmware or archive.stat().st_size != firmware['sizeBytes'] or digest(archive) != firmware['sha256']:
            raise ValueError('Firmware does not match the reviewed catalog size and SHA-256.')
        name = 'HS5-3222-full.zip'
        stage_file(archive, output / 'downloads' / name, args.link_files)
        firmware.update(fileName=name, downloadUrl=f'./downloads/{name}')
    (output / 'downloads.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    # Old upstream language URLs must never offer the unrelated DiPlay/BYD APK.
    for language in LANGUAGES:
        folder = output / language
        folder.mkdir(exist_ok=True)
        (folder / 'index.html').write_text(
            '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1">'
            '<meta http-equiv="refresh" content="0;url=../"><title>Autunn Play</title>'
            '</head><body><a href="../">访问 Autunn Play 官网</a></body></html>\n', encoding='utf-8')
    print(f'Official website ready: {output}')
    print('App download:', 'authorized updates in the vehicle app only')
    print('Firmware download:', 'staged complete ZIP' if catalog.get('firmware', {}).get('downloadUrl') else 'not opened until artifact is staged')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', help='Static website distribution directory; binaries must be outside the repository')
    parser.add_argument('--app-apk', help='Removed: public APK staging is rejected; use controlled distribution')
    parser.add_argument('--app-notes', help='Legacy option; public APK staging is disabled')
    parser.add_argument('--aapt', help='Legacy option; public APK staging is disabled')
    parser.add_argument('--firmware-zip', help='Complete reviewed 3222 outer ZIP, verified against the catalog')
    parser.add_argument('--link-files', action='store_true', help='Use local hard links instead of copying large binaries; do not edit the staged binaries')
    try:
        build(parser.parse_args())
    except (ValueError, OSError) as error:
        parser.exit(1, f'Website build failed: {error}\n')
