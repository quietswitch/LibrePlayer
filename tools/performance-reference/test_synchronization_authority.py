import importlib.util
import json
from pathlib import Path
import unittest


MODULE_PATH = Path(__file__).with_name("synchronization_authority.py")
SPEC = importlib.util.spec_from_file_location("synchronization_authority", MODULE_PATH)
sync = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(sync)


class SynchronizationAuthorityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture_dir = MODULE_PATH.parents[1] / "performance-fixtures"
        cls.dataset = cls.fixture_dir / "generated" / "MEDIUM"

    def manifest(self):
        if self.dataset.is_dir():
            return sync.load_inputs(self.dataset)
        profile = json.loads((self.fixture_dir / "profiles.json").read_text(encoding="utf-8"))["profiles"]["MEDIUM"]
        tracks = [
            sync.fixture_tool.track_record("MEDIUM", ordinal, profile["albums"], profile["artists"])
            for ordinal in range(1, profile["tracks"] + 1)
        ]
        manifest = {
            "profile": "MEDIUM",
            "expected": profile,
            "dataset_fingerprint_sha256": sync.MEDIUM_FINGERPRINT,
            "tracks": tracks,
        }
        mutation = json.loads(sync.MUTATION_MANIFEST.read_text(encoding="utf-8"))
        return manifest, mutation

    def test_expected_states_are_exact_deterministic_path_sets(self):
        manifest, mutation = self.manifest()
        unchanged = sync.expected_state(manifest, mutation, "unchanged")
        added = sync.expected_state(manifest, mutation, "add")
        deleted = sync.expected_state(manifest, mutation, "delete")
        modified = sync.expected_state(manifest, mutation, "modify")
        self.assertEqual(2000, unchanged["count"])
        self.assertEqual(2001, added["count"])
        self.assertEqual(1999, deleted["count"])
        self.assertEqual(unchanged["identity_sha256"], modified["identity_sha256"])
        self.assertNotEqual(unchanged["identity_sha256"], added["identity_sha256"])
        self.assertNotEqual(unchanged["identity_sha256"], deleted["identity_sha256"])

    def test_mutation_audio_materialization_matches_allowlisted_hashes(self):
        _, mutation = self.manifest()
        for name in ("add", "modify"):
            definition = mutation["mutations"][name]
            record = sync.fixture_tool.track_record("MEDIUM", definition["ordinal"], 200, 100)
            if name == "modify":
                record["title"] = definition["new_title"]
            content = sync.fixture_tool.id3_tag(record) + sync.fixture_tool.TEMPLATE_PATH.read_bytes()
            expected = definition["expected_sha256"] if name == "add" else definition["replacement_sha256"]
            self.assertEqual(expected, sync.hashlib.sha256(content).hexdigest())
            self.assertGreater(len(content), 30_000)

    def test_probe_bundle_parser_preserves_catalog_fields(self):
        parsed = sync.parse_bundle(
            "Bundle[{fixtureCount=2000, uniqueIdentities=2000, duplicateIdentities=0, "
            "identitySha256=abc123, inspectedTitle=Track 01000}]"
        )
        self.assertEqual("2000", parsed["fixtureCount"])
        self.assertEqual("abc123", parsed["identitySha256"])
        self.assertEqual("Track 01000", parsed["inspectedTitle"])

    def test_distribution_only_emits_p90_for_five_or_more_samples(self):
        self.assertNotIn("p90_ms", sync.distribution([1.0, 2.0, 3.0]))
        self.assertEqual(4.6, sync.distribution([1.0, 2.0, 3.0, 4.0, 5.0])["p90_ms"])

    def test_remote_paths_are_profile_scoped(self):
        self.assertEqual(
            "/sdcard/Music/LibrePlayerBenchmark/MEDIUM/audio/a.mp3",
            sync.remote_path("audio/a.mp3"),
        )
        with self.assertRaises(sync.SynchronizationAuthorityError):
            sync.remote_path("../outside.mp3")

    def test_matrix_classification_uses_session_quality_warning(self):
        stable = {"median_of_session_medians_ms": 100.0, "range_percent_of_median": 9.9}
        noisy = {"median_of_session_medians_ms": 100.0, "range_percent_of_median": 10.1}
        self.assertEqual(
            "NUMERIC REGRESSION AUTHORITY",
            sync.matrix_classification([stable, stable]),
        )
        self.assertEqual(
            "LIMITED NUMERIC AUTHORITY",
            sync.matrix_classification([stable, noisy]),
        )
        self.assertEqual(
            "PLATFORM-LIMITED / DEFERRED",
            sync.matrix_classification([stable, {"classification": "PLATFORM-LIMITED / DEFERRED"}]),
        )


if __name__ == "__main__":
    unittest.main()
