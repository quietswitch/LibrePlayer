from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


MODULE_PATH = Path(__file__).with_name("library_ui_authority.py")
SPEC = importlib.util.spec_from_file_location("library_ui_authority", MODULE_PATH)
library_ui = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(library_ui)


class LibraryUiAuthorityTests(unittest.TestCase):
    def test_physical_serial_is_rejected_by_pattern(self) -> None:
        self.assertIsNone(library_ui.startup.SERIAL_PATTERN.fullmatch("R5CT52F9ETF"))
        self.assertIsNotNone(library_ui.startup.SERIAL_PATTERN.fullmatch("emulator-5556"))

    def test_percentile_uses_linear_interpolation(self) -> None:
        self.assertEqual(library_ui.percentile([1.0, 2.0, 3.0], 50), 2.0)
        self.assertAlmostEqual(library_ui.percentile([0.0, 10.0], 90), 9.0)

    def test_sample_summary_keeps_iteration_distributions(self) -> None:
        runs = [[float(index), float(index + 1)] for index in range(5)]
        metric = {"P50": 2.5, "P90": 4.1, "P95": 4.55, "P99": 4.91, "runs": runs}
        summary = library_ui.summarize_sampled_metric(metric)
        self.assertEqual(summary["sample_count_per_iteration"], [2] * 5)
        self.assertEqual(len(summary["iteration_percentiles"]), 5)
        self.assertEqual(summary["androidx_percentiles"]["P50"], 2.5)

    def test_summary_requires_all_four_journeys_and_frame_metrics(self) -> None:
        sampled = {
            name: {
                "P50": 1.0,
                "P90": 2.0,
                "P95": 3.0,
                "P99": 4.0,
                "runs": [[1.0, 2.0] for _ in range(5)],
            }
            for name in library_ui.FRAME_METRICS
        }
        document = {
            "benchmarks": [
                {
                    "name": benchmark,
                    "metrics": {"frameCount": {"runs": [2.0] * 5}},
                    "sampledMetrics": sampled,
                }
                for benchmark in library_ui.JOURNEYS.values()
            ]
        }
        summary = library_ui.summarize_document(document, "raw.json")
        self.assertEqual(set(summary["journeys"]), set(library_ui.JOURNEYS))

    def test_session_warning_is_quality_diagnostic(self) -> None:
        stable = library_ui.session_distribution([100.0, 103.0, 105.0])
        unstable = library_ui.session_distribution([100.0, 120.0, 140.0])
        self.assertFalse(stable["unstable_warning"])
        self.assertTrue(unstable["unstable_warning"])


if __name__ == "__main__":
    unittest.main()
