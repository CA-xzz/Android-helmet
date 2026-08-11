#!/usr/bin/env python3
"""Small persistent media-ingest service used by the helmet integration stack."""

from __future__ import annotations

import argparse
import base64
from collections.abc import Iterable, Iterator
import hashlib
import hmac
import json
import logging
import math
import os
from pathlib import Path
import re
import sqlite3
import sys
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable
from urllib.parse import parse_qs, quote, unquote, urlparse
import uuid
from dataclasses import dataclass

if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from backend.database import connect_postgres
from backend.object_store import LocalObjectStore, ObjectStoreError, S3ObjectStore
from backend.private_file import read_owner_only_file, read_owner_only_text
from backend.json_codec import StrictJsonError, loads_strict


ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
CONTENT_RANGE_PATTERN = re.compile(r"^bytes (\d+)-(\d+)/(\d+)$")
MAX_METADATA_BYTES = 64 * 1024
MAX_MEDIA_BYTES = 16 * 1024 * 1024 * 1024
MAX_TRACK_BATCH_BYTES = 1024 * 1024
MAX_TRACK_POINTS_PER_BATCH = 200
MAX_DEVICE_STATUS_BYTES = 64 * 1024
MAX_SIGNAL_BYTES = 1024 * 1024
MAX_COMMUNICATION_RESPONSE_BYTES = 8 * 1024 * 1024
MAX_COMMUNICATION_QUERY_ROWS = 4
MAX_ALERT_BYTES = 256 * 1024
MAX_PRIVATE_SECRET_BYTES = 16 * 1024
MAX_SIGNED_INT64 = 9_223_372_036_854_775_807
TRACK_SOURCES = {"ANDROID_GNSS", "ANDROID_FUSED", "EXTERNAL_NMEA", "CELL_ASSISTED", "REPLAY"}
TRACK_QUALITIES = {"UNVALIDATED", "STANDARD", "DIFFERENTIAL", "RTK_FLOAT", "RTK_FIXED", "DEAD_RECKONING"}
CALL_STATES = {"REQUESTED", "RINGING", "ACCEPTED", "CONNECTING", "CONNECTED", "REJECTED", "ENDED", "FAILED"}
CALL_TRANSITIONS = {
    "REQUESTED": {"RINGING", "ACCEPTED", "REJECTED", "ENDED", "FAILED"},
    "RINGING": {"ACCEPTED", "REJECTED", "ENDED", "FAILED"},
    "ACCEPTED": {"CONNECTING", "CONNECTED", "ENDED", "FAILED"},
    "CONNECTING": {"CONNECTED", "ENDED", "FAILED"},
    "CONNECTED": {"ENDED", "FAILED"},
    "REJECTED": set(),
    "ENDED": set(),
    "FAILED": set(),
}
BROADCAST_STATES = {"RECEIVED", "PLAYING", "PLAYED", "FAILED", "EXPIRED"}
BROADCAST_TRANSITIONS = {
    "RECEIVED": {"PLAYING", "PLAYED", "FAILED", "EXPIRED"},
    "PLAYING": {"PLAYED", "FAILED", "EXPIRED"},
    "PLAYED": set(),
    "FAILED": set(),
    "EXPIRED": set(),
}
CALL_SIGNAL_TYPES = {"OFFER", "ANSWER", "ICE_CANDIDATE", "ICE_COMPLETE"}
VOICE_MIME_TYPES = {"audio/mp4", "audio/aac", "audio/ogg", "audio/webm", "audio/wav"}
VOICE_ROLES = {"DISPATCHER", "SUPERVISOR", "ADMIN"}
VOICE_SENDER_ROLES = VOICE_ROLES | {"DEVICE"}
ALERT_TYPES = {
    "FALL", "IMPACT", "VIOLENT_SHAKE", "NEAR_ELECTRIC", "HEIGHT_LIMIT", "SENSOR_FAULT", "GEOFENCE",
}
ALERT_SEVERITIES = {"INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL"}
ALERT_WORKFLOW_STATES = {"OPEN", "ACKNOWLEDGED", "IN_PROGRESS", "CLOSED"}
ALERT_WORKFLOW_TRANSITIONS = {
    "OPEN": {"ACKNOWLEDGED", "CLOSED"},
    "ACKNOWLEDGED": {"IN_PROGRESS", "CLOSED"},
    "IN_PROGRESS": {"CLOSED"},
    "CLOSED": set(),
}
ALERT_ROLES = {"VIEWER", "DISPATCHER", "SUPERVISOR", "ADMIN"}
ALERT_CONTROL_ROLES = {"DISPATCHER", "SUPERVISOR", "ADMIN"}
CALL_ROLES = ALERT_ROLES
CALL_CONTROL_ROLES = ALERT_CONTROL_ROLES
AUTH_PRINCIPAL_ROLES = ALERT_ROLES | {"DEVICE"}
ROLE_CAPABILITIES = {
    "VIEWER": ("READ_OPERATIONS",),
    "DISPATCHER": (
        "READ_OPERATIONS", "CONTROL_ALERTS", "CONTROL_CALLS", "CONTROL_BROADCASTS",
        "CONNECT_LIVE_VIDEO", "PLAY_VOICE_MESSAGES",
    ),
    "SUPERVISOR": (
        "READ_OPERATIONS", "CONTROL_ALERTS", "CONTROL_CALLS", "CONTROL_BROADCASTS",
        "CONNECT_LIVE_VIDEO", "PLAY_VOICE_MESSAGES",
    ),
    "ADMIN": (
        "READ_OPERATIONS", "CONTROL_ALERTS", "CONTROL_CALLS", "CONTROL_BROADCASTS",
        "CONNECT_LIVE_VIDEO", "PLAY_VOICE_MESSAGES", "VIEW_ACCESS_DIRECTORY",
        "VIEW_SECURITY_AUDIT",
    ),
    "DEVICE": ("DEVICE_DATA",),
}
class ApiError(Exception):
    def __init__(self, status: int, message: str, **details: Any) -> None:
        super().__init__(message)
        self.status = status
        self.message = message
        self.details = details


@dataclass(frozen=True)
class AuthPrincipal:
    principal_id: str
    role: str
    device_ids: tuple[str, ...]
    organization_id: str | None = None


@dataclass(frozen=True)
class AuthConfiguration:
    principals: dict[str, AuthPrincipal]
    device_organizations: dict[str, str]
    schema_version: int


def load_auth_configuration(path: Path) -> AuthConfiguration:
    try:
        raw = read_owner_only_file(path, MAX_METADATA_BYTES)
    except ValueError as error:
        if "permissions must be zero" in str(error):
            raise ValueError("auth config must not be group- or world-accessible") from error
        raise ValueError(f"auth config {error}") from error
    try:
        value = loads_strict(raw)
    except StrictJsonError as error:
        raise ValueError("auth config is not valid JSON") from error
    if not isinstance(value, dict) or value.get("schemaVersion") not in {1, 2}:
        raise ValueError("unsupported auth config schema")
    schema_version = value["schemaVersion"]
    device_organizations: dict[str, str] = {}
    organizations = value.get("organizations")
    if schema_version == 2:
        if not isinstance(organizations, list) or not organizations:
            raise ValueError("auth config organizations must be a non-empty array")
        seen_organizations: set[str] = set()
        for organization in organizations:
            if not isinstance(organization, dict):
                raise ValueError("auth organization must be an object")
            organization_id = organization.get("organizationId")
            device_ids = organization.get("deviceIds")
            if not isinstance(organization_id, str) or not ID_PATTERN.fullmatch(organization_id):
                raise ValueError("auth organization ID is invalid")
            if organization_id in seen_organizations:
                raise ValueError("duplicate auth organization ID")
            if (
                not isinstance(device_ids, list)
                or not device_ids
                or any(not isinstance(item, str) or not ID_PATTERN.fullmatch(item) for item in device_ids)
                or len(device_ids) != len(set(device_ids))
            ):
                raise ValueError("auth organization device IDs are invalid")
            seen_organizations.add(organization_id)
            for device_id in device_ids:
                if device_id in device_organizations:
                    raise ValueError("device belongs to multiple auth organizations")
                device_organizations[device_id] = organization_id
    entries = value.get("principals")
    if not isinstance(entries, list) or not entries:
        raise ValueError("auth config principals must be a non-empty array")
    principals: dict[str, AuthPrincipal] = {}
    principal_bindings: dict[str, tuple[str, tuple[str, ...], str | None]] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError("auth principal must be an object")
        digest = entry.get("tokenSha256")
        principal_id = entry.get("principalId")
        role = entry.get("role")
        device_ids = entry.get("deviceIds", [])
        organization_id = entry.get("organizationId")
        if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
            raise ValueError("auth principal tokenSha256 is invalid")
        if not isinstance(principal_id, str) or not ID_PATTERN.fullmatch(principal_id):
            raise ValueError("auth principal ID is invalid")
        if role not in AUTH_PRINCIPAL_ROLES:
            raise ValueError("auth principal role is invalid")
        if (
            not isinstance(device_ids, list)
            or any(not isinstance(item, str) or not ID_PATTERN.fullmatch(item) for item in device_ids)
            or len(device_ids) != len(set(device_ids))
        ):
            raise ValueError("auth principal device IDs are invalid")
        if role == "DEVICE" and not device_ids:
            raise ValueError("device principal must be bound to at least one device")
        if schema_version == 2:
            if not isinstance(organization_id, str) or organization_id not in set(device_organizations.values()):
                raise ValueError("auth principal organization is invalid")
            if any(device_organizations.get(device_id) != organization_id for device_id in device_ids):
                raise ValueError("auth principal device belongs to another organization")
        elif organization_id is not None:
            raise ValueError("schema 1 auth principal cannot declare organizationId")
        if digest in principals:
            raise ValueError("duplicate auth token digest")
        binding = (role, tuple(device_ids), organization_id)
        existing_binding = principal_bindings.get(principal_id)
        if existing_binding is not None and existing_binding != binding:
            raise ValueError("auth principal ID has conflicting bindings")
        principal_bindings[principal_id] = binding
        principals[digest] = AuthPrincipal(principal_id, role, tuple(device_ids), organization_id)
    if schema_version == 2:
        organizations_with_admin = {
            principal.organization_id for principal in principals.values() if principal.role == "ADMIN"
        }
        if organizations_with_admin != set(device_organizations.values()):
            raise ValueError("each auth organization must have an ADMIN principal")
        devices_with_principals = {
            device_id
            for principal in principals.values()
            if principal.role == "DEVICE"
            for device_id in principal.device_ids
        }
        if devices_with_principals != set(device_organizations):
            raise ValueError("each auth organization device must have a DEVICE principal")
    return AuthConfiguration(principals, device_organizations, schema_version)


def load_auth_principals(path: Path) -> dict[str, AuthPrincipal]:
    return load_auth_configuration(path).principals


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def bounded_json_page(
    field: str,
    values: Iterable[dict[str, Any]],
    maximum_bytes: int,
) -> dict[str, Any]:
    if not field or maximum_bytes <= 0:
        raise ValueError("invalid JSON page limit")
    selected: list[dict[str, Any]] = []
    encoded_size = len(canonical_json({field: []}).encode("utf-8"))
    for value in values:
        item_size = len(canonical_json(value).encode("utf-8"))
        candidate_size = encoded_size + item_size + (1 if selected else 0)
        if candidate_size > maximum_bytes:
            if not selected:
                raise ApiError(
                    HTTPStatus.INTERNAL_SERVER_ERROR,
                    f"{field} item exceeds response limit",
                )
            break
        selected.append(value)
        encoded_size = candidate_size
    return {field: selected}


def bounded_sequence_query_page(
    field: str,
    database: Any,
    statement: str,
    resource_id: str,
    after_sequence: int,
    limit: int,
    response_factory: Callable[[Any], dict[str, Any]],
    maximum_bytes: int,
) -> dict[str, Any]:
    if not 0 <= after_sequence <= MAX_SIGNED_INT64 or limit <= 0:
        raise ValueError("invalid sequence page range")

    def values() -> Iterator[dict[str, Any]]:
        current_sequence = after_sequence
        remaining = limit
        while remaining > 0:
            query_rows = min(remaining, MAX_COMMUNICATION_QUERY_ROWS)
            rows = database.execute(
                statement,
                (resource_id, current_sequence, query_rows),
            ).fetchall()
            if not rows:
                return
            for row in rows:
                sequence = row["sequence"]
                if sequence != current_sequence + 1:
                    raise RuntimeError(f"{field} database sequence is not continuous")
                yield response_factory(row)
                current_sequence = sequence
                remaining -= 1
            if len(rows) < query_rows:
                return

    return bounded_json_page(field, values(), maximum_bytes)


def validate_map_tile_template(raw: str) -> str:
    value = raw.strip()
    if not value:
        return ""
    if len(value) > 2048 or any(value.count(placeholder) != 1 for placeholder in ("{z}", "{x}", "{y}")):
        raise ValueError("map tile URL template must contain one {z}, {x}, and {y}")
    parsed = urlparse(value)
    loopback = (parsed.hostname or "").lower() in {"127.0.0.1", "localhost", "::1"}
    if (
        parsed.scheme not in {"http", "https"}
        or (parsed.scheme == "http" and not loopback)
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
    ):
        raise ValueError("map tile URL template must use HTTPS without credentials or fragment")
    return value


def parse_ice_urls(raw: str, allowed_schemes: set[str], name: str) -> tuple[str, ...]:
    urls = tuple(value.strip() for value in raw.split(",") if value.strip())
    if not urls:
        return ()
    for value in urls:
        if len(value) > 2048 or any(character.isspace() for character in value):
            raise ValueError(f"{name} contains an invalid URL")
        try:
            parsed = urlparse(value)
            endpoint = urlparse(f"//{parsed.path}")
        except ValueError as error:
            raise ValueError(f"{name} contains an invalid URL") from error
        if (
            parsed.scheme not in allowed_schemes
            or parsed.netloc
            or not parsed.path
            or "@" in parsed.path
            or parsed.fragment
        ):
            raise ValueError(f"{name} contains an invalid URL")
        try:
            port = endpoint.port
        except ValueError as error:
            raise ValueError(f"{name} contains an invalid port") from error
        if not endpoint.hostname or endpoint.username or endpoint.password:
            raise ValueError(f"{name} contains an invalid endpoint")
        if port is not None and not 1 <= port <= 65535:
            raise ValueError(f"{name} contains an invalid port")
        if parsed.query:
            query = parse_qs(parsed.query, keep_blank_values=True)
            if parsed.scheme not in {"turn", "turns"} or query not in (
                {"transport": ["udp"]},
                {"transport": ["tcp"]},
            ):
                raise ValueError(f"{name} contains an invalid query")
    return urls


@dataclass(frozen=True)
class RuntimeSecrets:
    database_url: str
    s3_access_key_id: str
    s3_secret_access_key: str
    turn_shared_secret: str


def _load_file_or_environment_secret(
    environment: dict[str, str],
    file_environment_name: str,
    direct_environment_name: str,
    production: bool,
) -> str:
    file_path = environment.get(file_environment_name, "").strip()
    direct_value = environment.get(direct_environment_name, "")
    if production and direct_value:
        raise ValueError(
            f"production mode does not permit {direct_environment_name}; use {file_environment_name}"
        )
    if file_path and direct_value:
        raise ValueError(
            f"{file_environment_name} and {direct_environment_name} cannot both be set"
        )
    if not file_path:
        return direct_value.strip()
    try:
        return read_owner_only_text(Path(file_path), MAX_PRIVATE_SECRET_BYTES)
    except ValueError as error:
        raise ValueError(f"{file_environment_name}: {error}") from error


def _build_postgres_database_url(
    environment: dict[str, str],
    password: str,
) -> str:
    if not password:
        return ""
    user = environment.get("HELMET_POSTGRES_USER", "helmet").strip()
    host = environment.get("HELMET_POSTGRES_HOST", "postgres").strip()
    port = environment.get("HELMET_POSTGRES_PORT", "5432").strip()
    database = environment.get("HELMET_POSTGRES_DB", "helmet").strip()
    sslmode = environment.get("HELMET_DATABASE_SSLMODE", "disable").strip()
    if not all((user, host, port, database, sslmode)):
        raise ValueError("PostgreSQL connection fields must not be empty")
    try:
        port_number = int(port)
    except ValueError as error:
        raise ValueError("HELMET_POSTGRES_PORT must be an integer") from error
    if not 1 <= port_number <= 65535:
        raise ValueError("HELMET_POSTGRES_PORT must be between 1 and 65535")
    if sslmode not in {"disable", "allow", "prefer", "require", "verify-ca", "verify-full"}:
        raise ValueError("HELMET_DATABASE_SSLMODE is invalid")
    encoded = tuple(
        quote(value, safe="")
        for value in (user, password, host, str(port_number), database, sslmode)
    )
    return "postgresql://%s:%s@%s:%s/%s?sslmode=%s" % encoded


def load_runtime_secrets(
    environment: dict[str, str],
    production: bool,
    *,
    database_url_environment_name: str = "HELMET_DATABASE_URL",
    database_password_file_environment_name: str = "HELMET_POSTGRES_PASSWORD_FILE",
    s3_access_key_file_environment_name: str = "HELMET_S3_ACCESS_KEY_FILE",
    s3_secret_key_file_environment_name: str = "HELMET_S3_SECRET_KEY_FILE",
    turn_secret_file_environment_name: str = "HELMET_TURN_SECRET_FILE",
    turn_secret_environment_name: str = "HELMET_TURN_SHARED_SECRET",
) -> RuntimeSecrets:
    direct_database_url = environment.get(database_url_environment_name, "")
    password_file = environment.get(database_password_file_environment_name, "").strip()
    if production and direct_database_url:
        raise ValueError(
            f"production mode does not permit {database_url_environment_name}; use "
            f"{database_password_file_environment_name}"
        )
    if password_file and direct_database_url:
        raise ValueError(
            f"{database_password_file_environment_name} and "
            f"{database_url_environment_name} cannot both be set"
        )
    if password_file:
        try:
            password = read_owner_only_text(
                Path(password_file), MAX_PRIVATE_SECRET_BYTES
            )
        except ValueError as error:
            raise ValueError(
                f"{database_password_file_environment_name}: {error}"
            ) from error
        database_url = _build_postgres_database_url(environment, password)
    else:
        database_url = direct_database_url.strip()

    s3_access_key_id = _load_file_or_environment_secret(
        environment,
        s3_access_key_file_environment_name,
        "AWS_ACCESS_KEY_ID",
        production,
    )
    s3_secret_access_key = _load_file_or_environment_secret(
        environment,
        s3_secret_key_file_environment_name,
        "AWS_SECRET_ACCESS_KEY",
        production,
    )
    if bool(s3_access_key_id) != bool(s3_secret_access_key):
        raise ValueError("S3 access key ID and secret access key must be configured together")
    turn_shared_secret = _load_file_or_environment_secret(
        environment,
        turn_secret_file_environment_name,
        turn_secret_environment_name,
        production,
    )
    return RuntimeSecrets(
        database_url,
        s3_access_key_id,
        s3_secret_access_key,
        turn_shared_secret,
    )


def validate_production_configuration(
    auth_configuration: AuthConfiguration,
    token: str,
    database_url: str,
    object_store_name: str,
    s3_endpoint: str,
    s3_bucket: str,
    s3_region: str,
    s3_access_key_id: str,
    s3_secret_access_key: str,
    stun_urls: tuple[str, ...],
    turn_urls: tuple[str, ...],
    turn_shared_secret: str,
    map_tile_url_template: str,
    map_attribution: str,
    mqtt_enabled: bool,
) -> None:
    required = {
        "PostgreSQL URL": database_url,
        "S3 endpoint": s3_endpoint,
        "S3 bucket": s3_bucket,
        "S3 region": s3_region,
        "S3 access key ID": s3_access_key_id,
        "S3 secret access key": s3_secret_access_key,
        "STUN URL": ",".join(stun_urls),
        "TURN URL": ",".join(turn_urls),
        "TURN shared secret": turn_shared_secret,
        "map tile URL template": map_tile_url_template,
        "map attribution": map_attribution,
        "MQTT mutual-TLS gateway": "configured" if mqtt_enabled else "",
    }
    missing = sorted(name for name, value in required.items() if not value)
    if missing:
        raise ValueError(f"production configuration is missing: {', '.join(missing)}")
    if auth_configuration.schema_version != 2:
        raise ValueError("production mode requires a schemaVersion 2 auth config")
    if object_store_name != "s3":
        raise ValueError("production mode requires S3 object storage")
    if urlparse(s3_endpoint).scheme != "https":
        raise ValueError("production S3 endpoint must use HTTPS")
    if urlparse(database_url).scheme not in {"postgres", "postgresql"}:
        raise ValueError("production mode requires PostgreSQL")
    if urlparse(map_tile_url_template).scheme != "https":
        raise ValueError("production map tile URL template must use HTTPS")
    validate_map_tile_template(map_tile_url_template)
    if token:
        raise ValueError("production mode does not permit development single-token authorization")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(64 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def require_signed_int64(
    value: Any,
    name: str,
    minimum: int = 0,
    maximum: int = MAX_SIGNED_INT64,
) -> int:
    if (
        not isinstance(value, int)
        or isinstance(value, bool)
        or not minimum <= value <= maximum
    ):
        raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    return value


def validate_metadata(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ApiError(HTTPStatus.BAD_REQUEST, "metadata must be an object")
    metadata = dict(raw)
    for name in ("mediaId", "deviceId"):
        value = metadata.get(name)
        if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
            raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    kind = metadata.get("kind")
    mime_type = metadata.get("mimeType")
    supported = (kind, mime_type) in {("PHOTO", "image/jpeg"), ("VIDEO", "video/mp4")}
    supported = supported or (kind == "VOICE" and mime_type in VOICE_MIME_TYPES)
    if not supported:
        raise ApiError(HTTPStatus.BAD_REQUEST, "unsupported media kind or MIME type")
    byte_size = metadata.get("byteSize")
    if not isinstance(byte_size, int) or isinstance(byte_size, bool) or not 0 <= byte_size <= MAX_MEDIA_BYTES:
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid byteSize")
    digest = metadata.get("sha256")
    if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid sha256")
    if kind != "VOICE":
        for name in ("width", "height"):
            require_signed_int64(metadata.get(name), name, 1)
    duration = metadata.get("durationMillis")
    if kind == "VOICE":
        require_signed_int64(duration, "durationMillis", 1)
    created_at = metadata.get("createdAtEpochMillis")
    require_signed_int64(created_at, "createdAtEpochMillis", 1)
    location = metadata.get("location")
    if kind != "VOICE" or location is not None:
        if not isinstance(location, dict):
            raise ApiError(HTTPStatus.BAD_REQUEST, "location must be an object")
        latitude = location.get("latitude")
        longitude = location.get("longitude")
        if (latitude is None) != (longitude is None):
            raise ApiError(HTTPStatus.BAD_REQUEST, "latitude and longitude must both be null or present")
        if latitude is not None:
            if not isinstance(latitude, (int, float)) or isinstance(latitude, bool) or not -90 <= latitude <= 90:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid latitude")
            if not isinstance(longitude, (int, float)) or isinstance(longitude, bool) or not -180 <= longitude <= 180:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid longitude")
        fix_type = location.get("fixType")
        if not isinstance(fix_type, str) or not ID_PATTERN.fullmatch(fix_type):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid location fixType")
        metadata["location"] = dict(location)
    if kind == "VOICE":
        for name in ("senderId",):
            value = metadata.get(name)
            if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
                raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
        call_id = metadata.get("callId")
        if call_id is not None and (not isinstance(call_id, str) or not ID_PATTERN.fullmatch(call_id)):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid callId")
        roles = metadata.get("allowedRoles")
        if (
            not isinstance(roles, list)
            or not roles
            or any(not isinstance(role, str) or role not in VOICE_ROLES for role in roles)
            or len(set(roles)) != len(roles)
        ):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid allowedRoles")
        metadata["allowedRoles"] = sorted(roles)
        sender_role = metadata.get("senderRole")
        if sender_role is None:
            sender_role = "DEVICE" if metadata["senderId"] == metadata["deviceId"] else "DISPATCHER"
        if sender_role not in VOICE_SENDER_ROLES:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid senderRole")
        metadata["senderRole"] = sender_role
    return metadata


def _number(value: Any, name: str, minimum: float | None = None, maximum: float | None = None) -> float:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    try:
        number = float(value)
    except (OverflowError, ValueError) as error:
        raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}") from error
    if not math.isfinite(number):
        raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    if minimum is not None and number < minimum:
        raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    if maximum is not None and number > maximum:
        raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    return number


def validate_track_point(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ApiError(HTTPStatus.BAD_REQUEST, "track point must be an object")
    point = dict(raw)
    for name in ("messageId", "deviceId", "fixId"):
        value = point.get(name)
        if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
            raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    for name in ("sequence", "occurredAtEpochMillis"):
        require_signed_int64(point.get(name), name, 1)
    elapsed = point.get("elapsedRealtimeNanos")
    if elapsed is not None:
        require_signed_int64(elapsed, "elapsedRealtimeNanos")
    if point.get("source") not in TRACK_SOURCES:
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid source")
    if point.get("quality") not in TRACK_QUALITIES:
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid quality")
    _number(point.get("latitude"), "latitude", -90, 90)
    _number(point.get("longitude"), "longitude", -180, 180)
    if point.get("isMock") is not False:
        raise ApiError(HTTPStatus.BAD_REQUEST, "mock track points are not accepted")
    optional_non_negative = (
        "horizontalAccuracyMeters",
        "verticalAccuracyMeters",
        "speedMetersPerSecond",
        "speedAccuracyMetersPerSecond",
        "bearingAccuracyDegrees",
        "pdop",
        "hdop",
        "vdop",
        "correctionAgeSeconds",
    )
    for name in optional_non_negative:
        if point.get(name) is not None:
            _number(point[name], name, 0)
    if point.get("altitudeMeters") is not None:
        _number(point["altitudeMeters"], "altitudeMeters")
    if point.get("bearingDegrees") is not None:
        _number(point["bearingDegrees"], "bearingDegrees", 0, 360)
    for name in ("satellitesUsed", "satellitesVisible"):
        value = point.get(name)
        if value is not None:
            require_signed_int64(value, name, maximum=0x7FFF_FFFF)
    used = point.get("satellitesUsed")
    visible = point.get("satellitesVisible")
    if used is not None and visible is not None and used > visible:
        raise ApiError(HTTPStatus.BAD_REQUEST, "satellitesUsed exceeds satellitesVisible")
    for name in ("provider", "correctionStationId"):
        value = point.get(name)
        if value is not None and (not isinstance(value, str) or not ID_PATTERN.fullmatch(value)):
            raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    return point


def validate_device_status(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ApiError(HTTPStatus.BAD_REQUEST, "device status must be an object")
    status = dict(raw)
    if status.get("schemaVersion") != 1:
        raise ApiError(HTTPStatus.BAD_REQUEST, "unsupported device status schemaVersion")
    for name in ("messageId", "deviceId"):
        value = status.get(name)
        if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
            raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    occurred = status.get("occurredAtEpochMillis")
    if (
        not isinstance(occurred, int)
        or isinstance(occurred, bool)
        or not 0 < occurred <= MAX_SIGNED_INT64
    ):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid occurredAtEpochMillis")
    status_sequence = status.get("statusSequence")
    if status_sequence is not None and (
        not isinstance(status_sequence, int)
        or isinstance(status_sequence, bool)
        or not 0 < status_sequence <= MAX_SIGNED_INT64
    ):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid statusSequence")
    person_id = status.get("personId")
    if person_id is not None and (not isinstance(person_id, str) or not ID_PATTERN.fullmatch(person_id)):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid personId")
    for name in ("operationalState", "networkState", "hardwareMode"):
        value = status.get(name)
        if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
            raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    app_version = status.get("appVersion")
    if not isinstance(app_version, str) or not 1 <= len(app_version) <= 128:
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid appVersion")
    if not isinstance(status.get("cameraAvailable"), bool):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid cameraAvailable")
    if not isinstance(status.get("simulated"), bool):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid simulated")
    active_call_id = status.get("activeCallId")
    if active_call_id is not None and (
        not isinstance(active_call_id, str) or not ID_PATTERN.fullmatch(active_call_id)
    ):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid activeCallId")

    battery = status.get("battery")
    if not isinstance(battery, dict) or not isinstance(battery.get("present"), bool):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid battery")
    percent = battery.get("percent")
    voltage = battery.get("voltageMillivolts")
    if percent is not None and (
        not isinstance(percent, int) or isinstance(percent, bool) or not 0 <= percent <= 100
    ):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid battery percent")
    if voltage is not None and (
        not isinstance(voltage, int) or isinstance(voltage, bool) or not 0 < voltage <= 100_000
    ):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid battery voltageMillivolts")
    if not battery["present"] and (percent is not None or voltage is not None):
        raise ApiError(HTTPStatus.BAD_REQUEST, "absent battery values must be null")

    location = status.get("location")
    if not isinstance(location, dict):
        raise ApiError(HTTPStatus.BAD_REQUEST, "location must be an object")
    latitude = location.get("latitude")
    longitude = location.get("longitude")
    if (latitude is None) != (longitude is None):
        raise ApiError(HTTPStatus.BAD_REQUEST, "latitude and longitude must both be null or present")
    if latitude is not None:
        _number(latitude, "latitude", -90, 90)
        _number(longitude, "longitude", -180, 180)
    accuracy = location.get("horizontalAccuracyMeters")
    if accuracy is not None:
        _number(accuracy, "horizontalAccuracyMeters", 0)
    fix_type = location.get("fixType")
    if not isinstance(fix_type, str) or not ID_PATTERN.fullmatch(fix_type):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid location fixType")
    location_occurred = location.get("occurredAtEpochMillis")
    if location_occurred is not None and (
        not isinstance(location_occurred, int)
        or isinstance(location_occurred, bool)
        or location_occurred <= 0
        or location_occurred > occurred
    ):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid location occurredAtEpochMillis")
    if location.get("isMock") is not False:
        raise ApiError(HTTPStatus.BAD_REQUEST, "mock device status locations are not accepted")

    rtk = status.get("rtk")
    if rtk is None:
        rtk = {
            "state": "UNREPORTED",
            "correctionFrames": 0,
            "correctionBytes": 0,
            "lastFixQuality": "NO_FIX",
            "lastError": None,
        }
    if not isinstance(rtk, dict):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid RTK status")
    for name in ("state", "lastFixQuality"):
        value = rtk.get(name)
        if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
            raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid RTK {name}")
    for name in ("correctionFrames", "correctionBytes"):
        value = rtk.get(name)
        require_signed_int64(value, f"RTK {name}")
    rtk_error = rtk.get("lastError")
    if rtk_error is not None and (not isinstance(rtk_error, str) or len(rtk_error) > 256):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid RTK lastError")

    local_intercom = status.get("localIntercom")
    if local_intercom is None:
        local_intercom = {
            "state": "UNREPORTED",
            "peerCount": 0,
            "codec": "unknown",
            "rssiDbm": None,
            "packetLossPermille": None,
            "oneWayLatencyMillis": None,
            "faultCode": 0,
            "lastError": None,
        }
    if not isinstance(local_intercom, dict):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid localIntercom status")
    for name in ("state", "codec"):
        value = local_intercom.get(name)
        if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
            raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid localIntercom {name}")
    for name, minimum, maximum in (
        ("peerCount", 0, 255),
        ("packetLossPermille", 0, 1_000),
        ("oneWayLatencyMillis", 0, 65_535),
        ("faultCode", 0, 65_535),
    ):
        value = local_intercom.get(name)
        if name in {"packetLossPermille", "oneWayLatencyMillis"} and value is None:
            continue
        if not isinstance(value, int) or isinstance(value, bool) or not minimum <= value <= maximum:
            raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid localIntercom {name}")
    rssi = local_intercom.get("rssiDbm")
    if rssi is not None and (
        not isinstance(rssi, int) or isinstance(rssi, bool) or not -200 <= rssi <= 0
    ):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid localIntercom rssiDbm")
    intercom_error = local_intercom.get("lastError")
    if intercom_error is not None and (
        not isinstance(intercom_error, str) or len(intercom_error) > 256
    ):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid localIntercom lastError")

    time_status = status.get("time")
    if time_status is None:
        time_status = {
            "source": "UNREPORTED",
            "synchronized": False,
            "uncertaintyMillis": None,
            "calibrationAgeMillis": None,
            "calibrationSequence": None,
        }
    if not isinstance(time_status, dict):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid time status")
    source = time_status.get("source")
    synchronized = time_status.get("synchronized")
    if source not in {"UNREPORTED", "SYSTEM", "SERVER", "GNSS"}:
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid time source")
    if not isinstance(synchronized, bool):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid time synchronized state")
    uncertainty = time_status.get("uncertaintyMillis")
    calibration_age = time_status.get("calibrationAgeMillis")
    calibration_sequence = time_status.get("calibrationSequence")
    if source in {"UNREPORTED", "SYSTEM"}:
        if synchronized or any(
            value is not None for value in (uncertainty, calibration_age, calibration_sequence)
        ):
            raise ApiError(HTTPStatus.BAD_REQUEST, "unsynchronized time cannot carry calibration")
    else:
        for name, value in (
            ("uncertaintyMillis", uncertainty),
            ("calibrationAgeMillis", calibration_age),
        ):
            require_signed_int64(value, f"time {name}")
        if (
            not isinstance(calibration_sequence, int)
            or isinstance(calibration_sequence, bool)
            or not 0 < calibration_sequence <= MAX_SIGNED_INT64
        ):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid time calibrationSequence")
        if not synchronized:
            raise ApiError(HTTPStatus.BAD_REQUEST, "calibrated time must be synchronized")
    status["battery"] = dict(battery)
    status["location"] = dict(location)
    status["rtk"] = dict(rtk)
    status["localIntercom"] = dict(local_intercom)
    status["time"] = dict(time_status)
    return status


def validate_safety_alert(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ApiError(HTTPStatus.BAD_REQUEST, "safety alert must be an object")
    alert = dict(raw)
    for name in ("messageId", "alertId", "deviceId"):
        value = alert.get(name)
        if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
            raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    if alert.get("type") not in ALERT_TYPES:
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid alert type")
    if alert.get("severity") not in ALERT_SEVERITIES:
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid alert severity")
    if not isinstance(alert.get("active"), bool):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid active")
    for name, maximum in (
        ("configVersion", 0xFFFF),
        ("sampleReference", 0xFFFF_FFFF),
        ("monotonicMillis", 0xFFFF_FFFF),
        ("localActions", 0xFF),
        ("sensorFaults", 0xFFFF),
    ):
        value = alert.get(name)
        if name in {"configVersion", "sampleReference"} and value is None:
            continue
        minimum = 1 if name == "configVersion" else 0
        if not isinstance(value, int) or isinstance(value, bool) or not minimum <= value <= maximum:
            raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    occurred = alert.get("occurredAtEpochMillis")
    require_signed_int64(occurred, "occurredAtEpochMillis", 1)
    if not isinstance(alert.get("simulated"), bool):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid simulated")
    snapshot = alert.get("sensorSnapshot")
    if not isinstance(snapshot, dict):
        raise ApiError(HTTPStatus.BAD_REQUEST, "sensorSnapshot must be an object")
    if len(canonical_json(snapshot).encode("utf-8")) > 64 * 1024:
        raise ApiError(HTTPStatus.BAD_REQUEST, "sensorSnapshot is too large")
    location = alert.get("location")
    if not isinstance(location, dict):
        raise ApiError(HTTPStatus.BAD_REQUEST, "location must be an object")
    latitude = location.get("latitude")
    longitude = location.get("longitude")
    if (latitude is None) != (longitude is None):
        raise ApiError(HTTPStatus.BAD_REQUEST, "latitude and longitude must both be null or present")
    if latitude is not None:
        _number(latitude, "latitude", -90, 90)
        _number(longitude, "longitude", -180, 180)
    accuracy = location.get("horizontalAccuracyMeters")
    if accuracy is not None:
        _number(accuracy, "horizontalAccuracyMeters", 0)
    fix_type = location.get("fixType")
    if not isinstance(fix_type, str) or not ID_PATTERN.fullmatch(fix_type):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid location fixType")
    evidence = alert.get("evidence")
    if not isinstance(evidence, dict):
        raise ApiError(HTTPStatus.BAD_REQUEST, "evidence must be an object")
    media_id = evidence.get("mediaAssetId")
    if media_id is not None and (not isinstance(media_id, str) or not ID_PATTERN.fullmatch(media_id)):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid evidence mediaAssetId")
    related_event_id = evidence.get("relatedEventId")
    if related_event_id is not None and (
        not isinstance(related_event_id, str) or not ID_PATTERN.fullmatch(related_event_id)
    ):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid evidence relatedEventId")
    alert["sensorSnapshot"] = dict(snapshot)
    alert["location"] = dict(location)
    alert["evidence"] = dict(evidence)
    return alert


class MediaRepository:
    def __init__(
        self,
        root: Path,
        chunk_size: int = 256 * 1024,
        database_url: str = "",
        object_store: Any | None = None,
        postgres_connect_factory: Any | None = None,
    ) -> None:
        if not 64 * 1024 <= chunk_size <= 4 * 1024 * 1024:
            raise ValueError("chunk_size must be between 64 KiB and 4 MiB")
        self.root = root.resolve()
        self.chunk_size = chunk_size
        self.uploads = self.root / "uploads"
        self.objects = self.root / "objects"
        self.database_path = self.root / "media.sqlite3"
        self.database_url = database_url
        self.postgres_connect_factory = postgres_connect_factory
        self.object_store = object_store if object_store is not None else LocalObjectStore(self.objects)
        self._lock = threading.RLock()
        self.uploads.mkdir(parents=True, exist_ok=True)
        self._initialize_database()

    def _connect(self) -> Any:
        if self.database_url:
            return connect_postgres(self.database_url, self.postgres_connect_factory)
        connection = sqlite3.connect(self.database_path, timeout=30)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA journal_mode = WAL")
        return connection

    def _initialize_database(self) -> None:
        with self._connect() as database:
            database.executescript(
                """
                CREATE TABLE IF NOT EXISTS media_objects (
                    sha256 TEXT PRIMARY KEY,
                    byte_size INTEGER NOT NULL,
                    object_path TEXT NOT NULL,
                    created_at_epoch_millis INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS media_archives (
                    archive_id TEXT PRIMARY KEY,
                    media_id TEXT NOT NULL UNIQUE,
                    object_sha256 TEXT NOT NULL REFERENCES media_objects(sha256),
                    metadata_json TEXT NOT NULL,
                    created_at_epoch_millis INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS upload_sessions (
                    session_id TEXT PRIMARY KEY,
                    media_id TEXT NOT NULL UNIQUE,
                    sha256 TEXT NOT NULL,
                    byte_size INTEGER NOT NULL,
                    metadata_json TEXT NOT NULL,
                    temp_path TEXT NOT NULL,
                    next_offset INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    archive_id TEXT,
                    deduplicated INTEGER NOT NULL DEFAULT 0,
                    created_at_epoch_millis INTEGER NOT NULL,
                    updated_at_epoch_millis INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS track_points (
                    message_id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    sequence INTEGER NOT NULL,
                    fix_id TEXT NOT NULL,
                    occurred_at_epoch_millis INTEGER NOT NULL,
                    payload_json TEXT NOT NULL,
                    received_at_epoch_millis INTEGER NOT NULL,
                    UNIQUE(device_id, sequence)
                );
                CREATE INDEX IF NOT EXISTS index_track_points_device_time
                    ON track_points(device_id, occurred_at_epoch_millis);
                CREATE TABLE IF NOT EXISTS device_status_events (
                    message_id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    occurred_at_epoch_millis INTEGER NOT NULL,
                    payload_json TEXT NOT NULL,
                    received_at_epoch_millis INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS index_device_status_device_time
                    ON device_status_events(device_id, occurred_at_epoch_millis);
                CREATE TABLE IF NOT EXISTS call_sessions (
                    call_id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    direction TEXT NOT NULL,
                    media_mode TEXT NOT NULL,
                    state TEXT NOT NULL,
                    state_sequence INTEGER NOT NULL,
                    related_event_id TEXT,
                    simulated INTEGER NOT NULL,
                    created_at_epoch_millis INTEGER NOT NULL,
                    updated_at_epoch_millis INTEGER NOT NULL,
                    last_reason TEXT
                );
                CREATE TABLE IF NOT EXISTS call_state_history (
                    call_id TEXT NOT NULL REFERENCES call_sessions(call_id),
                    state_sequence INTEGER NOT NULL,
                    state TEXT NOT NULL,
                    actor_id TEXT NOT NULL,
                    reason TEXT,
                    occurred_at_epoch_millis INTEGER NOT NULL,
                    PRIMARY KEY(call_id, state_sequence)
                );
                CREATE TABLE IF NOT EXISTS device_commands (
                    command_id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    sequence INTEGER NOT NULL,
                    command_type TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    created_at_epoch_millis INTEGER NOT NULL,
                    ack_json TEXT,
                    acked_at_epoch_millis INTEGER,
                    UNIQUE(device_id, sequence)
                );
                CREATE TABLE IF NOT EXISTS text_broadcasts (
                    broadcast_id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    text TEXT NOT NULL,
                    language TEXT NOT NULL,
                    priority INTEGER NOT NULL,
                    expires_at_epoch_millis INTEGER,
                    created_at_epoch_millis INTEGER NOT NULL,
                    last_state TEXT,
                    updated_at_epoch_millis INTEGER
                );
                CREATE TABLE IF NOT EXISTS broadcast_receipts (
                    broadcast_id TEXT NOT NULL REFERENCES text_broadcasts(broadcast_id),
                    state TEXT NOT NULL,
                    occurred_at_epoch_millis INTEGER NOT NULL,
                    error TEXT,
                    receipt_json TEXT NOT NULL,
                    PRIMARY KEY(broadcast_id, state)
                );
                CREATE TABLE IF NOT EXISTS call_signals (
                    signal_id TEXT PRIMARY KEY,
                    call_id TEXT NOT NULL REFERENCES call_sessions(call_id),
                    sequence INTEGER NOT NULL,
                    sender_id TEXT NOT NULL,
                    signal_type TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    created_at_epoch_millis INTEGER NOT NULL,
                    UNIQUE(call_id, sequence)
                );
                CREATE INDEX IF NOT EXISTS index_call_signals_call_sequence
                    ON call_signals(call_id, sequence);
                CREATE TABLE IF NOT EXISTS voice_messages (
                    message_id TEXT PRIMARY KEY,
                    object_sha256 TEXT NOT NULL REFERENCES media_objects(sha256),
                    device_id TEXT NOT NULL,
                    call_id TEXT,
                    related_event_id TEXT,
                    sender_id TEXT NOT NULL,
                    mime_type TEXT NOT NULL,
                    byte_size INTEGER NOT NULL,
                    duration_millis INTEGER NOT NULL,
                    allowed_roles_json TEXT NOT NULL,
                    metadata_json TEXT NOT NULL,
                    created_at_epoch_millis INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS index_voice_messages_device_time
                    ON voice_messages(device_id, created_at_epoch_millis);
                CREATE TABLE IF NOT EXISTS voice_message_audit (
                    audit_id TEXT PRIMARY KEY,
                    message_id TEXT NOT NULL REFERENCES voice_messages(message_id),
                    actor_id TEXT NOT NULL,
                    actor_role TEXT NOT NULL,
                    action TEXT NOT NULL,
                    occurred_at_epoch_millis INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS index_voice_message_audit_message_time
                    ON voice_message_audit(message_id, occurred_at_epoch_millis);
                CREATE TABLE IF NOT EXISTS safety_alerts (
                    alert_id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    alert_type TEXT NOT NULL,
                    severity TEXT NOT NULL,
                    device_active INTEGER NOT NULL,
                    workflow_state TEXT NOT NULL,
                    workflow_sequence INTEGER NOT NULL,
                    config_version INTEGER,
                    sample_reference INTEGER,
                    simulated INTEGER NOT NULL,
                    latest_payload_json TEXT NOT NULL,
                    created_at_epoch_millis INTEGER NOT NULL,
                    updated_at_epoch_millis INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS index_safety_alerts_device_time
                    ON safety_alerts(device_id, updated_at_epoch_millis);
                CREATE INDEX IF NOT EXISTS index_safety_alerts_workflow_time
                    ON safety_alerts(workflow_state, updated_at_epoch_millis);
                CREATE TABLE IF NOT EXISTS safety_alert_events (
                    message_id TEXT PRIMARY KEY,
                    alert_id TEXT NOT NULL REFERENCES safety_alerts(alert_id),
                    event_kind TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    occurred_at_epoch_millis INTEGER NOT NULL,
                    received_at_epoch_millis INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS index_safety_alert_events_alert_time
                    ON safety_alert_events(alert_id, occurred_at_epoch_millis);
                CREATE TABLE IF NOT EXISTS safety_alert_workflow_history (
                    alert_id TEXT NOT NULL REFERENCES safety_alerts(alert_id),
                    workflow_sequence INTEGER NOT NULL,
                    workflow_state TEXT NOT NULL,
                    actor_id TEXT NOT NULL,
                    actor_role TEXT NOT NULL,
                    note TEXT,
                    occurred_at_epoch_millis INTEGER NOT NULL,
                    PRIMARY KEY(alert_id, workflow_sequence)
                );
                CREATE TABLE IF NOT EXISTS security_audit_events (
                    event_id TEXT PRIMARY KEY,
                    principal_id TEXT NOT NULL,
                    principal_role TEXT NOT NULL,
                    organization_id TEXT,
                    method TEXT NOT NULL,
                    resource_path TEXT NOT NULL,
                    outcome TEXT NOT NULL,
                    status_code INTEGER NOT NULL,
                    reason TEXT NOT NULL,
                    occurred_at_epoch_millis INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS index_security_audit_organization_time
                    ON security_audit_events(organization_id, occurred_at_epoch_millis);
                """
            )
    def record_security_denial(
        self,
        principal: AuthPrincipal | None,
        method: str,
        resource_path: str,
        status_code: int,
        reason: str,
    ) -> None:
        principal_id = "ANONYMOUS" if principal is None else principal.principal_id
        principal_role = "ANONYMOUS" if principal is None else principal.role
        organization_id = None if principal is None else principal.organization_id
        with self._lock, self._connect() as database:
            database.execute(
                "INSERT INTO security_audit_events VALUES (?, ?, ?, ?, ?, ?, 'DENIED', ?, ?, ?)",
                (
                    str(uuid.uuid4()),
                    principal_id,
                    principal_role,
                    organization_id,
                    method,
                    resource_path[:2048],
                    status_code,
                    reason[:2000],
                    int(time.time() * 1000),
                ),
            )

    def security_audit_events(
        self,
        organization_id: str | None,
        limit: int,
    ) -> list[dict[str, Any]]:
        if not 1 <= limit <= 1000:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid security audit limit")
        with self._connect() as database:
            if organization_id is None:
                rows = database.execute(
                    "SELECT * FROM security_audit_events ORDER BY occurred_at_epoch_millis DESC LIMIT ?",
                    (limit,),
                ).fetchall()
            else:
                rows = database.execute(
                    "SELECT * FROM security_audit_events WHERE organization_id = ? "
                    "ORDER BY occurred_at_epoch_millis DESC LIMIT ?",
                    (organization_id, limit),
                ).fetchall()
        return [
            {
                "eventId": row["event_id"],
                "principalId": row["principal_id"],
                "principalRole": row["principal_role"],
                "organizationId": row["organization_id"],
                "method": row["method"],
                "resourcePath": row["resource_path"],
                "outcome": row["outcome"],
                "statusCode": row["status_code"],
                "reason": row["reason"],
                "occurredAtEpochMillis": row["occurred_at_epoch_millis"],
            }
            for row in rows
        ]

    def alert_device(self, alert_id: str) -> str:
        return self._resource_device("safety_alerts", "alert_id", alert_id)

    def call_device(self, call_id: str) -> str:
        return self._resource_device("call_sessions", "call_id", call_id)

    def voice_message_device(self, message_id: str) -> str:
        return self._resource_device("voice_messages", "message_id", message_id)

    def broadcast_device(self, broadcast_id: str) -> str:
        return self._resource_device("text_broadcasts", "broadcast_id", broadcast_id)

    def command_device(self, command_id: str) -> str:
        return self._resource_device("device_commands", "command_id", command_id)

    def upload_session_metadata(self, session_id: str) -> dict[str, Any]:
        if not ID_PATTERN.fullmatch(session_id):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid upload sessionId")
        with self._connect() as database:
            row = database.execute(
                "SELECT metadata_json FROM upload_sessions WHERE session_id = ?", (session_id,)
            ).fetchone()
        if row is None:
            raise ApiError(HTTPStatus.NOT_FOUND, "upload session not found")
        return json.loads(row["metadata_json"])

    def _resource_device(self, table: str, identifier_column: str, identifier: str) -> str:
        if not ID_PATTERN.fullmatch(identifier):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid resource ID")
        allowed = {
            ("safety_alerts", "alert_id"),
            ("call_sessions", "call_id"),
            ("voice_messages", "message_id"),
            ("text_broadcasts", "broadcast_id"),
            ("device_commands", "command_id"),
        }
        if (table, identifier_column) not in allowed:
            raise RuntimeError("unsupported resource lookup")
        with self._connect() as database:
            row = database.execute(
                f"SELECT device_id FROM {table} WHERE {identifier_column} = ?", (identifier,)
            ).fetchone()
        if row is None:
            raise ApiError(HTTPStatus.NOT_FOUND, "resource not found")
        return row["device_id"]

    @staticmethod
    def _validate_alert_actor(actor_id: str, actor_role: str, control: bool = False) -> None:
        allowed = ALERT_CONTROL_ROLES if control else ALERT_ROLES
        if not ID_PATTERN.fullmatch(actor_id) or actor_role not in allowed:
            raise ApiError(HTTPStatus.FORBIDDEN, "alert access is forbidden")

    @staticmethod
    def _alert_response(database: sqlite3.Connection, row: sqlite3.Row) -> dict[str, Any]:
        payload = json.loads(row["latest_payload_json"])
        location = payload["location"]
        evidence = dict(payload["evidence"])
        if evidence.get("mediaAssetId") is None:
            events = database.execute(
                "SELECT payload_json FROM safety_alert_events WHERE alert_id = ? "
                "ORDER BY occurred_at_epoch_millis DESC, message_id DESC",
                (row["alert_id"],),
            ).fetchall()
            related_event_ids = []
            for event in events:
                event_evidence = json.loads(event["payload_json"])["evidence"]
                if event_evidence.get("mediaAssetId") is not None:
                    evidence["mediaAssetId"] = event_evidence["mediaAssetId"]
                    break
                related_event_id = event_evidence.get("relatedEventId")
                if related_event_id is not None:
                    related_event_ids.append(related_event_id)
            media = None
            if evidence.get("mediaAssetId") is None and related_event_ids:
                placeholders = ",".join("?" for _ in related_event_ids)
                media = database.execute(
                    "SELECT media_id FROM media_archives "
                    "WHERE json_extract(metadata_json, '$.deviceId') = ? "
                    f"AND json_extract(metadata_json, '$.relatedEventId') IN ({placeholders}) "
                    "AND json_extract(metadata_json, '$.kind') IN ('PHOTO', 'VIDEO') "
                    "ORDER BY CAST(json_extract(metadata_json, '$.createdAtEpochMillis') AS INTEGER) DESC, "
                    "media_id DESC LIMIT 1",
                    (row["device_id"], *related_event_ids),
                ).fetchone()
            if media is not None:
                evidence["mediaAssetId"] = media["media_id"]
        requires_attention = row["workflow_state"] != "CLOSED" and bool(row["device_active"])
        return {
            "alertId": row["alert_id"],
            "deviceId": row["device_id"],
            "type": row["alert_type"],
            "severity": row["severity"],
            "active": bool(row["device_active"]),
            "workflowState": row["workflow_state"],
            "workflowSequence": row["workflow_sequence"],
            "configVersion": row["config_version"],
            "sampleReference": row["sample_reference"],
            "simulated": bool(row["simulated"]),
            "location": location,
            "evidence": evidence,
            "sensorSnapshot": payload["sensorSnapshot"],
            "localActions": payload["localActions"],
            "sensorFaults": payload["sensorFaults"],
            "occurredAtEpochMillis": payload["occurredAtEpochMillis"],
            "updatedAtEpochMillis": row["updated_at_epoch_millis"],
            "requiresAttention": requires_attention,
            "presentation": {
                "sound": requires_attention and row["severity"] in {"HIGH", "CRITICAL"},
                "visualPriority": row["severity"],
                "mapMarker": location.get("latitude") is not None,
            },
        }

    def ingest_safety_alert(self, raw: Any) -> dict[str, Any]:
        alert = validate_safety_alert(raw)
        payload_json = canonical_json(alert)
        now = int(time.time() * 1000)
        with self._lock, self._connect() as database:
            existing_event = database.execute(
                "SELECT payload_json, alert_id FROM safety_alert_events WHERE message_id = ?",
                (alert["messageId"],),
            ).fetchone()
            if existing_event is not None:
                if existing_event["payload_json"] != payload_json:
                    raise ApiError(
                        HTTPStatus.CONFLICT,
                        "messageId already exists with different alert content",
                    )
                row = database.execute(
                    "SELECT * FROM safety_alerts WHERE alert_id = ?", (existing_event["alert_id"],)
                ).fetchone()
                response = self._alert_response(database, row)
                response["deduplicated"] = True
                return response

            row = database.execute(
                "SELECT * FROM safety_alerts WHERE alert_id = ?", (alert["alertId"],)
            ).fetchone()
            if row is None:
                if not alert["active"]:
                    raise ApiError(HTTPStatus.CONFLICT, "cannot clear an unknown alert")
                database.execute(
                    "INSERT INTO safety_alerts VALUES (?, ?, ?, ?, 1, 'OPEN', 1, ?, ?, ?, ?, ?, ?)",
                    (
                        alert["alertId"],
                        alert["deviceId"],
                        alert["type"],
                        alert["severity"],
                        alert["configVersion"],
                        alert["sampleReference"],
                        int(alert["simulated"]),
                        payload_json,
                        alert["occurredAtEpochMillis"],
                        now,
                    ),
                )
                database.execute(
                    "INSERT INTO safety_alert_workflow_history VALUES (?, 1, 'OPEN', ?, 'DEVICE', 'DEVICE_ALERT', ?)",
                    (alert["alertId"], alert["deviceId"], alert["occurredAtEpochMillis"]),
                )
            else:
                if row["device_id"] != alert["deviceId"] or row["alert_type"] != alert["type"]:
                    raise ApiError(HTTPStatus.CONFLICT, "alertId belongs to different alert content")
                latest = database.execute(
                    "SELECT MAX(occurred_at_epoch_millis) AS latest FROM safety_alert_events WHERE alert_id = ?",
                    (alert["alertId"],),
                ).fetchone()["latest"]
                if latest is not None and alert["occurredAtEpochMillis"] < latest:
                    raise ApiError(HTTPStatus.CONFLICT, "alert events must be ordered")
                if row["workflow_state"] == "CLOSED" and alert["active"]:
                    raise ApiError(HTTPStatus.CONFLICT, "closed alert cannot be reactivated")
                database.execute(
                    "UPDATE safety_alerts SET severity = ?, device_active = ?, config_version = ?, "
                    "sample_reference = ?, simulated = CASE WHEN simulated <> 0 OR ? <> 0 "
                    "THEN 1 ELSE 0 END, latest_payload_json = ?, "
                    "updated_at_epoch_millis = ? WHERE alert_id = ?",
                    (
                        alert["severity"],
                        int(alert["active"]),
                        alert["configVersion"],
                        alert["sampleReference"],
                        int(alert["simulated"]),
                        payload_json,
                        now,
                        alert["alertId"],
                    ),
                )
            database.execute(
                "INSERT INTO safety_alert_events VALUES (?, ?, ?, ?, ?, ?)",
                (
                    alert["messageId"],
                    alert["alertId"],
                    "ACTIVATED" if alert["active"] else "CLEARED",
                    payload_json,
                    alert["occurredAtEpochMillis"],
                    now,
                ),
            )
            row = database.execute(
                "SELECT * FROM safety_alerts WHERE alert_id = ?", (alert["alertId"],)
            ).fetchone()
            response = self._alert_response(database, row)
            response["deduplicated"] = False
            return response

    def safety_alerts(
        self,
        actor_id: str,
        actor_role: str,
        device_id: str | None,
        workflow_state: str | None,
        limit: int,
        allowed_device_ids: set[str] | None = None,
    ) -> list[dict[str, Any]]:
        self._validate_alert_actor(actor_id, actor_role)
        if device_id is not None and not ID_PATTERN.fullmatch(device_id):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid deviceId")
        if workflow_state is not None and workflow_state not in ALERT_WORKFLOW_STATES:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid workflowState")
        if not 1 <= limit <= 1000:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid alert query limit")
        clauses: list[str] = []
        values: list[Any] = []
        if device_id is not None:
            clauses.append("device_id = ?")
            values.append(device_id)
        if allowed_device_ids is not None:
            if not allowed_device_ids:
                return []
            placeholders = ",".join("?" for _ in allowed_device_ids)
            clauses.append(f"device_id IN ({placeholders})")
            values.extend(sorted(allowed_device_ids))
        if workflow_state is not None:
            clauses.append("workflow_state = ?")
            values.append(workflow_state)
        where = f" WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._connect() as database:
            rows = database.execute(
                f"SELECT * FROM safety_alerts{where} ORDER BY updated_at_epoch_millis DESC LIMIT ?",
                (*values, limit),
            ).fetchall()
            return [self._alert_response(database, row) for row in rows]

    def safety_alert(self, alert_id: str, actor_id: str, actor_role: str) -> dict[str, Any]:
        self._validate_alert_actor(actor_id, actor_role)
        if not ID_PATTERN.fullmatch(alert_id):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid alertId")
        with self._connect() as database:
            row = database.execute(
                "SELECT * FROM safety_alerts WHERE alert_id = ?", (alert_id,)
            ).fetchone()
            if row is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "alert not found")
            events = database.execute(
                "SELECT event_kind, payload_json, received_at_epoch_millis FROM safety_alert_events "
                "WHERE alert_id = ? ORDER BY occurred_at_epoch_millis, message_id",
                (alert_id,),
            ).fetchall()
            history = database.execute(
                "SELECT workflow_sequence, workflow_state, actor_id, actor_role, note, occurred_at_epoch_millis "
                "FROM safety_alert_workflow_history WHERE alert_id = ? ORDER BY workflow_sequence",
                (alert_id,),
            ).fetchall()
            response = self._alert_response(database, row)
        response["events"] = [
            {
                "kind": event["event_kind"],
                "payload": json.loads(event["payload_json"]),
                "receivedAtEpochMillis": event["received_at_epoch_millis"],
            }
            for event in events
        ]
        response["history"] = [
            {
                "sequence": item["workflow_sequence"],
                "state": item["workflow_state"],
                "actorId": item["actor_id"],
                "actorRole": item["actor_role"],
                "note": item["note"],
                "occurredAtEpochMillis": item["occurred_at_epoch_millis"],
            }
            for item in history
        ]
        return response

    def transition_safety_alert(
        self,
        alert_id: str,
        raw: Any,
        actor_id: str,
        actor_role: str,
    ) -> dict[str, Any]:
        self._validate_alert_actor(actor_id, actor_role, control=True)
        if not ID_PATTERN.fullmatch(alert_id):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid alertId")
        if not isinstance(raw, dict):
            raise ApiError(HTTPStatus.BAD_REQUEST, "alert transition must be an object")
        target = raw.get("state")
        if target not in ALERT_WORKFLOW_STATES:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid workflow state")
        note = raw.get("note")
        if note is not None and (not isinstance(note, str) or not 1 <= len(note) <= 2000):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid transition note")
        if target == "CLOSED" and not note:
            raise ApiError(HTTPStatus.BAD_REQUEST, "closing an alert requires a note")
        occurred = raw.get("occurredAtEpochMillis")
        require_signed_int64(occurred, "occurredAtEpochMillis", 1)
        with self._lock, self._connect() as database:
            row = database.execute(
                "SELECT * FROM safety_alerts WHERE alert_id = ?", (alert_id,)
            ).fetchone()
            if row is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "alert not found")
            current = row["workflow_state"]
            if target == current:
                response = self._alert_response(database, row)
                response["deduplicated"] = True
                return response
            if target not in ALERT_WORKFLOW_TRANSITIONS[current]:
                raise ApiError(HTTPStatus.CONFLICT, f"invalid alert transition {current} -> {target}")
            sequence = row["workflow_sequence"] + 1
            database.execute(
                "UPDATE safety_alerts SET workflow_state = ?, workflow_sequence = ?, "
                "updated_at_epoch_millis = ? WHERE alert_id = ?",
                (target, sequence, int(time.time() * 1000), alert_id),
            )
            database.execute(
                "INSERT INTO safety_alert_workflow_history VALUES (?, ?, ?, ?, ?, ?, ?)",
                (alert_id, sequence, target, actor_id, actor_role, note, occurred),
            )
            row = database.execute(
                "SELECT * FROM safety_alerts WHERE alert_id = ?", (alert_id,)
            ).fetchone()
            response = self._alert_response(database, row)
            response["deduplicated"] = False
            return response

    def create_call(self, raw: Any) -> dict[str, Any]:
        if not isinstance(raw, dict):
            raise ApiError(HTTPStatus.BAD_REQUEST, "call request must be an object")
        call = dict(raw)
        for name in ("callId", "deviceId"):
            value = call.get(name)
            if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
                raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
        if call.get("direction") != "OUTGOING_DEVICE":
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid call direction")
        if call.get("mediaMode") not in {"AUDIO", "VIDEO_UPLINK"}:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid mediaMode")
        if call.get("state") != "REQUESTED" or call.get("stateSequence") != 1:
            raise ApiError(HTTPStatus.BAD_REQUEST, "new call must start at REQUESTED sequence 1")
        if not isinstance(call.get("simulated"), bool):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid simulated")
        created = call.get("createdAtEpochMillis")
        require_signed_int64(created, "createdAtEpochMillis", 1)
        related = call.get("relatedEventId")
        if related is not None and (not isinstance(related, str) or not ID_PATTERN.fullmatch(related)):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid relatedEventId")
        identity = canonical_json(call)
        with self._lock, self._connect() as database:
            existing = database.execute(
                "SELECT * FROM call_sessions WHERE call_id = ?", (call["callId"],)
            ).fetchone()
            if existing is not None:
                stored = self._call_response(database, call["callId"])
                comparable = {name: stored.get(name) for name in call}
                if canonical_json(comparable) != identity:
                    raise ApiError(HTTPStatus.CONFLICT, "callId already exists with different content")
                stored["deduplicated"] = True
                return stored
            database.execute(
                "INSERT INTO call_sessions VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, NULL)",
                (
                    call["callId"], call["deviceId"], call["direction"], call["mediaMode"],
                    call["state"], related, int(call["simulated"]), created, created,
                ),
            )
            database.execute(
                "INSERT INTO call_state_history VALUES (?, 1, 'REQUESTED', ?, NULL, ?)",
                (call["callId"], call["deviceId"], created),
            )
            response = self._call_response(database, call["callId"])
            response["deduplicated"] = False
            return response

    def transition_call(self, call_id: str, raw: Any) -> dict[str, Any]:
        if not ID_PATTERN.fullmatch(call_id) or not isinstance(raw, dict):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid call transition")
        target = raw.get("state")
        actor_id = raw.get("actorId")
        occurred = raw.get("occurredAtEpochMillis")
        reason = raw.get("reason")
        if target not in CALL_STATES:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid call state")
        if not isinstance(actor_id, str) or not ID_PATTERN.fullmatch(actor_id):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid actorId")
        require_signed_int64(occurred, "occurredAtEpochMillis", 1)
        if reason is not None and (not isinstance(reason, str) or len(reason) > 1024):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid reason")
        with self._lock, self._connect() as database:
            current = database.execute(
                "SELECT * FROM call_sessions WHERE call_id = ?", (call_id,)
            ).fetchone()
            if current is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "call session not found")
            if current["state"] == target:
                response = self._call_response(database, call_id)
                response["deduplicated"] = True
                return response
            if target not in CALL_TRANSITIONS[current["state"]]:
                raise ApiError(HTTPStatus.CONFLICT, "invalid call state transition")
            sequence = current["state_sequence"] + 1
            database.execute(
                "UPDATE call_sessions SET state = ?, state_sequence = ?, updated_at_epoch_millis = ?, "
                "last_reason = ? WHERE call_id = ?",
                (target, sequence, occurred, reason, call_id),
            )
            database.execute(
                "INSERT INTO call_state_history VALUES (?, ?, ?, ?, ?, ?)",
                (call_id, sequence, target, actor_id, reason, occurred),
            )
            if actor_id != current["device_id"]:
                self._enqueue_command(
                    database,
                    current["device_id"],
                    "CALL_STATE",
                    {
                        "callId": call_id,
                        "state": target,
                        "stateSequence": sequence,
                        "reason": reason,
                        "occurredAtEpochMillis": occurred,
                    },
                    occurred,
                )
            response = self._call_response(database, call_id)
            response["deduplicated"] = False
            return response

    def call(self, call_id: str) -> dict[str, Any]:
        if not ID_PATTERN.fullmatch(call_id):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid callId")
        with self._connect() as database:
            return self._call_response(database, call_id)

    def calls(
        self,
        actor_id: str,
        actor_role: str,
        device_id: str | None,
        state: str | None,
        limit: int,
        allowed_device_ids: set[str] | None = None,
    ) -> list[dict[str, Any]]:
        if not ID_PATTERN.fullmatch(actor_id) or actor_role not in CALL_ROLES:
            raise ApiError(HTTPStatus.FORBIDDEN, "call access is forbidden")
        if device_id is not None and not ID_PATTERN.fullmatch(device_id):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid deviceId")
        if state is not None and state not in CALL_STATES:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid call state")
        if not 1 <= limit <= 500:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid call query limit")
        clauses: list[str] = []
        values: list[Any] = []
        if device_id is not None:
            clauses.append("device_id = ?")
            values.append(device_id)
        if allowed_device_ids is not None:
            if not allowed_device_ids:
                return []
            placeholders = ",".join("?" for _ in allowed_device_ids)
            clauses.append(f"device_id IN ({placeholders})")
            values.extend(sorted(allowed_device_ids))
        if state is not None:
            clauses.append("state = ?")
            values.append(state)
        where = f" WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._connect() as database:
            rows = database.execute(
                f"SELECT call_id FROM call_sessions{where} "
                "ORDER BY updated_at_epoch_millis DESC LIMIT ?",
                (*values, limit),
            ).fetchall()
            return [self._call_response(database, row["call_id"]) for row in rows]

    def create_call_signal(self, call_id: str, raw: Any) -> dict[str, Any]:
        if not ID_PATTERN.fullmatch(call_id) or not isinstance(raw, dict):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid call signal")
        signal = dict(raw)
        for name in ("signalId", "senderId"):
            value = signal.get(name)
            if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
                raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
        signal_type = signal.get("type")
        if signal_type not in CALL_SIGNAL_TYPES:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid call signal type")
        created = signal.get("createdAtEpochMillis")
        require_signed_int64(created, "createdAtEpochMillis", 1)
        payload = signal.get("payload")
        if not isinstance(payload, dict):
            raise ApiError(HTTPStatus.BAD_REQUEST, "signal payload must be an object")
        payload_json = canonical_json(payload)
        if len(payload_json.encode("utf-8")) > MAX_SIGNAL_BYTES:
            raise ApiError(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "call signal is too large")
        if signal_type in {"OFFER", "ANSWER"}:
            sdp = payload.get("sdp")
            if not isinstance(sdp, str) or not 1 <= len(sdp) <= MAX_SIGNAL_BYTES:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid SDP")
        elif signal_type == "ICE_CANDIDATE":
            candidate = payload.get("candidate")
            line_index = payload.get("sdpMLineIndex")
            mid = payload.get("sdpMid")
            if not isinstance(candidate, str) or not 1 <= len(candidate) <= 8192:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid ICE candidate")
            require_signed_int64(line_index, "sdpMLineIndex", maximum=0x7FFF_FFFF)
            if mid is not None and (not isinstance(mid, str) or len(mid) > 256):
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid sdpMid")
        elif payload:
            raise ApiError(HTTPStatus.BAD_REQUEST, "ICE_COMPLETE payload must be empty")
        identity = canonical_json({
            "callId": call_id,
            "senderId": signal["senderId"],
            "type": signal_type,
            "payload": payload,
            "createdAtEpochMillis": created,
        })
        with self._lock, self._connect() as database:
            call = database.execute(
                "SELECT call_id FROM call_sessions WHERE call_id = ?", (call_id,)
            ).fetchone()
            if call is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "call session not found")
            existing = database.execute(
                "SELECT * FROM call_signals WHERE signal_id = ?", (signal["signalId"],)
            ).fetchone()
            if existing is not None:
                stored_identity = canonical_json({
                    "callId": existing["call_id"],
                    "senderId": existing["sender_id"],
                    "type": existing["signal_type"],
                    "payload": json.loads(existing["payload_json"]),
                    "createdAtEpochMillis": existing["created_at_epoch_millis"],
                })
                if stored_identity != identity:
                    raise ApiError(HTTPStatus.CONFLICT, "signalId already exists with different content")
                response = self._signal_response(existing)
                response["deduplicated"] = True
                return response
            sequence = database.execute(
                "SELECT COALESCE(MAX(sequence), 0) + 1 FROM call_signals WHERE call_id = ?",
                (call_id,),
            ).fetchone()[0]
            database.execute(
                "INSERT INTO call_signals VALUES (?, ?, ?, ?, ?, ?, ?)",
                (
                    signal["signalId"], call_id, sequence, signal["senderId"], signal_type,
                    payload_json, created,
                ),
            )
            row = database.execute(
                "SELECT * FROM call_signals WHERE signal_id = ?", (signal["signalId"],)
            ).fetchone()
            response = self._signal_response(row)
            response["deduplicated"] = False
            return response

    def call_signals(self, call_id: str, after_sequence: int, limit: int) -> list[dict[str, Any]]:
        return self.call_signal_page(
            call_id,
            after_sequence,
            limit,
            MAX_SIGNED_INT64,
        )["signals"]

    def call_signal_page(
        self,
        call_id: str,
        after_sequence: int,
        limit: int,
        maximum_bytes: int = MAX_COMMUNICATION_RESPONSE_BYTES,
    ) -> dict[str, Any]:
        if (
            not ID_PATTERN.fullmatch(call_id)
            or not 0 <= after_sequence <= MAX_SIGNED_INT64
            or not 1 <= limit <= 100
        ):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid call signal query")
        with self._connect() as database:
            if database.execute(
                "SELECT 1 FROM call_sessions WHERE call_id = ?", (call_id,)
            ).fetchone() is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "call session not found")
            return bounded_sequence_query_page(
                "signals",
                database,
                "SELECT * FROM call_signals WHERE call_id = ? AND sequence > ? "
                "ORDER BY sequence LIMIT ?",
                call_id,
                after_sequence,
                limit,
                self._signal_response,
                maximum_bytes,
            )

    @staticmethod
    def call_ice_configuration(
        call_id: str,
        requester_id: str,
        stun_urls: list[str],
        turn_urls: list[str],
        turn_shared_secret: str,
        now_epoch_seconds: int,
        ttl_seconds: int = 600,
    ) -> dict[str, Any]:
        if not ID_PATTERN.fullmatch(call_id) or not ID_PATTERN.fullmatch(requester_id):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid ICE configuration request")
        if not 60 <= ttl_seconds <= 3600:
            raise ApiError(HTTPStatus.INTERNAL_SERVER_ERROR, "invalid ICE credential TTL")
        expires = now_epoch_seconds + ttl_seconds
        servers: list[dict[str, Any]] = []
        if stun_urls:
            servers.append({"urls": stun_urls})
        if turn_urls:
            if not turn_shared_secret:
                raise ApiError(HTTPStatus.SERVICE_UNAVAILABLE, "TURN shared secret is not configured")
            username = f"{expires}:{call_id}:{requester_id}"
            digest = hmac.new(
                turn_shared_secret.encode("utf-8"), username.encode("utf-8"), hashlib.sha256
            ).digest()
            servers.append({
                "urls": turn_urls,
                "username": username,
                "credential": base64.b64encode(digest).decode("ascii"),
            })
        if not servers:
            raise ApiError(HTTPStatus.SERVICE_UNAVAILABLE, "ICE servers are not configured")
        return {
            "callId": call_id,
            "requesterId": requester_id,
            "iceServers": servers,
            "issuedAtEpochMillis": now_epoch_seconds * 1000,
            "expiresAtEpochMillis": expires * 1000,
            "credentialType": "password",
        }

    @staticmethod
    def _signal_response(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "signalId": row["signal_id"],
            "callId": row["call_id"],
            "sequence": row["sequence"],
            "senderId": row["sender_id"],
            "type": row["signal_type"],
            "payload": json.loads(row["payload_json"]),
            "createdAtEpochMillis": row["created_at_epoch_millis"],
        }

    def voice_messages(
        self,
        device_id: str | None,
        call_id: str | None,
        actor_id: str,
        actor_role: str,
        limit: int,
        allowed_device_ids: set[str] | None = None,
    ) -> list[dict[str, Any]]:
        self._validate_voice_actor(actor_id, actor_role)
        if device_id is not None and not ID_PATTERN.fullmatch(device_id):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid deviceId")
        if call_id is not None and not ID_PATTERN.fullmatch(call_id):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid callId")
        if not 1 <= limit <= 500:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid voice message limit")
        clauses: list[str] = []
        values: list[Any] = []
        if device_id is not None:
            clauses.append("device_id = ?")
            values.append(device_id)
        if allowed_device_ids is not None:
            if not allowed_device_ids:
                return []
            placeholders = ",".join("?" for _ in allowed_device_ids)
            clauses.append(f"device_id IN ({placeholders})")
            values.extend(sorted(allowed_device_ids))
        if call_id is not None:
            clauses.append("call_id = ?")
            values.append(call_id)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._connect() as database:
            rows = database.execute(
                f"SELECT * FROM voice_messages {where} ORDER BY created_at_epoch_millis DESC LIMIT ?",
                (*values, limit),
            ).fetchall()
        return [
            self._voice_response(row)
            for row in rows
            if actor_role in json.loads(row["allowed_roles_json"])
        ]

    def voice_message(
        self,
        message_id: str,
        actor_id: str,
        actor_role: str,
        action: str,
    ) -> tuple[dict[str, Any], str]:
        self._validate_voice_actor(actor_id, actor_role)
        if not ID_PATTERN.fullmatch(message_id) or action not in {"READ", "PLAYBACK"}:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid voice message request")
        with self._lock, self._connect() as database:
            row = database.execute(
                "SELECT v.*, o.object_path FROM voice_messages v "
                "JOIN media_objects o ON o.sha256 = v.object_sha256 WHERE v.message_id = ?",
                (message_id,),
            ).fetchone()
            if row is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "voice message not found")
            if actor_role not in json.loads(row["allowed_roles_json"]):
                raise ApiError(HTTPStatus.FORBIDDEN, "voice message access denied")
            database.execute(
                "INSERT INTO voice_message_audit VALUES (?, ?, ?, ?, ?, ?)",
                (str(uuid.uuid4()), message_id, actor_id, actor_role, action, int(time.time() * 1000)),
            )
            return self._voice_response(row), row["object_path"]

    @staticmethod
    def _validate_voice_actor(actor_id: str, actor_role: str) -> None:
        if not ID_PATTERN.fullmatch(actor_id) or actor_role not in VOICE_ROLES:
            raise ApiError(HTTPStatus.FORBIDDEN, "valid voice message actor headers are required")

    @staticmethod
    def _voice_response(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "messageId": row["message_id"],
            "deviceId": row["device_id"],
            "callId": row["call_id"],
            "relatedEventId": row["related_event_id"],
            "senderId": row["sender_id"],
            "senderRole": json.loads(row["metadata_json"]).get("senderRole"),
            "mimeType": row["mime_type"],
            "byteSize": row["byte_size"],
            "sha256": row["object_sha256"],
            "durationMillis": row["duration_millis"],
            "allowedRoles": json.loads(row["allowed_roles_json"]),
            "createdAtEpochMillis": row["created_at_epoch_millis"],
        }

    def create_broadcast(self, raw: Any) -> dict[str, Any]:
        if not isinstance(raw, dict):
            raise ApiError(HTTPStatus.BAD_REQUEST, "broadcast request must be an object")
        message = dict(raw)
        for name in ("broadcastId", "deviceId"):
            value = message.get(name)
            if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
                raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
        text = message.get("text")
        language = message.get("language")
        priority = message.get("priority")
        created = message.get("createdAtEpochMillis")
        expires = message.get("expiresAtEpochMillis")
        if not isinstance(text, str) or not 1 <= len(text) <= 2000:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid broadcast text")
        if not isinstance(language, str) or not ID_PATTERN.fullmatch(language):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid language")
        if not isinstance(priority, int) or isinstance(priority, bool) or priority not in range(0, 11):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid priority")
        require_signed_int64(created, "createdAtEpochMillis", 1)
        if expires is not None:
            require_signed_int64(expires, "expiresAtEpochMillis", created + 1)
        identity = canonical_json(message)
        with self._lock, self._connect() as database:
            existing = database.execute(
                "SELECT * FROM text_broadcasts WHERE broadcast_id = ?", (message["broadcastId"],)
            ).fetchone()
            if existing is not None:
                stored_identity = canonical_json({
                    "broadcastId": existing["broadcast_id"],
                    "deviceId": existing["device_id"],
                    "text": existing["text"],
                    "language": existing["language"],
                    "priority": existing["priority"],
                    "expiresAtEpochMillis": existing["expires_at_epoch_millis"],
                    "createdAtEpochMillis": existing["created_at_epoch_millis"],
                })
                if stored_identity != identity:
                    raise ApiError(HTTPStatus.CONFLICT, "broadcastId already exists with different content")
                return {"broadcastId": message["broadcastId"], "deduplicated": True}
            database.execute(
                "INSERT INTO text_broadcasts VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL)",
                (
                    message["broadcastId"], message["deviceId"], text, language, priority,
                    expires, created,
                ),
            )
            command = self._enqueue_command(database, message["deviceId"], "TEXT_BROADCAST", message, created)
            return {
                "broadcastId": message["broadcastId"],
                "commandId": command["commandId"],
                "commandSequence": command["sequence"],
                "deduplicated": False,
            }

    def broadcasts(
        self,
        device_id: str | None,
        limit: int,
        allowed_device_ids: set[str] | None = None,
    ) -> list[dict[str, Any]]:
        if device_id is not None and not ID_PATTERN.fullmatch(device_id):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid deviceId")
        if not 1 <= limit <= 100:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid broadcast query limit")
        clauses: list[str] = []
        values: list[Any] = []
        if device_id is not None:
            clauses.append("device_id = ?")
            values.append(device_id)
        if allowed_device_ids is not None:
            if not allowed_device_ids:
                return []
            placeholders = ",".join("?" for _ in allowed_device_ids)
            clauses.append(f"device_id IN ({placeholders})")
            values.extend(sorted(allowed_device_ids))
        where = f" WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._connect() as database:
            rows = database.execute(
                f"SELECT * FROM text_broadcasts{where} "
                "ORDER BY created_at_epoch_millis DESC, broadcast_id DESC LIMIT ?",
                (*values, limit),
            ).fetchall()
            broadcasts = []
            for row in rows:
                receipts = database.execute(
                    "SELECT state, occurred_at_epoch_millis, error FROM broadcast_receipts "
                    "WHERE broadcast_id = ? ORDER BY occurred_at_epoch_millis, state",
                    (row["broadcast_id"],),
                ).fetchall()
                broadcasts.append({
                    "broadcastId": row["broadcast_id"],
                    "deviceId": row["device_id"],
                    "text": row["text"],
                    "language": row["language"],
                    "priority": row["priority"],
                    "expiresAtEpochMillis": row["expires_at_epoch_millis"],
                    "createdAtEpochMillis": row["created_at_epoch_millis"],
                    "lastState": row["last_state"],
                    "updatedAtEpochMillis": row["updated_at_epoch_millis"],
                    "receipts": [
                        {
                            "state": receipt["state"],
                            "occurredAtEpochMillis": receipt["occurred_at_epoch_millis"],
                            "error": receipt["error"],
                        }
                        for receipt in receipts
                    ],
                })
        return broadcasts

    def record_broadcast_receipt(self, broadcast_id: str, raw: Any) -> dict[str, Any]:
        if not ID_PATTERN.fullmatch(broadcast_id) or not isinstance(raw, dict):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid broadcast receipt")
        state = raw.get("state")
        occurred = raw.get("occurredAtEpochMillis")
        error = raw.get("error")
        if state not in BROADCAST_STATES:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid broadcast state")
        require_signed_int64(occurred, "occurredAtEpochMillis", 1)
        if error is not None and (not isinstance(error, str) or len(error) > 1024):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid receipt error")
        receipt_json = canonical_json(raw)
        with self._lock, self._connect() as database:
            broadcast = database.execute(
                "SELECT * FROM text_broadcasts WHERE broadcast_id = ?", (broadcast_id,)
            ).fetchone()
            if broadcast is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "broadcast not found")
            existing = database.execute(
                "SELECT receipt_json FROM broadcast_receipts WHERE broadcast_id = ? AND state = ?",
                (broadcast_id, state),
            ).fetchone()
            if existing is not None:
                if existing["receipt_json"] != receipt_json:
                    raise ApiError(HTTPStatus.CONFLICT, "broadcast receipt conflicts with stored receipt")
                return {"broadcastId": broadcast_id, "state": state, "deduplicated": True}
            if occurred <= broadcast["created_at_epoch_millis"]:
                raise ApiError(HTTPStatus.CONFLICT, "broadcast receipt must follow broadcast creation")
            current_state = broadcast["last_state"]
            current_time = broadcast["updated_at_epoch_millis"]
            if current_time is not None and occurred <= current_time:
                raise ApiError(HTTPStatus.CONFLICT, "broadcast receipts must be time ordered")
            if current_state is None and state != "RECEIVED":
                raise ApiError(HTTPStatus.CONFLICT, "broadcast receipt sequence must start with RECEIVED")
            if current_state is not None and state not in BROADCAST_TRANSITIONS[current_state]:
                raise ApiError(HTTPStatus.CONFLICT, "invalid broadcast state transition")
            database.execute(
                "INSERT INTO broadcast_receipts VALUES (?, ?, ?, ?, ?)",
                (broadcast_id, state, occurred, error, receipt_json),
            )
            database.execute(
                "UPDATE text_broadcasts SET last_state = ?, updated_at_epoch_millis = ? WHERE broadcast_id = ?",
                (state, occurred, broadcast_id),
            )
            return {"broadcastId": broadcast_id, "state": state, "deduplicated": False}

    def device_commands(self, device_id: str, after_sequence: int, limit: int) -> list[dict[str, Any]]:
        return self.device_command_page(
            device_id,
            after_sequence,
            limit,
            MAX_SIGNED_INT64,
        )["commands"]

    def device_command_page(
        self,
        device_id: str,
        after_sequence: int,
        limit: int,
        maximum_bytes: int = MAX_COMMUNICATION_RESPONSE_BYTES,
    ) -> dict[str, Any]:
        if (
            not ID_PATTERN.fullmatch(device_id)
            or not 0 <= after_sequence <= MAX_SIGNED_INT64
            or not 1 <= limit <= 100
        ):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid device command query")
        with self._connect() as database:
            return bounded_sequence_query_page(
                "commands",
                database,
                "SELECT * FROM device_commands WHERE device_id = ? AND sequence > ? "
                "ORDER BY sequence LIMIT ?",
                device_id,
                after_sequence,
                limit,
                self._command_response,
                maximum_bytes,
            )

    def pending_device_commands(self, limit: int = 500, offset: int = 0) -> list[dict[str, Any]]:
        if not 1 <= limit <= 500 or offset < 0:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid pending command limit")
        with self._connect() as database:
            rows = database.execute(
                "SELECT * FROM device_commands WHERE ack_json IS NULL "
                "ORDER BY device_id, sequence LIMIT ? OFFSET ?",
                (limit, offset),
            ).fetchall()
        return [self._command_response(row) for row in rows]

    def pending_device_commands_for_device(
        self,
        device_id: str,
        after_sequence: int,
        limit: int = 100,
    ) -> list[dict[str, Any]]:
        if (
            not ID_PATTERN.fullmatch(device_id)
            or not 0 <= after_sequence <= MAX_SIGNED_INT64
            or not 1 <= limit <= 100
        ):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid pending device command query")
        with self._connect() as database:
            rows = database.execute(
                "SELECT * FROM device_commands WHERE device_id = ? AND sequence > ? "
                "AND ack_json IS NULL ORDER BY sequence LIMIT ?",
                (device_id, after_sequence, limit),
            ).fetchall()
        return [self._command_response(row) for row in rows]

    def ack_device_command(self, command_id: str, raw: Any) -> dict[str, Any]:
        if not ID_PATTERN.fullmatch(command_id) or not isinstance(raw, dict):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid command acknowledgement")
        ack_json = canonical_json(raw)
        occurred = raw.get("occurredAtEpochMillis")
        require_signed_int64(occurred, "occurredAtEpochMillis", 1)
        with self._lock, self._connect() as database:
            row = database.execute(
                "SELECT * FROM device_commands WHERE command_id = ?", (command_id,)
            ).fetchone()
            if row is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "device command not found")
            if row["ack_json"] is not None:
                if row["ack_json"] != ack_json:
                    raise ApiError(HTTPStatus.CONFLICT, "command acknowledgement conflicts")
                return {"commandId": command_id, "deduplicated": True}
            database.execute(
                "UPDATE device_commands SET ack_json = ?, acked_at_epoch_millis = ? WHERE command_id = ?",
                (ack_json, occurred, command_id),
            )
            return {"commandId": command_id, "deduplicated": False}

    def _enqueue_command(
        self,
        database: sqlite3.Connection,
        device_id: str,
        command_type: str,
        payload: dict[str, Any],
        created_at: int,
    ) -> dict[str, Any]:
        sequence = database.execute(
            "SELECT COALESCE(MAX(sequence), 0) + 1 FROM device_commands WHERE device_id = ?",
            (device_id,),
        ).fetchone()[0]
        command_id = str(uuid.uuid4())
        database.execute(
            "INSERT INTO device_commands VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)",
            (command_id, device_id, sequence, command_type, canonical_json(payload), created_at),
        )
        return {"commandId": command_id, "sequence": sequence}

    def _call_response(self, database: sqlite3.Connection, call_id: str) -> dict[str, Any]:
        row = database.execute("SELECT * FROM call_sessions WHERE call_id = ?", (call_id,)).fetchone()
        if row is None:
            raise ApiError(HTTPStatus.NOT_FOUND, "call session not found")
        history = database.execute(
            "SELECT state_sequence, state, actor_id, reason, occurred_at_epoch_millis "
            "FROM call_state_history WHERE call_id = ? ORDER BY state_sequence",
            (call_id,),
        ).fetchall()
        return {
            "callId": row["call_id"],
            "deviceId": row["device_id"],
            "direction": row["direction"],
            "mediaMode": row["media_mode"],
            "state": row["state"],
            "stateSequence": row["state_sequence"],
            "relatedEventId": row["related_event_id"],
            "simulated": bool(row["simulated"]),
            "createdAtEpochMillis": row["created_at_epoch_millis"],
            "updatedAtEpochMillis": row["updated_at_epoch_millis"],
            "lastReason": row["last_reason"],
            "history": [dict(item) for item in history],
        }

    @staticmethod
    def _command_response(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "commandId": row["command_id"],
            "deviceId": row["device_id"],
            "sequence": row["sequence"],
            "type": row["command_type"],
            "payload": json.loads(row["payload_json"]),
            "createdAtEpochMillis": row["created_at_epoch_millis"],
            "acknowledged": row["ack_json"] is not None,
        }

    def ingest_track_batch(self, raw: Any) -> dict[str, Any]:
        if not isinstance(raw, dict) or not isinstance(raw.get("points"), list):
            raise ApiError(HTTPStatus.BAD_REQUEST, "points must be an array")
        raw_points = raw["points"]
        if not 1 <= len(raw_points) <= MAX_TRACK_POINTS_PER_BATCH:
            raise ApiError(HTTPStatus.BAD_REQUEST, "track batch size is outside the allowed range")
        points = [validate_track_point(point) for point in raw_points]
        message_ids = [point["messageId"] for point in points]
        keys = [(point["deviceId"], point["sequence"]) for point in points]
        if len(set(message_ids)) != len(message_ids):
            raise ApiError(HTTPStatus.BAD_REQUEST, "duplicate messageId in track batch")
        if len(set(keys)) != len(keys):
            raise ApiError(HTTPStatus.BAD_REQUEST, "duplicate device sequence in track batch")
        if keys != sorted(keys):
            raise ApiError(HTTPStatus.BAD_REQUEST, "track batch must be ordered by device and sequence")

        accepted: list[str] = []
        duplicates: list[str] = []
        now = int(time.time() * 1000)
        with self._lock, self._connect() as database:
            for point in points:
                payload_json = canonical_json(point)
                existing_message = database.execute(
                    "SELECT payload_json FROM track_points WHERE message_id = ?",
                    (point["messageId"],),
                ).fetchone()
                if existing_message is not None:
                    if existing_message["payload_json"] != payload_json:
                        raise ApiError(
                            HTTPStatus.CONFLICT,
                            "messageId already exists with different track content",
                            messageId=point["messageId"],
                        )
                    duplicates.append(point["messageId"])
                    continue
                existing_sequence = database.execute(
                    "SELECT message_id FROM track_points WHERE device_id = ? AND sequence = ?",
                    (point["deviceId"], point["sequence"]),
                ).fetchone()
                if existing_sequence is not None:
                    raise ApiError(
                        HTTPStatus.CONFLICT,
                        "device sequence already belongs to another messageId",
                        messageId=point["messageId"],
                    )
                database.execute(
                    "INSERT INTO track_points VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (
                        point["messageId"],
                        point["deviceId"],
                        point["sequence"],
                        point["fixId"],
                        point["occurredAtEpochMillis"],
                        payload_json,
                        now,
                    ),
                )
                accepted.append(point["messageId"])
        return {
            "acceptedMessageIds": accepted,
            "duplicateMessageIds": duplicates,
        }

    def tracks(self, device_id: str, after_sequence: int = 0, limit: int = 1000) -> list[dict[str, Any]]:
        if not ID_PATTERN.fullmatch(device_id):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid deviceId")
        if not 0 <= after_sequence <= MAX_SIGNED_INT64 or not 1 <= limit <= 1000:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid track query range")
        with self._connect() as database:
            rows = database.execute(
                "SELECT payload_json FROM track_points WHERE device_id = ? AND sequence > ? "
                "ORDER BY sequence LIMIT ?",
                (device_id, after_sequence, limit),
            ).fetchall()
        return [json.loads(row["payload_json"]) for row in rows]

    def ingest_device_status(self, raw: Any) -> dict[str, Any]:
        status = validate_device_status(raw)
        payload_json = canonical_json(status)
        now = int(time.time() * 1000)
        with self._lock, self._connect() as database:
            existing = database.execute(
                "SELECT payload_json, received_at_epoch_millis FROM device_status_events "
                "WHERE message_id = ?",
                (status["messageId"],),
            ).fetchone()
            if existing is not None:
                if existing["payload_json"] != payload_json:
                    raise ApiError(
                        HTTPStatus.CONFLICT,
                        "device status messageId already exists with different content",
                    )
                return {
                    "messageId": status["messageId"],
                    "deduplicated": True,
                    "serverReceivedAtEpochMillis": existing["received_at_epoch_millis"],
                }
            if status.get("statusSequence") is not None:
                latest = database.execute(
                    "SELECT CAST(json_extract(payload_json, '$.statusSequence') AS INTEGER) AS status_sequence "
                    "FROM device_status_events WHERE device_id = ? "
                    "AND json_type(payload_json, '$.statusSequence') = 'integer' "
                    "ORDER BY status_sequence DESC LIMIT 1",
                    (status["deviceId"],),
                ).fetchone()
                if latest is not None and status["statusSequence"] <= latest["status_sequence"]:
                    raise ApiError(HTTPStatus.CONFLICT, "device statusSequence must increase")
            else:
                latest = database.execute(
                    "SELECT occurred_at_epoch_millis FROM device_status_events WHERE device_id = ? "
                    "ORDER BY occurred_at_epoch_millis DESC LIMIT 1",
                    (status["deviceId"],),
                ).fetchone()
                if (
                    latest is not None
                    and status["occurredAtEpochMillis"] < latest["occurred_at_epoch_millis"]
                ):
                    raise ApiError(HTTPStatus.CONFLICT, "device statuses must be time ordered")
            database.execute(
                "INSERT INTO device_status_events VALUES (?, ?, ?, ?, ?)",
                (
                    status["messageId"], status["deviceId"], status["occurredAtEpochMillis"],
                    payload_json, now,
                ),
            )
        return {
            "messageId": status["messageId"],
            "deduplicated": False,
            "serverReceivedAtEpochMillis": now,
        }

    def device_overview(
        self,
        actor_id: str,
        actor_role: str,
        allowed_device_ids: set[str] | None,
        limit: int = 500,
    ) -> list[dict[str, Any]]:
        self._validate_alert_actor(actor_id, actor_role)
        if not 1 <= limit <= 500:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid device overview limit")
        with self._connect() as database:
            if allowed_device_ids is None:
                rows = database.execute(
                    "SELECT device_id FROM device_status_events "
                    "UNION SELECT device_id FROM track_points "
                    "UNION SELECT device_id FROM call_sessions "
                    "UNION SELECT device_id FROM safety_alerts "
                    "UNION SELECT device_id FROM voice_messages "
                    "UNION SELECT json_extract(metadata_json, '$.deviceId') AS device_id FROM media_archives "
                    "ORDER BY device_id LIMIT ?",
                    (limit,),
                ).fetchall()
                device_ids = [row["device_id"] for row in rows if row["device_id"]]
            else:
                device_ids = sorted(allowed_device_ids)[:limit]
            return [self._device_overview_response(database, device_id) for device_id in device_ids]

    def _device_overview_response(
        self,
        database: sqlite3.Connection,
        device_id: str,
    ) -> dict[str, Any]:
        status_row = database.execute(
            "SELECT payload_json, received_at_epoch_millis FROM device_status_events WHERE device_id = ? "
            "ORDER BY received_at_epoch_millis DESC, message_id DESC LIMIT 1",
            (device_id,),
        ).fetchone()
        status = None if status_row is None else json.loads(status_row["payload_json"])
        status_received_at = (
            None if status_row is None else status_row["received_at_epoch_millis"]
        )
        track_rows = database.execute(
            "SELECT payload_json FROM track_points WHERE device_id = ? "
            "ORDER BY sequence DESC LIMIT 50",
            (device_id,),
        ).fetchall()
        track = [json.loads(row["payload_json"]) for row in reversed(track_rows)]
        latest_track = track[-1] if track else None
        alert_row = database.execute(
            "SELECT * FROM safety_alerts WHERE device_id = ? AND workflow_state != 'CLOSED' "
            "ORDER BY updated_at_epoch_millis DESC LIMIT 1",
            (device_id,),
        ).fetchone()
        active_alert_count = database.execute(
            "SELECT COUNT(*) FROM safety_alerts WHERE device_id = ? "
            "AND workflow_state != 'CLOSED' AND device_active = 1",
            (device_id,),
        ).fetchone()[0]
        terminal_states = tuple(sorted({"REJECTED", "ENDED", "FAILED"}))
        call_row = database.execute(
            "SELECT call_id FROM call_sessions WHERE device_id = ? "
            f"AND state NOT IN ({','.join('?' for _ in terminal_states)}) "
            "ORDER BY updated_at_epoch_millis DESC LIMIT 1",
            (device_id, *terminal_states),
        ).fetchone()
        call = None if call_row is None else self._call_response(database, call_row["call_id"])
        latest_offer_sequence = None
        if call is not None:
            latest_offer_sequence = database.execute(
                "SELECT MAX(sequence) FROM call_signals "
                "WHERE call_id = ? AND signal_type = 'OFFER'",
                (call["callId"],),
            ).fetchone()[0]
        media_row = database.execute(
            "SELECT metadata_json FROM media_archives "
            "WHERE json_extract(metadata_json, '$.deviceId') = ? "
            "AND json_extract(metadata_json, '$.kind') IN ('PHOTO', 'VIDEO') "
            "ORDER BY CAST(json_extract(metadata_json, '$.createdAtEpochMillis') AS INTEGER) DESC LIMIT 1",
            (device_id,),
        ).fetchone()
        media = None if media_row is None else json.loads(media_row["metadata_json"])

        status_location = None if status is None else status["location"]
        status_location_time = (
            0 if status_location is None or status_location.get("latitude") is None
            else status_location.get("occurredAtEpochMillis") or status["occurredAtEpochMillis"]
        )
        track_time = 0 if latest_track is None else latest_track["occurredAtEpochMillis"]
        if status_location_time >= track_time and status_location_time > 0:
            location = {
                "latitude": status_location["latitude"],
                "longitude": status_location["longitude"],
                "horizontalAccuracyMeters": status_location.get("horizontalAccuracyMeters"),
                "fixType": status_location["fixType"],
                "occurredAtEpochMillis": status_location_time,
                "source": "DEVICE_STATUS",
            }
        elif latest_track is not None:
            location = {
                "latitude": latest_track["latitude"],
                "longitude": latest_track["longitude"],
                "horizontalAccuracyMeters": latest_track.get("horizontalAccuracyMeters"),
                "fixType": latest_track["quality"],
                "occurredAtEpochMillis": latest_track["occurredAtEpochMillis"],
                "source": "TRACK",
            }
        else:
            location = {
                "latitude": None,
                "longitude": None,
                "horizontalAccuracyMeters": None,
                "fixType": "NO_FIX",
                "occurredAtEpochMillis": None,
                "source": "NONE",
            }
        latest_alert = None if alert_row is None else self._alert_response(database, alert_row)
        last_seen_candidates = [
            0 if status is None else status["occurredAtEpochMillis"],
            track_time,
            0 if latest_alert is None else latest_alert["updatedAtEpochMillis"],
            0 if call is None else call["updatedAtEpochMillis"],
            0 if media is None else media["createdAtEpochMillis"],
        ]
        battery = {
            **(
                {"present": False, "percent": None, "voltageMillivolts": None}
                if status is None else status["battery"]
            ),
            "reported": status is not None,
        }
        rtk_payload = {} if status is None else status.get("rtk", {})
        rtk = {
            "state": "UNREPORTED",
            "correctionFrames": 0,
            "correctionBytes": 0,
            "lastFixQuality": "NO_FIX",
            "lastError": None,
            **rtk_payload,
            "reported": bool(rtk_payload) and rtk_payload.get("state") != "UNREPORTED",
        }
        local_intercom_payload = {} if status is None else status.get("localIntercom", {})
        local_intercom = {
            "state": "UNREPORTED",
            "peerCount": 0,
            "codec": "unknown",
            "rssiDbm": None,
            "packetLossPermille": None,
            "oneWayLatencyMillis": None,
            "faultCode": 0,
            "lastError": None,
            **local_intercom_payload,
            "reported": (
                bool(local_intercom_payload)
                and local_intercom_payload.get("state") != "UNREPORTED"
            ),
        }
        clock = {
            "reported": status is not None,
            "source": None if status is None else status["time"]["source"],
            "synchronized": False if status is None else status["time"]["synchronized"],
            "uncertaintyMillis": (
                None if status is None else status["time"].get("uncertaintyMillis")
            ),
            "calibrationAgeMillis": (
                None if status is None else status["time"].get("calibrationAgeMillis")
            ),
            "calibrationSequence": (
                None if status is None else status["time"].get("calibrationSequence")
            ),
            "deviceOccurredAtEpochMillis": (
                None if status is None else status["occurredAtEpochMillis"]
            ),
            "serverReceivedAtEpochMillis": status_received_at,
            "observedOffsetMillis": (
                None if status is None or status_received_at is None
                else status["occurredAtEpochMillis"] - status_received_at
            ),
            "includesTransportDelay": True,
        }
        return {
            "deviceId": device_id,
            "personId": (
                status.get("personId") if status is not None and status.get("personId") is not None
                else None if media is None else media.get("personId")
            ),
            "operationalState": None if status is None else status["operationalState"],
            "networkState": None if status is None else status["networkState"],
            "statusSequence": None if status is None else status.get("statusSequence"),
            "hardwareMode": None if status is None else status["hardwareMode"],
            "cameraAvailable": None if status is None else status["cameraAvailable"],
            "simulated": None if status is None else status["simulated"],
            "lastSeenAtEpochMillis": max(last_seen_candidates) or None,
            "lastContactAtEpochMillis": status_received_at,
            "clock": clock,
            "battery": battery,
            "rtk": rtk,
            "localIntercom": local_intercom,
            "location": location,
            "track": [
                {
                    "latitude": point["latitude"],
                    "longitude": point["longitude"],
                    "occurredAtEpochMillis": point["occurredAtEpochMillis"],
                    "quality": point["quality"],
                }
                for point in track
            ],
            "activeAlertCount": active_alert_count,
            "latestAlert": latest_alert,
            "liveVideo": None if call is None or call["mediaMode"] != "VIDEO_UPLINK" else {
                "callId": call["callId"],
                "state": call["state"],
                "hasOffer": latest_offer_sequence is not None,
                "latestOfferSequence": latest_offer_sequence,
            },
            "latestMedia": None if media is None else {
                "mediaId": media["mediaId"],
                "kind": media["kind"],
                "createdAtEpochMillis": media["createdAtEpochMillis"],
                "contentPath": f"/v1/media/{media['mediaId']}/content",
            },
        }

    def create_or_resume(self, raw_metadata: Any) -> dict[str, Any]:
        metadata = validate_metadata(raw_metadata)
        media_id = metadata["mediaId"]
        digest = metadata["sha256"]
        byte_size = metadata["byteSize"]
        metadata_json = canonical_json(metadata)
        with self._lock, self._connect() as database:
            if metadata["kind"] == "VOICE" and metadata.get("callId") is not None:
                call = database.execute(
                    "SELECT device_id FROM call_sessions WHERE call_id = ?", (metadata["callId"],)
                ).fetchone()
                if call is None:
                    raise ApiError(HTTPStatus.NOT_FOUND, "voice message call session not found")
                if call["device_id"] != metadata["deviceId"]:
                    raise ApiError(HTTPStatus.CONFLICT, "voice message call belongs to another device")
            archive = database.execute(
                "SELECT archive_id, object_sha256, metadata_json FROM media_archives WHERE media_id = ?",
                (media_id,),
            ).fetchone()
            if archive is not None:
                existing = json.loads(archive["metadata_json"])
                self._require_same_identity(existing, metadata)
                return self._session_response(
                    session_id=f"archive:{archive['archive_id']}",
                    next_offset=byte_size,
                    status="COMPLETED",
                    archive_id=archive["archive_id"],
                    deduplicated=True,
                )

            session = database.execute(
                "SELECT * FROM upload_sessions WHERE media_id = ?",
                (media_id,),
            ).fetchone()
            if session is not None:
                self._require_same_identity(json.loads(session["metadata_json"]), metadata)
                return self._session_response_from_row(session)

            now = int(time.time() * 1000)
            session_id = str(uuid.uuid4())
            temp_path = self.uploads / f"{session_id}.partial"
            existing_object = database.execute(
                "SELECT * FROM media_objects WHERE sha256 = ?",
                (digest,),
            ).fetchone()
            if existing_object is not None:
                if existing_object["byte_size"] != byte_size:
                    raise ApiError(HTTPStatus.CONFLICT, "digest exists with a different size")
                try:
                    object_matches = self.object_store.matches(
                        existing_object["object_path"],
                        byte_size,
                        digest,
                    )
                except ObjectStoreError as error:
                    raise ApiError(
                        HTTPStatus.SERVICE_UNAVAILABLE,
                        "object storage verification failed",
                    ) from error
                if not object_matches:
                    raise ApiError(HTTPStatus.CONFLICT, "stored object is missing or corrupt")
                archive_id = media_id
                database.execute(
                    "INSERT INTO media_archives VALUES (?, ?, ?, ?, ?)",
                    (archive_id, media_id, digest, metadata_json, now),
                )
                if metadata["kind"] == "VOICE":
                    self._persist_voice_message(database, metadata)
                database.execute(
                    "INSERT INTO upload_sessions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        session_id,
                        media_id,
                        digest,
                        byte_size,
                        metadata_json,
                        str(temp_path),
                        byte_size,
                        "COMPLETED",
                        archive_id,
                        1,
                        now,
                        now,
                    ),
                )
                return self._session_response(
                    session_id, byte_size, "COMPLETED", archive_id, deduplicated=True
                )

            with temp_path.open("xb") as output:
                output.flush()
                os.fsync(output.fileno())
            database.execute(
                "INSERT INTO upload_sessions VALUES (?, ?, ?, ?, ?, ?, 0, 'UPLOADING', NULL, 0, ?, ?)",
                (session_id, media_id, digest, byte_size, metadata_json, str(temp_path), now, now),
            )
            return self._session_response(session_id, 0, "UPLOADING", None, deduplicated=False)

    def append_chunk(
        self,
        session_id: str,
        start: int,
        end_inclusive: int,
        total: int,
        body: bytes,
        chunk_sha256: str,
    ) -> dict[str, Any]:
        if not SHA256_PATTERN.fullmatch(chunk_sha256):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid chunk SHA-256")
        if hashlib.sha256(body).hexdigest() != chunk_sha256:
            raise ApiError(HTTPStatus.UNPROCESSABLE_ENTITY, "chunk SHA-256 mismatch")
        if end_inclusive != start + len(body) - 1 or len(body) > self.chunk_size:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid chunk range or size")
        with self._lock, self._connect() as database:
            session = self._require_session(database, session_id)
            if total != session["byte_size"] or end_inclusive >= total:
                raise ApiError(HTTPStatus.BAD_REQUEST, "chunk total does not match session")
            if session["status"] == "COMPLETED":
                return self._session_response_from_row(session)
            current = session["next_offset"]
            path = Path(session["temp_path"])
            if start < current:
                if end_inclusive >= current:
                    raise ApiError(HTTPStatus.CONFLICT, "chunk overlaps the current offset", nextOffset=current)
                with path.open("rb") as source:
                    source.seek(start)
                    if source.read(len(body)) != body:
                        raise ApiError(HTTPStatus.CONFLICT, "replayed chunk content differs", nextOffset=current)
                return self._session_response(session_id, current, "UPLOADING", None, False)
            if start > current:
                raise ApiError(HTTPStatus.CONFLICT, "chunk starts after current offset", nextOffset=current)
            with path.open("r+b") as output:
                output.seek(start)
                output.write(body)
                output.flush()
                os.fsync(output.fileno())
            next_offset = start + len(body)
            now = int(time.time() * 1000)
            changed = database.execute(
                "UPDATE upload_sessions SET next_offset = ?, updated_at_epoch_millis = ? "
                "WHERE session_id = ? AND next_offset = ? AND status = 'UPLOADING'",
                (next_offset, now, session_id, current),
            ).rowcount
            if changed != 1:
                raise ApiError(HTTPStatus.CONFLICT, "session offset changed concurrently")
            return self._session_response(session_id, next_offset, "UPLOADING", None, False)

    def complete(self, session_id: str, raw_request: Any) -> dict[str, Any]:
        if not isinstance(raw_request, dict):
            raise ApiError(HTTPStatus.BAD_REQUEST, "completion request must be an object")
        with self._lock, self._connect() as database:
            session = self._require_session(database, session_id)
            if session["status"] == "COMPLETED":
                return self._session_response_from_row(session)
            for name, expected in (
                ("mediaId", session["media_id"]),
                ("byteSize", session["byte_size"]),
                ("sha256", session["sha256"]),
            ):
                if raw_request.get(name) != expected:
                    raise ApiError(HTTPStatus.CONFLICT, f"completion {name} does not match session")
            if session["next_offset"] != session["byte_size"]:
                raise ApiError(
                    HTTPStatus.CONFLICT,
                    "upload is incomplete",
                    nextOffset=session["next_offset"],
                )
            temp_path = Path(session["temp_path"])
            if temp_path.exists():
                if temp_path.stat().st_size != session["byte_size"]:
                    raise ApiError(HTTPStatus.UNPROCESSABLE_ENTITY, "uploaded file size mismatch")
                if sha256_file(temp_path) != session["sha256"]:
                    raise ApiError(HTTPStatus.UNPROCESSABLE_ENTITY, "uploaded file SHA-256 mismatch")
            else:
                expected_locator = self.object_store.locator_for(session["sha256"])
                try:
                    already_stored = self.object_store.matches(
                        expected_locator,
                        session["byte_size"],
                        session["sha256"],
                    )
                except ObjectStoreError as error:
                    raise ApiError(
                        HTTPStatus.SERVICE_UNAVAILABLE,
                        "object storage verification failed",
                    ) from error
                if not already_stored:
                    raise ApiError(HTTPStatus.UNPROCESSABLE_ENTITY, "uploaded file is missing")

            try:
                object_locator, deduplicated = self.object_store.store(
                    temp_path,
                    session["byte_size"],
                    session["sha256"],
                )
            except ObjectStoreError as error:
                raise ApiError(HTTPStatus.SERVICE_UNAVAILABLE, "object storage commit failed") from error

            now = int(time.time() * 1000)
            database.execute(
                "INSERT OR IGNORE INTO media_objects VALUES (?, ?, ?, ?)",
                (session["sha256"], session["byte_size"], object_locator, now),
            )
            archive_id = session["media_id"]
            database.execute(
                "INSERT OR IGNORE INTO media_archives VALUES (?, ?, ?, ?, ?)",
                (
                    archive_id,
                    session["media_id"],
                    session["sha256"],
                    session["metadata_json"],
                    now,
                ),
            )
            metadata = json.loads(session["metadata_json"])
            if metadata["kind"] == "VOICE":
                self._persist_voice_message(database, metadata)
            database.execute(
                "UPDATE upload_sessions SET status = 'COMPLETED', archive_id = ?, deduplicated = ?, "
                "updated_at_epoch_millis = ? WHERE session_id = ?",
                (archive_id, int(deduplicated), now, session_id),
            )
            return self._session_response(
                session_id,
                session["byte_size"],
                "COMPLETED",
                archive_id,
                deduplicated,
            )

    @staticmethod
    def _persist_voice_message(database: sqlite3.Connection, metadata: dict[str, Any]) -> None:
        existing = database.execute(
            "SELECT metadata_json FROM voice_messages WHERE message_id = ?", (metadata["mediaId"],)
        ).fetchone()
        metadata_json = canonical_json(metadata)
        if existing is not None:
            if existing["metadata_json"] != metadata_json:
                raise ApiError(HTTPStatus.CONFLICT, "voice message already exists with different content")
            return
        database.execute(
            "INSERT INTO voice_messages VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                metadata["mediaId"], metadata["sha256"], metadata["deviceId"],
                metadata.get("callId"), metadata.get("relatedEventId"), metadata["senderId"],
                metadata["mimeType"], metadata["byteSize"], metadata["durationMillis"],
                canonical_json(metadata["allowedRoles"]), metadata_json,
                metadata["createdAtEpochMillis"],
            ),
        )
        database.execute(
            "INSERT INTO voice_message_audit VALUES (?, ?, ?, ?, 'UPLOAD', ?)",
            (
                str(uuid.uuid4()), metadata["mediaId"], metadata["senderId"], metadata["senderRole"],
                int(time.time() * 1000),
            ),
        )

    def archive(self, media_id: str) -> tuple[dict[str, Any], str]:
        with self._connect() as database:
            row = database.execute(
                "SELECT a.archive_id, a.metadata_json, o.object_path "
                "FROM media_archives a JOIN media_objects o ON o.sha256 = a.object_sha256 "
                "WHERE a.media_id = ?",
                (media_id,),
            ).fetchone()
        if row is None:
            raise ApiError(HTTPStatus.NOT_FOUND, "media archive not found")
        metadata = json.loads(row["metadata_json"])
        metadata.update({"archiveId": row["archive_id"], "status": "COMPLETED"})
        return metadata, row["object_path"]

    def media_archives(
        self,
        device_id: str | None,
        kind: str | None,
        limit: int,
        allowed_device_ids: set[str] | None = None,
    ) -> list[dict[str, Any]]:
        if device_id is not None and not ID_PATTERN.fullmatch(device_id):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid deviceId")
        if kind is not None and kind not in {"PHOTO", "VIDEO"}:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid media kind")
        if not 1 <= limit <= 100:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid media query limit")
        clauses = ["json_extract(metadata_json, '$.kind') IN ('PHOTO', 'VIDEO')"]
        values: list[Any] = []
        if device_id is not None:
            clauses.append("json_extract(metadata_json, '$.deviceId') = ?")
            values.append(device_id)
        if kind is not None:
            clauses.append("json_extract(metadata_json, '$.kind') = ?")
            values.append(kind)
        if allowed_device_ids is not None:
            if not allowed_device_ids:
                return []
            placeholders = ",".join("?" for _ in allowed_device_ids)
            clauses.append(f"json_extract(metadata_json, '$.deviceId') IN ({placeholders})")
            values.extend(sorted(allowed_device_ids))
        with self._connect() as database:
            rows = database.execute(
                "SELECT archive_id, media_id, metadata_json FROM media_archives "
                f"WHERE {' AND '.join(clauses)} "
                "ORDER BY CAST(json_extract(metadata_json, '$.createdAtEpochMillis') AS INTEGER) DESC, "
                "media_id DESC LIMIT ?",
                (*values, limit),
            ).fetchall()
        archives = []
        for row in rows:
            metadata = json.loads(row["metadata_json"])
            metadata.update({
                "archiveId": row["archive_id"],
                "status": "COMPLETED",
                "contentPath": f"/v1/media/{metadata['mediaId']}/content",
            })
            archives.append(metadata)
        return archives

    def open_object(self, locator: str, byte_size: int, digest: str) -> Any:
        try:
            return self.object_store.open_verified(
                locator,
                byte_size,
                digest,
                self.uploads,
            )
        except ObjectStoreError as error:
            raise ApiError(HTTPStatus.SERVICE_UNAVAILABLE, "object storage read failed") from error

    def check_ready(self) -> None:
        try:
            with self._connect() as database:
                row = database.execute("SELECT 1 AS ready").fetchone()
            if row is None or row["ready"] != 1:
                raise RuntimeError("database readiness query returned no result")
            self.object_store.ready()
        except Exception as error:
            raise RuntimeError("backend persistence is not ready") from error

    def _require_session(self, database: sqlite3.Connection, session_id: str) -> sqlite3.Row:
        row = database.execute(
            "SELECT * FROM upload_sessions WHERE session_id = ?",
            (session_id,),
        ).fetchone()
        if row is None:
            raise ApiError(HTTPStatus.NOT_FOUND, "upload session not found")
        return row

    @staticmethod
    def _require_same_identity(existing: dict[str, Any], requested: dict[str, Any]) -> None:
        for name in ("mediaId", "deviceId", "byteSize", "sha256"):
            if existing.get(name) != requested.get(name):
                raise ApiError(HTTPStatus.CONFLICT, f"mediaId already exists with different {name}")

    def _session_response_from_row(self, row: sqlite3.Row) -> dict[str, Any]:
        return self._session_response(
            row["session_id"],
            row["next_offset"],
            row["status"],
            row["archive_id"],
            bool(row["deduplicated"]),
        )

    def _session_response(
        self,
        session_id: str,
        next_offset: int,
        status: str,
        archive_id: str | None,
        deduplicated: bool,
    ) -> dict[str, Any]:
        return {
            "sessionId": session_id,
            "nextOffset": next_offset,
            "chunkSize": self.chunk_size,
            "status": status,
            "archiveId": archive_id,
            "deduplicated": deduplicated,
        }


class MediaHttpServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(
        self,
        address: tuple[str, int],
        repository: MediaRepository,
        bearer_token: str,
        stun_urls: tuple[str, ...] = (),
        turn_urls: tuple[str, ...] = (),
        turn_shared_secret: str = "",
        ice_credential_ttl_seconds: int = 600,
        dashboard_root: Path | None = None,
        auth_principals: dict[str, AuthPrincipal] | None = None,
        device_organizations: dict[str, str] | None = None,
        map_tile_url_template: str = "",
        map_attribution: str = "",
        map_min_zoom: int = 3,
        map_max_zoom: int = 19,
        readiness_checks: tuple[Callable[[], None], ...] = (),
    ) -> None:
        self.repository = repository
        self.bearer_token = bearer_token
        self.stun_urls = list(stun_urls)
        self.turn_urls = list(turn_urls)
        self.turn_shared_secret = turn_shared_secret
        self.ice_credential_ttl_seconds = ice_credential_ttl_seconds
        self.auth_principals = dict(auth_principals or {})
        self.device_organizations = dict(device_organizations or {})
        self.map_tile_url_template = validate_map_tile_template(map_tile_url_template)
        if not isinstance(map_attribution, str) or len(map_attribution) > 500:
            raise ValueError("map attribution is invalid")
        if not 0 <= map_min_zoom <= map_max_zoom <= 22:
            raise ValueError("map zoom range is invalid")
        self.map_attribution = map_attribution.strip()
        self.map_min_zoom = map_min_zoom
        self.map_max_zoom = map_max_zoom
        self.readiness_checks = tuple(readiness_checks)
        self.dashboard_root = (
            dashboard_root if dashboard_root is not None
            else Path(__file__).resolve().parent.parent / "dashboard"
        ).resolve()
        super().__init__(address, MediaRequestHandler)


class MediaRequestHandler(BaseHTTPRequestHandler):
    server: MediaHttpServer
    authenticated_principal: AuthPrincipal | None = None

    def do_GET(self) -> None:  # noqa: N802
        self._dispatch(self._get)

    def do_POST(self) -> None:  # noqa: N802
        self._dispatch(self._post)

    def do_PUT(self) -> None:  # noqa: N802
        self._dispatch(self._put)

    def _dispatch(self, action: Any) -> None:
        self.authenticated_principal = None
        try:
            action()
        except ApiError as error:
            if error.status in {HTTPStatus.UNAUTHORIZED, HTTPStatus.FORBIDDEN}:
                try:
                    self.server.repository.record_security_denial(
                        self.authenticated_principal,
                        self.command,
                        urlparse(self.path).path,
                        int(error.status),
                        error.message,
                    )
                except Exception:
                    logging.exception("failed to record security denial")
            self._json(error.status, {"error": error.message, **error.details})
        except (BrokenPipeError, ConnectionResetError):
            return
        except Exception:
            logging.exception("unhandled media request failure")
            self._json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": "internal server error"})

    def _get(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path in {"/dashboard", "/dashboard/", "/dashboard/app.js", "/dashboard/styles.css"}:
            self._serve_dashboard(parsed.path)
            return
        if parsed.path == "/health":
            self._json(HTTPStatus.OK, {"status": "ok"})
            return
        if parsed.path == "/ready":
            try:
                self.server.repository.check_ready()
                for readiness_check in self.server.readiness_checks:
                    readiness_check()
            except RuntimeError:
                self._json(HTTPStatus.SERVICE_UNAVAILABLE, {"status": "not_ready"})
                return
            self._json(HTTPStatus.OK, {"status": "ready"})
            return
        self._authorize()
        if parsed.path == "/v1/access-profile":
            self._json(HTTPStatus.OK, self._access_profile())
            return
        if parsed.path == "/v1/tracks":
            query = parse_qs(parsed.query)
            device_id = query.get("deviceId", [""])[0]
            self._authorize_operator_device(device_id)
            try:
                after_sequence = int(query.get("afterSequence", ["0"])[0])
                limit = int(query.get("limit", ["1000"])[0])
            except ValueError as error:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid track query range") from error
            self._json(
                HTTPStatus.OK,
                {"points": self.server.repository.tracks(device_id, after_sequence, limit)},
            )
            return
        if parsed.path == "/v1/device-commands":
            query = parse_qs(parsed.query)
            device_id = query.get("deviceId", [""])[0]
            self._authorize_device(device_id)
            try:
                after_sequence = int(query.get("afterSequence", ["0"])[0])
                limit = int(query.get("limit", ["100"])[0])
            except ValueError as error:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid device command query") from error
            self._json(
                HTTPStatus.OK,
                self.server.repository.device_command_page(
                    device_id,
                    after_sequence,
                    limit,
                ),
            )
            return
        if parsed.path == "/v1/alerts":
            query = parse_qs(parsed.query)
            try:
                limit = int(query.get("limit", ["100"])[0])
            except ValueError as error:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid alert query") from error
            actor_id, actor_role = self._alert_actor_headers()
            requested_device_id = query.get("deviceId", [None])[0]
            allowed_device_ids = self._authorize_operator_list(requested_device_id)
            self._json(
                HTTPStatus.OK,
                {
                    "alerts": self.server.repository.safety_alerts(
                        actor_id,
                        actor_role,
                        requested_device_id,
                        query.get("workflowState", [None])[0],
                        limit,
                        allowed_device_ids,
                    )
                },
            )
            return
        if parsed.path == "/v1/calls":
            query = parse_qs(parsed.query)
            try:
                limit = int(query.get("limit", ["100"])[0])
            except ValueError as error:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid call query") from error
            actor_id, actor_role = self._call_operator_actor(control=False)
            requested_device_id = query.get("deviceId", [None])[0]
            allowed_device_ids = self._authorize_operator_list(requested_device_id)
            self._json(
                HTTPStatus.OK,
                {
                    "calls": self.server.repository.calls(
                        actor_id,
                        actor_role,
                        requested_device_id,
                        query.get("state", [None])[0],
                        limit,
                        allowed_device_ids,
                    )
                },
            )
            return
        if parsed.path == "/v1/broadcasts":
            query = parse_qs(parsed.query)
            try:
                limit = int(query.get("limit", ["100"])[0])
            except ValueError as error:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid broadcast query") from error
            self._alert_actor_headers()
            requested_device_id = query.get("deviceId", [None])[0]
            allowed_device_ids = self._authorize_operator_list(requested_device_id)
            broadcasts = self.server.repository.broadcasts(
                requested_device_id, limit, allowed_device_ids,
            )
            self._json(
                HTTPStatus.OK,
                bounded_json_page("broadcasts", broadcasts, MAX_COMMUNICATION_RESPONSE_BYTES),
            )
            return
        if parsed.path == "/v1/devices/overview":
            query = parse_qs(parsed.query)
            try:
                limit = int(query.get("limit", ["500"])[0])
            except ValueError as error:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid device overview query") from error
            actor_id, actor_role = self._alert_actor_headers()
            allowed_device_ids = self._authorize_operator_list(None)
            self._json(
                HTTPStatus.OK,
                {
                    "devices": self.server.repository.device_overview(
                        actor_id, actor_role, allowed_device_ids, limit,
                    ),
                },
            )
            return
        if parsed.path == "/v1/dashboard-config":
            self._call_operator_actor(control=False)
            self._json(
                HTTPStatus.OK,
                {
                    "map": {
                        "tileUrlTemplate": self.server.map_tile_url_template or None,
                        "attribution": self.server.map_attribution or None,
                        "minimumZoom": self.server.map_min_zoom,
                        "maximumZoom": self.server.map_max_zoom,
                        "configured": bool(self.server.map_tile_url_template),
                    },
                },
            )
            return
        if parsed.path == "/v1/security-audit":
            query = parse_qs(parsed.query)
            try:
                limit = int(query.get("limit", ["100"])[0])
            except ValueError as error:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid security audit query") from error
            _, organization_id = self._admin_actor()
            self._json(
                HTTPStatus.OK,
                {"events": self.server.repository.security_audit_events(organization_id, limit)},
            )
            return
        match = re.fullmatch(r"/v1/alerts/([^/]+)", parsed.path)
        if match is not None:
            alert_id = unquote(match.group(1))
            actor_id, actor_role = self._alert_actor_headers()
            self._authorize_operator_device(self.server.repository.alert_device(alert_id))
            self._json(
                HTTPStatus.OK,
                self.server.repository.safety_alert(alert_id, actor_id, actor_role),
            )
            return
        match = re.fullmatch(r"/v1/calls/([^/]+)/ice-config", parsed.path)
        if match is not None:
            call_id = unquote(match.group(1))
            self.server.repository.call(call_id)
            requester_id = parse_qs(parsed.query).get("requesterId", [""])[0]
            authenticated_actor = self._authenticated_call_actor(call_id, control=True)
            if authenticated_actor is not None and requester_id != authenticated_actor:
                raise ApiError(HTTPStatus.FORBIDDEN, "call requester does not match authenticated principal")
            response = self.server.repository.call_ice_configuration(
                call_id,
                requester_id,
                self.server.stun_urls,
                self.server.turn_urls,
                self.server.turn_shared_secret,
                int(time.time()),
                self.server.ice_credential_ttl_seconds,
            )
            self._json(HTTPStatus.OK, response)
            return
        match = re.fullmatch(r"/v1/calls/([^/]+)/signals", parsed.path)
        if match is not None:
            call_id = unquote(match.group(1))
            self._authenticated_call_actor(call_id, control=True)
            query = parse_qs(parsed.query)
            try:
                after_sequence = int(query.get("afterSequence", ["0"])[0])
                limit = int(query.get("limit", ["100"])[0])
            except ValueError as error:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid call signal query") from error
            self._json(
                HTTPStatus.OK,
                self.server.repository.call_signal_page(
                    call_id,
                    after_sequence,
                    limit,
                ),
            )
            return
        match = re.fullmatch(r"/v1/calls/([^/]+)", parsed.path)
        if match is not None:
            call_id = unquote(match.group(1))
            self._authenticated_call_actor(call_id, control=False)
            self._json(HTTPStatus.OK, self.server.repository.call(call_id))
            return
        if parsed.path == "/v1/voice-messages":
            query = parse_qs(parsed.query)
            try:
                limit = int(query.get("limit", ["100"])[0])
            except ValueError as error:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid voice message query") from error
            actor_id, actor_role = self._voice_actor_headers()
            requested_device_id = query.get("deviceId", [None])[0]
            allowed_device_ids = self._authorize_operator_list(requested_device_id)
            messages = self.server.repository.voice_messages(
                requested_device_id,
                query.get("callId", [None])[0],
                actor_id,
                actor_role,
                limit,
                allowed_device_ids,
            )
            self._json(HTTPStatus.OK, {"messages": messages})
            return
        if parsed.path == "/v1/media":
            query = parse_qs(parsed.query)
            try:
                limit = int(query.get("limit", ["100"])[0])
            except ValueError as error:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid media query") from error
            self._alert_actor_headers()
            requested_device_id = query.get("deviceId", [None])[0]
            allowed_device_ids = self._authorize_operator_list(requested_device_id)
            archives = self.server.repository.media_archives(
                requested_device_id,
                query.get("kind", [None])[0],
                limit,
                allowed_device_ids,
            )
            self._json(
                HTTPStatus.OK,
                bounded_json_page("media", archives, MAX_COMMUNICATION_RESPONSE_BYTES),
            )
            return
        match = re.fullmatch(r"/v1/voice-messages/([^/]+)(/content)?", parsed.path)
        if match is not None:
            message_id = unquote(match.group(1))
            actor_id, actor_role = self._voice_actor_headers()
            self._authorize_operator_device(self.server.repository.voice_message_device(message_id))
            metadata, object_locator = self.server.repository.voice_message(
                message_id,
                actor_id,
                actor_role,
                "PLAYBACK" if match.group(2) is not None else "READ",
            )
            if match.group(2) is None:
                self._json(HTTPStatus.OK, metadata)
                return
            with self.server.repository.open_object(
                object_locator, metadata["byteSize"], metadata["sha256"]
            ) as source:
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", metadata["mimeType"])
                self.send_header("Content-Length", str(metadata["byteSize"]))
                self.send_header("ETag", f'"{metadata["sha256"]}"')
                self.send_header("Cache-Control", "private, no-store")
                self.end_headers()
                while chunk := source.read(64 * 1024):
                    self.wfile.write(chunk)
            return
        match = re.fullmatch(r"/v1/media/([^/]+)(/content)?", parsed.path)
        if match is None:
            raise ApiError(HTTPStatus.NOT_FOUND, "route not found")
        media_id = unquote(match.group(1))
        metadata, object_locator = self.server.repository.archive(media_id)
        self._authorize_operator_device(metadata["deviceId"])
        if self.authenticated_principal is not None and metadata["kind"] == "VOICE":
            raise ApiError(HTTPStatus.FORBIDDEN, "voice messages require the authorized voice endpoint")
        if match.group(2) is None:
            self._json(HTTPStatus.OK, metadata)
            return
        with self.server.repository.open_object(
            object_locator, metadata["byteSize"], metadata["sha256"]
        ) as source:
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", metadata["mimeType"])
            self.send_header("Content-Length", str(metadata["byteSize"]))
            self.send_header("ETag", f'"{metadata["sha256"]}"')
            self.send_header("Cache-Control", "private, no-store")
            self.end_headers()
            while chunk := source.read(64 * 1024):
                self.wfile.write(chunk)

    def _serve_dashboard(self, path: str) -> None:
        name = {
            "/dashboard": "index.html",
            "/dashboard/": "index.html",
            "/dashboard/app.js": "app.js",
            "/dashboard/styles.css": "styles.css",
        }[path]
        content_type = {
            "index.html": "text/html; charset=utf-8",
            "app.js": "text/javascript; charset=utf-8",
            "styles.css": "text/css; charset=utf-8",
        }[name]
        file_path = self.server.dashboard_root / name
        if not file_path.is_file():
            raise ApiError(HTTPStatus.NOT_FOUND, "dashboard asset not found")
        body = file_path.read_bytes()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self' https:; "
            "img-src 'self' blob: data: https:; media-src 'self' blob:; "
            "object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
        )
        self.end_headers()
        self.wfile.write(body)

    def _post(self) -> None:
        self._authorize()
        parsed = urlparse(self.path)
        if parsed.path == "/v1/device-status":
            value = self._read_json(MAX_DEVICE_STATUS_BYTES)
            if not isinstance(value, dict):
                raise ApiError(HTTPStatus.BAD_REQUEST, "device status must be an object")
            self._authorize_device(value.get("deviceId"))
            self._json(HTTPStatus.OK, self.server.repository.ingest_device_status(value))
            return
        if parsed.path == "/v1/tracks:batch":
            value = self._read_json(MAX_TRACK_BATCH_BYTES)
            self._authorize_device_batch(value)
            response = self.server.repository.ingest_track_batch(value)
            self._json(HTTPStatus.OK, response)
            return
        if parsed.path == "/v1/alerts":
            value = self._read_json(MAX_ALERT_BYTES)
            if not isinstance(value, dict):
                raise ApiError(HTTPStatus.BAD_REQUEST, "safety alert must be an object")
            self._authorize_device(value.get("deviceId"))
            self._json(
                HTTPStatus.OK,
                self.server.repository.ingest_safety_alert(value),
            )
            return
        match = re.fullmatch(r"/v1/alerts/([^/]+)/transitions", parsed.path)
        if match is not None:
            alert_id = unquote(match.group(1))
            actor_id, actor_role = self._alert_actor_headers()
            self._authorize_operator_device(
                self.server.repository.alert_device(alert_id), control=True,
            )
            self._json(
                HTTPStatus.OK,
                self.server.repository.transition_safety_alert(
                    alert_id,
                    self._read_json(MAX_ALERT_BYTES),
                    actor_id,
                    actor_role,
                ),
            )
            return
        if parsed.path == "/v1/calls":
            value = self._read_json()
            if not isinstance(value, dict):
                raise ApiError(HTTPStatus.BAD_REQUEST, "call request must be an object")
            self._authorize_device(value.get("deviceId"))
            self._json(HTTPStatus.OK, self.server.repository.create_call(value))
            return
        match = re.fullmatch(r"/v1/calls/([^/]+)/transitions", parsed.path)
        if match is not None:
            call_id = unquote(match.group(1))
            value = self._read_json()
            if not isinstance(value, dict):
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid call transition")
            authenticated_actor = self._authenticated_call_actor(call_id, control=True)
            if authenticated_actor is not None:
                supplied_actor = value.get("actorId")
                if supplied_actor is not None and supplied_actor != authenticated_actor:
                    raise ApiError(HTTPStatus.FORBIDDEN, "call actor does not match authenticated principal")
                value["actorId"] = authenticated_actor
            else:
                self._validate_legacy_call_control(value.get("actorId"))
            self._json(
                HTTPStatus.OK,
                self.server.repository.transition_call(call_id, value),
            )
            return
        match = re.fullmatch(r"/v1/calls/([^/]+)/signals", parsed.path)
        if match is not None:
            call_id = unquote(match.group(1))
            value = self._read_json(MAX_SIGNAL_BYTES)
            if not isinstance(value, dict):
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid call signal")
            authenticated_actor = self._authenticated_call_actor(call_id, control=True)
            if authenticated_actor is not None:
                supplied_sender = value.get("senderId")
                if supplied_sender != authenticated_actor:
                    raise ApiError(HTTPStatus.FORBIDDEN, "call sender does not match authenticated principal")
            self._json(
                HTTPStatus.OK,
                self.server.repository.create_call_signal(
                    call_id, value
                ),
            )
            return
        if parsed.path == "/v1/broadcasts":
            value = self._read_json()
            if not isinstance(value, dict):
                raise ApiError(HTTPStatus.BAD_REQUEST, "broadcast request must be an object")
            _, actor_role = self._alert_actor_headers()
            if actor_role not in ALERT_CONTROL_ROLES:
                raise ApiError(HTTPStatus.FORBIDDEN, "broadcast control is forbidden")
            self._authorize_operator_device(value.get("deviceId"), control=True)
            self._json(HTTPStatus.OK, self.server.repository.create_broadcast(value))
            return
        match = re.fullmatch(r"/v1/broadcasts/([^/]+)/receipts", parsed.path)
        if match is not None:
            broadcast_id = unquote(match.group(1))
            self._authorize_device(self.server.repository.broadcast_device(broadcast_id))
            self._json(
                HTTPStatus.OK,
                self.server.repository.record_broadcast_receipt(
                    broadcast_id, self._read_json()
                ),
            )
            return
        match = re.fullmatch(r"/v1/device-commands/([^/]+)/ack", parsed.path)
        if match is not None:
            command_id = unquote(match.group(1))
            self._authorize_device(self.server.repository.command_device(command_id))
            self._json(
                HTTPStatus.OK,
                self.server.repository.ack_device_command(command_id, self._read_json()),
            )
            return
        if parsed.path == "/v1/media/sessions":
            metadata = validate_metadata(self._read_json())
            self._authorize_media_upload(metadata)
            response = self.server.repository.create_or_resume(metadata)
            self._json(HTTPStatus.OK, response)
            return
        match = re.fullmatch(r"/v1/media/sessions/([^/]+)/complete", parsed.path)
        if match is None:
            raise ApiError(HTTPStatus.NOT_FOUND, "route not found")
        session_id = unquote(match.group(1))
        self._authorize_media_upload(self.server.repository.upload_session_metadata(session_id))
        response = self.server.repository.complete(session_id, self._read_json())
        self._json(HTTPStatus.OK, response)

    def _put(self) -> None:
        self._authorize()
        parsed = urlparse(self.path)
        match = re.fullmatch(r"/v1/media/sessions/([^/]+)/chunks", parsed.path)
        if match is None:
            raise ApiError(HTTPStatus.NOT_FOUND, "route not found")
        session_id = unquote(match.group(1))
        self._authorize_media_upload(self.server.repository.upload_session_metadata(session_id))
        content_range = self.headers.get("Content-Range", "")
        range_match = CONTENT_RANGE_PATTERN.fullmatch(content_range)
        if range_match is None:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid Content-Range")
        body = self._read_body(self.server.repository.chunk_size)
        response = self.server.repository.append_chunk(
            session_id=session_id,
            start=int(range_match.group(1)),
            end_inclusive=int(range_match.group(2)),
            total=int(range_match.group(3)),
            body=body,
            chunk_sha256=self.headers.get("X-Chunk-SHA256", ""),
        )
        self._json(HTTPStatus.OK, response)

    def _authorize(self) -> None:
        supplied = self.headers.get("Authorization", "")
        if self.server.auth_principals:
            if not supplied.startswith("Bearer "):
                raise ApiError(HTTPStatus.UNAUTHORIZED, "unauthorized")
            token = supplied.removeprefix("Bearer ")
            digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
            principal = next(
                (
                    value for expected, value in self.server.auth_principals.items()
                    if hmac.compare_digest(digest, expected)
                ),
                None,
            )
            if principal is None:
                raise ApiError(HTTPStatus.UNAUTHORIZED, "unauthorized")
            self.authenticated_principal = principal
            return
        expected = f"Bearer {self.server.bearer_token}"
        if not self.server.bearer_token or not hmac.compare_digest(supplied, expected):
            raise ApiError(HTTPStatus.UNAUTHORIZED, "unauthorized")

    def _voice_actor_headers(self) -> tuple[str, str]:
        principal = self.authenticated_principal
        if principal is not None:
            if principal.role not in VOICE_ROLES:
                raise ApiError(HTTPStatus.FORBIDDEN, "voice message role is not allowed")
            self._reject_principal_spoofing(principal)
            return principal.principal_id, principal.role
        actor_id = self.headers.get("X-Actor-Id", "")
        actor_role = self.headers.get("X-Actor-Role", "")
        self.server.repository._validate_voice_actor(actor_id, actor_role)
        return actor_id, actor_role

    def _alert_actor_headers(self) -> tuple[str, str]:
        principal = self.authenticated_principal
        if principal is not None:
            if principal.role not in ALERT_ROLES:
                raise ApiError(HTTPStatus.FORBIDDEN, "alert role is not allowed")
            self._reject_principal_spoofing(principal)
            return principal.principal_id, principal.role
        actor_id = self.headers.get("X-Actor-Id", "")
        actor_role = self.headers.get("X-Actor-Role", "")
        self.server.repository._validate_alert_actor(actor_id, actor_role)
        return actor_id, actor_role

    def _reject_principal_spoofing(self, principal: AuthPrincipal) -> None:
        supplied_id = self.headers.get("X-Actor-Id")
        supplied_role = self.headers.get("X-Actor-Role")
        if supplied_id is not None and supplied_id != principal.principal_id:
            raise ApiError(HTTPStatus.FORBIDDEN, "actor ID does not match authenticated principal")
        if supplied_role is not None and supplied_role != principal.role:
            raise ApiError(HTTPStatus.FORBIDDEN, "actor role does not match authenticated principal")

    def _admin_actor(self) -> tuple[str, str | None]:
        principal = self.authenticated_principal
        if principal is not None:
            if principal.role != "ADMIN":
                raise ApiError(HTTPStatus.FORBIDDEN, "administration requires ADMIN")
            self._reject_principal_spoofing(principal)
            return principal.principal_id, principal.organization_id
        actor_id = self.headers.get("X-Actor-Id", "")
        actor_role = self.headers.get("X-Actor-Role", "")
        if not ID_PATTERN.fullmatch(actor_id) or actor_role != "ADMIN":
            raise ApiError(HTTPStatus.FORBIDDEN, "administration requires ADMIN")
        return actor_id, None

    def _call_operator_actor(self, control: bool) -> tuple[str, str]:
        allowed = CALL_CONTROL_ROLES if control else CALL_ROLES
        principal = self.authenticated_principal
        if principal is not None:
            if principal.role not in allowed:
                raise ApiError(HTTPStatus.FORBIDDEN, "call access is forbidden")
            self._reject_principal_spoofing(principal)
            return principal.principal_id, principal.role
        actor_id = self.headers.get("X-Actor-Id", "")
        actor_role = self.headers.get("X-Actor-Role", "")
        if not ID_PATTERN.fullmatch(actor_id) or actor_role not in allowed:
            raise ApiError(HTTPStatus.FORBIDDEN, "call access is forbidden")
        return actor_id, actor_role

    def _authenticated_call_actor(self, call_id: str, control: bool) -> str | None:
        principal = self.authenticated_principal
        if principal is None:
            return None
        call = self.server.repository.call(call_id)
        allowed_roles = {"DEVICE"} | (CALL_CONTROL_ROLES if control else CALL_ROLES)
        self._authorize_resource_device(call["deviceId"], allowed_roles)
        if principal.role == "DEVICE":
            return call["deviceId"]
        allowed = CALL_CONTROL_ROLES if control else CALL_ROLES
        if principal.role not in allowed:
            raise ApiError(HTTPStatus.FORBIDDEN, "call access is forbidden")
        self._reject_principal_spoofing(principal)
        return principal.principal_id

    def _validate_legacy_call_control(self, actor_id: Any) -> None:
        supplied_header_id = self.headers.get("X-Actor-Id")
        supplied_header_role = self.headers.get("X-Actor-Role")
        if supplied_header_id is None and supplied_header_role is None:
            return
        if (
            not isinstance(actor_id, str)
            or supplied_header_id != actor_id
            or supplied_header_role not in CALL_CONTROL_ROLES
        ):
            raise ApiError(HTTPStatus.FORBIDDEN, "call control is forbidden")

    def _principal_device_ids(self) -> set[str] | None:
        principal = self.authenticated_principal
        if principal is None:
            return None
        if principal.organization_id is not None:
            organization_devices = {
                device_id for device_id, organization_id in self.server.device_organizations.items()
                if organization_id == principal.organization_id
            }
            if principal.device_ids:
                organization_devices.intersection_update(principal.device_ids)
            return organization_devices
        if principal.device_ids:
            return set(principal.device_ids)
        return None

    def _access_profile(self) -> dict[str, Any]:
        principal = self.authenticated_principal
        if principal is None:
            actor_id, actor_role = self._call_operator_actor(control=False)
            return {
                "mode": "DEVELOPMENT_SINGLE_TOKEN",
                "principalId": actor_id,
                "role": actor_role,
                "organizationId": None,
                "deviceScope": {"kind": "ALL", "deviceIds": []},
                "capabilities": list(ROLE_CAPABILITIES[actor_role]),
                "directory": None,
            }
        if principal.role not in ALERT_ROLES:
            raise ApiError(HTTPStatus.FORBIDDEN, "dashboard access is forbidden")

        effective_device_ids = sorted(self._principal_device_ids() or ())
        scope_kind = (
            "DEVICE_SUBSET" if principal.device_ids
            else "ORGANIZATION" if principal.organization_id is not None
            else "ALL"
        )
        response: dict[str, Any] = {
            "mode": "PRODUCTION_PRINCIPAL",
            "principalId": principal.principal_id,
            "role": principal.role,
            "organizationId": principal.organization_id,
            "deviceScope": {"kind": scope_kind, "deviceIds": effective_device_ids},
            "capabilities": list(ROLE_CAPABILITIES[principal.role]),
            "directory": None,
        }
        if principal.role == "ADMIN":
            organization_devices = sorted(
                device_id for device_id, organization_id in self.server.device_organizations.items()
                if organization_id == principal.organization_id
            )
            directory: dict[str, AuthPrincipal] = {}
            for candidate in self.server.auth_principals.values():
                if candidate.organization_id != principal.organization_id:
                    continue
                directory[candidate.principal_id] = candidate
            response["directory"] = {
                "organizationId": principal.organization_id,
                "deviceIds": organization_devices,
                "principals": [
                    {
                        "principalId": candidate.principal_id,
                        "role": candidate.role,
                        "configuredDeviceIds": sorted(candidate.device_ids),
                        "effectiveDeviceIds": sorted(
                            set(organization_devices).intersection(candidate.device_ids)
                            if candidate.device_ids else organization_devices
                        ),
                    }
                    for candidate in sorted(directory.values(), key=lambda item: item.principal_id)
                ],
            }
        return response

    def _authorize_resource_device(self, device_id: Any, allowed_roles: set[str]) -> None:
        if not isinstance(device_id, str) or not ID_PATTERN.fullmatch(device_id):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid deviceId")
        principal = self.authenticated_principal
        if principal is None:
            return
        if principal.role not in allowed_roles:
            raise ApiError(HTTPStatus.FORBIDDEN, "principal role cannot access this resource")
        allowed_device_ids = self._principal_device_ids()
        if allowed_device_ids is not None and device_id not in allowed_device_ids:
            raise ApiError(HTTPStatus.FORBIDDEN, "resource belongs to another organization or device")

    def _authorize_device(self, device_id: Any) -> None:
        self._authorize_resource_device(device_id, {"DEVICE"})

    def _authorize_operator_device(self, device_id: Any, control: bool = False) -> None:
        allowed = ALERT_CONTROL_ROLES if control else ALERT_ROLES
        self._authorize_resource_device(device_id, allowed)

    def _authorize_operator_list(self, requested_device_id: str | None) -> set[str] | None:
        principal = self.authenticated_principal
        if principal is None:
            if requested_device_id is not None and not ID_PATTERN.fullmatch(requested_device_id):
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid deviceId")
            return None
        if principal.role not in ALERT_ROLES:
            raise ApiError(HTTPStatus.FORBIDDEN, "operator access is forbidden")
        allowed_device_ids = self._principal_device_ids()
        if requested_device_id is not None:
            self._authorize_operator_device(requested_device_id)
        return allowed_device_ids

    def _authorize_device_batch(self, raw: Any) -> None:
        if self.authenticated_principal is None:
            return
        if not isinstance(raw, dict) or not isinstance(raw.get("points"), list):
            return
        for point in raw["points"]:
            if isinstance(point, dict):
                self._authorize_device(point.get("deviceId"))

    def _authorize_media_upload(self, metadata: dict[str, Any]) -> None:
        principal = self.authenticated_principal
        if principal is None:
            return
        device_id = metadata.get("deviceId")
        if metadata.get("kind") != "VOICE":
            self._authorize_device(device_id)
            return
        self._authorize_resource_device(device_id, VOICE_SENDER_ROLES)
        expected_sender = device_id if principal.role == "DEVICE" else principal.principal_id
        if metadata.get("senderId") != expected_sender:
            raise ApiError(HTTPStatus.FORBIDDEN, "voice sender does not match authenticated principal")
        if metadata.get("senderRole") != principal.role:
            raise ApiError(HTTPStatus.FORBIDDEN, "voice sender role does not match authenticated principal")

    def _read_json(self, maximum: int = MAX_METADATA_BYTES) -> Any:
        body = self._read_body(maximum)
        try:
            return loads_strict(body)
        except StrictJsonError as error:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid JSON") from error

    def _read_body(self, maximum: int) -> bytes:
        if self.headers.get_all("Transfer-Encoding", []):
            raise ApiError(HTTPStatus.BAD_REQUEST, "Transfer-Encoding is not supported")
        length_headers = self.headers.get_all("Content-Length", [])
        if not length_headers:
            raise ApiError(HTTPStatus.LENGTH_REQUIRED, "Content-Length is required")
        raw_length = length_headers[0]
        if len(length_headers) != 1 or re.fullmatch(r"[0-9]{1,20}", raw_length) is None:
            raise ApiError(HTTPStatus.BAD_REQUEST, "Content-Length is invalid or ambiguous")
        length = int(raw_length)
        if length < 0 or length > maximum:
            raise ApiError(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "request body is too large")
        body = self.rfile.read(length)
        if len(body) != length:
            raise ApiError(HTTPStatus.BAD_REQUEST, "request body is incomplete")
        return body

    def _json(self, status: int, value: Any) -> None:
        body = canonical_json(value).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format_string: str, *args: Any) -> None:
        logging.info("%s %s", self.address_string(), format_string % args)


def build_argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Helmet resumable media ingest service")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--chunk-size", type=int, default=256 * 1024)
    parser.add_argument("--production", action="store_true")
    parser.add_argument("--database-url-env", default="HELMET_DATABASE_URL")
    parser.add_argument(
        "--database-password-file-env", default="HELMET_POSTGRES_PASSWORD_FILE"
    )
    parser.add_argument("--object-store", choices=("local", "s3"), default="local")
    parser.add_argument("--s3-endpoint-env", default="HELMET_S3_ENDPOINT")
    parser.add_argument("--s3-bucket-env", default="HELMET_S3_BUCKET")
    parser.add_argument("--s3-region-env", default="HELMET_S3_REGION")
    parser.add_argument("--s3-prefix-env", default="HELMET_S3_PREFIX")
    parser.add_argument("--s3-sse-env", default="HELMET_S3_SSE")
    parser.add_argument("--s3-access-key-file-env", default="HELMET_S3_ACCESS_KEY_FILE")
    parser.add_argument("--s3-secret-key-file-env", default="HELMET_S3_SECRET_KEY_FILE")
    parser.add_argument("--token-env", default="HELMET_MEDIA_TOKEN")
    parser.add_argument("--auth-config", type=Path)
    parser.add_argument("--stun-urls-env", default="HELMET_STUN_URLS")
    parser.add_argument("--turn-urls-env", default="HELMET_TURN_URLS")
    parser.add_argument("--turn-secret-env", default="HELMET_TURN_SHARED_SECRET")
    parser.add_argument("--turn-secret-file-env", default="HELMET_TURN_SECRET_FILE")
    parser.add_argument("--map-tile-url-env", default="HELMET_MAP_TILE_URL_TEMPLATE")
    parser.add_argument("--map-attribution-env", default="HELMET_MAP_ATTRIBUTION")
    parser.add_argument("--map-min-zoom-env", default="HELMET_MAP_MIN_ZOOM")
    parser.add_argument("--map-max-zoom-env", default="HELMET_MAP_MAX_ZOOM")
    parser.add_argument("--ice-credential-ttl-seconds", type=int, default=600)
    return parser


def main() -> int:
    arguments = build_argument_parser().parse_args()
    runtime_environment = dict(os.environ)
    token = runtime_environment.get(arguments.token_env, "")
    auth_configuration = (
        load_auth_configuration(arguments.auth_config)
        if arguments.auth_config else AuthConfiguration({}, {}, 0)
    )
    auth_principals = auth_configuration.principals
    try:
        runtime_secrets = load_runtime_secrets(
            runtime_environment,
            arguments.production,
            database_url_environment_name=arguments.database_url_env,
            database_password_file_environment_name=arguments.database_password_file_env,
            s3_access_key_file_environment_name=arguments.s3_access_key_file_env,
            s3_secret_key_file_environment_name=arguments.s3_secret_key_file_env,
            turn_secret_file_environment_name=arguments.turn_secret_file_env,
            turn_secret_environment_name=arguments.turn_secret_env,
        )
    except ValueError as error:
        raise SystemExit(str(error)) from error
    database_url = runtime_secrets.database_url
    s3_endpoint = runtime_environment.get(arguments.s3_endpoint_env, "").strip()
    s3_bucket = runtime_environment.get(arguments.s3_bucket_env, "").strip()
    s3_region = runtime_environment.get(arguments.s3_region_env, "").strip()
    s3_prefix = runtime_environment.get(arguments.s3_prefix_env, "helmet-media").strip()
    s3_sse = runtime_environment.get(arguments.s3_sse_env, "AES256").strip()
    try:
        from backend.mqtt_gateway import MqttConfiguration, MqttGateway

        mqtt_configuration = MqttConfiguration.from_environment(runtime_environment)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    try:
        stun_urls = parse_ice_urls(
            runtime_environment.get(arguments.stun_urls_env, ""), {"stun", "stuns"}, "STUN URLs"
        )
        turn_urls = parse_ice_urls(
            runtime_environment.get(arguments.turn_urls_env, ""), {"turn", "turns"}, "TURN URLs"
        )
    except ValueError as error:
        raise SystemExit(str(error)) from error
    turn_shared_secret = runtime_secrets.turn_shared_secret
    map_tile_url_template = runtime_environment.get(arguments.map_tile_url_env, "").strip()
    map_attribution = runtime_environment.get(arguments.map_attribution_env, "").strip()
    try:
        map_min_zoom = int(runtime_environment.get(arguments.map_min_zoom_env, "3"))
        map_max_zoom = int(runtime_environment.get(arguments.map_max_zoom_env, "19"))
    except ValueError as error:
        raise SystemExit("map zoom environment values must be integers") from error
    if not token and not auth_principals:
        raise SystemExit(f"{arguments.token_env} must contain a non-empty bearer token")
    if database_url:
        database_scheme = urlparse(database_url).scheme
        if database_scheme not in {"postgres", "postgresql"}:
            raise SystemExit(f"{arguments.database_url_env} must be a PostgreSQL URL")
    if arguments.production:
        try:
            validate_production_configuration(
                auth_configuration,
                token,
                database_url,
                arguments.object_store,
                s3_endpoint,
                s3_bucket,
                s3_region,
                runtime_secrets.s3_access_key_id,
                runtime_secrets.s3_secret_access_key,
                stun_urls,
                turn_urls,
                turn_shared_secret,
                map_tile_url_template,
                map_attribution,
                mqtt_configuration is not None,
            )
        except ValueError as error:
            raise SystemExit(str(error)) from error

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    try:
        object_store = (
            S3ObjectStore(
                s3_endpoint,
                s3_bucket,
                s3_region,
                s3_prefix,
                s3_sse,
                access_key_id=runtime_secrets.s3_access_key_id,
                secret_access_key=runtime_secrets.s3_secret_access_key,
            )
            if arguments.object_store == "s3"
            else LocalObjectStore(arguments.data_dir / "objects")
        )
        repository = MediaRepository(
            arguments.data_dir,
            arguments.chunk_size,
            database_url=database_url,
            object_store=object_store,
        )
        repository.check_ready()
    except Exception as error:
        raise SystemExit("backend persistence dependencies are not ready") from error
    mqtt_gateway = None
    server = None
    try:
        if mqtt_configuration is not None:
            mqtt_device_ids = set(auth_configuration.device_organizations)
            if not mqtt_device_ids:
                mqtt_device_ids = {
                    device_id
                    for principal in auth_principals.values()
                    if principal.role == "DEVICE"
                    for device_id in principal.device_ids
                }
            mqtt_gateway = MqttGateway(repository, mqtt_configuration, mqtt_device_ids)
            mqtt_gateway.start()
        server = MediaHttpServer(
            (arguments.host, arguments.port),
            repository,
            token,
            stun_urls,
            turn_urls,
            turn_shared_secret,
            arguments.ice_credential_ttl_seconds,
            auth_principals=auth_principals,
            device_organizations=auth_configuration.device_organizations,
            map_tile_url_template=map_tile_url_template,
            map_attribution=map_attribution,
            map_min_zoom=map_min_zoom,
            map_max_zoom=map_max_zoom,
            readiness_checks=(mqtt_gateway.check_ready,) if mqtt_gateway is not None else (),
        )
    except Exception as error:
        if mqtt_gateway is not None:
            mqtt_gateway.stop()
        raise SystemExit("backend messaging dependencies are not ready") from error
    if token and not auth_principals:
        logging.warning("development single-token authorization is enabled")
    logging.info("media service listening on %s:%s", *server.server_address)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        if mqtt_gateway is not None:
            mqtt_gateway.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
