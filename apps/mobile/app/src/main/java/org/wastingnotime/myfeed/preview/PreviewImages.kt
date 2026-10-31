package org.wastingnotime.myfeed.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Only static raster formats accepted; inspect dimensions before allocating pixels.
 * Cached thumbnails are private, bounded JPEGs. The UI never fetches remote image URLs. */
class PreviewImages(context: Context) {
    private val directory = File(context.filesDir, "previews")
    fun save(id: Long, bytes: ByteArray): String? {
        if (bytes.size > SafeHttp.IMAGE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/webp") ||
            bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 ||
            bounds.outWidth.toLong() * bounds.outHeight > 8_000_000) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 1024 || bounds.outHeight / inSampleSize > 1024) inSampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        try {
            directory.mkdirs()
            val name = "$id.jpg"
            val temp = File(directory, "$id.tmp")
            try {
                temp.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) return null }
                if (temp.length() > SafeHttp.IMAGE_BYTES || !temp.renameTo(File(directory, name))) return null
                return name
            } finally { temp.delete() }
        } finally { bitmap.recycle() }
    }

    fun load(name: String): Bitmap? = if (name.matches(Regex("[0-9]+\\.jpg")))
        BitmapFactory.decodeFile(File(directory, name).path) else null
}
