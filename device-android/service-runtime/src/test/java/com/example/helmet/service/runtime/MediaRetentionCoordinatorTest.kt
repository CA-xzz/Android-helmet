package com.example.helmet.service.runtime

import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.MediaTransferState
import com.example.helmet.feature.camera.CaptureJournalEntry
import com.example.helmet.feature.camera.CaptureJournalState
import com.example.helmet.feature.camera.CaptureJournalStore
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRetentionCoordinatorTest {
    @Test
    fun rejectsMissingPendingAndDeletesOnlyOldTerminalAndOrphanFiles() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("media-retention-").toFile()
        val now = 10L * 24 * 60 * 60 * 1_000
        try {
            val pendingFile = File(root, "photo/pending.jpg").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1))
            }
            val terminalFile = File(root, "video/delivered.mp4").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(2))
            }
            val orphan = File(root, "voice/orphan.m4a").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(3))
                setLastModified(now - MediaRetentionCoordinator.ORPHAN_GRACE_MILLIS - 1)
            }
            val assets = mutableListOf(
                asset("pending", pendingFile, MediaTransferState.PENDING, now),
                asset("missing", File(root, "photo/missing.jpg"), MediaTransferState.PENDING, now),
                asset(
                    "delivered",
                    terminalFile,
                    MediaTransferState.DELIVERED,
                    now - MediaRetentionCoordinator.TERMINAL_RETENTION_MILLIS - 1,
                ),
            )
            val rejected = mutableListOf<String>()
            val deleted = mutableListOf<String>()
            val coordinator = MediaRetentionCoordinator(
                mediaRoot = root,
                loadAssets = { assets.toList() },
                markRejected = { assetId, _ ->
                    rejected += assetId
                    true
                },
                deleteTerminal = { assetId ->
                    deleted += assetId
                    true
                },
                wallClock = { now },
            )

            val result = coordinator.reconcile()

            assertEquals(listOf("missing"), rejected)
            assertEquals(listOf("delivered"), deleted)
            assertTrue(pendingFile.isFile)
            assertFalse(terminalFile.exists())
            assertFalse(orphan.exists())
            assertEquals(1, result.rejectedMissingAssets)
            assertEquals(1, result.deletedTerminalAssets)
            assertEquals(1, result.deletedOrphanFiles)
            assertEquals(0, result.failedFileDeletes)
            assertEquals(0, result.recoveredAssets)
            assertEquals(0, result.failedCaptureRecoveries)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun restoresFinalFileAfterRenameBeforeRoomAndIsIdempotent() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("media-retention-recover-final-").toFile()
        val assets = mutableListOf<MediaAsset>()
        try {
            val content = byteArrayOf(1, 2, 3, 4)
            val entry = journalEntry(content)
            val journals = CaptureJournalStore(root)
            val final = File(root, entry.finalRelativePath).apply {
                parentFile?.mkdirs()
                writeBytes(content)
            }
            journals.write(entry)
            val coordinator = recoveryCoordinator(root, assets)

            val first = coordinator.reconcile()
            val second = coordinator.reconcile()

            assertEquals(1, first.recoveredAssets)
            assertEquals(1, first.clearedCaptureJournals)
            assertEquals(0, second.recoveredAssets)
            assertEquals(0, first.failedCaptureRecoveries)
            assertEquals(1, assets.size)
            assertEquals(entry.assetId, assets.single().assetId)
            assertEquals(final.canonicalPath, assets.single().filePath)
            assertTrue(final.isFile)
            assertTrue(journals.scan().entries.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun promotesPreparedPartialBeforeRestoringRoom() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("media-retention-recover-partial-").toFile()
        val assets = mutableListOf<MediaAsset>()
        try {
            val content = byteArrayOf(4, 5, 6)
            val entry = journalEntry(content)
            val journals = CaptureJournalStore(root)
            File(root, entry.partialRelativePath).apply {
                parentFile?.mkdirs()
                writeBytes(content)
            }
            journals.write(entry)

            val result = recoveryCoordinator(root, assets).reconcile()

            assertEquals(1, result.recoveredAssets)
            assertFalse(File(root, entry.partialRelativePath).exists())
            assertTrue(File(root, entry.finalRelativePath).isFile)
            assertEquals(3L, assets.single().byteSize)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun roomRowClearsReplayJournalWithoutDeletingPendingMedia() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("media-retention-room-first-").toFile()
        try {
            val content = byteArrayOf(9)
            val entry = journalEntry(content)
            val final = File(root, entry.finalRelativePath).apply {
                parentFile?.mkdirs()
                writeBytes(content)
            }
            val assets = mutableListOf(entry.toMediaAsset(final))
            val journals = CaptureJournalStore(root).also { it.write(entry) }

            val result = recoveryCoordinator(root, assets).reconcile()

            assertEquals(0, result.recoveredAssets)
            assertEquals(1, result.clearedCaptureJournals)
            assertEquals(MediaTransferState.PENDING, assets.single().transferState)
            assertTrue(final.isFile)
            assertTrue(journals.scan().entries.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedRoomRestoreKeepsFinalAndJournalProtectedFromOrphanCleanup() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("media-retention-restore-failure-").toFile()
        try {
            val content = byteArrayOf(8, 8)
            val entry = journalEntry(content)
            val final = File(root, entry.finalRelativePath).apply {
                parentFile?.mkdirs()
                writeBytes(content)
                setLastModified(1)
            }
            val journals = CaptureJournalStore(root).also { it.write(entry) }
            val coordinator = MediaRetentionCoordinator(
                mediaRoot = root,
                loadAssets = { emptyList() },
                markRejected = { _, _ -> false },
                deleteTerminal = { false },
                wallClock = { System.currentTimeMillis() },
                findAsset = { null },
                restoreAsset = { throw IllegalStateException("database unavailable") },
            )

            val result = coordinator.reconcile()

            assertEquals(0, result.recoveredAssets)
            assertEquals(0, result.deletedOrphanFiles)
            assertEquals(1, result.failedCaptureRecoveries)
            assertTrue(final.isFile)
            assertEquals(listOf(entry), journals.scan().entries)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptJournalIsQuarantinedAndAbandonedPartialIsDeleted() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("media-retention-corrupt-").toFile()
        try {
            val entry = journalEntry().copy(
                state = CaptureJournalState.CAPTURING,
                expectedByteSize = null,
                expectedSha256 = null,
            )
            val journals = CaptureJournalStore(root)
            val partial = File(root, entry.partialRelativePath).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1))
            }
            journals.write(entry)
            val corrupt = File(root, "${CaptureJournalStore.JOURNAL_DIRECTORY_NAME}/corrupt.capture")
                .apply { writeBytes(byteArrayOf(0, 1, 2)) }

            val result = recoveryCoordinator(root, mutableListOf()).reconcile()

            assertFalse(partial.exists())
            assertFalse(corrupt.exists())
            assertEquals(1, result.quarantinedCaptureJournals)
            assertEquals(1, result.failedCaptureRecoveries)
            assertEquals(1, result.clearedCaptureJournals)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun conflictingRoomRowQuarantinesPreparedRecoveryEvidence() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("media-retention-room-conflict-").toFile()
        try {
            val content = byteArrayOf(1, 2, 3)
            val entry = journalEntry(content)
            val final = File(root, entry.finalRelativePath).apply {
                parentFile?.mkdirs()
                writeBytes(content)
            }
            val conflicting = entry.toMediaAsset(final).copy(
                sha256 = "f".repeat(64),
                byteSize = 99,
            )
            val assets = mutableListOf(conflicting)
            val journals = CaptureJournalStore(root).also { it.write(entry) }

            val result = recoveryCoordinator(root, assets).reconcile()

            assertEquals(1, result.failedCaptureRecoveries)
            assertEquals(0, result.clearedCaptureJournals)
            assertEquals(1, result.quarantinedCaptureJournals)
            assertTrue(journals.scan().entries.isEmpty())
            assertTrue(final.isFile)
            assertEquals(conflicting, assets.single())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun digestMismatchIsQuarantinedWithoutRestoringOrDeletingMedia() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("media-retention-digest-mismatch-").toFile()
        try {
            val entry = journalEntry(byteArrayOf(1, 2, 3))
            val final = File(root, entry.finalRelativePath).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(9, 9, 9))
            }
            val journals = CaptureJournalStore(root).also { it.write(entry) }
            val assets = mutableListOf<MediaAsset>()

            val result = recoveryCoordinator(root, assets).reconcile()

            assertEquals(1, result.failedCaptureRecoveries)
            assertEquals(1, result.quarantinedCaptureJournals)
            assertTrue(assets.isEmpty())
            assertTrue(journals.scan().entries.isEmpty())
            assertTrue(final.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun orphanCleanupDoesNotTraverseSymbolicLinkDirectories() = kotlinx.coroutines.runBlocking {
        val root = createTempDirectory("media-retention-link-root-").toFile()
        val external = createTempDirectory("media-retention-link-external-").toFile()
        val now = System.currentTimeMillis()
        try {
            val outside = File(external, "outside.jpg").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(now - MediaRetentionCoordinator.ORPHAN_GRACE_MILLIS - 1)
            }
            Files.createSymbolicLink(File(root, "linked").toPath(), external.toPath())

            val result = recoveryCoordinator(root, mutableListOf()).reconcile()

            assertEquals(0, result.deletedOrphanFiles)
            assertTrue(outside.isFile)
        } finally {
            root.deleteRecursively()
            external.deleteRecursively()
        }
    }

    private fun recoveryCoordinator(
        root: File,
        assets: MutableList<MediaAsset>,
    ) = MediaRetentionCoordinator(
        mediaRoot = root,
        loadAssets = { assets.toList() },
        markRejected = { _, _ -> false },
        deleteTerminal = { false },
        wallClock = { System.currentTimeMillis() },
        findAsset = { id -> assets.firstOrNull { it.assetId == id } },
        restoreAsset = { asset ->
            assets.firstOrNull { it.assetId == asset.assetId } ?: asset.also(assets::add)
        },
    )

    private fun journalEntry(content: ByteArray = byteArrayOf(1)) = CaptureJournalEntry(
        assetId = "photo-recovery-1",
        kind = MediaKind.PHOTO,
        state = CaptureJournalState.PREPARED,
        partialRelativePath = "photo/photo-recovery-1.jpg.partial",
        finalRelativePath = "photo/photo-recovery-1.jpg",
        mimeType = "image/jpeg",
        expectedByteSize = content.size.toLong(),
        expectedSha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(content)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
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

    private fun asset(
        id: String,
        file: File,
        state: MediaTransferState,
        createdAt: Long,
    ) = MediaAsset(
        assetId = id,
        kind = MediaKind.PHOTO,
        filePath = file.absolutePath,
        mimeType = "image/jpeg",
        byteSize = 1,
        sha256 = "a".repeat(64),
        width = 1,
        height = 1,
        durationMillis = null,
        createdAtEpochMillis = createdAt,
        deviceId = "device",
        relatedEventId = null,
        transferState = state,
        attemptCount = 0,
    )
}
