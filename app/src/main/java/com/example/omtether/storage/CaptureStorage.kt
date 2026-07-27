package com.example.omtether.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.example.omtether.camera.DownloadedObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedObject(
    val filename: String,
    val uri: Uri,
    val mimeType: String,
    val byteCount: Int,
    val relativePath: String,
)

class CaptureStorage(private val context: Context) {
    suspend fun saveOne(item: DownloadedObject): SavedObject = withContext(Dispatchers.IO) {
        saveOneBlocking(item)
    }

    private fun saveOneBlocking(item: DownloadedObject): SavedObject {
        require(item.bytes.isNotEmpty()) { "Refusing to save an empty camera object" }
        val resolver = context.contentResolver
        val filename = safeFilename(item.filename)
        val mimeType = mimeType(filename)
        val isJpeg = mimeType == "image/jpeg"
        val collection = if (isJpeg) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val datedFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val relativePath = CapturePathPolicy.relativePath(datedFolder)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: error("MediaStore could not create $filename")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                output.write(item.bytes)
                output.flush()
            } ?: error("MediaStore could not open $filename")
            val publishedRows = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            check(publishedRows > 0) { "MediaStore could not publish $filename" }
            return SavedObject(
                filename = filename,
                uri = uri,
                mimeType = mimeType,
                byteCount = item.bytes.size,
                relativePath = relativePath.trimEnd('/'),
            )
        } catch (error: Throwable) {
            // This removes only the new, incomplete MediaStore row created above.
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun safeFilename(input: String): String {
        val leaf = input.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F<>:\"/\\\\|?*]"), "_")
            .trim()
            .ifBlank { "OM_CAPTURE_${System.currentTimeMillis()}.BIN" }
        if (leaf.length <= 120) return leaf
        val extension = leaf.substringAfterLast('.', "")
        if (extension.isBlank() || extension.length >= 120) return leaf.take(120)
        val stemLimit = (119 - extension.length).coerceAtLeast(1)
        return leaf.take(stemLimit) + ".${extension}"
    }

    private fun mimeType(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "orf" -> "image/x-olympus-orf"
        else -> "application/octet-stream"
    }
}
