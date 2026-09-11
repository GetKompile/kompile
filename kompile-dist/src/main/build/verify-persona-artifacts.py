#!/usr/bin/env python3
"""Verify the three split application artifacts in an extracted Kompile distribution.

This deliberately uses only the Python standard library so it can run on release runners before
any Kompile component is started.  It validates the packaged contract, not application behavior:
Spring context and multi-process acceptance tests cover runtime behavior separately.
"""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path


PERSONAS = {
    "server": {
        "jar": "kompile-server.jar",
        "start_class": "ai.kompile.app.MainApplication",
        "required": ("kompile-app-web-shared", "kompile-app-web-admin"),
        "forbidden": ("kompile-app-web-chat", "kompile-app-web-crawl", "kompile-app-web-graph"),
        "spa": "admin",
        "binary": "kompile-server",
    },
    "chat": {
        "jar": "kompile-chat.jar",
        "start_class": "ai.kompile.app.chat.ChatApplication",
        "required": ("kompile-app-web-shared", "kompile-app-web-chat", "kompile-app-web-graph"),
        "forbidden": ("kompile-app-web-admin", "kompile-app-web-crawl"),
        "spa": "chat",
        "binary": "kompile-chat",
    },
    "crawl-manager": {
        "jar": "kompile-crawl-manager.jar",
        "start_class": "ai.kompile.app.crawlmanager.CrawlManagerApplication",
        "required": ("kompile-app-web-shared", "kompile-app-web-crawl", "kompile-app-web-graph"),
        "forbidden": ("kompile-app-web-admin", "kompile-app-web-chat"),
        "spa": "crawl",
        "binary": "kompile-crawl-manager",
    },
}

PERSONA_VARIANTS = {
    "full",
    "hosted",
    "cpu-intel",
    "cpu-arm",
    "cuda",
    "amd-zluda",
}


def fail(message: str) -> None:
    raise AssertionError(message)


def jar_basename(name: str) -> str:
    return name.rsplit("/", 1)[-1]


def nested_libs(names: set[str]) -> list[str]:
    return [jar_basename(name) for name in names if name.startswith("BOOT-INF/lib/") and name.endswith(".jar")]


def manifest_attributes(archive: zipfile.ZipFile) -> dict[str, str]:
    try:
        raw = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    except KeyError as error:
        fail(f"{archive.filename}: missing META-INF/MANIFEST.MF")
        raise error
    attributes: dict[str, str] = {}
    for line in raw.splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
            attributes[key.strip()] = value.strip()
    return attributes


def verify_exec_jar(path: Path, persona: str) -> None:
    contract = PERSONAS[persona]
    if not path.is_file():
        fail(f"missing {persona} exec JAR: {path}")
    try:
        with zipfile.ZipFile(path) as archive:
            names = set(archive.namelist())
            if "BOOT-INF/classes/" not in names and not any(
                name.startswith("BOOT-INF/classes/") for name in names
            ):
                fail(f"{path}: not a Spring Boot executable JAR (BOOT-INF/classes missing)")
            attributes = manifest_attributes(archive)
            if attributes.get("Start-Class") != contract["start_class"]:
                fail(
                    f"{path}: Start-Class={attributes.get('Start-Class')!r}, "
                    f"expected {contract['start_class']!r}"
                )
            libs = nested_libs(names)
            for required in contract["required"]:
                if not any(required in lib for lib in libs):
                    fail(f"{path}: required dependency {required} is absent from BOOT-INF/lib")
            for forbidden in contract["forbidden"]:
                leaked = [lib for lib in libs if forbidden in lib]
                if leaked:
                    fail(f"{path}: forbidden persona dependency {forbidden}: {leaked}")
            spa_suffix = f"-{contract['spa']}.jar"
            spa_jars = [lib for lib in libs if lib.startswith("kompile-app-ui-") and lib.endswith(spa_suffix)]
            all_spa_jars = [lib for lib in libs if lib.startswith("kompile-app-ui-")]
            if len(spa_jars) != 1:
                fail(f"{path}: expected exactly one {contract['spa']} SPA classifier, found {spa_jars}")
            if len(all_spa_jars) != 1 or all_spa_jars != spa_jars:
                fail(f"{path}: foreign or duplicate SPA classifiers: {all_spa_jars}")
    except zipfile.BadZipFile as error:
        fail(f"{path}: invalid ZIP/JAR: {error}")


def verify_manifest(dist_root: Path, required_files: list[str]) -> None:
    manifest_path = dist_root / "manifest.sha256"
    if not manifest_path.is_file():
        fail(f"missing distribution manifest: {manifest_path}")
    entries = set()
    for line in manifest_path.read_text(encoding="utf-8").splitlines():
        if "  " in line:
            entries.add(line.split("  ", 1)[1].strip())
    missing = [relative for relative in required_files if relative not in entries]
    if missing:
        fail(f"manifest.sha256 does not cover required files: {missing}")


def verify_dist(dist_root: Path, variant: str, platform: str, require_native: bool) -> None:
    if not dist_root.is_dir():
        fail(f"distribution root is not a directory: {dist_root}")
    info_path = dist_root / ".dist-info.json"
    if not info_path.is_file():
        fail(f"missing distribution metadata: {info_path}")
    info = json.loads(info_path.read_text(encoding="utf-8"))
    if info.get("variant") != variant:
        fail(f"metadata variant={info.get('variant')!r}, expected {variant!r}")

    # The builder deliberately excludes manifest.sha256 from its own checksum list.
    required_files: list[str] = [".dist-info.json"]

    if variant in PERSONA_VARIANTS:
        required_files.extend(
            f"bin/{name}.sh" for name in ("kompile-server", "kompile-chat", "kompile-crawl-manager")
        )
        for persona, contract in PERSONAS.items():
            jar_relative = f"lib/{contract['jar']}"
            verify_exec_jar(dist_root / jar_relative, persona)
            required_files.append(jar_relative)
            component = info.get("components", {}).get(persona, {})
            if component.get("jar") is not True:
                fail(f"metadata does not mark {persona} JAR as present")
            if require_native:
                suffix = ".exe" if platform.startswith("windows-") else ""
                binary_relative = f"bin/{contract['binary']}{suffix}"
                if not (dist_root / binary_relative).is_file():
                    fail(f"missing required native {persona} binary: {binary_relative}")
                required_files.append(binary_relative)
                if component.get("native") is not True:
                    fail(f"metadata does not mark {persona} native binary as present")
    else:
        for persona, contract in PERSONAS.items():
            if (dist_root / "lib" / contract["jar"]).exists():
                fail(f"{variant} distribution unexpectedly contains {persona} JAR")
            if (dist_root / "bin" / contract["binary"]).exists() or (
                dist_root / "bin" / f"{contract['binary']}.exe"
            ).exists():
                fail(f"{variant} distribution unexpectedly contains {persona} native binary")

    verify_manifest(dist_root, required_files)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dist-root", type=Path, required=True)
    parser.add_argument("--variant", required=True)
    parser.add_argument("--platform", default="")
    parser.add_argument("--require-native", action="store_true")
    args = parser.parse_args(argv)
    try:
        verify_dist(args.dist_root, args.variant, args.platform, args.require_native)
    except (AssertionError, OSError, json.JSONDecodeError) as error:
        print(f"persona artifact verification failed: {error}", file=sys.stderr)
        return 1
    print(f"persona artifact verification passed: {args.dist_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
