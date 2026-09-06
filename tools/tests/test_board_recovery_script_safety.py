from __future__ import annotations

import re
import subprocess
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_NAMES = (
    "run-board-durable-offline-recovery.sh",
    "run-board-automatic-startup-recovery.sh",
    "run-board-geofence-uart-e2e.sh",
)
SCRIPT_PATHS = {name: PROJECT_ROOT / "tools" / name for name in SCRIPT_NAMES}


class BoardRecoveryScriptSafetyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = {
            name: path.read_text(encoding="utf-8")
            for name, path in SCRIPT_PATHS.items()
        }

    def test_scripts_are_valid_and_require_an_explicit_device(self) -> None:
        for name, path in SCRIPT_PATHS.items():
            with self.subTest(script=name):
                result = subprocess.run(
                    ["sh", "-n", str(path)],
                    check=False,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(0, result.returncode, result.stderr)
                source = self.sources[name]
                self.assertIn('[ -n "$ADB_SERIAL" ] ||', source)
                self.assertIsNone(re.search(r"^ADB_SERIAL=\S+", source, re.MULTILINE))

    def test_main_package_and_vendor_autostart_are_never_destroyed(self) -> None:
        for name, source in self.sources.items():
            with self.subTest(script=name):
                self.assertNotIn('uninstall "$APP_PACKAGE"', source)
                self.assertNotRegex(source, re.compile(r"\bpm\s+clear\b"))
                self.assertNotIn("/data/myautorun.sh", source)
                for line in source.splitlines():
                    stripped = line.lstrip()
                    if stripped.startswith("adb ") or "$(adb " in line:
                        self.assertIn('adb -s "$ADB_SERIAL"', line)

    def test_tokens_are_ephemeral_and_never_printed(self) -> None:
        for name, source in self.sources.items():
            with self.subTest(script=name):
                self.assertIn("TEST_TOKEN=$(generate_test_token)", source)
                self.assertIn("openssl rand -hex 32", source)
                self.assertNotIn("set -x", source)
                self.assertNotRegex(
                    source,
                    re.compile(r"(?:echo|printf)[^\n]*TEST_TOKEN"),
                )

    def test_cleanup_preserves_status_and_uses_signal_exit_codes(self) -> None:
        for name, source in self.sources.items():
            with self.subTest(script=name):
                for expected in (
                    "EXIT_STATUS=$?",
                    "trap - EXIT HUP INT TERM",
                    "trap cleanup EXIT",
                    "trap 'exit 129' HUP",
                    "trap 'exit 130' INT",
                    "trap 'exit 143' TERM",
                    'exit "$EXIT_STATUS"',
                ):
                    self.assertIn(expected, source)
                self.assertNotIn("trap cleanup EXIT HUP INT TERM", source)

    def test_instrumentation_requires_exit_zero_and_one_exact_terminal(self) -> None:
        for name, source in self.sources.items():
            with self.subTest(script=name):
                self.assertIn("INSTRUMENT_STATUS=$?", source)
                self.assertIn("'^OK \\(1 test\\)$'", source)
                self.assertIn(
                    "FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED",
                    source,
                )
                self.assertNotRegex(
                    source,
                    re.compile(r"am instrument[^\n]*(?:\\\n[^\n]*)*\|\s*tee"),
                )

    def test_backend_readiness_belongs_to_the_started_process(self) -> None:
        for name, source in self.sources.items():
            with self.subTest(script=name):
                self.assertIn('kill -0 "$BACKEND_PID"', source)
                self.assertRegex(
                    source,
                    re.compile(
                        r'\[ "\$READY" = true \] && kill -0 "\$BACKEND_PID"'
                    ),
                )

    def test_backend_counts_use_the_sanitized_request_log_contract(self) -> None:
        expected_patterns = {
            "run-board-durable-offline-recovery.sh": (
                "request method=POST path=/v1/tracks:batch status=200",
                "request method=POST path=/v1/media/sessions/[^/ ]+/complete status=200",
                "request method=POST path=/v1/alerts status=200",
                "request method=POST path=/v1/calls status=200",
                "request method=POST path=/v1/device-commands/[^/ ]+/ack status=200",
                "request method=POST path=/v1/broadcasts/[^/ ]+/receipts status=200",
            ),
            "run-board-automatic-startup-recovery.sh": (
                "request method=POST path=/v1/tracks:batch status=200",
                "request method=PUT path=/v1/media/sessions/[^/ ]+/chunks status=200",
                "request method=POST path=/v1/media/sessions/[^/ ]+/complete status=200",
                "request method=POST path=/v1/alerts status=200",
                "request method=POST path=/v1/calls status=200",
            ),
            "run-board-geofence-uart-e2e.sh": (
                "request method=POST path=/v1/tracks:batch status=200",
                "request method=POST path=/v1/alerts status=200",
            ),
        }
        for name, patterns in expected_patterns.items():
            source = self.sources[name]
            with self.subTest(script=name):
                self.assertNotIn("HTTP/1.1", source)
                for pattern in patterns:
                    self.assertIn(pattern, source)

    def test_backend_gate_failures_print_safe_counts_before_exit(self) -> None:
        expected_count_labels = {
            "run-board-durable-offline-recovery.sh": (
                "backend_track_posts=",
                "backend_media_completes=",
                "backend_alert_posts=",
                "backend_call_posts=",
                "backend_command_ack_posts=",
                "backend_broadcast_receipt_posts=",
            ),
            "run-board-automatic-startup-recovery.sh": (
                "backend_track_posts=",
                "backend_media_chunks=",
                "backend_media_completes=",
                "backend_alert_posts=",
                "backend_call_posts=",
            ),
            "run-board-geofence-uart-e2e.sh": (
                "backend_track_posts=",
                "backend_alert_posts=",
            ),
        }
        for name, labels in expected_count_labels.items():
            source = self.sources[name]
            error_index = source.index('echo "ERROR: backend did not observe')
            block_start = source.rfind("|| {", 0, error_index)
            block_end = source.index("exit 1", error_index)
            with self.subTest(script=name):
                self.assertGreaterEqual(block_start, 0)
                body = source[block_start:block_end]
                self.assertNotIn("backend.log", body)
                for label in labels:
                    self.assertIn(label, body)

    def test_main_application_scenarios_restore_foreground_service(self) -> None:
        for name in (
            "run-board-automatic-startup-recovery.sh",
            "run-board-geofence-uart-e2e.sh",
        ):
            source = self.sources[name]
            with self.subTest(script=name):
                self.assertIn("restore_helmet_service()", source)
                self.assertIn("shell am start-foreground-service --user 0", source)
                self.assertIn("dumpsys activity services", source)
                self.assertIn("isForeground=true", source)
                self.assertIn("if ! restore_helmet_service", source)
                self.assertIn("RECOVERY_PENDING=true", source)
                self.assertIn("RECOVERY_PENDING=false", source)

        startup = self.sources["run-board-automatic-startup-recovery.sh"]
        self.assertIn("run_recovery_cleanup", startup)
        self.assertIn("run_phase recover", startup)
        cleanup_body = startup[
            startup.index("run_recovery_cleanup()") : startup.index("run_phase()")
        ]
        self.assertIn("automaticStartupRecoveryPhase cleanup", cleanup_body)
        self.assertNotIn("automaticStartupRecoveryPhase recover", cleanup_body)

        geofence = self.sources["run-board-geofence-uart-e2e.sh"]
        self.assertIn("run_geofence_instrumentation cleanup-retry", geofence)


if __name__ == "__main__":
    unittest.main()
