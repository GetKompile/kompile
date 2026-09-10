#!/usr/bin/env python3
"""Exercise the real wrapper with temporary stub producers; no SDK/build required."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


WRAPPER = Path(__file__).resolve().parents[1] / "build-tensor-g3-offline-apk.sh"
STUB = '''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys
print("CALL " + json.dumps([Path(sys.argv[0]).name, *sys.argv[1:]]))
if "--print-config" in sys.argv:
    root = Path(sys.argv[sys.argv.index("--output-root") + 1])
    root.mkdir(parents=True, exist_ok=True)
    (root / "resolved-build-config.properties").write_text(
        "android_ndk=/stub/ndk\\ngraalvm_home=/stub/graal\\n")
name = Path(sys.argv[0]).name
if name in ("build-android-sdx-sdk.sh", "prune-android-sdx-build-cache.sh"):
    print("RETENTION " + json.dumps([os.environ.get(key) for key in (
        "SDX_ANDROID_GENERATION_RETENTION", "SDX_ANDROID_MANAGED_STAGE_RETENTION",
        "SDX_ANDROID_OBJECT_STAGE_RETENTION")]))
    if "--print-config" not in sys.argv:
        fd = int(os.environ["SDX_ANDROID_PIPELINE_LOCK_FD"])
        os.fstat(fd)  # actual inherited descriptor, not just a Boolean flag
if (name == "build-offline-accelerators.sh" and "--variant" in sys.argv
        or name == "build-android-sdx-sdk.sh" and "all" in sys.argv):
    counter = Path(sys.argv[0]).with_suffix(".calls")
    count = int(counter.read_text()) + 1 if counter.exists() else 1
    counter.write_text(str(count))
    key = "TEST_PUBLISH_STATUSES" if name == "build-offline-accelerators.sh" else "TEST_SDK_STATUSES"
    statuses = os.environ.get(key, "0").split(",")
    sys.exit(int(statuses[min(count - 1, len(statuses) - 1)]))
'''


class TensorG3BuildJobsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="tensor-g3-jobs-test-")
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        kompile = root / "kompile"
        dl4j = root / "deeplearning4j"
        android = kompile / "kompile-chat-local/mobile/android"
        android.mkdir(parents=True)
        self.wrapper = android / WRAPPER.name
        shutil.copyfile(WRAPPER, self.wrapper)
        self.work = android / "build/sdx-android-build"
        graph = kompile / "kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local"
        maven = root / "mvn"
        for stub in (
            dl4j / "nd4j/sdx-aot/src/main/android/build-android-sdx-sdk.sh",
            dl4j / "nd4j/sdx-aot/src/main/android/prune-android-sdx-build-cache.sh",
            dl4j / "libnd4j/tools/mobile/build-android-accelerator.sh",
            android / "tools/build-offline-accelerators.sh",
            graph / "build-android-ndk.sh",
            maven,
        ):
            stub.parent.mkdir(parents=True, exist_ok=True)
            stub.write_text(STUB)
            stub.chmod(0o755)
        library = graph / "target/android-aot/jni/arm64-v8a/libkompile_reasoning_android.so"
        library.parent.mkdir(parents=True)
        library.write_text("stub")
        support = self.work / "graph-aot-work/clibraries/bionic"
        support.mkdir(parents=True)
        for name in ("libjvm.a", "liblibchelper.a", "libjava.a", "libnet.a", "libnio.a",
                     "libzip.a", "libprefs.a", "libextnet.a", "jdk-support-receipt"):
            (support / name).write_text("stub")
        self.env = os.environ.copy()
        self.env.pop("BUILD_JOBS", None)
        self.env["SDX_MAVEN"] = str(maven)

    def run_wrapper(self, *args, jobs=None):
        env = self.env.copy()
        if jobs is not None:
            env["BUILD_JOBS"] = jobs
        result = subprocess.run(
            ["bash", str(self.wrapper), *args], env=env,
            text=True, capture_output=True, timeout=10,
        )
        calls = [json.loads(line[5:]) for line in result.stdout.splitlines()
                 if line.startswith("CALL ")]
        return result, calls

    def assert_jobs(self, expected, *args, jobs=None):
        result, calls = self.run_wrapper(*args, jobs=jobs)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"native/Graal jobs: {expected}", result.stdout)
        sdk = [call for call in calls if call[0] == "build-android-sdx-sdk.sh"]
        accelerator = [call for call in calls if call[0] == "build-android-accelerator.sh"]
        maven = [call for call in calls if call[0] == "mvn"]
        self.assertEqual(["aot", "all"], [call[1] for call in sdk])
        self.assertEqual(1, len(accelerator))
        for call in sdk + accelerator:
            self.assertEqual(expected, call[call.index("--jobs") + 1], call)
        self.assertEqual(1, len(maven))
        self.assertIn(f"-Dkompile.android.jobs={expected}", maven[0])
        self.assertEqual("build-offline-accelerators.sh", calls[-1][0])
        self.assertIn("--variant", calls[-1])

    def test_cleanup_keeps_one_unpublished_aot_object(self):
        result, calls = self.run_wrapper()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        cleanup = [call for call in calls if call[0] == "prune-android-sdx-build-cache.sh"]
        self.assertTrue(cleanup)
        policies = [json.loads(line[len("RETENTION "):]) for line in result.stdout.splitlines()
                    if line.startswith("RETENTION ")]
        self.assertGreaterEqual(len(policies), 3)
        self.assertTrue(all(policy == ["1", "0", "1"] for policy in policies), policies)
        for call in cleanup:
            self.assertEqual("1", call[call.index("--retain-object-stages") + 1], call)
            self.assertEqual("1", call[call.index("--retain-generations") + 1], call)

    def test_symlink_pipeline_lock_is_rejected_without_truncation(self):
        lock_root = self.work / ".locks"
        lock_root.mkdir()
        sentinel = self.work / "keep"
        sentinel.write_text("do not truncate")
        (lock_root / "tensor-g3-offline-apk.lock").symlink_to(sentinel)
        result, calls = self.run_wrapper()
        self.assertEqual(3, result.returncode, result.stdout + result.stderr)
        self.assertIn("lock must not be a symlink", result.stderr)
        self.assertEqual("do not truncate", sentinel.read_text())
        self.assertEqual([], calls)

    def test_default_is_twelve_and_reaches_all_producers(self):
        self.assert_jobs("12")

    def test_empty_environment_uses_default(self):
        self.assert_jobs("12", jobs="")

    def test_environment_override(self):
        self.assert_jobs("8", jobs="8")

    def test_cli_override_wins_over_environment(self):
        self.assert_jobs("12", "--jobs", "12", jobs="2")

    def test_equals_override_wins_even_over_invalid_environment(self):
        self.assert_jobs("6", "--jobs=6", jobs="invalid")

    def test_invalid_counts_fail_before_any_builder_or_cleanup(self):
        cases = [(("--jobs", value), None) for value in
                 ("0", "-1", "1.5", "abc", "", "01", "2 3", "--resume-publish")]
        cases += [(("--jobs",), None), (("--jobs=",), None), (("--jobs=-2",), None)]
        cases += [((), value) for value in ("0", "-1", "abc")]
        for args, jobs in cases:
            with self.subTest(args=args, jobs=jobs):
                result, calls = self.run_wrapper(*args, jobs=jobs)
                self.assertEqual(3, result.returncode, result.stdout + result.stderr)
                self.assertIn("positive integer", result.stderr)
                self.assertEqual([], calls)
                self.assertFalse((self.work / ".locks").exists())

    def test_resume_keeps_historical_producers(self):
        result, calls = self.run_wrapper("--jobs", "12", "--resume-publish")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["build-offline-accelerators.sh"] * 2, [call[0] for call in calls])
        self.assertIn("--reuse-receipted-producers", calls[-1])

    def test_stale_aot_rebuilds_once_without_graph_or_cleanup_repeat(self):
        self.env["TEST_PUBLISH_STATUSES"] = "42,0"
        result, calls = self.run_wrapper("--jobs", "8")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        sdk = [c for c in calls if c[0] == "build-android-sdx-sdk.sh" and c[1] == "all"]
        self.assertEqual(2, len(sdk))
        for call in sdk:
            self.assertEqual("8", call[call.index("--jobs") + 1])
        self.assertEqual(1, sum(c[0] == "mvn" for c in calls))
        self.assertEqual(1, sum(c[0] == "prune-android-sdx-build-cache.sh" for c in calls))
        self.assertEqual(2, sum(c[0] == "build-android-accelerator.sh" for c in calls))
        self.assertEqual(2, sum("--variant" in c for c in calls))
        self.assertFalse(any("--reuse-receipted-producers" in c for c in calls))

    def test_persistent_staleness_stops_after_one_repair(self):
        self.env["TEST_PUBLISH_STATUSES"] = "42"
        result, calls = self.run_wrapper()
        self.assertEqual(42, result.returncode)
        self.assertEqual(2, sum("--variant" in c for c in calls))
        self.assertEqual(2, sum(c[0] == "build-android-sdx-sdk.sh" and c[1] == "all" for c in calls))

    def test_unrelated_packaging_failure_is_not_retried(self):
        self.env["TEST_PUBLISH_STATUSES"] = "1"
        result, calls = self.run_wrapper()
        self.assertEqual(1, result.returncode)
        self.assertEqual(1, sum("--variant" in c for c in calls))
        self.assertEqual(1, sum(c[0] == "build-android-sdx-sdk.sh" and c[1] == "all" for c in calls))

    def test_repair_build_failure_stops_before_repackaging(self):
        self.env["TEST_PUBLISH_STATUSES"] = "42,0"
        self.env["TEST_SDK_STATUSES"] = "0,9"
        result, calls = self.run_wrapper()
        self.assertEqual(9, result.returncode)
        self.assertEqual(1, sum("--variant" in c for c in calls))
        self.assertEqual(1, sum(c[0] == "build-android-accelerator.sh" for c in calls))

    def test_resume_does_not_rebuild_on_receipt_failure(self):
        self.env["TEST_PUBLISH_STATUSES"] = "42"
        result, calls = self.run_wrapper("--resume-publish")
        self.assertEqual(42, result.returncode)
        self.assertFalse(any(c[0] == "build-android-sdx-sdk.sh" for c in calls))
        self.assertEqual(1, sum("--variant" in c for c in calls))

    def test_real_packager_preserves_receipt_failure_status(self):
        packager = (WRAPPER.parent / "tools/build-offline-accelerators.sh").read_text()
        start = packager.index("verify_sdx_aot_sdk_receipt || {")
        end = packager.index("\n}", start) + 2
        for status in (1, 42):
            with self.subTest(status=status):
                result = subprocess.run(
                    ["bash", "-c", "set -euo pipefail\n"
                     + f"verify_sdx_aot_sdk_receipt() {{ return {status}; }}\n"
                     + packager[start:end]], capture_output=True, text=True, timeout=5)
                self.assertEqual(status, result.returncode)
        self.assertIn('"$expected_source" || return 42', packager)
        self.assertIn('different $artifact source" >&2\n        return 42', packager)

    def test_help_documents_override_without_starting_builders(self):
        result, calls = self.run_wrapper("--help")
        self.assertEqual(0, result.returncode)
        self.assertIn("--jobs > BUILD_JOBS > 12", result.stdout)
        self.assertEqual([], calls)
        self.assertFalse((self.work / ".locks").exists())


if __name__ == "__main__":
    unittest.main()
