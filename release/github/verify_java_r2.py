#!/usr/bin/env python3
"""Read-only, bounded-memory verification of a stored Java release catalog."""
import argparse
import hashlib
import json
import os
import re
import sys
import time
from pathlib import Path

ENDPOINT = "https://318204901782458555a243ad96f80e3f.r2.cloudflarestorage.com"
BUCKET = "dl4j-cache"
REPOSITORY = "GetKompile/kompile"
CHUNK = 1 << 20


class VerificationError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def decode_json(raw):
    data = json.loads(raw, object_pairs_hook=unique_object)
    require(isinstance(data, dict), "JSON document must be an object")
    return data


def read_object(client, key, limit, versions, *, digest_only=False, expected_etag=None):
    """Only GetObject; never buffer archives, and fail on excess bytes."""
    request = {"Bucket": BUCKET, "Key": key}
    if expected_etag is not None:
        request["IfMatch"] = expected_etag
    response = client.get_object(**request)
    body = response["Body"]
    digest, size, parts = hashlib.sha256(), 0, []
    try:
        etag = response.get("ETag")
        require(isinstance(etag, str) and etag, f"{key}: missing ETag")
        if expected_etag is not None:
            require(etag == expected_etag, f"{key}: changed before/during archive read")
        while True:
            block = body.read(CHUNK)
            if not block:
                break
            size += len(block)
            require(size <= limit, f"{key}: object exceeds expected/allowed size")
            digest.update(block)
            if not digest_only:
                parts.append(block)
    finally:
        body.close()
    require(type(response.get("ContentLength")) is int and size == response["ContentLength"],
            f"{key}: incomplete/invalid response length")
    versions[key] = etag
    return (size, digest.hexdigest()) if digest_only else b"".join(parts)


def read_archive(client, key, size, versions):
    """Restart only interrupted transfers, pinned to one object version, at most three times."""
    etag = client.head_object(Bucket=BUCKET, Key=key).get("ETag")
    require(isinstance(etag, str) and etag, f"{key}: missing archive ETag")
    for attempt in range(1, 4):
        try:
            return read_object(client, key, size, versions, digest_only=True, expected_etag=etag)
        except VerificationError:
            raise
        except Exception as error:
            # SDK request retries do not cover errors raised while consuming a response body.
            from botocore.exceptions import (ConnectionClosedError, IncompleteReadError,
                                            ReadTimeoutError, ResponseStreamingError)
            if attempt == 3 or not isinstance(error, (ConnectionClosedError, IncompleteReadError,
                                                     ReadTimeoutError, ResponseStreamingError)):
                raise
            print(f"RETRY archive stream {key}: attempt {attempt + 1}/3; restarting hash", flush=True)
            time.sleep(2 ** (attempt - 1))


def verify(client, planner, matrix, rows, version, commit, run_id):
    planner.check_identity(version, commit)
    require(re.fullmatch(r"[1-9][0-9]*", run_id), "invalid release run id")
    prefix = f"kompile/releases/{version}/java"
    versions = {}
    manifest_key = prefix + "/manifest.json"
    raw = read_object(client, manifest_key, CHUNK, versions)
    manifest = decode_json(raw)
    require(type(manifest.get("schema")) is int, "invalid manifest schema type")
    for field, value in {"schema": 1, "repository": REPOSITORY, "version": version,
                         "commit": commit, "bucket": BUCKET, "prefix": prefix,
                         "complete": True, "missing": [], "stale": []}.items():
        require(manifest.get(field) == value, f"manifest {field} mismatch")
    require(manifest["complete"] is True, "manifest is incomplete")
    expected = {row["distributionClassifier"]: row for row in rows}
    require(len(expected) == len(rows) and rows, "matrix contains duplicate/empty rows")
    run = manifest.get("run", {})
    require(isinstance(run, dict) and run.get("id") == run_id and run.get("missing") == [],
            "manifest release run mismatch/incomplete")
    planned = run.get("planned")
    require(isinstance(planned, list) and planned and all(isinstance(x, str) for x in planned)
            and len(set(planned)) == len(planned) and set(planned) <= set(expected),
            "manifest planned rows are invalid")
    blocked = [{"classifier": item["classifier"],
                "reason": matrix["blockedReasons"][item["blocked"]]}
               for item in matrix["classifiers"] if "blocked" in item]
    require(manifest.get("blocked") == blocked, "manifest blocked rows mismatch")
    entries = manifest.get("variants")
    require(isinstance(entries, list) and len(entries) == len(rows), "manifest coverage mismatch")
    seen, verified = set(), []
    for entry in entries:
        require(isinstance(entry, dict), "invalid manifest entry")
        row = entry.get("build")
        require(isinstance(row, dict), "invalid build row")
        classifier = row.get("distributionClassifier")
        require(isinstance(classifier, str) and classifier not in seen
                and expected.get(classifier) == row, "duplicate/unknown/mismatched build row")
        seen.add(classifier)
        name = planner.archive_name(version, row)
        key = f"{prefix}/{classifier}/{name}"
        archive = entry.get("archive")
        require(isinstance(archive, dict) and archive.get("key") == key
                and archive.get("name") == name and type(archive.get("size")) is int
                and archive["size"] > 0 and isinstance(archive.get("sha256"), str)
                and re.fullmatch(r"[0-9a-f]{64}", archive["sha256"]), "invalid archive identity")
        require(entry.get("checksum_key") == key + ".sha256"
                and entry.get("record_key") == f"{prefix}/{classifier}/variant.json",
                "record/checksum key mismatch")
        record = decode_json(read_object(client, entry["record_key"], CHUNK, versions))
        require(type(record.get("schema")) is int, f"{classifier}: invalid record schema type")
        require(isinstance(record.get("archive"), dict)
                and type(record["archive"].get("size")) is int,
                f"{classifier}: invalid record archive size type")
        for field, value in {"schema": 1, "repository": REPOSITORY, "version": version,
                             "commit": commit, "build": row,
                             "archive": {k: archive[k] for k in ("name", "size", "sha256")}}.items():
            require(record.get(field) == value, f"{classifier}: record {field} mismatch")
        for field in ("run_id", "run_attempt"):
            require(isinstance(record.get(field), str)
                    and re.fullmatch(r"[1-9][0-9]*", record[field])
                    and record[field] == entry.get(field), f"{classifier}: {field} mismatch")
        require(isinstance(record.get("built_at"), str) and record["built_at"]
                and record["built_at"] == entry.get("built_at"), f"{classifier}: build time mismatch")
        if classifier in planned:
            require(record["run_id"] == run_id, f"{classifier}: planned row from another run")
        sidecar = read_object(client, entry["checksum_key"], 4096, versions).decode("utf-8").split()
        require(len(sidecar) == 2 and sidecar[0].lower() == archive["sha256"]
                and sidecar[1].lstrip("*") == name, f"{classifier}: checksum mismatch")
        size, digest = read_archive(client, key, archive["size"], versions)
        require(size == archive["size"] and digest == archive["sha256"],
                f"{classifier}: archive bytes/hash mismatch")
        verified.append({"classifier": classifier, "key": key, "size": size, "sha256": digest,
                         "run_id": record["run_id"], "run_attempt": record["run_attempt"]})
        print(f"VERIFIED {len(verified)}/{len(rows)} {classifier}: {size} bytes, sha256 {digest}", flush=True)
    require(seen == set(expected), "incomplete classifier coverage")
    # Refuse qualification if any object changed during this readback window.
    for key, etag in versions.items():
        require(client.head_object(Bucket=BUCKET, Key=key)["ETag"] == etag,
                f"{key}: changed during verification")
    receipt = {"schema": 1, "repository": REPOSITORY, "version": version, "commit": commit,
               "bucket": BUCKET, "prefix": prefix, "release_run_id": run_id,
               "verified_at": planner.utc_now(), "verifier_run_id": os.environ.get("GITHUB_RUN_ID"),
               "manifest_sha256": hashlib.sha256(raw).hexdigest(), "complete": True,
               "verified_count": len(verified), "total_bytes": sum(x["size"] for x in verified),
               "objects": verified, "object_etags": versions}
    return raw, receipt


def client_from_secrets():
    import boto3
    from botocore.config import Config
    credentials = []
    for name in ("R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY"):
        value = os.environ.get(name, "").strip()
        require(value and all(ord(c) >= 33 and ord(c) != 127 for c in value),
                f"{name}: missing or invalid repository secret")
        credentials.append(value)
    # Explicit credentials and endpoint: no AWS account, profile or session token.
    return boto3.client("s3", endpoint_url=ENDPOINT, region_name="auto",
                        aws_access_key_id=credentials[0], aws_secret_access_key=credentials[1],
                        config=Config(signature_version="s3v4", s3={"addressing_style": "path"},
                                      connect_timeout=15, read_timeout=90,
                                      retries={"mode": "standard", "total_max_attempts": 4},
                                      request_checksum_calculation="when_required",
                                      response_checksum_validation="when_required"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ("version", "commit", "run-id", "source-root", "output"):
        parser.add_argument("--" + field, required=True)
    args = parser.parse_args()
    source = Path(args.source_root).resolve()
    sys.path.insert(0, str(source / "release/github"))
    import java_matrix as planner
    planner.check_identity(args.version, args.commit)
    matrix, rows = planner.buildable_rows(source / "release/github/java-matrix.json",
                                         source / "build-scripts/build-common.sh", source / "pom.xml")
    raw, receipt = verify(client_from_secrets(), planner, matrix, rows,
                          args.version, args.commit, args.run_id)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    (output / "manifest.json").write_bytes(raw)
    planner.write_json(output / "verification-receipt.json", receipt)
    print(f"R2_JAVA_READBACK_VERIFIED {len(rows)}/{len(rows)}; no objects modified", flush=True)


if __name__ == "__main__":
    main()
