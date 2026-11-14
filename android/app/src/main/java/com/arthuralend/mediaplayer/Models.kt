package com.arthuralend.mediaplayer

import org.json.JSONObject

data class TorrentFile(val index: Int, val path: String, val length: Long, val kind: String, val streamPath: String) {
    val name: String get() = path.substringAfterLast('/')
    val directory: String get() = if (path.contains('/')) path.substringBeforeLast('/') else ""
    val extension: String get() = path.substringAfterLast('.', "").lowercase()
    val isPlayable: Boolean get() = kind == "video" || kind == "audio"
    val isTextSubtitle: Boolean get() = extension in SubtitleParser.SUPPORTED_EXTENSIONS
}

data class TorrentInfo(val infoHash: String, val name: String, val length: Long, val files: List<TorrentFile>) {
    /** Video (or, failing that, audio) files in natural order, so episodes follow each other. */
    val playableFiles: List<TorrentFile>
        get() {
            val videos = files.filter { it.kind == "video" }
            return (videos.ifEmpty { files.filter { it.isPlayable } }).sortedWith { a, b -> naturalCompare(a.path, b.path) }
        }

    val subtitleFiles: List<TorrentFile>
        get() = files.filter { it.isTextSubtitle }.sortedWith { a, b -> naturalCompare(a.path, b.path) }

    companion object {
        fun parse(json: JSONObject): TorrentInfo {
            val arr = json.optJSONArray("files")
            val files = (0 until (arr?.length() ?: 0)).map { i ->
                val f = arr!!.getJSONObject(i)
                TorrentFile(f.getInt("index"), f.getString("path"), f.getLong("length"), f.getString("kind"), f.getString("streamPath"))
            }
            return TorrentInfo(json.getString("infoHash"), json.getString("name"), json.getLong("length"), files)
        }
    }
}

data class TorrentStatus(
    val peers: Int, val activePeers: Int, val downloadSpeed: Double,
    val fileCompleted: Long, val fileLength: Long,
) {
    val fileProgress: Double? get() = if (fileLength > 0) fileCompleted.toDouble() / fileLength else null

    val text: String
        get() = "👥 $activePeers/$peers  ↓ ${Format.speed(downloadSpeed)}" +
            (fileProgress?.let { "  ${(it * 100).toInt()}%" } ?: "")

    companion object {
        fun parse(json: JSONObject) = TorrentStatus(
            json.optInt("peers"), json.optInt("activePeers"), json.optDouble("downloadSpeed", 0.0),
            json.optLong("fileCompleted"), json.optLong("fileLength"),
        )
    }
}

/** A subtitle track inside a Matroska file. */
data class MkvTrack(
    val number: Long, val codec: String, val language: String, val name: String,
    val isDefault: Boolean, val forced: Boolean, val text: Boolean,
) {
    val displayName: String
        get() {
            val parts = listOf(SubtitleLanguage.displayName(language), name).filter { it.isNotBlank() }
            return if (parts.isEmpty()) "Дорожка $number" else parts.joinToString(" — ")
        }
    val matchText: String get() = "$language $name"
}

data class RecentItem(val uri: String, val name: String, val date: Long)

/** Natural order: "Episode 2" before "Episode 10". */
fun naturalCompare(a: String, b: String): Int {
    val re = Regex("\\d+|\\D+")
    val pa = re.findAll(a.lowercase()).map { it.value }.toList()
    val pb = re.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(pa.size, pb.size)) {
        val x = pa[i]; val y = pb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            val nx = x.trimStart('0'); val ny = y.trimStart('0')
            if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
        } else x.compareTo(y)
        if (c != 0) return c
    }
    return pa.size - pb.size
}

object Format {
    fun bytes(value: Long): String {
        val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var v = value.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i == 0) "$value ${units[0]}" else String.format("%.1f %s", v, units[i])
    }

    fun speed(bytesPerSecond: Double) = bytes(bytesPerSecond.toLong()) + "/с"

    fun time(ms: Long): String {
        val total = maxOf(0, ms / 1000)
        val h = total / 3600; val m = total % 3600 / 60; val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }
}
