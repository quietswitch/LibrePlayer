from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest

MODULE_PATH = Path(__file__).with_name("provision_android.py")
SPEC = importlib.util.spec_from_file_location("provision_android", MODULE_PATH)
provision_android = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(provision_android)


class ProvisionAndroidTests(unittest.TestCase):
    def test_profile_paths_are_dedicated(self) -> None:
        self.assertEqual(
            "/sdcard/Music/LibrePlayerBenchmark/SMALL",
            provision_android.remote_path("SMALL"),
        )

    def test_unsafe_profile_names_are_rejected(self) -> None:
        for value in ("../Music", "", "SMALL/OTHER", "small space"):
            with self.subTest(value=value):
                with self.assertRaises(provision_android.ProvisionError):
                    provision_android.remote_path(value)

    def test_only_emulator_serial_shape_is_allowed(self) -> None:
        self.assertIsNotNone(provision_android.SERIAL_PATTERN.fullmatch("emulator-5554"))
        self.assertIsNone(provision_android.SERIAL_PATTERN.fullmatch("R58M123456A"))


if __name__ == "__main__":
    unittest.main()
