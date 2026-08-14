#!/usr/bin/env python3
"""Run one immutable Kompile release shard outside GitHub Actions."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from typing import Any


def phase(name: str) -> None:
    print(f"::phase::{name}", flush=True)


def subprocess_command(command: list[str]) -> list[str]:
    executable = str(command[0]).lower()
    if os.name == "nt" and executable.endswith((".cmd", ".bat")):
        return [
            "cmd.exe", "/d", "/s", "/c", subprocess.list2cmdline(command),
        ]
    return command


def run(command: list[str], cwd: Path, env: dict[str, str] | None = None) -> None:
    print("+ " + " ".join(command), flush=True)
    subprocess.run(subprocess_command(command), cwd=cwd, env=env, check=True)


def maven() -> str:
    return "mvn.cmd" if os.name == "nt" else "mvn"


def native_image() -> str:
    return "native-image.cmd" if os.name == "nt" else "native-image"


def copy_tree_if_present(source: Path, target: Path) -> None:
    if source.exists():
        shutil.copytree(source, target, dirs_exist_ok=True)


def archive_directory(source: Path, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.suffix == ".zip":
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
            for item in sorted(source.rglob("*")):
                if item.is_file():
                    archive.write(item, item.relative_to(source))
    else:
        with tarfile.open(output, "w:gz") as archive:
            archive.add(source, arcname=".")


def safe_archive_member(name: str) -> Path:
    relative = Path(name.replace("\\", "/"))
    if relative.is_absolute() or ".." in relative.parts:
        raise RuntimeError(f"unsafe SDK archive member: {name}")
    return relative


def extract_sdk_archive(source: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    if zipfile.is_zipfile(source):
        with zipfile.ZipFile(source) as archive:
            for member in archive.infolist():
                relative = safe_archive_member(member.filename)
                if member.is_dir():
                    continue
                output = destination / relative
                output.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(member) as incoming, output.open("wb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing)
    elif tarfile.is_tarfile(source):
        with tarfile.open(source) as archive:
            for member in archive.getmembers():
                if not member.isfile():
                    continue
                relative = safe_archive_member(member.name)
                output = destination / relative
                output.parent.mkdir(parents=True, exist_ok=True)
                incoming = archive.extractfile(member)
                if incoming is None:
                    raise RuntimeError(f"unable to read SDK archive member: {member.name}")
                with incoming, output.open("wb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing)
    else:
        raise RuntimeError(f"unsupported DL4J SDK archive: {source}")


def validate_sdk_assets(root: Path, description: str) -> None:
    runtime_packages = [
        item for item in root.rglob("*")
        if item.is_file() and item.suffix.lower() in {".zip", ".aar"}
    ]
    platform_jars = list((root / "jars").glob("*.jar")) if (root / "jars").is_dir() else []
    if not runtime_packages or not platform_jars:
        raise RuntimeError(
            f"incomplete DL4J SDK assets from {description}: "
            f"runtime packages={len(runtime_packages)}, platform jars={len(platform_jars)}"
        )


def validate_dl4j_sdk_manifest(
    config: dict[str, Any],
    manifest: dict[str, Any],
    lane_id: str,
    variant: str,
    manifest_url: str,
) -> None:
    """Bind Azure SDK assets to the completion marker used by Maven."""
    if not str(config.get("dl4jRepositoryMarkerUrl", "")).strip():
        return
    expected = {
        "commit": config.get("dl4jCommit"),
        "releaseVersion": config.get("dl4jReleaseVersion"),
        "runId": config.get("dl4jRunId"),
    }
    mismatches = [
        key for key, value in expected.items()
        if value and manifest.get(key) != value
    ]
    shard = str(manifest.get("shard", ""))
    variants = {
        str(item) for item in manifest.get("variants", [])
    }
    if (
        mismatches
        or not (shard == lane_id or shard.startswith(lane_id + "--"))
        or variant not in variants
    ):
        raise RuntimeError(
            f"DL4J Azure SDK identity does not match its Maven completion marker "
            f"for {lane_id}--{variant}: {manifest_url}; "
            f"mismatches={mismatches}, shard={shard!r}, variants={sorted(variants)}"
        )


def download_dl4j_sdk_assets(
    config: dict[str, Any], lane_id: str, variant: str, destination: Path,
) -> None:
    template = str(config.get("dl4jSdkAssetsUrl", "")).strip()
    if not template:
        raise RuntimeError(
            "repository-backed distributions require --dl4j-sdk-assets-url; "
            "the URL must expose the DL4J sdk-assets archive and an adjacent .sha256 file"
        )
    values = {
        "lane": lane_id,
        "variant": variant,
        "platform": config["shard"]["build"]["javacppPlatform"],
        "version": config["snapshotVersion"],
        "snapshotVersion": config["snapshotVersion"],
    }
    try:
        url = template.format(**values)
    except KeyError as exc:
        raise RuntimeError(f"unknown DL4J SDK URL placeholder: {exc.args[0]}") from exc
    download = destination.parent / "dl4j-sdk-assets.archive"
    checksum = destination.parent / "dl4j-sdk-assets.archive.sha256"
    manifest_url = url.rsplit("/", 1)[0] + "/shard-manifest.json"
    manifest_path = destination.parent / "dl4j-shard-manifest.json"
    manifest: dict[str, Any] | None = None
    phase(f"download-dl4j-sdk-{variant}")
    urllib.request.urlretrieve(url, download)
    try:
        urllib.request.urlretrieve(url + ".sha256", checksum)
        expected = checksum.read_text(encoding="ascii").strip().split()[0].lower()
    except urllib.error.HTTPError as exc:
        if exc.code != 404:
            raise
        # DL4J's Azure worker attests the archive in the adjacent shard
        # manifest instead of emitting a standalone checksum sidecar.
        urllib.request.urlretrieve(manifest_url, manifest_path)
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        matches = [
            item for item in manifest.get("files", [])
            if str(item.get("path", "")).endswith("sdk-assets.tar.gz")
        ]
        if len(matches) != 1:
            raise RuntimeError(
                f"DL4J Azure shard manifest does not attest sdk-assets.tar.gz: "
                f"{manifest_url}"
            ) from exc
        expected = str(matches[0].get("sha256", "")).lower()
    if config.get("dl4jRepositoryMarkerUrl"):
        if manifest is None:
            urllib.request.urlretrieve(manifest_url, manifest_path)
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        validate_dl4j_sdk_manifest(
            config, manifest, lane_id, variant, manifest_url,
        )
    actual = sha256_file(download)
    if not re.fullmatch(r"[0-9a-f]{64}", expected) or expected != actual:
        raise RuntimeError(
            f"DL4J SDK archive checksum mismatch for {url}: "
            f"expected {expected!r}, calculated {actual}"
        )
    if manifest is not None:
        matches = [
            item for item in manifest.get("files", [])
            if str(item.get("path", "")).endswith("sdk-assets.tar.gz")
        ]
        if matches and matches[0].get("size") not in (None, download.stat().st_size):
            raise RuntimeError(
                f"DL4J SDK archive size does not match {manifest_url}"
            )
    extract_sdk_archive(download, destination)
    validate_sdk_assets(destination, url)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def latest_graalvm_community_version(java_major: str = "21") -> str:
    """Mirror setup-graalvm's graalvm-community major-version resolution."""
    request = urllib.request.Request(
        "https://api.github.com/repos/graalvm/graalvm-ce-builds/"
        f"git/matching-refs/tags/jdk-{java_major}",
        headers={"Accept": "application/vnd.github+json", "User-Agent": "kompile-release-builder"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        refs = json.loads(response.read())
    pattern = re.compile(rf"^refs/tags/jdk-({re.escape(java_major)}\.[0-9]+\.[0-9]+)$")
    versions = []
    for item in refs:
        match = pattern.fullmatch(str(item.get("ref", "")))
        if match:
            version = match.group(1)
            versions.append((tuple(int(part) for part in version.split(".")), version))
    if not versions:
        raise RuntimeError(f"no GraalVM Community JDK {java_major} release tag was found")
    return max(versions)[1]


def graalvm_environment(java_home: Path, distribution: str) -> dict[str, str]:
    env = os.environ.copy()
    env["GRAALVM_HOME"] = str(java_home)
    env["JAVA_HOME"] = str(java_home)
    env["KOMPILE_GRAALVM_DISTRIBUTION"] = distribution
    env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")
    # Keep the selected toolchain active for later build phases on this reusable host.
    os.environ.update({
        name: env[name]
        for name in ("GRAALVM_HOME", "JAVA_HOME", "KOMPILE_GRAALVM_DISTRIBUTION", "PATH")
    })
    return env


def ensure_graalvm(work: Path, architecture: str,
                   distribution: str = "graalvm-community") -> dict[str, str]:
    if distribution not in {"graalvm-community", "graalvm"}:
        raise ValueError(f"unsupported GraalVM distribution: {distribution}")
    installed_native_image = shutil.which(native_image())
    active_distribution = os.environ.get("KOMPILE_GRAALVM_DISTRIBUTION")
    if installed_native_image and active_distribution in {None, distribution}:
        java_home = Path(installed_native_image).resolve().parent.parent
        return graalvm_environment(java_home, distribution)

    work.mkdir(parents=True, exist_ok=True)
    marker = work / f"{distribution}.java-home"
    if marker.is_file():
        java_home = Path(marker.read_text(encoding="utf-8").strip())
        if shutil.which(native_image(), path=str(java_home / "bin")):
            return graalvm_environment(java_home, distribution)

    system = platform.system().lower()
    arch = "aarch64" if architecture == "arm64" else "x64"
    if system == "darwin":
        os_name, extension = "macos", "tar.gz"
    elif system == "windows":
        os_name, extension = "windows", "zip"
    else:
        os_name, extension = "linux", "tar.gz"

    if distribution == "graalvm-community":
        version = latest_graalvm_community_version("21")
        archive_name = f"graalvm-community-jdk-{version}_{os_name}-{arch}_bin.{extension}"
        url = (
            "https://github.com/graalvm/graalvm-ce-builds/releases/download/"
            f"jdk-{version}/{archive_name}"
        )
    else:
        archive_name = f"graalvm-jdk-21_{os_name}-{arch}_bin.{extension}"
        url = f"https://download.oracle.com/graalvm/21/latest/{archive_name}"

    download = work / f"{distribution}.{extension}"
    phase(f"bootstrap-{distribution}")
    urllib.request.urlretrieve(url, download)
    checksum = work / f"{distribution}.{extension}.sha256"
    urllib.request.urlretrieve(url + ".sha256", checksum)
    expected_checksum = checksum.read_text(encoding="ascii").strip().split()[0].lower()
    actual_checksum = sha256_file(download)
    if not re.fullmatch(r"[0-9a-f]{64}", expected_checksum) or actual_checksum != expected_checksum:
        raise RuntimeError(
            f"{distribution} archive checksum mismatch: expected {expected_checksum!r}, "
            f"calculated {actual_checksum}"
        )

    home = Path(tempfile.mkdtemp(prefix=f"{distribution}-", dir=work))
    if extension == "zip":
        with zipfile.ZipFile(download) as archive:
            archive.extractall(home)
    else:
        with tarfile.open(download) as archive:
            archive.extractall(home)
    candidates = [item for item in home.iterdir() if item.is_dir()]
    if len(candidates) != 1:
        raise RuntimeError(f"unexpected {distribution} archive layout: {candidates}")
    java_home = candidates[0]
    if system == "darwin" and (java_home / "Contents" / "Home").is_dir():
        java_home = java_home / "Contents" / "Home"
    marker.write_text(str(java_home), encoding="utf-8")
    env = graalvm_environment(java_home, distribution)
    run([native_image(), "--version"], work, env)
    return env


def ensure_dl4j_checkout(config: dict[str, Any], source: Path) -> Path:
    dl4j = source / "deeplearning4j"
    if not (dl4j / "pom.xml").exists():
        phase("checkout-deeplearning4j")
        run(["git", "init", str(dl4j)], source)
        run(["git", "remote", "add", "origin", config["dl4jRepository"]], dl4j)
        branch = str(config.get("dl4jBranch", "")).strip()
        if branch:
            branch_ref = f"refs/heads/{branch}"
            remote_ref = f"refs/remotes/origin/{branch}"
            run([
                "git", "fetch", "--depth=1", "origin",
                f"+{branch_ref}:{remote_ref}",
            ], dl4j)
            branch_commit = subprocess.run(
                ["git", "rev-parse", f"{remote_ref}^{{commit}}"], cwd=dl4j,
                text=True, capture_output=True, check=True,
            ).stdout.strip()
            if branch_commit != config["dl4jCommit"]:
                raise RuntimeError(
                    f"deeplearning4j branch {branch!r} resolved to {branch_commit}, "
                    f"expected {config['dl4jCommit']}"
                )
        else:
            run(["git", "fetch", "--depth=1", "origin", config["dl4jCommit"]], dl4j)
        run(["git", "checkout", "--detach", config["dl4jCommit"]], dl4j)
    actual = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=dl4j, text=True,
        capture_output=True, check=True,
    ).stdout.strip()
    if actual != config["dl4jCommit"]:
        raise RuntimeError(f"deeplearning4j commit mismatch: {actual}")
    return dl4j


def uses_prebuilt_dl4j(config: dict[str, Any]) -> bool:
    """Return whether Kompile should resolve DL4J from the configured Maven repository."""
    return bool(str(config.get("dl4jMavenRepositoryUrl", "")).strip())


def dl4j_maven_arguments(config: dict[str, Any]) -> list[str]:
    arguments = [f"-Dnd4j.version={config['snapshotVersion']}"]
    if uses_prebuilt_dl4j(config):
        arguments.extend([
            f"-Ddl4j.repository.id={config.get('dl4jMavenRepositoryId', 'dl4j-release')}",
            f"-Ddl4j.repository.url={config['dl4jMavenRepositoryUrl']}",
        ])
    return arguments


def configure_dl4j_environment(config: dict[str, Any], env: dict[str, str]) -> None:
    env["ND4J_VERSION"] = config["snapshotVersion"]
    if uses_prebuilt_dl4j(config):
        env["DL4J_MAVEN_REPOSITORY_URL"] = config["dl4jMavenRepositoryUrl"]
        env["DL4J_MAVEN_REPOSITORY_ID"] = config.get("dl4jMavenRepositoryId", "dl4j-release")


def hydrate_dl4j_sdk_jars(
    config: dict[str, Any],
    source: Path,
    repository: Path,
    destination: Path,
    classifier: str,
) -> None:
    """Replace companion SDK jars with artifacts from the configured Maven repo."""
    build = config["shard"]["build"]
    backend = str(build["backend"])
    platform_name = str(build["javacppPlatform"])
    if backend == "cpu":
        artifacts = ["nd4j-native", "nd4j-native-preset"]
        if platform_name in {"linux-x86_64", "windows-x86_64"}:
            artifacts.append("nd4j-native-platform")
        if platform_name in {
            "linux-x86_64", "windows-x86_64", "linux-arm64", "macosx-arm64",
        }:
            artifacts.extend([
                "libtokenizers", "tokenizers-native-preset", "tokenizers-native",
            ])
    elif backend == "cuda":
        cuda_version = str(build["cudaVersion"])
        base = f"nd4j-cuda-{cuda_version}"
        artifacts = [base, f"{base}-preset", f"{base}-platform"]
    else:
        raise RuntimeError(
            f"repository-backed SDK hydration is unsupported for backend {backend!r}"
        )

    coordinates = [(artifact, "") for artifact in artifacts]
    coordinates.extend([(artifacts[0], classifier), (artifacts[1], classifier)])
    jars = destination / "jars"
    shutil.rmtree(jars, ignore_errors=True)
    jars.mkdir(parents=True)
    phase(f"hydrate-dl4j-sdk-jars-{classifier}")
    for artifact_id, artifact_classifier in coordinates:
        coordinate = (
            f"org.eclipse.deeplearning4j:{artifact_id}:"
            f"{config['snapshotVersion']}:jar"
        )
        if artifact_classifier:
            coordinate += f":{artifact_classifier}"
        run([
            maven(), "--batch-mode", "--no-transfer-progress", "-U", "-N",
            "org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy",
            "-Dtransitive=false",
            f"-Dartifact={coordinate}",
            f"-DoutputDirectory={jars}",
            f"-Dmaven.repo.local={repository}",
            *dl4j_maven_arguments(config),
        ], source)
    if not any(jars.glob("*.jar")):
        raise RuntimeError(
            f"configured DL4J Maven repository produced no SDK jars for {classifier}"
        )


def stage_kompile_maven_artifacts(repository: Path, maven_output: Path) -> None:
    """Stage only Kompile coordinates for collector-side publication."""
    source = repository / "ai" / "kompile"
    if not source.is_dir():
        raise RuntimeError(f"Kompile Maven artifacts were not installed under {source}")
    destination = maven_output / "ai" / "kompile"
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source, destination, dirs_exist_ok=True)


def dl4j_lane_id(shard: dict[str, Any]) -> str:
    build = shard["build"]
    platform_name = build["javacppPlatform"]
    backend = build["backend"]
    if backend == "cuda":
        version = str(build["cudaVersion"]).replace(".", "-")
        prefix = "windows-x86_64" if shard["os"] == "windows" else "linux-x86_64"
        return f"{prefix}-cuda-{version}"
    return {
        "linux-x86_64": "linux-x86_64-cpu",
        "linux-arm64": "linux-arm64-cpu",
        "windows-x86_64": "windows-x86_64-cpu",
        "macosx-arm64": "macos-14-arm64-cpu",
    }[platform_name]


def run_dl4j_release_lane(config: dict[str, Any], source: Path, repository: Path,
                          maven_output: Path, assets: Path, lane_id: str,
                          variants: list[str] | None = None,
                          require_sdk: bool = True) -> None:
    """Delegate native SDK construction to the exact DL4J branch release driver."""
    dl4j = ensure_dl4j_checkout(config, source)
    driver = dl4j / "release" / "aws" / "build-platform.py"
    plan_path = dl4j / "release" / "aws" / "release-plan.json"
    if not driver.is_file() or not plan_path.is_file():
        raise RuntimeError(
            f"DL4J commit {config['dl4jCommit']} does not contain release/aws build infrastructure; "
            "select the tested DL4J release branch/commit"
        )
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    matches = [item for item in plan.get("shards", []) if item.get("id") == lane_id]
    if len(matches) != 1:
        raise RuntimeError(f"DL4J release plan does not define lane {lane_id!r}")
    lane = json.loads(json.dumps(matches[0]))
    if variants is not None:
        requested = set(variants)
        lane["build"]["variants"] = [
            item for item in lane["build"]["variants"] if item["name"] in requested
        ]
        found = {item["name"] for item in lane["build"]["variants"]}
        if found != requested:
            raise RuntimeError(
                f"DL4J lane {lane_id} does not define requested variants {sorted(requested - found)}"
            )
    lane["workloads"] = ["maven", "sdk"] if require_sdk else ["maven"]
    # Preserve the lane's artifactIds/classifiers: the upstream SDK packager
    # uses them to select jars/ and hard-fails an SDK workload if none remain.
    kompile_build = config["shard"]["build"]
    lane["build"]["buildThreads"] = int(kompile_build.get("buildThreads", 48))
    lane["build"]["mavenHeapGiB"] = int(kompile_build.get("mavenHeapGiB", 24))
    lane_config = {
        "runId": config["runId"],
        "releaseVersion": config["releaseVersion"],
        "snapshotVersion": config["snapshotVersion"],
        "commit": config["dl4jCommit"],
        "sourceBranch": None,
        "repository": config["dl4jRepository"],
        "shard": lane,
    }
    with tempfile.NamedTemporaryFile("w", suffix=".json", prefix="kompile-dl4j-",
                                     delete=False, encoding="utf-8") as stream:
        json.dump(lane_config, stream)
        config_path = Path(stream.name)
    try:
        run([
            sys.executable, str(driver), "--config", str(config_path),
            "--source", str(dl4j), "--repository", str(repository),
            "--maven-output", str(maven_output), "--sdk-output", str(assets),
        ], dl4j)
    finally:
        config_path.unlink(missing_ok=True)


def distribution_backend_lane(config: dict[str, Any], variant: str) -> tuple[str, list[str]] | None:
    platform_name = config["shard"]["build"]["javacppPlatform"]
    if variant in {"cli-only", "hosted"}:
        return None
    if variant == "cuda":
        return "linux-x86_64-cuda-12-9", ["base"]
    if variant == "amd-zluda":
        return "linux-x86_64-zluda", ["zluda"]
    cpu_lane = {
        "linux-x86_64": "linux-x86_64-cpu",
        "linux-arm64": "linux-arm64-cpu",
        "windows-x86_64": "windows-x86_64-cpu",
        "macosx-arm64": "macos-14-arm64-cpu",
    }[platform_name]
    return cpu_lane, ["avx2" if variant == "cpu-intel" else "base"]


def build_distribution(config: dict[str, Any], source: Path, repository: Path,
                       maven_output: Path, assets: Path) -> None:
    shard = config["shard"]
    version = config["releaseVersion"]
    env = ensure_graalvm(
        source / ".external-tools", shard["architecture"], "graalvm-community",
    )
    env["KOMPILE_MAVEN_REPO"] = str(repository)
    env["MAVEN_OPTS"] = f"-Xmx{shard['build'].get('mavenHeapGiB', 24)}g"
    configure_dl4j_environment(config, env)
    for variant_data in shard["build"]["variants"]:
        variant = variant_data["name"]
        prerequisite = distribution_backend_lane(config, variant)
        with tempfile.TemporaryDirectory(prefix=f"dl4j-sdk-{variant}-") as temporary:
            sdk_assets = Path(temporary) / "assets"
            command = [
                "bash", "./build-dist.sh", variant, "--version", version,
                "--platform", shard["build"]["javacppPlatform"],
                "--parallel", str(shard["build"].get("buildThreads", 48)),
                "--output-dir", str(assets),
            ]
            if prerequisite is not None:
                lane_id, variants = prerequisite
                if uses_prebuilt_dl4j(config):
                    download_dl4j_sdk_assets(config, lane_id, variant, sdk_assets)
                else:
                    run_dl4j_release_lane(
                        config, source, repository, maven_output, sdk_assets, lane_id, variants,
                    )
                    validate_sdk_assets(sdk_assets, f"DL4J source lane {lane_id}")
                env["KOMPILE_SDX_ASSETS_DIR"] = str(sdk_assets)
                command.extend(("--sdx-assets", str(sdk_assets)))
            else:
                env.pop("KOMPILE_SDX_ASSETS_DIR", None)
            phase(f"distribution-{variant}")
            run(command, source, env)
    stage_kompile_maven_artifacts(repository, maven_output)


def build_native_sdk(config: dict[str, Any], source: Path, repository: Path, maven_output: Path, assets: Path) -> None:
    shard = config["shard"]
    run_dl4j_release_lane(
        config, source, repository, maven_output, assets,
        dl4j_lane_id(shard), [item["name"] for item in shard["build"]["variants"]],
    )


def build_full_platform(config: dict[str, Any], source: Path, repository: Path,
                        maven_output: Path, assets: Path) -> None:
    """Build complete Kompile distributions for every selected DL4J classifier.

    The release controller keeps the Kompile source identity separate from its
    DL4J input identity. Source mode delegates the exact classifier to the
    selected DL4J commit's release driver. Repository mode resolves Maven
    coordinates from the configured repository and downloads the matching SDK
    companion archive only for classifiers that package a runtime SDK.
    """
    shard = config["shard"]
    build = shard["build"]
    env = ensure_graalvm(
        source / ".external-tools", shard["architecture"], "graalvm-community",
    )
    env["MAVEN_OPTS"] = f"-Xmx{build.get('mavenHeapGiB', 32)}g"
    env["MVN"] = maven()
    env["MAVEN_REPO_LOCAL"] = str(repository)
    env["KOMPILE_MAVEN_REPO"] = str(repository)
    env["BUILD_THREADS"] = str(build.get("buildThreads", 64))
    env["NATIVE_TARGETS"] = str(build.get("nativeTargets", "all"))
    env["KOMPILE_NATIVE_QUICK_BUILD"] = "0"
    configure_dl4j_environment(config, env)

    for variant in build["variants"]:
        classifier = str(variant["classifier"])
        lane_id = str(variant.get("dl4jLane", build["dl4jLane"]))
        dl4j_variant = str(variant.get("dl4jVariant", variant["name"]))
        require_sdk = bool(variant.get("requireSdk", build.get("requireSdk", True)))
        classifier_output = assets / classifier
        classifier_output.mkdir(parents=True, exist_ok=True)
        env["KOMPILE_OUTPUT_DIR"] = str(classifier_output)
        env["KOMPILE_SDX_OUTPUT_DIR"] = str(classifier_output / "sdx-sdk")

        with tempfile.TemporaryDirectory(prefix=f"dl4j-sdk-{classifier}-") as temporary:
            temporary_root = Path(temporary)
            sdk_assets = temporary_root / "assets"
            sdk_assets.mkdir(parents=True)
            if uses_prebuilt_dl4j(config):
                if require_sdk:
                    download_dl4j_sdk_assets(
                        config, lane_id, dl4j_variant, sdk_assets,
                    )
                    hydrate_dl4j_sdk_jars(
                        config, source, repository, sdk_assets, classifier,
                    )
            else:
                run_dl4j_release_lane(
                    config,
                    source,
                    repository,
                    temporary_root / "maven-output",
                    sdk_assets,
                    lane_id,
                    [dl4j_variant],
                    require_sdk=require_sdk,
                )
                if require_sdk:
                    validate_sdk_assets(
                        sdk_assets, f"DL4J source lane {lane_id}--{dl4j_variant}",
                    )

            command = [
                "bash", "./build-scripts/build-kompile-platform.sh", classifier,
                "--variant", "full",
                "--skip-dl4j",
                "--maven-repo-local", str(repository),
                "--nd4j-version", config["snapshotVersion"],
                "--version", config["releaseVersion"],
            ]
            if uses_prebuilt_dl4j(config):
                command.extend([
                    "--dl4j-repository", config["dl4jMavenRepositoryUrl"],
                    "--repository-id",
                    config.get("dl4jMavenRepositoryId", "dl4j-release"),
                ])
            if require_sdk:
                command.extend(["--dl4j-sdk-assets", str(sdk_assets)])
            if bool(variant.get("skipNative", build.get("skipNative", False))):
                command.append("--skip-native")
            phase(f"kompile-platform-{classifier}")
            run(command, source, env)

    stage_kompile_maven_artifacts(repository, maven_output)


def build_kompile_native(config: dict[str, Any], source: Path, repository: Path,
                         maven_output: Path, assets: Path) -> None:
    shard = config["shard"]
    env = ensure_graalvm(
        source / ".external-tools", shard["architecture"], "graalvm",
    )
    env["MAVEN_OPTS"] = f"-Xmx{shard['build'].get('mavenHeapGiB', 24)}g"
    configure_dl4j_environment(config, env)
    common = [
        f"-Dmaven.repo.local={repository}",
        f"-Dkompile.backend={shard['build']['backend']}",
        f"-Djavacpp.platform={shard['build']['javacppPlatform']}",
        *dl4j_maven_arguments(config),
        "--no-transfer-progress", "--batch-mode",
    ]
    phase("kompile-java")
    run([maven(), "clean", "install", "-DskipTests", "-Dskip.ui", *common], source, env)
    phase("kompile-native")
    modules = [
        "kompile-cli/kompile-cli-main",
        "kompile-cli/kompile-agent-cli",
        "kompile-cli/kompile-app-cli",
        "kompile-cli/kompile-model-cli",
        "kompile-cli/kompile-component-cli",
    ]
    for module in modules:
        module_path = source / module
        if (module_path / "pom.xml").exists():
            run([maven(), "package", "-Dkompile.dist=true", "-DskipTests",
                 *common], module_path, env)
    expected_binaries = {
        "kompile-cli/kompile-cli-main": "kompile-cli-main",
        "kompile-cli/kompile-agent-cli": "kompile-agent",
        "kompile-cli/kompile-app-cli": "kompile-app-cli",
        "kompile-cli/kompile-model-cli": "kompile-model",
        "kompile-cli/kompile-component-cli": "kompile-component",
    }
    executable_suffix = ".exe" if shard["os"] == "windows" else ""
    staging = Path(tempfile.mkdtemp(prefix="kompile-native-"))
    missing = []
    for module, binary_name in expected_binaries.items():
        binary = source / module / "target" / f"{binary_name}{executable_suffix}"
        if not binary.is_file():
            missing.append(str(binary.relative_to(source)))
            continue
        shutil.copy2(binary, staging / binary.name)
    if missing:
        raise RuntimeError(f"missing Kompile native binaries: {', '.join(missing)}")
    archive_directory(
        staging,
        assets / f"kompile-{config['releaseVersion']}-{shard['build']['javacppPlatform']}.zip",
    )
    stage_kompile_maven_artifacts(repository, maven_output)


def ensure_android_sdk(work: Path) -> dict[str, str]:
    env = os.environ.copy()
    sdk = Path(env.get("ANDROID_HOME", work / "android-sdk"))
    manager = next(iter(sdk.rglob("sdkmanager")), None) if sdk.exists() else None
    if manager is None:
        archive = work / "android-tools.zip"
        urllib.request.urlretrieve("https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip", archive)
        with zipfile.ZipFile(archive) as contents:
            contents.extractall(sdk / "cmdline-tools")
        extracted = sdk / "cmdline-tools" / "cmdline-tools"
        latest = sdk / "cmdline-tools" / "latest"
        latest.parent.mkdir(parents=True, exist_ok=True)
        extracted.rename(latest)
        manager = latest / "bin" / "sdkmanager"
    env["ANDROID_HOME"] = str(sdk)
    env["ANDROID_SDK_ROOT"] = str(sdk)
    subprocess.run([str(manager), "--licenses"], input="y\n" * 20, text=True, check=False, env=env)
    run([str(manager), "platform-tools", "platforms;android-35", "build-tools;35.0.0", "ndk;27.0.12077973"], work, env)
    return env


def build_android(config: dict[str, Any], source: Path, repository: Path, assets: Path) -> None:
    android = source / "kompile-chat-local" / "mobile" / "android"
    env = ensure_android_sdk(source / ".external-tools")
    env["KOMPILE_MAVEN_REPO"] = str(repository)
    java17 = next(iter(Path("/usr/lib/jvm").glob("java-17-openjdk-*")), None)
    if java17 is not None:
        env["JAVA_HOME"] = str(java17)
        env["PATH"] = str(java17 / "bin") + os.pathsep + env.get("PATH", "")
    common = [
        maven(), "-B", "-q", "-DskipTests", "-Dkompile.skip.native=true",
        f"-Dmaven.repo.local={repository}", "-Dkompile.backend=cpu",
        f"-Djavacpp.platform={config['shard']['build']['javacppPlatform']}",
        *dl4j_maven_arguments(config), "-f", "pom.xml", "install",
    ]
    phase("android-maven-parents")
    for modules in (
        "kompile-bom,kompile-parent",
        "kompile-utils",
        "kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning",
        "kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local",
        "kompile-chat-local/kompile-chat-local-core",
    ):
        run([*common, "-pl", modules, "--also-make"], source, env)
    phase("android-debug-apk")
    wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
    if os.name != "nt":
        (android / "gradlew").chmod(0o755)
    run([wrapper, ":app:assembleDebug", "--no-daemon", "--stacktrace"], android, env)
    apk = android / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    if not apk.is_file():
        raise RuntimeError("GitHub Actions-compatible Android debug APK was not produced")
    shutil.copy2(apk, assets / f"kompile-chat-local-{config['releaseVersion']}-debug.apk")


def write_checksums(root: Path) -> None:
    records = []
    primary_files = [
        item for item in sorted(root.rglob("*"))
        if item.is_file() and not item.name.endswith(".sha256") and item.name != "artifacts.json"
    ]
    for item in primary_files:
            digest = hashlib.sha256(item.read_bytes()).hexdigest()
            (item.parent / f"{item.name}.sha256").write_text(f"{digest}  {item.name}\n", encoding="ascii")
            records.append({"path": item.relative_to(root).as_posix(), "sha256": digest, "size": item.stat().st_size})
    (root / "artifacts.json").write_text(json.dumps(records, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--maven-output", type=Path, required=True)
    parser.add_argument("--sdk-output", type=Path, required=True)
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    for directory in (args.repository, args.maven_output, args.sdk_output):
        directory.mkdir(parents=True, exist_ok=True)
    kind = config["shard"]["build"]["kind"]
    if kind == "distribution":
        build_distribution(config, args.source, args.repository, args.maven_output, args.sdk_output)
    elif kind == "native-sdk":
        build_native_sdk(config, args.source, args.repository, args.maven_output, args.sdk_output)
    elif kind == "platform":
        build_full_platform(
            config, args.source, args.repository, args.maven_output, args.sdk_output,
        )
    elif kind == "macos-all":
        original = config["shard"]["build"]
        distribution_config = json.loads(json.dumps(config))
        distribution_config["shard"]["build"] = {**original, "kind": "distribution", "variants": [{"name": "cli-only"}]}
        build_distribution(distribution_config, args.source, args.repository, args.maven_output, args.sdk_output)
        native_config = json.loads(json.dumps(config))
        native_config["shard"]["build"] = {**original, "kind": "native-sdk", "variants": original["nativeVariants"]}
        build_native_sdk(native_config, args.source, args.repository, args.maven_output, args.sdk_output)
        build_kompile_native(
            config, args.source, args.repository, args.maven_output, args.sdk_output,
        )
    elif kind == "kompile-native":
        if not uses_prebuilt_dl4j(config):
            run_dl4j_release_lane(
                config, args.source, args.repository, args.maven_output, args.sdk_output,
                dl4j_lane_id(config["shard"]), ["base"],
            )
        build_kompile_native(
            config, args.source, args.repository, args.maven_output, args.sdk_output,
        )
    elif kind == "android":
        build_android(config, args.source, args.repository, args.sdk_output)
    else:
        raise ValueError(f"unsupported build kind: {kind}")
    write_checksums(args.sdk_output)
    phase("complete")


if __name__ == "__main__":
    main()
