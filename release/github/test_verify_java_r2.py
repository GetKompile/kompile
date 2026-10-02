"""No-network regressions for the read-only release qualification gate."""
import contextlib
import copy
import hashlib
import io
import json
import os
import unittest
from unittest import mock
import types
from pathlib import Path
import sys

SOURCE = Path(os.environ.get("RELEASE_SOURCE_ROOT", Path(__file__).resolve().parents[2])).resolve()
sys.path.insert(0, str(SOURCE / "release/github"))
import java_matrix as planner
sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_java_r2 as verifier


class ReadOnlyS3:
    def __init__(self, objects):
        self.objects = objects
        self.calls = []
        self.bodies = []
        self.changed = False

    def get_object(self, *, Bucket, Key, IfMatch=None):
        assert Bucket == verifier.BUCKET
        self.calls.append(("get", Key))
        raw = self.objects[Key]
        body = io.BytesIO(raw)
        self.bodies.append(body)
        return {"Body": body, "ContentLength": len(raw), "ETag": hashlib.sha256(raw).hexdigest()}

    def head_object(self, *, Bucket, Key):
        assert Bucket == verifier.BUCKET
        self.calls.append(("head", Key))
        return {"ETag": "changed" if self.changed else hashlib.sha256(self.objects[Key]).hexdigest()}


class VerificationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.matrix, cls.rows = planner.buildable_rows(SOURCE / "release/github/java-matrix.json",
                                                    SOURCE / "build-scripts/build-common.sh", SOURCE / "pom.xml")

    def setUp(self):
        self.version, self.commit, self.run_id = "0.1.0-java-proof.2", "a" * 40, "123"
        self.prefix = f"kompile/releases/{self.version}/java"
        self.objects, entries = {}, []
        for index, row in enumerate(self.rows):
            name = planner.archive_name(self.version, row)
            raw = f"archive-{index}".encode()
            archive = {"name": name, "size": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
            record = {"schema": 1, "repository": verifier.REPOSITORY, "version": self.version,
                      "commit": self.commit, "build": row, "archive": archive,
                      "run_id": self.run_id, "run_attempt": str(1 + index % 3),
                      "built_at": "2026-10-01T15:00:00Z"}
            key = f"{self.prefix}/{row['distributionClassifier']}/{name}"
            record_key = f"{self.prefix}/{row['distributionClassifier']}/variant.json"
            self.objects[key] = raw
            self.objects[key + ".sha256"] = f"{archive['sha256']}  {name}\n".encode()
            self.objects[record_key] = json.dumps(record).encode()
            entries.append({"build": row, "archive": {"key": key, **archive},
                            "checksum_key": key + ".sha256", "record_key": record_key,
                            **{k: record[k] for k in ("run_id", "run_attempt", "built_at")}})
        self.manifest = {"schema": 1, "repository": verifier.REPOSITORY,
                         "version": self.version, "commit": self.commit,
                         "bucket": verifier.BUCKET, "prefix": self.prefix,
                         "complete": True, "missing": [], "stale": [],
                         "run": {"id": self.run_id, "missing": [],
                                 "planned": [r["distributionClassifier"] for r in self.rows]},
                         "blocked": [{"classifier": r["classifier"],
                                      "reason": self.matrix["blockedReasons"][r["blocked"]]}
                                     for r in self.matrix["classifiers"] if "blocked" in r],
                         "variants": entries}
        self.client = ReadOnlyS3(self.objects)

    def verify(self):
        self.objects[self.prefix + "/manifest.json"] = json.dumps(self.manifest).encode()
        with contextlib.redirect_stdout(io.StringIO()):
            return verifier.verify(self.client, planner, self.matrix, self.rows,
                                   self.version, self.commit, self.run_id)

    def reject(self):
        with self.assertRaises((ValueError, KeyError)):
            self.verify()
        self.assertTrue(all(body.closed for body in self.client.bodies))

    def test_all_39_bytes_and_identity_verified_read_only(self):
        raw, receipt = self.verify()
        self.assertEqual(39, receipt["verified_count"])
        self.assertEqual(19, len(json.loads(raw)["blocked"]))
        self.assertEqual(118, len(receipt["object_etags"]))
        self.assertEqual({"get", "head"}, {op for op, _ in self.client.calls})
        self.assertTrue(all(body.closed for body in self.client.bodies))

    def test_missing_and_duplicate_rows_rejected(self):
        for entries in (self.manifest["variants"][:-1],
                        self.manifest["variants"][:-1] + [self.manifest["variants"][0]]):
            self.manifest["variants"] = entries
            self.reject()

    def test_manifest_identity_rejected(self):
        original = copy.deepcopy(self.manifest)
        for field, value in (("complete", False), ("commit", "b" * 40),
                             ("repository", "other/repo"), ("bucket", "other"),
                             ("missing", ["one"]), ("stale", ["one"]), ("blocked", [])):
            with self.subTest(field=field):
                self.manifest = copy.deepcopy(original)
                self.manifest[field] = value
                self.reject()

    def test_bad_run_or_build_rejected(self):
        self.manifest["run"]["id"] = "456"
        self.reject()
        self.manifest["run"]["id"] = self.run_id
        self.manifest["variants"][0]["build"] = {"distributionClassifier": "unknown"}
        self.reject()

    def test_keys_cannot_escape_release_prefix(self):
        original = copy.deepcopy(self.manifest)
        for field in ("checksum_key", "record_key"):
            self.manifest = copy.deepcopy(original)
            self.manifest["variants"][0][field] = "other-prefix/secret"
            self.reject()
            self.assertNotIn(("get", "other-prefix/secret"), self.client.calls)

    def test_previous_run_preserved_only_for_unplanned_rows(self):
        entry = self.manifest["variants"][0]
        self.manifest["run"]["planned"].remove(entry["build"]["distributionClassifier"])
        entry["run_id"] = "456"
        record = json.loads(self.objects[entry["record_key"]])
        record["run_id"] = "456"
        self.objects[entry["record_key"]] = json.dumps(record).encode()
        _, receipt = self.verify()
        self.assertEqual("456", receipt["objects"][0]["run_id"])
        self.manifest["run"]["planned"].append(entry["build"]["distributionClassifier"])
        self.reject()

    def test_record_conflict_rejected(self):
        key = self.manifest["variants"][0]["record_key"]
        record = json.loads(self.objects[key])
        record["commit"] = "b" * 40
        self.objects[key] = json.dumps(record).encode()
        self.reject()

    def test_sidecar_and_same_size_archive_corruption_rejected(self):
        entry = self.manifest["variants"][0]
        sidecar = self.objects[entry["checksum_key"]]
        self.objects[entry["checksum_key"]] = b"bad  archive.zip"
        self.reject()
        self.objects[entry["checksum_key"]] = sidecar
        self.objects[entry["archive"]["key"]] = b"X" * entry["archive"]["size"]
        self.reject()

    def test_short_or_excess_archive_rejected(self):
        entry = self.manifest["variants"][0]
        for size in (entry["archive"]["size"] - 1, entry["archive"]["size"] + 1):
            self.objects[entry["archive"]["key"]] = b"X" * size
            self.reject()

    def test_concurrent_object_change_rejected(self):
        original = self.client.head_object
        def changed_during_final_heads(**kwargs):
            response = original(**kwargs)
            if sum(op == "get" for op, _ in self.client.calls) == 3 * len(self.rows) + 1:
                response["ETag"] = "changed"
            return response
        self.client.head_object = changed_during_final_heads
        with self.assertRaisesRegex(verifier.VerificationError, "changed during verification"):
            self.verify()
        self.assertEqual(3 * len(self.rows) + 1, sum(op == "get" for op, _ in self.client.calls))
        self.assertTrue(all(body.closed for body in self.client.bodies))

    def test_truncated_response_rejected_and_closed(self):
        client = self.client
        original = client.get_object
        def truncated(**kwargs):
            response = original(**kwargs)
            response["ContentLength"] += 1
            return response
        client.get_object = truncated
        self.reject()

    def test_metadata_limit_rejected_and_closed(self):
        key = "oversized-metadata"
        self.objects[key] = b"x" * (verifier.CHUNK + 1)
        with self.assertRaises(verifier.VerificationError):
            verifier.read_object(self.client, key, verifier.CHUNK, {})
        self.assertTrue(self.client.bodies[-1].closed)

    def test_json_duplicates_rejected(self):
        with self.assertRaises(verifier.VerificationError):
            verifier.decode_json(b'{"complete":false,"complete":true}')

    def test_streaming_does_not_buffer_archive(self):
        class GeneratedBody:
            remaining = verifier.CHUNK * 3 + 1
            closed = False
            def read(body, count):
                self.assertEqual(verifier.CHUNK, count)
                block = b"x" * min(count, body.remaining)
                body.remaining -= len(block)
                return block
            def close(body):
                body.closed = True
        body = GeneratedBody()
        length = body.remaining
        client = types.SimpleNamespace(get_object=lambda **kw: {
            "Body": body, "ContentLength": length, "ETag": "stable"})
        size, digest = verifier.read_object(client, "stream-test", length, {}, digest_only=True)
        self.assertEqual(length, size)
        self.assertEqual(hashlib.sha256(b"x" * length).hexdigest(), digest)
        self.assertTrue(body.closed)

    def test_malformed_numeric_fields_rejected(self):
        original = copy.deepcopy(self.manifest)
        for value in (True, 1.0):
            self.manifest = copy.deepcopy(original)
            self.manifest["schema"] = value
            self.reject()
        self.manifest = original
        key = original["variants"][0]["record_key"]
        saved = json.loads(self.objects[key])
        for field, value in (("schema", True), ("schema", 1.0),
                             ("size", float(saved["archive"]["size"]))):
            record = copy.deepcopy(saved)
            if field == "size":
                record["archive"][field] = value
            else:
                record[field] = value
            self.objects[key] = json.dumps(record).encode()
            self.reject()

    def test_read_exception_closes_body(self):
        body = mock.Mock()
        body.read.side_effect = OSError("read interrupted")
        client = types.SimpleNamespace(get_object=lambda **kw: {"Body": body, "ETag": "stable", "ContentLength": 10})
        with self.assertRaises(OSError):
            verifier.read_object(client, "broken", 10, {}, digest_only=True)
        body.close.assert_called_once()

    def test_missing_etag_rejected_and_closed(self):
        body = io.BytesIO(b"abc")
        client = types.SimpleNamespace(get_object=lambda **kw: {"Body": body, "ContentLength": 3})
        with self.assertRaises(verifier.VerificationError):
            verifier.read_object(client, "missing-etag", 3, {})
        self.assertTrue(body.closed)

    def archive_retry_fixture(self):
        exceptions = types.ModuleType("botocore.exceptions")
        for name in ("ConnectionClosedError", "IncompleteReadError", "ReadTimeoutError", "ResponseStreamingError"):
            setattr(exceptions, name, type(name, (OSError,), {}))
        StreamError = exceptions.ResponseStreamingError
        body = mock.Mock()
        body.read.side_effect = [b"wrong", StreamError("connection broken")]
        return StreamError, exceptions, body

    def test_interrupted_archive_restarts_hash_with_same_etag(self):
        error, exceptions, interrupted = self.archive_retry_fixture()
        complete = io.BytesIO(b"correct bytes")
        client = mock.Mock()
        client.head_object.return_value = {"ETag": "pinned"}
        client.get_object.side_effect = [
            {"Body": interrupted, "ContentLength": 13, "ETag": "pinned"},
            {"Body": complete, "ContentLength": 13, "ETag": "pinned"}]
        versions = {}
        with mock.patch.dict(sys.modules, {"botocore.exceptions": exceptions}), mock.patch.object(verifier.time, "sleep"):
            size, digest = verifier.read_archive(client, "archive", 13, versions)
        self.assertEqual(13, size)
        self.assertEqual(hashlib.sha256(b"correct bytes").hexdigest(), digest)
        self.assertEqual({"archive": "pinned"}, versions)
        self.assertEqual(2, client.get_object.call_count)
        for call in client.get_object.call_args_list:
            self.assertEqual("pinned", call.kwargs["IfMatch"])
        interrupted.close.assert_called_once()
        self.assertTrue(complete.closed)

    def test_archive_transport_retries_are_bounded_and_fail_closed(self):
        error, exceptions, _ = self.archive_retry_fixture()
        bodies = [mock.Mock() for _ in range(3)]
        for body in bodies:
            body.read.side_effect = error("connection broken")
        client = mock.Mock()
        client.head_object.return_value = {"ETag": "pinned"}
        client.get_object.side_effect = [{"Body": body, "ContentLength": 13, "ETag": "pinned"}
                                         for body in bodies]
        versions = {}
        with mock.patch.dict(sys.modules, {"botocore.exceptions": exceptions}), mock.patch.object(verifier.time, "sleep") as sleep:
            with self.assertRaises(error):
                verifier.read_archive(client, "archive", 13, versions)
        self.assertEqual([mock.call(1), mock.call(2)], sleep.call_args_list)
        client.head_object.assert_called_once_with(Bucket=verifier.BUCKET, Key="archive")
        self.assertEqual(3, client.get_object.call_count)
        self.assertTrue(all(call.kwargs["IfMatch"] == "pinned" for call in client.get_object.call_args_list))
        self.assertEqual({}, versions)
        for body in bodies:
            body.close.assert_called_once()

    def test_archive_retry_rejects_changed_etag(self):
        error, exceptions, interrupted = self.archive_retry_fixture()
        changed = mock.Mock()
        client = mock.Mock()
        client.head_object.return_value = {"ETag": "pinned"}
        client.get_object.side_effect = [
            {"Body": interrupted, "ContentLength": 13, "ETag": "pinned"},
            {"Body": changed, "ContentLength": 13, "ETag": "changed"}]
        with mock.patch.dict(sys.modules, {"botocore.exceptions": exceptions}), mock.patch.object(verifier.time, "sleep"):
            with self.assertRaisesRegex(verifier.VerificationError, "changed"):
                verifier.read_archive(client, "archive", 13, {})
        self.assertEqual(2, client.get_object.call_count)
        changed.read.assert_not_called()
        changed.close.assert_called_once()

    def test_each_transport_error_can_restart(self):
        _, exceptions, _ = self.archive_retry_fixture()
        for name in ("ConnectionClosedError", "IncompleteReadError", "ReadTimeoutError", "ResponseStreamingError"):
            with self.subTest(error=name):
                client = mock.Mock()
                client.head_object.return_value = {"ETag": "pinned"}
                body = io.BytesIO(b"abc")
                client.get_object.side_effect = [getattr(exceptions, name)("interrupted"),
                                                {"Body": body, "ETag": "pinned", "ContentLength": 3}]
                with mock.patch.dict(sys.modules, {"botocore.exceptions": exceptions}), mock.patch.object(verifier.time, "sleep"):
                    self.assertEqual((3, hashlib.sha256(b"abc").hexdigest()),
                                     verifier.read_archive(client, "archive", 3, {}))
                self.assertEqual(2, client.get_object.call_count)
                self.assertTrue(body.closed)

    def test_precondition_failure_is_not_retried(self):
        _, exceptions, _ = self.archive_retry_fixture()
        class ClientError(Exception):
            response = {"ResponseMetadata": {"HTTPStatusCode": 412}}
        client = mock.Mock()
        client.head_object.return_value = {"ETag": "pinned"}
        client.get_object.side_effect = ClientError("precondition failed")
        with mock.patch.dict(sys.modules, {"botocore.exceptions": exceptions}), mock.patch.object(verifier.time, "sleep") as sleep:
            with self.assertRaises(ClientError):
                verifier.read_archive(client, "archive", 13, {})
        client.get_object.assert_called_once()
        sleep.assert_not_called()

    def test_archive_nontransport_error_is_not_retried(self):
        error, exceptions, _ = self.archive_retry_fixture()
        client = mock.Mock()
        client.head_object.return_value = {"ETag": "pinned"}
        client.get_object.side_effect = KeyError("not a transport failure")
        with mock.patch.dict(sys.modules, {"botocore.exceptions": exceptions}):
            with self.assertRaises(KeyError):
                verifier.read_archive(client, "archive", 13, {})
        client.get_object.assert_called_once()

    def test_archive_validation_failure_is_not_retried(self):
        client = mock.Mock()
        client.head_object.return_value = {"ETag": "pinned"}
        body = io.BytesIO(b"short")
        client.get_object.return_value = {"Body": body, "ContentLength": 13, "ETag": "pinned"}
        with self.assertRaisesRegex(verifier.VerificationError, "response length"):
            verifier.read_archive(client, "archive", 13, {})
        client.get_object.assert_called_once()
        self.assertTrue(body.closed)

    def test_explicit_repo_secrets_need_no_profile(self):
        boto = types.ModuleType("boto3")
        boto.client = mock.Mock(return_value="client")
        config = types.ModuleType("botocore.config")
        config.Config = mock.Mock(side_effect=lambda **kw: kw)
        modules = {"boto3": boto, "botocore": types.ModuleType("botocore"),
                   "botocore.config": config}
        with mock.patch.dict(sys.modules, modules), mock.patch.dict(os.environ, {}, clear=True):
            for secrets in ({}, {"R2_ACCESS_KEY_ID": "key"},
                            {"R2_ACCESS_KEY_ID": "bad key", "R2_SECRET_ACCESS_KEY": "secret"}):
                with mock.patch.dict(os.environ, secrets, clear=True):
                    with self.assertRaises(verifier.VerificationError):
                        verifier.client_from_secrets()
            boto.client.assert_not_called()
            with mock.patch.dict(os.environ, {"R2_ACCESS_KEY_ID": "key", "R2_SECRET_ACCESS_KEY": "secret"}):
                self.assertEqual("client", verifier.client_from_secrets())
            kwargs = boto.client.call_args.kwargs
            self.assertEqual(verifier.ENDPOINT, kwargs["endpoint_url"])
            self.assertEqual("key", kwargs["aws_access_key_id"])
            self.assertEqual("secret", kwargs["aws_secret_access_key"])
            self.assertNotIn("profile_name", kwargs)
            self.assertNotIn("aws_session_token", kwargs)


class WorkflowTests(unittest.TestCase):
    def test_dispatch_only_existing_secrets_and_read_only_scope(self):
        text = (Path(__file__).resolve().parents[2] / ".github/workflows/verify-java-r2.yml").read_text()
        self.assertIn("workflow_dispatch:", text)
        self.assertNotIn("  push:", text)
        self.assertNotIn("  pull_request:", text)
        self.assertIn("contents: read", text)
        self.assertIn("runner.environment != 'github-hosted'", text)
        for name in ("R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY"):
            self.assertIn("${{ secrets." + name + " }}", text)
        self.assertNotIn("AWS_PROFILE", text)
        self.assertIn("path: catalog-source", text)
        self.assertIn("--source-root catalog-source", text)
        self.assertEqual(2, text.count("persist-credentials: false"))
        for command in ("build-dist.sh", "mvn ", "aws s3 cp", "promote", "git push"):
            self.assertNotIn(command, text)


if __name__ == "__main__":
    unittest.main()
