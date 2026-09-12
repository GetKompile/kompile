"""Build-free contracts: python3 build-scripts/test-native-payload-producers.py.

Execute only the distribution's staging dispatch with a recording bash function;
Also validate CUDA closure against temporary marker files using only the stager's
validation fragment. No Maven, native-image, native libraries, or distribution
assembly are executed.
"""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = "kompile-app/kompile-app-parent/kompile-app-main"
CLI = "kompile-cli/kompile-cli-main"
WORKERS = (
    "kompile-app/kompile-app-parent/kompile-app-subprocess/kompile-app-subprocess-serving",
    "kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving",
)
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def dispatch(app=False, workers=False, jars=False, server_jars=False,
             staging=False, backend="nd4j-cuda-13.1", classifier="linux-arm64",
             fail_source=""):
    source = (ROOT / "build-dist.sh").read_text()
    fragment = source.split('APP_NATIVE_LIBS=', 1)[1].split(
        'echo "  lib/ ($(du -sh', 1)[0]
    script = '''
SCRIPT_DIR=/contract; DIST_DIR=/contract/dist; PLATFORM=linux-arm64
APP_NATIVE=$1; LOCAL_RUNTIME=$2; JARS_ONLY=$3; SERVER_JARS_ONLY=$4
STAGING_NATIVE=$5; ND4J_BACKEND=$6; SDK_CLASSIFIER=$7; FAIL_SOURCE=$8
# Record the real dispatch arguments without launching its stager.
bash() {
    printf 'CALL|%s|%s|%s|%s|%s|%s\\n' "$@"
    [ "$2" != "$FAIL_SOURCE" ] || return 42
}
''' + 'APP_NATIVE_LIBS=' + fragment
    flags = [str(flag).lower() for flag in (app, workers, jars, server_jars, staging)]
    return subprocess.run(
        ["bash", "-eu", "-o", "pipefail", "-c", script, "contract",
         *flags, backend, classifier, fail_source],
        env={k: os.environ[k] for k in ("PATH", "HOME") if k in os.environ},
        text=True, capture_output=True,
    )


def calls(result):
    return [line.split("|")[1:] for line in result.stdout.splitlines()
            if line.startswith("CALL|")]


# SONAMEs are independent of toolkit minor versions. CUDA 13 retains cuSPARSE 12.
CUDA_CLOSURES = {
    "12": ("libcudart.so.12", "libcublas.so.12", "libcublasLt.so.12",
           "libcusolver.so.11", "libcusparse.so.12", "libnvrtc.so.12",
           "libnvJitLink.so.12"),
    "13": ("libcudart.so.13", "libcublas.so.13", "libcublasLt.so.13",
           "libcusolver.so.12", "libcusparse.so.12", "libnvrtc.so.13",
           "libnvJitLink.so.13"),
}
CUDA_DEPENDENCIES = ("CUDA-runtime", "cuBLAS", "cuBLAS-Lt", "cuSOLVER",
                     "cuSPARSE", "NVRTC", "NVJitLink")


def validate_cuda_closure(backend, libraries):
    source = (ROOT / "kompile-dist/src/main/build/stage-native-libs.sh").read_text()
    _, start, rest = source.partition("require_destination_native() {")
    fragment, end, _ = rest.partition('if [ "${OS}" != windows ]; then')
    assert start and end, "CUDA validation boundaries changed"
    # Do not execute staging, symbol inspection, native binaries or compilers.
    with tempfile.TemporaryDirectory() as directory:
        for library in libraries:
            (Path(directory) / library).touch()
        return subprocess.run(
            ["bash", "-eu", "-o", "pipefail", "-c",
             'OS=linux; DEST_DIR=$1; BACKEND_ARTIFACT=$2\n' + start + fragment,
             "contract", directory, backend],
            env={k: os.environ[k] for k in ("PATH", "HOME") if k in os.environ},
            text=True, capture_output=True,
        )


class NativePayloadProducersTest(unittest.TestCase):
    def test_worker_redists_match_app_activation_version_and_classifier(self):
        artifacts = {"cuda-redist", "cuda-redist-cublas", "cuda-redist-cusolver",
                     "cuda-redist-cusparse"}
        for module in (APP, *WORKERS):
            with self.subTest(module=module):
                pom = ET.parse(ROOT / module / "pom.xml")
                profile = pom.find("m:profiles/m:profile[m:id='cuda-dual-backend']", NS)
                self.assertIsNotNone(profile)
                prop = profile.find("m:activation/m:property", NS)
                self.assertEqual("kompile.cuda", prop.findtext("m:name", namespaces=NS))
                self.assertIsNone(prop.find("m:value", NS))
                redists = [d for d in pom.findall(".//m:dependency", NS)
                           if d.findtext("m:artifactId", default="", namespaces=NS)
                           .startswith("cuda-redist")]
                self.assertEqual(4, len(redists))
                self.assertEqual(artifacts, {d.findtext("m:artifactId", namespaces=NS)
                                             for d in redists})
                for dependency in redists:
                    self.assertIn(dependency, profile.findall("m:dependencies/m:dependency", NS))
                    for field, expected in (("groupId", "org.bytedeco"),
                                            ("version", "${kompile.cuda.redist.version}"),
                                            ("classifier", "${javacpp.platform}")):
                        self.assertEqual(expected, dependency.findtext(f"m:{field}", namespaces=NS))

    def test_cuda12_and_cuda13_complete_closures(self):
        for backend in ("nd4j-cuda-12.6", "nd4j-cuda-12.9", "nd4j-cuda-13.1"):
            for suffix in ("", ".1.0"):
                with self.subTest(backend=backend, suffix=suffix):
                    libraries = [name + suffix for name in CUDA_CLOSURES[backend.split("-")[-1][:2]]]
                    result = validate_cuda_closure(backend, libraries)
                    self.assertEqual(0, result.returncode, result.stderr)

    def test_each_missing_cuda_dependency_is_rejected(self):
        for major, backend in (("12", "nd4j-cuda-12.9"), ("13", "nd4j-cuda-13.1")):
            for missing, dependency in zip(CUDA_CLOSURES[major], CUDA_DEPENDENCIES):
                with self.subTest(backend=backend, missing=missing):
                    libraries = set(CUDA_CLOSURES[major]) - {missing}
                    result = validate_cuda_closure(backend, libraries)
                    self.assertEqual(1, result.returncode)
                    self.assertIn(f"missing {dependency}.", result.stderr)

    def test_cuda13_rejects_cuda12_closure_and_wrong_abi_prefixes(self):
        for libraries in ((), CUDA_CLOSURES["12"],
                          [name + "0" for name in CUDA_CLOSURES["13"]]):
            result = validate_cuda_closure("nd4j-cuda-13.1", libraries)
            self.assertEqual(1, result.returncode)
            self.assertIn("missing CUDA-runtime.", result.stderr)

    def test_worker_native_profiles_match_app_payload_extraction(self):
        def execution(module):
            pom = ET.parse(ROOT / module / "pom.xml")
            return pom.find("m:profiles/m:profile[m:id='native']/m:build/m:plugins/"
                            "m:plugin[m:artifactId='maven-dependency-plugin']/m:executions/"
                            "m:execution[m:id='unpack-native-libs']", NS)

        reference = execution(APP)
        self.assertIsNotNone(reference)
        paths = ("m:phase", "m:goals/m:goal", "m:configuration/m:outputDirectory",
                 "m:configuration/m:includeTypes", "m:configuration/m:includes")
        for worker in WORKERS:
            with self.subTest(worker=worker):
                actual = execution(worker)
                self.assertIsNotNone(actual)
                for path in paths:
                    self.assertEqual(reference.findtext(path, namespaces=NS),
                                     actual.findtext(path, namespaces=NS))
                self.assertIn("shared-runtime-manifest.txt",
                              actual.findtext(paths[-1], namespaces=NS))

    def test_cli_only_cuda_stages_cli_and_both_workers_not_app(self):
        result = dispatch(workers=True)
        self.assertEqual(0, result.returncode, result.stderr)
        rows = calls(result)
        self.assertEqual([f"{module}/target/native-libs" for module in (CLI, *WORKERS)],
                         [row[1] for row in rows])
        for index, row in enumerate(rows):
            self.assertEqual("/contract/kompile-dist/src/main/build/stage-native-libs.sh", row[0])
            self.assertEqual(["/contract/dist/lib", "linux-arm64", "",
                              "none" if index == 0 else "nd4j-cuda-13.1"], row[2:])

    def test_each_included_producer_gate_is_independent(self):
        for app in (False, True):
            for workers in (False, True):
                result = dispatch(app=app, workers=workers, staging=True)
                self.assertEqual(0, result.returncode, result.stderr)
                expected = [CLI] + ([APP] if app else []) + (list(WORKERS) if workers else [])
                self.assertEqual([f"{module}/target/native-libs" for module in expected],
                                 [row[1] for row in calls(result)])

    def test_jar_tiers_do_not_stage_native_service_payloads(self):
        for jars, server_jars in ((True, False), (False, True), (True, True)):
            result = dispatch(app=True, workers=True, jars=jars, server_jars=server_jars)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([], calls(result))

    def test_missing_worker_payload_fails_without_app_or_cache_fallback(self):
        result = dispatch(workers=True, fail_source=f"{WORKERS[0]}/target/native-libs")
        self.assertEqual(42, result.returncode)
        self.assertEqual(2, len(calls(result)))

    def test_qualified_backend_classifier_is_preserved(self):
        result = dispatch(workers=True, backend="nd4j-zluda-rocm-6.4",
                          classifier="linux-arm64-rocm-6.4")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(all(row[4] == "-rocm-6.4" for row in calls(result)))


if __name__ == "__main__":
    unittest.main()
