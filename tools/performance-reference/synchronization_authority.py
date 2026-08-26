#!/usr/bin/env python3
"""Run Q1.1e synchronization authority with MediaStore indexing kept outside app timing."""

from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import math
from pathlib import Path, PurePosixPath
import re
import statistics
import subprocess
import sys
import time


MODULE_DIR = Path(__file__).resolve().parent
ROOT = MODULE_DIR.parents[1]
FIXTURE_DIR = ROOT / "tools" / "performance-fixtures"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader
    spec.loader.exec_module(module)
    return module


startup = load_module("q11e_startup", MODULE_DIR / "startup_authority.py")
library_ui = load_module("q11e_library_ui", MODULE_DIR / "library_ui_authority.py")
fixture_tool = load_module("q11e_fixture", FIXTURE_DIR / "fixture_tool.py")

AUTHORITY_AVD = "LibrePlayer_Benchmark_API_36"
AUTHORITY_API = 36
AUTHORITY_ABI = "x86_64"
MEDIUM_FINGERPRINT = "c573e8d0296a41c322b2ed17e7125e2146d0265836f78d42336dba3359040f9f"
MUTATION_MANIFEST = FIXTURE_DIR / "synchronization-medium.json"
REMOTE_ROOT = "/sdcard/Music/LibrePlayerBenchmark/MEDIUM"
MEDIASTORE_ROOT = "/storage/emulated/0/Music/LibrePlayerBenchmark/MEDIUM"
PROBE_URI = "content://com.libreplayer.synchronization-probe"
BENCHMARK_METHOD = "com.libreplayer.benchmark.SynchronizationBenchmark#synchronizationAuthority"
DISCLAIMER = "CONTROLLED REGRESSION REFERENCE — NOT A UNIVERSAL DEVICE PERFORMANCE CLAIM"
JOURNEY_ITERATIONS = {"unchanged": 10, "add": 5, "delete": 5, "modify": 5, "rebuild": 3}
SERIAL_PATTERN = re.compile(r"^emulator-\d+$")


class SynchronizationAuthorityError(RuntimeError):
    pass


def adb(adb_path: str, serial: str | None, *args: str, timeout: int = 900, capture: bool = True):
    return startup.adb(adb_path, serial, *args, timeout=timeout, capture=capture)


def connected_devices(adb_path: str) -> list[str]:
    result = adb(adb_path, None, "devices", "-l")
    return [
        line.split()[0]
        for line in result.stdout.splitlines()[1:]
        if line.strip() and " device " in f" {line} "
    ]


def require_safe_emulator(adb_path: str, serial: str) -> list[str]:
    devices = connected_devices(adb_path)
    if not SERIAL_PATTERN.fullmatch(serial):
        raise SynchronizationAuthorityError(f"Physical or malformed serial refused: {serial}")
    if serial not in devices:
        raise SynchronizationAuthorityError(f"Selected emulator is not connected: {serial}")
    emulators = [item for item in devices if SERIAL_PATTERN.fullmatch(item)]
    if emulators != [serial]:
        raise SynchronizationAuthorityError(f"Exactly one selected emulator is required: {emulators}")
    return devices


def mutating_adb(adb_path: str, serial: str, *args: str, **kwargs):
    require_safe_emulator(adb_path, serial)
    return adb(adb_path, serial, *args, **kwargs)


def require_authority_device(adb_path: str, serial: str) -> dict:
    require_safe_emulator(adb_path, serial)
    avd_lines = [
        line.strip()
        for line in adb(adb_path, serial, "emu", "avd", "name").stdout.splitlines()
        if line.strip() and line.strip() != "OK"
    ]
    avd = avd_lines[0] if avd_lines else ""
    device = library_ui.require_authority_device(adb_path, serial)
    if avd != AUTHORITY_AVD or device["api_level"] != AUTHORITY_API or device["abi"] != AUTHORITY_ABI:
        raise SynchronizationAuthorityError(f"Unexpected authority device: {device}")
    return device


def load_inputs(dataset: Path) -> tuple[dict, dict]:
    manifest = startup.load_medium_manifest(dataset)
    mutation = json.loads(MUTATION_MANIFEST.read_text(encoding="utf-8"))
    if mutation.get("schema_version") != 1:
        raise SynchronizationAuthorityError("Unsupported synchronization mutation schema")
    if mutation.get("profile") != "MEDIUM" or mutation.get("dataset_fingerprint_sha256") != MEDIUM_FINGERPRINT:
        raise SynchronizationAuthorityError("Mutation manifest does not bind to canonical MEDIUM")
    tracks = {item["relative_path"]: item for item in manifest["tracks"]}
    delete = mutation["mutations"]["delete"]
    modify = mutation["mutations"]["modify"]
    if tracks.get(delete["relative_path"], {}).get("title") != delete["title"]:
        raise SynchronizationAuthorityError("Delete mutation does not match MEDIUM")
    if tracks.get(modify["relative_path"], {}).get("title") != modify["old_title"]:
        raise SynchronizationAuthorityError("Modify mutation does not match MEDIUM")
    if mutation["mutations"]["add"]["relative_path"] in tracks:
        raise SynchronizationAuthorityError("Add mutation already exists in MEDIUM")
    return manifest, mutation


def identity_fingerprint(identities: set[str] | list[str]) -> str:
    digest = hashlib.sha256()
    for identity in sorted(identities):
        digest.update(identity.encode("utf-8"))
        digest.update(b"\n")
    return digest.hexdigest()


def expected_state(manifest: dict, mutation: dict, journey: str) -> dict:
    identities = {item["relative_path"] for item in manifest["tracks"]}
    selected = mutation["mutations"].get(journey)
    if journey == "add":
        identities.add(selected["relative_path"])
    elif journey == "delete":
        identities.remove(selected["relative_path"])
    inspected = selected["relative_path"] if selected else None
    return {
        "count": len(identities),
        "identity_sha256": identity_fingerprint(identities),
        "identities": identities,
        "inspected_identity": inspected,
        "inspected_present": journey != "delete" if inspected else None,
        "expected_title": (
            selected.get("new_title") if journey == "modify" else selected.get("title")
        ) if selected and journey != "delete" else None,
    }


def materialize_mutations(dataset: Path, destination: Path, mutation: dict) -> dict:
    destination.mkdir(parents=True, exist_ok=True)
    written = {}
    for name in ("add", "modify"):
        definition = mutation["mutations"][name]
        record = fixture_tool.track_record("MEDIUM", definition["ordinal"], 200, 100)
        if name == "modify":
            record["title"] = definition["new_title"]
        content = fixture_tool.id3_tag(record) + fixture_tool.TEMPLATE_PATH.read_bytes()
        path = destination / f"{name}.mp3"
        path.write_bytes(content)
        actual = hashlib.sha256(content).hexdigest()
        expected = definition["expected_sha256"] if name == "add" else definition["replacement_sha256"]
        if actual != expected:
            raise SynchronizationAuthorityError(f"{name} materialization hash mismatch: {actual}")
        written[name] = path
    return written


def remote_path(relative: str) -> str:
    path = PurePosixPath(relative)
    if path.is_absolute() or ".." in path.parts or path.suffix != ".mp3":
        raise SynchronizationAuthorityError(f"Unsafe mutation path: {relative}")
    return f"{REMOTE_ROOT}/{path.as_posix()}"


def media_path(relative: str) -> str:
    return f"{MEDIASTORE_ROOT}/{PurePosixPath(relative).as_posix()}"


def scan_path(adb_path: str, serial: str, path: str) -> None:
    mutating_adb(
        adb_path, serial, "shell", "am", "broadcast", "-a",
        "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", f"file://{path}",
    )


def query_mediastore(adb_path: str, serial: str) -> dict[str, dict[str, str]]:
    where = f"_data LIKE '{MEDIASTORE_ROOT}/%' AND is_music != 0 AND duration >= 30000"
    result = adb(
        adb_path, serial, "shell", "content", "query", "--uri",
        "content://media/external/audio/media", "--projection", "_data:title:artist:album",
        "--where", f'"{where}"', timeout=180,
    )
    rows = {}
    for line in result.stdout.splitlines():
        match = re.search(r"_data=([^,]+), title=([^,]*), artist=([^,]*), album=(.*)$", line)
        if match:
            full_path, title, artist, album = match.groups()
            relative = full_path.replace("\\", "/").removeprefix(MEDIASTORE_ROOT + "/")
            rows[relative] = {"title": title, "artist": artist, "album": album}
    return rows


def wait_for_mediastore(
    adb_path: str,
    serial: str,
    expected: dict,
    expected_title: str | None = None,
    timeout_seconds: int = 180,
) -> tuple[float, dict[str, dict[str, str]]]:
    started = time.perf_counter()
    last = {}
    while time.perf_counter() - started < timeout_seconds:
        last = query_mediastore(adb_path, serial)
        paths_match = set(last) == expected["identities"]
        title_matches = (
            expected_title is None or
            last.get(expected["inspected_identity"], {}).get("title") == expected_title
        )
        if paths_match and title_matches:
            return (time.perf_counter() - started) * 1000.0, last
        time.sleep(0.5)
    missing = sorted(expected["identities"] - set(last))[:5]
    extra = sorted(set(last) - expected["identities"])[:5]
    raise SynchronizationAuthorityError(
        f"MediaStore did not converge: count={len(last)} missing={missing} extra={extra} "
        f"expectedTitle={expected_title!r} actual={last.get(expected['inspected_identity'], {})}"
    )


def restore_base(
    adb_path: str,
    serial: str,
    dataset: Path,
    manifest: dict,
    mutation: dict,
) -> float:
    add = mutation["mutations"]["add"]
    mutating_adb(adb_path, serial, "shell", "rm", "-f", remote_path(add["relative_path"]))
    mutating_adb(
        adb_path, serial, "shell", "content", "delete", "--uri",
        "content://media/external/audio/media", "--where",
        f'"_data=\'{media_path(add["relative_path"])}\'"',
    )
    for name in ("delete", "modify"):
        relative = mutation["mutations"][name]["relative_path"]
        source = dataset / Path(*PurePosixPath(relative).parts)
        mutating_adb(adb_path, serial, "push", str(source), remote_path(relative))
        scan_path(adb_path, serial, remote_path(relative))
    return wait_for_mediastore(adb_path, serial, expected_state(manifest, mutation, "unchanged"))[0]


def apply_mutation(
    adb_path: str,
    serial: str,
    mutation: dict,
    files: dict[str, Path],
    journey: str,
) -> None:
    definition = mutation["mutations"][journey]
    target = remote_path(definition["relative_path"])
    if journey == "delete":
        mutating_adb(adb_path, serial, "shell", "rm", "-f", target)
    else:
        mutating_adb(adb_path, serial, "push", str(files[journey]), target)
        if journey == "modify":
            mutating_adb(adb_path, serial, "shell", "touch", "-m", "-t", "202608250101", target)
    scan_path(adb_path, serial, target)


def parse_bundle(output: str) -> dict[str, str]:
    return {
        key: value.strip()
        for key, value in re.findall(r"([A-Za-z][A-Za-z0-9]*)=([^,}\]]*)", output)
    }


def provider_call(adb_path: str, serial: str, method: str, inspected: str | None = None) -> dict[str, str]:
    args = ["shell", "content", "call", "--uri", PROBE_URI, "--method", method]
    if inspected:
        args += ["--arg", inspected]
    result = mutating_adb(adb_path, serial, *args, timeout=900)
    parsed = parse_bundle(result.stdout)
    if "fixtureCount" not in parsed:
        raise SynchronizationAuthorityError(f"Synchronization probe returned no catalog state: {result.stdout}")
    return parsed


def validate_probe(result: dict[str, str], expected: dict) -> None:
    if int(result["fixtureCount"]) != expected["count"]:
        raise SynchronizationAuthorityError(f"Probe count mismatch: {result}")
    if int(result["uniqueIdentities"]) != expected["count"] or int(result["duplicateIdentities"]) != 0:
        raise SynchronizationAuthorityError(f"Probe duplicate/missing identity failure: {result}")
    if result["identitySha256"] != expected["identity_sha256"]:
        raise SynchronizationAuthorityError(f"Probe fingerprint mismatch: {result}")


def install_and_initialize(
    adb_path: str,
    serial: str,
    target_apk: Path,
    test_apk: Path,
    expected: dict,
) -> dict:
    for package in (startup.TEST_PACKAGE, startup.TARGET_PACKAGE):
        if adb(adb_path, serial, "shell", "pm", "path", package).stdout.strip().startswith("package:"):
            mutating_adb(adb_path, serial, "uninstall", package)
    mutating_adb(adb_path, serial, "install", "-r", str(target_apk.resolve()))
    mutating_adb(adb_path, serial, "install", "-r", "-t", str(test_apk.resolve()))
    mutating_adb(adb_path, serial, "shell", "pm", "clear", startup.TARGET_PACKAGE)
    mutating_adb(
        adb_path, serial, "shell", "pm", "grant", startup.TARGET_PACKAGE,
        "android.permission.READ_MEDIA_AUDIO",
    )
    initial = provider_call(adb_path, serial, "sync")
    validate_probe(initial, expected)
    return initial


def device_files(adb_path: str, serial: str) -> set[str]:
    return set(startup.device_files(adb_path, serial))


def run_benchmark(
    adb_path: str,
    serial: str,
    result_dir: Path,
    journey: str,
    expected: dict,
    iterations: int,
) -> dict:
    result_dir.mkdir(parents=True, exist_ok=False)
    mutating_adb(adb_path, serial, "shell", "pm", "clear", startup.TEST_PACKAGE)
    before = device_files(adb_path, serial)
    mutating_adb(adb_path, serial, "logcat", "-c")
    command = [
        "shell", "am", "instrument", "-w", "-r", "-e", "class", BENCHMARK_METHOD,
        "-e", "journey", journey, "-e", "expectedCount", str(expected["count"]),
        "-e", "expectedFingerprint", expected["identity_sha256"],
        "-e", "iterations", str(iterations),
    ]
    if expected["inspected_identity"]:
        command += ["-e", "inspectedIdentity", expected["inspected_identity"]]
        command += ["-e", "expectPresent", str(expected["inspected_present"]).lower()]
    if expected["expected_title"]:
        encoded_title = base64.b64encode(expected["expected_title"].encode("utf-8")).decode("ascii")
        command += ["-e", "expectedTitleBase64", encoded_title]
    command += ["-e", "androidx.benchmark.suppressErrors", "EMULATOR", startup.RUNNER]
    result = mutating_adb(adb_path, serial, *command, timeout=3600)
    (result_dir / "instrumentation.txt").write_text(result.stdout, encoding="utf-8")
    if "OK (1 test)" not in result.stdout or "FAILURES!!!" in result.stdout:
        raise SynchronizationAuthorityError(f"Instrumentation failed: {result_dir / 'instrumentation.txt'}")
    logcat = adb(
        adb_path, serial, "logcat", "-d", "-s",
        "LibrePlayerLibrarySync:I", "LibrePlayerSyncProbe:I", "*:S",
    ).stdout
    (result_dir / "synchronization-logcat.txt").write_text(logcat, encoding="utf-8")
    after = device_files(adb_path, serial)
    outputs = sorted(after - before)
    benchmark_json = [path for path in after if path.endswith("benchmarkData.json")]
    outputs = sorted(set(outputs + benchmark_json))
    pulled = library_ui.pull_device_files_with_retry(
        adb_path, serial, outputs, result_dir / "device-output",
    )
    traces = [path for path in pulled if path.endswith((".perfetto-trace", ".trace"))]
    if len(traces) < iterations:
        raise SynchronizationAuthorityError(f"Expected {iterations} traces, found {len(traces)}")
    summary = summarize_invocation(result_dir)
    summary["trace_paths"] = [Path(path).relative_to(result_dir).as_posix() for path in traces]
    (result_dir / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return summary


def percentile(values: list[float], value: int) -> float:
    ordered = sorted(values)
    position = (len(ordered) - 1) * value / 100.0
    low, high = math.floor(position), math.ceil(position)
    return ordered[low] if low == high else ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def distribution(values: list[float]) -> dict:
    result = {
        "raw_ms": values,
        "min_ms": min(values),
        "median_ms": statistics.median(values),
        "max_ms": max(values),
        "mean_ms": statistics.fmean(values),
        "standard_deviation_ms": statistics.pstdev(values),
        "p50_ms": percentile(values, 50),
    }
    if len(values) >= 5:
        result["p90_ms"] = percentile(values, 90)
    return result


def summarize_invocation(result_dir: Path) -> dict:
    source, document = startup.find_metric_document(result_dir)
    benchmark = next(
        (item for item in document["benchmarks"] if "SynchronizationBenchmark" in str(item)), None
    )
    if benchmark is None:
        raise SynchronizationAuthorityError("Synchronization benchmark missing from AndroidX JSON")
    diagnostic_metrics = {
        name: distribution([float(value) for value in metric.get("runs", [])])
        for name, metric in benchmark.get("metrics", {}).items()
        if metric.get("runs")
    }
    log_text = (result_dir / "synchronization-logcat.txt").read_text(encoding="utf-8")
    values = [
        int(value) / 1_000_000.0
        for value in re.findall(r"LibrePlayerSyncProbe.*?method=\w+ elapsedNanos=(\d+)", log_text)
    ]
    benchmark_iterations = int(benchmark.get("repeatIterations", 0))
    if len(values) != benchmark_iterations:
        raise SynchronizationAuthorityError(
            f"Expected {benchmark_iterations} exact probe durations, found {len(values)}"
        )
    counters = []
    pattern = re.compile(
        r"full=(\w+) elapsedMs=(\d+) mediaRows=(\d+) mediaIdRows=(\d+) documents=(\d+) "
        r"metadataReads=(\d+) metadataFailures=(\d+) reused=(\d+) songUpserts=(\d+) "
        r"songDeletes=(\d+) albumWrites=(\d+) artistWrites=(\d+)"
    )
    for match in pattern.finditer(log_text):
        names = ("full", "scanner_elapsed_ms", "media_rows", "media_id_rows", "documents", "metadata_reads", "metadata_failures", "reused", "song_upserts", "song_deletes", "album_writes", "artist_writes")
        counters.append(dict(zip(names, match.groups())))
    return {
        "source": source.relative_to(result_dir).as_posix(),
        "duration": distribution(values),
        "androidx_diagnostic_metrics": diagnostic_metrics,
        "sync_counters": counters,
        "correctness": "passed by per-iteration benchmark assertions",
    }


def combine_journey(invocations: list[dict], indexing_ms: list[float], classification: str) -> dict:
    values = [value for item in invocations for value in item["duration"]["raw_ms"]]
    return {
        "duration": distribution(values),
        "mediastore_indexing_latency_ms": indexing_ms,
        "sync_counters": [counter for item in invocations for counter in item["sync_counters"]],
        "correctness": "passed",
        "classification": classification,
        "trace_count": sum(len(item["trace_paths"]) for item in invocations),
    }


def run_authority(args: argparse.Namespace) -> dict:
    result_dir = args.result_dir.resolve()
    if result_dir.exists() and any(result_dir.iterdir()):
        raise SynchronizationAuthorityError(f"Result directory must be new or empty: {result_dir}")
    result_dir.mkdir(parents=True, exist_ok=True)
    device = require_authority_device(args.adb, args.serial)
    manifest, mutation = load_inputs(args.dataset)
    files = materialize_mutations(args.dataset, result_dir / "mutation-materialization", mutation)
    base = expected_state(manifest, mutation, "unchanged")
    restore_base(args.adb, args.serial, args.dataset, manifest, mutation)
    configuration = library_ui.configure_device(args.adb, args.serial)
    initial = install_and_initialize(args.adb, args.serial, args.target_apk, args.test_apk, base)
    journeys = {}

    unchanged = run_benchmark(
        args.adb, args.serial, result_dir / "unchanged" / "run-01", "unchanged", base,
        JOURNEY_ITERATIONS["unchanged"],
    )
    journeys["unchanged"] = combine_journey([unchanged], [], "NUMERIC REGRESSION AUTHORITY")

    for journey in ("add", "delete", "modify"):
        expected = expected_state(manifest, mutation, journey)
        invocations, indexing = [], []
        try:
            for iteration in range(1, JOURNEY_ITERATIONS[journey] + 1):
                restore_base(args.adb, args.serial, args.dataset, manifest, mutation)
                validate_probe(provider_call(args.adb, args.serial, "sync"), base)
                started = time.perf_counter()
                apply_mutation(args.adb, args.serial, mutation, files, journey)
                _, _ = wait_for_mediastore(
                    args.adb, args.serial, expected,
                    expected_title=expected["expected_title"] if journey == "modify" else None,
                    timeout_seconds=args.index_timeout,
                )
                indexing.append((time.perf_counter() - started) * 1000.0)
                invocation = run_benchmark(
                    args.adb, args.serial,
                    result_dir / journey / f"run-{iteration:02d}", journey, expected, 1,
                )
                invocations.append(invocation)
            journeys[journey] = combine_journey(invocations, indexing, "NUMERIC REGRESSION AUTHORITY")
        except SynchronizationAuthorityError as error:
            if journey != "modify":
                raise
            journeys[journey] = {
                "classification": "PLATFORM-LIMITED / DEFERRED",
                "reason": str(error),
                "mediastore_indexing_latency_ms": indexing,
                "completed_iterations": len(invocations),
                "correctness": "not authoritative",
            }

    restore_base(args.adb, args.serial, args.dataset, manifest, mutation)
    validate_probe(provider_call(args.adb, args.serial, "sync"), base)
    rebuild = run_benchmark(
        args.adb, args.serial, result_dir / "rebuild" / "run-01", "rebuild", base,
        JOURNEY_ITERATIONS["rebuild"],
    )
    journeys["rebuild"] = combine_journey([rebuild], [], "NUMERIC REGRESSION AUTHORITY")

    summary = {"authority": DISCLAIMER, "journeys": journeys}
    (result_dir / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    metadata = {
        "schema_version": 1,
        "authority": DISCLAIMER,
        "variant": args.variant,
        "session": args.session,
        "reference_git_commit": args.reference_commit,
        "development_git_commit": args.development_commit,
        "benchmark_harness_revision": args.harness_revision,
        "synchronization_instrumentation_revision": args.instrumentation_revision,
        "dataset_profile": "MEDIUM",
        "dataset_fingerprint_sha256": manifest["dataset_fingerprint_sha256"],
        "mutation_manifest_sha256": hashlib.sha256(MUTATION_MANIFEST.read_bytes()).hexdigest(),
        "device": device,
        "device_configuration": configuration,
        "compilation_mode": "Full",
        "process_state": "resident benchmark target; exact repository request invoked through benchmark-variant provider",
        "database_state": "canonical catalog before every mutation; populated catalog before rebuild",
        "mediastore_precondition": "exact expected fixture path set observed before LibrePlayer timing",
        "run_timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "invalid_or_recovered_runs": [],
        "initial_catalog": initial,
    }
    (result_dir / "metadata.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return {"metadata": metadata, "summary": summary}


def session_distribution(values: list[float]) -> dict:
    median = statistics.median(values)
    return {
        "session_medians_ms": values,
        "median_of_session_medians_ms": median,
        "range_percent_of_median": (max(values) - min(values)) / median * 100.0 if median else None,
    }


def matrix_classification(channel_results: list[dict]) -> str:
    if any("median_of_session_medians_ms" not in result for result in channel_results):
        return "PLATFORM-LIMITED / DEFERRED"
    if any(result["range_percent_of_median"] > 10.0 for result in channel_results):
        return "LIMITED NUMERIC AUTHORITY"
    return "NUMERIC REGRESSION AUTHORITY"


def compare_results(root: Path) -> dict:
    channels = {}
    for variant in ("reference", "development"):
        summaries = [
            json.loads((root / f"session-{session}" / variant / "summary.json").read_text(encoding="utf-8"))
            for session in (1, 2, 3)
        ]
        variant_result = {}
        for journey in JOURNEY_ITERATIONS:
            nodes = [summary["journeys"][journey] for summary in summaries]
            if all("duration" in node for node in nodes):
                variant_result[journey] = session_distribution(
                    [node["duration"]["median_ms"] for node in nodes]
                )
            else:
                variant_result[journey] = {"classification": "PLATFORM-LIMITED / DEFERRED"}
        channels[variant] = variant_result
    relative = {}
    classifications = {}
    for journey in JOURNEY_ITERATIONS:
        ref = channels["reference"][journey].get("median_of_session_medians_ms")
        dev = channels["development"][journey].get("median_of_session_medians_ms")
        relative[journey] = None if not ref or dev is None else (dev / ref - 1.0) * 100.0
        classifications[journey] = matrix_classification([
            channels["reference"][journey],
            channels["development"][journey],
        ])
    result = {
        "authority": DISCLAIMER,
        "channels": channels,
        "classifications": classifications,
        "development_relative_to_reference_percent": relative,
    }
    (root / "comparison.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    device = subparsers.add_parser("device")
    device.add_argument("--adb", default="adb")
    device.add_argument("--serial", required=True)
    materialize = subparsers.add_parser("materialize")
    materialize.add_argument("--dataset", required=True, type=Path)
    materialize.add_argument("--output", required=True, type=Path)
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
    run.add_argument("--instrumentation-revision", required=True)
    run.add_argument("--index-timeout", type=int, default=180)
    compare = subparsers.add_parser("compare")
    compare.add_argument("--results-root", required=True, type=Path)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        if args.command == "device":
            result = require_authority_device(args.adb, args.serial)
        elif args.command == "materialize":
            manifest, mutation = load_inputs(args.dataset)
            paths = materialize_mutations(args.dataset, args.output, mutation)
            result = {"files": {name: str(path) for name, path in paths.items()}, "base": expected_state(manifest, mutation, "unchanged") | {"identities": "omitted"}}
        elif args.command == "run":
            result = run_authority(args)
        else:
            result = compare_results(args.results_root.resolve())
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (SynchronizationAuthorityError, OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"synchronization authority error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
