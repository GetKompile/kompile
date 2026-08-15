#!/usr/bin/env python3
"""Provision large Azure CPU builders for the complete Kompile release matrix.

The controller deliberately keeps Kompile's release/build contract cloud-neutral:
Azure owns VM lifecycle and Blob transport, while release/aws/build-platform.py
builds either against an immutable DL4J source commit or an Azure Blob Maven
repository plus its classifier-matched SDK archive.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import copy
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
from typing import Any, Iterable
import urllib.parse
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PLAN = ROOT / "release/azure/release-plan.json"
BUILD_DRIVER = ROOT / "release/aws/build-platform.py"
DEFAULT_REPOSITORY = "https://github.com/GetKompile/kompile.git"
DEFAULT_DL4J_REPOSITORY = "https://github.com/deeplearning4j/deeplearning4j.git"
BLOB_DATA_CONTRIBUTOR = "Storage Blob Data Contributor"
BLOB_DATA_READER = "Storage Blob Data Reader"
NAME_PATTERN = re.compile(r"[^a-z0-9-]+")
COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}")
MAVEN_PRIMARY_SUFFIXES = {
    ".aar", ".gz", ".jar", ".json", ".module", ".pom", ".so", ".xml", ".zip",
}
DISTRIBUTION_VARIANTS = {"cli-only", "full"}


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def normalized_name(value: str, maximum: int = 63) -> str:
    value = NAME_PATTERN.sub("-", value.lower()).strip("-")
    if len(value) <= maximum:
        return value
    digest = hashlib.sha1(value.encode("utf-8")).hexdigest()[:8]
    return value[: maximum - 9].rstrip("-") + "-" + digest


def command(
    arguments: list[str],
    *,
    check: bool = True,
    json_output: bool = False,
) -> Any:
    completed = subprocess.run(
        arguments,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if check and completed.returncode:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise RuntimeError(f"{arguments[0]} failed ({completed.returncode}): {detail}")
    if json_output:
        payload = completed.stdout.strip()
        return json.loads(payload) if payload else None
    return completed.stdout.strip()


def az(arguments: list[str], *, check: bool = True, json_output: bool = True) -> Any:
    output = "json" if json_output else "tsv"
    return command(["az", *arguments, "--only-show-errors", "--output", output],
                   check=check, json_output=json_output)


def require_tools() -> None:
    missing = [name for name in ("az", "git") if shutil.which(name) is None]
    if missing:
        raise RuntimeError("missing required controller tools: " + ", ".join(missing))


def load_plan(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or value.get("schemaVersion") != 2:
        raise ValueError("Azure release plan must use schemaVersion 2")
    if value.get("provider") != "azure":
        raise ValueError("Azure release plan provider must be 'azure'")
    for key in ("artifactContainer", "repositoryContainer", "controlContainer"):
        if not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])?", str(value.get(key, ""))):
            raise ValueError(f"Azure release plan has invalid {key}")
    defaults = value.get("defaults")
    if not isinstance(defaults, dict):
        raise ValueError("Azure release plan requires defaults")
    for key in (
        "rootVolumeGiB", "mavenHeapGiB", "buildThreads", "minMemoryGiB",
        "maxCores", "maxTotalCores",
    ):
        if not isinstance(defaults.get(key), int) or defaults[key] < 1:
            raise ValueError(f"defaults.{key} must be a positive integer")
    if defaults["maxTotalCores"] < defaults["maxCores"]:
        raise ValueError("defaults.maxTotalCores must be >= defaults.maxCores")
    for key in ("x86MachineCandidates", "armMachineCandidates"):
        candidates = defaults.get(key)
        if not isinstance(candidates, list) or not candidates or not all(
            isinstance(candidate, str) and candidate for candidate in candidates
        ):
            raise ValueError(f"defaults.{key} must be a non-empty string list")
    coverage = value.get("buildCoverage")
    if not isinstance(coverage, dict):
        raise ValueError("Azure release plan requires buildCoverage")
    if set(coverage.get("distributionVariants", [])) != DISTRIBUTION_VARIANTS:
        raise ValueError(
            "buildCoverage.distributionVariants must contain cli-only and full"
        )
    if coverage.get("publishingPolicy") != "maven-assemblies-to-azure-blob":
        raise ValueError(
            "Azure distribution publication must use the collected Maven repository"
        )
    shards = value.get("shards")
    if not isinstance(shards, list) or not shards:
        raise ValueError("Azure release plan requires shards")
    shard_ids: set[str] = set()
    published_classifiers: set[str] = set()
    for shard in shards:
        shard_id = str(shard.get("id", ""))
        if not shard_id or shard_id in shard_ids:
            raise ValueError(f"duplicate or empty Azure shard id: {shard_id!r}")
        shard_ids.add(shard_id)
        if shard.get("os") not in {"linux", "windows"}:
            raise ValueError(f"Azure shard {shard_id} has unsupported os")
        expected_worker = "worker.ps1" if shard["os"] == "windows" else "worker.sh"
        if shard.get("worker") != expected_worker:
            raise ValueError(f"Azure shard {shard_id} must use {expected_worker}")
        build = shard.get("build")
        kind = build.get("kind") if isinstance(build, dict) else None
        if kind not in {"platform", "distribution"}:
            raise ValueError(
                f"Azure shard {shard_id} must be a platform or distribution build"
            )
        if not isinstance(build.get("javacppPlatform"), str):
            raise ValueError(f"Azure shard {shard_id} requires build.javacppPlatform")
        if kind == "platform" and not isinstance(build.get("dl4jLane"), str):
            raise ValueError(f"Azure shard {shard_id} requires build.dl4jLane")
        variants = build.get("variants")
        if not isinstance(variants, list) or not variants:
            raise ValueError(f"Azure shard {shard_id} requires variants")
        for variant in variants:
            if kind == "platform":
                classifier = str(variant.get("classifier", ""))
            else:
                name = str(variant.get("name", ""))
                if name != "cli-only":
                    raise ValueError(
                        f"Azure distribution shard {shard_id} supports only cli-only"
                    )
                classifier = str(variant.get("distributionClassifier", ""))
                expected = f"cli-only-{build['javacppPlatform']}"
                if classifier != expected:
                    raise ValueError(
                        f"Azure distribution classifier must be {expected}: {classifier!r}"
                    )
                if variant.get("requireSdk") is not False:
                    raise ValueError(
                        f"Azure cli-only shard {shard_id} must not require DL4J SDK assets"
                    )
            if not classifier or classifier in published_classifiers:
                raise ValueError(
                    f"duplicate or empty published classifier: {classifier!r}"
                )
            published_classifiers.add(classifier)
    return value


def release_classifiers(plan: dict[str, Any]) -> set[str]:
    return {
        variant["classifier"]
        for shard in plan["shards"]
        if shard["build"]["kind"] == "platform"
        for variant in shard["build"]["variants"]
    }


def distribution_classifiers(plan: dict[str, Any]) -> set[str]:
    return {
        variant["distributionClassifier"]
        for shard in plan["shards"]
        if shard["build"]["kind"] == "distribution"
        for variant in shard["build"]["variants"]
    }


def published_classifier(shard: dict[str, Any], variant: dict[str, Any]) -> str:
    key = (
        "classifier"
        if shard["build"]["kind"] == "platform"
        else "distributionClassifier"
    )
    return str(variant[key])


def selected_executions(
    plan: dict[str, Any],
    selected: list[str] | None,
    excluded: list[str] | None = None,
) -> list[dict[str, Any]]:
    selectors = set(selected or [])
    exclusions = set(excluded or [])
    known = (
        {shard["id"] for shard in plan["shards"]}
        | release_classifiers(plan)
        | distribution_classifiers(plan)
    )
    unknown = sorted((selectors | exclusions) - known)
    if unknown:
        raise ValueError("unknown Azure lane/classifier: " + ", ".join(unknown))
    executions: list[dict[str, Any]] = []
    for shard in plan["shards"]:
        parent_selected = not selectors or shard["id"] in selectors
        variants = []
        for variant in shard["build"]["variants"]:
            classifier = published_classifier(shard, variant)
            if shard["id"] in exclusions or classifier in exclusions:
                continue
            if parent_selected or classifier in selectors:
                variants.append(copy.deepcopy(variant))
        if not variants:
            continue
        execution = copy.deepcopy(shard)
        execution["parentId"] = shard["id"]
        execution["build"]["variants"] = variants
        if len(variants) == 1 and not parent_selected:
            execution["id"] = f"{shard['id']}--{variants[0]['name']}"
        executions.append(execution)
    if not executions:
        raise ValueError("selection produced no Azure executions")
    return executions


def resolve_commit(repository: str, branch: str) -> str:
    command(["git", "check-ref-format", "--branch", branch])
    branch_ref = f"refs/heads/{branch}"
    result = command(["git", "ls-remote", "--refs", repository, branch_ref])
    rows = [line.split() for line in result.splitlines() if line.strip()]
    commits = {
        row[0].lower()
        for row in rows
        if len(row) == 2 and row[1] == branch_ref
    }
    if len(commits) != 1:
        raise RuntimeError(
            f"unable to resolve branch {branch!r} to one immutable commit in {repository}"
        )
    commit = commits.pop()
    if not COMMIT_PATTERN.fullmatch(commit):
        raise RuntimeError(f"resolved invalid Git commit: {commit}")
    return commit


def https_url(value: str, label: str, *, template: bool = False) -> str:
    probe = value
    if template:
        try:
            probe = value.format(
                lane="lane", variant="variant", platform="linux-x86_64",
                version="1.0.0-SNAPSHOT", snapshotVersion="1.0.0-SNAPSHOT",
            )
        except KeyError as exc:
            raise ValueError(f"{label} has unknown placeholder {exc.args[0]!r}") from exc
    parsed = urllib.parse.urlparse(probe)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError(
            f"{label} must be an HTTPS URL without credentials, query, or fragment"
        )
    return value.rstrip("/")


def is_azure_blob_url(value: str) -> bool:
    hostname = urllib.parse.urlparse(value).hostname
    return bool(hostname and hostname.endswith(".blob.core.windows.net"))


def azure_blob_url(value: str, label: str, *, template: bool = False) -> str:
    normalized = https_url(value, label, template=template)
    probe = normalized
    if template:
        probe = normalized.format(
            lane="lane", variant="variant", platform="linux-x86_64",
            version="1.0.0-SNAPSHOT", snapshotVersion="1.0.0-SNAPSHOT",
        )
    if not is_azure_blob_url(probe):
        raise ValueError(f"{label} must be an HTTPS Azure Blob URL")
    return normalized


def fetch_json_url(url: str) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        headers={"Accept": "application/json", "User-Agent": "kompile-release"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        value = json.loads(response.read())
    if not isinstance(value, dict):
        raise ValueError(f"expected a JSON object from {url}")
    return value


def source_inputs(args: argparse.Namespace, executions: list[dict[str, Any]]) -> dict[str, Any]:
    source_commit = ""
    source_branch = args.dl4j_branch or ""
    if args.dl4j_commit:
        source_commit = args.dl4j_commit.lower()
        if not COMMIT_PATTERN.fullmatch(source_commit):
            raise ValueError("--dl4j-commit must be a full 40-character Git commit")
    elif source_branch:
        source_commit = resolve_commit(args.dl4j_repository, source_branch)

    if args.dl4j_maven_repository_url:
        repository_url = https_url(
            args.dl4j_maven_repository_url,
            "--dl4j-maven-repository-url",
        ) + "/"
        azure_repository = is_azure_blob_url(repository_url)
        runtime_required = any(
            bool(variant.get("requireSdk", shard["build"].get("requireSdk", True)))
            for shard in executions
            for variant in shard["build"]["variants"]
        )
        if runtime_required and not args.dl4j_sdk_assets_url:
            raise ValueError(
                "repository mode requires --dl4j-sdk-assets-url for selected runtime classifiers"
            )
        sdk_url = (
            azure_blob_url(
                args.dl4j_sdk_assets_url,
                "--dl4j-sdk-assets-url",
                template=True,
            )
            if args.dl4j_sdk_assets_url else ""
        )
        if args.dl4j_maven_marker_url and not azure_repository:
            raise ValueError(
                "--dl4j-maven-marker-url is only supported with an Azure Blob Maven repository"
            )
        marker_url = ""
        marker_commit = ""
        marker_version = ""
        marker_run_id = ""
        input_mode = "maven+source"
        if azure_repository:
            marker_url = azure_blob_url(
                args.dl4j_maven_marker_url
                or repository_url + ".dl4j/complete.json",
                "--dl4j-maven-marker-url",
            )
            marker = fetch_json_url(marker_url)
            marker_commit = str(marker.get("commit", "")).lower()
            if marker.get("ready") is not True or not COMMIT_PATTERN.fullmatch(
                marker_commit
            ):
                raise ValueError(
                    "DL4J Maven completion marker is not ready or lacks an immutable commit"
                )
            marker_version = str(marker.get("releaseVersion", ""))
            if not marker_version:
                raise ValueError("DL4J Maven completion marker lacks releaseVersion")
            marker_run_id = str(marker.get("runId", ""))
            if marker.get("provider") != "azure" or not marker_run_id:
                raise ValueError(
                    "DL4J Maven completion marker must identify an Azure run"
                )
            marker_snapshot = str(marker.get("snapshotVersion", ""))
            if marker_snapshot and marker_snapshot != args.snapshot_version:
                raise ValueError(
                    "DL4J Maven completion marker snapshotVersion does not match "
                    "--snapshot-version"
                )
            if source_commit and source_commit != marker_commit:
                raise ValueError(
                    "the requested DL4J source ref does not match the Azure Maven "
                    "repository completion marker"
                )
            source_commit = source_commit or marker_commit
            input_mode = "azure-blob-maven+source"
        elif not source_commit:
            raise ValueError(
                "a non-Azure Maven repository requires --dl4j-branch or "
                "--dl4j-commit so owned DL4J Java modules can be co-built"
            )
        return {
            "dl4jRepository": args.dl4j_repository,
            "dl4jBranch": source_branch,
            "dl4jCommit": source_commit,
            "dl4jMavenRepositoryUrl": repository_url,
            "dl4jMavenRepositoryId": args.dl4j_maven_repository_id,
            "dl4jSdkAssetsUrl": sdk_url,
            "dl4jRepositoryMarkerUrl": marker_url,
            "dl4jReleaseVersion": marker_version,
            "dl4jRunId": marker_run_id,
            "dl4jInputMode": input_mode,
        }
    if not source_commit:
        raise ValueError(
            "one of --dl4j-branch, --dl4j-commit, or an Azure Blob Maven "
            "repository with a completion marker is required"
        )
    return {
        "dl4jRepository": args.dl4j_repository,
        "dl4jBranch": source_branch,
        "dl4jCommit": source_commit,
        "dl4jMavenRepositoryUrl": "",
        "dl4jMavenRepositoryId": args.dl4j_maven_repository_id,
        "dl4jSdkAssetsUrl": "",
        "dl4jRepositoryMarkerUrl": "",
        "dl4jReleaseVersion": "",
        "dl4jRunId": "",
        "dl4jInputMode": "source",
    }


def subscription_id(value: str | None) -> str:
    if value:
        return value
    environment = os.environ.get("AZURE_SUBSCRIPTION_ID", "").strip()
    if environment:
        return environment
    account = az(["account", "show"])
    return str(account["id"])


def azure_location(value: str | None) -> str:
    location = value or os.environ.get("AZURE_LOCATION") or os.environ.get(
        "AZURE_DEFAULTS_LOCATION"
    )
    if not location:
        raise ValueError("--location or AZURE_LOCATION is required")
    if not re.fullmatch(r"[a-z0-9-]+", location):
        raise ValueError(f"invalid Azure location: {location!r}")
    return location


def storage_account_name(subscription: str, location: str, override: str | None) -> str:
    if override:
        name = override.lower()
    else:
        digest = hashlib.sha1(f"{subscription}/{location}".encode()).hexdigest()[:15]
        name = "kompilerel" + digest
    if not re.fullmatch(r"[a-z0-9]{3,24}", name):
        raise ValueError("Azure storage account must be 3-24 lowercase letters/digits")
    return name


def sku_inventory(
    location: str, requested_names: set[str] | None = None,
) -> dict[str, dict[str, Any]]:
    if requested_names:
        # An explicit --machine-type/--lane-machine is already an operator choice.
        # Use the lightweight size endpoint for that path; list-skus --all can hang
        # on a transient Azure Compute RP response and is unnecessary here.
        records = az(["vm", "list-sizes", "--location", location])
        result: dict[str, dict[str, Any]] = {}
        for record in records or []:
            name = str(record.get("name", ""))
            if name not in requested_names:
                continue
            result[name] = {
                "name": name,
                "vcpus": int(record.get("numberOfCores", 0)),
                "memoryGiB": float(record.get("memoryInMB", 0)) / 1024,
                "restricted": False,
                "capabilities": {
                    "vCPUs": str(record.get("numberOfCores", 0)),
                    "MemoryGB": str(float(record.get("memoryInMB", 0)) / 1024),
                },
            }
        return result

    records = az([
        "vm", "list-skus", "--location", location,
        "--resource-type", "virtualMachines", "--all",
    ])
    result: dict[str, dict[str, Any]] = {}
    for record in records or []:
        capabilities = {
            str(item.get("name")): str(item.get("value"))
            for item in record.get("capabilities", [])
        }
        try:
            cores = int(capabilities.get("vCPUs", "0"))
        except ValueError:
            cores = 0
        try:
            memory_gib = float(capabilities.get("MemoryGB", "0"))
        except ValueError:
            memory_gib = 0.0
        restricted = bool(record.get("restrictions"))
        result[str(record.get("name"))] = {
            "name": str(record.get("name")),
            "vcpus": cores,
            "memoryGiB": memory_gib,
            "restricted": restricted,
            "capabilities": capabilities,
        }
    return result


def parse_lane_machines(values: list[str] | None) -> dict[str, str]:
    result: dict[str, str] = {}
    for value in values or []:
        lane, separator, machine = value.partition("=")
        if not separator or not lane or not machine:
            raise ValueError("--lane-machine must use LANE=AZURE_VM_SIZE")
        if lane in result:
            raise ValueError(f"duplicate --lane-machine for {lane}")
        result[lane] = machine
    return result


def select_machines(
    plan: dict[str, Any],
    executions: list[dict[str, Any]],
    inventory: dict[str, dict[str, Any]],
    *,
    machine_type: str | None,
    lane_machines: dict[str, str],
    max_cores: int,
) -> None:
    known_lanes = {item["parentId"] for item in executions}
    unknown = sorted(set(lane_machines) - known_lanes)
    if unknown:
        raise ValueError("--lane-machine references unselected lanes: " + ", ".join(unknown))
    defaults = plan["defaults"]
    for execution in executions:
        candidates = (
            [lane_machines[execution["parentId"]]]
            if execution["parentId"] in lane_machines
            else [machine_type] if machine_type
            else defaults[
                "armMachineCandidates"
                if execution["architecture"] == "arm64"
                else "x86MachineCandidates"
            ]
        )
        rejected = []
        selected = None
        for name in candidates:
            sku = inventory.get(str(name))
            if sku is None:
                rejected.append(f"{name}: unavailable")
            elif sku["restricted"]:
                rejected.append(f"{name}: restricted")
            elif sku["vcpus"] < 1:
                rejected.append(f"{name}: vCPU count unavailable")
            elif sku["vcpus"] > max_cores:
                rejected.append(f"{name}: {sku['vcpus']} exceeds max {max_cores}")
            elif float(sku.get("memoryGiB", 0)) < defaults["minMemoryGiB"]:
                rejected.append(
                    f"{name}: {sku.get('memoryGiB', 0)} GiB is below required "
                    f"{defaults['minMemoryGiB']} GiB"
                )
            else:
                selected = copy.deepcopy(sku)
                break
        if selected is None:
            raise RuntimeError(
                f"no usable Azure VM size for {execution['id']}: " + "; ".join(rejected)
            )
        execution["selectedMachine"] = selected
        execution["build"]["buildThreads"] = min(
            int(execution["build"].get("buildThreads", defaults["buildThreads"])),
            max(1, selected["vcpus"]),
        )
        execution["build"]["mavenHeapGiB"] = int(
            execution["build"].get("mavenHeapGiB", defaults["mavenHeapGiB"])
        )


def execution_batches(
    executions: list[dict[str, Any]], max_total_cores: int,
) -> list[list[dict[str, Any]]]:
    batches: list[list[dict[str, Any]]] = []
    current: list[dict[str, Any]] = []
    cores = 0
    for execution in executions:
        value = int(execution["selectedMachine"]["vcpus"])
        if value > max_total_cores:
            raise ValueError(
                f"{execution['id']} needs {value} cores, above --max-total-cores"
            )
        if current and cores + value > max_total_cores:
            batches.append(current)
            current = []
            cores = 0
        current.append(execution)
        cores += value
    if current:
        batches.append(current)
    return batches


def blob_arguments(account: str) -> list[str]:
    return ["--account-name", account, "--auth-mode", "key"]


def blob_exists(account: str, container: str, name: str) -> bool:
    value = az([
        "storage", "blob", "exists",
        *blob_arguments(account),
        "--container-name", container,
        "--name", name,
    ])
    return bool(value and value.get("exists"))


def upload_blob(
    account: str, container: str, name: str, path: Path, *, overwrite: bool = True,
) -> None:
    az([
        "storage", "blob", "upload",
        *blob_arguments(account),
        "--container-name", container,
        "--name", name,
        "--file", str(path),
        "--overwrite", "true" if overwrite else "false",
    ])


def put_json_blob(
    account: str, container: str, name: str, value: dict[str, Any],
) -> None:
    with tempfile.NamedTemporaryFile(
        "w", suffix=".json", delete=False, encoding="utf-8"
    ) as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write("\n")
        path = Path(stream.name)
    try:
        upload_blob(account, container, name, path)
    finally:
        path.unlink(missing_ok=True)


def download_blob(account: str, container: str, name: str, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    az([
        "storage", "blob", "download",
        *blob_arguments(account),
        "--container-name", container,
        "--name", name,
        "--file", str(path),
        "--overwrite", "true",
    ])


def get_json_blob(
    account: str, container: str, name: str,
) -> dict[str, Any] | None:
    if not blob_exists(account, container, name):
        return None
    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as stream:
        path = Path(stream.name)
    try:
        download_blob(account, container, name, path)
        return json.loads(path.read_text(encoding="utf-8"))
    finally:
        path.unlink(missing_ok=True)


def configure_storage(
    subscription: str,
    location: str,
    resource_group: str,
    account: str,
    plan: dict[str, Any],
) -> dict[str, str]:
    az(["account", "set", "--subscription", subscription], json_output=False)
    az(["group", "create", "--name", resource_group, "--location", location])
    existing = az([
        "storage", "account", "show",
        "--resource-group", resource_group,
        "--name", account,
    ], check=False)
    if not existing:
        az([
            "storage", "account", "create",
            "--resource-group", resource_group,
            "--name", account,
            "--location", location,
            "--sku", "Standard_ZRS",
            "--kind", "StorageV2",
            "--min-tls-version", "TLS1_2",
            "--allow-blob-public-access", "true",
        ])
    else:
        az([
            "storage", "account", "update",
            "--resource-group", resource_group,
            "--name", account,
            "--allow-blob-public-access", "true",
        ])
    for container in (
        plan["artifactContainer"],
        plan["repositoryContainer"],
        plan["controlContainer"],
    ):
        az([
            "storage", "container", "create",
            *blob_arguments(account),
            "--name", container,
        ])
    keys = az([
        "storage", "account", "keys", "list",
        "--resource-group", resource_group,
        "--account-name", account,
    ])
    account_key = (
        str(keys[0].get("value", "")).strip()
        if isinstance(keys, list) and keys
        else ""
    )
    if not account_key:
        raise RuntimeError(f"Azure storage account key unavailable: {account}")
    az([
        "storage", "container", "set-permission",
        "--account-name", account,
        "--account-key", account_key,
        "--auth-mode", "key",
        "--name", plan["repositoryContainer"],
        "--public-access", "blob",
    ])
    identity_name = "kompile-release-worker"
    identity = az([
        "identity", "create",
        "--resource-group", resource_group,
        "--name", identity_name,
        "--location", location,
    ])
    storage = az([
        "storage", "account", "show",
        "--resource-group", resource_group,
        "--name", account,
    ])
    scopes = (
        (
            storage["id"]
            + "/blobServices/default/containers/"
            + plan["artifactContainer"],
            BLOB_DATA_CONTRIBUTOR,
        ),
        (
            storage["id"]
            + "/blobServices/default/containers/"
            + plan["controlContainer"],
            BLOB_DATA_READER,
        ),
    )
    for scope, role in scopes:
        assignment = az([
            "role", "assignment", "list",
            "--assignee-object-id", identity["principalId"],
            "--scope", scope,
            "--role", role,
        ])
        if not assignment:
            az([
                "role", "assignment", "create",
                "--assignee-object-id", identity["principalId"],
                "--assignee-principal-type", "ServicePrincipal",
                "--scope", scope,
                "--role", role,
            ])
    return {
        "identityId": identity["id"],
        "identityClientId": identity["clientId"],
        "identityPrincipalId": identity["principalId"],
    }


def bootstrap_blob_sas(
    account: str, container: str, name: str, timeout_hours: int,
) -> str:
    expiry = dt.datetime.now(dt.timezone.utc) + dt.timedelta(
        hours=min(timeout_hours + 2, 6 * 24)
    )
    return str(az([
        "storage", "blob", "generate-sas",
        "--account-name", account,
        "--auth-mode", "key",
        "--container-name", container,
        "--name", name,
        "--permissions", "r",
        "--expiry", expiry.strftime("%Y-%m-%dT%H:%MZ"),
        "--https-only",
        "--full-uri",
    ], json_output=False)).strip()


def render_worker(path: Path, config: dict[str, Any]) -> bytes:
    content = path.read_text(encoding="utf-8")
    replacements = {
        "__KOMPILE_AZURE_WORKER_CONFIG_B64__": base64.b64encode(
            json.dumps(config, sort_keys=True).encode("utf-8")
        ).decode("ascii"),
        "__KOMPILE_BUILD_DRIVER_B64__": base64.b64encode(
            BUILD_DRIVER.read_bytes()
        ).decode("ascii"),
    }
    for marker, value in replacements.items():
        content = content.replace(marker, value)
    unresolved = re.findall(r"__KOMPILE_[A-Z0-9_]+__", content)
    if unresolved:
        raise RuntimeError(f"unresolved worker placeholders: {sorted(set(unresolved))}")
    return content.encode("utf-8")


def upload_worker(
    account: str,
    plan: dict[str, Any],
    run_id: str,
    execution: dict[str, Any],
    config: dict[str, Any],
    timeout_hours: int,
) -> str:
    worker = ROOT / "release" / "azure" / execution["worker"]
    payload = render_worker(worker, config)
    blob_name = (
        f"{plan['artifactPrefix'].strip('/')}/{run_id}/bootstrap/"
        f"{execution['id']}/{worker.name}"
    )
    with tempfile.NamedTemporaryFile(delete=False, suffix=worker.suffix) as stream:
        stream.write(payload)
        path = Path(stream.name)
    try:
        upload_blob(account, plan["controlContainer"], blob_name, path)
    finally:
        path.unlink(missing_ok=True)
    return bootstrap_blob_sas(
        account, plan["controlContainer"], blob_name, timeout_hours
    )


def vm_name(run_id: str, execution_id: str) -> str:
    return normalized_name(f"kompile-{run_id}-{execution_id}", 60)


def windows_computer_name(run_id: str, execution_id: str) -> str:
    digest = hashlib.sha256(f"{run_id}/{execution_id}".encode()).hexdigest()[:7]
    return f"kompile-{digest}"


def create_vm(
    args: argparse.Namespace,
    plan: dict[str, Any],
    execution: dict[str, Any],
    config: dict[str, Any],
    identity: dict[str, str],
    worker_url: str,
) -> str:
    name = vm_name(config["runId"], execution["id"])
    image = plan["defaults"][
        "windowsImage" if execution["os"] == "windows" else "linuxImage"
    ]
    create = [
        "vm", "create",
        "--resource-group", config["computeResourceGroup"],
        "--name", name,
        "--location", config["location"],
        "--image", image,
        "--size", execution["selectedMachine"]["name"],
        "--os-disk-size-gb", str(args.root_volume_gib),
        "--storage-sku", "Premium_LRS",
        "--assign-identity", identity["identityId"],
        "--vnet-name", "kompile-release-vnet",
        "--subnet", "builders",
        "--public-ip-sku", "Standard",
        "--tags",
        "kompile-release-managed=true",
        f"kompile-run={config['runId']}",
        f"kompile-shard={normalized_name(execution['id'])}",
    ]
    if execution["os"] == "windows":
        password = args.windows_admin_password or (
            "Kompile!" + secrets.token_urlsafe(24) + "9a"
        )
        create.extend([
            "--computer-name", windows_computer_name(config["runId"], execution["id"]),
            "--admin-username", "kompile",
            "--admin-password", password,
        ])
    else:
        create.extend([
            "--admin-username", "kompile",
            "--generate-ssh-keys",
        ])
    az(create)
    if execution["os"] == "windows":
        publisher = "Microsoft.Compute"
        extension = "CustomScriptExtension"
        bootstrap = (
            "$ErrorActionPreference = 'Stop'; "
            "$workers = @(Get-ChildItem -LiteralPath (Get-Location).Path "
            "-Filter 'worker.ps1' -File -Recurse | Select-Object -First 2); "
            "if ($workers.Count -ne 1) { "
            "throw \"Expected exactly one downloaded worker.ps1, found "
            "$($workers.Count)\"; }; "
            "$target = 'C:\\kompile-azure-worker.ps1'; "
            "Copy-Item -LiteralPath $workers[0].FullName "
            "-Destination $target -Force; "
            "$process = Start-Process -FilePath powershell.exe -ArgumentList "
            "@('-NoLogo','-NoProfile','-NonInteractive','-ExecutionPolicy',"
            "'Bypass','-File',$target) -WindowStyle Hidden -PassThru; "
            "Start-Sleep -Seconds 2; "
            "if ($process.HasExited) { "
            "throw \"Worker exited prematurely with code $($process.ExitCode)\"; }"
        )
        encoded = base64.b64encode(bootstrap.encode("utf-16le")).decode("ascii")
        command_line = (
            "powershell.exe -NoLogo -NoProfile -NonInteractive "
            f"-EncodedCommand {encoded}"
        )
    else:
        publisher = "Microsoft.Azure.Extensions"
        extension = "CustomScript"
        command_line = (
            "bash -c 'install -m 700 worker.sh "
            "/usr/local/bin/kompile-azure-worker && "
            "systemd-run --unit=kompile-release-worker --collect "
            "/usr/local/bin/kompile-azure-worker'"
        )
    az([
        "vm", "extension", "set",
        "--resource-group", config["computeResourceGroup"],
        "--vm-name", name,
        "--publisher", publisher,
        "--name", extension,
        "--settings", json.dumps({"fileUris": [worker_url]}),
        "--protected-settings", json.dumps({"commandToExecute": command_line}),
    ])
    return name


def wait_for_execution(
    account: str,
    plan: dict[str, Any],
    run_id: str,
    execution: dict[str, Any],
    compute_resource_group: str,
    vm_name: str,
    timeout_hours: int,
) -> dict[str, Any]:
    prefix = (
        f"{plan['artifactPrefix'].strip('/')}/{run_id}/{execution['id']}"
    )
    deadline = time.monotonic() + timeout_hours * 3600
    while time.monotonic() < deadline:
        status = get_json_blob(
            account, plan["artifactContainer"], f"{prefix}/status.json"
        )
        if status is not None:
            status["executionId"] = execution["id"]
            return status
        instance = az([
            "vm", "get-instance-view",
            "--resource-group", compute_resource_group,
            "--name", vm_name,
        ], check=False)
        if isinstance(instance, dict):
            statuses = instance.get("instanceView", {}).get(
                "statuses", instance.get("statuses", [])
            )
            codes = {
                str(item.get("code", "")).lower()
                for item in statuses
                if isinstance(item, dict)
            }
            provisioning = str(instance.get("provisioningState", "")).lower()
            if provisioning == "failed" or "provisioningstate/failed" in codes:
                raise RuntimeError(
                    f"Azure VM provisioning failed before status upload: {execution['id']}"
                )
            power = next(
                (
                    code.removeprefix("powerstate/")
                    for code in codes
                    if code.startswith("powerstate/")
                ),
                "",
            )
            if power in {"stopped", "deallocated"}:
                raise RuntimeError(
                    f"Azure worker stopped before durable status upload: "
                    f"{execution['id']} ({power})"
                )
        time.sleep(30)
    raise TimeoutError(f"Azure execution timed out: {execution['id']}")


def run_execution(
    args: argparse.Namespace,
    plan: dict[str, Any],
    base: dict[str, Any],
    identity: dict[str, str],
    execution: dict[str, Any],
) -> dict[str, Any]:
    config = {
        **base,
        "shard": execution,
        "selectedMachine": execution["selectedMachine"],
    }
    url = upload_worker(
        base["storageAccount"], plan, base["runId"], execution, config,
        args.timeout_hours,
    )
    vm_name = create_vm(args, plan, execution, config, identity, url)
    return wait_for_execution(
        base["storageAccount"], plan, base["runId"], execution,
        base["computeResourceGroup"], vm_name, args.timeout_hours,
    )


def safe_extract_maven(archive: Path, destination: Path) -> None:
    with tarfile.open(archive, "r:gz") as bundle:
        for member in bundle.getmembers():
            if not member.isfile():
                continue
            relative = Path(member.name)
            if relative.is_absolute() or ".." in relative.parts:
                raise RuntimeError(f"unsafe Maven archive member: {member.name}")
            parts = list(relative.parts)
            try:
                group_index = parts.index("ai")
            except ValueError:
                continue
            relative = Path(*parts[group_index:])
            if len(relative.parts) < 2 or relative.parts[:2] != ("ai", "kompile"):
                continue
            incoming = bundle.extractfile(member)
            if incoming is None:
                raise RuntimeError(f"unable to read Maven archive member: {member.name}")
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            payload = incoming.read()
            if target.exists() and target.read_bytes() != payload:
                if target.name.startswith("maven-metadata") or target.name in {
                    "_remote.repositories", "resolver-status.properties",
                }:
                    continue
                raise RuntimeError(
                    f"conflicting Kompile Maven artifact across Azure shards: {relative}"
                )
            target.write_bytes(payload)


def prepare_maven_repository(root: Path) -> None:
    for path in list(root.rglob("*")):
        if not path.is_file():
            continue
        if path.name in {"_remote.repositories", "resolver-status.properties"} or (
            path.name.endswith(".lastUpdated")
        ):
            path.unlink()
            continue
        if path.name == "maven-metadata-local.xml":
            remote = path.with_name("maven-metadata.xml")
            if not remote.exists():
                shutil.copy2(path, remote)
    primaries = [
        path for path in root.rglob("*")
        if path.is_file()
        and path.suffix.lower() in MAVEN_PRIMARY_SUFFIXES
        and not any(path.name.endswith("." + algorithm) for algorithm in (
            "md5", "sha1", "sha256", "sha512"
        ))
    ]
    for path in primaries:
        payload = path.read_bytes()
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            digest = hashlib.new(algorithm, payload).hexdigest()
            Path(str(path) + f".{algorithm}").write_text(
                digest + "\n", encoding="ascii"
            )


def collect_run(
    account: str,
    plan: dict[str, Any],
    run: dict[str, Any],
) -> dict[str, Any]:
    executions = [
        item for item in run["executions"]
        if int((item.get("result") or {}).get("exitCode", 1)) == 0
    ]
    if not executions:
        raise RuntimeError("no successful Azure executions to collect")
    with tempfile.TemporaryDirectory(prefix="kompile-azure-collect-") as temporary:
        root = Path(temporary)
        repository = root / "repository"
        repository.mkdir()
        for item in executions:
            execution_id = item["id"]
            name = (
                f"{plan['artifactPrefix'].strip('/')}/{run['runId']}/"
                f"{execution_id}/maven-repository.tar.gz"
            )
            archive = root / f"{normalized_name(execution_id)}.tar.gz"
            download_blob(account, plan["artifactContainer"], name, archive)
            safe_extract_maven(archive, repository)
        prepare_maven_repository(repository)
        az([
            "storage", "blob", "upload-batch",
            *blob_arguments(account),
            "--destination", plan["repositoryContainer"],
            "--destination-path", plan["mavenRepositoryPrefix"].strip("/"),
            "--source", str(repository),
            "--overwrite", "true",
        ])
        marker = {
            "schemaVersion": 1,
            "layout": "maven2",
            "ready": True,
            "provider": "azure",
            "runId": run["runId"],
            "releaseVersion": run["releaseVersion"],
            "snapshotVersion": run["snapshotVersion"],
            "commit": run["commit"],
            "dl4jInputMode": run["dl4jInputMode"],
            "dl4jCommit": run.get("dl4jCommit", ""),
            "executions": [item["id"] for item in executions],
            "completedAt": utc_now(),
        }
        put_json_blob(
            account,
            plan["repositoryContainer"],
            f"{plan['mavenRepositoryPrefix'].strip('/')}/.kompile/complete.json",
            marker,
        )
    marker["url"] = (
        f"https://{account}.blob.core.windows.net/{plan['repositoryContainer']}/"
        f"{plan['mavenRepositoryPrefix'].strip('/')}/"
    )
    return marker


def start(args: argparse.Namespace) -> None:
    require_tools()
    plan = load_plan(args.plan)
    executions = selected_executions(plan, args.shard, args.exclude_shard)
    commit = (
        args.commit.lower()
        if args.commit
        else resolve_commit(args.repository, args.branch)
    )
    if not COMMIT_PATTERN.fullmatch(commit):
        raise ValueError("--commit must be a full 40-character Git commit")
    dl4j = source_inputs(args, executions)
    subscription = subscription_id(args.subscription)
    location = azure_location(args.location)
    resource_group = args.resource_group or f"kompile-release-{location}"
    account = storage_account_name(subscription, location, args.storage_account)
    lane_machines = parse_lane_machines(args.lane_machine)
    requested_machines = set(lane_machines.values())
    if args.machine_type:
        requested_machines.add(args.machine_type)
    inventory = sku_inventory(location, requested_machines or None)
    select_machines(
        plan,
        executions,
        inventory,
        machine_type=args.machine_type,
        lane_machines=lane_machines,
        max_cores=args.max_cores,
    )
    batches = execution_batches(executions, args.max_total_cores)
    run_id = args.run_id or normalized_name(
        f"{args.version}-{commit[:10]}-{uuid.uuid4().hex[:6]}", 54
    )
    if args.dry_run:
        print(json.dumps({
            "runId": run_id,
            "branch": args.branch or "",
            "commit": commit,
            **dl4j,
            "batches": [
                [
                    {
                        "id": item["id"],
                        "machine": item["selectedMachine"]["name"],
                        "vcpus": item["selectedMachine"]["vcpus"],
                        "classifiers": [
                            published_classifier(item, value)
                            for value in item["build"]["variants"]
                        ],
                    }
                    for item in batch
                ]
                for batch in batches
            ],
        }, indent=2))
        return

    identity = configure_storage(
        subscription, location, resource_group, account, plan
    )
    run_blob = f"{plan['artifactPrefix'].strip('/')}/{run_id}/run.json"
    if blob_exists(account, plan["artifactContainer"], run_blob):
        raise RuntimeError(f"Azure run already exists: {run_id}")
    kill_blob = f"{plan['artifactPrefix'].strip('/')}/control/kill-switch.json"
    current_kill = get_json_blob(account, plan["controlContainer"], kill_blob)
    if current_kill and current_kill.get("enabled") and not args.reset_kill_switch:
        raise RuntimeError("global Azure release kill switch is enabled")
    put_json_blob(
        account, plan["controlContainer"], kill_blob,
        {"enabled": False, "updatedAt": utc_now(), "reason": "release start"},
    )
    compute_group = normalized_name(f"{resource_group}-{run_id}", 90)
    az(["group", "create", "--name", compute_group, "--location", location,
        "--tags", "kompile-release-managed=true", f"kompile-run={run_id}"])
    az([
        "network", "vnet", "create",
        "--resource-group", compute_group,
        "--name", "kompile-release-vnet",
        "--subnet-name", "builders",
        "--address-prefixes", "10.77.0.0/16",
        "--subnet-prefixes", "10.77.0.0/24",
    ])
    base = {
        "provider": "azure",
        "subscription": subscription,
        "location": location,
        "resourceGroup": resource_group,
        "computeResourceGroup": compute_group,
        "storageAccount": account,
        "artifactContainer": plan["artifactContainer"],
        "controlContainer": plan["controlContainer"],
        "artifactPrefix": plan["artifactPrefix"],
        "runId": run_id,
        "releaseVersion": args.version,
        "snapshotVersion": args.snapshot_version,
        "branch": args.branch or "",
        "commit": commit,
        "repository": args.repository,
        "managedIdentityClientId": identity["identityClientId"],
        "killSwitchUrl": (
            f"https://{account}.blob.core.windows.net/{plan['controlContainer']}/"
            f"{kill_blob}"
        ),
        **dl4j,
    }
    manifest = {
        "schemaVersion": 2,
        **base,
        "status": "running",
        "createdAt": utc_now(),
        "executions": [
            {
                "id": item["id"],
                "parentId": item["parentId"],
                "status": "pending",
                "machine": item["selectedMachine"],
                "classifiers": [
                    published_classifier(item, variant)
                    for variant in item["build"]["variants"]
                ],
            }
            for item in executions
        ],
    }
    put_json_blob(account, plan["artifactContainer"], run_blob, manifest)
    by_id = {item["id"]: item for item in manifest["executions"]}
    failure: BaseException | None = None
    try:
        for batch in batches:
            with concurrent.futures.ThreadPoolExecutor(
                max_workers=len(batch)
            ) as executor:
                futures = {
                    executor.submit(
                        run_execution, args, plan, base, identity, execution
                    ): execution
                    for execution in batch
                }
                for future in concurrent.futures.as_completed(futures):
                    execution = futures[future]
                    record = by_id[execution["id"]]
                    try:
                        record["result"] = future.result()
                        record["status"] = (
                            "succeeded"
                            if int(record["result"].get("exitCode", 1)) == 0
                            else "failed"
                        )
                        if record["status"] == "failed" and failure is None:
                            failure = RuntimeError(
                                f"Azure execution failed: {execution['id']}"
                            )
                    except BaseException as exc:
                        record["status"] = "failed"
                        record["failure"] = str(exc)
                        if failure is None:
                            failure = exc
                    if failure is not None:
                        put_json_blob(
                            account, plan["controlContainer"], kill_blob,
                            {
                                "enabled": True,
                                "updatedAt": utc_now(),
                                "reason": f"run {run_id} failed",
                            },
                        )
                    put_json_blob(
                        account, plan["artifactContainer"], run_blob, manifest
                    )
            if failure is not None:
                put_json_blob(
                    account, plan["controlContainer"], kill_blob,
                    {
                        "enabled": True,
                        "updatedAt": utc_now(),
                        "reason": f"run {run_id} failed",
                    },
                )
                break
        if failure is None and all(
            item["status"] == "succeeded" for item in manifest["executions"]
        ):
            manifest["status"] = "succeeded"
            if args.auto_collect:
                manifest["mavenRepository"] = collect_run(account, plan, manifest)
        else:
            manifest["status"] = "failed"
            manifest["failure"] = str(failure or "one or more Azure builds failed")
    except BaseException as exc:
        failure = exc
        manifest["status"] = "failed"
        manifest["failure"] = str(exc)
        put_json_blob(
            account, plan["controlContainer"], kill_blob,
            {
                "enabled": True,
                "updatedAt": utc_now(),
                "reason": f"run {run_id} controller failure",
            },
        )
    finally:
        manifest["completedAt"] = utc_now()
        put_json_blob(account, plan["artifactContainer"], run_blob, manifest)
        if not args.keep_resources:
            az(["group", "delete", "--name", compute_group, "--yes", "--no-wait"],
               json_output=False)
    print(json.dumps(manifest, indent=2))
    if manifest["status"] != "succeeded":
        raise RuntimeError(manifest["failure"])


def preflight(args: argparse.Namespace) -> None:
    require_tools()
    plan = load_plan(args.plan)
    executions = selected_executions(plan, args.shard, args.exclude_shard)
    subscription = subscription_id(args.subscription)
    location = azure_location(args.location)
    lane_machines = parse_lane_machines(args.lane_machine)
    requested_machines = set(lane_machines.values())
    if args.machine_type:
        requested_machines.add(args.machine_type)
    inventory = sku_inventory(location, requested_machines or None)
    select_machines(
        plan,
        executions,
        inventory,
        machine_type=args.machine_type,
        lane_machines=lane_machines,
        max_cores=args.max_cores,
    )
    batches = execution_batches(executions, args.max_total_cores)
    print(json.dumps({
        "subscription": subscription,
        "location": location,
        "classifiers": sorted(
            variant["classifier"]
            for item in executions
            for variant in item["build"]["variants"]
        ),
        "batches": [
            [
                {
                    "id": item["id"],
                    "size": item["selectedMachine"]["name"],
                    "vcpus": item["selectedMachine"]["vcpus"],
                }
                for item in batch
            ]
            for batch in batches
        ],
    }, indent=2))


def status(args: argparse.Namespace) -> None:
    plan = load_plan(args.plan)
    subscription = subscription_id(args.subscription)
    location = azure_location(args.location)
    account = storage_account_name(subscription, location, args.storage_account)
    if args.run_id:
        run = get_json_blob(
            account, plan["artifactContainer"],
            f"{plan['artifactPrefix'].strip('/')}/{args.run_id}/run.json",
        )
        if run is None:
            raise RuntimeError(f"Azure run not found: {args.run_id}")
        print(json.dumps(run, indent=2))
        return
    blobs = az([
        "storage", "blob", "list",
        *blob_arguments(account),
        "--container-name", plan["artifactContainer"],
        "--prefix", plan["artifactPrefix"].strip("/") + "/",
    ])
    runs = sorted(
        item["name"] for item in blobs or [] if item["name"].endswith("/run.json")
    )
    print(json.dumps({"runs": runs}, indent=2))


def decode_worker_log(payload: bytes) -> str:
    if payload.startswith(b"\xef\xbb\xbf"):
        return payload.decode("utf-8-sig", errors="replace")
    if payload.startswith((b"\xff\xfe", b"\xfe\xff")):
        return payload.decode("utf-16", errors="replace")
    if payload and payload.count(b"\x00") > len(payload) // 4:
        return payload.decode("utf-16-le", errors="replace")
    return payload.decode("utf-8", errors="replace")


def logs(args: argparse.Namespace) -> None:
    plan = load_plan(args.plan)
    subscription = subscription_id(args.subscription)
    location = azure_location(args.location)
    account = storage_account_name(subscription, location, args.storage_account)
    name = (
        f"{plan['artifactPrefix'].strip('/')}/{args.run_id}/"
        f"{args.execution}/build.log"
    )
    with tempfile.NamedTemporaryFile(delete=False) as stream:
        path = Path(stream.name)
    try:
        download_blob(account, plan["artifactContainer"], name, path)
        content = decode_worker_log(path.read_bytes())
        if args.tail_lines is not None:
            if args.tail_lines < 1:
                raise ValueError("--tail-lines must be positive")
            content = "".join(content.splitlines(keepends=True)[-args.tail_lines:])
        sys.stdout.write(content)
    finally:
        path.unlink(missing_ok=True)


def collect(args: argparse.Namespace) -> None:
    plan = load_plan(args.plan)
    subscription = subscription_id(args.subscription)
    location = azure_location(args.location)
    account = storage_account_name(subscription, location, args.storage_account)
    run_name = f"{plan['artifactPrefix'].strip('/')}/{args.run_id}/run.json"
    run = get_json_blob(account, plan["artifactContainer"], run_name)
    if run is None:
        raise RuntimeError(f"Azure run not found: {args.run_id}")
    repository = collect_run(account, plan, run)
    run["mavenRepository"] = repository
    put_json_blob(account, plan["artifactContainer"], run_name, run)
    print(json.dumps(repository, indent=2))


def stop(args: argparse.Namespace) -> None:
    plan = load_plan(args.plan)
    subscription = subscription_id(args.subscription)
    location = azure_location(args.location)
    resource_group = args.resource_group or f"kompile-release-{location}"
    account = storage_account_name(subscription, location, args.storage_account)
    kill_blob = f"{plan['artifactPrefix'].strip('/')}/control/kill-switch.json"
    put_json_blob(
        account, plan["controlContainer"], kill_blob,
        {"enabled": True, "updatedAt": utc_now(), "reason": args.reason},
    )
    groups = az([
        "group", "list",
        "--tag", "kompile-release-managed=true",
    ])
    deleted = []
    for group in groups or []:
        name = str(group.get("name", ""))
        tags = group.get("tags") or {}
        if args.run_id and tags.get("kompile-run") != args.run_id:
            continue
        if name == resource_group:
            continue
        az(["group", "delete", "--name", name, "--yes", "--no-wait"],
           json_output=False)
        deleted.append(name)
    print(json.dumps({"killSwitch": True, "deletedComputeGroups": deleted}, indent=2))


def add_cloud_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--subscription")
    parser.add_argument("--location")
    parser.add_argument("--resource-group")
    parser.add_argument("--storage-account")


def add_selection_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--shard", action="append",
                        help="lane id or exact classifier; repeatable")
    parser.add_argument("--exclude-shard", action="append",
                        help="lane id or exact classifier to exclude; repeatable")
    parser.add_argument("--machine-type")
    parser.add_argument("--lane-machine", action="append",
                        metavar="LANE=AZURE_VM_SIZE")
    parser.add_argument("--max-cores", type=int)
    parser.add_argument("--max-total-cores", type=int)


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--plan", type=Path, default=DEFAULT_PLAN)
    sub = root.add_subparsers(dest="command", required=True)

    check = sub.add_parser("preflight")
    add_cloud_options(check)
    add_selection_options(check)
    check.set_defaults(func=preflight)

    launch = sub.add_parser("start")
    add_cloud_options(launch)
    add_selection_options(launch)
    launch.add_argument("--version", required=True)
    launch.add_argument("--snapshot-version", default="1.0.0-SNAPSHOT")
    source = launch.add_mutually_exclusive_group(required=True)
    source.add_argument("--commit")
    source.add_argument("--branch")
    launch.add_argument("--repository", default=DEFAULT_REPOSITORY)
    dl4j = launch.add_mutually_exclusive_group()
    dl4j.add_argument("--dl4j-commit")
    dl4j.add_argument("--dl4j-branch")
    launch.add_argument("--dl4j-maven-repository-url")
    launch.add_argument("--dl4j-repository", default=DEFAULT_DL4J_REPOSITORY)
    launch.add_argument("--dl4j-maven-repository-id", default="dl4j-release")
    launch.add_argument("--dl4j-maven-marker-url")
    launch.add_argument("--dl4j-sdk-assets-url")
    launch.add_argument("--run-id")
    launch.add_argument("--root-volume-gib", type=int)
    launch.add_argument("--timeout-hours", type=int, default=48)
    launch.add_argument("--windows-admin-password",
                        default=os.environ.get("AZURE_WINDOWS_ADMIN_PASSWORD"))
    launch.add_argument("--reset-kill-switch", action="store_true")
    launch.add_argument("--keep-resources", action="store_true")
    launch.add_argument("--no-auto-collect", dest="auto_collect",
                        action="store_false")
    launch.add_argument("--dry-run", action="store_true")
    launch.set_defaults(func=start, auto_collect=True)

    show = sub.add_parser("status")
    add_cloud_options(show)
    show.add_argument("--run-id")
    show.set_defaults(func=status)

    show_logs = sub.add_parser("logs")
    add_cloud_options(show_logs)
    show_logs.add_argument("--run-id", required=True)
    show_logs.add_argument("--execution", required=True)
    show_logs.add_argument("--tail-lines", type=int)
    show_logs.set_defaults(func=logs)

    gather = sub.add_parser("collect")
    add_cloud_options(gather)
    gather.add_argument("--run-id", required=True)
    gather.set_defaults(func=collect)

    emergency = sub.add_parser("stop")
    add_cloud_options(emergency)
    emergency.add_argument("--run-id")
    emergency.add_argument("--reason", default="operator requested stop")
    emergency.set_defaults(func=stop)
    return root


def main() -> None:
    args = parser().parse_args()
    plan = load_plan(args.plan)
    defaults = plan["defaults"]
    if hasattr(args, "max_cores"):
        args.max_cores = args.max_cores or defaults["maxCores"]
        args.max_total_cores = (
            args.max_total_cores or defaults["maxTotalCores"]
        )
        if args.max_cores < 1 or args.max_total_cores < 1:
            raise SystemExit("core limits must be positive")
    if hasattr(args, "root_volume_gib"):
        args.root_volume_gib = (
            args.root_volume_gib or defaults["rootVolumeGiB"]
        )
        if args.root_volume_gib < 64:
            raise SystemExit("--root-volume-gib must be at least 64")
    if hasattr(args, "timeout_hours") and args.timeout_hours < 1:
        raise SystemExit("--timeout-hours must be positive")
    args.func(args)


if __name__ == "__main__":
    main()
