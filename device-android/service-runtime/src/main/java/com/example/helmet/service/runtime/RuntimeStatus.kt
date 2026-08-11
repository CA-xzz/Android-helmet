package com.example.helmet.service.runtime

import com.example.helmet.core.model.RuntimeSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RuntimeStatus {
    private val mutableSnapshot = MutableStateFlow(RuntimeSnapshot())
    val snapshot: StateFlow<RuntimeSnapshot> = mutableSnapshot.asStateFlow()

    fun update(transform: (RuntimeSnapshot) -> RuntimeSnapshot) {
        mutableSnapshot.value = transform(mutableSnapshot.value)
    }
}
