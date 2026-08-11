package com.example.helmet.feature.connectivity

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class HttpConnectionPolicyTest {
    @Test
    fun disablesAutomaticRedirectsBeforeCredentialsAreAttached() {
        val connection = RecordingConnection()
        connection.instanceFollowRedirects = true

        val configured = HttpConnectionPolicy.apply(connection)

        assertFalse(configured.instanceFollowRedirects)
    }

    @Test
    fun readsSuccessfulAndErrorResponsesAtTheByteLimit() {
        val success = ResponseConnection(successBody = "12345678".toByteArray(), declaredLength = 8)
        val error = ResponseConnection(errorBody = "failure".toByteArray())

        assertEquals("12345678", HttpConnectionPolicy.readUtf8Response(success, 200, 8))
        assertEquals("failure", HttpConnectionPolicy.readUtf8Response(error, 503, 7))
    }

    @Test
    fun rejectsDeclaredAndStreamedResponsesBeyondTheByteLimit() {
        val declared = ResponseConnection(successBody = "small".toByteArray(), declaredLength = 9)
        val streamed = ResponseConnection(successBody = "123456789".toByteArray())

        assertThrows(HttpResponseTooLargeException::class.java) {
            HttpConnectionPolicy.readUtf8Response(declared, 200, 8)
        }
        assertThrows(HttpResponseTooLargeException::class.java) {
            HttpConnectionPolicy.readUtf8Response(streamed, 200, 8)
        }
    }

    @Test
    fun rejectsNonPositiveResponseLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpConnectionPolicy.readUtf8Response(ResponseConnection(), 204, 0)
        }
    }

    private class RecordingConnection : HttpURLConnection(URL("https://backend.example.test")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
    }

    private class ResponseConnection(
        private val successBody: ByteArray? = null,
        private val errorBody: ByteArray? = null,
        private val declaredLength: Long = -1,
    ) : HttpURLConnection(URL("https://backend.example.test")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getInputStream(): InputStream = ByteArrayInputStream(requireNotNull(successBody))
        override fun getErrorStream(): InputStream? = errorBody?.let(::ByteArrayInputStream)
        override fun getContentLengthLong(): Long = declaredLength
    }
}
