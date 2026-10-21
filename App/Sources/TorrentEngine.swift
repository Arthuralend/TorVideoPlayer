import Foundation

enum EngineError: LocalizedError {
    case missingBinary
    case startFailed
    case server(String)

    var errorDescription: String? {
        switch self {
        case .missingBinary: return "Не найден торрент-движок (torengine) внутри приложения."
        case .startFailed: return "Не удалось запустить торрент-движок."
        case .server(let message): return message
        }
    }
}

/// Runs the bundled Go helper (`torengine`) and talks to it over HTTP on
/// 127.0.0.1. The helper downloads torrents and serves files with Range
/// support, prioritising the pieces the player is reading right now.
@MainActor
final class TorrentEngine {
    static let shared = TorrentEngine()

    private var process: Process?
    private var stdinPipe: Pipe?
    private var baseURL: URL?
    private var startTask: Task<URL, Error>?

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 600
        config.timeoutIntervalForResource = 600
        return URLSession(configuration: config)
    }()

    static var supportDirectory: URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return caches.appendingPathComponent("TorVideoPlayer", isDirectory: true)
    }

    static var downloadsDirectory: URL {
        supportDirectory.appendingPathComponent("Downloads", isDirectory: true)
    }

    func ensureStarted() async throws -> URL {
        if let baseURL { return baseURL }
        if let startTask { return try await startTask.value }
        let task = Task { try await self.launch() }
        startTask = task
        do {
            let url = try await task.value
            baseURL = url
            return url
        } catch {
            startTask = nil
            throw error
        }
    }

    private func launch() async throws -> URL {
        guard let executable = Bundle.main.url(forAuxiliaryExecutable: "torengine") else {
            throw EngineError.missingBinary
        }
        let keep = UserDefaults.standard.bool(forKey: Prefs.keepDownloads)
        let fm = FileManager.default
        try? fm.createDirectory(at: Self.supportDirectory, withIntermediateDirectories: true)
        if !keep {
            // Leftovers from a previous crash.
            try? fm.removeItem(at: Self.downloadsDirectory)
        }

        let process = Process()
        process.executableURL = executable
        process.arguments = ["-data", Self.downloadsDirectory.path, "-cleanup=\(!keep)"]
        let output = Pipe()
        let input = Pipe()
        process.standardOutput = output
        process.standardInput = input
        let logURL = Self.supportDirectory.appendingPathComponent("engine.log")
        fm.createFile(atPath: logURL.path, contents: nil)
        process.standardError = (try? FileHandle(forWritingTo: logURL)) ?? FileHandle.nullDevice
        process.terminationHandler = { _ in
            Task { @MainActor in TorrentEngine.shared.didTerminate() }
        }
        try process.run()
        self.process = process
        self.stdinPipe = input

        // The helper prints "LISTENING <port>" once its HTTP server is up.
        let port: Int = try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let handle = output.fileHandleForReading
                var buffer = Data()
                while true {
                    let chunk = handle.availableData
                    if chunk.isEmpty {
                        continuation.resume(throwing: EngineError.startFailed)
                        return
                    }
                    buffer.append(chunk)
                    let text = String(decoding: buffer, as: UTF8.self)
                    for line in text.split(separator: "\n") where line.hasPrefix("LISTENING ") {
                        if let port = Int(line.dropFirst("LISTENING ".count).trimmingCharacters(in: .whitespaces)) {
                            continuation.resume(returning: port)
                            return
                        }
                    }
                }
            }
        }
        return URL(string: "http://127.0.0.1:\(port)")!
    }

    private func didTerminate() {
        process = nil
        stdinPipe = nil
        baseURL = nil
        startTask = nil
    }

    func stop() {
        // Closing stdin makes the helper shut down and clean up.
        try? stdinPipe?.fileHandleForWriting.close()
        if let process, process.isRunning {
            let deadline = Date().addingTimeInterval(1.5)
            while process.isRunning && Date() < deadline { usleep(50_000) }
            if process.isRunning { process.terminate() }
        }
        didTerminate()
    }

    // MARK: API

    func add(uri: String) async throws -> TorrentInfo {
        let base = try await ensureStarted()
        var request = URLRequest(url: base.appendingPathComponent("api/add"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(["uri": uri])
        return try await perform(request)
    }

    func status(infoHash: String, fileIndex: Int?) async throws -> TorrentStatus {
        let base = try await ensureStarted()
        var components = URLComponents(url: base.appendingPathComponent("api/status/\(infoHash)"), resolvingAgainstBaseURL: false)!
        if let fileIndex { components.queryItems = [URLQueryItem(name: "file", value: String(fileIndex))] }
        return try await perform(URLRequest(url: components.url!))
    }

    func drop(infoHash: String) async {
        guard let base = baseURL else { return }
        var request = URLRequest(url: base.appendingPathComponent("api/drop/\(infoHash)"))
        request.httpMethod = "POST"
        _ = try? await session.data(for: request)
    }

    func streamURL(for file: TorrentFile) -> URL? {
        guard let base = baseURL else { return nil }
        return URL(string: base.absoluteString + file.streamPath)
    }

    private func perform<T: Decodable>(_ request: URLRequest) async throws -> T {
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, http.statusCode != 200 {
            if let body = try? JSONDecoder().decode([String: String].self, from: data), let message = body["error"] {
                throw EngineError.server(message)
            }
            throw EngineError.server("Ошибка торрент-движка (HTTP \(http.statusCode))")
        }
        return try JSONDecoder().decode(T.self, from: data)
    }
}
