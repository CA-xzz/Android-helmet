from __future__ import annotations

import re
import unittest
from pathlib import Path
from xml.etree import ElementTree


PROJECT_ROOT = Path(__file__).resolve().parents[2]
PROVIDER_CLASS = ".HelmetHardwareProvider"
PROVIDER_AUTHORITY = "com.example.helmet.hardware"
SELFTEST_SERVICE_CLASS = ".debug.HardwareSelfTestService"
PERMISSION = "com.example.helmet.permission.BIND_HARDWARE_SERVICE"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def provider_element(path: Path) -> ElementTree.Element:
    root = ElementTree.parse(path).getroot()
    providers = [
        element
        for element in root.findall(".//provider")
        if element.get(f"{ANDROID}name") == PROVIDER_CLASS
    ]
    if len(providers) != 1:
        raise AssertionError(
            f"expected one {PROVIDER_CLASS} in {path}, found {len(providers)}"
        )
    return providers[0]


class AndroidHardwareProviderManifestTest(unittest.TestCase):
    def test_base_manifest_keeps_cross_process_provider_private(self) -> None:
        path = PROJECT_ROOT / "native-hardware-service" / "src" / "main" / "AndroidManifest.xml"
        root = ElementTree.parse(path).getroot()
        provider = provider_element(path)

        permission = root.find("permission")
        self.assertIsNotNone(permission)
        self.assertEqual(PERMISSION, permission.get(f"{ANDROID}name"))
        self.assertEqual("signature", permission.get(f"{ANDROID}protectionLevel"))
        self.assertEqual("false", provider.get(f"{ANDROID}exported"))
        self.assertEqual("false", provider.get(f"{ANDROID}grantUriPermissions"))
        self.assertEqual(PERMISSION, provider.get(f"{ANDROID}permission"))
        self.assertEqual(":hardware", provider.get(f"{ANDROID}process"))
        self.assertEqual(PROVIDER_AUTHORITY, provider.get(f"{ANDROID}authorities"))
        self.assertFalse(root.findall(".//service"), "production hardware module must be provider-only")
        self.assertFalse(
            (
                PROJECT_ROOT
                / "native-hardware-service"
                / "src"
                / "main"
                / "java"
                / "com"
                / "example"
                / "helmet"
                / "hardware"
                / "service"
                / "HelmetHardwareService.kt"
            ).exists()
        )

    def test_no_build_variant_overrides_the_hardware_provider_policy(self) -> None:
        source_root = PROJECT_ROOT / "native-hardware-service" / "src"
        variant_manifests = sorted(source_root.glob("*/AndroidManifest.xml"))
        self.assertEqual([source_root / "main" / "AndroidManifest.xml"], variant_manifests)

    def test_provider_surface_and_runtime_enforce_the_application_uid(self) -> None:
        source_root = (
            PROJECT_ROOT
            / "native-hardware-service"
            / "src"
            / "main"
            / "java"
            / "com"
            / "example"
            / "helmet"
            / "hardware"
            / "service"
        )
        provider_source = (source_root / "HelmetHardwareProvider.kt").read_text(encoding="utf-8")
        runtime_source = (source_root / "HelmetHardwareRuntime.kt").read_text(encoding="utf-8")

        self.assertIn("Binder.getCallingUid()", provider_source)
        self.assertIn("isInternalHardwareCaller", provider_source)
        self.assertIn("check(arg == null && extras == null)", provider_source)
        self.assertIn("METHOD_GET_SERVICE_BINDER", provider_source)
        self.assertIn("RESULT_SERVICE_BINDER", provider_source)
        self.assertEqual(6, runtime_source.count("            enforceInternalCaller()"))
        self.assertIn("Binder.getCallingUid()", runtime_source)
        self.assertIn("callingUid == applicationUid", runtime_source)
        self.assertIn("override fun onCallbackDied", runtime_source)
        self.assertIn("registeredCallbackCount == 0", runtime_source)
        self.assertIn("closePortAfterCallbackDeath()", runtime_source)
        self.assertIn("check(callbacks.register(callback))", runtime_source)

    def test_gateway_uses_unstable_provider_client_and_generation_safe_recovery(self) -> None:
        api_root = (
            PROJECT_ROOT
            / "device-android"
            / "hardware-api"
            / "src"
            / "main"
            / "java"
            / "com"
            / "example"
            / "helmet"
            / "hardware"
            / "api"
        )
        connection_source = (api_root / "HardwareProviderConnection.kt").read_text(
            encoding="utf-8"
        )
        gateway_source = (api_root / "BoundHardwareGateway.kt").read_text(encoding="utf-8")

        self.assertIn("acquireUnstableContentProviderClient", connection_source)
        self.assertNotIn("acquireContentProviderClient(", connection_source)
        self.assertIn("binder.interfaceDescriptor", connection_source)
        self.assertIn("IHelmetHardwareService.Stub.DESCRIPTOR", connection_source)
        self.assertIn("binder.linkToDeath(deathRecipient, 0)", gateway_source)
        self.assertIn("deathObserved.set(true)", gateway_source)
        self.assertIn("providerTransport?.binder === binder", gateway_source)
        self.assertIn("current?.binder !== deadBinder", gateway_source)
        self.assertIn("scheduleProviderReconnect()", gateway_source)
        self.assertIn("private val lifecycleMutex = Mutex()", gateway_source)
        self.assertIn("override suspend fun start() = lifecycleMutex.withLock", gateway_source)
        self.assertIn("lifecycleMutex.lock()", gateway_source)
        self.assertIn("withContext(NonCancellable)", gateway_source)
        self.assertIn("joinAll()", gateway_source)
        self.assertIn("launch(start = CoroutineStart.LAZY)", gateway_source)
        self.assertIn("!isActive || !bound || currentProviderTransport() !== transport", gateway_source)
        self.assertNotIn("bindService(", gateway_source)
        self.assertNotIn("ServiceConnection", gateway_source)

        start_source = gateway_source[
            gateway_source.index("    override suspend fun start()") :
            gateway_source.index("    override suspend fun stop()")
        ]
        self.assertIn("scheduleProviderReconnect()", start_source)
        self.assertIn("scheduledMonitor.start()", start_source)
        self.assertIn("initialFailure?.let { throw it }", start_source)
        self.assertNotIn("bound = false", start_source)
        self.assertLess(
            start_source.index("scheduleProviderReconnect()"),
            start_source.index("initialFailure?.let { throw it }"),
        )

        stop_source = gateway_source[
            gateway_source.index("    override suspend fun stop()") :
            gateway_source.index("    override suspend fun send(")
        ]
        close_port_index = stop_source.index("transport.service.closePort()")
        unregister_index = stop_source.index("transport.service.unregisterCallback(transport.callback)")
        unlink_index = stop_source.index("transport.binder.unlinkToDeath")
        close_client_index = stop_source.index("transport.connection.close()")
        self.assertLess(close_port_index, unregister_index)
        self.assertLess(unregister_index, unlink_index)
        self.assertLess(unlink_index, close_client_index)

        runtime_service_source = (
            PROJECT_ROOT
            / "device-android"
            / "service-runtime"
            / "src"
            / "main"
            / "java"
            / "com"
            / "example"
            / "helmet"
            / "service"
            / "runtime"
            / "HelmetService.kt"
        ).read_text(encoding="utf-8")
        self.assertIn(
            "HardwareRuntimeMaintenance.unregisterAndStop(hardwareMaintenanceRegistration)",
            runtime_service_source,
        )
        self.assertIn(
            "HardwareRuntimeMaintenance.startRegistered(hardwareMaintenanceRegistration)",
            runtime_service_source,
        )
        self.assertNotIn("lifecycleScope.launch { hardware.stop() }", runtime_service_source)

    def test_debug_selftest_component_is_private_and_signature_protected(self) -> None:
        manifest = (
            PROJECT_ROOT
            / "device-android"
            / "app"
            / "src"
            / "debug"
            / "AndroidManifest.xml"
        )
        root = ElementTree.parse(manifest).getroot()
        services = [
            element
            for element in root.findall(".//service")
            if element.get(f"{ANDROID}name") == SELFTEST_SERVICE_CLASS
        ]
        self.assertEqual(1, len(services))
        service = services[0]
        self.assertEqual("false", service.get(f"{ANDROID}exported"))
        self.assertEqual(PERMISSION, service.get(f"{ANDROID}permission"))
        self.assertIsNone(service.get(f"{ANDROID}process"))
        self.assertEqual("true", service.get(f"{ANDROID}stopWithTask"))

        for source_set in ("main", "release"):
            source_root = PROJECT_ROOT / "device-android" / "app" / "src" / source_set
            if source_root.exists():
                self.assertFalse(
                    any(
                        "HardwareSelfTest" in path.read_text(encoding="utf-8")
                        for path in source_root.rglob("*")
                        if path.is_file()
                    )
                )

    def test_debug_selftest_uses_production_provider_and_atomic_controlled_result(self) -> None:
        source = (
            PROJECT_ROOT
            / "device-android"
            / "app"
            / "src"
            / "debug"
            / "java"
            / "com"
            / "example"
            / "helmet"
            / "debug"
            / "HardwareSelfTestService.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("selfTestCheck(!providerInfo.exported)", source)
        self.assertIn("selfTestCheck(!providerInfo.grantUriPermissions)", source)
        self.assertIn("TARGET_HARDWARE_PROCESS", source)
        self.assertIn("connection.binder !is Binder", source)
        self.assertIn("providerInfo.applicationInfo.uid == Process.myUid()", source)
        self.assertIn("BIND_PERMISSION", source)
        self.assertIn("PermissionInfo.PROTECTION_SIGNATURE", source)
        self.assertNotIn("context.stopService(HelmetService.startIntent(context))", source)
        self.assertNotIn("stopRuntimeService()", source)
        self.assertIn("HardwareRuntimeMaintenance.pause()", source)
        self.assertIn("HardwareRuntimeMaintenance.resume(lease)", source)
        self.assertIn("HardwareRuntimeMaintenance.resumeCurrent()", source)
        self.assertIn("ownsProviderPort = true", source)
        self.assertIn("if (ownsProviderPort) runCatching { capturedService.closePort() }", source)
        self.assertIn("if (capturedService == null || capturedLease == null)", source)
        self.assertIn("progress.registeredCallbacksAfterResume != 1", source)
        self.assertIn("HardwareSelfTestStage.PROVIDER_CONNECT", source)
        self.assertIn("startForeground(", source)
        self.assertIn("stopForeground(STOP_FOREGROUND_REMOVE)", source)
        connect_index = source.index(
            "HardwareProviderConnection.connect(context).also(::setActiveConnection)"
        )
        pause_index = source.index("HardwareRuntimeMaintenance.pause()", connect_index)
        close_index = source.index("connectedService.closePort()", pause_index)
        policy_index = source.index("verifyPolicy(connection)", close_index)
        self.assertLess(connect_index, pause_index)
        self.assertLess(pause_index, close_index)
        self.assertLess(close_index, policy_index)
        destroy_index = source.index("    override fun onDestroy()")
        destroy_end = source.index("    companion object", destroy_index)
        destroy_source = source[destroy_index:destroy_end]
        self.assertIn("executor.shutdown()", destroy_source)
        self.assertNotIn("executor.shutdownNow()", destroy_source)
        self.assertNotIn("releaseActiveConnection()", destroy_source)
        self.assertIn("executionLock.lock()", source)
        self.assertIn("executionLock.unlock()", source)
        self.assertIn("result?.let", source)
        concurrent_source = source[
            source.index("    private fun verifyConcurrentFrames(") :
            source.index("    private fun verifyGenerationIsolation(")
        ]
        cancel_index = concurrent_source.index("future.cancel(true)")
        shutdown_index = concurrent_source.index("writers.shutdownNow()")
        drain_index = concurrent_source.index("awaitExecutorDrain(writers, reader)")
        close_index = concurrent_source.index("service.closePort()", drain_index)
        self.assertLess(cancel_index, shutdown_index)
        self.assertLess(shutdown_index, drain_index)
        self.assertLess(drain_index, close_index)
        self.assertIn("writers.awaitTermination", concurrent_source)
        self.assertIn("reader.awaitTermination", concurrent_source)
        self.assertIn("HardwareSelfTestProcessRecoveryRequiredException", concurrent_source)
        self.assertIn('errorCode = "EXECUTOR_DRAIN_FAILED"', source)
        self.assertIn('status = "RESTART_REQUIRED"', source)
        self.assertIn("AtomicFile(resultFile)", source)
        self.assertIn("stream.fd.sync()", source)
        self.assertIn("resultFile.renameTo(staleFile)", source)
        self.assertIn("atomicNewFile.delete()", source)
        self.assertIn("atomicBackupFile.delete()", source)
        self.assertIn('appendLine("nonce=${result.nonce}")', source)
        self.assertIn('appendLine("pass=${progress.passCount}")', source)
        self.assertIn('appendLine("schema=3")', source)
        self.assertIn("concurrent_writes_completed", source)
        self.assertIn("concurrent_bytes_observed", source)
        self.assertIn("concurrent_read_failure", source)
        self.assertIn("decoder_crc_errors", source)
        self.assertIn("registered_callbacks_before_pty", source)
        self.assertIn("registered_callbacks_after_resume", source)
        self.assertIn("activeConnection.getAndSet(null)?.close()", source)
        self.assertIn('errorCode = "${stage.name}_FAILED"', source)
        self.assertNotIn(".message", source)
        self.assertNotIn("stackTrace", source)
        self.assertEqual(1, source.count("private const val FRAME_COUNT = 96"))
        self.assertFalse(
            (
                PROJECT_ROOT
                / "device-android"
                / "app"
                / "src"
                / "androidTest"
                / "java"
                / "com"
                / "example"
                / "helmet"
                / "HardwareServiceInstrumentedTest.kt"
            ).exists()
        )

    def test_test_pty_is_enabled_only_in_debug_native_builds(self) -> None:
        build_source = (
            PROJECT_ROOT / "native-hardware-service" / "build.gradle.kts"
        ).read_text(encoding="utf-8")
        self.assertEqual(
            1,
            build_source.count('buildConfigField("boolean", "ALLOW_TEST_PTY", "true")'),
        )
        self.assertEqual(
            1,
            build_source.count('buildConfigField("boolean", "ALLOW_TEST_PTY", "false")'),
        )
        self.assertEqual(1, build_source.count('"-DHELMET_ENABLE_TEST_PTY=ON"'))
        self.assertEqual(1, build_source.count('"-DHELMET_ENABLE_TEST_PTY=OFF"'))

        native_source = (
            PROJECT_ROOT
            / "native-hardware-service"
            / "src"
            / "main"
            / "cpp"
            / "serial_port.cpp"
        ).read_text(encoding="utf-8")
        guarded_fixture = native_source[
            native_source.index("#if HELMET_ENABLE_TEST_PTY") :
            native_source.index("#endif", native_source.index("#if HELMET_ENABLE_TEST_PTY"))
        ]
        self.assertEqual(
            5,
            len(
                re.findall(
                    r"^Java_com_example_helmet_testfixture_AndroidTestPtyFixture_native",
                    guarded_fixture,
                    re.MULTILINE,
                )
            ),
        )

        app_main_policy = (
            PROJECT_ROOT
            / "device-android"
            / "app"
            / "src"
            / "main"
            / "java"
            / "com"
            / "example"
            / "helmet"
            / "LocalConfigurationPolicy.kt"
        ).read_text(encoding="utf-8")
        native_main_policy = (
            PROJECT_ROOT
            / "native-hardware-service"
            / "src"
            / "main"
            / "java"
            / "com"
            / "example"
            / "helmet"
            / "hardware"
            / "service"
            / "SerialDevicePathPolicy.kt"
        ).read_text(encoding="utf-8")
        self.assertNotIn("/dev/pts", app_main_policy)
        self.assertNotIn("/dev/pts", native_main_policy)
        self.assertIn("VariantSerialDevicePathPolicy", app_main_policy)
        self.assertIn("VariantSerialDevicePathPolicy", native_main_policy)

        for module, package_path in (
            ("device-android/app", Path("com/example/helmet")),
            (
                "native-hardware-service",
                Path("com/example/helmet/hardware/service"),
            ),
        ):
            debug_policy = (
                PROJECT_ROOT
                / module
                / "src"
                / "debug"
                / "java"
                / package_path
                / "VariantSerialDevicePathPolicy.kt"
            ).read_text(encoding="utf-8")
            release_policy = (
                PROJECT_ROOT
                / module
                / "src"
                / "release"
                / "java"
                / package_path
                / "VariantSerialDevicePathPolicy.kt"
            ).read_text(encoding="utf-8")
            self.assertIn("/dev/pts", debug_policy)
            self.assertNotIn("/dev/pts", release_policy)
            self.assertIn("acceptsAdditionalPath", release_policy)
            self.assertIn("= false", release_policy)
        self.assertEqual(
            5,
            len(
                re.findall(
                    r"^Java_com_example_helmet_debug_HardwareSelfTestPtyFixture_native",
                    guarded_fixture,
                    re.MULTILINE,
                )
            ),
        )


if __name__ == "__main__":
    unittest.main()
