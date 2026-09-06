package com.example.helmet.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.MediaTransferState
import com.example.helmet.core.model.VoiceMessageRole
import com.example.helmet.core.model.VoiceMessageSenderRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MediaStoreInstrumentedTest {
    private lateinit var database: HelmetDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, HelmetDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun assetIsIdempotentAndSurvivesTransferTransitions() = runBlocking {
        val store = MediaStore(database)
        val asset = MediaAsset(
            assetId = "asset-1",
            kind = MediaKind.PHOTO,
            filePath = "/private/photo.jpg",
            mimeType = "image/jpeg",
            byteSize = 123,
            sha256 = "a".repeat(64),
            width = 4_160,
            height = 3_120,
            durationMillis = null,
            createdAtEpochMillis = 100,
            deviceId = "device-1",
            relatedEventId = "event-1",
            transferState = MediaTransferState.PENDING,
            attemptCount = 0,
            personId = "person-1",
            latitude = 30.5,
            longitude = 114.3,
            horizontalAccuracyMeters = 3.5f,
            locationFixType = "GNSS_3D",
        )

        assertTrue(store.add(asset))
        assertFalse(store.add(asset))
        assertEquals(1, store.pendingCount())
        assertTrue(store.markAttempt(asset.assetId, 200))
        assertEquals(MediaTransferState.IN_FLIGHT, store.find(asset.assetId)?.transferState)
        assertEquals("person-1", store.find(asset.assetId)?.personId)
        assertEquals("GNSS_3D", store.find(asset.assetId)?.locationFixType)
        assertTrue(store.markDelivered(asset.assetId, 300))
        assertEquals(MediaTransferState.DELIVERED, store.find(asset.assetId)?.transferState)
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun pendingMediaSurvivesDatabaseReopen(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "media-restart-${UUID.randomUUID()}.db"
        val asset = MediaAsset(
            assetId = "asset-restart",
            kind = MediaKind.VIDEO,
            filePath = "/private/video.mp4",
            mimeType = "video/mp4",
            byteSize = 456,
            sha256 = "b".repeat(64),
            width = 1_920,
            height = 1_080,
            durationMillis = 10_000,
            createdAtEpochMillis = 500,
            deviceId = "device-restart",
            relatedEventId = null,
            transferState = MediaTransferState.PENDING,
            attemptCount = 0,
        )
        try {
            val first = Room.databaseBuilder(context, HelmetDatabase::class.java, databaseName).build()
            try {
                assertTrue(MediaStore(first).add(asset))
            } finally {
                first.close()
            }
            val reopened = Room.databaseBuilder(context, HelmetDatabase::class.java, databaseName).build()
            try {
                val pending = MediaStore(reopened).pending()
                assertEquals(listOf(asset), pending)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
        Unit
    }

    @Test
    fun voiceMessageMetadataSurvivesPersistence() = runBlocking {
        val asset = MediaAsset(
            assetId = "voice-1",
            kind = MediaKind.VOICE,
            filePath = "/private/voice.wav",
            mimeType = "audio/wav",
            byteSize = 32_044,
            sha256 = "c".repeat(64),
            width = 0,
            height = 0,
            durationMillis = 1_000,
            createdAtEpochMillis = 600,
            deviceId = "device-voice",
            relatedEventId = "event-voice",
            transferState = MediaTransferState.PENDING,
            attemptCount = 0,
            voiceSenderId = "device-voice",
            voiceSenderRole = VoiceMessageSenderRole.DEVICE,
            voiceAllowedRoles = setOf(VoiceMessageRole.ADMIN, VoiceMessageRole.DISPATCHER),
            voiceCallId = "call-voice",
        )

        val store = MediaStore(database)
        assertTrue(store.add(asset))
        assertEquals(asset, store.find(asset.assetId))
    }

    @Test
    fun relatedEventAndKindLookupMakesCaptureReplayIdempotent() = runBlocking {
        val store = MediaStore(database)
        val original = mediaAsset("stable-photo", MediaTransferState.PENDING).copy(
            relatedEventId = "hardware-event-7",
        )

        assertEquals(original, store.addIdempotently(original))
        assertEquals(original, store.addIdempotently(original))
        assertEquals(
            original,
            store.findByRelatedEventAndKind("device-retention", "hardware-event-7", MediaKind.PHOTO),
        )
        assertEquals(1, store.all().size)
    }

    @Test
    fun relatedEventLookupIsDeviceScopedAndRejectsConflictingMetadata() = runBlocking {
        val store = MediaStore(database)
        val firstDevice = mediaAsset("device-one-photo", MediaTransferState.PENDING).copy(
            relatedEventId = "shared-event",
        )
        val secondDevice = firstDevice.copy(
            assetId = "device-two-photo",
            deviceId = "device-2",
            filePath = "/private/device-two-photo.jpg",
        )
        assertEquals(firstDevice, store.addIdempotently(firstDevice))
        assertEquals(secondDevice, store.addIdempotently(secondDevice))
        assertEquals(
            secondDevice,
            store.findByRelatedEventAndKind("device-2", "shared-event", MediaKind.PHOTO),
        )

        val conflicting = firstDevice.copy(byteSize = 2, sha256 = "e".repeat(64))
        assertTrue(runCatching { store.addIdempotently(conflicting) }.isFailure)
        assertEquals(2, store.all().size)
    }

    @Test
    fun retentionDeletionCannotRemovePendingMedia() = runBlocking {
        val store = MediaStore(database)
        val pending = mediaAsset("retention-pending", MediaTransferState.PENDING)
        val delivered = mediaAsset("retention-delivered", MediaTransferState.PENDING)
        assertTrue(store.add(pending))
        assertTrue(store.add(delivered))
        assertTrue(store.markAttempt(delivered.assetId, 1_999))
        assertTrue(store.markDelivered(delivered.assetId, 2_000))

        assertFalse(store.deleteTerminal(pending.assetId))
        assertTrue(store.deleteTerminal(delivered.assetId))
        assertEquals(pending, store.find(pending.assetId))
        assertEquals(null, store.find(delivered.assetId))
    }

    @Test
    fun lateUploadCallbacksCannotResurrectRejectedMedia() = runBlocking {
        val store = MediaStore(database)
        val rejected = mediaAsset("rejected-race", MediaTransferState.PENDING)
        assertTrue(store.add(rejected))
        assertTrue(store.markAttempt(rejected.assetId, 2_000))
        assertTrue(store.markRejected(rejected.assetId, "permanent rejection"))

        assertFalse(store.markAttempt(rejected.assetId, 2_001))
        assertFalse(store.markFailed(rejected.assetId, "late failure"))
        assertFalse(store.markDelivered(rejected.assetId, 2_002))
        assertEquals(MediaTransferState.REJECTED, store.find(rejected.assetId)?.transferState)
    }

    private fun mediaAsset(assetId: String, transferState: MediaTransferState) = MediaAsset(
        assetId = assetId,
        kind = MediaKind.PHOTO,
        filePath = "/private/$assetId.jpg",
        mimeType = "image/jpeg",
        byteSize = 1,
        sha256 = "d".repeat(64),
        width = 1,
        height = 1,
        durationMillis = null,
        createdAtEpochMillis = 1_000,
        deviceId = "device-retention",
        relatedEventId = null,
        transferState = transferState,
        attemptCount = 0,
    )
}
