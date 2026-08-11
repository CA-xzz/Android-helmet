from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace
import unittest

from backend.mqtt_gateway import (
    MqttConfiguration,
    MqttGateway,
    MqttRequestError,
    MQTT_UPLINK_FILTER,
)


class PublishResult:
    rc = 0


class FakeClient:
    def __init__(self) -> None:
        self.published: list[tuple[str, bytes, int, bool]] = []
        self.subscriptions: list[tuple[str, int]] = []

    def subscribe(self, topic: str, qos: int) -> tuple[int, int]:
        self.subscriptions.append((topic, qos))
        return 0, 1

    def publish(self, topic: str, payload: bytes, qos: int, retain: bool) -> PublishResult:
        self.published.append((topic, payload, qos, retain))
        return PublishResult()


class FakeRepository:
    def __init__(self) -> None:
        self.calls: list[tuple[str, object]] = []
        self.commands: list[dict[str, object]] = []
        self.status_error: Exception | None = None

    def ingest_device_status(self, payload: dict[str, object]) -> dict[str, object]:
        self.calls.append(("status", payload))
        if self.status_error is not None:
            raise self.status_error
        return {"messageId": payload["messageId"], "deduplicated": False}

    def ingest_track_batch(self, payload: dict[str, object]) -> dict[str, object]:
        self.calls.append(("tracks", payload))
        return {"acceptedMessageIds": ["point-1"], "duplicateMessageIds": []}

    def ingest_safety_alert(self, payload: dict[str, object]) -> dict[str, object]:
        self.calls.append(("alert", payload))
        return {"alertId": payload["alertId"], "deduplicated": False}

    def command_device(self, command_id: str) -> str:
        return "device-a"

    def ack_device_command(self, command_id: str, payload: dict[str, object]) -> dict[str, object]:
        self.calls.append(("command-ack", (command_id, payload)))
        return {"commandId": command_id, "deduplicated": False}

    def broadcast_device(self, broadcast_id: str) -> str:
        return "device-a"

    def record_broadcast_receipt(
        self, broadcast_id: str, payload: dict[str, object]
    ) -> dict[str, object]:
        self.calls.append(("broadcast-receipt", (broadcast_id, payload)))
        return {"broadcastId": broadcast_id, "deduplicated": False}

    def pending_device_commands(self, limit: int, offset: int) -> list[dict[str, object]]:
        self.calls.append(("pending", (limit, offset)))
        return list(self.commands[offset:offset + limit])

    def pending_device_commands_for_device(
        self,
        device_id: str,
        after_sequence: int,
        limit: int,
    ) -> list[dict[str, object]]:
        self.calls.append(("command-sync", (device_id, after_sequence, limit)))
        return [
            command
            for command in self.commands
            if command["deviceId"] == device_id and command["sequence"] > after_sequence
        ][:limit]


class MqttGatewayTest(unittest.TestCase):
    def setUp(self) -> None:
        self.now = 1_786_000_000.0
        self.configuration = MqttConfiguration(
            host="mqtt.company.cn",
            port=8883,
            ca_file=Path("/run/secrets/mqtt_ca"),
            certificate_file=Path("/run/secrets/mqtt_backend_certificate"),
            private_key_file=Path("/run/secrets/mqtt_backend_private_key"),
        )
        self.repository = FakeRepository()
        self.client = FakeClient()
        self.gateway = MqttGateway(
            self.repository,
            self.configuration,
            {"device-a"},
            client=self.client,
            wall_clock=lambda: self.now,
        )
        self.gateway._on_connect(self.client, None, None, 0, None)
        self.gateway._on_subscribe(self.client, None, 1, [1], None)

    def envelope(
        self,
        kind: str,
        message_id: str,
        payload: dict[str, object],
        occurred_at: int = 1_786_000_000_000,
    ) -> bytes:
        return json.dumps(
            {
                "schemaVersion": 1,
                "messageId": message_id,
                "deviceId": "device-a",
                "occurredAtEpochMillis": occurred_at,
                "kind": kind,
                "payload": payload,
            }
        ).encode()

    def message(self, kind: str, payload: bytes, device_id: str = "device-a") -> SimpleNamespace:
        return SimpleNamespace(
            topic=f"helmet/v1/devices/{device_id}/up/{kind}",
            payload=payload,
            qos=1,
            retain=False,
        )

    def result(self) -> tuple[str, dict[str, object]]:
        topic, payload, qos, retain = self.client.published[-1]
        self.assertEqual(1, qos)
        self.assertFalse(retain)
        return topic, json.loads(payload)

    def test_connect_subscribes_to_qos_one_uplink_and_sets_readiness(self) -> None:
        self.assertEqual([(MQTT_UPLINK_FILTER, 1)], self.client.subscriptions)
        self.gateway.check_ready()
        self.gateway._on_subscribe(self.client, None, 1, [128], None)
        with self.assertRaisesRegex(RuntimeError, "not ready"):
            self.gateway.check_ready()
        self.gateway._on_subscribe(self.client, None, 1, [1], None)
        self.gateway._on_disconnect(self.client, None, None, 1, None)
        with self.assertRaisesRegex(RuntimeError, "not ready"):
            self.gateway.check_ready()

    def test_status_uplink_reuses_repository_and_publishes_application_receipt(self) -> None:
        payload = {
            "messageId": "status-1",
            "deviceId": "device-a",
            "occurredAtEpochMillis": 1_786_000_000_000,
        }
        self.gateway._on_message(
            self.client,
            None,
            self.message("status", self.envelope("status", "status-1", payload)),
        )
        self.assertEqual("status", self.repository.calls[0][0])
        topic, result = self.result()
        self.assertEqual("helmet/v1/devices/device-a/down/result/status-1", topic)
        self.assertTrue(result["accepted"])
        self.assertEqual("status-1", result["result"]["messageId"])

        class ForeignApiError(Exception):
            status = 409
            message = "repository conflict"

        self.repository.status_error = ForeignApiError()
        conflicting_payload = {
            "messageId": "status-foreign-error",
            "deviceId": "device-a",
            "occurredAtEpochMillis": 1_786_000_000_000,
        }
        self.gateway._on_message(
            self.client,
            None,
            self.message(
                "status",
                self.envelope("status", "status-foreign-error", conflicting_payload),
            ),
        )
        _, conflict = self.result()
        self.assertFalse(conflict["accepted"])
        self.assertEqual(409, conflict["status"])
        self.assertEqual("repository conflict", conflict["error"])

    def test_mqtt_rejects_ambiguous_non_standard_and_deep_json(self) -> None:
        payload = {
            "messageId": "status-strict-json",
            "deviceId": "device-a",
            "occurredAtEpochMillis": 1_786_000_000_000,
        }
        encoded = self.envelope("status", "status-strict-json", payload)
        duplicate = encoded.replace(
            b'"schemaVersion": 1',
            b'"schemaVersion": 1, "schemaVersion": 1',
            1,
        )
        with self.assertRaisesRegex(MqttRequestError, "not valid JSON"):
            MqttGateway._decode_envelope(duplicate, "device-a", "status")

        non_standard = encoded.replace(
            b'"payload": {',
            b'"payload": {"unexpected": NaN, ',
            1,
        )
        with self.assertRaisesRegex(MqttRequestError, "not valid JSON"):
            MqttGateway._decode_envelope(non_standard, "device-a", "status")

        nested: object = 1
        for _ in range(65):
            nested = [nested]
        deep_payload = dict(payload)
        deep_payload["unexpected"] = nested
        with self.assertRaisesRegex(MqttRequestError, "not valid JSON"):
            MqttGateway._decode_envelope(
                self.envelope("status", "status-strict-json", deep_payload),
                "device-a",
                "status",
            )

    def test_inner_device_mismatch_is_rejected_without_repository_write(self) -> None:
        payload = {
            "messageId": "status-2",
            "deviceId": "device-b",
            "occurredAtEpochMillis": 1_786_000_000_000,
        }
        self.gateway._on_message(
            self.client,
            None,
            self.message("status", self.envelope("status", "status-2", payload)),
        )
        self.assertEqual([], self.repository.calls)
        _, result = self.result()
        self.assertFalse(result["accepted"])
        self.assertEqual(403, result["status"])

    def test_unenrolled_device_and_invalid_envelope_are_discarded(self) -> None:
        self.gateway._on_message(
            self.client,
            None,
            self.message("status", b"{}", device_id="device-b"),
        )
        self.gateway._on_message(
            self.client,
            None,
            self.message("status", b"not-json"),
        )
        self.assertEqual([], self.repository.calls)
        self.assertEqual([], self.client.published)

    def test_command_ack_and_broadcast_receipt_are_bound_to_topic_device(self) -> None:
        command_payload = {
            "commandId": "command-1",
            "status": "APPLIED",
            "error": None,
            "occurredAtEpochMillis": 1_786_000_000_000,
        }
        receipt_payload = {
            "broadcastId": "broadcast-1",
            "state": "PLAYED",
            "error": None,
            "occurredAtEpochMillis": 1_786_000_000_000,
        }
        for kind, message_id, payload in (
            ("command-ack", "ack-1", command_payload),
            ("broadcast-receipt", "receipt-1", receipt_payload),
        ):
            self.gateway._on_message(
                self.client,
                None,
                self.message(kind, self.envelope(kind, message_id, payload)),
            )
        self.assertEqual(["command-ack", "broadcast-receipt"], [call[0] for call in self.repository.calls])
        self.assertNotIn("commandId", self.repository.calls[0][1][1])
        self.assertNotIn("broadcastId", self.repository.calls[1][1][1])

    def test_pending_commands_publish_once_and_device_sync_recovers_delivery(self) -> None:
        self.repository.commands = [
            {
                "commandId": "command-1",
                "deviceId": "device-a",
                "sequence": 1,
                "type": "TEXT_BROADCAST",
                "payload": {"broadcastId": "broadcast-1"},
                "createdAtEpochMillis": 1_786_000_000_000,
                "acknowledged": False,
            }
        ]
        self.assertEqual(1, self.gateway.publish_pending_commands())
        topic, envelope = self.result()
        self.assertEqual("helmet/v1/devices/device-a/down/command", topic)
        self.assertEqual("command-1", envelope["messageId"])
        self.assertEqual(0, self.gateway.publish_pending_commands())

        sync_payload = {
            "deviceId": "device-a",
            "afterSequence": 0,
            "occurredAtEpochMillis": 1_786_000_000_000,
        }
        self.gateway._on_message(
            self.client,
            None,
            self.message(
                "command-sync",
                self.envelope("command-sync", "sync-1", sync_payload),
            ),
        )
        self.assertEqual(
            "helmet/v1/devices/device-a/down/command",
            self.client.published[-2][0],
        )
        _, sync_result = self.result()
        self.assertTrue(sync_result["accepted"])
        self.assertEqual(1, sync_result["result"]["publishedCommands"])

        invalid_sync = dict(sync_payload)
        invalid_sync["afterSequence"] = 2 ** 63
        self.gateway._on_message(
            self.client,
            None,
            self.message(
                "command-sync",
                self.envelope("command-sync", "sync-overflow", invalid_sync),
            ),
        )
        _, invalid_result = self.result()
        self.assertFalse(invalid_result["accepted"])
        self.assertEqual(400, invalid_result["status"])

        self.gateway._on_message(
            self.client,
            None,
            self.message(
                "command-sync",
                self.envelope("command-sync", "sync-time-overflow", sync_payload, 2 ** 63),
            ),
        )
        _, invalid_time_result = self.result()
        self.assertFalse(invalid_time_result["accepted"])
        self.assertEqual(400, invalid_time_result["status"])

        self.gateway._on_disconnect(self.client, None, None, 1, None)
        self.gateway._on_connect(self.client, None, None, 0, None)
        self.gateway._on_subscribe(self.client, None, 1, [1], None)
        second_command = {
            **self.repository.commands[0],
            "commandId": "command-2",
            "sequence": 2,
        }
        self.repository.commands.append(second_command)
        self.assertEqual(1, self.gateway.publish_pending_commands(limit=1))
        self.assertEqual(1, self.gateway.publish_pending_commands(limit=1))
        self.assertEqual(0, self.gateway.publish_pending_commands(limit=1))
        self.assertEqual(0, self.gateway._pending_scan_offset)
        self.repository.commands = []
        self.assertEqual(0, self.gateway.publish_pending_commands())
        self.assertEqual({"command-1", "command-2"}, self.gateway._published_command_ids)

    def test_partial_environment_and_non_acl_client_identity_fail_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "configuration is missing"):
            MqttConfiguration.from_environment({"HELMET_MQTT_HOST": "mqtt.company.cn"})
        with self.assertRaisesRegex(ValueError, "must match the broker ACL"):
            MqttConfiguration(
                host="mqtt.company.cn",
                port=8883,
                ca_file=Path("/ca"),
                certificate_file=Path("/cert"),
                private_key_file=Path("/key"),
                client_id="different-client",
            ).validate(require_files=False)


if __name__ == "__main__":
    unittest.main()
