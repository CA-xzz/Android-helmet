package com.example.helmet.service.runtime

import com.example.helmet.core.model.EventSeverity
import com.example.helmet.data.local.EventStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object CrashCapture {
    @Volatile
    private var installedHandler: CapturingExceptionHandler? = null

    @Synchronized
    fun install(eventStore: EventStore) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        installedHandler?.takeIf { previous === it }?.let { handler ->
            handler.eventStore = eventStore
            return
        }

        val handler = CapturingExceptionHandler(eventStore, previous)
        installedHandler = handler
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    private class CapturingExceptionHandler(
        @Volatile var eventStore: EventStore,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                awaitCrashPersistence(CRASH_PERSIST_TIMEOUT_MILLIS) {
                    runBlocking {
                        eventStore.record(
                            eventType = "APP_CRASH",
                            severity = EventSeverity.CRITICAL,
                            payloadJson = crashPayload(thread.name, error),
                        )
                    }
                }
            }
            StructuredLogger.error("uncaught_exception", error, mapOf("thread" to thread.name))
            previous?.uncaughtException(thread, error)
        }
    }

    private const val CRASH_PERSIST_TIMEOUT_MILLIS = 750L
}

internal fun awaitCrashPersistence(timeoutMillis: Long, persist: () -> Unit): Boolean {
    require(timeoutMillis > 0)
    val completed = CountDownLatch(1)
    val writer = Thread(
        {
            try {
                persist()
            } finally {
                completed.countDown()
            }
        },
        "helmet-crash-persistence",
    ).apply { isDaemon = true }
    writer.start()
    return completed.await(timeoutMillis, TimeUnit.MILLISECONDS)
}

internal fun crashPayload(threadName: String, error: Throwable): String = JSONObject()
    .put("exception", error.javaClass.name)
    .also { payload ->
        threadName.take(MAX_THREAD_NAME_LENGTH)
            .takeIf(SAFE_THREAD_NAME::matches)
            ?.let { safeName -> payload.put("thread", safeName) }
    }
    .toString()

private const val MAX_THREAD_NAME_LENGTH = 128
private val SAFE_THREAD_NAME = Regex("[A-Za-z0-9_.:-]{1,128}")
