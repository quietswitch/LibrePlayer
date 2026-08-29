from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import unittest
from unittest import mock


MODULE_PATH = Path(__file__).with_name("baseline_profile_authority.py")
SPEC = importlib.util.spec_from_file_location("baseline_profile_authority", MODULE_PATH)
authority = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(authority)


class BaselineProfileAuthorityTests(unittest.TestCase):
    def test_balanced_order_and_iteration_counts_are_fixed(self) -> None:
        self.assertEqual(
            {
                (1, 1): "disabled",
                (1, 2): "enabled",
                (2, 1): "enabled",
                (2, 2): "disabled",
                (3, 1): "disabled",
                (3, 2): "enabled",
            },
            authority.EXPECTED_ORDER,
        )
        self.assertEqual(
            {
                "startupEffect": 10,
                "songsEffect": 5,
                "albumsEffect": 5,
                "playbackNowPlayingCorrectness": 1,
            },
            authority.ITERATIONS,
        )

    def test_comparison_preserves_paired_direction(self) -> None:
        result = authority.comparison_node(
            disabled=[10.0, 12.0, 14.0],
            enabled=[9.0, 13.0, 11.0],
        )
        self.assertEqual(12.0, result["disabled"]["median_of_session_medians"])
        self.assertEqual(11.0, result["enabled"]["median_of_session_medians"])
        self.assertEqual([True, False, True], result["paired_enabled_lower_by_session"])
        self.assertEqual(2, result["paired_directional_consistency"])

    def test_compare_requires_all_six_functionally_equivalent_sides(self) -> None:
        def summary(value: float, playback_passed: bool = True) -> dict:
            percentile = {name: value for name in authority.PERCENTILES}
            return {
                "startup": {
                    "timeToInitialDisplayMs": {"median": value},
                    "timeToFullDisplayMs": {"median": value + 1.0},
                },
                "songs": {
                    metric: {"median_of_iteration_percentiles": percentile}
                    for metric in authority.FRAME_METRICS
                },
                "albums": {
                    metric: {"median_of_iteration_percentiles": percentile}
                    for metric in authority.FRAME_METRICS
                },
                "playback_now_playing": {"passed": playback_passed},
            }

        def read_summary(path: Path, **_: object) -> str:
            session = int(path.parts[-3].removeprefix("session-"))
            channel = path.parts[-2]
            passed = not (session == 2 and channel == "enabled")
            return json.dumps(summary(10.0 - (channel == "enabled"), passed))

        with (
            mock.patch.object(Path, "read_text", autospec=True, side_effect=read_summary),
            mock.patch.object(Path, "write_text", autospec=True, return_value=1),
        ):
            result = authority.compare_results(Path("authority-root"))

        self.assertEqual([True, False, True], result["playback_now_playing"]["enabled_passes"])
        self.assertFalse(result["playback_now_playing"]["functional_equivalence"])
        self.assertEqual(3, result["startup"]["timeToInitialDisplayMs"]["paired_directional_consistency"])


if __name__ == "__main__":
    unittest.main()
