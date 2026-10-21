import AppKit
import UniformTypeIdentifiers
import VLCKit

struct TrackOption: Identifiable, Hashable {
    let id: Int32
    let name: String
}

struct SubtitleFileOption: Identifiable, Hashable {
    let id: String
    let name: String
    let url: URL
}

/// Where a subtitle slot takes its text from.
/// - `stream`: a text track inside the MKV, read by the engine and drawn by
///   our overlay, so any two of them can be shown at once;
/// - `file`: an external .srt/.ass/.vtt file, also drawn by the overlay;
/// - `embedded`: a track drawn by VLC itself (only one at a time) — used for
///   formats the engine can't read (MP4 text, PGS/VobSub pictures).
enum SubtitleSource: Hashable {
    case off
    case embedded(Int32)
    case stream(UInt64)
    case file(String)
}

enum SubtitleSlot {
    case primary, secondary
}

/// Video surface that lets mouse events fall through to the SwiftUI layer.
final class PassthroughView: NSView {
    override func hitTest(_ point: NSPoint) -> NSView? { nil }
}

final class PassthroughVideoView: VLCVideoView {
    override func hitTest(_ point: NSPoint) -> NSView? { nil }
}

@MainActor
final class PlayerController: ObservableObject {
    let mediaPlayer = VLCMediaPlayer()
    let videoView = PassthroughVideoView(frame: NSRect(x: 0, y: 0, width: 640, height: 360))

    @Published private(set) var title = ""
    @Published private(set) var isPlaying = false
    @Published private(set) var isBuffering = false
    @Published private(set) var hasMedia = false
    @Published private(set) var timeMs = 0
    @Published private(set) var lengthMs = 0
    @Published private(set) var rate: Float = 1
    @Published private(set) var isMuted = false
    @Published var volume: Double = 100 {
        didSet { vlcAudio?.volume = Int32(volume) }
    }

    @Published private(set) var audioTracks: [TrackOption] = []
    @Published private(set) var currentAudioTrack: Int32 = -1
    @Published private(set) var embeddedSubtitles: [TrackOption] = []
    @Published private(set) var subtitleFiles: [SubtitleFileOption] = []
    @Published private(set) var streamTracks: [MKVTrack] = []
    @Published private(set) var primarySource: SubtitleSource = .off
    @Published private(set) var secondarySource: SubtitleSource = .off
    @Published var primaryDelay: Double = 0 { didSet { applyEmbeddedDelay(); refreshSubtitleText() } }
    @Published var secondaryDelay: Double = 0 { didSet { applyEmbeddedDelay(); refreshSubtitleText() } }
    @Published private(set) var primaryText: String?
    @Published private(set) var secondaryText: String?
    @Published private(set) var osdMessage: String?
    @Published private(set) var errorText: String?

    /// Called once when playback reaches the end of the media.
    var onEnded: (() -> Void)?

    private var primaryTrack: SubtitleTrack?
    private var secondaryTrack: SubtitleTrack?
    private var parsedTracks: [String: SubtitleTrack] = [:]
    private var tickTask: Task<Void, Never>?
    private var tickCount = 0
    private var lastTimeMs = -1
    private var stalledTicks = 0
    private var endedReported = false
    private var userChoseSubtitles = false
    private var osdTask: Task<Void, Never>?

    // Embedded MKV subtitles, fetched from the engine in time windows.
    private var mediaSource: MediaSource?
    private var mediaGeneration = 0
    private var liveTracks: [UInt64: SubtitleTrack] = [:]
    private var liveCoverage: [UInt64: ClosedRange<Double>] = [:]
    private var liveLoading: Set<UInt64> = []

    init() {
        videoView.backColor = .black
        videoView.fillScreen = false
        videoView.autoresizingMask = [.width, .height]
        mediaPlayer.drawable = videoView
    }

    /// Typed as optional whatever nullability VLCKit's headers declare.
    private var vlcAudio: VLCAudio? { mediaPlayer.audio }

    var vlcRendersSubtitle: Bool {
        if case .embedded = primarySource { return true }
        if case .embedded = secondarySource { return true }
        return false
    }

    // MARK: Playback

    func open(url: URL, title: String, subtitleCandidates: [SubtitleFileOption], source: MediaSource? = nil) {
        stop()
        self.title = title
        subtitleFiles = subtitleCandidates
        mediaGeneration += 1
        mediaSource = source
        streamTracks = []
        liveTracks = [:]
        liveCoverage = [:]
        liveLoading = []
        parsedTracks = [:]
        primarySource = .off
        secondarySource = .off
        primaryTrack = nil
        secondaryTrack = nil
        primaryText = nil
        secondaryText = nil
        primaryDelay = 0
        secondaryDelay = 0
        userChoseSubtitles = false
        audioTracks = []
        embeddedSubtitles = []
        currentAudioTrack = -1
        timeMs = 0
        lengthMs = 0
        lastTimeMs = -1
        stalledTicks = 0
        endedReported = false
        errorText = nil
        isBuffering = true

        let caching = max(500, UserDefaults.standard.integer(forKey: Prefs.networkCaching))
        let media = VLCMedia(url: url)
        media.addOptions([
            "network-caching": caching,
            "file-caching": 1000,
            "http-reconnect": true,
        ])
        mediaPlayer.media = media
        mediaPlayer.rate = rate
        mediaPlayer.play()
        vlcAudio?.volume = Int32(volume)
        hasMedia = true
        startTicking()
        autoSelectSubtitles()
        if let source { loadStreamTracks(source) }
    }

    private func loadStreamTracks(_ source: MediaSource) {
        let generation = mediaGeneration
        Task {
            guard let tracks = try? await TorrentEngine.shared.mkvTracks(source),
                  generation == mediaGeneration else { return }
            streamTracks = tracks.filter(\.text)
            autoSelectSubtitles()
        }
    }

    func stop() {
        tickTask?.cancel()
        tickTask = nil
        if mediaPlayer.media != nil {
            mediaPlayer.stop()
        }
        isPlaying = false
        isBuffering = false
        hasMedia = false
    }

    func togglePlay() {
        guard hasMedia else { return }
        if mediaPlayer.isPlaying {
            mediaPlayer.pause()
        } else {
            if mediaPlayer.state == .ended || mediaPlayer.state == .stopped, let media = mediaPlayer.media {
                mediaPlayer.media = media
                endedReported = false
            }
            mediaPlayer.play()
        }
    }

    func seek(toMs ms: Int) {
        guard hasMedia else { return }
        let target = lengthMs > 0 ? min(max(0, ms), lengthMs - 500) : max(0, ms)
        mediaPlayer.time = VLCTime(int: Int32(clamping: target))
        timeMs = target
        stalledTicks = 0
        refreshSubtitleText()
        updateLiveSubtitles()
    }

    func seek(fraction: Double) {
        if lengthMs > 0 {
            seek(toMs: Int(fraction * Double(lengthMs)))
        } else {
            mediaPlayer.position = Float(fraction)
        }
    }

    func jump(seconds: Int) {
        seek(toMs: timeMs + seconds * 1000)
        showOSD(seconds > 0 ? "+\(seconds) с" : "−\(-seconds) с")
    }

    func changeVolume(by delta: Double) {
        volume = min(200, max(0, volume + delta))
        if isMuted { toggleMute() }
        showOSD("Громкость \(Int(volume))%")
    }

    func toggleMute() {
        isMuted.toggle()
        vlcAudio?.isMuted = isMuted
        showOSD(isMuted ? "Звук выключен" : "Звук включён")
    }

    func setRate(_ value: Float) {
        rate = value
        mediaPlayer.rate = value
        showOSD(String(format: "Скорость %.2g×", value))
    }

    func selectAudioTrack(_ id: Int32) {
        mediaPlayer.currentAudioTrackIndex = id
        currentAudioTrack = id
    }

    // MARK: Subtitles

    func source(for slot: SubtitleSlot) -> SubtitleSource {
        slot == .primary ? primarySource : secondarySource
    }

    func select(_ source: SubtitleSource, for slot: SubtitleSlot) {
        userChoseSubtitles = true
        apply(source, to: slot)
    }

    func swapSlots() {
        userChoseSubtitles = true
        let (p, s) = (primarySource, secondarySource)
        let (pt, st) = (primaryTrack, secondaryTrack)
        let (pd, sd) = (primaryDelay, secondaryDelay)
        primarySource = s
        secondarySource = p
        primaryTrack = st
        secondaryTrack = pt
        primaryDelay = sd
        secondaryDelay = pd
        syncVLCSubtitle()
        refreshSubtitleText()
    }

    /// VLC-rendered tracks are only offered when the engine couldn't read
    /// all embedded tracks itself (non-MKV files, picture subtitles).
    var vlcSubtitleOptions: [TrackOption] {
        streamTracks.isEmpty || embeddedSubtitles.count > streamTracks.count ? embeddedSubtitles : []
    }

    /// Options in menu order: off, embedded tracks, files.
    var allSubtitleSources: [SubtitleSource] {
        [.off] + streamTracks.map { .stream($0.number) } + vlcSubtitleOptions.map { .embedded($0.id) }
            + subtitleFiles.map { .file($0.id) }
    }

    func name(of source: SubtitleSource) -> String {
        switch source {
        case .off: return "Выключены"
        case .embedded(let id): return embeddedSubtitles.first { $0.id == id }?.name ?? "Дорожка \(id)"
        case .stream(let number): return streamTracks.first { $0.number == number }?.displayName ?? "Дорожка \(number)"
        case .file(let id): return subtitleFiles.first { $0.id == id }?.name ?? "Файл"
        }
    }

    func cycle(_ slot: SubtitleSlot) {
        let all = allSubtitleSources
        let current = source(for: slot)
        let index = all.firstIndex(of: current) ?? 0
        let next = all[(index + 1) % all.count]
        select(next, for: slot)
        showOSD("Субтитры \(slot == .primary ? 1 : 2): \(name(of: next))")
    }

    func adjustDelay(_ slot: SubtitleSlot, by delta: Double) {
        if slot == .primary {
            primaryDelay = ((primaryDelay + delta) * 10).rounded() / 10
            showOSD(String(format: "Задержка субтитров 1: %+.1f с", primaryDelay))
        } else {
            secondaryDelay = ((secondaryDelay + delta) * 10).rounded() / 10
            showOSD(String(format: "Задержка субтитров 2: %+.1f с", secondaryDelay))
        }
    }

    func addSubtitleFile(url: URL) {
        let id = "local:" + url.path
        if !subtitleFiles.contains(where: { $0.id == id }) {
            subtitleFiles.append(SubtitleFileOption(id: id, name: url.lastPathComponent, url: url))
        }
        let slot: SubtitleSlot = primarySource == .off ? .primary : (secondarySource == .off ? .secondary : .secondary)
        select(.file(id), for: slot)
        showOSD("Субтитры \(slot == .primary ? 1 : 2): \(url.lastPathComponent)")
    }

    func showSubtitleOpenPanel() {
        let panel = NSOpenPanel()
        panel.title = "Выберите файлы субтитров"
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.allowedContentTypes = SubtitleParser.supportedExtensions.compactMap { UTType(filenameExtension: $0) }
        if panel.runModal() == .OK {
            for url in panel.urls { addSubtitleFile(url: url) }
        }
    }

    private func apply(_ source: SubtitleSource, to slot: SubtitleSlot) {
        // VLC can draw only one embedded track: the other slot gives it up.
        if case .embedded = source {
            let other: SubtitleSlot = slot == .primary ? .secondary : .primary
            if case .embedded = self.source(for: other) { set(.off, track: nil, for: other) }
        }
        switch source {
        case .off, .embedded:
            set(source, track: nil, for: slot)
        case .file(let id):
            set(source, track: parsedTracks[id], for: slot)
            if parsedTracks[id] == nil {
                Task { await loadTrack(id: id, for: slot) }
            }
        case .stream(let number):
            let track = liveTracks[number] ?? SubtitleTrack(cues: [])
            liveTracks[number] = track
            set(source, track: track, for: slot)
            updateLiveSubtitles()
        }
        syncVLCSubtitle()
        refreshSubtitleText()
    }

    private func set(_ source: SubtitleSource, track: SubtitleTrack?, for slot: SubtitleSlot) {
        if slot == .primary {
            primarySource = source
            primaryTrack = track
        } else {
            secondarySource = source
            secondaryTrack = track
        }
    }

    private func loadTrack(id: String, for slot: SubtitleSlot) async {
        guard let option = subtitleFiles.first(where: { $0.id == id }) else { return }
        do {
            let data: Data
            if option.url.isFileURL {
                data = try Data(contentsOf: option.url)
            } else {
                data = try await URLSession.shared.data(from: option.url).0
            }
            let track = try SubtitleParser.parse(data: data, fileExtension: option.url.pathExtension)
            parsedTracks[id] = track
            if source(for: slot) == .file(id) {
                set(.file(id), track: track, for: slot)
                refreshSubtitleText()
            }
        } catch {
            showOSD("Не удалось загрузить «\(option.name)»: \(error.localizedDescription)")
            if source(for: slot) == .file(id) { set(.off, track: nil, for: slot) }
        }
    }

    private func syncVLCSubtitle() {
        var index: Int32 = -1
        if case .embedded(let id) = primarySource {
            index = id
        } else if case .embedded(let id) = secondarySource {
            index = id
        }
        if mediaPlayer.currentVideoSubTitleIndex != index {
            mediaPlayer.currentVideoSubTitleIndex = index
        }
        applyEmbeddedDelay()
    }

    private func applyEmbeddedDelay() {
        var delay = 0.0
        if case .embedded = primarySource { delay = primaryDelay }
        if case .embedded = secondarySource { delay = secondaryDelay }
        let micros = Int(delay * 1_000_000)
        if mediaPlayer.currentVideoSubTitleDelay != micros {
            mediaPlayer.currentVideoSubTitleDelay = micros
        }
    }

    private func refreshSubtitleText() {
        let t = Double(timeMs) / 1000
        let p = primaryTrack?.text(at: t - primaryDelay)
        let s = secondaryTrack?.text(at: t - secondaryDelay)
        if p != primaryText { primaryText = p }
        if s != secondaryText { secondaryText = s }
    }

    /// Picks subtitles by the preferred languages from Settings until the
    /// user makes a choice by hand.
    private func autoSelectSubtitles() {
        guard !userChoseSubtitles, UserDefaults.standard.bool(forKey: Prefs.autoSubtitles) else { return }
        let lang1 = UserDefaults.standard.string(forKey: Prefs.subLang1) ?? "none"
        let lang2 = UserDefaults.standard.string(forKey: Prefs.subLang2) ?? "none"

        var primary = bestMatch(language: lang1, allowVLC: true)
        if primary == nil, mediaPlayer.currentVideoSubTitleIndex >= 0 {
            // Keep a track enabled when the file asks for one by default.
            if let track = streamTracks.first(where: { $0.isDefault && !$0.forced }) ?? streamTracks.first {
                primary = .stream(track.number)
            } else if !vlcSubtitleOptions.isEmpty {
                primary = .embedded(mediaPlayer.currentVideoSubTitleIndex)
            }
        }
        var primaryIsVLC = false
        if case .embedded = primary { primaryIsVLC = true }
        var secondary = bestMatch(language: lang2, allowVLC: !primaryIsVLC)
        if secondary == primary { secondary = nil }

        if let primary, primary != primarySource { apply(primary, to: .primary) }
        if let secondary, secondary != secondarySource { apply(secondary, to: .secondary) }
    }

    private func bestMatch(language: String, allowVLC: Bool) -> SubtitleSource? {
        guard language != "none" else { return nil }
        func pick<T>(_ items: [T], name: (T) -> String) -> T? {
            let matching = items.filter { SubtitleLanguage.matches(name($0), code: language) }
            return matching.first { !SubtitleLanguage.isForced(name($0)) } ?? matching.first
        }
        if let track = pick(streamTracks.filter { !$0.forced }, name: { $0.matchText }) {
            return .stream(track.number)
        }
        if allowVLC, let track = pick(vlcSubtitleOptions, name: { $0.name }) {
            return .embedded(track.id)
        }
        if let file = pick(subtitleFiles, name: { $0.name }) {
            return .file(file.id)
        }
        return nil
    }

    /// Keeps a window of embedded cues loaded around the playback position:
    /// a fresh window after a seek, and the next one shortly before the
    /// current window runs out.
    private func updateLiveSubtitles() {
        guard let source = mediaSource else { return }
        var active: [UInt64] = []
        for src in [primarySource, secondarySource] {
            if case .stream(let number) = src, !active.contains(number) { active.append(number) }
        }
        let now = Double(timeMs) / 1000
        for number in active where !liveLoading.contains(number) {
            let window: ClosedRange<Double>
            let extending: Bool
            if let covered = liveCoverage[number], covered.contains(now) {
                guard now > covered.upperBound - 20 else { continue }
                window = covered.upperBound...(covered.upperBound + 90)
                extending = true
            } else {
                window = max(0, now - 3)...(now + 45)
                extending = false
            }
            if lengthMs > 0, window.lowerBound > Double(lengthMs) / 1000 { continue }
            liveLoading.insert(number)
            let generation = mediaGeneration
            Task {
                let result = try? await TorrentEngine.shared.mkvCues(source, tracks: [number], from: window.lowerBound, to: window.upperBound)
                guard generation == mediaGeneration else { return }
                liveLoading.remove(number)
                guard let cues = result?[number] else { return }
                liveTracks[number]?.merge(cues)
                if extending, let covered = liveCoverage[number], covered.upperBound >= window.lowerBound {
                    liveCoverage[number] = covered.lowerBound...window.upperBound
                } else {
                    liveCoverage[number] = window
                }
                refreshSubtitleText()
            }
        }
    }

    // MARK: Ticking

    private func startTicking() {
        tickTask?.cancel()
        tickTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                self?.tick()
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
        }
    }

    private func tick() {
        let state = mediaPlayer.state
        let playing = mediaPlayer.isPlaying
        if playing != isPlaying { isPlaying = playing }

        let time: VLCTime? = mediaPlayer.time
        let t = Int(time?.intValue ?? 0)
        if t != timeMs { timeMs = t }
        if let length = mediaPlayer.media?.length.intValue, length > 0, Int(length) != lengthMs {
            lengthMs = Int(length)
        }

        // VLC's own "buffering" state is noisy; treat "time doesn't move while
        // we are supposed to be playing" as buffering instead.
        if playing && t == lastTimeMs { stalledTicks += 1 } else { stalledTicks = 0 }
        lastTimeMs = t
        let buffering = state == .opening || (state == .buffering && t == 0) || stalledTicks > 8
        if buffering != isBuffering { isBuffering = buffering }

        if state == .error, errorText == nil {
            errorText = "VLC не смог воспроизвести этот файл"
        }
        if state == .ended, !endedReported {
            endedReported = true
            onEnded?()
        }

        tickCount += 1
        if tickCount % 10 == 1 { refreshTracks() }
        if tickCount % 3 == 0 { updateLiveSubtitles() }
        refreshSubtitleText()
    }

    private func refreshTracks() {
        let audioNames = mediaPlayer.audioTrackNames as? [String] ?? []
        let audioIndexes = (mediaPlayer.audioTrackIndexes as? [NSNumber] ?? []).map { $0.int32Value }
        let audio = zip(audioIndexes, audioNames).filter { $0.0 >= 0 }.map { TrackOption(id: $0.0, name: $0.1) }
        if audio != audioTracks { audioTracks = audio }
        let currentAudio = mediaPlayer.currentAudioTrackIndex
        if currentAudio != currentAudioTrack { currentAudioTrack = currentAudio }

        let subNames = mediaPlayer.videoSubTitlesNames as? [String] ?? []
        let subIndexes = (mediaPlayer.videoSubTitlesIndexes as? [NSNumber] ?? []).map { $0.int32Value }
        let subs = zip(subIndexes, subNames).filter { $0.0 >= 0 }.map { TrackOption(id: $0.0, name: $0.1) }
        if subs != embeddedSubtitles {
            embeddedSubtitles = subs
            autoSelectSubtitles()
        }
        // VLC may auto-enable a default track after we've chosen otherwise.
        if hasMedia, mediaPlayer.state == .playing || mediaPlayer.state == .paused {
            syncVLCSubtitle()
        }
    }

    // MARK: OSD

    func showOSD(_ message: String) {
        osdMessage = message
        osdTask?.cancel()
        osdTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 1_600_000_000)
            if !Task.isCancelled { self?.osdMessage = nil }
        }
    }
}
