#!/usr/bin/env python3
"""Prepare and safely remove a benchmarkable detached v1.0.4 worktree."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
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
    worktree.parent.mkdir(parents=True, exist_ok=True)
    git(repo, "worktree", "add", "--detach", str(worktree), REFERENCE_COMMIT, capture=False)
    try:
        patch = git(repo, "diff", "--binary", REFERENCE_COMMIT, HARNESS_COMMIT, "--", *sorted(ALLOWED_OVERLAY_PATHS)).stdout
        git(worktree, "apply", "--whitespace=nowarn", "-", input_bytes=patch)
        applied = status_paths(worktree)
        if applied != ALLOWED_OVERLAY_PATHS:
            raise ReferenceError(f"Applied overlay differs from allowlist: {sorted(applied)}")
        if output_text(git(worktree, "diff", "--name-only", "--", "app/src/main")):
            raise ReferenceError("Reference overlay changed app/src/main")
        head = output_text(git(worktree, "rev-parse", "HEAD"))
        if head != REFERENCE_COMMIT:
            raise ReferenceError(f"Detached reference HEAD changed: {head}")
        return {
            "reference_git_commit": REFERENCE_COMMIT,
            "benchmark_harness_revision": HARNESS_COMMIT,
            "worktree": str(worktree),
            "detached": True,
            "overlay_paths": sorted(applied),
            "production_source_changed": False,
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
    unexpected = status_paths(worktree) - ALLOWED_OVERLAY_PATHS
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
