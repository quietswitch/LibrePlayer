from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import shutil
import unittest

MODULE_PATH = Path(__file__).with_name("artwork_fixture.py")
SPEC = importlib.util.spec_from_file_location("artwork_fixture", MODULE_PATH)
fixture = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(fixture)


class ArtworkFixtureTests(unittest.TestCase):
    def setUp(self) -> None:
        self.root = Path.cwd() / "build" / "q35-artwork-tool-tests"
        shutil.rmtree(self.root, ignore_errors=True)
        self.root.mkdir(parents=True)

    def tearDown(self) -> None:
        shutil.rmtree(self.root, ignore_errors=True)

    def test_generated_corpus_is_deterministic_compact_and_decodable(self) -> None:
        first = self.root / "first"
        second = self.root / "second"

        first_result = fixture.generate(first)
        second_result = fixture.generate(second)
        first_manifest = json.loads((first / fixture.MANIFEST_NAME).read_text(encoding="utf-8"))
        second_manifest = json.loads((second / fixture.MANIFEST_NAME).read_text(encoding="utf-8"))

        self.assertEqual(14, first_result["tracks"])
        self.assertEqual(first_result["fingerprint"], second_result["fingerprint"])
        self.assertEqual(first_manifest, second_manifest)
        self.assertLess(first_result["bytes"], 2_000_000)
        self.assertEqual(14, fixture.validate(first, require_ffprobe=True)["tracks"])

    def test_image_formats_dimensions_and_pathologies_are_explicit(self) -> None:
        output = self.root / "corpus"
        fixture.generate(output)
        manifest = json.loads((output / fixture.MANIFEST_NAME).read_text(encoding="utf-8"))

        images = manifest["images"]
        self.assertEqual((512, 512, "JPEG"), tuple(images["red_jpeg_512"][key] for key in ("width", "height", "format")))
        self.assertEqual((512, 512, "PNG"), tuple(images["green_png_512"][key] for key in ("width", "height", "format")))
        self.assertEqual((512, 512, "WebP"), tuple(images["blue_webp_512"][key] for key in ("width", "height", "format")))
        self.assertEqual((2_048, 2_048, "WebP"), tuple(images["large_webp_2048"][key] for key in ("width", "height", "format")))
        self.assertEqual("invalid", images["corrupt_payload"]["format"])
        self.assertEqual("truncated PNG", images["truncated_png"]["format"])

    def test_art_change_keeps_audio_and_metadata_but_changes_embedded_artwork(self) -> None:
        output = self.root / "corpus"
        fixture.generate(output)
        manifest = json.loads((output / fixture.MANIFEST_NAME).read_text(encoding="utf-8"))

        before_record = next(track for track in manifest["tracks"] if track["relative_path"] == "14-art-change.mp3")
        after_record = manifest["update"]
        before = (output / before_record["relative_path"]).read_bytes()
        after = (output / after_record["relative_path"]).read_bytes()
        audio = fixture.template_audio()

        self.assertNotEqual(before_record["sha256"], after_record["sha256"])
        self.assertTrue(before.endswith(audio))
        self.assertTrue(after.endswith(audio))


if __name__ == "__main__":
    unittest.main()
