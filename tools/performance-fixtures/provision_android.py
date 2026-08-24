#!/usr/bin/env python3
"""Safely provision a generated fixture into emulator public Music storage."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from pathlib import PurePosixPath
import re
import subprocess
import sys

REMOTE_ROOT = "/sdcard/Music/LibrePlayerBenchmark"
MEDIASTORE_ROOT = "/storage/emulated/0/Music/LibrePlayerBenchmark"
SERIAL_PATTERN = re.compile(r"^emulator-\d+$")
PROFILE_PATTERN = re.compile(r"^[A-Z][A-Z0-9_-]*$")


class ProvisionError(RuntimeError):
    pass


def adb(adb_path: str, serial: str | None, *args: str, capture: bool = True) -> subprocess.CompletedProcess[str]:
    command = [adb_path]
    if serial:
        command += ["-s", serial]
    command += list(args)
    return subprocess.run(command, check=True, capture_output=capture, text=True, encoding="utf-8", errors="replace")


def enumerate_devices(adb_path: str) -> list[str]:
    result = adb(adb_path, None, "devices", "-l")
    print(result.stdout, end="")
    return [line.split()[0] for line in result.stdout.splitlines()[1:] if line.strip() and " device " in f" {line} "]


def require_safe_serial(adb_path: str, serial: str) -> None:
    devices = enumerate_devices(adb_path)
    if not SERIAL_PATTERN.fullmatch(serial):
        raise ProvisionError(f"Physical or malformed serial refused: {serial}")
    if serial not in devices:
        raise ProvisionError(f"Selected emulator is not connected: {serial}")


def remote_path(profile: str) -> str:
    profile = profile.upper()
    if not PROFILE_PATTERN.fullmatch(profile):
        raise ProvisionError(f"Unsafe profile name: {profile}")
    path = f"{REMOTE_ROOT}/{profile}"
    if not path.startswith(REMOTE_ROOT + "/") or path.count("/") != REMOTE_ROOT.count("/") + 1:
        raise ProvisionError(f"Unsafe remote fixture path: {path}")
    return path


def load_manifest(dataset: Path) -> dict:
    manifest = json.loads((dataset / "fixture-manifest.json").read_text(encoding="utf-8"))
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--dataset", required=True, type=Path)
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--cleanup", action="store_true")
    action.add_argument("--scan", action="store_true")
    action.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    try:
        dataset = args.dataset.resolve()
        manifest = load_manifest(dataset)
        profile = manifest["profile"]
        destination = remote_path(profile)
        require_safe_serial(args.adb, args.serial)
        if args.cleanup:
            media_store_path = f"{MEDIASTORE_ROOT}/{profile}"
            where = f"_data LIKE '{media_store_path}/%'"
            deleted = adb(
                args.adb,
                args.serial,
                "shell",
                "content",
                "delete",
                "--uri",
                "content://media/external/audio/media",
                "--where",
                f'"{where}"',
            ).stdout.strip()
            adb(args.adb, args.serial, "shell", "rm", "-rf", destination)
            print(json.dumps({"profile": profile, "cleaned": destination, "mediastore_cleanup": deleted}, sort_keys=True))
            return 0
        if args.scan:
            tracks = manifest["tracks"]
            for item in tracks:
                relative = PurePosixPath(item["relative_path"])
                if relative.is_absolute() or ".." in relative.parts or relative.suffix.lower() != ".mp3":
                    raise ProvisionError(f"Unsafe manifest media path: {relative}")
                target = f"{destination}/{relative.as_posix()}"
                adb(
                    args.adb,
                    args.serial,
                    "shell",
                    "am",
                    "broadcast",
                    "-a",
                    "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                    "-d",
                    f"file://{target}",
                )
            print(json.dumps({"profile": profile, "serial": args.serial, "scanned_tracks": len(tracks)}, sort_keys=True))
            return 0
        if args.verify:
            media_store_path = f"{MEDIASTORE_ROOT}/{profile}"
            where = f"_data LIKE '{media_store_path}/%' AND is_music != 0 AND duration >= 30000"
            result = adb(
                args.adb,
                args.serial,
                "shell",
                "content",
                "query",
                "--uri",
                "content://media/external/audio/media",
                "--projection",
                "_id:duration:mime_type:_data:title:artist:album",
                "--where",
                f'"{where}"',
            )
            rows = [line for line in result.stdout.splitlines() if line.startswith("Row:")]
            expected = manifest["expected"]["tracks"]
            if len(rows) != expected:
                raise ProvisionError(f"MediaStore count mismatch for {profile}: expected {expected}, found {len(rows)}")
            print(json.dumps({
                "profile": profile,
                "serial": args.serial,
                "remote_path": destination,
                "expected_tracks": expected,
                "mediastore_tracks": len(rows),
                "representative_row": rows[0] if rows else None,
                "dataset_fingerprint_sha256": manifest["dataset_fingerprint_sha256"],
            }, sort_keys=True))
            return 0
        adb(args.adb, args.serial, "shell", "mkdir", "-p", destination)
        adb(args.adb, args.serial, "push", str(dataset) + "/.", destination, capture=False)
        print(json.dumps({
            "profile": profile,
            "serial": args.serial,
            "remote_path": destination,
            "expected_tracks": manifest["expected"]["tracks"],
            "dataset_fingerprint_sha256": manifest["dataset_fingerprint_sha256"],
        }, sort_keys=True))
        return 0
    except (ProvisionError, OSError, subprocess.CalledProcessError, ValueError, KeyError) as error:
        print(f"provision error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
