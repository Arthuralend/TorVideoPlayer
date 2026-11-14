package com.arthuralend.mediaplayer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A file the engine can read embedded subtitles from. */
sealed class MediaSource {
    data class Torrent(val infoHash: String, val fileIndex: Int) : MediaSource()
    data class Local(val path: String) : MediaSource()

    val query: String
        get() = when (this) {
            is Torrent -> "ih=$infoHash&file=$fileIndex"
            is Local -> "path=" + URLEncoder.encode(path, "UTF-8")
        }

    companion object {
        val MATROSKA_EXTENSIONS = setOf("mkv", "mka", "mk3d", "webm")
    }
}

/**
 * Runs the bundled Go helper (shipped as libtorengine.so) and talks to it over
 * HTTP on 127.0.0.1. The helper downloads torrents and serves files with Range
 * support, prioritising the pieces the player is reading right now.
 */
object TorrentEngine {
    private var process: Process? = null
    @Volatile private var baseUrl: String? = null
    private val mutex = Mutex()
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun downloadsDir(): File =
        if (Prefs.keepDownloads) File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "Downloads")
        else File(appContext.cacheDir, "torrents")

    suspend fun ensureStarted(): String = mutex.withLock {
        val current = baseUrl
        if (current != null && process?.isAlive == true) return current
        withContext(Dispatchers.IO) { launch() }
    }

    private fun launch(): String {
        val exe = File(appContext.applicationInfo.nativeLibraryDir, "libtorengine.so")
        if (!exe.exists()) throw IOException("Не найден торрент-движок")
        val keep = Prefs.keepDownloads
        val data = downloadsDir()
        if (!keep) data.deleteRecursively()
        data.mkdirs()
        val pb = ProcessBuilder(exe.path, "-data", data.path, "-cleanup=${!keep}")
        pb.redirectError(File(appContext.cacheDir, "engine.log"))
        val p = pb.start()
        process = p
        // The helper prints "LISTENING <port>" once its HTTP server is up.
        val reader = p.inputStream.bufferedReader()
        while (true) {
            val line = reader.readLine() ?: throw IOException("Торрент-движок завершился при запуске")
            if (line.startsWith("LISTENING ")) {
                val port = line.substring(10).trim().toInt()
                val url = "http://127.0.0.1:$port"
                baseUrl = url
                return url
            }
        }
    }

    fun stop() {
        val p = process ?: return
        try {
            // Closing stdin makes the helper shut down and delete temporary files.
            p.outputStream.close()
            Thread { try { Thread.sleep(2000); p.destroy() } catch (_: Exception) {} }.start()
        } catch (_: Exception) {}
        process = null
        baseUrl = null
    }

    fun streamUrl(file: TorrentFile): String? = baseUrl?.let { it + file.streamPath }

    suspend fun add(uri: String): TorrentInfo {
        val base = ensureStarted()
        val body = JSONObject().put("uri", uri).toString()
        return TorrentInfo.parse(JSONObject(request("POST", "$base/api/add", body, 600_000)))
    }

    suspend fun status(infoHash: String, fileIndex: Int?): TorrentStatus {
        val base = ensureStarted()
        val q = fileIndex?.let { "?file=$it" } ?: ""
        return TorrentStatus.parse(JSONObject(request("GET", "$base/api/status/$infoHash$q", null, 10_000)))
    }

    suspend fun drop(infoHash: String) {
        val base = baseUrl ?: return
        try { request("POST", "$base/api/drop/$infoHash", "", 10_000) } catch (_: Exception) {}
    }

    suspend fun mkvTracks(source: MediaSource): List<MkvTrack> {
        val base = ensureStarted()
        val json = JSONObject(request("GET", "$base/api/mkv/tracks?${source.query}", null, 120_000))
        val arr = json.optJSONArray("tracks") ?: return emptyList()
        return (0 until arr.length()).map {
            val t = arr.getJSONObject(it)
            MkvTrack(
                t.getLong("number"), t.optString("codec"), t.optString("language", "und"), t.optString("name"),
                t.optBoolean("default"), t.optBoolean("forced"), t.optBoolean("text"),
            )
        }
    }

    /** Embedded subtitle cues of a track visible between from and to seconds. */
    suspend fun mkvCues(source: MediaSource, track: Long, from: Double, to: Double): List<SubtitleCue> {
        val base = ensureStarted()
        val url = "$base/api/mkv/cues?${source.query}&tracks=$track&from=${"%.3f".format(java.util.Locale.US, from)}&to=${"%.3f".format(java.util.Locale.US, to)}"
        val json = JSONObject(request("GET", url, null, 300_000))
        val arr = json.optJSONObject("cues")?.optJSONArray(track.toString()) ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            val c = arr.getJSONObject(it)
            val text = SubtitleParser.clean(c.getString("text"))
            if (text.isEmpty()) null else SubtitleCue(c.getDouble("start"), c.getDouble("end"), text)
        }
    }

    suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 300_000
        try { conn.inputStream.use { it.readBytes() } } finally { conn.disconnect() }
    }

    private suspend fun request(method: String, url: String, body: String?, timeoutMs: Int): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = timeoutMs
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val message = try { JSONObject(text).optString("error") } catch (_: Exception) { "" }
                throw IOException(message.ifEmpty { "Ошибка торрент-движка (HTTP $code)" })
            }
            text
        } finally {
            conn.disconnect()
        }
    }
}
