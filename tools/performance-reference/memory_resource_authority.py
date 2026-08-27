#!/usr/bin/env python3
"""Run and analyze Q1.1g memory/resource authority on the controlled API 36 AVD."""

from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import re
import statistics


MODULE_DIR = Path(__file__).resolve().parent
ROOT = MODULE_DIR.parents[1]


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader
    spec.loader.exec_module(module)
    return module


sync = load_module("q11g_sync", MODULE_DIR / "synchronization_authority.py")
startup = sync.startup
library_ui = sync.library_ui

DISCLAIMER = "CONTROLLED REGRESSION REFERENCE — NOT A UNIVERSAL DEVICE MEMORY REQUIREMENT"
EXPECTED_FINGERPRINT = "c573e8d0296a41c322b2ed17e7125e2146d0265836f78d42336dba3359040f9f"
BENCHMARK_METHOD = "com.libreplayer.benchmark.MemoryResourceBenchmark#memoryResourceAuthority"
SESSION_ORDER = {1: ("reference", "development"), 2: ("development", "reference"), 3: ("reference", "development")}
JOURNEYS = ("idle", "songs", "albums", "search", "playback")
EXPECTED_CHECKPOINTS = {"idle": 6, "songs": 12, "albums": 12, "search": 7, "playback": 7}


class MemoryResourceError(RuntimeError):
    pass


def first_int(pattern: str, text: str, label: str) -> int:
    match = re.search(pattern, text, re.MULTILINE)
    if not match:
        raise MemoryResourceError(f"Missing {label} in checkpoint")
    return int(match.group(1))


def parse_checkpoint(raw: str) -> dict:
    values = {}
    for key in ("journey", "label", "elapsedRealtimeMs"):
        match = re.search(rf"^{key}=(.+)$", raw, re.MULTILINE)
        if not match:
            raise MemoryResourceError(f"Missing {key} in checkpoint")
        values[key] = match.group(1).strip()
    values["elapsed_realtime_ms"] = int(values.pop("elapsedRealtimeMs"))
    total = re.search(r"TOTAL PSS:\s*(\d+)\s+TOTAL RSS:\s*(\d+)\s+TOTAL SWAP PSS:\s*(\d+)", raw)
    if not total:
        raise MemoryResourceError("Missing API 36 TOTAL PSS/RSS line")
    values.update(total_pss_kb=int(total.group(1)), total_rss_kb=int(total.group(2)), swap_pss_kb=int(total.group(3)))
    summary_fields = {
        "java_heap_kb": "Java Heap", "native_heap_kb": "Native Heap", "code_kb": "Code",
        "stack_kb": "Stack", "graphics_kb": "Graphics", "private_other_kb": "Private Other",
        "system_kb": "System",
    }
    for key, label in summary_fields.items():
        values[key] = first_int(rf"^\s*{re.escape(label)}:\s*(\d+)", raw, label)
    object_patterns = {
        "views": r"Views:\s*(\d+)", "view_roots": r"ViewRootImpl:\s*(\d+)",
        "app_contexts": r"AppContexts:\s*(\d+)", "activities": r"Activities:\s*(\d+)",
        "assets": r"Assets:\s*(\d+)", "asset_managers": r"AssetManagers:\s*(\d+)",
        "local_binders": r"Local Binders:\s*(\d+)", "proxy_binders": r"Proxy Binders:\s*(\d+)",
        "parcel_memory_kb": r"Parcel memory:\s*(\d+)", "parcel_count": r"Parcel count:\s*(\d+)",
        "death_recipients": r"Death Recipients:\s*(\d+)", "webviews": r"WebViews:\s*(\d+)",
        "threads": r"^Threads:\s*(\d+)",
    }
    for key, pattern in object_patterns.items():
        values[key] = first_int(pattern, raw, key)
    bitmap = re.search(r"Bitmap \(malloced\):\s*(\d+)", raw)
    values["bitmap_count"] = int(bitmap.group(1)) if bitmap else None
    fd = re.search(r"^fdProbe=(.*)$", raw, re.MULTILINE)
    values["fd_count"] = int(fd.group(1)) if fd and fd.group(1).isdigit() else None
    values["fd_probe"] = fd.group(1) if fd else "missing"
    values["media_session_count"] = len(re.findall(r"package=com\.libreplayer", raw))
    controllers = re.findall(r"controllers:\s*(\d+)", raw)
    values["media_session_controllers"] = int(controllers[-1]) if controllers else 0
    queue = re.findall(r"queueTitle=.*?size=(\d+)", raw)
    values["media_session_queue_size"] = int(queue[-1]) if queue else 0
    values["playback_active"] = "state=PLAYING(3)" in raw
    values["playback_service_count"] = len(re.findall(r"ServiceRecord\{.*com\.libreplayer", raw))
    values["playback_service_foreground"] = "isForeground=true" in raw
    values["raw"] = raw
    return values


def extract_checkpoints(log_text: str) -> list[dict]:
    encoded = re.findall(r"LibrePlayerMemory.*?checkpoint=([A-Za-z0-9+/=]+)", log_text)
    checkpoints = []
    for value in encoded:
        try:
            raw = base64.b64decode(value, validate=True).decode("utf-8")
        except (ValueError, UnicodeDecodeError) as error:
            raise MemoryResourceError(f"Invalid checkpoint encoding: {error}") from error
        checkpoints.append(parse_checkpoint(raw))
    return checkpoints


def slope(values: list[float]) -> float:
    if len(values) < 2:
        return 0.0
    x_mean = (len(values) - 1) / 2
    y_mean = statistics.mean(values)
    denominator = sum((index - x_mean) ** 2 for index in range(len(values)))
    return sum((index - x_mean) * (value - y_mean) for index, value in enumerate(values)) / denominator


def growth(checkpoints: list[dict], metric: str) -> dict:
    values = [int(item[metric]) for item in checkpoints]
    baseline, final = values[0], values[-1]
    activity = values[1:-1] or values[1:]
    midpoint = max(1, len(activity) // 2)
    first_half = activity[:midpoint]
    second_half = activity[midpoint:] or activity[-1:]
    first_median = statistics.median(first_half)
    second_median = statistics.median(second_half)
    threshold = max(8192 if metric.endswith("_kb") else 3, baseline * 0.08)
    if final - baseline > threshold and second_median - first_median > threshold and slope(activity) > 0:
        behavior = "ratchet"
    elif abs(final - (activity[0] if activity else baseline)) <= threshold or second_median <= first_median + threshold:
        behavior = "warm/plateau"
    else:
        behavior = "oscillation"
    return {
        "raw": values, "baseline": baseline, "first": activity[0] if activity else baseline,
        "last": activity[-1] if activity else baseline, "final": final, "net": final - baseline,
        "peak": max(values), "first_half_median": first_median, "second_half_median": second_median,
        "diagnostic_slope_per_checkpoint": slope(activity), "classification": behavior,
        "classification_tolerance": threshold,
    }


def summarize(result_dir: Path, journey: str) -> dict:
    growth_metrics = ("total_pss_kb", "total_rss_kb", "threads", "local_binders", "proxy_binders", "parcel_count", "bitmap_count")
    log_text = (result_dir / "logcat.txt").read_text(encoding="utf-8")
    checkpoints = extract_checkpoints(log_text)
    if len(checkpoints) != EXPECTED_CHECKPOINTS[journey]:
        raise MemoryResourceError(f"{journey}: expected {EXPECTED_CHECKPOINTS[journey]} checkpoints, found {len(checkpoints)}")
    if any(item["journey"] != journey for item in checkpoints):
        raise MemoryResourceError(f"{journey}: cross-journey checkpoint contamination")
    if journey == "playback":
        active = checkpoints[:-1]
        if not all(item["playback_active"] and item["playback_service_foreground"] and item["media_session_queue_size"] == 20 for item in active):
            raise MemoryResourceError("Playback/session/service correctness failed")
        if checkpoints[-1]["playback_active"]:
            raise MemoryResourceError("Playback remained active after stop")
    source, document = startup.find_metric_document(result_dir)
    benchmark = next((item for item in document["benchmarks"] if "MemoryResourceBenchmark" in str(item)), None)
    if benchmark is None:
        raise MemoryResourceError("MemoryResourceBenchmark missing from AndroidX result")
    peak_metrics = {
        name: metric.get("runs", []) for name, metric in benchmark.get("metrics", {}).items()
        if metric.get("runs")
    }
    return {
        "checkpoint_count": len(checkpoints), "checkpoints": checkpoints,
        "growth": {metric: growth(checkpoints, metric) for metric in growth_metrics if all(item[metric] is not None for item in checkpoints)},
        "androidx_peak_metrics": peak_metrics, "androidx_source": source.relative_to(result_dir).as_posix(),
        "fd_authority": "unavailable: API 36 shell returned Permission denied for /proc/<pid>/fd",
    }


def run_journey(adb_path: str, serial: str, result_dir: Path, journey: str) -> dict:
    result_dir.mkdir(parents=True, exist_ok=False)
    sync.mutating_adb(adb_path, serial, "shell", "pm", "clear", startup.TEST_PACKAGE)
    before = set(startup.device_files(adb_path, serial))
    sync.mutating_adb(adb_path, serial, "logcat", "-c")
    command = ["shell", "am", "instrument", "-w", "-r", "-e", "class", BENCHMARK_METHOD,
               "-e", "journey", journey, "-e", "androidx.benchmark.suppressErrors", "EMULATOR", startup.RUNNER]
    result = sync.mutating_adb(adb_path, serial, *command, timeout=900)
    (result_dir / "instrumentation.txt").write_text(result.stdout, encoding="utf-8")
    if "OK (1 test)" not in result.stdout or "FAILURES!!!" in result.stdout:
        raise MemoryResourceError(f"Instrumentation failed: {result_dir / 'instrumentation.txt'}")
    logcat = sync.adb(adb_path, serial, "logcat", "-d", "-s", "LibrePlayerMemory:I", "*:S").stdout
    (result_dir / "logcat.txt").write_text(logcat, encoding="utf-8")
    after = set(startup.device_files(adb_path, serial))
    outputs = sorted((after - before) | {path for path in after if path.endswith("benchmarkData.json")})
    pulled = library_ui.pull_device_files_with_retry(adb_path, serial, outputs, result_dir / "device-output")
    traces = [path for path in pulled if path.endswith((".perfetto-trace", ".trace"))]
    if not traces:
        raise MemoryResourceError(f"No Perfetto trace preserved for {journey}")
    result_summary = summarize(result_dir, journey)
    result_summary["trace_paths"] = [Path(path).relative_to(result_dir).as_posix() for path in traces]
    (result_dir / "summary.json").write_text(json.dumps(result_summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result_summary


def run(args: argparse.Namespace) -> dict:
    result_dir = args.result_dir.resolve()
    if result_dir.exists() and any(result_dir.iterdir()):
        raise MemoryResourceError(f"Result directory must be new or empty: {result_dir}")
    result_dir.mkdir(parents=True, exist_ok=True)
    device = sync.require_authority_device(args.adb, args.serial)
    expected_order = SESSION_ORDER[args.session]
    if expected_order[args.side - 1] != args.variant:
        raise MemoryResourceError("Variant does not match the balanced session order")
    manifest, mutation = sync.load_inputs(args.dataset)
    if manifest["profile"] != "MEDIUM" or len(manifest["tracks"]) != 2000 or manifest["dataset_fingerprint_sha256"] != EXPECTED_FINGERPRINT:
        raise MemoryResourceError("Dataset is not canonical MEDIUM")
    expected = sync.expected_state(manifest, mutation, "unchanged")
    sync.restore_base(args.adb, args.serial, args.dataset, manifest, mutation)
    configuration = library_ui.configure_device(args.adb, args.serial)
    initial = sync.install_and_initialize(args.adb, args.serial, args.target_apk, args.test_apk, expected)
    summaries = {journey: run_journey(args.adb, args.serial, result_dir / journey, journey) for journey in JOURNEYS}
    metadata = {
        "schema_version": 1, "authority": DISCLAIMER, "variant": args.variant,
        "session": args.session, "side": args.side, "expected_session_order": list(expected_order),
        "reference_git_commit": args.reference_commit, "development_git_commit": args.development_commit,
        "benchmark_harness_revision": args.harness_revision, "dataset_profile": "MEDIUM",
        "dataset_track_count": 2000, "dataset_fingerprint_sha256": EXPECTED_FINGERPRINT,
        "device": device, "device_configuration": configuration, "initial_catalog": initial,
        "target_apk_bytes": args.target_apk.stat().st_size, "test_apk_bytes": args.test_apk.stat().st_size,
        "settle_semantics": "2.5 seconds after baseline/cycles; final 5 seconds; natural settling only; no force-GC or trim",
        "run_timestamp_utc": datetime.now(timezone.utc).isoformat(),
    }
    result = {"metadata": metadata, "journeys": summaries}
    (result_dir / "authority.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def compare(results_root: Path) -> dict:
    documents = [json.loads(path.read_text(encoding="utf-8")) for path in results_root.glob("session-*/*/authority.json")]
    if len(documents) != 6:
        raise MemoryResourceError(f"Expected six balanced side documents, found {len(documents)}")
    result = {"authority": DISCLAIMER, "sessions": 3, "sides": 6, "journeys": {}}
    for journey in JOURNEYS:
        channels = {}
        for variant in ("reference", "development"):
            sides = [item for item in documents if item["metadata"]["variant"] == variant]
            pss_net = [item["journeys"][journey]["growth"]["total_pss_kb"]["net"] for item in sides]
            rss_net = [item["journeys"][journey]["growth"]["total_rss_kb"]["net"] for item in sides]
            channels[variant] = {
                "pss_net_kb": pss_net, "pss_net_median_kb": statistics.median(pss_net),
                "rss_net_kb": rss_net, "rss_net_median_kb": statistics.median(rss_net),
                "behaviors": [item["journeys"][journey]["growth"]["total_pss_kb"]["classification"] for item in sides],
                "peak_metrics": [item["journeys"][journey]["androidx_peak_metrics"] for item in sides],
            }
        result["journeys"][journey] = channels
    result["apk_sizes"] = {
        variant: sorted({item["metadata"]["target_apk_bytes"] for item in documents if item["metadata"]["variant"] == variant})
        for variant in ("reference", "development")
    }
    (results_root / "comparison.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    sub = root.add_subparsers(dest="command", required=True)
    run_cmd = sub.add_parser("run")
    for name in ("adb", "serial", "variant", "reference_commit", "development_commit", "harness_revision"):
        run_cmd.add_argument(f"--{name.replace('_', '-')}", required=True)
    for name in ("dataset", "target_apk", "test_apk", "result_dir"):
        run_cmd.add_argument(f"--{name.replace('_', '-')}", required=True, type=Path)
    run_cmd.add_argument("--session", required=True, type=int, choices=(1, 2, 3))
    run_cmd.add_argument("--side", required=True, type=int, choices=(1, 2))
    compare_cmd = sub.add_parser("compare")
    compare_cmd.add_argument("--results-root", required=True, type=Path)
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        result = run(args) if args.command == "run" else compare(args.results_root.resolve())
        print(json.dumps(result if args.command == "compare" else result["metadata"], indent=2, sort_keys=True))
        return 0
    except (MemoryResourceError, OSError, ValueError) as error:
        print(f"memory/resource authority error: {error}", file=__import__("sys").stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
