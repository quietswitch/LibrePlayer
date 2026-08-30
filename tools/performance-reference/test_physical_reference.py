from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest


MODULE_PATH = Path(__file__).with_name("physical_reference.py")
SPEC = importlib.util.spec_from_file_location("physical_reference", MODULE_PATH)
physical = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(physical)


class PhysicalReferenceTests(unittest.TestCase):
    def test_packages_are_side_by_side(self) -> None:
        self.assertEqual("com.libreplayer", physical.USER_PACKAGE)
        self.assertEqual("com.libreplayer.physicalreference", physical.TARGET_PACKAGE)
        self.assertNotEqual(physical.USER_PACKAGE, physical.TARGET_PACKAGE)

    def test_bounded_iteration_counts(self) -> None:
        self.assertEqual(
            {
                "startupReference": 5,
                "songsReference": 3,
                "albumsReference": 3,
                "playbackRefreshRebuildReference": 1,
            },
            physical.TEST_ITERATIONS,
        )

    def test_memory_markers_are_privacy_safe(self) -> None:
        logcat = "\n".join(
            [
                "I Q11I_PHYSICAL: memory label=settled-idle pss_kb=100 rss_kb=200 threads=30",
                "I unrelated: title=Personal metadata must not be parsed",
                "I Q11I_PHYSICAL: memory label=during-playback pss_kb=120 rss_kb=230 threads=42",
            ]
        )
        self.assertEqual(
            [
                {"label": "settled-idle", "pss_kb": 100, "rss_kb": 200, "threads": 30},
                {"label": "during-playback", "pss_kb": 120, "rss_kb": 230, "threads": 42},
            ],
            physical.parse_memory_markers(logcat),
        )


if __name__ == "__main__":
    unittest.main()
