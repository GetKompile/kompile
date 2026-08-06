#!/usr/bin/env python3
"""Tests for shared Maven repository materialization."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


repository = load_module("kompile_central_repository", HERE / "repository.py")
NS = {"m": repository.MAVEN_METADATA_NAMESPACE}


class MavenMetadataTests(unittest.TestCase):
    def write_component(
        self,
        root: Path,
        *,
        group_path: str,
        artifact_id: str,
        version: str,
        classifier: str | None,
    ) -> Path:
        version_dir = root / group_path / artifact_id / version
        version_dir.mkdir(parents=True, exist_ok=True)
        base_name = f"{artifact_id}-{version}"
        (version_dir / f"{base_name}.pom").write_text(
            "<project><modelVersion>4.0.0</modelVersion></project>\n",
            encoding="utf-8",
        )
        jar_name = base_name + (f"-{classifier}" if classifier else "") + ".jar"
        (version_dir / jar_name).write_bytes(b"PK\x03\x04test-jar")
        return version_dir

    def test_repository_files_accepts_kompile_and_dl4j_namespaces_only(self):
        version = "1.0.0-SNAPSHOT"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            kompile = self.write_component(
                root,
                group_path="ai/kompile",
                artifact_id="kompile-parent",
                version=version,
                classifier=None,
            )
            dl4j = self.write_component(
                root,
                group_path="org/eclipse/deeplearning4j",
                artifact_id="nd4j-native",
                version=version,
                classifier="linux-x86_64-avx2",
            )
            unrelated = self.write_component(
                root,
                group_path="com/example",
                artifact_id="unrelated",
                version=version,
                classifier=None,
            )
            selected = set(repository.repository_files(root))
            self.assertTrue(any(path.is_relative_to(kompile) for path in selected))
            self.assertTrue(any(path.is_relative_to(dl4j) for path in selected))
            self.assertFalse(any(path.is_relative_to(unrelated) for path in selected))

    def test_shared_repository_preserves_kompile_and_dl4j_versions(self):
        kompile_version = "0.1.0-SNAPSHOT"
        dl4j_version = "1.0.0-SNAPSHOT"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "shard"
            self.write_component(
                source,
                group_path="ai/kompile",
                artifact_id="kompile-parent",
                version=kompile_version,
                classifier=None,
            )
            self.write_component(
                source,
                group_path="org/eclipse/deeplearning4j",
                artifact_id="nd4j-native",
                version=dl4j_version,
                classifier="linux-x86_64-avx2",
            )
            output = root / "repository"
            repository.materialize_test_repository(
                [source],
                output,
                root / "repository-manifest.json",
                kompile_version,
                "deadbeef",
                metadata_updated="20260805010203",
            )

            kompile_metadata = ET.parse(
                output / "ai" / "kompile" / "kompile-parent" / "maven-metadata.xml"
            ).getroot()
            dl4j_metadata = ET.parse(
                output / "org" / "eclipse" / "deeplearning4j" / "nd4j-native" /
                "maven-metadata.xml"
            ).getroot()
            self.assertEqual(
                kompile_version,
                kompile_metadata.findtext("m:versioning/m:latest", namespaces=NS),
            )
            self.assertEqual(
                dl4j_version,
                dl4j_metadata.findtext("m:versioning/m:latest", namespaces=NS),
            )

    def test_snapshot_repository_has_a_and_v_metadata_with_stable_names(self):
        version = "1.0.0-SNAPSHOT"
        updated = "20260805010203"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "shard"
            version_dir = self.write_component(
                source,
                group_path="org/eclipse/deeplearning4j",
                artifact_id="nd4j-cuda-12.9",
                version=version,
                classifier="windows-x86_64-zluda",
            )
            output = root / "repository"
            manifest_path = root / "repository-manifest.json"

            manifest = repository.materialize_test_repository(
                [source],
                output,
                manifest_path,
                version,
                "deadbeef",
                metadata_updated=updated,
            )

            artifact_dir = output / version_dir.relative_to(source).parent
            published_version_dir = artifact_dir / version
            artifact_metadata_path = artifact_dir / "maven-metadata.xml"
            version_metadata_path = published_version_dir / "maven-metadata.xml"
            self.assertTrue(artifact_metadata_path.is_file())
            self.assertTrue(version_metadata_path.is_file())

            artifact_metadata = ET.parse(artifact_metadata_path).getroot()
            self.assertEqual("1.1.0", artifact_metadata.attrib["modelVersion"])
            self.assertEqual(
                "org.eclipse.deeplearning4j",
                artifact_metadata.findtext("m:groupId", namespaces=NS),
            )
            self.assertEqual(
                "nd4j-cuda-12.9",
                artifact_metadata.findtext("m:artifactId", namespaces=NS),
            )
            self.assertEqual(
                version,
                artifact_metadata.findtext("m:versioning/m:latest", namespaces=NS),
            )
            self.assertIsNone(
                artifact_metadata.find("m:versioning/m:release", namespaces=NS)
            )
            self.assertEqual(
                [version],
                [
                    node.text
                    for node in artifact_metadata.findall(
                        "m:versioning/m:versions/m:version", namespaces=NS
                    )
                ],
            )
            self.assertEqual(
                updated,
                artifact_metadata.findtext(
                    "m:versioning/m:lastUpdated", namespaces=NS
                ),
            )

            version_metadata = ET.parse(version_metadata_path).getroot()
            self.assertEqual(
                version, version_metadata.findtext("m:version", namespaces=NS)
            )
            self.assertEqual(
                "true",
                version_metadata.findtext(
                    "m:versioning/m:snapshot/m:localCopy", namespaces=NS
                ),
            )
            snapshot_versions = {
                (
                    node.findtext("m:extension", namespaces=NS),
                    node.findtext("m:classifier", namespaces=NS),
                    node.findtext("m:value", namespaces=NS),
                    node.findtext("m:updated", namespaces=NS),
                )
                for node in version_metadata.findall(
                    "m:versioning/m:snapshotVersions/m:snapshotVersion",
                    namespaces=NS,
                )
            }
            self.assertEqual(
                {
                    ("pom", None, version, updated),
                    ("jar", "windows-x86_64-zluda", version, updated),
                },
                snapshot_versions,
            )

            for metadata_path in (artifact_metadata_path, version_metadata_path):
                for algorithm in repository.CHECKSUMS:
                    checksum_path = Path(str(metadata_path) + f".{algorithm}")
                    self.assertEqual(
                        repository.digest(metadata_path, algorithm) + "\n",
                        checksum_path.read_text(encoding="ascii"),
                    )

            manifest_files = {item["path"] for item in manifest["files"]}
            self.assertIn(
                "org/eclipse/deeplearning4j/nd4j-cuda-12.9/maven-metadata.xml",
                manifest_files,
            )
            self.assertIn(
                "org/eclipse/deeplearning4j/nd4j-cuda-12.9/"
                f"{version}/maven-metadata.xml.sha512",
                manifest_files,
            )
            repository.verify(output, manifest_path, version, "deadbeef")
            manifest["files"][0]["size"] += 1
            manifest_path.write_text(
                json.dumps(manifest, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "manifest mismatch"):
                repository.verify(output, manifest_path, version, "deadbeef")

    def test_release_repository_has_a_metadata_and_no_v_metadata(self):
        version = "1.2.3"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "shard"
            version_dir = self.write_component(
                source,
                group_path="org/nd4j",
                artifact_id="nd4j-native",
                version=version,
                classifier=None,
            )
            output = root / "repository"
            manifest_path = root / "repository-manifest.json"

            repository.materialize_test_repository(
                [source],
                output,
                manifest_path,
                version,
                "cafebabe",
                metadata_updated="20260805030405",
            )

            artifact_dir = output / version_dir.relative_to(source).parent
            artifact_metadata = ET.parse(
                artifact_dir / "maven-metadata.xml"
            ).getroot()
            self.assertEqual(
                version,
                artifact_metadata.findtext("m:versioning/m:latest", namespaces=NS),
            )
            self.assertEqual(
                version,
                artifact_metadata.findtext("m:versioning/m:release", namespaces=NS),
            )
            self.assertFalse((artifact_dir / version / "maven-metadata.xml").exists())
            repository.verify(output, manifest_path, version, "cafebabe")

    def test_migration_provenance_is_preserved_and_stale_metadata_is_regenerated(self):
        version = "1.0.0-SNAPSHOT"
        commits = ["a" * 40, "b" * 40]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "first"
            second = root / "second"
            first_version = self.write_component(
                first,
                group_path="org/nd4j",
                artifact_id="nd4j-native",
                version=version,
                classifier="linux-x86_64",
            )
            second_version = self.write_component(
                second,
                group_path="org/nd4j",
                artifact_id="nd4j-native",
                version=version,
                classifier="linux-arm64",
            )
            (first_version / "maven-metadata.xml").write_text(
                "<metadata>old-first</metadata>\n", encoding="utf-8"
            )
            (second_version / "maven-metadata.xml").write_text(
                "<metadata>old-second</metadata>\n", encoding="utf-8"
            )
            (first_version / "maven-metadata.xml.sha1").write_text(
                "first\n", encoding="ascii"
            )
            (second_version / "maven-metadata.xml.sha1").write_text(
                "second\n", encoding="ascii"
            )
            provenance = {
                "schemaVersion": 1,
                "releaseVersion": version,
                "targetCommit": "c" * 40,
                "sourceCommits": commits,
                "sources": [
                    {
                        "runId": f"run-{index}",
                        "shard": f"shard-{index}",
                        "commit": commit,
                        "sha256": str(index) * 64,
                        "size": index,
                    }
                    for index, commit in enumerate(commits, start=1)
                ],
            }
            output = root / "repository"
            manifest_path = root / "repository-manifest.json"

            manifest = repository.materialize_test_repository(
                [first, second],
                output,
                manifest_path,
                version,
                None,
                metadata_updated="20260805040506",
                provenance=provenance,
            )

            self.assertEqual(2, manifest["schemaVersion"])
            self.assertNotIn("commit", manifest)
            self.assertEqual("c" * 40, manifest["targetCommit"])
            self.assertEqual(commits, manifest["sourceCommits"])
            self.assertEqual(2, len(manifest["sources"]))
            published = output / first_version.relative_to(first)
            self.assertTrue(
                (published / f"nd4j-native-{version}-linux-x86_64.jar").is_file()
            )
            self.assertTrue(
                (published / f"nd4j-native-{version}-linux-arm64.jar").is_file()
            )
            self.assertNotIn(
                "old-first",
                (published / "maven-metadata.xml").read_text(encoding="utf-8"),
            )
            repository.verify(output, manifest_path, version, None)

    def test_metadata_timestamp_must_be_maven_utc_format(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.write_component(
                root,
                group_path="org/nd4j",
                artifact_id="nd4j-native",
                version="1.0.0-SNAPSHOT",
                classifier=None,
            )
            for invalid_timestamp in ("2026-08-05", "20261399000000"):
                with self.subTest(updated=invalid_timestamp):
                    with self.assertRaisesRegex(ValueError, "yyyyMMddHHmmss"):
                        repository.write_maven_metadata(
                            root,
                            "1.0.0-SNAPSHOT",
                            updated=invalid_timestamp,
                        )

    def test_classified_distribution_zip_is_materialized_and_deployed(self):
        version = "0.1.0-SNAPSHOT"
        classifier = "full-linux-x86_64"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "shard"
            version_dir = self.write_component(
                source,
                group_path="ai/kompile",
                artifact_id="kompile-dist",
                version=version,
                classifier=None,
            )
            (version_dir / f"kompile-dist-{version}.jar").unlink()
            zip_name = f"kompile-dist-{version}-{classifier}.zip"
            (version_dir / zip_name).write_bytes(b"PK\x03\x04complete-distribution")

            output = root / "repository"
            repository.materialize_test_repository(
                [source],
                output,
                root / "repository-manifest.json",
                version,
                "deadbeef",
                metadata_updated="20260805060708",
            )
            published_zip = output / version_dir.relative_to(source) / zip_name
            self.assertTrue(published_zip.is_file())

            commands = repository.snapshot_deploy_commands(
                output, version, "dl4j-release", "https://repo.example/snapshots",
            )
            command = next(
                item for item in commands
                if any("kompile-dist" in argument for argument in item)
            )
            self.assertIn(f"-Dfiles={published_zip}", command)
            self.assertIn(f"-Dclassifiers={classifier}", command)
            self.assertIn("-Dtypes=zip", command)


if __name__ == "__main__":
    unittest.main()
