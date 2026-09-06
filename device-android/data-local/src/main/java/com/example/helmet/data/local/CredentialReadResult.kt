package com.example.helmet.data.local

internal enum class CredentialReadState {
    AVAILABLE,
    MISSING,
    CORRUPTED,
}

internal class CredentialReadResult private constructor(
    val state: CredentialReadState,
    val value: String,
    val errorType: String?,
) {
    override fun toString(): String = "CredentialReadResult(state=$state, errorType=$errorType)"

    companion object {
        fun available(value: String) = CredentialReadResult(CredentialReadState.AVAILABLE, value, null)
        fun missing() = CredentialReadResult(CredentialReadState.MISSING, "", null)
        fun corrupted(errorType: String) = CredentialReadResult(
            CredentialReadState.CORRUPTED,
            "",
            errorType.take(120),
        )
    }
}
