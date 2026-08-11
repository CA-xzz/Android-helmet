from __future__ import annotations

import hashlib
from http.client import HTTPConnection
from io import BytesIO
import json
from pathlib import Path
import tempfile
import threading
import unittest
import os
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from backend.media_service import (
    ApiError,
    AuthPrincipal,
    MAX_COMMUNICATION_QUERY_ROWS,
    MAX_COMMUNICATION_RESPONSE_BYTES,
    MediaHttpServer,
    MediaRepository,
    load_auth_configuration,
    load_auth_principals,
)
from backend.object_store import ObjectStoreError, S3ObjectStore


TOKEN = "integration-test-token"


class ReadOnlyS3Client:
    def __init__(self) -> None:
        self.objects: dict[tuple[str, str], tuple[bytes, dict[str, str]]] = {}

    def head_object(self, *, Bucket: str, Key: str) -> dict[str, object]:
        body, metadata = self.objects[(Bucket, Key)]
        return {"ContentLength": len(body), "Metadata": metadata}

    def get_object(self, *, Bucket: str, Key: str) -> dict[str, BytesIO]:
        return {"Body": BytesIO(self.objects[(Bucket, Key)][0])}


class MediaServiceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repository = MediaRepository(Path(self.temporary.name), chunk_size=64 * 1024)
        self.server = MediaHttpServer(
            ("127.0.0.1", 0),
            self.repository,
            TOKEN,
            ("stun:stun.example.test:3478",),
            ("turn:turn.example.test:3478?transport=udp",),
            "test-turn-shared-secret",
            600,
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temporary.cleanup()

    def request(
        self,
        method: str,
        path: str,
        body: bytes = b"",
        headers: dict[str, str] | None = None,
        authorize: bool = True,
    ) -> tuple[int, bytes, dict[str, str]]:
        request_headers = dict(headers or {})
        if authorize:
            request_headers["Authorization"] = f"Bearer {TOKEN}"
        request = Request(self.base_url + path, data=body, headers=request_headers, method=method)
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, response.read(), dict(response.headers.items())
        except HTTPError as error:
            return error.code, error.read(), dict(error.headers.items())

    def metadata(self, media_id: str, payload: bytes) -> dict[str, object]:
        return {
            "mediaId": media_id,
            "deviceId": "device-1",
            "kind": "VIDEO",
            "mimeType": "video/mp4",
            "byteSize": len(payload),
            "sha256": hashlib.sha256(payload).hexdigest(),
            "width": 1920,
            "height": 1080,
            "durationMillis": 1000,
            "createdAtEpochMillis": 1_786_000_000_000,
            "personId": None,
            "relatedEventId": "event-1",
            "location": {
                "latitude": None,
                "longitude": None,
                "horizontalAccuracyMeters": None,
                "fixType": "NO_FIX",
            },
        }

    def post_json(
        self,
        path: str,
        value: object,
        authorize: bool = True,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, object]]:
        status, body, _ = self.request(
            "POST",
            path,
            json.dumps(value).encode(),
            {"Content-Type": "application/json", **(headers or {})},
            authorize,
        )
        return status, json.loads(body)

    def track_point(self, message_id: str, sequence: int) -> dict[str, object]:
        return {
            "messageId": message_id,
            "deviceId": "device-1",
            "sequence": sequence,
            "fixId": f"fix-{sequence}",
            "occurredAtEpochMillis": 1_786_000_000_000 + sequence,
            "elapsedRealtimeNanos": sequence * 1_000_000,
            "source": "ANDROID_GNSS",
            "quality": "STANDARD",
            "latitude": 31.2304 + sequence / 100_000,
            "longitude": 121.4737,
            "altitudeMeters": 12.5,
            "horizontalAccuracyMeters": 2.0,
            "verticalAccuracyMeters": 3.0,
            "speedMetersPerSecond": 1.5,
            "speedAccuracyMetersPerSecond": 0.2,
            "bearingDegrees": 45.0,
            "bearingAccuracyDegrees": 3.0,
            "satellitesUsed": 12,
            "satellitesVisible": 18,
            "pdop": 1.2,
            "hdop": 0.8,
            "vdop": 1.0,
            "correctionAgeSeconds": None,
            "correctionStationId": None,
            "provider": "gps",
            "isMock": False,
        }

    def device_status(
        self,
        message_id: str = "status-message-1",
        device_id: str = "device-1",
        occurred_at: int = 1_786_000_000_000,
        status_sequence: int | None = 1,
    ) -> dict[str, object]:
        return {
            "schemaVersion": 1,
            "messageId": message_id,
            "deviceId": device_id,
            "statusSequence": status_sequence,
            "personId": "person-1",
            "occurredAtEpochMillis": occurred_at,
            "operationalState": "IDLE",
            "networkState": "AVAILABLE",
            "hardwareMode": "UART",
            "appVersion": "0.2.0",
            "cameraAvailable": True,
            "simulated": False,
            "activeCallId": None,
            "time": {
                "source": "SYSTEM",
                "synchronized": False,
                "uncertaintyMillis": None,
                "calibrationAgeMillis": None,
                "calibrationSequence": None,
            },
            "battery": {
                "present": True,
                "percent": 68,
                "voltageMillivolts": 3_920,
            },
            "location": {
                "latitude": 31.2304,
                "longitude": 121.4737,
                "horizontalAccuracyMeters": 2.0,
                "fixType": "STANDARD",
                "occurredAtEpochMillis": occurred_at,
                "isMock": False,
            },
            "rtk": {
                "state": "STREAMING",
                "correctionFrames": 12,
                "correctionBytes": 4_096,
                "lastFixQuality": "RTK_FIXED",
                "lastError": None,
            },
            "localIntercom": {
                "state": "READY",
                "peerCount": 3,
                "codec": "VENDOR_NARROWBAND",
                "rssiDbm": -98,
                "packetLossPermille": 15,
                "oneWayLatencyMillis": 145,
                "faultCode": 0,
                "lastError": None,
            },
        }

    def safety_alert(
        self,
        message_id: str = "alert-message-1",
        active: bool = True,
        occurred_at: int = 1_786_000_000_000,
    ) -> dict[str, object]:
        return {
            "messageId": message_id,
            "alertId": "alert-1",
            "deviceId": "device-1",
            "type": "NEAR_ELECTRIC",
            "severity": "CRITICAL",
            "active": active,
            "configVersion": 3,
            "sampleReference": 77,
            "monotonicMillis": 12_345,
            "occurredAtEpochMillis": occurred_at,
            "localActions": 7,
            "sensorFaults": 0,
            "simulated": False,
            "sensorSnapshot": {
                "electricFieldMilliVolts": 920,
                "electricFieldBaselineMilliVolts": 100,
                "electricFieldExposureMilliVoltMillis": 82_000,
            },
            "location": {
                "latitude": 31.2304,
                "longitude": 121.4737,
                "horizontalAccuracyMeters": 2.0,
                "fixType": "STANDARD",
            },
            "evidence": {"mediaAssetId": "photo-alert-1"},
        }

    def test_resumable_integrity_checked_and_idempotent_archive(self) -> None:
        payload = bytes((index % 251 for index in range(150_000)))
        metadata = self.metadata("media-1", payload)

        status, session = self.post_json("/v1/media/sessions", metadata)
        self.assertEqual(200, status)
        self.assertEqual(0, session["nextOffset"])
        session_id = session["sessionId"]

        offset = 0
        first_chunk = payload[: 64 * 1024]
        headers = {
            "Content-Type": "application/octet-stream",
            "Content-Range": f"bytes 0-{len(first_chunk) - 1}/{len(payload)}",
            "X-Chunk-SHA256": hashlib.sha256(first_chunk).hexdigest(),
        }
        status, body, _ = self.request(
            "PUT", f"/v1/media/sessions/{session_id}/chunks", first_chunk, headers
        )
        self.assertEqual(200, status)
        offset = json.loads(body)["nextOffset"]
        self.assertEqual(len(first_chunk), offset)

        status, body, _ = self.request(
            "PUT", f"/v1/media/sessions/{session_id}/chunks", first_chunk, headers
        )
        self.assertEqual(200, status)
        self.assertEqual(offset, json.loads(body)["nextOffset"])

        while offset < len(payload):
            chunk = payload[offset : offset + 64 * 1024]
            headers = {
                "Content-Type": "application/octet-stream",
                "Content-Range": f"bytes {offset}-{offset + len(chunk) - 1}/{len(payload)}",
                "X-Chunk-SHA256": hashlib.sha256(chunk).hexdigest(),
            }
            status, body, _ = self.request(
                "PUT", f"/v1/media/sessions/{session_id}/chunks", chunk, headers
            )
            self.assertEqual(200, status)
            offset = json.loads(body)["nextOffset"]

        status, completed = self.post_json(
            f"/v1/media/sessions/{session_id}/complete",
            {"mediaId": "media-1", "byteSize": len(payload), "sha256": metadata["sha256"]},
        )
        self.assertEqual(200, status)
        self.assertEqual("COMPLETED", completed["status"])

        status, resumed = self.post_json("/v1/media/sessions", metadata)
        self.assertEqual(200, status)
        self.assertEqual("COMPLETED", resumed["status"])
        self.assertEqual(len(payload), resumed["nextOffset"])

        status, content, headers = self.request("GET", "/v1/media/media-1/content")
        self.assertEqual(200, status)
        self.assertEqual(payload, content)
        self.assertEqual(f'"{metadata["sha256"]}"', headers.get("ETag") or headers.get("Etag"))
        self.assertEqual("private, no-store", headers["Cache-Control"])

        second = self.metadata("media-2", payload)
        status, deduplicated = self.post_json("/v1/media/sessions", second)
        self.assertEqual(200, status)
        self.assertEqual("COMPLETED", deduplicated["status"])
        self.assertTrue(deduplicated["deduplicated"])
        photo = {
            **self.metadata("media-3", payload),
            "kind": "PHOTO",
            "mimeType": "image/jpeg",
        }
        self.assertEqual(200, self.post_json("/v1/media/sessions", photo)[0])
        operator_headers = {"X-Actor-Id": "viewer-media", "X-Actor-Role": "VIEWER"}
        status, body, _ = self.request("GET", "/v1/media?limit=100", headers=operator_headers)
        self.assertEqual(200, status)
        media = json.loads(body)["media"]
        self.assertEqual(["media-3", "media-2", "media-1"], [item["mediaId"] for item in media])
        self.assertEqual("COMPLETED", media[0]["status"])
        self.assertEqual("/v1/media/media-3/content", media[0]["contentPath"])
        status, body, _ = self.request(
            "GET", "/v1/media?kind=PHOTO&deviceId=device-1", headers=operator_headers,
        )
        self.assertEqual(200, status)
        self.assertEqual(["media-3"], [item["mediaId"] for item in json.loads(body)["media"]])
        self.assertEqual(400, self.request("GET", "/v1/media?kind=VOICE", headers=operator_headers)[0])
        self.assertEqual(400, self.request("GET", "/v1/media?limit=101", headers=operator_headers)[0])
        self.assertEqual(403, self.request("GET", "/v1/media")[0])
        objects = list((Path(self.temporary.name) / "objects").glob("*/*.bin"))
        self.assertEqual(1, len(objects))

        objects[0].write_bytes(b"corrupt")
        status, body, _ = self.request("GET", "/v1/media/media-1/content")
        self.assertEqual(503, status)
        self.assertIn("object storage read failed", json.loads(body)["error"])

        s3_client = ReadOnlyS3Client()
        s3_store = S3ObjectStore(
            "https://s3.example.test",
            "helmet-media",
            "region-1",
            client=s3_client,
        )
        digest = str(metadata["sha256"])
        locator = s3_store.locator_for(digest)
        key = locator.split("/", 3)[3]
        corrupt_s3_body = bytes([payload[0] ^ 1]) + payload[1:]
        s3_client.objects[("helmet-media", key)] = (
            corrupt_s3_body,
            {"sha256": digest},
        )
        with self.repository._connect() as database:
            database.execute(
                "UPDATE media_objects SET object_path = ? WHERE sha256 = ?",
                (locator, digest),
            )
        self.repository.object_store = s3_store
        status, body, _ = self.request("GET", "/v1/media/media-1/content")
        self.assertEqual(503, status)
        self.assertIn("object storage read failed", json.loads(body)["error"])

    def test_auth_collision_and_chunk_digest_fail_closed(self) -> None:
        status, _, _ = self.request("GET", "/v1/media/unknown", authorize=False)
        self.assertEqual(401, status)

        payload = b"helmet" * 20_000
        metadata = self.metadata("media-conflict", payload)
        status, session = self.post_json("/v1/media/sessions", metadata)
        self.assertEqual(200, status)

        conflict = dict(metadata)
        conflict["sha256"] = "f" * 64
        status, response = self.post_json("/v1/media/sessions", conflict)
        self.assertEqual(409, status)
        self.assertIn("different", response["error"])

        chunk = payload[: 64 * 1024]
        status, body, _ = self.request(
            "PUT",
            f"/v1/media/sessions/{session['sessionId']}/chunks",
            chunk,
            {
                "Content-Type": "application/octet-stream",
                "Content-Range": f"bytes 0-{len(chunk) - 1}/{len(payload)}",
                "X-Chunk-SHA256": "0" * 64,
            },
        )
        self.assertEqual(422, status)
        self.assertIn("mismatch", json.loads(body)["error"])

    def test_dashboard_assets_are_same_origin_and_hardened(self) -> None:
        status, body, headers = self.request("GET", "/dashboard", authorize=False)
        self.assertEqual(200, status)
        self.assertIn("安全帽运行台", body.decode())
        self.assertIn("权限中心", body.decode())
        self.assertIn("媒体归档", body.decode())
        self.assertIn("文字广播", body.decode())
        self.assertIn("语音消息", body.decode())
        self.assertIn("default-src 'self'", headers["Content-Security-Policy"])
        self.assertIn("img-src 'self' blob: data: https:", headers["Content-Security-Policy"])
        self.assertIn("media-src 'self' blob:", headers["Content-Security-Policy"])
        self.assertEqual("nosniff", headers["X-Content-Type-Options"])
        status, script, _ = self.request("GET", "/dashboard/app.js", authorize=False)
        self.assertEqual(200, status)
        self.assertIn(b"/v1/alerts", script)
        self.assertIn(b"/v1/calls", script)
        self.assertIn(b"/v1/broadcasts?", script)
        self.assertIn(b"CONTROL_BROADCASTS", script)
        self.assertIn(b"/v1/voice-messages?", script)
        self.assertIn(b"PLAY_VOICE_MESSAGES", script)
        self.assertIn(b"MAX_VOICE_PREVIEW_BYTES", script)
        self.assertIn(b"/v1/media?", script)
        self.assertIn("查看现场证据".encode(), script)
        self.assertIn(b"/v1/media/${mediaId}", script)
        self.assertIn(b"MAX_MEDIA_PREVIEW_BYTES", script)
        self.assertIn(b"URL.createObjectURL", script)
        self.assertIn("服务器校时".encode(), script)
        self.assertIn("时间来源未上报".encode(), script)
        self.assertIn(b"/v1/access-profile", script)
        self.assertIn(b"AUTO_REFRESH_INTERVAL_MILLIS = 5_000", script)
        self.assertIn(b"scheduleAutoRefresh()", script)
        self.assertIn(b'addEventListener("visibilitychange"', script)
        self.assertIn(b"VIEW_SECURITY_AUDIT", script)
        self.assertIn(b"resetAccessIdentity(true)", script)
        self.assertIn(b"state.devices = []", script)
        self.assertIn(b"state.broadcasts = []", script)
        self.assertIn(b"state.voiceMessages = []", script)
        self.assertIn(b"state.media = []", script)
        self.assertNotIn(b"localStorage", script)

    def test_development_access_profile_requires_actor_and_reports_capabilities(self) -> None:
        status, body, _ = self.request("GET", "/v1/access-profile")
        self.assertEqual(403, status)
        self.assertIn("call access is forbidden", json.loads(body)["error"])

        status, body, _ = self.request(
            "GET",
            "/v1/access-profile",
            headers={"X-Actor-Id": "viewer-dev", "X-Actor-Role": "VIEWER"},
        )
        self.assertEqual(200, status)
        profile = json.loads(body)
        self.assertEqual("DEVELOPMENT_SINGLE_TOKEN", profile["mode"])
        self.assertEqual("viewer-dev", profile["principalId"])
        self.assertEqual("VIEWER", profile["role"])
        self.assertIsNone(profile["organizationId"])
        self.assertEqual({"kind": "ALL", "deviceIds": []}, profile["deviceScope"])
        self.assertEqual(["READ_OPERATIONS"], profile["capabilities"])
        self.assertIsNone(profile["directory"])

        status, body, _ = self.request(
            "GET",
            "/v1/access-profile",
            headers={"X-Actor-Id": "dispatcher-dev", "X-Actor-Role": "DISPATCHER"},
        )
        self.assertEqual(200, status)
        dispatcher_capabilities = json.loads(body)["capabilities"]
        self.assertIn("CONTROL_BROADCASTS", dispatcher_capabilities)
        self.assertIn("PLAY_VOICE_MESSAGES", dispatcher_capabilities)

    def test_readiness_checks_persistence_without_authorization(self) -> None:
        status, body, _ = self.request("GET", "/ready", authorize=False)
        self.assertEqual(200, status)
        self.assertEqual({"status": "ready"}, json.loads(body))

        original_ready = self.repository.object_store.ready

        def unavailable() -> None:
            raise ObjectStoreError("unavailable")

        self.repository.object_store.ready = unavailable
        try:
            status, body, _ = self.request("GET", "/ready", authorize=False)
            self.assertEqual(503, status)
            self.assertEqual({"status": "not_ready"}, json.loads(body))
        finally:
            self.repository.object_store.ready = original_ready

        original_checks = self.server.readiness_checks

        def messaging_unavailable() -> None:
            raise RuntimeError("MQTT unavailable")

        self.server.readiness_checks = (messaging_unavailable,)
        try:
            status, body, _ = self.request("GET", "/ready", authorize=False)
            self.assertEqual(503, status)
            self.assertEqual({"status": "not_ready"}, json.loads(body))
        finally:
            self.server.readiness_checks = original_checks

    def test_device_status_overview_combines_position_battery_alert_and_live_video(self) -> None:
        status, created = self.post_json("/v1/device-status", self.device_status())
        self.assertEqual(200, status)
        self.assertFalse(created["deduplicated"])
        self.assertGreater(created["serverReceivedAtEpochMillis"], 0)
        repeated = self.post_json("/v1/device-status", self.device_status())[1]
        self.assertTrue(repeated["deduplicated"])
        self.assertEqual(
            created["serverReceivedAtEpochMillis"], repeated["serverReceivedAtEpochMillis"],
        )

        stale = self.device_status("status-message-stale", occurred_at=1_785_999_999_999)
        self.assertEqual(409, self.post_json("/v1/device-status", stale)[0])
        invalid_battery = self.device_status("status-message-invalid")
        invalid_battery["battery"] = {
            "present": False,
            "percent": 70,
            "voltageMillivolts": None,
        }
        self.assertEqual(400, self.post_json("/v1/device-status", invalid_battery)[0])

        point = self.track_point("overview-track", 1)
        self.assertEqual(200, self.post_json("/v1/tracks:batch", {"points": [point]})[0])
        alert = self.safety_alert(message_id="overview-alert")
        self.assertEqual(200, self.post_json("/v1/alerts", alert)[0])
        call = {
            "callId": "overview-video-call",
            "deviceId": "device-1",
            "direction": "OUTGOING_DEVICE",
            "mediaMode": "VIDEO_UPLINK",
            "state": "REQUESTED",
            "stateSequence": 1,
            "relatedEventId": "overview-alert",
            "simulated": False,
            "createdAtEpochMillis": 1_786_000_000_010,
        }
        self.assertEqual(200, self.post_json("/v1/calls", call)[0])
        self.assertEqual(
            200,
            self.post_json(
                "/v1/calls/overview-video-call/signals",
                {
                    "signalId": "overview-offer",
                    "senderId": "device-1",
                    "type": "OFFER",
                    "payload": {"sdp": "v=0\r\n"},
                    "createdAtEpochMillis": 1_786_000_000_011,
                },
            )[0],
        )
        actor_headers = {"X-Actor-Id": "viewer-overview", "X-Actor-Role": "VIEWER"}
        response_status, body, _ = self.request(
            "GET", "/v1/devices/overview", headers=actor_headers,
        )
        self.assertEqual(200, response_status)
        devices = json.loads(body)["devices"]
        self.assertEqual(1, len(devices))
        overview = devices[0]
        self.assertEqual("device-1", overview["deviceId"])
        self.assertEqual("person-1", overview["personId"])
        self.assertTrue(overview["battery"]["reported"])
        self.assertEqual(68, overview["battery"]["percent"])
        self.assertEqual(3_920, overview["battery"]["voltageMillivolts"])
        self.assertEqual("STREAMING", overview["rtk"]["state"])
        self.assertEqual(12, overview["rtk"]["correctionFrames"])
        self.assertEqual("READY", overview["localIntercom"]["state"])
        self.assertEqual(15, overview["localIntercom"]["packetLossPermille"])
        self.assertEqual(created["serverReceivedAtEpochMillis"], overview["lastContactAtEpochMillis"])
        self.assertTrue(overview["clock"]["reported"])
        self.assertEqual("SYSTEM", overview["clock"]["source"])
        self.assertFalse(overview["clock"]["synchronized"])
        self.assertEqual(1, overview["statusSequence"])
        self.assertEqual(
            1_786_000_000_000 - created["serverReceivedAtEpochMillis"],
            overview["clock"]["observedOffsetMillis"],
        )
        self.assertTrue(overview["clock"]["includesTransportDelay"])
        self.assertEqual("TRACK", overview["location"]["source"])
        self.assertEqual(1, overview["activeAlertCount"])
        self.assertEqual("overview-video-call", overview["liveVideo"]["callId"])
        self.assertTrue(overview["liveVideo"]["hasOffer"])
        self.assertEqual(1, len(overview["track"]))

        invalid_intercom = self.device_status("status-message-invalid-intercom", occurred_at=1_786_000_000_100)
        invalid_intercom["localIntercom"]["packetLossPermille"] = 1_001
        self.assertEqual(400, self.post_json("/v1/device-status", invalid_intercom)[0])

    def test_legacy_device_status_does_not_claim_unreported_rtk_or_intercom(self) -> None:
        legacy = self.device_status("status-message-legacy")
        del legacy["statusSequence"]
        del legacy["time"]
        del legacy["rtk"]
        del legacy["localIntercom"]
        response_status, receipt = self.post_json("/v1/device-status", legacy)
        self.assertEqual(200, response_status)

        actor_headers = {"X-Actor-Id": "viewer-legacy", "X-Actor-Role": "VIEWER"}
        response_status, body, _ = self.request(
            "GET", "/v1/devices/overview", headers=actor_headers,
        )
        self.assertEqual(200, response_status)
        overview = json.loads(body)["devices"][0]
        self.assertEqual("UNREPORTED", overview["rtk"]["state"])
        self.assertFalse(overview["rtk"]["reported"])
        self.assertEqual("UNREPORTED", overview["localIntercom"]["state"])
        self.assertFalse(overview["localIntercom"]["reported"])
        self.assertEqual("UNREPORTED", overview["clock"]["source"])
        self.assertFalse(overview["clock"]["synchronized"])
        self.assertEqual(receipt["serverReceivedAtEpochMillis"], overview["lastContactAtEpochMillis"])

    def test_device_status_sequence_accepts_backward_clock_correction(self) -> None:
        future = self.device_status(
            "status-before-calibration",
            occurred_at=1_786_086_400_000,
            status_sequence=1,
        )
        self.assertEqual(200, self.post_json("/v1/device-status", future)[0])

        corrected = self.device_status(
            "status-after-calibration",
            occurred_at=1_786_000_000_000,
            status_sequence=2,
        )
        corrected["time"] = {
            "source": "SERVER",
            "synchronized": True,
            "uncertaintyMillis": 25,
            "calibrationAgeMillis": 1_000,
            "calibrationSequence": 1,
        }
        self.assertEqual(200, self.post_json("/v1/device-status", corrected)[0])

        repeated_sequence = self.device_status(
            "status-repeated-sequence",
            occurred_at=1_786_000_000_100,
            status_sequence=2,
        )
        self.assertEqual(409, self.post_json("/v1/device-status", repeated_sequence)[0])

        actor_headers = {"X-Actor-Id": "viewer-time", "X-Actor-Role": "VIEWER"}
        response_status, body, _ = self.request(
            "GET", "/v1/devices/overview", headers=actor_headers,
        )
        self.assertEqual(200, response_status)
        overview = json.loads(body)["devices"][0]
        self.assertEqual(2, overview["statusSequence"])
        self.assertEqual(1_786_000_000_000, overview["lastSeenAtEpochMillis"])
        self.assertEqual("SERVER", overview["clock"]["source"])
        self.assertTrue(overview["clock"]["synchronized"])
        self.assertEqual(25, overview["clock"]["uncertaintyMillis"])

    def test_device_status_rejects_inconsistent_time_provenance(self) -> None:
        invalid = self.device_status("status-invalid-time")
        invalid["time"] = {
            "source": "SERVER",
            "synchronized": False,
            "uncertaintyMillis": 10,
            "calibrationAgeMillis": 0,
            "calibrationSequence": 1,
        }
        self.assertEqual(400, self.post_json("/v1/device-status", invalid)[0])

        oversized_sequence = self.device_status(
            "status-oversized-sequence",
            status_sequence=9_223_372_036_854_775_808,
        )
        self.assertEqual(400, self.post_json("/v1/device-status", oversized_sequence)[0])

    def test_dashboard_map_configuration_is_validated_and_role_protected(self) -> None:
        actor_headers = {"X-Actor-Id": "viewer-map", "X-Actor-Role": "VIEWER"}
        status, body, _ = self.request("GET", "/v1/dashboard-config", headers=actor_headers)
        self.assertEqual(200, status)
        self.assertFalse(json.loads(body)["map"]["configured"])
        with self.assertRaisesRegex(ValueError, "must contain"):
            MediaHttpServer(
                ("127.0.0.1", 0), self.repository, TOKEN,
                map_tile_url_template="https://tiles.example.test/no-placeholders.png",
            )
        configured_server = MediaHttpServer(
            ("127.0.0.1", 0),
            self.repository,
            "configured-map-token",
            map_tile_url_template="https://tiles.example.test/{z}/{x}/{y}.png",
            map_attribution="Example map provider",
            map_min_zoom=4,
            map_max_zoom=18,
        )
        configured_thread = threading.Thread(target=configured_server.serve_forever, daemon=True)
        configured_thread.start()
        try:
            request_value = Request(
                f"http://127.0.0.1:{configured_server.server_port}/v1/dashboard-config",
                headers={
                    "Authorization": "Bearer configured-map-token",
                    "X-Actor-Id": "viewer-map",
                    "X-Actor-Role": "VIEWER",
                },
            )
            with urlopen(request_value, timeout=5) as response:
                configuration = json.loads(response.read())["map"]
            self.assertTrue(configuration["configured"])
            self.assertEqual("https://tiles.example.test/{z}/{x}/{y}.png", configuration["tileUrlTemplate"])
            self.assertEqual("Example map provider", configuration["attribution"])
            self.assertEqual(4, configuration["minimumZoom"])
            self.assertEqual(18, configuration["maximumZoom"])
        finally:
            configured_server.shutdown()
            configured_server.server_close()
            configured_thread.join(timeout=2)

    def test_partial_offset_survives_repository_restart(self) -> None:
        payload = b"restart-safe-media" * 10_000
        metadata = self.metadata("media-restart", payload)
        session = self.repository.create_or_resume(metadata)
        chunk = payload[: 64 * 1024]
        response = self.repository.append_chunk(
            session["sessionId"],
            0,
            len(chunk) - 1,
            len(payload),
            chunk,
            hashlib.sha256(chunk).hexdigest(),
        )
        self.assertEqual(len(chunk), response["nextOffset"])

        reopened = MediaRepository(Path(self.temporary.name), chunk_size=64 * 1024)
        resumed = reopened.create_or_resume(metadata)
        self.assertEqual(session["sessionId"], resumed["sessionId"])
        self.assertEqual(len(chunk), resumed["nextOffset"])

    def test_completion_recovers_when_object_commit_precedes_database_commit(self) -> None:
        payload = b"database-commit-recovery" * 1_000
        metadata = self.metadata("media-object-first", payload)
        session = self.repository.create_or_resume(metadata)
        self.repository.append_chunk(
            session["sessionId"],
            0,
            len(payload) - 1,
            len(payload),
            payload,
            hashlib.sha256(payload).hexdigest(),
        )
        first = self.repository.complete(
            session["sessionId"],
            {
                "mediaId": metadata["mediaId"],
                "byteSize": len(payload),
                "sha256": metadata["sha256"],
            },
        )
        self.assertFalse(first["deduplicated"])

        with self.repository._connect() as database:
            database.execute("DELETE FROM media_archives WHERE media_id = ?", (metadata["mediaId"],))
            database.execute("DELETE FROM media_objects WHERE sha256 = ?", (metadata["sha256"],))
            database.execute(
                "UPDATE upload_sessions SET status = 'UPLOADING', archive_id = NULL, "
                "deduplicated = 0 WHERE session_id = ?",
                (session["sessionId"],),
            )

        recovered = self.repository.complete(
            session["sessionId"],
            {
                "mediaId": metadata["mediaId"],
                "byteSize": len(payload),
                "sha256": metadata["sha256"],
            },
        )
        self.assertEqual("COMPLETED", recovered["status"])
        self.assertTrue(recovered["deduplicated"])

    def test_track_batch_is_ordered_persistent_and_idempotent(self) -> None:
        points = [self.track_point("track-1", 1), self.track_point("track-2", 2)]
        status, response = self.post_json("/v1/tracks:batch", {"points": points})
        self.assertEqual(200, status)
        self.assertEqual(["track-1", "track-2"], response["acceptedMessageIds"])
        self.assertEqual([], response["duplicateMessageIds"])

        status, duplicate = self.post_json("/v1/tracks:batch", {"points": points})
        self.assertEqual(200, status)
        self.assertEqual([], duplicate["acceptedMessageIds"])
        self.assertEqual(["track-1", "track-2"], duplicate["duplicateMessageIds"])

        status, body, _ = self.request("GET", "/v1/tracks?deviceId=device-1&afterSequence=0")
        self.assertEqual(200, status)
        archived = json.loads(body)["points"]
        self.assertEqual([1, 2], [point["sequence"] for point in archived])
        status, _, _ = self.request(
            "GET", f"/v1/tracks?deviceId=device-1&afterSequence={2 ** 63}",
        )
        self.assertEqual(400, status)

        reopened = MediaRepository(Path(self.temporary.name), chunk_size=64 * 1024)
        self.assertEqual([1, 2], [point["sequence"] for point in reopened.tracks("device-1")])

    def test_persisted_integer_and_numeric_overflow_are_rejected(self) -> None:
        overflow = 2 ** 63
        payload = b"integer-boundary-media"
        metadata = self.metadata("media-integer-overflow", payload)
        metadata["createdAtEpochMillis"] = overflow
        self.assertEqual(400, self.post_json("/v1/media/sessions", metadata)[0])

        track = self.track_point("track-integer-overflow", 1)
        track["occurredAtEpochMillis"] = overflow
        self.assertEqual(400, self.post_json("/v1/tracks:batch", {"points": [track]})[0])
        track["occurredAtEpochMillis"] = 1_786_000_000_000
        track["latitude"] = 10 ** 1_000
        self.assertEqual(400, self.post_json("/v1/tracks:batch", {"points": [track]})[0])

        self.assertEqual(
            400,
            self.post_json(
                "/v1/alerts",
                self.safety_alert(message_id="alert-integer-overflow", occurred_at=overflow),
            )[0],
        )
        call = {
            "callId": "call-integer-overflow",
            "deviceId": "device-1",
            "direction": "OUTGOING_DEVICE",
            "mediaMode": "AUDIO",
            "state": "REQUESTED",
            "stateSequence": 1,
            "relatedEventId": None,
            "simulated": False,
            "createdAtEpochMillis": overflow,
        }
        self.assertEqual(400, self.post_json("/v1/calls", call)[0])
        broadcast = {
            "broadcastId": "broadcast-integer-overflow",
            "deviceId": "device-1",
            "text": "integer boundary",
            "language": "en-US",
            "priority": 5,
            "expiresAtEpochMillis": overflow,
            "createdAtEpochMillis": 1_786_000_000_000,
        }
        self.assertEqual(400, self.post_json(
            "/v1/broadcasts",
            broadcast,
            headers={"X-Actor-Id": "dispatcher-overflow", "X-Actor-Role": "DISPATCHER"},
        )[0])
    def test_json_and_http_framing_ambiguity_are_rejected(self) -> None:
        status = self.device_status("status-duplicate-json")
        encoded = json.dumps(status).encode()
        duplicate = encoded.replace(
            b'"schemaVersion": 1',
            b'"schemaVersion": 1, "schemaVersion": 1',
            1,
        )
        response_status, body, _ = self.request(
            "POST",
            "/v1/device-status",
            duplicate,
            {"Content-Type": "application/json"},
        )
        self.assertEqual(400, response_status)
        self.assertEqual("invalid JSON", json.loads(body)["error"])

        non_standard = self.device_status("status-non-standard-json")
        non_standard["unexpected"] = float("nan")
        self.assertEqual(
            400,
            self.request(
                "POST",
                "/v1/device-status",
                json.dumps(non_standard).encode(),
                {"Content-Type": "application/json"},
            )[0],
        )

        nested: object = 1
        for _ in range(65):
            nested = [nested]
        too_deep = self.device_status("status-deep-json")
        too_deep["unexpected"] = nested
        self.assertEqual(
            400,
            self.request(
                "POST",
                "/v1/device-status",
                json.dumps(too_deep).encode(),
                {"Content-Type": "application/json"},
            )[0],
        )

        auth_path = Path(self.temporary.name) / "ambiguous-auth.json"
        auth_path.write_text(
            '{"schemaVersion":1,"schemaVersion":1,"principals":[]}',
            encoding="utf-8",
        )
        os.chmod(auth_path, 0o600)
        with self.assertRaisesRegex(ValueError, "not valid JSON"):
            load_auth_configuration(auth_path)

        def framed_request(headers: list[tuple[str, str]]) -> int:
            connection = HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
            try:
                connection.putrequest("POST", "/v1/device-status")
                connection.putheader("Authorization", f"Bearer {TOKEN}")
                connection.putheader("Content-Type", "application/json")
                for name, value in headers:
                    connection.putheader(name, value)
                connection.endheaders(b"{}")
                return connection.getresponse().status
            finally:
                connection.close()

        self.assertEqual(
            400,
            framed_request([("Content-Length", "2"), ("Content-Length", "2")]),
        )
        self.assertEqual(
            400,
            framed_request([("Content-Length", "2"), ("Transfer-Encoding", "chunked")]),
        )

    def test_track_batch_rejects_mock_out_of_order_and_collisions(self) -> None:
        mock = self.track_point("mock-track", 1)
        mock["isMock"] = True
        status, response = self.post_json("/v1/tracks:batch", {"points": [mock]})
        self.assertEqual(400, status)
        self.assertIn("mock", response["error"])

        status, response = self.post_json(
            "/v1/tracks:batch",
            {"points": [self.track_point("track-2", 2), self.track_point("track-1", 1)]},
        )
        self.assertEqual(400, status)
        self.assertIn("ordered", response["error"])

        status, _ = self.post_json("/v1/tracks:batch", {"points": [self.track_point("track-1", 1)]})
        self.assertEqual(200, status)
        collision = self.track_point("track-other", 1)
        status, response = self.post_json("/v1/tracks:batch", {"points": [collision]})
        self.assertEqual(409, status)
        self.assertIn("sequence", response["error"])

        changed = self.track_point("track-1", 1)
        changed["latitude"] = 30.0
        status, response = self.post_json("/v1/tracks:batch", {"points": [changed]})
        self.assertEqual(409, status)
        self.assertIn("different", response["error"])

    def test_call_lifecycle_commands_ack_and_audit_are_idempotent(self) -> None:
        call = {
            "callId": "call-1",
            "deviceId": "device-1",
            "direction": "OUTGOING_DEVICE",
            "mediaMode": "VIDEO_UPLINK",
            "state": "REQUESTED",
            "stateSequence": 1,
            "relatedEventId": "event-1",
            "simulated": True,
            "createdAtEpochMillis": 1_786_000_000_000,
        }
        status, created = self.post_json("/v1/calls", call)
        self.assertEqual(200, status)
        self.assertFalse(created["deduplicated"])
        status, repeated = self.post_json("/v1/calls", call)
        self.assertEqual(200, status)
        self.assertTrue(repeated["deduplicated"])
        operator_headers = {
            "X-Actor-Id": "dispatcher-1",
            "X-Actor-Role": "DISPATCHER",
        }
        status, body, _ = self.request("GET", "/v1/calls?limit=10", headers=operator_headers)
        self.assertEqual(200, status)
        self.assertEqual(["call-1"], [item["callId"] for item in json.loads(body)["calls"]])

        transition = {
            "state": "RINGING",
            "actorId": "dispatcher-1",
            "occurredAtEpochMillis": 1_786_000_001_000,
            "reason": None,
        }
        status, ringing = self.post_json("/v1/calls/call-1/transitions", transition)
        self.assertEqual(200, status)
        self.assertEqual("RINGING", ringing["state"])
        self.assertEqual(2, ringing["stateSequence"])
        status, repeated_transition = self.post_json("/v1/calls/call-1/transitions", transition)
        self.assertEqual(200, status)
        self.assertTrue(repeated_transition["deduplicated"])
        status, body, _ = self.request(
            "POST",
            "/v1/calls/call-1/transitions",
            json.dumps(
                {
                    "state": "ACCEPTED",
                    "actorId": "viewer-1",
                    "occurredAtEpochMillis": 1_786_000_001_050,
                    "reason": "VIEWER_MUST_NOT_CONTROL",
                },
            ).encode(),
            {
                "Content-Type": "application/json",
                "X-Actor-Id": "viewer-1",
                "X-Actor-Role": "VIEWER",
            },
        )
        self.assertEqual(403, status)
        self.assertIn("forbidden", json.loads(body)["error"])

        status, body, _ = self.request(
            "GET", "/v1/device-commands?deviceId=device-1&afterSequence=0"
        )
        self.assertEqual(200, status)
        commands = json.loads(body)["commands"]
        self.assertEqual([1], [command["sequence"] for command in commands])
        self.assertEqual("CALL_STATE", commands[0]["type"])
        self.assertEqual(
            [commands[0]["commandId"]],
            [
                command["commandId"]
                for command in self.repository.pending_device_commands_for_device(
                    "device-1", 0
                )
            ],
        )
        self.assertEqual(
            [],
            self.repository.pending_device_commands_for_device("device-1", 1),
        )
        ack = {"status": "APPLIED", "occurredAtEpochMillis": 1_786_000_001_100}
        status, response = self.post_json(
            f"/v1/device-commands/{commands[0]['commandId']}/ack", ack
        )
        self.assertEqual(200, status)
        self.assertFalse(response["deduplicated"])
        status, response = self.post_json(
            f"/v1/device-commands/{commands[0]['commandId']}/ack", ack
        )
        self.assertEqual(200, status)
        self.assertTrue(response["deduplicated"])
        self.assertEqual(
            [],
            self.repository.pending_device_commands_for_device("device-1", 0),
        )

        status, body, _ = self.request("GET", "/v1/calls/call-1")
        self.assertEqual(200, status)
        history = json.loads(body)["history"]
        self.assertEqual(["REQUESTED", "RINGING"], [item["state"] for item in history])
        status, response = self.post_json(
            "/v1/calls/call-1/transitions",
            {
                "state": "REQUESTED",
                "actorId": "dispatcher-1",
                "occurredAtEpochMillis": 1_786_000_002_000,
                "reason": None,
            },
        )
        self.assertEqual(409, status)
        self.assertIn("transition", response["error"])

    def test_text_broadcast_command_and_playback_receipts_are_persistent(self) -> None:
        message = {
            "broadcastId": "broadcast-1",
            "deviceId": "device-1",
            "text": "请立即撤离",
            "language": "zh-CN",
            "priority": 10,
            "expiresAtEpochMillis": 1_786_000_060_000,
            "createdAtEpochMillis": 1_786_000_000_000,
        }
        control_headers = {"X-Actor-Id": "dispatcher-broadcast", "X-Actor-Role": "DISPATCHER"}
        status, created = self.post_json("/v1/broadcasts", message, headers=control_headers)
        self.assertEqual(200, status)
        self.assertEqual(1, created["commandSequence"])
        status, repeated = self.post_json("/v1/broadcasts", message, headers=control_headers)
        self.assertEqual(200, status)
        self.assertTrue(repeated["deduplicated"])

        status, body, _ = self.request(
            "GET", "/v1/device-commands?deviceId=device-1&afterSequence=0"
        )
        self.assertEqual(200, status)
        command = json.loads(body)["commands"][0]
        self.assertEqual("TEXT_BROADCAST", command["type"])
        self.assertEqual("请立即撤离", command["payload"]["text"])

        status, conflict = self.post_json(
            "/v1/broadcasts/broadcast-1/receipts",
            {"state": "RECEIVED", "occurredAtEpochMillis": 1_786_000_000_000, "error": None},
        )
        self.assertEqual(409, status)
        self.assertIn("creation", conflict["error"])
        status, conflict = self.post_json(
            "/v1/broadcasts/broadcast-1/receipts",
            {"state": "PLAYING", "occurredAtEpochMillis": 1_786_000_000_500, "error": None},
        )
        self.assertEqual(409, status)
        self.assertIn("start with RECEIVED", conflict["error"])

        received = {"state": "RECEIVED", "occurredAtEpochMillis": 1_786_000_001_000, "error": None}
        status, receipt = self.post_json("/v1/broadcasts/broadcast-1/receipts", received)
        self.assertEqual(200, status)
        self.assertFalse(receipt["deduplicated"])
        status, receipt = self.post_json("/v1/broadcasts/broadcast-1/receipts", received)
        self.assertEqual(200, status)
        self.assertTrue(receipt["deduplicated"])
        status, receipt = self.post_json(
            "/v1/broadcasts/broadcast-1/receipts",
            {"state": "FAILED", "occurredAtEpochMillis": 1_786_000_002_000, "error": "TTS_UNAVAILABLE"},
        )
        self.assertEqual(200, status)
        self.assertEqual("FAILED", receipt["state"])
        status, conflict = self.post_json(
            "/v1/broadcasts/broadcast-1/receipts",
            {"state": "PLAYING", "occurredAtEpochMillis": 1_786_000_003_000, "error": None},
        )
        self.assertEqual(409, status)
        self.assertIn("transition", conflict["error"])

        operator_headers = {"X-Actor-Id": "viewer-broadcast", "X-Actor-Role": "VIEWER"}
        status, body, _ = self.request("GET", "/v1/broadcasts?limit=100", headers=operator_headers)
        self.assertEqual(200, status)
        broadcasts = json.loads(body)["broadcasts"]
        self.assertEqual(["broadcast-1"], [item["broadcastId"] for item in broadcasts])
        self.assertEqual("请立即撤离", broadcasts[0]["text"])
        self.assertEqual("FAILED", broadcasts[0]["lastState"])
        self.assertEqual(
            ["RECEIVED", "FAILED"],
            [item["state"] for item in broadcasts[0]["receipts"]],
        )
        self.assertEqual(400, self.request(
            "GET", "/v1/broadcasts?limit=101", headers=operator_headers,
        )[0])
        self.assertEqual(403, self.request("GET", "/v1/broadcasts")[0])
        status, denied = self.post_json(
            "/v1/broadcasts",
            {**message, "broadcastId": "broadcast-viewer-denied"},
            headers=operator_headers,
        )
        self.assertEqual(403, status)
        self.assertIn("forbidden", denied["error"])

        reopened = MediaRepository(Path(self.temporary.name), chunk_size=64 * 1024)
        with reopened._connect() as database:
            rows = database.execute(
                "SELECT state FROM broadcast_receipts WHERE broadcast_id = ? ORDER BY occurred_at_epoch_millis",
                ("broadcast-1",),
            ).fetchall()
        self.assertEqual(["RECEIVED", "FAILED"], [row["state"] for row in rows])

    def test_device_command_page_crosses_bounded_database_chunks(self) -> None:
        command_count = MAX_COMMUNICATION_QUERY_ROWS + 2
        for index in range(command_count):
            self.repository.create_broadcast({
                "broadcastId": f"broadcast-page-{index}",
                "deviceId": "device-1",
                "text": f"message {index}",
                "language": "en-US",
                "priority": 5,
                "expiresAtEpochMillis": 1_786_000_060_000 + index,
                "createdAtEpochMillis": 1_786_000_000_000 + index,
            })

        status, body, headers = self.request(
            "GET", "/v1/device-commands?deviceId=device-1&afterSequence=0&limit=100",
        )
        self.assertEqual(200, status)
        self.assertLessEqual(len(body), MAX_COMMUNICATION_RESPONSE_BYTES)
        self.assertEqual(len(body), int(headers["Content-Length"]))
        commands = json.loads(body)["commands"]
        self.assertEqual(
            list(range(1, command_count + 1)),
            [command["sequence"] for command in commands],
        )
        status, _, _ = self.request(
            "GET", f"/v1/device-commands?deviceId=device-1&afterSequence={2 ** 63}",
        )
        self.assertEqual(400, status)

    def test_webrtc_signals_are_ordered_idempotent_and_use_short_lived_turn_credentials(self) -> None:
        call = {
            "callId": "call-webrtc",
            "deviceId": "device-1",
            "direction": "OUTGOING_DEVICE",
            "mediaMode": "VIDEO_UPLINK",
            "state": "REQUESTED",
            "stateSequence": 1,
            "relatedEventId": None,
            "simulated": False,
            "createdAtEpochMillis": 1_786_000_000_000,
        }
        self.assertEqual(200, self.post_json("/v1/calls", call)[0])
        status, body, _ = self.request(
            "GET", "/v1/calls/call-webrtc/ice-config?requesterId=device-1"
        )
        self.assertEqual(200, status)
        ice = json.loads(body)
        self.assertEqual(600_000, ice["expiresAtEpochMillis"] - ice["issuedAtEpochMillis"])
        self.assertEqual(2, len(ice["iceServers"]))
        self.assertTrue(ice["iceServers"][1]["username"].endswith(":call-webrtc:device-1"))
        self.assertTrue(ice["iceServers"][1]["credential"])

        offer = {
            "signalId": "signal-offer",
            "senderId": "device-1",
            "type": "OFFER",
            "payload": {"sdp": "v=0\r\na=group:BUNDLE 0\r\n"},
            "createdAtEpochMillis": 1_786_000_001_000,
        }
        status, response = self.post_json("/v1/calls/call-webrtc/signals", offer)
        self.assertEqual(200, status)
        self.assertEqual(1, response["sequence"])
        status, response = self.post_json("/v1/calls/call-webrtc/signals", offer)
        self.assertEqual(200, status)
        self.assertTrue(response["deduplicated"])
        candidate = {
            "signalId": "signal-candidate",
            "senderId": "dispatcher-1",
            "type": "ICE_CANDIDATE",
            "payload": {
                "sdpMid": "0",
                "sdpMLineIndex": 0,
                "candidate": "candidate:1 1 UDP 2122260223 192.0.2.1 50000 typ host",
            },
            "createdAtEpochMillis": 1_786_000_001_100,
        }
        self.assertEqual(200, self.post_json("/v1/calls/call-webrtc/signals", candidate)[0])
        status, body, _ = self.request(
            "GET", "/v1/calls/call-webrtc/signals?afterSequence=0&limit=10"
        )
        self.assertEqual(200, status)
        signals = json.loads(body)["signals"]
        self.assertEqual([1, 2], [signal["sequence"] for signal in signals])
        self.assertEqual(["OFFER", "ICE_CANDIDATE"], [signal["type"] for signal in signals])

    def test_large_webrtc_signals_are_paginated_within_device_response_limit(self) -> None:
        call_id = "call-large-signals"
        self.repository.create_call({
            "callId": call_id,
            "deviceId": "device-1",
            "direction": "OUTGOING_DEVICE",
            "mediaMode": "VIDEO_UPLINK",
            "state": "REQUESTED",
            "stateSequence": 1,
            "relatedEventId": None,
            "simulated": False,
            "createdAtEpochMillis": 1_786_000_001_000,
        })
        large_sdp = "a" * 900_000
        for index in range(10):
            self.repository.create_call_signal(call_id, {
                "signalId": f"large-signal-{index}",
                "senderId": "dispatcher-1",
                "type": "ANSWER",
                "payload": {"sdp": large_sdp},
                "createdAtEpochMillis": 1_786_000_002_000 + index,
            })

        status, first_body, first_headers = self.request(
            "GET", f"/v1/calls/{call_id}/signals?afterSequence=0&limit=100",
        )
        self.assertEqual(200, status)
        self.assertLessEqual(len(first_body), MAX_COMMUNICATION_RESPONSE_BYTES)
        self.assertEqual(len(first_body), int(first_headers["Content-Length"]))
        first = json.loads(first_body)["signals"]
        self.assertGreater(len(first), MAX_COMMUNICATION_QUERY_ROWS)
        self.assertLess(len(first), 10)

        status, second_body, second_headers = self.request(
            "GET",
            f"/v1/calls/{call_id}/signals?afterSequence={first[-1]['sequence']}&limit=100",
        )
        self.assertEqual(200, status)
        self.assertLessEqual(len(second_body), MAX_COMMUNICATION_RESPONSE_BYTES)
        self.assertEqual(len(second_body), int(second_headers["Content-Length"]))
        second = json.loads(second_body)["signals"]
        self.assertEqual(list(range(1, 11)), [item["sequence"] for item in first + second])

        status, _, _ = self.request(
            "GET", f"/v1/calls/{call_id}/signals?afterSequence=0&limit=101",
        )
        self.assertEqual(400, status)
        status, _, _ = self.request(
            "GET", f"/v1/calls/{call_id}/signals?afterSequence={2 ** 63}&limit=100",
        )
        self.assertEqual(400, status)

    def test_voice_message_upload_query_playback_authorization_and_audit(self) -> None:
        call = {
            "callId": "call-voice",
            "deviceId": "device-1",
            "direction": "OUTGOING_DEVICE",
            "mediaMode": "AUDIO",
            "state": "REQUESTED",
            "stateSequence": 1,
            "relatedEventId": None,
            "simulated": False,
            "createdAtEpochMillis": 1_786_000_000_000,
        }
        self.assertEqual(200, self.post_json("/v1/calls", call)[0])
        payload = b"voice-message-fixture" * 5000
        metadata = {
            "mediaId": "voice-1",
            "deviceId": "device-1",
            "kind": "VOICE",
            "mimeType": "audio/mp4",
            "byteSize": len(payload),
            "sha256": hashlib.sha256(payload).hexdigest(),
            "durationMillis": 3200,
            "senderId": "dispatcher-1",
            "callId": "call-voice",
            "relatedEventId": None,
            "allowedRoles": ["DISPATCHER", "ADMIN"],
            "createdAtEpochMillis": 1_786_000_002_000,
        }
        status, session = self.post_json("/v1/media/sessions", metadata)
        self.assertEqual(200, status)
        offset = 0
        while offset < len(payload):
            chunk = payload[offset : offset + 64 * 1024]
            status, body, _ = self.request(
                "PUT",
                f"/v1/media/sessions/{session['sessionId']}/chunks",
                chunk,
                {
                    "Content-Type": "application/octet-stream",
                    "Content-Range": f"bytes {offset}-{offset + len(chunk) - 1}/{len(payload)}",
                    "X-Chunk-SHA256": hashlib.sha256(chunk).hexdigest(),
                },
            )
            self.assertEqual(200, status)
            offset = json.loads(body)["nextOffset"]
        status, completed = self.post_json(
            f"/v1/media/sessions/{session['sessionId']}/complete",
            {"mediaId": "voice-1", "byteSize": len(payload), "sha256": metadata["sha256"]},
        )
        self.assertEqual(200, status)
        self.assertEqual("COMPLETED", completed["status"])

        status, _, _ = self.request("GET", "/v1/voice-messages?deviceId=device-1")
        self.assertEqual(403, status)
        actor_headers = {"X-Actor-Id": "operator-1", "X-Actor-Role": "DISPATCHER"}
        status, body, _ = self.request(
            "GET", "/v1/voice-messages?deviceId=device-1&callId=call-voice", headers=actor_headers
        )
        self.assertEqual(200, status)
        self.assertEqual(["voice-1"], [item["messageId"] for item in json.loads(body)["messages"]])
        status, body, _ = self.request("GET", "/v1/voice-messages/voice-1", headers=actor_headers)
        self.assertEqual(200, status)
        self.assertEqual(metadata["sha256"], json.loads(body)["sha256"])
        status, content, headers = self.request(
            "GET", "/v1/voice-messages/voice-1/content", headers=actor_headers
        )
        self.assertEqual(200, status)
        self.assertEqual(payload, content)
        self.assertEqual(f'"{metadata["sha256"]}"', headers.get("ETag") or headers.get("Etag"))
        self.assertEqual("private, no-store", headers["Cache-Control"])
        status, _, _ = self.request(
            "GET",
            "/v1/voice-messages/voice-1/content",
            headers={"X-Actor-Id": "supervisor-1", "X-Actor-Role": "SUPERVISOR"},
        )
        self.assertEqual(403, status)
        with self.repository._connect() as database:
            actions = database.execute(
                "SELECT action FROM voice_message_audit WHERE message_id = ? ORDER BY rowid",
                ("voice-1",),
            ).fetchall()
        self.assertEqual(["UPLOAD", "READ", "PLAYBACK"], [row["action"] for row in actions])

    def test_safety_alert_ingest_presentation_workflow_clear_and_audit(self) -> None:
        alert = self.safety_alert()
        status, created = self.post_json("/v1/alerts", alert)
        self.assertEqual(200, status)
        self.assertFalse(created["deduplicated"])
        self.assertEqual("OPEN", created["workflowState"])
        self.assertTrue(created["requiresAttention"])
        self.assertEqual(
            {"sound": True, "visualPriority": "CRITICAL", "mapMarker": True},
            created["presentation"],
        )

        status, duplicate = self.post_json("/v1/alerts", alert)
        self.assertEqual(200, status)
        self.assertTrue(duplicate["deduplicated"])

        actor_headers = {"X-Actor-Id": "dispatcher-1", "X-Actor-Role": "DISPATCHER"}
        status, body, _ = self.request("GET", "/v1/alerts?deviceId=device-1", headers=actor_headers)
        self.assertEqual(200, status)
        self.assertEqual(["alert-1"], [item["alertId"] for item in json.loads(body)["alerts"]])

        status, _, _ = self.request(
            "POST",
            "/v1/alerts/alert-1/transitions",
            json.dumps(
                {"state": "ACKNOWLEDGED", "note": "已确认", "occurredAtEpochMillis": 1_786_000_001_000}
            ).encode(),
            {
                "Content-Type": "application/json",
                "X-Actor-Id": "viewer-1",
                "X-Actor-Role": "VIEWER",
            },
        )
        self.assertEqual(403, status)

        status, body, _ = self.request(
            "POST",
            "/v1/alerts/alert-1/transitions",
            json.dumps(
                {"state": "ACKNOWLEDGED", "note": "已确认", "occurredAtEpochMillis": 1_786_000_001_000}
            ).encode(),
            {"Content-Type": "application/json", **actor_headers},
        )
        self.assertEqual(200, status)
        self.assertEqual("ACKNOWLEDGED", json.loads(body)["workflowState"])

        status, body, _ = self.request(
            "POST",
            "/v1/alerts/alert-1/transitions",
            json.dumps(
                {
                    "state": "IN_PROGRESS",
                    "note": "正在处置",
                    "occurredAtEpochMillis": 1_786_000_001_500,
                }
            ).encode(),
            {"Content-Type": "application/json", **actor_headers},
        )
        self.assertEqual(200, status)
        self.assertEqual("IN_PROGRESS", json.loads(body)["workflowState"])

        cleared = self.safety_alert(
            message_id="alert-message-2",
            active=False,
            occurred_at=1_786_000_002_000,
        )
        cleared["severity"] = "INFO"
        status, response = self.post_json("/v1/alerts", cleared)
        self.assertEqual(200, status)
        self.assertFalse(response["active"])
        self.assertFalse(response["requiresAttention"])

        status, body, _ = self.request(
            "POST",
            "/v1/alerts/alert-1/transitions",
            json.dumps(
                {"state": "CLOSED", "note": "现场已排除", "occurredAtEpochMillis": 1_786_000_003_000}
            ).encode(),
            {"Content-Type": "application/json", **actor_headers},
        )
        self.assertEqual(200, status)
        self.assertEqual("CLOSED", json.loads(body)["workflowState"])

        status, body, _ = self.request("GET", "/v1/alerts/alert-1", headers=actor_headers)
        self.assertEqual(200, status)
        detail = json.loads(body)
        self.assertEqual(["ACTIVATED", "CLEARED"], [event["kind"] for event in detail["events"]])
        self.assertEqual(
            ["OPEN", "ACKNOWLEDGED", "IN_PROGRESS", "CLOSED"],
            [item["state"] for item in detail["history"]],
        )

        reopened = MediaRepository(Path(self.temporary.name), chunk_size=64 * 1024)
        detail = reopened.safety_alert("alert-1", "admin-1", "ADMIN")
        self.assertEqual("CLOSED", detail["workflowState"])
        self.assertEqual(
            ["OPEN", "ACKNOWLEDGED", "IN_PROGRESS", "CLOSED"],
            [item["state"] for item in detail["history"]],
        )

    def test_safety_alert_rejects_unknown_clear_collision_and_invalid_sensor_values(self) -> None:
        unknown_clear = self.safety_alert(active=False)
        status, response = self.post_json("/v1/alerts", unknown_clear)
        self.assertEqual(409, status)
        self.assertIn("unknown", response["error"])

        alert = self.safety_alert()
        self.assertEqual(200, self.post_json("/v1/alerts", alert)[0])
        changed = dict(alert)
        changed["severity"] = "HIGH"
        status, response = self.post_json("/v1/alerts", changed)
        self.assertEqual(409, status)
        self.assertIn("different", response["error"])

        invalid = self.safety_alert(message_id="invalid-alert")
        invalid["localActions"] = 256
        status, response = self.post_json("/v1/alerts", invalid)
        self.assertEqual(400, status)
        self.assertIn("localActions", response["error"])

    def test_archived_media_is_resolved_as_alert_evidence_without_changing_device_payload(self) -> None:
        alert = self.safety_alert(message_id="motion-alert-event")
        alert.update({"alertId": "motion-alert-1", "type": "FALL", "severity": "HIGH"})
        alert["evidence"] = {"mediaAssetId": None, "relatedEventId": alert["messageId"]}
        status, created = self.post_json("/v1/alerts", alert)
        self.assertEqual(200, status)
        self.assertIsNone(created["evidence"]["mediaAssetId"])

        payload = b"captured-motion-evidence" * 100
        metadata = {
            **self.metadata("motion-alert-photo", payload),
            "kind": "PHOTO",
            "mimeType": "image/jpeg",
            "width": 1920,
            "height": 1080,
            "durationMillis": None,
            "createdAtEpochMillis": 1_786_000_000_100,
            "relatedEventId": alert["messageId"],
        }
        status, session = self.post_json("/v1/media/sessions", metadata)
        self.assertEqual(200, status)
        chunk_headers = {
            "Content-Type": "application/octet-stream",
            "Content-Range": f"bytes 0-{len(payload) - 1}/{len(payload)}",
            "X-Chunk-SHA256": hashlib.sha256(payload).hexdigest(),
        }
        status, body, _ = self.request(
            "PUT", f"/v1/media/sessions/{session['sessionId']}/chunks", payload, chunk_headers,
        )
        self.assertEqual(200, status)
        self.assertEqual(len(payload), json.loads(body)["nextOffset"])
        status, completed = self.post_json(
            f"/v1/media/sessions/{session['sessionId']}/complete",
            {
                "mediaId": metadata["mediaId"],
                "byteSize": len(payload),
                "sha256": metadata["sha256"],
            },
        )
        self.assertEqual(200, status)
        self.assertEqual("COMPLETED", completed["status"])

        actor_headers = {"X-Actor-Id": "dispatcher-evidence", "X-Actor-Role": "DISPATCHER"}
        status, body, _ = self.request(
            "GET", "/v1/alerts/motion-alert-1", headers=actor_headers,
        )
        self.assertEqual(200, status)
        self.assertEqual("motion-alert-photo", json.loads(body)["evidence"]["mediaAssetId"])
        status, duplicate = self.post_json("/v1/alerts", alert)
        self.assertEqual(200, status)
        self.assertTrue(duplicate["deduplicated"])
        self.assertEqual("motion-alert-photo", duplicate["evidence"]["mediaAssetId"])

        cleared = self.safety_alert(
            message_id="motion-alert-clear",
            active=False,
            occurred_at=1_786_000_000_200,
        )
        cleared.update({"alertId": "motion-alert-1", "type": "FALL", "severity": "HIGH"})
        cleared["evidence"] = {
            "mediaAssetId": None,
            "relatedEventId": cleared["messageId"],
        }
        status, response = self.post_json("/v1/alerts", cleared)
        self.assertEqual(200, status)
        self.assertFalse(response["active"])
        self.assertEqual("motion-alert-photo", response["evidence"]["mediaAssetId"])
        status, body, _ = self.request(
            "GET", "/v1/alerts/motion-alert-1", headers=actor_headers,
        )
        self.assertEqual(200, status)
        self.assertEqual("motion-alert-photo", json.loads(body)["evidence"]["mediaAssetId"])

    def test_geofence_exit_clear_and_operator_workflow_are_persistent(self) -> None:
        exit_alert = {
            **self.safety_alert(message_id="geofence-exit", occurred_at=1_786_000_010_000),
            "alertId": "geofence-yard-device-1",
            "type": "GEOFENCE",
            "severity": "HIGH",
            "sampleReference": None,
            "configVersion": None,
            "localActions": 0,
            "sensorSnapshot": {
                "geofenceId": "yard",
                "fixId": "fix-exit",
                "transition": "EXIT",
                "distanceMeters": 175.5,
            },
        }
        status, created = self.post_json("/v1/alerts", exit_alert)
        self.assertEqual(200, status)
        self.assertTrue(created["active"])
        self.assertTrue(created["presentation"]["mapMarker"])

        clear_alert = {
            **exit_alert,
            "messageId": "geofence-enter",
            "severity": "INFO",
            "active": False,
            "occurredAtEpochMillis": 1_786_000_011_000,
            "sensorSnapshot": {
                **exit_alert["sensorSnapshot"],
                "fixId": "fix-enter",
                "transition": "ENTER",
                "distanceMeters": 95.0,
            },
        }
        status, cleared = self.post_json("/v1/alerts", clear_alert)
        self.assertEqual(200, status)
        self.assertFalse(cleared["active"])
        self.assertEqual("OPEN", cleared["workflowState"])

        status, body, _ = self.request(
            "POST",
            "/v1/alerts/geofence-yard-device-1/transitions",
            json.dumps(
                {
                    "state": "CLOSED",
                    "note": "worker returned inside controlled area",
                    "occurredAtEpochMillis": 1_786_000_012_000,
                },
            ).encode(),
            {
                "Content-Type": "application/json",
                "X-Actor-Id": "dispatcher-geofence",
                "X-Actor-Role": "DISPATCHER",
            },
        )
        closed = json.loads(body)
        self.assertEqual(200, status)
        self.assertEqual("CLOSED", closed["workflowState"])
        detail = self.repository.safety_alert(
            "geofence-yard-device-1", "admin-geofence", "ADMIN",
        )
        self.assertEqual(["ACTIVATED", "CLEARED"], [item["kind"] for item in detail["events"]])
        self.assertEqual(["OPEN", "CLOSED"], [item["state"] for item in detail["history"]])

    def test_production_auth_config_uses_token_digests_and_restricted_permissions(self) -> None:
        token = "production-test-dispatcher-token"
        config_path = Path(self.temporary.name) / "auth.json"
        config_path.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "principals": [
                        {
                            "tokenSha256": hashlib.sha256(token.encode()).hexdigest(),
                            "principalId": "dispatcher-production",
                            "role": "DISPATCHER",
                            "deviceIds": ["device-1"],
                        },
                    ],
                },
            ),
            encoding="utf-8",
        )
        os.chmod(config_path, 0o600)
        principals = load_auth_principals(config_path)
        self.assertEqual(
            AuthPrincipal("dispatcher-production", "DISPATCHER", ("device-1",)),
            principals[hashlib.sha256(token.encode()).hexdigest()],
        )
        self.assertNotIn(token, config_path.read_text(encoding="utf-8"))

        os.chmod(config_path, 0o644)
        with self.assertRaisesRegex(ValueError, "group- or world-accessible"):
            load_auth_principals(config_path)

    def test_production_auth_config_rejects_symlinked_files(self) -> None:
        physical = Path(self.temporary.name) / "physical-auth.json"
        physical.write_text(
            json.dumps({"schemaVersion": 1, "principals": []}),
            encoding="utf-8",
        )
        os.chmod(physical, 0o600)
        linked = Path(self.temporary.name) / "linked-auth.json"
        linked.symlink_to(physical)
        with self.assertRaisesRegex(ValueError, "physical regular file"):
            load_auth_configuration(linked)

    def test_schema2_auth_config_validates_organization_membership(self) -> None:
        tokens = {
            "admin-a": "organization-a-admin-token",
            "viewer-a": "organization-a-viewer-token",
            "device-a": "organization-a-device-token",
            "admin-b": "organization-b-admin-token",
            "device-b": "organization-b-device-token",
        }
        config_path = Path(self.temporary.name) / "auth-v2.json"
        value = {
            "schemaVersion": 2,
            "organizations": [
                {"organizationId": "organization-a", "deviceIds": ["device-a"]},
                {"organizationId": "organization-b", "deviceIds": ["device-b"]},
            ],
            "principals": [
                {
                    "tokenSha256": hashlib.sha256(tokens["admin-a"].encode()).hexdigest(),
                    "principalId": "admin-a",
                    "role": "ADMIN",
                    "organizationId": "organization-a",
                },
                {
                    "tokenSha256": hashlib.sha256(tokens["viewer-a"].encode()).hexdigest(),
                    "principalId": "viewer-a",
                    "role": "VIEWER",
                    "organizationId": "organization-a",
                },
                {
                    "tokenSha256": hashlib.sha256(tokens["device-a"].encode()).hexdigest(),
                    "principalId": "device-principal-a",
                    "role": "DEVICE",
                    "organizationId": "organization-a",
                    "deviceIds": ["device-a"],
                },
                {
                    "tokenSha256": hashlib.sha256(tokens["admin-b"].encode()).hexdigest(),
                    "principalId": "admin-b",
                    "role": "ADMIN",
                    "organizationId": "organization-b",
                },
                {
                    "tokenSha256": hashlib.sha256(tokens["device-b"].encode()).hexdigest(),
                    "principalId": "device-principal-b",
                    "role": "DEVICE",
                    "organizationId": "organization-b",
                    "deviceIds": ["device-b"],
                },
            ],
        }
        config_path.write_text(json.dumps(value), encoding="utf-8")
        os.chmod(config_path, 0o600)
        configuration = load_auth_configuration(config_path)
        self.assertEqual(
            {"device-a": "organization-a", "device-b": "organization-b"},
            configuration.device_organizations,
        )
        viewer = configuration.principals[
            hashlib.sha256(tokens["viewer-a"].encode()).hexdigest()
        ]
        self.assertEqual("organization-a", viewer.organization_id)
        self.assertEqual((), viewer.device_ids)

        invalid = json.loads(json.dumps(value))
        invalid["principals"][2]["deviceIds"] = ["device-b"]
        config_path.write_text(json.dumps(invalid), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "another organization"):
            load_auth_configuration(config_path)

        invalid = json.loads(json.dumps(value))
        invalid["principals"] = [
            principal for principal in invalid["principals"]
            if principal["principalId"] != "admin-b"
        ]
        config_path.write_text(json.dumps(invalid), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "must have an ADMIN"):
            load_auth_configuration(config_path)

    def test_organization_acl_covers_resources_and_audits_denials(self) -> None:
        tokens = {
            "admin-a": "acl-admin-a-token",
            "dispatcher-a": "acl-dispatcher-a-token",
            "viewer-a": "acl-viewer-a-token",
            "device-a": "acl-device-a-token",
            "admin-b": "acl-admin-b-token",
            "dispatcher-b": "acl-dispatcher-b-token",
            "viewer-b": "acl-viewer-b-token",
            "device-b": "acl-device-b-token",
        }

        def principal(name: str, role: str, organization_id: str, devices: tuple[str, ...] = ()) -> AuthPrincipal:
            return AuthPrincipal(name, role, devices, organization_id)

        principals = {
            hashlib.sha256(tokens["admin-a"].encode()).hexdigest(): principal(
                "admin-a", "ADMIN", "organization-a",
            ),
            hashlib.sha256(tokens["dispatcher-a"].encode()).hexdigest(): principal(
                "dispatcher-a", "DISPATCHER", "organization-a",
            ),
            hashlib.sha256(tokens["viewer-a"].encode()).hexdigest(): principal(
                "viewer-a", "VIEWER", "organization-a",
            ),
            hashlib.sha256(tokens["device-a"].encode()).hexdigest(): principal(
                "device-principal-a", "DEVICE", "organization-a", ("device-a",),
            ),
            hashlib.sha256(tokens["admin-b"].encode()).hexdigest(): principal(
                "admin-b", "ADMIN", "organization-b",
            ),
            hashlib.sha256(tokens["dispatcher-b"].encode()).hexdigest(): principal(
                "dispatcher-b", "DISPATCHER", "organization-b",
            ),
            hashlib.sha256(tokens["viewer-b"].encode()).hexdigest(): principal(
                "viewer-b", "VIEWER", "organization-b",
            ),
            hashlib.sha256(tokens["device-b"].encode()).hexdigest(): principal(
                "device-principal-b", "DEVICE", "organization-b", ("device-b",),
            ),
        }
        production_server = MediaHttpServer(
            ("127.0.0.1", 0),
            self.repository,
            "",
            auth_principals=principals,
            device_organizations={"device-a": "organization-a", "device-b": "organization-b"},
        )
        thread = threading.Thread(target=production_server.serve_forever, daemon=True)
        thread.start()
        endpoint = f"http://127.0.0.1:{production_server.server_port}"

        def request(
            token_name: str,
            method: str,
            path: str,
            value: object | bytes | None = None,
            headers: dict[str, str] | None = None,
        ) -> tuple[int, bytes, dict[str, str]]:
            body = None
            request_headers = {"Authorization": f"Bearer {tokens[token_name]}", **(headers or {})}
            if value is not None:
                if isinstance(value, bytes):
                    body = value
                else:
                    body = json.dumps(value).encode()
                    request_headers["Content-Type"] = "application/json"
            request_value = Request(
                endpoint + path, data=body, headers=request_headers, method=method,
            )
            try:
                with urlopen(request_value, timeout=5) as response:
                    return response.status, response.read(), dict(response.headers.items())
            except HTTPError as error:
                return error.code, error.read(), dict(error.headers.items())

        def request_json(
            token_name: str,
            method: str,
            path: str,
            value: object | None = None,
        ) -> tuple[int, dict[str, object]]:
            status, body, _ = request(token_name, method, path, value)
            return status, json.loads(body)

        try:
            status, viewer_profile = request_json("viewer-a", "GET", "/v1/access-profile")
            self.assertEqual(200, status)
            self.assertEqual("PRODUCTION_PRINCIPAL", viewer_profile["mode"])
            self.assertEqual("viewer-a", viewer_profile["principalId"])
            self.assertEqual("VIEWER", viewer_profile["role"])
            self.assertEqual("organization-a", viewer_profile["organizationId"])
            self.assertEqual(
                {"kind": "ORGANIZATION", "deviceIds": ["device-a"]},
                viewer_profile["deviceScope"],
            )
            self.assertEqual(["READ_OPERATIONS"], viewer_profile["capabilities"])
            self.assertIsNone(viewer_profile["directory"])

            status, admin_profile = request_json("admin-a", "GET", "/v1/access-profile")
            self.assertEqual(200, status)
            self.assertEqual("admin-a", admin_profile["principalId"])
            self.assertIn("VIEW_ACCESS_DIRECTORY", admin_profile["capabilities"])
            self.assertEqual("organization-a", admin_profile["directory"]["organizationId"])
            self.assertEqual(["device-a"], admin_profile["directory"]["deviceIds"])
            self.assertEqual(
                ["admin-a", "device-principal-a", "dispatcher-a", "viewer-a"],
                [item["principalId"] for item in admin_profile["directory"]["principals"]],
            )
            serialized_profile = json.dumps(admin_profile)
            self.assertNotIn("tokenSha256", serialized_profile)
            for token in tokens.values():
                self.assertNotIn(token, serialized_profile)

            status, admin_b_profile = request_json("admin-b", "GET", "/v1/access-profile")
            self.assertEqual(200, status)
            self.assertEqual("organization-b", admin_b_profile["directory"]["organizationId"])
            self.assertEqual(["device-b"], admin_b_profile["directory"]["deviceIds"])
            self.assertEqual(
                ["admin-b", "device-principal-b", "dispatcher-b", "viewer-b"],
                [item["principalId"] for item in admin_b_profile["directory"]["principals"]],
            )

            status, body, _ = request(
                "admin-a",
                "GET",
                "/v1/access-profile",
                headers={"X-Actor-Id": "admin-b", "X-Actor-Role": "VIEWER"},
            )
            self.assertEqual(200, status)
            spoofed_profile = json.loads(body)
            self.assertEqual("admin-a", spoofed_profile["principalId"])
            self.assertEqual("ADMIN", spoofed_profile["role"])
            self.assertEqual(403, request_json("device-a", "GET", "/v1/access-profile")[0])

            status_a = {
                **self.device_status("status-a", "device-a"),
                "personId": "person-a",
            }
            status_b = {
                **self.device_status("status-b", "device-b"),
                "personId": "person-b",
            }
            self.assertEqual(200, request_json("device-a", "POST", "/v1/device-status", status_a)[0])
            self.assertEqual(200, request_json("device-b", "POST", "/v1/device-status", status_b)[0])
            status, overview_a = request_json("viewer-a", "GET", "/v1/devices/overview")
            self.assertEqual(200, status)
            self.assertEqual(["device-a"], [item["deviceId"] for item in overview_a["devices"]])
            self.assertEqual("person-a", overview_a["devices"][0]["personId"])

            point_a = {**self.track_point("track-a", 1), "deviceId": "device-a"}
            point_b = {**self.track_point("track-b", 1), "deviceId": "device-b"}
            self.assertEqual(200, request_json("device-a", "POST", "/v1/tracks:batch", {"points": [point_a]})[0])
            self.assertEqual(200, request_json("device-b", "POST", "/v1/tracks:batch", {"points": [point_b]})[0])
            self.assertEqual(200, request_json("viewer-a", "GET", "/v1/tracks?deviceId=device-a")[0])
            self.assertEqual(403, request_json("viewer-a", "GET", "/v1/tracks?deviceId=device-b")[0])
            self.assertEqual(
                403,
                request_json("device-b", "POST", "/v1/tracks:batch", {"points": [
                    {**point_a, "messageId": "track-a-spoof", "sequence": 2},
                ]})[0],
            )

            alert_a = {
                **self.safety_alert(message_id="alert-message-a"),
                "alertId": "alert-a",
                "deviceId": "device-a",
            }
            alert_b = {
                **self.safety_alert(message_id="alert-message-b"),
                "alertId": "alert-b",
                "deviceId": "device-b",
            }
            self.assertEqual(200, request_json("device-a", "POST", "/v1/alerts", alert_a)[0])
            self.assertEqual(200, request_json("device-b", "POST", "/v1/alerts", alert_b)[0])
            status, alerts_a = request_json("viewer-a", "GET", "/v1/alerts")
            self.assertEqual(200, status)
            self.assertEqual(["alert-a"], [item["alertId"] for item in alerts_a["alerts"]])
            self.assertEqual(403, request_json("viewer-b", "GET", "/v1/alerts/alert-a")[0])
            self.assertEqual(
                403,
                request_json(
                    "device-b", "POST", "/v1/alerts",
                    {**alert_a, "messageId": "alert-message-a-spoof", "alertId": "alert-a-spoof"},
                )[0],
            )
            self.assertEqual(
                403,
                request_json(
                    "dispatcher-b",
                    "POST",
                    "/v1/alerts/alert-a/transitions",
                    {"state": "ACKNOWLEDGED", "occurredAtEpochMillis": 1_786_000_001_000},
                )[0],
            )

            def call(call_id: str, device_id: str) -> dict[str, object]:
                return {
                    "callId": call_id,
                    "deviceId": device_id,
                    "direction": "OUTGOING_DEVICE",
                    "mediaMode": "AUDIO",
                    "state": "REQUESTED",
                    "stateSequence": 1,
                    "relatedEventId": None,
                    "simulated": False,
                    "createdAtEpochMillis": 1_786_000_000_000,
                }

            self.assertEqual(200, request_json("device-a", "POST", "/v1/calls", call("call-a", "device-a"))[0])
            self.assertEqual(200, request_json("device-b", "POST", "/v1/calls", call("call-b", "device-b"))[0])
            status, calls_a = request_json("viewer-a", "GET", "/v1/calls")
            self.assertEqual(200, status)
            self.assertEqual(["call-a"], [item["callId"] for item in calls_a["calls"]])
            self.assertEqual(403, request_json("viewer-a", "GET", "/v1/calls/call-b")[0])
            self.assertEqual(
                403,
                request_json("device-b", "POST", "/v1/calls", call("call-a-spoof", "device-a"))[0],
            )
            self.assertEqual(
                403,
                request_json(
                    "dispatcher-b", "GET", "/v1/calls/call-a/ice-config?requesterId=dispatcher-b",
                )[0],
            )
            self.assertEqual(
                403,
                request_json(
                    "dispatcher-a",
                    "POST",
                    "/v1/calls/call-b/transitions",
                    {
                        "state": "ACCEPTED",
                        "actorId": "dispatcher-a",
                        "reason": "OPERATOR_ACCEPTED",
                        "occurredAtEpochMillis": 1_786_000_001_000,
                    },
                )[0],
            )

            broadcast = {
                "broadcastId": "broadcast-a",
                "deviceId": "device-a",
                "text": "organization a message",
                "language": "zh-CN",
                "priority": 5,
                "expiresAtEpochMillis": None,
                "createdAtEpochMillis": 1_786_000_000_000,
            }
            status, created_broadcast = request_json(
                "dispatcher-a", "POST", "/v1/broadcasts", broadcast,
            )
            self.assertEqual(200, status)
            status, broadcasts_a = request_json("viewer-a", "GET", "/v1/broadcasts")
            self.assertEqual(200, status)
            self.assertEqual(
                ["broadcast-a"],
                [item["broadcastId"] for item in broadcasts_a["broadcasts"]],
            )
            self.assertEqual([], request_json("viewer-b", "GET", "/v1/broadcasts")[1]["broadcasts"])
            self.assertEqual(
                403,
                request_json("viewer-a", "GET", "/v1/broadcasts?deviceId=device-b")[0],
            )
            self.assertEqual(
                403,
                request_json(
                    "viewer-a", "POST", "/v1/broadcasts",
                    {**broadcast, "broadcastId": "broadcast-viewer-denied"},
                )[0],
            )
            self.assertEqual(
                403,
                request_json(
                    "dispatcher-a", "POST", "/v1/broadcasts",
                    {**broadcast, "broadcastId": "broadcast-cross", "deviceId": "device-b"},
                )[0],
            )
            self.assertEqual(200, request_json("device-a", "GET", "/v1/device-commands?deviceId=device-a")[0])
            self.assertEqual(403, request_json("device-b", "GET", "/v1/device-commands?deviceId=device-a")[0])
            self.assertEqual(
                403,
                request_json(
                    "device-b", "POST", "/v1/broadcasts/broadcast-a/receipts",
                    {"state": "RECEIVED", "occurredAtEpochMillis": 1_786_000_001_000},
                )[0],
            )
            self.assertEqual(
                403,
                request_json(
                    "device-b", "POST",
                    f"/v1/device-commands/{created_broadcast['commandId']}/ack",
                    {"occurredAtEpochMillis": 1_786_000_002_000},
                )[0],
            )

            media_payload = b"organization-a-media"
            media = {**self.metadata("media-a", media_payload), "deviceId": "device-a"}
            status, session = request_json("device-a", "POST", "/v1/media/sessions", media)
            self.assertEqual(200, status)
            session_id = session["sessionId"]
            chunk_headers = {
                "Content-Type": "application/octet-stream",
                "Content-Range": f"bytes 0-{len(media_payload) - 1}/{len(media_payload)}",
                "X-Chunk-SHA256": hashlib.sha256(media_payload).hexdigest(),
            }
            self.assertEqual(
                403,
                request(
                    "device-b", "PUT", f"/v1/media/sessions/{session_id}/chunks",
                    media_payload, chunk_headers,
                )[0],
            )
            self.assertEqual(
                200,
                request(
                    "device-a", "PUT", f"/v1/media/sessions/{session_id}/chunks",
                    media_payload, chunk_headers,
                )[0],
            )
            self.assertEqual(
                200,
                request_json(
                    "device-a", "POST", f"/v1/media/sessions/{session_id}/complete",
                    {
                        "mediaId": media["mediaId"],
                        "byteSize": media["byteSize"],
                        "sha256": media["sha256"],
                    },
                )[0],
            )
            self.assertEqual(200, request("viewer-a", "GET", "/v1/media/media-a/content")[0])
            self.assertEqual(403, request_json("viewer-b", "GET", "/v1/media/media-a")[0])
            status, media_a = request_json("viewer-a", "GET", "/v1/media")
            self.assertEqual(200, status)
            self.assertEqual(["media-a"], [item["mediaId"] for item in media_a["media"]])
            self.assertEqual([], request_json("viewer-b", "GET", "/v1/media")[1]["media"])
            self.assertEqual(
                403,
                request_json("viewer-a", "GET", "/v1/media?deviceId=device-b")[0],
            )
            self.assertEqual(
                403,
                request_json(
                    "device-b", "POST", "/v1/media/sessions",
                    {**media, "mediaId": "media-a-spoof"},
                )[0],
            )

            voice_payload = b"RIFF-organization-a-voice"
            voice = {
                "mediaId": "voice-a",
                "deviceId": "device-a",
                "kind": "VOICE",
                "mimeType": "audio/wav",
                "byteSize": len(voice_payload),
                "sha256": hashlib.sha256(voice_payload).hexdigest(),
                "durationMillis": 1000,
                "createdAtEpochMillis": 1_786_000_003_000,
                "senderId": "device-a",
                "senderRole": "DEVICE",
                "callId": None,
                "relatedEventId": None,
                "allowedRoles": ["DISPATCHER"],
            }
            status, voice_session = request_json("device-a", "POST", "/v1/media/sessions", voice)
            self.assertEqual(200, status)
            voice_session_id = voice_session["sessionId"]
            voice_headers = {
                "Content-Type": "application/octet-stream",
                "Content-Range": f"bytes 0-{len(voice_payload) - 1}/{len(voice_payload)}",
                "X-Chunk-SHA256": hashlib.sha256(voice_payload).hexdigest(),
            }
            self.assertEqual(
                200,
                request(
                    "device-a", "PUT", f"/v1/media/sessions/{voice_session_id}/chunks",
                    voice_payload, voice_headers,
                )[0],
            )
            self.assertEqual(
                200,
                request_json(
                    "device-a", "POST", f"/v1/media/sessions/{voice_session_id}/complete",
                    {
                        "mediaId": voice["mediaId"],
                        "byteSize": voice["byteSize"],
                        "sha256": voice["sha256"],
                    },
                )[0],
            )
            status, voices_a = request_json("dispatcher-a", "GET", "/v1/voice-messages")
            self.assertEqual(200, status)
            self.assertEqual(["voice-a"], [item["messageId"] for item in voices_a["messages"]])
            self.assertEqual([], request_json("dispatcher-b", "GET", "/v1/voice-messages")[1]["messages"])
            self.assertEqual(403, request_json("dispatcher-b", "GET", "/v1/voice-messages/voice-a")[0])
            self.assertEqual(403, request_json("dispatcher-a", "GET", "/v1/media/voice-a")[0])

            status, audit_a = request_json("admin-a", "GET", "/v1/security-audit?limit=100")
            self.assertEqual(200, status)
            self.assertTrue(audit_a["events"])
            self.assertTrue(all(event["organizationId"] == "organization-a" for event in audit_a["events"]))
            self.assertIn("/v1/broadcasts", {event["resourcePath"] for event in audit_a["events"]})

            status, audit_b = request_json("admin-b", "GET", "/v1/security-audit?limit=100")
            self.assertEqual(200, status)
            self.assertTrue(audit_b["events"])
            self.assertTrue(all(event["organizationId"] == "organization-b" for event in audit_b["events"]))
            denied_paths = {event["resourcePath"] for event in audit_b["events"]}
            self.assertIn("/v1/alerts/alert-a", denied_paths)
        finally:
            production_server.shutdown()
            production_server.server_close()
            thread.join(timeout=2)

    def test_production_principal_binds_alert_actor_and_rejects_spoofing(self) -> None:
        self.assertEqual(200, self.post_json("/v1/alerts", self.safety_alert())[0])
        token = "production-test-dispatcher-token"
        production_server = MediaHttpServer(
            ("127.0.0.1", 0),
            self.repository,
            "",
            auth_principals={
                hashlib.sha256(token.encode()).hexdigest(): AuthPrincipal(
                    "dispatcher-production",
                    "DISPATCHER",
                    ("device-1",),
                ),
            },
        )
        thread = threading.Thread(target=production_server.serve_forever, daemon=True)
        thread.start()
        endpoint = f"http://127.0.0.1:{production_server.server_port}"

        def request(headers: dict[str, str]) -> tuple[int, bytes]:
            value = Request(endpoint + "/v1/alerts/alert-1", headers=headers, method="GET")
            try:
                with urlopen(value, timeout=5) as response:
                    return response.status, response.read()
            except HTTPError as error:
                return error.code, error.read()

        try:
            status, body = request({"Authorization": f"Bearer {token}"})
            self.assertEqual(200, status)
            self.assertEqual("alert-1", json.loads(body)["alertId"])

            status, body = request(
                {
                    "Authorization": f"Bearer {token}",
                    "X-Actor-Id": "attacker",
                    "X-Actor-Role": "ADMIN",
                },
            )
            self.assertEqual(403, status)
            self.assertIn("does not match", json.loads(body)["error"])

            status, _ = request({"Authorization": "Bearer incorrect-token"})
            self.assertEqual(401, status)
        finally:
            production_server.shutdown()
            production_server.server_close()
            thread.join(timeout=2)

    def test_production_call_roles_bind_actor_sender_and_device(self) -> None:
        call = {
            "callId": "call-production",
            "deviceId": "device-1",
            "direction": "OUTGOING_DEVICE",
            "mediaMode": "VIDEO_UPLINK",
            "state": "REQUESTED",
            "stateSequence": 1,
            "relatedEventId": None,
            "simulated": False,
            "createdAtEpochMillis": 1_786_000_000_000,
        }
        self.repository.create_call(call)
        dispatcher_token = "production-call-dispatcher-token"
        viewer_token = "production-call-viewer-token"
        device_token = "production-call-device-token"
        wrong_device_token = "production-call-wrong-device-token"
        principals = {
            hashlib.sha256(dispatcher_token.encode()).hexdigest(): AuthPrincipal(
                "dispatcher-production", "DISPATCHER", (),
            ),
            hashlib.sha256(viewer_token.encode()).hexdigest(): AuthPrincipal(
                "viewer-production", "VIEWER", (),
            ),
            hashlib.sha256(device_token.encode()).hexdigest(): AuthPrincipal(
                "device-principal-1", "DEVICE", ("device-1",),
            ),
            hashlib.sha256(wrong_device_token.encode()).hexdigest(): AuthPrincipal(
                "device-principal-2", "DEVICE", ("device-2",),
            ),
        }
        production_server = MediaHttpServer(
            ("127.0.0.1", 0), self.repository, "", auth_principals=principals,
        )
        thread = threading.Thread(target=production_server.serve_forever, daemon=True)
        thread.start()
        endpoint = f"http://127.0.0.1:{production_server.server_port}"

        def request(
            token: str,
            method: str,
            path: str,
            value: object | None = None,
        ) -> tuple[int, dict[str, object]]:
            data = None if value is None else json.dumps(value).encode()
            headers = {"Authorization": f"Bearer {token}"}
            if data is not None:
                headers["Content-Type"] = "application/json"
            request_value = Request(endpoint + path, data=data, headers=headers, method=method)
            try:
                with urlopen(request_value, timeout=5) as response:
                    return response.status, json.loads(response.read())
            except HTTPError as error:
                return error.code, json.loads(error.read())

        try:
            status, body = request(dispatcher_token, "GET", "/v1/calls?limit=10")
            self.assertEqual(200, status)
            self.assertEqual("call-production", body["calls"][0]["callId"])
            self.assertEqual(200, request(viewer_token, "GET", "/v1/calls?limit=10")[0])
            transition = {
                "state": "ACCEPTED",
                "actorId": "attacker",
                "occurredAtEpochMillis": 1_786_000_001_000,
                "reason": "OPERATOR_ACCEPTED",
            }
            self.assertEqual(
                403,
                request(viewer_token, "POST", "/v1/calls/call-production/transitions", transition)[0],
            )
            self.assertEqual(
                403,
                request(dispatcher_token, "POST", "/v1/calls/call-production/transitions", transition)[0],
            )
            transition["actorId"] = "dispatcher-production"
            status, body = request(
                dispatcher_token, "POST", "/v1/calls/call-production/transitions", transition,
            )
            self.assertEqual(200, status)
            self.assertEqual("dispatcher-production", body["history"][-1]["actor_id"])

            signal = {
                "signalId": "production-device-offer",
                "senderId": "device-1",
                "type": "OFFER",
                "payload": {"sdp": "v=0\r\n"},
                "createdAtEpochMillis": 1_786_000_002_000,
            }
            self.assertEqual(
                200,
                request(device_token, "POST", "/v1/calls/call-production/signals", signal)[0],
            )
            self.assertEqual(
                403,
                request(wrong_device_token, "GET", "/v1/calls/call-production")[0],
            )
        finally:
            production_server.shutdown()
            production_server.server_close()
            thread.join(timeout=2)

if __name__ == "__main__":
    unittest.main()
