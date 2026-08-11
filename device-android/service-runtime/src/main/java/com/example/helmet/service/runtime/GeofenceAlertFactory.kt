package com.example.helmet.service.runtime

import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.GeofenceTransition
import com.example.helmet.core.model.GeofenceTransitionType
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.SafetyAlertRecord
import java.util.UUID
import org.json.JSONObject

internal object GeofenceAlertFactory {
    fun create(
        deviceId: String,
        transition: GeofenceTransition,
        fix: LocationFix,
        previousAlertActive: Boolean,
    ): SafetyAlertRecord? {
        require(fix.deviceId == deviceId)
        require(fix.fixId == transition.fixId)
        require(fix.hasPosition && !fix.isMock)
        val active = transition.type == GeofenceTransitionType.EXIT
        if (!active && !previousAlertActive) return null
        val alertId = alertId(deviceId, transition.geofenceId)
        val messageId = stableId(
            "geofence-event",
            "$alertId\n${transition.type.name}\n${transition.fixId}\n${transition.occurredAtEpochMillis}",
        )
        return SafetyAlertRecord(
            messageId = messageId,
            alertId = alertId,
            deviceId = deviceId,
            alarmType = "GEOFENCE",
            severity = if (active) EventSeverity.HIGH else EventSeverity.INFO,
            active = active,
            configVersion = null,
            sampleReference = null,
            monotonicMillis = ((fix.elapsedRealtimeNanos ?: 0L) / 1_000_000L) and 0xFFFF_FFFFL,
            occurredAtEpochMillis = transition.occurredAtEpochMillis,
            localActions = 0,
            sensorFaults = 0,
            simulated = false,
            sensorSnapshotJson = JSONObject()
                .put("geofenceId", transition.geofenceId)
                .put("fixId", transition.fixId)
                .put("transition", transition.type.name)
                .put("distanceMeters", transition.distanceMeters)
                .toString(),
            latitude = fix.latitude,
            longitude = fix.longitude,
            horizontalAccuracyMeters = fix.horizontalAccuracyMeters,
            locationFixType = fix.quality.name,
            evidenceAssetId = null,
            deliveryState = DeliveryState.PENDING,
            attemptCount = 0,
        )
    }

    fun alertId(deviceId: String, geofenceId: String): String =
        stableId("geofence", "$deviceId\n$geofenceId")

    private fun stableId(prefix: String, identity: String): String =
        "$prefix-${UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8))}"
}
