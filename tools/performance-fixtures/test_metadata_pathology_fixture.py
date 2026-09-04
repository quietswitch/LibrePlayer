from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import shutil
import unittest

MODULE_PATH = Path(__file__).with_name("metadata_pathology_fixture.py")
SPEC = importlib.util.spec_from_file_location("metadata_pathology_fixture", MODULE_PATH)
fixture = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(fixture)


class MetadataPathologyFixtureTests(unittest.TestCase):
    def setUp(self) -> None:
        self.root = Path.cwd() / "build" / "q34-metadata-pathology-tool-tests"
        shutil.rmtree(self.root, ignore_errors=True)
        self.root.mkdir(parents=True)

    def tearDown(self) -> None:
        shutil.rmtree(self.root, ignore_errors=True)

    def test_generated_corpus_is_deterministic_and_bounded(self) -> None:
        first = self.root / "first"
        second = self.root / "second"

        first_result = fixture.generate(first)
        second_result = fixture.generate(second)
        first_manifest = json.loads((first / fixture.MANIFEST_NAME).read_text(encoding="utf-8"))
        second_manifest = json.loads((second / fixture.MANIFEST_NAME).read_text(encoding="utf-8"))

        self.assertEqual(11, first_result["tracks"])
        self.assertEqual(first_result["fingerprint"], second_result["fingerprint"])
        self.assertEqual(first_manifest, second_manifest)
        self.assertLess(first_result["bytes"], 2_000_000)
        long_track = next(
            track for track in first_manifest["tracks"]
            if track["relative_path"] == "08-long-needle.mp3"
        )
        self.assertEqual(
            {"title": 1_024, "artist": 4_096, "album": 4_096},
            long_track["declared_lengths"],
        )

    def test_malformed_tag_retains_the_exact_control_audio_payload(self) -> None:
        output = self.root / "corpus"
        fixture.generate(output)

        malformed = (output / "11-malformed-playable.mp3").read_bytes()
        control = (output / "01-control.mp3").read_bytes()
        audio = fixture.template_audio()

        self.assertTrue(malformed.startswith(b"ID3\x03"))
        self.assertTrue(malformed.endswith(audio))
        self.assertTrue(control.endswith(audio))
        self.assertEqual(11, fixture.validate(output, require_ffprobe=False)["tracks"])


if __name__ == "__main__":
    unittest.main()
