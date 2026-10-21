import Foundation

struct TorrentFile: Codable, Identifiable, Hashable {
    let index: Int
    let path: String
    let length: Int64
    let kind: String
    let streamPath: String

    var id: Int { index }
    var name: String { (path as NSString).lastPathComponent }
    var directory: String { (path as NSString).deletingLastPathComponent }
    var fileExtension: String { (path as NSString).pathExtension.lowercased() }
    var isPlayable: Bool { kind == "video" || kind == "audio" }
    var isTextSubtitle: Bool { SubtitleParser.supportedExtensions.contains(fileExtension) }
}

struct TorrentInfo: Codable, Hashable {
    let infoHash: String
    let name: String
    let length: Int64
    let files: [TorrentFile]

    /// Video (and audio) files in natural order, so episodes follow each other.
    var playableFiles: [TorrentFile] {
        let videos = files.filter { $0.kind == "video" }
        let list = videos.isEmpty ? files.filter(\.isPlayable) : videos
        return list.sorted { $0.path.localizedStandardCompare($1.path) == .orderedAscending }
    }

    var subtitleFiles: [TorrentFile] {
        files.filter(\.isTextSubtitle)
            .sorted { $0.path.localizedStandardCompare($1.path) == .orderedAscending }
    }
}

struct TorrentStatus: Codable {
    let peers: Int
    let activePeers: Int
    let seeders: Int
    let downloadSpeed: Double
    let uploadSpeed: Double
    let bytesCompleted: Int64
    let length: Int64
    let fileCompleted: Int64?
    let fileLength: Int64?

    var fileProgress: Double? {
        guard let done = fileCompleted, let total = fileLength, total > 0 else { return nil }
        return Double(done) / Double(total)
    }
}

struct RecentItem: Codable, Identifiable, Hashable {
    let uri: String
    var name: String
    var date: Date
    var id: String { uri }
}

enum Format {
    static func bytes(_ value: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: value, countStyle: .file)
    }

    static func speed(_ bytesPerSecond: Double) -> String {
        bytes(Int64(bytesPerSecond)) + "/с"
    }

    static func time(ms: Int) -> String {
        let total = max(0, ms / 1000)
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}
