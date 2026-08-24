#!/usr/bin/env python3
"""Generate and validate deterministic, original LibrePlayer media fixtures."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import struct
import subprocess
import sys
import time
import zlib

SCHEMA_VERSION = 1
GENERATOR_VERSION = "1.0.0"
FIXED_TIMESTAMP = 1_700_000_000
ROOT = Path(__file__).resolve().parent
PROFILES_PATH = ROOT / "profiles.json"
TEMPLATE_PATH = ROOT / "audio-template.mp3"
MANIFEST_NAME = "fixture-manifest.json"


class FixtureError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def canonical_bytes(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def dataset_fingerprint(manifest: dict) -> str:
    logical = dict(manifest)
    logical.pop("dataset_fingerprint_sha256", None)
    return hashlib.sha256(canonical_bytes(logical)).hexdigest()


def load_profiles() -> dict:
    with PROFILES_PATH.open("r", encoding="utf-8") as source:
        return json.load(source)


def synchsafe(value: int) -> bytes:
    return bytes(((value >> 21) & 0x7F, (value >> 14) & 0x7F, (value >> 7) & 0x7F, value & 0x7F))


def text_frame(frame_id: str, value: str | None) -> bytes:
    if not value:
        return b""
    payload = b"\x01\xff\xfe" + value.encode("utf-16-le")
    return frame_id.encode("ascii") + struct.pack(">I", len(payload)) + b"\x00\x00" + payload


def id3_tag(track: dict) -> bytes:
    frames = b"".join(
        (
            text_frame("TIT2", track["title"]),
            text_frame("TPE1", track["artist"]),
            text_frame("TALB", track["album"]),
            text_frame("TPE2", track["album_artist"]),
            text_frame("TRCK", str(track["track_number"])),
            text_frame("TPOS", str(track["disc_number"])),
            text_frame("TYER", "2024"),
        )
    )
    return b"ID3\x03\x00\x00" + synchsafe(len(frames)) + frames


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)


def png_bytes(width: int, height: int, album_index: int) -> bytes:
    red = (album_index * 53) % 256
    green = (album_index * 97) % 256
    blue = (album_index * 193) % 256
    row = b"\x00" + bytes((red, green, blue)) * width
    raw = row * height
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", header) + png_chunk(b"IDAT", zlib.compress(raw, 9)) + png_chunk(b"IEND", b"")


def decorated_name(kind: str, index: int) -> str:
    variants = {
        1: f"{kind} Café 東京 {index:05d}",
        2: f"{kind} Cafe\u0301 {index:05d}",
        3: f"{kind} Orbit 🚀 {index:05d}",
        4: f"{kind} ليلة هادئة {index:05d}",
        5: f"{kind} Long " + ("descriptive-name-" * 12) + f"{index:05d}",
    }
    return variants.get(index, f"{kind} {index:05d}")


def track_record(profile: str, ordinal: int, albums: int, artists: int) -> dict:
    album_index = ((ordinal - 1) % albums) + 1
    artist_index = ((album_index - 1) % artists) + 1
    position = ((ordinal - 1) // albums) + 1
    multidisc = album_index % 5 == 0
    disc_number = 1 + ((position - 1) % 2) if multidisc else 1
    track_number = ((position - 1) // 2) + 1 if multidisc else position
    title = decorated_name("Track", ordinal) if ordinal <= 5 else f"Track {ordinal:05d}"
    artist = decorated_name("Artist", artist_index) if artist_index <= 5 else f"Artist {artist_index:05d}"
    album = decorated_name("Album", album_index) if album_index <= 5 else f"Album {album_index:05d}"
    if ordinal % 23 in (0, 1):
        title, artist, album = "Duplicate Display Metadata", "Duplicate Artist", "Duplicate Album"
    elif ordinal % 17 == 0:
        title = "Repeated Visible Title"
    if ordinal % 31 == 0:
        artist = None
    if ordinal % 37 == 0:
        album = None
    art_class = "none" if album_index % 5 == 0 else ("oversized" if album_index % 50 == 1 else "ordinary")
    art_path = None if art_class == "none" else f"artwork/album-{album_index:05d}/cover.png"
    relative_path = f"audio/artist-{artist_index:05d}/album-{album_index:05d}/disc-{disc_number:02d}/track-{ordinal:05d}.mp3"
    playlists = []
    if ordinal % 7 == 0:
        playlists.append("benchmark-mixed")
    if ordinal <= 5 or ordinal % 101 == 0:
        playlists.append("benchmark-unicode")
    return {
        "fixture_id": f"{profile.lower()}-track-{ordinal:05d}",
        "relative_path": relative_path,
        "canonical_album_id": f"album-{album_index:05d}",
        "canonical_artist_id": f"artist-{artist_index:05d}",
        "title": title,
        "artist": artist,
        "album": album,
        "album_artist": artist,
        "disc_number": disc_number,
        "track_number": track_number,
        "artwork_class": art_class,
        "artwork_relative_path": art_path,
        "expected_duration_class": "standard-31s",
        "expected_minimum_duration_ms": 30_000,
        "deterministic_timestamp_epoch_seconds": FIXED_TIMESTAMP,
        "favorite": ordinal % 11 == 0,
        "playlists": playlists,
    }


def ensure_empty_output(output: Path) -> None:
    if output.exists() and any(output.iterdir()):
        raise FixtureError(f"Output must not exist or must be empty: {output}")
    output.mkdir(parents=True, exist_ok=True)


def write_file(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    os.utime(path, (FIXED_TIMESTAMP, FIXED_TIMESTAMP))


def generate(profile_name: str, output: Path) -> dict:
    started = time.perf_counter()
    config = load_profiles()
    profile_name = profile_name.upper()
    if profile_name not in config["profiles"]:
        raise FixtureError(f"Unknown profile: {profile_name}")
    profile = config["profiles"][profile_name]
    ensure_empty_output(output)
    tracks: list[dict] = []
    artwork: list[dict] = []
    template = b""
    template_hash = None
    if profile["tracks"]:
        if not TEMPLATE_PATH.is_file():
            raise FixtureError(f"Missing original audio template: {TEMPLATE_PATH}")
        template = TEMPLATE_PATH.read_bytes()
        template_hash = hashlib.sha256(template).hexdigest()
    for album_index in range(1, profile["albums"] + 1):
        art_class = "none" if album_index % 5 == 0 else ("oversized" if album_index % 50 == 1 else "ordinary")
        if art_class == "none":
            continue
        width = height = 2048 if art_class == "oversized" else 128
        relative = f"artwork/album-{album_index:05d}/cover.png"
        path = output / PurePosixPath(relative)
        write_file(path, png_bytes(width, height, album_index))
        artwork.append({
            "canonical_album_id": f"album-{album_index:05d}",
            "classification": art_class,
            "relative_path": relative,
            "width": width,
            "height": height,
            "sha256": sha256_file(path),
        })
    for ordinal in range(1, profile["tracks"] + 1):
        record = track_record(profile_name, ordinal, profile["albums"], profile["artists"])
        path = output / PurePosixPath(record["relative_path"])
        write_file(path, id3_tag(record) + template)
        record["sha256"] = sha256_file(path)
        record["size_bytes"] = path.stat().st_size
        tracks.append(record)
    manifest = {
        "schema_version": SCHEMA_VERSION,
        "generator_version": GENERATOR_VERSION,
        "profile": profile_name,
        "fixed_seed": config["seed"],
        "audio_template_sha256": template_hash,
        "expected": dict(profile),
        "logical_identity": {
            "path_model": "relative-posix",
            "timestamp_epoch_seconds": FIXED_TIMESTAMP,
            "byte_deterministic": True,
        },
        "artwork": artwork,
        "tracks": tracks,
    }
    manifest["dataset_fingerprint_sha256"] = dataset_fingerprint(manifest)
    manifest_path = output / MANIFEST_NAME
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8", newline="\n")
    os.utime(manifest_path, (FIXED_TIMESTAMP, FIXED_TIMESTAMP))
    validation = validate(output, decode_check=True)
    elapsed = time.perf_counter() - started
    files = [path for path in output.rglob("*") if path.is_file()]
    return {
        "profile": profile_name,
        "fingerprint": manifest["dataset_fingerprint_sha256"],
        "seconds": round(elapsed, 3),
        "files": len(files),
        "bytes": sum(path.stat().st_size for path in files),
        "validation": validation,
    }


def safe_relative_path(value: str) -> PurePosixPath:
    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts or not path.parts:
        raise FixtureError(f"Unsafe relative path: {value}")
    return path


def validate_png(path: Path, expected_width: int, expected_height: int) -> None:
    content = path.read_bytes()
    if not content.startswith(b"\x89PNG\r\n\x1a\n") or len(content) < 33:
        raise FixtureError(f"Invalid PNG: {path}")
    width, height = struct.unpack(">II", content[16:24])
    if (width, height) != (expected_width, expected_height):
        raise FixtureError(f"Unexpected PNG dimensions for {path}: {width}x{height}")


def internal_audio_check(path: Path) -> None:
    content = path.read_bytes()
    if not content.startswith(b"ID3"):
        raise FixtureError(f"Missing ID3 metadata: {path}")
    tag_size = ((content[6] & 0x7F) << 21) | ((content[7] & 0x7F) << 14) | ((content[8] & 0x7F) << 7) | (content[9] & 0x7F)
    payload = content[10 + tag_size:]
    if len(payload) < 4 or payload[0] != 0xFF or (payload[1] & 0xE0) != 0xE0:
        raise FixtureError(f"Missing MPEG audio frames: {path}")


def ffprobe_audio(path: Path) -> dict:
    executable = shutil.which("ffprobe")
    if not executable:
        raise FixtureError("ffprobe is required for acceptance decode validation")
    result = subprocess.run(
        [executable, "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name,duration", "-of", "json", str(path)],
        check=True,
        capture_output=True,
        text=True,
    )
    parsed = json.loads(result.stdout)
    if not parsed.get("streams"):
        raise FixtureError(f"No decodable audio stream: {path}")
    stream = parsed["streams"][0]
    duration = float(stream.get("duration", 0))
    if stream.get("codec_name") != "mp3" or duration < 30.0:
        raise FixtureError(f"Unexpected audio stream for {path}: {stream}")
    return {"codec": stream["codec_name"], "duration_seconds": duration}


def validate(output: Path, decode_check: bool) -> dict:
    manifest_path = output / MANIFEST_NAME
    if not manifest_path.is_file():
        raise FixtureError(f"Missing manifest: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema_version") != SCHEMA_VERSION:
        raise FixtureError("Unsupported fixture manifest schema")
    if dataset_fingerprint(manifest) != manifest.get("dataset_fingerprint_sha256"):
        raise FixtureError("Dataset fingerprint mismatch")
    expected = manifest["expected"]
    tracks = manifest["tracks"]
    artwork = manifest["artwork"]
    if len(tracks) != expected["tracks"]:
        raise FixtureError("Track count mismatch")
    if len({item["canonical_album_id"] for item in tracks}) != expected["albums"]:
        raise FixtureError("Canonical album count mismatch")
    if len({item["canonical_artist_id"] for item in tracks}) != expected["artists"]:
        raise FixtureError("Canonical artist count mismatch")
    fixture_ids = [item["fixture_id"] for item in tracks]
    relative_paths = [item["relative_path"] for item in tracks]
    if len(fixture_ids) != len(set(fixture_ids)):
        raise FixtureError("Fixture IDs are not unique")
    if len(relative_paths) != len(set(relative_paths)):
        raise FixtureError("Relative paths are not unique")
    for item in tracks:
        relative = safe_relative_path(item["relative_path"])
        path = output / relative
        if not path.is_file() or sha256_file(path) != item["sha256"]:
            raise FixtureError(f"Missing or hash-mismatched audio: {relative}")
        if item["expected_minimum_duration_ms"] < 30_000:
            raise FixtureError(f"Duration requirement too small: {relative}")
    for item in artwork:
        relative = safe_relative_path(item["relative_path"])
        path = output / relative
        if not path.is_file() or sha256_file(path) != item["sha256"]:
            raise FixtureError(f"Missing or hash-mismatched artwork: {relative}")
        validate_png(path, item["width"], item["height"])
    decode = None
    if tracks:
        representatives = [tracks[0], tracks[len(tracks) // 2], tracks[-1]]
        for item in representatives:
            internal_audio_check(output / safe_relative_path(item["relative_path"]))
        if decode_check:
            decode = ffprobe_audio(output / safe_relative_path(representatives[0]["relative_path"]))
    return {
        "profile": manifest["profile"],
        "tracks": len(tracks),
        "artwork_files": len(artwork),
        "fingerprint": manifest["dataset_fingerprint_sha256"],
        "decode": decode,
    }


def compare(left: Path, right: Path) -> dict:
    left_manifest = json.loads((left / MANIFEST_NAME).read_text(encoding="utf-8"))
    right_manifest = json.loads((right / MANIFEST_NAME).read_text(encoding="utf-8"))
    left_fingerprint = dataset_fingerprint(left_manifest)
    right_fingerprint = dataset_fingerprint(right_manifest)
    if left_fingerprint != right_fingerprint or canonical_bytes(left_manifest) != canonical_bytes(right_manifest):
        raise FixtureError("Fixture manifests or fingerprints differ")
    return {"match": True, "fingerprint": left_fingerprint}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    generate_parser = subparsers.add_parser("generate")
    generate_parser.add_argument("--profile", required=True)
    generate_parser.add_argument("--output", required=True, type=Path)
    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("--input", required=True, type=Path)
    validate_parser.add_argument("--no-decode-check", action="store_true")
    compare_parser = subparsers.add_parser("compare")
    compare_parser.add_argument("--left", required=True, type=Path)
    compare_parser.add_argument("--right", required=True, type=Path)
    args = parser.parse_args()
    try:
        if args.command == "generate":
            result = generate(args.profile, args.output.resolve())
        elif args.command == "validate":
            result = validate(args.input.resolve(), decode_check=not args.no_decode_check)
        else:
            result = compare(args.left.resolve(), args.right.resolve())
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    except (FixtureError, OSError, subprocess.CalledProcessError, ValueError) as error:
        print(f"fixture error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
