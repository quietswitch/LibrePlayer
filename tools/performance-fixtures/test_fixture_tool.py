from __future__ import annotations

import importlib.util
from pathlib import Path, PurePosixPath
import shutil
import unittest

MODULE_PATH = Path(__file__).with_name("fixture_tool.py")
SPEC = importlib.util.spec_from_file_location("fixture_tool", MODULE_PATH)
fixture_tool = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(fixture_tool)


class FixtureToolTests(unittest.TestCase):
    def test_profile_contract(self) -> None:
        profiles = fixture_tool.load_profiles()["profiles"]
        self.assertEqual((0, 0, 0), tuple(profiles["EMPTY"].values()))
        self.assertEqual(100, profiles["SMALL"]["tracks"])
        self.assertEqual(2_000, profiles["MEDIUM"]["tracks"])
        self.assertEqual(10_000, profiles["LARGE"]["tracks"])
        self.assertEqual(50_000, profiles["STRESS"]["tracks"])

    def test_track_design_is_deterministic_and_safe(self) -> None:
        first = fixture_tool.track_record("SMALL", 1, 10, 10)
        second = fixture_tool.track_record("SMALL", 1, 10, 10)
        self.assertEqual(first, second)
        self.assertFalse(PurePosixPath(first["relative_path"]).is_absolute())
        self.assertNotIn("..", PurePosixPath(first["relative_path"]).parts)

    def test_small_profile_covers_controlled_metadata_variation(self) -> None:
        tracks = [fixture_tool.track_record("SMALL", ordinal, 10, 10) for ordinal in range(1, 101)]
        text = " ".join(
            value
            for track in tracks
            for value in (track["title"], track["artist"], track["album"])
            if value
        )
        self.assertIn("Café", text)
        self.assertIn("Cafe\u0301", text)
        self.assertIn("🚀", text)
        self.assertIn("ليلة", text)
        self.assertIn("descriptive-name-", text)
        self.assertTrue(any(track["artist"] is None for track in tracks))
        self.assertTrue(any(track["album"] is None for track in tracks))
        self.assertTrue(any(track["disc_number"] == 2 for track in tracks))
        self.assertEqual({"none", "ordinary", "oversized"}, {track["artwork_class"] for track in tracks})
        self.assertLess(len({track["title"] for track in tracks}), len(tracks))
        self.assertTrue(any(track["favorite"] for track in tracks))
        self.assertTrue(any(track["playlists"] for track in tracks))

    def test_fingerprint_ignores_only_its_own_field(self) -> None:
        manifest = {"schema_version": 1, "tracks": [], "dataset_fingerprint_sha256": "ignored"}
        first = fixture_tool.dataset_fingerprint(manifest)
        manifest["dataset_fingerprint_sha256"] = "changed"
        self.assertEqual(first, fixture_tool.dataset_fingerprint(manifest))

    def test_empty_profile_round_trip(self) -> None:
        generated_root = MODULE_PATH.parent / "generated"
        generated_root.mkdir(exist_ok=True)
        temporary = generated_root / "unit-test-empty"
        if temporary.exists():
            shutil.rmtree(temporary)
        try:
            output = temporary / "EMPTY"
            generated = fixture_tool.generate("EMPTY", output)
            validated = fixture_tool.validate(output, decode_check=False)
            self.assertEqual(0, generated["validation"]["tracks"])
            self.assertEqual(generated["fingerprint"], validated["fingerprint"])
        finally:
            if temporary.exists():
                shutil.rmtree(temporary)


if __name__ == "__main__":
    unittest.main()
