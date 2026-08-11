"""Content-addressed local and S3-compatible object storage."""

from __future__ import annotations

from contextlib import AbstractContextManager
import hashlib
import os
from pathlib import Path
import re
import stat
import tempfile
import time
from typing import Any, BinaryIO
from urllib.parse import urlparse


SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
BUCKET_RE = re.compile(r"^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
MAX_S3_SINGLE_PUT_BYTES = 4 * 1024 * 1024 * 1024
S3_MULTIPART_PART_BYTES = 64 * 1024 * 1024
S3_READINESS_PAYLOAD = b"helmet-s3-readiness-v1\n"
S3_READINESS_DIGEST = hashlib.sha256(S3_READINESS_PAYLOAD).hexdigest()
S3_READINESS_KEY_SUFFIX = "_readiness/conditional-write-v1.bin"
S3_CAPABILITY_RECHECK_SECONDS = 300.0


class ObjectStoreError(RuntimeError):
    pass


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(64 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _stream_matches(
    source: BinaryIO,
    byte_size: int,
    digest: str,
    destination: BinaryIO | None = None,
) -> bool:
    observed_size = 0
    observed_digest = hashlib.sha256()
    try:
        while chunk := source.read(64 * 1024):
            if not isinstance(chunk, bytes):
                raise ObjectStoreError("object storage returned a non-byte body")
            observed_size += len(chunk)
            if observed_size > byte_size:
                return False
            observed_digest.update(chunk)
            if destination is not None:
                destination.write(chunk)
    except ObjectStoreError:
        raise
    except Exception as error:
        raise ObjectStoreError("object storage body read failed") from error
    return observed_size == byte_size and observed_digest.hexdigest() == digest


def _close_stream(source: Any) -> None:
    close = getattr(source, "close", None)
    if close is not None:
        try:
            close()
        except Exception:
            pass


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def upload_file_if_absent(
    client: Any,
    bucket: str,
    key: str,
    source: Path,
    byte_size: int,
    *,
    content_type: str,
    metadata: dict[str, str],
    server_side_encryption: str = "",
    maximum_single_put_bytes: int = MAX_S3_SINGLE_PUT_BYTES,
    multipart_part_bytes: int = S3_MULTIPART_PART_BYTES,
) -> None:
    common_arguments: dict[str, Any] = {
        "Bucket": bucket,
        "Key": key,
        "ContentType": content_type,
        "Metadata": metadata,
    }
    if server_side_encryption:
        common_arguments["ServerSideEncryption"] = server_side_encryption
    try:
        if byte_size <= maximum_single_put_bytes:
            with source.open("rb") as body:
                client.put_object(
                    **common_arguments,
                    Body=body,
                    ContentLength=byte_size,
                    IfNoneMatch="*",
                )
            return

        upload_id: str | None = None
        try:
            created = client.create_multipart_upload(**common_arguments)
            upload_id = created.get("UploadId") if isinstance(created, dict) else None
            if not isinstance(upload_id, str) or not upload_id:
                raise ObjectStoreError("S3 multipart upload ID is invalid")
            parts: list[dict[str, Any]] = []
            with source.open("rb") as body:
                part_number = 1
                while chunk := body.read(multipart_part_bytes):
                    uploaded = client.upload_part(
                        Bucket=bucket,
                        Key=key,
                        UploadId=upload_id,
                        PartNumber=part_number,
                        Body=chunk,
                        ContentLength=len(chunk),
                    )
                    etag = uploaded.get("ETag") if isinstance(uploaded, dict) else None
                    if not isinstance(etag, str) or not etag:
                        raise ObjectStoreError("S3 multipart part ETag is invalid")
                    parts.append({"ETag": etag, "PartNumber": part_number})
                    part_number += 1
            client.complete_multipart_upload(
                Bucket=bucket,
                Key=key,
                UploadId=upload_id,
                MultipartUpload={"Parts": parts},
                IfNoneMatch="*",
            )
            upload_id = None
        finally:
            if upload_id is not None:
                try:
                    client.abort_multipart_upload(
                        Bucket=bucket,
                        Key=key,
                        UploadId=upload_id,
                    )
                except Exception:
                    pass
    except ObjectStoreError:
        raise
    except Exception as error:
        raise ObjectStoreError("conditional S3 upload failed") from error


class LocalObjectStore:
    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def locator_for(self, digest: str) -> str:
        if not SHA256_RE.fullmatch(digest):
            raise ValueError("object digest is invalid")
        return str(self.root / digest[:2] / f"{digest}.bin")

    def matches(self, locator: str, byte_size: int, digest: str) -> bool:
        try:
            with self.open_verified(locator, byte_size, digest):
                return True
        except ObjectStoreError:
            return False

    def store(self, source: Path, byte_size: int, digest: str) -> tuple[str, bool]:
        locator = self.locator_for(digest)
        destination = Path(locator)
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            if not self.matches(locator, byte_size, digest):
                raise ObjectStoreError("stored local object conflicts with upload")
            source.unlink(missing_ok=True)
            return locator, True
        if byte_size < 0 or not source.is_file():
            raise ObjectStoreError("local upload source is missing")
        if source.stat().st_size != byte_size or _sha256_file(source) != digest:
            raise ObjectStoreError("local upload source failed integrity verification")
        os.replace(source, destination)
        with destination.open("rb") as stored:
            os.fsync(stored.fileno())
        _fsync_directory(destination.parent)
        if not self.matches(locator, byte_size, digest):
            raise ObjectStoreError("stored local object failed integrity verification")
        return locator, False

    def open_verified(
        self,
        locator: str,
        byte_size: int,
        digest: str,
        staging_root: Path | None = None,
    ) -> AbstractContextManager[BinaryIO]:
        del staging_root
        source: BinaryIO | None = None
        try:
            if Path(locator) != Path(self.locator_for(digest)):
                raise ObjectStoreError("local object locator does not match its digest")
            path = Path(locator).resolve(strict=True)
            path.relative_to(self.root)
            if not path.is_file():
                raise ObjectStoreError("local object is not a regular file")
            source = path.open("rb")
            metadata = os.fstat(source.fileno())
            if (
                not stat.S_ISREG(metadata.st_mode)
                or metadata.st_size != byte_size
                or not _stream_matches(source, byte_size, digest)
            ):
                raise ObjectStoreError("local object failed size or SHA-256 verification")
            source.seek(0)
            return source
        except ObjectStoreError:
            if source is not None:
                source.close()
            raise
        except (OSError, ValueError) as error:
            if source is not None:
                source.close()
            raise ObjectStoreError("local object locator leaves storage root") from error

    def ready(self) -> None:
        if not self.root.is_dir() or not os.access(self.root, os.R_OK | os.W_OK | os.X_OK):
            raise ObjectStoreError("local object storage is not accessible")


class S3ObjectStore:
    def __init__(
        self,
        endpoint_url: str,
        bucket: str,
        region: str,
        prefix: str = "helmet-media",
        server_side_encryption: str = "AES256",
        *,
        access_key_id: str = "",
        secret_access_key: str = "",
        client: Any | None = None,
    ) -> None:
        parsed = urlparse(endpoint_url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username:
            raise ValueError("S3 endpoint must be an HTTP(S) URL without credentials")
        if not BUCKET_RE.fullmatch(bucket):
            raise ValueError("S3 bucket name is invalid")
        normalized_prefix = prefix.strip("/")
        if not normalized_prefix or ".." in normalized_prefix.split("/"):
            raise ValueError("S3 object prefix is invalid")
        if server_side_encryption not in {"", "AES256", "aws:kms"}:
            raise ValueError("unsupported S3 server-side encryption mode")
        if bool(access_key_id) != bool(secret_access_key):
            raise ValueError("S3 access key ID and secret access key must be configured together")
        self.endpoint_url = endpoint_url
        self.bucket = bucket
        self.region = region
        self.prefix = normalized_prefix
        self.server_side_encryption = server_side_encryption
        self._capabilities_verified_until = 0.0
        if client is None:
            try:
                import boto3
            except ImportError as error:
                raise RuntimeError("boto3 is required for S3 production storage") from error
            client_arguments = {
                "endpoint_url": endpoint_url,
                "region_name": region,
            }
            if access_key_id:
                client_arguments.update(
                    aws_access_key_id=access_key_id,
                    aws_secret_access_key=secret_access_key,
                )
            client = boto3.client("s3", **client_arguments)
        self.client = client

    def locator_for(self, digest: str) -> str:
        if not SHA256_RE.fullmatch(digest):
            raise ValueError("object digest is invalid")
        key = f"{self.prefix}/{digest[:2]}/{digest}.bin"
        return f"s3://{self.bucket}/{key}"

    def _key(self, locator: str) -> str:
        parsed = urlparse(locator)
        if parsed.scheme != "s3" or parsed.netloc != self.bucket:
            raise ObjectStoreError("S3 object locator belongs to another bucket")
        key = parsed.path.lstrip("/")
        if not key.startswith(f"{self.prefix}/"):
            raise ObjectStoreError("S3 object locator leaves the configured prefix")
        return key

    @staticmethod
    def _not_found(error: Exception) -> bool:
        response = getattr(error, "response", {})
        code = str(response.get("Error", {}).get("Code", ""))
        status = response.get("ResponseMetadata", {}).get("HTTPStatusCode")
        return code in {"404", "NoSuchKey", "NotFound"} or status == 404

    def _head(self, locator: str) -> dict[str, Any] | None:
        try:
            return self.client.head_object(Bucket=self.bucket, Key=self._key(locator))
        except Exception as error:
            if self._not_found(error):
                return None
            raise ObjectStoreError("S3 object metadata request failed") from error

    def matches(self, locator: str, byte_size: int, digest: str) -> bool:
        if locator != self.locator_for(digest):
            return False
        head = self._head(locator)
        if not (
            head
            and head.get("ContentLength") == byte_size
            and head.get("Metadata", {}).get("sha256") == digest
        ):
            return False
        body = self._open_body(locator)
        try:
            return _stream_matches(body, byte_size, digest)
        finally:
            _close_stream(body)

    def store(self, source: Path, byte_size: int, digest: str) -> tuple[str, bool]:
        locator = self.locator_for(digest)
        head = self._head(locator)
        if head is not None:
            if not self.matches(locator, byte_size, digest):
                raise ObjectStoreError("stored S3 object conflicts with upload")
            source.unlink(missing_ok=True)
            return locator, True
        if byte_size < 0 or not source.is_file():
            raise ObjectStoreError("S3 upload source is missing")
        if source.stat().st_size != byte_size or _sha256_file(source) != digest:
            raise ObjectStoreError("S3 upload source failed integrity verification")
        try:
            upload_file_if_absent(
                self.client,
                self.bucket,
                self._key(locator),
                source,
                byte_size,
                content_type="application/octet-stream",
                metadata={"sha256": digest},
                server_side_encryption=self.server_side_encryption,
                maximum_single_put_bytes=MAX_S3_SINGLE_PUT_BYTES,
                multipart_part_bytes=S3_MULTIPART_PART_BYTES,
            )
        except ObjectStoreError:
            if self.matches(locator, byte_size, digest):
                source.unlink(missing_ok=True)
                return locator, True
            raise
        if not self.matches(locator, byte_size, digest):
            raise ObjectStoreError("stored S3 object failed integrity verification")
        source.unlink()
        return locator, False

    def _open_body(self, locator: str) -> BinaryIO:
        try:
            return self.client.get_object(Bucket=self.bucket, Key=self._key(locator))["Body"]
        except Exception as error:
            raise ObjectStoreError("S3 object download failed") from error

    def open_verified(
        self,
        locator: str,
        byte_size: int,
        digest: str,
        staging_root: Path | None = None,
    ) -> AbstractContextManager[BinaryIO]:
        if locator != self.locator_for(digest):
            raise ObjectStoreError("S3 object locator does not match its digest")
        head = self._head(locator)
        if not (
            head
            and head.get("ContentLength") == byte_size
            and head.get("Metadata", {}).get("sha256") == digest
        ):
            raise ObjectStoreError("S3 object metadata does not match its digest identity")
        temporary: BinaryIO | None = None
        body: BinaryIO | None = None
        try:
            temporary = tempfile.TemporaryFile(mode="w+b", dir=staging_root)
            body = self._open_body(locator)
            if not _stream_matches(body, byte_size, digest, temporary):
                raise ObjectStoreError("S3 object body failed size or SHA-256 verification")
            temporary.flush()
            os.fsync(temporary.fileno())
            temporary.seek(0)
            return temporary
        except ObjectStoreError:
            if temporary is not None:
                temporary.close()
            raise
        except OSError as error:
            if temporary is not None:
                temporary.close()
            raise ObjectStoreError("S3 verified read staging failed") from error
        finally:
            if body is not None:
                _close_stream(body)

    def ready(self) -> None:
        try:
            self.client.head_bucket(Bucket=self.bucket)
        except Exception as error:
            raise ObjectStoreError("S3 bucket is not accessible") from error
        self.verify_capabilities()

    def verify_capabilities(self) -> None:
        now = time.monotonic()
        if now < self._capabilities_verified_until:
            return
        self._verify_capabilities()
        self._capabilities_verified_until = now + S3_CAPABILITY_RECHECK_SECONDS

    @staticmethod
    def _precondition_failed(error: Exception) -> bool:
        response = getattr(error, "response", {})
        code = str(response.get("Error", {}).get("Code", ""))
        status = response.get("ResponseMetadata", {}).get("HTTPStatusCode")
        return code in {"412", "PreconditionFailed"} or status == 412

    def _verify_capabilities(self) -> None:
        key = f"{self.prefix}/{S3_READINESS_KEY_SUFFIX}"
        locator = f"s3://{self.bucket}/{key}"
        arguments: dict[str, Any] = {
            "Bucket": self.bucket,
            "Key": key,
            "Body": S3_READINESS_PAYLOAD,
            "ContentLength": len(S3_READINESS_PAYLOAD),
            "ContentType": "application/octet-stream",
            "Metadata": {"sha256": S3_READINESS_DIGEST},
            "IfNoneMatch": "*",
        }
        if self.server_side_encryption:
            arguments["ServerSideEncryption"] = self.server_side_encryption
        head = self._head(locator)
        if head is None:
            try:
                self.client.put_object(**arguments)
            except Exception as error:
                if not self._precondition_failed(error):
                    raise ObjectStoreError("S3 readiness object cannot be created") from error
        head = self._head(locator)
        if not (
            head
            and head.get("ContentLength") == len(S3_READINESS_PAYLOAD)
            and head.get("Metadata", {}).get("sha256") == S3_READINESS_DIGEST
        ):
            raise ObjectStoreError("S3 readiness object metadata is invalid")
        body = self._open_body(locator)
        try:
            if not _stream_matches(body, len(S3_READINESS_PAYLOAD), S3_READINESS_DIGEST):
                raise ObjectStoreError("S3 readiness object body is invalid")
        finally:
            _close_stream(body)
        try:
            self.client.put_object(**arguments)
        except Exception as error:
            if self._precondition_failed(error):
                return
            raise ObjectStoreError("S3 readiness conditional write failed") from error
        raise ObjectStoreError("S3 storage does not enforce If-None-Match")
