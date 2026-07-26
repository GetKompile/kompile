#!/usr/bin/env python3
"""Safely extract and validate one manifest-verified canonical SDX AOT SDK ZIP."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import stat
import sys
import zipfile

MAX_ENTRIES = 200_000
MAX_EXPANDED_BYTES = 16 * 1024 * 1024 * 1024
CHUNK_SIZE = 1024 * 1024


def fail(message: str) -> None:
    raise ValueError(message)


def safe_parts(name: str) -> tuple[str, ...]:
    normalized = name.replace("\\", "/")
    stripped = normalized[:-1] if normalized.endswith("/") else normalized
    if not stripped or normalized.startswith("/") or "\0" in normalized:
        fail(f"unsafe ZIP entry path: {name!r}")
    parts = tuple(stripped.split("/"))
    if any(part in ("", ".", "..") or ":" in part for part in parts):
        fail(f"unsafe ZIP entry path: {name!r}")
    return parts


def validate_entry_type(info: zipfile.ZipInfo) -> None:
    if info.flag_bits & 0x1:
        fail(f"encrypted ZIP entries are unsupported: {info.filename!r}")
    mode = (info.external_attr >> 16) & 0xFFFF
    kind = stat.S_IFMT(mode)
    if kind not in (0, stat.S_IFREG, stat.S_IFDIR):
        fail(f"ZIP entry is a link or special file: {info.filename!r}")


def extract(archive_path: Path, destination: Path, version: str, platform: str, variant: str) -> None:
    if not archive_path.is_file() or archive_path.is_symlink():
        fail(f"SDK archive is not a regular file: {archive_path}")
    if not destination.is_dir() or destination.is_symlink() or any(destination.iterdir()):
        fail(f"extraction destination must be an empty directory: {destination}")

    seen: set[tuple[str, ...]] = set()
    expanded = 0
    with zipfile.ZipFile(archive_path) as archive:
        entries = archive.infolist()
        if not entries:
            fail("SDK archive is empty")
        if len(entries) > MAX_ENTRIES:
            fail(f"SDK archive exceeds the {MAX_ENTRIES} entry limit")
        for info in entries:
            validate_entry_type(info)
            parts = safe_parts(info.filename)
            if parts in seen:
                fail(f"duplicate ZIP entry path: {info.filename!r}")
            seen.add(parts)
            if info.file_size < 0 or info.file_size > MAX_EXPANDED_BYTES - expanded:
                fail("SDK archive exceeds the expanded size limit")

            target = destination.joinpath(*parts)
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                if not target.is_dir() or target.is_symlink():
                    fail(f"ZIP directory collides with another entry: {info.filename!r}")
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            written = 0
            with archive.open(info) as source, target.open("xb") as output:
                while True:
                    chunk = source.read(CHUNK_SIZE)
                    if not chunk:
                        break
                    if len(chunk) > MAX_EXPANDED_BYTES - expanded:
                        fail("SDK archive exceeds the expanded size limit")
                    output.write(chunk)
                    written += len(chunk)
                    expanded += len(chunk)
            if written != info.file_size:
                fail(f"ZIP entry size changed while extracting: {info.filename!r}")
            mode = (info.external_attr >> 16) & 0o777
            if mode:
                target.chmod(mode)

    header = destination / "include" / "sdx_llm_c.h"
    internal_manifest = destination / "share" / "sdx" / "aot-manifest.json"
    libraries = tuple((destination / "lib").glob("libsdx_llm.*")) if (destination / "lib").is_dir() else ()
    if not header.is_file() or header.is_symlink():
        fail("AOT SDK is missing include/sdx_llm_c.h")
    if not internal_manifest.is_file() or internal_manifest.is_symlink():
        fail("AOT SDK is missing share/sdx/aot-manifest.json")
    if not any(path.is_file() and not path.is_symlink() for path in libraries):
        fail("AOT SDK is missing lib/libsdx_llm.*")

    metadata = json.loads(internal_manifest.read_text(encoding="utf-8"))
    extension = metadata.get("extension", "")
    internal_variant = metadata.get("variant")
    if not isinstance(extension, str) or not isinstance(internal_variant, str):
        fail("AOT SDK internal manifest has invalid variant metadata")
    selector_variant = f"{extension.lstrip('-')}-{internal_variant}" if extension else internal_variant
    if metadata.get("name") != "sdx-llm-aot":
        fail("AOT SDK internal manifest has an invalid name")
    if metadata.get("version") != version:
        fail("AOT SDK internal manifest version does not match the release manifest")
    if metadata.get("platform") != platform or selector_variant != variant:
        fail("AOT SDK internal platform/variant does not match the release manifest")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--platform", required=True)
    parser.add_argument("--variant", required=True)
    args = parser.parse_args()
    try:
        extract(args.archive, args.destination, args.version, args.platform, args.variant)
    except (OSError, ValueError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        print(f"Unsafe or invalid canonical AOT SDK archive: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
