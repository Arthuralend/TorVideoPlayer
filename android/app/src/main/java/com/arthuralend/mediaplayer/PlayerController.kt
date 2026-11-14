package com.arthuralend.mediaplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.MainScope
import android.util.Log
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.util.VLCVideoLayout
import org.videolan.libvlc.MediaPlayer as VlcPlayer

data class TrackOption(val id: Int, val name: String)

data class SubtitleFileOption(val id: String, val name: String, val url: String)

enum class SourceKind { OFF, EMBEDDED, STREAM, FILE }

/**
 * Where a subtitle slot takes its text from:
 * STREAM — a text track inside the MKV, read by the engine and drawn by our overlay;
 * FILE — an external .srt/.ass/.vtt file, also drawn by the overlay;
 * EMBEDDED — drawn by VLC itself (one at a time), for formats the engine can't read.
 */
data class SubtitleSource(val kind: SourceKind, val number: Long = 0, val key: String = "") {
    companion object {
        val OFF = SubtitleSource(SourceKind.OFF)
        fun embedded(id: Int) = SubtitleSource(SourceKind.EMBEDDED, id.toLong())
        fun stream(number: Long) = SubtitleSource(SourceKind.STREAM, number)
        fun file(id: String) = SubtitleSource(SourceKind.FILE, 0, id)
    }
}

data class SubtitleOption(val source: SubtitleSource, val name: String)

enum class SubtitleSlot { PRIMARY, SECONDARY }

class PlayerController(context: Context) {
    private val libVLC = LibVLC(context, arrayListOf("--no-osd", "--audio-time-stretch"))
    val player = VlcPlayer(libVLC)
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())

    /** Called after every state change; the activity redraws the player UI. */
    var onChange: (() -> Unit)? = null
    /** Called once when playback reaches the end of the media. */
    var onEnded: (() -> Unit)? = null
    /** Called with a short message to show on screen. */
    var onOsd: ((String) -> Unit)? = null

    var title = ""; private set
    var isPlaying = false; private set
    var isBuffering = false; private set
    var hasMedia = false; private set
    var timeMs = 0L; private set
    var lengthMs = 0L; private set
    var rate = 1f; private set
    var errorText: String? = null; private set

    var audioTracks: List<TrackOption> = emptyList(); private set
    var currentAudioTrack = -1; private set
    var embeddedSubtitles: List<TrackOption> = emptyList(); private set
    var subtitleFiles: List<SubtitleFileOption> = emptyList(); private set
    var streamTracks: List<MkvTrack> = emptyList(); private set
    var primarySource = SubtitleSource.OFF; private set
    var secondarySource = SubtitleSource.OFF; private set
    var primaryDelay = 0.0; private set
    var secondaryDelay = 0.0; private set
    var primaryText: String? = null; private set
    var secondaryText: String? = null; private set

    private var currentUri: Uri? = null
    private var tickCount = 0
    private var lastTime = -1L
    private var stalledTicks = 0
    private var endedReported = false
    private var userChoseSubtitles = false

    private var primaryTrack: SubtitleTrack? = null
    private var secondaryTrack: SubtitleTrack? = null
    private val parsedTracks = HashMap<String, SubtitleTrack>()

    // Embedded MKV subtitles, fetched from the engine in time windows.
    private var mediaSource: MediaSource? = null
    private var generation = 0
    private val liveTracks = HashMap<Long, SubtitleTrack>()
    private val liveCoverage = HashMap<Long, Pair<Double, Double>>()
    private val liveLoading = HashSet<Long>()

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 100)
        }
    }

    fun attach(layout: VLCVideoLayout) {
        // VLC draws its own (embedded) subtitles on the video surface.
        player.attachViews(layout, null, true, false)
    }

    fun detach() = player.detachViews()

    val vlcRendersSubtitle: Boolean
        get() = primarySource.kind == SourceKind.EMBEDDED || secondarySource.kind == SourceKind.EMBEDDED

    val subtitleSummary: String
        get() {
            val count = listOf(primarySource, secondarySource).count { it.kind != SourceKind.OFF }
            return when (count) { 0 -> "Выкл"; 2 -> "1 + 2"; else -> "1" }
        }

    /** VLC-rendered tracks are offered only when the engine couldn't read all embedded tracks itself. */
    val vlcSubtitleOptions: List<TrackOption>
        get() = if (streamTracks.isEmpty() || embeddedSubtitles.size > streamTracks.size) embeddedSubtitles else emptyList()

    val subtitleOptions: List<SubtitleOption>
        get() {
            val list = arrayListOf(SubtitleOption(SubtitleSource.OFF, "Выключены"))
            streamTracks.forEach { list.add(SubtitleOption(SubtitleSource.stream(it.number), it.displayName)) }
            val suffix = if (streamTracks.isEmpty()) "" else " (через VLC)"
            vlcSubtitleOptions.forEach { list.add(SubtitleOption(SubtitleSource.embedded(it.id), it.name + suffix)) }
            subtitleFiles.forEach { list.add(SubtitleOption(SubtitleSource.file(it.id), it.name)) }
            return list
        }

    // MARK: Playback

    fun open(uri: Uri, title: String, subtitleCandidates: List<SubtitleFileOption>, source: MediaSource?) {
        stop()
        this.title = title
        generation++
        mediaSource = source
        streamTracks = emptyList()
        liveTracks.clear(); liveCoverage.clear(); liveLoading.clear()
        subtitleFiles = subtitleCandidates
        parsedTracks.clear()
        primaryTrack = null; secondaryTrack = null
        primarySource = SubtitleSource.OFF; secondarySource = SubtitleSource.OFF
        primaryText = null; secondaryText = null
        primaryDelay = 0.0; secondaryDelay = 0.0
        userChoseSubtitles = false
        audioTracks = emptyList(); embeddedSubtitles = emptyList(); currentAudioTrack = -1
        timeMs = 0; lengthMs = 0; lastTime = -1; stalledTicks = 0; endedReported = false
        errorText = null
        isBuffering = true

        currentUri = uri
        startMedia(uri)
        hasMedia = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        autoSelectSubtitles()
        if (source != null) loadStreamTracks(source)
        onChange?.invoke()
    }

    private fun startMedia(uri: Uri) {
        val media = Media(libVLC, uri)
        media.addOption(":network-caching=${maxOf(500, Prefs.networkCaching)}")
        media.addOption(":file-caching=1000")
        media.addOption(":http-reconnect")
        player.setMedia(media)
        media.release()
        player.play()
        player.setRate(rate)
    }

    private fun loadStreamTracks(source: MediaSource) {
        val gen = generation
        scope.launch {
            val tracks = try {
                TorrentEngine.mkvTracks(source)
            } catch (e: Exception) {
                Log.w(TAG, "mkv tracks failed", e)
                onOsd?.invoke("Не удалось прочитать встроенные субтитры: ${e.message}")
                return@launch
            }
            if (gen != generation) return@launch
            streamTracks = tracks.filter { it.text }
            Log.i(TAG, "mkv tracks: " + tracks.joinToString { "${it.number}:${it.codec}:${it.language}:text=${it.text}" })
            autoSelectSubtitles()
            onChange?.invoke()
        }
    }

    fun stop() {
        handler.removeCallbacks(ticker)
        if (hasMedia) player.stop()
        isPlaying = false
        isBuffering = false
        hasMedia = false
    }

    fun release() {
        stop()
        player.release()
        libVLC.release()
    }

    fun pause() {
        if (player.isPlaying) player.pause()
    }

    fun togglePlay() {
        if (!hasMedia) return
        if (player.isPlaying) {
            player.pause()
        } else {
            val state = player.playerState
            if ((state == STATE_ENDED || state == STATE_STOPPED) && currentUri != null) {
                startMedia(currentUri!!)
                endedReported = false
            } else {
                player.play()
            }
        }
        onChange?.invoke()
    }

    fun seekToMs(ms: Long) {
        if (!hasMedia) return
        val target = if (lengthMs > 0) ms.coerceIn(0, maxOf(0, lengthMs - 500)) else maxOf(0, ms)
        player.setTime(target)
        timeMs = target
        stalledTicks = 0
        refreshSubtitleText()
        updateLiveSubtitles()
        onChange?.invoke()
    }

    fun seekFraction(fraction: Double) {
        if (lengthMs > 0) seekToMs((fraction * lengthMs).toLong()) else player.setPosition(fraction.toFloat())
    }

    fun jump(seconds: Int) {
        seekToMs(timeMs + seconds * 1000L)
        onOsd?.invoke(if (seconds > 0) "+$seconds с" else "−${-seconds} с")
    }

    fun setRate(value: Float) {
        rate = value
        player.setRate(value)
        onOsd?.invoke("Скорость ${formatRate(value)}")
        onChange?.invoke()
    }

    fun selectAudioTrack(id: Int) {
        player.setAudioTrack(id)
        currentAudioTrack = id
        onChange?.invoke()
    }

    // MARK: Subtitles

    fun sourceFor(slot: SubtitleSlot) = if (slot == SubtitleSlot.PRIMARY) primarySource else secondarySource

    fun select(source: SubtitleSource, slot: SubtitleSlot) {
        userChoseSubtitles = true
        apply(source, slot)
        onChange?.invoke()
    }

    fun swapSlots() {
        userChoseSubtitles = true
        primarySource = secondarySource.also { secondarySource = primarySource }
        primaryTrack = secondaryTrack.also { secondaryTrack = primaryTrack }
        primaryDelay = secondaryDelay.also { secondaryDelay = primaryDelay }
        syncVlcSubtitle()
        refreshSubtitleText()
        onChange?.invoke()
    }

    fun adjustDelay(slot: SubtitleSlot, delta: Double) {
        if (slot == SubtitleSlot.PRIMARY) primaryDelay = Math.round((primaryDelay + delta) * 10) / 10.0
        else secondaryDelay = Math.round((secondaryDelay + delta) * 10) / 10.0
        applyEmbeddedDelay()
        refreshSubtitleText()
        onChange?.invoke()
    }

    fun addSubtitleFile(name: String, url: String) {
        val id = "local:$url"
        if (subtitleFiles.none { it.id == id }) subtitleFiles = subtitleFiles + SubtitleFileOption(id, name, url)
        val slot = if (primarySource.kind == SourceKind.OFF) SubtitleSlot.PRIMARY else SubtitleSlot.SECONDARY
        select(SubtitleSource.file(id), slot)
        onOsd?.invoke("Субтитры ${if (slot == SubtitleSlot.PRIMARY) 1 else 2}: $name")
    }

    private fun apply(source: SubtitleSource, slot: SubtitleSlot) {
        Log.i(TAG, "subtitles $slot -> $source")
        // VLC can draw only one subtitle track: the other slot gives it up.
        if (source.kind == SourceKind.EMBEDDED) {
            val other = if (slot == SubtitleSlot.PRIMARY) SubtitleSlot.SECONDARY else SubtitleSlot.PRIMARY
            if (sourceFor(other).kind == SourceKind.EMBEDDED) setSlot(SubtitleSource.OFF, null, other)
        }
        when (source.kind) {
            SourceKind.OFF, SourceKind.EMBEDDED -> setSlot(source, null, slot)
            SourceKind.FILE -> {
                val parsed = parsedTracks[source.key]
                setSlot(source, parsed, slot)
                if (parsed == null) loadFileTrack(source.key, slot)
            }
            SourceKind.STREAM -> {
                val live = liveTracks.getOrPut(source.number) { SubtitleTrack(emptyList()) }
                setSlot(source, live, slot)
                updateLiveSubtitles()
            }
        }
        syncVlcSubtitle()
        refreshSubtitleText()
    }

    private fun setSlot(source: SubtitleSource, track: SubtitleTrack?, slot: SubtitleSlot) {
        if (slot == SubtitleSlot.PRIMARY) { primarySource = source; primaryTrack = track }
        else { secondarySource = source; secondaryTrack = track }
    }

    private fun loadFileTrack(id: String, slot: SubtitleSlot) {
        val option = subtitleFiles.firstOrNull { it.id == id } ?: return
        scope.launch {
            try {
                val data = if (option.url.startsWith("http")) TorrentEngine.getBytes(option.url)
                else ContentReader.read(option.url)
                val track = SubtitleParser.parse(data, option.name.substringAfterLast('.', ""))
                Log.i(TAG, "subtitle file loaded: ${option.name}")
                parsedTracks[id] = track
                if (sourceFor(slot) == SubtitleSource.file(id)) {
                    setSlot(SubtitleSource.file(id), track, slot)
                    refreshSubtitleText()
                }
            } catch (e: Exception) {
                Log.w(TAG, "subtitle file failed: ${option.name}", e)
                onOsd?.invoke("Не удалось загрузить «${option.name}»: ${e.message}")
                if (sourceFor(slot) == SubtitleSource.file(id)) setSlot(SubtitleSource.OFF, null, slot)
            }
            onChange?.invoke()
        }
    }

    private fun syncVlcSubtitle() {
        val index = when {
            primarySource.kind == SourceKind.EMBEDDED -> primarySource.number.toInt()
            secondarySource.kind == SourceKind.EMBEDDED -> secondarySource.number.toInt()
            else -> -1
        }
        if (player.spuTrack != index) player.setSpuTrack(index)
        applyEmbeddedDelay()
    }

    private fun applyEmbeddedDelay() {
        var delay = 0.0
        if (primarySource.kind == SourceKind.EMBEDDED) delay = primaryDelay
        if (secondarySource.kind == SourceKind.EMBEDDED) delay = secondaryDelay
        val micros = (delay * 1_000_000).toLong()
        if (player.spuDelay != micros) player.setSpuDelay(micros)
    }

    private fun refreshSubtitleText() {
        val t = timeMs / 1000.0
        primaryText = primaryTrack?.textAt(t - primaryDelay)
        secondaryText = secondaryTrack?.textAt(t - secondaryDelay)
    }

    /** Picks subtitles by the preferred languages until the user chooses by hand. */
    private fun autoSelectSubtitles() {
        if (userChoseSubtitles || !Prefs.autoSubtitles) return
        var primary = bestMatch(Prefs.subLang1, allowVlc = true)
        if (primary == null && player.spuTrack >= 0) {
            // Keep a track enabled when the file asks for one by default.
            val track = streamTracks.firstOrNull { it.isDefault && !it.forced } ?: streamTracks.firstOrNull()
            primary = when {
                track != null -> SubtitleSource.stream(track.number)
                vlcSubtitleOptions.isNotEmpty() -> SubtitleSource.embedded(player.spuTrack)
                else -> null
            }
        }
        var secondary = bestMatch(Prefs.subLang2, allowVlc = primary?.kind != SourceKind.EMBEDDED)
        if (secondary == primary) secondary = null
        if (primary != null && primary != primarySource) apply(primary, SubtitleSlot.PRIMARY)
        if (secondary != null && secondary != secondarySource) apply(secondary, SubtitleSlot.SECONDARY)
    }

    private fun bestMatch(language: String, allowVlc: Boolean): SubtitleSource? {
        if (language == "none") return null
        fun <T> pick(items: List<T>, name: (T) -> String): T? {
            val matching = items.filter { SubtitleLanguage.matches(name(it), language) }
            return matching.firstOrNull { !SubtitleLanguage.isForced(name(it)) } ?: matching.firstOrNull()
        }
        pick(streamTracks.filter { !it.forced }) { it.matchText }?.let { return SubtitleSource.stream(it.number) }
        if (allowVlc) pick(vlcSubtitleOptions) { it.name }?.let { return SubtitleSource.embedded(it.id) }
        pick(subtitleFiles) { it.name }?.let { return SubtitleSource.file(it.id) }
        return null
    }

    /**
     * Keeps a window of embedded cues loaded around the playback position:
     * a fresh window after a seek, the next one before the current runs out.
     */
    private fun updateLiveSubtitles() {
        val source = mediaSource ?: return
        val active = listOf(primarySource, secondarySource).filter { it.kind == SourceKind.STREAM }.map { it.number }.distinct()
        val now = timeMs / 1000.0
        for (number in active) {
            if (number in liveLoading) continue
            val covered = liveCoverage[number]
            val from: Double; val to: Double; val extending: Boolean
            if (covered != null && now >= covered.first && now <= covered.second) {
                if (now <= covered.second - 20) continue
                from = covered.second; to = covered.second + 90; extending = true
            } else {
                from = maxOf(0.0, now - 3); to = now + 45; extending = false
            }
            if (lengthMs > 0 && from > lengthMs / 1000.0) continue
            liveLoading.add(number)
            val gen = generation
            scope.launch {
                val cues = try {
                    TorrentEngine.mkvCues(source, number, from, to)
                } catch (e: Exception) {
                    Log.w(TAG, "mkv cues failed for track $number", e)
                    null
                }
                if (gen != generation) return@launch
                liveLoading.remove(number)
                if (cues == null) return@launch
                Log.i(TAG, "mkv cues track $number [$from, $to]: ${cues.size}")
                liveTracks[number]?.merge(cues)
                val cov = liveCoverage[number]
                liveCoverage[number] = if (extending && cov != null && cov.second >= from) Pair(cov.first, to) else Pair(from, to)
                refreshSubtitleText()
                onChange?.invoke()
            }
        }
    }

    // MARK: Ticking

    private fun tick() {
        val state = player.playerState
        val playing = player.isPlaying
        isPlaying = playing
        val t = maxOf(0L, player.time)
        timeMs = t
        val length = player.length
        if (length > 0) lengthMs = length

        // VLC's own "buffering" state is noisy; treat "time doesn't move while
        // we are supposed to be playing" for 2.5 s as buffering instead.
        stalledTicks = if (playing && t == lastTime) stalledTicks + 1 else 0
        lastTime = t
        isBuffering = state == STATE_OPENING || (state == STATE_BUFFERING && t == 0L) || stalledTicks >= 25

        if (state == STATE_ERROR && errorText == null) errorText = "VLC не смог воспроизвести этот файл"
        if (state == STATE_ENDED && !endedReported) {
            endedReported = true
            onEnded?.invoke()
        }

        tickCount++
        if (tickCount % 10 == 1) refreshTracks()
        if (tickCount % 3 == 0) updateLiveSubtitles()
        refreshSubtitleText()
        onChange?.invoke()
    }

    private fun refreshTracks() {
        val audio = (player.audioTracks ?: emptyArray()).filter { it.id >= 0 }.map { TrackOption(it.id, it.name ?: "Дорожка ${it.id}") }
        if (audio != audioTracks) audioTracks = audio
        currentAudioTrack = player.audioTrack
        val subs = (player.spuTracks ?: emptyArray()).filter { it.id >= 0 }.map { TrackOption(it.id, it.name ?: "Дорожка ${it.id}") }
        if (subs != embeddedSubtitles) {
            Log.i(TAG, "vlc spu tracks: " + subs.joinToString { "${it.id}:${it.name}" })
            embeddedSubtitles = subs
            autoSelectSubtitles()
        }
        // VLC may auto-enable a default track after we've chosen otherwise.
        if (hasMedia && (player.playerState == STATE_PLAYING || player.playerState == STATE_PAUSED)) syncVlcSubtitle()
    }

    companion object {
        const val TAG = "MediaPlayer"

        // libVLC player states (org.videolan.libvlc.interfaces.IMedia.State).
        const val STATE_OPENING = 1
        const val STATE_BUFFERING = 2
        const val STATE_PLAYING = 3
        const val STATE_PAUSED = 4
        const val STATE_STOPPED = 5
        const val STATE_ENDED = 6
        const val STATE_ERROR = 7

        fun formatRate(v: Float): String = (if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()) + "×"
    }
}
