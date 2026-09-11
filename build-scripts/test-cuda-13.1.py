"""Build-free CUDA distribution contracts. Run: python3 build-scripts/test-cuda-13.1.py.

Only configuration fragments and resolver functions execute. Maven, DL4J source
loading, native-image, SDK collection and distribution assembly never run.
"""
import os
from pathlib import Path
import re
import subprocess
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
COMMON = ROOT / "build-scripts/build-common.sh"
DIST = ROOT / "build-dist.sh"
WRAPPER = ROOT / "build-scripts/build-kompile-cuda-13.1.sh"


def shell(script, *args):
    # Do not inherit backend, repository, or source-checkout overrides.
    env = {key: os.environ[key] for key in ("PATH", "HOME") if key in os.environ}
    env.update({"LC_ALL": "C", "DL4J_PROJECT_ROOT": "/nonexistent-kompile-contract-dl4j"})
    return subprocess.run(
        ["bash", "-eu", "-o", "pipefail", "-c", script, *map(str, args)],
        env=env, text=True, capture_output=True, check=False,
    )


def dist_config(variant, *options):
    # Stop before the first repository scan or build/package operation.
    prefix, marker, _ = DIST.read_text().partition("# Mixed-vintage guard:")
    assert marker, "distribution configuration boundary changed"
    return shell(prefix + r'''
printf 'CONTRACT|%s\n' "$ND4J_BACKEND" "$KOMPILE_BACKEND_PROFILE" \
    "$CUDA_VERSION" "$SDK_CLASSIFIER" "$DISTRIBUTION_CLASSIFIER" \
    "$LOCAL_RUNTIME" "$CLI_NATIVE" "$APP_NATIVE" "$STAGING_NATIVE" \
    "$BUNDLE_RUNTIME" "$INCLUDE_PRODUCT_EXTRAS" "$SERVER_JARS_ONLY"
printf 'ARG|%s\n' "${MAVEN_BUILD_ARGS[@]}"
''', DIST, variant, "--version", "contract", *options)


def values(result, prefix="CONTRACT|"):
    return [line[len(prefix):] for line in result.stdout.splitlines() if line.startswith(prefix)]


class Cuda131ContractTest(unittest.TestCase):
    def test_plain_arm64_cli_has_native_workers_and_exact_maven_backend(self):
        result = dist_config("cli-only", "--platform", "linux-arm64",
                             "--backend-profile", "cuda-13.1")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([
            "nd4j-cuda-13.1", "cuda-13.1", "13.1", "linux-arm64",
            "cli-only-linux-arm64-cuda-13.1", "true", "true", "false",
            "false", "false", "false", "false",
        ], values(result))
        args = values(result, "ARG|")
        for arg in ("--no-snapshot-updates", "-Dnd4j.backend=nd4j-cuda-13.1",
                    "-Dkompile.backend=cuda-13.1", "-Djavacpp.platform=linux-arm64",
                    "-Dkompile.cuda=true"):
            self.assertIn(arg, args)
        self.assertNotIn("-compile", " ".join(args))

    def test_cuda_version_and_old_default_are_preserved(self):
        for version in ("12.6", "12.9", "13.1"):
            with self.subTest(version=version):
                result = dist_config("cuda", "--platform", "linux-arm64",
                                     "--cuda-version", version)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual([f"nd4j-cuda-{version}", f"cuda-{version}", version,
                                  "linux-arm64", f"cuda-linux-arm64-cuda-{version}"],
                                 values(result)[:5])
        self.assertEqual("nd4j-cuda-12.9", values(dist_config("cuda"))[0])
        self.assertNotEqual(0, dist_config("cuda", "--cuda-version", "99.0").returncode)

    def test_no_unattested_13_helpers_but_old_helpers_still_work(self):
        for version in ("12.6", "12.9", "13.1"):
            for helper in ("compile", "cudnn"):
                with self.subTest(version=version, helper=helper):
                    result = dist_config("cli-only", "--platform", "linux-x86_64",
                                         "--backend-profile", f"cuda-{version}-{helper}")
                    if version == "13.1":
                        self.assertNotEqual(0, result.returncode)
                        self.assertIn("Unsupported backend profile", result.stderr)
                    else:
                        self.assertEqual(0, result.returncode, result.stderr)
                        self.assertEqual(f"linux-x86_64-{helper}", values(result)[3])

    def test_catalog_and_resolvers_keep_13_plain(self):
        result = shell(r'''
source "$1"
for platform in linux-arm64-cuda-13.1 linux-x86_64-cuda-13.1; do
    kompile_validate_platform "$platform"
    _resolve_backend_from_platform "$platform"
    _resolve_javacpp_platform "$platform"
    _resolve_sdk_classifier "$platform"
done
if kompile_validate_platform linux-arm64-cuda-13.1-compile; then exit 90; fi
if kompile_validate_platform linux-arm64-cuda-13.1-cudnn; then exit 91; fi
''', "contract", COMMON)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([
            "cuda|13.1|cuda-13.1", "linux-arm64", "linux-arm64",
            "cuda|13.1|cuda-13.1", "linux-x86_64", "linux-x86_64",
        ], result.stdout.splitlines()[:6])

    def test_sdk_collection_selects_13_artifacts_and_current_group(self):
        source = COMMON.read_text()
        selection = source.split("  local -a sdk_artifact_ids\n", 1)[1].split(
            '  for namespace in "${sdk_namespaces[@]}";', 1)[0]
        # This isolated case block performs only array/variable assignments.
        result = shell('select_sdk() {\nlocal platform=linux-arm64-cuda-13.1\n' + selection +
                       '\nprintf "%s\\n" "${sdk_artifact_ids[@]}" "${sdk_namespaces[@]}"\n}\nselect_sdk')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([
            "nd4j-cuda-13.1", "nd4j-cuda-13.1-preset", "nd4j-cuda-13.1-platform",
            "org/eclipse/deeplearning4j",
        ], result.stdout.splitlines())
        self.assertIn("cuda-13.1) backend_artifact=nd4j-cuda-13.1 ;;", source)
        self.assertIn("*cuda-13.1) args+=(--cuda-version 13.1) ;;", source)

    def test_cli_only_does_not_require_separate_sdx_packages(self):
        validator = ROOT / "kompile-dist/src/main/build/validate-sdx-assets.sh"
        # cli-only returns before any filesystem scan: JNI closure is staged
        # separately from the platform JARs, not from a source-built SDX shard.
        result = shell('bash "$1" /nonexistent-kompile-contract-sdk cli-only '
                       'linux-arm64 1.0.0-SNAPSHOT 13.1 nd4j-cuda-13.1 linux-arm64',
                       "contract", validator)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_pom_profile_pins_redist_without_classifier_extension(self):
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        pom = ET.parse(ROOT / "pom.xml")
        profiles = {p.findtext("m:id", namespaces=ns): p
                    for p in pom.findall("m:profiles/m:profile", ns)}
        profile = profiles["backend-cuda-13.1"]
        self.assertEqual("cuda-13.1", profile.findtext(
            "m:activation/m:property/m:value", namespaces=ns))
        props = profile.find("m:properties", ns)
        self.assertEqual("nd4j-cuda-13.1", props.findtext("m:nd4j.backend", namespaces=ns))
        self.assertEqual("13.1-9.19-1.5.13", props.findtext(
            "m:kompile.cuda.redist.version", namespaces=ns))
        self.assertEqual("", props.findtext("m:javacpp.platform.extension", namespaces=ns))
        self.assertEqual("${javacpp.platform}-cuda-13.1", props.findtext(
            "m:kompile.distribution.platform.classifier", namespaces=ns))
        self.assertNotIn("backend-cuda-13.1-compile", profiles)
        for version in ("12.6", "12.9"):
            for suffix in ("", "-cudnn", "-compile"):
                self.assertIn(f"backend-cuda-{version}{suffix}", profiles)

    def test_wrapper_detects_host_and_forwards_skip_flags_without_building(self):
        # Intercept exec, so the real platform driver cannot build or install.
        script = r'''
uname() { case "$1" in -s) printf '%s\n' "$TEST_OS" ;; -m) printf '%s\n' "$TEST_ARCH" ;; esac; }
exec() { printf 'CALL|%s\n' "$@"; printf 'MODE|%s\n' "$VARIANT" "$NATIVE_TARGETS"; }
TEST_OS="$1"; TEST_ARCH="$2"; wrapper="$3"; shift 3
source "$wrapper"
'''
        for arch in ("aarch64", "arm64", "x86_64", "amd64"):
            with self.subTest(arch=arch):
                result = shell(script, "contract", "Linux", arch, WRAPPER,
                               "--skip-dl4j", "--skip-java")
                self.assertEqual(0, result.returncode, result.stderr)
                expected = "arm64" if arch in ("aarch64", "arm64") else "x86_64"
                self.assertEqual([
                    "bash", str(COMMON.parent / "build-kompile-platform.sh"),
                    f"linux-{expected}-cuda-13.1", "--skip-dl4j", "--skip-java",
                ], values(result, "CALL|"))
                self.assertEqual(["cuda", "all"], values(result, "MODE|"))
        result = shell(script, "contract", "Darwin", "arm64", WRAPPER)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("CALL|", result.stdout)

    def test_cli_worker_reactor_and_native_gates_remain_backend_driven(self):
        source = DIST.read_text()
        reactor = source.split('    JAVA_BUILD_MODULES=""', 1)[1].split(
            '    if [ -z "${JAVA_BUILD_MODULES}" ];', 1)[0]
        result = shell('VARIANT=cli-only; JARS_ONLY=false; LOCAL_RUNTIME=true\n' +
                       reactor + '\nprintf "%s\\n" "$JAVA_BUILD_MODULES"')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            ":kompile-cli-main,:kompile-model-cli,:kompile-agent-cli,"
            ":kompile-app-subprocess-serving,:kompile-pipeline-serving\n", result.stdout)
        self.assertRegex(source, re.compile(
            r'if \[ "\$\{LOCAL_RUNTIME\}" = true \] && \[ "\$\{SERVER_JARS_ONLY\}" = false \]; then'
            r'.*?kompile-model-serving.*?kompile-pipeline-serving.*?"\$\{MAVEN_BUILD_ARGS\[@\]\}"',
            re.DOTALL))


if __name__ == "__main__":
    unittest.main()
