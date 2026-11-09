package com.abhijit.airealityscanner.data.local

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.abhijit.airealityscanner.core.util.ImageUtils
import com.abhijit.airealityscanner.domain.model.StoredImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Saves scan images in the app's private storage (never in the public gallery). */
@Singleton
class ImageStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File
        get() = File(context.filesDir, "scans").apply { mkdirs() }

    suspend fun save(bitmap: Bitmap, source: String, quality: Int = 92): StoredImage =
        withContext(Dispatchers.IO) {
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            StoredImage(file.absolutePath, bitmap.width, bitmap.height, source)
        }

    suspend fun import(uri: Uri, maxDim: Int = 2048): StoredImage? = withContext(Dispatchers.IO) {
        val bitmap = ImageUtils.decodeUri(context, uri, maxDim) ?: return@withContext null
        save(bitmap, source = "gallery")
    }

    fun delete(path: String) {
        runCatching { File(path).takeIf { it.parentFile == dir }?.delete() }
    }

    fun listFiles(): List<File> = dir.listFiles()?.toList().orEmpty()

    fun deleteAll() {
        listFiles().forEach { it.delete() }
    }
}
