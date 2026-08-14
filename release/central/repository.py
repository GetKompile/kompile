#!/usr/bin/env python3
"""Merge, validate, sign, bundle, and publish prebuilt Kompile/DL4J Maven shards."""

from __future__ import annotations

import argparse
import base64
from datetime import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from typing import Iterable

PRIMARY_SUFFIXES = (".pom", ".jar", ".aar", ".war", ".zip", ".tar.gz", ".module")
CHECKSUMS = {"md5": hashlib.md5, "sha1": hashlib.sha1, "sha256": hashlib.sha256, "sha512": hashlib.sha512}  # nosec: Central requires MD5/SHA1 metadata
DERIVED_SUFFIXES = (".asc", ".md5", ".sha1", ".sha256", ".sha512")
MAX_BUNDLE_BYTES = 1_000_000_000
MAVEN_METADATA_NAMESPACE = "http://maven.apache.org/METADATA/1.1.0"
MAVEN_METADATA_SCHEMA = "https://maven.apache.org/xsd/repository-metadata-1.1.0.xsd"
XML_SCHEMA_INSTANCE_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance"


def artifact_suffix(path: Path, suffixes: Iterable[str] = PRIMARY_SUFFIXES) -> str | None:
    """Return the longest recognized Maven extension, including compound types."""
    return next(
        (
            suffix for suffix in sorted(suffixes, key=len, reverse=True)
            if path.name.endswith(suffix)
        ),
        None,
    )


def digest(path: Path, algorithm: str = "sha256") -> str:
    result = CHECKSUMS[algorithm]()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def safe_relative(path: str) -> Path:
    relative = Path(path)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError(f"unsafe archive member: {path}")
    return relative


def extract_input(source: Path, destination: Path) -> Path:
    if source.is_dir():
        return source
    target = destination / source.name.replace(".", "-")
    target.mkdir(parents=True, exist_ok=True)
    if zipfile.is_zipfile(source):
        with zipfile.ZipFile(source) as archive:
            for member in archive.infolist():
                relative = safe_relative(member.filename)
                if member.is_dir():
                    continue
                output = target / relative
                output.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(member) as incoming, output.open("wb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing)
    elif tarfile.is_tarfile(source):
        with tarfile.open(source) as archive:
            for member in archive.getmembers():
                if not member.isfile():
                    continue
                relative = safe_relative(member.name)
                output = target / relative
                output.parent.mkdir(parents=True, exist_ok=True)
                incoming = archive.extractfile(member)
                if incoming is None:
                    raise ValueError(f"unable to read archive member: {member.name}")
                with incoming, output.open("wb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing)
    else:
        raise ValueError(f"unsupported shard input: {source}")
    return target


def repository_files(root: Path, *, include_derived: bool = True) -> Iterable[Path]:
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        if relative.name in {"shard-manifest.json", "release-build-manifest.json"}:
            continue
        if not include_derived and (
            relative.name == "maven-metadata.xml"
            or relative.name.endswith(DERIVED_SUFFIXES)
        ):
            continue
        is_kompile = relative.parts[:2] == ("ai", "kompile")
        is_eclipse_dl4j = relative.parts[:3] == ("org", "eclipse", "deeplearning4j")
        is_legacy_nd4j = relative.parts[:2] == ("org", "nd4j")
        if not is_kompile and not is_eclipse_dl4j and not is_legacy_nd4j:
            continue
        yield path


def merge(inputs: list[Path], output: Path, manifest_path: Path, release_version: str, commit: str) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    ownership: dict[str, list[str]] = {}
    with tempfile.TemporaryDirectory(prefix="kompile-central-merge-") as temporary:
        scratch = Path(temporary)
        for source in inputs:
            root = extract_input(source, scratch)
            # Metadata, checksums, and signatures are derived publication files.
            # Historical shards may contain different generations of them even
            # when their primary Maven artifacts are compatible. Regenerate them
            # after the strict byte-for-byte primary-artifact merge.
            candidates = list(repository_files(root, include_derived=False))
            if not candidates:
                raise ValueError(f"Maven shard has no supported repository files: {source}")
            for path in candidates:
                relative = path.relative_to(root)
                destination = output / relative
                key = relative.as_posix()
                ownership.setdefault(key, []).append(source.name)
                if destination.exists():
                    if digest(destination) != digest(path):
                        raise ValueError(f"conflicting duplicate Maven path {key} from {source}")
                    continue
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, destination)
    files = [{"path": path.relative_to(output).as_posix(), "sha256": digest(path), "size": path.stat().st_size, "shards": ownership[path.relative_to(output).as_posix()]} for path in repository_files(output)]
    manifest = {"schemaVersion": 1, "releaseVersion": release_version, "commit": commit, "workloads": ["maven", "sdk"], "files": files}
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    Path(str(manifest_path) + ".sha256").write_text(f"{digest(manifest_path)}  {manifest_path.name}\n", encoding="ascii")
    return manifest


def verify_release_assets(directory: Path, manifest_path: Path, version: str, commit: str) -> None:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("releaseVersion") != version:
        raise ValueError(f"release version mismatch: {manifest.get('releaseVersion')} != {version}")
    if manifest.get("commit") != commit:
        raise ValueError(f"commit mismatch: {manifest.get('commit')} != {commit}")
    if set(manifest.get("workloads", [])) != {"maven", "sdk"}:
        raise ValueError("release manifest must attest both maven and sdk workloads")
    assets = manifest.get("assets", [])
    expected: dict[str, dict] = {}
    for item in assets:
        name = item.get("fileName", "")
        if not name or Path(name).name != name or name in expected:
            raise ValueError(f"invalid or duplicate release asset name: {name!r}")
        expected[name] = item
    maven_assets = [name for name in expected if name.startswith("maven-repository-") and name.endswith(".tar.gz")]
    sdk_assets = [name for name in expected if name.startswith("sdk-assets-") and name.endswith(".tar.gz")]
    if not maven_assets or not sdk_assets:
        raise ValueError("release manifest must contain both Maven repository and SDK asset archives")
    for name, item in expected.items():
        path = directory / name
        if not path.is_file():
            raise ValueError(f"release asset is missing: {name}")
        if digest(path) != item.get("sha256"):
            raise ValueError(f"release asset checksum mismatch: {name}")
        if path.stat().st_size != item.get("size"):
            raise ValueError(f"release asset size mismatch: {name}")


def verify(repository: Path, manifest_path: Path | None, version: str | None, commit: str | None) -> None:
    if manifest_path:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if version and manifest.get("releaseVersion") != version:
            raise ValueError(f"release version mismatch: {manifest.get('releaseVersion')} != {version}")
        if commit and manifest.get("commit") != commit:
            raise ValueError(f"commit mismatch: {manifest.get('commit')} != {commit}")
        expected = {
            item["path"]: (item["sha256"], item["size"])
            for item in manifest.get("files", [])
        }
        actual = {
            path.relative_to(repository).as_posix(): (
                digest(path),
                path.stat().st_size,
            )
            for path in repository_files(repository)
        }
        if expected != actual:
            missing = sorted(set(expected) - set(actual))
            extra = sorted(set(actual) - set(expected))
            changed = sorted(
                path
                for path in expected.keys() & actual.keys()
                if expected[path] != actual[path]
            )
            raise ValueError(
                f"manifest mismatch; missing={missing}, extra={extra}, changed={changed}"
            )
    publishable_files = list(repository_files(repository))
    has_kompile = any(
        path.relative_to(repository).parts[:2] == ("ai", "kompile")
        for path in publishable_files
    )
    version_dirs: dict[Path, list[Path]] = {}
    for path in publishable_files:
        if path.name.endswith((".asc", ".md5", ".sha1", ".sha256", ".sha512")):
            continue
        if path.name == "maven-metadata.xml":
            continue
        relative = path.relative_to(repository)
        validate_release_version = (
            not has_kompile or relative.parts[:2] == ("ai", "kompile")
        )
        if version and validate_release_version and version not in path.parts:
            raise ValueError(f"unexpected version path in repository: {relative}")
        version_dirs.setdefault(path.parent, []).append(path)
    if not version_dirs:
        raise ValueError("repository contains no publishable components")
    for directory, files in version_dirs.items():
        suffixes = {path.suffix for path in files}
        if ".pom" not in suffixes:
            raise ValueError(f"component is missing its POM: {directory.relative_to(repository)}")
        jars = [path for path in files if path.suffix == ".jar"]
        non_metadata_jars = [path for path in jars if not path.name.endswith(("-sources.jar", "-javadoc.jar"))]
        if jars and not non_metadata_jars:
            raise ValueError(f"component has no main/classifier JAR: {directory.relative_to(repository)}")


def primary_files(repository: Path) -> list[Path]:
    return [
        path
        for path in repository_files(repository)
        if artifact_suffix(path) is not None and not path.name.endswith(".asc")
    ]


def write_checksums(paths: Iterable[Path]) -> None:
    for path in paths:
        for algorithm in CHECKSUMS:
            Path(str(path) + f".{algorithm}").write_text(
                digest(path, algorithm) + "\n", encoding="ascii"
            )


def metadata_element() -> ET.Element:
    ET.register_namespace("", MAVEN_METADATA_NAMESPACE)
    ET.register_namespace("xsi", XML_SCHEMA_INSTANCE_NAMESPACE)
    return ET.Element(
        f"{{{MAVEN_METADATA_NAMESPACE}}}metadata",
        {
            "modelVersion": "1.1.0",
            f"{{{XML_SCHEMA_INSTANCE_NAMESPACE}}}schemaLocation": (
                f"{MAVEN_METADATA_NAMESPACE} {MAVEN_METADATA_SCHEMA}"
            ),
        },
    )


def metadata_child(parent: ET.Element, name: str, value: str | None = None) -> ET.Element:
    child = ET.SubElement(parent, f"{{{MAVEN_METADATA_NAMESPACE}}}{name}")
    child.text = value
    return child


def write_metadata_xml(path: Path, root: ET.Element) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(root, space="  ")
    ET.ElementTree(root).write(
        path, encoding="utf-8", xml_declaration=True, short_empty_elements=True
    )


def snapshot_file_identity(
    path: Path, artifact_id: str, release_version: str
) -> tuple[str, str | None]:
    suffix = artifact_suffix(path)
    if suffix is None:
        raise ValueError(f"unsupported Maven artifact extension: {path.name}")
    extension = suffix.removeprefix(".")
    stem = path.name[:-len(suffix)]
    base_name = f"{artifact_id}-{release_version}"
    if stem == base_name:
        return extension, None
    classifier_prefix = base_name + "-"
    if not stem.startswith(classifier_prefix):
        raise ValueError(
            f"Maven artifact name does not match its coordinates: {path.name}"
        )
    classifier = stem[len(classifier_prefix) :]
    if not classifier:
        raise ValueError(f"Maven artifact has an empty classifier: {path.name}")
    return extension, classifier


def write_maven_metadata(
    repository: Path, release_version: str, updated: str | None = None
) -> list[Path]:
    """Write Maven A-level and snapshot V-level metadata with stable filenames."""
    updated = updated or time.strftime("%Y%m%d%H%M%S", time.gmtime())
    try:
        if len(updated) != 14 or not updated.isdigit():
            raise ValueError
        datetime.strptime(updated, "%Y%m%d%H%M%S")
    except ValueError as exc:
        raise ValueError(
            "Maven metadata timestamp must use UTC yyyyMMddHHmmss"
        ) from exc

    primary = primary_files(repository)
    has_kompile = any(
        path.relative_to(repository).parts[:2] == ("ai", "kompile")
        for path in primary
    )
    components: dict[tuple[str, str, Path], list[Path]] = {}
    for path in primary:
        version_dir = path.parent
        relative = path.relative_to(repository)
        validate_release_version = (
            not has_kompile or relative.parts[:2] == ("ai", "kompile")
        )
        if validate_release_version and version_dir.name != release_version:
            raise ValueError(
                f"unexpected Maven version directory: {version_dir.relative_to(repository)}"
            )
        artifact_dir = version_dir.parent
        relative_artifact = artifact_dir.relative_to(repository)
        if len(relative_artifact.parts) < 2:
            raise ValueError(
                f"invalid Maven coordinate path: {relative_artifact.as_posix()}"
            )
        group_id = ".".join(relative_artifact.parts[:-1])
        artifact_id = relative_artifact.parts[-1]
        components.setdefault((group_id, artifact_id, version_dir), []).append(path)
    if not components:
        raise ValueError("repository contains no components for Maven metadata")

    metadata_paths: list[Path] = []
    for (group_id, artifact_id, version_dir), artifacts in sorted(
        components.items(), key=lambda item: (item[0][0], item[0][1])
    ):
        component_version = version_dir.name
        artifact_metadata = metadata_element()
        metadata_child(artifact_metadata, "groupId", group_id)
        metadata_child(artifact_metadata, "artifactId", artifact_id)
        artifact_versioning = metadata_child(artifact_metadata, "versioning")
        metadata_child(artifact_versioning, "latest", component_version)
        if not component_version.endswith("-SNAPSHOT"):
            metadata_child(artifact_versioning, "release", component_version)
        versions = metadata_child(artifact_versioning, "versions")
        metadata_child(versions, "version", component_version)
        metadata_child(artifact_versioning, "lastUpdated", updated)
        artifact_metadata_path = version_dir.parent / "maven-metadata.xml"
        write_metadata_xml(artifact_metadata_path, artifact_metadata)
        metadata_paths.append(artifact_metadata_path)

        if component_version.endswith("-SNAPSHOT"):
            version_metadata = metadata_element()
            metadata_child(version_metadata, "groupId", group_id)
            metadata_child(version_metadata, "artifactId", artifact_id)
            metadata_child(version_metadata, "version", component_version)
            versioning = metadata_child(version_metadata, "versioning")
            snapshot = metadata_child(versioning, "snapshot")
            metadata_child(snapshot, "localCopy", "true")
            metadata_child(versioning, "lastUpdated", updated)
            snapshot_versions = metadata_child(versioning, "snapshotVersions")
            identities: set[tuple[str, str | None]] = set()
            for path in sorted(artifacts):
                extension, classifier = snapshot_file_identity(
                    path, artifact_id, component_version
                )
                identity = (extension, classifier)
                if identity in identities:
                    raise ValueError(
                        "duplicate Maven snapshot extension/classifier for "
                        f"{group_id}:{artifact_id}:{component_version}: {identity}"
                    )
                identities.add(identity)
                snapshot_version = metadata_child(
                    snapshot_versions, "snapshotVersion"
                )
                if classifier is not None:
                    metadata_child(snapshot_version, "classifier", classifier)
                metadata_child(snapshot_version, "extension", extension)
                metadata_child(snapshot_version, "value", component_version)
                metadata_child(snapshot_version, "updated", updated)
            version_metadata_path = version_dir / "maven-metadata.xml"
            write_metadata_xml(version_metadata_path, version_metadata)
            metadata_paths.append(version_metadata_path)
    return metadata_paths


def materialize_test_repository(
    inputs: list[Path],
    output: Path,
    manifest_path: Path,
    release_version: str,
    commit: str | None,
    *,
    metadata_updated: str | None = None,
    provenance: dict | None = None,
) -> dict:
    """Merge shards into a checksum-complete, remote-consumable Maven 2 layout."""
    provenance_fields: dict = {}
    merge_commit = commit
    if provenance is not None:
        if provenance.get("releaseVersion") != release_version:
            raise ValueError("repository provenance release version mismatch")
        sources = provenance.get("sources")
        if not isinstance(sources, list) or not sources or any(
            not isinstance(item, dict) for item in sources
        ):
            raise ValueError("repository provenance requires a non-empty sources list")
        source_identities: set[tuple[str, str]] = set()
        source_commits: set[str] = set()
        normalized_sources: list[dict] = []
        for source in sources:
            run_id = source.get("runId")
            shard = source.get("shard")
            source_commit = source.get("commit")
            sha256 = source.get("sha256")
            size = source.get("size")
            if (
                not isinstance(run_id, str)
                or not run_id
                or not isinstance(shard, str)
                or not shard
                or not isinstance(source_commit, str)
                or not re.fullmatch(r"[0-9a-f]{40}", source_commit)
                or not isinstance(sha256, str)
                or not re.fullmatch(r"[0-9a-f]{64}", sha256)
                or not isinstance(size, int)
                or size < 0
            ):
                raise ValueError("repository provenance contains an invalid source")
            identity = (run_id, shard)
            if identity in source_identities:
                raise ValueError(f"duplicate repository provenance source: {run_id}:{shard}")
            source_identities.add(identity)
            source_commits.add(source_commit)
            normalized_sources.append(source)
        target_commit = provenance.get("targetCommit")
        if not isinstance(target_commit, str) or not re.fullmatch(
            r"[0-9a-f]{40}", target_commit
        ):
            raise ValueError("repository provenance targetCommit must be a full Git SHA")
        declared_commits = provenance.get("sourceCommits")
        if declared_commits is not None and declared_commits != sorted(source_commits):
            raise ValueError("repository provenance sourceCommits do not match sources")
        merge_commit = target_commit
        provenance_fields = {
            "schemaVersion": 2,
            "targetCommit": target_commit,
            "sourceCommits": sorted(source_commits),
            "sources": sorted(
                normalized_sources, key=lambda item: (item["runId"], item["shard"])
            ),
        }
    elif not commit:
        raise ValueError("repository materialization requires commit or provenance")
    scratch_manifest = manifest_path.with_name(manifest_path.name + ".merge")
    merge(inputs, output, scratch_manifest, release_version, merge_commit)
    verify(output, scratch_manifest, release_version, merge_commit)
    metadata_paths = write_maven_metadata(
        output, release_version, updated=metadata_updated
    )
    write_checksums([*primary_files(output), *metadata_paths])
    files = [
        {
            "path": path.relative_to(output).as_posix(),
            "sha256": digest(path),
            "size": path.stat().st_size,
        }
        for path in repository_files(output)
    ]
    manifest = {
        "schemaVersion": 1,
        "layout": "maven2",
        "releaseVersion": release_version,
        "files": files,
        **provenance_fields,
    }
    if provenance is None:
        manifest["commit"] = commit
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    Path(str(manifest_path) + ".sha256").write_text(
        f"{digest(manifest_path)}  {manifest_path.name}\n", encoding="ascii"
    )
    verify(output, manifest_path, release_version, commit if provenance is None else None)
    scratch_manifest.unlink(missing_ok=True)
    Path(str(scratch_manifest) + ".sha256").unlink(missing_ok=True)
    return manifest


def sign_bundle(repository: Path, output: Path, gpg_executable: str = "gpg") -> None:
    for path in primary_files(repository):
        signature = Path(str(path) + ".asc")
        command = [gpg_executable, "--batch", "--yes", "--armor", "--detach-sign", "--output", str(signature)]
        passphrase = os.environ.get("MAVEN_GPG_PASSPHRASE")
        input_bytes = None
        if passphrase:
            command.extend(["--pinentry-mode", "loopback", "--passphrase-fd", "0"])
            input_bytes = (passphrase + "\n").encode()
        subprocess.run([*command, str(path)], input=input_bytes, check=True)
        for algorithm in CHECKSUMS:
            Path(str(path) + f".{algorithm}").write_text(digest(path, algorithm) + "\n", encoding="ascii")
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=True) as archive:
        for path in sorted(repository.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(repository).as_posix())
    if output.stat().st_size >= MAX_BUNDLE_BYTES:
        output.unlink()
        raise ValueError("Central bundle is 1 GB or larger; it cannot be uploaded as one deployment")


def snapshot_deploy_commands(
    repository: Path,
    version: str,
    repository_id: str,
    url: str,
    maven_executable: str = "mvn",
) -> list[list[str]]:
    """Create deploy-file commands for a prebuilt snapshot repository without rebuilding it."""
    if not version.endswith("-SNAPSHOT"):
        raise ValueError(f"snapshot publication requires a -SNAPSHOT version: {version}")
    commands: list[list[str]] = []
    supported = (".jar", ".aar", ".war", ".zip", ".tar.gz")
    for pom in sorted(repository.rglob(f"*-{version}.pom")):
        if pom.parent.name != version:
            continue
        artifact_id = pom.parent.parent.name
        base = f"{artifact_id}-{version}"
        main_files = [pom.parent / f"{base}{suffix}" for suffix in supported]
        main = next((path for path in main_files if path.is_file()), pom)
        main_suffix = artifact_suffix(main, supported) if main != pom else None
        packaging = main_suffix.lstrip(".") if main_suffix else "pom"
        attachments: list[tuple[Path, str, str]] = []
        unsupported = []
        for path in sorted(pom.parent.iterdir()):
            if not path.is_file() or path in {pom, main}:
                continue
            if path.name.endswith((".asc", ".md5", ".sha1", ".sha256", ".sha512")):
                continue
            suffix = artifact_suffix(path, supported)
            if suffix is None or not path.name.startswith(base + "-"):
                if artifact_suffix(path) is not None:
                    unsupported.append(path.name)
                continue
            classifier = path.name[len(base) + 1:-len(suffix)]
            if not classifier:
                unsupported.append(path.name)
                continue
            attachments.append((path, classifier, suffix.lstrip(".")))
        if unsupported:
            raise ValueError(f"snapshot component {artifact_id} has unsupported prebuilt files: {unsupported}")
        command = [
            maven_executable, "--batch-mode",
            "org.apache.maven.plugins:maven-deploy-plugin:3.1.4:deploy-file",
            f"-DrepositoryId={repository_id}", f"-Durl={url}",
            f"-Dfile={main}", f"-DpomFile={pom}", f"-Dpackaging={packaging}",
            "-DgeneratePom=false", "-DretryFailedDeploymentCount=3",
        ]
        if attachments:
            command.extend((
                "-Dfiles=" + ",".join(str(item[0]) for item in attachments),
                "-Dclassifiers=" + ",".join(item[1] for item in attachments),
                "-Dtypes=" + ",".join(item[2] for item in attachments),
            ))
        commands.append(command)
    if not commands:
        raise ValueError(f"repository contains no deployable {version} snapshot components")
    return commands


def deploy_snapshot(repository: Path, version: str, repository_id: str, url: str, maven_executable: str = "mvn") -> None:
    for index, command in enumerate(snapshot_deploy_commands(repository, version, repository_id, url, maven_executable), start=1):
        print(json.dumps({"snapshotComponent": index, "command": command}), flush=True)
        subprocess.run(command, check=True)


def authorization(username: str, password: str) -> str:
    token = base64.b64encode(f"{username}:{password}".encode()).decode()
    return f"Bearer {token}"


def request(url: str, auth: str, data: bytes | None = None, method: str = "POST", content_type: str | None = None) -> bytes:
    headers = {"Authorization": auth, "Accept": "application/json"}
    if content_type:
        headers["Content-Type"] = content_type
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=120) as response:
        return response.read()


def upload(bundle: Path, username: str, password: str, automatic: bool, wait_seconds: int) -> str:
    boundary = f"----dl4j-{uuid.uuid4().hex}"
    payload = b"".join((
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"{bundle.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n".encode(),
        bundle.read_bytes(),
        f"\r\n--{boundary}--\r\n".encode(),
    ))
    publishing_type = "AUTOMATIC" if automatic else "USER_MANAGED"
    url = "https://central.sonatype.com/api/v1/publisher/upload?" + urllib.parse.urlencode({"name": bundle.stem, "publishingType": publishing_type})
    auth = authorization(username, password)
    deployment_id = request(url, auth, payload, content_type=f"multipart/form-data; boundary={boundary}").decode().strip()
    deadline = time.time() + wait_seconds
    while True:
        status_raw = request("https://central.sonatype.com/api/v1/publisher/status?" + urllib.parse.urlencode({"id": deployment_id}), auth)
        status = json.loads(status_raw)
        state = status.get("deploymentState")
        print(json.dumps(status, sort_keys=True))
        if state in {"PUBLISHED", "VALIDATED"}:
            return deployment_id
        if state == "FAILED":
            raise RuntimeError(f"Central deployment failed: {status}")
        if time.time() >= deadline:
            raise TimeoutError(f"Central deployment {deployment_id} remained in {state}")
        time.sleep(10)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    merge_cmd = sub.add_parser("merge")
    merge_cmd.add_argument("--input", type=Path, action="append", required=True)
    merge_cmd.add_argument("--output", type=Path, required=True)
    merge_cmd.add_argument("--manifest", type=Path, required=True)
    merge_cmd.add_argument("--release-version", required=True)
    merge_cmd.add_argument("--commit", required=True)
    materialize_cmd = sub.add_parser("materialize-test-repository")
    materialize_cmd.add_argument("--input", type=Path, action="append", required=True)
    materialize_cmd.add_argument("--output", type=Path, required=True)
    materialize_cmd.add_argument("--manifest", type=Path, required=True)
    materialize_cmd.add_argument("--release-version", required=True)
    materialize_identity = materialize_cmd.add_mutually_exclusive_group(required=True)
    materialize_identity.add_argument("--commit")
    materialize_identity.add_argument("--provenance-manifest", type=Path)
    assets_cmd = sub.add_parser("verify-release-assets")
    assets_cmd.add_argument("--directory", type=Path, required=True)
    assets_cmd.add_argument("--manifest", type=Path, required=True)
    assets_cmd.add_argument("--release-version", required=True)
    assets_cmd.add_argument("--commit", required=True)
    verify_cmd = sub.add_parser("verify")
    verify_cmd.add_argument("--repository", type=Path, required=True)
    verify_cmd.add_argument("--manifest", type=Path)
    verify_cmd.add_argument("--release-version")
    verify_cmd.add_argument("--commit")
    sign_cmd = sub.add_parser("sign-bundle")
    sign_cmd.add_argument("--repository", type=Path, required=True)
    sign_cmd.add_argument("--output", type=Path, required=True)
    sign_cmd.add_argument("--gpg-executable", default="gpg")
    upload_cmd = sub.add_parser("upload")
    upload_cmd.add_argument("--bundle", type=Path, required=True)
    upload_cmd.add_argument("--automatic", action="store_true")
    upload_cmd.add_argument("--wait-seconds", type=int, default=3600)
    snapshot_cmd = sub.add_parser("deploy-snapshot")
    snapshot_cmd.add_argument("--repository", type=Path, required=True)
    snapshot_cmd.add_argument("--release-version", required=True)
    snapshot_cmd.add_argument("--repository-id", default="central-portal-snapshots")
    snapshot_cmd.add_argument("--url", default="https://central.sonatype.com/repository/maven-snapshots/")
    snapshot_cmd.add_argument("--maven-executable", default="mvn")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.command == "merge":
        merge(args.input, args.output, args.manifest, args.release_version, args.commit)
    elif args.command == "materialize-test-repository":
        provenance = (
            json.loads(args.provenance_manifest.read_text(encoding="utf-8"))
            if args.provenance_manifest
            else None
        )
        materialize_test_repository(
            args.input,
            args.output,
            args.manifest,
            args.release_version,
            args.commit,
            provenance=provenance,
        )
    elif args.command == "verify-release-assets":
        verify_release_assets(args.directory, args.manifest, args.release_version, args.commit)
    elif args.command == "verify":
        verify(args.repository, args.manifest, args.release_version, args.commit)
    elif args.command == "sign-bundle":
        sign_bundle(args.repository, args.output, args.gpg_executable)
    elif args.command == "upload":
        username = os.environ.get("CENTRAL_SONATYPE_TOKEN_USERNAME")
        password = os.environ.get("CENTRAL_SONATYPE_TOKEN_PASSWORD")
        if not username or not password:
            raise SystemExit("CENTRAL_SONATYPE_TOKEN_USERNAME and CENTRAL_SONATYPE_TOKEN_PASSWORD are required")
        deployment_id = upload(args.bundle, username, password, args.automatic, args.wait_seconds)
        print(deployment_id)
    elif args.command == "deploy-snapshot":
        # Arbitrary Maven repositories are supported. Maven reads credentials from
        # settings.xml using --repository-id; Central-specific environment variables
        # are required only by the Central Publisher Portal upload command above.
        deploy_snapshot(args.repository, args.release_version, args.repository_id, args.url, args.maven_executable)


if __name__ == "__main__":
    main()
