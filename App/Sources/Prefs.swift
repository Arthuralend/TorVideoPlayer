import Foundation

/// UserDefaults keys (also used with @AppStorage).
enum Prefs {
    static let networkCaching = "networkCaching"
    static let keepDownloads = "keepDownloads"
    static let autoSubtitles = "autoSubtitles"
    static let subLang1 = "subLang1"
    static let subLang2 = "subLang2"
    static let subFontSize = "subFontSize"
    static let secondaryPosition = "secondaryPosition"
    static let secondaryColor = "secondaryColor"
    static let subBackground = "subBackground"
    static let recents = "recents"

    static func registerDefaults() {
        UserDefaults.standard.register(defaults: [
            networkCaching: 3000,
            keepDownloads: false,
            autoSubtitles: true,
            subLang1: "ru",
            subLang2: "en",
            subFontSize: 34.0,
            secondaryPosition: "top",
            secondaryColor: "yellow",
            subBackground: true,
        ])
    }
}
