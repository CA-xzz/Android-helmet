"""Read and materialize owner-only files without following replaceable paths."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import secrets
import stat
import sys


def read_owner_only_file(path: Path, maximum_bytes: int) -> bytes:
    if maximum_bytes < 1:
        raise ValueError("private file size limit must be positive")
    descriptor = -1
    try:
        metadata = path.lstat()
        if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
            raise ValueError("must reference an owner-only physical regular file")
        if metadata.st_mode & 0o077:
            raise ValueError(
                "must be owner-only; group and other permissions must be zero"
            )
        descriptor = os.open(
            path,
            os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0),
        )
        opened = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened.st_mode)
            or opened.st_mode & 0o077
            or opened.st_dev != metadata.st_dev
            or opened.st_ino != metadata.st_ino
        ):
            raise ValueError("changed while it was being opened")
        if opened.st_size > maximum_bytes:
            raise ValueError(f"exceeds {maximum_bytes} bytes")
        stream = os.fdopen(descriptor, "rb")
        descriptor = -1
        with stream:
            raw = stream.read(maximum_bytes + 1)
    except ValueError:
        raise
    except OSError as error:
        raise ValueError("cannot be read") from error
    finally:
        if descriptor >= 0:
            os.close(descriptor)
    if len(raw) > maximum_bytes:
        raise ValueError(f"exceeds {maximum_bytes} bytes")
    return raw


def read_owner_only_text(path: Path, maximum_bytes: int) -> str:
    raw = read_owner_only_file(path, maximum_bytes)
    try:
        value = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValueError("must contain UTF-8 text") from error
    if "\x00" in value:
        raise ValueError("must not contain NUL bytes")
    if value.endswith("\r\n"):
        value = value[:-2]
    elif value.endswith("\n"):
        value = value[:-1]
    if "\r" in value or "\n" in value:
        raise ValueError("must contain a single line with at most one trailing line ending")
    if not value.strip():
        raise ValueError("must not be empty")
    return value


def materialize_owner_only_file(
    source: Path,
    destination: Path,
    maximum_bytes: int,
) -> None:
    payload = read_owner_only_file(source, maximum_bytes)
    _materialize_owner_only_payload(payload, destination)


def _materialize_owner_only_payload(payload: bytes, destination: Path) -> None:
    destination_name = destination.name
    if destination_name in {"", ".", ".."}:
        raise ValueError("destination must name a file")

    parent = destination.parent
    parent_descriptor = -1
    output_descriptor = -1
    temporary_name = f".{destination_name}.tmp.{secrets.token_hex(8)}"
    try:
        parent_metadata = parent.lstat()
        if stat.S_ISLNK(parent_metadata.st_mode) or not stat.S_ISDIR(
            parent_metadata.st_mode
        ):
            raise ValueError("destination parent must be a physical directory")
        if parent_metadata.st_mode & 0o077:
            raise ValueError("destination parent must be owner-only")
        parent_descriptor = os.open(
            parent,
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_NOFOLLOW", 0),
        )
        opened_parent = os.fstat(parent_descriptor)
        if (
            not stat.S_ISDIR(opened_parent.st_mode)
            or opened_parent.st_mode & 0o077
            or opened_parent.st_dev != parent_metadata.st_dev
            or opened_parent.st_ino != parent_metadata.st_ino
        ):
            raise ValueError("destination parent changed while it was being opened")
        try:
            existing = os.stat(
                destination_name,
                dir_fd=parent_descriptor,
                follow_symlinks=False,
            )
        except FileNotFoundError:
            existing = None
        if existing is not None and not stat.S_ISREG(existing.st_mode):
            raise ValueError("destination must be absent or a physical regular file")

        output_descriptor = os.open(
            temporary_name,
            os.O_WRONLY
            | os.O_CREAT
            | os.O_EXCL
            | getattr(os, "O_NOFOLLOW", 0),
            0o600,
            dir_fd=parent_descriptor,
        )
        remaining = memoryview(payload)
        while remaining:
            written = os.write(output_descriptor, remaining)
            if written < 1:
                raise OSError("private file write made no progress")
            remaining = remaining[written:]
        os.fchmod(output_descriptor, 0o600)
        os.fsync(output_descriptor)
        os.close(output_descriptor)
        output_descriptor = -1
        os.replace(
            temporary_name,
            destination_name,
            src_dir_fd=parent_descriptor,
            dst_dir_fd=parent_descriptor,
        )
        os.fsync(parent_descriptor)
    except ValueError:
        raise
    except OSError as error:
        raise ValueError("destination cannot be written safely") from error
    finally:
        if output_descriptor >= 0:
            os.close(output_descriptor)
        if parent_descriptor >= 0:
            try:
                os.unlink(temporary_name, dir_fd=parent_descriptor)
            except FileNotFoundError:
                pass
            finally:
                os.close(parent_descriptor)


def materialize_postgres_password_file(
    source: Path,
    destination: Path,
    user: str,
    maximum_bytes: int,
) -> None:
    if not user or any(character in user for character in "\x00\r\n"):
        raise ValueError("PostgreSQL user must be non-empty single-line text")
    password = read_owner_only_text(source, maximum_bytes)

    def escape(value: str) -> str:
        return value.replace("\\", "\\\\").replace(":", "\\:")

    payload = f"*:*:*:{escape(user)}:{escape(password)}\n".encode("utf-8")
    _materialize_owner_only_payload(payload, destination)


def _positive_byte_limit(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be an integer") from error
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be positive")
    return parsed


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Safely read deployment-owned private files."
    )
    commands = parser.add_subparsers(dest="command", required=True)
    read_text = commands.add_parser("read-text")
    read_text.add_argument("path", type=Path)
    read_text.add_argument("--maximum-bytes", type=_positive_byte_limit, default=16384)
    materialize = commands.add_parser("materialize")
    materialize.add_argument("source", type=Path)
    materialize.add_argument("destination", type=Path)
    materialize.add_argument(
        "--maximum-bytes", type=_positive_byte_limit, default=65536
    )
    pgpass = commands.add_parser("materialize-pgpass")
    pgpass.add_argument("source", type=Path)
    pgpass.add_argument("destination", type=Path)
    pgpass.add_argument("user")
    pgpass.add_argument("--maximum-bytes", type=_positive_byte_limit, default=16384)
    values = parser.parse_args(arguments)
    try:
        if values.command == "read-text":
            sys.stdout.write(read_owner_only_text(values.path, values.maximum_bytes))
        elif values.command == "materialize":
            materialize_owner_only_file(
                values.source,
                values.destination,
                values.maximum_bytes,
            )
        else:
            materialize_postgres_password_file(
                values.source,
                values.destination,
                values.user,
                values.maximum_bytes,
            )
    except ValueError as error:
        print(f"ERROR: private file validation failed: {error}", file=sys.stderr)
        return 78
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
