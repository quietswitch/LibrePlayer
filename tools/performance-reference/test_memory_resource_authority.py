import base64
from pathlib import Path
import importlib.util
import json
import unittest


PATH = Path(__file__).with_name("memory_resource_authority.py")
SPEC = importlib.util.spec_from_file_location("memory_resource_authority", PATH)
authority = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(authority)

RAW = """journey=songs
label=baseline
elapsedRealtimeMs=12345
           Java Heap:    20020                          37356
         Native Heap:    13356                          14316
                Code:    40264                         127064
               Stack:     1096                           1104
            Graphics:        0                              0
       Private Other:    17428
              System:    11430
           TOTAL PSS:   103594            TOTAL RSS:   199968       TOTAL SWAP PSS:      414
               Views:        8         ViewRootImpl:        1
         AppContexts:        7           Activities:        1
              Assets:       20        AssetManagers:        0
       Local Binders:       34        Proxy Binders:       84
       Parcel memory:       13         Parcel count:       50
    Death Recipients:        1             WebViews:        0
   Bitmap (malloced):       16                             68
Threads:\t48
fdProbe=ls: /proc/3478/fd: Permission denied
      package=com.libreplayer
      controllers: 9
      state=PlaybackState {state=PLAYING(3)}
      queueTitle=null, size=20
  * ServiceRecord{abc u0 com.libreplayer/.media.service.PlaybackService c:com.libreplayer}
    isForeground=true foregroundId=1001
"""


class MemoryResourceAuthorityTests(unittest.TestCase):
    def test_parse_actual_api_36_fields(self):
        parsed = authority.parse_checkpoint(RAW)
        self.assertEqual(parsed["total_pss_kb"], 103594)
        self.assertEqual(parsed["total_rss_kb"], 199968)
        self.assertEqual(parsed["threads"], 48)
        self.assertIsNone(parsed["fd_count"])
        self.assertEqual(parsed["media_session_queue_size"], 20)
        self.assertTrue(parsed["playback_active"])
        self.assertTrue(parsed["playback_service_foreground"])

    def test_extract_base64_record(self):
        encoded = base64.b64encode(RAW.encode()).decode()
        parsed = authority.extract_checkpoints(f"I/LibrePlayerMemory: checkpoint={encoded}")
        self.assertEqual(len(parsed), 1)
        self.assertEqual(parsed[0]["label"], "baseline")

    def test_growth_plateau_and_ratchet(self):
        plateau = [{"total_pss_kb": value} for value in (100_000, 110_000, 111_000, 109_000)]
        ratchet = [{"total_pss_kb": value} for value in (100_000, 110_000, 125_000, 140_000, 150_000)]
        self.assertEqual(authority.growth(plateau, "total_pss_kb")["classification"], "warm/plateau")
        self.assertEqual(authority.growth(ratchet, "total_pss_kb")["classification"], "ratchet")

    def test_curated_baseline_contract(self):
        baseline = json.loads((PATH.parents[2] / "performance-baselines" / "memory-resource-v1.0.4.json").read_text(encoding="utf-8"))
        self.assertEqual(baseline["authority"], authority.DISCLAIMER)
        self.assertEqual(baseline["dataset"]["fingerprint_sha256"], authority.EXPECTED_FINGERPRINT)
        self.assertEqual(baseline["method"]["checkpoints"], 264)
        self.assertIn("UNAVAILABLE", baseline["sources"]["file_descriptors"])
        self.assertEqual(baseline["apk_size"]["delta_bytes"], 0)
        self.assertEqual(set(baseline["journeys"]), set(authority.JOURNEYS))
