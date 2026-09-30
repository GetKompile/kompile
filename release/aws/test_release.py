import importlib.util
import contextlib
import hashlib
import io
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import MagicMock, Mock, patch

ROOT = pathlib.Path(__file__).resolve().parent
REPOSITORY = ROOT.parents[1]
SPEC = importlib.util.spec_from_file_location("kompile_aws_release", ROOT / "release.py")
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)
BUILD_SPEC = importlib.util.spec_from_file_location("kompile_aws_build", ROOT / "build-platform.py")
BUILD_MODULE = importlib.util.module_from_spec(BUILD_SPEC)
assert BUILD_SPEC.loader
BUILD_SPEC.loader.exec_module(BUILD_MODULE)
JAVA_MATRIX_SPEC = importlib.util.spec_from_file_location(
    "kompile_java_matrix", REPOSITORY / "release" / "github" / "java_matrix.py")
JAVA_MATRIX = importlib.util.module_from_spec(JAVA_MATRIX_SPEC)
assert JAVA_MATRIX_SPEC.loader
JAVA_MATRIX_SPEC.loader.exec_module(JAVA_MATRIX)
# The full commit every Java distribution fixture is built from.
JAVA_COMMIT = "0123456789abcdef0123456789abcdef01234567"


def record_java_distribution(directory, row, version="1.2.3", commit=JAVA_COMMIT, run_id="7", data=None):
    """Writes a row's archive, sidecar and variant.json into directory, as the variant job does."""
    name = JAVA_MATRIX.archive_name(version, row)
    directory.mkdir(parents=True, exist_ok=True)
    archive = directory / name
    archive.write_bytes(data if data is not None else f"{row['distributionClassifier']} archive\n".encode())
    checksum = directory / f"{name}.sha256"
    checksum.write_text(f"{hashlib.sha256(archive.read_bytes()).hexdigest()}  {name}\n", encoding="utf-8")
    errors = io.StringIO()
    with patch.dict(os.environ, {"ROW": json.dumps(row)}), \
            contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(errors):
        status = JAVA_MATRIX.main([
            "record", "--row-env", "ROW", "--version", version,
            "--archive", str(archive), "--checksum", str(checksum),
            "--commit", commit, "--repository", "GetKompile/kompile",
            "--run-id", run_id, "--run-attempt", "1",
            "--output", str(directory / "variant.json"),
        ])
    if status:
        raise AssertionError(f"recording {name} failed: {errors.getvalue()}")
    return directory / "variant.json"


class ReleasePlanTest(unittest.TestCase):
    def setUp(self):
        self.plan = MODULE.load_plan(ROOT / "release-plan.json")
        self.shards = {item["id"]: item for item in self.plan["shards"]}

    def test_covers_current_workflows(self):
        self.assertEqual({
            "release.yml", "publish-release.yml", "publish-external-aws-release.yml",
            "build-native-linux-x86_64.yml", "build-native-linux-arm64.yml",
            "build-native-linux-cuda.yml", "build-native-windows-x86_64.yml",
            "build-native-windows-cuda.yml", "build-native-mac-arm64.yml",
            "build-kompile-chat-local-android.yml",
        }, set(self.plan["coveredWorkflows"]))

    def test_compile_matrix_never_uses_gpu_instances(self):
        for shard in self.plan["shards"]:
            family = shard["instanceType"].split(".", 1)[0]
            self.assertFalse(family.startswith(MODULE.GPU_INSTANCE_PREFIXES), shard["id"])

    def test_every_lane_has_verified_ami_and_serial_host_reuse(self):
        for shard in self.plan["shards"]:
            query = shard["amiQuery"]
            self.assertTrue(query["owners"], shard["id"])
            self.assertTrue(query["name"], shard["id"])
            self.assertTrue(query["architecture"], shard["id"])
            self.assertTrue(shard["reuseHostForVariants"], shard["id"])
        self.assertEqual(len(self.plan["shards"]), len(MODULE.execution_shards(self.plan)))

    def test_required_workloads_and_platforms_exist(self):
        workloads = {workload for shard in self.plan["shards"] for workload in shard["workloads"]}
        self.assertEqual({"maven", "sdk", "distribution", "android"}, workloads)
        platforms = {shard["build"]["javacppPlatform"] for shard in self.plan["shards"]}
        self.assertTrue({"linux-x86_64", "linux-arm64", "windows-x86_64", "macosx-arm64", "android-arm64"} <= platforms)

    def test_distribution_variants_and_chips_are_complete(self):
        self.assertEqual(MODULE.DISTRIBUTION_VARIANTS,
                         set(self.plan["buildCoverage"]["distributionVariants"]))
        variants = {
            variant["name"]: variant["chip"]
            for shard in self.plan["shards"] if shard["build"]["kind"] == "distribution"
            for variant in shard["build"]["variants"]
        }
        self.assertEqual({
            "cli-only": "none", "full": "cpu", "hosted": "none",
            "cpu-intel": "cpu-avx2", "cpu-arm": "cpu-arm64",
            "cuda": "cuda-12.9", "amd-zluda": "amd-zluda",
        }, variants)
        zluda = next(
            variant
            for shard in self.plan["shards"] if shard["build"]["kind"] == "distribution"
            for variant in shard["build"]["variants"]
            if variant["name"] == "amd-zluda"
        )
        self.assertEqual("linux-x86_64-zluda", zluda["dl4jLane"])
        self.assertEqual("cuda-12.9", zluda["dl4jVariant"])
        self.assertEqual(
            "linux-x86_64-zluda-rocm-7.2.4", zluda["sdkClassifier"],
        )
        self.assertFalse(zluda["requireSdk"])
        for shard in self.plan["shards"]:
            if shard["build"]["kind"] == "distribution":
                self.assertIn("maven", shard["workloads"], shard["id"])

    def test_rocm_10_is_an_explicit_linux_candidate_platform(self):
        shard = self.shards["platform-linux-x86_64-zluda-rocm-10.0.0"]
        self.assertEqual("linux", shard["os"])
        self.assertEqual("platform", shard["build"]["kind"])
        self.assertEqual("linux-x86_64-zluda-rocm-10.0.0", shard["build"]["dl4jLane"])
        self.assertFalse(shard["build"]["requireSdk"])
        self.assertEqual([{
            "name": "cuda-12.9",
            "classifier": "linux-x86_64-cuda-12.9-zluda-rocm-10.0.0",
            "kompileVariant": "amd-zluda",
            "requireSdk": False,
        }], shard["build"]["variants"])
        self.assertNotIn(
            "platform-windows-x86_64-zluda-rocm-10.0.0", self.shards,
        )

    def test_cpu_classifier_matrix_matches_dl4j_release(self):
        common = {
            "base", "avx2", "avx512", "onednn", "onednn-avx2",
            "onednn-avx512", "compile",
        }
        linux = {
            item["name"]
            for item in self.shards["native-linux-x86_64"]["build"]["variants"]
        }
        windows = {
            item["name"]
            for item in self.shards["native-windows-x86_64"]["build"]["variants"]
        }
        self.assertEqual(common | {"compile-avx2", "compile-avx512"}, linux)
        self.assertEqual(common, windows)

    def test_parent_and_classifier_selection(self):
        parent = MODULE.selected_executions(self.plan, ["native-linux-x86_64"])
        self.assertEqual(1, len(parent))
        self.assertEqual(9, len(parent[0]["build"]["variants"]))
        classifier = MODULE.selected_executions(self.plan, ["native-linux-x86_64--avx2"])
        self.assertEqual("native-linux-x86_64--avx2", classifier[0]["id"])
        self.assertEqual(["avx2"], [item["name"] for item in classifier[0]["build"]["variants"]])

    def test_cuda_versions_platforms_and_classifiers(self):
        cuda = [
            item for item in self.plan["shards"]
            if item["build"]["backend"] == "cuda"
            and "zluda" not in str(item["build"].get("dl4jLane", ""))
        ]
        self.assertEqual({("linux", "12.6"), ("linux", "12.9"),
                          ("windows", "12.6"), ("windows", "12.9")},
                         {(item["os"], item["build"]["cudaVersion"]) for item in cuda})
        for shard in cuda:
            self.assertEqual({"base", "cudnn", "compile"},
                             {item["name"] for item in shard["build"]["variants"]})
            if shard["os"] == "linux":
                self.assertTrue(shard["containerImage"].startswith("nvidia/cuda:"))

    def test_native_cli_targets_cover_every_supported_binary(self):
        self.assertEqual(MODULE.KOMPILE_NATIVE_TARGETS,
                         set(self.plan["buildCoverage"]["kompileNativeTargets"]))
        source = (ROOT / "build-platform.py").read_text(encoding="utf-8")
        for target in MODULE.KOMPILE_NATIVE_TARGETS:
            self.assertIn(target, source)
        self.assertIn(
            "maven", self.shards["kompile-native-linux-x86_64"]["workloads"],
        )

    def test_macos_is_one_consolidated_host(self):
        mac = [item for item in self.plan["shards"] if item["os"] == "macos"]
        self.assertEqual(1, len(mac))
        self.assertEqual("mac2-m2pro.metal", mac[0]["instanceType"])
        self.assertTrue(mac[0]["dedicatedHost"])
        self.assertEqual({"maven", "sdk", "distribution"}, set(mac[0]["workloads"]))
        self.assertEqual({"base", "compile"},
                         {item["name"] for item in mac[0]["build"]["nativeVariants"]})

    def test_android_matches_active_github_actions_build(self):
        android = self.shards["android-chat-local"]
        self.assertEqual("debug-apk", self.plan["buildCoverage"]["androidArtifact"])
        self.assertEqual(["debug-apk"], [item["name"] for item in android["build"]["variants"]])
        source = (ROOT / "build-platform.py").read_text(encoding="utf-8")
        self.assertIn(":app:assembleDebug", source)
        self.assertNotIn(":app:assembleRelease", source)

    def test_exact_dl4j_release_driver_is_delegated(self):
        source = (ROOT / "build-platform.py").read_text(encoding="utf-8")
        self.assertIn('"release" / "aws" / "build-platform.py"', source)
        self.assertNotIn("build-scripts/release", source)

    def test_start_accepts_prebuilt_dl4j_repository_configuration(self):
        args = MODULE.parser().parse_args([
            "start", "--version", "0.1.0-SNAPSHOT", "--commit", "a" * 40,
            "--dl4j-maven-repository-url", "https://repo.example/snapshots",
            "--dl4j-maven-repository-id", "shared-release",
            "--dl4j-sdk-assets-url", "https://downloads.example/{lane}.tar.gz",
        ])
        self.assertEqual(
            "https://repo.example/snapshots", args.dl4j_maven_repository_url,
        )
        self.assertEqual("shared-release", args.dl4j_maven_repository_id)
        self.assertEqual(
            "https://downloads.example/{lane}.tar.gz", args.dl4j_sdk_assets_url,
        )

    def test_kill_switch_and_log_retention(self):
        self.assertEqual("/kompile/release/kill-switch", self.plan["killSwitchParameter"])
        self.assertEqual(30, self.plan["logRetentionDays"])


class BuildPlatformParityTest(unittest.TestCase):
    def test_dl4j_checkout_fetches_controller_pinned_commit(self):
        commit = "b" * 40
        config = {
            "dl4jRepository": "https://example.test/deeplearning4j.git",
            "dl4jBranch": "release/snapshot",
            "dl4jCommit": commit,
        }
        completed = Mock(stdout=commit + "\n")
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(BUILD_MODULE, "run") as run, \
                patch.object(
                    BUILD_MODULE.subprocess, "run",
                    return_value=completed,
                ):
            checkout = BUILD_MODULE.ensure_dl4j_checkout(
                config, pathlib.Path(temporary)
            )
        self.assertEqual(
            pathlib.Path(temporary) / "deeplearning4j", checkout
        )
        commands = [item.args[0] for item in run.call_args_list]
        self.assertIn(
            ["git", "fetch", "--depth=1", "origin", commit],
            commands,
        )
        self.assertIn(
            ["git", "checkout", "--detach", commit],
            commands,
        )
        self.assertFalse(
            any(
                any("refs/heads/" in str(argument) for argument in command)
                for command in commands
            )
        )

    def test_sdk_jars_are_hydrated_from_configured_snapshot_repository(self):
        config = {
            "snapshotVersion": "1.0.0-SNAPSHOT",
            "dl4jMavenRepositoryUrl": (
                "https://central.sonatype.com/repository/maven-snapshots/"
            ),
            "dl4jMavenRepositoryId": "sonatype-snapshots",
            "shard": {
                "build": {
                    "backend": "cpu",
                    "javacppPlatform": "windows-x86_64",
                },
            },
        }
        classifier = "windows-x86_64-compile"
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            source = root / "source"
            source.mkdir()
            repository = root / "m2"
            destination = root / "sdk"
            jars = destination / "jars"
            jars.mkdir(parents=True)
            (jars / "nd4j-native-1.0.0-windows-x86_64-compile.jar").write_bytes(
                b"stale-release"
            )

            def copy_snapshot(command, _cwd):
                coordinate = next(
                    item.removeprefix("-Dartifact=")
                    for item in command if item.startswith("-Dartifact=")
                )
                parts = coordinate.split(":")
                artifact_id = parts[1]
                version = parts[2]
                artifact_classifier = parts[4] if len(parts) == 5 else ""
                suffix = (
                    f"-{artifact_classifier}" if artifact_classifier else ""
                )
                (jars / f"{artifact_id}-{version}{suffix}.jar").write_bytes(
                    b"snapshot"
                )

            with patch.object(BUILD_MODULE, "run", side_effect=copy_snapshot) as run:
                BUILD_MODULE.hydrate_dl4j_sdk_jars(
                    config, source, repository, destination, classifier
                )

            self.assertFalse(
                (jars / "nd4j-native-1.0.0-windows-x86_64-compile.jar").exists()
            )
            self.assertTrue(
                (
                    jars /
                    "nd4j-native-1.0.0-SNAPSHOT-windows-x86_64-compile.jar"
                ).is_file()
            )
            self.assertTrue(
                (jars / "nd4j-native-platform-1.0.0-SNAPSHOT.jar").is_file()
            )
            commands = [item.args[0] for item in run.call_args_list]
            self.assertEqual(11, len(commands))
            coordinates = [
                next(item.removeprefix("-Dartifact=") for item in command
                     if item.startswith("-Dartifact="))
                for command in commands
            ]
            self.assertIn(
                "org.eclipse.deeplearning4j:nd4j-cpu-backend-common:1.0.0-SNAPSHOT:jar",
                coordinates,
            )
            self.assertIn(
                "org.eclipse.deeplearning4j:nd4j-native:1.0.0-SNAPSHOT:jar:windows-x86_64",
                coordinates,
            )
            self.assertIn(
                "org.eclipse.deeplearning4j:nd4j-native-preset:1.0.0-SNAPSHOT:jar:windows-x86_64",
                coordinates,
            )
            for command in commands:
                self.assertIn(
                    "-Ddl4j.repository.url="
                    "https://central.sonatype.com/repository/maven-snapshots/",
                    command,
                )

    def test_zluda_sdk_hydration_uses_versioned_artifacts_and_one_native_classifier(self):
        config = {
            "snapshotVersion": "1.0.0-SNAPSHOT",
            "dl4jMavenRepositoryUrl": "https://repo.example/snapshots",
            "dl4jMavenRepositoryId": "dl4j-release",
            "shard": {
                "build": {
                    "backend": "cuda",
                    "cudaVersion": "12.9",
                    "javacppPlatform": "linux-x86_64",
                    "dl4jLane": "linux-x86_64-zluda-rocm-10.0.0",
                },
            },
        }
        expected_artifacts = [
            "nd4j-zluda-12.9",
            "nd4j-zluda-12.9-platform",
            "nd4j-cuda-12.9-preset",
            "nd4j-cuda-backend-common",
            "nd4j-presets-common",
        ]
        self.assertEqual(
            expected_artifacts,
            BUILD_MODULE.dl4j_sdk_artifact_ids(config["shard"]["build"]),
        )
        classifier = "linux-x86_64-zluda-rocm-10.0.0"
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            source = root / "source"
            source.mkdir()
            destination = root / "sdk"
            jars = destination / "jars"

            def copy_snapshot(command, _cwd):
                coordinate = next(
                    item.removeprefix("-Dartifact=")
                    for item in command if item.startswith("-Dartifact=")
                )
                parts = coordinate.split(":")
                artifact_id = parts[1]
                artifact_classifier = parts[4] if len(parts) == 5 else ""
                suffix = f"-{artifact_classifier}" if artifact_classifier else ""
                jars.mkdir(parents=True, exist_ok=True)
                (jars / f"{artifact_id}-1.0.0-SNAPSHOT{suffix}.jar").write_bytes(b"jar")

            with patch.object(BUILD_MODULE, "run", side_effect=copy_snapshot) as run:
                BUILD_MODULE.hydrate_dl4j_sdk_jars(
                    config, source, root / "m2", destination, classifier,
                )

            coordinates = [
                next(item.removeprefix("-Dartifact=") for item in call.args[0]
                     if item.startswith("-Dartifact="))
                for call in run.call_args_list
            ]
            self.assertEqual(len(expected_artifacts) + 1, len(coordinates))
            self.assertIn(
                "org.eclipse.deeplearning4j:nd4j-zluda-12.9:"
                "1.0.0-SNAPSHOT:jar:linux-x86_64-zluda-rocm-10.0.0",
                coordinates,
            )
            self.assertFalse(any(
                coordinate.startswith(
                    "org.eclipse.deeplearning4j:nd4j-zluda-12.9-platform:"
                ) and coordinate.endswith(f":{classifier}")
                for coordinate in coordinates
            ))

    def test_graalvm_community_resolution_matches_latest_java_21_tag(self):
        response = MagicMock()
        response.read.return_value = (
            b'[{"ref":"refs/tags/jdk-21.0.8"},'
            b'{"ref":"refs/tags/jdk-21.0.10"},'
            b'{"ref":"refs/tags/jdk-22.0.2"}]'
        )
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        with patch.object(BUILD_MODULE.urllib.request, "urlopen", return_value=response):
            self.assertEqual("21.0.10", BUILD_MODULE.latest_graalvm_community_version())
        source = (ROOT / "build-platform.py").read_text(encoding="utf-8")
        self.assertIn("graalvm-community-jdk-", source)
        self.assertIn("graalvm/graalvm-ce-builds", source)

    def test_windows_native_image_uses_cmd_launcher(self):
        with patch.object(BUILD_MODULE.os, "name", "nt"):
            self.assertEqual("native-image.cmd", BUILD_MODULE.native_image())
        with patch.object(BUILD_MODULE.os, "name", "posix"):
            self.assertEqual("native-image", BUILD_MODULE.native_image())

    def test_windows_maven_uses_cmd_launcher(self):
        with patch.object(BUILD_MODULE.os, "name", "nt"):
            self.assertEqual("mvn.cmd", BUILD_MODULE.maven())
        with patch.object(BUILD_MODULE.os, "name", "posix"):
            self.assertEqual("mvn", BUILD_MODULE.maven())

    def test_release_build_preserves_required_test_jars(self):
        source = (REPOSITORY / "build-scripts" / "build-common.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("  -DskipTests\n", source)
        self.assertNotIn("-Dmaven.test.skip=true", source)

    def test_windows_batch_commands_run_through_cmd(self):
        original = ["mvn.cmd", "--batch-mode", "-Dvalue=space value"]
        with patch.object(BUILD_MODULE.os, "name", "nt"):
            command = BUILD_MODULE.subprocess_command(original)
        self.assertEqual(["cmd.exe", "/d", "/s", "/c"], command[:4])
        self.assertEqual(
            BUILD_MODULE.subprocess.list2cmdline(original), command[4],
        )
        with patch.object(BUILD_MODULE.os, "name", "posix"):
            self.assertIs(original, BUILD_MODULE.subprocess_command(original))

    def test_existing_native_image_sets_graalvm_and_java_home(self):
        with patch.dict(os.environ, {}, clear=False), \
             patch.object(BUILD_MODULE.shutil, "which", return_value="/opt/graalvm/bin/native-image"):
            env = BUILD_MODULE.ensure_graalvm(pathlib.Path("/tmp/unused"), "x86_64")
        self.assertEqual("/opt/graalvm", env["GRAALVM_HOME"])
        self.assertEqual("/opt/graalvm", env["JAVA_HOME"])
        self.assertEqual("graalvm-community", env["KOMPILE_GRAALVM_DISTRIBUTION"])

    def test_windows_distribution_uses_oracle_native_image_toolchain(self):
        self.assertEqual(
            "graalvm",
            BUILD_MODULE.native_image_distribution({
                "shard": {"os": "windows", "build": {}},
            }),
        )
        self.assertEqual(
            "graalvm-community",
            BUILD_MODULE.native_image_distribution({
                "shard": {"os": "linux", "build": {}},
            }),
        )

    def test_native_build_requires_and_collects_all_five_exact_binaries(self):
        binary_names = {
            "kompile-cli-main": "kompile-cli-main",
            "kompile-agent-cli": "kompile-agent",
            "kompile-app-cli": "kompile-app-cli",
            "kompile-model-cli": "kompile-model",
            "kompile-component-cli": "kompile-component",
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            source = root / "source"
            assets = root / "assets"
            assets.mkdir(parents=True)
            for module, binary in binary_names.items():
                target = source / "kompile-cli" / module / "target"
                target.mkdir(parents=True)
                (target / binary).write_bytes(b"native")
            config = {
                "releaseVersion": "1.2.3",
                "snapshotVersion": "1.0.0-SNAPSHOT",
                "shard": {
                    "os": "linux",
                    "architecture": "x86_64",
                    "build": {
                        "backend": "cpu", "mavenHeapGiB": 4,
                        "javacppPlatform": "linux-x86_64",
                    },
                },
            }
            with patch.object(BUILD_MODULE, "ensure_graalvm", return_value={}) as graalvm, \
                 patch.object(BUILD_MODULE, "stage_kompile_maven_artifacts"), \
                 patch.object(BUILD_MODULE, "run"):
                BUILD_MODULE.build_kompile_native(
                    config, source, root / "m2", root / "maven-output", assets,
                )
            graalvm.assert_called_once_with(
                source / ".external-tools", "x86_64", "graalvm",
            )
            self.assertTrue((assets / "kompile-1.2.3-linux-x86_64.zip").is_file())

            (source / "kompile-cli" / "kompile-model-cli" / "target" / "kompile-model").unlink()
            with patch.object(BUILD_MODULE, "ensure_graalvm", return_value={}), \
                 patch.object(BUILD_MODULE, "stage_kompile_maven_artifacts"), \
                 patch.object(BUILD_MODULE, "run"):
                with self.assertRaisesRegex(RuntimeError, "kompile-model"):
                    BUILD_MODULE.build_kompile_native(
                        config, source, root / "m2", root / "maven-output", assets,
                    )

    def test_distribution_variants_delegate_expected_dl4j_chips_then_canonical_script(self):
        plan = MODULE.load_plan(ROOT / "release-plan.json")
        shards = {item["id"]: item for item in plan["shards"]}

        def create_sdk(*args, **kwargs):
            sdk = pathlib.Path(args[4])
            if kwargs.get("require_sdk", True):
                (sdk / "jars").mkdir(parents=True, exist_ok=True)
                (sdk / "runtime.zip").write_bytes(b"PK\x03\x04runtime")
                (sdk / "jars" / "nd4j-platform.jar").write_bytes(b"jar")

        def create_maven_only_sdk(_repository, destination, *_args):
            jars = pathlib.Path(destination) / "jars"
            jars.mkdir(parents=True, exist_ok=True)
            (jars / "nd4j-zluda-12.9.jar").write_bytes(b"jar")

        with patch.object(BUILD_MODULE, "ensure_graalvm", return_value={}) as graalvm, \
             patch.object(BUILD_MODULE, "run_dl4j_release_lane", side_effect=create_sdk) as dl4j, \
             patch.object(
                 BUILD_MODULE, "stage_local_dl4j_sdk_jars",
                 side_effect=create_maven_only_sdk,
             ) as local_sdk, \
             patch.object(BUILD_MODULE, "stage_dl4j_release_artifacts"), \
             patch.object(BUILD_MODULE, "stage_kompile_maven_artifacts"), \
             patch.object(BUILD_MODULE, "run") as run:
            for shard_id in ("distribution-linux-x86_64", "distribution-linux-x86_64-accelerators"):
                config = {
                    "releaseVersion": "1.2.3", "runId": "test",
                    "snapshotVersion": "1.0.0-SNAPSHOT", "commit": "a" * 40,
                    "dl4jCommit": "b" * 40,
                    "dl4jRepository": "https://example.invalid/dl4j.git",
                    "shard": shards[shard_id],
                }
                BUILD_MODULE.build_distribution(
                    config, pathlib.Path("/source"), pathlib.Path("/m2"),
                    pathlib.Path("/maven-output"), pathlib.Path("/assets"),
                )
        delegated = [(call.args[5], call.args[6]) for call in dl4j.call_args_list]
        self.assertEqual([
            ("linux-x86_64-cpu", ["base"]),
            ("linux-x86_64-cpu", ["avx2"]),
            ("linux-x86_64-cuda-12-9", ["base"]),
            ("linux-x86_64-zluda", ["cuda-12.9"]),
        ], delegated)
        zluda_call = next(
            call for call in dl4j.call_args_list
            if call.args[5] == "linux-x86_64-zluda"
        )
        self.assertFalse(zluda_call.kwargs["require_sdk"])
        local_sdk.assert_called_once()
        canonical = [call for call in run.call_args_list if call.args[0][:2] == ["bash", "./build-dist.sh"]]
        self.assertEqual(6, len(canonical))
        self.assertTrue(all(call.args[2]["KOMPILE_MAVEN_REPO"] == "/m2" for call in canonical))
        self.assertEqual(
            {"graalvm-community"},
            {call.args[2] for call in graalvm.call_args_list},
        )
        self.assertIn(
            "https://download.oracle.com/graalvm/21/latest/",
            (ROOT / "build-platform.py").read_text(encoding="utf-8"),
        )
        self.assertEqual("nvidia/cuda:12.9.1-devel-ubuntu22.04",
                         shards["distribution-linux-x86_64-accelerators"]["containerImage"])

    def test_repository_default_zluda_hydrates_maven_only_sdk_without_runtime_archive(self):
        plan = MODULE.load_plan(ROOT / "release-plan.json")
        shard = next(
            json.loads(json.dumps(item)) for item in plan["shards"]
            if item["id"] == "distribution-linux-x86_64-accelerators"
        )
        shard["build"]["variants"] = [
            item for item in shard["build"]["variants"]
            if item["name"] == "amd-zluda"
        ]
        config = {
            "releaseVersion": "1.2.3",
            "snapshotVersion": "1.0.0-SNAPSHOT",
            "dl4jMavenRepositoryUrl": "https://repo.example/snapshots",
            "dl4jMavenRepositoryId": "dl4j-release",
            "shard": shard,
        }

        def hydrate(_config, _source, _repository, destination, _classifier, **_kwargs):
            jars = pathlib.Path(destination) / "jars"
            jars.mkdir(parents=True, exist_ok=True)
            (jars / "nd4j-zluda-12.9.jar").write_bytes(b"jar")

        captured = {}

        def capture_build(command, _cwd, _env=None):
            captured["command"] = command
            sdk_root = pathlib.Path(command[command.index("--sdx-assets") + 1])
            captured["runtime_packages"] = [
                item for item in sdk_root.rglob("*")
                if item.is_file() and item.suffix.lower() in {".zip", ".aar"}
            ]

        with patch.object(BUILD_MODULE, "ensure_graalvm", return_value={}), \
             patch.object(BUILD_MODULE, "download_dl4j_sdk_assets") as download, \
             patch.object(BUILD_MODULE, "hydrate_dl4j_sdk_jars", side_effect=hydrate) as hydrated, \
             patch.object(BUILD_MODULE, "run_dl4j_release_lane") as dl4j, \
             patch.object(BUILD_MODULE, "stage_dl4j_release_artifacts"), \
             patch.object(BUILD_MODULE, "stage_kompile_maven_artifacts"), \
             patch.object(BUILD_MODULE, "run", side_effect=capture_build):
            BUILD_MODULE.build_distribution(
                config, pathlib.Path("/source"), pathlib.Path("/m2"),
                pathlib.Path("/maven-output"), pathlib.Path("/assets"),
            )

        download.assert_not_called()
        dl4j.assert_not_called()
        hydrated.assert_called_once()
        hydrate_call = hydrated.call_args
        self.assertEqual(
            "linux-x86_64-zluda-rocm-7.2.4", hydrate_call.args[4],
        )
        self.assertEqual(
            "linux-x86_64-zluda", hydrate_call.kwargs["build_override"]["dl4jLane"],
        )
        command = captured["command"]
        self.assertIn("--sdx-assets", command)
        self.assertEqual([], captured["runtime_packages"])

    def test_dl4j_lane_preserves_sdk_artifact_rules_for_jar_packaging(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            dl4j = root / "deeplearning4j"
            release_dir = dl4j / "release" / "aws"
            release_dir.mkdir(parents=True)
            (release_dir / "build-platform.py").write_text("# driver\n", encoding="utf-8")
            rules = {
                "mode": "classifier",
                "artifactIds": ["nd4j-native", "nd4j-native-preset"],
                "classifierTokens": ["linux-x86_64"],
            }
            (release_dir / "release-plan.json").write_text(
                json.dumps({
                    "shards": [{
                        "id": "linux-x86_64-cpu",
                        "artifactRules": rules,
                        "build": {
                            "variants": [{"name": "base"}],
                            "buildThreads": 8,
                            "mavenHeapGiB": 4,
                        },
                    }],
                }),
                encoding="utf-8",
            )
            captured = {}

            def capture(command, _cwd, _env=None):
                config_path = pathlib.Path(command[command.index("--config") + 1])
                captured.update(json.loads(config_path.read_text(encoding="utf-8")))

            config = {
                "runId": "test",
                "releaseVersion": "0.1.0-SNAPSHOT",
                "snapshotVersion": "1.0.0-SNAPSHOT",
                "dl4jCommit": "b" * 40,
                "dl4jRepository": "https://example.invalid/dl4j.git",
                "shard": {"build": {"buildThreads": 16, "mavenHeapGiB": 6}},
            }
            with patch.object(BUILD_MODULE, "ensure_dl4j_checkout", return_value=dl4j), \
                 patch.object(BUILD_MODULE, "run", side_effect=capture):
                BUILD_MODULE.run_dl4j_release_lane(
                    config, root, root / "m2", root / "maven-output",
                    root / "sdk-output", "linux-x86_64-cpu", ["base"],
                )
            self.assertEqual(rules, captured["shard"]["artifactRules"])
            self.assertEqual(["maven", "sdk"], captured["shard"]["workloads"])
            self.assertEqual("1.0.0-SNAPSHOT", captured["releaseVersion"])

    def test_dl4j_java_reactor_builds_owned_modules_from_pinned_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            dl4j = root / "deeplearning4j"
            driver = dl4j / "release" / "aws" / "build-platform.py"
            driver.parent.mkdir(parents=True)
            driver.write_text("# test driver\n", encoding="utf-8")
            repository = root / "m2"
            captured = {}

            def build_owned_artifacts(command, _cwd, _env=None):
                captured.setdefault("commands", []).append(command)
                if "--config" not in command:
                    return
                config_path = pathlib.Path(command[command.index("--config") + 1])
                captured.update(json.loads(config_path.read_text(encoding="utf-8")))
                version = captured["snapshotVersion"]
                for artifact_id in BUILD_MODULE.OWNED_DL4J_JAVA_ARTIFACTS:
                    target = (
                        repository / "org" / "eclipse" / "deeplearning4j" /
                        artifact_id / version / f"{artifact_id}-{version}.jar"
                    )
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(b"owned-source")

            config = {
                "runId": "test",
                "releaseVersion": "0.1.0-SNAPSHOT",
                "snapshotVersion": "1.0.0-SNAPSHOT",
                "dl4jBranch": "release/snapshot",
                "dl4jCommit": "b" * 40,
                "dl4jRepository": "https://example.invalid/dl4j.git",
                "shard": {
                    "os": "windows",
                    "architecture": "x86_64",
                    "build": {
                        "javacppPlatform": "windows-x86_64",
                        "buildThreads": 8,
                        "mavenHeapGiB": 40,
                    },
                },
            }
            with patch.object(BUILD_MODULE, "ensure_dl4j_checkout", return_value=dl4j), \
                 patch.object(BUILD_MODULE, "run", side_effect=build_owned_artifacts):
                BUILD_MODULE.run_dl4j_java_reactor(
                    config, root, repository, root / "maven-output",
                )

            self.assertEqual("cross-platform", captured["shard"]["build"]["kind"])
            self.assertEqual("1.0.0-SNAPSHOT", captured["releaseVersion"])
            self.assertEqual("release/snapshot", captured["sourceBranch"])
            self.assertEqual(
                [f":{artifact_id}" for artifact_id in BUILD_MODULE.OWNED_DL4J_JAVA_ARTIFACTS],
                captured["shard"]["build"]["modules"],
            )
            self.assertIn(
                "samediff-llm",
                captured["shard"]["artifactRules"]["unclassifiedArtifactIds"],
            )
            self.assertNotIn(
                "nd4j-sdx-model",
                captured["shard"]["artifactRules"]["unclassifiedArtifactIds"],
            )
            module_commands = [
                command for command in captured["commands"] if "--config" not in command
            ]
            self.assertEqual(1, len(module_commands))
            self.assertIn("-Psdx", module_commands[0])
            self.assertIn(":nd4j-sdx-model", module_commands[0])
            self.assertTrue(
                (
                    root / "maven-output" / "org" / "eclipse" /
                    "deeplearning4j" / "samediff-llm" /
                    "1.0.0-SNAPSHOT" / "samediff-llm-1.0.0-SNAPSHOT.jar"
                ).is_file()
            )

    def test_full_repository_platform_co_builds_dl4j_java_once(self):
        config = {
            "runId": "test",
            "releaseVersion": "0.1.0-SNAPSHOT",
            "snapshotVersion": "1.0.0-SNAPSHOT",
            "dl4jBranch": "release/snapshot",
            "dl4jCommit": "b" * 40,
            "dl4jRepository": "https://example.invalid/dl4j.git",
            "dl4jMavenRepositoryUrl": "https://repo.example/snapshots/",
            "dl4jMavenRepositoryId": "sonatype-snapshots",
            "dl4jSdkAssetsUrl": "https://downloads.example/{lane}.tar.gz",
            "shard": {
                "os": "windows",
                "architecture": "x86_64",
                "build": {
                    "kind": "platform",
                    "backend": "cpu",
                    "javacppPlatform": "windows-x86_64",
                    "dl4jLane": "windows-x86_64-cpu",
                    "buildThreads": 8,
                    "mavenHeapGiB": 40,
                    "variants": [{
                        "name": "compile",
                        "classifier": "windows-x86_64-compile",
                    }],
                },
            },
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            with patch.object(BUILD_MODULE, "ensure_graalvm", return_value={}), \
                 patch.object(BUILD_MODULE, "download_dl4j_sdk_assets"), \
                 patch.object(BUILD_MODULE, "hydrate_dl4j_sdk_jars"), \
                 patch.object(BUILD_MODULE, "run_dl4j_java_reactor") as java, \
                 patch.object(BUILD_MODULE, "stage_kompile_maven_artifacts"), \
                 patch.object(BUILD_MODULE, "run") as run:
                BUILD_MODULE.build_full_platform(
                    config, root, root / "m2", root / "maven-output", root / "assets",
                )
            java.assert_called_once_with(
                config, root, root / "m2", root / "maven-output",
            )
            platform_runs = [
                call for call in run.call_args_list
                if call.args[0][1] == "./build-scripts/build-kompile-platform.sh"
            ]
            self.assertEqual(1, len(platform_runs))
            self.assertEqual(
                ",".join(BUILD_MODULE.FULL_DISTRIBUTION_NATIVE_TARGETS),
                platform_runs[0].args[2]["NATIVE_TARGETS"],
            )

    def test_full_repository_only_platform_skips_dl4j_java_reactor(self):
        config = {
            "releaseVersion": "0.1.0-SNAPSHOT",
            "snapshotVersion": "1.0.0-SNAPSHOT",
            "dl4jInputMode": "maven",
            "dl4jMavenRepositoryUrl": "https://repo.example/snapshots/",
            "dl4jMavenRepositoryId": "sonatype-snapshots",
            "dl4jSdkAssetsUrl": "https://downloads.example/{lane}.tar.gz",
            "shard": {
                "os": "windows",
                "architecture": "x86_64",
                "build": {
                    "kind": "platform",
                    "backend": "cpu",
                    "javacppPlatform": "windows-x86_64",
                    "dl4jLane": "windows-x86_64-cpu",
                    "buildThreads": 8,
                    "mavenHeapGiB": 4,
                    "variants": [{
                        "name": "compile",
                        "classifier": "windows-x86_64-compile",
                    }],
                },
            },
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            with patch.object(BUILD_MODULE, "ensure_graalvm", return_value={}):
                with patch.object(BUILD_MODULE, "download_dl4j_sdk_assets"):
                    with patch.object(BUILD_MODULE, "hydrate_dl4j_sdk_jars"):
                        with patch.object(BUILD_MODULE, "run_dl4j_java_reactor") as java:
                            with patch.object(BUILD_MODULE, "stage_dl4j_release_artifacts"):
                                with patch.object(BUILD_MODULE, "stage_kompile_maven_artifacts"):
                                    with patch.object(BUILD_MODULE, "run") as run:
                                        BUILD_MODULE.build_full_platform(
                                            config, root, root / "m2",
                                            root / "maven-output", root / "assets",
                                        )
            java.assert_not_called()
            platform_runs = [
                call for call in run.call_args_list
                if call.args[0][1] == "./build-scripts/build-kompile-platform.sh"
            ]
            self.assertEqual(1, len(platform_runs))

    def test_repository_mode_skips_dl4j_source_lane_and_propagates_repository(self):
        config = {
            "releaseVersion": "1.2.3",
            "snapshotVersion": "1.0.0-SNAPSHOT",
            "dl4jMavenRepositoryUrl": "https://repo.example/snapshots",
            "dl4jMavenRepositoryId": "shared-release",
            "dl4jSdkAssetsUrl": "https://downloads.example/{lane}.tar.gz",
            "shard": {
                "architecture": "x86_64",
                "build": {
                    "backend": "cpu", "buildThreads": 8, "mavenHeapGiB": 4,
                    "javacppPlatform": "linux-x86_64",
                    "variants": [{"name": "cpu-intel"}],
                },
            },
        }
        with patch.object(BUILD_MODULE, "ensure_graalvm", return_value={}), \
             patch.object(BUILD_MODULE, "run_dl4j_release_lane") as dl4j, \
             patch.object(BUILD_MODULE, "download_dl4j_sdk_assets") as download_sdk, \
             patch.object(BUILD_MODULE, "stage_kompile_maven_artifacts"), \
             patch.object(BUILD_MODULE, "run") as run:
            BUILD_MODULE.build_distribution(
                config, pathlib.Path("/source"), pathlib.Path("/m2"),
                pathlib.Path("/maven-output"), pathlib.Path("/assets"),
            )
        dl4j.assert_not_called()
        download_sdk.assert_called_once()
        environment = run.call_args.args[2]
        self.assertEqual("1.0.0-SNAPSHOT", environment["ND4J_VERSION"])
        self.assertEqual(
            "https://repo.example/snapshots",
            environment["DL4J_MAVEN_REPOSITORY_URL"],
        )
        self.assertEqual("shared-release", environment["DL4J_MAVEN_REPOSITORY_ID"])
        self.assertEqual([
            "-Dnd4j.version=1.0.0-SNAPSHOT",
            "-Ddl4j.repository.id=shared-release",
            "-Ddl4j.repository.url=https://repo.example/snapshots",
        ], BUILD_MODULE.dl4j_maven_arguments(config))

    def test_repository_sdk_archive_is_checksum_verified_and_safely_extracted(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            archive = root / "sdk-assets.tar.gz"
            with zipfile.ZipFile(archive, "w") as contents:
                contents.writestr("runtime.zip", b"PK\x03\x04runtime")
                contents.writestr("jars/nd4j-native-linux-x86_64.jar", b"jar")
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            pathlib.Path(str(archive) + ".sha256").write_text(
                f"{digest}  {archive.name}\n", encoding="ascii",
            )
            output = root / "output"
            BUILD_MODULE.download_dl4j_sdk_assets(
                {
                    "snapshotVersion": "1.0.0-SNAPSHOT",
                    "dl4jSdkAssetsUrl": archive.as_uri(),
                    "shard": {"build": {"javacppPlatform": "linux-x86_64"}},
                },
                "linux-x86_64-cpu",
                "full",
                output,
            )
            self.assertTrue((output / "runtime.zip").is_file())
            self.assertTrue((output / "jars" / "nd4j-native-linux-x86_64.jar").is_file())

            malicious = root / "malicious.zip"
            with zipfile.ZipFile(malicious, "w") as contents:
                contents.writestr("../escape", b"bad")
            with self.assertRaisesRegex(RuntimeError, "unsafe SDK archive member"):
                BUILD_MODULE.extract_sdk_archive(malicious, root / "malicious-output")

    def test_azure_sdk_archive_can_use_adjacent_shard_manifest_attestation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            archive = root / "source-sdk.zip"
            with zipfile.ZipFile(archive, "w") as contents:
                contents.writestr("runtime.zip", b"PK\x03\x04runtime")
                contents.writestr("jars/nd4j-native-linux-x86_64.jar", b"jar")
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()

            def retrieve(url, destination):
                destination = pathlib.Path(destination)
                if url.endswith(".sha256"):
                    raise BUILD_MODULE.urllib.error.HTTPError(
                        url, 404, "missing", {}, None,
                    )
                if url.endswith("shard-manifest.json"):
                    destination.write_text(json.dumps({
                        "files": [{
                            "path": "sdk-assets.tar.gz",
                            "sha256": digest,
                            "size": archive.stat().st_size,
                        }],
                    }), encoding="utf-8")
                else:
                    shutil.copy2(archive, destination)
                return str(destination), None

            output = root / "output"
            with patch.object(
                BUILD_MODULE.urllib.request, "urlretrieve", side_effect=retrieve,
            ):
                BUILD_MODULE.download_dl4j_sdk_assets(
                    {
                        "snapshotVersion": "1.0.0-SNAPSHOT",
                        "dl4jSdkAssetsUrl": (
                            "https://builds.blob.core.windows.net/releases/"
                            "{lane}/sdk-assets.tar.gz"
                        ),
                        "shard": {
                            "build": {"javacppPlatform": "linux-x86_64"},
                        },
                    },
                    "linux-x86_64-cpu",
                    "base",
                    output,
                )
            self.assertTrue((output / "runtime.zip").is_file())

    def test_cli_only_distribution_installs_zip_and_tar_maven_assemblies(self):
        maven = shutil.which("mvn")
        configured_maven = pathlib.Path("/home/agibsonccc/dev-apps/mvn/bin/mvn")
        if maven is None and configured_maven.is_file():
            maven = str(configured_maven)
        if maven is None:
            self.skipTest("Maven is required to verify classified distribution installation")
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            shutil.copy2(REPOSITORY / "build-dist.sh", root / "build-dist.sh")
            (root / "build-dist.sh").chmod(0o755)
            # Like a release checkout, carry the first-party skill packages that
            # build-dist.sh ships as managed payload and fails closed without.
            shutil.copytree(REPOSITORY / "skills", root / "skills")
            (root / "pom.xml").write_text(
                "<project><modelVersion>4.0.0</modelVersion>"
                "<groupId>ai.kompile</groupId><artifactId>synthetic-root</artifactId>"
                "<version>0.1.0-SNAPSHOT</version></project>\n",
                encoding="utf-8",
            )
            build_helpers = root / "kompile-dist" / "src" / "main" / "build"
            build_helpers.mkdir(parents=True)
            normalizer = build_helpers / "normalize-elf-portability.sh"
            normalizer.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            normalizer.chmod(0o755)
            native_stager = build_helpers / "stage-native-libs.sh"
            shutil.copy2(
                REPOSITORY / "kompile-dist" / "src" / "main" / "build" /
                "stage-native-libs.sh",
                native_stager,
            )
            native_stager.chmod(0o755)
            (root / "kompile-dist" / "pom.xml").write_text(
                "<project><modelVersion>4.0.0</modelVersion>"
                "<groupId>ai.kompile</groupId><artifactId>kompile-dist</artifactId>"
                "<version>0.1.0-SNAPSHOT</version><packaging>pom</packaging></project>\n",
                encoding="utf-8",
            )
            cli = root / "kompile-cli" / "kompile-cli-main" / "target" / "kompile-cli-main"
            cli.parent.mkdir(parents=True)
            cli.write_bytes(b"native-cli")
            cli.chmod(0o755)
            (cli.parent / "native-libs").mkdir()
            model_cli = (
                root / "kompile-cli" / "kompile-model-cli" / "target" /
                "kompile-model"
            )
            model_cli.parent.mkdir(parents=True)
            model_cli.write_bytes(b"native-model-cli")
            model_cli.chmod(0o755)
            agent_cli = (
                root / "kompile-cli" / "kompile-agent-cli" / "target" /
                "kompile-agent"
            )
            agent_cli.parent.mkdir(parents=True)
            agent_cli.write_bytes(b"native-agent-cli")
            agent_cli.chmod(0o755)
            # cli-only packaging fail-closes on lib/kompile-chat.jar; provide the
            # handoff exec JAR the same way the persona verifier fixture does.
            chat_target = (
                root / "kompile-app" / "kompile-app-parent" / "kompile-app-chat" /
                "target"
            )
            chat_target.mkdir(parents=True)
            chat_jar = chat_target / "kompile-app-chat-0.1.0-SNAPSHOT-exec.jar"
            with zipfile.ZipFile(chat_jar, "w") as archive:
                archive.writestr(
                    "META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\n"
                    "Start-Class: ai.kompile.app.chat.ChatApplication\n\n",
                )
                archive.writestr("BOOT-INF/classes/example.class", b"class")
            output = root / "output"
            repository = root / "m2"
            env = os.environ.copy()
            env.update({
                "KOMPILE_MAVEN_REPO": str(repository),
                "MVN": maven,
            })
            result = subprocess.run(
                [
                    "bash", "./build-dist.sh", "cli-only",
                    "--skip-java-build", "--skip-native",
                    "--platform", "linux-x86_64",
                    "--output-dir", str(output),
                    "--version", "0.1.0-SNAPSHOT",
                ],
                cwd=root,
                env=env,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            classifier = "cli-only-linux-x86_64"
            base = f"kompile-dist-0.1.0-SNAPSHOT-{classifier}"
            self.assertTrue((output / f"{base}.zip").is_file())
            self.assertTrue((output / f"{base}.tar.gz").is_file())
            installed = (
                repository / "ai" / "kompile" / "kompile-dist" /
                "0.1.0-SNAPSHOT"
            )
            self.assertTrue((installed / f"{base}.zip").is_file())
            self.assertTrue((installed / f"{base}.tar.gz").is_file())
            # The CLI loads its bundled first-party skills from lib/skills, so
            # even the smallest archive carries the complete packages.
            with zipfile.ZipFile(output / f"{base}.zip") as contents:
                names = set(contents.namelist())
            skills = REPOSITORY / "skills"
            for skill in skills.rglob("*"):
                if skill.is_file():
                    self.assertIn(
                        f"{base}/lib/skills/" + skill.relative_to(skills).as_posix(),
                        names,
                    )

    def test_archive_format_zip_opt_out_halves_maven_assemblies(self):
        """--archive-format zip skips the tar.gz: output, checksum, and Maven copy."""
        maven = shutil.which("mvn")
        configured_maven = pathlib.Path("/home/agibsonccc/dev-apps/mvn/bin/mvn")
        if maven is None and configured_maven.is_file():
            maven = str(configured_maven)
        if maven is None:
            self.skipTest("Maven is required to verify classified distribution installation")
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            shutil.copy2(REPOSITORY / "build-dist.sh", root / "build-dist.sh")
            (root / "build-dist.sh").chmod(0o755)
            # Like a release checkout, carry the first-party skill packages that
            # build-dist.sh ships as managed payload and fails closed without.
            shutil.copytree(REPOSITORY / "skills", root / "skills")
            (root / "pom.xml").write_text(
                "<project><modelVersion>4.0.0</modelVersion>"
                "<groupId>ai.kompile</groupId><artifactId>synthetic-root</artifactId>"
                "<version>0.1.0-SNAPSHOT</version></project>\n",
                encoding="utf-8",
            )
            build_helpers = root / "kompile-dist" / "src" / "main" / "build"
            build_helpers.mkdir(parents=True)
            normalizer = build_helpers / "normalize-elf-portability.sh"
            normalizer.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            normalizer.chmod(0o755)
            native_stager = build_helpers / "stage-native-libs.sh"
            shutil.copy2(
                REPOSITORY / "kompile-dist" / "src" / "main" / "build" /
                "stage-native-libs.sh",
                native_stager,
            )
            native_stager.chmod(0o755)
            (root / "kompile-dist" / "pom.xml").write_text(
                "<project><modelVersion>4.0.0</modelVersion>"
                "<groupId>ai.kompile</groupId><artifactId>kompile-dist</artifactId>"
                "<version>0.1.0-SNAPSHOT</version><packaging>pom</packaging></project>\n",
                encoding="utf-8",
            )
            cli = root / "kompile-cli" / "kompile-cli-main" / "target" / "kompile-cli-main"
            cli.parent.mkdir(parents=True)
            cli.write_bytes(b"native-cli")
            cli.chmod(0o755)
            (cli.parent / "native-libs").mkdir()
            model_cli = (
                root / "kompile-cli" / "kompile-model-cli" / "target" /
                "kompile-model"
            )
            model_cli.parent.mkdir(parents=True)
            model_cli.write_bytes(b"native-model-cli")
            model_cli.chmod(0o755)
            agent_cli = (
                root / "kompile-cli" / "kompile-agent-cli" / "target" /
                "kompile-agent"
            )
            agent_cli.parent.mkdir(parents=True)
            agent_cli.write_bytes(b"native-agent-cli")
            agent_cli.chmod(0o755)
            # cli-only packaging fail-closes on lib/kompile-chat.jar; provide the
            # handoff exec JAR the same way the persona verifier fixture does.
            chat_target = (
                root / "kompile-app" / "kompile-app-parent" / "kompile-app-chat" /
                "target"
            )
            chat_target.mkdir(parents=True)
            chat_jar = chat_target / "kompile-app-chat-0.1.0-SNAPSHOT-exec.jar"
            with zipfile.ZipFile(chat_jar, "w") as archive:
                archive.writestr(
                    "META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\n"
                    "Start-Class: ai.kompile.app.chat.ChatApplication\n\n",
                )
                archive.writestr("BOOT-INF/classes/example.class", b"class")
            output = root / "output"
            repository = root / "m2"
            env = os.environ.copy()
            env.update({
                "KOMPILE_MAVEN_REPO": str(repository),
                "MVN": maven,
            })
            result = subprocess.run(
                [
                    "bash", "./build-dist.sh", "cli-only",
                    "--skip-java-build", "--skip-native",
                    "--platform", "linux-x86_64",
                    "--output-dir", str(output),
                    "--version", "0.1.0-SNAPSHOT",
                    "--archive-format", "zip",
                ],
                cwd=root,
                env=env,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            classifier = "cli-only-linux-x86_64"
            base = f"kompile-dist-0.1.0-SNAPSHOT-{classifier}"
            self.assertTrue((output / f"{base}.zip").is_file())
            self.assertFalse((output / f"{base}.tar.gz").exists(),
                             "zip opt-out must not produce the tar.gz")
            self.assertFalse((output / f"{base}.tar.gz.sha256").exists())
            installed = (
                repository / "ai" / "kompile" / "kompile-dist" /
                "0.1.0-SNAPSHOT"
            )
            self.assertTrue((installed / f"{base}.zip").is_file(),
                            "the classified ZIP stays in the Maven lane")
            self.assertFalse((installed / f"{base}.tar.gz").exists(),
                             "zip opt-out must not install the tar.gz copy")

    def test_archive_format_targz_with_maven_lane_is_rejected(self):
        """tar.gz-only cannot silently drop the classified ZIP the install lane needs."""
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            shutil.copy2(REPOSITORY / "build-dist.sh", root / "build-dist.sh")
            result = subprocess.run(
                [
                    "bash", "./build-dist.sh", "cli-only",
                    "--platform", "linux-x86_64",
                    "--archive-format", "tar.gz",
                ],
                cwd=root,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("--skip-maven-install", result.stderr)

    def test_native_stager_preserves_manifest_owned_rocm_kernel_packs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            helper_dir = root / "helpers"
            helper_dir.mkdir()
            stager = helper_dir / "stage-native-libs.sh"
            shutil.copy2(
                REPOSITORY / "kompile-dist" / "src" / "main" / "build" /
                "stage-native-libs.sh",
                stager,
            )
            stager.chmod(0o755)
            normalizer = helper_dir / "normalize-elf-portability.sh"
            normalizer.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            normalizer.chmod(0o755)

            classifier = "linux-x86_64-zluda-rocm-10.0.0"
            backend = (
                root / "source" / "org" / "nd4j" / "linalg" / "jcublas" /
                "bindings" / classifier
            )
            resources = backend / ".kpack"
            resources.mkdir(parents=True)
            (resources / "blas_lib_gfx1103.kpack").write_bytes(b"blas-pack")
            (resources / "sparse_lib_gfx1103.kpack").write_bytes(b"sparse-pack")
            shutil.copy2("/bin/true", backend / "libnd4jcuda.so")
            shutil.copy2("/bin/true", backend / "libjnind4jcuda.so")
            (backend / "shared-runtime-manifest.txt").write_text(
                "# nd4j-shared-runtime-manifest-v1\n"
                "# runtime-count=2\n"
                "# resource-count=2\n"
                "# resource=.kpack/blas_lib_gfx1103.kpack\n"
                "# resource=.kpack/sparse_lib_gfx1103.kpack\n"
                "libnd4jcuda.so\n"
                "libjnind4jcuda.so\n",
                encoding="utf-8",
            )

            destination = root / "dist" / "lib"
            command = [
                "bash", str(stager), str(root / "source"), str(destination),
                "linux-x86_64", "-zluda-rocm-10.0.0", "nd4j-zluda-12.9",
            ]
            completed = subprocess.run(
                command, check=False, capture_output=True, text=True,
            )
            self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
            self.assertEqual(
                b"blas-pack", (destination / ".kpack" / "blas_lib_gfx1103.kpack").read_bytes(),
            )
            self.assertEqual(
                b"sparse-pack", (destination / ".kpack" / "sparse_lib_gfx1103.kpack").read_bytes(),
            )

            (resources / "sparse_lib_gfx1103.kpack").unlink()
            failed = subprocess.run(
                [*command[:3], str(root / "missing-dist" / "lib"), *command[4:]],
                check=False, capture_output=True, text=True,
            )
            self.assertNotEqual(0, failed.returncode)
            self.assertIn("runtime resource is missing", failed.stdout + failed.stderr)

    def test_exec_jar_reuse_requires_exact_rocm_classifier(self):
        source = (REPOSITORY / "build-dist.sh").read_text(encoding="utf-8")
        marker = "exec_jar_matches_backend() {"
        function_body = source.split(marker, 1)[1].split(
            '\n}\n\nif [ "${SKIP_JAVA_BUILD}"', 1,
        )[0]
        function = marker + function_body + "\n}\n"
        backend = "nd4j-zluda-12.9"
        version = "1.0.0-SNAPSHOT"
        classifier = "linux-x86_64-zluda-rocm-10.0.0"
        script = (
            f'ND4J_BACKEND="{backend}"\n'
            f'ND4J_VERSION="{version}"\n'
            f'SDK_CLASSIFIER="{classifier}"\n'
            'KOMPILE_BACKEND_PROFILE="zluda-rocm-10.0.0"\n'
            + function
            + 'exec_jar_matches_backend "$1"\n'
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            exact = root / "exact.jar"
            stale = root / "stale.jar"
            with zipfile.ZipFile(exact, "w") as archive:
                archive.writestr(
                    f"BOOT-INF/lib/{backend}-{version}.jar", b"java-backend",
                )
                archive.writestr(
                    f"BOOT-INF/lib/{backend}-{version}-{classifier}.jar",
                    b"rocm10-native",
                )
            with zipfile.ZipFile(stale, "w") as archive:
                archive.writestr(
                    f"BOOT-INF/lib/{backend}-{version}.jar", b"java-backend",
                )
                archive.writestr(
                    f"BOOT-INF/lib/{backend}-{version}-linux-x86_64-zluda-rocm-7.2.4.jar",
                    b"rocm7-native",
                )
            exact_result = subprocess.run(
                ["bash", "-c", script, "bash", str(exact)],
                check=False,
                capture_output=True,
                text=True,
            )
            stale_result = subprocess.run(
                ["bash", "-c", script, "bash", str(stale)],
                check=False,
                capture_output=True,
                text=True,
            )
        self.assertEqual(
            0, exact_result.returncode, exact_result.stdout + exact_result.stderr,
        )
        self.assertNotEqual(0, stale_result.returncode)

    def test_repository_only_backend_assembly_produces_self_contained_zip(self):
        maven = shutil.which("mvn")
        configured_maven = pathlib.Path("/home/agibsonccc/dev-apps/mvn/bin/mvn")
        if maven is None and configured_maven.is_file():
            maven = str(configured_maven)
        if maven is None:
            self.skipTest("Maven is required to verify classified distribution installation")
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            shutil.copy2(REPOSITORY / "build-dist.sh", root / "build-dist.sh")
            (root / "build-dist.sh").chmod(0o755)
            # Like a release checkout, carry the first-party skill packages that
            # build-dist.sh ships as managed payload and fails closed without.
            shutil.copytree(REPOSITORY / "skills", root / "skills")
            (root / "pom.xml").write_text(
                "<project><modelVersion>4.0.0</modelVersion>"
                "<groupId>ai.kompile</groupId><artifactId>synthetic-root</artifactId>"
                "<version>0.1.0-SNAPSHOT</version></project>\n",
                encoding="utf-8",
            )
            normalizer = root / "kompile-dist" / "src" / "main" / "build"
            normalizer.mkdir(parents=True)
            helper = normalizer / "normalize-elf-portability.sh"
            helper.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            helper.chmod(0o755)
            validator = normalizer / "validate-sdx-assets.sh"
            shutil.copy2(
                REPOSITORY / "kompile-dist" / "src" / "main" / "build" /
                "validate-sdx-assets.sh",
                validator,
            )
            validator.chmod(0o755)
            native_stager = normalizer / "stage-native-libs.sh"
            shutil.copy2(
                REPOSITORY / "kompile-dist" / "src" / "main" / "build" /
                "stage-native-libs.sh",
                native_stager,
            )
            native_stager.chmod(0o755)
            (root / "kompile-dist" / "pom.xml").write_text(
                "<project><modelVersion>4.0.0</modelVersion>"
                "<groupId>ai.kompile</groupId><artifactId>kompile-dist</artifactId>"
                "<version>0.1.0-SNAPSHOT</version><packaging>pom</packaging></project>\n",
                encoding="utf-8",
            )

            files = {
                "kompile-cli/kompile-cli-main/target/kompile-cli-main": b"native-cli",
                "kompile-cli/kompile-agent-cli/target/kompile-agent": b"native-agent-cli",
                "kompile-cli/kompile-app-cli/target/kompile-app-cli": b"native-app-cli",
                "kompile-cli/kompile-model-cli/target/kompile-model": b"native-model-cli",
                "kompile-cli/kompile-component-cli/target/kompile-component": b"native-component-cli",
                "kompile-app/kompile-app-parent/kompile-app-main/target/kompile-app": b"native-app",
                "kompile-app/kompile-app-parent/kompile-app-main/target/app-exec.jar": b"app",
                "kompile-app/kompile-app-parent/kompile-app-main/target/kompile-vlm-test": b"native-vlm",
                "kompile-app/kompile-models/kompile-model-staging/target/kompile-model-staging": b"native-staging",
                "kompile-app/kompile-models/kompile-model-staging/target/staging-exec.jar": b"staging",
                "kompile-app/kompile-app-parent/kompile-app-subprocess/kompile-app-subprocess-serving/target/kompile-model-serving": b"native-model-serving",
                "kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving/target/kompile-pipeline-serving": b"native-pipeline-serving",
                "kompile-app/kompile-app-parent/kompile-app-chat/target/kompile-chat": b"native-chat",
                "kompile-app/kompile-app-parent/kompile-app-chat/target/chat-exec.jar": b"chat",
                "kompile-app/kompile-app-parent/kompile-app-crawl-manager/target/kompile-crawl-manager": b"native-crawl",
                "kompile-app/kompile-app-parent/kompile-app-crawl-manager/target/crawl-exec.jar": b"crawl",
                "kompile-app/kompile-data/kompile-compute-graphs/kompile-compute-graph-scripting/target/scripting-exec.jar": b"scripting",
            }
            for relative, content in files.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
                if not relative.endswith(".jar"):
                    path.chmod(0o755)
            (
                root / "kompile-cli" / "kompile-cli-main" / "target" /
                "native-libs"
            ).mkdir()
            app_native_libraries = (
                root / "kompile-app" / "kompile-app-parent" /
                "kompile-app-main" / "target" / "native-libs"
            )
            backend_manifest = (
                app_native_libraries / "org" / "nd4j" / "linalg" / "cpu" /
                "nativecpu" / "bindings" / "linux-x86_64-avx2" /
                "shared-runtime-manifest.txt"
            )
            backend_manifest.parent.mkdir(parents=True)
            backend_manifest.write_text(
                "# nd4j-shared-runtime-manifest-v1\n"
                "# runtime-count=2\n"
                "libnd4jcpu.so\n"
                "libjnind4jcpu.so\n",
                encoding="utf-8",
            )
            shutil.copy2(
                "/bin/true", backend_manifest.parent / "libnd4jcpu.so"
            )
            shutil.copy2(
                "/bin/true", backend_manifest.parent / "libjnind4jcpu.so"
            )

            # cpu-intel sets LOCAL_RUNTIME=true (ND4J_BACKEND is non-empty), so
            # build-dist.sh also stages each request-scoped serving worker's own
            # native-library tree (stage-native-libs.sh calls for
            # kompile-app-subprocess-serving and kompile-pipeline-serving): every
            # module needs its own producer-owned nd4j-native manifest, same as
            # app-main above.
            for local_runtime_module in (
                "kompile-app/kompile-app-parent/kompile-app-subprocess/kompile-app-subprocess-serving",
                "kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving",
            ):
                runtime_manifest = (
                    root / local_runtime_module / "target" / "native-libs" /
                    "org" / "nd4j" / "linalg" / "cpu" / "nativecpu" / "bindings" /
                    "linux-x86_64-avx2" / "shared-runtime-manifest.txt"
                )
                runtime_manifest.parent.mkdir(parents=True)
                runtime_manifest.write_text(
                    "# nd4j-shared-runtime-manifest-v1\n"
                    "# runtime-count=2\n"
                    "libnd4jcpu.so\n"
                    "libjnind4jcpu.so\n",
                    encoding="utf-8",
                )
                shutil.copy2(
                    "/bin/true", runtime_manifest.parent / "libnd4jcpu.so"
                )
                shutil.copy2(
                    "/bin/true", runtime_manifest.parent / "libjnind4jcpu.so"
                )

            sdk = root / "sdk-assets"
            (sdk / "jars").mkdir(parents=True)
            (sdk / "runtime.zip").write_bytes(b"PK\x03\x04runtime")
            sdk_jars = (
                "nd4j-native-1.0.0-SNAPSHOT-linux-x86_64-avx2.jar",
                "nd4j-native-preset-1.0.0-SNAPSHOT.jar",
                "nd4j-native-platform-1.0.0-SNAPSHOT.jar",
                "libtokenizers-1.0.0-SNAPSHOT.jar",
                "tokenizers-native-preset-1.0.0-SNAPSHOT.jar",
                "tokenizers-native-1.0.0-SNAPSHOT.jar",
            )
            for jar_name in sdk_jars:
                (sdk / "jars" / jar_name).write_bytes(b"jar")
            output = root / "output"
            env = os.environ.copy()
            env.update({
                "KOMPILE_JAVA": "/bin/false",
                "DL4J_MAVEN_REPOSITORY_URL": (root / "maven").as_uri(),
                "KOMPILE_MAVEN_REPO": str(root / "m2"),
                "MVN": maven,
            })
            result = subprocess.run(
                [
                    "bash", "./build-dist.sh", "cpu-intel",
                    "--skip-java-build", "--skip-native",
                    "--platform", "linux-x86_64", "--sdx-assets", str(sdk),
                    "--output-dir", str(output), "--version", "0.1.0-SNAPSHOT",
                ],
                cwd=root,
                env=env,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            archive = output / "kompile-dist-0.1.0-SNAPSHOT-cpu-intel-linux-x86_64-avx2.zip"
            self.assertTrue(archive.is_file())
            with zipfile.ZipFile(archive) as contents:
                names = set(contents.namelist())
                prefix = "kompile-dist-0.1.0-SNAPSHOT-cpu-intel-linux-x86_64-avx2/"
                self.assertIn(prefix + "manifest.sha256", names)
                self.assertIn(prefix + "sdx-sdk/runtime.zip", names)
                self.assertIn(
                    prefix + "sdx-sdk/jars/nd4j-native-1.0.0-SNAPSHOT-linux-x86_64-avx2.jar",
                    names,
                )
                self.assertIn(prefix + "lib/kompile-server.jar", names)
                skills = REPOSITORY / "skills"
                for skill in skills.rglob("*"):
                    if skill.is_file():
                        self.assertIn(
                            prefix + "lib/skills/" + skill.relative_to(skills).as_posix(),
                            names,
                        )
                manifest = contents.read(prefix + "manifest.sha256").decode()
                self.assertIn("sdx-sdk/runtime.zip", manifest)
                self.assertIn("lib/kompile-server.jar", manifest)
            installed_directory = (
                root / "m2" / "ai" / "kompile" / "kompile-dist" /
                "0.1.0-SNAPSHOT"
            )
            installed_zip = (
                installed_directory /
                "kompile-dist-0.1.0-SNAPSHOT-cpu-intel-linux-x86_64-avx2.zip"
            )
            installed_tar = (
                installed_directory /
                "kompile-dist-0.1.0-SNAPSHOT-cpu-intel-linux-x86_64-avx2.tar.gz"
            )
            self.assertTrue(installed_zip.is_file())
            self.assertTrue(installed_tar.is_file())

    def test_collector_surfaces_verified_inner_distribution_zip(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            archive = root / "sdk-assets.tar.gz"
            payload = b"PK\x03\x04complete-kompile"
            record = {
                "path": "kompile-dist-0.1.0-full-linux-x86_64.zip",
                "sha256": hashlib.sha256(payload).hexdigest(),
                "size": len(payload),
            }
            with zipfile.ZipFile(archive, "w") as contents:
                contents.writestr(record["path"], payload)
                contents.writestr("artifacts.json", json.dumps([record]))
                contents.writestr(
                    "kompile-dist-0.1.0-full-linux-x86_64/sdx-sdk/artifacts.json",
                    json.dumps([{"path": "upstream-runtime.zip"}]),
                )
            output = root / "collected"
            output.mkdir()
            assets = MODULE.collect_inner_zip_assets(
                archive, output, "distribution-linux", "s3://worker/sdk-assets", set(),
            )
            names = {item["fileName"] for item in assets}
            self.assertEqual(
                {
                    record["path"],
                    record["path"] + ".sha256",
                },
                names,
            )
            self.assertTrue((output / record["path"]).is_file())

    def test_maven_distribution_stages_only_complete_sdk_assets(self):
        script = REPOSITORY / "kompile-dist" / "src" / "main" / "build" / "stage-sdx-assets.sh"
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            complete = root / "complete"
            (complete / "jars").mkdir(parents=True)
            (complete / "runtime.zip").write_bytes(b"runtime")
            required = (
                "nd4j-native-1.0.0-SNAPSHOT-linux-x86_64.jar",
                "nd4j-native-preset-1.0.0-SNAPSHOT.jar",
                "nd4j-native-platform-1.0.0-SNAPSHOT.jar",
                "libtokenizers-1.0.0-SNAPSHOT.jar",
                "tokenizers-native-preset-1.0.0-SNAPSHOT.jar",
                "tokenizers-native-1.0.0-SNAPSHOT.jar",
            )
            for jar_name in required:
                (complete / "jars" / jar_name).write_bytes(b"jar")
            staged = root / "staged"
            validator_args = ["full", "linux-x86_64", "1.0.0-SNAPSHOT", "12.9"]
            subprocess.run(
                ["bash", str(script), str(complete), str(staged), *validator_args],
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertTrue((staged / "runtime.zip").is_file())
            self.assertTrue((staged / "jars" / required[0]).is_file())

            cuda = root / "cuda-12.6"
            (cuda / "jars").mkdir(parents=True)
            (cuda / "runtime.zip").write_bytes(b"runtime")
            cuda_required = (
                "nd4j-cuda-12.6-1.0.0-SNAPSHOT-linux-x86_64.jar",
                "nd4j-cuda-12.6-preset-1.0.0-SNAPSHOT.jar",
                "nd4j-cuda-12.6-platform-1.0.0-SNAPSHOT.jar",
            )
            for jar_name in cuda_required:
                (cuda / "jars" / jar_name).write_bytes(b"jar")
            subprocess.run(
                [
                    "bash", str(script), str(cuda), str(root / "cuda-staged"),
                    "cuda", "linux-x86_64", "1.0.0-SNAPSHOT", "12.6",
                ],
                check=True,
                capture_output=True,
                text=True,
            )

            android = root / "android"
            (android / "jars").mkdir(parents=True)
            (android / "runtime.aar").write_bytes(b"runtime")
            for jar_name in (
                "nd4j-native-1.0.0-SNAPSHOT-android-arm64.jar",
                "nd4j-native-preset-1.0.0-SNAPSHOT.jar",
            ):
                (android / "jars" / jar_name).write_bytes(b"jar")
            subprocess.run(
                [
                    "bash", str(script), str(android), str(root / "android-staged"),
                    "android", "android-arm64", "1.0.0-SNAPSHOT", "12.9",
                ],
                check=True,
                capture_output=True,
                text=True,
            )

            vulkan = root / "vulkan"
            (vulkan / "jars").mkdir(parents=True)
            for jar_name in (
                "nd4j-vulkan-1.0.0-SNAPSHOT-linux-x86_64.jar",
                "nd4j-vulkan-preset-1.0.0-SNAPSHOT.jar",
            ):
                (vulkan / "jars" / jar_name).write_bytes(b"jar")
            subprocess.run(
                [
                    "bash", str(script), str(vulkan), str(root / "vulkan-staged"),
                    "vulkan", "linux-x86_64", "1.0.0-SNAPSHOT", "12.9",
                ],
                check=True,
                capture_output=True,
                text=True,
            )

            compat = root / "compat"
            (compat / "jars").mkdir(parents=True)
            for jar_name in (
                "nd4j-native-1.0.0-SNAPSHOT-linux-x86_64-compat.jar",
                "nd4j-native-preset-1.0.0-SNAPSHOT.jar",
            ):
                (compat / "jars" / jar_name).write_bytes(b"jar")
            subprocess.run(
                [
                    "bash", str(script), str(compat), str(root / "compat-staged"),
                    "compat", "linux-x86_64", "1.0.0-SNAPSHOT", "12.9",
                ],
                check=True,
                capture_output=True,
                text=True,
            )

            windows_zluda = root / "windows-zluda"
            (windows_zluda / "jars").mkdir(parents=True)
            for jar_name in (
                "nd4j-zluda-12.9-1.0.0-SNAPSHOT.jar",
                "nd4j-zluda-12.9-1.0.0-SNAPSHOT-windows-x86_64-zluda-rocm-7.2.4.jar",
                "nd4j-zluda-12.9-platform-1.0.0-SNAPSHOT.jar",
                "nd4j-cuda-12.9-preset-1.0.0-SNAPSHOT.jar",
                "nd4j-cuda-backend-common-1.0.0-SNAPSHOT.jar",
                "nd4j-presets-common-1.0.0-SNAPSHOT.jar",
            ):
                (windows_zluda / "jars" / jar_name).write_bytes(b"jar")
            subprocess.run(
                [
                    "bash", str(script), str(windows_zluda), str(root / "windows-zluda-staged"),
                    "amd-zluda", "windows-x86_64", "1.0.0-SNAPSHOT", "12.9",
                ],
                check=True,
                capture_output=True,
                text=True,
            )

            legacy_zluda = root / "legacy-windows-zluda"
            (legacy_zluda / "jars").mkdir(parents=True)
            for jar_name in (
                "nd4j-cuda-12.9-1.0.0-SNAPSHOT-windows-x86_64-zluda.jar",
                "nd4j-cuda-12.9-preset-1.0.0-SNAPSHOT.jar",
            ):
                (legacy_zluda / "jars" / jar_name).write_bytes(b"jar")
            subprocess.run(
                [
                    "bash", str(script), str(legacy_zluda), str(root / "legacy-zluda-staged"),
                    "zluda", "windows-x86_64", "1.0.0-SNAPSHOT", "12.9",
                ],
                check=True,
                capture_output=True,
                text=True,
            )

            partial = root / "partial"
            (partial / "jars").mkdir(parents=True)
            (partial / "runtime.zip").write_bytes(b"runtime")
            (partial / "jars" / required[0]).write_bytes(b"jar")
            result = subprocess.run(
                [
                    "bash", str(script), str(partial), str(root / "partial-staged"),
                    *validator_args,
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("incomplete DL4J SDK shard", result.stderr)
            self.assertIn("missing artifactId: nd4j-native-preset", result.stderr)

            prefix_only = root / "prefix-only"
            (prefix_only / "jars").mkdir(parents=True)
            (prefix_only / "runtime.zip").write_bytes(b"runtime")
            for jar_name in required[1:]:
                (prefix_only / "jars" / jar_name).write_bytes(b"jar")
            result = subprocess.run(
                [
                    "bash", str(script), str(prefix_only), str(root / "prefix-staged"),
                    *validator_args,
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("missing artifactId: nd4j-native", result.stderr)

            wrong_version = root / "wrong-version"
            (wrong_version / "jars").mkdir(parents=True)
            (wrong_version / "runtime.zip").write_bytes(b"runtime")
            stable_required = (
                "nd4j-native-1.0.0-SNAPSHOT-linux-x86_64.jar",
                "nd4j-native-preset-1.0.0.jar",
                "nd4j-native-platform-1.0.0.jar",
                "libtokenizers-1.0.0.jar",
                "tokenizers-native-preset-1.0.0.jar",
                "tokenizers-native-1.0.0.jar",
            )
            for jar_name in stable_required:
                (wrong_version / "jars" / jar_name).write_bytes(b"jar")
            result = subprocess.run(
                [
                    "bash", str(script), str(wrong_version), str(root / "wrong-version-staged"),
                    "full", "linux-x86_64", "1.0.0", "12.9",
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("missing artifactId: nd4j-native", result.stderr)

    def test_android_gradle_uses_isolated_maven_repository(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            source = root / "source"
            android = source / "kompile-chat-local" / "mobile" / "android"
            android.mkdir(parents=True)
            (android / "gradlew").write_text("#!/usr/bin/env sh\n", encoding="utf-8")
            apk = android / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"apk")
            assets = root / "assets"
            assets.mkdir()
            repository = root / "isolated m2"
            config = {
                "releaseVersion": "0.1.0-SNAPSHOT",
                "snapshotVersion": "1.0.0-SNAPSHOT",
                "shard": {"build": {"javacppPlatform": "android-arm64"}},
            }
            with patch.object(BUILD_MODULE, "ensure_android_sdk", return_value={}), \
                 patch.object(BUILD_MODULE, "run") as run:
                BUILD_MODULE.build_android(config, source, repository, assets)
            gradle_calls = [
                call for call in run.call_args_list
                if pathlib.Path(call.args[0][0]).name in {"gradlew", "gradlew.bat"}
            ]
            self.assertEqual(1, len(gradle_calls))
            self.assertEqual(str(repository), gradle_calls[0].args[2]["KOMPILE_MAVEN_REPO"])
            settings = (REPOSITORY / "kompile-chat-local" / "mobile" / "android" /
                        "settings.gradle.kts").read_text(encoding="utf-8")
            self.assertIn('System.getenv("KOMPILE_MAVEN_REPO")', settings)

    def test_cuda_dual_backend_keeps_cpu_fallback_on_base_classifier(self):
        poms = (
            "kompile-app/kompile-app-parent/kompile-app-main/pom.xml",
            "kompile-app/kompile-app-parent/kompile-app-chat/pom.xml",
            "kompile-app/kompile-app-parent/kompile-app-crawl-manager/pom.xml",
            "kompile-app/kompile-models/kompile-model-staging/pom.xml",
        )
        for relative in poms:
            source = (REPOSITORY / relative).read_text(encoding="utf-8")
            profile = re.search(
                r"<profile>\s*<id>cuda-dual-backend</id>.*?</profile>", source, re.DOTALL,
            )
            self.assertIsNotNone(profile, relative)
            self.assertIn(
                "<classifier>${javacpp.platform}</classifier>", profile.group(0), relative,
            )
            self.assertNotIn(
                "<classifier>${javacpp.platform}${javacpp.platform.extension}</classifier>",
                profile.group(0), relative,
            )

    def test_build_script_accepts_android_and_accelerator_release_lanes(self):
        source = (REPOSITORY / "build-scripts" / "build-common.sh").read_text(encoding="utf-8")
        for lane in (
            "android-arm64", "android-arm64-armcompute", "android-arm64-nnapi",
            "android-arm64-compile", "android-arm64-compile-nnapi",
            "android-x86_64", "android-x86_64-onednn", "android-x86_64-compile",
            "linux-x86_64-vulkan", "linux-x86_64-vulkan-compile",
            "linux-x86_64-hexagon", "linux-x86_64-tpu",
            "linux-x86_64-cuda-12.9-zluda-rocm-7.2.4",
            "windows-x86_64-cuda-12.9-zluda-rocm-7.2.4",
            "linux-x86_64-cuda-12.9-zluda-rocm-10.0.0",
        ):
            self.assertIn(f'"{lane}"', source)
        self.assertIn('*cuda*-cudnn) echo "${base}-cudnn"', source)
        self.assertIn('*cuda*-compile) echo "${base}-compile"', source)
        self.assertIn('*cuda*-zluda) echo "${base}-zluda"', source)
        self.assertIn('*cuda*-zluda-rocm-*)', source)
        self.assertIn(
            '*zluda-rocm-*) sdk_namespaces=(org/eclipse/deeplearning4j)',
            source,
        )
        self.assertIn('"-Djavacpp.platform=${javacpp_platform}"', source)
        self.assertIn('--distribution-classifier "${distribution_classifier}"', source)

    def test_rocm_wrapper_defaults_to_7_and_rocm_10_fails_closed_off_linux(self):
        wrapper = (REPOSITORY / "build-scripts" / "build-kompile-rocm.sh").read_text(
            encoding="utf-8",
        )
        self.assertIn('KOMPILE_ROCM_VERSION:-7.2.4', wrapper)
        self.assertIn('7.2.4|10.0.0', wrapper)
        self.assertIn('kompile_build_for_platform "${PLATFORM}" 1', wrapper)

        with tempfile.TemporaryDirectory() as temporary:
            completed = subprocess.run(
                [
                    "bash", str(REPOSITORY / "build-dist.sh"), "amd-zluda",
                    "--backend-profile", "zluda-rocm-10.0.0",
                    "--platform", "windows-x86_64",
                    "--version", "0.1.0-SNAPSHOT",
                    "--output-dir", temporary,
                    "--skip-java-build", "--skip-native", "--skip-maven-install",
                ],
                cwd=REPOSITORY,
                check=False,
                capture_output=True,
                text=True,
            )
        self.assertNotEqual(0, completed.returncode)
        self.assertIn(
            "ROCm 10 ZLUDA distributions are supported only on linux-x86_64",
            completed.stdout + completed.stderr,
        )

        with tempfile.TemporaryDirectory() as temporary:
            sdk = pathlib.Path(temporary)
            (sdk / "jars").mkdir()
            low_level = subprocess.run(
                [
                    "bash",
                    str(
                        REPOSITORY / "kompile-dist" / "src" / "main" /
                        "build" / "validate-sdx-assets.sh"
                    ),
                    str(sdk), "amd-zluda", "windows-x86_64",
                    "1.0.0-SNAPSHOT", "12.9", "nd4j-zluda-12.9",
                    "windows-x86_64-zluda-rocm-10.0.0",
                ],
                check=False,
                capture_output=True,
                text=True,
            )
        self.assertNotEqual(0, low_level.returncode)
        self.assertIn(
            "unsupported version-qualified ZLUDA classifier",
            low_level.stdout + low_level.stderr,
        )

        installer = (REPOSITORY / "install.sh").read_text(encoding="utf-8")
        self.assertIn('BACKEND_PROFILE="zluda-rocm-7.2.4"', installer)
        self.assertIn(
            'BACKEND_PROFILE}" = "zluda-rocm-10.0.0"', installer,
        )

    def test_maven_profiles_match_dl4j_release_classifier_contract(self):
        source = (REPOSITORY / "pom.xml").read_text(encoding="utf-8")

        def profile(name):
            match = re.search(
                rf"<profile>\s*<id>backend-{re.escape(name)}</id>.*?</profile>",
                source,
                re.DOTALL,
            )
            self.assertIsNotNone(match, name)
            return match.group(0)

        self.assertIn(
            "<javacpp.platform.extension></javacpp.platform.extension>",
            profile("cuda-12.6"),
        )
        self.assertIn(
            "<javacpp.platform.extension>-cudnn</javacpp.platform.extension>",
            profile("cuda-12.6-cudnn"),
        )
        self.assertIn(
            "<javacpp.platform.extension>-compile</javacpp.platform.extension>",
            profile("cuda-12.9-compile"),
        )
        self.assertIn(
            "<javacpp.platform.extension>-zluda</javacpp.platform.extension>",
            profile("zluda"),
        )
        self.assertIn(
            "${javacpp.platform}-cuda-12.9-zluda",
            profile("zluda"),
        )
        self.assertIn(
            "<nd4j.backend>nd4j-zluda-12.9</nd4j.backend>",
            profile("zluda-rocm-7.2.4"),
        )
        self.assertIn(
            "<javacpp.platform.extension>-zluda-rocm-7.2.4</javacpp.platform.extension>",
            profile("zluda-rocm-7.2.4"),
        )
        self.assertIn(
            "<nd4j.native.backend>nd4j-zluda-12.9</nd4j.native.backend>",
            profile("zluda-rocm-10.0.0"),
        )
        self.assertIn(
            "${javacpp.platform}-cuda-12.9-zluda-rocm-10.0.0",
            profile("zluda-rocm-10.0.0"),
        )
        self.assertIn(
            "require-rocm-10-linux-x86-64",
            profile("zluda-rocm-10.0.0"),
        )
        for cpu_variant in (
            "cpu-avx2", "cpu-avx512", "cpu-onednn", "cpu-onednn-avx2",
            "cpu-onednn-avx512", "cpu-compile", "cpu-compile-avx2",
            "cpu-compile-avx512", "cpu-armcompute", "cpu-mps",
            "cpu-mps-compile", "cpu-nnapi", "cpu-compile-nnapi",
        ):
            self.assertNotIn("tokenizers.platform.classifier", profile(cpu_variant))
        self.assertNotIn("tokenizers.platform.classifier", profile("cpu-compat"))

        distribution_pom = (REPOSITORY / "kompile-dist" / "pom.xml").read_text(
            encoding="utf-8"
        )
        self.assertIn(
            "<kompile.sdk.classifier>${javacpp.platform}${javacpp.platform.extension}</kompile.sdk.classifier>",
            distribution_pom,
        )
        self.assertIn('<arg value="${javacpp.platform}"/>', distribution_pom)
        self.assertIn('<arg value="${javacpp.platform.extension}"/>', distribution_pom)
        assembly = (
            REPOSITORY / "kompile-dist" / "src" / "main" / "assembly" / "dist.xml"
        ).read_text(encoding="utf-8")
        self.assertIn("<id>${kompile.distribution.classifier}</id>", assembly)
        self.assertIn("<include>.kpack/**</include>", assembly)
        app_main = (
            REPOSITORY / "kompile-app" / "kompile-app-parent" /
            "kompile-app-main" / "pom.xml"
        ).read_text(encoding="utf-8")
        self.assertEqual(2, app_main.count("**/*.kpack"))

    def test_stages_only_kompile_maven_coordinates(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            repository = root / "m2"
            artifact = repository / "ai" / "kompile" / "example" / "0.1.0-SNAPSHOT"
            artifact.mkdir(parents=True)
            (artifact / "example-0.1.0-SNAPSHOT.jar").write_bytes(b"jar")
            dl4j = (
                repository / "org" / "eclipse" / "deeplearning4j" / "nd4j-native" /
                "1.0.0-SNAPSHOT"
            )
            dl4j.mkdir(parents=True)
            (dl4j / "nd4j-native-1.0.0-SNAPSHOT.jar").write_bytes(b"dependency")
            output = root / "output"
            BUILD_MODULE.stage_kompile_maven_artifacts(repository, output)
            self.assertTrue(
                (output / "ai" / "kompile" / "example" / "0.1.0-SNAPSHOT" /
                 "example-0.1.0-SNAPSHOT.jar").is_file()
            )
            self.assertFalse((output / "org").exists())


class GithubWorkflowParityTest(unittest.TestCase):
    def test_java_distribution_smoke_covers_supported_release_platforms(self):
        source = (
            REPOSITORY / ".github" / "workflows" /
            "build-java-distributions.yml"
        ).read_text(encoding="utf-8")
        expected_runners = {
            "linux-x86_64": "ubuntu-22.04",
            "linux-arm64": "ubuntu-24.04-arm",
            "windows-x86_64": "windows-2022",
            "macosx-arm64": "macos-14",
        }
        for platform, runner in expected_runners.items():
            self.assertIn(f"'{platform}': '{runner}'", source)
        self.assertNotIn("windows-11-arm", source)
        self.assertNotIn("macos-15-intel", source)
        self.assertIn("distribution:", source)
        self.assertIn("- cli", source)
        self.assertIn("- full", source)
        self.assertIn("- both", source)
        self.assertIn("--jars-only", source)
        self.assertIn("--skip-maven-install", source)
        self.assertIn("git config --global core.longpaths true", source)
        self.assertIn('-jar "${DIST_ROOT}/lib/kompile-cli.jar" --version', source)
        self.assertIn("actions/upload-artifact@v4", source)
        self.assertIn("contents: read", source)
        self.assertNotIn("contents: write", source)
        self.assertNotIn("gh release", source)

    def test_cpu_actions_use_explicit_seven_classifier_matrix(self):
        expected = {"base", "avx2", "avx512", "onednn", "onednn-avx2", "onednn-avx512", "compile"}
        for name in ("build-native-linux-x86_64.yml", "build-native-windows-x86_64.yml"):
            source = (REPOSITORY / ".github" / "workflows" / name).read_text(encoding="utf-8")
            names = set(re.findall(
                r"^\s+- \{ name: (base|avx2|avx512|onednn|onednn-avx2|onednn-avx512|compile),",
                source, flags=re.MULTILINE,
            ))
            self.assertEqual(expected, names, name)
            self.assertNotIn("compile-avx2", source)
            self.assertNotIn("compile-avx512", source)

    def test_native_actions_use_real_nested_modules_and_binary_names(self):
        expected = {
            "kompile-cli-main": "kompile-cli-main",
            "kompile-agent-cli": "kompile-agent",
            "kompile-app-cli": "kompile-app-cli",
            "kompile-model-cli": "kompile-model",
            "kompile-component-cli": "kompile-component",
        }
        workflows = (
            "build-native-linux-x86_64.yml",
            "build-native-linux-arm64.yml",
            "build-native-mac-arm64.yml",
            "build-native-windows-x86_64.yml",
        )
        for name in workflows:
            source = (
                REPOSITORY / ".github" / "workflows" / name
            ).read_text(encoding="utf-8").replace("\\", "/")
            suffix = ".exe" if "windows" in name else ""
            shared_orchestrator = "./build-scripts/build-kompile-native-only.sh" in source
            if shared_orchestrator:
                self.assertIn('NATIVE_TARGETS="${TARGETS}"', source, name)
            for module, binary in expected.items():
                if not shared_orchestrator:
                    self.assertIn(f"cd kompile-cli/{module}", source, name)
                self.assertIn(
                    f"cp kompile-cli/{module}/target/{binary}{suffix} native-binaries/",
                    source,
                    name,
                )
            self.assertNotIn("cd kompile-agent-cli", source)
            self.assertNotIn("cd kompile-app-cli", source)
            self.assertNotIn("cd kompile-model-cli", source)
            self.assertNotIn("cd kompile-component-cli", source)
            self.assertNotIn("cp kompile-cli/target/", source)

    def test_external_workflow_cannot_create_publish_or_overwrite_release(self):
        source = (REPOSITORY / ".github" / "workflows" / "publish-external-aws-release.yml").read_text(encoding="utf-8")
        self.assertNotIn("gh release create", source)
        self.assertNotIn("gh release edit", source)
        self.assertNotIn("--clobber", source)
        self.assertNotIn("publish:", source)
        self.assertIn("Existing canonical GitHub release tag", source)
        self.assertIn("group: release-${{ inputs.releaseTag }}", source)
        for variable in (
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
            "AWS_REGION", "AWS_DEFAULT_REGION", "GH_TOKEN",
        ):
            self.assertIn(variable, source)

    def test_composite_build_actions_never_mutate_github_releases(self):
        for name in (
            "publish-native-binaries",
            "publish-sdk-jars",
            "publish-sdx-runtime-sdk",
            "package-distribution",
        ):
            source = (
                REPOSITORY / ".github" / "actions" / name / "action.yml"
            ).read_text(encoding="utf-8")
            self.assertIn("actions/upload-artifact@v4", source, name)
            self.assertNotIn("gh release create", source, name)
            self.assertNotIn("gh release upload", source, name)
            self.assertNotIn("--clobber", source, name)

    def test_composite_distribution_action_emits_complete_zip_contract(self):
        source = (
            REPOSITORY / ".github" / "actions" / "package-distribution" / "action.yml"
        ).read_text(encoding="utf-8")
        self.assertIn("sdx-assets-dir:", source)
        self.assertIn("nd4j-version:", source)
        self.assertIn("cuda-version:", source)
        self.assertIn("backend-artifact:", source)
        self.assertIn("sdx-classifier:", source)
        self.assertIn("distribution-classifier:", source)
        self.assertIn("validate-sdx-assets.sh", source)
        self.assertIn("manifest.sha256", source)
        self.assertIn('echo "archive-base=${DIST_NAME}"', source)
        self.assertIn('ARCHIVE_BASE="${{ steps.layout.outputs.archive-base }}"', source)
        self.assertIn('ARCHIVE_FILE="${STAGING}/${ARCHIVE_BASE}.zip"', source)
        self.assertNotIn('ARCHIVE_FILE="${STAGING}/${ARCHIVE_BASE}.tar.gz"', source)

    def test_canonical_github_release_splits_jvm_and_aot_runners(self):
        source = (
            REPOSITORY / ".github" / "workflows" / "release.yml"
        ).read_text(encoding="utf-8")
        self.assertIn("KOMPILE_JAVA_RUNNER", source)
        self.assertIn("KOMPILE_AOT_LINUX_X64_RUNNER", source)
        self.assertIn("KOMPILE_AOT_MACOS_ARM64_RUNNER", source)
        self.assertIn("KOMPILE_AOT_WINDOWS_X64_RUNNER", source)
        self.assertIn("EXPECTED_GIB=32", source)
        self.assertIn("AOT builds require at least ${EXPECTED_GIB} GiB RAM", source)
        self.assertIn("execution:", source)
        self.assertIn("distribution:", source)
        self.assertIn("platforms:", source)
        self.assertIn("uses: ./.github/workflows/build-java-distributions.yml", source)
        self.assertIn("default: false", source)
        self.assertIn("DL4J_MAVEN_REPOSITORY_URL", source)
        self.assertIn("DL4J_VERSION", source)
        self.assertIn("--jars-only", source)
        self.assertIn("-Dnative.quickBuild=false", source)
        self.assertIn("-DprocessAllModules=true", source)
        self.assertIn("-Dproperty=project.version", source)
        self.assertIn("-Dproperty=anserini.version", source)
        self.assertNotIn("dl4j_sdk_assets_url:", source)
        self.assertNotIn("DL4J_SDK_ASSETS_URL", source)
        self.assertIn('echo "archive=${NAME}.zip"', source)
        self.assertIn("compatibility_archive", source)
        self.assertIn(
            "name: kompile-dist-${{ steps.ver.outputs.version }}-full-linux-x86_64.zip",
            source,
        )
        self.assertIn("(cd \"$HOME/.kompile\" && sha256sum -c manifest.sha256)", source)
        self.assertIn('info["components"]["cli"]["native"] is False', source)
        self.assertIn("http://localhost:18090/mcp/status", source)
        self.assertIn("kompile-model-staging.jar", source)
        self.assertIn("publish:", source)
        self.assertIn("github.event_name == 'push' || inputs.publish", source)
        self.assertIn("!contains(needs.*.result, 'failure')", source)
        self.assertIn("target_commitish: ${{ github.sha }}", source)
        self.assertIn("release publication never moves an existing tag", source)
        self.assertNotIn("7z a -tzip", source)

    RUNNER_GUARD = (
        "      - name: Require a GitHub-hosted runner\n"
        "        if: runner.environment != 'github-hosted'\n"
        "        run: |\n"
        "          echo \"::error::Release jobs run on GitHub-hosted runners only; "
        "this runner reports '${{ runner.environment }}'.\"\n"
        "          exit 1\n"
    )

    @staticmethod
    def workflow_jobs(executable):
        """Maps each job id under `jobs:` to its body text."""
        lines = executable.splitlines()
        jobs = {}
        current = None
        for line in lines[lines.index("jobs:") + 1:]:
            header = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
            if header:
                current = header.group(1)
                jobs[current] = []
            elif current is not None:
                jobs[current].append(line)
        return {job: "\n".join(body) + "\n" for job, body in jobs.items()}

    def release_workflows(self):
        """Maps every workflow the release entry points reach to (text, jobs).

        Follows each local reusable-workflow call, so a workflow a release starts
        calling is checked too. Comment lines are removed from the text.
        """
        workflows = REPOSITORY / ".github" / "workflows"
        pending = ["release.yml", "publish-release.yml", "publish-external-aws-release.yml"]
        reached = {}
        while pending:
            name = pending.pop()
            if name in reached:
                continue
            executable = "\n".join(
                line for line in (workflows / name).read_text(encoding="utf-8").splitlines()
                if not line.lstrip().startswith("#")
            )
            jobs = self.workflow_jobs(executable)
            reached[name] = (executable, jobs)
            for job, body in jobs.items():
                call = re.search(r"^    uses: (\S+)\s*$", body, re.M)
                if call:
                    # A remote reusable workflow would run jobs this walk never sees.
                    target = re.fullmatch(r"\./\.github/workflows/([A-Za-z0-9._-]+\.yml)", call.group(1))
                    self.assertIsNotNone(target, f"{name}:{job} calls {call.group(1)}")
                    pending.append(target.group(1))
        self.assertLessEqual(
            {
                "release.yml", "build-java-distributions.yml", "mirror-release-to-r2.yml",
                "publish-release.yml", "publish-external-aws-release.yml",
                "build-native-linux-x86_64.yml", "build-native-linux-arm64.yml",
                "build-native-mac-arm64.yml", "build-native-windows-x86_64.yml",
                "build-native-linux-cuda.yml", "build-native-windows-cuda.yml",
            },
            set(reached),
        )
        return reached

    def test_release_workflows_run_on_github_hosted_runners(self):
        for name, (executable, jobs) in self.release_workflows().items():
            self.assertIn("runs-on:", executable, name)
            self.assertNotIn("self-hosted", executable, name)
            self.assertNotIn("release/azure", executable, name)
            self.assertTrue(jobs, name)
            for job, body in jobs.items():
                if re.search(r"^    uses: ", body, re.M):
                    continue
                self.assertIn("\n    steps:\n", "\n" + body, f"{name}:{job}")
                first_step = ("\n" + body).split("\n    steps:\n", 1)[1]
                self.assertTrue(
                    first_step.startswith(self.RUNNER_GUARD),
                    f"{name}:{job} must start with the GitHub-hosted runner guard",
                )

    def test_release_windows_checkouts_enable_long_paths(self):
        # Tracked paths pass Windows MAX_PATH, so a Windows checkout without
        # core.longpaths fails before the build starts.
        windows_jobs = {
            ("release.yml", "build"),
            ("build-java-distributions.yml", "build"),
            ("build-java-distributions.yml", "variant"),
            ("build-native-windows-x86_64.yml", "build-nd4j-native"),
            ("build-native-windows-x86_64.yml", "build-kompile-native"),
            ("build-native-windows-cuda.yml", "build-nd4j-cuda"),
        }
        checked = set()
        for name, (_, jobs) in self.release_workflows().items():
            for job, body in jobs.items():
                on_windows = re.search(r"^ +(runs-on|- runner): .*windows", body, re.M | re.I)
                if (name, job) not in windows_jobs and not on_windows:
                    continue
                checked.add((name, job))
                checkout = body.find("uses: actions/checkout@")
                longpaths = body.find("run: git config --global core.longpaths true")
                self.assertGreaterEqual(checkout, 0, f"{name}:{job}")
                self.assertTrue(
                    0 <= longpaths < checkout,
                    f"{name}:{job} must enable core.longpaths before its first checkout",
                )
        self.assertEqual(windows_jobs, checked)

    def shell_steps(self, name, expressions=None):
        """Yields (line, shell, script) for every `run:` step in a workflow.

        `name` is a workflow file name, or the path of another file with steps,
        such as a composite action. `shell` is None when the step names none.
        GitHub expressions become a plain word, since the runner substitutes
        them before the shell parses the script; `expressions` maps the text of
        an expression to the value to put there instead.
        """
        path = name if isinstance(name, pathlib.Path) else REPOSITORY / ".github" / "workflows" / name
        lines = path.read_text(encoding="utf-8").splitlines()
        for number, line in enumerate(lines):
            run = re.match(r"^( *)(- )?run:[ \t]*(.*)$", line)
            if not run or not run.group(3).strip():
                continue
            key = len(run.group(1)) + (2 if run.group(2) else 0)
            value = run.group(3)
            if not value.startswith(("'", '"')):
                value = re.sub(r"\s+#.*$", "", value)
            self.assertFalse(value.startswith(">"), f"{name}:{number + 1} folds its script")
            if re.fullmatch(r"\|[-+]?", value):
                body, indent = [], None
                for text in lines[number + 1:]:
                    if text.strip():
                        width = len(text) - len(text.lstrip(" "))
                        indent = width if indent is None else indent
                        if width < indent or width <= key:
                            break
                    body.append(text[indent:] if indent is not None else "")
                script = "\n".join(body) + "\n"
            elif value.startswith("'"):
                script = value[1:-1].replace("''", "'") + "\n"
            elif value.startswith('"'):
                script = json.loads(value) + "\n"
            else:
                script = value + "\n"
            step = number
            while step > 0 and not lines[step].startswith(" " * (key - 2) + "- "):
                step -= 1
            end = number + 1
            while end < len(lines) and (not lines[end].strip() or lines[end].startswith(" " * (key - 1))):
                end += 1
            shell = re.search(r"^ {%d}shell: *(\S+)" % key, "\n".join(lines[step:end]), re.M)
            script = re.sub(
                r"\$\{\{(.*?)\}\}",
                lambda expression: (expressions or {}).get(expression.group(1).strip(), "EXPR"),
                script,
                flags=re.S,
            )
            yield number + 1, shell.group(1) if shell else None, script

    def test_release_shell_steps_parse(self):
        # A syntax error only surfaces when its step runs, after every build step
        # before it. A step that names no shell runs under bash on Linux and
        # macOS; on a Windows runner the only such step is the runner guard,
        # which is also valid PowerShell. bash -n only warns about a heredoc that
        # never ends, so any output fails too.
        heredoc = re.compile(r"\bpython3?\b.*<<-?\s*(['\"])([A-Za-z_][A-Za-z0-9_]*)\1")
        scripts = heredocs = 0
        for name in self.release_workflows():
            for line, shell, script in self.shell_steps(name):
                if shell not in (None, "bash", "sh"):
                    continue
                scripts += 1
                parsed = subprocess.run(["bash", "-n"], input=script, text=True, capture_output=True)
                self.assertEqual(
                    (0, ""), (parsed.returncode, parsed.stderr.strip()), f"{name}:{line} does not parse",
                )
                lines = script.splitlines()
                for index, text in enumerate(lines):
                    python = heredoc.search(text)
                    if not python:
                        continue
                    heredocs += 1
                    delimiter = python.group(2)
                    self.assertIn(delimiter, lines[index + 1:], f"{name}:{line} never ends {delimiter}")
                    body = lines[index + 1:lines.index(delimiter, index + 1)]
                    try:
                        compile("\n".join(body) + "\n", f"{name}:{line}", "exec")
                    except SyntaxError as error:
                        self.fail(f"{name}:{line} {delimiter} heredoc: {error}")
        self.assertGreater(scripts, 100)
        self.assertGreater(heredocs, 0)

    PRUNE_MAVEN_CACHE = "uses: ./.github/actions/prune-maven-cache"

    def test_release_maven_caches_hold_no_snapshots_or_kompile_artifacts(self):
        # setup-java and setup-graalvm restore ~/.m2/repository on an exact key
        # match and save it after the job. build-dist.sh resolves with
        # --no-snapshot-updates, so a restored DL4J snapshot would ship in place
        # of the published one, and every run would add its reactor build to the
        # saved repository. Each job prunes right after the restore, and again as
        # its last step, which runs before the post step that saves the cache.
        workflows = self.release_workflows()
        cached = set()
        for name in ("release.yml", "build-java-distributions.yml"):
            for job, body in workflows[name][1].items():
                steps = re.split(r"^      - ", body, flags=re.M)[1:]
                restores = [
                    index for index, step in enumerate(steps)
                    if re.search(r"^ +cache: *['\"]?maven['\"]? *$", step, re.M)
                ]
                if not restores:
                    continue
                where = f"{name}:{job}"
                cached.add((name, job))
                self.assertEqual(1, len(restores), where)
                checkouts = [index for index, step in enumerate(steps) if "uses: actions/checkout@" in step]
                self.assertEqual(1, len(checkouts), where)
                self.assertIn("\n        id: checkout\n", steps[checkouts[0]], where)
                self.assertLess(checkouts[0], restores[0], where)
                self.assertIn(
                    self.PRUNE_MAVEN_CACHE, steps[restores[0] + 1], f"{where} must prune the restored cache first",
                )
                self.assertIn(self.PRUNE_MAVEN_CACHE, steps[-1], f"{where} must prune the cache last")
                self.assertIn("if: always() && steps.checkout.outcome == 'success'", steps[-1], where)
        self.assertEqual(
            {
                ("release.yml", "build"),
                ("release.yml", "full-dist-linux"),
                ("build-java-distributions.yml", "build"),
                ("build-java-distributions.yml", "variant"),
            },
            cached,
        )

    def test_maven_cache_prune_keeps_only_third_party_releases(self):
        action = REPOSITORY / ".github" / "actions" / "prune-maven-cache" / "action.yml"
        steps = list(self.shell_steps(action))
        self.assertEqual(1, len(steps))
        _, shell, script = steps[0]
        self.assertEqual("bash", shell)
        # The command line GitHub Actions runs a bash step with.
        bash = ["bash", "--noprofile", "--norc", "-eo", "pipefail"]
        kept = (
            "org/springframework/spring-core/6.1.6/spring-core-6.1.6.jar",
            "org/bytedeco/mkl/2025.3-1.5.13/mkl-2025.3-1.5.13-linux-x86_64-redist.jar",
            "org/eclipse/deeplearning4j/nd4j-native/1.0.0-M2.1/nd4j-native-1.0.0-M2.1.jar",
        )
        dropped = (
            "ai/kompile/kompile-app-main/0.0.0-dev-java4/kompile-app-main-0.0.0-dev-java4-exec.jar",
            "io/anserini/anserini/0.0.0-dev-java4/anserini-0.0.0-dev-java4.jar",
            "org/eclipse/deeplearning4j/nd4j-native/1.0.0-SNAPSHOT/"
            "nd4j-native-1.0.0-20260923.101010-709-linux-x86_64.jar",
            "com/example/tool/2.0-SNAPSHOT/tool-2.0-SNAPSHOT.jar",
        )
        with tempfile.TemporaryDirectory() as home:
            repository = pathlib.Path(home) / ".m2" / "repository"
            for relative in kept + dropped:
                (repository / relative).parent.mkdir(parents=True, exist_ok=True)
                (repository / relative).write_bytes(b"PK")
            pruned = subprocess.run(
                bash, input=script, text=True, capture_output=True, env={**os.environ, "HOME": home},
            )
            self.assertEqual(0, pruned.returncode, pruned.stderr)
            for relative in kept:
                self.assertTrue((repository / relative).is_file(), relative)
            for relative in dropped:
                self.assertFalse((repository / relative).parent.exists(), relative)
            self.assertFalse((repository / "ai" / "kompile").exists())
            self.assertFalse((repository / "io" / "anserini").exists())
        with tempfile.TemporaryDirectory() as home:
            missing = subprocess.run(
                bash, input=script, text=True, capture_output=True, env={**os.environ, "HOME": home},
            )
            self.assertEqual(0, missing.returncode, missing.stderr)

    def test_aot_capacity_gate_admits_the_measured_runners(self):
        # The optimized CLI build peaked at 9.82 GB on the 15.61 GB Linux x64
        # runner, at 13.06 GB on the 15.99 GB Windows x64 runner and at 3.32 GB
        # on the 7 GB macOS ARM64 runner. linux-arm64 stands for a platform that
        # has not been measured: it needs 30 GiB, unless a manual dispatch asks
        # for a constrained trial.
        def gate(platform, gib, event="push", constrained="false"):
            os_id, arch = platform.split("-", 1)
            expressions = {
                "matrix.os_id": os_id,
                "matrix.arch": arch,
                "github.event_name": event,
                "inputs.allow_constrained_aot": constrained,
            }
            steps = [
                (shell, script) for _, shell, script in self.shell_steps("release.yml", expressions)
                if "AOT runner memory:" in script
            ]
            self.assertEqual(1, len(steps))
            shell, script = steps[0]
            self.assertEqual("bash", shell)
            with tempfile.TemporaryDirectory() as directory:
                # The step reads the runner's memory through node.
                node = pathlib.Path(directory) / "node"
                node.write_text(f"#!/bin/sh\necho {gib}\n", encoding="utf-8")
                node.chmod(0o755)
                return subprocess.run(
                    ["bash", "--noprofile", "--norc", "-eo", "pipefail"],
                    input=script,
                    text=True,
                    capture_output=True,
                    env={**os.environ, "PATH": directory + os.pathsep + os.environ["PATH"]},
                ).returncode

        for platform, floor in (
            ("linux-x86_64", 15), ("windows-x86_64", 15), ("macosx-arm64", 7), ("linux-arm64", 30),
        ):
            self.assertEqual(0, gate(platform, floor), platform)
            self.assertEqual(1, gate(platform, floor - 1), platform)
        self.assertEqual(1, gate("linux-arm64", 15, "workflow_dispatch"))
        self.assertEqual(0, gate("linux-arm64", 15, "workflow_dispatch", "true"))
        self.assertEqual(1, gate("windows-x86_64", 7, "push", "true"))

    def test_canonical_release_adds_jvm_platforms_and_refuses_duplicate_assets(self):
        workflows = REPOSITORY / ".github" / "workflows"
        release = (workflows / "release.yml").read_text(encoding="utf-8")
        self.assertIn("JAVA_PLATFORMS=linux-arm64,windows-x86_64,macosx-arm64", release)
        self.assertIn(
            "needs: [plan, java-distributions, build, full-dist-linux, smoke-full-dist]",
            release,
        )
        self.assertIn("is produced by both", release)
        self.assertIn('sha256sum -c "${CHECKSUMS[@]}"', release)
        self.assertNotIn("merge-multiple: true", release)
        java = (workflows / "build-java-distributions.yml").read_text(encoding="utf-8")
        self.assertIn("server-id: ${{ env.DL4J_MAVEN_REPOSITORY_ID }}", java)
        self.assertIn("name: java-full-linux-x86_64-jars", java)
        self.assertIn("kompile-model-staging.jar", java)

    R2_ENDPOINT = "https://318204901782458555a243ad96f80e3f.r2.cloudflarestorage.com"

    def test_release_publishers_mirror_the_release_to_r2(self):
        workflows = REPOSITORY / ".github" / "workflows"
        mirror = (workflows / "mirror-release-to-r2.yml").read_text(encoding="utf-8")
        for expected in (
            "runs-on: ubuntu-24.04",
            "contents: read",
            f"R2_ENDPOINT: {self.R2_ENDPOINT}",
            "R2_BUCKET: dl4j-cache",
            'PREFIX="kompile/releases/${RELEASE_TAG#v}"',
            "unset AWS_PROFILE AWS_DEFAULT_PROFILE AWS_SESSION_TOKEN AWS_SECURITY_TOKEN",
            "AWS_REQUEST_CHECKSUM_CALCULATION=WHEN_REQUIRED",
            "AWS_RESPONSE_CHECKSUM_VALIDATION=WHEN_REQUIRED",
        ):
            self.assertIn(expected, mirror)
        for forbidden in (
            "contents: write", "gh release upload", "gh release create",
            "gh release edit", "--clobber", "s3 rm", "--delete", "GITHUB_ENV",
        ):
            self.assertNotIn(forbidden, mirror)
        callers = {
            "release.yml": (
                "tag: v${{ needs.plan.outputs.version }}",
                "needs.release.result == 'success'",
            ),
            "publish-release.yml": (
                "tag: ${{ needs.setup.outputs.release_tag }}",
                "needs.collect-and-publish.result == 'success'",
            ),
            "publish-external-aws-release.yml": (
                "tag: ${{ inputs.releaseTag }}",
                "needs: verify-and-attach",
            ),
        }
        for name, expected in callers.items():
            source = (workflows / name).read_text(encoding="utf-8")
            for text in (
                "uses: ./.github/workflows/mirror-release-to-r2.yml",
                "R2_ACCESS_KEY_ID: ${{ secrets.R2_ACCESS_KEY_ID }}",
                "R2_SECRET_ACCESS_KEY: ${{ secrets.R2_SECRET_ACCESS_KEY }}",
                *expected,
            ):
                self.assertIn(text, source, name)

    R2_MIRROR_GH_STUB = r"""#!/usr/bin/env bash
set -euo pipefail
if [ -n "${R2_ACCESS_KEY_ID:-}${R2_SECRET_ACCESS_KEY:-}${AWS_ACCESS_KEY_ID:-}" ]; then
  echo "gh received R2 credentials" >&2
  exit 97
fi
case "$1 $2" in
  "release view") cat "${FIXTURE}/release.json" ;;
  "api repos/GetKompile/kompile/commits/v1.2.3") echo 0123456789abcdef0123456789abcdef01234567 ;;
  "release download")
    while [ $# -gt 0 ]; do
      case "$1" in
        --pattern) name="$2"; shift 2 ;;
        --dir) dir="$2"; shift 2 ;;
        *) shift ;;
      esac
    done
    cp "${FIXTURE}/assets/${name}" "${dir}/${name}"
    if [ "${TRUNCATE:-}" = "${name}" ]; then truncate -s 1 "${dir}/${name}"; fi
    ;;
  *) echo "unexpected: gh $*" >&2; exit 98 ;;
esac
"""

    R2_MIRROR_AWS_STUB = r"""#!/usr/bin/env bash
set -euo pipefail
if [ "$1" = --version ]; then echo aws-cli/2-stub; exit 0; fi
[ "$1" = --endpoint-url ] || { echo "aws called without the R2 endpoint" >&2; exit 98; }
echo "$2" > "${FAKE_R2}.endpoint"
env | grep -E '^(AWS|R2|MSYS)_' | LC_ALL=C sort > "${FAKE_R2}.env"
shift 2
option() {
  local key="$1"
  shift
  while [ $# -gt 0 ]; do
    if [ "$1" = "${key}" ]; then echo "$2"; return; fi
    shift
  done
}
case "$1 $2" in
  "s3 cp")
    if [[ "$3" == s3://* ]]; then
      object="${FAKE_R2}/${3#s3://}"
      [ -f "${object}" ] || { echo "download failed: $3 does not exist" >&2; exit 1; }
      cp "${object}" "$4"
      exit 0
    fi
    key="${4#s3://}"
    mkdir -p "$(dirname "${FAKE_R2}/${key}")"
    cp "$3" "${FAKE_R2}/${key}"
    echo "${key##*/}" >> "${FAKE_R2}.uploads"
    if [ "${DROP:-}" = "${key##*/}" ]; then rm "${FAKE_R2}/${key}"; fi
    if [ "${SHORTEN:-}" = "${key##*/}" ]; then truncate -s 1 "${FAKE_R2}/${key}"; fi
    ;;
  "s3api list-objects-v2")
    bucket="$(option --bucket "$@")"
    prefix="$(option --prefix "$@")"
    delimiter="$(option --delimiter "$@")"
    root="${FAKE_R2}/${bucket}/"
    objects=()
    # With --delimiter / only the objects directly under the prefix are listed.
    if [ -n "${delimiter}" ]; then
      for object in "${root}${prefix}"*; do
        if [ -f "${object}" ]; then objects+=("${object}"); fi
      done
    elif [ -d "${root}${prefix}" ]; then
      mapfile -t objects < <(find "${root}${prefix}" -type f | LC_ALL=C sort)
    fi
    for object in ${objects[@]+"${objects[@]}"}; do
      printf '%s\t%s\n' "${object#"${root}"}" "$(stat -c %s "${object}")"
    done
    [ "${#objects[@]}" -gt 0 ] || echo None
    ;;
  "s3api head-object")
    object="${FAKE_R2}/$(option --bucket "$@")/$(option --key "$@")"
    [ -f "${object}" ] || { echo "Not Found" >&2; exit 254; }
    size="$(stat -c %s "${object}")"
    # The AWS CLI on Windows ends its output with CRLF.
    if [ -n "${CRLF:-}" ]; then printf '%s\r\n' "${size}"; else printf '%s\n' "${size}"; fi
    ;;
  *) echo "unexpected: aws $*" >&2; exit 98 ;;
esac
"""

    def _run_r2_mirror(self, root, assets, existing=None, **environment):
        """Run the mirror step's script against stub gh/aws and a directory bucket.

        `existing` maps bucket keys to the bytes already stored under them.
        """
        for tool in ("bash", "jq", "sha256sum", "truncate"):
            if shutil.which(tool) is None:
                self.skipTest(f"{tool} is required to run the R2 mirror script")
        if subprocess.run(["stat", "-c", "%s", __file__], capture_output=True).returncode:
            self.skipTest("GNU stat is required to run the R2 mirror script")
        lines = (
            REPOSITORY / ".github" / "workflows" / "mirror-release-to-r2.yml"
        ).read_text(encoding="utf-8").splitlines()
        step = lines.index("      - name: Mirror release assets and manifest")
        script = []
        for line in lines[lines.index("        run: |", step) + 1:]:
            if line.strip() and not line.startswith(" " * 10):
                break
            script.append(line[10:])
        stubs = root / "bin"
        stubs.mkdir()
        for name, body in (("gh", self.R2_MIRROR_GH_STUB), ("aws", self.R2_MIRROR_AWS_STUB)):
            (stubs / name).write_text(body, encoding="utf-8")
            (stubs / name).chmod(0o755)
        fixture = root / "release"
        (fixture / "assets").mkdir(parents=True)
        for name, data in assets.items():
            (fixture / "assets" / name).write_bytes(data)
        (fixture / "release.json").write_text(json.dumps({
            "url": "https://github.com/GetKompile/kompile/releases/tag/v1.2.3",
            "publishedAt": "2026-09-29T00:00:00Z",
            "isPrerelease": False,
            "assets": [{"name": name, "size": len(data)} for name, data in assets.items()],
        }), encoding="utf-8")
        (root / "runner").mkdir()
        (root / "r2").mkdir()
        for key, data in (existing or {}).items():
            (root / "r2" / "dl4j-cache" / key).parent.mkdir(parents=True, exist_ok=True)
            (root / "r2" / "dl4j-cache" / key).write_bytes(data)
        env = {
            "PATH": f"{stubs}{os.pathsep}{os.environ.get('PATH', '')}",
            "HOME": str(root),
            "RUNNER_TEMP": str(root / "runner"),
            "GITHUB_REPOSITORY": "GetKompile/kompile",
            "GITHUB_SERVER_URL": "https://github.com",
            "GITHUB_RUN_ID": "7",
            "GH_TOKEN": "test-github-token",
            "RELEASE_TAG": "v1.2.3",
            "R2_ENDPOINT": self.R2_ENDPOINT,
            "R2_BUCKET": "dl4j-cache",
            "R2_ACCESS_KEY_ID": " test-key-id ",
            "R2_SECRET_ACCESS_KEY": "test-secret\n",
            "AWS_SESSION_TOKEN": "ambient-session",
            "AWS_PROFILE": "ambient-profile",
            "FIXTURE": str(fixture),
            "FAKE_R2": str(root / "r2"),
        }
        env.update(environment)
        completed = subprocess.run(
            ["bash", "-c", "\n".join(script)],
            env=env, capture_output=True, text=True, timeout=120,
        )
        return completed, root / "r2" / "dl4j-cache" / "kompile" / "releases" / "1.2.3"

    def test_r2_mirror_verifies_every_asset_before_uploading_manifest(self):
        linux = b"linux archive " * 64
        windows = b"windows archive " * 48
        linux_zip = "kompile-dist-1.2.3-full-linux-x86_64.zip"
        windows_zip = "kompile-dist-1.2.3-full-windows-x86_64.zip"
        assets = {
            "kompile-cli.jar": b"cli jar " * 32,
            linux_zip: linux,
            # A sidecar may record a path, a binary-mode marker, CRLF and upper case.
            f"{linux_zip}.sha256":
                f"{hashlib.sha256(linux).hexdigest()}  dist/{linux_zip}\n".encode(),
            windows_zip: windows,
            f"{windows_zip}.sha256":
                f"{hashlib.sha256(windows).hexdigest().upper()} *{windows_zip}\r\n".encode(),
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            completed, prefix = self._run_r2_mirror(root, assets)
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual(
                sorted([*assets, "manifest.json"]),
                sorted(path.name for path in prefix.iterdir()),
            )
            for name, data in assets.items():
                self.assertEqual(data, (prefix / name).read_bytes(), name)
            uploads = (root / "r2.uploads").read_text(encoding="utf-8").split()
            self.assertEqual("manifest.json", uploads[-1])
            manifest = json.loads((prefix / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual("v1.2.3", manifest["tag"])
            self.assertEqual("1.2.3", manifest["version"])
            self.assertEqual("0123456789abcdef0123456789abcdef01234567", manifest["commit"])
            self.assertEqual("kompile/releases/1.2.3", manifest["prefix"])
            self.assertEqual(
                {
                    name: {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                    for name, data in assets.items()
                },
                {
                    entry["name"]: {"size": entry["size"], "sha256": entry["sha256"]}
                    for entry in manifest["assets"]
                },
            )
            self.assertEqual(
                self.R2_ENDPOINT,
                (root / "r2.endpoint").read_text(encoding="utf-8").strip(),
            )
            aws_environment = dict(
                line.split("=", 1)
                for line in (root / "r2.env").read_text(encoding="utf-8").splitlines()
            )
            self.assertEqual("test-key-id", aws_environment["AWS_ACCESS_KEY_ID"])
            self.assertEqual("test-secret", aws_environment["AWS_SECRET_ACCESS_KEY"])
            self.assertEqual("auto", aws_environment["AWS_DEFAULT_REGION"])
            self.assertEqual("WHEN_REQUIRED", aws_environment["AWS_REQUEST_CHECKSUM_CALCULATION"])
            self.assertEqual("WHEN_REQUIRED", aws_environment["AWS_RESPONSE_CHECKSUM_VALIDATION"])
            for leaked in (
                "AWS_SESSION_TOKEN", "AWS_PROFILE", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY",
            ):
                self.assertNotIn(leaked, aws_environment)
            self.assertEqual([], list((root / "runner" / "r2-mirror" / "assets").iterdir()))

        tampered = dict(assets)
        tampered[f"{linux_zip}.sha256"] = f"{'0' * 64}  {linux_zip}\n".encode()
        for label, fixture, environment, message in (
            ("sidecar mismatch", tampered, {}, f"{linux_zip} does not match {linux_zip}.sha256"),
            ("truncated download", assets, {"TRUNCATE": "kompile-cli.jar"},
             "kompile-cli.jar downloaded as 1 bytes"),
            ("object lost after upload", assets, {"DROP": "kompile-cli.jar"},
             "kompile-cli.jar is missing"),
            ("missing credentials", assets, {"R2_SECRET_ACCESS_KEY": ""},
             "must be configured as repository secrets"),
        ):
            with self.subTest(label), tempfile.TemporaryDirectory() as temporary:
                completed, prefix = self._run_r2_mirror(
                    pathlib.Path(temporary), fixture, **environment
                )
                self.assertNotEqual(0, completed.returncode)
                self.assertIn(message, completed.stderr)
                self.assertFalse((prefix / "manifest.json").exists())

    def test_r2_mirror_leaves_the_java_distributions_alone(self):
        # build-java-distributions.yml keeps each classifier's archive under
        # java/<distribution classifier>/ in the same release prefix. The mirror
        # neither reports those as stray objects nor replaces java/manifest.json.
        java = "kompile/releases/1.2.3/java"
        existing = {
            f"{java}/full-linux-x86_64-avx2/kompile-dist-1.2.3-full-linux-x86_64-avx2.zip": b"java archive\n",
            f"{java}/full-linux-x86_64-avx2/variant.json": b"{}\n",
            f"{java}/manifest.json": b"{}\n",
            "kompile/releases/1.2.3/stray.txt": b"stray\n",
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            completed, prefix = self._run_r2_mirror(
                root, {"kompile-cli.jar": b"cli jar " * 32}, existing=existing
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual(
                [
                    "::warning::dl4j-cache/kompile/releases/1.2.3/stray.txt "
                    "is not an asset of v1.2.3; left in place."
                ],
                [line for line in completed.stdout.splitlines() if line.startswith("::warning::")],
            )
            for key, data in existing.items():
                self.assertEqual(data, (root / "r2" / "dl4j-cache" / key).read_bytes(), key)
            manifest = json.loads((prefix / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(["kompile-cli.jar"], [entry["name"] for entry in manifest["assets"]])

    def _bash_step(self, workflow, marker):
        """The script of the one `run:` step in workflow whose script contains marker."""
        steps = [(shell, script) for _, shell, script in self.shell_steps(workflow) if marker in script]
        self.assertEqual(1, len(steps), f"{workflow} must have one step containing {marker!r}")
        shell, script = steps[0]
        self.assertEqual("bash", shell, f"{workflow}: the step containing {marker!r}")
        return script

    def _run_r2_step(self, root, script, cwd=None, **environment):
        """Run a bash step's script as GitHub does, against stub aws and a directory bucket."""
        for tool in ("bash", "find", "truncate"):
            if shutil.which(tool) is None:
                self.skipTest(f"{tool} is required to run the R2 step")
        if subprocess.run(["stat", "-c", "%s", __file__], capture_output=True).returncode:
            self.skipTest("GNU stat is required to run the R2 step")
        stubs = root / "bin"
        stubs.mkdir(exist_ok=True)
        (stubs / "aws").write_text(self.R2_MIRROR_AWS_STUB, encoding="utf-8")
        (stubs / "aws").chmod(0o755)
        (root / "runner").mkdir(exist_ok=True)
        (root / "r2").mkdir(exist_ok=True)
        step = root / "step.sh"
        step.write_text(script, encoding="utf-8")
        env = {
            "PATH": f"{stubs}{os.pathsep}{os.environ.get('PATH', '')}",
            "HOME": str(root),
            "RUNNER_TEMP": str(root / "runner"),
            "GITHUB_REPOSITORY": "GetKompile/kompile",
            "GITHUB_SERVER_URL": "https://github.com",
            "GITHUB_RUN_ID": "7",
            "R2_ENDPOINT": self.R2_ENDPOINT,
            "R2_BUCKET": "dl4j-cache",
            "R2_ACCESS_KEY_ID": " test-key-id ",
            "R2_SECRET_ACCESS_KEY": "test-secret\n",
            "AWS_SESSION_TOKEN": "ambient-session",
            "AWS_PROFILE": "ambient-profile",
            "FAKE_R2": str(root / "r2"),
        }
        env.update(environment)
        # GitHub runs a `shell: bash` step as bash --noprofile --norc -eo pipefail {0}.
        return subprocess.run(
            ["bash", "--noprofile", "--norc", "-eo", "pipefail", str(step)],
            cwd=cwd or root, env=env, capture_output=True, text=True, timeout=120,
        )

    def test_java_variants_upload_each_distribution_then_the_manifest(self):
        source = (
            REPOSITORY / ".github" / "workflows" / "build-java-distributions.yml"
        ).read_text(encoding="utf-8")
        _, jobs = self.release_workflows()["build-java-distributions.yml"]
        # A variants request replaces the distribution and platforms request.
        self.assertIn("    if: needs.validate.outputs.variants == ''\n", jobs["build"])
        for text in (
            "      variants: ${{ steps.plan.outputs.variants }}\n",
            "      variant_matrix: ${{ steps.plan.outputs.variant_matrix }}\n",
            "python3 release/github/java_matrix.py plan \\\n",
            '--github-output "${GITHUB_OUTPUT}"\n',
        ):
            self.assertIn(text, jobs["validate"])
        variant = jobs["variant"]
        for text in (
            "    needs: validate\n",
            "    if: needs.validate.outputs.variants != ''\n",
            "    runs-on: ${{ matrix.runner }}\n",
            "      fail-fast: false\n",
            "      matrix: ${{ fromJSON(needs.validate.outputs.variant_matrix) }}\n",
        ):
            self.assertIn(text, variant)
        # R2 is where each classifier's archive goes; a workflow artifact per
        # classifier would also hold every CUDA archive in GitHub storage.
        self.assertNotIn("actions/upload-artifact", variant)
        steps = [
            "Build Java distribution", "Smoke Java archive", "Record the distribution",
            "Ensure the AWS CLI", "Upload the distribution to R2",
        ]
        positions = [variant.find(f"      - name: {step}\n") for step in steps]
        self.assertNotIn(-1, positions, steps)
        self.assertEqual(sorted(positions), positions)
        self.assertEqual(2, variant.count("          ROW: ${{ toJSON(matrix) }}\n"))
        self.assertEqual(2, variant.count("        if: inputs.upload_r2\n"))
        collect = jobs["collect"]
        for text in (
            "    needs: [validate, variant]\n",
            "    if: ${{ !cancelled() && needs.validate.result == 'success' "
            "&& needs.validate.outputs.variants != '' && inputs.upload_r2 }}\n",
            "      group: kompile-java-manifest-${{ needs.validate.outputs.version }}\n",
            "      cancel-in-progress: false\n",
            "          PLAN: ${{ needs.validate.outputs.variant_matrix }}\n",
            "          COMMIT: ${{ github.sha }}\n",
            "python3 release/github/java_matrix.py manifest \\\n",
        ):
            self.assertIn(text, collect)
        for text in (
            f"          R2_ENDPOINT: {self.R2_ENDPOINT}\n",
            "          R2_BUCKET: dl4j-cache\n",
            "          R2_ACCESS_KEY_ID: ${{ secrets.R2_ACCESS_KEY_ID }}\n",
            "          R2_SECRET_ACCESS_KEY: ${{ secrets.R2_SECRET_ACCESS_KEY }}\n",
        ):
            self.assertEqual(1, variant.count(text), text)
            self.assertEqual(1, collect.count(text), text)
        for secret in ("R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY"):
            self.assertIn(f"      {secret}:\n        required: false\n", source)
        for forbidden in ("GITHUB_ENV", "s3 rm", "--delete"):
            self.assertNotIn(forbidden, source)

    def test_java_variant_upload_writes_its_record_last(self):
        script = self._bash_step(
            "build-java-distributions.yml",
            'PREFIX="kompile/releases/${VERSION}/java/${DISTRIBUTION_CLASSIFIER}"',
        )
        _, rows = JAVA_MATRIX.buildable_rows()
        row = next(row for row in rows if row["classifier"] == "windows-x86_64-onednn")
        classifier = row["distributionClassifier"]
        name = JAVA_MATRIX.archive_name("1.2.3", row)
        files = [name, f"{name}.sha256", "variant.json"]
        prefix = f"kompile/releases/1.2.3/java/{classifier}"

        def run(root, **environment):
            dist = root / "dist"
            record_java_distribution(dist, row)
            completed = self._run_r2_step(
                root, script,
                VERSION="1.2.3",
                DISTRIBUTION_CLASSIFIER=classifier,
                ARCHIVE=str(dist / name),
                CHECKSUM=str(dist / f"{name}.sha256"),
                RECORD=str(dist / "variant.json"),
                **environment,
            )
            uploads = root / "r2.uploads"
            return completed, dist, (
                uploads.read_text(encoding="utf-8").split() if uploads.exists() else []
            )

        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            # The AWS CLI on Windows ends what it prints with CRLF.
            completed, dist, uploads = run(root, CRLF="1")
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual(files, uploads)
            for file in files:
                self.assertEqual(
                    (dist / file).read_bytes(),
                    (root / "r2" / "dl4j-cache" / prefix / file).read_bytes(),
                    file,
                )
            self.assertEqual(
                self.R2_ENDPOINT,
                (root / "r2.endpoint").read_text(encoding="utf-8").strip(),
            )
            aws_environment = dict(
                line.split("=", 1)
                for line in (root / "r2.env").read_text(encoding="utf-8").splitlines()
            )
            self.assertEqual("test-key-id", aws_environment["AWS_ACCESS_KEY_ID"])
            self.assertEqual("test-secret", aws_environment["AWS_SECRET_ACCESS_KEY"])
            self.assertEqual("auto", aws_environment["AWS_DEFAULT_REGION"])
            self.assertEqual("1", aws_environment["MSYS_NO_PATHCONV"])
            for leaked in (
                "AWS_SESSION_TOKEN", "AWS_PROFILE", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY",
            ):
                self.assertNotIn(leaked, aws_environment)
            self.assertIn(f"Uploaded {classifier} to dl4j-cache/{prefix}/", completed.stdout)

        for label, environment, message, uploaded in (
            ("archive lost after upload", {"DROP": name}, "Not Found", [name]),
            ("checksum short in R2", {"SHORTEN": f"{name}.sha256"},
             f"ERROR: dl4j-cache/{prefix}/{name}.sha256 is 1 bytes, expected",
             [name, f"{name}.sha256"]),
            ("missing credentials", {"R2_ACCESS_KEY_ID": ""},
             "must be configured as repository secrets", []),
        ):
            with self.subTest(label), tempfile.TemporaryDirectory() as temporary:
                root = pathlib.Path(temporary)
                completed, _, uploads = run(root, **environment)
                self.assertNotEqual(0, completed.returncode)
                self.assertIn(message, completed.stderr)
                self.assertEqual(uploaded, uploads)
                self.assertFalse((root / "r2" / "dl4j-cache" / prefix / "variant.json").exists())

    def test_java_manifest_lists_the_recorded_distributions(self):
        script = self._bash_step("build-java-distributions.yml", "java_matrix.py manifest")
        _, rows = JAVA_MATRIX.buildable_rows()
        planned = [
            row for row in rows if row["classifier"] in ("linux-x86_64-avx2", "macosx-arm64-mps")
        ]
        classifiers = [row["distributionClassifier"] for row in planned]
        prefix = "kompile/releases/1.2.3/java"

        def run(root, recorded, **environment):
            bucket = root / "r2" / "dl4j-cache" / prefix
            for row in recorded:
                record_java_distribution(bucket / row["distributionClassifier"], row)
            # An earlier run's manifest is replaced, and a record nested below a
            # distribution's directory is no distribution.
            bucket.mkdir(parents=True, exist_ok=True)
            (bucket / "manifest.json").write_text("{}\n", encoding="utf-8")
            nested = bucket / classifiers[0] / "extra" / "variant.json"
            nested.parent.mkdir(parents=True)
            nested.write_text("{}\n", encoding="utf-8")
            completed = self._run_r2_step(
                root, script, cwd=REPOSITORY,
                VERSION="1.2.3",
                PLAN=json.dumps({"include": planned}),
                COMMIT=JAVA_COMMIT,
                **environment,
            )
            uploads = root / "r2.uploads"
            return completed, (
                uploads.read_text(encoding="utf-8").split() if uploads.exists() else []
            ), json.loads((bucket / "manifest.json").read_text(encoding="utf-8"))

        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            completed, uploads, manifest = run(root, planned)
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertEqual(["manifest.json"], uploads)
            self.assertIn(f"Wrote dl4j-cache/{prefix}/manifest.json", completed.stdout)
            self.assertEqual(prefix, manifest["prefix"])
            self.assertEqual(JAVA_COMMIT, manifest["commit"])
            self.assertEqual(
                "https://github.com/GetKompile/kompile/actions/runs/7", manifest["generated_by"]
            )
            self.assertEqual({"id": "7", "planned": classifiers, "missing": []}, manifest["run"])
            self.assertEqual(
                [
                    f"{prefix}/{row['distributionClassifier']}/{JAVA_MATRIX.archive_name('1.2.3', row)}"
                    for row in planned
                ],
                [entry["archive"]["key"] for entry in manifest["variants"]],
            )
            self.assertFalse(manifest["complete"])
            self.assertEqual([], manifest["stale"])
            self.assertEqual(len(rows) - len(planned), len(manifest["missing"]))
            self.assertFalse(
                (root / "runner" / "java-manifest" / "records" / classifiers[0] / "extra").exists()
            )

        # A failed row leaves no record; the manifest still says what landed.
        with tempfile.TemporaryDirectory() as temporary:
            completed, uploads, manifest = run(pathlib.Path(temporary), planned[:1])
            self.assertEqual(3, completed.returncode, completed.stderr)
            self.assertIn(
                f"ERROR: this run planned {classifiers[1]} but recorded no build of them.",
                completed.stderr,
            )
            self.assertEqual(["manifest.json"], uploads)
            self.assertEqual(
                {"id": "7", "planned": classifiers, "missing": classifiers[1:]}, manifest["run"]
            )
            self.assertEqual(
                classifiers[:1],
                [entry["build"]["distributionClassifier"] for entry in manifest["variants"]],
            )

        with tempfile.TemporaryDirectory() as temporary:
            completed, uploads, manifest = run(
                pathlib.Path(temporary), planned, R2_SECRET_ACCESS_KEY=""
            )
            self.assertEqual(1, completed.returncode)
            self.assertIn("must be configured as repository secrets", completed.stderr)
            self.assertEqual([], uploads)
            self.assertEqual({}, manifest)

    def test_release_refuses_assets_github_cannot_publish(self):
        script = self._bash_step("release.yml", "GitHub release assets must be under 2 GiB")
        for tool in ("bash", "find", "sort", "sha256sum"):
            if shutil.which(tool) is None:
                self.skipTest(f"{tool} is required to run the release asset check")
        if subprocess.run(["stat", "-c", "%s", __file__], capture_output=True).returncode:
            self.skipTest("GNU stat is required to run the release asset check")

        def run(root, artifact, name, data=b"", size=None):
            directory = root / "artifacts" / artifact
            directory.mkdir(parents=True)
            archive = directory / name
            if size is None:
                archive.write_bytes(data)
                digest = hashlib.sha256(data).hexdigest()
            else:
                # Sparse, so the test never writes the bytes.
                with archive.open("wb") as stream:
                    stream.truncate(size)
                digest = "0" * 64
            (directory / f"{name}.sha256").write_text(f"{digest}  {name}\n", encoding="utf-8")
            (root / "step.sh").write_text(script, encoding="utf-8")
            return subprocess.run(
                ["bash", "--noprofile", "--norc", "-eo", "pipefail", str(root / "step.sh")],
                cwd=root, capture_output=True, text=True, timeout=120,
            )

        with tempfile.TemporaryDirectory() as temporary:
            name = "kompile-dist-1.2.3-cli-linux-x86_64.zip"
            completed = run(pathlib.Path(temporary), "java-cli", name, data=b"cli archive\n")
            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertIn(f"{name}: OK", completed.stdout)

        limit = 2 * 1024 ** 3
        with tempfile.TemporaryDirectory() as temporary:
            name = "kompile-dist-1.2.3-full-linux-x86_64.zip"
            completed = run(pathlib.Path(temporary), "java-full", name, size=limit)
            self.assertEqual(1, completed.returncode)
            self.assertIn(
                f"ERROR: {name} is {limit} bytes; GitHub release assets must be under 2 GiB.",
                completed.stderr,
            )
            # Refused before sha256sum reads the archive.
            self.assertNotIn(name, completed.stdout)

    def test_release_dispatch_builds_java_variants_into_r2_only(self):
        # build-java-distributions.yml is not on the default branch, so GitHub
        # dispatches it only through release.yml.
        _, jobs = self.release_workflows()["release.yml"]
        self.assertIn("java_variants: ${{ steps.plan.outputs.java_variants }}", jobs["plan"])
        for text in (
            "needs: plan",
            "if: needs.plan.outputs.java_variants != ''",
            "uses: ./.github/workflows/build-java-distributions.yml",
            "version: ${{ needs.plan.outputs.version }}",
            "variants: ${{ needs.plan.outputs.java_variants }}",
            "upload_r2: true",
            "DL4J_MAVEN_USERNAME: ${{ secrets.DL4J_MAVEN_USERNAME }}",
            "DL4J_MAVEN_PASSWORD: ${{ secrets.DL4J_MAVEN_PASSWORD }}",
            "R2_ACCESS_KEY_ID: ${{ secrets.R2_ACCESS_KEY_ID }}",
            "R2_SECRET_ACCESS_KEY: ${{ secrets.R2_SECRET_ACCESS_KEY }}",
        ):
            self.assertIn(text, jobs["java-variants"])
        # The platform archives never take the classifier list, and the release
        # job neither waits for nor publishes the classifier builds.
        self.assertNotIn("variants:", jobs["java-distributions"])
        self.assertNotIn("java-variants", re.search(r"^    needs: .*$", jobs["release"], re.M).group(0))

        script = self._bash_step("release.yml", 'echo "java_variants=${JAVA_VARIANTS}"')
        if shutil.which("bash") is None:
            self.skipTest("bash is required to run the release plan")

        def plan(event="workflow_dispatch", **inputs):
            environment = {
                "PATH": os.environ.get("PATH", ""),
                "EVENT_NAME": event,
                "REF_NAME": "v1.2.3" if event == "push" else "feat/java",
                "INPUT_VERSION": "1.2.3",
                "INPUT_EXECUTION": "java",
                "INPUT_DISTRIBUTION": "cli",
                "INPUT_PLATFORMS": "linux-x86_64,linux-arm64,windows-x86_64,macosx-arm64",
                "INPUT_JAVA_VARIANTS": "",
                "INPUT_PUBLISH": "false",
            }
            environment.update(inputs)
            with tempfile.TemporaryDirectory() as temporary:
                root = pathlib.Path(temporary)
                (root / "step.sh").write_text(script, encoding="utf-8")
                output = root / "github_output"
                output.touch()
                environment["GITHUB_OUTPUT"] = str(output)
                completed = subprocess.run(
                    ["bash", "--noprofile", "--norc", "-eo", "pipefail", str(root / "step.sh")],
                    cwd=root, env=environment, capture_output=True, text=True, timeout=60,
                )
                lines = output.read_text(encoding="utf-8").splitlines()
            return completed, dict(line.split("=", 1) for line in lines), len(lines)

        # A tag push keeps its JVM platform archives and builds no classifiers.
        completed, outputs, count = plan("push", INPUT_EXECUTION="", INPUT_DISTRIBUTION="",
                                         INPUT_PLATFORMS="", INPUT_PUBLISH="")
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual(
            {"version": "1.2.3", "java_distribution": "full",
             "java_platforms": "linux-arm64,windows-x86_64,macosx-arm64", "java_variants": ""},
            outputs,
        )

        completed, outputs, count = plan(INPUT_PLATFORMS=" linux-x86_64, windows-x86_64 ")
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual(
            {"version": "1.2.3", "java_distribution": "cli",
             "java_platforms": "linux-x86_64,windows-x86_64", "java_variants": ""},
            outputs,
        )

        # The classifiers replace the platform archives, whatever execution
        # asks of them; whitespace, a newline included, never reaches an output.
        for execution in ("java", "native", "both"):
            with self.subTest(execution=execution):
                completed, outputs, count = plan(
                    INPUT_EXECUTION=execution,
                    INPUT_JAVA_VARIANTS=" linux-x86_64-avx2,\nmacosx-arm64-mps ",
                )
                self.assertEqual(0, completed.returncode, completed.stderr)
                self.assertEqual(4, count)
                self.assertEqual(
                    {"version": "1.2.3", "java_distribution": "", "java_platforms": "",
                     "java_variants": "linux-x86_64-avx2,macosx-arm64-mps"},
                    outputs,
                )
                self.assertIn(
                    "Release 1.2.3: JVM distributions for linux-x86_64-avx2,macosx-arm64-mps to R2",
                    completed.stdout,
                )

        for inputs, message in (
            ({"INPUT_JAVA_VARIANTS": "all", "INPUT_PUBLISH": "true"},
             "ERROR: java_variants builds distributions into R2 only; dispatch it with publish=false."),
            ({"INPUT_JAVA_VARIANTS": "linux-x86_64-avx2;id"},
             "ERROR: invalid java_variants: linux-x86_64-avx2;id"),
        ):
            with self.subTest(**inputs):
                completed, outputs, count = plan(**inputs)
                self.assertEqual(2, completed.returncode)
                self.assertIn(message, completed.stderr)
                self.assertEqual(0, count)

    def test_build_workflows_have_read_only_contents_permissions(self):
        for name in (
            "build-native-linux-x86_64.yml",
            "build-native-linux-arm64.yml",
            "build-native-mac-arm64.yml",
            "build-native-windows-x86_64.yml",
            "build-native-linux-cuda.yml",
            "build-native-windows-cuda.yml",
        ):
            source = (
                REPOSITORY / ".github" / "workflows" / name
            ).read_text(encoding="utf-8")
            self.assertNotIn("contents: write", source, name)
            self.assertIn("contents: read", source, name)

    def test_collector_keeps_purgeable_build_logs_out_of_release_assets(self):
        source = (ROOT / "release.py").read_text(encoding="utf-8")
        self.assertIn(
            'if name in {"maven-repository.tar.gz", "sdk-assets.tar.gz", "shard-manifest.json"}:',
            source,
        )
        self.assertNotIn(
            '{"maven-repository.tar.gz", "sdk-assets.tar.gz", "shard-manifest.json", "build.log"}',
            source,
        )

    def test_build_dist_passes_array_safe_backend_and_repository_to_every_maven_call(self):
        source = (REPOSITORY / "build-dist.sh").read_text(encoding="utf-8")
        direct = [
            line for line in source.splitlines()
            if '"${MVN}"' in line and "BUILD_CMD=" not in line
        ]
        self.assertTrue(direct)
        self.assertTrue(all('"${MAVEN_BUILD_ARGS[@]}"' in line for line in direct))
        self.assertIn(
            'BUILD_CMD=("${MVN}" clean install -DskipTests "${MAVEN_BUILD_ARGS[@]}")',
            source,
        )
        self.assertIn('"--no-snapshot-updates"', source)
        self.assertIn('"-Dmaven.repo.local=${MAVEN_REPOSITORY}"', source)
        self.assertIn('"-Ddl4j.repository.url=${DL4J_MAVEN_REPOSITORY_URL}"', source)
        self.assertIn('"-Dkompile.backend=${KOMPILE_BACKEND_PROFILE}"', source)
        self.assertIn('"-Dkompile.windows.pe-safe=true"', source)
        self.assertIn('native-windows-pe-safe', (REPOSITORY / "pom.xml").read_text(encoding="utf-8"))
        self.assertIn('<buildArg>-O1</buildArg>', (REPOSITORY / "pom.xml").read_text(encoding="utf-8"))
        self.assertNotIn('eval "${BUILD_CMD}"', source)

    def test_product_distribution_reactor_excludes_unrelated_root_siblings(self):
        source = (REPOSITORY / "build-dist.sh").read_text(encoding="utf-8")
        branch = re.search(
            r'full\|hosted\|cpu-intel\|cpu-arm\|cuda\|amd-zluda\)(.*?)\n\s*;;',
            source,
            re.DOTALL,
        )
        self.assertIsNotNone(branch)
        match = re.search(r'JAVA_BUILD_MODULES="([^"]+)"', branch.group(1))
        self.assertIsNotNone(match)
        modules = set(match.group(1).split(","))
        self.assertEqual({
            ":kompile-cli-main",
            ":kompile-agent-cli",
            ":kompile-app-cli",
            ":kompile-model-cli",
            ":kompile-component-cli",
            ":kompile-app-main",
            ":kompile-app-chat",
            ":kompile-app-crawl-manager",
            ":kompile-model-staging",
            ":kompile-app-subprocess-serving",
            ":kompile-pipeline-serving",
            ":kompile-compute-graph-scripting",
            ":kompile-app-lite",
            ":kompile-sdk-serving",
        }, modules)
        self.assertTrue({
            ":kompile-chat-local",
            ":kompile-chat-local-mobile",
            ":kompile-e2e-tests",
        }.isdisjoint(modules))
        self.assertIn("JAVA_BUILD_ALSO_MAKE=true", branch.group(1))
        self.assertIn('BUILD_CMD+=(-pl "${JAVA_BUILD_MODULES}")', source)
        self.assertIn('if [ -z "${JAVA_BUILD_MODULES}" ]; then', source)

    def test_distribution_modes_build_every_required_delegated_cli_form(self):
        source = (REPOSITORY / "build-dist.sh").read_text(encoding="utf-8")
        step_one = source.split(
            "# ── Step 1: Java build", 1
        )[1].split("# ── Step 1b:", 1)[0]
        local_branch = re.search(r'\n\s*local\)(.*?)\n\s*;;', step_one, re.DOTALL)
        self.assertIsNotNone(local_branch)
        self.assertIn('if [ "${JARS_ONLY}" = true ]; then', local_branch.group(1))
        for module in (
            ":kompile-agent-cli",
            ":kompile-app-cli",
            ":kompile-component-cli",
        ):
            self.assertIn(module, local_branch.group(1))
        for native_spec in (
            '"kompile-cli/kompile-agent-cli:kompile-agent"',
            '"kompile-cli/kompile-app-cli:kompile-app-cli"',
            '"kompile-cli/kompile-component-cli:kompile-component"',
        ):
            self.assertIn(native_spec, source)
        # kompile-agent is CLI contract, not a product extra: it is always
        # added once CLI_NATIVE is true, with the app/component CLIs nested
        # behind INCLUDE_PRODUCT_EXTRAS inside that same build-gate block.
        self.assertIn(
            'if [ "${CLI_NATIVE}" = true ]; then\n'
            '        DELEGATED_CLIS=("kompile-cli/kompile-agent-cli:kompile-agent")\n'
            '        if [ "${INCLUDE_PRODUCT_EXTRAS}" = true ]; then\n'
            '            DELEGATED_CLIS+=(\n'
            '                "kompile-cli/kompile-app-cli:kompile-app-cli"\n'
            '                "kompile-cli/kompile-component-cli:kompile-component"\n'
            '            )\n'
            '        fi',
            source,
        )
        # Mirrored on the require side: app/component CLI native binaries are
        # required only inside the product-extras block (line-scoped above by
        # "Optional artifacts"), nested behind their own CLI_NATIVE check.
        self.assertIn(
            'if [ "${CLI_NATIVE}" = true ]; then\n'
            '    require_native_component "app CLI" "kompile-app-cli${EXE_SUFFIX}"\n'
            '    require_native_component "component CLI" "kompile-component${EXE_SUFFIX}"\n'
            'fi\n'
            'fi',
            source,
        )
        for binary in ("kompile-agent", "kompile-app-cli", "kompile-component"):
            self.assertIn(f"target/{binary}${{EXE_SUFFIX}}", source)
            requirement = (
                f'require_native_component "{binary.split("-")[1] if binary != "kompile-app-cli" else "app"} CLI" '
                f'"{binary}${{EXE_SUFFIX}}"'
            )
            self.assertIn(requirement, source)
            self.assertGreater(
                source.index(requirement),
                source.index('cp "${extra}" "${DIST_DIR}/bin/${BNAME}"'),
            )

    def test_persona_verifier_covers_every_server_distribution_variant(self):
        source = (
            REPOSITORY / "kompile-dist" / "src" / "main" / "build" /
            "verify-persona-artifacts.py"
        ).read_text(encoding="utf-8")
        variants = re.search(r"PERSONA_VARIANTS = \{(.*?)\}", source, re.DOTALL)
        self.assertIsNotNone(variants)
        self.assertEqual({
            "full",
            "hosted",
            "cpu-intel",
            "cpu-arm",
            "cuda",
            "amd-zluda",
        }, set(re.findall(r'"([^"]+)"', variants.group(1))))
        self.assertIn("if variant in PERSONA_VARIANTS:", source)

    def test_windows_pe_safe_flag_reaches_native_orchestration_path(self):
        source = (REPOSITORY / "build-scripts" / "build-common.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn('windows-*) extra_mvn_args+=("-Dkompile.windows.pe-safe=true")', source)
        self.assertIn('kompile_build_all_native "${extra_mvn_args[@]}"', source)

    def test_windows_pe_safe_build_arg_is_appended_to_native_modules(self):
        native_poms = (
            "kompile-cli/kompile-cli-main/pom.xml",
            "kompile-cli/kompile-component-cli/pom.xml",
            "kompile-app/kompile-app-parent/kompile-app-main/pom.xml",
            "kompile-app/kompile-app-parent/kompile-app-chat/pom.xml",
            "kompile-app/kompile-app-parent/kompile-app-crawl-manager/pom.xml",
            "kompile-app/kompile-app-parent/kompile-app-lite/pom.xml",
            "kompile-app/kompile-app-parent/kompile-app-subprocess/kompile-app-subprocess-serving/pom.xml",
            "kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving/pom.xml",
            "kompile-app/kompile-models/kompile-model-staging/pom.xml",
        )
        for relative_path in native_poms:
            source = (REPOSITORY / relative_path).read_text(encoding="utf-8")
            self.assertIn(
                '<buildArgs combine.children="append">',
                source,
                relative_path,
            )
        root_pom = (REPOSITORY / "pom.xml").read_text(encoding="utf-8")
        self.assertIn("native-windows-pe-safe", root_pom)
        self.assertIn("<buildArg>-O1</buildArg>", root_pom)

    def test_windows_native_heap_caps_fit_64_gib_azure_host(self):
        native_poms = (
            "kompile-app/kompile-app-parent/kompile-app-main/pom.xml",
            "kompile-app/kompile-app-parent/kompile-app-chat/pom.xml",
            "kompile-app/kompile-app-parent/kompile-app-crawl-manager/pom.xml",
        )
        for relative_path in native_poms:
            source = (REPOSITORY / relative_path).read_text(encoding="utf-8")
            self.assertNotIn("-J-Xmx80g", source, relative_path)
            self.assertIn("-J-Xmx32g", source, relative_path)

    def test_dl4j_backend_dry_run_preserves_repository_paths_with_spaces(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            dl4j = root / "dl4j"
            dl4j.mkdir()
            (dl4j / "pom.xml").write_text(
                "<project><version>1.0.0-SNAPSHOT</version></project>\n",
                encoding="utf-8",
            )
            subprocess.run(
                ["git", "-C", str(dl4j), "init", "-q"], check=True
            )
            subprocess.run(
                ["git", "-C", str(dl4j), "add", "pom.xml"], check=True
            )
            subprocess.run(
                [
                    "git", "-C", str(dl4j),
                    "-c", "user.name=Kompile Release Test",
                    "-c", "user.email=release-test@example.invalid",
                    "commit", "-q", "-m", "fixture",
                ],
                check=True,
            )
            local_repository = root / "maven repo"
            libnd4j = root / "lib nd4j"
            script = REPOSITORY / "build-scripts" / "build-dl4j-backend.sh"
            completed = subprocess.run([
                "bash", str(script), "--dl4j-dir", str(dl4j), "--chip", "cuda",
                "--cuda-version", "12.9", "--helper", "cudnn",
                "--maven-repo-local", str(local_repository),
                "--libnd4j-home", str(libnd4j), "--dry-run",
            ], text=True, capture_output=True, check=True)
            escaped_repository = str(local_repository).replace(" ", "\\ ")
            escaped_libnd4j = str(libnd4j).replace(" ", "\\ ")
            self.assertIn(
                f"-Dmaven.repo.local={escaped_repository}",
                completed.stdout,
            )
            self.assertIn(
                f"-DLIBND4J_HOME={escaped_libnd4j}",
                completed.stdout,
            )
            self.assertNotIn("eval", script.read_text(encoding="utf-8"))

    def test_status_is_uploaded_last_and_controller_enforces_zero_overlap(self):
        linux_worker = (ROOT / "worker.sh").read_text(encoding="utf-8")
        windows_worker = (ROOT / "worker.ps1").read_text(encoding="utf-8")
        self.assertGreater(linux_worker.index('status.json" status.json'),
                           linux_worker.index('shard-manifest.json" shard-manifest.json'))
        self.assertGreater(windows_worker.index("'status.json') 'status.json'"),
                           windows_worker.index("'shard-manifest.json') 'shard-manifest.json'"))
        controller = (ROOT / "release.py").read_text(encoding="utf-8")
        self.assertIn("except BaseException:", controller)
        self.assertIn("serial schedule never overlaps quota usage", controller)
        self.assertIn('ec2.get_waiter("instance_terminated").wait', controller)


class JavaMatrixTest(unittest.TestCase):
    """release/github/java_matrix.py against the real build-common.sh and root pom.xml."""

    # Rows whose build arguments differ from the plain CPU shape, as planned.
    EXPECTED_ROWS = [
        {
            "classifier": "linux-arm64-armcompute", "platform": "linux-arm64",
            "runner": "ubuntu-24.04-arm", "variant": "full",
            "backendProfile": "cpu-armcompute", "cudaVersion": "",
            "sdkClassifier": "linux-arm64-armcompute",
            "distributionClassifier": "full-linux-arm64-armcompute",
            "nd4jBackend": "nd4j-native", "nativeBackend": "nd4j-native",
            "nativeClassifier": "linux-arm64-armcompute",
        },
        {
            "classifier": "macosx-arm64-mps", "platform": "macosx-arm64",
            "runner": "macos-14", "variant": "full",
            "backendProfile": "cpu-mps", "cudaVersion": "",
            "sdkClassifier": "macosx-arm64-mps",
            "distributionClassifier": "full-macosx-arm64-mps",
            "nd4jBackend": "nd4j-native", "nativeBackend": "nd4j-native",
            "nativeClassifier": "macosx-arm64-mps",
        },
        {
            "classifier": "linux-x86_64-cuda-12.9-cudnn", "platform": "linux-x86_64",
            "runner": "ubuntu-22.04", "variant": "full",
            "backendProfile": "cuda-12.9-cudnn", "cudaVersion": "12.9",
            "sdkClassifier": "linux-x86_64-cudnn",
            "distributionClassifier": "full-linux-x86_64-cuda-12.9-cudnn",
            "nd4jBackend": "nd4j-cuda-12.9", "nativeBackend": "nd4j-cuda-12.9",
            "nativeClassifier": "linux-x86_64-cudnn",
        },
        {
            "classifier": "windows-x86_64-cuda-12.9-zluda-rocm-7.2.4", "platform": "windows-x86_64",
            "runner": "windows-2022", "variant": "full",
            "backendProfile": "zluda-rocm-7.2.4", "cudaVersion": "12.9",
            "sdkClassifier": "windows-x86_64-zluda-rocm-7.2.4",
            "distributionClassifier": "full-windows-x86_64-cuda-12.9-zluda-rocm-7.2.4",
            "nd4jBackend": "nd4j-zluda-12.9", "nativeBackend": "nd4j-zluda-12.9",
            "nativeClassifier": "windows-x86_64-zluda-rocm-7.2.4",
        },
        {
            "classifier": "linux-x86_64-cuda-12.9-zluda-rocm-10.0.0", "platform": "linux-x86_64",
            "runner": "ubuntu-22.04", "variant": "amd-zluda",
            "backendProfile": "zluda-rocm-10.0.0", "cudaVersion": "12.9",
            "sdkClassifier": "linux-x86_64-zluda-rocm-10.0.0",
            "distributionClassifier": "amd-zluda-linux-x86_64-cuda-12.9-zluda-rocm-10.0.0",
            "nd4jBackend": "nd4j-zluda-12.9", "nativeBackend": "nd4j-zluda-12.9",
            "nativeClassifier": "linux-x86_64-zluda-rocm-10.0.0",
        },
        {
            "classifier": "linux-x86_64-vulkan-compile", "platform": "linux-x86_64",
            "runner": "ubuntu-22.04", "variant": "full",
            "backendProfile": "vulkan-compile", "cudaVersion": "",
            "sdkClassifier": "linux-x86_64-vulkan-compile",
            "distributionClassifier": "full-linux-x86_64-vulkan-compile",
            "nd4jBackend": "nd4j-vulkan", "nativeBackend": "nd4j-vulkan",
            "nativeClassifier": "linux-x86_64-compile",
        },
    ]
    PREFIX = "kompile/releases/1.2.3/java"

    @classmethod
    def setUpClass(cls):
        cls.matrix, cls.rows = JAVA_MATRIX.buildable_rows()
        cls.platforms, _ = JAVA_MATRIX.resolve_classifiers([])

    def planner(self):
        """Serves the rows planned once for the class instead of resolving them again."""
        return patch.object(JAVA_MATRIX, "buildable_rows", return_value=(self.matrix, self.rows))

    def run_main(self, *argv, environment=None):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.dict(os.environ, environment or {}), \
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            status = JAVA_MATRIX.main(list(argv))
        return status, stdout.getvalue(), stderr.getvalue()

    def row(self, classifier):
        return next(row for row in self.rows if row["classifier"] == classifier)

    def run_manifest(self, bucket, planned, prefix=PREFIX):
        """Run `manifest` over bucket, a directory laid out like the java/ prefix in R2."""
        lines = [
            f"{prefix}/{path.relative_to(bucket).as_posix()}\t{path.stat().st_size}"
            for path in sorted(bucket.rglob("*")) if path.is_file()
        ]
        listing = bucket.parent / "listing.tsv"
        # list-objects-v2 prints None for an empty prefix.
        listing.write_text("\n".join(lines or ["None"]) + "\n", encoding="utf-8")
        output = bucket.parent / "manifest.json"
        with self.planner():
            status, stdout, stderr = self.run_main(
                "manifest", "--plan-env", "PLAN", "--listing", str(listing),
                "--records", str(bucket), "--version", "1.2.3", "--commit", JAVA_COMMIT,
                "--repository", "GetKompile/kompile", "--run-id", "7",
                "--run-url", "https://github.com/GetKompile/kompile/actions/runs/7",
                "--bucket", "dl4j-cache", "--prefix", prefix, "--output", str(output),
                environment={"PLAN": json.dumps({"include": planned})},
            )
        manifest = json.loads(output.read_text(encoding="utf-8")) if output.exists() else None
        return status, stdout, stderr, manifest

    def test_matrix_lists_build_common_platforms_and_blocks_by_rule(self):
        listed = [entry["classifier"] for entry in self.matrix["classifiers"]]
        self.assertEqual(self.platforms, listed)
        for entry in self.matrix["classifiers"]:
            classifier = entry["classifier"]
            if classifier.startswith("android-"):
                expected = "android"
            elif re.search(r"-cuda-(12\.6|13\.1)(-|$)", classifier):
                expected = "dl4j-cuda-line"
            elif classifier.endswith("-zluda"):
                expected = "zluda-without-rocm"
            else:
                expected = None
            self.assertEqual(expected, entry.get("blocked"), classifier)
        self.assertEqual(
            [entry["classifier"] for entry in self.matrix["classifiers"] if "variant" in entry],
            [row["classifier"] for row in self.rows],
        )
        names = [row["distributionClassifier"] for row in self.rows]
        self.assertEqual(len(names), len(set(names)))

    def test_rows_carry_the_build_arguments_each_classifier_resolves_to(self):
        by_classifier = {row["classifier"]: row for row in self.rows}
        for expected in self.EXPECTED_ROWS:
            self.assertEqual(expected, by_classifier[expected["classifier"]])
        for row in self.rows:
            self.assertEqual(JAVA_MATRIX.ROW_FIELDS, tuple(row))
            self.assertEqual(f"{row['variant']}-{row['classifier']}", row["distributionClassifier"])
            self.assertEqual(self.matrix["runners"][row["platform"]], row["runner"])
            self.assertEqual(
                "12.9" if "-cuda-12.9" in row["classifier"] else "", row["cudaVersion"], row["classifier"]
            )

    def test_only_rocm_10_builds_the_amd_zluda_variant(self):
        variants = {row["classifier"]: row["variant"] for row in self.rows}
        self.assertEqual(
            {"linux-x86_64-cuda-12.9-zluda-rocm-10.0.0"},
            {classifier for classifier, variant in variants.items() if variant == "amd-zluda"},
        )
        # The farm release plans build the same classifiers into the same
        # variants. Their other variant entries are DL4J lanes and named
        # distributions, which name no classifier.
        for plan in ("aws", "azure"):
            data = json.loads(
                (REPOSITORY / "release" / plan / "release-plan.json").read_text(encoding="utf-8")
            )
            planned = {
                item["classifier"]: item.get("kompileVariant", "full")
                for shard in data["shards"]
                for item in (shard.get("build") or {}).get("variants", [])
                if "classifier" in item
            }
            self.assertEqual(
                "amd-zluda", planned.get("linux-x86_64-cuda-12.9-zluda-rocm-10.0.0"), plan
            )
            for classifier, variant in planned.items():
                if classifier in variants:
                    self.assertEqual(variants[classifier], variant, f"{plan}: {classifier}")

    def test_runners_match_the_platform_request_runners(self):
        source = (
            REPOSITORY / ".github" / "workflows" / "build-java-distributions.yml"
        ).read_text(encoding="utf-8")
        block = re.search(r"const runners = \{(.*?)\};", source, re.S)
        self.assertIsNotNone(block)
        self.assertEqual(
            self.matrix["runners"], dict(re.findall(r"'([^']+)': '([^']+)'", block.group(1)))
        )

    def test_select_rows_refuses_what_it_cannot_build(self):
        def select(request):
            return JAVA_MATRIX.select_rows(self.matrix, self.rows, request)

        self.assertEqual(self.rows, select(" all "))
        # Matrix order, whatever order the request names them in.
        self.assertEqual(
            ["linux-x86_64-avx2", "macosx-arm64-mps"],
            [row["classifier"] for row in select("macosx-arm64-mps, linux-x86_64-avx2")],
        )
        for request, message in (
            ("linux-x86_64-avx3",
             "linux-x86_64-avx3 is not a DL4J release classifier; see release/github/java-matrix.json"),
            ("android-arm64",
             "android-arm64 is not built as a JVM distribution: Android classifiers ship as APK/AAR"),
            ("linux-x86_64-cuda-12.6",
             "linux-x86_64-cuda-12.6 is not built as a JVM distribution: The DL4J release shards"),
            ("linux-x86_64,linux-x86_64", "linux-x86_64 is requested twice"),
            (" , ", "no classifiers were requested"),
            ("all,linux-x86_64", "'all' cannot be combined with named classifiers"),
        ):
            with self.subTest(request=request):
                with self.assertRaises(JAVA_MATRIX.MatrixError) as raised:
                    select(request)
                self.assertIn(message, str(raised.exception))

    def test_plan_writes_one_line_github_outputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = pathlib.Path(temporary) / "github_output"
            with self.planner():
                status, stdout, stderr = self.run_main(
                    "plan", "--classifiers", "macosx-arm64-mps,linux-x86_64-avx2",
                    "--github-output", str(output),
                )
            self.assertEqual(0, status, stderr)
            lines = output.read_text(encoding="utf-8").splitlines()
        self.assertEqual(2, len(lines))
        self.assertEqual("variants=linux-x86_64-avx2,macosx-arm64-mps", lines[0])
        key, _, value = lines[1].partition("=")
        self.assertEqual("variant_matrix", key)
        self.assertEqual(
            {"include": [self.row("linux-x86_64-avx2"), self.row("macosx-arm64-mps")]},
            json.loads(value),
        )
        self.assertIn(
            "full-macosx-arm64-mps: macos-14, backend profile cpu-mps, nd4j-native:macosx-arm64-mps",
            stdout,
        )
        with self.planner():
            status, _, stderr = self.run_main("plan", "--classifiers", "android-arm64")
        self.assertEqual(2, status)
        self.assertIn("ERROR: android-arm64 is not built as a JVM distribution", stderr)

    def test_record_describes_the_archive_it_was_given(self):
        row = self.row("windows-x86_64-onednn")
        name = "kompile-dist-1.2.3-full-windows-x86_64-onednn.zip"
        data = b"archive bytes"
        digest = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as temporary:
            path = record_java_distribution(pathlib.Path(temporary), row, data=data)
            text = path.read_bytes()
        record = json.loads(text)
        self.assertEqual(1, record["schema"])
        self.assertEqual("GetKompile/kompile", record["repository"])
        self.assertEqual("1.2.3", record["version"])
        self.assertEqual(JAVA_COMMIT, record["commit"])
        self.assertEqual(("7", "1"), (record["run_id"], record["run_attempt"]))
        self.assertEqual(row, record["build"])
        self.assertEqual({"name": name, "size": len(data), "sha256": digest}, record["archive"])
        self.assertRegex(record["built_at"], r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
        # Written as bytes, so a Windows runner writes LF too.
        self.assertNotIn(b"\r", text)

        def record(directory, sidecar, commit=JAVA_COMMIT, run_id="7", fields=row):
            (directory / name).write_bytes(data)
            (directory / f"{name}.sha256").write_bytes(sidecar.encode("utf-8"))
            output = directory / "variant.json"
            status, _, stderr = self.run_main(
                "record", "--row-env", "ROW", "--version", "1.2.3",
                "--archive", str(directory / name), "--checksum", str(directory / f"{name}.sha256"),
                "--commit", commit, "--repository", "GetKompile/kompile",
                "--run-id", run_id, "--run-attempt", "1", "--output", str(output),
                environment={"ROW": json.dumps(fields)},
            )
            return status, stderr, output.exists()

        # A sidecar may name the archive by a path, with a binary-mode marker, CRLF and upper case.
        with tempfile.TemporaryDirectory() as temporary:
            status, stderr, written = record(
                pathlib.Path(temporary), f"{digest.upper()} *dist\\{name}\r\n"
            )
            self.assertEqual((0, True), (status, written), stderr)
        for label, arguments, message in (
            ("wrong digest", {"sidecar": f"{'0' * 64}  {name}\n"}, "does not describe"),
            ("sidecar of another archive",
             {"sidecar": f"{digest}  kompile-dist-1.2.3-full-linux-x86_64.zip\n"}, "does not describe"),
            ("short commit", {"commit": "0123456"}, "invalid commit '0123456'"),
            ("run id", {"run_id": "7a"}, "the run id and run attempt must be numbers"),
            ("extra field", {"fields": {**row, "extra": "value"}}, "must hold exactly the string fields"),
        ):
            with self.subTest(label), tempfile.TemporaryDirectory() as temporary:
                arguments = {"sidecar": f"{digest}  {name}\n", **arguments}
                status, stderr, written = record(pathlib.Path(temporary), **arguments)
                self.assertEqual((2, False), (status, written))
                self.assertIn(message, stderr)

    def test_manifest_is_complete_when_every_distribution_is_recorded(self):
        with tempfile.TemporaryDirectory() as temporary:
            bucket = pathlib.Path(temporary) / "java"
            for row in self.rows:
                record_java_distribution(bucket / row["distributionClassifier"], row)
            status, stdout, stderr, manifest = self.run_manifest(bucket, self.rows)
        self.assertEqual(0, status, stderr)
        self.assertTrue(manifest["complete"])
        self.assertEqual([], manifest["missing"])
        self.assertEqual([], manifest["stale"])
        self.assertEqual(
            [row["distributionClassifier"] for row in self.rows],
            [entry["build"]["distributionClassifier"] for entry in manifest["variants"]],
        )
        self.assertEqual(
            [entry["classifier"] for entry in self.matrix["classifiers"] if "blocked" in entry],
            [entry["classifier"] for entry in manifest["blocked"]],
        )
        first, row = manifest["variants"][0], self.rows[0]
        directory = f"{self.PREFIX}/{row['distributionClassifier']}"
        name = JAVA_MATRIX.archive_name("1.2.3", row)
        self.assertEqual(f"{directory}/{name}", first["archive"]["key"])
        self.assertEqual(f"{directory}/{name}.sha256", first["checksum_key"])
        self.assertEqual(f"{directory}/variant.json", first["record_key"])
        self.assertIn(
            f"{len(self.rows)} of {len(self.rows)} distributions are recorded for {JAVA_COMMIT}.", stdout
        )

    def test_manifest_leaves_out_records_that_are_not_current(self):
        avx2, mps, onednn, cudnn, vulkan, armcompute = (self.row(classifier) for classifier in (
            "linux-x86_64-avx2", "macosx-arm64-mps", "windows-x86_64-onednn",
            "linux-x86_64-cuda-12.9-cudnn", "linux-x86_64-vulkan-compile", "linux-arm64-armcompute",
        ))
        with tempfile.TemporaryDirectory() as temporary:
            bucket = pathlib.Path(temporary) / "java"

            def directory(row):
                return bucket / row["distributionClassifier"]

            record_java_distribution(directory(avx2), avx2)
            record_java_distribution(directory(mps), mps, commit="f" * 40)
            # Planned by this run, which never replaced an earlier run's record.
            record_java_distribution(directory(onednn), onednn, run_id="6")
            record_java_distribution(directory(cudnn), cudnn)
            cudnn_archive = directory(cudnn) / JAVA_MATRIX.archive_name("1.2.3", cudnn)
            size = cudnn_archive.stat().st_size
            cudnn_archive.write_bytes(b"x")
            record_java_distribution(directory(vulkan), vulkan)
            (directory(vulkan) / f"{JAVA_MATRIX.archive_name('1.2.3', vulkan)}.sha256").unlink()
            record_java_distribution(directory(armcompute), {**armcompute, "runner": "ubuntu-22.04-arm"})
            record_java_distribution(
                bucket / "full-linux-x86_64-avx3", {**avx2, "distributionClassifier": "full-linux-x86_64-avx3"}
            )
            status, stdout, stderr, manifest = self.run_manifest(bucket, [avx2, onednn])
        self.assertEqual(JAVA_MATRIX.INCOMPLETE, status)
        self.assertIn(
            f"ERROR: this run planned {onednn['distributionClassifier']} but recorded no build of them.",
            stderr,
        )
        self.assertEqual(
            [avx2["distributionClassifier"]],
            [entry["build"]["distributionClassifier"] for entry in manifest["variants"]],
        )
        self.assertEqual(
            {
                "id": "7",
                "planned": [avx2["distributionClassifier"], onednn["distributionClassifier"]],
                "missing": [onednn["distributionClassifier"]],
            },
            manifest["run"],
        )
        self.assertEqual(
            [row["distributionClassifier"] for row in self.rows if row is not avx2], manifest["missing"]
        )

        def key(name):
            return f"{self.PREFIX}/{name}/variant.json"

        self.assertEqual(
            {
                key("full-linux-x86_64-avx3"): "not a distribution this matrix builds",
                key(mps["distributionClassifier"]): f"built from {'f' * 40}, not {JAVA_COMMIT}",
                key(onednn["distributionClassifier"]):
                    "recorded by run 6; this run did not finish rebuilding it",
                key(cudnn["distributionClassifier"]):
                    f"{JAVA_MATRIX.archive_name('1.2.3', cudnn)} is 1, expected {size} bytes",
                key(vulkan["distributionClassifier"]):
                    f"{JAVA_MATRIX.archive_name('1.2.3', vulkan)}.sha256 is missing",
                key(armcompute["distributionClassifier"]): "its build fields no longer match the matrix",
            },
            {entry["key"]: entry["reason"] for entry in manifest["stale"]},
        )
        for entry in manifest["stale"]:
            self.assertIn(f"Not listed: {entry['key']}: {entry['reason']}", stdout)

    def test_manifest_of_an_empty_prefix_lists_nothing(self):
        with tempfile.TemporaryDirectory() as temporary:
            bucket = pathlib.Path(temporary) / "java"
            bucket.mkdir()
            status, _, stderr, manifest = self.run_manifest(bucket, [self.row("linux-x86_64-avx2")])
        self.assertEqual(JAVA_MATRIX.INCOMPLETE, status)
        self.assertEqual([], manifest["variants"])
        self.assertEqual([], manifest["stale"])
        self.assertEqual(len(self.rows), len(manifest["missing"]))
        self.assertIn("ERROR: this run planned full-linux-x86_64-avx2 but recorded no build", stderr)

    def test_manifest_refuses_a_plan_or_prefix_it_does_not_match(self):
        avx2 = self.row("linux-x86_64-avx2")
        with tempfile.TemporaryDirectory() as temporary:
            bucket = pathlib.Path(temporary) / "java"
            record_java_distribution(bucket / avx2["distributionClassifier"], avx2)
            for label, planned, prefix, message in (
                ("changed row", [{**avx2, "runner": "ubuntu-24.04"}], self.PREFIX,
                 "this run's full-linux-x86_64-avx2 row no longer matches the matrix"),
                ("another version", [avx2], "kompile/releases/1.2.4/java",
                 "prefix kompile/releases/1.2.4/java is not kompile/releases/1.2.3/java"),
                ("empty plan", [], self.PREFIX, "must hold this run's"),
            ):
                with self.subTest(label):
                    status, _, stderr, manifest = self.run_manifest(bucket, planned, prefix=prefix)
                    self.assertEqual(2, status)
                    self.assertIn(message, stderr)
                    self.assertIsNone(manifest)


class EnvironmentWizardTest(unittest.TestCase):
    def test_noninteractive_ci_never_prompts(self):
        with patch.dict(os.environ, {"CI": "true"}, clear=True):
            self.assertFalse(MODULE.interactive_wizard_enabled(True))

    def test_direct_prompts_name_exact_environment_variables(self):
        candidate = Mock()
        candidate.get_credentials.return_value = object()
        candidate.client.return_value.get_caller_identity.return_value = {"Account": "123"}
        prompts = []

        def answer(label, **kwargs):
            prompts.append(label)
            return ["AKIATEST", "secret", ""][len(prompts) - 1]

        with patch.object(MODULE, "prompt_value", side_effect=answer), \
             patch.object(MODULE, "create_aws_session", return_value=(candidate, None)), \
             patch.dict(os.environ, {}, clear=True):
            result = MODULE.configure_aws_credentials(Mock(), "us-east-1", "missing")
            self.assertIs(candidate, result)
            self.assertEqual("us-east-1", os.environ["AWS_REGION"])
            self.assertNotIn("AWS_ACCESS_KEY_ID", os.environ)
        self.assertEqual([
            "AWS user access key ID (AWS_ACCESS_KEY_ID)",
            "AWS user secret access key (AWS_SECRET_ACCESS_KEY)",
            "AWS temporary session token (AWS_SESSION_TOKEN; leave blank for a long-lived key)",
        ], prompts)

    def test_rejected_entered_credentials_exit_instead_of_looping(self):
        with patch.object(MODULE, "prompt_value", side_effect=["AKIATEST", "secret", ""]) as prompt, \
             patch.object(MODULE, "create_aws_session", return_value=(None, "credential validation returned InvalidClientTokenId")):
            with self.assertRaises(SystemExit) as error:
                MODULE.configure_aws_credentials(Mock(), "us-east-1", "bad profile")
        self.assertEqual(3, prompt.call_count)
        self.assertIn("AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY", str(error.exception))


class SchedulingTest(unittest.TestCase):
    class Ec2:
        def describe_instance_types(self, **kwargs):
            return {"InstanceTypes": [
                {"InstanceType": "c7i.large", "VCpuInfo": {"DefaultVCpus": 2},
                 "MemoryInfo": {"SizeInMiB": 4096}, "ProcessorInfo": {"SupportedArchitectures": ["x86_64"]}},
                {"InstanceType": "c7i.4xlarge", "VCpuInfo": {"DefaultVCpus": 16},
                 "MemoryInfo": {"SizeInMiB": 32768}, "ProcessorInfo": {"SupportedArchitectures": ["x86_64"]}},
                {"InstanceType": "c7i.8xlarge", "VCpuInfo": {"DefaultVCpus": 32},
                 "MemoryInfo": {"SizeInMiB": 65536}, "ProcessorInfo": {"SupportedArchitectures": ["x86_64"]}},
            ]}

        def describe_instance_type_offerings(self, **kwargs):
            values = kwargs["Filters"][0]["Values"]
            return {"InstanceTypeOfferings": [{"InstanceType": value} for value in values]}

    def test_core_constraint_selects_largest_feasible_size(self):
        plan = MODULE.load_plan(ROOT / "release-plan.json")
        lanes = MODULE.selected_executions(plan, ["native-linux-x86_64--base"])
        MODULE.apply_plan_defaults(plan, lanes)
        schedule = MODULE.apply_core_constraint(self.Ec2(), lanes, 16)
        self.assertEqual("c7i.4xlarge", lanes[0]["instanceType"])
        self.assertEqual(16, lanes[0]["build"]["buildThreads"])
        self.assertEqual(16, schedule[0]["selectedVcpus"])


class LogDeletionTest(unittest.TestCase):
    class Paginator:
        def paginate(self, **kwargs):
            return [{
                "Versions": [
                    {"Key": "kompile/releases/run/a/build.log", "VersionId": "1"},
                    {"Key": "kompile/releases/run/a/status.json", "VersionId": "2"},
                    {"Key": "kompile/releases/run/b/build.log", "VersionId": "3"},
                ],
                "DeleteMarkers": [
                    {"Key": "kompile/releases/run/a/build.log", "VersionId": "4"},
                ],
            }]

    class S3:
        def __init__(self):
            self.deleted = []

        def get_paginator(self, name):
            return LogDeletionTest.Paginator()

        def delete_objects(self, **kwargs):
            self.deleted.extend(kwargs["Delete"]["Objects"])

    def test_s3_purge_removes_versions_and_markers_for_selected_log(self):
        s3 = self.S3()
        with patch.object(MODULE, "_boto3", return_value=(None, Exception)):
            deleted = MODULE.delete_s3_log_objects(
                s3, "bucket", "kompile/releases/run/", {"a"},
            )
        self.assertEqual({"1", "4"}, {item["VersionId"] for item in deleted})
        self.assertEqual(deleted, s3.deleted)


if __name__ == "__main__":
    unittest.main()
