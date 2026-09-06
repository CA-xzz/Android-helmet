package com.example.helmet.feature.location

import com.example.helmet.core.model.CircleGeofence
import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.GeofenceTransition
import com.example.helmet.core.model.GeofenceTransitionType
import com.example.helmet.core.model.LocationFix
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GeofenceEngine(geofences: Collection<CircleGeofence>) {
    private data class State(
        var inside: Boolean = false,
        var initialized: Boolean = false,
        var candidateInside: Boolean? = null,
        var candidateSamples: Int = 0,
    )

    private val geofencesById = geofences.associateBy(CircleGeofence::geofenceId).also {
        require(it.size == geofences.size) { "duplicate geofence ID" }
    }
    private val states = geofencesById.keys.associateWith { State() }

    fun evaluate(fix: LocationFix): List<GeofenceTransition> {
        if (!fix.hasPosition || fix.isMock) return emptyList()
        val latitude = requireNotNull(fix.latitude)
        val longitude = requireNotNull(fix.longitude)
        return geofencesById.values.mapNotNull { geofence ->
            val distance = distanceMeters(
                latitude,
                longitude,
                geofence.centerLatitude,
                geofence.centerLongitude,
            )
            val state = states.getValue(geofence.geofenceId)
            val accuracy = conservativeHorizontalUncertaintyMeters(fix) ?: return@mapNotNull null
            val minimumDistance = (distance - accuracy).coerceAtLeast(0.0)
            val maximumDistance = distance + accuracy
            val innerBoundary = geofence.radiusMeters - geofence.hysteresisMeters
            val outerBoundary = geofence.radiusMeters + geofence.hysteresisMeters
            val candidate = when {
                !state.initialized -> when {
                    maximumDistance <= geofence.radiusMeters -> true
                    minimumDistance > geofence.radiusMeters -> false
                    else -> null
                }
                state.inside -> when {
                    minimumDistance > outerBoundary -> false
                    maximumDistance <= outerBoundary -> true
                    else -> null
                }
                else -> when {
                    maximumDistance <= innerBoundary -> true
                    minimumDistance > innerBoundary -> false
                    else -> null
                }
            }
            if (candidate == null) {
                state.candidateInside = null
                state.candidateSamples = 0
                return@mapNotNull null
            }
            if (state.initialized && candidate == state.inside) {
                state.candidateInside = null
                state.candidateSamples = 0
                return@mapNotNull null
            }
            if (state.candidateInside == candidate) {
                state.candidateSamples += 1
            } else {
                state.candidateInside = candidate
                state.candidateSamples = 1
            }
            if (state.candidateSamples < geofence.confirmationSamples) return@mapNotNull null
            val previous = state.inside
            val wasInitialized = state.initialized
            state.inside = candidate
            state.initialized = true
            state.candidateInside = null
            state.candidateSamples = 0
            if (!wasInitialized && !candidate) {
                GeofenceTransition(
                    geofenceId = geofence.geofenceId,
                    type = GeofenceTransitionType.EXIT,
                    fixId = fix.fixId,
                    occurredAtEpochMillis = fix.occurredAtEpochMillis,
                    distanceMeters = distance,
                )
            } else if (!previous && candidate) {
                GeofenceTransition(
                    geofenceId = geofence.geofenceId,
                    type = GeofenceTransitionType.ENTER,
                    fixId = fix.fixId,
                    occurredAtEpochMillis = fix.occurredAtEpochMillis,
                    distanceMeters = distance,
                )
            } else if (previous && !candidate) {
                GeofenceTransition(
                    geofenceId = geofence.geofenceId,
                    type = GeofenceTransitionType.EXIT,
                    fixId = fix.fixId,
                    occurredAtEpochMillis = fix.occurredAtEpochMillis,
                    distanceMeters = distance,
                )
            } else {
                null
            }
        }
    }

    companion object {
        internal fun conservativeHorizontalUncertaintyMeters(fix: LocationFix): Double? {
            fix.horizontalAccuracyMeters?.let { return it.toDouble() }
            val qualityFloor = when (fix.quality) {
                FixQuality.RTK_FIXED -> 0.5
                FixQuality.RTK_FLOAT -> 2.0
                FixQuality.DIFFERENTIAL -> 5.0
                FixQuality.STANDARD -> 10.0
                FixQuality.NO_FIX,
                FixQuality.UNVALIDATED,
                FixQuality.DEAD_RECKONING,
                -> return null
            }
            return fix.gnss.hdop?.times(5.0)?.coerceAtLeast(qualityFloor) ?: qualityFloor
        }

        fun distanceMeters(
            latitudeA: Double,
            longitudeA: Double,
            latitudeB: Double,
            longitudeB: Double,
        ): Double {
            val latitudeDelta = Math.toRadians(latitudeB - latitudeA)
            val longitudeDelta = Math.toRadians(longitudeB - longitudeA)
            val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
                cos(Math.toRadians(latitudeA)) * cos(Math.toRadians(latitudeB)) *
                sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
            return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }

        private const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}
