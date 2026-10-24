#!/usr/bin/env python3
"""Build a licensed release without exposing passwords in command arguments or logs."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-url', required=True)
    parser.add_argument('--oidc-issuer', required=True)
    parser.add_argument('--public-key', type=Path, required=True, help='base64url SPKI public-key text file')
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--alias', required=True)
    parser.add_argument('--password-file', type=Path, required=True)
    parser.add_argument('--auth-assets', type=Path, help='Existing explicitly selected CarPlay runtime assets')
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--android-sdk', type=Path)
    parser.add_argument('--output', type=Path, required=True, help='Destination APK; never overwritten')
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Output already exists; choose a new output path')
    repo = Path(__file__).resolve().parent.parent
    env = os.environ.copy()
    password = args.password_file.read_text().strip()
    if not password:
        parser.error('Password file is empty')
    if args.java_home:
        env['JAVA_HOME'] = str(args.java_home.resolve())
    if args.android_sdk:
        env['ANDROID_HOME'] = str(args.android_sdk.resolve())
    java_home = env.get('JAVA_HOME')
    keytool = str(Path(java_home) / 'bin/keytool') if java_home else 'keytool'
    env['AUTUNN_BUILD_STORE_PASSWORD'] = password
    certificate = subprocess.run([keytool, '-exportcert', '-keystore', str(args.keystore.resolve()),
        '-alias', args.alias, '-storepass:env', 'AUTUNN_BUILD_STORE_PASSWORD'], env=env,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if certificate.returncode:
        raise RuntimeError('Cannot read signing certificate; check keystore, alias and password file')
    env.update(AUTUNN_LICENSE_SERVER_URL=args.server_url,
        AUTUNN_LICENSE_OIDC_ISSUER=args.oidc_issuer,
        AUTUNN_LICENSE_PUBLIC_KEY_SPKI=args.public_key.read_text().strip(),
        AUTUNN_LICENSE_SIGNING_CERT_SHA256=hashlib.sha256(certificate.stdout).hexdigest(),
        ANDROID_KEYSTORE_PATH=str(args.keystore.resolve()), ANDROID_KEY_ALIAS=args.alias,
        ANDROID_KEYSTORE_PASSWORD=password, ANDROID_KEY_PASSWORD=password)
    env.pop('AUTUNN_BUILD_STORE_PASSWORD')
    if args.auth_assets:
        env['DIPLAY_AUTH_ASSETS_DIR'] = str(args.auth_assets.resolve())
    else:
        env.pop('DIPLAY_AUTH_ASSETS_DIR', None)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    log = args.output.with_suffix('.build.log')
    with log.open('w') as stream:
        # Interface additions must regenerate every listener delegation in the shipped app.
        # A release must not inherit stale Kotlin classes from an incremental local build.
        result = subprocess.run([str(repo / 'gradlew'), ':shared:clean', ':common:clean', ':mobile:clean',
            ':mobile:lintVitalRelease', ':mobile:assembleRelease', '--no-build-cache',
            '--console=plain'], cwd=repo, env=env, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f'Build failed; see {log}')
    shutil.copy2(repo / 'mobile/build/outputs/apk/release/mobile-release.apk', args.output)
    print(f'Built: {args.output.resolve()}')
    if not args.auth_assets:
        print('Source-only APK: no CarPlay runtime authentication assets were included.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
