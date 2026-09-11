package com.lyco256.llm.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream

interface ImageEmbeddingBitmapDecoder {
    fun decode(file: File): Bitmap
}

class LocalImageEmbeddingBitmapDecoder(
    private val targetLongSide: Int = JapaneseClipModelSpec.IMAGE_SIZE,
) : ImageEmbeddingBitmapDecoder {
    init {
        require(targetLongSide > 0) { "Target image side must be positive" }
    }

    override fun decode(file: File): Bitmap {
        require(file.isFile) { "Image source is not a regular file: ${file.path}" }
        require(file.length() > 0L) { "Image source is empty: ${file.path}" }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        FileInputStream(file).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Image bounds decode failed: ${file.path}"
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetLongSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = FileInputStream(file).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            bitmap?.recycle()
            error("Image decode failed: ${file.path}")
        }
        return bitmap
    }

    companion object {
        internal fun sampleSizeFor(width: Int, height: Int, targetLongSide: Int): Int {
            require(width > 0 && height > 0) { "Image bounds must be positive" }
            require(targetLongSide > 0) { "Target image side must be positive" }
            val longSide = maxOf(width, height).toLong()
            var sampleSize = 1
            while (
                sampleSize <= Int.MAX_VALUE / 2 &&
                longSide / (sampleSize.toLong() * 2L) >= targetLongSide.toLong()
            ) {
                sampleSize *= 2
            }
            return sampleSize
        }
    }
}
