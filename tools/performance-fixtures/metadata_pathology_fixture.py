#!/usr/bin/env python3
"""Generate the compact deterministic Q3.4 metadata-pathology MP3 corpus."""

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

FIXED_TIMESTAMP = 1_700_000_000
PROFILE = "Q34_METADATA_PATHOLOGY"
ROOT = Path(__file__).resolve().parent
TEMPLATE_PATH = ROOT / "audio-template.mp3"
MANIFEST_NAME = "fixture-manifest.json"


class FixtureError(RuntimeError):
    pass


def synchsafe(value: int) -> bytes:
    return bytes(((value >> 21) & 0x7F, (value >> 14) & 0x7F, (value >> 7) & 0x7F, value & 0x7F))


def text_frame(frame_id: str, value: str) -> bytes:
    payload = b"\x01\xff\xfe" + value.encode("utf-16-le")
    return frame_id.encode("ascii") + struct.pack(">I", len(payload)) + b"\x00\x00" + payload


def latin1_text_frame(frame_id: str, value: str) -> bytes:
    payload = b"\x00" + value.encode("latin-1")
    return frame_id.encode("ascii") + struct.pack(">I", len(payload)) + b"\x00\x00" + payload


def id3_tag(frames: list[bytes]) -> bytes:
    payload = b"".join(frames)
    return b"ID3\x03\x00\x00" + synchsafe(len(payload)) + payload


def normal_frames(
    title: str | None,
    artist: str | None,
    album: str | None,
    track: str | None = "1/1",
    disc: str | None = "1/1",
    year: str | None = "2026",
) -> list[bytes]:
    values = (
        ("TIT2", title),
        ("TPE1", artist),
        ("TALB", album),
        ("TRCK", track),
        ("TPOS", disc),
        ("TYER", year),
    )
    return [text_frame(frame_id, value) for frame_id, value in values if value is not None]


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def template_audio() -> bytes:
    if not TEMPLATE_PATH.is_file():
        raise FixtureError(f"Missing original audio template: {TEMPLATE_PATH}")
    content = TEMPLATE_PATH.read_bytes()
    if content.startswith(b"ID3"):
        size = ((content[6] & 0x7F) << 21) | ((content[7] & 0x7F) << 14) | ((content[8] & 0x7F) << 7) | (content[9] & 0x7F)
        content = content[10 + size :]
    if len(content) < 4 or content[0] != 0xFF or (content[1] & 0xE0) != 0xE0:
        raise FixtureError("Audio template does not begin with an MPEG frame")
    return content


def definitions() -> list[dict]:
    unicode_precomposed = "Q34 Café Ω Ж 東京 مرحبا 😀"
    unicode_decomposed = "Q34 Cafe\u0301 Ω Ж 東京 مرحبا 😀"
    punctuation = "Q34 | : / \\ % ? # & \" ' ( ) [ ]"
    long_title = "Q34 Long " + "T" * (1_024 - len("Q34 Long "))
    long_artist = "Q34 Long Artist " + "A" * (4_096 - len("Q34 Long Artist "))
    long_album = "Q34 Long Album " + "B" * (4_096 - len("Q34 Long Album "))
    duplicate_frames = normal_frames(None, None, None, track=None, disc=None, year=None) + [
        text_frame("TIT2", "Q34 Conflict First"),
        text_frame("TIT2", "Q34 Conflict Second"),
        text_frame("TPE1", "Conflict Artist First"),
        text_frame("TPE1", "Conflict Artist Second"),
        text_frame("TALB", "Conflict Album First"),
        text_frame("TALB", "Conflict Album Second"),
        text_frame("TRCK", "2/12"),
        text_frame("TRCK", "10/12"),
        text_frame("TYER", "2024"),
        text_frame("TYER", "1999"),
    ]
    malformed_payload = b"TIT2" + struct.pack(">I", 0x7FFF_FFFF) + b"\x00\x00" + b"\x01\xff\xfeQ\x003\x00"
    return [
        {
            "file": "01-control.mp3",
            "purpose": "normal tagged control",
            "tag": id3_tag(normal_frames("Q34 Control", "Control Artist", "Control Album")),
        },
        {
            "file": "02-absent.mp3",
            "purpose": "absent embedded text metadata",
            "tag": b"",
        },
        {
            "file": "03-whitespace.mp3",
            "purpose": "empty and whitespace-only text values",
            "tag": id3_tag(normal_frames("", " \t", "\r\n")),
        },
        {
            "file": "04-literal-unknown.mp3",
            "purpose": "literal fallback-like artist and album values",
            "tag": id3_tag(normal_frames("Q34 Literal Unknown", "Unknown Artist", "Unknown Album")),
        },
        {
            "file": "05-unicode-precomposed.mp3",
            "purpose": "precomposed Unicode across representative scripts and emoji",
            "tag": id3_tag(normal_frames(unicode_precomposed, unicode_precomposed, punctuation)),
        },
        {
            "file": "06-unicode-decomposed.mp3",
            "purpose": "decomposed combining sequence distinct from precomposed text",
            "tag": id3_tag(normal_frames(unicode_decomposed, unicode_decomposed, punctuation)),
        },
        {
            "file": "07-controls.mp3",
            "purpose": "newline carriage-return and tab presentation behavior",
            "tag": id3_tag(
                [
                    latin1_text_frame("TIT2", "Q34 Line One\nLine Two"),
                    latin1_text_frame("TPE1", "Q34 Tab\tArtist"),
                    latin1_text_frame("TALB", "Q34 Carriage\rAlbum"),
                    latin1_text_frame("TRCK", "1/1"),
                    latin1_text_frame("TPOS", "1/1"),
                    latin1_text_frame("TYER", "2026"),
                ]
            ),
        },
        {
            "file": "08-long-needle.mp3",
            "purpose": "bounded 1 KiB title and 4 KiB artist and album",
            "tag": id3_tag(normal_frames(long_title, long_artist, long_album)),
            "declared_lengths": {"title": len(long_title), "artist": len(long_artist), "album": len(long_album)},
        },
        {
            "file": "09-numeric.mp3",
            "purpose": "overflow-like track plus negative disc and out-of-range year",
            "tag": id3_tag(
                normal_frames(
                    "Q34 Numeric Pathology",
                    "Numeric Artist",
                    "Numeric Album",
                    track="999999999999999999999999/12",
                    disc="-1/2",
                    year="10000",
                )
            ),
        },
        {
            "file": "10-conflicting.mp3",
            "purpose": "duplicate conflicting ID3 text frames for platform observation",
            "tag": id3_tag(duplicate_frames),
        },
        {
            "file": "11-malformed-playable.mp3",
            "purpose": "malformed internal ID3 frame with intact playable MPEG audio",
            "tag": b"ID3\x03\x00\x00" + synchsafe(len(malformed_payload)) + malformed_payload,
        },
    ]


def fingerprint(manifest: dict) -> str:
    logical = dict(manifest)
    logical.pop("dataset_fingerprint_sha256", None)
    encoded = json.dumps(logical, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256_bytes(encoded)


def ensure_empty(output: Path) -> None:
    if output.exists() and any(output.iterdir()):
        raise FixtureError(f"Output must not exist or must be empty: {output}")
    output.mkdir(parents=True, exist_ok=True)


def generate(output: Path) -> dict:
    ensure_empty(output)
    audio = template_audio()
    tracks = []
    for definition in definitions():
        relative = PurePosixPath(definition["file"])
        content = definition["tag"] + audio
        destination = output / relative
        destination.write_bytes(content)
        os.utime(destination, (FIXED_TIMESTAMP, FIXED_TIMESTAMP))
        record = {
            "relative_path": relative.as_posix(),
            "format": "MP3",
            "tag_version": "ID3v2.3" if definition["tag"] else "none",
            "size_bytes": len(content),
            "sha256": sha256_bytes(content),
            "purpose": definition["purpose"],
            "expected_minimum_duration_ms": 30_000,
        }
        if "declared_lengths" in definition:
            record["declared_lengths"] = definition["declared_lengths"]
        tracks.append(record)
    manifest = {
        "schema_version": 1,
        "generator_version": "1.0.0",
        "profile": PROFILE,
        "fixed_timestamp_epoch_seconds": FIXED_TIMESTAMP,
        "audio_template_sha256": sha256_bytes(audio),
        "expected": {"tracks": len(tracks)},
        "tracks": tracks,
    }
    manifest["dataset_fingerprint_sha256"] = fingerprint(manifest)
    manifest_path = output / MANIFEST_NAME
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    os.utime(manifest_path, (FIXED_TIMESTAMP, FIXED_TIMESTAMP))
    return validate(output, require_ffprobe=False)


def validate(output: Path, require_ffprobe: bool) -> dict:
    manifest_path = output / MANIFEST_NAME
    if not manifest_path.is_file():
        raise FixtureError(f"Missing manifest: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("profile") != PROFILE or fingerprint(manifest) != manifest.get("dataset_fingerprint_sha256"):
        raise FixtureError("Manifest profile or fingerprint mismatch")
    audio = template_audio()
    ffprobe = shutil.which("ffprobe")
    if require_ffprobe and not ffprobe:
        raise FixtureError("ffprobe is required for decode validation")
    for track in manifest["tracks"]:
        relative = PurePosixPath(track["relative_path"])
        if relative.is_absolute() or ".." in relative.parts or relative.suffix.lower() != ".mp3":
            raise FixtureError(f"Unsafe fixture path: {relative}")
        path = output / relative
        content = path.read_bytes()
        if sha256_bytes(content) != track["sha256"] or len(content) != track["size_bytes"]:
            raise FixtureError(f"Hash or size mismatch: {relative}")
        if not content.endswith(audio):
            raise FixtureError(f"Fixture does not preserve the audio payload: {relative}")
        if ffprobe:
            result = subprocess.run(
                [ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name,duration", "-of", "json", str(path)],
                check=True,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
            streams = json.loads(result.stdout).get("streams", [])
            if not streams or streams[0].get("codec_name") != "mp3" or float(streams[0].get("duration", 0)) < 30.0:
                raise FixtureError(f"Unexpected decoded audio stream: {relative}: {streams}")
    return {
        "profile": PROFILE,
        "tracks": len(manifest["tracks"]),
        "bytes": sum(track["size_bytes"] for track in manifest["tracks"]),
        "fingerprint": manifest["dataset_fingerprint_sha256"],
        "ffprobe": bool(ffprobe),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    generate_parser = subparsers.add_parser("generate")
    generate_parser.add_argument("--output", required=True, type=Path)
    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("--input", required=True, type=Path)
    validate_parser.add_argument("--require-ffprobe", action="store_true")
    args = parser.parse_args()
    try:
        if args.command == "generate":
            result = generate(args.output.resolve())
        else:
            result = validate(args.input.resolve(), require_ffprobe=args.require_ffprobe)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    except (FixtureError, OSError, subprocess.CalledProcessError, ValueError, KeyError) as error:
        print(f"fixture error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
