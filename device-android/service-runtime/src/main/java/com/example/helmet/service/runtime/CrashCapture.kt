package com.example.helmet.service.runtime

import com.example.helmet.core.model.EventSeverity
import com.example.helmet.data.local.EventStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
                runBlocking {
                    withContext(Dispatchers.IO) {
                        eventStore.record(
                            eventType = "APP_CRASH",
                            severity = EventSeverity.CRITICAL,
                            payloadJson = JSONObject(
                                mapOf(
                                    "thread" to thread.name,
                                    "exception" to error.javaClass.name,
                                    "message" to error.message,
                                ),
                            ).toString(),
                        )
                    }
                }
            }
            StructuredLogger.error("uncaught_exception", error, mapOf("thread" to thread.name))
            previous?.uncaughtException(thread, error)
        }
    }
}
