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


if __name__ == "__main__":
    unittest.main()
