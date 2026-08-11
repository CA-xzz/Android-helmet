package com.example.helmet.feature.connectivity

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection

object HttpConnectionPolicy {
    fun apply(connection: HttpURLConnection): HttpURLConnection = connection.apply {
        instanceFollowRedirects = false
    }

    @Throws(IOException::class)
    fun readUtf8Response(
        connection: HttpURLConnection,
        statusCode: Int,
        maximumBytes: Int,
    ): String {
        require(maximumBytes > 0) { "maximum response size must be positive" }
        val declaredLength = connection.contentLengthLong
        if (declaredLength > maximumBytes) {
            throw HttpResponseTooLargeException(maximumBytes)
        }
        val input = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            ?: return ""
        return input.use { stream ->
            val initialCapacity = when {
                declaredLength in 0..maximumBytes.toLong() -> declaredLength.toInt()
                else -> minOf(DEFAULT_INITIAL_CAPACITY, maximumBytes)
            }
            val output = ByteArrayOutputStream(initialCapacity)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() > maximumBytes - count) {
                    throw HttpResponseTooLargeException(maximumBytes)
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }
    }

    private const val DEFAULT_INITIAL_CAPACITY = 8 * 1024
}

class HttpResponseTooLargeException(maximumBytes: Int) :
    IOException("HTTP response exceeds $maximumBytes bytes")
