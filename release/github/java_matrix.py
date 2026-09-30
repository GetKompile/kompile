#!/usr/bin/env python3
"""Plans, records, and indexes the per-classifier Java distributions.

release/github/java-matrix.json lists every DL4J release classifier that
build-scripts/build-common.sh attests (KOMPILE_PLATFORMS, in the same order)
and says whether a standard GitHub-hosted runner builds it. Nothing else about a
classifier is written down twice: its backend profile, CUDA line, and SDK
classifier come from the resolvers in build-common.sh, and its ND4J artifacts
come from the root pom.xml profile that backend profile activates. When those
sources disagree the plan fails, so a mislabelled archive never ships.

  plan      prints the GitHub Actions matrix for the requested classifiers
  record    writes variant.json for one built and checksummed archive
  manifest  writes java/manifest.json from the variant records found in R2

.github/workflows/build-java-distributions.yml runs all three.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MATRIX_PATH = REPO_ROOT / "release" / "github" / "java-matrix.json"
BUILD_COMMON = REPO_ROOT / "build-scripts" / "build-common.sh"
ROOT_POM = REPO_ROOT / "pom.xml"

SCHEMA = 1
VARIANTS = ("full", "amd-zluda")
# manifest.json was written, but a distribution this run planned has no record.
INCOMPLETE = 3
POM = "{http://maven.apache.org/POM/4.0.0}"
NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
COMMIT = re.compile(r"^[0-9a-f]{40}$")

# The identity of one build. variant.json repeats every field, so the manifest
# can refuse a record that no longer matches the plan.
ROW_FIELDS = (
    "classifier",
    "platform",
    "runner",
    "variant",
    "backendProfile",
    "cudaVersion",
    "sdkClassifier",
    "distributionClassifier",
    "nd4jBackend",
    "nativeBackend",
    "nativeClassifier",
)

# Sources build-common.sh the way a GitHub-hosted runner does, with no DL4J
# checkout beside it, and prints the classifier list and the resolver results
# as tagged lines. Anything else the script prints is ignored.
RESOLVE = r"""
common="$1"
shift
classifiers=("$@")
set --
source "${common}" >/dev/null 2>&1 || exit 90
for platform in "${KOMPILE_PLATFORMS[@]}"; do
  printf 'P\t%s\n' "${platform}"
done
for classifier in "${classifiers[@]}"; do
  valid=0
  if kompile_validate_platform "${classifier}" >/dev/null 2>&1; then
    valid=1
  fi
  base="$(_resolve_javacpp_platform "${classifier}" 2>/dev/null)" || base=""
  backend="$(_resolve_backend_from_platform "${classifier}" 2>/dev/null)" || backend=""
  sdk="$(_resolve_sdk_classifier "${classifier}" 2>/dev/null)" || sdk=""
  printf 'C\t%s\t%s\t%s\t%s\t%s\n' "${classifier}" "${valid}" "${base}" "${backend}" "${sdk}"
done
"""


class MatrixError(Exception):
    """An inconsistent matrix, plan, or record. Nothing is written."""


def load_matrix(path: Path = MATRIX_PATH) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise MatrixError(f"{path}: {error}") from error
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA:
        raise MatrixError(f"{path}: schemaVersion must be {SCHEMA}")
    unknown = set(data) - {"schemaVersion", "description", "runners", "blockedReasons", "classifiers"}
    if unknown:
        raise MatrixError(f"{path}: unknown keys {sorted(unknown)}")
    runners, reasons, rows = data.get("runners"), data.get("blockedReasons"), data.get("classifiers")
    if not isinstance(runners, dict) or not runners or not all(
            isinstance(label, str) and label for label in runners.values()):
        raise MatrixError(f"{path}: runners must map each platform to a runner label")
    if not isinstance(reasons, dict) or not all(isinstance(text, str) and text for text in reasons.values()):
        raise MatrixError(f"{path}: blockedReasons must map each key to a reason")
    if not isinstance(rows, list) or not rows:
        raise MatrixError(f"{path}: classifiers must be a non-empty list")
    seen = set()
    for row in rows:
        classifier = row.get("classifier") if isinstance(row, dict) else None
        if not isinstance(classifier, str) or not NAME.match(classifier):
            raise MatrixError(f"{path}: invalid classifier row {row!r}")
        if classifier in seen:
            raise MatrixError(f"{path}: {classifier} is listed twice")
        seen.add(classifier)
        keys = set(row) - {"classifier"}
        if keys == {"variant"}:
            if row["variant"] not in VARIANTS:
                raise MatrixError(
                    f"{path}: {classifier} names variant {row['variant']!r}; expected one of {', '.join(VARIANTS)}")
        elif keys == {"blocked"}:
            if row["blocked"] not in reasons:
                raise MatrixError(f"{path}: {classifier} is blocked for an undefined reason {row['blocked']!r}")
        else:
            raise MatrixError(f"{path}: {classifier} must name exactly one of 'variant' or 'blocked'")
    unused = set(reasons) - {row.get("blocked") for row in rows}
    if unused:
        raise MatrixError(f"{path}: no classifier uses blockedReasons {sorted(unused)}")
    return data


def resolve_classifiers(classifiers: list, build_common: Path = BUILD_COMMON) -> tuple:
    """Returns (KOMPILE_PLATFORMS, {classifier: resolution}) from build-common.sh."""
    with tempfile.TemporaryDirectory(prefix="kompile-java-matrix-") as scratch:
        env = {
            "PATH": os.environ.get("PATH", os.defpath),
            "HOME": scratch,
            "LC_ALL": "C",
            "KOMPILE_ROOT": str(build_common.resolve().parents[1]),
            # An absent DL4J checkout keeps DL4J's own build-common.sh out of
            # the resolution, exactly as on a GitHub-hosted runner.
            "DL4J_PROJECT_ROOT": str(Path(scratch) / "no-deeplearning4j"),
        }
        completed = subprocess.run(
            ["bash", "--noprofile", "--norc", "-c", RESOLVE, "java-matrix", str(build_common), *classifiers],
            env=env, capture_output=True, text=True, timeout=120, check=False,
        )
    if completed.returncode != 0:
        detail = completed.stderr.strip()
        raise MatrixError(
            f"resolving classifiers with {build_common} failed (exit {completed.returncode})"
            + (f": {detail}" if detail else ""))
    platforms, resolved = [], {}
    for line in completed.stdout.splitlines():
        fields = line.split("\t")
        if fields[0] == "P" and len(fields) == 2:
            platforms.append(fields[1])
        elif fields[0] == "C" and len(fields) == 6:
            _, classifier, valid, base, backend, sdk = fields
            parts = backend.split("|")
            if len(parts) != 3:
                raise MatrixError(f"_resolve_backend_from_platform {classifier} printed {backend!r}")
            resolved[classifier] = {
                "valid": valid == "1",
                "platform": base,
                "backendType": parts[0],
                "cudaVersion": parts[1],
                "backendProfile": parts[2],
                "sdkClassifier": sdk,
            }
    if not platforms:
        raise MatrixError(f"{build_common} defines no KOMPILE_PLATFORMS")
    unresolved = [classifier for classifier in classifiers if classifier not in resolved]
    if unresolved:
        raise MatrixError(f"{build_common} did not resolve {', '.join(unresolved)}")
    return platforms, resolved


def _properties(element) -> dict:
    if element is None:
        return {}
    return {
        child.tag[len(POM):]: (child.text or "").strip()
        for child in element
        if isinstance(child.tag, str) and child.tag.startswith(POM)
    }


def backend_profiles(pom: Path = ROOT_POM) -> dict:
    """Maps each kompile.backend profile value to the ND4J artifacts it selects."""
    try:
        project = ET.parse(pom).getroot()
    except (OSError, ET.ParseError) as error:
        raise MatrixError(f"{pom}: {error}") from error
    defaults = _properties(project.find(f"{POM}properties"))
    profiles = {}
    for profile in project.iterfind(f"{POM}profiles/{POM}profile"):
        activation = profile.find(f"{POM}activation/{POM}property")
        if activation is None or (activation.findtext(f"{POM}name") or "").strip() != "kompile.backend":
            continue
        value = (activation.findtext(f"{POM}value") or "").strip()
        if not value or value in profiles:
            raise MatrixError(f"{pom}: the kompile.backend profile {value!r} is empty or declared twice")
        # Profile properties override the defaults before Maven interpolates.
        merged = {**defaults, **_properties(profile.find(f"{POM}properties"))}
        backend = merged.get("nd4j.backend", "")
        selected = {
            "nd4jBackend": backend,
            "nativeBackend": merged.get("nd4j.native.backend", "${nd4j.backend}").replace("${nd4j.backend}", backend),
            "extension": merged.get("javacpp.platform.extension", ""),
            "distributionClassifier": merged.get("kompile.distribution.platform.classifier", ""),
        }
        if not backend or "${" in selected["nd4jBackend"] + selected["nativeBackend"] + selected["extension"]:
            raise MatrixError(f"{pom}: the {value} profile leaves an ND4J property unresolved: {selected}")
        profiles[value] = selected
    if not profiles:
        raise MatrixError(f"{pom} declares no kompile.backend profiles")
    return profiles


def derive_rows(matrix: dict, platforms: list, resolved: dict, profiles: dict) -> list:
    """Every buildable row with its build arguments, in matrix order."""
    listed = [entry["classifier"] for entry in matrix["classifiers"]]
    if listed != platforms:
        raise MatrixError(
            "java-matrix.json must list KOMPILE_PLATFORMS from build-common.sh in the same order; "
            f"only in java-matrix.json: {sorted(set(listed) - set(platforms))}, "
            f"only in build-common.sh: {sorted(set(platforms) - set(listed))}")
    runners = matrix["runners"]
    rows = []
    for entry in matrix["classifiers"]:
        classifier = entry["classifier"]
        resolution = resolved[classifier]
        if not resolution["valid"]:
            raise MatrixError(f"kompile_validate_platform rejects {classifier}")
        if "blocked" in entry:
            continue
        base = resolution["platform"]
        profile_name = resolution["backendProfile"]
        if base not in runners:
            raise MatrixError(f"{classifier}: java-matrix.json names no runner for {base or 'its platform'}")
        profile = profiles.get(profile_name)
        if profile is None:
            raise MatrixError(f"{classifier}: the root pom.xml has no kompile.backend={profile_name} profile")
        lane = profile["distributionClassifier"].replace("${javacpp.platform}", base)
        if lane != classifier:
            raise MatrixError(f"{classifier}: the {profile_name} profile labels {base} distributions {lane}")
        sdk = resolution["sdkClassifier"]
        if sdk != base and not sdk.startswith(base + "-"):
            raise MatrixError(f"{classifier}: SDK classifier {sdk!r} does not belong to {base}")
        if profile_name == "zluda-rocm-10.0.0" and base != "linux-x86_64":
            raise MatrixError(f"{classifier}: ROCm 10 ZLUDA distributions are linux-x86_64 only")
        variant = entry["variant"]
        rows.append({
            "classifier": classifier,
            "platform": base,
            "runner": runners[base],
            "variant": variant,
            "backendProfile": profile_name,
            "cudaVersion": resolution["cudaVersion"],
            "sdkClassifier": sdk,
            "distributionClassifier": f"{variant}-{classifier}",
            "nd4jBackend": profile["nd4jBackend"],
            "nativeBackend": profile["nativeBackend"],
            "nativeClassifier": base + profile["extension"],
        })
    idle = set(runners) - {row["platform"] for row in rows}
    if idle:
        raise MatrixError(f"java-matrix.json names runners for platforms nothing builds: {sorted(idle)}")
    return rows


def buildable_rows(matrix_path: Path = MATRIX_PATH, build_common: Path = BUILD_COMMON,
                   pom: Path = ROOT_POM) -> tuple:
    """(matrix, rows) with every row checked against build-common.sh and the root pom."""
    matrix = load_matrix(matrix_path)
    platforms, resolved = resolve_classifiers([entry["classifier"] for entry in matrix["classifiers"]], build_common)
    return matrix, derive_rows(matrix, platforms, resolved, backend_profiles(pom))


def select_rows(matrix: dict, rows: list, request: str) -> list:
    """The rows a comma- or space-separated request names, or every row for 'all'."""
    names = [name for name in re.split(r"[\s,]+", request) if name]
    if not names:
        raise MatrixError("no classifiers were requested")
    if "all" in names:
        if names != ["all"]:
            raise MatrixError("'all' cannot be combined with named classifiers")
        return list(rows)
    buildable = {row["classifier"] for row in rows}
    blocked = {entry["classifier"]: entry["blocked"] for entry in matrix["classifiers"] if "blocked" in entry}
    seen = set()
    for name in names:
        if name in seen:
            raise MatrixError(f"{name} is requested twice")
        seen.add(name)
        if name in blocked:
            raise MatrixError(f"{name} is not built as a JVM distribution: {matrix['blockedReasons'][blocked[name]]}")
        if name not in buildable:
            raise MatrixError(f"{name} is not a DL4J release classifier; see release/github/java-matrix.json")
    return [row for row in rows if row["classifier"] in seen]


def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    # Bytes, so a Windows runner writes the same LF file as the others.
    path.write_bytes((json.dumps(data, indent=2) + "\n").encode("utf-8"))


def utc_now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def read_sidecar(path: Path) -> tuple:
    """(digest, file name) from a `<sha256>  <file name>` checksum file."""
    fields = path.read_text(encoding="utf-8").split()
    if len(fields) != 2:
        raise MatrixError(f"{path} must hold '<sha256>  <file name>'")
    return fields[0].lower(), fields[1].lstrip("*").replace("\\", "/").rsplit("/", 1)[-1]


def parse_row(data, origin: str) -> dict:
    if not isinstance(data, dict) or set(data) != set(ROW_FIELDS) or not all(
            isinstance(data[field], str) for field in ROW_FIELDS):
        raise MatrixError(f"{origin} must hold exactly the string fields {', '.join(ROW_FIELDS)}")
    return {field: data[field] for field in ROW_FIELDS}


def load_env_json(name: str):
    text = os.environ.get(name, "")
    try:
        return json.loads(text)
    except ValueError as error:
        raise MatrixError(f"${name} does not hold JSON: {error}") from error


def archive_name(version: str, row: dict) -> str:
    return f"kompile-dist-{version}-{row['distributionClassifier']}.zip"


def check_identity(version: str, commit: str) -> None:
    if not NAME.match(version):
        raise MatrixError(f"invalid version {version!r}")
    if not COMMIT.match(commit):
        raise MatrixError(f"invalid commit {commit!r}; expected a full lowercase SHA-1")


def cmd_plan(args) -> int:
    matrix, rows = buildable_rows()
    selected = select_rows(matrix, rows, args.classifiers)
    plan = json.dumps({"include": selected}, separators=(",", ":"))
    variants = ",".join(row["classifier"] for row in selected)
    if args.github_output:
        with open(args.github_output, "a", encoding="utf-8") as output:
            output.write(f"variants={variants}\n")
            output.write(f"variant_matrix={plan}\n")
        for row in selected:
            print(f"{row['distributionClassifier']}: {row['runner']}, backend profile {row['backendProfile']}, "
                  f"{row['nativeBackend']}:{row['nativeClassifier']}")
    else:
        print(plan)
    return 0


def cmd_record(args) -> int:
    check_identity(args.version, args.commit)
    if not args.run_id.isdigit() or not args.run_attempt.isdigit():
        raise MatrixError("the run id and run attempt must be numbers")
    row = parse_row(load_env_json(args.row_env), f"${args.row_env}")
    archive, checksum = Path(args.archive), Path(args.checksum)
    expected = archive_name(args.version, row)
    if archive.name != expected or checksum.name != expected + ".sha256":
        raise MatrixError(f"{archive.name} and {checksum.name} are not the archive and checksum of {expected}")
    digest = sha256_of(archive)
    listed, name = read_sidecar(checksum)
    if listed != digest or name != expected:
        raise MatrixError(f"{checksum.name} does not describe {expected}, whose sha256 is {digest}")
    record = {
        "schema": SCHEMA,
        "repository": args.repository,
        "version": args.version,
        "commit": args.commit,
        "run_id": args.run_id,
        "run_attempt": args.run_attempt,
        "built_at": utc_now(),
        "build": row,
        "archive": {"name": expected, "size": archive.stat().st_size, "sha256": digest},
    }
    write_json(Path(args.output), record)
    print(f"Recorded {expected} ({record['archive']['size']} bytes, sha256 {digest})")
    return 0


def read_listing(path: Path, prefix: str) -> dict:
    """Maps each key under prefix/ in a list-objects-v2 text listing to its size."""
    objects = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.strip() == "None":
            continue
        key, _, size = line.rpartition("\t")
        if not key or not size.isdigit():
            raise MatrixError(f"{path}: unexpected listing line {line!r}")
        if not key.startswith(prefix + "/"):
            raise MatrixError(f"{path}: {key} is outside {prefix}/")
        objects[key[len(prefix) + 1:]] = int(size)
    return objects


def check_record(path: Path, rows: dict, objects: dict, planned: set, args) -> tuple:
    """(entry, None) for a record the manifest can list, else (None, reason)."""
    classifier = path.parent.name
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        return None, f"variant.json is unreadable: {error}"
    if not isinstance(record, dict) or record.get("schema") != SCHEMA:
        return None, f"variant.json is not schema {SCHEMA}"
    row = rows.get(classifier)
    if row is None:
        return None, "not a distribution this matrix builds"
    if record.get("version") != args.version:
        return None, f"records version {record.get('version')!r}"
    if record.get("build") != row:
        return None, "its build fields no longer match the matrix"
    if record.get("commit") != args.commit:
        return None, f"built from {record.get('commit')}, not {args.commit}"
    if classifier in planned and record.get("run_id") != args.run_id:
        # This run was rebuilding it and may have replaced some of its objects.
        return None, f"recorded by run {record.get('run_id')}; this run did not finish rebuilding it"
    archive = record.get("archive")
    name = archive_name(args.version, row)
    if (not isinstance(archive, dict) or archive.get("name") != name
            or not isinstance(archive.get("size"), int) or not SHA256.match(str(archive.get("sha256")))):
        return None, "variant.json does not describe its archive"
    if objects.get(f"{classifier}/{name}") != archive["size"]:
        return None, f"{name} is {objects.get(f'{classifier}/{name}', 'missing')}, expected {archive['size']} bytes"
    if f"{classifier}/{name}.sha256" not in objects:
        return None, f"{name}.sha256 is missing"
    return {
        "build": row,
        "archive": {"key": f"{args.prefix}/{classifier}/{name}", **archive},
        "checksum_key": f"{args.prefix}/{classifier}/{name}.sha256",
        "record_key": f"{args.prefix}/{classifier}/variant.json",
        "run_id": record.get("run_id"),
        "run_attempt": record.get("run_attempt"),
        "built_at": record.get("built_at"),
    }, None


def cmd_manifest(args) -> int:
    check_identity(args.version, args.commit)
    if args.prefix != f"kompile/releases/{args.version}/java":
        raise MatrixError(f"prefix {args.prefix} is not kompile/releases/{args.version}/java")
    matrix, rows = buildable_rows()
    by_classifier = {row["distributionClassifier"]: row for row in rows}
    plan = load_env_json(args.plan_env)
    if not isinstance(plan, dict) or not isinstance(plan.get("include"), list) or not plan["include"]:
        raise MatrixError(f"${args.plan_env} must hold this run's {{\"include\": [...]}} matrix")
    planned = []
    for item in plan["include"]:
        row = parse_row(item, f"${args.plan_env}")
        if by_classifier.get(row["distributionClassifier"]) != row:
            raise MatrixError(f"this run's {row['distributionClassifier']} row no longer matches the matrix")
        planned.append(row["distributionClassifier"])
    objects = read_listing(Path(args.listing), args.prefix)
    current, stale = {}, []
    for path in sorted(Path(args.records).glob("*/variant.json")):
        entry, reason = check_record(path, by_classifier, objects, set(planned), args)
        if entry is None:
            stale.append({"key": f"{args.prefix}/{path.parent.name}/variant.json", "reason": reason})
        else:
            current[path.parent.name] = entry
    missing = [row["distributionClassifier"] for row in rows if row["distributionClassifier"] not in current]
    unfinished = [classifier for classifier in planned if classifier not in current]
    manifest = {
        "schema": SCHEMA,
        "repository": args.repository,
        "version": args.version,
        "commit": args.commit,
        "bucket": args.bucket,
        "prefix": args.prefix,
        "generated_at": utc_now(),
        "generated_by": args.run_url,
        "complete": not missing,
        "run": {"id": args.run_id, "planned": planned, "missing": unfinished},
        "variants": [current[row["distributionClassifier"]] for row in rows
                     if row["distributionClassifier"] in current],
        "missing": missing,
        "blocked": [
            {"classifier": entry["classifier"], "reason": matrix["blockedReasons"][entry["blocked"]]}
            for entry in matrix["classifiers"] if "blocked" in entry
        ],
        "stale": stale,
    }
    write_json(Path(args.output), manifest)
    for item in stale:
        print(f"Not listed: {item['key']}: {item['reason']}")
    print(f"{len(current)} of {len(rows)} distributions are recorded for {args.commit}.")
    if unfinished:
        print(f"ERROR: this run planned {', '.join(unfinished)} but recorded no build of them.", file=sys.stderr)
        return INCOMPLETE
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Per-classifier Java distribution plans and manifests.")
    commands = parser.add_subparsers(dest="command", required=True)

    plan = commands.add_parser("plan", help="print the matrix for the requested classifiers")
    plan.add_argument("--classifiers", required=True, help="comma-separated DL4J release classifiers, or all")
    plan.add_argument("--github-output", help="append variants= and variant_matrix= to this file")

    record = commands.add_parser("record", help="write variant.json for one built archive")
    record.add_argument("--row-env", required=True, help="environment variable holding the matrix row as JSON")
    record.add_argument("--version", required=True)
    record.add_argument("--archive", required=True)
    record.add_argument("--checksum", required=True)
    record.add_argument("--commit", required=True)
    record.add_argument("--repository", required=True)
    record.add_argument("--run-id", required=True)
    record.add_argument("--run-attempt", required=True)
    record.add_argument("--output", required=True)

    manifest = commands.add_parser("manifest", help="write java/manifest.json from the records in R2")
    manifest.add_argument("--plan-env", required=True, help="environment variable holding this run's matrix")
    manifest.add_argument("--listing", required=True, help="list-objects-v2 text listing of Key and Size")
    manifest.add_argument("--records", required=True, help="directory of <distributionClassifier>/variant.json")
    manifest.add_argument("--version", required=True)
    manifest.add_argument("--commit", required=True)
    manifest.add_argument("--repository", required=True)
    manifest.add_argument("--run-id", required=True)
    manifest.add_argument("--run-url", required=True)
    manifest.add_argument("--bucket", required=True)
    manifest.add_argument("--prefix", required=True)
    manifest.add_argument("--output", required=True)

    args = parser.parse_args(argv)
    handlers = {"plan": cmd_plan, "record": cmd_record, "manifest": cmd_manifest}
    try:
        return handlers[args.command](args)
    except MatrixError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
