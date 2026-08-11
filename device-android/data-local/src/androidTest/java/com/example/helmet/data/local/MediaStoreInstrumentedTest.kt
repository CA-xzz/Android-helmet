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
}
