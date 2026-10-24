#!/usr/bin/env python3
"""Validate a signed APK with Android SDK tools and prepare an atomic local stable update. No network publishing."""
import argparse
import fcntl
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

MAX_APK_BYTES = 512 * 1024 * 1024
PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")
HEX = re.compile(r"[0-9a-f]{64}")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tool_path(name, override, sdk):
    if override:
        candidate = pathlib.Path(override)
        if candidate.is_file():
            return candidate
        raise ValueError(f"{name} executable does not exist")
    found = shutil.which(name)
    if found:
        return pathlib.Path(found)
    sdk = pathlib.Path(sdk or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or pathlib.Path.home() / "Library/Android/sdk")
    candidates = [p for p in (sdk / "build-tools").glob("*/" + name) if p.is_file()]
    if not candidates:
        raise ValueError(f"Android SDK {name} is unavailable; supply --{name} or --sdk")
    def version(path):
        numbers = [int(part) for part in re.findall(r"\d+", path.parent.name)]
        return tuple(numbers)
    return max(candidates, key=version)


def run_tool(command):
    result = subprocess.run([str(part) for part in command], capture_output=True, text=True, timeout=120)
    if result.returncode:
        # Tool diagnostics may contain paths or signing details; never echo raw tool output.
        raise ValueError(f"Android SDK {pathlib.Path(command[0]).name} rejected the APK")
    return result.stdout


def inspect_apk(apk, aapt, apksigner):
    badging = run_tool([aapt, "dump", "badging", apk])
    package_line = next((line for line in badging.splitlines() if line.startswith("package:")), None)
    if package_line is None:
        raise ValueError("APK manifest lacks package metadata")
    attributes = dict(re.findall(r"([A-Za-z][A-Za-z0-9_]*)='([^']*)'", package_line))
    if attributes.get("split") or "uses-split:" in badging:
        raise ValueError("Only a standalone base APK is supported; split APKs cannot be published")
    package = attributes.get("name", "")
    if not PACKAGE.fullmatch(package):
        raise ValueError("APK packageName is invalid")
    try:
        version = (int(attributes.get("versionCodeMajor", "0")) << 32) | int(attributes["versionCode"])
        minimum = int(re.search(r"(?m)^sdkVersion:'(\d+)'$", badging).group(1))
    except (ValueError, KeyError, AttributeError):
        raise ValueError("APK must declare numeric versionCode and minSdk") from None
    name = attributes.get("versionName", "")
    if not 1 <= version <= 2147483647 or not 28 <= minimum <= 2147483647 or not name or len(name) > 128:
        raise ValueError("APK version/minSdk is outside the update contract")
    certificates = re.findall(r"(?m)^Signer #\d+ certificate SHA-256 digest:\s*([A-Fa-f0-9]+)\s*$",
                              run_tool([apksigner, "verify", "--verbose", "--print-certs", apk]))
    if len(certificates) != 1 or not HEX.fullmatch(certificates[0].lower()):
        raise ValueError("APK must verify with exactly one current signing certificate")
    return {"packageName": package, "versionCode": version, "versionName": name, "minSdk": minimum,
            "signingCertSha256": certificates[0].lower()}


def ensure_plain_path(path, root):
    if not path.is_relative_to(root):
        raise ValueError("Update path escaped its directory")
    current = path
    while current != root:
        if current.is_symlink():
            raise ValueError("Update directory must not contain symbolic links")
        current = current.parent


def validate_previous(previous, expected_package, expected_certificate):
    if not isinstance(previous, dict) or previous.get("schema") != 1 or previous.get("packageName") != expected_package:
        raise ValueError("Existing update metadata has a different package or schema")
    digest = previous.get("sha256")
    if previous.get("signingCertSha256") != expected_certificate or not isinstance(digest, str) or not HEX.fullmatch(digest):
        raise ValueError("Existing update metadata has a different signer or invalid digest")
    version = previous.get("versionCode")
    if type(version) is not int or not 1 <= version <= 2147483647:
        raise ValueError("Existing update versionCode is invalid")


def publish(args):
    if not PACKAGE.fullmatch(args.package_name) or not HEX.fullmatch(args.signing_cert_sha256.lower()):
        raise ValueError("Provide a valid expected packageName and 64-character certificate SHA-256")
    expected_certificate = args.signing_cert_sha256.lower()
    source = pathlib.Path(args.apk)
    if not source.is_file() or not 1 <= source.stat().st_size <= MAX_APK_BYTES:
        raise ValueError("APK must be a regular nonempty file no larger than 512 MiB")
    aapt = tool_path("aapt", args.aapt, args.sdk)
    apksigner = tool_path("apksigner", args.apksigner, args.sdk)
    if args.notes_file and pathlib.Path(args.notes_file).stat().st_size > 16000:
        raise ValueError("releaseNotes file exceeds its size limit")
    notes = pathlib.Path(args.notes_file).read_text(encoding="utf-8") if args.notes_file else ""
    if len(notes) > 4000:
        raise ValueError("releaseNotes must contain at most 4000 characters")
    root = pathlib.Path(args.directory).expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    stable = root / args.package_name / "stable"
    artifacts = stable / "artifacts"
    ensure_plain_path(artifacts, root)
    artifacts.mkdir(parents=True, exist_ok=True)
    ensure_plain_path(artifacts, root)
    lock = stable / ".publish.lock"
    ensure_plain_path(lock, root)
    descriptor = os.open(lock, os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0), 0o600)
    with os.fdopen(descriptor, "a+b") as lock_file:
        fcntl.lockf(lock_file, fcntl.LOCK_EX)
        with tempfile.TemporaryDirectory(prefix=".publish-", dir=stable) as temporary:
            # Analyze a private snapshot, so a concurrently rebuilt input cannot change after verification.
            snapshot = pathlib.Path(temporary) / "candidate.apk"
            with source.open("rb") as src, snapshot.open("xb") as dest:
                count = 0
                for chunk in iter(lambda: src.read(64 * 1024), b""):
                    count += len(chunk)
                    if count > MAX_APK_BYTES:
                        raise ValueError("APK changed size or exceeds 512 MiB")
                    dest.write(chunk)
                dest.flush(); os.fsync(dest.fileno())
            if count < 1:
                raise ValueError("APK snapshot is empty")
            details = inspect_apk(snapshot, aapt, apksigner)
            if details["packageName"] != args.package_name or details["signingCertSha256"] != expected_certificate:
                raise ValueError("APK packageName or signing certificate differs from the expected identity")
            digest = sha256(snapshot)
            metadata = stable / "manifest.json"
            ensure_plain_path(metadata, root)
            if metadata.exists():
                if metadata.stat().st_size > 24 * 1024:
                    raise ValueError("Existing update metadata exceeds its size limit")
                previous = json.loads(metadata.read_text(encoding="utf-8"))
                validate_previous(previous, args.package_name, expected_certificate)
                if details["versionCode"] < previous["versionCode"]:
                    raise ValueError("Refusing to downgrade the published versionCode")
                if details["versionCode"] == previous["versionCode"] and digest != previous["sha256"]:
                    raise ValueError("Refusing a different APK with the same versionCode")
            destination = artifacts / (digest + ".apk")
            ensure_plain_path(destination, root)
            if destination.exists():
                if not destination.is_file() or destination.stat().st_size != count or sha256(destination) != digest:
                    raise ValueError("Existing immutable artifact differs from its digest")
            else:
                snapshot.chmod(0o444)
                # link() is atomic and refuses to overwrite an existing hash, unlike replace().
                os.link(snapshot, destination)
                artifact_directory = os.open(artifacts, os.O_RDONLY)
                try: os.fsync(artifact_directory)
                finally: os.close(artifact_directory)
            manifest = {"schema": 1, **details, "sizeBytes": count, "sha256": digest,
                        "downloadPath": f"/v1/updates/{args.package_name}/stable/artifacts/{digest}.apk", "releaseNotes": notes}
            prepared = pathlib.Path(temporary) / "manifest.json"
            with prepared.open("x", encoding="utf-8") as dest:
                json.dump(manifest, dest, ensure_ascii=False, separators=(",", ":")); dest.write("\n")
                dest.flush(); os.fsync(dest.fileno())
            prepared.chmod(0o444)
            os.replace(prepared, metadata)
            directory_descriptor = os.open(stable, os.O_RDONLY)
            try: os.fsync(directory_descriptor)
            finally: os.close(directory_descriptor)
            print(json.dumps({"packageName": details["packageName"], "versionCode": details["versionCode"],
                              "sha256": digest, "manifestPath": str(metadata), "deployment": "local files only"}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    parser.add_argument("--directory", required=True, help="Local update directory; this tool never deploys or uploads")
    parser.add_argument("--package-name", required=True, help="Expected package identity; actual metadata comes from the APK")
    parser.add_argument("--signing-cert-sha256", required=True, help="Expected certificate fingerprint, never a keystore password")
    parser.add_argument("--notes-file")
    parser.add_argument("--sdk")
    parser.add_argument("--aapt")
    parser.add_argument("--apksigner")
    args = parser.parse_args()
    try:
        publish(args)
    except (OSError, ValueError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        print(str(error), file=sys.stderr); return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
