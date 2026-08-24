from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest

MODULE_PATH = Path(__file__).with_name("startup_authority.py")
SPEC = importlib.util.spec_from_file_location("startup_authority", MODULE_PATH)
startup_authority = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(startup_authority)


class StartupAuthorityTests(unittest.TestCase):
    def test_physical_serial_pattern_is_rejected(self) -> None:
        self.assertIsNone(startup_authority.SERIAL_PATTERN.fullmatch("R58M123456"))
        self.assertIsNotNone(startup_authority.SERIAL_PATTERN.fullmatch("emulator-5554"))

    def test_medium_manifest_identity_is_required(self) -> None:
        startup_authority.validate_medium_manifest(
            {
                "profile": "MEDIUM",
                "dataset_fingerprint_sha256": startup_authority.MEDIUM_FINGERPRINT,
                "expected": {"tracks": 2_000},
            }
        )

    def test_metric_summary_keeps_raw_values_and_population_statistics(self) -> None:
        summary = startup_authority.summarize_metric({"runs": [10.0, 30.0, 20.0]})
        self.assertEqual(summary["raw"], [10.0, 30.0, 20.0])
        self.assertEqual(summary["min"], 10.0)
        self.assertEqual(summary["median"], 20.0)
        self.assertEqual(summary["max"], 30.0)
        self.assertEqual(summary["mean"], 20.0)

    def test_session_distribution_uses_median_and_reports_range_diagnostic(self) -> None:
        summary = startup_authority.session_distribution([100.0, 105.0, 110.0])
        self.assertEqual(summary["median_of_session_medians"], 105.0)
        self.assertAlmostEqual(summary["range_percent_of_overall_median"], 10.0 / 105.0 * 100.0)

    def test_summary_requires_both_startup_metrics(self) -> None:
        document = {
            "benchmarks": [
                {
                    "name": "CachedLibraryStartupBenchmark.cachedSongsColdStartup",
                    "metrics": {"timeToInitialDisplayMs": {"runs": [1.0]}},
                }
            ]
        }
        with self.assertRaises(startup_authority.StartupAuthorityError):
            startup_authority.summarize_document(document, "result.json")


if __name__ == "__main__":
    unittest.main()
