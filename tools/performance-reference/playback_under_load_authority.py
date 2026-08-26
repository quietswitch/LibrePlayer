#!/usr/bin/env python3
"""Run Q1.1f playback-under-load authority on the controlled API 36 emulator."""

from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import statistics
import subprocess
import sys


MODULE_DIR = Path(__file__).resolve().parent
ROOT = MODULE_DIR.parents[1]
FIXTURE_DIR = ROOT / "tools" / "performance-fixtures"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader
    spec.loader.exec_module(module)
    return module


sync = load_module("q11f_sync", MODULE_DIR / "synchronization_authority.py")
startup = sync.startup
library_ui = sync.library_ui

DISCLAIMER = "CONTROLLED REGRESSION REFERENCE — NOT A UNIVERSAL DEVICE OR ACOUSTIC PERFORMANCE CLAIM"
PLAYBACK_FIXTURE_IDENTITY = "audio/artist-00010/album-00010/disc-01/track-00010.mp3"
PLAYBACK_FIXTURE_TITLE = "Track 00010"
PLAYBACK_FIXTURE_SHA256 = "e36b35ecf92aad64312fd4f3507a1fd2d72ac36f0190c50887c7b30c6d0dbb85"
PLAYBACK_FIXTURE_DURATION_MS = 31_176
PROBE_URI = "content://com.libreplayer.playback-load-probe"
BENCHMARK_METHOD = "com.libreplayer.benchmark.PlaybackUnderLoadBenchmark#playbackUnderLoadAuthority"
JOURNEY_ITERATIONS = {"unchanged": 10, "add": 5, "rebuild": 5}
SESSION_ORDER = {
    1: ("reference", "development"),
    2: ("development", "reference"),
    3: ("reference", "development"),
}
MINIMUM_OBSERVATION_NANOS = 1_800_000_000
MINIMUM_POSITION_ADVANCEMENT_MS = 1_000
READY = 3
NO_SUPPRESSION = 0


class PlaybackAuthorityError(RuntimeError):
    pass


def verify_playback_fixture(dataset: Path, manifest: dict) -> dict:
    matches = [item for item in manifest["tracks"] if item["relative_path"] == PLAYBACK_FIXTURE_IDENTITY]
    if len(matches) != 1 or matches[0].get("title") != PLAYBACK_FIXTURE_TITLE:
        raise PlaybackAuthorityError("Playback fixture identity/title is not uniquely canonical")
    fixture = dataset / Path(*PLAYBACK_FIXTURE_IDENTITY.split("/"))
    actual_hash = hashlib.sha256(fixture.read_bytes()).hexdigest()
    if actual_hash != PLAYBACK_FIXTURE_SHA256:
        raise PlaybackAuthorityError(f"Playback fixture hash mismatch: {actual_hash}")
    mutation = json.loads(sync.MUTATION_MANIFEST.read_text(encoding="utf-8"))["mutations"]
    mutation_targets = {item["relative_path"] for item in mutation.values()}
    if PLAYBACK_FIXTURE_IDENTITY in mutation_targets:
        raise PlaybackAuthorityError("Playback fixture overlaps a synchronization mutation target")
    return {
        "relative_path": PLAYBACK_FIXTURE_IDENTITY,
        "title": PLAYBACK_FIXTURE_TITLE,
        "artist": matches[0]["artist"],
        "album": matches[0]["album"],
        "sha256": actual_hash,
        "codec": "MP3",
        "duration_ms": PLAYBACK_FIXTURE_DURATION_MS,
        "minimum_duration_ms": 30_000,
        "mutation_target": False,
    }


def parse_bool(value) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str) and value.lower() in ("true", "false"):
        return value.lower() == "true"
    raise PlaybackAuthorityError(f"Expected boolean, got {value!r}")


def parse_record(encoded: str) -> dict:
    try:
        return json.loads(base64.b64decode(encoded, validate=True).decode("utf-8"))
    except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PlaybackAuthorityError(f"Invalid playback observation record: {error}") from error


def validate_record(record: dict, expected: dict, method: str) -> None:
    if record.get("method") != method:
        raise PlaybackAuthorityError(f"Unexpected observation method: {record.get('method')}")
    if record.get("playbackFixtureIdentity") != PLAYBACK_FIXTURE_IDENTITY:
        raise PlaybackAuthorityError("Observed playback fixture identity changed")
    before_media = record.get("beforeMediaId")
    if not before_media or before_media != record.get("afterMediaId"):
        raise PlaybackAuthorityError("Media item changed during library load")
    for prefix in ("before", "after"):
        if not parse_bool(record[f"{prefix}Connected"]):
            raise PlaybackAuthorityError(f"Session disconnected at {prefix}")
        if int(record[f"{prefix}PlaybackState"]) != READY:
            raise PlaybackAuthorityError(f"Playback was not READY at {prefix}")
        if not parse_bool(record[f"{prefix}PlayWhenReady"]):
            raise PlaybackAuthorityError(f"playWhenReady false at {prefix}")
        if not parse_bool(record[f"{prefix}IsPlaying"]):
            raise PlaybackAuthorityError(f"isPlaying false at {prefix}")
        if int(record[f"{prefix}SuppressionReason"]) != NO_SUPPRESSION:
            raise PlaybackAuthorityError(f"Playback suppressed at {prefix}")
        if parse_bool(record[f"{prefix}HasPlayerError"]):
            raise PlaybackAuthorityError(f"Player error present at {prefix}")
        if float(record[f"{prefix}Speed"]) <= 0:
            raise PlaybackAuthorityError(f"Invalid playback speed at {prefix}")
    if int(record["afterMonotonicNanos"]) <= int(record["beforeMonotonicNanos"]):
        raise PlaybackAuthorityError("Playback snapshots are not monotonic")
    if int(record["observationElapsedNanos"]) < MINIMUM_OBSERVATION_NANOS:
        raise PlaybackAuthorityError("Playback observation window was too short")
    if int(record["positionAdvancementMs"]) < MINIMUM_POSITION_ADVANCEMENT_MS:
        raise PlaybackAuthorityError("Playback position did not advance sufficiently")
    if int(record["synchronizationElapsedNanos"]) <= 0:
        raise PlaybackAuthorityError("Repository synchronization duration is not positive")
    for key in ("playerErrors", "mediaTransitions", "positionDiscontinuities", "sessionDisconnects"):
        if int(record[key]) != 0:
            raise PlaybackAuthorityError(f"Playback continuity event failure: {key}={record[key]}")
    if int(record["fixtureCount"]) != expected["count"]:
        raise PlaybackAuthorityError("Catalog count mismatch")
    if int(record["uniqueIdentities"]) != expected["count"] or int(record["duplicateIdentities"]) != 0:
        raise PlaybackAuthorityError("Catalog uniqueness mismatch")
    if record["identitySha256"] != expected["identity_sha256"]:
        raise PlaybackAuthorityError("Catalog identity fingerprint mismatch")
    if expected["inspected_identity"]:
        if parse_bool(record["inspectedPresent"]) != expected["inspected_present"]:
            raise PlaybackAuthorityError("Inspected identity presence mismatch")
        if expected["expected_title"] and record.get("inspectedTitle") != expected["expected_title"]:
            raise PlaybackAuthorityError("Inspected identity title mismatch")


def playback_provider_call(adb_path: str, serial: str, method: str) -> dict[str, str]:
    result = sync.mutating_adb(
        adb_path, serial, "shell", "content", "call", "--uri", PROBE_URI,
        "--method", method, timeout=180,
    )
    parsed = sync.parse_bundle(result.stdout)
    expected_key = "stopped" if method == "stop" else "prepared"
    if parsed.get(expected_key) != "true":
        raise PlaybackAuthorityError(f"Playback provider {method} failed: {result.stdout}")
    return parsed


def extract_records(log_text: str) -> list[dict]:
    encoded = re.findall(r"LibrePlayerPlaybackLoad.*?record=([A-Za-z0-9+/=]+)", log_text)
    return [parse_record(item) for item in encoded]


def summarize_invocation(result_dir: Path, expected: dict, method: str) -> dict:
    source, document = startup.find_metric_document(result_dir)
    benchmark = next(
        (item for item in document["benchmarks"] if "PlaybackUnderLoadBenchmark" in str(item)), None
    )
    if benchmark is None:
        raise PlaybackAuthorityError("Playback-under-load benchmark missing from AndroidX JSON")
    iterations = int(benchmark.get("repeatIterations", 0))
    log_text = (result_dir / "playback-load-logcat.txt").read_text(encoding="utf-8")
    records = extract_records(log_text)
    if len(records) != iterations:
        raise PlaybackAuthorityError(f"Expected {iterations} playback records, found {len(records)}")
    for record in records:
        validate_record(record, expected, method)
    duration_ms = [int(item["synchronizationElapsedNanos"]) / 1_000_000.0 for item in records]
    advancement_ms = [float(item["positionAdvancementMs"]) for item in records]
    observation_ms = [int(item["observationElapsedNanos"]) / 1_000_000.0 for item in records]
    diagnostic_metrics = {
        name: sync.distribution([float(value) for value in metric.get("runs", [])])
        for name, metric in benchmark.get("metrics", {}).items()
        if metric.get("runs")
    }
    counter_pattern = re.compile(
        r"full=(\w+) elapsedMs=(\d+) mediaRows=(\d+) mediaIdRows=(\d+) documents=(\d+) "
        r"metadataReads=(\d+) metadataFailures=(\d+) reused=(\d+) songUpserts=(\d+) "
        r"songDeletes=(\d+) albumWrites=(\d+) artistWrites=(\d+)"
    )
    names = (
        "full", "scanner_elapsed_ms", "media_rows", "media_id_rows", "documents",
        "metadata_reads", "metadata_failures", "reused", "song_upserts", "song_deletes",
        "album_writes", "artist_writes",
    )
    return {
        "source": source.relative_to(result_dir).as_posix(),
        "synchronization_duration": sync.distribution(duration_ms),
        "position_advancement": sync.distribution(advancement_ms),
        "observation_duration": sync.distribution(observation_ms),
        "androidx_diagnostic_metrics": diagnostic_metrics,
        "event_totals": {
            key: sum(int(record[key]) for record in records)
            for key in (
                "playerErrors", "mediaTransitions", "positionDiscontinuities", "sessionDisconnects",
                "playbackStateChanges", "playWhenReadyChanges", "suppressionReasonChanges",
                "isPlayingChanges",
            )
        },
        "sync_counters": [dict(zip(names, match.groups())) for match in counter_pattern.finditer(log_text)],
        "records": records,
        "correctness": "passed by Android and independent host assertions",
    }


def run_benchmark(
    adb_path: str,
    serial: str,
    result_dir: Path,
    journey: str,
    expected: dict,
    iterations: int,
) -> dict:
    result_dir.mkdir(parents=True, exist_ok=False)
    sync.mutating_adb(adb_path, serial, "shell", "pm", "clear", startup.TEST_PACKAGE)
    before = set(startup.device_files(adb_path, serial))
    sync.mutating_adb(adb_path, serial, "logcat", "-c")
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
        title = base64.b64encode(expected["expected_title"].encode()).decode("ascii")
        command += ["-e", "expectedTitleBase64", title]
    command += ["-e", "androidx.benchmark.suppressErrors", "EMULATOR", startup.RUNNER]
    result = sync.mutating_adb(adb_path, serial, *command, timeout=3600)
    (result_dir / "instrumentation.txt").write_text(result.stdout, encoding="utf-8")
    if "OK (1 test)" not in result.stdout or "FAILURES!!!" in result.stdout:
        raise PlaybackAuthorityError(f"Instrumentation failed: {result_dir / 'instrumentation.txt'}")
    logcat = sync.adb(
        adb_path, serial, "logcat", "-d", "-s", "LibrePlayerPlaybackLoad:I",
        "LibrePlayerLibrarySync:I", "LibrePlayerAudio:I", "*:S",
    ).stdout
    (result_dir / "playback-load-logcat.txt").write_text(logcat, encoding="utf-8")
    after = set(startup.device_files(adb_path, serial))
    benchmark_json = [path for path in after if path.endswith("benchmarkData.json")]
    outputs = sorted((after - before) | set(benchmark_json))
    pulled = library_ui.pull_device_files_with_retry(
        adb_path, serial, outputs, result_dir / "device-output",
    )
    traces = [path for path in pulled if path.endswith((".perfetto-trace", ".trace"))]
    if len(traces) < iterations:
        raise PlaybackAuthorityError(f"Expected {iterations} traces, found {len(traces)}")
    summary = summarize_invocation(result_dir, expected, "rebuild" if journey == "rebuild" else "sync")
    summary["trace_paths"] = [Path(path).relative_to(result_dir).as_posix() for path in traces]
    (result_dir / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return summary


def combine_journey(invocations: list[dict], indexing_ms: list[float]) -> dict:
    durations = [value for item in invocations for value in item["synchronization_duration"]["raw_ms"]]
    advancement = [value for item in invocations for value in item["position_advancement"]["raw_ms"]]
    observations = [value for item in invocations for value in item["observation_duration"]["raw_ms"]]
    event_keys = invocations[0]["event_totals"]
    return {
        "synchronization_duration": sync.distribution(durations),
        "position_advancement": sync.distribution(advancement),
        "observation_duration": sync.distribution(observations),
        "mediastore_indexing_latency_ms": indexing_ms,
        "event_totals": {key: sum(item["event_totals"][key] for item in invocations) for key in event_keys},
        "sync_counters": [counter for item in invocations for counter in item["sync_counters"]],
        "correctness": "passed",
        "continuity": "passed",
        "trace_count": sum(len(item["trace_paths"]) for item in invocations),
        "records": [record for item in invocations for record in item["records"]],
    }


def run_authority(args: argparse.Namespace) -> dict:
    result_dir = args.result_dir.resolve()
    if result_dir.exists() and any(result_dir.iterdir()):
        raise PlaybackAuthorityError(f"Result directory must be new or empty: {result_dir}")
    result_dir.mkdir(parents=True, exist_ok=True)
    device = sync.require_authority_device(args.adb, args.serial)
    expected_order = SESSION_ORDER[args.session]
    if expected_order[args.side - 1] != args.variant:
        raise PlaybackAuthorityError(
            f"Unbalanced session order: session {args.session} side {args.side} must be {expected_order[args.side - 1]}"
        )
    manifest, mutation = sync.load_inputs(args.dataset)
    fixture = verify_playback_fixture(args.dataset, manifest)
    mutation_files = sync.materialize_mutations(args.dataset, result_dir / "mutation-materialization", mutation)
    base = sync.expected_state(manifest, mutation, "unchanged")
    sync.restore_base(args.adb, args.serial, args.dataset, manifest, mutation)
    configuration = library_ui.configure_device(args.adb, args.serial)
    initial = sync.install_and_initialize(args.adb, args.serial, args.target_apk, args.test_apk, base)
    journeys = {}
    invalid_or_recovered_runs = []
    try:
        unchanged = run_benchmark(
            args.adb, args.serial, result_dir / "unchanged" / "run-01", "unchanged",
            base, JOURNEY_ITERATIONS["unchanged"],
        )
        journeys["unchanged"] = combine_journey([unchanged], [])

        added = sync.expected_state(manifest, mutation, "add")
        add_invocations, indexing = [], []
        for iteration in range(1, JOURNEY_ITERATIONS["add"] + 1):
            sync.restore_base(args.adb, args.serial, args.dataset, manifest, mutation)
            sync.validate_probe(sync.provider_call(args.adb, args.serial, "sync"), base)
            sync.apply_mutation(args.adb, args.serial, mutation, mutation_files, "add")
            elapsed, _ = sync.wait_for_mediastore(
                args.adb, args.serial, added, timeout_seconds=args.index_timeout,
            )
            indexing.append(elapsed)
            add_invocations.append(run_benchmark(
                args.adb, args.serial, result_dir / "add" / f"run-{iteration:02d}",
                "add", added, 1,
            ))
        journeys["add"] = combine_journey(add_invocations, indexing)

        sync.restore_base(args.adb, args.serial, args.dataset, manifest, mutation)
        sync.validate_probe(sync.provider_call(args.adb, args.serial, "sync"), base)
        rebuild = run_benchmark(
            args.adb, args.serial, result_dir / "rebuild" / "run-01", "rebuild",
            base, JOURNEY_ITERATIONS["rebuild"],
        )
        journeys["rebuild"] = combine_journey([rebuild], [])
    finally:
        try:
            playback_provider_call(args.adb, args.serial, "stop")
        except (PlaybackAuthorityError, subprocess.CalledProcessError) as error:
            invalid_or_recovered_runs.append({"stage": "playback-stop", "error": str(error)})

    summary = {"authority": DISCLAIMER, "journeys": journeys}
    (result_dir / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    metadata = {
        "schema_version": 1,
        "authority": DISCLAIMER,
        "variant": args.variant,
        "session": args.session,
        "side": args.side,
        "expected_session_order": list(expected_order),
        "reference_git_commit": args.reference_commit,
        "development_git_commit": args.development_commit,
        "benchmark_harness_revision": args.harness_revision,
        "playback_instrumentation_revision": args.instrumentation_revision,
        "dataset_profile": "MEDIUM",
        "dataset_fingerprint_sha256": manifest["dataset_fingerprint_sha256"],
        "playback_fixture": fixture,
        "device": device,
        "device_configuration": configuration,
        "compilation_mode": "Full",
        "process_state": "resident target; real PlaybackService and MediaSession observed by an independent benchmark-only controller",
        "service_state": "real PlaybackService active before every measured library operation",
        "player_state": "same MediaItem, READY, playWhenReady, isPlaying, unsuppressed, connected, and error-free required before and after",
        "database_state": "canonical catalog before unchanged/rebuild and before the isolated Add mutation",
        "mediastore_precondition": "exact expected fixture path set observed before LibrePlayer timing",
        "timing_clock": "android.os.SystemClock.elapsedRealtimeNanos",
        "trace_metric": "MemoryUsageMetric(Mode.Max), diagnostic only",
        "audio_underrun_authority": "not captured: existing PlaybackAudioDiagnostics is debug-only while the controlled benchmark target is release-like; no acoustic authority is claimed",
        "large_profile": "deferred: no generated/provisioned LARGE fixture was present and Q1.1f does not authorize a new large-scale infrastructure exercise",
        "run_timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "invalid_or_recovered_runs": invalid_or_recovered_runs,
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


def compare_results(root: Path) -> dict:
    channels = {}
    all_continuity = True
    for variant in ("reference", "development"):
        summaries = [
            json.loads((root / f"session-{session}" / variant / "summary.json").read_text(encoding="utf-8"))
            for session in (1, 2, 3)
        ]
        channels[variant] = {}
        for journey in JOURNEY_ITERATIONS:
            nodes = [summary["journeys"][journey] for summary in summaries]
            all_continuity &= all(node.get("continuity") == "passed" for node in nodes)
            channels[variant][journey] = session_distribution(
                [node["synchronization_duration"]["median_ms"] for node in nodes]
            )
    classifications, relative = {}, {}
    for journey in JOURNEY_ITERATIONS:
        ref = channels["reference"][journey]
        dev = channels["development"][journey]
        stable = ref["range_percent_of_median"] <= 10.0 and dev["range_percent_of_median"] <= 10.0
        classifications[journey] = (
            "PLAYBACK CONTINUITY AUTHORITY + NUMERIC LOAD AUTHORITY" if all_continuity and stable
            else "PLAYBACK CONTINUITY AUTHORITY + LIMITED NUMERIC LOAD AUTHORITY" if all_continuity
            else "PLATFORM-LIMITED / DEFERRED"
        )
        relative[journey] = (
            dev["median_of_session_medians_ms"] / ref["median_of_session_medians_ms"] - 1.0
        ) * 100.0
    result = {
        "authority": DISCLAIMER,
        "channels": channels,
        "classifications": classifications,
        "development_relative_to_reference_percent": relative,
        "continuity_authority": all_continuity,
        "session_order": {str(key): list(value) for key, value in SESSION_ORDER.items()},
    }
    (root / "comparison.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return result


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
    run.add_argument("--side", required=True, type=int, choices=(1, 2))
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
            result = sync.require_authority_device(args.adb, args.serial)
        elif args.command == "run":
            result = run_authority(args)
        else:
            result = compare_results(args.results_root.resolve())
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (
        PlaybackAuthorityError, sync.SynchronizationAuthorityError, OSError, ValueError,
        KeyError, subprocess.CalledProcessError,
    ) as error:
        print(f"playback-under-load authority error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
