package com.example.helmet.feature.camera

import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.VoiceMessageRole
import com.example.helmet.core.model.VoiceMessageSenderRole
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureJournalTest {
    @Test
    fun relatedEventProducesStableKindScopedAssetId() {
        val first = MediaAssetIdentity.create("device-1", MediaKind.PHOTO, "key-event-9")
        val replay = MediaAssetIdentity.create("device-1", MediaKind.PHOTO, "key-event-9")

        assertEquals(first, replay)
        assertNotEquals(first, MediaAssetIdentity.create("device-1", MediaKind.VIDEO, "key-event-9"))
        assertNotEquals(first, MediaAssetIdentity.create("device-2", MediaKind.PHOTO, "key-event-9"))
    }

    @Test
    fun preparedJournalRoundTripsAndBuildsCompleteVoiceAsset() {
        val root = createTempDirectory("capture-journal-").toFile()
        try {
            val store = CaptureJournalStore(root)
            val content = byteArrayOf(1, 2, 3, 4)
            val entry = voiceEntry(content)
            val final = File(root, entry.finalRelativePath).apply {
                parentFile?.mkdirs()
                writeBytes(content)
            }

            store.write(entry)

            assertEquals(listOf(entry), store.scan().entries)
            val asset = entry.toMediaAsset(final)
            assertEquals(entry.assetId, asset.assetId)
            assertEquals(MediaKind.VOICE, asset.kind)
            assertEquals(4L, asset.byteSize)
            assertEquals("person-1", asset.personId)
            assertEquals("event-1", asset.relatedEventId)
            assertEquals(31.2, asset.latitude)
            assertEquals(121.4, asset.longitude)
            assertEquals(VoiceMessageSenderRole.DEVICE, asset.voiceSenderRole)
            assertEquals(VoiceMessageRole.entries.toSet(), asset.voiceAllowedRoles)
            assertEquals("call-1", asset.voiceCallId)
            assertTrue(asset.sha256.matches(Regex("[0-9a-f]{64}")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptJournalIsQuarantinedWithoutTouchingFinalMedia() {
        val root = createTempDirectory("capture-journal-corrupt-").toFile()
        try {
            val store = CaptureJournalStore(root)
            val content = byteArrayOf(7, 8, 9)
            val entry = photoEntry(content)
            val final = File(root, entry.finalRelativePath).apply {
                parentFile?.mkdirs()
                writeBytes(content)
            }
            store.write(entry)
            val journal = File(root, "${CaptureJournalStore.JOURNAL_DIRECTORY_NAME}/${entry.assetId}.capture")
            journal.writeBytes(byteArrayOf(0, 1, 2, 3))

            val scan = store.scan()

            assertTrue(scan.entries.isEmpty())
            assertEquals(1, scan.quarantinedJournals)
            assertTrue(final.isFile)
            assertTrue(
                File(root, "${CaptureJournalStore.JOURNAL_DIRECTORY_NAME}/quarantine")
                    .listFiles().orEmpty().single().name.endsWith(".bad"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun journalSymlinkIsQuarantinedWithoutReadingOrDeletingTarget() {
        val root = createTempDirectory("capture-journal-symlink-").toFile()
        val external = File.createTempFile("capture-journal-external-", ".bin")
        try {
            val store = CaptureJournalStore(root)
            val entry = photoEntry()
            store.write(entry)
            val journal = File(root, "${CaptureJournalStore.JOURNAL_DIRECTORY_NAME}/${entry.assetId}.capture")
            assertTrue(journal.delete())
            external.writeText("external-private-content")
            Files.createSymbolicLink(journal.toPath(), external.toPath())

            val scan = store.scan()

            assertEquals(1, scan.quarantinedJournals)
            assertTrue(scan.entries.isEmpty())
            assertEquals("external-private-content", external.readText())
            assertTrue(external.isFile)
        } finally {
            root.deleteRecursively()
            external.delete()
        }
    }

    @Test
    fun pathTraversalAndOversizedMetadataAreRejectedBeforeDiskWrite() {
        val root = createTempDirectory("capture-journal-boundary-").toFile()
        try {
            val store = CaptureJournalStore(root)
            val traversal = photoEntry().copy(
                partialRelativePath = "../outside.jpg.partial",
                finalRelativePath = "../outside.jpg",
            )
            val oversized = photoEntry().copy(deviceId = "d".repeat(4_097))

            assertTrue(runCatching { store.write(traversal) }.isFailure)
            assertTrue(runCatching { store.write(oversized) }.isFailure)
            assertFalse(File(root.parentFile, "outside.jpg").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preparedPartialCanBePromotedExactlyOnce() {
        val root = createTempDirectory("capture-journal-promote-").toFile()
        try {
            val store = CaptureJournalStore(root)
            val content = byteArrayOf(4, 5, 6)
            val entry = photoEntry(content)
            val partial = File(root, entry.partialRelativePath).apply {
                parentFile?.mkdirs()
                writeBytes(content)
            }
            store.write(entry)

            val final = store.promotePrepared(entry)

            assertFalse(partial.exists())
            assertEquals(listOf<Byte>(4, 5, 6), final.readBytes().toList())
            assertTrue(runCatching { store.promotePrepared(entry) }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun terminationGateAllowsOnlyOneConcurrentFinalizer() {
        val gate = CaptureTerminationGate()
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val workers = List(20) { thread { results += gate.tryClaim() } }
        workers.forEach(Thread::join)

        assertEquals(1, results.count { it })
        assertEquals(19, results.count { !it })
    }

    private fun photoEntry(content: ByteArray = byteArrayOf(1)) = CaptureJournalEntry(
        assetId = "photo-asset-1",
        kind = MediaKind.PHOTO,
        state = CaptureJournalState.PREPARED,
        partialRelativePath = "photo/photo-asset-1.jpg.partial",
        finalRelativePath = "photo/photo-asset-1.jpg",
        mimeType = "image/jpeg",
        expectedByteSize = content.size.toLong(),
        expectedSha256 = sha256(content),
        width = 4_160,
        height = 3_120,
        durationMillis = null,
        createdAtEpochMillis = 1_000,
        deviceId = "device-1",
        personId = "person-1",
        relatedEventId = "event-1",
        latitude = 31.2,
        longitude = 121.4,
        horizontalAccuracyMeters = 2.5f,
        locationFixType = "RTK_FIXED",
    )

    private fun voiceEntry(content: ByteArray = byteArrayOf(1)) = CaptureJournalEntry(
        assetId = "voice-asset-1",
        kind = MediaKind.VOICE,
        state = CaptureJournalState.PREPARED,
        partialRelativePath = "voice/voice-asset-1.m4a.partial",
        finalRelativePath = "voice/voice-asset-1.m4a",
        mimeType = "audio/mp4",
        expectedByteSize = content.size.toLong(),
        expectedSha256 = sha256(content),
        width = 0,
        height = 0,
        durationMillis = 2_000,
        createdAtEpochMillis = 1_000,
        deviceId = "device-1",
        personId = "person-1",
        relatedEventId = "event-1",
        latitude = 31.2,
        longitude = 121.4,
        horizontalAccuracyMeters = 2.5f,
        locationFixType = "RTK_FIXED",
        voiceSenderId = "device-1",
        voiceSenderRole = VoiceMessageSenderRole.DEVICE,
        voiceAllowedRoles = VoiceMessageRole.entries.toSet(),
        voiceCallId = "call-1",
    )

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
