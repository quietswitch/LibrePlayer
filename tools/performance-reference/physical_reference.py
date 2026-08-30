#!/usr/bin/env python3
"""Run privacy-safe, side-by-side Q1.1i physical-reference measurements."""

from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import re
import statistics
import subprocess
import sys


MODULE_DIR = Path(__file__).resolve().parent
LIBRARY_SPEC = importlib.util.spec_from_file_location(
    "q11i_library_ui_authority", MODULE_DIR / "library_ui_authority.py"
)
library_ui = importlib.util.module_from_spec(LIBRARY_SPEC)
assert LIBRARY_SPEC.loader
LIBRARY_SPEC.loader.exec_module(library_ui)
startup = library_ui.startup

DISCLAIMER = "PHYSICAL DEVICE REFERENCE — NOT A UNIVERSAL ANDROID PERFORMANCE REQUIREMENT"
USER_PACKAGE = "com.libreplayer"
TARGET_PACKAGE = "com.libreplayer.physicalreference"
TEST_PACKAGE = "com.libreplayer.benchmark"
BENCHMARK_CLASS = "com.libreplayer.benchmark.PhysicalReferenceBenchmark"
RUNNER = f"{TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
TEST_ITERATIONS = {
    "startupReference": 5,
    "songsReference": 3,
    "albumsReference": 3,
    "playbackRefreshRebuildReference": 1,
}
FRAME_METRICS = ("frameDurationCpuMs", "frameOverrunMs")
MEMORY_PATTERN = re.compile(
    r"Q11I_PHYSICAL: memory label=([^ ]+) pss_kb=(-?\d+) rss_kb=(-?\d+) threads=(-?\d+)"
)


class PhysicalReferenceError(RuntimeError):
    pass


def adb(adb_path: str, serial: str | None, *args: str, timeout: int = 900, check: bool = True):
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


def require_physical_device(adb_path: str, serial: str) -> dict:
    listing = adb(adb_path, None, "devices", "-l").stdout.splitlines()[1:]
    connected = [line.strip() for line in listing if line.strip()]
    usable = [line for line in connected if len(line.split()) >= 2 and line.split()[1] == "device"]
    if len(connected) != 1 or len(usable) != 1:
        raise PhysicalReferenceError(
            f"Expected exactly one authorized device; connected={len(connected)} usable={len(usable)}"
        )
    if usable[0].split()[0] != serial:
        raise PhysicalReferenceError("The sole authorized device does not match the explicit serial")
    qemu = adb(adb_path, serial, "shell", "getprop", "ro.kernel.qemu").stdout.strip()
    abi = adb(adb_path, serial, "shell", "getprop", "ro.product.cpu.abi").stdout.strip()
    if qemu == "1" or not abi.startswith("arm64"):
        raise PhysicalReferenceError(f"Refusing non-physical/non-ARM64 target: qemu={qemu} abi={abi}")
    return {
        "serial": serial,
        "manufacturer": adb(
            adb_path, serial, "shell", "getprop", "ro.product.manufacturer"
        ).stdout.strip(),
        "model": adb(adb_path, serial, "shell", "getprop", "ro.product.model").stdout.strip(),
        "android": adb(
            adb_path, serial, "shell", "getprop", "ro.build.version.release"
        ).stdout.strip(),
        "api": int(
            adb(adb_path, serial, "shell", "getprop", "ro.build.version.sdk").stdout.strip()
        ),
        "abi": abi,
        "qemu": False,
    }


def package_path(adb_path: str, serial: str, package_name: str) -> str:
    return adb(
        adb_path, serial, "shell", "pm", "path", package_name, check=False
    ).stdout.strip()


def package_data_dir(adb_path: str, serial: str, package_name: str) -> str:
    dump = adb(adb_path, serial, "shell", "dumpsys", "package", package_name).stdout
    match = re.search(r"\bdataDir=(\S+)", dump)
    return match.group(1) if match else ""


def require_isolation(adb_path: str, serial: str) -> dict:
    paths = {
        package: package_path(adb_path, serial, package)
        for package in (USER_PACKAGE, TARGET_PACKAGE, TEST_PACKAGE)
    }
    if not all(value.startswith("package:") for value in paths.values()):
        raise PhysicalReferenceError(f"Required installed packages are missing: {paths}")
    user_data = package_data_dir(adb_path, serial, USER_PACKAGE)
    target_data = package_data_dir(adb_path, serial, TARGET_PACKAGE)
    if user_data != f"/data/user/0/{USER_PACKAGE}":
        raise PhysicalReferenceError("Unexpected user-package data directory")
    if target_data != f"/data/user/0/{TARGET_PACKAGE}" or target_data == user_data:
        raise PhysicalReferenceError("Physical-reference data isolation was not established")
    return {"package_paths": paths, "user_data_dir": user_data, "target_data_dir": target_data}


def parse_memory_markers(logcat: str) -> list[dict]:
    return [
        {
            "label": match.group(1),
            "pss_kb": int(match.group(2)),
            "rss_kb": int(match.group(3)),
            "threads": int(match.group(4)),
        }
        for match in MEMORY_PATTERN.finditer(logcat)
    ]


def summarize_benchmark(result_dir: Path, method: str, iterations: int) -> dict:
    source, document = library_ui.find_metric_document(result_dir)
    benchmark = next(
        (item for item in document["benchmarks"] if item.get("name") == method),
        None,
    )
    if benchmark is None:
        raise PhysicalReferenceError(f"Benchmark JSON did not contain {method}")
    summary: dict = {"source": source.relative_to(result_dir).as_posix(), "iterations": iterations}
    if method == "startupReference":
        summary["startup"] = {
            metric: library_ui.summarize_scalar_metric(benchmark["metrics"][metric], iterations)
            for metric in ("timeToInitialDisplayMs", "timeToFullDisplayMs")
        }
    elif method in {"songsReference", "albumsReference"}:
        summary["frames"] = {
            metric: library_ui.summarize_sampled_metric(
                benchmark["sampledMetrics"][metric], iterations
            )
            for metric in FRAME_METRICS
        }
    else:
        summary["functional"] = {
            "playback": True,
            "background": True,
            "notification": True,
            "refreshes": 3,
            "rebuild": True,
        }
    return summary


def run_test(args: argparse.Namespace) -> dict:
    result_dir = args.result_dir.resolve()
    if result_dir.exists() and any(result_dir.iterdir()):
        raise PhysicalReferenceError(f"Refusing non-empty result directory: {result_dir}")
    result_dir.mkdir(parents=True, exist_ok=True)
    device = require_physical_device(args.adb, args.serial)
    isolation = require_isolation(args.adb, args.serial)
    method = args.method
    iterations = args.iterations or TEST_ITERATIONS[method]
    adb(args.adb, args.serial, "shell", "pm", "clear", TEST_PACKAGE)
    log_start = adb(
        args.adb, args.serial, "shell", "date", "+%m-%d %H:%M:%S.000"
    ).stdout.strip()
    before = set(startup.device_files(args.adb, args.serial))
    command = [
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class", f"{BENCHMARK_CLASS}#{method}",
    ]
    if method != "prepareReference":
        command += ["-e", "iterations", str(iterations)]
    if args.channel:
        command += ["-e", "channel", args.channel]
    if method == "playbackRefreshRebuildReference":
        command += ["-e", "backgroundMillis", str(args.background_millis)]
    command.append(RUNNER)
    result = adb(args.adb, args.serial, *command, timeout=3_600)
    (result_dir / "instrumentation.txt").write_text(result.stdout, encoding="utf-8")
    if "OK (1 test)" not in result.stdout or "FAILURES!!!" in result.stdout:
        raise PhysicalReferenceError("Physical instrumentation failed; see instrumentation.txt")
    logcat = adb(
        args.adb,
        args.serial,
        "logcat",
        "-d",
        "-T",
        log_start,
        "-s",
        "Q11I_PHYSICAL:I",
        "ProfileInstaller:I",
        "*:S",
        timeout=300,
    ).stdout
    (result_dir / "logcat.txt").write_text(logcat, encoding="utf-8")
    dexopt = adb(
        args.adb, args.serial, "shell", "dumpsys", "package", "dexopt", timeout=300
    ).stdout
    (result_dir / "dexopt.txt").write_text(dexopt, encoding="utf-8")
    after = set(startup.device_files(args.adb, args.serial))
    files = sorted(after - before)
    files.extend(path for path in after if path.endswith("benchmarkData.json") and path not in files)
    pulled = library_ui.pull_device_files_with_retry(
        args.adb, args.serial, sorted(set(files)), result_dir / "device-output"
    )
    traces = [path for path in pulled if path.endswith((".perfetto-trace", ".trace"))]
    if len(traces) < iterations:
        raise PhysicalReferenceError(
            f"Expected at least {iterations} physical traces, found {len(traces)}"
        )
    if method == "playbackRefreshRebuildReference" and "playback passed" not in logcat:
        raise PhysicalReferenceError("Playback/refresh/rebuild correctness marker was absent")
    summary = summarize_benchmark(result_dir, method, iterations)
    summary["memory"] = parse_memory_markers(logcat)
    summary["trace_count"] = len(traces)
    metadata = {
        "authority": DISCLAIMER,
        "device": device,
        "isolation": isolation,
        "method": method,
        "channel": args.channel,
        "iterations": iterations,
        "background_millis": args.background_millis if method == "playbackRefreshRebuildReference" else None,
        "trace_paths": traces,
        "summary_path": "summary.json",
    }
    (result_dir / "summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (result_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return {"metadata": metadata, "summary": summary}


def compare_startup(results_root: Path) -> dict:
    order = ("disabled-1", "enabled-1", "enabled-2", "disabled-2")
    summaries = {
        name: json.loads((results_root / name / "summary.json").read_text(encoding="utf-8"))
        for name in order
    }
    comparison: dict = {"authority": DISCLAIMER, "order": list(order), "metrics": {}}
    for metric in ("timeToInitialDisplayMs", "timeToFullDisplayMs"):
        disabled = [
            value
            for name in ("disabled-1", "disabled-2")
            for value in summaries[name]["startup"][metric]["raw"]
        ]
        enabled = [
            value
            for name in ("enabled-1", "enabled-2")
            for value in summaries[name]["startup"][metric]["raw"]
        ]
        disabled_median = statistics.median(disabled)
        enabled_median = statistics.median(enabled)
        comparison["metrics"][metric] = {
            "disabled_raw": disabled,
            "enabled_raw": enabled,
            "disabled_median": disabled_median,
            "enabled_median": enabled_median,
            "enabled_minus_disabled": enabled_median - disabled_median,
            "enabled_relative_to_disabled_percent":
                (enabled_median / disabled_median - 1.0) * 100.0,
            "disabled_min": min(disabled),
            "disabled_max": max(disabled),
            "enabled_min": min(enabled),
            "enabled_max": max(enabled),
        }
    (results_root / "startup-comparison.json").write_text(
        json.dumps(comparison, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return comparison


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    inspect = subparsers.add_parser("inspect")
    inspect.add_argument("--adb", default="adb")
    inspect.add_argument("--serial", required=True)
    run = subparsers.add_parser("run")
    run.add_argument("--adb", default="adb")
    run.add_argument("--serial", required=True)
    run.add_argument("--method", required=True, choices=tuple(TEST_ITERATIONS))
    run.add_argument("--channel", choices=("disabled", "enabled"))
    run.add_argument("--iterations", type=int)
    run.add_argument("--background-millis", type=int, default=120_000)
    run.add_argument("--result-dir", required=True, type=Path)
    compare = subparsers.add_parser("compare-startup")
    compare.add_argument("--results-root", required=True, type=Path)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        if args.command == "inspect":
            result = {
                "authority": DISCLAIMER,
                "device": require_physical_device(args.adb, args.serial),
                "isolation": require_isolation(args.adb, args.serial),
            }
        elif args.command == "compare-startup":
            result = compare_startup(args.results_root.resolve())
        else:
            result = run_test(args)
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (
        PhysicalReferenceError,
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
