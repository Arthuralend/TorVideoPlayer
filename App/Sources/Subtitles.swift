import Foundation

struct SubtitleCue {
    let start: Double
    let end: Double
    let text: String
}

/// A parsed text subtitle file, rendered by our own overlay so that two of
/// them (or one plus an embedded VLC track) can be shown at once.
final class SubtitleTrack {
    let cues: [SubtitleCue]

    init(cues: [SubtitleCue]) {
        self.cues = cues.sorted { $0.start < $1.start }
    }

    func text(at time: Double) -> String? {
        // Last cue that starts at or before `time`.
        var lo = 0, hi = cues.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if cues[mid].start <= time { lo = mid + 1 } else { hi = mid }
        }
        var lines: [String] = []
        var i = lo - 1
        var scanned = 0
        // Overlapping cues are rare but legal; look back a little.
        while i >= 0 && scanned < 30 {
            let cue = cues[i]
            if cue.end > time { lines.append(cue.text) }
            i -= 1
            scanned += 1
        }
        return lines.isEmpty ? nil : lines.reversed().joined(separator: "\n")
    }
}

enum SubtitleError: LocalizedError {
    case empty
    var errorDescription: String? { "В файле не найдено ни одной строки субтитров" }
}

enum SubtitleParser {
    static let supportedExtensions: Set<String> = ["srt", "vtt", "ass", "ssa"]

    static func parse(data: Data, fileExtension: String) throws -> SubtitleTrack {
        let text = decode(data)
        let ext = fileExtension.lowercased()
        var cues: [SubtitleCue]
        if ext == "ass" || ext == "ssa" || text.contains("[Events]") {
            cues = parseASS(text)
        } else {
            cues = parseSRT(text)
        }
        if cues.isEmpty { cues = parseASS(text) }
        guard !cues.isEmpty else { throw SubtitleError.empty }
        return SubtitleTrack(cues: cues)
    }

    /// UTF-8 / UTF-16 first, then Windows-1251 (most old Russian .srt files),
    /// then Latin-1 which never fails.
    static func decode(_ data: Data) -> String {
        if data.starts(with: [0xFF, 0xFE]) || data.starts(with: [0xFE, 0xFF]),
           let s = String(data: data, encoding: .utf16) {
            return s
        }
        if let s = String(data: data, encoding: .utf8) {
            return s.hasPrefix("\u{FEFF}") ? String(s.dropFirst()) : s
        }
        let cp1251 = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(
            CFStringEncoding(CFStringEncodings.windowsCyrillic.rawValue)))
        if let s = String(data: data, encoding: cp1251) { return s }
        return String(decoding: data, as: UTF8.self)
    }

    /// SRT and WebVTT.
    static func parseSRT(_ text: String) -> [SubtitleCue] {
        let lines = text.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n")
        var cues: [SubtitleCue] = []
        var i = 0
        while i < lines.count {
            let line = lines[i]
            guard let arrow = line.range(of: "-->") else { i += 1; continue }
            let startText = line[..<arrow.lowerBound].trimmingCharacters(in: .whitespaces)
            let endText = line[arrow.upperBound...].trimmingCharacters(in: .whitespaces)
                .split(separator: " ").first.map(String.init) ?? ""
            i += 1
            var body: [String] = []
            while i < lines.count, !lines[i].trimmingCharacters(in: .whitespaces).isEmpty {
                if lines[i].contains("-->") { break }
                body.append(lines[i])
                i += 1
            }
            if let start = parseTime(startText), let end = parseTime(endText), end > start {
                let cleaned = clean(body.joined(separator: "\n"))
                if !cleaned.isEmpty { cues.append(SubtitleCue(start: start, end: end, text: cleaned)) }
            }
        }
        return cues
    }

    /// ASS / SSA: reads the [Events] Format line to locate the fields.
    static func parseASS(_ text: String) -> [SubtitleCue] {
        var fields = ["layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"]
        var cues: [SubtitleCue] = []
        var inEvents = false
        for raw in text.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("[") {
                inEvents = line.lowercased() == "[events]"
                continue
            }
            guard inEvents else { continue }
            if line.lowercased().hasPrefix("format:") {
                fields = line.dropFirst("format:".count).split(separator: ",")
                    .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
                continue
            }
            guard line.lowercased().hasPrefix("dialogue:") else { continue }
            let parts = line.dropFirst("dialogue:".count)
                .split(separator: ",", maxSplits: max(fields.count - 1, 1), omittingEmptySubsequences: false)
                .map(String.init)
            guard let startIdx = fields.firstIndex(of: "start"),
                  let endIdx = fields.firstIndex(of: "end"),
                  let textIdx = fields.firstIndex(of: "text"),
                  parts.count > max(startIdx, endIdx, textIdx) else { continue }
            let body = parts[textIdx]
            if body.contains("\\p1") || body.contains("\\p2") { continue } // vector drawings
            guard let start = parseTime(parts[startIdx].trimmingCharacters(in: .whitespaces)),
                  let end = parseTime(parts[endIdx].trimmingCharacters(in: .whitespaces)),
                  end > start else { continue }
            let cleaned = clean(body
                .replacingOccurrences(of: "\\N", with: "\n")
                .replacingOccurrences(of: "\\n", with: "\n")
                .replacingOccurrences(of: "\\h", with: " "))
            if !cleaned.isEmpty { cues.append(SubtitleCue(start: start, end: end, text: cleaned)) }
        }
        return cues
    }

    /// Accepts "HH:MM:SS,mmm", "HH:MM:SS.mmm", "MM:SS.mmm" and "H:MM:SS.cc".
    static func parseTime(_ text: String) -> Double? {
        let parts = text.replacingOccurrences(of: ",", with: ".").split(separator: ":")
        guard parts.count == 2 || parts.count == 3 else { return nil }
        var total = 0.0
        for part in parts {
            guard let value = Double(part.trimmingCharacters(in: .whitespaces)) else { return nil }
            total = total * 60 + value
        }
        return total
    }

    private static let tagPattern = try! NSRegularExpression(pattern: "<[^>]*>|\\{[^}]*\\}")

    static func clean(_ text: String) -> String {
        let range = NSRange(text.startIndex..., in: text)
        var s = tagPattern.stringByReplacingMatches(in: text, range: range, withTemplate: "")
        s = s.replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&nbsp;", with: " ")
        return s.components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n")
    }
}

/// Matches subtitle file / track names against a language.
enum SubtitleLanguage {
    struct Language {
        let code: String
        let name: String
        let keys: Set<String>
    }

    static let all: [Language] = [
        Language(code: "ru", name: "Русский", keys: ["ru", "rus", "russian", "рус", "русский", "russkiy"]),
        Language(code: "en", name: "English", keys: ["en", "eng", "english", "англ", "английский"]),
        Language(code: "uk", name: "Українська", keys: ["uk", "ukr", "ukrainian", "укр", "украинский", "українська"]),
        Language(code: "de", name: "Deutsch", keys: ["de", "ger", "deu", "german", "deutsch"]),
        Language(code: "fr", name: "Français", keys: ["fr", "fre", "fra", "french", "français", "francais"]),
        Language(code: "es", name: "Español", keys: ["es", "spa", "spanish", "español", "espanol"]),
        Language(code: "it", name: "Italiano", keys: ["ita", "italian", "italiano"]),
        Language(code: "pt", name: "Português", keys: ["pt", "por", "portuguese", "português", "portugues"]),
        Language(code: "pl", name: "Polski", keys: ["pl", "pol", "polish", "polski"]),
        Language(code: "tr", name: "Türkçe", keys: ["tr", "tur", "turkish", "türkçe"]),
        Language(code: "ja", name: "日本語", keys: ["ja", "jpn", "japanese", "日本語"]),
        Language(code: "zh", name: "中文", keys: ["zh", "chi", "zho", "chinese", "中文"]),
        Language(code: "ko", name: "한국어", keys: ["ko", "kor", "korean", "한국어"]),
    ]

    static func matches(_ name: String, code: String) -> Bool {
        guard let language = all.first(where: { $0.code == code }) else { return false }
        let tokens = name.lowercased()
            .components(separatedBy: CharacterSet.letters.inverted)
            .filter { !$0.isEmpty }
        return tokens.contains { language.keys.contains($0) }
    }

    static func isForced(_ name: String) -> Bool {
        let lower = name.lowercased()
        return lower.contains("forced") || lower.contains("форс") || lower.contains("signs")
    }
}
