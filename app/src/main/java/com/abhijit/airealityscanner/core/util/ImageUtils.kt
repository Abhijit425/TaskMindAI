package com.abhijit.airealityscanner.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Bitmap helpers: decoding with down-sampling, EXIF rotation, resizing and JPEG/Base64 encoding. */
object ImageUtils {

    /** Converts a CameraX frame/capture to an upright bitmap, scaled so its longest side is ≤ [maxDim]. */
    fun imageProxyToBitmap(image: ImageProxy, maxDim: Int = 2048): Bitmap {
        val raw = image.toBitmap()
        val rotated = rotate(raw, image.imageInfo.rotationDegrees.toFloat())
        return scaleDown(rotated, maxDim)
    }

    fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    /** Down-scales (never up-scales) so that the longest side is at most [maxDim]. */
    fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longest
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    fun toJpegBytes(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 100), out)
        return out.toByteArray()
    }

    fun toJpegBase64(bitmap: Bitmap, quality: Int): String =
        Base64.encodeToString(toJpegBytes(bitmap, quality), Base64.NO_WRAP)

    fun decodeFile(path: String, maxDim: Int): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
        }
        val bitmap = BitmapFactory.decodeFile(path, opts) ?: return null
        return scaleDown(bitmap, maxDim)
    }

    /** Decodes a gallery/content URI honouring EXIF orientation. */
    fun decodeUri(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        return scaleDown(rotate(decoded, rotation), maxDim)
    }

    private fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        var longest = max(width, height)
        while (longest / 2 >= maxDim) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    /** 16×16 luminance grid used by scene change detection. */
    fun lumaSignature(bitmap: Bitmap, size: Int = 16): FloatArray {
        val small = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        small.getPixels(pixels, 0, size, 0, 0, size, size)
        if (small != bitmap) small.recycle()
        return FloatArray(pixels.size) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
    }
}
