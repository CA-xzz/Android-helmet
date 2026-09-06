package com.example.helmet.service.runtime

import com.example.helmet.core.model.FixQuality
import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.LocationSource

internal fun isLocationFixValidForReceiverState(
    fix: LocationFix,
    externalReceiverQuality: FixQuality,
): Boolean = fix.source != LocationSource.EXTERNAL_NMEA || externalReceiverQuality != FixQuality.NO_FIX

internal fun isFreshLocationFix(
    fix: LocationFix,
    observedAtElapsedRealtimeNanos: Long,
    maximumAgeNanos: Long,
): Boolean {
    require(observedAtElapsedRealtimeNanos >= 0)
    require(maximumAgeNanos >= 0)
    val fixTime = fix.elapsedRealtimeNanos ?: return true
    val age = observedAtElapsedRealtimeNanos - fixTime
    return age in 0..maximumAgeNanos
}

internal fun usableLocationFix(
    fix: LocationFix?,
    externalReceiverQuality: FixQuality,
    observedAtElapsedRealtimeNanos: Long,
    observedAtEpochMillis: Long,
    maximumAgeMillis: Long,
): LocationFix? {
    require(observedAtElapsedRealtimeNanos >= 0)
    require(observedAtEpochMillis >= 0)
    require(maximumAgeMillis in 0..Long.MAX_VALUE / 1_000_000L)
    fix ?: return null
    if (!fix.hasPosition || fix.isMock) return null
    if (!isLocationFixValidForReceiverState(fix, externalReceiverQuality)) return null
    val fresh = if (fix.elapsedRealtimeNanos != null) {
        isFreshLocationFix(
            fix,
            observedAtElapsedRealtimeNanos,
            maximumAgeMillis * 1_000_000L,
        )
    } else {
        observedAtEpochMillis - fix.occurredAtEpochMillis in 0..maximumAgeMillis
    }
    return fix.takeIf { fresh }
}

internal fun shouldSelectRealtimeLocationFix(
    current: LocationFix?,
    candidate: LocationFix,
    observedAtElapsedRealtimeNanos: Long,
    maximumCandidateAgeNanos: Long,
    preferredSourceHoldNanos: Long,
): Boolean {
    require(candidate.hasPosition && !candidate.isMock)
    require(maximumCandidateAgeNanos > 0)
    require(preferredSourceHoldNanos >= 0 && preferredSourceHoldNanos <= maximumCandidateAgeNanos)
    if (!isFreshLocationFix(candidate, observedAtElapsedRealtimeNanos, maximumCandidateAgeNanos)) return false
    current ?: return true
    if (!isFreshLocationFix(current, observedAtElapsedRealtimeNanos, maximumCandidateAgeNanos)) return true

    val currentTime = current.elapsedRealtimeNanos
    val candidateTime = candidate.elapsedRealtimeNanos
    val candidateIsNewer = if (currentTime != null && candidateTime != null) {
        candidateTime >= currentTime
    } else {
        candidate.occurredAtEpochMillis >= current.occurredAtEpochMillis
    }
    val sameInput = current.source == candidate.source && current.provider == candidate.provider
    if (sameInput) return candidateIsNewer

    val currentRank = locationQualityRank(current.quality)
    val candidateRank = locationQualityRank(candidate.quality)
    if (candidateRank > currentRank) return true
    if (candidateRank == currentRank) return candidateIsNewer

    val currentAge = currentTime?.let { observedAtElapsedRealtimeNanos - it }
        ?: if (candidateIsNewer) preferredSourceHoldNanos + 1 else 0
    return currentAge > preferredSourceHoldNanos && candidateIsNewer
}

private fun locationQualityRank(quality: FixQuality): Int = when (quality) {
    FixQuality.NO_FIX -> 0
    FixQuality.UNVALIDATED -> 1
    FixQuality.DEAD_RECKONING -> 2
    FixQuality.STANDARD -> 3
    FixQuality.DIFFERENTIAL -> 4
    FixQuality.RTK_FLOAT -> 5
    FixQuality.RTK_FIXED -> 6
}
