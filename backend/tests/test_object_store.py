from __future__ import annotations

import hashlib
from io import BytesIO
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from backend.object_store import (
    LocalObjectStore,
    ObjectStoreError,
    S3ObjectStore,
    S3_READINESS_DIGEST,
    S3_READINESS_KEY_SUFFIX,
    S3_READINESS_PAYLOAD,
)


class MissingObject(Exception):
    def __init__(self) -> None:
        self.response = {
            "Error": {"Code": "NoSuchKey"},
            "ResponseMetadata": {"HTTPStatusCode": 404},
        }


class PreconditionFailed(Exception):
    def __init__(self) -> None:
        self.response = {
            "Error": {"Code": "PreconditionFailed"},
            "ResponseMetadata": {"HTTPStatusCode": 412},
        }


class FakeS3Client:
    def __init__(self) -> None:
        self.objects: dict[tuple[str, str], tuple[bytes, dict[str, str]]] = {}
        self.upload_count = 0
        self.bucket_ready = True
        self.conditional_writes: list[tuple[str, str, object]] = []
        self.multipart_uploads: dict[str, dict[str, object]] = {}
        self.race_objects: dict[tuple[str, str], tuple[bytes, dict[str, str]]] = {}
        self.fail_part = False
        self.write_allowed = True
        self.conditional_writes_supported = True

    def head_object(self, *, Bucket: str, Key: str) -> dict[str, object]:
        try:
            body, metadata = self.objects[(Bucket, Key)]
        except KeyError as error:
            raise MissingObject() from error
        return {"ContentLength": len(body), "Metadata": metadata}

    def put_object(self, **arguments: object) -> None:
        if not self.write_allowed:
            raise RuntimeError("write access denied")
        body = arguments["Body"]
        if hasattr(body, "read"):
            body = body.read()
        if not isinstance(body, bytes):
            raise TypeError("body must contain bytes")
        bucket = str(arguments["Bucket"])
        key = str(arguments["Key"])
        metadata = arguments["Metadata"]
        if not isinstance(metadata, dict):
            raise TypeError("metadata must be a dictionary")
        self.conditional_writes.append(("put", key, arguments.get("IfNoneMatch")))
        race = self.race_objects.pop((bucket, key), None)
        if race is not None:
            self.objects[(bucket, key)] = race
            raise PreconditionFailed()
        if (
            self.conditional_writes_supported
            and arguments.get("IfNoneMatch") == "*"
            and (bucket, key) in self.objects
        ):
            raise PreconditionFailed()
        self.upload_count += 1
        self.objects[(bucket, key)] = (
            body,
            {str(name): str(value) for name, value in metadata.items()},
        )

    def create_multipart_upload(self, **arguments: object) -> dict[str, str]:
        upload_id = f"upload-{len(self.multipart_uploads) + 1}"
        self.multipart_uploads[upload_id] = {
            "bucket": str(arguments["Bucket"]),
            "key": str(arguments["Key"]),
            "metadata": dict(arguments["Metadata"]),
            "parts": {},
        }
        return {"UploadId": upload_id}

    def upload_part(self, **arguments: object) -> dict[str, str]:
        if self.fail_part:
            raise RuntimeError("part upload failed")
        upload = self.multipart_uploads[str(arguments["UploadId"])]
        parts = upload["parts"]
        body = arguments["Body"]
        if not isinstance(parts, dict) or not isinstance(body, bytes):
            raise TypeError("multipart part is invalid")
        part_number = int(arguments["PartNumber"])
        parts[part_number] = body
        return {"ETag": f'"part-{part_number}"'}

    def complete_multipart_upload(self, **arguments: object) -> None:
        upload_id = str(arguments["UploadId"])
        upload = self.multipart_uploads[upload_id]
        bucket = str(upload["bucket"])
        key = str(upload["key"])
        self.conditional_writes.append(("complete", key, arguments.get("IfNoneMatch")))
        if (
            self.conditional_writes_supported
            and arguments.get("IfNoneMatch") == "*"
            and (bucket, key) in self.objects
        ):
            raise PreconditionFailed()
        parts = upload["parts"]
        metadata = upload["metadata"]
        if not isinstance(parts, dict) or not isinstance(metadata, dict):
            raise TypeError("multipart upload is invalid")
        self.objects[(bucket, key)] = (
            b"".join(parts[number] for number in sorted(parts)),
            {str(name): str(value) for name, value in metadata.items()},
        )
        self.upload_count += 1
        del self.multipart_uploads[upload_id]

    def abort_multipart_upload(self, **arguments: object) -> None:
        self.multipart_uploads.pop(str(arguments["UploadId"]), None)

    def get_object(self, *, Bucket: str, Key: str) -> dict[str, BytesIO]:
        try:
            body = self.objects[(Bucket, Key)][0]
        except KeyError as error:
            raise MissingObject() from error
        return {"Body": BytesIO(body)}

    def head_bucket(self, *, Bucket: str) -> None:
        if not self.bucket_ready:
            raise RuntimeError(f"bucket {Bucket} unavailable")


class ObjectStoreTest(unittest.TestCase):
    def test_local_store_commits_verifies_reads_and_deduplicates(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            store = LocalObjectStore(root / "objects")
            payload = b"content-addressed-media"
            digest = hashlib.sha256(payload).hexdigest()
            first = root / "first.upload"
            first.write_bytes(payload)

            locator, deduplicated = store.store(first, len(payload), digest)
            self.assertFalse(deduplicated)
            self.assertFalse(first.exists())
            self.assertTrue(store.matches(locator, len(payload), digest))
            with store.open_verified(locator, len(payload), digest) as source:
                self.assertEqual(payload, source.read())

            second = root / "second.upload"
            second.write_bytes(payload)
            repeated_locator, deduplicated = store.store(second, len(payload), digest)
            self.assertTrue(deduplicated)
            self.assertEqual(locator, repeated_locator)
            self.assertFalse(second.exists())
            self.assertEqual(
                (locator, True),
                store.store(root / "missing-after-commit.upload", len(payload), digest),
            )

    def test_local_store_rejects_corrupt_source_and_locator_escape(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            store = LocalObjectStore(root / "objects")
            source = root / "source.upload"
            source.write_bytes(b"wrong")
            digest = hashlib.sha256(b"expected").hexdigest()
            with self.assertRaisesRegex(ObjectStoreError, "integrity"):
                store.store(source, source.stat().st_size, digest)
            with self.assertRaises(ObjectStoreError):
                store.open_verified(str(source), source.stat().st_size, digest)

    def test_s3_store_commits_with_metadata_reads_and_deduplicates(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            client = FakeS3Client()
            store = S3ObjectStore(
                "https://s3.example.test", "helmet-media", "region-1", client=client
            )
            payload = b"s3-media-payload"
            digest = hashlib.sha256(payload).hexdigest()
            source = Path(temporary) / "first.upload"
            source.write_bytes(payload)

            locator, deduplicated = store.store(source, len(payload), digest)
            self.assertFalse(deduplicated)
            self.assertEqual(1, client.upload_count)
            self.assertTrue(store.matches(locator, len(payload), digest))
            with store.open_verified(locator, len(payload), digest, Path(temporary)) as body:
                self.assertEqual(payload, body.read())

            repeated = Path(temporary) / "second.upload"
            repeated.write_bytes(payload)
            self.assertEqual((locator, True), store.store(repeated, len(payload), digest))
            self.assertEqual(1, client.upload_count)
            self.assertEqual(
                (locator, True),
                store.store(Path(temporary) / "missing-after-commit.upload", len(payload), digest),
            )

            multipart_payload = b"multipart-media-payload"
            multipart_digest = hashlib.sha256(multipart_payload).hexdigest()
            multipart_source = Path(temporary) / "multipart.upload"
            multipart_source.write_bytes(multipart_payload)
            with patch("backend.object_store.MAX_S3_SINGLE_PUT_BYTES", 1):
                multipart_locator, multipart_deduplicated = store.store(
                    multipart_source,
                    len(multipart_payload),
                    multipart_digest,
                )
            self.assertFalse(multipart_deduplicated)
            self.assertTrue(
                store.matches(multipart_locator, len(multipart_payload), multipart_digest)
            )
            self.assertEqual({}, client.multipart_uploads)
            self.assertTrue(all(item[2] == "*" for item in client.conditional_writes))

            race_payload = b"concurrent-identical-media"
            race_digest = hashlib.sha256(race_payload).hexdigest()
            race_locator = store.locator_for(race_digest)
            race_key = race_locator.split("/", 3)[3]
            client.race_objects[("helmet-media", race_key)] = (
                race_payload,
                {"sha256": race_digest},
            )
            race_source = Path(temporary) / "race.upload"
            race_source.write_bytes(race_payload)
            self.assertEqual(
                (race_locator, True),
                store.store(race_source, len(race_payload), race_digest),
            )
            self.assertFalse(race_source.exists())

    def test_s3_store_fails_closed_on_bad_metadata_and_readiness(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            client = FakeS3Client()
            store = S3ObjectStore(
                "https://s3.example.test", "helmet-media", "region-1", client=client
            )
            payload = b"existing-payload"
            digest = hashlib.sha256(payload).hexdigest()
            locator = store.locator_for(digest)
            key = locator.split("/", 3)[3]
            client.objects[("helmet-media", key)] = (payload, {"sha256": "0" * 64})
            source = Path(temporary) / "source.upload"
            source.write_bytes(payload)
            with self.assertRaisesRegex(ObjectStoreError, "conflicts"):
                store.store(source, len(payload), digest)
            self.assertTrue(source.exists())

            client.objects[("helmet-media", key)] = (
                b"Existing-payload",
                {"sha256": digest},
            )
            self.assertFalse(store.matches(locator, len(payload), digest))
            with self.assertRaisesRegex(ObjectStoreError, "SHA-256"):
                store.open_verified(locator, len(payload), digest, Path(temporary))

            race_payload = b"concurrent expected body"
            race_digest = hashlib.sha256(race_payload).hexdigest()
            race_locator = store.locator_for(race_digest)
            race_key = race_locator.split("/", 3)[3]
            client.race_objects[("helmet-media", race_key)] = (
                b"Concurrent expected body",
                {"sha256": race_digest},
            )
            race_source = Path(temporary) / "corrupt-race.upload"
            race_source.write_bytes(race_payload)
            with self.assertRaisesRegex(ObjectStoreError, "conditional S3 upload failed"):
                store.store(race_source, len(race_payload), race_digest)
            self.assertTrue(race_source.exists())

            failed_payload = b"failed multipart body"
            failed_digest = hashlib.sha256(failed_payload).hexdigest()
            failed_source = Path(temporary) / "failed-multipart.upload"
            failed_source.write_bytes(failed_payload)
            client.fail_part = True
            with patch("backend.object_store.MAX_S3_SINGLE_PUT_BYTES", 1):
                with self.assertRaisesRegex(ObjectStoreError, "conditional S3 upload failed"):
                    store.store(failed_source, len(failed_payload), failed_digest)
            self.assertTrue(failed_source.exists())
            self.assertEqual({}, client.multipart_uploads)

            client.bucket_ready = False
            with self.assertRaisesRegex(ObjectStoreError, "not accessible"):
                store.ready()

    def test_s3_readiness_proves_write_read_and_periodic_conditional_create(self) -> None:
        client = FakeS3Client()
        store = S3ObjectStore(
            "https://s3.example.test", "helmet-media", "region-1", client=client
        )
        readiness_key = f"helmet-media/{S3_READINESS_KEY_SUFFIX}"

        with patch("backend.object_store.time.monotonic", return_value=0.0):
            store.ready()

        self.assertEqual(
            (S3_READINESS_PAYLOAD, {"sha256": S3_READINESS_DIGEST}),
            client.objects[("helmet-media", readiness_key)],
        )
        self.assertEqual(1, client.upload_count)
        self.assertEqual(
            [("put", readiness_key, "*"), ("put", readiness_key, "*")],
            client.conditional_writes,
        )

        with patch("backend.object_store.time.monotonic", return_value=1.0):
            store.ready()
        self.assertEqual(2, len(client.conditional_writes))
        with patch("backend.object_store.time.monotonic", return_value=301.0):
            store.ready()
        self.assertEqual(3, len(client.conditional_writes))
        client.bucket_ready = False
        with self.assertRaisesRegex(ObjectStoreError, "not accessible"):
            store.ready()

    def test_s3_readiness_rejects_read_only_or_unsafe_storage(self) -> None:
        read_only_client = FakeS3Client()
        read_only_client.write_allowed = False
        read_only_store = S3ObjectStore(
            "https://s3.example.test", "helmet-media", "region-1", client=read_only_client
        )
        with self.assertRaisesRegex(ObjectStoreError, "cannot be created"):
            read_only_store.ready()

        unsafe_client = FakeS3Client()
        unsafe_client.conditional_writes_supported = False
        unsafe_store = S3ObjectStore(
            "https://s3.example.test", "helmet-media", "region-1", client=unsafe_client
        )
        with self.assertRaisesRegex(ObjectStoreError, "does not enforce If-None-Match"):
            unsafe_store.ready()

        corrupt_client = FakeS3Client()
        readiness_key = f"helmet-media/{S3_READINESS_KEY_SUFFIX}"
        corrupt_client.objects[("helmet-media", readiness_key)] = (
            b"Helmet-s3-readiness-v1\n",
            {"sha256": S3_READINESS_DIGEST},
        )
        corrupt_store = S3ObjectStore(
            "https://s3.example.test", "helmet-media", "region-1", client=corrupt_client
        )
        with self.assertRaisesRegex(ObjectStoreError, "body is invalid"):
            corrupt_store.ready()

    def test_s3_configuration_rejects_credentials_and_unsafe_prefix(self) -> None:
        client = FakeS3Client()
        with self.assertRaises(ValueError):
            S3ObjectStore("https://user@s3.example.test", "helmet-media", "region-1", client=client)
        with self.assertRaises(ValueError):
            S3ObjectStore("https://s3.example.test", "helmet-media", "region-1", "../media", client=client)
        with self.assertRaisesRegex(ValueError, "configured together"):
            S3ObjectStore(
                "https://s3.example.test",
                "helmet-media",
                "region-1",
                access_key_id="access-key-without-secret",
                client=client,
            )

    def test_s3_client_receives_explicit_file_loaded_credentials(self) -> None:
        captured: dict[str, object] = {}
        client = FakeS3Client()

        class FakeBoto3:
            @staticmethod
            def client(service: str, **arguments: object) -> FakeS3Client:
                captured["service"] = service
                captured.update(arguments)
                return client

        with patch.dict("sys.modules", {"boto3": FakeBoto3()}):
            store = S3ObjectStore(
                "https://s3.example.test",
                "helmet-media",
                "region-1",
                access_key_id="file-access-key",
                secret_access_key="file-secret-key",
            )
        self.assertIs(client, store.client)
        self.assertEqual("s3", captured["service"])
        self.assertEqual("file-access-key", captured["aws_access_key_id"])
        self.assertEqual("file-secret-key", captured["aws_secret_access_key"])


if __name__ == "__main__":
    unittest.main()
