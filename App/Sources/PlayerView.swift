import AppKit
import SwiftUI

/// Hosts the controller's persistent VLC video view.
struct VideoSurface: NSViewRepresentable {
    let player: PlayerController

    func makeNSView(context: Context) -> PassthroughView {
        let container = PassthroughView()
        container.wantsLayer = true
        container.layer?.backgroundColor = NSColor.black.cgColor
        let video = player.videoView
        video.removeFromSuperview()
        video.frame = container.bounds
        video.autoresizingMask = [.width, .height]
        container.addSubview(video)
        return container
    }

    func updateNSView(_ nsView: PassthroughView, context: Context) {
        if player.videoView.superview !== nsView {
            player.videoView.removeFromSuperview()
            player.videoView.frame = nsView.bounds
            nsView.addSubview(player.videoView)
        }
    }
}

struct PlayerView: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var player: PlayerController

    @State private var controlsVisible = true
    @State private var hideTask: Task<Void, Never>?
    @State private var seeking = false
    @State private var seekValue: Double = 0
    @State private var showSubtitleSettings = false

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black
                VideoSurface(player: player)

                SubtitleOverlay(height: geo.size.height, controlsVisible: controlsVisible)

                if player.isBuffering || player.errorText != nil {
                    BufferingBadge()
                }

                if let message = player.osdMessage {
                    VStack {
                        Text(message)
                            .font(.title3.weight(.semibold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 10))
                            .padding(.top, controlsVisible ? 70 : 24)
                        Spacer()
                    }
                    .allowsHitTesting(false)
                }

                // Transparent layer that receives clicks and hover over the video.
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture(count: 2) { AppState.toggleFullScreen() }
                    .onTapGesture { player.togglePlay(); pokeControls() }
                    .onContinuousHover { phase in
                        if case .active = phase { pokeControls() }
                    }

                VStack(spacing: 0) {
                    if controlsVisible { topBar.transition(.opacity) }
                    Spacer()
                    if controlsVisible { bottomBar.transition(.opacity) }
                }
            }
            .animation(.easeInOut(duration: 0.2), value: controlsVisible)
        }
        .onAppear { pokeControls() }
        .onDisappear {
            hideTask?.cancel()
            NSCursor.setHiddenUntilMouseMoves(false)
        }
        .onChange(of: showSubtitleSettings) { _ in pokeControls() }
    }

    // MARK: Bars

    private var topBar: some View {
        HStack(spacing: 14) {
            Button {
                state.closePlayer()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.title3.weight(.semibold))
            }
            .help("Назад")
            Text(player.title)
                .font(.headline)
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer()
            if let status = state.status {
                TorrentStatusLine(status: status)
            }
        }
        .buttonStyle(.plain)
        .foregroundColor(.white)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(LinearGradient(colors: [.black.opacity(0.75), .clear], startPoint: .top, endPoint: .bottom))
        .onHover { if $0 { pokeControls() } }
    }

    private var bottomBar: some View {
        VStack(spacing: 6) {
            HStack(spacing: 10) {
                Text(Format.time(ms: seeking ? Int(seekValue * Double(player.lengthMs)) : player.timeMs))
                    .monospacedDigit()
                    .frame(minWidth: 54, alignment: .trailing)
                Slider(
                    value: Binding(
                        get: { seeking ? seekValue : progress },
                        set: { seekValue = $0 }
                    ),
                    in: 0...1,
                    onEditingChanged: { editing in
                        if editing {
                            seekValue = progress
                            seeking = true
                        } else {
                            player.seek(fraction: seekValue)
                            seeking = false
                        }
                        pokeControls()
                    }
                )
                .controlSize(.small)
                Text(Format.time(ms: player.lengthMs))
                    .monospacedDigit()
                    .frame(minWidth: 54, alignment: .leading)
            }

            HStack(spacing: 18) {
                Button { player.jump(seconds: -10) } label: { Image(systemName: "gobackward.10") }
                    .help("Назад 10 с (←)")
                Button { player.togglePlay() } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 24))
                        .frame(width: 30)
                }
                .help("Пауза (пробел)")
                Button { player.jump(seconds: 10) } label: { Image(systemName: "goforward.10") }
                    .help("Вперёд 10 с (→)")
                if let next = state.nextFile {
                    Button { state.playNext() } label: { Image(systemName: "forward.end.fill") }
                        .help("Следующий: \(next.name) (N)")
                }

                HStack(spacing: 6) {
                    Button { player.toggleMute() } label: {
                        Image(systemName: player.isMuted || player.volume == 0 ? "speaker.slash.fill" : "speaker.wave.2.fill")
                            .frame(width: 22)
                    }
                    Slider(value: $player.volume, in: 0...200)
                        .controlSize(.mini)
                        .frame(width: 90)
                }

                Spacer()

                Button {
                    showSubtitleSettings.toggle()
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "captions.bubble.fill")
                        Text(subtitleSummary).font(.caption)
                    }
                }
                .help("Субтитры (S / D — переключить)")
                .popover(isPresented: $showSubtitleSettings, arrowEdge: .top) {
                    SubtitleSettingsView()
                        .environmentObject(player)
                }

                Menu {
                    if player.audioTracks.isEmpty {
                        Text("Нет звуковых дорожек")
                    }
                    ForEach(player.audioTracks) { track in
                        Button {
                            player.selectAudioTrack(track.id)
                        } label: {
                            if track.id == player.currentAudioTrack {
                                Label(track.name, systemImage: "checkmark")
                            } else {
                                Text(track.name)
                            }
                        }
                    }
                } label: {
                    Image(systemName: "waveform")
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .help("Звуковая дорожка")

                Menu {
                    ForEach([0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0], id: \.self) { value in
                        Button {
                            player.setRate(Float(value))
                        } label: {
                            if abs(Double(player.rate) - value) < 0.01 {
                                Label(String(format: "%.2g×", value), systemImage: "checkmark")
                            } else {
                                Text(String(format: "%.2g×", value))
                            }
                        }
                    }
                } label: {
                    Text(String(format: "%.2g×", player.rate)).font(.callout.monospacedDigit())
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .help("Скорость ([ ] \\)")

                Button { AppState.toggleFullScreen() } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                }
                .help("Полный экран (F)")
            }
            .font(.system(size: 17))
        }
        .buttonStyle(.plain)
        .foregroundColor(.white)
        .tint(.white)
        .padding(.horizontal, 18)
        .padding(.top, 26)
        .padding(.bottom, 12)
        .background(LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom))
        .onHover { if $0 { pokeControls(keepVisible: true) } }
    }

    private var progress: Double {
        player.lengthMs > 0 ? min(1, Double(player.timeMs) / Double(player.lengthMs)) : 0
    }

    private var subtitleSummary: String {
        let count = [player.primarySource, player.secondarySource].filter { $0 != .off }.count
        return count == 0 ? "Выкл" : (count == 2 ? "1 + 2" : "1")
    }

    // MARK: Auto-hiding controls

    private func pokeControls(keepVisible: Bool = false) {
        if !controlsVisible { controlsVisible = true }
        hideTask?.cancel()
        guard !keepVisible else { return }
        hideTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_800_000_000)
            guard !Task.isCancelled, player.isPlaying, !seeking, !showSubtitleSettings else { return }
            controlsVisible = false
            NSCursor.setHiddenUntilMouseMoves(true)
        }
    }
}

private struct TorrentStatusLine: View {
    let status: TorrentStatus

    var body: some View {
        HStack(spacing: 12) {
            Label("\(status.activePeers)/\(status.peers)", systemImage: "person.2.fill")
                .help("Подключённые пиры / известные пиры")
            Label(Format.speed(status.downloadSpeed), systemImage: "arrow.down")
            if let progress = status.fileProgress {
                Text("\(Int(progress * 100))%")
                    .help("Скачано от файла")
            }
        }
        .font(.caption.monospacedDigit())
        .foregroundColor(.white.opacity(0.85))
    }
}

private struct BufferingBadge: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var player: PlayerController

    var body: some View {
        VStack(spacing: 10) {
            if let error = player.errorText {
                Image(systemName: "exclamationmark.triangle.fill").font(.largeTitle)
                Text(error)
            } else {
                ProgressView().controlSize(.large).tint(.white)
                Text("Буферизация…").font(.headline)
                if let status = state.status {
                    Text("Пиры: \(status.activePeers) · \(Format.speed(status.downloadSpeed))")
                        .font(.callout.monospacedDigit())
                        .foregroundColor(.white.opacity(0.8))
                }
            }
        }
        .foregroundColor(.white)
        .padding(22)
        .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 14))
        .allowsHitTesting(false)
    }
}

// MARK: Subtitles overlay

struct SubtitleOverlay: View {
    @EnvironmentObject private var player: PlayerController
    @AppStorage(Prefs.subFontSize) private var fontSize = 34.0
    @AppStorage(Prefs.secondaryPosition) private var secondaryPosition = "top"
    @AppStorage(Prefs.secondaryColor) private var secondaryColor = "yellow"
    @AppStorage(Prefs.subBackground) private var subBackground = true

    let height: CGFloat
    let controlsVisible: Bool

    var body: some View {
        let scale = max(0.55, height / 720)
        let size = fontSize * scale
        // Leave room for VLC's own (embedded) subtitle line at the bottom.
        let vlcLine = player.vlcRendersSubtitle ? size * 2.8 : 0
        let bottomPadding = max(controlsVisible ? 92 : 0, 28 * scale + vlcLine)

        VStack(spacing: 0) {
            if secondaryPosition == "top", let text = player.secondaryText {
                line(text, size: size * 0.92, color: Self.color(named: secondaryColor))
                    .padding(.top, controlsVisible ? 64 : 24 * scale)
            }
            Spacer(minLength: 0)
            VStack(spacing: 6 * scale) {
                if secondaryPosition != "top", let text = player.secondaryText {
                    line(text, size: size * 0.92, color: Self.color(named: secondaryColor))
                }
                if let text = player.primaryText {
                    line(text, size: size, color: .white)
                }
            }
            .padding(.bottom, bottomPadding)
        }
        .padding(.horizontal, 48)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .allowsHitTesting(false)
    }

    private func line(_ text: String, size: CGFloat, color: Color) -> some View {
        Text(text)
            .font(.system(size: size, weight: .semibold))
            .foregroundColor(color)
            .multilineTextAlignment(.center)
            .shadow(color: .black, radius: 1.5, x: 0, y: 0)
            .shadow(color: .black.opacity(0.8), radius: 3, x: 0, y: 1)
            .padding(.horizontal, subBackground ? 10 : 0)
            .padding(.vertical, subBackground ? 3 : 0)
            .background(subBackground ? Color.black.opacity(0.45) : Color.clear, in: RoundedRectangle(cornerRadius: 6))
            .fixedSize(horizontal: false, vertical: true)
    }

    static func color(named name: String) -> Color {
        switch name {
        case "white": return .white
        case "cyan": return Color(red: 0.55, green: 0.9, blue: 1)
        case "green": return Color(red: 0.6, green: 1, blue: 0.6)
        default: return Color(red: 1, green: 0.88, blue: 0.3)
        }
    }
}

struct SubtitleSettingsView: View {
    @EnvironmentObject private var player: PlayerController
    @AppStorage(Prefs.subFontSize) private var fontSize = 34.0
    @AppStorage(Prefs.secondaryPosition) private var secondaryPosition = "top"
    @AppStorage(Prefs.secondaryColor) private var secondaryColor = "yellow"
    @AppStorage(Prefs.subBackground) private var subBackground = true

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Двойные субтитры").font(.headline)

            slot("Субтитры 1 (белые, внизу)", slot: .primary, delay: $player.primaryDelay)
            slot("Субтитры 2 (цветные)", slot: .secondary, delay: $player.secondaryDelay)

            HStack {
                Button("Поменять местами") { player.swapSlots() }
                Spacer()
                Button("Загрузить из файла…") { player.showSubtitleOpenPanel() }
            }

            Divider()

            Picker("Субтитры 2", selection: $secondaryPosition) {
                Text("Вверху экрана").tag("top")
                Text("Над субтитрами 1").tag("stacked")
            }
            .pickerStyle(.segmented)

            Picker("Цвет субтитров 2", selection: $secondaryColor) {
                Text("Жёлтый").tag("yellow")
                Text("Голубой").tag("cyan")
                Text("Зелёный").tag("green")
                Text("Белый").tag("white")
            }

            HStack {
                Text("Размер")
                Slider(value: $fontSize, in: 16...72, step: 1)
                Text("\(Int(fontSize))").monospacedDigit().frame(width: 28)
            }
            Toggle("Тёмная подложка под текстом", isOn: $subBackground)

            Text("Встроенные дорожки рисует VLC (одну за раз), файлы .srt/.ass/.vtt — сам плеер, поэтому можно сочетать любые два варианта.\nКлавиши: S / D — переключить, Z / X и C / V — задержка ±0,1 с.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(18)
        .frame(width: 420)
    }

    private func slot(_ title: String, slot: SubtitleSlot, delay: Binding<Double>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Picker(title, selection: Binding(
                get: { player.source(for: slot) },
                set: { player.select($0, for: slot) }
            )) {
                Text("Выключены").tag(SubtitleSource.off)
                if !player.embeddedSubtitles.isEmpty {
                    Section("Встроенные в видео") {
                        ForEach(player.embeddedSubtitles) { track in
                            Text(track.name).tag(SubtitleSource.embedded(track.id))
                        }
                    }
                }
                if !player.subtitleFiles.isEmpty {
                    Section("Файлы субтитров") {
                        ForEach(player.subtitleFiles) { file in
                            Text(file.name).tag(SubtitleSource.file(file.id))
                        }
                    }
                }
            }
            Stepper(value: delay, in: -600...600, step: 0.1) {
                Text(String(format: "Задержка: %+.1f с", delay.wrappedValue))
                    .monospacedDigit()
            }
        }
    }
}
