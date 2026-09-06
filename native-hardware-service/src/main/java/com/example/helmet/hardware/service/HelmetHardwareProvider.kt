package com.example.helmet.hardware.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.example.helmet.hardware.api.HardwareProviderConnection
import com.example.helmet.hardware.api.RtkSerialProviderConnection

/** Private bootstrap transport for the AIDL hardware runtime in the `:hardware` process. */
class HelmetHardwareProvider : ContentProvider() {
    private lateinit var runtime: HelmetHardwareRuntime
    private lateinit var rtkRuntime: RtkSerialRuntime

    override fun onCreate(): Boolean {
        val providerContext = checkNotNull(context) { "hardware provider has no context" }
        runtime = HelmetHardwareRuntime(providerContext.applicationInfo.uid)
        rtkRuntime = RtkSerialRuntime(providerContext.applicationInfo.uid)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceInternalCaller()
        check(arg == null && extras == null) { "hardware provider arguments are not supported" }
        return when (method) {
            HardwareProviderConnection.METHOD_GET_SERVICE_BINDER -> Bundle().apply {
                putBinder(HardwareProviderConnection.RESULT_SERVICE_BINDER, runtime.binder)
            }
            RtkSerialProviderConnection.METHOD_GET_RTK_SERVICE_BINDER -> Bundle().apply {
                putBinder(RtkSerialProviderConnection.RESULT_RTK_SERVICE_BINDER, rtkRuntime.binder)
            }
            else -> throw IllegalArgumentException("unsupported hardware provider method")
        }
    }

    override fun getType(uri: Uri): String? = throw UnsupportedOperationException()

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = throw UnsupportedOperationException()

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    private fun enforceInternalCaller() {
        val providerContext = checkNotNull(context) { "hardware provider has no context" }
        if (!isInternalHardwareCaller(Binder.getCallingUid(), providerContext.applicationInfo.uid)) {
            throw SecurityException("hardware provider caller UID is not the application UID")
        }
    }
}
