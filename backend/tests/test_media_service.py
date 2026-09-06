from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from backend.media_service import MediaHttpServer, MediaRepository


TOKEN = "minimal-test-token"


class MinimalMediaServiceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.repository = MediaRepository(Path(self.temporary.name), chunk_size=65536)
        self.server = MediaHttpServer(("127.0.0.1", 0), self.repository, TOKEN)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temporary.cleanup()

    def request(self, method: str, path: str, body: object | bytes | None = None):
        if isinstance(body, bytes):
            payload = body
        elif body is None:
            payload = None
        else:
            payload = json.dumps(body).encode()
        request = Request(
            self.base_url + path,
            data=payload,
            method=method,
            headers={
                "Authorization": f"Bearer {TOKEN}",
                "Content-Type": "application/json",
            },
        )
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read())

    def request_bytes(self, path: str) -> tuple[int, bytes, str]:
        request = Request(
            self.base_url + path,
            method="GET",
            headers={"Authorization": f"Bearer {TOKEN}"},
        )
        with urlopen(request, timeout=5) as response:
            return response.status, response.read(), response.headers["Content-Type"]

    def create_call(self, call_id: str = "call-1") -> dict[str, object]:
        status, call = self.request("POST", "/v1/calls", {
            "callId": call_id,
            "deviceId": "device-1",
            "direction": "OUTGOING_DEVICE",
            "mediaMode": "VIDEO_UPLINK",
            "state": "REQUESTED",
            "stateSequence": 1,
            "relatedEventId": None,
            "simulated": False,
            "createdAtEpochMillis": 1_786_000_000_000,
        })
        self.assertEqual(200, status)
        return call

    def track_point(self, sequence: int) -> dict[str, object]:
        return {
            "messageId": f"track-{sequence}",
            "deviceId": "device-1",
            "sequence": sequence,
            "fixId": f"fix-{sequence}",
            "occurredAtEpochMillis": 1_786_000_000_000 + sequence,
            "source": "EXTERNAL_NMEA",
            "quality": "RTK_FIXED",
            "latitude": 30.0,
            "longitude": 114.0,
            "isMock": False,
        }

    def test_requires_token(self) -> None:
        request = Request(self.base_url + "/v1/calls", method="GET")
        with self.assertRaises(HTTPError) as captured:
            urlopen(request, timeout=5)
        self.assertEqual(401, captured.exception.code)

    def test_call_transition_creates_device_command(self) -> None:
        self.create_call()
        occurred = int(time.time() * 1000)
        status, transition = self.request("POST", "/v1/calls/call-1/transitions", {
            "state": "ACCEPTED",
            "actorId": "viewer-1",
            "occurredAtEpochMillis": occurred,
            "reason": "viewer accepted",
        })
        self.assertEqual(200, status)
        self.assertEqual("ACCEPTED", transition["state"])
        status, page = self.request(
            "GET", "/v1/device-commands?deviceId=device-1&afterSequence=0&limit=100"
        )
        self.assertEqual(200, status)
        self.assertEqual("CALL_STATE", page["commands"][0]["type"])
        self.assertEqual("ACCEPTED", page["commands"][0]["payload"]["state"])

    def test_device_cannot_have_two_active_calls(self) -> None:
        self.create_call("call-active-1")
        status, body = self.request("POST", "/v1/calls", {
            "callId": "call-active-2",
            "deviceId": "device-1",
            "direction": "OUTGOING_DEVICE",
            "mediaMode": "AUDIO",
            "state": "REQUESTED",
            "stateSequence": 1,
            "relatedEventId": None,
            "simulated": False,
            "createdAtEpochMillis": 1_786_000_000_100,
        })
        self.assertEqual(409, status)
        self.assertEqual("device already has an active call", body["error"])

    def test_broadcast_returns_stable_command_identity(self) -> None:
        request = {
            "broadcastId": "broadcast-1",
            "deviceId": "device-1",
            "text": "安全广播",
            "language": "zh-CN",
            "priority": 10,
            "expiresAtEpochMillis": 1_786_000_600_000,
            "createdAtEpochMillis": 1_786_000_000_000,
        }
        status, created = self.request("POST", "/v1/broadcasts", request)
        self.assertEqual(200, status)
        self.assertFalse(created["deduplicated"])
        self.assertEqual(1, created["commandSequence"])

        status, repeated = self.request("POST", "/v1/broadcasts", request)
        self.assertEqual(200, status)
        self.assertTrue(repeated["deduplicated"])
        self.assertEqual(created["commandId"], repeated["commandId"])
        self.assertEqual(created["commandSequence"], repeated["commandSequence"])

        status, page = self.request(
            "GET", "/v1/device-commands?deviceId=device-1&afterSequence=0&limit=100"
        )
        self.assertEqual(200, status)
        self.assertEqual(created["commandId"], page["commands"][0]["commandId"])
        self.assertEqual("TEXT_BROADCAST", page["commands"][0]["type"])

    def test_broadcast_rejects_empty_invalid_or_out_of_order_data(self) -> None:
        base = {
            "broadcastId": "broadcast-validation",
            "deviceId": "device-validation",
            "text": "安全广播",
            "language": "zh-CN",
            "priority": 10,
            "expiresAtEpochMillis": 1_786_000_600_000,
            "createdAtEpochMillis": 1_786_000_000_000,
        }
        for replacement in (
            {"text": "  "},
            {"language": "not a language tag"},
            {"priority": 11},
            {"expiresAtEpochMillis": 1_785_999_999_999},
        ):
            request = dict(base, **replacement)
            request["broadcastId"] = "invalid-" + hashlib.sha256(str(replacement).encode()).hexdigest()[:12]
            self.assertEqual(400, self.request("POST", "/v1/broadcasts", request)[0])

        status, created = self.request("POST", "/v1/broadcasts", base)
        self.assertEqual(200, status)
        stream = self.repository.command_stream_id()
        playing = {
            "commandStreamId": stream,
            "state": "PLAYING",
            "occurredAtEpochMillis": 1_786_000_000_010,
            "error": None,
        }
        self.assertEqual(
            409,
            self.request("POST", f"/v1/broadcasts/{created['broadcastId']}/receipts", playing)[0],
        )
        received = dict(playing, state="RECEIVED")
        self.assertEqual(
            200,
            self.request("POST", f"/v1/broadcasts/{created['broadcastId']}/receipts", received)[0],
        )
        failed_without_error = dict(playing, state="FAILED", occurredAtEpochMillis=1_786_000_000_011)
        self.assertEqual(
            400,
            self.request(
                "POST",
                f"/v1/broadcasts/{created['broadcastId']}/receipts",
                failed_without_error,
            )[0],
        )

    def test_offer_answer_and_ice_are_ordered(self) -> None:
        self.create_call()
        status, offer = self.request("POST", "/v1/calls/call-1/signals", {
            "signalId": "offer-1",
            "senderId": "device-1",
            "type": "OFFER",
            "payload": {"sdp": "v=0"},
            "createdAtEpochMillis": 1_786_000_000_010,
        })
        self.assertEqual(200, status)
        status, answer = self.request("POST", "/v1/calls/call-1/signals", {
            "signalId": "answer-1",
            "senderId": "viewer-1",
            "type": "ANSWER",
            "payload": {"sdp": "v=0", "offerSequence": offer["sequence"]},
            "createdAtEpochMillis": 1_786_000_000_011,
        })
        self.assertEqual(200, status)
        self.assertGreater(answer["sequence"], offer["sequence"])

    def test_status_track_and_alert_are_idempotent(self) -> None:
        requests = [
            ("/v1/device-status", {"messageId": "status-1", "deviceId": "device-1"}),
            ("/v1/tracks:batch", {"points": [self.track_point(1)]}),
            ("/v1/alerts", {
                "messageId": "alert-message-1", "alertId": "alert-1", "deviceId": "device-1"
            }),
        ]
        for path, body in requests:
            self.assertEqual(200, self.request("POST", path, body)[0])
            self.assertEqual(200, self.request("POST", path, body)[0])

    def test_track_and_alert_readback_supports_board_verification(self) -> None:
        for sequence in (1, 2):
            status, _ = self.request("POST", "/v1/tracks:batch", {"points": [self.track_point(sequence)]})
            self.assertEqual(200, status)
        status, page = self.request(
            "GET", "/v1/tracks?deviceId=device-1&afterSequence=0&limit=100"
        )
        self.assertEqual(200, status)
        self.assertEqual([1, 2], [point["sequence"] for point in page["points"]])

        invalid = self.track_point(3)
        invalid["latitude"] = 91.0
        self.assertEqual(400, self.request("POST", "/v1/tracks:batch", {"points": [invalid]})[0])

        for active, suffix in ((True, "on"), (False, "off")):
            status, _ = self.request("POST", "/v1/alerts", {
                "messageId": f"alert-{suffix}",
                "alertId": "geofence-1",
                "deviceId": "device-1",
                "active": active,
            })
            self.assertEqual(200, status)
        status, alert = self.request("GET", "/v1/alerts/geofence-1")
        self.assertEqual(200, status)
        self.assertFalse(alert["active"])
        self.assertEqual(["ACTIVATED", "CLEARED"], [event["kind"] for event in alert["events"]])

    def test_resumable_media_upload(self) -> None:
        payload = b"helmet-media"
        digest = hashlib.sha256(payload).hexdigest()
        status, session = self.request("POST", "/v1/media/sessions", {
            "mediaId": "media-1",
            "deviceId": "device-1",
            "kind": "VIDEO",
            "mimeType": "video/mp4",
            "byteSize": len(payload),
            "sha256": digest,
            "createdAtEpochMillis": 1_786_000_000_000,
            "relatedEventId": "event-1",
            "width": 1920,
            "height": 1080,
            "durationMillis": 2000,
            "personId": "person-1",
            "location": {
                "latitude": 30.0,
                "longitude": 114.0,
                "horizontalAccuracyMeters": 1.0,
                "fixType": "RTK_FIXED",
            },
        })
        self.assertEqual(200, status)
        request = Request(
            self.base_url + f"/v1/media/sessions/{session['sessionId']}/chunks",
            data=payload,
            method="PUT",
            headers={
                "Authorization": f"Bearer {TOKEN}",
                "Content-Type": "application/octet-stream",
                "Content-Range": f"bytes 0-{len(payload) - 1}/{len(payload)}",
                "X-Chunk-SHA256": digest,
            },
        )
        with urlopen(request, timeout=5) as response:
            self.assertEqual(len(payload), json.loads(response.read())["nextOffset"])
        status, completed = self.request(
            "POST",
            f"/v1/media/sessions/{session['sessionId']}/complete",
            {"mediaId": "media-1", "byteSize": len(payload), "sha256": digest},
        )
        self.assertEqual(200, status)
        self.assertEqual("COMPLETED", completed["status"])
        status, archived = self.request("GET", "/v1/media/media-1")
        self.assertEqual(200, status)
        self.assertEqual("media-1", archived["mediaId"])
        self.assertEqual("COMPLETED", archived["status"])
        self.assertEqual(digest, archived["sha256"])

    def test_media_upload_rejects_invalid_visual_metadata_and_oversized_chunk(self) -> None:
        payload = b"x" * 65537
        digest = hashlib.sha256(payload).hexdigest()
        metadata = {
            "mediaId": "media-invalid",
            "deviceId": "device-1",
            "kind": "PHOTO",
            "mimeType": "image/jpeg",
            "byteSize": len(payload),
            "sha256": digest,
            "createdAtEpochMillis": 1_786_000_000_000,
            "relatedEventId": None,
            "width": 4160,
            "height": 3120,
            "durationMillis": None,
            "personId": None,
            "location": {
                "latitude": None,
                "longitude": None,
                "horizontalAccuracyMeters": None,
                "fixType": "NO_FIX",
            },
        }
        invalid = dict(metadata)
        invalid["location"] = dict(metadata["location"], latitude=91.0, longitude=114.0)
        self.assertEqual(400, self.request("POST", "/v1/media/sessions", invalid)[0])

        status, session = self.request("POST", "/v1/media/sessions", metadata)
        self.assertEqual(200, status)
        request = Request(
            self.base_url + f"/v1/media/sessions/{session['sessionId']}/chunks",
            data=payload,
            method="PUT",
            headers={
                "Authorization": f"Bearer {TOKEN}",
                "Content-Type": "application/octet-stream",
                "Content-Range": f"bytes 0-{len(payload) - 1}/{len(payload)}",
                "X-Chunk-SHA256": digest,
            },
        )
        with self.assertRaises(HTTPError) as captured:
            urlopen(request, timeout=5)
        self.assertEqual(400, captured.exception.code)

    def test_voice_message_can_be_listed_and_played_back_as_authenticated_content(self) -> None:
        payload = b"encoded-audio-message"
        digest = hashlib.sha256(payload).hexdigest()
        status, session = self.request("POST", "/v1/media/sessions", {
            "mediaId": "voice-1",
            "deviceId": "device-1",
            "kind": "VOICE",
            "mimeType": "audio/mp4",
            "byteSize": len(payload),
            "sha256": digest,
            "createdAtEpochMillis": 1_786_000_000_000,
            "relatedEventId": "voice-event-1",
            "durationMillis": 1200,
            "senderId": "device-1",
            "senderRole": "DEVICE",
            "allowedRoles": ["DISPATCHER", "ADMIN"],
            "callId": None,
        })
        self.assertEqual(200, status)
        request = Request(
            self.base_url + f"/v1/media/sessions/{session['sessionId']}/chunks",
            data=payload,
            method="PUT",
            headers={
                "Authorization": f"Bearer {TOKEN}",
                "Content-Type": "application/octet-stream",
                "Content-Range": f"bytes 0-{len(payload) - 1}/{len(payload)}",
                "X-Chunk-SHA256": digest,
            },
        )
        with urlopen(request, timeout=5) as response:
            self.assertEqual(200, response.status)
        status, completed = self.request(
            "POST",
            f"/v1/media/sessions/{session['sessionId']}/complete",
            {"mediaId": "voice-1", "byteSize": len(payload), "sha256": digest},
        )
        self.assertEqual(200, status)
        self.assertEqual("COMPLETED", completed["status"])

        status, page = self.request("GET", "/v1/media?deviceId=device-1&kind=VOICE&limit=20")
        self.assertEqual(200, status)
        self.assertEqual(["voice-1"], [item["mediaId"] for item in page["media"]])
        status, content, content_type = self.request_bytes("/v1/media/voice-1/content")
        self.assertEqual(200, status)
        self.assertEqual(payload, content)
        self.assertEqual("audio/mp4", content_type)


if __name__ == "__main__":
    unittest.main()
