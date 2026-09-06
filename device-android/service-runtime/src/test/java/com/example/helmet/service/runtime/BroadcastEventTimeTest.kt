package com.example.helmet.service.runtime

import com.example.helmet.core.model.BroadcastPlaybackState
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.TextBroadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BroadcastEventTimeTest {
    @Test
    fun advancesPastThePreviousEventWhenDeviceClockIsBehind() {
        assertEquals(1_786_000_000_001, nextBroadcastEventTime(1_785_000_000_000, 1_786_000_000_000))
        assertEquals(1_786_000_000_001, nextBroadcastEventTime(1_786_000_000_000, 1_786_000_000_000))
    }

    @Test
    fun preservesADeviceTimeThatAlreadyFollowsThePreviousEvent() {
        assertEquals(1_786_000_000_500, nextBroadcastEventTime(1_786_000_000_500, 1_786_000_000_000))
    }

    @Test
    fun rejectsAnUnrepresentableNextEventTime() {
        assertThrows(IllegalArgumentException::class.java) {
            nextBroadcastEventTime(Long.MAX_VALUE, Long.MAX_VALUE)
        }
    }

    @Test
    fun replaysEveryPersistedBroadcastStateInServerOrder() {
        val replay = broadcastReceiptReplay(broadcast(BroadcastPlaybackState.FAILED, 101, 102, 103, "TTS_FAILED"))

        assertEquals(
            listOf(
                BroadcastReceiptReplay(BroadcastPlaybackState.RECEIVED, 101, null),
                BroadcastReceiptReplay(BroadcastPlaybackState.PLAYING, 102, null),
                BroadcastReceiptReplay(BroadcastPlaybackState.FAILED, 103, "TTS_FAILED"),
            ),
            replay,
        )
    }

    @Test
    fun replaysExpiredBroadcastWithoutInventingAPlayingState() {
        val replay = broadcastReceiptReplay(broadcast(BroadcastPlaybackState.EXPIRED, 101, null, 102, "EXPIRED"))

        assertEquals(
            listOf(
                BroadcastReceiptReplay(BroadcastPlaybackState.RECEIVED, 101, null),
                BroadcastReceiptReplay(BroadcastPlaybackState.EXPIRED, 102, null),
            ),
            replay,
        )
    }

    private fun broadcast(
        state: BroadcastPlaybackState,
        receivedAt: Long,
        playingAt: Long?,
        playedAt: Long?,
        error: String?,
    ) = TextBroadcast(
        broadcastId = "broadcast-1",
        deviceId = "device-1",
        serverSequence = 1,
        text = "test",
        language = "zh-CN",
        priority = 1,
        expiresAtEpochMillis = null,
        playbackState = state,
        receivedAtEpochMillis = receivedAt,
        playingAtEpochMillis = playingAt,
        playedAtEpochMillis = playedAt,
        lastError = error,
        receiptDeliveryState = DeliveryState.PENDING,
        receiptAttemptCount = 0,
    )
}
