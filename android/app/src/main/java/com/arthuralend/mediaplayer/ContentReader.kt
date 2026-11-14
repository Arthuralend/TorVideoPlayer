package com.arthuralend.mediaplayer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Reads content:// and file:// URIs handed to the app by other apps. */
object ContentReader {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun read(url: String): ByteArray = withContext(Dispatchers.IO) {
        val uri = Uri.parse(url)
        if (uri.scheme == "file" || uri.scheme == null) File(uri.path ?: url).readBytes()
        else appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Не удалось открыть файл")
    }

    fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { return it }
                }
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** Copies a .torrent file to the cache so the engine can read it by path. */
    suspend fun copyToCache(uri: Uri, name: String): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "incoming").apply { mkdirs() }
        val file = File(dir, name.replace('/', '_'))
        appContext.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("Не удалось открыть файл")
        file
    }
}
