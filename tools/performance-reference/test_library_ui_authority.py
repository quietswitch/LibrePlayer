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
        summary = library_ui.summarize_sampled_metric(metric, 5)
        self.assertEqual(summary["sample_count_per_iteration"], [2] * 5)
        self.assertEqual(summary["empty_iteration_count"], 0)
        self.assertEqual(len(summary["iteration_percentiles"]), 5)
        self.assertEqual(summary["androidx_percentiles"]["P50"], 2.5)

    def test_sample_summary_preserves_empty_iterations(self) -> None:
        runs = [[1.0], [], [2.0], [3.0], [4.0]]
        metric = {"P50": 2.5, "P90": 4.1, "P95": 4.55, "P99": 4.91, "runs": runs}
        summary = library_ui.summarize_sampled_metric(metric, 5)
        self.assertEqual(summary["sample_count_per_iteration"], [1, 0, 1, 1, 1])
        self.assertEqual(summary["empty_iteration_count"], 1)
        self.assertIsNone(summary["iteration_percentiles"][1])

    def test_summary_supports_selected_journeys_and_iteration_counts(self) -> None:
        def sampled(iterations: int) -> dict:
            return {
                name: {
                    "P50": 1.0,
                    "P90": 2.0,
                    "P95": 3.0,
                    "P99": 4.0,
                    "runs": [[1.0, 2.0] for _ in range(iterations)],
                }
                for name in library_ui.FRAME_METRICS
            }

        document = {
            "benchmarks": [
                {
                    "name": library_ui.JOURNEYS[journey],
                    "metrics": {
                        "frameCount": {
                            "runs": [2.0] * library_ui.JOURNEY_ITERATIONS[journey]
                        }
                    },
                    "sampledMetrics": sampled(library_ui.JOURNEY_ITERATIONS[journey]),
                }
                for journey in ("search", "resume")
            ]
        }
        summary = library_ui.summarize_document(
            document,
            "raw.json",
            ("search", "resume"),
        )
        self.assertEqual(set(summary["journeys"]), {"search", "resume"})
        self.assertEqual(summary["journeys"]["search"]["iteration_count"], 15)
        self.assertEqual(summary["journeys"]["resume"]["iteration_count"], 20)

    def test_journey_selection_rejects_unknown_or_duplicate_names(self) -> None:
        self.assertEqual(library_ui.parse_journeys("search,resume"), ("search", "resume"))
        with self.assertRaises(library_ui.LibraryUiAuthorityError):
            library_ui.parse_journeys("search,search")
        with self.assertRaises(library_ui.LibraryUiAuthorityError):
            library_ui.parse_journeys("search,unknown")

    def test_session_warning_is_quality_diagnostic(self) -> None:
        stable = library_ui.session_distribution([100.0, 103.0, 105.0])
        unstable = library_ui.session_distribution([100.0, 120.0, 140.0])
        self.assertFalse(stable["unstable_warning"])
        self.assertTrue(unstable["unstable_warning"])


if __name__ == "__main__":
    unittest.main()
