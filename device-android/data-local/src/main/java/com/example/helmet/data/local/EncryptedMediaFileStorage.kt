package com.example.helmet.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedMediaIntegrity(val byteSize: Long, val sha256: String)

/** Application-level encryption for finalized media and capture-journal payloads. */
class EncryptedMediaFileStorage(context: Context) {
    private val keyStore = MediaKeyStore(context.applicationContext)

    fun isEncrypted(file: File): Boolean = file.isFile && readHeader(file) != null

    fun encryptInPlace(file: File, expectedByteSize: Long, expectedSha256: String): Boolean =
        synchronized(FILE_MIGRATION_LOCK) {
            encryptInPlaceLocked(file, expectedByteSize, expectedSha256)
        }

    private fun encryptInPlaceLocked(file: File, expectedByteSize: Long, expectedSha256: String): Boolean {
        require(expectedByteSize in 1..MAX_PLAINTEXT_BYTES)
        require(expectedSha256.matches(SHA_256))
        val parent = requireNotNull(file.parentFile)
        val staging = File(parent, ".${file.name}.encrypted-new")
        val backup = File(parent, ".${file.name}.plaintext-backup")
        if (!file.exists() && backup.isFile) moveAtomically(backup, file)
        readHeader(file)?.let { header ->
            require(header.byteSize == expectedByteSize && header.sha256 == expectedSha256) {
                "encrypted media metadata differs from the stored asset"
            }
            require(inspect(file) == EncryptedMediaIntegrity(expectedByteSize, expectedSha256)) {
                "encrypted media integrity differs from the stored asset"
            }
            check(backup.delete() || !backup.exists()) { "plaintext media backup could not be removed" }
            staging.delete()
            return false
        }
        require(file.isFile && file.length() == expectedByteSize) { "plaintext media size changed" }
        check(!Files.isSymbolicLink(file.toPath())) { "media file must not be a symbolic link" }
        staging.delete()
        backup.delete()
        val key = keyStore.loadOrCreate(allowCreate = true)
        try {
            writeEncrypted(file, staging, expectedByteSize, expectedSha256, key)
            require(inspectEncrypted(staging, key) == EncryptedMediaIntegrity(expectedByteSize, expectedSha256)) {
                "encrypted media verification failed"
            }
            moveAtomically(file, backup)
            try {
                moveAtomically(staging, file)
                require(inspectEncrypted(file, key) == EncryptedMediaIntegrity(expectedByteSize, expectedSha256)) {
                    "installed encrypted media verification failed"
                }
                check(backup.delete() || !backup.exists()) { "plaintext media backup could not be removed" }
                syncDirectory(parent)
            } catch (error: Throwable) {
                if (backup.exists()) {
                    file.delete()
                    moveAtomically(backup, file)
                }
                throw error
            }
        } finally {
            key.fill(0)
            staging.delete()
        }
        return true
    }

    fun inspect(file: File): EncryptedMediaIntegrity {
        val header = readHeader(file)
        if (header == null) return inspectPlaintext(file)
        val key = keyStore.loadOrCreate(allowCreate = false)
        return try {
            inspectEncrypted(file, key)
        } finally {
            key.fill(0)
        }
    }

    fun openPlaintext(file: File, offset: Long = 0): InputStream {
        require(offset >= 0)
        val header = readHeader(file)
        val input = if (header == null) {
            FileInputStream(file)
        } else {
            val key = keyStore.loadOrCreate(allowCreate = false)
            try {
                encryptedInput(file, header, key)
            } finally {
                key.fill(0)
            }
        }
        try {
            skipFully(input, offset)
            return input
        } catch (error: Throwable) {
            input.close()
            throw error
        }
    }

    fun encryptPayload(plaintext: ByteArray): ByteArray {
        require(plaintext.size in 1..MAX_PAYLOAD_BYTES)
        val key = keyStore.loadOrCreate(allowCreate = true)
        return try {
            val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
            val header = payloadHeader(plaintext.size, iv)
            val cipher = cipher(Cipher.ENCRYPT_MODE, key, iv, header)
            header + cipher.doFinal(plaintext)
        } finally {
            key.fill(0)
        }
    }

    fun decryptPayload(encoded: ByteArray): ByteArray {
        require(encoded.size in PAYLOAD_HEADER_BYTES + TAG_BYTES + 1..MAX_PAYLOAD_ENCODED_BYTES)
        val input = DataInputStream(ByteArrayInputStream(encoded))
        val magic = ByteArray(PAYLOAD_MAGIC.size).also(input::readFully)
        require(magic.contentEquals(PAYLOAD_MAGIC)) { "capture journal is not encrypted" }
        val size = input.readInt()
        require(size in 1..MAX_PAYLOAD_BYTES)
        val iv = ByteArray(IV_BYTES).also(input::readFully)
        val header = encoded.copyOfRange(0, PAYLOAD_HEADER_BYTES)
        val ciphertext = encoded.copyOfRange(PAYLOAD_HEADER_BYTES, encoded.size)
        val key = keyStore.loadOrCreate(allowCreate = false)
        return try {
            cipher(Cipher.DECRYPT_MODE, key, iv, header).doFinal(ciphertext).also {
                require(it.size == size) { "capture journal size changed" }
            }
        } finally {
            key.fill(0)
        }
    }

    fun isEncryptedPayload(encoded: ByteArray): Boolean =
        encoded.size >= PAYLOAD_MAGIC.size && encoded.copyOfRange(0, PAYLOAD_MAGIC.size).contentEquals(PAYLOAD_MAGIC)

    private fun writeEncrypted(
        source: File,
        destination: File,
        expectedByteSize: Long,
        expectedSha256: String,
        key: ByteArray,
    ) {
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val header = mediaHeader(expectedByteSize, expectedSha256, iv)
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(destination).use { fileOutput ->
            fileOutput.write(header)
            CipherOutputStream(
                fileOutput,
                cipher(Cipher.ENCRYPT_MODE, key, iv, header),
            ).use { encryptedOutput ->
                FileInputStream(source).use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        encryptedOutput.write(buffer, 0, count)
                        total += count
                    }
                    require(total == expectedByteSize) { "plaintext media size changed during encryption" }
                }
            }
        }
        FileOutputStream(destination, true).use { it.fd.sync() }
        require(hex(digest.digest()) == expectedSha256) { "plaintext media digest changed during encryption" }
    }

    private fun inspectEncrypted(file: File, key: ByteArray): EncryptedMediaIntegrity {
        val header = requireNotNull(readHeader(file)) { "encrypted media header is missing" }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        encryptedInput(file, header, key).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                total += count
                require(total <= header.byteSize) { "encrypted media exceeds its authenticated size" }
            }
        }
        val actual = EncryptedMediaIntegrity(total, hex(digest.digest()))
        require(actual.byteSize == header.byteSize && actual.sha256 == header.sha256) {
            "encrypted media authentication failed"
        }
        return actual
    }

    private fun encryptedInput(file: File, header: Header, key: ByteArray): InputStream {
        val fileInput = FileInputStream(file)
        try {
            skipFully(fileInput, MEDIA_HEADER_BYTES.toLong())
            val decrypted = CipherInputStream(
                fileInput,
                cipher(Cipher.DECRYPT_MODE, key, header.iv, header.encoded),
            )
            return object : FilterInputStream(decrypted) {}
        } catch (error: Throwable) {
            fileInput.close()
            throw error
        }
    }

    private fun inspectPlaintext(file: File): EncryptedMediaIntegrity {
        require(file.isFile && file.length() in 1..MAX_PLAINTEXT_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return EncryptedMediaIntegrity(file.length(), hex(digest.digest()))
    }

    private fun readHeader(file: File): Header? {
        if (!file.isFile || file.length() < MEDIA_HEADER_BYTES + TAG_BYTES) return null
        FileInputStream(file).use { stream ->
            val encoded = ByteArray(MEDIA_HEADER_BYTES)
            DataInputStream(stream).readFully(encoded)
            val input = DataInputStream(ByteArrayInputStream(encoded))
            val magic = ByteArray(MEDIA_MAGIC.size).also(input::readFully)
            if (!magic.contentEquals(MEDIA_MAGIC)) return null
            val byteSize = input.readLong()
            require(byteSize in 1..MAX_PLAINTEXT_BYTES)
            val sha = ByteArray(SHA_256_BYTES).also(input::readFully)
            val iv = ByteArray(IV_BYTES).also(input::readFully)
            require(file.length() == MEDIA_HEADER_BYTES + byteSize + TAG_BYTES) {
                "encrypted media length is invalid"
            }
            return Header(byteSize, hex(sha), iv, encoded)
        }
    }

    private fun mediaHeader(byteSize: Long, sha256: String, iv: ByteArray): ByteArray =
        ByteArrayOutputStream(MEDIA_HEADER_BYTES).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MEDIA_MAGIC)
                output.writeLong(byteSize)
                output.write(hexBytes(sha256))
                output.write(iv)
            }
            bytes.toByteArray().also { require(it.size == MEDIA_HEADER_BYTES) }
        }

    private fun payloadHeader(size: Int, iv: ByteArray): ByteArray =
        ByteArrayOutputStream(PAYLOAD_HEADER_BYTES).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(PAYLOAD_MAGIC)
                output.writeInt(size)
                output.write(iv)
            }
            bytes.toByteArray().also { require(it.size == PAYLOAD_HEADER_BYTES) }
        }

    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad)
        }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
        syncDirectory(requireNotNull(destination.parentFile))
    }

    private fun syncDirectory(directory: File) {
        runCatching { FileInputStream(directory).use { it.fd.sync() } }
    }

    private fun skipFully(input: InputStream, requested: Long) {
        var remaining = requested
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                check(input.read() >= 0) { "media offset exceeds plaintext length" }
                remaining -= 1
            }
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun hexBytes(value: String): ByteArray = ByteArray(SHA_256_BYTES) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private data class Header(
        val byteSize: Long,
        val sha256: String,
        val iv: ByteArray,
        val encoded: ByteArray,
    )

    companion object {
        private val FILE_MIGRATION_LOCK = Any()
        private val MEDIA_MAGIC = "HLMEDIA1".toByteArray(Charsets.US_ASCII)
        private val PAYLOAD_MAGIC = "HLJRNL01".toByteArray(Charsets.US_ASCII)
        private const val IV_BYTES = 12
        private const val SHA_256_BYTES = 32
        private const val TAG_BYTES = 16
        private const val TAG_BITS = TAG_BYTES * 8
        private const val MEDIA_HEADER_BYTES = 8 + 8 + SHA_256_BYTES + IV_BYTES
        private const val PAYLOAD_HEADER_BYTES = 8 + 4 + IV_BYTES
        private const val MAX_PAYLOAD_BYTES = 128 * 1024
        private const val MAX_PAYLOAD_ENCODED_BYTES = MAX_PAYLOAD_BYTES + PAYLOAD_HEADER_BYTES + TAG_BYTES
        private const val MAX_PLAINTEXT_BYTES = 8L * 1024 * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

private class MediaKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var cachedKey: ByteArray? = null

    fun loadOrCreate(allowCreate: Boolean): ByteArray = synchronized(KEY_LOCK) {
        cachedKey?.let { return@synchronized it.copyOf() }
        read()?.let { key ->
            cachedKey = key.copyOf()
            return@synchronized key
        }
        check(allowCreate) { "encrypted media key is missing" }
        ByteArray(KEY_BYTES)
            .also(SecureRandom()::nextBytes)
            .also(::write)
            .also { key -> cachedKey = key.copyOf() }
    }

    private fun read(): ByteArray? {
        if (!preferences.contains(KEY_CIPHERTEXT) && !preferences.contains(KEY_IV)) return null
        check(preferences.getInt(KEY_VERSION, -1) == FORMAT_VERSION) { "media key version is unsupported" }
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: error("media key record is incomplete")
        val iv = preferences.getString(KEY_IV, null) ?: error("media key record is incomplete")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val wrappingKey = existingWrappingKey() ?: error("media AndroidKeyStore key is missing")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).also {
            check(it.size == KEY_BYTES) { "media key has invalid length" }
        }
    }

    private fun write(key: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingWrappingKey() ?: createWrappingKey())
        val ciphertext = cipher.doFinal(key)
        check(
            preferences.edit()
                .putInt(KEY_VERSION, FORMAT_VERSION)
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit(),
        ) { "failed to persist encrypted media key" }
    }

    private fun existingWrappingKey(): SecretKey? = KeyStore.getInstance(KEYSTORE_PROVIDER).run {
        load(null)
        getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun createWrappingKey(): SecretKey = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        KEYSTORE_PROVIDER,
    ).run {
        init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
    }

    companion object {
        private val KEY_LOCK = Any()
        private const val PREFERENCES_NAME = "helmet_media_key"
        private const val KEY_ALIAS = "helmet_media_file_aes_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val KEY_BYTES = 32
        private const val FORMAT_VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_IV = "iv"
        private const val KEY_CIPHERTEXT = "ciphertext"
    }
}
