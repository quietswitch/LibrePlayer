#!/usr/bin/env python3
"""Generate the compact deterministic Q3.5 local-artwork MP3 corpus."""

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
import zlib

FIXED_TIMESTAMP = 1_700_100_000
UPDATED_TIMESTAMP = FIXED_TIMESTAMP + 100
PROFILE = "Q35_ARTWORK_AUTHORITY"
ROOT = Path(__file__).resolve().parent
TEMPLATE_PATH = ROOT / "audio-template.mp3"
MANIFEST_NAME = "fixture-manifest.json"


class FixtureError(RuntimeError):
    pass


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def synchsafe(value: int) -> bytes:
    return bytes(((value >> 21) & 0x7F, (value >> 14) & 0x7F, (value >> 7) & 0x7F, value & 0x7F))


def text_frame(frame_id: str, value: str) -> bytes:
    payload = b"\x01\xff\xfe" + value.encode("utf-16-le")
    return frame_id.encode("ascii") + struct.pack(">I", len(payload)) + b"\x00\x00" + payload


def artwork_frame(image: bytes, mime_type: str) -> bytes:
    payload = b"\x00" + mime_type.encode("ascii") + b"\x00\x03\x00" + image
    return b"APIC" + struct.pack(">I", len(payload)) + b"\x00\x00" + payload


def id3_tag(frames: list[bytes]) -> bytes:
    payload = b"".join(frames)
    return b"ID3\x03\x00\x00" + synchsafe(len(payload)) + payload


def solid_png(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    def chunk(kind: bytes, payload: bytes) -> bytes:
        body = kind + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFF_FFFF)

    scanline = b"\x00" + bytes(rgb) * width
    pixels = scanline * height
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(pixels, level=9))
        + chunk(b"IEND", b"")
    )


def transcode_image(png: bytes, codec: str) -> bytes:
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise FixtureError("ffmpeg is required to generate deterministic JPEG/WebP artwork")
    if codec == "jpeg":
        codec_args = ["-c:v", "mjpeg", "-q:v", "2"]
        muxer = "image2pipe"
    elif codec == "webp":
        codec_args = ["-c:v", "libwebp", "-lossless", "1", "-compression_level", "6"]
        muxer = "webp"
    else:
        raise FixtureError(f"Unsupported fixture image codec: {codec}")
    result = subprocess.run(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-threads",
            "1",
            "-f",
            "image2pipe",
            "-i",
            "pipe:0",
            "-frames:v",
            "1",
            "-map_metadata",
            "-1",
            *codec_args,
            "-f",
            muxer,
            "pipe:1",
        ],
        input=png,
        check=True,
        capture_output=True,
    )
    if not result.stdout:
        raise FixtureError(f"ffmpeg emitted no {codec} bytes")
    return result.stdout


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


def image_definitions() -> dict[str, dict]:
    red_png = solid_png(512, 512, (220, 24, 32))
    green_png = solid_png(512, 512, (20, 190, 70))
    blue_png = solid_png(512, 512, (35, 85, 220))
    large_png = solid_png(2_048, 2_048, (125, 55, 200))
    values = {
        "red_jpeg_512": (transcode_image(red_png, "jpeg"), "JPEG", "image/jpeg", 512, 512),
        "green_png_512": (green_png, "PNG", "image/png", 512, 512),
        "blue_webp_512": (transcode_image(blue_png, "webp"), "WebP", "image/webp", 512, 512),
        "large_webp_2048": (transcode_image(large_png, "webp"), "WebP", "image/webp", 2_048, 2_048),
        "corrupt_payload": (b"Q35-not-an-image\x00\xff\x10", "invalid", "image/png", -1, -1),
        "truncated_png": (green_png[:48], "truncated PNG", "image/png", 512, 512),
    }
    return {
        name: {
            "bytes": content,
            "format": image_format,
            "mime_type": mime_type,
            "width": width,
            "height": height,
            "size_bytes": len(content),
            "sha256": sha256_bytes(content),
        }
        for name, (content, image_format, mime_type, width, height) in values.items()
    }


def tagged_audio(
    audio: bytes,
    title: str,
    artist: str,
    album: str,
    track: int,
    artwork: dict | None,
) -> bytes:
    frames = [
        text_frame("TIT2", title),
        text_frame("TPE1", artist),
        text_frame("TALB", album),
        text_frame("TRCK", str(track)),
        text_frame("TPOS", "1"),
        text_frame("TYER", "2026"),
    ]
    if artwork is not None:
        frames.append(artwork_frame(artwork["bytes"], artwork["mime_type"]))
    return id3_tag(frames) + audio


def track_definitions(audio: bytes, images: dict[str, dict]) -> list[dict]:
    rows = [
        ("01-normal-jpeg.mp3", "Normal JPEG", "Q35 Artist", "Q35 Normal", 1, "red_jpeg_512", "normal valid embedded JPEG"),
        ("02-missing.mp3", "Missing Art", "Q35 Artist", "Q35 Missing", 1, None, "no embedded artwork"),
        ("03-partial-missing.mp3", "Partial One", "Q35 Artist", "Q35 Partial", 1, None, "first Album member has no artwork"),
        ("04-partial-green.mp3", "Partial Two", "Q35 Artist", "Q35 Partial", 2, "green_png_512", "later Album member supplies PNG artwork"),
        ("05-conflict-red.mp3", "Conflict One", "Q35 Artist", "Q35 Conflict", 1, "red_jpeg_512", "ordered Album representative with red JPEG"),
        ("06-conflict-green.mp3", "Conflict Two", "Q35 Artist", "Q35 Conflict", 2, "green_png_512", "conflicting later member with green PNG"),
        ("07-greatest-a.mp3", "Greatest A", "Q35 Artist A", "Greatest Hits", 1, "red_jpeg_512", "same-title Album A isolation"),
        ("08-greatest-b.mp3", "Greatest B", "Q35 Artist B", "Greatest Hits", 1, "blue_webp_512", "same-title Album B isolation"),
        ("09-twin-red.mp3", "Twin", "Q35 Twin Artist", "Q35 Twins", 1, "red_jpeg_512", "duplicate metadata occurrence with red art"),
        ("10-twin-green.mp3", "Twin", "Q35 Twin Artist", "Q35 Twins", 1, "green_png_512", "duplicate metadata occurrence with green art"),
        ("11-corrupt-art.mp3", "Corrupt Art", "Q35 Artist", "Q35 Corrupt", 1, "corrupt_payload", "valid audio with corrupt embedded image bytes"),
        ("12-truncated-art.mp3", "Truncated Art", "Q35 Artist", "Q35 Truncated", 1, "truncated_png", "valid audio with truncated embedded PNG"),
        ("13-large-webp.mp3", "Large Art", "Q35 Artist", "Q35 Large", 1, "large_webp_2048", "bounded 2048-square WebP"),
        ("14-art-change.mp3", "Art Change", "Q35 Artist", "Q35 Change", 1, "red_jpeg_512", "same-source artwork A before update"),
    ]
    definitions = []
    for file_name, title, artist, album, track, image_name, purpose in rows:
        artwork = images[image_name] if image_name else None
        definitions.append(
            {
                "file": file_name,
                "title": title,
                "artist": artist,
                "album": album,
                "track": track,
                "image": image_name,
                "purpose": purpose,
                "content": tagged_audio(audio, title, artist, album, track, artwork),
            }
        )
    return definitions


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
    images = image_definitions()
    definitions = track_definitions(audio, images)
    tracks = []
    for definition in definitions:
        relative = PurePosixPath(definition["file"])
        content = definition["content"]
        destination = output / relative
        destination.write_bytes(content)
        os.utime(destination, (FIXED_TIMESTAMP, FIXED_TIMESTAMP))
        tracks.append(
            {
                "relative_path": relative.as_posix(),
                "audio_format": "MP3",
                "tag_version": "ID3v2.3",
                "embedded_artwork": definition["image"],
                "size_bytes": len(content),
                "sha256": sha256_bytes(content),
                "purpose": definition["purpose"],
                "expected_minimum_duration_ms": 30_000,
            }
        )

    updated_definition = next(item for item in definitions if item["file"] == "14-art-change.mp3")
    updated_content = tagged_audio(
        audio,
        updated_definition["title"],
        updated_definition["artist"],
        updated_definition["album"],
        updated_definition["track"],
        images["blue_webp_512"],
    )
    update_relative = PurePosixPath("updates/14-art-change-blue.mp3")
    update_path = output / update_relative
    update_path.parent.mkdir(parents=True, exist_ok=True)
    update_path.write_bytes(updated_content)
    os.utime(update_path, (UPDATED_TIMESTAMP, UPDATED_TIMESTAMP))

    image_manifest = {
        name: {key: value for key, value in image.items() if key != "bytes"}
        for name, image in images.items()
    }
    manifest = {
        "schema_version": 1,
        "generator_version": "1.0.0",
        "profile": PROFILE,
        "fixed_timestamp_epoch_seconds": FIXED_TIMESTAMP,
        "audio_template_sha256": sha256_bytes(audio),
        "expected": {"tracks": len(tracks)},
        "images": image_manifest,
        "tracks": tracks,
        "update": {
            "relative_path": update_relative.as_posix(),
            "target_relative_path": "14-art-change.mp3",
            "embedded_artwork": "blue_webp_512",
            "size_bytes": len(updated_content),
            "sha256": sha256_bytes(updated_content),
            "fixed_timestamp_epoch_seconds": UPDATED_TIMESTAMP,
        },
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
    ffprobe = shutil.which("ffprobe")
    if require_ffprobe and not ffprobe:
        raise FixtureError("ffprobe is required for decode validation")
    audio = template_audio()
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
    update = manifest["update"]
    update_path = output / PurePosixPath(update["relative_path"])
    update_content = update_path.read_bytes()
    if sha256_bytes(update_content) != update["sha256"] or len(update_content) != update["size_bytes"]:
        raise FixtureError("Artwork update payload hash or size mismatch")
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
