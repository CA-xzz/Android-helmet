package com.example.helmet.feature.camera

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.MediaKind
import com.example.helmet.data.local.EncryptedMediaFileStorage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureJournalEncryptionInstrumentedTest {
    @Test
    fun productionJournalStoreDoesNotPersistReadableMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.filesDir, "encrypted-journal-test").apply { mkdirs() }
        val storage = EncryptedMediaFileStorage(context)
        val store = CaptureJournalStore(root, storage)
        val entry = CaptureJournalEntry(
            assetId = "journal-encryption-test",
            kind = MediaKind.PHOTO,
            state = CaptureJournalState.CAPTURING,
            partialRelativePath = "photo/journal-encryption-test.jpg.partial",
            finalRelativePath = "photo/journal-encryption-test.jpg",
            mimeType = "image/jpeg",
            expectedByteSize = null,
            expectedSha256 = null,
            width = 1920,
            height = 1080,
            durationMillis = null,
            createdAtEpochMillis = 1,
            deviceId = "helmet-sensitive-device",
            personId = "person-sensitive",
            relatedEventId = "event-sensitive",
            latitude = 31.2,
            longitude = 121.5,
            horizontalAccuracyMeters = 2.5f,
            locationFixType = "RTK_FIXED",
        )

        store.write(entry)
        val persisted = File(root, ".capture-journal/${entry.assetId}.capture").readBytes()
        val readable = persisted.toString(Charsets.UTF_8)
        assertTrue(storage.isEncryptedPayload(persisted))
        assertFalse(readable.contains(entry.deviceId))
        assertFalse(readable.contains(requireNotNull(entry.personId)))
        assertEquals(listOf(entry), CaptureJournalStore(root, EncryptedMediaFileStorage(context)).scan().entries)
    }
}
