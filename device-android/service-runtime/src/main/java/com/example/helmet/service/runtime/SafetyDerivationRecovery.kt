package com.example.helmet.service.runtime

import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.core.model.SafetySensorTelemetry
import org.json.JSONObject

internal fun isProtectedCurrentPendingSafetyAlert(
    alert: SafetyAlertRecord,
    pendingSampleReferences: Set<Long>,
    currentThresholdFingerprint: String,
): Boolean = alert.sampleReference?.let(pendingSampleReferences::contains) == true &&
    runCatching {
        JSONObject(alert.sensorSnapshotJson).optString("thresholdConfigFingerprint") ==
            currentThresholdFingerprint
    }.getOrDefault(false)

/**
 * Rebuilds detector state exclusively from durable inputs before replaying pending samples.
 * HelmetService uses this coordinator for live input, startup and retry recovery; tests use
 * the same function so failure-injection cannot accidentally exercise a copied algorithm.
 */
internal suspend fun recoverSafetyDerivationsDurably(
    processor: SafetySampleProcessor,
    rebuildProcessor: () -> Unit,
    prepareDurableSamples: suspend () -> Unit = {},
    loadPendingSamples: suspend () -> List<SafetySensorTelemetry>,
    beforeReplay: suspend (List<SafetySensorTelemetry>) -> Boolean = { true },
    persistAlarm: suspend (EvaluatedSafetyAlarm) -> Boolean,
    commitCheckpoint: suspend (SafetyProcessorCheckpoint) -> Unit,
): SafetyStateRestoreResult {
    rebuildProcessor()
    prepareDurableSamples()
    val pending = loadPendingSamples()
    if (!beforeReplay(pending)) {
        throw SafetyDerivationDeferredException(
            "safety derivation is waiting for a strictly newer durable sample",
        )
    }
    return replaySafetySamplesDurably(
        samples = pending,
        processor = processor,
        persistAlarm = persistAlarm,
        commitCheckpoint = commitCheckpoint,
    )
}

internal class SafetyDerivationDeferredException(message: String) : IllegalStateException(message)

/**
 * Selects a real sample that strictly advances every stale episode and makes the replay
 * waterline durable before publishing any clear. If publishing the clear succeeds and the
 * process then dies, a retry cannot replay samples from the unusable detector state.
 */
internal suspend fun terminateSafetyEpisodesUsingDurableSample(
    pending: List<SafetySensorTelemetry>,
    latestPublishedReferences: List<Long>,
    quarantineBeforeTermination: suspend (Long) -> Unit = {},
    terminateAt: suspend (SafetySensorTelemetry) -> Boolean,
): Boolean {
    if (latestPublishedReferences.isEmpty()) return true
    val latestPublishedReference = latestPublishedReferences.max()
    val candidate = pending.firstOrNull { it.sampleReference > latestPublishedReference }
        ?: return false
    // This must precede the clear. The invalid checkpoint remains the retry marker until
    // both operations finish, so either side of a process crash is safe and idempotent.
    quarantineBeforeTermination(candidate.sampleReference)
    return terminateAt(candidate)
}

internal data class SafetyThresholdIdentity(
    val version: Int,
    val fingerprint: String,
) {
    init {
        require(version in 1..0xFFFF)
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
    }
}

/** Production invalid/future/config-mismatch checkpoint recovery ordering. */
internal suspend fun recoverUnusableSafetyCheckpointDurably(
    processor: SafetySampleProcessor,
    retiredIdentity: SafetyThresholdIdentity?,
    stageRetiredEpisodes: suspend (List<SafetySensorTelemetry>) -> Unit,
    isolateAllDerivedSamples: suspend () -> Unit,
    isolateRetiredIdentity: suspend (SafetyThresholdIdentity) -> Unit,
    isolateOutsideCurrentIdentity: suspend () -> Unit,
    loadPendingSamples: suspend () -> List<SafetySensorTelemetry>,
    terminateRetiredEpisodes: suspend (List<SafetySensorTelemetry>) -> Boolean,
    deleteCheckpoint: suspend () -> Boolean,
    persistAlarm: suspend (EvaluatedSafetyAlarm) -> Boolean,
    commitCheckpoint: suspend (SafetyProcessorCheckpoint) -> Unit,
): SafetyStateRestoreResult {
    processor.reset()
    if (retiredIdentity == null) {
        isolateAllDerivedSamples()
    } else {
        isolateRetiredIdentity(retiredIdentity)
    }
    isolateOutsideCurrentIdentity()
    val pendingBeforeTermination = loadPendingSamples()
    stageRetiredEpisodes(pendingBeforeTermination)
    // A failed or deferred termination leaves the old checkpoint as the durable retry
    // marker. It is removed only after every old Room/backend episode is cleared.
    if (!terminateRetiredEpisodes(pendingBeforeTermination)) {
        throw SafetyDerivationDeferredException(
            "safety derivation is waiting for a strictly newer durable sample",
        )
    }
    check(deleteCheckpoint()) { "invalid safety detection checkpoint could not be removed" }
    // Termination may quarantine samples that were interpreted using the unusable baseline.
    // Reload after that durable change so they cannot reopen the just-cleared episode.
    val replayable = loadPendingSamples()
    return replaySafetySamplesDurably(
        samples = replayable,
        processor = processor,
        persistAlarm = persistAlarm,
        commitCheckpoint = commitCheckpoint,
    )
}

/**
 * Replays durable raw samples through the production detector and advances its checkpoint
 * only after every derived alarm is durable. Any detector, alarm, or checkpoint failure
 * propagates to the caller, which must withhold acknowledgement and retry from Room.
 */
internal suspend fun replaySafetySamplesDurably(
    samples: List<SafetySensorTelemetry>,
    processor: SafetySampleProcessor,
    persistAlarm: suspend (EvaluatedSafetyAlarm) -> Boolean,
    commitCheckpoint: suspend (SafetyProcessorCheckpoint) -> Unit,
): SafetyStateRestoreResult {
    var restored = 0
    var resets = 0
    val alarms = mutableListOf<EvaluatedSafetyAlarm>()
    samples.forEach { telemetry ->
        val processing = processor.process(telemetry)
        processing.alarms.forEach { alarm ->
            check(persistAlarm(alarm)) {
                "replayed safety alarm was not durably persisted"
            }
        }
        restored += 1
        if (processing.engineReset) resets += 1
        alarms += processing.alarms
    }
    if (restored > 0) {
        commitCheckpoint(processor.checkpoint())
    }
    return SafetyStateRestoreResult(restored, 0, resets, alarms)
}
