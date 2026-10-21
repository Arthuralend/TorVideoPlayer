import AppKit
import SwiftUI

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationWillFinishLaunching(_ notification: Notification) {
        Prefs.registerDefaults()
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Start the torrent engine early so the first magnet opens faster.
        Task { _ = try? await TorrentEngine.shared.ensureStarted() }
    }

    func application(_ application: NSApplication, open urls: [URL]) {
        for url in urls { AppState.shared.handle(url: url) }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    func applicationWillTerminate(_ notification: Notification) {
        AppState.shared.player.stop()
        TorrentEngine.shared.stop()
    }
}

@main
struct TorVideoPlayerApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var state = AppState.shared

    var body: some Scene {
        Window("VLC Torrent player", id: "main") {
            ContentView()
                .environmentObject(state)
                .environmentObject(state.player)
                .frame(minWidth: 820, minHeight: 500)
        }
        .defaultSize(width: 1100, height: 680)
        .commands { AppCommands(state: state) }

        Settings {
            SettingsView()
        }
    }
}

struct AppCommands: Commands {
    let state: AppState

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button("Открыть .torrent, видео или субтитры…") { state.showOpenPanel() }
                .keyboardShortcut("o")
            Button("Открыть ссылку из буфера обмена") { state.openFromClipboard() }
                .keyboardShortcut("v", modifiers: [.command, .shift])
            Divider()
            Button("На главный экран") { state.goHome() }
                .keyboardShortcut("h", modifiers: [.command, .shift])
        }
        CommandMenu("Воспроизведение") {
            Button("Пауза / Воспроизведение") { state.player.togglePlay() }
                .keyboardShortcut("p", modifiers: [.command])
            Button("Следующий файл") { state.playNext() }
                .keyboardShortcut(.rightArrow, modifiers: [.command])
            Divider()
            Button("Загрузить субтитры из файла…") { state.player.showSubtitleOpenPanel() }
                .keyboardShortcut("s", modifiers: [.command, .shift])
            Button("Переключить субтитры 1") { state.player.cycle(.primary) }
            Button("Переключить субтитры 2") { state.player.cycle(.secondary) }
            Button("Поменять субтитры местами") { state.player.swapSlots() }
            Divider()
            Button("Полноэкранный режим") { AppState.toggleFullScreen() }
                .keyboardShortcut("f", modifiers: [.command, .control])
        }
    }
}

struct ContentView: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        ZStack {
            switch state.screen {
            case .home:
                HomeView()
            case .loading(let message):
                LoadingView(message: message)
            case .files(let info):
                FileListView(info: info)
            case .player:
                PlayerView()
            }
        }
        .onDrop(of: [.fileURL, .url, .plainText], isTargeted: nil) { providers in
            state.handleDrop(providers)
        }
        .alert(
            "Ошибка",
            isPresented: Binding(
                get: { state.errorMessage != nil },
                set: { if !$0 { state.errorMessage = nil } }
            )
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(state.errorMessage ?? "")
        }
    }
}
