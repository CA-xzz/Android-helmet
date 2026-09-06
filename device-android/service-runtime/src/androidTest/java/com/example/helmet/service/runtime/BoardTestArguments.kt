package com.example.helmet.service.runtime

internal const val BACKEND_BEARER_TOKEN_ARGUMENT = "backendBearerToken"

internal fun requireBoardBackendBearerToken(raw: String?): String =
    requireNotNull(raw?.takeIf(String::isNotBlank)) {
        "$BACKEND_BEARER_TOKEN_ARGUMENT instrumentation argument is required"
    }
