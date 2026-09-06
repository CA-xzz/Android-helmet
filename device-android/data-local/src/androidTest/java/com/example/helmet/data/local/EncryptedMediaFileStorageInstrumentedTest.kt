package com.example.helmet.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedMediaFileStorageInstrumentedTest {
    @Test
    fun mediaAndJournalPayloadAreEncryptedAuthenticatedAndRestartReadable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.filesDir, "encrypted-media-test").apply { mkdirs() }
        val file = File(root, "sample.mp4")
        val plaintext = ByteArray(192 * 1024 + 37) { index -> (index * 31).toByte() }
        file.writeBytes(plaintext)
        val storage = EncryptedMediaFileStorage(context)
        val integrity = storage.inspect(file)

        assertTrue(storage.encryptInPlace(file, integrity.byteSize, integrity.sha256))
        assertTrue(storage.isEncrypted(file))
        assertNotEquals(plaintext.size.toLong(), file.length())
        assertFalse(file.readBytes().copyOf(16).contentEquals(plaintext.copyOf(16)))
        assertEquals(integrity, storage.inspect(file))
        assertArrayEquals(plaintext.copyOfRange(65_531, plaintext.size), storage.openPlaintext(file, 65_531).readBytes())

        val restarted = EncryptedMediaFileStorage(context)
        assertFalse(restarted.encryptInPlace(file, integrity.byteSize, integrity.sha256))
        assertArrayEquals(plaintext, restarted.openPlaintext(file).readBytes())

        val journal = "private capture metadata".toByteArray()
        val encryptedJournal = restarted.encryptPayload(journal)
        assertTrue(restarted.isEncryptedPayload(encryptedJournal))
        assertFalse(encryptedJournal.toString(Charsets.UTF_8).contains("private capture metadata"))
        assertArrayEquals(journal, EncryptedMediaFileStorage(context).decryptPayload(encryptedJournal))

        val tampered = encryptedJournal.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertTrue(runCatching { restarted.decryptPayload(tampered) }.isFailure)
    }
}
