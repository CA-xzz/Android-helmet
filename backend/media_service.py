#!/usr/bin/env python3
"""Minimal local companion service for the Android smart-helmet app."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import logging
import math
import os
from pathlib import Path
import re
import sqlite3
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, unquote, urlparse
import uuid

try:
    from .json_codec import StrictJsonError, loads_strict
except ImportError:  # Direct script execution from the repository root.
    from json_codec import StrictJsonError, loads_strict


ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
LANGUAGE_TAG_PATTERN = re.compile(r"^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
CONTENT_RANGE_PATTERN = re.compile(r"^bytes (\d+)-(\d+)/(\d+)$")
CALL_STATES = {
    "REQUESTED", "RINGING", "ACCEPTED", "CONNECTING", "CONNECTED",
    "REJECTED", "ENDED", "FAILED",
}
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
TERMINAL_CALL_STATES = {"REJECTED", "ENDED", "FAILED"}
CALL_SIGNAL_TYPES = {"OFFER", "ANSWER", "ICE_CANDIDATE", "ICE_COMPLETE"}
TRACK_SOURCES = {"ANDROID_GNSS", "ANDROID_FUSED", "EXTERNAL_NMEA", "CELL_ASSISTED", "REPLAY"}
TRACK_QUALITIES = {"UNVALIDATED", "STANDARD", "DIFFERENTIAL", "RTK_FLOAT", "RTK_FIXED", "DEAD_RECKONING"}
MEDIA_LOCATION_FIX_TYPES = TRACK_QUALITIES | {"NO_FIX"}
MEDIA_KINDS = {"PHOTO", "VIDEO", "VOICE"}
VOICE_MIME_TYPES = {"audio/mp4", "audio/aac", "audio/ogg", "audio/webm", "audio/wav"}
VOICE_SENDER_ROLES = {"DEVICE", "DISPATCHER", "SUPERVISOR", "ADMIN"}
VOICE_ALLOWED_ROLES = {"DISPATCHER", "SUPERVISOR", "ADMIN"}
MAX_JSON_BYTES = 1024 * 1024
MAX_MEDIA_BYTES = 16 * 1024 * 1024 * 1024
MAX_MEDIA_DIMENSION = 32_768
MAX_MEDIA_DURATION_MILLIS = 24 * 60 * 60 * 1000


class ApiError(Exception):
    def __init__(self, status: HTTPStatus, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


def _now() -> int:
    return int(time.time() * 1000)


def _canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def _require_object(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ApiError(HTTPStatus.BAD_REQUEST, "request body must be a JSON object")
    return value


def _require_id(value: Any, name: str) -> str:
    if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
        raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    return value


def _require_positive_int(value: Any, name: str, *, allow_zero: bool = False) -> int:
    minimum = 0 if allow_zero else 1
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    return value


def _require_finite_number(
    value: Any,
    name: str,
    *,
    minimum: float | None = None,
    maximum: float | None = None,
) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    number = float(value)
    if minimum is not None and number < minimum:
        raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    if maximum is not None and number > maximum:
        raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}")
    return number


def _validate_track_point(point: dict[str, Any]) -> tuple[str, str, int]:
    message_id = _require_id(point.get("messageId"), "messageId")
    device_id = _require_id(point.get("deviceId"), "deviceId")
    sequence = _require_positive_int(point.get("sequence"), "sequence")
    _require_id(point.get("fixId"), "fixId")
    _require_positive_int(point.get("occurredAtEpochMillis"), "occurredAtEpochMillis")
    if point.get("source") not in TRACK_SOURCES:
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid source")
    if point.get("quality") not in TRACK_QUALITIES:
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid quality")
    _require_finite_number(point.get("latitude"), "latitude", minimum=-90.0, maximum=90.0)
    _require_finite_number(point.get("longitude"), "longitude", minimum=-180.0, maximum=180.0)
    if point.get("isMock") is not False:
        raise ApiError(HTTPStatus.BAD_REQUEST, "mock track points are not accepted")

    non_negative_numbers = (
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
    for field in non_negative_numbers:
        if point.get(field) is not None:
            _require_finite_number(point[field], field, minimum=0.0)
    if point.get("bearingDegrees") is not None:
        _require_finite_number(point["bearingDegrees"], "bearingDegrees", minimum=0.0, maximum=360.0)
    if point.get("altitudeMeters") is not None:
        _require_finite_number(point["altitudeMeters"], "altitudeMeters")
    if point.get("elapsedRealtimeNanos") is not None:
        _require_positive_int(point["elapsedRealtimeNanos"], "elapsedRealtimeNanos", allow_zero=True)
    satellites_used = point.get("satellitesUsed")
    satellites_visible = point.get("satellitesVisible")
    if satellites_used is not None:
        satellites_used = _require_positive_int(satellites_used, "satellitesUsed", allow_zero=True)
    if satellites_visible is not None:
        satellites_visible = _require_positive_int(satellites_visible, "satellitesVisible", allow_zero=True)
    if satellites_used is not None and satellites_visible is not None and satellites_used > satellites_visible:
        raise ApiError(HTTPStatus.BAD_REQUEST, "satellitesUsed exceeds satellitesVisible")
    return message_id, device_id, sequence


def _validate_media_metadata(value: dict[str, Any]) -> tuple[str, str, int, str]:
    media_id = _require_id(value.get("mediaId"), "mediaId")
    device_id = _require_id(value.get("deviceId"), "deviceId")
    byte_size = _require_positive_int(value.get("byteSize"), "byteSize")
    if byte_size > MAX_MEDIA_BYTES:
        raise ApiError(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "media is too large")
    sha256 = value.get("sha256")
    if not isinstance(sha256, str) or not SHA256_PATTERN.fullmatch(sha256):
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid sha256")
    _require_positive_int(value.get("createdAtEpochMillis"), "createdAtEpochMillis")
    related_event_id = value.get("relatedEventId")
    if related_event_id is not None:
        _require_id(related_event_id, "relatedEventId")

    kind = value.get("kind")
    if kind not in MEDIA_KINDS:
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid media kind")
    mime_type = value.get("mimeType")
    if kind == "VOICE":
        if mime_type not in VOICE_MIME_TYPES:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid voice mimeType")
        _require_positive_int(value.get("durationMillis"), "durationMillis")
        _require_id(value.get("senderId"), "senderId")
        if value.get("senderRole") not in VOICE_SENDER_ROLES:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid senderRole")
        allowed_roles = value.get("allowedRoles")
        if (
            not isinstance(allowed_roles, list)
            or not allowed_roles
            or any(not isinstance(role, str) or role not in VOICE_ALLOWED_ROLES for role in allowed_roles)
            or len(allowed_roles) != len(set(allowed_roles))
        ):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid allowedRoles")
        if value.get("callId") is not None:
            _require_id(value["callId"], "callId")
        return media_id, device_id, byte_size, sha256

    expected_mime_type = "image/jpeg" if kind == "PHOTO" else "video/mp4"
    if mime_type != expected_mime_type:
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid visual media mimeType")
    width = _require_positive_int(value.get("width"), "width")
    height = _require_positive_int(value.get("height"), "height")
    if width > MAX_MEDIA_DIMENSION or height > MAX_MEDIA_DIMENSION:
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid visual media dimensions")
    duration = value.get("durationMillis")
    if kind == "PHOTO":
        if duration is not None:
            raise ApiError(HTTPStatus.BAD_REQUEST, "photo durationMillis must be null")
    else:
        duration = _require_positive_int(duration, "durationMillis")
        if duration > MAX_MEDIA_DURATION_MILLIS:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid durationMillis")
    if value.get("personId") is not None:
        _require_id(value["personId"], "personId")
    location = _require_object(value.get("location"))
    fix_type = location.get("fixType")
    if fix_type not in MEDIA_LOCATION_FIX_TYPES:
        raise ApiError(HTTPStatus.BAD_REQUEST, "invalid media location fixType")
    latitude = location.get("latitude")
    longitude = location.get("longitude")
    if (latitude is None) != (longitude is None):
        raise ApiError(HTTPStatus.BAD_REQUEST, "incomplete media coordinates")
    if latitude is not None:
        _require_finite_number(latitude, "latitude", minimum=-90.0, maximum=90.0)
        _require_finite_number(longitude, "longitude", minimum=-180.0, maximum=180.0)
    if fix_type == "NO_FIX" and latitude is not None:
        raise ApiError(HTTPStatus.BAD_REQUEST, "NO_FIX media must not contain coordinates")
    if fix_type != "NO_FIX" and latitude is None:
        raise ApiError(HTTPStatus.BAD_REQUEST, "positioned media requires coordinates")
    accuracy = location.get("horizontalAccuracyMeters")
    if accuracy is not None:
        _require_finite_number(accuracy, "horizontalAccuracyMeters", minimum=0.0)
    return media_id, device_id, byte_size, sha256


class MediaRepository:
    """Small SQLite store for App integration and one WebRTC viewer."""

    def __init__(
        self,
        root: Path,
        chunk_size: int = 256 * 1024,
        required_app_version: str | None = None,
    ) -> None:
        if not 64 * 1024 <= chunk_size <= 4 * 1024 * 1024:
            raise ValueError("chunk size must be between 64 KiB and 4 MiB")
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)
        self.upload_root = self.root / "uploads"
        self.object_root = self.root / "objects"
        self.upload_root.mkdir(exist_ok=True)
        self.object_root.mkdir(exist_ok=True)
        self.database_path = self.root / "helmet.sqlite3"
        self.chunk_size = chunk_size
        self.required_app_version = required_app_version
        self._lock = threading.RLock()
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        database = sqlite3.connect(self.database_path, timeout=30)
        database.row_factory = sqlite3.Row
        database.execute("PRAGMA foreign_keys = ON")
        return database

    def _initialize(self) -> None:
        schema = """
        CREATE TABLE IF NOT EXISTS metadata (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS device_status (
            message_id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            received_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS tracks (
            message_id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            sequence INTEGER NOT NULL,
            payload_json TEXT NOT NULL,
            received_at INTEGER NOT NULL,
            UNIQUE(device_id, sequence)
        );
        CREATE TABLE IF NOT EXISTS alerts (
            message_id TEXT PRIMARY KEY,
            alert_id TEXT NOT NULL,
            device_id TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            received_at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS alerts_by_alert_id ON alerts(alert_id, received_at);
        CREATE TABLE IF NOT EXISTS calls (
            call_id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            direction TEXT NOT NULL,
            media_mode TEXT NOT NULL,
            state TEXT NOT NULL,
            state_sequence INTEGER NOT NULL,
            related_event_id TEXT,
            simulated INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            last_reason TEXT
        );
        CREATE TABLE IF NOT EXISTS call_history (
            call_id TEXT NOT NULL,
            state_sequence INTEGER NOT NULL,
            state TEXT NOT NULL,
            actor_id TEXT NOT NULL,
            occurred_at INTEGER NOT NULL,
            reason TEXT,
            PRIMARY KEY(call_id, state_sequence),
            FOREIGN KEY(call_id) REFERENCES calls(call_id)
        );
        CREATE TABLE IF NOT EXISTS call_signals (
            signal_id TEXT PRIMARY KEY,
            call_id TEXT NOT NULL,
            sequence INTEGER NOT NULL,
            sender_id TEXT NOT NULL,
            signal_type TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            UNIQUE(call_id, sequence),
            FOREIGN KEY(call_id) REFERENCES calls(call_id)
        );
        CREATE TABLE IF NOT EXISTS commands (
            command_id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            sequence INTEGER NOT NULL,
            command_type TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            ack_json TEXT,
            UNIQUE(device_id, sequence)
        );
        CREATE TABLE IF NOT EXISTS broadcasts (
            broadcast_id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS broadcast_receipts (
            broadcast_id TEXT NOT NULL,
            state TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            PRIMARY KEY(broadcast_id, state),
            FOREIGN KEY(broadcast_id) REFERENCES broadcasts(broadcast_id)
        );
        CREATE TABLE IF NOT EXISTS upload_sessions (
            session_id TEXT PRIMARY KEY,
            media_id TEXT NOT NULL UNIQUE,
            device_id TEXT NOT NULL,
            expected_size INTEGER NOT NULL,
            expected_sha256 TEXT NOT NULL,
            metadata_json TEXT NOT NULL,
            next_offset INTEGER NOT NULL,
            status TEXT NOT NULL,
            archive_id TEXT,
            temp_path TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE IF NOT EXISTS media (
            media_id TEXT PRIMARY KEY,
            archive_id TEXT NOT NULL UNIQUE,
            device_id TEXT NOT NULL,
            sha256 TEXT NOT NULL,
            byte_size INTEGER NOT NULL,
            object_path TEXT NOT NULL,
            metadata_json TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );
        """
        with self._connect() as database:
            database.executescript(schema)
            alert_schema = database.execute(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'alerts'"
            ).fetchone()["sql"]
            if "alert_id TEXT NOT NULL UNIQUE" in alert_schema:
                database.executescript(
                    """
                    DROP INDEX IF EXISTS alerts_by_alert_id;
                    ALTER TABLE alerts RENAME TO alerts_with_unique_id;
                    CREATE TABLE alerts (
                        message_id TEXT PRIMARY KEY,
                        alert_id TEXT NOT NULL,
                        device_id TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        received_at INTEGER NOT NULL
                    );
                    INSERT INTO alerts SELECT * FROM alerts_with_unique_id;
                    DROP TABLE alerts_with_unique_id;
                    CREATE INDEX alerts_by_alert_id ON alerts(alert_id, received_at);
                    """
                )
            row = database.execute(
                "SELECT value FROM metadata WHERE key = 'command_stream_id'"
            ).fetchone()
            if row is None:
                database.execute(
                    "INSERT INTO metadata(key, value) VALUES('command_stream_id', ?)",
                    (str(uuid.uuid4()),),
                )

    def command_stream_id(self) -> str:
        with self._connect() as database:
            return str(database.execute(
                "SELECT value FROM metadata WHERE key = 'command_stream_id'"
            ).fetchone()["value"])

    def save_status(self, raw: Any) -> dict[str, Any]:
        value = _require_object(raw)
        message_id = _require_id(value.get("messageId"), "messageId")
        device_id = _require_id(value.get("deviceId"), "deviceId")
        payload = _canonical(value)
        received = _now()
        with self._lock, self._connect() as database:
            existing = database.execute(
                "SELECT payload_json, received_at FROM device_status WHERE message_id = ?",
                (message_id,),
            ).fetchone()
            if existing is not None:
                if existing["payload_json"] != payload:
                    raise ApiError(HTTPStatus.CONFLICT, "messageId already has different content")
                deduplicated = True
                received = int(existing["received_at"])
            else:
                database.execute(
                    "INSERT INTO device_status VALUES (?, ?, ?, ?)",
                    (message_id, device_id, payload, received),
                )
                deduplicated = False
        response: dict[str, Any] = {
            "messageId": message_id,
            "deduplicated": deduplicated,
            "serverReceivedAtEpochMillis": received,
        }
        if self.required_app_version:
            reported = value.get("appVersion")
            response["firmware"] = {
                "reportedVersion": reported,
                "requiredVersion": self.required_app_version,
                "updateRequired": reported != self.required_app_version,
            }
        return response

    def save_tracks(self, raw: Any) -> dict[str, Any]:
        points = _require_object(raw).get("points")
        if not isinstance(points, list) or not 1 <= len(points) <= 200:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid track batch")
        accepted: list[str] = []
        duplicates: list[str] = []
        with self._lock, self._connect() as database:
            for item in points:
                point = _require_object(item)
                message_id, device_id, sequence = _validate_track_point(point)
                payload = _canonical(point)
                existing = database.execute(
                    "SELECT payload_json FROM tracks WHERE message_id = ?", (message_id,)
                ).fetchone()
                if existing is not None:
                    if existing["payload_json"] != payload:
                        raise ApiError(HTTPStatus.CONFLICT, "track messageId conflict")
                    duplicates.append(message_id)
                    continue
                try:
                    database.execute(
                        "INSERT INTO tracks VALUES (?, ?, ?, ?, ?)",
                        (message_id, device_id, sequence, payload, _now()),
                    )
                except sqlite3.IntegrityError as error:
                    raise ApiError(HTTPStatus.CONFLICT, "track sequence conflict") from error
                accepted.append(message_id)
        return {"acceptedMessageIds": accepted, "duplicateMessageIds": duplicates}

    def save_alert(self, raw: Any) -> dict[str, Any]:
        value = _require_object(raw)
        message_id = _require_id(value.get("messageId"), "messageId")
        alert_id = _require_id(value.get("alertId"), "alertId")
        device_id = _require_id(value.get("deviceId"), "deviceId")
        payload = _canonical(value)
        with self._lock, self._connect() as database:
            existing = database.execute(
                "SELECT alert_id, payload_json FROM alerts WHERE message_id = ?", (message_id,)
            ).fetchone()
            if existing is not None:
                if existing["alert_id"] != alert_id or existing["payload_json"] != payload:
                    raise ApiError(HTTPStatus.CONFLICT, "alert messageId conflict")
                return {"alertId": alert_id, "deduplicated": True}
            database.execute(
                "INSERT INTO alerts VALUES (?, ?, ?, ?, ?)",
                (message_id, alert_id, device_id, payload, _now()),
            )
        return {"alertId": alert_id, "deduplicated": False}

    def track_page(self, device_id: str, after_sequence: int, limit: int) -> dict[str, Any]:
        with self._connect() as database:
            rows = database.execute(
                """
                SELECT payload_json FROM tracks
                WHERE device_id = ? AND sequence > ?
                ORDER BY sequence
                LIMIT ?
                """,
                (device_id, after_sequence, limit),
            ).fetchall()
        return {"points": [json.loads(row["payload_json"]) for row in rows]}

    def alert(self, alert_id: str) -> dict[str, Any]:
        with self._connect() as database:
            rows = database.execute(
                """
                SELECT payload_json FROM alerts
                WHERE alert_id = ?
                ORDER BY received_at, rowid
                """,
                (alert_id,),
            ).fetchall()
        if not rows:
            raise ApiError(HTTPStatus.NOT_FOUND, "alert not found")
        payloads = [json.loads(row["payload_json"]) for row in rows]
        response = dict(payloads[-1])
        response["events"] = [
            {"kind": "ACTIVATED" if payload.get("active") else "CLEARED"}
            for payload in payloads
        ]
        return response

    @staticmethod
    def _call_response(row: sqlite3.Row, **extra: Any) -> dict[str, Any]:
        response = {
            "callId": row["call_id"],
            "deviceId": row["device_id"],
            "direction": row["direction"],
            "mediaMode": row["media_mode"],
            "state": row["state"],
            "stateSequence": row["state_sequence"],
            "relatedEventId": row["related_event_id"],
            "simulated": bool(row["simulated"]),
            "createdAtEpochMillis": row["created_at"],
            "updatedAtEpochMillis": row["updated_at"],
            "lastReason": row["last_reason"],
        }
        response.update(extra)
        return response

    def create_call(self, raw: Any) -> dict[str, Any]:
        value = _require_object(raw)
        call_id = _require_id(value.get("callId"), "callId")
        device_id = _require_id(value.get("deviceId"), "deviceId")
        if value.get("direction") not in {"OUTGOING_DEVICE", "INCOMING_BACKEND"}:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid direction")
        if value.get("mediaMode") not in {"AUDIO", "VIDEO_UPLINK"}:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid mediaMode")
        if value.get("state") != "REQUESTED" or value.get("stateSequence") != 1:
            raise ApiError(HTTPStatus.BAD_REQUEST, "new call must start at REQUESTED sequence 1")
        created = _require_positive_int(value.get("createdAtEpochMillis"), "createdAtEpochMillis")
        related = value.get("relatedEventId")
        if related is not None:
            related = _require_id(related, "relatedEventId")
        simulated = value.get("simulated")
        if not isinstance(simulated, bool):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid simulated")
        identity = (device_id, value["direction"], value["mediaMode"], related, int(simulated), created)
        with self._lock, self._connect() as database:
            row = database.execute("SELECT * FROM calls WHERE call_id = ?", (call_id,)).fetchone()
            if row is None:
                active = database.execute(
                    "SELECT call_id FROM calls WHERE device_id = ? "
                    "AND state NOT IN ('REJECTED', 'ENDED', 'FAILED') LIMIT 1",
                    (device_id,),
                ).fetchone()
                if active is not None:
                    raise ApiError(HTTPStatus.CONFLICT, "device already has an active call")
                database.execute(
                    "INSERT INTO calls VALUES (?, ?, ?, ?, 'REQUESTED', 1, ?, ?, ?, ?, NULL)",
                    (call_id, *identity[:3], identity[3], identity[4], identity[5], identity[5]),
                )
                database.execute(
                    "INSERT INTO call_history VALUES (?, 1, 'REQUESTED', ?, ?, NULL)",
                    (call_id, device_id, created),
                )
                row = database.execute("SELECT * FROM calls WHERE call_id = ?", (call_id,)).fetchone()
                deduplicated = False
            else:
                stored = (
                    row["device_id"], row["direction"], row["media_mode"],
                    row["related_event_id"], row["simulated"], row["created_at"],
                )
                if stored != identity:
                    raise ApiError(HTTPStatus.CONFLICT, "callId already has different content")
                deduplicated = True
        return self._call_response(
            row,
            acknowledgedState="REQUESTED",
            acknowledgedStateSequence=1,
            deduplicated=deduplicated,
        )

    def call(self, call_id: str) -> dict[str, Any]:
        with self._connect() as database:
            row = database.execute("SELECT * FROM calls WHERE call_id = ?", (call_id,)).fetchone()
        if row is None:
            raise ApiError(HTTPStatus.NOT_FOUND, "call not found")
        return self._call_response(row)

    def calls(self, device_id: str | None, limit: int) -> dict[str, Any]:
        if device_id is not None:
            _require_id(device_id, "deviceId")
        if not 1 <= limit <= 100:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid limit")
        with self._connect() as database:
            if device_id is None:
                rows = database.execute(
                    "SELECT * FROM calls ORDER BY updated_at DESC LIMIT ?", (limit,)
                ).fetchall()
            else:
                rows = database.execute(
                    "SELECT * FROM calls WHERE device_id = ? ORDER BY updated_at DESC LIMIT ?",
                    (device_id, limit),
                ).fetchall()
        return {"calls": [self._call_response(row) for row in rows]}

    def _enqueue_command(
        self,
        database: sqlite3.Connection,
        device_id: str,
        command_type: str,
        payload: dict[str, Any],
        created_at: int,
    ) -> dict[str, Any]:
        sequence = int(database.execute(
            "SELECT COALESCE(MAX(sequence), 0) + 1 FROM commands WHERE device_id = ?",
            (device_id,),
        ).fetchone()[0])
        command_id = str(uuid.uuid4())
        database.execute(
            "INSERT INTO commands VALUES (?, ?, ?, ?, ?, ?, NULL)",
            (command_id, device_id, sequence, command_type, _canonical(payload), created_at),
        )
        return {"commandId": command_id, "sequence": sequence}

    def transition_call(self, call_id: str, raw: Any) -> dict[str, Any]:
        value = _require_object(raw)
        target = value.get("state")
        if target not in CALL_STATES:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid call state")
        actor_id = _require_id(value.get("actorId"), "actorId")
        occurred = _require_positive_int(value.get("occurredAtEpochMillis"), "occurredAtEpochMillis")
        reason = value.get("reason")
        if reason is not None and (not isinstance(reason, str) or len(reason) > 256):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid reason")
        with self._lock, self._connect() as database:
            current = database.execute("SELECT * FROM calls WHERE call_id = ?", (call_id,)).fetchone()
            if current is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "call not found")
            historical = database.execute(
                "SELECT state_sequence FROM call_history WHERE call_id = ? AND state = ? "
                "AND actor_id = ? AND occurred_at = ? AND reason IS ?",
                (call_id, target, actor_id, occurred, reason),
            ).fetchone()
            if historical is not None:
                return self._call_response(
                    current,
                    acknowledgedState=target,
                    acknowledgedStateSequence=historical["state_sequence"],
                    deduplicated=True,
                )
            if target == current["state"]:
                raise ApiError(HTTPStatus.CONFLICT, "call transition conflicts with current state")
            if target not in CALL_TRANSITIONS[current["state"]]:
                raise ApiError(HTTPStatus.CONFLICT, "invalid call state transition")
            sequence = int(current["state_sequence"]) + 1
            database.execute(
                "UPDATE calls SET state = ?, state_sequence = ?, updated_at = ?, last_reason = ? "
                "WHERE call_id = ?",
                (target, sequence, occurred, reason, call_id),
            )
            database.execute(
                "INSERT INTO call_history VALUES (?, ?, ?, ?, ?, ?)",
                (call_id, sequence, target, actor_id, occurred, reason),
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
                        "occurredAtEpochMillis": occurred,
                        "reason": reason,
                    },
                    occurred,
                )
            updated = database.execute("SELECT * FROM calls WHERE call_id = ?", (call_id,)).fetchone()
        return self._call_response(
            updated,
            acknowledgedState=target,
            acknowledgedStateSequence=sequence,
            deduplicated=False,
        )

    def save_signal(self, call_id: str, raw: Any) -> dict[str, Any]:
        value = _require_object(raw)
        signal_id = _require_id(value.get("signalId"), "signalId")
        sender_id = _require_id(value.get("senderId"), "senderId")
        signal_type = value.get("type")
        if signal_type not in CALL_SIGNAL_TYPES:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid signal type")
        payload = _require_object(value.get("payload"))
        created = _require_positive_int(value.get("createdAtEpochMillis"), "createdAtEpochMillis")
        identity = (call_id, sender_id, signal_type, _canonical(payload), created)
        with self._lock, self._connect() as database:
            call = database.execute("SELECT * FROM calls WHERE call_id = ?", (call_id,)).fetchone()
            if call is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "call not found")
            if call["state"] in TERMINAL_CALL_STATES:
                raise ApiError(HTTPStatus.CONFLICT, "terminal call does not accept signals")
            existing = database.execute(
                "SELECT * FROM call_signals WHERE signal_id = ?", (signal_id,)
            ).fetchone()
            if existing is not None:
                stored = (
                    existing["call_id"], existing["sender_id"], existing["signal_type"],
                    existing["payload_json"], existing["created_at"],
                )
                if stored != identity:
                    raise ApiError(HTTPStatus.CONFLICT, "signalId already has different content")
                return self._signal_response(existing)
            latest_offer = database.execute(
                "SELECT sequence FROM call_signals WHERE call_id = ? AND signal_type = 'OFFER' "
                "ORDER BY sequence DESC LIMIT 1", (call_id,),
            ).fetchone()
            latest_answer = database.execute(
                "SELECT sequence, sender_id FROM call_signals WHERE call_id = ? AND signal_type = 'ANSWER' "
                "ORDER BY sequence DESC LIMIT 1", (call_id,),
            ).fetchone()
            if signal_type == "OFFER":
                if sender_id != call["device_id"] or not isinstance(payload.get("sdp"), str):
                    raise ApiError(HTTPStatus.CONFLICT, "OFFER must come from the device")
            else:
                if latest_offer is None or payload.get("offerSequence") != latest_offer["sequence"]:
                    raise ApiError(HTTPStatus.CONFLICT, "signal does not match the latest OFFER")
                if signal_type == "ANSWER":
                    if sender_id == call["device_id"] or not isinstance(payload.get("sdp"), str):
                        raise ApiError(HTTPStatus.CONFLICT, "ANSWER must come from the viewer")
                elif sender_id != call["device_id"]:
                    if latest_answer is None or latest_answer["sequence"] <= latest_offer["sequence"]:
                        raise ApiError(HTTPStatus.CONFLICT, "viewer ICE requires an ANSWER")
                    if sender_id != latest_answer["sender_id"]:
                        raise ApiError(HTTPStatus.CONFLICT, "viewer ICE sender mismatch")
            sequence = int(database.execute(
                "SELECT COALESCE(MAX(sequence), 0) + 1 FROM call_signals WHERE call_id = ?",
                (call_id,),
            ).fetchone()[0])
            database.execute(
                "INSERT INTO call_signals VALUES (?, ?, ?, ?, ?, ?, ?)",
                (signal_id, call_id, sequence, sender_id, signal_type, identity[3], created),
            )
            row = database.execute(
                "SELECT * FROM call_signals WHERE signal_id = ?", (signal_id,)
            ).fetchone()
        return self._signal_response(row)

    @staticmethod
    def _signal_response(row: sqlite3.Row) -> dict[str, Any]:
        return {
            "signalId": row["signal_id"],
            "callId": row["call_id"],
            "sequence": row["sequence"],
            "senderId": row["sender_id"],
            "type": row["signal_type"],
            "payload": json.loads(row["payload_json"]),
            "createdAtEpochMillis": row["created_at"],
        }

    def signals(self, call_id: str, after_sequence: int, limit: int) -> dict[str, Any]:
        if not 0 <= after_sequence or not 1 <= limit <= 100:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid signal cursor")
        self.call(call_id)
        with self._connect() as database:
            rows = database.execute(
                "SELECT * FROM call_signals WHERE call_id = ? AND sequence > ? "
                "ORDER BY sequence LIMIT ?", (call_id, after_sequence, limit),
            ).fetchall()
        return {"signals": [self._signal_response(row) for row in rows]}

    def command_page(self, device_id: str, after_sequence: int, limit: int) -> dict[str, Any]:
        _require_id(device_id, "deviceId")
        if after_sequence < 0 or not 1 <= limit <= 100:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid command cursor")
        with self._connect() as database:
            high_water = int(database.execute(
                "SELECT COALESCE(MAX(sequence), 0) FROM commands WHERE device_id = ?",
                (device_id,),
            ).fetchone()[0])
            rows = database.execute(
                "SELECT * FROM commands WHERE device_id = ? AND sequence > ? "
                "ORDER BY sequence LIMIT ?", (device_id, after_sequence, limit),
            ).fetchall()
        commands = [{
            "commandId": row["command_id"],
            "deviceId": row["device_id"],
            "sequence": row["sequence"],
            "type": row["command_type"],
            "payload": json.loads(row["payload_json"]),
            "createdAtEpochMillis": row["created_at"],
            "acknowledged": row["ack_json"] is not None,
        } for row in rows]
        returned_cursor = commands[-1]["sequence"] if commands else after_sequence
        has_more = returned_cursor < high_water
        return {
            "commandStreamId": self.command_stream_id(),
            "commandHighWaterSequence": high_water,
            "commands": commands,
            "hasMore": has_more,
            "nextAfterSequence": returned_cursor if has_more else None,
        }

    def acknowledge_command(self, command_id: str, raw: Any) -> dict[str, Any]:
        value = _require_object(raw)
        if value.get("commandStreamId") != self.command_stream_id():
            raise ApiError(HTTPStatus.CONFLICT, "command stream mismatch")
        payload = _canonical(value)
        with self._lock, self._connect() as database:
            row = database.execute(
                "SELECT ack_json FROM commands WHERE command_id = ?", (command_id,)
            ).fetchone()
            if row is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "command not found")
            if row["ack_json"] is not None and row["ack_json"] != payload:
                raise ApiError(HTTPStatus.CONFLICT, "command acknowledgement conflict")
            database.execute(
                "UPDATE commands SET ack_json = ? WHERE command_id = ?", (payload, command_id)
            )
        return {"commandId": command_id, "acknowledged": True}

    def create_broadcast(self, raw: Any) -> dict[str, Any]:
        value = _require_object(raw)
        broadcast_id = _require_id(value.get("broadcastId"), "broadcastId")
        device_id = _require_id(value.get("deviceId"), "deviceId")
        if not isinstance(value.get("text"), str) or not value["text"].strip():
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid broadcast text")
        if len(value["text"]) > 2_000:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid broadcast text")
        value.setdefault("language", "zh-CN")
        value.setdefault("priority", 0)
        value.setdefault("expiresAtEpochMillis", None)
        value.setdefault("createdAtEpochMillis", _now())
        if not isinstance(value["language"], str) or not LANGUAGE_TAG_PATTERN.fullmatch(value["language"]):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid broadcast language")
        priority = value["priority"]
        if isinstance(priority, bool) or not isinstance(priority, int) or not 0 <= priority <= 10:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid broadcast priority")
        created_at = _require_positive_int(value["createdAtEpochMillis"], "createdAtEpochMillis")
        expires_at = value["expiresAtEpochMillis"]
        if expires_at is not None:
            _require_positive_int(expires_at, "expiresAtEpochMillis")
            if expires_at <= created_at:
                raise ApiError(HTTPStatus.BAD_REQUEST, "broadcast expiry must follow creation")
        payload = _canonical(value)
        with self._lock, self._connect() as database:
            existing = database.execute(
                "SELECT payload_json FROM broadcasts WHERE broadcast_id = ?", (broadcast_id,)
            ).fetchone()
            if existing is not None:
                if existing["payload_json"] != payload:
                    raise ApiError(HTTPStatus.CONFLICT, "broadcastId conflict")
                command = database.execute(
                    "SELECT command_id, sequence FROM commands "
                    "WHERE device_id = ? AND command_type = 'TEXT_BROADCAST' AND payload_json = ? "
                    "ORDER BY sequence LIMIT 1",
                    (device_id, payload),
                ).fetchone()
                if command is None:
                    raise ApiError(HTTPStatus.CONFLICT, "broadcast command is missing")
                return {
                    "broadcastId": broadcast_id,
                    "commandId": command["command_id"],
                    "commandSequence": command["sequence"],
                    "deduplicated": True,
                }
            database.execute(
                "INSERT INTO broadcasts VALUES (?, ?, ?, ?)",
                (broadcast_id, device_id, payload, value["createdAtEpochMillis"]),
            )
            command = self._enqueue_command(
                database, device_id, "TEXT_BROADCAST", value, value["createdAtEpochMillis"]
            )
        return {
            "broadcastId": broadcast_id,
            "commandId": command["commandId"],
            "commandSequence": command["sequence"],
            "deduplicated": False,
        }

    def save_broadcast_receipt(self, broadcast_id: str, raw: Any) -> dict[str, Any]:
        value = _require_object(raw)
        if value.get("commandStreamId") != self.command_stream_id():
            raise ApiError(HTTPStatus.CONFLICT, "command stream mismatch")
        state = value.get("state")
        if state not in {"RECEIVED", "PLAYING", "PLAYED", "FAILED", "EXPIRED"}:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid broadcast state")
        occurred_at = _require_positive_int(value.get("occurredAtEpochMillis"), "occurredAtEpochMillis")
        error = value.get("error")
        if state == "FAILED":
            if not isinstance(error, str) or not error.strip() or len(error) > 1_024:
                raise ApiError(HTTPStatus.BAD_REQUEST, "failed broadcast receipt requires an error")
        elif error is not None:
            raise ApiError(HTTPStatus.BAD_REQUEST, "non-failed broadcast receipt cannot contain an error")
        payload = _canonical(value)
        with self._lock, self._connect() as database:
            if database.execute(
                "SELECT 1 FROM broadcasts WHERE broadcast_id = ?", (broadcast_id,)
            ).fetchone() is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "broadcast not found")
            existing = database.execute(
                "SELECT payload_json FROM broadcast_receipts WHERE broadcast_id = ? AND state = ?",
                (broadcast_id, state),
            ).fetchone()
            if existing is not None and existing["payload_json"] != payload:
                raise ApiError(HTTPStatus.CONFLICT, "broadcast receipt conflict")
            if existing is None:
                prior = database.execute(
                    "SELECT state, payload_json FROM broadcast_receipts WHERE broadcast_id = ?",
                    (broadcast_id,),
                ).fetchall()
                prior_states = {row["state"] for row in prior}
                terminal_states = {"PLAYED", "FAILED", "EXPIRED"}
                if state == "RECEIVED" and prior_states:
                    raise ApiError(HTTPStatus.CONFLICT, "broadcast receipt is out of order")
                if state == "PLAYING" and ("RECEIVED" not in prior_states or prior_states & terminal_states):
                    raise ApiError(HTTPStatus.CONFLICT, "broadcast receipt is out of order")
                if state in terminal_states and ("RECEIVED" not in prior_states or prior_states & terminal_states):
                    raise ApiError(HTTPStatus.CONFLICT, "broadcast receipt is out of order")
                if prior:
                    latest_time = max(json.loads(row["payload_json"])["occurredAtEpochMillis"] for row in prior)
                    if occurred_at <= latest_time:
                        raise ApiError(HTTPStatus.CONFLICT, "broadcast receipt time is not monotonic")
            database.execute(
                "INSERT OR REPLACE INTO broadcast_receipts VALUES (?, ?, ?)",
                (broadcast_id, state, payload),
            )
        return {"broadcastId": broadcast_id, "state": state, "deduplicated": existing is not None}

    def create_upload_session(self, raw: Any) -> dict[str, Any]:
        value = _require_object(raw)
        media_id, device_id, byte_size, sha256 = _validate_media_metadata(value)
        metadata = _canonical(value)
        with self._lock, self._connect() as database:
            archived = database.execute(
                "SELECT archive_id, sha256, byte_size FROM media WHERE media_id = ?", (media_id,)
            ).fetchone()
            if archived is not None:
                if archived["sha256"] != sha256 or archived["byte_size"] != byte_size:
                    raise ApiError(HTTPStatus.CONFLICT, "mediaId conflict")
                return {
                    "sessionId": media_id,
                    "nextOffset": byte_size,
                    "chunkSize": self.chunk_size,
                    "status": "COMPLETED",
                    "archiveId": archived["archive_id"],
                    "deduplicated": True,
                }
            session = database.execute(
                "SELECT * FROM upload_sessions WHERE media_id = ?", (media_id,)
            ).fetchone()
            if session is None:
                session_id = str(uuid.uuid4())
                temp_path = str(self.upload_root / f"{session_id}.partial")
                Path(temp_path).touch(mode=0o600, exist_ok=False)
                database.execute(
                    "INSERT INTO upload_sessions VALUES (?, ?, ?, ?, ?, ?, 0, 'UPLOADING', NULL, ?, ?)",
                    (session_id, media_id, device_id, byte_size, sha256, metadata, temp_path, _now()),
                )
                session = database.execute(
                    "SELECT * FROM upload_sessions WHERE session_id = ?", (session_id,)
                ).fetchone()
            elif (
                session["expected_size"] != byte_size
                or session["expected_sha256"] != sha256
                or session["metadata_json"] != metadata
            ):
                raise ApiError(HTTPStatus.CONFLICT, "media session content conflict")
        return self._session_response(session)

    def _session_response(self, row: sqlite3.Row, *, deduplicated: bool = False) -> dict[str, Any]:
        return {
            "sessionId": row["session_id"],
            "nextOffset": row["next_offset"],
            "chunkSize": self.chunk_size,
            "status": row["status"],
            "archiveId": row["archive_id"],
            "deduplicated": deduplicated,
        }

    def upload_chunk(
        self,
        session_id: str,
        content_range: str | None,
        chunk_sha256: str | None,
        payload: bytes,
    ) -> dict[str, Any]:
        match = CONTENT_RANGE_PATTERN.fullmatch(content_range or "")
        if match is None or not SHA256_PATTERN.fullmatch(chunk_sha256 or ""):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid chunk headers")
        start, end, total = map(int, match.groups())
        if (
            not payload
            or len(payload) > self.chunk_size
            or end < start
            or end >= total
            or end - start + 1 != len(payload)
        ):
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid chunk range")
        if hashlib.sha256(payload).hexdigest() != chunk_sha256:
            raise ApiError(HTTPStatus.BAD_REQUEST, "chunk digest mismatch")
        with self._lock, self._connect() as database:
            row = database.execute(
                "SELECT * FROM upload_sessions WHERE session_id = ?", (session_id,)
            ).fetchone()
            if row is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "upload session not found")
            if row["status"] != "UPLOADING":
                raise ApiError(HTTPStatus.CONFLICT, "upload session is complete")
            if total != row["expected_size"] or start != row["next_offset"]:
                raise ApiError(HTTPStatus.CONFLICT, "unexpected chunk offset")
            with Path(row["temp_path"]).open("ab") as output:
                output.write(payload)
                output.flush()
                os.fsync(output.fileno())
            next_offset = end + 1
            database.execute(
                "UPDATE upload_sessions SET next_offset = ? WHERE session_id = ?",
                (next_offset, session_id),
            )
        return {"sessionId": session_id, "nextOffset": next_offset}

    def complete_upload(self, session_id: str, raw: Any) -> dict[str, Any]:
        value = _require_object(raw)
        with self._lock, self._connect() as database:
            row = database.execute(
                "SELECT * FROM upload_sessions WHERE session_id = ?", (session_id,)
            ).fetchone()
            if row is None:
                raise ApiError(HTTPStatus.NOT_FOUND, "upload session not found")
            if row["status"] == "COMPLETED":
                return self._session_response(row, deduplicated=True)
            if (
                value.get("mediaId") != row["media_id"]
                or value.get("byteSize") != row["expected_size"]
                or value.get("sha256") != row["expected_sha256"]
                or row["next_offset"] != row["expected_size"]
            ):
                raise ApiError(HTTPStatus.CONFLICT, "upload completion mismatch")
            temp_path = Path(row["temp_path"])
            digest = hashlib.sha256()
            with temp_path.open("rb") as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk)
            if digest.hexdigest() != row["expected_sha256"]:
                raise ApiError(HTTPStatus.CONFLICT, "uploaded media digest mismatch")
            archive_id = str(uuid.uuid4())
            object_path = self.object_root / row["expected_sha256"]
            if object_path.exists():
                temp_path.unlink()
            else:
                temp_path.replace(object_path)
            database.execute(
                "INSERT INTO media VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    row["media_id"], archive_id, row["device_id"], row["expected_sha256"],
                    row["expected_size"], str(object_path), row["metadata_json"], _now(),
                ),
            )
            database.execute(
                "UPDATE upload_sessions SET status = 'COMPLETED', archive_id = ? WHERE session_id = ?",
                (archive_id, session_id),
            )
            completed = database.execute(
                "SELECT * FROM upload_sessions WHERE session_id = ?", (session_id,)
            ).fetchone()
        return self._session_response(completed)

    def media(self, media_id: str) -> dict[str, Any]:
        with self._lock, self._connect() as database:
            row = database.execute(
                "SELECT * FROM media WHERE media_id = ?", (media_id,)
            ).fetchone()
        if row is None:
            raise ApiError(HTTPStatus.NOT_FOUND, "media not found")
        return self._media_response(row)

    @staticmethod
    def _media_response(row: sqlite3.Row) -> dict[str, Any]:
        value = json.loads(row["metadata_json"])
        value.update(
            {
                "mediaId": row["media_id"],
                "archiveId": row["archive_id"],
                "deviceId": row["device_id"],
                "sha256": row["sha256"],
                "byteSize": row["byte_size"],
                "status": "COMPLETED",
            }
        )
        return value

    def media_page(self, device_id: str, kind: str | None, limit: int) -> dict[str, Any]:
        if kind is not None and kind not in MEDIA_KINDS:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid media kind")
        if not 1 <= limit <= 200:
            raise ApiError(HTTPStatus.BAD_REQUEST, "invalid media page")
        with self._lock, self._connect() as database:
            rows = database.execute(
                "SELECT * FROM media WHERE device_id = ? ORDER BY created_at DESC, media_id DESC",
                (device_id,),
            ).fetchall()
        media = []
        for row in rows:
            value = self._media_response(row)
            if kind is None or value.get("kind") == kind:
                media.append(value)
                if len(media) == limit:
                    break
        return {"media": media}

    def media_content(self, media_id: str) -> tuple[Path, str, int, str]:
        with self._lock, self._connect() as database:
            row = database.execute(
                "SELECT * FROM media WHERE media_id = ?", (media_id,)
            ).fetchone()
        if row is None:
            raise ApiError(HTTPStatus.NOT_FOUND, "media not found")
        object_path = Path(row["object_path"]).resolve()
        object_root = self.object_root.resolve()
        if object_path.parent != object_root or not object_path.is_file():
            raise ApiError(HTTPStatus.NOT_FOUND, "media content is unavailable")
        if object_path.stat().st_size != row["byte_size"]:
            raise ApiError(HTTPStatus.CONFLICT, "media content size mismatch")
        metadata = json.loads(row["metadata_json"])
        mime_type = metadata.get("mimeType")
        if not isinstance(mime_type, str) or not mime_type:
            raise ApiError(HTTPStatus.CONFLICT, "media content type is unavailable")
        return object_path, mime_type, row["byte_size"], row["sha256"]


class MediaHttpServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(
        self,
        address: tuple[str, int],
        repository: MediaRepository,
        token: str,
        stun_urls: tuple[str, ...] = ("stun:stun.l.google.com:19302",),
        turn_urls: tuple[str, ...] = (),
        turn_shared_secret: str = "",
        ice_ttl_seconds: int = 600,
    ) -> None:
        if not token:
            raise ValueError("HELMET_MEDIA_TOKEN must not be empty")
        super().__init__(address, MediaRequestHandler)
        self.repository = repository
        self.token = token
        self.stun_urls = stun_urls
        self.turn_urls = turn_urls
        self.turn_shared_secret = turn_shared_secret
        self.ice_ttl_seconds = ice_ttl_seconds

    def ice_configuration(self, call_id: str, requester_id: str) -> dict[str, Any]:
        now = _now()
        servers: list[dict[str, Any]] = [{"urls": list(self.stun_urls)}]
        if self.turn_urls and self.turn_shared_secret:
            expiry = int(time.time()) + self.ice_ttl_seconds
            username = f"{expiry}:{requester_id}"
            credential = hmac.new(
                self.turn_shared_secret.encode(), username.encode(), hashlib.sha1
            ).hexdigest()
            servers.append({
                "urls": list(self.turn_urls),
                "username": username,
                "credential": credential,
            })
        return {
            "callId": call_id,
            "requesterId": requester_id,
            "iceServers": servers,
            "issuedAtEpochMillis": now,
            "expiresAtEpochMillis": now + self.ice_ttl_seconds * 1000,
        }


class MediaRequestHandler(BaseHTTPRequestHandler):
    server: MediaHttpServer

    def do_GET(self) -> None:  # noqa: N802
        self._dispatch("GET")

    def do_POST(self) -> None:  # noqa: N802
        self._dispatch("POST")

    def do_PUT(self) -> None:  # noqa: N802
        self._dispatch("PUT")

    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_header("Allow", "GET, POST, PUT, OPTIONS")
        self.end_headers()

    def log_message(self, format: str, *args: Any) -> None:
        method = self.command if self.command in {"GET", "POST", "PUT", "OPTIONS"} else "UNKNOWN"
        path = urlparse(self.path).path
        if not path.startswith("/") or any(ord(character) < 32 for character in path):
            path = "<invalid-request-target>"
        status = str(args[1]) if len(args) > 1 and str(args[1]).isdigit() else "UNKNOWN"
        logging.info("request method=%s path=%s status=%s", method, path, status)

    def _dispatch(self, method: str) -> None:
        try:
            parsed = urlparse(self.path)
            if method == "GET" and parsed.path == "/health":
                self._send_json(HTTPStatus.OK, {"status": "ok"})
                return
            if method == "GET" and parsed.path == "/ready":
                self._send_json(HTTPStatus.OK, {"status": "ready"})
                return
            self._authorize()
            self._dispatch_api(method, parsed.path, parse_qs(parsed.query))
        except ApiError as error:
            self._send_json(error.status, {"error": error.message})
        except (StrictJsonError, UnicodeDecodeError):
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid JSON"})
        except Exception:
            logging.exception("request failed")
            self._send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": "internal server error"})

    def _authorize(self) -> None:
        expected = f"Bearer {self.server.token}"
        if not hmac.compare_digest(self.headers.get("Authorization", ""), expected):
            raise ApiError(HTTPStatus.UNAUTHORIZED, "invalid bearer token")

    def _dispatch_api(self, method: str, path: str, query: dict[str, list[str]]) -> None:
        repository = self.server.repository
        if method == "POST" and path == "/v1/device-status":
            self._send_json(HTTPStatus.OK, repository.save_status(self._read_json()))
            return
        if method == "POST" and path == "/v1/tracks:batch":
            self._send_json(HTTPStatus.OK, repository.save_tracks(self._read_json()))
            return
        if method == "GET" and path == "/v1/tracks":
            device_id = _require_id(query.get("deviceId", [None])[0], "deviceId")
            after = self._query_int(query, "afterSequence", 0)
            limit = self._query_int(query, "limit", 100)
            if after < 0 or not 1 <= limit <= 200:
                raise ApiError(HTTPStatus.BAD_REQUEST, "invalid track page")
            self._send_json(HTTPStatus.OK, repository.track_page(device_id, after, limit))
            return
        if method == "POST" and path == "/v1/alerts":
            self._send_json(HTTPStatus.OK, repository.save_alert(self._read_json()))
            return
        match = re.fullmatch(r"/v1/alerts/([^/]+)", path)
        if method == "GET" and match:
            alert_id = _require_id(unquote(match.group(1)), "alertId")
            self._send_json(HTTPStatus.OK, repository.alert(alert_id))
            return
        if method == "POST" and path == "/v1/calls":
            self._send_json(HTTPStatus.OK, repository.create_call(self._read_json()))
            return
        if method == "GET" and path == "/v1/calls":
            device_id = query.get("deviceId", [None])[0]
            limit = self._query_int(query, "limit", 20)
            self._send_json(HTTPStatus.OK, repository.calls(device_id, limit))
            return
        match = re.fullmatch(r"/v1/calls/([^/]+)", path)
        if method == "GET" and match:
            self._send_json(HTTPStatus.OK, repository.call(_require_id(unquote(match.group(1)), "callId")))
            return
        match = re.fullmatch(r"/v1/calls/([^/]+)/transitions", path)
        if method == "POST" and match:
            call_id = _require_id(unquote(match.group(1)), "callId")
            self._send_json(HTTPStatus.OK, repository.transition_call(call_id, self._read_json()))
            return
        match = re.fullmatch(r"/v1/calls/([^/]+)/signals", path)
        if match:
            call_id = _require_id(unquote(match.group(1)), "callId")
            if method == "POST":
                self._send_json(HTTPStatus.OK, repository.save_signal(call_id, self._read_json()))
                return
            if method == "GET":
                after = self._query_int(query, "afterSequence", 0)
                limit = self._query_int(query, "limit", 100)
                self._send_json(HTTPStatus.OK, repository.signals(call_id, after, limit))
                return
        match = re.fullmatch(r"/v1/calls/([^/]+)/ice-config", path)
        if method == "GET" and match:
            call_id = _require_id(unquote(match.group(1)), "callId")
            repository.call(call_id)
            requester = _require_id(query.get("requesterId", [None])[0], "requesterId")
            self._send_json(HTTPStatus.OK, self.server.ice_configuration(call_id, requester))
            return
        if method == "GET" and path == "/v1/device-commands":
            device_id = _require_id(query.get("deviceId", [None])[0], "deviceId")
            after = self._query_int(query, "afterSequence", 0)
            limit = self._query_int(query, "limit", 100)
            self._send_json(HTTPStatus.OK, repository.command_page(device_id, after, limit))
            return
        match = re.fullmatch(r"/v1/device-commands/([^/]+)/ack", path)
        if method == "POST" and match:
            command_id = _require_id(unquote(match.group(1)), "commandId")
            self._send_json(HTTPStatus.OK, repository.acknowledge_command(command_id, self._read_json()))
            return
        if method == "POST" and path == "/v1/broadcasts":
            self._send_json(HTTPStatus.OK, repository.create_broadcast(self._read_json()))
            return
        match = re.fullmatch(r"/v1/broadcasts/([^/]+)/receipts", path)
        if method == "POST" and match:
            broadcast_id = _require_id(unquote(match.group(1)), "broadcastId")
            self._send_json(
                HTTPStatus.OK,
                repository.save_broadcast_receipt(broadcast_id, self._read_json()),
            )
            return
        if method == "POST" and path == "/v1/media/sessions":
            self._send_json(HTTPStatus.OK, repository.create_upload_session(self._read_json()))
            return
        if method == "GET" and path == "/v1/media":
            device_id = _require_id(query.get("deviceId", [None])[0], "deviceId")
            kind = query.get("kind", [None])[0]
            limit = self._query_int(query, "limit", 20)
            self._send_json(HTTPStatus.OK, repository.media_page(device_id, kind, limit))
            return
        match = re.fullmatch(r"/v1/media/sessions/([^/]+)/chunks", path)
        if method == "PUT" and match:
            session_id = _require_id(unquote(match.group(1)), "sessionId")
            payload = self._read_body(MAX_JSON_BYTES * 4)
            self._send_json(
                HTTPStatus.OK,
                repository.upload_chunk(
                    session_id,
                    self.headers.get("Content-Range"),
                    self.headers.get("X-Chunk-SHA256"),
                    payload,
                ),
            )
            return
        match = re.fullmatch(r"/v1/media/sessions/([^/]+)/complete", path)
        if method == "POST" and match:
            session_id = _require_id(unquote(match.group(1)), "sessionId")
            self._send_json(HTTPStatus.OK, repository.complete_upload(session_id, self._read_json()))
            return
        match = re.fullmatch(r"/v1/media/([^/]+)", path)
        if method == "GET" and match:
            media_id = _require_id(unquote(match.group(1)), "mediaId")
            self._send_json(HTTPStatus.OK, repository.media(media_id))
            return
        match = re.fullmatch(r"/v1/media/([^/]+)/content", path)
        if method == "GET" and match:
            media_id = _require_id(unquote(match.group(1)), "mediaId")
            self._send_media_content(media_id, *repository.media_content(media_id))
            return
        raise ApiError(HTTPStatus.NOT_FOUND, "endpoint not found")

    def _read_body(self, maximum: int) -> bytes:
        raw_length = self.headers.get("Content-Length")
        try:
            length = int(raw_length or "")
        except ValueError as error:
            raise ApiError(HTTPStatus.LENGTH_REQUIRED, "valid Content-Length is required") from error
        if length < 0 or length > maximum:
            raise ApiError(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "request body is too large")
        payload = self.rfile.read(length)
        if len(payload) != length:
            raise ApiError(HTTPStatus.BAD_REQUEST, "incomplete request body")
        return payload

    def _read_json(self) -> Any:
        return loads_strict(self._read_body(MAX_JSON_BYTES))

    @staticmethod
    def _query_int(query: dict[str, list[str]], name: str, default: int) -> int:
        try:
            return int(query.get(name, [str(default)])[0])
        except ValueError as error:
            raise ApiError(HTTPStatus.BAD_REQUEST, f"invalid {name}") from error

    def _send_json(self, status: HTTPStatus, value: Any) -> None:
        payload = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)

    def _send_media_content(
        self,
        media_id: str,
        path: Path,
        mime_type: str,
        byte_size: int,
        sha256: str,
    ) -> None:
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", mime_type)
        self.send_header("Content-Length", str(byte_size))
        self.send_header("Content-Disposition", f'inline; filename="{media_id}"')
        self.send_header("Cache-Control", "private, no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("ETag", f'"{sha256}"')
        self.end_headers()
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(64 * 1024), b""):
                self.wfile.write(chunk)

def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Minimal Android helmet companion service")
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--chunk-size", type=int, default=256 * 1024)
    parser.add_argument("--required-app-version")
    options = parser.parse_args(arguments)
    token = os.environ.get("HELMET_MEDIA_TOKEN", "")
    stun_urls = tuple(filter(None, os.environ.get(
        "HELMET_STUN_URLS", "stun:stun.l.google.com:19302"
    ).split(",")))
    turn_urls = tuple(filter(None, os.environ.get("HELMET_TURN_URLS", "").split(",")))
    server = MediaHttpServer(
        (options.host, options.port),
        MediaRepository(options.data_dir, options.chunk_size, options.required_app_version),
        token,
        stun_urls,
        turn_urls,
        os.environ.get("HELMET_TURN_SHARED_SECRET", ""),
    )
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    logging.info("minimal helmet service listening on %s:%s", options.host, options.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
