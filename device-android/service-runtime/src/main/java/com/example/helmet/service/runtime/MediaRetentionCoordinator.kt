package com.example.helmet.service.runtime

import android.content.Context
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaTransferState
import com.example.helmet.data.local.MediaStore
import com.example.helmet.data.local.EncryptedMediaFileStorage
import com.example.helmet.data.local.hasSameImmutableIdentityAndContent
import com.example.helmet.feature.camera.CaptureJournalState
import com.example.helmet.feature.camera.CaptureJournalStore
import com.example.helmet.feature.camera.MediaFileRecovery
import java.io.File

internal data class MediaRetentionResult(
    val recoveredAssets: Int,
    val clearedCaptureJournals: Int,
    val quarantinedCaptureJournals: Int,
    val failedCaptureRecoveries: Int,
    val deletedIncompleteFiles: Int,
    val rejectedMissingAssets: Int,
    val deletedTerminalAssets: Int,
    val deletedOrphanFiles: Int,
    val failedFileDeletes: Int,
    val encryptedMediaFiles: Int = 0,
    val failedMediaEncryptions: Int = 0,
)

internal class MediaRetentionCoordinator(
    private val mediaRoot: File,
    private val loadAssets: suspend () -> List<MediaAsset>,
    private val markRejected: suspend (assetId: String, error: String) -> Boolean,
    private val deleteTerminal: suspend (assetId: String) -> Boolean,
    private val wallClock: () -> Long,
    private val findAsset: suspend (assetId: String) -> MediaAsset? = { assetId ->
        loadAssets().firstOrNull { it.assetId == assetId }
    },
    private val restoreAsset: suspend (asset: MediaAsset) -> MediaAsset = { asset -> asset },
    private val encryptedMedia: EncryptedMediaFileStorage? = null,
) {
    suspend fun reconcile(): MediaRetentionResult {
        val now = wallClock()
        val root = mediaRoot.canonicalFile
        root.mkdirs()
        val journalStore = CaptureJournalStore(root, encryptedMedia)
        val scan = journalStore.scan()
        var recoveredAssets = 0
        var clearedJournals = 0
        var quarantinedJournals = scan.quarantinedJournals
        var failedRecoveries = scan.quarantinedJournals
        var deletedIncomplete = 0
        var encryptedMediaFiles = 0
        var failedMediaEncryptions = 0
        val quarantinedPaths = mutableSetOf<String>()
        scan.entries.forEach { entry ->
            if (encryptedMedia != null) journalStore.write(entry)
            val partial = journalStore.resolvePartial(entry)
            val final = journalStore.resolveFinal(entry)
            val existing = findAsset(entry.assetId)
            if (entry.state == CaptureJournalState.CAPTURING) {
                if (existing != null || final.exists()) {
                    failedRecoveries += 1
                    if (journalStore.quarantine(entry)) {
                        quarantinedJournals += 1
                        quarantinedPaths += partial.canonicalPath
                        quarantinedPaths += final.canonicalPath
                    }
                } else {
                    if (partial.delete()) deletedIncomplete += 1
                    if (journalStore.clear(entry)) clearedJournals += 1
                }
                return@forEach
            }
            val recoverableFinal = when {
                final.isFile -> final
                partial.isFile && partial.length() > 0 -> runCatching {
                    journalStore.promotePrepared(entry)
                }.getOrNull()
                else -> null
            }
            if (recoverableFinal == null) {
                failedRecoveries += 1
                if (partial.delete()) deletedIncomplete += 1
                if (journalStore.quarantine(entry)) {
                    quarantinedJournals += 1
                    quarantinedPaths += partial.canonicalPath
                    quarantinedPaths += final.canonicalPath
                }
                return@forEach
            }
            val candidate = runCatching {
                val integrity = encryptedMedia?.inspect(recoverableFinal)?.let {
                    com.example.helmet.feature.camera.MediaFileIntegrity(it.byteSize, it.sha256)
                } ?: com.example.helmet.feature.camera.MediaFileIntegrityInspector.inspect(recoverableFinal)
                require(integrity.byteSize == entry.expectedByteSize) {
                    "prepared media size differs from capture journal"
                }
                require(integrity.sha256 == entry.expectedSha256) {
                    "prepared media digest differs from capture journal"
                }
                if (encryptedMedia?.encryptInPlace(recoverableFinal, integrity.byteSize, integrity.sha256) == true) {
                    encryptedMediaFiles += 1
                }
                entry.toMediaAsset(recoverableFinal, integrity)
            }.getOrElse {
                failedRecoveries += 1
                if (journalStore.quarantine(entry)) {
                    quarantinedJournals += 1
                    quarantinedPaths += partial.canonicalPath
                    quarantinedPaths += final.canonicalPath
                }
                return@forEach
            }
            if (existing != null) {
                if (existing.hasSameImmutableIdentityAndContent(candidate)) {
                    if (journalStore.clear(entry)) clearedJournals += 1
                } else {
                    failedRecoveries += 1
                    if (journalStore.quarantine(entry)) {
                        quarantinedJournals += 1
                        quarantinedPaths += partial.canonicalPath
                        quarantinedPaths += final.canonicalPath
                    }
                }
                return@forEach
            }
            runCatching {
                val recovered = restoreAsset(candidate)
                require(recovered.hasSameImmutableIdentityAndContent(candidate)) {
                    "capture journal restored conflicting immutable media metadata"
                }
                recovered
            }.onSuccess {
                recoveredAssets += 1
                if (journalStore.clear(entry)) clearedJournals += 1
            }.onFailure {
                failedRecoveries += 1
            }
        }

        val activeJournalPaths = journalStore.scan().entries.flatMap { entry ->
            listOf(journalStore.resolvePartial(entry).canonicalPath, journalStore.resolveFinal(entry).canonicalPath)
        }.toSet() + quarantinedPaths
        deletedIncomplete += MediaFileRecovery.deleteIncompleteFiles(root, activeJournalPaths)
        val assets = loadAssets()
        val trackedPaths = mutableSetOf<String>()
        var rejectedMissing = 0
        assets.forEach { asset ->
            val file = runCatching { File(asset.filePath).canonicalFile }.getOrNull()
            val insideRoot = file != null && file.path.startsWith(root.path + File.separator)
            if (insideRoot) trackedPaths += requireNotNull(file).path
            if (
                asset.transferState in NON_TERMINAL_STATES &&
                (!insideRoot || file?.isFile != true)
            ) {
                val reason = if (insideRoot) {
                    "media file is missing during retention reconciliation"
                } else {
                    "media path is outside the private media directory"
                }
                if (markRejected(asset.assetId, reason)) rejectedMissing += 1
            } else if (insideRoot && file?.isFile == true && encryptedMedia != null) {
                val storage = encryptedMedia
                runCatching {
                    !storage.isEncrypted(file) &&
                        storage.encryptInPlace(file, asset.byteSize, asset.sha256)
                }.onSuccess { changed ->
                    if (changed) encryptedMediaFiles += 1
                }.onFailure {
                    failedMediaEncryptions += 1
                }
            }
        }

        var deletedOrphans = 0
        var failedDeletes = 0
        if (root.isDirectory) {
            root.walkTopDown()
                .onEnter { directory -> !java.nio.file.Files.isSymbolicLink(directory.toPath()) }
                .filter(File::isFile)
                .filter { file ->
                    !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
                        file.canonicalPath.startsWith(root.path + File.separator) &&
                        !journalStore.isJournalPath(file) &&
                        file.canonicalPath !in activeJournalPaths &&
                        file.canonicalPath !in trackedPaths &&
                        now - file.lastModified() >= ORPHAN_GRACE_MILLIS
                }
                .forEach { file ->
                    if (file.delete()) deletedOrphans += 1 else failedDeletes += 1
                }
        }

        val terminal = assets.filter { it.transferState in TERMINAL_STATES }
        val expired = terminal.filter { it.createdAtEpochMillis <= now - TERMINAL_RETENTION_MILLIS }
        val overflow = terminal.sortedWith(
            compareByDescending<MediaAsset> { it.createdAtEpochMillis }.thenByDescending { it.assetId },
        ).drop(MAX_RETAINED_TERMINAL_ASSETS)
        val candidates = (expired + overflow).distinctBy(MediaAsset::assetId)
        var deletedTerminal = 0
        candidates.forEach { asset ->
            val file = runCatching { File(asset.filePath).canonicalFile }.getOrNull()
            val insideRoot = file != null && file.path.startsWith(root.path + File.separator)
            val fileRemoved = !insideRoot || file?.exists() != true || requireNotNull(file).delete()
            if (!fileRemoved) {
                failedDeletes += 1
            } else if (deleteTerminal(asset.assetId)) {
                deletedTerminal += 1
            }
        }
        return MediaRetentionResult(
            recoveredAssets = recoveredAssets,
            clearedCaptureJournals = clearedJournals,
            quarantinedCaptureJournals = quarantinedJournals,
            failedCaptureRecoveries = failedRecoveries,
            deletedIncompleteFiles = deletedIncomplete,
            rejectedMissingAssets = rejectedMissing,
            deletedTerminalAssets = deletedTerminal,
            deletedOrphanFiles = deletedOrphans,
            failedFileDeletes = failedDeletes,
            encryptedMediaFiles = encryptedMediaFiles,
            failedMediaEncryptions = failedMediaEncryptions,
        )
    }

    companion object {
        const val ORPHAN_GRACE_MILLIS = 60L * 60 * 1_000
        const val TERMINAL_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1_000
        const val MAX_RETAINED_TERMINAL_ASSETS = 500
        private val NON_TERMINAL_STATES = setOf(
            MediaTransferState.PENDING,
            MediaTransferState.IN_FLIGHT,
            MediaTransferState.FAILED,
        )
        private val TERMINAL_STATES = setOf(
            MediaTransferState.DELIVERED,
            MediaTransferState.REJECTED,
        )
    }
}

internal suspend fun reconcileMediaStorage(
    context: Context,
    mediaStore: MediaStore,
    wallClock: () -> Long,
): MediaRetentionResult = MediaRetentionCoordinator(
    mediaRoot = File(context.filesDir, "media"),
    loadAssets = mediaStore::all,
    markRejected = mediaStore::markRejected,
    deleteTerminal = mediaStore::deleteTerminal,
    wallClock = wallClock,
    findAsset = mediaStore::find,
    restoreAsset = mediaStore::addIdempotently,
    encryptedMedia = EncryptedMediaFileStorage(context.applicationContext),
).reconcile()
