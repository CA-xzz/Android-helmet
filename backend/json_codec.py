"""Strict, bounded JSON decoding for production trust boundaries."""

from __future__ import annotations

import json
import math
import os
from pathlib import Path
import stat
from typing import Any


MAX_JSON_NESTING_DEPTH = 64


class StrictJsonError(ValueError):
    """Raised when a document is not an unambiguous RFC 8259 JSON value."""


def _reject_constant(value: str) -> None:
    raise StrictJsonError(f"non-standard JSON number {value} is not allowed")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise StrictJsonError(f"duplicate JSON object field {key!r}")
        value[key] = item
    return value


def _require_bounded_nesting(value: Any, maximum_depth: int) -> None:
    if maximum_depth < 1:
        raise ValueError("maximum JSON nesting depth must be positive")
    stack: list[tuple[Any, int]] = [(value, 1)]
    while stack:
        item, depth = stack.pop()
        if isinstance(item, float) and not math.isfinite(item):
            raise StrictJsonError("JSON numbers must be finite")
        if not isinstance(item, (dict, list)):
            continue
        if depth > maximum_depth:
            raise StrictJsonError("JSON nesting depth exceeds the allowed maximum")
        children = item.values() if isinstance(item, dict) else item
        stack.extend((child, depth + 1) for child in children)


def loads_strict(
    raw: str | bytes | bytearray,
    *,
    maximum_depth: int = MAX_JSON_NESTING_DEPTH,
) -> Any:
    """Decode JSON while rejecting duplicate fields, extensions and deep nesting."""

    if isinstance(raw, (bytes, bytearray)):
        try:
            text = bytes(raw).decode("utf-8")
        except UnicodeDecodeError as error:
            raise StrictJsonError("JSON document is not valid UTF-8") from error
    elif isinstance(raw, str):
        text = raw
    else:
        raise TypeError("JSON input must be text or bytes")
    try:
        value = json.loads(
            text,
            object_pairs_hook=_unique_object,
            parse_constant=_reject_constant,
        )
    except StrictJsonError:
        raise
    except (json.JSONDecodeError, RecursionError, ValueError) as error:
        raise StrictJsonError("JSON document is invalid") from error
    _require_bounded_nesting(value, maximum_depth)
    return value


def load_strict_json_file(
    path: Path,
    maximum_bytes: int,
    *,
    maximum_depth: int = MAX_JSON_NESTING_DEPTH,
) -> Any:
    """Read and strictly decode a JSON file without loading an oversized document."""

    if maximum_bytes < 1:
        raise ValueError("maximum JSON file size must be positive")
    try:
        expected = os.lstat(path)
    except FileNotFoundError:
        raise
    except OSError as error:
        raise StrictJsonError("JSON path cannot be inspected") from error
    if stat.S_ISLNK(expected.st_mode) or not stat.S_ISREG(expected.st_mode):
        raise StrictJsonError("JSON path must reference a physical regular file")
    flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_NONBLOCK", 0)
    )
    try:
        descriptor = os.open(path, flags)
    except FileNotFoundError:
        raise
    except OSError as error:
        raise StrictJsonError(
            "JSON path must reference a readable physical regular file"
        ) from error
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode):
            raise StrictJsonError("JSON path must reference a physical regular file")
        if (expected.st_dev, expected.st_ino) != (before.st_dev, before.st_ino):
            raise StrictJsonError("JSON path changed while it was being opened")
        if before.st_size > maximum_bytes:
            raise StrictJsonError("JSON document exceeds the allowed size")
        raw = bytearray()
        while len(raw) <= maximum_bytes:
            chunk = os.read(descriptor, min(64 * 1024, maximum_bytes + 1 - len(raw)))
            if not chunk:
                break
            raw.extend(chunk)
        after = os.fstat(descriptor)
        if (
            before.st_dev,
            before.st_ino,
            before.st_size,
            before.st_mtime_ns,
            before.st_ctime_ns,
        ) != (
            after.st_dev,
            after.st_ino,
            after.st_size,
            after.st_mtime_ns,
            after.st_ctime_ns,
        ) or len(raw) != after.st_size:
            raise StrictJsonError("JSON document changed while it was being read")
    finally:
        os.close(descriptor)
    if len(raw) > maximum_bytes:
        raise StrictJsonError("JSON document exceeds the allowed size")
    return loads_strict(raw, maximum_depth=maximum_depth)
