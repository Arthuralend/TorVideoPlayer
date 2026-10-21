import AppKit
import SwiftUI

@MainActor
final class AppState: ObservableObject {
    static let shared = AppState()

    enum Screen {
        case home
        case loading(String)
        case files(TorrentInfo)
        case player
    }

    @Published var screen: Screen = .home
    @Published var errorMessage: String?
    @Published private(set) var status: TorrentStatus?
    @Published private(set) var currentTorrent: TorrentInfo?
    @Published private(set) var currentFile: TorrentFile?
    @Published private(set) var recents: [RecentItem] = []

    let player = PlayerController()

    private var addTask: Task<Void, Never>?
    private var statusTask: Task<Void, Never>?
    private var keyMonitor: Any?

    static let videoExtensions: Set<String> = [
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "webm", "ts", "m2ts", "mts", "mpg", "mpeg",
        "flv", "ogv", "3gp", "vob", "divx", "rmvb", "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav",
    ]

    init() {
        loadRecents()
        player.onEnded = { [weak self] in self?.playNext() }
        installKeyMonitor()
    }

    var isPlayerScreen: Bool {
        if case .player = screen { return true }
        return false
    }

    // MARK: Opening things

    func handle(url: URL) {
        if url.isFileURL { openFile(url) } else { open(input: url.absoluteString) }
    }

    func open(input raw: String) {
        let input = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !input.isEmpty else { return }
        let lower = input.lowercased()

        if lower.hasPrefix("acestream://") {
            // Ace Stream content IDs need the Ace Stream engine (it has its own
            // P2P network); if it is running locally we can play through it.
            let id = String(input.dropFirst("acestream://".count)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard let url = URL(string: "http://127.0.0.1:6878/ace/getstream?id=\(id)") else { return }
            playDirect(url, title: "Ace Stream \(id.prefix(10))…")
            return
        }
        if lower.hasPrefix("http://") || lower.hasPrefix("https://"), let url = URL(string: input) {
            let ext = url.pathExtension.lowercased()
            if Self.videoExtensions.contains(ext) || ext == "m3u8" || lower.contains("/ace/getstream") {
                playDirect(url, title: url.lastPathComponent)
                return
            }
        }
        if lower.hasPrefix("file://"), let url = URL(string: input) {
            openFile(url)
            return
        }
        if input.hasPrefix("/") || input.hasPrefix("~") {
            openFile(URL(fileURLWithPath: (input as NSString).expandingTildeInPath))
            return
        }
        addTorrent(uri: input)
    }

    func openFile(_ url: URL) {
        let ext = url.pathExtension.lowercased()
        if ext == "torrent" {
            addTorrent(uri: url.path)
        } else if SubtitleParser.supportedExtensions.contains(ext) {
            if player.hasMedia {
                player.addSubtitleFile(url: url)
            } else {
                errorMessage = "Сначала откройте видео, затем добавьте субтитры."
            }
        } else {
            playDirect(url, title: url.lastPathComponent)
        }
    }

    func showOpenPanel() {
        let panel = NSOpenPanel()
        panel.title = "Откройте .torrent файл, видео или субтитры"
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        if panel.runModal() == .OK, let url = panel.url {
            openFile(url)
        }
    }

    func openFromClipboard() {
        if let text = NSPasteboard.general.string(forType: .string) {
            open(input: text)
        }
    }

    func handleDrop(_ providers: [NSItemProvider]) -> Bool {
        guard let provider = providers.first else { return false }
        if provider.canLoadObject(ofClass: URL.self) {
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                guard let url else { return }
                Task { @MainActor in AppState.shared.handle(url: url) }
            }
            return true
        }
        if provider.canLoadObject(ofClass: String.self) {
            _ = provider.loadObject(ofClass: String.self) { text, _ in
                guard let text else { return }
                Task { @MainActor in AppState.shared.open(input: text) }
            }
            return true
        }
        return false
    }

    private func addTorrent(uri: String) {
        addTask?.cancel()
        leaveCurrentTorrent()
        screen = .loading("Получение информации о торренте…")
        addTask = Task {
            do {
                let info = try await TorrentEngine.shared.add(uri: uri)
                guard !Task.isCancelled else { return }
                rememberRecent(uri: uri, name: info.name)
                currentTorrent = info
                let files = info.playableFiles
                if files.count == 1 {
                    play(file: files[0])
                } else {
                    if files.isEmpty { errorMessage = "В этом торренте нет видеофайлов." }
                    screen = .files(info)
                }
            } catch {
                guard !Task.isCancelled else { return }
                screen = .home
                errorMessage = error.localizedDescription
            }
        }
    }

    func cancelLoading() {
        addTask?.cancel()
        addTask = nil
        screen = .home
    }

    // MARK: Playback

    func play(file: TorrentFile) {
        guard let torrent = currentTorrent, let url = TorrentEngine.shared.streamURL(for: file) else { return }
        currentFile = file
        player.open(url: url, title: file.name, subtitleCandidates: subtitleCandidates(for: file, in: torrent))
        screen = .player
        startStatusPolling()
    }

    func playDirect(_ url: URL, title: String) {
        leaveCurrentTorrent()
        player.open(url: url, title: title, subtitleCandidates: url.isFileURL ? localSubtitles(near: url) : [])
        screen = .player
    }

    var nextFile: TorrentFile? {
        guard let torrent = currentTorrent, let file = currentFile else { return nil }
        let files = torrent.playableFiles
        guard let index = files.firstIndex(of: file), index + 1 < files.count else { return nil }
        return files[index + 1]
    }

    func playNext() {
        if let next = nextFile { play(file: next) }
    }

    func closePlayer() {
        player.stop()
        if let torrent = currentTorrent, torrent.playableFiles.count > 1 {
            stopStatusPolling()
            screen = .files(torrent)
        } else {
            goHome()
        }
    }

    func goHome() {
        player.stop()
        leaveCurrentTorrent()
        screen = .home
    }

    private func leaveCurrentTorrent() {
        stopStatusPolling()
        if let torrent = currentTorrent {
            Task { await TorrentEngine.shared.drop(infoHash: torrent.infoHash) }
        }
        currentTorrent = nil
        currentFile = nil
    }

    private func startStatusPolling() {
        stopStatusPolling()
        guard let torrent = currentTorrent else { return }
        let fileIndex = currentFile?.index
        statusTask = Task { [weak self] in
            while !Task.isCancelled {
                if let status = try? await TorrentEngine.shared.status(infoHash: torrent.infoHash, fileIndex: fileIndex) {
                    self?.status = status
                }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    private func stopStatusPolling() {
        statusTask?.cancel()
        statusTask = nil
        status = nil
    }

    /// Subtitle files from the torrent, those named like the video first.
    private func subtitleCandidates(for file: TorrentFile, in torrent: TorrentInfo) -> [SubtitleFileOption] {
        let base = (file.name as NSString).deletingPathExtension.lowercased()
        let subs = torrent.subtitleFiles
        let related = subs.filter { $0.name.lowercased().hasPrefix(base) }
        let others = subs.filter { !$0.name.lowercased().hasPrefix(base) }
        // In a season pack only show the current episode's subtitles if any match.
        let list = related.isEmpty ? others : related
        return list.compactMap { sub in
            guard let url = TorrentEngine.shared.streamURL(for: sub) else { return nil }
            let shown = sub.directory.isEmpty ? sub.name : "\((sub.directory as NSString).lastPathComponent)/\(sub.name)"
            return SubtitleFileOption(id: "torrent:\(sub.index)", name: shown, url: url)
        }
    }

    private func localSubtitles(near video: URL) -> [SubtitleFileOption] {
        let fm = FileManager.default
        let dir = video.deletingLastPathComponent()
        let base = video.deletingPathExtension().lastPathComponent.lowercased()
        var urls: [URL] = []
        for folder in [dir, dir.appendingPathComponent("Subs"), dir.appendingPathComponent("subs"), dir.appendingPathComponent("Subtitles")] {
            let items = (try? fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
            urls += items.filter { SubtitleParser.supportedExtensions.contains($0.pathExtension.lowercased()) }
        }
        let related = urls.filter { $0.lastPathComponent.lowercased().hasPrefix(base) }
        return (related.isEmpty ? urls : related)
            .sorted { $0.path.localizedStandardCompare($1.path) == .orderedAscending }
            .map { SubtitleFileOption(id: "local:" + $0.path, name: $0.lastPathComponent, url: $0) }
    }

    // MARK: Recents

    private func loadRecents() {
        if let data = UserDefaults.standard.data(forKey: Prefs.recents),
           let items = try? JSONDecoder().decode([RecentItem].self, from: data) {
            recents = items
        }
    }

    private func saveRecents() {
        if let data = try? JSONEncoder().encode(recents) {
            UserDefaults.standard.set(data, forKey: Prefs.recents)
        }
    }

    private func rememberRecent(uri: String, name: String) {
        recents.removeAll { $0.uri == uri }
        recents.insert(RecentItem(uri: uri, name: name, date: Date()), at: 0)
        if recents.count > 20 { recents.removeLast(recents.count - 20) }
        saveRecents()
    }

    func removeRecent(_ item: RecentItem) {
        recents.removeAll { $0.id == item.id }
        saveRecents()
    }

    func clearRecents() {
        recents = []
        saveRecents()
    }

    // MARK: Keyboard

    static func toggleFullScreen() {
        (NSApp.keyWindow ?? NSApp.mainWindow ?? NSApp.windows.first)?.toggleFullScreen(nil)
    }

    private func installKeyMonitor() {
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self, self.handleKey(event) else { return event }
            return nil
        }
    }

    /// Player hotkeys. Uses key codes so they work with any keyboard layout.
    private func handleKey(_ event: NSEvent) -> Bool {
        guard isPlayerScreen else { return false }
        if NSApp.keyWindow?.firstResponder is NSText { return false }
        let mods = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        if mods.contains(.command) || mods.contains(.control) { return false }
        let shift = mods.contains(.shift)
        switch event.keyCode {
        case 49: player.togglePlay()                                  // space
        case 123: player.jump(seconds: shift ? -60 : -10)             // ←
        case 124: player.jump(seconds: shift ? 60 : 10)               // →
        case 126: player.changeVolume(by: 5)                          // ↑
        case 125: player.changeVolume(by: -5)                         // ↓
        case 3, 36: Self.toggleFullScreen()                           // F, Return
        case 53:                                                      // Esc
            if NSApp.keyWindow?.styleMask.contains(.fullScreen) == true {
                Self.toggleFullScreen()
            } else {
                return false
            }
        case 46: player.toggleMute()                                  // M
        case 1: player.cycle(.primary)                                // S
        case 2: player.cycle(.secondary)                              // D
        case 6: player.adjustDelay(.primary, by: -0.1)                // Z
        case 7: player.adjustDelay(.primary, by: 0.1)                 // X
        case 8: player.adjustDelay(.secondary, by: -0.1)              // C
        case 9: player.adjustDelay(.secondary, by: 0.1)               // V
        case 45: playNext()                                           // N
        case 33: player.setRate(max(0.25, player.rate - 0.25))        // [
        case 30: player.setRate(min(4, player.rate + 0.25))           // ]
        case 42: player.setRate(1)                                    // \
        default: return false
        }
        return true
    }
}
