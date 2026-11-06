package com.trackfix.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max

/**
 * Loads a photo from a content URI, applies the EXIF orientation and limits
 * its largest side so a modern phone photo cannot exhaust the heap.
 *
 * The URI is read exactly once. Some gallery providers allow only a single
 * stream per URI, so decoding the bounds and then reopening the photo can
 * fail even for a perfectly valid JPEG.
 */
object PhotoLoader {

    const val DEFAULT_MAX_DIMENSION = 2048

    private const val TAG = "TrackFix"
    private const val MAX_PHOTO_BYTES = 64 * 1024 * 1024

    fun load(
        context: Context,
        uri: Uri,
        maxDimension: Int = DEFAULT_MAX_DIMENSION
    ): Bitmap {
        val bytes = readBytes(context, uri)
        val decoded = decode(bytes, maxDimension)
        return limitSize(decoded, maxDimension)
    }

    private fun readBytes(context: Context, uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("cannot open photo")
        try {
            input.use { stream ->
                val output = ByteArrayOutputStream(64 * 1024)
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_PHOTO_BYTES) {
                        throw IOException("photo is too large")
                    }
                    output.write(buffer, 0, read)
                }
                if (total == 0) throw IOException("photo is empty")
                return output.toByteArray()
            }
        } catch (e: OutOfMemoryError) {
            throw IOException("photo is too large", e)
        }
    }

    private fun decode(bytes: ByteArray, maxDimension: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // decodeByteArray returns null in bounds-only mode; the dimensions are
        // written into the options object. Do not treat that null as a failure.
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("cannot read photo bounds")
        }

        val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        val attempts = listOf(
            sample to Bitmap.Config.ARGB_8888,
            sample to Bitmap.Config.RGB_565,
            max(sample * 2, 2) to Bitmap.Config.RGB_565,
            max(sample * 4, 4) to Bitmap.Config.RGB_565
        )
        for ((currentSample, config) in attempts) {
            val bitmap = decodeBitmap(bytes, currentSample, config) ?: continue
            return applyOrientation(bitmap, readOrientation(bytes))
        }
        throw IOException("cannot decode photo")
    }

    private fun decodeBitmap(
        bytes: ByteArray,
        sampleSize: Int,
        config: Bitmap.Config
    ): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = config
            inScaled = false
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "BitmapFactory ran out of memory at sample size $sampleSize", e)
            null
        }
    }

    private fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxDimension || height / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        return sample
    }

    @Suppress("DEPRECATION")
    private fun readOrientation(bytes: ByteArray): Int {
        return try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Cannot rotate photo: out of memory", e)
            bitmap
        }
    }

    private fun limitSize(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / largest
        return try {
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== bitmap) bitmap.recycle()
            scaled
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Cannot scale photo: out of memory", e)
            bitmap
        }
    }
}
