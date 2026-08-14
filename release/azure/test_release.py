import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parent
REPOSITORY = ROOT.parents[1]

SPEC = importlib.util.spec_from_file_location("kompile_azure_release", ROOT / "release.py")
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)

BUILD_SPEC = importlib.util.spec_from_file_location(
    "kompile_release_build_platform", REPOSITORY / "release/aws/build-platform.py"
)
BUILD_MODULE = importlib.util.module_from_spec(BUILD_SPEC)
assert BUILD_SPEC.loader
BUILD_SPEC.loader.exec_module(BUILD_MODULE)


class AzurePlanTest(unittest.TestCase):
    def setUp(self):
        self.plan = MODULE.load_plan(ROOT / "release-plan.json")
        self.classifiers = MODULE.release_classifiers(self.plan)

    def test_publication_targets_the_canonical_dl4j_maven_tree(self):
        self.assertEqual("releases", self.plan["repositoryContainer"])
        self.assertEqual(
            "deeplearning4j/releases/maven-repository",
            self.plan["mavenRepositoryPrefix"],
        )
        self.assertNotEqual(
            self.plan["artifactContainer"], self.plan["repositoryContainer"]
        )

    def test_plan_matches_dl4j_azure_classifier_matrix(self):
        expected = {
            "linux-x86_64",
            "linux-x86_64-avx2",
            "linux-x86_64-avx512",
            "linux-x86_64-onednn",
            "linux-x86_64-onednn-avx2",
            "linux-x86_64-onednn-avx512",
            "linux-x86_64-compile",
            "linux-x86_64-compile-avx2",
            "linux-x86_64-compile-avx512",
            "linux-x86_64-compat",
            "linux-arm64",
            "linux-arm64-armcompute",
            "linux-arm64-onednn",
            "linux-arm64-compile",
            "android-arm64",
            "android-arm64-armcompute",
            "android-arm64-nnapi",
            "android-arm64-compile",
            "android-arm64-compile-nnapi",
            "android-arm64-vulkan",
            "android-x86_64",
            "android-x86_64-onednn",
            "android-x86_64-compile",
            "windows-x86_64",
            "windows-x86_64-avx2",
            "windows-x86_64-avx512",
            "windows-x86_64-onednn",
            "windows-x86_64-onednn-avx2",
            "windows-x86_64-onednn-avx512",
            "windows-x86_64-compile",
            "windows-x86_64-vulkan",
            "linux-x86_64-cuda-12.6",
            "linux-x86_64-cuda-12.6-cudnn",
            "linux-x86_64-cuda-12.6-compile",
            "linux-x86_64-cuda-12.9",
            "linux-x86_64-cuda-12.9-cudnn",
            "linux-x86_64-cuda-12.9-compile",
            "windows-x86_64-cuda-12.6",
            "windows-x86_64-cuda-12.6-cudnn",
            "windows-x86_64-cuda-12.6-compile",
            "windows-x86_64-cuda-12.9",
            "windows-x86_64-cuda-12.9-cudnn",
            "windows-x86_64-cuda-12.9-compile",
            "linux-x86_64-cuda-12.9-zluda",
            "windows-x86_64-cuda-12.9-zluda",
            "linux-x86_64-vulkan",
            "linux-x86_64-vulkan-compile",
            "linux-x86_64-hexagon",
            "linux-x86_64-tpu",
        }
        self.assertEqual(expected, self.classifiers)

    def test_every_azure_classifier_is_accepted_by_build_common(self):
        source = (REPOSITORY / "build-scripts/build-common.sh").read_text(
            encoding="utf-8"
        )
        for classifier in self.classifiers:
            self.assertIn(f'"{classifier}"', source, classifier)

    def test_representative_classifiers_resolve_to_maven_profiles(self):
        expected = {
            "linux-x86_64-compile-avx2": "cpu||cpu-compile-avx2",
            "linux-x86_64-compile-avx512": "cpu||cpu-compile-avx512",
            "linux-x86_64-vulkan-compile": "vulkan||vulkan-compile",
            "android-arm64-vulkan": "vulkan||vulkan",
            "windows-x86_64-cuda-12.6-compile": (
                "cuda|12.6|cuda-12.6-compile"
            ),
            "linux-x86_64-cuda-12.9-zluda": "cuda|12.9|zluda",
        }
        common = REPOSITORY / "build-scripts/build-common.sh"
        pom = (REPOSITORY / "pom.xml").read_text(encoding="utf-8")
        for classifier, mapping in expected.items():
            completed = subprocess.run(
                [
                    "bash", "-c",
                    'source "$1"; _resolve_backend_from_platform "$2"',
                    "bash", str(common), classifier,
                ],
                check=True,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            self.assertEqual(mapping, completed.stdout.strip(), classifier)
            profile = mapping.rsplit("|", 1)[-1]
            self.assertIn(f"<id>backend-{profile}</id>", pom, classifier)
            self.assertIn(f"<value>{profile}</value>", pom, classifier)

    def test_azure_uses_memory_sized_cpu_compile_hosts(self):
        defaults = self.plan["defaults"]
        self.assertEqual(64, defaults["minMemoryGiB"])
        self.assertLessEqual(defaults["maxCores"], 16)
        self.assertGreaterEqual(defaults["maxTotalCores"], 4 * 16)
        self.assertGreaterEqual(defaults["rootVolumeGiB"], 512)
        for candidate in (
            defaults["x86MachineCandidates"] + defaults["armMachineCandidates"]
        ):
            self.assertTrue(candidate.startswith("Standard_"))
            self.assertNotRegex(candidate, r"Standard_(N|NC|ND|NV)")

    def test_every_lane_publishes_maven_distributions(self):
        for shard in self.plan["shards"]:
            self.assertIn("maven", shard["workloads"], shard["id"])
            self.assertIn("distribution", shard["workloads"], shard["id"])
            self.assertTrue(shard["build"]["variants"], shard["id"])
            if shard["build"]["kind"] == "platform":
                self.assertTrue(shard["build"]["dl4jLane"], shard["id"])
                self.assertTrue(all(
                    variant["classifier"].startswith(
                        shard["build"]["javacppPlatform"]
                    )
                    for variant in shard["build"]["variants"]
                ))
            else:
                self.assertEqual("distribution", shard["build"]["kind"])
                self.assertEqual(
                    ["cli-only"],
                    [variant["name"] for variant in shard["build"]["variants"]],
                )
                self.assertTrue(all(
                    variant["requireSdk"] is False
                    for variant in shard["build"]["variants"]
                ))

    def test_cli_light_and_full_distribution_coverage_is_explicit(self):
        self.assertEqual(
            {"cli-only", "full"},
            set(self.plan["buildCoverage"]["distributionVariants"]),
        )
        self.assertEqual({
            "cli-only-linux-x86_64",
            "cli-only-linux-arm64",
            "cli-only-windows-x86_64",
        }, MODULE.distribution_classifiers(self.plan))
        self.assertTrue(all(
            shard["build"]["kind"] == "platform"
            for shard in self.plan["shards"]
            if any(
                variant.get("classifier") in self.classifiers
                for variant in shard["build"]["variants"]
            )
        ))

    def test_cli_light_classifier_selects_one_publishable_distribution(self):
        selected = MODULE.selected_executions(
            self.plan, ["cli-only-linux-x86_64"]
        )
        self.assertEqual(1, len(selected))
        self.assertEqual(
            "distribution-cli-only-linux-x86_64--cli-only", selected[0]["id"]
        )
        self.assertEqual(
            "cli-only-linux-x86_64",
            selected[0]["build"]["variants"][0]["distributionClassifier"],
        )

    def test_macos_is_explicitly_delegated_to_aws(self):
        self.assertIn("macos", self.plan["unsupportedWorkflows"])
        self.assertFalse(any(value.startswith("macosx-") for value in self.classifiers))


class SelectionAndInputTest(unittest.TestCase):
    def setUp(self):
        self.plan = MODULE.load_plan(ROOT / "release-plan.json")

    @staticmethod
    def dl4j_marker() -> dict:
        return {
            "ready": True,
            "provider": "azure",
            "commit": "b" * 40,
            "releaseVersion": "1.0.0",
            "runId": "dl4j-run",
        }

    def test_exact_classifier_selection_keeps_one_variant(self):
        selected = MODULE.selected_executions(
            self.plan, ["linux-x86_64-compile-avx2"]
        )
        self.assertEqual(1, len(selected))
        self.assertEqual(
            ["linux-x86_64-compile-avx2"],
            [item["classifier"] for item in selected[0]["build"]["variants"]],
        )
        self.assertEqual(
            "linux-x86_64-cpu--compile-avx2", selected[0]["id"]
        )

    def test_branch_resolution_uses_exact_remote_branch_ref(self):
        branch = "feat/graph-reasoning-trace-enhancements"
        commit = "c" * 40
        with patch.object(
            MODULE,
            "command",
            side_effect=[branch + "\n", f"{commit}\trefs/heads/{branch}\n"],
        ) as git:
            self.assertEqual(
                commit,
                MODULE.resolve_commit("https://example.test/kompile.git", branch),
            )
        self.assertEqual(
            ["git", "check-ref-format", "--branch", branch],
            git.call_args_list[0].args[0],
        )
        self.assertEqual(
            [
                "git", "ls-remote", "--refs",
                "https://example.test/kompile.git", f"refs/heads/{branch}",
            ],
            git.call_args_list[1].args[0],
        )

    def test_source_mode_accepts_exact_dl4j_commit(self):
        args = MODULE.parser().parse_args([
            "start",
            "--version", "1.2.3",
            "--commit", "a" * 40,
            "--dl4j-commit", "b" * 40,
        ])
        selected = MODULE.selected_executions(
            self.plan, ["linux-x86_64"]
        )
        values = MODULE.source_inputs(args, selected)
        self.assertEqual("source", values["dl4jInputMode"])
        self.assertEqual("b" * 40, values["dl4jCommit"])
        self.assertEqual("", values["dl4jBranch"])
        self.assertFalse(values["dl4jMavenRepositoryUrl"])

    def test_source_mode_preserves_dl4j_branch(self):
        args = MODULE.parser().parse_args([
            "start",
            "--version", "1.2.3",
            "--commit", "a" * 40,
            "--dl4j-branch", "release/snapshot",
        ])
        selected = MODULE.selected_executions(
            self.plan, ["windows-x86_64-compile"]
        )
        with patch.object(MODULE, "resolve_commit", return_value="b" * 40) as resolve:
            values = MODULE.source_inputs(args, selected)
        resolve.assert_called_once_with(
            MODULE.DEFAULT_DL4J_REPOSITORY, "release/snapshot"
        )
        self.assertEqual("release/snapshot", values["dl4jBranch"])
        self.assertEqual("b" * 40, values["dl4jCommit"])

    def test_blob_repository_mode_requires_and_accepts_sdk_template(self):
        args = MODULE.parser().parse_args([
            "start",
            "--version", "1.2.3",
            "--commit", "a" * 40,
            "--dl4j-maven-repository-url",
            "https://builds.blob.core.windows.net/releases/deeplearning4j/releases/maven-repository",
            "--dl4j-sdk-assets-url",
            "https://builds.blob.core.windows.net/releases/deeplearning4j/releases/{lane}/sdk-assets.tar.gz",
        ])
        selected = MODULE.selected_executions(
            self.plan, ["linux-x86_64"]
        )
        with patch.object(
            MODULE, "fetch_json_url", return_value=self.dl4j_marker()
        ):
            values = MODULE.source_inputs(args, selected)
        self.assertEqual("azure-blob-maven+source", values["dl4jInputMode"])
        self.assertTrue(values["dl4jMavenRepositoryUrl"].endswith("/"))
        self.assertIn("{lane}", values["dl4jSdkAssetsUrl"])
        self.assertEqual("b" * 40, values["dl4jCommit"])
        self.assertEqual("dl4j-run", values["dl4jRunId"])
        self.assertTrue(
            values["dl4jRepositoryMarkerUrl"].endswith(
                "/.dl4j/complete.json"
            )
        )

    def test_blob_repository_mode_rejects_unready_marker(self):
        args = MODULE.parser().parse_args([
            "start",
            "--version", "1.2.3",
            "--commit", "a" * 40,
            "--dl4j-maven-repository-url",
            "https://builds.blob.core.windows.net/releases/maven-repository",
        ])
        selected = MODULE.selected_executions(
            self.plan, ["linux-x86_64-vulkan"]
        )
        marker = {**self.dl4j_marker(), "ready": False}
        with patch.object(MODULE, "fetch_json_url", return_value=marker):
            with self.assertRaisesRegex(ValueError, "not ready"):
                MODULE.source_inputs(args, selected)

    def test_sonatype_repository_is_accepted_with_windows_sdk_assets(self):
        args = MODULE.parser().parse_args([
            "start",
            "--version", "1.2.3",
            "--commit", "a" * 40,
            "--dl4j-maven-repository-url",
            "https://central.sonatype.com/repository/maven-snapshots/",
            "--dl4j-maven-repository-id", "sonatype-snapshots",
            "--dl4j-sdk-assets-url",
            "https://builds.blob.core.windows.net/releases/{lane}/sdk-assets.tar.gz",
            "--dl4j-branch", "release/snapshot",
        ])
        selected = MODULE.selected_executions(
            self.plan, ["windows-x86_64-compile"]
        )
        with patch.object(MODULE, "fetch_json_url") as fetch, patch.object(
            MODULE, "resolve_commit", return_value="b" * 40,
        ):
            values = MODULE.source_inputs(args, selected)
        fetch.assert_not_called()
        self.assertEqual("maven+source", values["dl4jInputMode"])
        self.assertEqual(
            "https://central.sonatype.com/repository/maven-snapshots/",
            values["dl4jMavenRepositoryUrl"],
        )
        self.assertEqual("sonatype-snapshots", values["dl4jMavenRepositoryId"])
        self.assertEqual("", values["dl4jRepositoryMarkerUrl"])
        self.assertEqual("release/snapshot", values["dl4jBranch"])
        self.assertEqual("b" * 40, values["dl4jCommit"])

    def test_sonatype_repository_requires_dl4j_source_ref(self):
        args = MODULE.parser().parse_args([
            "start",
            "--version", "1.2.3",
            "--commit", "a" * 40,
            "--dl4j-maven-repository-url",
            "https://central.sonatype.com/repository/maven-snapshots/",
            "--dl4j-sdk-assets-url",
            "https://builds.blob.core.windows.net/releases/{lane}/sdk-assets.tar.gz",
        ])
        selected = MODULE.selected_executions(
            self.plan, ["windows-x86_64-compile"]
        )
        with self.assertRaisesRegex(ValueError, "owned DL4J Java modules"):
            MODULE.source_inputs(args, selected)

    def test_repository_url_rejects_insecure_or_credentialed_urls(self):
        selected = MODULE.selected_executions(
            self.plan, ["linux-x86_64-vulkan"]
        )
        for url in (
            "http://repo.example/snapshots",
            "https://user:password@repo.example/snapshots",
            "https://repo.example/snapshots?mutable=true",
        ):
            with self.subTest(url=url):
                args = MODULE.parser().parse_args([
                    "start",
                    "--version", "1.2.3",
                    "--commit", "a" * 40,
                    "--dl4j-maven-repository-url", url,
                ])
                with self.assertRaisesRegex(ValueError, "HTTPS URL"):
                    MODULE.source_inputs(args, selected)

    def test_maven_only_classifier_does_not_require_sdk_url(self):
        args = MODULE.parser().parse_args([
            "start",
            "--version", "1.2.3",
            "--commit", "a" * 40,
            "--dl4j-maven-repository-url",
            "https://builds.blob.core.windows.net/releases/maven-repository",
        ])
        selected = MODULE.selected_executions(
            self.plan, ["linux-x86_64-vulkan"]
        )
        with patch.object(
            MODULE, "fetch_json_url", return_value=self.dl4j_marker()
        ):
            values = MODULE.source_inputs(args, selected)
        self.assertEqual("", values["dl4jSdkAssetsUrl"])


class MachineSelectionTest(unittest.TestCase):
    def setUp(self):
        self.plan = MODULE.load_plan(ROOT / "release-plan.json")

    def test_candidate_selection_and_aggregate_batches(self):
        selected = MODULE.selected_executions(
            self.plan, ["linux-x86_64-cpu", "linux-x86_64-cuda-12-6"]
        )
        inventory = {
            "Standard_E8ds_v7": {
                "name": "Standard_E8ds_v7",
                "vcpus": 8,
                "memoryGiB": 64.0,
                "restricted": False,
                "capabilities": {"MemoryGB": "64"},
            }
        }
        MODULE.select_machines(
            self.plan,
            selected,
            inventory,
            machine_type=None,
            lane_machines={},
            max_cores=16,
        )
        batches = MODULE.execution_batches(selected, 8)
        self.assertEqual(2, len(batches))
        self.assertEqual(
            {"Standard_E8ds_v7"},
            {item["selectedMachine"]["name"] for item in selected},
        )


class AzureProviderContractTest(unittest.TestCase):
    def setUp(self):
        self.plan = MODULE.load_plan(ROOT / "release-plan.json")

    def test_controller_blob_operations_use_storage_key_auth(self):
        arguments = MODULE.blob_arguments("account")
        self.assertEqual("account", arguments[arguments.index("--account-name") + 1])
        self.assertEqual("key", arguments[arguments.index("--auth-mode") + 1])

    def test_worker_log_decoder_handles_utf8_and_windows_utf16(self):
        value = "bootstrap line\nerror line\n"
        self.assertEqual(value, MODULE.decode_worker_log(value.encode("utf-8")))
        self.assertEqual(value, MODULE.decode_worker_log(value.encode("utf-8-sig")))
        self.assertEqual(value, MODULE.decode_worker_log(value.encode("utf-16")))
        self.assertEqual(value, MODULE.decode_worker_log(value.encode("utf-16-le")))

    def test_worker_identity_has_container_scoped_least_privilege_roles(self):
        storage_shows = 0
        calls = []

        def fake_az(arguments, **kwargs):
            nonlocal storage_shows
            calls.append(arguments)
            if arguments[:2] == ["identity", "create"]:
                return {
                    "id": "/identity",
                    "clientId": "client",
                    "principalId": "principal",
                }
            if arguments[:3] == ["storage", "account", "show"]:
                storage_shows += 1
                return None if storage_shows == 1 else {
                    "id": "/subscriptions/sub/resourceGroups/rg/providers/"
                          "Microsoft.Storage/storageAccounts/account",
                }
            if arguments[:4] == ["storage", "account", "keys", "list"]:
                return [{"value": "test-account-key"}]
            if arguments[:3] == ["role", "assignment", "list"]:
                return []
            return {}

        with patch.object(MODULE, "az", side_effect=fake_az):
            MODULE.configure_storage(
                "sub", "eastus", "rg", "account", self.plan,
            )
        creates = [
            call for call in calls
            if call[:3] == ["role", "assignment", "create"]
        ]
        self.assertEqual(2, len(creates))
        permissions = [
            call for call in calls
            if call[:3] == ["storage", "container", "set-permission"]
        ]
        self.assertEqual(1, len(permissions))
        permission = permissions[0]
        public_container = permission[permission.index("--name") + 1]
        self.assertEqual(self.plan["repositoryContainer"], public_container)
        self.assertNotEqual(self.plan["artifactContainer"], public_container)
        self.assertEqual("key", permission[permission.index("--auth-mode") + 1])
        self.assertEqual(
            "test-account-key", permission[permission.index("--account-key") + 1],
        )
        by_role = {call[call.index("--role") + 1]: call for call in creates}
        contributor = by_role[MODULE.BLOB_DATA_CONTRIBUTOR]
        reader = by_role[MODULE.BLOB_DATA_READER]
        self.assertTrue(
            contributor[contributor.index("--scope") + 1].endswith(
                "/blobServices/default/containers/" + self.plan["artifactContainer"]
            )
        )
        self.assertTrue(
            reader[reader.index("--scope") + 1].endswith(
                "/blobServices/default/containers/control"
            )
        )

    def test_storage_public_permission_requires_account_key(self):
        def fake_az(arguments, **kwargs):
            if arguments[:3] == ["storage", "account", "show"]:
                return {"id": "/storage/account"}
            if arguments[:4] == ["storage", "account", "keys", "list"]:
                return []
            return {}

        with patch.object(MODULE, "az", side_effect=fake_az):
            with self.assertRaisesRegex(
                RuntimeError, "Azure storage account key unavailable",
            ):
                MODULE.configure_storage(
                    "sub", "eastus", "rg", "account", self.plan,
                )

    def test_bootstrap_sas_is_blob_scoped_read_only_and_https(self):
        with patch.object(
            MODULE, "az",
            return_value="https://account.blob.core.windows.net/control/worker?sig=x",
        ) as azure:
            value = MODULE.bootstrap_blob_sas(
                "account", "control", "worker", 48,
            )
        arguments = azure.call_args.args[0]
        self.assertNotIn("--as-user", arguments)
        self.assertIn("--https-only", arguments)
        self.assertEqual("key", arguments[arguments.index("--auth-mode") + 1])
        self.assertEqual("r", arguments[arguments.index("--permissions") + 1])
        self.assertEqual("control", arguments[arguments.index("--container-name") + 1])
        self.assertEqual("worker", arguments[arguments.index("--name") + 1])
        self.assertTrue(value.startswith("https://"))

    def test_linux_and_windows_use_managed_identity_custom_script(self):
        base = {
            "runId": "run",
            "location": "eastus",
            "computeResourceGroup": "compute",
        }
        identity = {"identityId": "/identity"}
        args = argparse.Namespace(
            root_volume_gib=1024,
            windows_admin_password="Strong!Password9",
        )
        executions = [
            {
                "id": "linux",
                "os": "linux",
                "selectedMachine": {"name": "Standard_F72s_v2"},
            },
            {
                "id": "windows",
                "os": "windows",
                "selectedMachine": {"name": "Standard_F72s_v2"},
            },
        ]
        calls = []
        with patch.object(
            MODULE, "az", side_effect=lambda arguments, **kwargs: calls.append(arguments),
        ):
            for execution in executions:
                MODULE.create_vm(
                    args, self.plan, execution, base, identity,
                    "https://bootstrap.example/worker?sig=x",
                )
        creates = [call for call in calls if call[:2] == ["vm", "create"]]
        extensions = [
            call for call in calls if call[:3] == ["vm", "extension", "set"]
        ]
        self.assertEqual(2, len(creates))
        self.assertEqual(2, len(extensions))
        self.assertTrue(all("--assign-identity" in call for call in creates))
        self.assertNotIn("--computer-name", creates[0])
        self.assertIn("--computer-name", creates[1])
        computer_name = creates[1][creates[1].index("--computer-name") + 1]
        self.assertLessEqual(len(computer_name), 15)
        self.assertRegex(computer_name, r"^kompile-[0-9a-f]{7}$")
        publishers = {
            call[call.index("--publisher") + 1] for call in extensions
        }
        self.assertEqual(
            {"Microsoft.Azure.Extensions", "Microsoft.Compute"}, publishers,
        )
        commands = [
            json.loads(call[call.index("--protected-settings") + 1])[
                "commandToExecute"
            ]
            for call in extensions
        ]
        self.assertTrue(any("systemd-run" in command for command in commands))
        windows_command = next(
            command for command in commands if "-EncodedCommand" in command
        )
        encoded = windows_command.rsplit(" ", 1)[1]
        bootstrap = __import__("base64").b64decode(encoded).decode("utf-16le")
        self.assertIn("$ErrorActionPreference = 'Stop'", bootstrap)
        self.assertIn("-Filter 'worker.ps1' -File -Recurse", bootstrap)
        self.assertIn("Expected exactly one downloaded worker.ps1", bootstrap)
        self.assertIn("-PassThru", bootstrap)
        self.assertIn("Worker exited prematurely", bootstrap)

    def test_stopped_vm_without_status_fails_immediately(self):
        execution = {"id": "linux-x86_64-cpu", "os": "linux"}
        instance = {
            "instanceView": {
                "statuses": [{"code": "PowerState/stopped"}]
            }
        }
        with patch.object(MODULE, "get_json_blob", return_value=None), \
             patch.object(MODULE, "az", return_value=instance), \
             patch.object(MODULE.time, "monotonic", side_effect=[0, 0]):
            with self.assertRaisesRegex(RuntimeError, "durable status"):
                MODULE.wait_for_execution(
                    "account", self.plan, "run", execution,
                    "compute", "vm", 1,
                )


class WorkerContractTest(unittest.TestCase):
    def test_rendered_workers_embed_config_and_driver(self):
        config = {"runId": "run", "shard": {"id": "lane"}}
        for name in ("worker.sh", "worker.ps1"):
            rendered = MODULE.render_worker(ROOT / name, config)
            self.assertNotIn(b"__KOMPILE_", rendered)
            self.assertIn(
                json.dumps(config, sort_keys=True).encode("utf-8"),
                __import__("base64").b64decode(
                    re_search_config(rendered, name)
                ),
            )

    def test_workers_fetch_controller_pinned_commit(self):
        shell = (ROOT / "worker.sh").read_text(encoding="utf-8")
        powershell = (ROOT / "worker.ps1").read_text(encoding="utf-8")
        self.assertIn(
            'git -C "${SOURCE_DIR}" fetch --depth=1 origin "${COMMIT}"', shell
        )
        self.assertNotIn("refs/heads/", shell)
        self.assertIn(
            "git -C $SourceDir fetch --depth=1 origin $Config.commit",
            powershell,
        )
        self.assertNotIn("$BranchRef", powershell)

    def test_windows_worker_enables_git_long_paths(self):
        powershell = (ROOT / "worker.ps1").read_text(encoding="utf-8")
        self.assertIn("git config --system core.longpaths true", powershell)
        self.assertIn("Failed to enable Git long-path support", powershell)

    def test_windows_worker_refreshes_native_tool_paths(self):
        powershell = (ROOT / "worker.ps1").read_text(encoding="utf-8")
        self.assertIn(
            "[Environment]::GetEnvironmentVariable('Path', 'Machine')",
            powershell,
        )
        self.assertIn("Get-Command python.exe", powershell)
        self.assertIn("$PythonCommand.Source -like 'C:\\tools\\msys64\\*'", powershell)
        self.assertIn("Get-Command mvn.cmd", powershell)
        self.assertIn("Start-Process $PythonExe", powershell)
        self.assertIn("& $PythonExe -c", powershell)

    def test_windows_worker_pins_gnu_rust_toolchain_for_cbindgen(self):
        powershell = (ROOT / "worker.ps1").read_text(encoding="utf-8")
        self.assertIn(
            "$RustToolchain = 'stable-x86_64-pc-windows-gnu'", powershell,
        )
        self.assertIn("'.cargo\\bin\\rustup.exe'", powershell)
        self.assertIn("$env:RUSTUP_TOOLCHAIN = $RustToolchain", powershell)
        self.assertIn(
            "& $Rustup run $RustToolchain cargo install --locked cbindgen",
            powershell,
        )
        self.assertNotIn("rustup default stable-x86_64-pc-windows-gnu", powershell)

    def test_windows_worker_combines_build_streams_before_exit_check(self):
        powershell = (ROOT / "worker.ps1").read_text(encoding="utf-8")
        wait = powershell.index("$Process.WaitForExit()")
        capture_exit = powershell.index("$BuildExitCode = $Process.ExitCode")
        append_stderr = powershell.index(
            "Get-Content $BuildStderr | Add-Content $BuildLog -Encoding UTF8"
        )
        check_exit = powershell.index(
            'if ($BuildExitCode -ne 0) { throw "Build failed with exit code '
        )
        self.assertLess(wait, capture_exit)
        self.assertLess(capture_exit, append_stderr)
        self.assertLess(append_stderr, check_exit)
        self.assertNotIn("-RedirectStandardOutput $BuildLog", powershell)
        self.assertNotIn("-RedirectStandardError \"$BuildLog.err\"", powershell)

    def test_status_is_uploaded_after_artifacts(self):
        for name in ("worker.sh", "worker.ps1"):
            source = (ROOT / name).read_text(encoding="utf-8")
            if name.endswith(".sh"):
                durable_section = source[
                    source.index("finish() {"):source.index("trap finish EXIT")
                ]
            else:
                durable_section = source[source.index("finally {"):]
            self.assertLess(
                durable_section.rfind("maven-repository.tar.gz"),
                durable_section.rfind("status.json"),
            )
            self.assertLess(
                durable_section.rfind("shard-manifest.json"),
                durable_section.rfind("status.json"),
            )
            if name.endswith(".sh"):
                self.assertNotIn(
                    'status.json" status.json || true', durable_section
                )
                self.assertIn(
                    "Failed to upload durable status marker", durable_section
                )
            else:
                status_upload = durable_section.rfind(
                    "Upload-IfPresent (Join-Path $OutputDir 'status.json')"
                )
                self.assertGreater(
                    durable_section.find(
                        "if ($UploadFailed) { $ExitCode = 1 }",
                        status_upload,
                    ),
                    status_upload,
                )

    def test_maven_tar_assemblies_receive_repository_checksums(self):
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            archive = (
                repository / "ai" / "kompile" / "kompile-dist" /
                "1.2.3" / "kompile-dist-1.2.3-cli-only-linux-x86_64.tar.gz"
            )
            archive.parent.mkdir(parents=True)
            archive.write_bytes(b"maven-tar-assembly")
            MODULE.prepare_maven_repository(repository)
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                self.assertTrue(Path(str(archive) + f".{algorithm}").is_file())

    def test_safe_maven_extraction_rejects_traversal(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "bad.tar.gz"
            payload = root / "payload"
            payload.write_text("bad", encoding="utf-8")
            with tarfile.open(archive, "w:gz") as bundle:
                bundle.add(payload, arcname="../../ai/kompile/bad.jar")
            with self.assertRaisesRegex(RuntimeError, "unsafe"):
                MODULE.safe_extract_maven(archive, root / "output")


def re_search_config(rendered: bytes, name: str) -> bytes:
    import re
    if name.endswith(".sh"):
        match = re.search(rb"CONFIG_B64='([^']+)'", rendered)
    else:
        match = re.search(rb"\$ConfigB64 = '([^']+)'", rendered)
    if match is None:
        raise AssertionError("rendered worker config was not found")
    return match.group(1)


class FullPlatformBuildTest(unittest.TestCase):
    def config(self, repository_mode: bool) -> dict:
        return {
            "runId": "run",
            "releaseVersion": "1.2.3",
            "snapshotVersion": "1.0.0-SNAPSHOT",
            "dl4jRepository": "https://example.test/dl4j.git",
            "dl4jCommit": "b" * 40,
            "dl4jMavenRepositoryUrl": (
                "https://builds.blob.core.windows.net/releases/maven/"
                if repository_mode else ""
            ),
            "dl4jMavenRepositoryId": "dl4j-release",
            "dl4jSdkAssetsUrl": (
                "https://builds.blob.core.windows.net/releases/{lane}.tar.gz"
                if repository_mode else ""
            ),
            "shard": {
                "id": "linux-x86_64-cpu--compile-avx2",
                "os": "linux",
                "architecture": "x86_64",
                "build": {
                    "kind": "platform",
                    "backend": "cpu",
                    "javacppPlatform": "linux-x86_64",
                    "dl4jLane": "linux-x86_64-cpu",
                    "buildThreads": 64,
                    "mavenHeapGiB": 48,
                    "variants": [{
                        "name": "compile-avx2",
                        "classifier": "linux-x86_64-compile-avx2",
                    }],
                },
            },
        }

    def test_source_mode_delegates_exact_lane_variant_then_builds_kompile(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            repository = root / "m2"
            output = root / "maven"
            assets = root / "assets"
            with patch.object(BUILD_MODULE, "ensure_graalvm", return_value={}),                  patch.object(BUILD_MODULE, "run_dl4j_release_lane") as dl4j,                  patch.object(BUILD_MODULE, "run_dl4j_java_reactor") as java,                  patch.object(BUILD_MODULE, "validate_sdk_assets"),                  patch.object(BUILD_MODULE, "run") as run,                  patch.object(BUILD_MODULE, "stage_kompile_maven_artifacts"):
                BUILD_MODULE.build_full_platform(
                    self.config(False), source, repository, output, assets
                )
            self.assertEqual(
                ("linux-x86_64-cpu", ["compile-avx2"]),
                (dl4j.call_args.args[5], dl4j.call_args.args[6]),
            )
            command = run.call_args.args[0]
            self.assertIn("linux-x86_64-compile-avx2", command)
            self.assertEqual(
                "1.2.3", command[command.index("--version") + 1]
            )
            self.assertIn("--skip-dl4j", command)
            self.assertIn("--dl4j-sdk-assets", command)
            java.assert_called_once()

    def test_repository_mode_downloads_native_sdk_and_co_builds_dl4j_java(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            with patch.object(BUILD_MODULE, "ensure_graalvm", return_value={}),                  patch.object(BUILD_MODULE, "download_dl4j_sdk_assets") as download,                  patch.object(BUILD_MODULE, "hydrate_dl4j_sdk_jars") as hydrate,                  patch.object(BUILD_MODULE, "run_dl4j_release_lane") as dl4j,                  patch.object(BUILD_MODULE, "run_dl4j_java_reactor") as java,                  patch.object(BUILD_MODULE, "run"),                  patch.object(BUILD_MODULE, "stage_kompile_maven_artifacts"):
                BUILD_MODULE.build_full_platform(
                    self.config(True),
                    source,
                    root / "m2",
                    root / "maven",
                    root / "assets",
                )
            download.assert_called_once()
            hydrate.assert_called_once()
            dl4j.assert_not_called()
            java.assert_called_once()

    def test_cli_light_lane_builds_and_stages_maven_assemblies_without_dl4j(self):
        plan = MODULE.load_plan(ROOT / "release-plan.json")
        shard = next(
            item for item in plan["shards"]
            if item["id"] == "distribution-cli-only-linux-x86_64"
        )
        config = {
            "runId": "run",
            "releaseVersion": "1.2.3",
            "snapshotVersion": "1.0.0-SNAPSHOT",
            "dl4jMavenRepositoryUrl": "",
            "shard": shard,
        }
        with patch.object(BUILD_MODULE, "ensure_graalvm", return_value={}), \
             patch.object(BUILD_MODULE, "run_dl4j_release_lane") as dl4j, \
             patch.object(BUILD_MODULE, "run") as run, \
             patch.object(BUILD_MODULE, "stage_kompile_maven_artifacts") as stage:
            BUILD_MODULE.build_distribution(
                config,
                Path("/source"),
                Path("/m2"),
                Path("/maven"),
                Path("/assets"),
            )
        dl4j.assert_not_called()
        command = run.call_args.args[0]
        self.assertEqual(
            ["bash", "./build-dist.sh", "cli-only"], command[:3]
        )
        self.assertEqual(
            "1.2.3", command[command.index("--version") + 1]
        )
        self.assertEqual(
            "linux-x86_64", command[command.index("--platform") + 1]
        )
        stage.assert_called_once_with(Path("/m2"), Path("/maven"))

    def test_repository_sdk_manifest_is_bound_to_maven_release_identity(self):
        config = {
            "dl4jRepositoryMarkerUrl": (
                "https://builds.blob.core.windows.net/releases/"
                "maven/.dl4j/complete.json"
            ),
            "dl4jCommit": "b" * 40,
            "dl4jReleaseVersion": "1.0.0",
            "dl4jRunId": "dl4j-run",
        }
        manifest = {
            "commit": "b" * 40,
            "releaseVersion": "1.0.0",
            "runId": "dl4j-run",
            "shard": "linux-x86_64-cpu--compile-avx2",
            "variants": ["compile-avx2"],
        }
        manifest_url = (
            "https://builds.blob.core.windows.net/releases/"
            "shard-manifest.json"
        )
        BUILD_MODULE.validate_dl4j_sdk_manifest(
            config, manifest, "linux-x86_64-cpu", "compile-avx2",
            manifest_url,
        )
        for key, value in (
            ("commit", "c" * 40),
            ("shard", "windows-x86_64-cpu"),
            ("variants", ["base"]),
        ):
            invalid = {**manifest, key: value}
            with self.subTest(key=key):
                with self.assertRaisesRegex(RuntimeError, "does not match"):
                    BUILD_MODULE.validate_dl4j_sdk_manifest(
                        config, invalid, "linux-x86_64-cpu",
                        "compile-avx2", manifest_url,
                    )


if __name__ == "__main__":
    unittest.main()
