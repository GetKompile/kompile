"""First-party skill packaging checks; no native build or real installation needed.

Run: python3 build-scripts/test_skill_distribution.py
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


class SkillDistributionTest(unittest.TestCase):
    def assert_package(self, install):
        source = ROOT / "skills"
        files = [p for p in source.rglob("*") if p.is_file()]
        self.assertTrue(any(p.name == "SKILL.md" for p in files))
        self.assertTrue(any("references" in p.parts for p in files))
        for path in files:
            target = install / "lib/skills" / path.relative_to(source)
            self.assertEqual(path.read_bytes(), target.read_bytes(), str(target))

    def test_shell_staging_copies_complete_packages(self):
        script = (ROOT / "build-dist.sh").read_text()
        start = script.index("# First-party skill packages,")
        end = script.index("# Default application configuration", start)
        with tempfile.TemporaryDirectory() as tmp:
            install = Path(tmp) / "dist with spaces"
            env = dict(os.environ, DIST_DIR=str(install))
            subprocess.run(["bash", "-eu", "-c", script[start:end]],
                           cwd=ROOT, env=env, check=True, capture_output=True, timeout=30)
            self.assert_package(install)

    def test_maven_assembly_includes_complete_skill_tree(self):
        assembly = ET.parse(ROOT / "kompile-dist/src/main/assembly/dist.xml")
        ns = {"a": "http://maven.apache.org/ASSEMBLY/2.2.0"}
        sets = [entry for entry in assembly.findall("a:fileSets/a:fileSet", ns)
                if entry.findtext("a:outputDirectory", namespaces=ns) == "lib/skills"]
        self.assertEqual(1, len(sets))
        self.assertEqual("${project.parent.basedir}/skills",
                         sets[0].findtext("a:directory", namespaces=ns))
        self.assertIsNone(sets[0].find("a:includes", ns))
        self.assertIsNone(sets[0].find("a:excludes", ns))

    def test_developer_install_keeps_user_skills(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            checkout = root / "checkout"
            checkout.mkdir()
            shutil.copy2(ROOT / "install.sh", checkout / "install.sh")
            shutil.copytree(ROOT / "skills", checkout / "skills")
            home = root / "home"
            home.mkdir()
            install = home / ".kompile"
            override = install / "skills/kompile-orchestrator.md"
            override.parent.mkdir(parents=True)
            override.write_text("User-owned override")
            env = dict(os.environ, HOME=str(home), KOMPILE_INSTALL_DIR=str(install))
            subprocess.run(["bash", str(checkout / "install.sh"), "--dev"],
                           cwd=checkout, env=env, check=True, capture_output=True, timeout=30)
            self.assert_package(install)
            self.assertEqual("User-owned override", override.read_text())


if __name__ == "__main__":
    unittest.main()
