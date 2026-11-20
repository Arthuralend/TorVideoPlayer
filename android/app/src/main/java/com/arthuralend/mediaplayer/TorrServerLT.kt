package com.arthuralend.mediaplayer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder

/**
 * Experimental "FS MediaPlayer LT" flavor: torrents are downloaded and
 * streamed by TorrServer-LT (libtorrent, GPL-3.0), shipped in that flavor as
 * libtorrserverlt.so. Our own engine still runs alongside it, only to read
 * embedded MKV subtitles from TorrServer's stream (MediaSource.Http).
 */
object TorrServerLT {
    private var process: Process? = null
    @Volatile private var baseUrl: String? = null
    private val mutex = Mutex()
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun logFile(): File = File(appContext.cacheDir, "torrserver.log")

    suspend fun ensureStarted(): String = mutex.withLock {
        val current = baseUrl
        if (current != null && process?.isAlive == true) return current
        launch()
    }

    private suspend fun launch(): String {
        val exe = File(appContext.applicationInfo.nativeLibraryDir, "libtorrserverlt.so")
        if (!exe.exists()) throw IOException("Не найден TorrServer-LT для этого устройства")
        val dir = File(appContext.filesDir, "torrserver").apply { mkdirs() }
        val port = withContext(Dispatchers.IO) { ServerSocket(0).use { it.localPort } }
        val pb = ProcessBuilder(
            exe.path, "--port", port.toString(), "--ip", "127.0.0.1",
            "--path", dir.path, "--logpath", logFile().path,
        )
        pb.directory(dir)
        pb.environment()["HOME"] = dir.path
        pb.environment()["TMPDIR"] = appContext.cacheDir.path
        pb.redirectErrorStream(true)
        pb.redirectOutput(File(appContext.cacheDir, "torrserver.out"))
        val p = withContext(Dispatchers.IO) { pb.start() }
        process = p
        // Wait until the web server answers (it loads tracker lists first).
        val url = "http://127.0.0.1:$port"
        val deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) throw IOException("TorrServer-LT завершился при запуске")
            val ok = try { request("GET", "$url/echo", null, 2_000); true } catch (_: Exception) { false }
            if (ok) { baseUrl = url; return url }
            delay(300)
        }
        p.destroy()
        throw IOException("TorrServer-LT не запустился")
    }

    fun stop() {
        process?.destroy()
        process = null
        baseUrl = null
    }

    suspend fun add(uri: String): TorrentInfo {
        val base = ensureStarted()
        val added = if (uri.startsWith("/")) upload(base, File(uri))
        else post(base, JSONObject().put("action", "add").put("link", uri))
        val hash = added.optString("hash")
        if (hash.isEmpty()) throw IOException("TorrServer-LT не принял торрент")
        // Metadata arrives from peers; poll until the file list is known.
        var current = added
        while ((current.optJSONArray("file_stats")?.length() ?: 0) == 0) {
            yield()
            delay(500)
            current = post(base, JSONObject().put("action", "get").put("hash", hash))
        }
        return info(current, hash)
    }

    suspend fun status(infoHash: String): TorrentStatus {
        val s = post(ensureStarted(), JSONObject().put("action", "get").put("hash", infoHash))
        return TorrentStatus(
            s.optInt("total_peers"), s.optInt("active_peers"), s.optDouble("download_speed", 0.0),
            0, 0,
        )
    }

    suspend fun drop(infoHash: String) {
        val base = baseUrl ?: return
        try { post(base, JSONObject().put("action", "drop").put("hash", infoHash)) } catch (_: Exception) {}
    }

    fun streamUrl(file: TorrentFile): String? = baseUrl?.let { it + file.streamPath }

    private fun info(s: JSONObject, hash: String): TorrentInfo {
        val arr = s.optJSONArray("file_stats")
        val files = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val f = arr!!.getJSONObject(i)
            val id = f.optInt("id", -1)
            val path = f.optString("path")
            if (id < 0 || path.isEmpty()) return@mapNotNull null
            val name = URLEncoder.encode(path.substringAfterLast('/'), "UTF-8").replace("+", "%20")
            TorrentFile(id, path, f.optLong("length"), kind(path), "/stream/$name?link=$hash&index=$id&play")
        }
        val name = s.optString("name").ifEmpty { s.optString("title") }.ifEmpty { hash }
        return TorrentInfo(hash, name, s.optLong("torrent_size", files.sumOf { it.length }), files)
    }

    private val VIDEO = setOf("mkv", "mp4", "m4v", "avi", "mov", "wmv", "webm", "ts", "m2ts", "mts", "mpg", "mpeg", "flv", "ogv", "3gp", "vob", "divx", "rmvb")
    private val AUDIO = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ac3", "dts", "mka", "eac3")

    private fun kind(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when {
            ext in VIDEO -> "video"
            ext in AUDIO -> "audio"
            ext in SubtitleParser.SUPPORTED_EXTENSIONS || ext == "sub" || ext == "idx" -> "subtitle"
            else -> "other"
        }
    }

    private suspend fun post(base: String, body: JSONObject): JSONObject =
        JSONObject(request("POST", "$base/torrents", body.toString().toByteArray() to "application/json", 60_000))

    private suspend fun upload(base: String, file: File): JSONObject {
        val boundary = "fsmp-" + System.nanoTime()
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name.replace("\"", "")}\"\r\n" +
            "Content-Type: application/x-bittorrent\r\n\r\n"
        val data = withContext(Dispatchers.IO) { file.readBytes() }
        val body = head.toByteArray() + data + "\r\n--$boundary--\r\n".toByteArray()
        return JSONObject(request("POST", "$base/torrent/upload", body to "multipart/form-data; boundary=$boundary", 60_000))
    }

    private suspend fun request(method: String, url: String, body: Pair<ByteArray, String>?, timeoutMs: Int): String =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = minOf(timeoutMs, 10_000)
            conn.readTimeout = timeoutMs
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", body.second)
                conn.outputStream.use { it.write(body.first) }
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    val message = try { JSONObject(text).optString("error") } catch (_: Exception) { "" }
                    throw IOException("TorrServer-LT: " + message.ifEmpty { "HTTP $code" })
                }
                text
            } finally {
                conn.disconnect()
            }
        }
}
