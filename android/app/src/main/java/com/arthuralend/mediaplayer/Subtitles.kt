package com.arthuralend.mediaplayer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class SubtitleCue(val start: Double, val end: Double, val text: String)

/**
 * Subtitle lines rendered by our own overlay, so that two tracks can be
 * shown at once. Embedded MKV tracks are merged in as time windows arrive.
 */
class SubtitleTrack(initial: List<SubtitleCue>) {
    private val cues = ArrayList<SubtitleCue>()
    private val seen = HashSet<String>()

    init { merge(initial) }

    fun merge(newCues: List<SubtitleCue>) {
        var added = false
        for (cue in newCues) {
            if (seen.add("${cue.start}|${cue.text}")) { cues.add(cue); added = true }
        }
        if (added) cues.sortBy { it.start }
    }

    fun textAt(time: Double): String? {
        var lo = 0
        var hi = cues.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cues[mid].start <= time) lo = mid + 1 else hi = mid
        }
        val lines = ArrayList<String>()
        var i = lo - 1
        var scanned = 0
        while (i >= 0 && scanned < 30) {
            if (cues[i].end > time) lines.add(cues[i].text)
            i--; scanned++
        }
        return if (lines.isEmpty()) null else lines.reversed().joinToString("\n")
    }
}

object SubtitleParser {
    val SUPPORTED_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")

    fun parse(data: ByteArray, extension: String): SubtitleTrack {
        val text = decode(data)
        val ext = extension.lowercase().trimStart('.')
        var cues = if (ext == "ass" || ext == "ssa" || text.contains("[Events]")) parseAss(text) else parseSrt(text)
        if (cues.isEmpty()) cues = parseAss(text)
        if (cues.isEmpty()) throw IllegalArgumentException("В файле не найдено ни одной строки субтитров")
        return SubtitleTrack(cues)
    }

    /** UTF-8/UTF-16 first, then Windows-1251 (old Russian .srt files). */
    fun decode(data: ByteArray): String {
        if (data.size >= 2 && ((data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) || (data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()))) {
            return String(data, Charsets.UTF_16)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString().trimStart('﻿')
        } catch (_: CharacterCodingException) {
            String(data, Charset.forName("windows-1251"))
        }
    }

    /** SRT and WebVTT. */
    fun parseSrt(text: String): List<SubtitleCue> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<SubtitleCue>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val arrow = line.indexOf("-->")
            if (arrow < 0) { i++; continue }
            val startText = line.substring(0, arrow).trim()
            val endText = line.substring(arrow + 3).trim().split(' ').firstOrNull { it.isNotEmpty() } ?: ""
            i++
            val body = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank() && !lines[i].contains("-->")) { body.add(lines[i]); i++ }
            val start = parseTime(startText)
            val end = parseTime(endText)
            if (start != null && end != null && end > start) {
                val cleaned = clean(body.joinToString("\n"))
                if (cleaned.isNotEmpty()) cues.add(SubtitleCue(start, end, cleaned))
            }
        }
        return cues
    }

    /** ASS / SSA: reads the [Events] Format line to locate the fields. */
    fun parseAss(text: String): List<SubtitleCue> {
        var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        val cues = ArrayList<SubtitleCue>()
        var inEvents = false
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.startsWith("[")) { inEvents = line.equals("[events]", ignoreCase = true); continue }
            if (!inEvents) continue
            if (line.startsWith("format:", ignoreCase = true)) {
                fields = line.substring(7).split(',').map { it.trim().lowercase() }
                continue
            }
            if (!line.startsWith("dialogue:", ignoreCase = true)) continue
            val parts = line.substring(9).split(",", limit = maxOf(fields.size, 2))
            val si = fields.indexOf("start"); val ei = fields.indexOf("end"); val ti = fields.indexOf("text")
            if (si < 0 || ei < 0 || ti < 0 || parts.size <= maxOf(si, ei, ti)) continue
            val body = parts[ti]
            if (body.contains("\\p1") || body.contains("\\p2")) continue
            val start = parseTime(parts[si].trim()) ?: continue
            val end = parseTime(parts[ei].trim()) ?: continue
            if (end <= start) continue
            val cleaned = clean(body.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " "))
            if (cleaned.isNotEmpty()) cues.add(SubtitleCue(start, end, cleaned))
        }
        return cues
    }

    /** "HH:MM:SS,mmm", "HH:MM:SS.mmm", "MM:SS.mmm" and "H:MM:SS.cc". */
    fun parseTime(text: String): Double? {
        val parts = text.replace(',', '.').split(':')
        if (parts.size != 2 && parts.size != 3) return null
        var total = 0.0
        for (p in parts) total = total * 60 + (p.trim().toDoubleOrNull() ?: return null)
        return total
    }

    private val tags = Regex("<[^>]*>|\\{[^}]*\\}")

    fun clean(text: String): String {
        val s = tags.replace(text, "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        return s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}

object SubtitleLanguage {
    data class Language(val code: String, val name: String, val keys: Set<String>)

    val ALL = listOf(
        Language("ru", "Русский", setOf("ru", "rus", "russian", "рус", "русский", "russkiy")),
        Language("en", "English", setOf("en", "eng", "english", "англ", "английский")),
        Language("uk", "Українська", setOf("uk", "ukr", "ukrainian", "укр", "украинский", "українська")),
        Language("de", "Deutsch", setOf("de", "ger", "deu", "german", "deutsch")),
        Language("fr", "Français", setOf("fr", "fre", "fra", "french", "français", "francais")),
        Language("es", "Español", setOf("es", "spa", "spanish", "español", "espanol")),
        Language("it", "Italiano", setOf("ita", "italian", "italiano")),
        Language("pt", "Português", setOf("pt", "por", "portuguese", "português", "portugues")),
        Language("pl", "Polski", setOf("pl", "pol", "polish", "polski")),
        Language("tr", "Türkçe", setOf("tr", "tur", "turkish", "türkçe")),
        Language("ja", "日本語", setOf("ja", "jpn", "japanese", "日本語")),
        Language("zh", "中文", setOf("zh", "chi", "zho", "chinese", "中文")),
        Language("ko", "한국어", setOf("ko", "kor", "korean", "한국어")),
    )

    fun matches(name: String, code: String): Boolean {
        val language = ALL.firstOrNull { it.code == code } ?: return false
        return name.lowercase().split(Regex("[^\\p{L}]+")).any { it.isNotEmpty() && it in language.keys }
    }

    fun displayName(code: String): String {
        val base = code.lowercase().split('-')[0]
        return ALL.firstOrNull { it.code == base || base in it.keys }?.name ?: if (code == "und") "" else code
    }

    fun isForced(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("forced") || lower.contains("форс") || lower.contains("signs")
    }
}
