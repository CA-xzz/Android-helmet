package com.example.helmet.feature.camera

import com.example.helmet.core.model.LocationFix
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.MediaTransferState
import com.example.helmet.core.model.VoiceMessageRole
import com.example.helmet.core.model.VoiceMessageSenderRole
import com.example.helmet.data.local.EncryptedMediaFileStorage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class MediaSize(val width: Int, val height: Int) {
    init {
        require(width > 0)
        require(height > 0)
    }

    val pixels: Long = width.toLong() * height.toLong()
}

data class CameraCapabilities(
    val cameraCount: Int,
    val selectedCameraId: String?,
    val lensFacing: String?,
    val maximumJpegSize: MediaSize?,
    val selectedVideoSize: MediaSize?,
    val supportsThirteenMegapixelPhoto: Boolean,
    val supports1080pVideo: Boolean,
    val hasCameraPermission: Boolean,
    val hasAudioPermission: Boolean,
)

interface MediaCaptureController {
    val isRecording: Boolean
    val events: Flow<MediaCaptureEvent>
        get() = emptyFlow()

    fun inspect(): CameraCapabilities
    suspend fun capturePhoto(relatedEventId: String? = null, locationFix: LocationFix? = null): MediaAsset
    suspend fun startRecording(relatedEventId: String? = null, locationFix: LocationFix? = null): String
    suspend fun stopRecording(): MediaAsset
    fun close()
}

class CameraOperationException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class MediaCaptureTerminationReason {
    USER,
    DURATION_LIMIT,
    FILE_SIZE_LIMIT,
    STORAGE_RESERVE,
    CAMERA_DISCONNECTED,
    CAMERA_ERROR,
    RECORDER_ERROR,
    CLOSED,
}

sealed interface MediaCaptureEvent {
    val assetId: String
    val kind: MediaKind

    data class Finalized(
        override val assetId: String,
        override val kind: MediaKind,
        val asset: MediaAsset,
        val reason: MediaCaptureTerminationReason,
    ) : MediaCaptureEvent

    data class Failed(
        override val assetId: String,
        override val kind: MediaKind,
        val reason: MediaCaptureTerminationReason,
        val errorType: String,
    ) : MediaCaptureEvent
}

/** Ensures callbacks and an explicit stop cannot finalize the same recorder twice. */
class CaptureTerminationGate {
    private val claimed = AtomicBoolean(false)

    fun tryClaim(): Boolean = claimed.compareAndSet(false, true)
}

data class MediaLocationMetadata(
    val latitude: Double?,
    val longitude: Double?,
    val horizontalAccuracyMeters: Float?,
    val fixType: String,
)

object MediaLocationAssociation {
    fun from(fix: LocationFix?): MediaLocationMetadata {
        val usable = fix?.takeIf { it.hasPosition && !it.isMock }
        return MediaLocationMetadata(
            latitude = usable?.latitude,
            longitude = usable?.longitude,
            horizontalAccuracyMeters = usable?.horizontalAccuracyMeters,
            fixType = usable?.quality?.name ?: "NO_FIX",
        )
    }
}

object CameraSelection {
    fun largest(sizes: Collection<MediaSize>): MediaSize? = sizes.maxByOrNull(MediaSize::pixels)

    fun video1080pOrClosest(sizes: Collection<MediaSize>): MediaSize? {
        if (sizes.isEmpty()) return null
        sizes.firstOrNull { it.width == FULL_HD_WIDTH && it.height == FULL_HD_HEIGHT }?.let { return it }
        return sizes
            .filter { it.width <= FULL_HD_WIDTH && it.height <= FULL_HD_HEIGHT }
            .maxByOrNull(MediaSize::pixels)
            ?: sizes.minByOrNull { size ->
                kotlin.math.abs(size.width - FULL_HD_WIDTH) + kotlin.math.abs(size.height - FULL_HD_HEIGHT)
            }
    }

    const val FULL_HD_WIDTH = 1_920
    const val FULL_HD_HEIGHT = 1_080
    const val THIRTEEN_MEGAPIXELS = 13_000_000L
}

internal fun videoRecordingIncludesAudio(
    hasMicrophoneFeature: Boolean,
    hasRecordAudioPermission: Boolean,
): Boolean = hasMicrophoneFeature && hasRecordAudioPermission

object MediaFileRecovery {
    fun deleteIncompleteFiles(mediaRoot: File, protectedPaths: Set<String> = emptySet()): Int {
        val root = runCatching { mediaRoot.canonicalFile }.getOrNull() ?: return 0
        if (!root.isDirectory) return 0
        var deleted = 0
        root.walkTopDown()
            .onEnter { directory -> !Files.isSymbolicLink(directory.toPath()) }
            .filter { file ->
                file.isFile &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    file.name.endsWith(PARTIAL_SUFFIX) &&
                    runCatching { file.canonicalPath }.getOrNull()?.let { path ->
                        path.startsWith(root.path + File.separator) && path !in protectedPaths
                    } == true
            }
            .forEach { file -> if (file.delete()) deleted += 1 }
        return deleted
    }

    private const val PARTIAL_SUFFIX = ".partial"
}

object MediaAssetIdentity {
    fun create(
        deviceId: String,
        kind: MediaKind,
        relatedEventId: String?,
        randomId: () -> String = { UUID.randomUUID().toString() },
    ): String {
        require(deviceId.isNotBlank() && deviceId.length <= 4_096)
        require(relatedEventId == null || relatedEventId.length <= 4_096)
        if (relatedEventId.isNullOrBlank()) return validateRandomId(randomId())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("helmet-media-v1\u0000$deviceId\u0000${kind.name}\u0000$relatedEventId".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "${kind.name.lowercase()}-$digest"
    }

    private fun validateRandomId(value: String): String {
        require(value.matches(ASSET_ID_PATTERN)) { "invalid generated media asset ID" }
        return value
    }

    internal val ASSET_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$")
}

enum class CaptureJournalState {
    CAPTURING,
    PREPARED,
}

/** Metadata needed to restore a finalized private media file into Room after a process crash. */
data class CaptureJournalEntry(
    val assetId: String,
    val kind: MediaKind,
    val state: CaptureJournalState,
    val partialRelativePath: String,
    val finalRelativePath: String,
    val mimeType: String,
    val expectedByteSize: Long?,
    val expectedSha256: String?,
    val width: Int,
    val height: Int,
    val durationMillis: Long?,
    val createdAtEpochMillis: Long,
    val deviceId: String,
    val personId: String?,
    val relatedEventId: String?,
    val latitude: Double?,
    val longitude: Double?,
    val horizontalAccuracyMeters: Float?,
    val locationFixType: String,
    val voiceSenderId: String? = null,
    val voiceSenderRole: VoiceMessageSenderRole? = null,
    val voiceAllowedRoles: Set<VoiceMessageRole> = emptySet(),
    val voiceCallId: String? = null,
) {
    fun validate() {
        require(assetId.matches(MediaAssetIdentity.ASSET_ID_PATTERN))
        require(relativePathIsSafe(partialRelativePath))
        require(relativePathIsSafe(finalRelativePath))
        require(partialRelativePath == "$finalRelativePath.partial")
        val expectedExtension = when (kind) {
            MediaKind.PHOTO -> ".jpg"
            MediaKind.VIDEO -> ".mp4"
            MediaKind.VOICE -> ".m4a"
        }
        val expectedDirectory = kind.name.lowercase()
        require(finalRelativePath == "$expectedDirectory/$assetId$expectedExtension")
        require(mimeType == when (kind) {
            MediaKind.PHOTO -> "image/jpeg"
            MediaKind.VIDEO -> "video/mp4"
            MediaKind.VOICE -> "audio/mp4"
        })
        require(expectedByteSize == null || expectedByteSize in 1..MAX_MEDIA_FILE_BYTES)
        require(expectedSha256 == null || expectedSha256.matches(SHA_256_PATTERN))
        require((expectedByteSize == null) == (expectedSha256 == null))
        if (state == CaptureJournalState.PREPARED) {
            require(expectedByteSize != null)
        } else {
            require(expectedByteSize == null)
        }
        require(width in 0..MAX_DIMENSION && height in 0..MAX_DIMENSION)
        if (kind == MediaKind.VOICE) require(width == 0 && height == 0) else require(width > 0 && height > 0)
        require(durationMillis == null || durationMillis in 1..MAX_DURATION_MILLIS)
        if (state == CaptureJournalState.PREPARED && kind != MediaKind.PHOTO) {
            require(durationMillis != null)
        }
        require(createdAtEpochMillis >= 0)
        require(deviceId.isNotBlank() && deviceId.length <= MAX_TEXT_LENGTH)
        require(personId == null || personId.length <= MAX_TEXT_LENGTH)
        require(relatedEventId == null || relatedEventId.length <= MAX_TEXT_LENGTH)
        require(locationFixType.isNotBlank() && locationFixType.length <= MAX_TEXT_LENGTH)
        require(latitude == null || latitude in -90.0..90.0)
        require(longitude == null || longitude in -180.0..180.0)
        require((latitude == null) == (longitude == null))
        require(horizontalAccuracyMeters == null || horizontalAccuracyMeters.isFinite() && horizontalAccuracyMeters >= 0f)
        require(voiceSenderId == null || voiceSenderId.length <= MAX_TEXT_LENGTH)
        require(voiceCallId == null || voiceCallId.length <= MAX_TEXT_LENGTH)
        if (kind == MediaKind.VOICE) {
            require(!voiceSenderId.isNullOrBlank())
            require(voiceSenderRole != null)
            require(voiceAllowedRoles.isNotEmpty())
        } else {
            require(voiceSenderId == null && voiceSenderRole == null && voiceAllowedRoles.isEmpty() && voiceCallId == null)
        }
    }

    fun toMediaAsset(
        finalFile: File,
        integrity: MediaFileIntegrity = MediaFileIntegrityInspector.inspect(finalFile),
    ): MediaAsset {
        validate()
        require(state == CaptureJournalState.PREPARED)
        require(integrity.byteSize == expectedByteSize) { "final media size differs from capture journal" }
        require(integrity.sha256 == expectedSha256) { "final media digest differs from capture journal" }
        return MediaAsset(
            assetId = assetId,
            kind = kind,
            filePath = finalFile.canonicalPath,
            mimeType = mimeType,
            byteSize = integrity.byteSize,
            sha256 = integrity.sha256,
            width = width,
            height = height,
            durationMillis = durationMillis,
            createdAtEpochMillis = createdAtEpochMillis,
            deviceId = deviceId,
            relatedEventId = relatedEventId,
            transferState = MediaTransferState.PENDING,
            attemptCount = 0,
            personId = personId,
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracyMeters = horizontalAccuracyMeters,
            locationFixType = locationFixType,
            voiceSenderId = voiceSenderId,
            voiceSenderRole = voiceSenderRole,
            voiceAllowedRoles = voiceAllowedRoles,
            voiceCallId = voiceCallId,
        )
    }

    companion object {
        const val MAX_MEDIA_FILE_BYTES = 8L * 1024 * 1024 * 1024
        private const val MAX_DIMENSION = 32_768
        private const val MAX_DURATION_MILLIS = 24L * 60 * 60 * 1_000
        private const val MAX_TEXT_LENGTH = 4_096
        private val SHA_256_PATTERN = Regex("^[0-9a-f]{64}$")

        private fun relativePathIsSafe(value: String): Boolean {
            if (value.isBlank() || value.length > MAX_TEXT_LENGTH || '\u0000' in value) return false
            val path = runCatching { File(value).toPath() }.getOrNull() ?: return false
            return !path.isAbsolute && path.normalize().toString() == value && path.none { it.toString() == ".." }
        }

    }
}

data class MediaFileIntegrity(val byteSize: Long, val sha256: String)

object MediaFileIntegrityInspector {
    fun inspect(file: File): MediaFileIntegrity {
        require(file.isFile && file.length() in 1..CaptureJournalEntry.MAX_MEDIA_FILE_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return MediaFileIntegrity(
            byteSize = file.length(),
            sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) },
        )
    }
}

data class CaptureJournalScan(
    val entries: List<CaptureJournalEntry>,
    val quarantinedJournals: Int,
)

/** Atomic sidecar storage rooted below the application's private media directory. */
class CaptureJournalStore(
    mediaRoot: File,
    private val encryptedStorage: EncryptedMediaFileStorage? = null,
) {
    val root: File = mediaRoot.canonicalFile
    private val journalDirectory = File(root, JOURNAL_DIRECTORY_NAME)
    private val quarantineDirectory = File(journalDirectory, QUARANTINE_DIRECTORY_NAME)

    fun write(entry: CaptureJournalEntry) {
        entry.validate()
        resolvePartial(entry)
        resolveFinal(entry)
        root.mkdirsChecked()
        journalDirectory.mkdirsChecked()
        val destination = journalFile(entry.assetId)
        val temporary = File(journalDirectory, ".${entry.assetId}.${UUID.randomUUID()}.tmp")
        try {
            val encoded = CaptureJournalCodec.encode(entry).let { plaintext ->
                encryptedStorage?.encryptPayload(plaintext) ?: plaintext
            }
            FileOutputStream(temporary).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            moveAtomically(temporary, destination)
            syncDirectory(journalDirectory)
        } finally {
            temporary.delete()
        }
    }

    fun scan(): CaptureJournalScan {
        require(!Files.isSymbolicLink(journalDirectory.toPath())) {
            "capture journal directory must not be a symbolic link"
        }
        if (!journalDirectory.isDirectory) return CaptureJournalScan(emptyList(), 0)
        val entries = mutableListOf<CaptureJournalEntry>()
        var quarantined = 0
        journalDirectory.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(JOURNAL_SUFFIX) && (it.isFile || Files.isSymbolicLink(it.toPath())) }
            .sortedBy(File::getName)
            .forEach { journal ->
                val entry = runCatching {
                    require(!Files.isSymbolicLink(journal.toPath()))
                    require(journal.length() in 1..MAX_JOURNAL_FILE_BYTES)
                    val encoded = journal.readBytes()
                    val plaintext = when {
                        encryptedStorage == null -> encoded
                        encryptedStorage.isEncryptedPayload(encoded) -> encryptedStorage.decryptPayload(encoded)
                        else -> encoded
                    }
                    CaptureJournalCodec.decode(plaintext).also {
                        require(journal.name == "${it.assetId}$JOURNAL_SUFFIX")
                        resolvePartial(it)
                        resolveFinal(it)
                    }
                }.getOrNull()
                if (entry == null) {
                    if (quarantine(journal)) quarantined += 1
                } else {
                    entries += entry
                }
            }
        return CaptureJournalScan(entries, quarantined)
    }

    fun exists(assetId: String): Boolean =
        assetId.matches(MediaAssetIdentity.ASSET_ID_PATTERN) && journalFile(assetId).isFile

    fun clear(entry: CaptureJournalEntry): Boolean = clear(entry.assetId)

    fun clear(assetId: String): Boolean {
        require(assetId.matches(MediaAssetIdentity.ASSET_ID_PATTERN))
        val file = journalFile(assetId)
        if (!file.exists()) return true
        val deleted = file.delete()
        if (deleted) syncDirectory(journalDirectory)
        return deleted
    }

    fun quarantine(entry: CaptureJournalEntry): Boolean = quarantine(journalFile(entry.assetId))

    fun promotePrepared(entry: CaptureJournalEntry): File {
        entry.validate()
        require(entry.state == CaptureJournalState.PREPARED)
        val partial = resolvePartial(entry)
        val final = resolveFinal(entry)
        require(partial.isFile && partial.length() in 1..CaptureJournalEntry.MAX_MEDIA_FILE_BYTES)
        require(!final.exists())
        final.parentFile?.mkdirsChecked()
        moveAtomically(partial, final)
        syncDirectory(requireNotNull(final.parentFile))
        return final
    }

    fun resolvePartial(entry: CaptureJournalEntry): File = resolvePrivate(entry.partialRelativePath)

    fun resolveFinal(entry: CaptureJournalEntry): File = resolvePrivate(entry.finalRelativePath)

    fun isJournalPath(file: File): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val journalRoot = runCatching { journalDirectory.canonicalFile }.getOrNull() ?: return false
        return canonical == journalRoot || canonical.path.startsWith(journalRoot.path + File.separator)
    }

    private fun resolvePrivate(relativePath: String): File {
        require(CaptureJournalEntry.run {
            val path = runCatching { File(relativePath).toPath() }.getOrNull()
            path != null && !path.isAbsolute && path.normalize().toString() == relativePath &&
                path.none { it.toString() == ".." }
        })
        var lexical = root
        File(relativePath).toPath().forEach { segment ->
            lexical = File(lexical, segment.toString())
            require(!Files.isSymbolicLink(lexical.toPath())) { "capture path contains a symbolic link" }
        }
        val resolved = lexical.canonicalFile
        require(resolved.path.startsWith(root.path + File.separator)) { "capture path escapes private media root" }
        require(!resolved.path.startsWith(journalDirectory.canonicalPath + File.separator))
        return resolved
    }

    private fun journalFile(assetId: String): File = File(journalDirectory, "$assetId$JOURNAL_SUFFIX")

    private fun quarantine(journal: File): Boolean = runCatching {
        quarantineDirectory.mkdirsChecked()
        val destination = File(
            quarantineDirectory,
            "${journal.nameWithoutExtension}-${System.currentTimeMillis()}-${UUID.randomUUID()}$QUARANTINE_SUFFIX",
        )
        moveAtomically(journal, destination)
        syncDirectory(quarantineDirectory)
        true
    }.getOrElse { false }

    private fun File.mkdirsChecked() {
        check(!Files.isSymbolicLink(toPath())) { "capture directory must not be a symbolic link" }
        check(isDirectory || mkdirs()) { "failed to create private capture directory" }
        check(canonicalPath == path || canonicalPath.startsWith(root.path + File.separator) || this == root) {
            "capture directory escapes private root"
        }
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun syncDirectory(directory: File) {
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) }
        }
    }

    companion object {
        const val JOURNAL_DIRECTORY_NAME = ".capture-journal"
        private const val QUARANTINE_DIRECTORY_NAME = "quarantine"
        private const val JOURNAL_SUFFIX = ".capture"
        private const val QUARANTINE_SUFFIX = ".bad"
        private const val MAX_JOURNAL_FILE_BYTES = 128L * 1024
    }
}

private object CaptureJournalCodec {
    private const val MAGIC = 0x48434A31
    private const val VERSION = 2
    private const val MAX_PAYLOAD_BYTES = 96 * 1024
    private const val MAX_STRING_BYTES = 16 * 1024

    fun encode(entry: CaptureJournalEntry): ByteArray {
        entry.validate()
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeString(entry.assetId)
                output.writeString(entry.kind.name)
                output.writeString(entry.state.name)
                output.writeString(entry.partialRelativePath)
                output.writeString(entry.finalRelativePath)
                output.writeString(entry.mimeType)
                output.writeNullableLong(entry.expectedByteSize)
                output.writeNullableString(entry.expectedSha256)
                output.writeInt(entry.width)
                output.writeInt(entry.height)
                output.writeNullableLong(entry.durationMillis)
                output.writeLong(entry.createdAtEpochMillis)
                output.writeString(entry.deviceId)
                output.writeNullableString(entry.personId)
                output.writeNullableString(entry.relatedEventId)
                output.writeNullableDouble(entry.latitude)
                output.writeNullableDouble(entry.longitude)
                output.writeNullableFloat(entry.horizontalAccuracyMeters)
                output.writeString(entry.locationFixType)
                output.writeNullableString(entry.voiceSenderId)
                output.writeNullableString(entry.voiceSenderRole?.name)
                output.writeInt(entry.voiceAllowedRoles.size)
                entry.voiceAllowedRoles.map(VoiceMessageRole::name).sorted().forEach { role ->
                    output.writeString(role)
                }
                output.writeNullableString(entry.voiceCallId)
            }
            bytes.toByteArray()
        }
        require(payload.size <= MAX_PAYLOAD_BYTES)
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(payload.size)
                output.write(payload)
                output.write(checksum)
            }
            bytes.toByteArray()
        }
    }

    fun decode(encoded: ByteArray): CaptureJournalEntry {
        val input = DataInputStream(ByteArrayInputStream(encoded))
        require(input.readInt() == MAGIC)
        require(input.readInt() == VERSION)
        val payloadSize = input.readInt()
        require(payloadSize in 1..MAX_PAYLOAD_BYTES)
        require(encoded.size == 12 + payloadSize + 32)
        val payload = ByteArray(payloadSize).also(input::readFully)
        val expectedChecksum = ByteArray(32).also(input::readFully)
        require(MessageDigest.isEqual(expectedChecksum, MessageDigest.getInstance("SHA-256").digest(payload)))
        val payloadInput = DataInputStream(ByteArrayInputStream(payload))
        val entry = CaptureJournalEntry(
            assetId = payloadInput.readString(),
            kind = MediaKind.valueOf(payloadInput.readString()),
            state = CaptureJournalState.valueOf(payloadInput.readString()),
            partialRelativePath = payloadInput.readString(),
            finalRelativePath = payloadInput.readString(),
            mimeType = payloadInput.readString(),
            expectedByteSize = payloadInput.readNullableLong(),
            expectedSha256 = payloadInput.readNullableString(),
            width = payloadInput.readInt(),
            height = payloadInput.readInt(),
            durationMillis = payloadInput.readNullableLong(),
            createdAtEpochMillis = payloadInput.readLong(),
            deviceId = payloadInput.readString(),
            personId = payloadInput.readNullableString(),
            relatedEventId = payloadInput.readNullableString(),
            latitude = payloadInput.readNullableDouble(),
            longitude = payloadInput.readNullableDouble(),
            horizontalAccuracyMeters = payloadInput.readNullableFloat(),
            locationFixType = payloadInput.readString(),
            voiceSenderId = payloadInput.readNullableString(),
            voiceSenderRole = payloadInput.readNullableString()?.let(VoiceMessageSenderRole::valueOf),
            voiceAllowedRoles = buildSet {
                val count = payloadInput.readInt()
                require(count in 0..VoiceMessageRole.entries.size)
                repeat(count) { add(VoiceMessageRole.valueOf(payloadInput.readString())) }
            },
            voiceCallId = payloadInput.readNullableString(),
        )
        require(payloadInput.available() == 0)
        entry.validate()
        return entry
    }

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_STRING_BYTES)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataOutputStream.writeNullableDouble(value: Double?) {
        writeBoolean(value != null)
        if (value != null) writeDouble(value)
    }

    private fun DataOutputStream.writeNullableFloat(value: Float?) {
        writeBoolean(value != null)
        if (value != null) writeFloat(value)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES)
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readString() else null
    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null
    private fun DataInputStream.readNullableDouble(): Double? = if (readBoolean()) readDouble() else null
    private fun DataInputStream.readNullableFloat(): Float? = if (readBoolean()) readFloat() else null
}
