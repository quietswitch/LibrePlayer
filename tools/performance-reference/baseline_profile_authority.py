#!/usr/bin/env python3
"""Run and compare the Q1.1h same-build Baseline Profile authority matrix."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import statistics
import subprocess
import sys
import time


MODULE_DIR = Path(__file__).resolve().parent
LIBRARY_SPEC = importlib.util.spec_from_file_location(
    "q11h_library_ui_authority", MODULE_DIR / "library_ui_authority.py"
)
library_ui = importlib.util.module_from_spec(LIBRARY_SPEC)
assert LIBRARY_SPEC.loader
LIBRARY_SPEC.loader.exec_module(library_ui)
startup = library_ui.startup

DISCLAIMER = "CONTROLLED BASELINE PROFILE A/B AUTHORITY — NOT A UNIVERSAL ANDROID PERFORMANCE CLAIM"
BENCHMARK_CLASS = "com.libreplayer.benchmark.BaselineProfileAuthorityBenchmark"
TESTS = (
    "startupEffect",
    "songsEffect",
    "albumsEffect",
    "playbackNowPlayingCorrectness",
)
ITERATIONS = {
    "startupEffect": 10,
    "songsEffect": 5,
    "albumsEffect": 5,
    "playbackNowPlayingCorrectness": 1,
}
FRAME_METRICS = ("frameDurationCpuMs", "frameOverrunMs")
PERCENTILES = ("P50", "P90", "P95", "P99")
MEDIUM_FINGERPRINT = "c573e8d0296a41c322b2ed17e7125e2146d0265836f78d42336dba3359040f9f"
EXPECTED_ORDER = {
    (1, 1): "disabled",
    (1, 2): "enabled",
    (2, 1): "enabled",
    (2, 2): "disabled",
    (3, 1): "disabled",
    (3, 2): "enabled",
}


class BaselineProfileAuthorityError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def require_apk(path: Path, label: str) -> Path:
    resolved = path.resolve()
    if not resolved.is_file() or resolved.suffix.lower() != ".apk":
        raise BaselineProfileAuthorityError(f"{label} APK not found: {resolved}")
    return resolved


def run_adb(adb_path: str, serial: str | None, *args: str, check: bool = True, timeout: int = 900):
    command = [adb_path]
    if serial:
        command += ["-s", serial]
    command += list(args)
    return subprocess.run(
        command,
        check=check,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )


def require_dataset(dataset: Path, adb_path: str, serial: str) -> dict:
    manifest = startup.load_medium_manifest(dataset)
    if manifest.get("dataset_fingerprint_sha256") != MEDIUM_FINGERPRINT:
        raise BaselineProfileAuthorityError("Canonical MEDIUM fingerprint mismatch")
    rows = startup.verify_mediastore(adb_path, serial)
    return {
        "profile": "MEDIUM",
        "tracks": rows,
        "fingerprint_sha256": MEDIUM_FINGERPRINT,
    }


def install_fresh(adb_path: str, serial: str, target_apk: Path, test_apk: Path) -> None:
    for package_name in (startup.TEST_PACKAGE, startup.TARGET_PACKAGE):
        run_adb(adb_path, serial, "uninstall", package_name, check=False, timeout=120)
    for apk in (target_apk, test_apk):
        result = run_adb(adb_path, serial, "install", "-r", "-t", str(apk), timeout=300)
        if "Success" not in result.stdout:
            raise BaselineProfileAuthorityError(f"APK installation failed: {apk}\n{result.stdout}")


def prepare_cached_state(adb_path: str, serial: str, timeout_seconds: int) -> dict:
    cleared = run_adb(adb_path, serial, "shell", "pm", "clear", startup.TARGET_PACKAGE).stdout
    if "Success" not in cleared:
        raise BaselineProfileAuthorityError(f"Could not clear target state: {cleared}")
    run_adb(
        adb_path,
        serial,
        "shell",
        "pm",
        "grant",
        startup.TARGET_PACKAGE,
        "android.permission.READ_MEDIA_AUDIO",
    )
    run_adb(adb_path, serial, "logcat", "-c")
    launch = run_adb(
        adb_path,
        serial,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        f"{startup.TARGET_PACKAGE}/.app.MainActivity",
    ).stdout
    evidence = startup.wait_for_cached_library(adb_path, serial, timeout_seconds)
    run_adb(adb_path, serial, "shell", "am", "force-stop", startup.TARGET_PACKAGE)
    return {"launch": launch.strip(), **evidence}


def run_instrumentation(adb_path: str, serial: str, channel: str, result_dir: Path) -> dict:
    run_adb(adb_path, serial, "shell", "pm", "clear", startup.TEST_PACKAGE)
    before = set(startup.device_files(adb_path, serial))
    class_filter = ",".join(f"{BENCHMARK_CLASS}#{name}" for name in TESTS)
    result = run_adb(
        adb_path,
        serial,
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        class_filter,
        "-e",
        "channel",
        channel,
        "-e",
        "androidx.benchmark.suppressErrors",
        "EMULATOR",
        startup.RUNNER,
        timeout=7_200,
    )
    raw_path = result_dir / "instrumentation.txt"
    raw_path.write_text(result.stdout, encoding="utf-8")
    if f"OK ({len(TESTS)} tests)" not in result.stdout or "FAILURES!!!" in result.stdout:
        raise BaselineProfileAuthorityError(f"Instrumentation failed; see {raw_path}")

    logcat = run_adb(adb_path, serial, "logcat", "-d", timeout=300).stdout
    (result_dir / "logcat.txt").write_text(logcat, encoding="utf-8")
    dexopt = run_adb(adb_path, serial, "shell", "dumpsys", "package", "dexopt", timeout=300).stdout
    (result_dir / "dexopt.txt").write_text(dexopt, encoding="utf-8")

    after = set(startup.device_files(adb_path, serial))
    files = sorted(after - before)
    files.extend(path for path in after if path.endswith("benchmarkData.json") and path not in files)
    pulled = library_ui.pull_device_files_with_retry(
        adb_path,
        serial,
        sorted(set(files)),
        result_dir / "device-output",
    )
    traces = [path for path in pulled if path.endswith((".perfetto-trace", ".trace"))]
    json_results = [path for path in pulled if path.endswith(".json")]
    expected_traces = sum(ITERATIONS.values())
    if len(traces) < expected_traces:
        raise BaselineProfileAuthorityError(
            f"Expected at least {expected_traces} traces, found {len(traces)}"
        )
    if not json_results:
        raise BaselineProfileAuthorityError("No AndroidX benchmark JSON was collected")
    return {
        "raw_result_path": raw_path.as_posix(),
        "trace_paths": traces,
        "json_result_paths": json_results,
        "trace_count": len(traces),
    }


def summarize_run(result_dir: Path) -> dict:
    source, document = library_ui.find_metric_document(result_dir)
    by_name = {str(item.get("name")): item for item in document["benchmarks"]}
    missing = [name for name in TESTS if name not in by_name]
    if missing:
        raise BaselineProfileAuthorityError(f"Missing benchmark results: {missing}")

    startup_summary = {
        metric: library_ui.summarize_scalar_metric(
            by_name["startupEffect"]["metrics"][metric], ITERATIONS["startupEffect"]
        )
        for metric in ("timeToInitialDisplayMs", "timeToFullDisplayMs")
    }
    scrolls: dict[str, dict] = {}
    for journey, benchmark_name in (("songs", "songsEffect"), ("albums", "albumsEffect")):
        benchmark = by_name[benchmark_name]
        sampled = benchmark.get("sampledMetrics", {})
        scrolls[journey] = {
            metric: library_ui.summarize_sampled_metric(
                sampled[metric], ITERATIONS[benchmark_name]
            )
            for metric in FRAME_METRICS
        }
    summary = {
        "source": source.relative_to(result_dir).as_posix(),
        "startup": startup_summary,
        **scrolls,
        "playback_now_playing": {
            "iterations": ITERATIONS["playbackNowPlayingCorrectness"],
            "ui_expected_title": "Track 00010",
            "media_session_expected_state": "PLAYING(3)",
            "media_session_expected_error": None,
            "passed": True,
        },
    }
    (result_dir / "summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return summary


def run_authority(args: argparse.Namespace) -> dict:
    expected = EXPECTED_ORDER[(args.session, args.side)]
    if args.channel != expected:
        raise BaselineProfileAuthorityError(
            f"Wrong balanced order for session {args.session} side {args.side}: expected {expected}"
        )
    result_dir = args.result_dir.resolve()
    if result_dir.exists() and any(result_dir.iterdir()):
        raise BaselineProfileAuthorityError(f"Refusing non-empty result directory: {result_dir}")
    result_dir.mkdir(parents=True, exist_ok=True)
    target_apk = require_apk(args.target_apk, "target")
    test_apk = require_apk(args.test_apk, "test")
    device = library_ui.require_authority_device(args.adb, args.serial)
    dataset = require_dataset(args.dataset.resolve(), args.adb, args.serial)
    settings = library_ui.configure_device(args.adb, args.serial)
    install_fresh(args.adb, args.serial, target_apk, test_apk)
    cached = prepare_cached_state(args.adb, args.serial, args.cache_timeout)
    time.sleep(args.settle_seconds)
    artifacts = run_instrumentation(args.adb, args.serial, args.channel, result_dir)
    summary = summarize_run(result_dir)
    compilation_mode = (
        "CompilationMode.Partial(BaselineProfileMode.Require, warmupIterations = 0)"
        if args.channel == "enabled"
        else "CompilationMode.None()"
    )
    metadata = {
        "authority": DISCLAIMER,
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "session": args.session,
        "side": args.side,
        "channel": args.channel,
        "compilation_mode": compilation_mode,
        "warmup_generated_profile": False,
        "device": device,
        "device_settings": settings,
        "dataset": dataset,
        "cached_state": cached,
        "target_apk": {
            "path": target_apk.as_posix(),
            "bytes": target_apk.stat().st_size,
            "sha256": sha256(target_apk),
        },
        "test_apk": {
            "path": test_apk.as_posix(),
            "bytes": test_apk.stat().st_size,
            "sha256": sha256(test_apk),
        },
        "iterations": ITERATIONS,
        "artifacts": artifacts,
        "summary_path": "summary.json",
    }
    (result_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return {"metadata": metadata, "summary": summary}


def distribution(values: list[float]) -> dict:
    median = statistics.median(values)
    spread = max(values) - min(values)
    return {
        "session_medians": values,
        "median_of_session_medians": median,
        "between_session_range": spread,
        "between_session_range_percent": spread / max(abs(median), 1e-9) * 100.0,
    }


def comparison_node(disabled: list[float], enabled: list[float]) -> dict:
    disabled_node = distribution(disabled)
    enabled_node = distribution(enabled)
    disabled_value = disabled_node["median_of_session_medians"]
    enabled_value = enabled_node["median_of_session_medians"]
    return {
        "disabled": disabled_node,
        "enabled": enabled_node,
        "enabled_minus_disabled": enabled_value - disabled_value,
        "enabled_relative_to_disabled_percent": (enabled_value / disabled_value - 1.0) * 100.0,
        "paired_enabled_lower_by_session": [e < d for d, e in zip(disabled, enabled)],
        "paired_directional_consistency": sum(e < d for d, e in zip(disabled, enabled)),
    }


def compare_results(results_root: Path) -> dict:
    summaries: dict[str, list[dict]] = {"disabled": [], "enabled": []}
    correctness: dict[str, list[bool]] = {"disabled": [], "enabled": []}
    for session in (1, 2, 3):
        for channel in ("disabled", "enabled"):
            path = results_root / f"session-{session}" / channel / "summary.json"
            summary = json.loads(path.read_text(encoding="utf-8"))
            summaries[channel].append(summary)
            correctness[channel].append(bool(summary["playback_now_playing"]["passed"]))

    startup_result = {}
    for metric in ("timeToInitialDisplayMs", "timeToFullDisplayMs"):
        startup_result[metric] = comparison_node(
            [item["startup"][metric]["median"] for item in summaries["disabled"]],
            [item["startup"][metric]["median"] for item in summaries["enabled"]],
        )

    frame_result: dict[str, dict] = {}
    for journey in ("songs", "albums"):
        frame_result[journey] = {}
        for metric in FRAME_METRICS:
            frame_result[journey][metric] = {
                percentile: comparison_node(
                    [
                        item[journey][metric]["median_of_iteration_percentiles"][percentile]
                        for item in summaries["disabled"]
                    ],
                    [
                        item[journey][metric]["median_of_iteration_percentiles"][percentile]
                        for item in summaries["enabled"]
                    ],
                )
                for percentile in PERCENTILES
            }

    comparison = {
        "authority": DISCLAIMER,
        "session_order": [
            "disabled then enabled",
            "enabled then disabled",
            "disabled then enabled",
        ],
        "startup": startup_result,
        **frame_result,
        "playback_now_playing": {
            "disabled_passes": correctness["disabled"],
            "enabled_passes": correctness["enabled"],
            "functional_equivalence": all(correctness["disabled"] + correctness["enabled"]),
        },
    }
    (results_root / "comparison.json").write_text(
        json.dumps(comparison, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return comparison


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    device = subparsers.add_parser("device")
    device.add_argument("--adb", default="adb")
    device.add_argument("--serial", required=True)
    run = subparsers.add_parser("run")
    run.add_argument("--adb", default="adb")
    run.add_argument("--serial", required=True)
    run.add_argument("--dataset", required=True, type=Path)
    run.add_argument("--target-apk", required=True, type=Path)
    run.add_argument("--test-apk", required=True, type=Path)
    run.add_argument("--result-dir", required=True, type=Path)
    run.add_argument("--channel", required=True, choices=("disabled", "enabled"))
    run.add_argument("--session", required=True, type=int, choices=(1, 2, 3))
    run.add_argument("--side", required=True, type=int, choices=(1, 2))
    run.add_argument("--cache-timeout", type=int, default=900)
    run.add_argument("--settle-seconds", type=int, default=10)
    compare = subparsers.add_parser("compare")
    compare.add_argument("--results-root", required=True, type=Path)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        if args.command == "device":
            result = library_ui.require_authority_device(args.adb, args.serial)
        elif args.command == "compare":
            result = compare_results(args.results_root.resolve())
        else:
            result = run_authority(args)
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (
        BaselineProfileAuthorityError,
        library_ui.LibraryUiAuthorityError,
        startup.StartupAuthorityError,
        subprocess.CalledProcessError,
        OSError,
        ValueError,
    ) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
