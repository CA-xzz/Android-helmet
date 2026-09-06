from __future__ import annotations

import re
import subprocess
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = PROJECT_ROOT / "tools" / "run-board-connected-software-tests.sh"


class BoardConnectedSoftwareScriptTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = SCRIPT_PATH.read_text(encoding="utf-8")

    def test_shell_is_valid_executable_and_requires_explicit_serial(self) -> None:
        syntax = subprocess.run(
            ["sh", "-n", str(SCRIPT_PATH)],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, syntax.returncode, syntax.stderr)
        self.assertTrue(SCRIPT_PATH.stat().st_mode & 0o111)
        self.assertIn('[ -n "$ADB_SERIAL" ] ||', self.source)
        self.assertIn('[ -n "$HARDWARE_DEVICE_PATH" ] ||', self.source)
        self.assertIn('[ -n "$HARDWARE_BAUD_RATE" ] ||', self.source)
        self.assertNotRegex(self.source, re.compile(r"^ADB_SERIAL=\S+", re.MULTILINE))
        for line in self.source.splitlines():
            stripped = line.lstrip()
            if stripped.startswith("adb ") or "$(adb " in line:
                self.assertIn('adb -s "$ADB_SERIAL"', line)

    def test_script_never_uninstalls_or_clears_main_application_or_autostart(self) -> None:
        forbidden = (
            "/data/myautorun.sh",
            "connectedDebugAndroidTest",
            "pm clear",
            'uninstall "$APP_PACKAGE"',
            'uninstall "com.example.helmet"',
            "/dev/ttyAS2",
            "AudioHardwareInstrumentedTest",
            "opensAndClosesBoardUart",
        )
        for value in forbidden:
            self.assertNotIn(value, self.source)
        self.assertIn('shell pm path "$APP_PACKAGE"', self.source)

    def test_current_debug_app_is_built_and_overlaid_before_instrumentation(self) -> None:
        self.assertIn(
            'APP_APK="$PROJECT_ROOT/device-android/app/build/outputs/apk/debug/app-debug.apk"',
            self.source,
        )
        self.assertEqual(
            1,
            len(
                re.findall(
                    r"^\s*:device-android:app:assembleDebug\s*\\$",
                    self.source,
                    re.MULTILINE,
                )
            ),
        )
        self.assertEqual(1, self.source.count('install -r "$APP_APK"'))
        execution = self.source[self.source.index('echo "Building the current debug application'):]
        self.assertLess(
            execution.index('install -r "$APP_APK"'),
            execution.index("run_instrumentation data-local"),
        )
        self.assertIn("current main debug APK was not installed successfully", self.source)
        self.assertIn("^lib/arm64-v8a/libhelmet_serial\\.so$", self.source)
        self.assertIn("versionCode=3", self.source)
        self.assertIn("versionName=0\\.3\\.0", self.source)
        self.assertIn("(pkgFlags|flags)=\\[[^]]*DEBUGGABLE", self.source)
        self.assertNotIn("assembleRelease", self.source)
        self.assertNotIn("without modifying main application data", self.source)
        self.assertIn(
            "the main application was not uninstalled or cleared; persisted simulator mode "
            "was safely corrected to real hardware mode",
            self.source,
        )

    def test_real_hardware_parameters_are_mandatory_before_any_adb_operation(self) -> None:
        missing_both = subprocess.run(
            [str(SCRIPT_PATH), "--adb-serial", "not-a-device"],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(64, missing_both.returncode)
        self.assertIn("--hardware-device-path is required", missing_both.stdout)

        missing_baud = subprocess.run(
            [
                str(SCRIPT_PATH),
                "--adb-serial",
                "not-a-device",
                "--hardware-device-path",
                "/dev/not-used-by-static-test",
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(64, missing_baud.returncode)
        self.assertIn("--hardware-baud-rate is required", missing_baud.stdout)
        self.assertIn("^/dev/tty(AS|S|USB|ACM)[0-9]{1,3}$", self.source)
        self.assertIn("9600|19200|38400|57600|115200|230400|460800|921600", self.source)
        execution = self.source[self.source.index('[ -n "$ADB_SERIAL" ] ||'):]
        self.assertLess(
            execution.index('[ -n "$HARDWARE_DEVICE_PATH" ] ||'),
            execution.index('adb -s "$ADB_SERIAL"'),
        )
        self.assertLess(
            execution.index('[ -n "$HARDWARE_BAUD_RATE" ] ||'),
            execution.index('adb -s "$ADB_SERIAL"'),
        )

    def test_only_selected_nonempty_library_packages_are_built_and_run(self) -> None:
        selected_modules = (
            "data-local",
            "feature-camera",
            "feature-connectivity",
            "feature-location",
            "safety-detection",
            "webrtc-runtime",
            "service-runtime",
            "app",
        )
        for module in selected_modules:
            self.assertEqual(
                1,
                self.source.count(
                    f":device-android:{module}:assembleDebugAndroidTest"
                ),
            )
        empty_modules = (
            "alert-sync",
            "communication-sync",
            "core-model",
            "core-protocol",
            "hardware-api",
            "location-sync",
        )
        for module in empty_modules:
            self.assertNotIn(
                f":device-android:{module}:assembleDebugAndroidTest",
                self.source,
            )
        for package_name in (
            "com.example.helmet.data.local",
            "com.example.helmet.feature.camera",
            "com.example.helmet.feature.connectivity",
            "com.example.helmet.feature.location",
            "com.example.helmet.safety.detection",
            "com.example.helmet.webrtc",
        ):
            self.assertIn(f"    {package_name}\n", self.source)

    def test_service_runtime_is_restricted_to_the_thirteen_offline_selectors(self) -> None:
        expected = (
            "BoardTestArgumentsInstrumentedTest",
            "CallVolumeInstrumentedTest",
            "DeviceStatusWorkerInstrumentedTest#"
            "outboxSurvivesRecreationAndQuarantinesIncompleteSnapshot",
            "DurableAlarmAckInstrumentedTest",
            "GeofenceAlertFactoryInstrumentedTest",
            "HardwareKeyActionRecoveryInstrumentedTest",
            "HardwareCallToggleInstrumentedTest",
            "LocalIntercomControllerInstrumentedTest",
            "PersistentPreferencesSnapshotFixtureInstrumentedTest",
            "RtkCorrectionControllerInstrumentedTest",
            "SafetyCheckpointCrashRecoveryInstrumentedTest",
            "SafetyOutputRecoveryStoreInstrumentedTest",
            "SafetySampleProcessorInstrumentedTest",
        )
        selector_match = re.search(
            r"SERVICE_RUNTIME_SELECTORS=(.*?)\n\nAPP_MANIFEST_SELECTORS=",
            self.source,
            re.DOTALL,
        )
        self.assertIsNotNone(selector_match)
        selector_block = selector_match.group(1)
        for selector in expected:
            self.assertEqual(1, selector_block.count(selector))
        self.assertEqual(13, selector_block.count("com.example.helmet.service.runtime."))
        for online_test in (
            "CommunicationWorkerInstrumentedTest",
            "DurableOfflineRecoveryInstrumentedTest",
            "MediaBatchContinuationInstrumentedTest",
            "TrackUploadWorkerInstrumentedTest",
        ):
            self.assertNotIn(online_test, selector_block)

    def test_checkpoint_crash_recovery_selector_is_device_local_and_isolated(self) -> None:
        source = (
            PROJECT_ROOT
            / "device-android"
            / "service-runtime"
            / "src"
            / "androidTest"
            / "java"
            / "com"
            / "example"
            / "helmet"
            / "service"
            / "runtime"
            / "SafetyCheckpointCrashRecoveryInstrumentedTest.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("Room.inMemoryDatabaseBuilder", source)
        self.assertGreaterEqual(source.count("@Test"), 2)
        self.assertIn(
            "durableSampleIsReplayedWhenAlarmPersistenceFailsBeforeCheckpoint",
            source,
        )
        self.assertIn(
            "durableAlarmIsIdempotentlyReplayedWhenCheckpointCommitFails",
            source,
        )
        self.assertIn("assertTrue(acknowledgements.isEmpty())", source)
        self.assertIn("SAFETY_ALARM_RESULT_SUCCESS", source)
        for forbidden in (
            "ServerSocket",
            "Socket(",
            "HttpURLConnection",
            "WorkManager",
            "HelmetService",
            "getSharedPreferences",
            "/dev/",
            "Camera",
            "Audio",
        ):
            self.assertNotIn(forbidden, source)

    def test_app_general_instrumentation_is_restricted_to_manifest_policy(self) -> None:
        for class_name in (
            "BoardTestArgumentsInstrumentedTest",
            "DirectRtkHardwareGatewayInstrumentedTest",
            "DualSerialIsolationInstrumentedTest",
            "HslContractSimulatorInstrumentedTest",
            "LocalConfigurationManifestInstrumentedTest",
        ):
            self.assertIn(f"com.example.helmet.{class_name}", self.source)
        self.assertNotIn("APP_HARDWARE_SELECTORS", self.source)
        self.assertNotIn("HardwareServiceInstrumentedTest", self.source)
        self.assertIn("run_debug_hardware_selftest", self.source)
        self.assertLess(
            self.source.index("run_instrumentation app-manifest-policy"),
            self.source.rindex("run_debug_hardware_selftest"),
        )

    def test_real_hardware_mode_uses_one_exact_debug_selector_and_audited_restart(self) -> None:
        selector = (
            "com.example.helmet.RealHardwareModePreparationInstrumentedTest#"
            "persistProvisionedRealHardwareMode"
        )
        self.assertEqual(1, self.source.count(f"REAL_HARDWARE_MODE_SELECTOR={selector}"))
        preparation_match = re.search(
            r"run_real_hardware_mode_preparation\(\) \{(.*?)\n\}",
            self.source,
            re.DOTALL,
        )
        restart_match = re.search(
            r"restart_helmet_application_in_real_hardware_mode\(\) \{(.*?)\n\}",
            self.source,
            re.DOTALL,
        )
        capture_match = re.search(
            r"capture_and_force_stop_hardware_mode_generation\(\) \{(.*?)"
            r"\n\}\n\nread_hardware_mode_generation_state\(\)",
            self.source,
            re.DOTALL,
        )
        exit_match = re.search(
            r"wait_for_hardware_mode_generation_exit\(\) \{(.*?)\n\}",
            self.source,
            re.DOTALL,
        )
        replacement_match = re.search(
            r"wait_for_replacement_real_hardware_runtime\(\) \{(.*?)\n\}",
            self.source,
            re.DOTALL,
        )
        generation_state_match = re.search(
            r"read_hardware_mode_generation_state\(\) \{(.*?)\n\}",
            self.source,
            re.DOTALL,
        )
        self.assertIsNotNone(preparation_match)
        self.assertIsNotNone(restart_match)
        self.assertIsNotNone(capture_match)
        self.assertIsNotNone(exit_match)
        self.assertIsNotNone(replacement_match)
        self.assertIsNotNone(generation_state_match)
        preparation = preparation_match.group(1)
        restart = restart_match.group(1)
        capture = capture_match.group(1)
        generation_exit = exit_match.group(1)
        replacement = replacement_match.group(1)
        generation_state = generation_state_match.group(1)
        for required in (
            'class "$REAL_HARDWARE_MODE_SELECTOR"',
            "realHardwareModeNonce",
            "realHardwareModeConfirmation",
            "realHardwareDevicePath",
            "realHardwareBaudRate",
            'run-as "$APP_PACKAGE" cat',
            "schema=1",
            '"nonce=$PREPARATION_NONCE"',
            "simulator_enabled=false",
            "previous_revision=[1-9][0-9]*",
            "revision=[1-9][0-9]*",
        ):
            self.assertIn(required, preparation)
        self.assertIn(
            "REAL_HARDWARE_MODE_CONFIRMATION=PERSIST_REAL_UART_CONFIGURATION",
            self.source,
        )
        for forbidden in (
            "helmet_runtime_config.xml",
            "shared_prefs",
            "backendBearerToken",
            "rtkPassword",
            "pm clear",
            "force-stop",
        ):
            self.assertNotIn(forbidden, preparation)
        for required in (
            'shell id -u',
            'shell pm path "$APP_TEST_PACKAGE"',
            "requires a root ADB shell",
            "wait_for_stable_foreground_main_process",
            'VERIFIED_MAIN_PID=$(adb -s "$ADB_SERIAL" shell pidof "$APP_PACKAGE"',
            'VERIFIED_MAIN_UID=$(read_process_uid "$VERIFIED_MAIN_PID"',
            'VERIFIED_MAIN_COMMAND=$(read_process_cmdline "$VERIFIED_MAIN_PID"',
            'VERIFIED_MAIN_COMMAND" != "$APP_PACKAGE',
            "am force-stop --user 0 com.example.helmet",
            "REAL_HARDWARE_MODE_FORCE_STOP_REQUESTED=true",
            "REAL_HARDWARE_MODE_FORCE_STOP_COMPLETED=true",
        ):
            self.assertIn(required, capture)
        self.assertLess(
            capture.index('VERIFIED_MAIN_COMMAND=$(read_process_cmdline'),
            capture.index("am force-stop --user 0 com.example.helmet"),
        )
        self.assertLess(
            capture.index("REAL_HARDWARE_MODE_FORCE_STOP_REQUESTED=true"),
            capture.index("am force-stop --user 0 com.example.helmet"),
        )
        self.assertLess(
            capture.index("am force-stop --user 0 com.example.helmet"),
            capture.rindex("REAL_HARDWARE_MODE_FORCE_STOP_COMPLETED=true"),
        )
        self.assertIn("read_hardware_mode_generation_state", generation_exit)
        self.assertIn('"$REAL_HARDWARE_MODE_OLD_MAIN_PID"', generation_exit)
        self.assertIn('"$REAL_HARDWARE_MODE_OLD_PROVIDER_PID"', generation_exit)
        self.assertIn("/proc/$GENERATION_PID", generation_state)
        self.assertIn("echo ALIVE", generation_state)
        self.assertIn("echo GONE", generation_state)
        self.assertNotIn("dumpsys activity services", generation_exit)
        for required in (
            'pidof "$APP_PACKAGE"',
            'pidof "$HARDWARE_PROCESS"',
            "REAL_HARDWARE_MODE_OLD_MAIN_PID",
            "REAL_HARDWARE_MODE_OLD_PROVIDER_PID",
            "REAL_HARDWARE_MODE_EXPECTED_UID",
            'CANDIDATE_MAIN_COMMAND" = "$APP_PACKAGE',
            'CANDIDATE_PROVIDER_COMMAND" = "$HARDWARE_PROCESS',
            '"$HARDWARE_PROVIDER_AUTHORITY"',
            "HelmetHardwareProvider",
            "isForeground=true",
        ):
            self.assertIn(required, replacement)
        self.assertIn("capture_and_force_stop_hardware_mode_generation", restart)
        self.assertIn("wait_for_hardware_mode_generation_exit", restart)
        self.assertIn("restore_helmet_service", restart)
        self.assertIn("wait_for_replacement_real_hardware_runtime", restart)
        for required in (
            "capture_and_force_stop_hardware_mode_generation || return 1",
            "wait_for_hardware_mode_generation_exit || return 1",
            "restore_helmet_service || return 1",
            "wait_for_replacement_real_hardware_runtime || return 1",
        ):
            self.assertIn(required, restart)
        self.assertIn("recover_hardware_maintenance", restart)
        self.assertIn("registered_callbacks=1", restart)
        self.assertNotIn("uninstall", capture + generation_exit + replacement + restart)
        self.assertNotIn(
            'am stopservice --user 0 \\\n        -n "$SERVICE_COMPONENT"',
            self.source,
        )

        cleanup_match = re.search(r"cleanup\(\) \{(.*?)\n\}", self.source, re.DOTALL)
        self.assertIsNotNone(cleanup_match)
        cleanup = cleanup_match.group(1)
        self.assertIn("REAL_HARDWARE_MODE_RESTART_REQUIRED", cleanup)
        self.assertIn("restart_helmet_application_in_real_hardware_mode", cleanup)
        self.assertLess(
            preparation.index("REAL_HARDWARE_MODE_RESTART_REQUIRED=true"),
            preparation.index('run_instrumentation "$LABEL"'),
        )

        execution = self.source[self.source.index('echo "Building the current debug application'):]
        preparation_index = execution.index("run_real_hardware_mode_preparation")
        restart_index = execution.index("restart_helmet_application_in_real_hardware_mode")
        selftest_index = execution.rindex("run_debug_hardware_selftest")
        self.assertLess(preparation_index, restart_index)
        self.assertLess(restart_index, selftest_index)

        instrumentation_source = (
            PROJECT_ROOT
            / "device-android"
            / "app"
            / "src"
            / "androidTest"
            / "java"
            / "com"
            / "example"
            / "helmet"
            / "RealHardwareModePreparationInstrumentedTest.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("RuntimeConfigStore(context).persistRealHardwareMode", instrumentation_source)
        self.assertIn("BuildConfig.PRODUCTION_BUILD", instrumentation_source)
        self.assertIn("productionBuild = true", instrumentation_source)
        self.assertIn("AtomicFile", instrumentation_source)
        self.assertNotIn("getSharedPreferences", instrumentation_source)
        self.assertNotIn("backendBearerToken", instrumentation_source)
        self.assertNotIn("rtk.password", instrumentation_source)

    def test_private_debug_hardware_selftest_runs_after_test_package_removal(self) -> None:
        orchestration_match = re.search(
            r"run_debug_hardware_selftest\(\) \{(.*?)\n\}",
            self.source,
            re.DOTALL,
        )
        self.assertIsNotNone(orchestration_match)
        orchestration = orchestration_match.group(1)
        self.assertIn('shell id -u', orchestration)
        self.assertIn('shell pm path "$APP_TEST_PACKAGE"', orchestration)
        self.assertIn('shell am start-foreground-service --user 0', orchestration)
        self.assertIn('HARDWARE_SELFTEST_COMPONENT', orchestration)
        self.assertIn('START_LINE_COUNT', orchestration)
        self.assertIn('START_SUCCESS_COUNT', orchestration)
        self.assertIn("returned an unexpected start result", orchestration)
        self.assertIn('od -An -N16 -tx1 /dev/urandom', orchestration)
        self.assertIn('run-as "$APP_PACKAGE" cat', orchestration)
        self.assertIn('"nonce=$NONCE"', orchestration)
        self.assertIn('schema=3', orchestration)
        self.assertIn('status=PASS', orchestration)
        self.assertIn('pass=5', orchestration)
        self.assertIn('fail=0', orchestration)
        self.assertIn('frames_observed=96', orchestration)
        self.assertIn('concurrent_writes_completed=96', orchestration)
        self.assertIn('concurrent_bytes_observed=[1-9][0-9]*', orchestration)
        self.assertIn('concurrent_read_failure=NONE', orchestration)
        self.assertIn('decoder_crc_errors=0', orchestration)
        self.assertIn('registered_callbacks_before_pty=0', orchestration)
        self.assertIn('registered_callbacks_after_resume=1', orchestration)
        self.assertIn('error_code=NONE', orchestration)
        self.assertIn('error_type=NONE', orchestration)
        self.assertIn('pidof "$HARDWARE_PROCESS"', orchestration)
        self.assertIn('MAIN_UID=$(read_process_uid "$MAIN_PID"', orchestration)
        self.assertIn('HARDWARE_UID=$(read_process_uid "$HARDWARE_PID"', orchestration)
        self.assertIn('"$MAIN_UID" != "$HARDWARE_UID"', orchestration)
        self.assertIn('MAIN_PID_BEFORE_SELFTEST', orchestration)
        self.assertIn('verify_provider_crash_recovery', orchestration)
        self.assertLess(
            orchestration.index('shell pm path "$APP_TEST_PACKAGE"'),
            orchestration.index('shell am start-foreground-service --user 0'),
        )
        self.assertIn("requires a root ADB shell", orchestration)
        self.assertIn("clear_hardware_selftest_result", orchestration)
        self.assertIn("stop_hardware_selftest", orchestration)
        self.assertIn("restore_helmet_service", orchestration)
        self.assertNotIn("stop_hardware_service", orchestration)
        self.assertNotIn('stopservice --user 0 \\\n                -n "$SERVICE_COMPONENT"', orchestration)
        self.assertNotIn("am instrument", orchestration)
        self.assertNotIn("force-stop", orchestration)

    def test_hardware_restart_requires_a_strict_same_nonce_directive(self) -> None:
        validator_match = re.search(
            r"validate_hardware_process_restart_directive\(\) \{(.*?)\n\}",
            self.source,
            re.DOTALL,
        )
        marker_match = re.search(
            r"mark_hardware_process_restart_required\(\) \{(.*?)\n\}",
            self.source,
            re.DOTALL,
        )
        recovery_match = re.search(
            r"recover_hardware_maintenance\(\) \{(.*?)\n\}",
            self.source,
            re.DOTALL,
        )
        self.assertIsNotNone(validator_match)
        self.assertIsNotNone(marker_match)
        self.assertIsNotNone(recovery_match)
        validator = validator_match.group(1)
        marker = marker_match.group(1)
        recovery = recovery_match.group(1)
        for required in (
            "^[a-f0-9]{32}$",
            "-eq 5",
            "^schema=1$",
            '"^nonce=$EXPECTED_NONCE$"',
            "^status=RESTART_REQUIRED$",
            "^registered_callbacks=-1$",
            "EXECUTOR_DRAIN_FAILED|PROCESS_RESTART_REQUIRED",
        ):
            self.assertIn(required, validator)
        self.assertIn(
            'validate_hardware_process_restart_directive "$EXPECTED_NONCE" '
            '"$DIRECTIVE_FILE"',
            marker,
        )
        self.assertEqual(1, self.source.count("HARDWARE_PROCESS_RESTART_REQUIRED=true"))
        self.assertNotIn("HARDWARE_PROCESS_RESTART_REQUIRED=true", recovery)
        self.assertIn(
            'mark_hardware_process_restart_required "$RECOVERY_NONCE" '
            '"$RECOVERY_COPY"',
            recovery,
        )

    def test_selftest_waits_for_consecutive_stable_foreground_main_samples(self) -> None:
        wait_match = re.search(
            r"wait_for_stable_foreground_main_process\(\) \{(.*?)\n\}",
            self.source,
            re.DOTALL,
        )
        orchestration_match = re.search(
            r"run_debug_hardware_selftest\(\) \{(.*?)\n\}",
            self.source,
            re.DOTALL,
        )
        self.assertIsNotNone(wait_match)
        self.assertIsNotNone(orchestration_match)
        wait = wait_match.group(1)
        orchestration = orchestration_match.group(1)
        self.assertIn("FOREGROUND_STABLE_REQUIRED=4", wait)
        self.assertIn("FOREGROUND_STABLE_LIMIT=80", wait)
        self.assertIn('shell pidof "$APP_PACKAGE"', wait)
        self.assertIn('read_process_uid "$FOREGROUND_SAMPLE_PID"', wait)
        self.assertIn('read_process_cmdline "$FOREGROUND_SAMPLE_PID"', wait)
        self.assertIn("dumpsys activity services", wait)
        self.assertIn('"$SERVICE_COMPONENT"', wait)
        self.assertIn('FOREGROUND_SAMPLE_COMMAND" = "$APP_PACKAGE', wait)
        self.assertIn("isForeground=true", wait)
        self.assertIn("FOREGROUND_SAMPLE_SIGNATURE", wait)
        self.assertIn("FOREGROUND_STABLE_COUNT=0", wait)
        self.assertIn("STABLE_MAIN_PID=$FOREGROUND_SAMPLE_PID", wait)
        self.assertIn("wait_for_stable_foreground_main_process", orchestration)
        self.assertLess(
            orchestration.index("wait_for_stable_foreground_main_process"),
            orchestration.index("HARDWARE_SELFTEST_ACTIVE=true"),
        )

    def test_restore_failure_logs_only_filtered_service_summary(self) -> None:
        restore_match = re.search(
            r"restore_helmet_service\(\) \{(.*?)\n\}",
            self.source,
            re.DOTALL,
        )
        self.assertIsNotNone(restore_match)
        restore = restore_match.group(1)
        self.assertIn("LAST_PID_SUMMARY=missing", restore)
        self.assertIn("LAST_PID_SUMMARY=ambiguous", restore)
        self.assertIn("LAST_SERVICE_RECORD", restore)
        self.assertIn("LAST_SERVICE_FOREGROUND", restore)
        self.assertIn(
            "helmet_service_pid=%s helmet_service_foreground=%s "
            "helmet_service_record=%s",
            restore,
        )
        self.assertNotIn("\"$LAST_SERVICE_DUMP\" >&2", restore)

    def test_every_run_isolated_to_a_test_package_and_strict_result_count(self) -> None:
        self.assertIn('remove_test_package "$TEST_PACKAGE"', self.source)
        self.assertIn('install -r -t "$TEST_APK"', self.source)
        self.assertIn('-e package "$SELECTOR_VALUE"', self.source)
        self.assertIn('-e class "$SELECTOR_VALUE"', self.source)
        self.assertIn("did not produce exactly one OK (N tests) result", self.source)
        self.assertIn("FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED", self.source)
        self.assertIn("module-counts.tsv", self.source)
        self.assertIn("TOTAL_TESTS=$((TOTAL_TESTS + OK_COUNTS))", self.source)

    def test_trap_restores_and_verifies_main_foreground_service(self) -> None:
        self.assertIn("trap cleanup EXIT", self.source)
        self.assertIn("SERVICE_RECOVERY_REQUIRED=true", self.source)
        self.assertIn("HARDWARE_SELFTEST_ACTIVE=false", self.source)
        cleanup_match = re.search(
            r"cleanup\(\) \{(.*?)\n\}", self.source, re.DOTALL
        )
        self.assertIsNotNone(cleanup_match)
        self.assertIn("restore_helmet_service", cleanup_match.group(1))
        self.assertIn("stop_hardware_selftest", cleanup_match.group(1))
        self.assertIn("clear_hardware_selftest_result", cleanup_match.group(1))
        self.assertIn("wait_for_hardware_selftest_resume", cleanup_match.group(1))
        self.assertIn("recover_hardware_maintenance", cleanup_match.group(1))
        self.assertNotIn("abort_active_instrumentation", self.source)
        self.assertEqual(
            1,
            self.source.count('shell am force-stop --user 0 "$APP_PACKAGE"'),
        )
        self.assertEqual(1, self.source.count("am force-stop --user 0 com.example.helmet"))
        self.assertIn("recover_hardware_by_process_restart", cleanup_match.group(1))
        self.assertIn("HARDWARE_PROCESS_RESTART_REQUIRED", cleanup_match.group(1))
        self.assertIn("EXECUTOR_DRAIN_FAILED", self.source)
        self.assertIn("PROCESS_RESTART_REQUIRED", self.source)
        force_stop_index = self.source.index(
            'shell am force-stop --user 0 "$APP_PACKAGE"'
        )
        self.assertLess(
            self.source.index('VERIFIED_MAIN_COMMAND=$(read_process_cmdline'),
            force_stop_index,
        )
        self.assertLess(
            force_stop_index,
            self.source.index("restore_helmet_service || return 1", force_stop_index),
        )
        self.assertNotIn("stop_hardware_service()", self.source)
        self.assertIn("HARDWARE_PROVIDER_AUTHORITY=com.example.helmet.hardware", self.source)
        self.assertIn('shell pidof "$APP_PACKAGE"', self.source)
        self.assertIn('shell dumpsys activity services "$SERVICE_COMPONENT"', self.source)
        self.assertIn("isForeground=true", self.source)
        self.assertIn("--ez recovery_only true", self.source)
        self.assertIn('shell kill -9 "$OLD_HARDWARE_PID"', self.source)
        self.assertIn('VERIFIED_OLD_HARDWARE_PID', self.source)
        self.assertIn('VERIFIED_OLD_HARDWARE_COMMAND', self.source)
        self.assertIn('[ "$VERIFIED_OLD_HARDWARE_PID" != "$OLD_HARDWARE_PID" ]', self.source)
        self.assertIn('[ "$VERIFIED_OLD_HARDWARE_UID" != "$EXPECTED_UID" ]', self.source)
        self.assertIn('[ "$VERIFIED_OLD_HARDWARE_COMMAND" != "$HARDWARE_PROCESS" ]', self.source)
        self.assertLess(
            self.source.index('VERIFIED_OLD_HARDWARE_COMMAND=$(read_process_cmdline'),
            self.source.index('shell kill -9 "$OLD_HARDWARE_PID"'),
        )
        self.assertIn("provider_crash_recovered=true", self.source)
        self.assertIn(
            "com\\.example\\.helmet/\\.service\\.runtime\\.HelmetService",
            self.source,
        )


if __name__ == "__main__":
    unittest.main()
