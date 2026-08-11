"""Mutual-TLS MQTT bridge for durable device uplink and command delivery."""

from __future__ import annotations

from dataclasses import dataclass
import json
import logging
from pathlib import Path
import re
import ssl
import threading
import time
from typing import Any, Callable

from backend.json_codec import StrictJsonError, loads_strict

MQTT_MAX_PAYLOAD_BYTES = 1024 * 1024
MAX_SIGNED_INT64 = 9_223_372_036_854_775_807
ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
MQTT_UPLINK_FILTER = "helmet/v1/devices/+/up/+"
MQTT_UPLINK_TOPIC = re.compile(
    r"^helmet/v1/devices/([A-Za-z0-9._:-]{1,128})/up/"
    r"(status|tracks|alert|command-sync|command-ack|broadcast-receipt)$"
)
MQTT_CLIENT_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
MQTT_UPLINK_KINDS = frozenset(
    {
        "status", "tracks", "alert", "command-sync", "command-ack",
        "broadcast-receipt",
    }
)


class MqttRequestError(Exception):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


@dataclass(frozen=True)
class MqttConfiguration:
    host: str
    port: int
    ca_file: Path
    certificate_file: Path
    private_key_file: Path
    client_id: str = "backend-gateway"
    keepalive_seconds: int = 60
    connect_timeout_seconds: float = 20.0
    command_poll_seconds: float = 2.0

    @classmethod
    def from_environment(cls, values: dict[str, str]) -> MqttConfiguration | None:
        names = {
            "host": "HELMET_MQTT_HOST",
            "port": "HELMET_MQTT_PORT",
            "ca_file": "HELMET_MQTT_CA_FILE",
            "certificate_file": "HELMET_MQTT_CERTIFICATE_FILE",
            "private_key_file": "HELMET_MQTT_PRIVATE_KEY_FILE",
            "client_id": "HELMET_MQTT_CLIENT_ID",
        }
        configured = {field: values.get(name, "").strip() for field, name in names.items()}
        required = {field for field in names if field != "client_id"}
        present = {field for field in required if configured[field]}
        if not present:
            return None
        missing = sorted(names[field] for field in required - present)
        if missing:
            raise ValueError(f"MQTT configuration is missing: {', '.join(missing)}")
        try:
            port = int(configured["port"])
        except ValueError as error:
            raise ValueError("HELMET_MQTT_PORT must be an integer") from error
        configuration = cls(
            host=configured["host"],
            port=port,
            ca_file=Path(configured["ca_file"]),
            certificate_file=Path(configured["certificate_file"]),
            private_key_file=Path(configured["private_key_file"]),
            client_id=configured["client_id"] or "backend-gateway",
        )
        configuration.validate(require_files=True)
        return configuration

    def validate(self, require_files: bool = True) -> None:
        if (
            not self.host
            or len(self.host) > 253
            or any(character.isspace() for character in self.host)
            or "://" in self.host
            or "/" in self.host
        ):
            raise ValueError("MQTT host is invalid")
        if not 1 <= self.port <= 65535:
            raise ValueError("MQTT port is invalid")
        if not MQTT_CLIENT_ID_PATTERN.fullmatch(self.client_id):
            raise ValueError("MQTT client ID is invalid")
        if self.client_id != "backend-gateway":
            raise ValueError("MQTT backend client ID must match the broker ACL identity")
        if not 10 <= self.keepalive_seconds <= 3600:
            raise ValueError("MQTT keepalive is invalid")
        if self.connect_timeout_seconds <= 0:
            raise ValueError("MQTT connect timeout is invalid")
        if self.command_poll_seconds <= 0:
            raise ValueError("MQTT command polling interval is invalid")
        for name, path in (
            ("CA", self.ca_file),
            ("client certificate", self.certificate_file),
            ("client private key", self.private_key_file),
        ):
            if not path.is_absolute():
                raise ValueError(f"MQTT {name} file must use an absolute path")
            if require_files and (not path.is_file() or path.stat().st_size == 0):
                raise ValueError(f"MQTT {name} file is missing or empty")


class MqttGateway:
    """Bridges MQTT messages into the canonical repository and publishes pending commands."""

    def __init__(
        self,
        repository: Any,
        configuration: MqttConfiguration,
        allowed_device_ids: set[str],
        client: Any | None = None,
        wall_clock: Callable[[], float] = time.time,
    ) -> None:
        configuration.validate(require_files=client is None)
        if not allowed_device_ids or any(not ID_PATTERN.fullmatch(value) for value in allowed_device_ids):
            raise ValueError("MQTT allowed device IDs are invalid")
        self.repository = repository
        self.configuration = configuration
        self.allowed_device_ids = frozenset(allowed_device_ids)
        self._client = client
        self._wall_clock = wall_clock
        self._connected = threading.Event()
        self._stopping = threading.Event()
        self._command_wakeup = threading.Event()
        self._command_thread: threading.Thread | None = None
        self._command_state_lock = threading.Lock()
        self._published_command_ids: set[str] = set()
        self._pending_scan_offset = 0

    def start(self) -> None:
        if self._command_thread is not None:
            raise RuntimeError("MQTT gateway is already started")
        if self._client is None:
            self._client = self._build_client()
        self._install_callbacks(self._client)
        result = self._client.connect_async(
            self.configuration.host,
            self.configuration.port,
            self.configuration.keepalive_seconds,
        )
        if int(result) != 0:
            raise RuntimeError(f"MQTT connect request failed with code {result}")
        self._client.loop_start()
        if not self._connected.wait(self.configuration.connect_timeout_seconds):
            self._client.loop_stop()
            raise RuntimeError("MQTT broker did not become ready before the startup deadline")
        self._command_thread = threading.Thread(
            target=self._command_loop,
            name="helmet-mqtt-command-publisher",
            daemon=True,
        )
        self._command_thread.start()

    def stop(self) -> None:
        self._stopping.set()
        self._command_wakeup.set()
        if self._command_thread is not None:
            self._command_thread.join(timeout=max(2.0, self.configuration.command_poll_seconds + 1.0))
            self._command_thread = None
        if self._client is not None:
            try:
                self._client.disconnect()
            finally:
                self._client.loop_stop()
        self._connected.clear()

    def check_ready(self) -> None:
        if not self._connected.is_set():
            raise RuntimeError("MQTT broker connection is not ready")
        if self._command_thread is not None and not self._command_thread.is_alive():
            raise RuntimeError("MQTT command publisher is not running")

    def publish_pending_commands(self, limit: int = 500) -> int:
        if not self._connected.is_set():
            return 0
        with self._command_state_lock:
            scan_offset = self._pending_scan_offset
        commands = self.repository.pending_device_commands(limit, scan_offset)
        with self._command_state_lock:
            if not self._connected.is_set():
                return 0
            self._pending_scan_offset = scan_offset + len(commands) if len(commands) == limit else 0
        published = 0
        for command in commands:
            if command["commandId"] in self._published_command_ids:
                continue
            if self._publish_command(command):
                published += 1
        return published

    def _publish_command(self, command: dict[str, Any], force: bool = False) -> bool:
        command_id = command["commandId"]
        with self._command_state_lock:
            if not force and command_id in self._published_command_ids:
                return False
            device_id = command["deviceId"]
            if device_id not in self.allowed_device_ids:
                logging.error("refusing MQTT command for unenrolled device %s", device_id)
                return False
            envelope = {
                "schemaVersion": 1,
                "messageId": command_id,
                "deviceId": device_id,
                "occurredAtEpochMillis": command["createdAtEpochMillis"],
                "kind": "command",
                "payload": command,
            }
            self._publish(f"helmet/v1/devices/{device_id}/down/command", envelope)
            self._published_command_ids.add(command_id)
            return True

    def _build_client(self) -> Any:
        try:
            import paho.mqtt.client as mqtt
        except ImportError as error:
            raise RuntimeError("paho-mqtt is required when MQTT is configured") from error
        context = ssl.create_default_context(
            purpose=ssl.Purpose.SERVER_AUTH,
            cafile=str(self.configuration.ca_file),
        )
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(
            certfile=str(self.configuration.certificate_file),
            keyfile=str(self.configuration.private_key_file),
        )
        client = mqtt.Client(
            mqtt.CallbackAPIVersion.VERSION2,
            client_id=self.configuration.client_id,
            protocol=mqtt.MQTTv5,
        )
        client.tls_set_context(context)
        client.reconnect_delay_set(min_delay=1, max_delay=30)
        client.enable_logger()
        return client

    def _install_callbacks(self, client: Any) -> None:
        client.on_connect = self._on_connect
        client.on_connect_fail = self._on_connect_fail
        client.on_disconnect = self._on_disconnect
        client.on_subscribe = self._on_subscribe
        client.on_message = self._on_message

    def _on_connect(
        self,
        client: Any,
        userdata: Any,
        flags: Any,
        reason_code: Any,
        properties: Any,
    ) -> None:
        failure = getattr(reason_code, "is_failure", None)
        connection_failed = failure if failure is not None else reason_code != 0
        if connection_failed:
            self._connected.clear()
            logging.error("MQTT connection refused: %s", reason_code)
            return
        result, _ = client.subscribe(MQTT_UPLINK_FILTER, qos=1)
        if int(result) != 0:
            self._connected.clear()
            logging.error("MQTT uplink subscription request failed: %s", result)
            return
        logging.info("MQTT gateway connected; awaiting uplink subscription confirmation")

    def _on_connect_fail(self, client: Any, userdata: Any) -> None:
        self._connected.clear()
        logging.error("MQTT broker connection attempt failed")

    def _on_disconnect(
        self,
        client: Any,
        userdata: Any,
        disconnect_flags: Any,
        reason_code: Any,
        properties: Any,
    ) -> None:
        self._connected.clear()
        with self._command_state_lock:
            self._published_command_ids.clear()
            self._pending_scan_offset = 0
        if not self._stopping.is_set():
            logging.warning("MQTT broker connection lost: %s", reason_code)

    def _on_subscribe(
        self,
        client: Any,
        userdata: Any,
        mid: int,
        reason_code_list: list[Any],
        properties: Any,
    ) -> None:
        rejected = []
        for code in reason_code_list:
            failure = getattr(code, "is_failure", None)
            rejected.append(failure if failure is not None else code >= 128)
        if any(rejected):
            self._connected.clear()
            logging.error("MQTT broker rejected the uplink subscription")
            return
        self._connected.set()
        self._command_wakeup.set()
        logging.info("MQTT gateway subscribed to %s", MQTT_UPLINK_FILTER)

    def _on_message(self, client: Any, userdata: Any, message: Any) -> None:
        match = MQTT_UPLINK_TOPIC.fullmatch(message.topic)
        if match is None or message.qos != 1 or message.retain:
            logging.warning("discarding MQTT message with invalid topic or delivery attributes")
            return
        topic_device_id, kind = match.groups()
        if topic_device_id not in self.allowed_device_ids:
            logging.warning("discarding MQTT message from unenrolled device %s", topic_device_id)
            return
        try:
            envelope = self._decode_envelope(message.payload, topic_device_id, kind)
        except MqttRequestError as error:
            logging.warning("discarding invalid MQTT envelope for %s: %s", topic_device_id, error.message)
            return
        try:
            result = self._dispatch(
                topic_device_id,
                kind,
                envelope["messageId"],
                envelope["occurredAtEpochMillis"],
                envelope["payload"],
            )
            response = {
                "schemaVersion": 1,
                "messageId": envelope["messageId"],
                "deviceId": topic_device_id,
                "occurredAtEpochMillis": int(self._wall_clock() * 1000),
                "kind": kind,
                "accepted": True,
                "result": result,
            }
        except Exception as error:
            status = getattr(error, "status", None)
            message_text = getattr(error, "message", None)
            if (
                isinstance(status, int)
                and 400 <= status <= 599
                and isinstance(message_text, str)
                and message_text
            ):
                response = {
                    "schemaVersion": 1,
                    "messageId": envelope["messageId"],
                    "deviceId": topic_device_id,
                    "occurredAtEpochMillis": int(self._wall_clock() * 1000),
                    "kind": kind,
                    "accepted": False,
                    "status": int(status),
                    "error": message_text,
                }
            else:
                logging.exception("MQTT uplink processing failed")
                response = {
                    "schemaVersion": 1,
                    "messageId": envelope["messageId"],
                    "deviceId": topic_device_id,
                    "occurredAtEpochMillis": int(self._wall_clock() * 1000),
                    "kind": kind,
                    "accepted": False,
                    "status": 500,
                    "error": "internal server error",
                }
        try:
            self._publish(
                f"helmet/v1/devices/{topic_device_id}/down/result/{envelope['messageId']}",
                response,
                client=client,
            )
        except Exception:
            logging.exception("failed to publish MQTT application receipt")

    @staticmethod
    def _decode_envelope(payload_bytes: bytes, topic_device_id: str, topic_kind: str) -> dict[str, Any]:
        if not 0 < len(payload_bytes) <= MQTT_MAX_PAYLOAD_BYTES:
            raise MqttRequestError(400, "MQTT payload size is invalid")
        try:
            value = loads_strict(payload_bytes)
        except StrictJsonError as error:
            raise MqttRequestError(400, "MQTT payload is not valid JSON") from error
        if not isinstance(value, dict) or value.get("schemaVersion") != 1:
            raise MqttRequestError(400, "MQTT envelope schema is invalid")
        message_id = value.get("messageId")
        device_id = value.get("deviceId")
        occurred_at = value.get("occurredAtEpochMillis")
        if not isinstance(message_id, str) or not ID_PATTERN.fullmatch(message_id):
            raise MqttRequestError(400, "MQTT envelope messageId is invalid")
        if device_id != topic_device_id:
            raise MqttRequestError(403, "MQTT envelope device does not match certificate topic")
        if value.get("kind") != topic_kind:
            raise MqttRequestError(400, "MQTT envelope kind does not match its topic")
        if (
            not isinstance(occurred_at, int)
            or isinstance(occurred_at, bool)
            or not 0 < occurred_at <= MAX_SIGNED_INT64
        ):
            raise MqttRequestError(400, "MQTT envelope time is invalid")
        if not isinstance(value.get("payload"), dict):
            raise MqttRequestError(400, "MQTT envelope payload must be an object")
        return value

    def _dispatch(
        self,
        device_id: str,
        kind: str,
        message_id: str,
        occurred_at: int,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        if kind not in MQTT_UPLINK_KINDS:
            raise MqttRequestError(400, "unsupported MQTT uplink kind")
        if kind == "tracks":
            points = payload.get("points")
            if not isinstance(points, list) or any(
                not isinstance(point, dict) or point.get("deviceId") != device_id for point in points
            ):
                raise MqttRequestError(403, "track batch contains another device")
            return self.repository.ingest_track_batch(payload)
        if payload.get("deviceId") != device_id and kind in {
            "status", "alert", "command-sync",
        }:
            raise MqttRequestError(403, "MQTT payload contains another device")
        if kind in {"status", "alert"} and payload.get("messageId") != message_id:
            raise MqttRequestError(400, "MQTT envelope and payload message IDs differ")
        if kind != "tracks" and payload.get("occurredAtEpochMillis") != occurred_at:
            raise MqttRequestError(400, "MQTT envelope and payload times differ")
        if kind == "status":
            return self.repository.ingest_device_status(payload)
        if kind == "alert":
            return self.repository.ingest_safety_alert(payload)
        if kind == "command-sync":
            after_sequence = payload.get("afterSequence")
            if (
                not isinstance(after_sequence, int)
                or isinstance(after_sequence, bool)
                or after_sequence < 0
                or after_sequence > MAX_SIGNED_INT64
            ):
                raise MqttRequestError(400, "command sync sequence is invalid")
            commands = self.repository.pending_device_commands_for_device(
                device_id,
                after_sequence,
                100,
            )
            published = sum(1 for command in commands if self._publish_command(command, force=True))
            return {"afterSequence": after_sequence, "publishedCommands": published}
        if kind == "command-ack":
            command_id = payload.get("commandId")
            if not isinstance(command_id, str) or not ID_PATTERN.fullmatch(command_id):
                raise MqttRequestError(400, "command acknowledgement ID is invalid")
            if self.repository.command_device(command_id) != device_id:
                raise MqttRequestError(403, "command belongs to another device")
            acknowledgement = dict(payload)
            del acknowledgement["commandId"]
            return self.repository.ack_device_command(command_id, acknowledgement)
        broadcast_id = payload.get("broadcastId")
        if not isinstance(broadcast_id, str) or not ID_PATTERN.fullmatch(broadcast_id):
            raise MqttRequestError(400, "broadcast receipt ID is invalid")
        if self.repository.broadcast_device(broadcast_id) != device_id:
            raise MqttRequestError(403, "broadcast belongs to another device")
        receipt = dict(payload)
        del receipt["broadcastId"]
        return self.repository.record_broadcast_receipt(broadcast_id, receipt)

    def _publish(self, topic: str, value: dict[str, Any], client: Any | None = None) -> None:
        active_client = client or self._client
        if active_client is None:
            raise RuntimeError("MQTT client is not initialized")
        result = active_client.publish(topic, canonical_json(value).encode("utf-8"), qos=1, retain=False)
        if int(result.rc) != 0:
            raise RuntimeError(f"MQTT publish failed with code {result.rc}")

    def _command_loop(self) -> None:
        while not self._stopping.is_set():
            self._command_wakeup.wait(self.configuration.command_poll_seconds)
            self._command_wakeup.clear()
            if self._stopping.is_set():
                break
            try:
                self.publish_pending_commands()
            except Exception:
                logging.exception("MQTT pending command publication failed")
