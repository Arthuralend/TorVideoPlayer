import AppKit
import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var state: AppState
    @State private var input = ""

    var body: some View {
        VStack(spacing: 20) {
            Spacer(minLength: 20)
            Image(nsImage: NSApp.applicationIconImage)
                .resizable()
                .frame(width: 96, height: 96)
            Text("VLC Torrent player")
                .font(.largeTitle.bold())
            Text("Смотрите торренты онлайн — без ожидания полной загрузки")
                .foregroundStyle(.secondary)

            HStack {
                TextField("magnet:?xt=…, ссылка на .torrent, info-hash или acestream://…", text: $input)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(open)
                Button("Смотреть", action: open)
                    .keyboardShortcut(.defaultAction)
                    .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .frame(maxWidth: 680)

            HStack(spacing: 12) {
                Button {
                    state.showOpenPanel()
                } label: {
                    Label("Открыть файл…", systemImage: "folder")
                }
                Button {
                    if let text = NSPasteboard.general.string(forType: .string) {
                        input = text
                        open()
                    }
                } label: {
                    Label("Вставить из буфера", systemImage: "doc.on.clipboard")
                }
            }
            Text("Или перетащите .torrent файл, magnet-ссылку или видео в это окно")
                .font(.callout)
                .foregroundStyle(.secondary)

            if !state.recents.isEmpty {
                RecentsList()
                    .frame(maxWidth: 680, maxHeight: 260)
            }
            Spacer(minLength: 20)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear {
            if input.isEmpty, let text = NSPasteboard.general.string(forType: .string),
               text.lowercased().hasPrefix("magnet:") {
                input = text
            }
        }
    }

    private func open() {
        state.open(input: input)
    }
}

private struct RecentsList: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Недавние").font(.headline)
                Spacer()
                Button("Очистить") { state.clearRecents() }
                    .buttonStyle(.link)
            }
            ScrollView {
                VStack(spacing: 2) {
                    ForEach(state.recents) { item in
                        Button {
                            state.open(input: item.uri)
                        } label: {
                            HStack {
                                Image(systemName: "clock.arrow.circlepath")
                                    .foregroundStyle(.secondary)
                                Text(item.name)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                                Spacer()
                                Text(item.date, style: .date)
                                    .foregroundStyle(.secondary)
                                    .font(.caption)
                            }
                            .padding(.vertical, 6)
                            .padding(.horizontal, 10)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .background(RoundedRectangle(cornerRadius: 6).fill(Color.primary.opacity(0.04)))
                        .contextMenu {
                            Button("Скопировать ссылку") {
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(item.uri, forType: .string)
                            }
                            Button("Удалить из списка") { state.removeRecent(item) }
                        }
                    }
                }
            }
        }
    }
}

struct LoadingView: View {
    @EnvironmentObject private var state: AppState
    let message: String

    var body: some View {
        VStack(spacing: 18) {
            ProgressView()
                .controlSize(.large)
            Text(message)
                .font(.title3)
            Text("Поиск пиров может занять до минуты для редких раздач")
                .foregroundStyle(.secondary)
                .font(.callout)
            Button("Отмена") { state.cancelLoading() }
                .keyboardShortcut(.cancelAction)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct FileListView: View {
    @EnvironmentObject private var state: AppState
    let info: TorrentInfo

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Button {
                    state.goHome()
                } label: {
                    Label("Назад", systemImage: "chevron.left")
                }
                Spacer()
            }
            Text(info.name)
                .font(.title2.bold())
                .lineLimit(2)
            Text("\(info.playableFiles.count) видео · \(info.subtitleFiles.count) файлов субтитров · \(Format.bytes(info.length))")
                .foregroundStyle(.secondary)

            List(info.playableFiles) { file in
                Button {
                    state.play(file: file)
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: file == state.currentFile ? "play.circle.fill" : "play.circle")
                            .font(.title2)
                            .foregroundStyle(Color.accentColor)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(file.name)
                                .lineLimit(1)
                                .truncationMode(.middle)
                            if !file.directory.isEmpty {
                                Text(file.directory)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                        }
                        Spacer()
                        Text(Format.bytes(file.length))
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                    }
                    .padding(.vertical, 4)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(20)
    }
}

struct SettingsView: View {
    @AppStorage(Prefs.networkCaching) private var networkCaching = 3000
    @AppStorage(Prefs.keepDownloads) private var keepDownloads = false
    @AppStorage(Prefs.autoSubtitles) private var autoSubtitles = true
    @AppStorage(Prefs.subLang1) private var subLang1 = "ru"
    @AppStorage(Prefs.subLang2) private var subLang2 = "en"

    var body: some View {
        Form {
            Section("Субтитры") {
                Toggle("Включать субтитры автоматически", isOn: $autoSubtitles)
                languagePicker("Язык субтитров 1", selection: $subLang1)
                languagePicker("Язык субтитров 2", selection: $subLang2)
            }
            Section("Сеть") {
                HStack {
                    Text("Буфер VLC")
                    Slider(value: Binding(
                        get: { Double(networkCaching) },
                        set: { networkCaching = Int($0) }
                    ), in: 1000...15000, step: 500)
                    Text("\(String(format: "%.1f", Double(networkCaching) / 1000)) с")
                        .monospacedDigit()
                        .frame(width: 50, alignment: .trailing)
                }
                Toggle("Сохранять скачанные файлы после выхода", isOn: $keepDownloads)
                Button("Показать папку загрузок") {
                    let dir = TorrentEngine.downloadsDirectory
                    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                    NSWorkspace.shared.open(dir)
                }
                Text("Изменение «Сохранять файлы» вступит в силу после перезапуска.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .frame(width: 480, height: 400)
    }

    private func languagePicker(_ title: String, selection: Binding<String>) -> some View {
        Picker(title, selection: selection) {
            Text("Не выбирать").tag("none")
            ForEach(SubtitleLanguage.all, id: \.code) { language in
                Text(language.name).tag(language.code)
            }
        }
    }
}
