package com.example.helmet.hardware.api

import android.content.ContentProviderClient
import android.content.Context
import android.os.IBinder
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Private connection to the raw UART4 runtime in the App hardware process. */
class RtkSerialProviderConnection private constructor(
    private val client: ContentProviderClient,
    val binder: IBinder,
) : Closeable {
    private val closed = AtomicBoolean()
    val service: IRtkSerialService = IRtkSerialService.Stub.asInterface(binder)

    override fun close() {
        if (closed.compareAndSet(false, true)) client.close()
    }

    companion object {
        const val METHOD_GET_RTK_SERVICE_BINDER = "getRtkSerialServiceBinder"
        const val RESULT_RTK_SERVICE_BINDER = "rtkSerialServiceBinder"

        fun connect(context: Context): RtkSerialProviderConnection {
            val client = checkNotNull(
                context.applicationContext.contentResolver.acquireUnstableContentProviderClient(
                    HardwareProviderConnection.HARDWARE_PROVIDER_AUTHORITY,
                ),
            ) { "hardware provider is unavailable" }
            return try {
                val response = checkNotNull(
                    client.call(METHOD_GET_RTK_SERVICE_BINDER, null, null),
                ) { "hardware provider returned no RTK response" }
                val binder = checkNotNull(response.getBinder(RESULT_RTK_SERVICE_BINDER)) {
                    "hardware provider returned no RTK service"
                }
                check(binder.pingBinder()) { "hardware provider returned a dead RTK service" }
                check(binder.interfaceDescriptor == IRtkSerialService.Stub.DESCRIPTOR) {
                    "hardware provider returned an unexpected RTK interface"
                }
                RtkSerialProviderConnection(client, binder)
            } catch (error: Throwable) {
                client.close()
                throw error
            }
        }
    }
}
