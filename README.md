# FS MediaPlayer

**FS MediaPlayer** — плеер для **macOS, Windows, Android и Android TV**, который воспроизводит торренты **онлайн, не дожидаясь полной загрузки** (как Ace Stream / Soda Player), на базе **VLC** (VLCKit) с поддержкой **двойных субтитров**.

Разработчик — **FantasticSoftware**, связь: [armenoid@gmail.com](mailto:armenoid@gmail.com).

<img src="assets/icon-1024.png" width="160" alt="FS MediaPlayer icon">

## Возможности

- **Magnet-ссылки, .torrent файлы, ссылки на .torrent, info-hash** — вставьте, перетащите в окно или откройте двойным кликом (приложение регистрируется как обработчик `magnet:` и `.torrent`).
- **Стриминг во время загрузки**: в первую очередь качаются куски файла, которые сейчас смотрит плеер. Перемотка работает сразу — загрузка переключается на новую позицию.
- **VLC внутри** (VLCKit на Mac, LibVLCSharp на Windows): MKV, AVI, MP4, HEVC/H.265, AC3/DTS, несколько звуковых дорожек — всё, что умеет VLC.
- **Двойные субтитры**: одновременно две дорожки (например, английские + русские для изучения языка):
  - любые две дорожки, встроенные в MKV (текстовые SRT/ASS/WebVTT читаются прямо из торрента рядом с позицией просмотра), и/или внешние файлы `.srt / .ass / .ssa / .vtt`;
  - для форматов, которые движок не читает (субтитры в MP4, картинки PGS/VobSub), — дорожка через VLC;
  - субтитры из самого торрента подхватываются автоматически (в т.ч. из папок `Subs`);
  - автоматический выбор по языкам (по умолчанию: 1 — русский, 2 — английский, меняется в настройках);
  - отдельная задержка для каждой дорожки, цвет и положение вторых субтитров (сверху экрана или над первыми), размер шрифта, подложка;
  - кодировки UTF‑8, UTF‑16 и Windows‑1251 (старые русские .srt) определяются автоматически.
- **Сериалы**: список файлов раздачи, кнопка «следующая серия» и автопереход к следующему файлу.
- Статистика: пиры, скорость, сколько скачано.
- Недавние торренты на главном экране.
- Локальные видеофайлы и прямые http-ссылки на видео тоже открываются.
- macOS: universal-сборка для Apple Silicon (M1–M4) и Intel, macOS 13 Ventura и новее.
- Windows: 10 и 11 (64-бит), установщик или портативная версия.

## Установка

Все файлы — на странице [Releases](https://github.com/Arthuralend/TorVideoPlayer/releases/latest).

### macOS

1. Скачайте `FS-MediaPlayer.dmg` из [Releases](https://github.com/Arthuralend/TorVideoPlayer/releases) (или из артефактов последней сборки во вкладке [Actions](https://github.com/Arthuralend/TorVideoPlayer/actions)).
2. Перетащите **FS MediaPlayer** в «Программы».
3. Приложение не подписано сертификатом Apple, поэтому при первом запуске macOS его заблокирует. Выполните в Терминале один раз:
   ```bash
   xattr -dr com.apple.quarantine "/Applications/FS MediaPlayer.app"
   ```
   (или: правый клик по приложению → «Открыть» → «Открыть»; на macOS 15 — «Системные настройки → Конфиденциальность и безопасность → Всё равно открыть»).

### Android 8+

- `FS-MediaPlayer-Android-arm64.apk` — для большинства телефонов и планшетов; `FS-MediaPlayer-Android.apk` — универсальный, если первый не ставится.
- Откройте APK на телефоне и разрешите установку из этого источника. Magnet-ссылки из браузера и .torrent файлы открываются в FS MediaPlayer; ссылку можно и «Поделиться» → FS MediaPlayer.
- Управление: касание — показать/скрыть кнопки, двойное касание слева/справа — ∓10 с, в центре — пауза.

### Android TV и ТВ-приставки

- Скачайте `FS-MediaPlayer-Android-armv7.apk` (у многих приставок — Mi Box, Chromecast с Google TV и др. — 32-битная система) или универсальный `FS-MediaPlayer-Android.apk`. Установить можно через флешку и файловый менеджер, приложение Downloader или `adb install`.
- Приложение появляется на главном экране Android TV (баннер FS MediaPlayer). Всё управляется пультом.
- Видео, .torrent и субтитры с внутренней памяти или USB-флешки открываются кнопкой «Файл…» — встроенным обзором файлов (при первом запуске он попросит доступ к медиафайлам; чтобы видеть .torrent и .srt на Android 11+, нажмите «Доступ ко всем файлам»).

| Кнопка пульта | Действие |
|---|---|
| OK | Показать панель управления (в панели — нажать выбранную кнопку) |
| ← / → (панель скрыта или выбрана полоса перемотки) | −10 / +10 секунд; удерживайте — перемотка ускоряется до 30 и 60 с |
| ↑ / ↓ | Показать панель управления |
| Назад | Скрыть панель, затем выйти из плеера |
| ⏯ / ▶ / ⏸ / ⏹ | Пауза / воспроизведение / стоп |
| ⏪ / ⏩ | Перемотка |
| Меню, CC, жёлтая | Двойные субтитры |
| Зелёная, Audio | Звуковая дорожка |
| ⏭, CH+ | Следующий файл |

### Windows

- **Установщик**: `FS-MediaPlayer-Setup-x64.exe`. Права администратора не нужны; при установке можно включить открытие magnet-ссылок и .torrent файлов в FS MediaPlayer. Программа не подписана, поэтому SmartScreen может показать предупреждение: «Подробнее» → «Выполнить в любом случае».
- **Портативная версия**: `FS-MediaPlayer-Windows-x64.zip` — распакуйте в любую папку и запустите `MediaPlayer.exe`. Ассоциации с magnet/.torrent включаются в «Настройках».

Файлы программы на Windows: настройки — `%APPDATA%\MediaPlayer`, загрузки и логи — `%LOCALAPPDATA%\MediaPlayer`.

## Горячие клавиши

| Клавиша | Действие |
|---|---|
| Пробел / клик | Пауза / воспроизведение |
| ← / → | −10 / +10 секунд (с Shift — 60 секунд) |
| ↑ / ↓ | Громкость |
| F, Enter, двойной клик | Полный экран (Esc — выход) |
| M | Без звука |
| S / D | Переключить субтитры 1 / субтитры 2 |
| Z / X | Задержка субтитров 1 −0,1 / +0,1 с |
| C / V | Задержка субтитров 2 −0,1 / +0,1 с |
| N | Следующий файл |
| [ / ] / \ | Медленнее / быстрее / обычная скорость |
| ⌘O | Открыть файл |
| ⇧⌘V | Открыть ссылку из буфера обмена |
| ⇧⌘S | Загрузить субтитры из файла |

Клавиши работают при любой раскладке (в т.ч. русской). Файл субтитров можно просто перетащить на окно с видео.

## Как это устроено

```
┌───────────────────── FS MediaPlayer.app ─────────────────────┐
│ SwiftUI интерфейс                                                  │
│   ├─ VLCKit (libVLC 3) ── http://127.0.0.1:PORT/stream/… ──┐       │
│   └─ свой рендер субтитров (SRT/ASS/VTT) поверх видео      │       │
│                                                            ▼       │
│ torengine (Go, anacrolix/torrent) — локальный HTTP-сервер с Range, │
│   приоритет загрузки кусков вокруг позиции воспроизведения         │
└────────────────────────────────────────────────────────────────────┘
```

- `engine/` — торрент-движок на Go: DHT, трекеры, PEX, uTP/TCP. Отдаёт файлы по HTTP с поддержкой Range, поэтому VLC может перематывать. Сам завершается вместе с приложением и удаляет временные файлы (их можно сохранять — см. настройки).
- `App/Sources/` — приложение для macOS на Swift/SwiftUI + VLCKit.
- `android/` — приложение для Android и Android TV на Kotlin + libVLC, движок встроен как `libtorengine.so`.
- `windows/MediaPlayer/` — приложение для Windows на C#/WPF (.NET 8) + LibVLCSharp, тот же движок (`torengine.exe`).

Загрузки лежат в `~/Library/Caches/TorVideoPlayer/Downloads` и удаляются при выходе (если не включено «Сохранять скачанные файлы»). Лог движка: `~/Library/Caches/TorVideoPlayer/engine.log`.

### Про Ace Stream

Ссылки `acestream://…` работают через собственную P2P-сеть Ace Stream, а не BitTorrent. Нативного Ace Stream для macOS нет; если запустить Ace Stream Engine (например, в Docker на порту 6878), FS MediaPlayer воспроизведёт `acestream://ID` через него. Обычные торренты (magnet / .torrent) работают без каких-либо дополнительных программ.

## Сборка из исходников

### macOS

Нужны Xcode 15+, Go, XcodeGen и CocoaPods:

```bash
brew install go xcodegen cocoapods
git clone https://github.com/Arthuralend/TorVideoPlayer.git
cd TorVideoPlayer
./scripts/build-app.sh          # → build/TorVideoPlayer.app и build/FS-MediaPlayer.dmg
```

Для разработки: `./scripts/build-engine.sh && xcodegen generate && pod install`, затем откройте `TorVideoPlayer.xcworkspace` в Xcode.

### macOS: экспериментальная сборка FS MediaPlayer LT

Отдельное приложение, в котором торренты качает и раздаёт плееру [TorrServer-LT](https://github.com/trinity-aml/TorrServer-LT) (libtorrent) вместо встроенного движка; наш `torengine` в ней только читает встроенные субтитры MKV. TorrServer-LT распространяется по GPL-3.0, поэтому сборка не публикуется в релизах и делается для личного использования:

```bash
./scripts/build-engine.sh && ./scripts/fetch-torrserver-lt.sh
xcodegen generate && pod install
xcodebuild -workspace TorVideoPlayer.xcworkspace -scheme FSMediaPlayerLT -configuration Release -derivedDataPath build/DerivedData build
```

В GitHub Actions её собирает ручной запуск workflow (Run workflow) — DMG появляется в артефактах как `FS-MediaPlayer-LT-dmg`.

Для Android (телефоны, Mi Box и другие ТВ-приставки на ARM) есть такой же вариант — flavor `lt`, ставится рядом с обычным приложением как «FS MediaPlayer LT»:

```bash
./scripts/build-engine-android.sh && ./scripts/fetch-torrserver-lt-android.sh
gradle -p android assembleLtRelease      # → android/app/build/outputs/apk/lt/release/
```

При ручном запуске workflow APK появляются в артефактах как `FS-MediaPlayer-LT-android`.

### Windows

Нужны .NET 8 SDK и Go (а для установщика — Inno Setup 6):

```bash
./scripts/build-engine-windows.sh                       # → build/engine-windows/torengine.exe (собирается на любой ОС)
dotnet publish windows/MediaPlayer/MediaPlayer.csproj -c Release -o build/windows/publish
iscc windows/installer.iss                              # → build/FS-MediaPlayer-Setup-x64.exe
```

Каждый push в `main` собирается в GitHub Actions (macOS, Windows, Android) и публикует релиз с DMG, установщиком, zip-архивом и APK.

## Другие программы для macOS (для сравнения)

- **Stremio** (+ аддон Torrentio) — стриминг торрентов, есть Mac-версия.
- **WebTorrent Desktop** — простой торрент-плеер, но видит в основном пиров WebTorrent и плохо работает с MKV/HEVC.
- **TorrServer** + **IINA**/VLC — сервер торрент-стриминга + внешний плеер (IINA умеет вторые субтитры).
- **Soda Player** — похожая идея, но давно не обновляется.

## Разработчик

FantasticSoftware — [armenoid@gmail.com](mailto:armenoid@gmail.com)

## Лицензия

Код проекта — MIT. VLCKit/libVLC распространяются по LGPL 2.1+, anacrolix/torrent — MPL 2.0.

Используйте программу для контента, на просмотр которого у вас есть права.
