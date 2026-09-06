package com.example.helmet.service.runtime

import android.content.Context
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.data.local.EncryptedMediaFileStorage
import com.example.helmet.media.sync.MediaContentIntegrity
import com.example.helmet.media.sync.MediaContentSource
import java.io.File
import java.io.InputStream

internal class EncryptedMediaContentSource(context: Context) : MediaContentSource {
    private val storage = EncryptedMediaFileStorage(context.applicationContext)

    override fun inspect(asset: MediaAsset): MediaContentIntegrity {
        val file = File(asset.filePath)
        if (!storage.isEncrypted(file)) {
            storage.encryptInPlace(file, asset.byteSize, asset.sha256)
            return MediaContentIntegrity(asset.byteSize, asset.sha256)
        }
        return storage.inspect(file).let { integrity ->
            MediaContentIntegrity(integrity.byteSize, integrity.sha256)
        }
    }

    override fun open(asset: MediaAsset, offset: Long): InputStream =
        storage.openPlaintext(File(asset.filePath), offset)
}
