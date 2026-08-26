from __future__ import annotations

import base64
import importlib.util
import json
from pathlib import Path
import unittest


MODULE_PATH = Path(__file__).with_name("playback_under_load_authority.py")
SPEC = importlib.util.spec_from_file_location("playback_under_load_authority", MODULE_PATH)
playback = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(playback)


class PlaybackUnderLoadAuthorityTest(unittest.TestCase):
    def expected(self):
        return {
            "count": 2000,
            "identity_sha256": "catalog",
            "inspected_identity": None,
            "inspected_present": None,
            "expected_title": None,
        }

    def record(self):
        record = {
            "method": "sync",
            "playbackFixtureIdentity": playback.PLAYBACK_FIXTURE_IDENTITY,
            "observationElapsedNanos": 2_000_000_000,
            "synchronizationElapsedNanos": 100_000_000,
            "positionAdvancementMs": 1900,
            "playerErrors": 0,
            "mediaTransitions": 0,
            "positionDiscontinuities": 0,
            "sessionDisconnects": 0,
            "playbackStateChanges": 0,
            "playWhenReadyChanges": 0,
            "suppressionReasonChanges": 0,
            "isPlayingChanges": 0,
            "fixtureCount": 2000,
            "uniqueIdentities": 2000,
            "duplicateIdentities": 0,
            "identitySha256": "catalog",
            "beforeMonotonicNanos": 1,
            "afterMonotonicNanos": 2_000_000_001,
        }
        for prefix, position in (("before", 100), ("after", 2000)):
            record |= {
                f"{prefix}MediaId": "content://media/10",
                f"{prefix}Connected": True,
                f"{prefix}PlaybackState": 3,
                f"{prefix}PlayWhenReady": True,
                f"{prefix}IsPlaying": True,
                f"{prefix}SuppressionReason": 0,
                f"{prefix}HasPlayerError": False,
                f"{prefix}Speed": 1.0,
                f"{prefix}PositionMs": position,
            }
        return record

    def test_valid_record_passes_independent_host_validation(self):
        playback.validate_record(self.record(), self.expected(), "sync")

    def test_transition_or_stalled_position_is_rejected(self):
        transitioned = self.record()
        transitioned["mediaTransitions"] = 1
        with self.assertRaises(playback.PlaybackAuthorityError):
            playback.validate_record(transitioned, self.expected(), "sync")
        stalled = self.record()
        stalled["positionAdvancementMs"] = 999
        with self.assertRaises(playback.PlaybackAuthorityError):
            playback.validate_record(stalled, self.expected(), "sync")

    def test_base64_record_round_trip(self):
        expected = self.record()
        encoded = base64.b64encode(json.dumps(expected).encode()).decode()
        self.assertEqual(expected, playback.parse_record(encoded))

    def test_balanced_session_order_is_fixed(self):
        self.assertEqual(("reference", "development"), playback.SESSION_ORDER[1])
        self.assertEqual(("development", "reference"), playback.SESSION_ORDER[2])
        self.assertEqual(("reference", "development"), playback.SESSION_ORDER[3])

    def test_exact_required_iteration_counts(self):
        self.assertEqual({"unchanged": 10, "add": 5, "rebuild": 5}, playback.JOURNEY_ITERATIONS)

    def test_playback_fixture_is_exact_and_not_a_mutation_target(self):
        dataset = MODULE_PATH.parents[1] / "performance-fixtures" / "generated" / "MEDIUM"
        manifest, _ = playback.sync.load_inputs(dataset)
        fixture = playback.verify_playback_fixture(dataset, manifest)
        self.assertEqual(playback.PLAYBACK_FIXTURE_IDENTITY, fixture["relative_path"])
        self.assertEqual(playback.PLAYBACK_FIXTURE_SHA256, fixture["sha256"])
        self.assertFalse(fixture["mutation_target"])
        self.assertGreaterEqual(fixture["duration_ms"], fixture["minimum_duration_ms"])


if __name__ == "__main__":
    unittest.main()
