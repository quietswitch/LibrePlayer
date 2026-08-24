#!/usr/bin/env python3
"""Run the Q1.1c cached-library startup benchmark on the authority emulator."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path, PurePosixPath
import re
import statistics
import subprocess
import sys
import time

AUTHORITY_AVD = "LibrePlayer_API_28"
AUTHORITY_API = 28
AUTHORITY_ABI = "x86_64"
TARGET_PACKAGE = "com.libreplayer"
TEST_PACKAGE = "com.libreplayer.benchmark"
RUNNER = f"{TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
BENCHMARK_CLASS = "com.libreplayer.benchmark.CachedLibraryStartupBenchmark#cachedSongsColdStartup"
MEDIUM_FINGERPRINT = "c573e8d0296a41c322b2ed17e7125e2146d0265836f78d42336dba3359040f9f"
SERIAL_PATTERN = re.compile(r"^emulator-\d+$")
SAFE_RESULT_ROOTS = (
    f"/sdcard/Android/data/{TEST_PACKAGE}/files",
    f"/sdcard/Android/data/{TEST_PACKAGE}/cache",
    f"/sdcard/Android/media/{TEST_PACKAGE}",
)
VISIBLE_SONG = "Duplicate Display Metadata"


class StartupAuthorityError(RuntimeError):
    pass


def adb(
    adb_path: str,
    serial: str | None,
    *args: str,
    capture: bool = True,
    timeout: int = 900,
) -> subprocess.CompletedProcess[str]:
    command = [adb_path]
    if serial:
        command += ["-s", serial]
    command += list(args)
    return subprocess.run(
        command,
        check=True,
        capture_output=capture,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )


def enumerate_devices(adb_path: str) -> list[str]:
    result = adb(adb_path, None, "devices", "-l")
    print(result.stdout, end="")
    return [
        line.split()[0]
        for line in result.stdout.splitlines()[1:]
        if line.strip() and " device " in f" {line} "
    ]


def property_value(adb_path: str, serial: str, name: str) -> str:
    return adb(adb_path, serial, "shell", "getprop", name).stdout.strip()


def require_authority_device(adb_path: str, serial: str) -> dict:
    devices = enumerate_devices(adb_path)
    if not SERIAL_PATTERN.fullmatch(serial):
        raise StartupAuthorityError(f"Physical or malformed serial refused: {serial}")
    if serial not in devices:
        raise StartupAuthorityError(f"Selected emulator is not connected: {serial}")
    avd_lines = [
        line.strip()
        for line in adb(adb_path, serial, "emu", "avd", "name").stdout.splitlines()
        if line.strip() and line.strip() != "OK"
    ]
    avd = avd_lines[0] if avd_lines else ""
    device = {
        "authority_name": avd,
        "serial": serial,
        "android_version": property_value(adb_path, serial, "ro.build.version.release"),
        "api_level": int(property_value(adb_path, serial, "ro.build.version.sdk")),
        "abi": property_value(adb_path, serial, "ro.product.cpu.abi"),
        "locale": property_value(adb_path, serial, "persist.sys.locale")
        or property_value(adb_path, serial, "ro.product.locale")
        or "unknown",
    }
    if avd != AUTHORITY_AVD:
        raise StartupAuthorityError(f"Wrong AVD: expected {AUTHORITY_AVD}, found {avd!r}")
    if device["api_level"] != AUTHORITY_API:
        raise StartupAuthorityError(
            f"Wrong API: expected {AUTHORITY_API}, found {device['api_level']}"
        )
    if device["abi"] != AUTHORITY_ABI:
        raise StartupAuthorityError(f"Wrong ABI: expected {AUTHORITY_ABI}, found {device['abi']!r}")
    return device


def load_medium_manifest(dataset: Path) -> dict:
    manifest_path = dataset.resolve() / "fixture-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_medium_manifest(manifest)
    return manifest


def validate_medium_manifest(manifest: dict) -> None:
    if manifest.get("profile") != "MEDIUM":
        raise StartupAuthorityError(f"Expected MEDIUM fixture, found {manifest.get('profile')!r}")
    fingerprint = manifest.get("dataset_fingerprint_sha256")
    if fingerprint != MEDIUM_FINGERPRINT:
        raise StartupAuthorityError(f"MEDIUM fingerprint mismatch: {fingerprint}")
    if manifest.get("expected", {}).get("tracks") != 2_000:
        raise StartupAuthorityError("MEDIUM manifest does not define exactly 2,000 tracks")


def verify_mediastore(adb_path: str, serial: str) -> int:
    where = (
        "_data LIKE '/storage/emulated/0/Music/LibrePlayerBenchmark/MEDIUM/%' "
        "AND is_music != 0 AND duration >= 30000"
    )
    result = adb(
        adb_path,
        serial,
        "shell",
        "content",
        "query",
        "--uri",
        "content://media/external/audio/media",
        "--projection",
        "_id",
        "--where",
        f'"{where}"',
    )
    rows = sum(line.startswith("Row:") for line in result.stdout.splitlines())
    if rows != 2_000:
        raise StartupAuthorityError(f"MEDIUM MediaStore count mismatch: expected 2000, found {rows}")
    return rows


def require_apk(path: Path, label: str) -> Path:
    resolved = path.resolve()
    if not resolved.is_file() or resolved.suffix.lower() != ".apk":
        raise StartupAuthorityError(f"{label} APK not found: {resolved}")
    return resolved


def configure_device(adb_path: str, serial: str) -> dict:
    settings = {
        "accelerometer_rotation": "0",
        "user_rotation": "0",
        "font_scale": "1.0",
        "window_animation_scale": "0",
        "transition_animation_scale": "0",
        "animator_duration_scale": "0",
    }
    for name, value in settings.items():
        namespace = "system" if name in {"accelerometer_rotation", "user_rotation", "font_scale"} else "global"
        adb(adb_path, serial, "shell", "settings", "put", namespace, name, value)
    return settings


def install_apps(adb_path: str, serial: str, target_apk: Path, test_apk: Path) -> None:
    for package_name in (TEST_PACKAGE, TARGET_PACKAGE):
        installed = adb(
            adb_path,
            serial,
            "shell",
            "pm",
            "path",
            package_name,
        ).stdout.strip()
        if installed.startswith("package:"):
            adb(adb_path, serial, "uninstall", package_name, capture=False)
    adb(adb_path, serial, "install", "-r", str(require_apk(target_apk, "target")), capture=False)
    adb(adb_path, serial, "install", "-r", "-t", str(require_apk(test_apk, "test")), capture=False)


def ui_dump(adb_path: str, serial: str) -> str:
    remote = "/sdcard/LibrePlayerBenchmark-q11c-window.xml"
    try:
        adb(adb_path, serial, "shell", "uiautomator", "dump", remote, timeout=60)
        return adb(adb_path, serial, "exec-out", "cat", remote).stdout
    finally:
        adb(adb_path, serial, "shell", "rm", "-f", remote)


def wait_for_cached_library(adb_path: str, serial: str, timeout_seconds: int) -> dict:
    deadline = time.monotonic() + timeout_seconds
    last_log = ""
    last_ui = ""
    while time.monotonic() < deadline:
        last_log = adb(
            adb_path,
            serial,
            "logcat",
            "-d",
            "-s",
            "LibrePlayerLibrarySync:I",
            "*:S",
        ).stdout
        try:
            last_ui = ui_dump(adb_path, serial)
        except subprocess.CalledProcessError:
            last_ui = ""
        scan_complete = re.search(r"\bmediaRows=2000\b", last_log) is not None
        songs_visible = 'text="Songs"' in last_ui and f'text="{VISIBLE_SONG}"' in last_ui
        refresh_visible = any(
            f'text="{title}"' in last_ui
            for title in ("Scanning library", "Updating library")
        )
        if scan_complete and songs_visible and not refresh_visible:
            return {
                "sync_log_evidence": next(
                    line for line in reversed(last_log.splitlines()) if "mediaRows=2000" in line
                ),
                "songs_destination_visible": True,
                "representative_cached_song_visible": VISIBLE_SONG,
                "refresh_settled": True,
            }
        time.sleep(2)
    raise StartupAuthorityError(
        "Timed out waiting for the populated cached Songs screen; "
        f"last_sync_log={last_log[-1000:]!r} last_ui={last_ui[-1000:]!r}"
    )


def prepare_cached_state(
    adb_path: str,
    serial: str,
    target_apk: Path,
    test_apk: Path,
    timeout_seconds: int,
) -> dict:
    install_apps(adb_path, serial, target_apk, test_apk)
    cleared = adb(adb_path, serial, "shell", "pm", "clear", TARGET_PACKAGE).stdout.strip()
    if "Success" not in cleared:
        raise StartupAuthorityError(f"Could not clear emulator target state: {cleared}")
    adb(
        adb_path,
        serial,
        "shell",
        "pm",
        "grant",
        TARGET_PACKAGE,
        "android.permission.READ_EXTERNAL_STORAGE",
    )
    adb(adb_path, serial, "logcat", "-c")
    launch = adb(
        adb_path,
        serial,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        f"{TARGET_PACKAGE}/.app.MainActivity",
    ).stdout
    evidence = wait_for_cached_library(adb_path, serial, timeout_seconds)
    adb(adb_path, serial, "shell", "am", "force-stop", TARGET_PACKAGE)
    return {"launch": launch.strip(), **evidence}


def device_files(adb_path: str, serial: str) -> list[str]:
    files: list[str] = []
    for root in SAFE_RESULT_ROOTS:
        try:
            result = adb(adb_path, serial, "shell", "find", root, "-type", "f", timeout=60)
        except subprocess.CalledProcessError:
            continue
        files.extend(
            line.strip()
            for line in result.stdout.splitlines()
            if line.strip().startswith(root + "/")
        )
    return sorted(set(files))


def pull_device_files(adb_path: str, serial: str, files: list[str], destination: Path) -> list[str]:
    pulled: list[str] = []
    for remote in files:
        root = next((item for item in SAFE_RESULT_ROOTS if remote.startswith(item + "/")), None)
        if root is None:
            raise StartupAuthorityError(f"Refusing unexpected device result path: {remote}")
        relative = PurePosixPath(remote).relative_to(PurePosixPath(root))
        local = destination / str(SAFE_RESULT_ROOTS.index(root)) / Path(*relative.parts)
        local.parent.mkdir(parents=True, exist_ok=True)
        adb(adb_path, serial, "pull", remote, str(local), capture=False)
        pulled.append(local.as_posix())
    return pulled


def run_instrumentation(adb_path: str, serial: str, result_dir: Path) -> dict:
    adb(adb_path, serial, "shell", "pm", "clear", TEST_PACKAGE)
    before = set(device_files(adb_path, serial))
    result = adb(
        adb_path,
        serial,
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        BENCHMARK_CLASS,
        "-e",
        "androidx.benchmark.suppressErrors",
        "EMULATOR",
        RUNNER,
        timeout=1_800,
    )
    raw_path = result_dir / "instrumentation.txt"
    raw_path.write_text(result.stdout, encoding="utf-8")
    if "OK (1 test)" not in result.stdout or "FAILURES!!!" in result.stdout:
        raise StartupAuthorityError(f"Instrumentation did not complete successfully; see {raw_path}")
    after = set(device_files(adb_path, serial))
    new_files = sorted(after - before)
    pulled = pull_device_files(adb_path, serial, new_files, result_dir / "device-output")
    traces = [path for path in pulled if path.endswith(".perfetto-trace") or path.endswith(".trace")]
    json_results = [path for path in pulled if path.endswith(".json")]
    if not traces:
        raise StartupAuthorityError("Benchmark passed but no Macrobenchmark traces were found")
    if not json_results:
        raise StartupAuthorityError("Benchmark passed but no raw Macrobenchmark JSON result was found")
    return {
        "raw_result_path": raw_path.as_posix(),
        "device_result_paths": pulled,
        "trace_paths": traces,
        "json_result_paths": json_results,
    }


def find_metric_document(run_dir: Path) -> tuple[Path, dict]:
    for path in sorted(run_dir.rglob("*.json")):
        document = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(document, dict) and isinstance(document.get("benchmarks"), list):
            return path, document
    raise StartupAuthorityError(f"No AndroidX benchmark JSON found under {run_dir}")


def summarize_metric(metric: dict) -> dict:
    values = [float(value) for value in metric.get("runs", [])]
    if not values:
        raise StartupAuthorityError("Metric has no raw runs")
    return {
        "raw": values,
        "min": min(values),
        "median": statistics.median(values),
        "max": max(values),
        "mean": statistics.fmean(values),
        "standard_deviation": statistics.pstdev(values),
    }


def summarize_document(document: dict, source: str) -> dict:
    benchmark = next(
        (
            item
            for item in document["benchmarks"]
            if "CachedLibraryStartupBenchmark" in str(item.get("name", ""))
            or "CachedLibraryStartupBenchmark" in str(item.get("className", ""))
        ),
        None,
    )
    if benchmark is None:
        raise StartupAuthorityError("Authoritative cached-library benchmark missing from result JSON")
    metrics = benchmark.get("metrics", {})
    required = ("timeToInitialDisplayMs", "timeToFullDisplayMs")
    missing = [name for name in required if name not in metrics]
    if missing:
        raise StartupAuthorityError(f"Required startup metrics missing: {missing}")
    summary = {
        "source": source,
        "timeToInitialDisplayMs": summarize_metric(metrics["timeToInitialDisplayMs"]),
        "timeToFullDisplayMs": summarize_metric(metrics["timeToFullDisplayMs"]),
        "memory": {
            name: summarize_metric(metric)
            for name, metric in metrics.items()
            if name.lower().startswith("memory")
        },
    }
    return summary


def summarize_run(run_dir: Path) -> dict:
    source, document = find_metric_document(run_dir)
    summary = summarize_document(document, source.relative_to(run_dir).as_posix())
    (run_dir / "summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return summary


def session_distribution(values: list[float]) -> dict:
    overall = statistics.median(values)
    return {
        "session_medians": values,
        "median_of_session_medians": overall,
        "range_percent_of_overall_median": (max(values) - min(values)) / overall * 100.0,
    }


def compare_results(results_root: Path) -> dict:
    channels: dict[str, dict] = {}
    for variant in ("reference", "development"):
        summaries = [
            json.loads(
                (results_root / f"session-{session}" / variant / "summary.json").read_text(
                    encoding="utf-8"
                )
            )
            for session in (1, 2, 3)
        ]
        channels[variant] = {
            metric: session_distribution([summary[metric]["median"] for summary in summaries])
            for metric in ("timeToInitialDisplayMs", "timeToFullDisplayMs")
        }
        channels[variant]["memoryHeapSizeMaxKb"] = session_distribution(
            [summary["memory"]["memoryHeapSizeMaxKb"]["median"] for summary in summaries]
        )
    comparison = {
        metric: (
            channels["development"][metric]["median_of_session_medians"]
            / channels["reference"][metric]["median_of_session_medians"]
            - 1.0
        )
        * 100.0
        for metric in (
            "timeToInitialDisplayMs",
            "timeToFullDisplayMs",
            "memoryHeapSizeMaxKb",
        )
    }
    result = {
        "authority": "CONTROLLED REGRESSION RESULTS FOR THIS ENVIRONMENT — NOT A UNIVERSAL PERFORMANCE CLAIM",
        "channels": channels,
        "development_relative_to_reference_percent": comparison,
    }
    (results_root / "comparison.json").write_text(
        json.dumps(result, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return result


def run_authority(args: argparse.Namespace) -> dict:
    result_dir = args.result_dir.resolve()
    if result_dir.exists() and any(result_dir.iterdir()):
        raise StartupAuthorityError(f"Result directory must be new or empty: {result_dir}")
    result_dir.mkdir(parents=True, exist_ok=True)
    device = require_authority_device(args.adb, args.serial)
    manifest = load_medium_manifest(args.dataset)
    mediastore_tracks = verify_mediastore(args.adb, args.serial)
    configuration = configure_device(args.adb, args.serial)
    precondition = prepare_cached_state(
        args.adb,
        args.serial,
        args.target_apk,
        args.test_apk,
        args.cache_timeout,
    )
    execution = run_instrumentation(args.adb, args.serial, result_dir)
    summary = summarize_run(result_dir)
    metadata = {
        "schema_version": 2,
        "authority": "CONTROLLED REGRESSION RESULTS FOR THIS ENVIRONMENT — NOT A UNIVERSAL PERFORMANCE CLAIM",
        "variant": args.variant,
        "session": args.session,
        "reference_git_commit": args.reference_commit,
        "development_git_commit": args.development_commit,
        "benchmark_harness_revision": args.harness_revision,
        "measurement_hook_sha256": args.measurement_hook_sha256,
        "dataset_profile": "MEDIUM",
        "dataset_fingerprint_sha256": manifest["dataset_fingerprint_sha256"],
        "device": device,
        "device_configuration": configuration,
        "toolchain": {
            "android_gradle_plugin": args.agp,
            "benchmark_library": args.benchmark_library,
            "compile_sdk": args.compile_sdk,
            "build_tools": args.build_tools,
        },
        "startup_mode": "COLD",
        "compilation_mode": "Full",
        "iteration_count": 10,
        "run_timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "mediastore_tracks": mediastore_tracks,
        "precondition": precondition,
        "raw_result_path": Path(execution["raw_result_path"]).relative_to(result_dir).as_posix(),
        "trace_paths": [
            Path(path).relative_to(result_dir).as_posix()
            for path in execution["trace_paths"]
        ],
        "summary_path": "summary.json",
    }
    (result_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return {"metadata": metadata, "summary": summary}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    device = subparsers.add_parser("device", help="validate and report the authority emulator")
    device.add_argument("--adb", default="adb")
    device.add_argument("--serial", required=True)

    run = subparsers.add_parser("run", help="prepare cached state and run one 10-iteration side")
    run.add_argument("--adb", default="adb")
    run.add_argument("--serial", required=True)
    run.add_argument("--dataset", required=True, type=Path)
    run.add_argument("--target-apk", required=True, type=Path)
    run.add_argument("--test-apk", required=True, type=Path)
    run.add_argument("--result-dir", required=True, type=Path)
    run.add_argument("--variant", required=True, choices=("reference", "development"))
    run.add_argument("--session", required=True, type=int, choices=(1, 2, 3))
    run.add_argument("--reference-commit", required=True)
    run.add_argument("--development-commit", required=True)
    run.add_argument("--harness-revision", required=True)
    run.add_argument("--measurement-hook-sha256", required=True)
    run.add_argument("--agp", default="9.1.0")
    run.add_argument("--benchmark-library", default="1.4.1")
    run.add_argument("--compile-sdk", default=36, type=int)
    run.add_argument("--build-tools", required=True)
    run.add_argument("--cache-timeout", default=900, type=int)

    summarize = subparsers.add_parser("summarize", help="summarize an already-pulled run")
    summarize.add_argument("--run-dir", required=True, type=Path)
    compare = subparsers.add_parser("compare", help="compare three paired session summaries")
    compare.add_argument("--results-root", required=True, type=Path)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        if args.command == "device":
            result = require_authority_device(args.adb, args.serial)
        elif args.command == "summarize":
            result = summarize_run(args.run_dir.resolve())
        elif args.command == "compare":
            result = compare_results(args.results_root.resolve())
        else:
            result = run_authority(args)
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (
        StartupAuthorityError,
        OSError,
        ValueError,
        KeyError,
        json.JSONDecodeError,
        subprocess.CalledProcessError,
        subprocess.TimeoutExpired,
    ) as error:
        print(f"startup authority error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
