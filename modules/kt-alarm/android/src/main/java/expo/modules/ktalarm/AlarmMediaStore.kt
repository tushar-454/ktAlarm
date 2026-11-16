package expo.modules.ktalarm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max

/** Private, durable media, also readable during Direct Boot. All imports/decoding run off main. */
internal object AlarmMediaStore {
  val io = Executors.newSingleThreadExecutor()
  private fun directory(context: Context) = File(
    context.createDeviceProtectedStorageContext().filesDir, "alarm_media",
  ).apply { mkdirs() }

  fun checkedUri(context: Context, value: String?): String? {
    if (value.isNullOrBlank()) return null
    val uri = Uri.parse(value)
    require(uri.scheme == "file") { "Choose media from your phone first." }
    val file = File(requireNotNull(uri.path)).canonicalFile
    require(file.parentFile == directory(context).canonicalFile && file.isFile) {
      "The selected file is unavailable. Choose it again."
    }
    return Uri.fromFile(file).toString()
  }

  fun importMedia(context: Context, uri: Uri, kind: String): Map<String, String> {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri).orEmpty()
    require(mime.startsWith(if (kind == "image") "image/" else "audio/")) {
      "Choose ${if (kind == "image") "an image" else "an audio file"}."
    }
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
      if (it.moveToFirst()) it.getString(0) else null
    }?.take(120) ?: if (kind == "image") "My background" else "My ringtone"
    val dir = directory(context)
    val source = File(dir, "${UUID.randomUUID()}.import")
    val target = File(dir, "${UUID.randomUUID()}.${if (kind == "image") "jpg" else "audio"}")
    try {
      val limit = (if (kind == "image") 25 else 50) * 1024 * 1024L
      resolver.openInputStream(uri)?.use { input ->
        source.outputStream().use { output ->
          val buffer = ByteArray(32 * 1024)
          var total = 0L
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Choose a file smaller than ${limit / 1024 / 1024} MB." }
            output.write(buffer, 0, count)
          }
        }
      } ?: error("Could not open this file.")
      if (kind == "image") {
        val bitmap = decodeImage(source)
        try {
          target.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
        } finally {
          bitmap.recycle()
        }
      } else {
        val metadata = MediaMetadataRetriever()
        try {
          metadata.setDataSource(source.absolutePath)
          require((metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0) {
            "This audio file cannot be played. Choose another ringtone."
          }
        } finally {
          metadata.release()
        }
        check(source.renameTo(target)) { "Could not store the ringtone." }
      }
      // Replaced files and cancelled selections are collected later, without touching saved media.
      val used = AlarmStore.all(context).flatMap { listOfNotNull(it.ringtoneUri, it.backgroundUri) }.toSet()
      val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
      dir.listFiles()?.filter { it != target && it.lastModified() < cutoff && Uri.fromFile(it).toString() !in used }
        ?.forEach { it.delete() }
      return mapOf("uri" to Uri.fromFile(target).toString(), "name" to name)
    } catch (e: Exception) {
      target.delete()
      throw e
    } finally {
      source.delete()
    }
  }

  private fun decodeImage(source: File): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      // ImageDecoder applies EXIF orientation, and downsamples before allocating the bitmap.
      return ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
        val scale = max(info.size.width, info.size.height) / 1600f
        if (scale > 1) decoder.setTargetSize(
          (info.size.width / scale).toInt().coerceAtLeast(1),
          (info.size.height / scale).toInt().coerceAtLeast(1),
        )
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
    }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, options)
    require(options.outWidth > 0 && options.outHeight > 0) { "Could not read this image." }
    options.inSampleSize = 1
    while (max(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
    options.inJustDecodeBounds = false
    return requireNotNull(BitmapFactory.decodeFile(source.absolutePath, options)) { "Could not read this image." }
  }
}
