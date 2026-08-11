package com.example.helmet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PermissionInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helmet.hardware.api.BoundHardwareGateway
import com.example.helmet.hardware.api.IHelmetHardwareService
import com.example.helmet.service.runtime.HelmetService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareServiceInstrumentedTest {
    @Test
    fun serviceUsesSignaturePermission() {
        val component = ComponentName(TARGET_PACKAGE, BoundHardwareGateway.HARDWARE_SERVICE_CLASS)
        val serviceInfo = context.packageManager.getServiceInfo(component, 0)
        val permissionName = serviceInfo.permission
        assertEquals("com.example.helmet.permission.BIND_HARDWARE_SERVICE", permissionName)

        val permissionInfo = context.packageManager.getPermissionInfo(permissionName, 0)
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, permissionInfo.protection)
        assertEquals(
            android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(permissionName),
        )
    }

    @Test
    fun invalidDevicePathIsRejected() {
        assertThrows(RuntimeException::class.java) {
            service.openPort("/data/local/tmp/not-a-uart", 115_200)
        }
        assertFalse(service.diagnostics.getBoolean("isOpen"))
    }

    @Test
    fun opensAndClosesBoardUart() {
        service.openPort("/dev/ttyAS2", 115_200)
        val opened = service.diagnostics
        assertTrue(opened.getBoolean("isOpen"))
        assertEquals("/dev/ttyAS2", opened.getString("devicePath"))
        assertEquals(115_200, opened.getInt("baudRate"))

        service.closePort()
        assertFalse(service.diagnostics.getBoolean("isOpen"))
    }

    companion object {
        private const val TARGET_PACKAGE = "com.example.helmet"
        private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        private lateinit var connection: ServiceConnection
        private lateinit var service: IHelmetHardwareService

        @JvmStatic
        @BeforeClass
        fun bindService() {
            context.startActivity(
                Intent().setComponent(ComponentName(TARGET_PACKAGE, "$TARGET_PACKAGE.MainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            SystemClock.sleep(500)
            context.stopService(HelmetService.startIntent(context))
            SystemClock.sleep(500)
            val connected = CountDownLatch(1)
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    service = IHelmetHardwareService.Stub.asInterface(binder)
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
            val intent = Intent().addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).setComponent(
                ComponentName(TARGET_PACKAGE, BoundHardwareGateway.HARDWARE_SERVICE_CLASS),
            )
            context.packageManager.getServiceInfo(intent.component!!, 0)
            assertTrue(
                "hardware service bind returned false",
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE),
            )
            assertTrue("hardware service bind timed out", connected.await(5, TimeUnit.SECONDS))
        }

        @JvmStatic
        @AfterClass
        fun unbindService() {
            runCatching { service.closePort() }
            runCatching { context.unbindService(connection) }
        }
    }
}
