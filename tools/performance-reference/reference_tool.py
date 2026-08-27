#!/usr/bin/env python3
"""Prepare and safely remove a benchmarkable detached v1.0.4 worktree."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

REFERENCE_COMMIT = "d2c212640ea3955591799a21d5bd8e382a628a57"
HARNESS_COMMIT = "304af67ad1efcbe0b4cdb9019cdaaf70c38c4a68"
REFERENCE_TAG = "v1.0.4"
ALLOWED_OVERLAY_PATHS = {
    "app/build.gradle.kts",
    "app/src/benchmark/AndroidManifest.xml",
    "benchmark/build.gradle.kts",
    "benchmark/src/main/java/com/libreplayer/benchmark/ColdStartupSmokeBenchmark.kt",
    "build.gradle.kts",
    "gradle/libs.versions.toml",
    "settings.gradle.kts",
}
IGNORED_HARNESS_PATHS = {"STATUS.md"}
STARTUP_HOOK_PATH = "app/src/main/java/com/libreplayer/navigation/LibrePlayerApp.kt"
STARTUP_OVERLAY_HASHES = {
    STARTUP_HOOK_PATH: "d3367152a56b16ed13c49dad6fddd2c5c128f9a23d48057900ce5649a8b33a93",
    "benchmark/src/main/java/com/libreplayer/benchmark/CachedLibraryStartupBenchmark.kt":
        "4af7a9b8f69f21a53f4a935246cc83404043220da5d1c758dc89671ef6392ebc",
}
STARTUP_OVERLAY_PATHS = set(STARTUP_OVERLAY_HASHES)
LIBRARY_UI_OVERLAY_HASHES = {
    "benchmark/src/main/java/com/libreplayer/benchmark/LibraryUiBenchmark.kt":
        "1a0e4d4cfff586986857ac0e6ab4919fce9ca2631118b5f499bfd0854c6096ee",
}
LIBRARY_UI_OVERLAY_PATHS = set(LIBRARY_UI_OVERLAY_HASHES)
SYNCHRONIZATION_OVERLAY_HASHES = {
    "app/src/benchmark/AndroidManifest.xml":
        "2ce524b947d2ef04a4ac38d26cab8510b4597b1337096ad3cb0ef27b05418f35",
    "app/src/benchmark/java/com/libreplayer/benchmark/SynchronizationProbeProvider.kt":
        "e7b54d20ebb8abbad7b8a151c63cd73f01a3d6c24092bf8f630e4993b307565e",
    "benchmark/src/main/java/com/libreplayer/benchmark/SynchronizationBenchmark.kt":
        "c4f92999ee4db94d548bc01bf4c0faac6b78f625d6f794c60a58e0ec4179635e",
}
SYNCHRONIZATION_OVERLAY_PATHS = set(SYNCHRONIZATION_OVERLAY_HASHES)
ACCEPTED_SYNCHRONIZATION_HARNESS = (
    "q1.1e-sha256:b2edbb194d2392eb70020115a5f6831a299bfc8e03994e7608391d52c4706d24"
)
PLAYBACK_LOAD_OVERLAY_HASHES = {
    "app/src/benchmark/AndroidManifest.xml":
        "b11872e42c18c2af7037a0222f7e10a108bccc998345b42bb9e8a97c0019eada",
    "app/src/benchmark/java/com/libreplayer/benchmark/SynchronizationProbeProvider.kt":
        "e7b54d20ebb8abbad7b8a151c63cd73f01a3d6c24092bf8f630e4993b307565e",
    "benchmark/src/main/java/com/libreplayer/benchmark/SynchronizationBenchmark.kt":
        "c4f92999ee4db94d548bc01bf4c0faac6b78f625d6f794c60a58e0ec4179635e",
    "app/src/benchmark/java/com/libreplayer/benchmark/PlaybackLoadProbeProvider.kt":
        "5011f642278c535195039743993e7f8ba42be64688bbf621aa586c104ad547d9",
    "benchmark/src/main/java/com/libreplayer/benchmark/PlaybackUnderLoadBenchmark.kt":
        "5ca8c3715d8bff099c0a7615d0699efb8fc861c0cbe32faea9b086cb190daca3",
}
PLAYBACK_LOAD_OVERLAY_PATHS = set(PLAYBACK_LOAD_OVERLAY_HASHES)
ACCEPTED_PLAYBACK_LOAD_HARNESS = (
    "q1.1f-sha256:72a584f18fb60f047ebb659ce7c96f88888a4d04db2e1fb21602cb4828ffccca"
)
MEMORY_RESOURCE_OVERLAY_HASHES = {
    "app/src/benchmark/AndroidManifest.xml":
        "d719a3457df8468f14a0de0ec961bb6e01580381e316b6430c8bb769abe7ccf7",
    "app/src/benchmark/java/com/libreplayer/benchmark/SynchronizationProbeProvider.kt":
        "e7b54d20ebb8abbad7b8a151c63cd73f01a3d6c24092bf8f630e4993b307565e",
    "benchmark/src/main/java/com/libreplayer/benchmark/SynchronizationBenchmark.kt":
        "c4f92999ee4db94d548bc01bf4c0faac6b78f625d6f794c60a58e0ec4179635e",
    "app/src/benchmark/java/com/libreplayer/benchmark/PlaybackLoadProbeProvider.kt":
        "5011f642278c535195039743993e7f8ba42be64688bbf621aa586c104ad547d9",
    "benchmark/src/main/java/com/libreplayer/benchmark/PlaybackUnderLoadBenchmark.kt":
        "5ca8c3715d8bff099c0a7615d0699efb8fc861c0cbe32faea9b086cb190daca3",
    "app/src/benchmark/java/com/libreplayer/benchmark/MemoryResourceProbeProvider.kt":
        "e4c060d3719d3dc77a5c465aedab8e87b975d748ef9f090019f4622e6b1fd669",
    "benchmark/src/main/java/com/libreplayer/benchmark/MemoryResourceBenchmark.kt":
        "26aca27fd3753e57070747c094943064420b07ead85102bb223e63262c11b546",
}
MEMORY_RESOURCE_OVERLAY_PATHS = set(MEMORY_RESOURCE_OVERLAY_HASHES)


class ReferenceError(RuntimeError):
    pass


def git(repo: Path, *args: str, capture: bool = True, input_bytes: bytes | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", "-C", str(repo), *args],
        check=True,
        capture_output=capture,
        input=input_bytes,
    )


def output_text(result: subprocess.CompletedProcess) -> str:
    return result.stdout.decode("utf-8", errors="replace").rstrip()


def changed_paths(repo: Path, base: str, target: str) -> set[str]:
    result = git(repo, "diff", "--name-only", base, target, "--")
    return {line for line in output_text(result).splitlines() if line}


def validate_harness_delta(paths: set[str]) -> None:
    unexpected = paths - ALLOWED_OVERLAY_PATHS - IGNORED_HARNESS_PATHS
    missing = ALLOWED_OVERLAY_PATHS - paths
    production = {path for path in paths if path.startswith("app/src/main/")}
    if production:
        raise ReferenceError(f"Production source changes are forbidden: {sorted(production)}")
    if unexpected:
        raise ReferenceError(f"Unexpected Q1.1a paths: {sorted(unexpected)}")
    if missing:
        raise ReferenceError(f"Expected Q1.1a overlay paths missing: {sorted(missing)}")


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_startup_overlay_paths(production_paths: set[str], hashes: dict[str, str]) -> None:
    if production_paths != {STARTUP_HOOK_PATH}:
        raise ReferenceError(
            "Q1.1c production delta must contain only the approved fully-drawn hook: "
            f"{sorted(production_paths)}"
        )
    if set(hashes) != STARTUP_OVERLAY_PATHS:
        raise ReferenceError(f"Startup overlay paths differ from allowlist: {sorted(hashes)}")
    mismatches = {
        path: hashes[path]
        for path, expected in STARTUP_OVERLAY_HASHES.items()
        if hashes[path] != expected
    }
    if mismatches:
        raise ReferenceError(f"Startup overlay content hash mismatch: {mismatches}")


def validate_startup_overlay(repo: Path) -> dict[str, str]:
    production_paths = changed_paths(repo, REFERENCE_COMMIT, "HEAD")
    production_paths = {path for path in production_paths if path.startswith("app/src/main/")}
    working_production = output_text(
        git(repo, "diff", "--name-only", REFERENCE_COMMIT, "--", "app/src/main")
    )
    production_paths.update(line for line in working_production.splitlines() if line)
    untracked_production = output_text(
        git(repo, "ls-files", "--others", "--exclude-standard", "--", "app/src/main")
    )
    production_paths.update(line for line in untracked_production.splitlines() if line)
    hashes = {
        path: file_sha256(repo / path)
        for path in STARTUP_OVERLAY_PATHS
        if (repo / path).is_file()
    }
    validate_startup_overlay_paths(production_paths, hashes)
    return hashes


def startup_harness_revision(hashes: dict[str, str]) -> str:
    digest = hashlib.sha256()
    digest.update(f"foundation:{HARNESS_COMMIT}\n".encode())
    for path in sorted(hashes):
        digest.update(f"{path}:{hashes[path]}\n".encode())
    return f"q1.1c-sha256:{digest.hexdigest()}"


def validate_library_ui_overlay_hashes(hashes: dict[str, str]) -> None:
    if set(hashes) != LIBRARY_UI_OVERLAY_PATHS:
        raise ReferenceError(f"Library UI overlay paths differ from allowlist: {sorted(hashes)}")
    mismatches = {
        path: hashes[path]
        for path, expected in LIBRARY_UI_OVERLAY_HASHES.items()
        if hashes[path] != expected
    }
    if mismatches:
        raise ReferenceError(f"Library UI overlay content hash mismatch: {mismatches}")


def validate_library_ui_overlay(repo: Path) -> dict[str, str]:
    hashes = {
        path: file_sha256(repo / path)
        for path in LIBRARY_UI_OVERLAY_PATHS
        if (repo / path).is_file()
    }
    validate_library_ui_overlay_hashes(hashes)
    return hashes


def library_ui_harness_revision(
    startup_hashes: dict[str, str],
    library_ui_hashes: dict[str, str],
) -> str:
    digest = hashlib.sha256()
    digest.update(f"startup:{startup_harness_revision(startup_hashes)}\n".encode())
    for path in sorted(library_ui_hashes):
        digest.update(f"{path}:{library_ui_hashes[path]}\n".encode())
    return f"q1.1d-sha256:{digest.hexdigest()}"


def validate_synchronization_overlay_hashes(hashes: dict[str, str]) -> None:
    if set(hashes) != SYNCHRONIZATION_OVERLAY_PATHS:
        raise ReferenceError(f"Synchronization overlay paths differ from allowlist: {sorted(hashes)}")
    mismatches = {
        path: hashes[path]
        for path, expected in SYNCHRONIZATION_OVERLAY_HASHES.items()
        if hashes[path] != expected
    }
    if mismatches:
        raise ReferenceError(f"Synchronization overlay content hash mismatch: {mismatches}")


def validate_synchronization_overlay(repo: Path) -> dict[str, str]:
    hashes = {
        path: file_sha256(repo / path)
        for path in SYNCHRONIZATION_OVERLAY_PATHS
        if (repo / path).is_file()
    }
    validate_synchronization_overlay_hashes(hashes)
    return hashes


def synchronization_harness_revision(
    startup_hashes: dict[str, str],
    library_ui_hashes: dict[str, str],
    synchronization_hashes: dict[str, str],
) -> str:
    digest = hashlib.sha256()
    digest.update(f"library-ui:{library_ui_harness_revision(startup_hashes, library_ui_hashes)}\n".encode())
    for path in sorted(synchronization_hashes):
        digest.update(f"{path}:{synchronization_hashes[path]}\n".encode())
    return f"q1.1e-sha256:{digest.hexdigest()}"


def validate_playback_load_overlay_hashes(hashes: dict[str, str]) -> None:
    if set(hashes) != PLAYBACK_LOAD_OVERLAY_PATHS:
        raise ReferenceError(f"Playback-load overlay paths differ from allowlist: {sorted(hashes)}")
    mismatches = {
        path: hashes[path]
        for path, expected in PLAYBACK_LOAD_OVERLAY_HASHES.items()
        if hashes[path] != expected
    }
    if mismatches:
        raise ReferenceError(f"Playback-load overlay content hash mismatch: {mismatches}")


def validate_playback_load_overlay(repo: Path) -> dict[str, str]:
    hashes = {
        path: file_sha256(repo / path)
        for path in PLAYBACK_LOAD_OVERLAY_PATHS
        if (repo / path).is_file()
    }
    validate_playback_load_overlay_hashes(hashes)
    return hashes


def playback_load_harness_revision(hashes: dict[str, str]) -> str:
    digest = hashlib.sha256()
    digest.update(f"synchronization:{ACCEPTED_SYNCHRONIZATION_HARNESS}\n".encode())
    for path in sorted(hashes):
        digest.update(f"{path}:{hashes[path]}\n".encode())
    return f"q1.1f-sha256:{digest.hexdigest()}"


def validate_memory_resource_overlay_hashes(hashes: dict[str, str]) -> None:
    if set(hashes) != MEMORY_RESOURCE_OVERLAY_PATHS:
        raise ReferenceError(f"Memory/resource overlay paths differ from allowlist: {sorted(hashes)}")
    mismatches = {
        path: hashes[path]
        for path, expected in MEMORY_RESOURCE_OVERLAY_HASHES.items()
        if hashes[path] != expected
    }
    if mismatches:
        raise ReferenceError(f"Memory/resource overlay content hash mismatch: {mismatches}")


def validate_memory_resource_overlay(repo: Path) -> dict[str, str]:
    hashes = {
        path: file_sha256(repo / path)
        for path in MEMORY_RESOURCE_OVERLAY_PATHS
        if (repo / path).is_file()
    }
    validate_memory_resource_overlay_hashes(hashes)
    return hashes


def memory_resource_harness_revision(hashes: dict[str, str]) -> str:
    digest = hashlib.sha256()
    digest.update(f"playback-load:{ACCEPTED_PLAYBACK_LOAD_HARNESS}\n".encode())
    for path in sorted(hashes):
        digest.update(f"{path}:{hashes[path]}\n".encode())
    return f"q1.1g-sha256:{digest.hexdigest()}"


def ensure_external_worktree(repo: Path, worktree: Path) -> None:
    repo = repo.resolve()
    worktree = worktree.resolve()
    if worktree == repo or repo in worktree.parents:
        raise ReferenceError("Reference worktree must be outside the normal repository working tree")
    if worktree.exists():
        raise ReferenceError(f"Reference worktree path already exists: {worktree}")


def verify_reference_identity(repo: Path) -> None:
    tag_commit = output_text(git(repo, "rev-list", "-n", "1", REFERENCE_TAG))
    if tag_commit != REFERENCE_COMMIT:
        raise ReferenceError(f"{REFERENCE_TAG} moved: {tag_commit}")
    git(repo, "cat-file", "-e", f"{HARNESS_COMMIT}^{{commit}}")
    harness_parent = output_text(git(repo, "rev-parse", f"{HARNESS_COMMIT}^"))
    if harness_parent != REFERENCE_COMMIT:
        raise ReferenceError(f"Benchmark harness is not directly based on v1.0.4: {harness_parent}")


def status_paths(worktree: Path) -> set[str]:
    result = git(worktree, "status", "--porcelain=v1", "--untracked-files=all")
    paths = set()
    for line in output_text(result).splitlines():
        if line:
            paths.add(line[3:].split(" -> ")[-1])
    return paths


def prepare(repo: Path, worktree: Path) -> dict:
    repo = repo.resolve()
    worktree = worktree.resolve()
    ensure_external_worktree(repo, worktree)
    verify_reference_identity(repo)
    delta = changed_paths(repo, REFERENCE_COMMIT, HARNESS_COMMIT)
    validate_harness_delta(delta)
    startup_hashes = validate_startup_overlay(repo)
    library_ui_hashes = validate_library_ui_overlay(repo)
    memory_resource_hashes = validate_memory_resource_overlay(repo)
    worktree.parent.mkdir(parents=True, exist_ok=True)
    git(repo, "worktree", "add", "--detach", str(worktree), REFERENCE_COMMIT, capture=False)
    try:
        patch = git(repo, "diff", "--binary", REFERENCE_COMMIT, HARNESS_COMMIT, "--", *sorted(ALLOWED_OVERLAY_PATHS)).stdout
        git(worktree, "apply", "--whitespace=nowarn", "-", input_bytes=patch)
        for path in sorted(
            STARTUP_OVERLAY_PATHS |
            LIBRARY_UI_OVERLAY_PATHS |
            MEMORY_RESOURCE_OVERLAY_PATHS
        ):
            destination = worktree / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(repo / path, destination)
        applied = status_paths(worktree)
        expected_applied = (
            ALLOWED_OVERLAY_PATHS |
            STARTUP_OVERLAY_PATHS |
            LIBRARY_UI_OVERLAY_PATHS |
            MEMORY_RESOURCE_OVERLAY_PATHS
        )
        if applied != expected_applied:
            raise ReferenceError(f"Applied overlay differs from allowlist: {sorted(applied)}")
        production_applied = {
            path for path in applied if path.startswith("app/src/main/")
        }
        if production_applied != {STARTUP_HOOK_PATH}:
            raise ReferenceError(f"Unexpected reference production overlay: {sorted(production_applied)}")
        head = output_text(git(worktree, "rev-parse", "HEAD"))
        if head != REFERENCE_COMMIT:
            raise ReferenceError(f"Detached reference HEAD changed: {head}")
        return {
            "reference_git_commit": REFERENCE_COMMIT,
            "benchmark_foundation_commit": HARNESS_COMMIT,
            "benchmark_harness_revision": memory_resource_harness_revision(memory_resource_hashes),
            "predecessor_harness_revision": ACCEPTED_PLAYBACK_LOAD_HARNESS,
            "memory_resource_instrumentation_sha256": memory_resource_hashes[
                "app/src/benchmark/java/com/libreplayer/benchmark/MemoryResourceProbeProvider.kt"
            ],
            "measurement_hook_sha256": startup_hashes[STARTUP_HOOK_PATH],
            "worktree": str(worktree),
            "detached": True,
            "overlay_paths": sorted(applied),
            "production_source_changed": True,
            "production_overlay_paths": [STARTUP_HOOK_PATH],
        }
    except Exception:
        git(repo, "worktree", "remove", "--force", str(worktree), capture=False)
        raise


def cleanup(repo: Path, worktree: Path, gradle_user_home: Path | None = None) -> dict:
    repo = repo.resolve()
    worktree = worktree.resolve()
    if worktree == repo or repo in worktree.parents:
        raise ReferenceError("Refusing to remove a path inside the normal repository")
    registered = output_text(git(repo, "worktree", "list", "--porcelain"))
    registered_paths = {
        Path(line.removeprefix("worktree ")).resolve()
        for line in registered.splitlines()
        if line.startswith("worktree ")
    }
    if worktree not in registered_paths:
        raise ReferenceError(f"Path is not a registered Git worktree: {worktree}")
    if output_text(git(worktree, "rev-parse", "HEAD")) != REFERENCE_COMMIT:
        raise ReferenceError("Refusing to remove worktree at an unexpected commit")
    unexpected = (
        status_paths(worktree)
        - ALLOWED_OVERLAY_PATHS
        - STARTUP_OVERLAY_PATHS
        - LIBRARY_UI_OVERLAY_PATHS
        - MEMORY_RESOURCE_OVERLAY_PATHS
    )
    if unexpected:
        raise ReferenceError(f"Refusing to remove worktree with unexpected files: {sorted(unexpected)}")
    if any((worktree / path).exists() for path in ("app/build", "benchmark/build", "build")):
        wrapper = worktree / ("gradlew.bat" if os.name == "nt" else "gradlew")
        command = [str(wrapper)]
        if gradle_user_home:
            command += ["--gradle-user-home", str(gradle_user_home.resolve())]
        command += ["clean", "--console=plain"]
        subprocess.run(command, cwd=worktree, check=True)
    git(repo, "worktree", "remove", "--force", str(worktree), capture=False)
    return {"removed": str(worktree), "reference_git_commit": REFERENCE_COMMIT}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "cleanup"))
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--worktree", required=True, type=Path)
    parser.add_argument("--gradle-user-home", type=Path)
    args = parser.parse_args()
    try:
        result = (
            prepare(args.repo, args.worktree)
            if args.command == "prepare"
            else cleanup(args.repo, args.worktree, args.gradle_user_home)
        )
        print(json.dumps(result, sort_keys=True))
        return 0
    except (ReferenceError, OSError, subprocess.CalledProcessError) as error:
        print(f"reference error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
