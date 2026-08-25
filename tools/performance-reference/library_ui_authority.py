#!/usr/bin/env python3
"""Run and summarize Q1.1d library UI authority on the API 36 emulator."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import importlib.util
import json
import math
from pathlib import Path, PurePosixPath
import platform
import statistics
import subprocess
import sys
import time


MODULE_DIR = Path(__file__).resolve().parent
STARTUP_SPEC = importlib.util.spec_from_file_location(
    "q11d_startup_authority", MODULE_DIR / "startup_authority.py"
)
startup = importlib.util.module_from_spec(STARTUP_SPEC)
assert STARTUP_SPEC.loader
STARTUP_SPEC.loader.exec_module(startup)

AUTHORITY_AVD = "LibrePlayer_Benchmark_API_36"
AUTHORITY_API = 36
AUTHORITY_ABI = "x86_64"
EXPECTED_SIZE = "1080x2160"
EXPECTED_DENSITY = 440
BENCHMARK_CLASS = "com.libreplayer.benchmark.LibraryUiBenchmark"
ITERATIONS = 5
DISCLAIMER = "CONTROLLED REGRESSION REFERENCE — NOT A UNIVERSAL DEVICE PERFORMANCE CLAIM"
JOURNEYS = {
    "songs": "songsScroll",
    "albums": "albumsScrollColdAppArtworkCache",
    "search": "searchProgressiveQuery",
    "resume": "backgroundForegroundReturn",
}
FRAME_METRICS = ("frameDurationCpuMs", "frameOverrunMs")
PERCENTILES = ("P50", "P90", "P95", "P99")


class LibraryUiAuthorityError(RuntimeError):
    pass


def adb(adb_path: str, serial: str | None, *args: str, **kwargs):
    return startup.adb(adb_path, serial, *args, **kwargs)


def property_value(adb_path: str, serial: str, name: str) -> str:
    return startup.property_value(adb_path, serial, name)


def parse_prefixed_int(output: str, prefix: str) -> int:
    line = next((line for line in output.splitlines() if line.strip().startswith(prefix)), "")
    if not line:
        raise LibraryUiAuthorityError(f"Missing {prefix!r} in {output!r}")
    return int(line.split(":", 1)[1].strip().split()[0])


def require_authority_device(adb_path: str, serial: str) -> dict:
    devices = startup.enumerate_devices(adb_path)
    if not startup.SERIAL_PATTERN.fullmatch(serial):
        raise LibraryUiAuthorityError(f"Physical or malformed serial refused: {serial}")
    if serial not in devices:
        raise LibraryUiAuthorityError(f"Selected emulator is not connected: {serial}")
    avd_lines = [
        line.strip()
        for line in adb(adb_path, serial, "emu", "avd", "name").stdout.splitlines()
        if line.strip() and line.strip() != "OK"
    ]
    avd = avd_lines[0] if avd_lines else ""
    api = int(property_value(adb_path, serial, "ro.build.version.sdk"))
    abi = property_value(adb_path, serial, "ro.product.cpu.abi")
    size_output = adb(adb_path, serial, "shell", "wm", "size").stdout
    density_output = adb(adb_path, serial, "shell", "wm", "density").stdout
    size = next(
        (line.split(":", 1)[1].strip() for line in size_output.splitlines() if "Physical size:" in line),
        "",
    )
    density = parse_prefixed_int(density_output, "Physical density")
    if avd != AUTHORITY_AVD:
        raise LibraryUiAuthorityError(f"Wrong AVD: expected {AUTHORITY_AVD}, found {avd!r}")
    if api != AUTHORITY_API:
        raise LibraryUiAuthorityError(f"Wrong API: expected {AUTHORITY_API}, found {api}")
    if abi != AUTHORITY_ABI:
        raise LibraryUiAuthorityError(f"Wrong ABI: expected {AUTHORITY_ABI}, found {abi!r}")
    if size != EXPECTED_SIZE or density != EXPECTED_DENSITY:
        raise LibraryUiAuthorityError(
            f"Wrong display: expected {EXPECTED_SIZE}/{EXPECTED_DENSITY}, found {size}/{density}"
        )
    mem_kb = parse_prefixed_int(
        adb(adb_path, serial, "shell", "cat", "/proc/meminfo").stdout,
        "MemTotal",
    )
    return {
        "authority_name": avd,
        "serial": serial,
        "device_profile": "Pixel 3",
        "android_version": property_value(adb_path, serial, "ro.build.version.release"),
        "api_level": api,
        "abi": abi,
        "system_image": "system-images;android-36;google_apis;x86_64",
        "build_fingerprint": property_value(adb_path, serial, "ro.build.fingerprint"),
        "resolution": size,
        "density_dpi": density,
        "ram_kb": mem_kb,
        "locale": property_value(adb_path, serial, "persist.sys.locale")
        or property_value(adb_path, serial, "ro.product.locale")
        or "unknown",
    }


def configure_device(adb_path: str, serial: str) -> dict:
    settings = startup.configure_device(adb_path, serial)
    settings["snapshot_policy"] = "AVD launched with -no-snapshot"
    settings["orientation"] = "portrait"
    return settings


def prepare_cached_state(
    adb_path: str,
    serial: str,
    target_apk: Path,
    test_apk: Path,
    timeout_seconds: int,
) -> dict:
    startup.install_apps(adb_path, serial, target_apk, test_apk)
    cleared = adb(adb_path, serial, "shell", "pm", "clear", startup.TARGET_PACKAGE).stdout.strip()
    if "Success" not in cleared:
        raise LibraryUiAuthorityError(f"Could not clear emulator target state: {cleared}")
    adb(
        adb_path,
        serial,
        "shell",
        "pm",
        "grant",
        startup.TARGET_PACKAGE,
        "android.permission.READ_MEDIA_AUDIO",
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
        f"{startup.TARGET_PACKAGE}/.app.MainActivity",
    ).stdout
    evidence = startup.wait_for_cached_library(adb_path, serial, timeout_seconds)
    adb(adb_path, serial, "shell", "am", "force-stop", startup.TARGET_PACKAGE)
    return {"launch": launch.strip(), **evidence}


def run_instrumentation(adb_path: str, serial: str, result_dir: Path) -> dict:
    adb(adb_path, serial, "shell", "pm", "clear", startup.TEST_PACKAGE)
    before = set(startup.device_files(adb_path, serial))
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
        startup.RUNNER,
        timeout=3_600,
    )
    raw_path = result_dir / "instrumentation.txt"
    raw_path.write_text(result.stdout, encoding="utf-8")
    if "OK (4 tests)" not in result.stdout or "FAILURES!!!" in result.stdout:
        raise LibraryUiAuthorityError(f"Instrumentation failed; see {raw_path}")
    after = set(startup.device_files(adb_path, serial))
    new_files = sorted(after - before)
    benchmark_json = [path for path in after if path.endswith("benchmarkData.json")]
    if benchmark_json:
        new_files.extend(path for path in benchmark_json if path not in new_files)
    new_files = sorted(set(new_files))
    pulled = pull_device_files_with_retry(
        adb_path,
        serial,
        new_files,
        result_dir / "device-output",
    )
    traces = [path for path in pulled if path.endswith((".perfetto-trace", ".trace"))]
    json_results = [path for path in pulled if path.endswith(".json")]
    if len(traces) < len(JOURNEYS) * ITERATIONS:
        raise LibraryUiAuthorityError(
            f"Expected at least {len(JOURNEYS) * ITERATIONS} traces, found {len(traces)}"
        )
    if not json_results:
        raise LibraryUiAuthorityError("No AndroidX benchmark JSON was collected")
    return {
        "raw_result_path": raw_path.as_posix(),
        "device_result_paths": pulled,
        "trace_paths": traces,
        "json_result_paths": json_results,
    }


def pull_device_files_with_retry(
    adb_path: str,
    serial: str,
    files: list[str],
    destination: Path,
) -> list[str]:
    pulled: list[str] = []
    for remote in files:
        root = next(
            (item for item in startup.SAFE_RESULT_ROOTS if remote.startswith(item + "/")),
            None,
        )
        if root is None:
            raise LibraryUiAuthorityError(f"Refusing unexpected device result path: {remote}")
        relative = PurePosixPath(remote).relative_to(PurePosixPath(root))
        local = destination / str(startup.SAFE_RESULT_ROOTS.index(root)) / Path(*relative.parts)
        local.parent.mkdir(parents=True, exist_ok=True)
        remote_size_text = adb(
            adb_path,
            serial,
            "shell",
            "stat",
            "-c",
            "%s",
            remote,
            timeout=60,
        ).stdout.strip()
        remote_size = int(remote_size_text)
        if local.is_file() and local.stat().st_size == remote_size:
            pulled.append(local.as_posix())
            continue
        if local.exists():
            incomplete = Path(str(local) + ".incomplete")
            suffix = 1
            while incomplete.exists():
                incomplete = Path(str(local) + f".incomplete-{suffix}")
                suffix += 1
            local.replace(incomplete)
        last_error: subprocess.CalledProcessError | None = None
        for attempt in range(1, 4):
            try:
                adb(adb_path, serial, "pull", remote, str(local), capture=False)
                last_error = None
                break
            except subprocess.CalledProcessError as error:
                last_error = error
                if attempt < 3:
                    time.sleep(attempt * 2)
        if last_error is not None:
            raise last_error
        pulled.append(local.as_posix())
    return pulled


def recover_instrumentation(adb_path: str, serial: str, result_dir: Path) -> dict:
    raw_path = result_dir / "instrumentation.txt"
    raw = raw_path.read_text(encoding="utf-8")
    if "OK (4 tests)" not in raw or "FAILURES!!!" in raw:
        raise LibraryUiAuthorityError("Partial result is not a successful four-journey run")
    files = [
        path
        for path in startup.device_files(adb_path, serial)
        if "/LibraryUiBenchmark_" in path or path.endswith("benchmarkData.json")
    ]
    pulled = pull_device_files_with_retry(
        adb_path,
        serial,
        sorted(files),
        result_dir / "device-output",
    )
    traces = [path for path in pulled if path.endswith((".perfetto-trace", ".trace"))]
    json_results = [path for path in pulled if path.endswith(".json")]
    if len(traces) < len(JOURNEYS) * ITERATIONS or not json_results:
        raise LibraryUiAuthorityError("Recovered artifact set is incomplete")
    return {
        "raw_result_path": raw_path.as_posix(),
        "device_result_paths": pulled,
        "trace_paths": traces,
        "json_result_paths": json_results,
    }


def percentile(values: list[float], percentile_value: int) -> float:
    if not values:
        raise LibraryUiAuthorityError("Cannot compute a percentile from no samples")
    ordered = sorted(float(value) for value in values)
    position = (len(ordered) - 1) * percentile_value / 100.0
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def summarize_sampled_metric(metric: dict) -> dict:
    runs = [[float(value) for value in run] for run in metric.get("runs", [])]
    if len(runs) != ITERATIONS or any(not run for run in runs):
        raise LibraryUiAuthorityError(
            f"Expected {ITERATIONS} populated sampled runs, found {len(runs)}"
        )
    iteration_percentiles = [
        {name: percentile(run, int(name[1:])) for name in PERCENTILES}
        for run in runs
    ]
    flattened = [value for run in runs for value in run]
    return {
        "androidx_percentiles": {name: float(metric[name]) for name in PERCENTILES},
        "iteration_percentiles": iteration_percentiles,
        "median_of_iteration_percentiles": {
            name: statistics.median(item[name] for item in iteration_percentiles)
            for name in PERCENTILES
        },
        "sample_count_per_iteration": [len(run) for run in runs],
        "minimum": min(flattened),
        "maximum": max(flattened),
    }


def summarize_scalar_metric(metric: dict) -> dict:
    runs = [float(value) for value in metric.get("runs", [])]
    if len(runs) != ITERATIONS:
        raise LibraryUiAuthorityError(f"Expected {ITERATIONS} scalar runs, found {len(runs)}")
    return {
        "raw": runs,
        "minimum": min(runs),
        "median": statistics.median(runs),
        "maximum": max(runs),
    }


def find_metric_document(run_dir: Path) -> tuple[Path, dict]:
    for path in sorted(run_dir.rglob("*.json")):
        document = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(document, dict) and isinstance(document.get("benchmarks"), list):
            return path, document
    raise LibraryUiAuthorityError(f"No AndroidX benchmark JSON found under {run_dir}")


def summarize_document(document: dict, source: str) -> dict:
    by_name = {str(item.get("name")): item for item in document["benchmarks"]}
    journeys: dict[str, dict] = {}
    for journey, benchmark_name in JOURNEYS.items():
        benchmark = by_name.get(benchmark_name)
        if benchmark is None:
            raise LibraryUiAuthorityError(f"Missing benchmark result: {benchmark_name}")
        sampled = benchmark.get("sampledMetrics", {})
        missing = [name for name in FRAME_METRICS if name not in sampled]
        if missing:
            raise LibraryUiAuthorityError(f"{benchmark_name} missing sampled metrics: {missing}")
        journeys[journey] = {
            "benchmark": benchmark_name,
            "frameCount": summarize_scalar_metric(benchmark["metrics"]["frameCount"]),
            **{name: summarize_sampled_metric(sampled[name]) for name in FRAME_METRICS},
        }
    return {"source": source, "journeys": journeys}


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
    relative_range = (max(values) - min(values)) / max(abs(overall), 1.0) * 100.0
    return {
        "session_medians": values,
        "median_of_session_medians": overall,
        "range_percent_of_absolute_overall_median": relative_range,
        "unstable_warning": relative_range > 10.0,
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
        variant_journeys: dict[str, dict] = {}
        for journey in JOURNEYS:
            variant_journeys[journey] = {
                metric: {
                    percentile_name: session_distribution(
                        [
                            summary["journeys"][journey][metric][
                                "median_of_iteration_percentiles"
                            ][percentile_name]
                            for summary in summaries
                        ]
                    )
                    for percentile_name in PERCENTILES
                }
                for metric in FRAME_METRICS
            }
        channels[variant] = variant_journeys
    relative: dict[str, dict] = {}
    for journey in JOURNEYS:
        relative[journey] = {}
        for metric in FRAME_METRICS:
            relative[journey][metric] = {}
            for percentile_name in PERCENTILES:
                reference = channels["reference"][journey][metric][percentile_name][
                    "median_of_session_medians"
                ]
                development = channels["development"][journey][metric][percentile_name][
                    "median_of_session_medians"
                ]
                relative[journey][metric][percentile_name] = (
                    None if reference == 0 else (development / reference - 1.0) * 100.0
                )
    comparison = {
        "authority": DISCLAIMER,
        "channels": channels,
        "development_relative_to_reference_percent": relative,
    }
    (results_root / "comparison.json").write_text(
        json.dumps(comparison, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return comparison


def host_environment() -> dict:
    return {
        "platform": platform.platform(),
        "processor": platform.processor() or "unknown",
        "logical_cpu_count": __import__("os").cpu_count(),
    }


def run_authority(args: argparse.Namespace) -> dict:
    result_dir = args.result_dir.resolve()
    recovering = (
        result_dir.exists()
        and (result_dir / "instrumentation.txt").is_file()
        and not (result_dir / "metadata.json").exists()
    )
    if result_dir.exists() and any(result_dir.iterdir()) and not recovering:
        raise LibraryUiAuthorityError(f"Result directory must be new or empty: {result_dir}")
    result_dir.mkdir(parents=True, exist_ok=True)
    device = require_authority_device(args.adb, args.serial)
    manifest = startup.load_medium_manifest(args.dataset)
    mediastore_tracks = startup.verify_mediastore(args.adb, args.serial)
    configuration = configure_device(args.adb, args.serial)
    if recovering:
        precondition = {
            "artifact_collection_retry": True,
            "measurement_status": "successful; recovered from instrumentation.txt",
        }
        thermal_before = "unavailable after artifact-transfer retry"
        execution = recover_instrumentation(args.adb, args.serial, result_dir)
    else:
        precondition = prepare_cached_state(
            args.adb,
            args.serial,
            args.target_apk,
            args.test_apk,
            args.cache_timeout,
        )
        time.sleep(args.settle_seconds)
        thermal_before = adb(
            args.adb, args.serial, "shell", "dumpsys", "thermalservice", timeout=60
        ).stdout[-8_000:]
        execution = run_instrumentation(args.adb, args.serial, result_dir)
    thermal_after = adb(
        args.adb, args.serial, "shell", "dumpsys", "thermalservice", timeout=60
    ).stdout[-8_000:]
    summary = summarize_run(result_dir)
    metadata = {
        "schema_version": 1,
        "authority": DISCLAIMER,
        "variant": args.variant,
        "session": args.session,
        "reference_git_commit": args.reference_commit,
        "development_git_commit": args.development_commit,
        "benchmark_harness_revision": args.harness_revision,
        "dataset_profile": "MEDIUM",
        "dataset_fingerprint_sha256": manifest["dataset_fingerprint_sha256"],
        "device": device,
        "device_configuration": configuration,
        "emulator_version": args.emulator_version,
        "host_environment": host_environment(),
        "thermal_before": thermal_before,
        "thermal_after": thermal_after,
        "toolchain": {
            "android_gradle_plugin": args.agp,
            "benchmark_library": args.benchmark_library,
            "compile_sdk": args.compile_sdk,
            "build_tools": args.build_tools,
        },
        "compilation_mode": "Full",
        "iteration_count_per_journey": ITERATIONS,
        "journeys": list(JOURNEYS),
        "cache_states": {
            "songs": "cached database; newly launched process; settled refresh",
            "albums": "cold LibrePlayer in-memory artwork cache per iteration; OS/file caches unspecified",
            "search": "loaded stable library; blank query at iteration start",
            "resume": "resident process; normal Home then launcher foreground return",
        },
        "run_timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "mediastore_tracks": mediastore_tracks,
        "precondition": precondition,
        "raw_result_path": Path(execution["raw_result_path"]).relative_to(result_dir).as_posix(),
        "trace_paths": [
            Path(path).relative_to(result_dir).as_posix() for path in execution["trace_paths"]
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
    run.add_argument("--variant", required=True, choices=("reference", "development"))
    run.add_argument("--session", required=True, type=int, choices=(1, 2, 3))
    run.add_argument("--reference-commit", required=True)
    run.add_argument("--development-commit", required=True)
    run.add_argument("--harness-revision", required=True)
    run.add_argument("--emulator-version", required=True)
    run.add_argument("--agp", default="9.1.0")
    run.add_argument("--benchmark-library", default="1.4.1")
    run.add_argument("--compile-sdk", default=36, type=int)
    run.add_argument("--build-tools", default="36.1.0")
    run.add_argument("--cache-timeout", default=900, type=int)
    run.add_argument("--settle-seconds", default=10, type=int)
    summarize = subparsers.add_parser("summarize")
    summarize.add_argument("--run-dir", required=True, type=Path)
    compare = subparsers.add_parser("compare")
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
        LibraryUiAuthorityError,
        startup.StartupAuthorityError,
        OSError,
        ValueError,
        KeyError,
        json.JSONDecodeError,
        subprocess.CalledProcessError,
        subprocess.TimeoutExpired,
    ) as error:
        print(f"library UI authority error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
