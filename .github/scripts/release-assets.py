"""Prepare and verify the universal + ABI-specific GitHub Release assets."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import shutil
import urllib.parse
import zipfile

ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
MAX_APK = 300 * 1024 * 1024


def apk_names(code):
    return {None: f"CleanARecovery-{code}.apk", **{
        abi: f"CleanARecovery-{code}-{abi}.apk" for abi in ABIS}}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inspect_apk(path, expected_abis):
    with zipfile.ZipFile(path) as apk:
        names = set(apk.namelist())
        actual = {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")}
        if actual != set(expected_abis):
            raise ValueError(f"{path.name}: expected ABIs {expected_abis}, got {actual}")
        for abi in expected_abis:
            required = {f"lib/{abi}/{name}" for name in (
                "libpython.zip.so", "libpython.so", "libffmpeg.zip.so", "libffmpeg.so", "libqjs.so")}
            if abi in ("arm64-v8a", "x86_64"):
                required.add(f"lib/{abi}/libmihomo.so")
            if not required <= names:
                raise ValueError(f"{path.name}: missing native runtime files {required - names}")
        # All shared content and same-ABI libraries must survive packaging intact.
        return {entry.filename: (entry.CRC, entry.file_size) for entry in apk.infolist()
                if entry.filename != "AndroidManifest.xml" and not entry.filename.startswith("META-INF/")}


def prepare(apk_dir, output, code):
    source = {None: apk_dir / "app-release.apk", **{
        abi: apk_dir / f"app-{abi}-release.apk" for abi in ABIS}}
    universal = inspect_apk(source[None], ABIS)
    for abi in ABIS:
        content = inspect_apk(source[abi], [abi])
        expected = {name: value for name, value in universal.items()
                    if not name.startswith("lib/") or name.startswith(f"lib/{abi}/")}
        if content != expected:
            raise ValueError(f"{abi}: packaged contents differ from the universal APK")
    output.mkdir(parents=True, exist_ok=True)
    checksums = []
    for abi, name in apk_names(code).items():
        if not 0 < source[abi].stat().st_size <= MAX_APK:
            raise ValueError(f"Invalid APK size: {source[abi]}")
        destination = output / name
        shutil.copyfile(source[abi], destination)
        checksums.append(f"{digest(destination)}  {name}\n")
        print(f"{name}: {destination.stat().st_size / 1024**2:.2f} MiB")
    (output / "SHA256SUMS.txt").write_text("".join(checksums), encoding="utf-8", newline="\n")


def update_manifest(metadata, release_dir, code, repository, version_name):
    result = copy.deepcopy(metadata)
    if result.get("tag_name") != "v" + version_name:
        raise ValueError("Release tag does not match this build")
    expected = set(apk_names(code).values()) | {"SHA256SUMS.txt"}
    assets = result.get("assets", [])
    for name in expected:
        matches = [a for a in assets if a.get("name") == name]
        if len(matches) != 1:
            raise ValueError(f"Expected one uploaded asset: {name}")
        asset, local = matches[0], release_dir / name
        if (asset.get("state") != "uploaded" or asset.get("size") != local.stat().st_size
                or asset.get("digest", "").lower() != "sha256:" + digest(local)):
            raise ValueError(f"Uploaded asset does not match local bytes: {name}")
    result["draft"] = False
    base = f"https://github.com/{repository}/releases"
    tag = urllib.parse.quote(result["tag_name"], safe="")
    result["html_url"] = f"{base}/tag/{tag}"
    # Do not carry a stale update.json asset from a retried draft into its own manifest.
    result["assets"] = [a for a in assets if a.get("name") in expected]
    for asset in result["assets"]:
        asset["browser_download_url"] = f"{base}/download/{tag}/" + urllib.parse.quote(asset["name"], safe="")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    package = commands.add_parser("prepare")
    package.add_argument("--apk-dir", type=Path, required=True)
    package.add_argument("--output", type=Path, required=True)
    package.add_argument("--code", type=int, required=True)
    manifest = commands.add_parser("manifest")
    manifest.add_argument("--metadata", type=Path, required=True)
    manifest.add_argument("--release-dir", type=Path, required=True)
    manifest.add_argument("--code", type=int, required=True)
    manifest.add_argument("--repository", required=True)
    manifest.add_argument("--version-name", required=True)
    manifest.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.apk_dir, args.output, args.code)
    else:
        result = update_manifest(json.loads(args.metadata.read_text(encoding="utf-8")),
                                 args.release_dir, args.code, args.repository, args.version_name)
        args.output.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
