package com.example.helmet.hardware.api

import android.content.ContentProviderClient
import android.content.Context
import android.os.IBinder
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connection to the private hardware-process provider.
 *
 * Keeping the unstable [ContentProviderClient] open gives callers a provider reference without
 * allowing a provider crash to take down the main process. The provider itself is not exported,
 * and every AIDL call also enforces the application UID in the remote process.
 */
class HardwareProviderConnection private constructor(
    private val client: ContentProviderClient,
    val binder: IBinder,
) : Closeable {
    private val closed = AtomicBoolean()

    val service: IHelmetHardwareService = IHelmetHardwareService.Stub.asInterface(binder)

    val authority: String
        get() = HARDWARE_PROVIDER_AUTHORITY

    override fun close() {
        if (closed.compareAndSet(false, true)) client.close()
    }

    companion object {
        const val HARDWARE_PROVIDER_AUTHORITY = "com.example.helmet.hardware"
        const val HARDWARE_PROVIDER_CLASS =
            "com.example.helmet.hardware.service.HelmetHardwareProvider"
        const val METHOD_GET_SERVICE_BINDER = "getHardwareServiceBinder"
        const val RESULT_SERVICE_BINDER = "hardwareServiceBinder"

        fun connect(context: Context): HardwareProviderConnection {
            val client = checkNotNull(
                context.applicationContext.contentResolver.acquireUnstableContentProviderClient(
                    HARDWARE_PROVIDER_AUTHORITY,
                ),
            ) { "hardware provider is unavailable" }
            return try {
                val response = checkNotNull(
                    client.call(METHOD_GET_SERVICE_BINDER, null, null),
                ) { "hardware provider returned no response" }
                val binder = checkNotNull(response.getBinder(RESULT_SERVICE_BINDER)) {
                    "hardware provider returned no service binder"
                }
                check(binder.pingBinder()) { "hardware provider returned a dead service binder" }
                check(binder.interfaceDescriptor == IHelmetHardwareService.Stub.DESCRIPTOR) {
                    "hardware provider returned an unexpected binder interface"
                }
                HardwareProviderConnection(client, binder)
            } catch (error: Throwable) {
                client.close()
                throw error
            }
        }
    }
}
