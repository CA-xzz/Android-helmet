package com.example.helmet

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helmet.service.runtime.BootReceiver
import com.example.helmet.service.runtime.ShutdownReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalConfigurationManifestInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun launcherIsExportedButLocalConfigurationFollowsBuildPolicy() {
        val launcher = context.packageManager.getActivityInfo(
            ComponentName(context, HelmetLauncherActivity::class.java),
            0,
        )
        val localConfiguration = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0,
        )

        assertTrue(launcher.exported)
        assertEquals(!BuildConfig.PRODUCTION_BUILD, localConfiguration.exported)
        assertEquals(
            HelmetLauncherActivity::class.java.name,
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.className,
        )
    }

    @Test
    fun bootReceiverIsNotAnExternalServiceStartEntryPoint() {
        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(context, BootReceiver::class.java),
            0,
        )

        assertTrue(receiver.enabled)
        assertFalse(receiver.exported)
    }

    @Test
    fun shutdownReceiverIsEnabledButNotExternallyCallable() {
        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(context, ShutdownReceiver::class.java),
            0,
        )

        assertTrue(receiver.enabled)
        assertFalse(receiver.exported)
    }
}
