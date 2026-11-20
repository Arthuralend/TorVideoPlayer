#if LT_ENGINE
import Darwin
import Foundation

/// Experimental "FS MediaPlayer LT" backend: downloads and streams torrents
/// with TorrServer-LT (libtorrent), bundled as `torrserver-lt`. Our own
/// engine still runs alongside it, only to read embedded MKV subtitles from
/// TorrServer's stream (`MediaSource.http`).
@MainActor
final class TorrServerLT {
    static let shared = TorrServerLT()

    private var process: Process?
    private var baseURL: URL?
    private var startTask: Task<URL, Error>?

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 600
        return URLSession(configuration: config)
    }()

    static var directory: URL {
        TorrentEngine.supportDirectory.appendingPathComponent("TorrServerLT", isDirectory: true)
    }

    // MARK: Process

    func ensureStarted() async throws -> URL {
        if let baseURL, process?.isRunning == true { return baseURL }
        if let startTask { return try await startTask.value }
        let task = Task { try await self.launch() }
        startTask = task
        do {
            let url = try await task.value
            baseURL = url
            startTask = nil
            return url
        } catch {
            startTask = nil
            throw error
        }
    }

    private func launch() async throws -> URL {
        guard let executable = Bundle.main.url(forAuxiliaryExecutable: "torrserver-lt") else {
            throw EngineError.server("Не найден TorrServer-LT (torrserver-lt) внутри приложения.")
        }
        let fm = FileManager.default
        try? fm.createDirectory(at: Self.directory, withIntermediateDirectories: true)
        let port = Self.freePort() ?? 18090
        let logURL = Self.directory.appendingPathComponent("torrserver.log")

        let process = Process()
        process.executableURL = executable
        process.arguments = [
            "--port", String(port),
            "--ip", "127.0.0.1",
            "--path", Self.directory.path,
            "--logpath", logURL.path,
        ]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        process.terminationHandler = { _ in
            Task { @MainActor in TorrServerLT.shared.didTerminate() }
        }
        try process.run()
        self.process = process

        // Wait until the web server answers (it loads tracker lists first).
        let url = URL(string: "http://127.0.0.1:\(port)")!
        let deadline = Date().addingTimeInterval(30)
        while Date() < deadline {
            if !process.isRunning { throw EngineError.server("TorrServer-LT завершился при запуске. Журнал: \(logURL.path)") }
            var request = URLRequest(url: url.appendingPathComponent("echo"))
            request.timeoutInterval = 2
            if let result = try? await session.data(for: request),
               (result.1 as? HTTPURLResponse)?.statusCode == 200 {
                return url
            }
            try await Task.sleep(nanoseconds: 300_000_000)
        }
        process.terminate()
        throw EngineError.server("TorrServer-LT не запустился. Журнал: \(logURL.path)")
    }

    private func didTerminate() {
        process = nil
        baseURL = nil
        startTask = nil
    }

    func stop() {
        if let process, process.isRunning {
            process.terminate()
            let deadline = Date().addingTimeInterval(1.5)
            while process.isRunning && Date() < deadline { usleep(50_000) }
            if process.isRunning { kill(process.processIdentifier, SIGKILL) }
        }
        didTerminate()
    }

    /// A free TCP port on the loopback interface.
    private static func freePort() -> Int? {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return nil }
        defer { close(fd) }
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = 0
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, len) }
        }
        guard bound == 0 else { return nil }
        let named = withUnsafeMutablePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &len) }
        }
        guard named == 0 else { return nil }
        return Int(UInt16(bigEndian: addr.sin_port))
    }

    // MARK: API

    private struct FileStat: Decodable {
        let id: Int?
        let path: String?
        let length: Int64?
    }

    private struct Status: Decodable {
        let hash: String?
        let name: String?
        let title: String?
        let stat: Int?
        let torrent_size: Int64?
        let loaded_size: Int64?
        let download_speed: Double?
        let upload_speed: Double?
        let total_peers: Int?
        let active_peers: Int?
        let connected_seeders: Int?
        let file_stats: [FileStat]?
    }

    func add(uri: String) async throws -> TorrentInfo {
        let base = try await ensureStarted()
        let added: Status
        if uri.hasPrefix("/") {
            added = try await upload(torrentFile: URL(fileURLWithPath: uri), base: base)
        } else {
            added = try await post(base, ["action": "add", "link": uri])
        }
        guard let hash = added.hash, !hash.isEmpty else { throw EngineError.server("TorrServer-LT не принял торрент.") }
        // Metadata arrives from peers; poll until the file list is known.
        var current = added
        while (current.file_stats ?? []).isEmpty {
            try Task.checkCancellation()
            try await Task.sleep(nanoseconds: 500_000_000)
            current = try await post(base, ["action": "get", "hash": hash])
        }
        return info(from: current, hash: hash)
    }

    func status(infoHash: String) async throws -> TorrentStatus {
        let base = try await ensureStarted()
        let s: Status = try await post(base, ["action": "get", "hash": infoHash])
        return TorrentStatus(
            peers: s.total_peers ?? 0,
            activePeers: s.active_peers ?? 0,
            seeders: s.connected_seeders ?? 0,
            downloadSpeed: s.download_speed ?? 0,
            uploadSpeed: s.upload_speed ?? 0,
            bytesCompleted: s.loaded_size ?? 0,
            length: s.torrent_size ?? 0,
            fileCompleted: nil,
            fileLength: nil
        )
    }

    func drop(infoHash: String) async {
        guard let base = baseURL else { return }
        _ = try? await post(base, ["action": "drop", "hash": infoHash]) as Status
    }

    func streamURL(for file: TorrentFile) -> URL? {
        guard let base = baseURL else { return nil }
        return URL(string: base.absoluteString + file.streamPath)
    }

    private func info(from s: Status, hash: String) -> TorrentInfo {
        let files: [TorrentFile] = (s.file_stats ?? []).compactMap { f in
            guard let id = f.id, let path = f.path else { return nil }
            let name = (path as NSString).lastPathComponent
            let escaped = name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? "file"
            return TorrentFile(
                index: id,
                path: path,
                length: f.length ?? 0,
                kind: Self.kind(of: path),
                streamPath: "/stream/\(escaped)?link=\(hash)&index=\(id)&play"
            )
        }
        let name = [s.name, s.title].compactMap { $0 }.first { !$0.isEmpty } ?? hash
        return TorrentInfo(infoHash: hash, name: name, length: s.torrent_size ?? files.reduce(0) { $0 + $1.length }, files: files)
    }

    private static let videoExt: Set<String> = [
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "webm", "ts", "m2ts", "mts", "mpg", "mpeg",
        "flv", "ogv", "3gp", "vob", "divx", "rmvb",
    ]
    private static let audioExt: Set<String> = ["mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ac3", "dts", "mka", "eac3"]
    private static let subtitleExt: Set<String> = ["srt", "ass", "ssa", "vtt", "sub", "idx"]

    private static func kind(of path: String) -> String {
        let ext = (path as NSString).pathExtension.lowercased()
        if videoExt.contains(ext) { return "video" }
        if audioExt.contains(ext) { return "audio" }
        if subtitleExt.contains(ext) { return "subtitle" }
        return "other"
    }

    private func post<T: Decodable>(_ base: URL, _ body: [String: String]) async throws -> T {
        var request = URLRequest(url: base.appendingPathComponent("torrents"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)
        return try await perform(request)
    }

    private func upload(torrentFile: URL, base: URL) async throws -> Status {
        let data = try Data(contentsOf: torrentFile)
        let boundary = "fsmp-\(UUID().uuidString)"
        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(torrentFile.lastPathComponent.replacingOccurrences(of: "\"", with: ""))\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: application/x-bittorrent\r\n\r\n".data(using: .utf8)!)
        body.append(data)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        var request = URLRequest(url: base.appendingPathComponent("torrent/upload"))
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        return try await perform(request)
    }

    private func perform<T: Decodable>(_ request: URLRequest) async throws -> T {
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, http.statusCode != 200 {
            if let body = try? JSONDecoder().decode([String: String].self, from: data), let message = body["error"] {
                throw EngineError.server("TorrServer-LT: \(message)")
            }
            throw EngineError.server("Ошибка TorrServer-LT (HTTP \(http.statusCode))")
        }
        return try JSONDecoder().decode(T.self, from: data)
    }
}
#endif
