package com.halfblanket.cloudly.work

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.halfblanket.cloudly.data.drive.MimeTypes
import com.halfblanket.cloudly.data.drive.UploadSource
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Helpers for reading local content and writing downloads to device storage. */
object LocalFiles {

    data class LocalMeta(val name: String, val size: Long?, val mime: String)

    fun meta(context: Context, uri: Uri): LocalMeta {
        var name: String? = null
        var size: Long? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si).takeIf { it >= 0 }
                    }
                }
        }
        val finalName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Upload"
        val mime = context.contentResolver.getType(uri) ?: MimeTypes.fromName(finalName)
        return LocalMeta(finalName, size, mime)
    }

    /** An upload source over a content URI. Each [open] re-opens the stream and skips to the offset. */
    class ContentUriSource(private val context: Context, private val uri: Uri, override val length: Long) : UploadSource {
        override fun open(offset: Long): InputStream {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't read the selected file")
            skipFully(input, offset)
            return input
        }
    }

    class FileUploadSource(private val file: File) : UploadSource {
        override val length: Long get() = file.length()
        override fun open(offset: Long): InputStream = file.inputStream().also { skipFully(it, offset) }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) throw IOException("Unexpected end of file")
                remaining--
            } else remaining -= skipped
        }
    }

    /** A writable download target plus a way to finalize or discard it. */
    class DownloadTarget(
        val output: OutputStream,
        val uri: Uri,
        val displayPath: String,
        private val onCommit: () -> Unit,
        private val onAbort: () -> Unit,
    ) {
        fun commit() = onCommit()
        fun abort() = runCatching { onAbort() }
    }

    /**
     * Opens a new file for a download: inside the user's chosen folder (Storage Access Framework) if one is
     * set, otherwise in Downloads/Cloudly.
     */
    fun openDownloadTarget(context: Context, treeUri: String?, name: String, mime: String): DownloadTarget {
        val resolver = context.contentResolver
        if (treeUri != null) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            if (tree != null && tree.canWrite()) {
                val doc = tree.createFile(mime, name) ?: throw IOException("Couldn't create $name in the download folder")
                val out = resolver.openOutputStream(doc.uri) ?: throw IOException("Couldn't write $name")
                return DownloadTarget(out, doc.uri, "${tree.name ?: "Selected folder"}/${doc.name ?: name}", {}, { doc.delete() })
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Cloudly")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Couldn't create $name in Downloads")
            val out = resolver.openOutputStream(uri) ?: throw IOException("Couldn't write $name")
            return DownloadTarget(
                out, uri, "Downloads/Cloudly/$name",
                onCommit = {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                },
                onAbort = { resolver.delete(uri, null, null) },
            )
        }
        // Android 8–9: public Downloads needs the storage permission; without it, use the app's own
        // external folder (Android/data/<app>/files/Download) so the download still succeeds.
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        @Suppress("DEPRECATION")
        val dir = if (granted) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Cloudly")
        } else {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")
        }
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Couldn't create the download folder")
        val file = uniqueFile(dir, name)
        val label = if (granted) "Downloads/Cloudly/${file.name}" else file.absolutePath
        return DownloadTarget(file.outputStream(), Uri.fromFile(file), label, {}, { file.delete() })
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (candidate.exists()) candidate = File(dir, "$base ($i)$ext").also { i++ }
        return candidate
    }

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().ifEmpty { "file" }.take(180)

    fun dirSize(dir: File): Long = if (!dir.exists()) 0 else dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
