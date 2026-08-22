import importlib.util
import hashlib
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
        for shard in self.plan["shards"]:
            if shard["build"]["kind"] == "distribution":
                self.assertIn("maven", shard["workloads"], shard["id"])

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
        cuda = [item for item in self.plan["shards"] if item["build"]["backend"] == "cuda"]
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

        def create_sdk(*args):
            sdk = pathlib.Path(args[4])
            (sdk / "jars").mkdir(parents=True, exist_ok=True)
            (sdk / "runtime.zip").write_bytes(b"PK\x03\x04runtime")
            (sdk / "jars" / "nd4j-platform.jar").write_bytes(b"jar")

        with patch.object(BUILD_MODULE, "ensure_graalvm", return_value={}) as graalvm, \
             patch.object(BUILD_MODULE, "run_dl4j_release_lane", side_effect=create_sdk) as dl4j, \
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
            ("linux-x86_64-zluda", ["zluda"]),
        ], delegated)
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
                "nd4j-cuda-12.9-1.0.0-SNAPSHOT-windows-x86_64-zluda.jar",
                "nd4j-cuda-12.9-preset-1.0.0-SNAPSHOT.jar",
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
        ):
            self.assertIn(f'"{lane}"', source)
        self.assertIn('*cuda*-cudnn) echo "${base}-cudnn"', source)
        self.assertIn('*cuda*-compile) echo "${base}-compile"', source)
        self.assertIn('*cuda*-zluda) echo "${base}-zluda"', source)
        self.assertIn('"-Djavacpp.platform=${javacpp_platform}"', source)
        self.assertIn('--distribution-classifier "${distribution_classifier}"', source)

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
            for module, binary in expected.items():
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
        self.assertIn("AOT release builds require a runner with at least 32 GiB RAM", source)
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
        self.assertIn('"-Dmaven.repo.local=${MAVEN_REPOSITORY}"', source)
        self.assertIn('"-Ddl4j.repository.url=${DL4J_MAVEN_REPOSITORY_URL}"', source)
        self.assertIn('"-Dkompile.backend=${KOMPILE_BACKEND_PROFILE}"', source)
        self.assertIn('"-Dkompile.windows.pe-safe=true"', source)
        self.assertIn('native-windows-pe-safe', (REPOSITORY / "pom.xml").read_text(encoding="utf-8"))
        self.assertIn('<buildArg>-O1</buildArg>', (REPOSITORY / "pom.xml").read_text(encoding="utf-8"))
        self.assertNotIn('eval "${BUILD_CMD}"', source)

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
