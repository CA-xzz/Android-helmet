package com.example.helmet.service.runtime

import org.json.JSONObject

/**
 * Diagnostic details that are safe to retain in Room and local events. Throwable messages are
 * intentionally excluded because transport errors can contain URLs, response bodies, or secrets.
 */
internal data class PersistedFailure(
    val errorType: String,
    val statusCode: Int?,
    val reason: String,
) {
    init {
        require(SAFE_ERROR_TYPE.matches(errorType))
        require(statusCode == null || statusCode in 100..599)
        require(SAFE_REASON.matches(reason))
    }

    fun asStorageText(): String = JSONObject(toEventFields()).toString()

    fun toEventFields(): Map<String, Any> = mapOf(
        "errorType" to errorType,
        "statusCode" to (statusCode ?: JSONObject.NULL),
        "reason" to reason,
    )
}

internal fun persistedFailure(
    error: Throwable,
    statusCode: Int?,
    reason: String,
): PersistedFailure = PersistedFailure(
    errorType = error.javaClass.name.takeIf(SAFE_ERROR_TYPE::matches)
        ?: Throwable::class.java.name,
    statusCode = statusCode?.takeIf { it in 100..599 },
    reason = reason,
)

private val SAFE_ERROR_TYPE = Regex("[A-Za-z_$][A-Za-z0-9_.$]{0,255}")
private val SAFE_REASON = Regex("[A-Z][A-Z0-9_]{0,127}")
