#!/usr/bin/env python3

import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest
import zipfile

SDK_ROOT = Path(__file__).resolve().parents[1]


class LocalSdkAssemblyContractTest(unittest.TestCase):
    def test_manifest_selected_archive_is_verified_and_composed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "release"
            release.mkdir()
            artifact_name = "sdx-aot-1.0.0-linux-x86_64-cpu-aot.zip"
            artifact = release / artifact_name
            with zipfile.ZipFile(artifact, "w") as archive:
                archive.writestr("include/sdx_llm_c.h", "canonical header\n")
                archive.writestr("lib/libsdx_llm.so", b"canonical runtime")
                archive.writestr("share/sdx/aot-manifest.json", json.dumps({
                    "name": "sdx-llm-aot",
                    "version": "1.0.0",
                    "platform": "linux-x86_64",
                    "variant": "cpu",
                    "extension": "",
                }))

            artifact_bytes = artifact.read_bytes()
            manifest = {
                "schemaVersion": 1,
                "releaseVersion": "1.0.0",
                "releaseTag": "sdk-v1.0.0",
                "artifacts": [{
                    "component": "aot",
                    "packageRole": "aot-sdk",
                    "platform": "linux-x86_64",
                    "variant": "cpu",
                    "classifier": "linux-x86_64-cpu",
                    "fileName": artifact_name,
                    "packaging": "zip",
                    "sha256": hashlib.sha256(artifact_bytes).hexdigest(),
                    "size": len(artifact_bytes),
                }],
            }
            manifest_path = release / "sdx-sdk-manifest.json"
            manifest_payload = json.dumps(manifest, indent=2) + "\n"
            manifest_path.write_text(manifest_payload, encoding="utf-8")
            manifest_path.with_name(manifest_path.name + ".sha256").write_text(
                hashlib.sha256(manifest_payload.encode()).hexdigest()
                + "  sdx-sdk-manifest.json\n",
                encoding="utf-8",
            )
            reasoning_library = root / "libkompile_reasoning.so"
            reasoning_library.write_bytes(b"reasoning")
            output = root / "output"

            environment = os.environ.copy()
            environment.update({
                "SDX_SDK_MANIFEST": str(manifest_path),
                "SDX_SDK_ARTIFACT": str(artifact),
                "SDX_SDK_PLATFORM": "linux-x86_64",
                "SDX_SDK_VARIANT": "cpu",
                "KGR_LIB_SRC": str(reasoning_library),
                "KOMPILE_SDK_OUTPUT_DIR": str(output),
                # A consumer override must never influence a manifest-owned path.
                "KOMPILE_SDK_PLATFORM": "../../outside",
            })
            result = subprocess.run(
                ["bash", str(SDK_ROOT / "assemble-local-sdk.sh")],
                cwd=SDK_ROOT,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)

            output_zip = output / "kompile-local-sdk-0.1.0-SNAPSHOT-linux-x86_64-cpu.zip"
            self.assertTrue(output_zip.is_file())
            prefix = "kompile-local-sdk-0.1.0-SNAPSHOT-linux-x86_64-cpu/"
            with zipfile.ZipFile(output_zip) as archive:
                names = set(archive.namelist())
                self.assertIn(prefix + "include/sdx_llm_c.h", names)
                self.assertIn(prefix + "lib/libsdx_llm.so", names)
                self.assertIn(prefix + "include/kompile_reasoning.h", names)
                self.assertIn(prefix + "lib/libkompile_reasoning.so", names)
                self.assertIn(prefix + "sdx-sdk-manifest.json", names)
                self.assertIn(prefix + "sdx-sdk-manifest.json.sha256", names)
                composition = json.loads(
                    archive.read(prefix + "kompile-composition-manifest.json")
                )
            selected = composition["upstreamSdk"]["selectedArtifact"]
            self.assertEqual(artifact_name, selected["fileName"])
            self.assertEqual("linux-x86_64-cpu", selected["classifier"])

            root_name = output_zip.stem
            published_stage = output / "stage" / root_name
            previous_zip = output_zip.read_bytes()
            previous_reasoning = (published_stage / "lib/libkompile_reasoning.so").read_bytes()
            reasoning_library.write_bytes(b"replacement reasoning")
            fake_bin = root / "fake-bin"
            fake_bin.mkdir()
            real_mv = shutil.which("mv")
            self.assertIsNotNone(real_mv)
            fake_mv = fake_bin / "mv"
            fake_mv.write_text(
                "#!/usr/bin/env bash\n"
                "set -euo pipefail\n"
                "destination=\"${@: -1}\"\n"
                "source=\"${1:-}\"\n"
                "if [[ \"${destination}\" == \"${KOMPILE_TEST_FAIL_DEST}\" && "
                "( \"${source}\" == *\".work.\"* || \"${source}\" == *\".tmp.\"* ) ]]; then\n"
                "  echo 'synthetic SDK publication failure' >&2\n"
                "  exit 73\n"
                "fi\n"
                "exec \"${KOMPILE_REAL_MV}\" \"$@\"\n",
                encoding="utf-8",
            )
            fake_mv.chmod(0o755)
            failed_promotion = subprocess.run(
                ["bash", str(SDK_ROOT / "assemble-local-sdk.sh")],
                cwd=SDK_ROOT,
                env={
                    **environment,
                    "PATH": str(fake_bin) + os.pathsep + environment["PATH"],
                    "KOMPILE_REAL_MV": real_mv,
                    "KOMPILE_TEST_FAIL_DEST": str(published_stage),
                },
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(0, failed_promotion.returncode)
            self.assertIn("previous published SDK was restored", failed_promotion.stderr)
            self.assertEqual(previous_zip, output_zip.read_bytes())
            self.assertEqual(
                previous_reasoning,
                (published_stage / "lib/libkompile_reasoning.so").read_bytes(),
            )
            self.assertFalse(Path(str(published_stage) + ".backup").exists())

            failed_archive_promotion = subprocess.run(
                ["bash", str(SDK_ROOT / "assemble-local-sdk.sh")],
                cwd=SDK_ROOT,
                env={
                    **environment,
                    "PATH": str(fake_bin) + os.pathsep + environment["PATH"],
                    "KOMPILE_REAL_MV": real_mv,
                    "KOMPILE_TEST_FAIL_DEST": str(output_zip),
                },
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(0, failed_archive_promotion.returncode)
            self.assertIn("previous published SDK was restored", failed_archive_promotion.stderr)
            self.assertEqual(previous_zip, output_zip.read_bytes())
            self.assertEqual(
                previous_reasoning,
                (published_stage / "lib/libkompile_reasoning.so").read_bytes(),
            )
            self.assertFalse(Path(str(published_stage) + ".backup").exists())
            self.assertFalse(Path(str(output_zip) + ".backup").exists())
            self.assertFalse((output / ("." + root_name + ".transaction")).exists())

            artifact.write_bytes(artifact_bytes + b"corrupt")
            corrupted = subprocess.run(
                ["bash", str(SDK_ROOT / "assemble-local-sdk.sh")],
                cwd=SDK_ROOT,
                env={**environment, "KOMPILE_SDK_OUTPUT_DIR": str(root / "corrupt-output")},
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(0, corrupted.returncode)
            self.assertIn("size does not match", corrupted.stderr)

    def test_safe_extractor_rejects_traversal_and_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            extractor = SDK_ROOT / "tools" / "extract_verified_sdk.py"

            traversal = root / "traversal.zip"
            with zipfile.ZipFile(traversal, "w") as archive:
                archive.writestr("include/sdx_llm_c.h", "header")
                archive.writestr("../escape.txt", "escape")
            destination = root / "traversal-output"
            destination.mkdir()
            result = subprocess.run(
                ["python3", str(extractor), str(traversal), str(destination),
                 "--version", "1.0.0", "--platform", "linux-x86_64", "--variant", "cpu"],
                text=True, capture_output=True, check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("unsafe ZIP entry path", result.stderr)
            self.assertFalse((root / "escape.txt").exists())

            symlink = root / "symlink.zip"
            link = zipfile.ZipInfo("lib/libsdx_llm.so")
            link.create_system = 3
            link.external_attr = (stat.S_IFLNK | 0o777) << 16
            with zipfile.ZipFile(symlink, "w") as archive:
                archive.writestr(link, "../../outside")
            destination = root / "symlink-output"
            destination.mkdir()
            result = subprocess.run(
                ["python3", str(extractor), str(symlink), str(destination),
                 "--version", "1.0.0", "--platform", "linux-x86_64", "--variant", "cpu"],
                text=True, capture_output=True, check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("link or special file", result.stderr)


if __name__ == "__main__":
    unittest.main()
