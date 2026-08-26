from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest

MODULE_PATH = Path(__file__).with_name("reference_tool.py")
SPEC = importlib.util.spec_from_file_location("reference_tool", MODULE_PATH)
reference_tool = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(reference_tool)


class ReferenceToolTests(unittest.TestCase):
    def test_expected_q11a_delta_is_accepted(self) -> None:
        paths = reference_tool.ALLOWED_OVERLAY_PATHS | reference_tool.IGNORED_HARNESS_PATHS
        reference_tool.validate_harness_delta(paths)

    def test_production_source_is_rejected(self) -> None:
        paths = reference_tool.ALLOWED_OVERLAY_PATHS | reference_tool.IGNORED_HARNESS_PATHS | {"app/src/main/example.kt"}
        with self.assertRaises(reference_tool.ReferenceError):
            reference_tool.validate_harness_delta(paths)

    def test_unknown_overlay_path_is_rejected(self) -> None:
        paths = reference_tool.ALLOWED_OVERLAY_PATHS | reference_tool.IGNORED_HARNESS_PATHS | {"app/proguard-rules.pro"}
        with self.assertRaises(reference_tool.ReferenceError):
            reference_tool.validate_harness_delta(paths)

    def test_exact_startup_overlay_is_accepted(self) -> None:
        reference_tool.validate_startup_overlay_paths(
            {reference_tool.STARTUP_HOOK_PATH},
            dict(reference_tool.STARTUP_OVERLAY_HASHES),
        )

    def test_unrelated_production_source_is_rejected_for_startup(self) -> None:
        with self.assertRaises(reference_tool.ReferenceError):
            reference_tool.validate_startup_overlay_paths(
                {
                    reference_tool.STARTUP_HOOK_PATH,
                    "app/src/main/java/com/libreplayer/data/repository/LibraryRepository.kt",
                },
                dict(reference_tool.STARTUP_OVERLAY_HASHES),
            )

    def test_modified_startup_hook_is_rejected(self) -> None:
        hashes = dict(reference_tool.STARTUP_OVERLAY_HASHES)
        hashes[reference_tool.STARTUP_HOOK_PATH] = "0" * 64
        with self.assertRaises(reference_tool.ReferenceError):
            reference_tool.validate_startup_overlay_paths(
                {reference_tool.STARTUP_HOOK_PATH},
                hashes,
            )

    def test_library_ui_overlay_is_exactly_allowlisted(self) -> None:
        hashes = dict(reference_tool.LIBRARY_UI_OVERLAY_HASHES)
        reference_tool.validate_library_ui_overlay_hashes(hashes)

    def test_modified_library_ui_benchmark_is_rejected(self) -> None:
        hashes = dict(reference_tool.LIBRARY_UI_OVERLAY_HASHES)
        path = next(iter(hashes))
        hashes[path] = "0" * 64
        with self.assertRaises(reference_tool.ReferenceError):
            reference_tool.validate_library_ui_overlay_hashes(hashes)

    def test_library_ui_harness_revision_is_content_addressed(self) -> None:
        revision = reference_tool.library_ui_harness_revision(
            dict(reference_tool.STARTUP_OVERLAY_HASHES),
            dict(reference_tool.LIBRARY_UI_OVERLAY_HASHES),
        )
        self.assertTrue(revision.startswith("q1.1d-sha256:"))

    def test_synchronization_overlay_is_exactly_allowlisted(self) -> None:
        hashes = dict(reference_tool.SYNCHRONIZATION_OVERLAY_HASHES)
        reference_tool.validate_synchronization_overlay_hashes(hashes)

    def test_modified_synchronization_probe_is_rejected(self) -> None:
        hashes = dict(reference_tool.SYNCHRONIZATION_OVERLAY_HASHES)
        path = next(iter(hashes))
        hashes[path] = "0" * 64
        with self.assertRaises(reference_tool.ReferenceError):
            reference_tool.validate_synchronization_overlay_hashes(hashes)

    def test_synchronization_harness_revision_is_content_addressed(self) -> None:
        revision = reference_tool.synchronization_harness_revision(
            dict(reference_tool.STARTUP_OVERLAY_HASHES),
            dict(reference_tool.LIBRARY_UI_OVERLAY_HASHES),
            dict(reference_tool.SYNCHRONIZATION_OVERLAY_HASHES),
        )
        self.assertTrue(revision.startswith("q1.1e-sha256:"))


if __name__ == "__main__":
    unittest.main()
