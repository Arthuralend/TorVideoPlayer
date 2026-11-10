using System.IO;
using System.Windows.Threading;
using LibVLCSharp.Shared;
using VlcPlayer = LibVLCSharp.Shared.MediaPlayer;

namespace MediaPlayerApp;

public sealed record TrackOption(int Id, string Name);

public sealed record SubtitleFileOption(string Id, string Name, Uri Uri);

public enum SourceKind { Off, Embedded, Stream, File }

/// <summary>
/// Where a subtitle slot takes its text from:
/// Stream — a text track inside the MKV, read by the engine and drawn by our overlay;
/// File — an external .srt/.ass/.vtt file, also drawn by the overlay;
/// Embedded — drawn by VLC itself (one at a time), for formats the engine can't read.
/// </summary>
public sealed record SubtitleSource(SourceKind Kind, long Number = 0, string Key = "")
{
    public static readonly SubtitleSource Off = new(SourceKind.Off);
    public static SubtitleSource Embedded(int id) => new(SourceKind.Embedded, id);
    public static SubtitleSource Stream(ulong number) => new(SourceKind.Stream, (long)number);
    public static SubtitleSource File(string id) => new(SourceKind.File, 0, id);
}

public sealed record SubtitleOption(SubtitleSource Source, string Name, string Group)
{
    public override string ToString() => Name;
}

public enum SubtitleSlot { Primary, Secondary }

public sealed class PlayerController : Observable
{
    public LibVLC LibVLC { get; }
    public VlcPlayer Player { get; }

    private readonly DispatcherTimer timer;
    private Media? currentMedia;
    private int tickCount;
    private long lastTime = -1;
    private int stalledTicks;
    private bool endedReported;
    private bool userChoseSubtitles;
    private CancellationTokenSource? osdCts;

    private SubtitleTrack? primaryTrack, secondaryTrack;
    private readonly Dictionary<string, SubtitleTrack> parsedTracks = new();

    // Embedded MKV subtitles, fetched from the engine in time windows.
    private MediaSource? mediaSource;
    private int generation;
    private readonly Dictionary<ulong, SubtitleTrack> liveTracks = new();
    private readonly Dictionary<ulong, (double From, double To)> liveCoverage = new();
    private readonly HashSet<ulong> liveLoading = new();

    /// <summary>Called once when playback reaches the end of the media.</summary>
    public Action? Ended;

    public PlayerController()
    {
        Core.Initialize();
        LibVLC = new LibVLC("--no-osd", "--no-video-title-show");
        Player = new VlcPlayer(LibVLC) { EnableHardwareDecoding = true };
        volume = Prefs.Current.Volume;
        timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(100) };
        timer.Tick += (_, _) => Tick();
    }

    // MARK: Observable state

    private string title = "";
    public string Title { get => title; private set => Set(ref title, value); }
    private bool isPlaying;
    public bool IsPlaying { get => isPlaying; private set => Set(ref isPlaying, value); }
    private bool isBuffering;
    public bool IsBuffering { get => isBuffering; private set => Set(ref isBuffering, value); }
    private bool hasMedia;
    public bool HasMedia { get => hasMedia; private set => Set(ref hasMedia, value); }
    private long timeMs;
    public long TimeMs { get => timeMs; private set { if (Set(ref timeMs, value)) Raise(nameof(Progress)); } }
    private long lengthMs;
    public long LengthMs { get => lengthMs; private set { if (Set(ref lengthMs, value)) Raise(nameof(Progress)); } }
    public double Progress => LengthMs > 0 ? Math.Min(1, (double)TimeMs / LengthMs) : 0;
    private float rate = 1;
    public float Rate { get => rate; private set => Set(ref rate, value); }
    private bool isMuted;
    public bool IsMuted { get => isMuted; private set => Set(ref isMuted, value); }
    private double volume;
    public double Volume
    {
        get => volume;
        set
        {
            if (!Set(ref volume, Math.Clamp(value, 0, 200))) return;
            Player.Volume = (int)volume;
            Prefs.Current.Volume = volume;
        }
    }

    private List<TrackOption> audioTracks = new();
    public List<TrackOption> AudioTracks { get => audioTracks; private set => Set(ref audioTracks, value); }
    private int currentAudioTrack = -1;
    public int CurrentAudioTrack { get => currentAudioTrack; private set => Set(ref currentAudioTrack, value); }
    private List<TrackOption> embeddedSubtitles = new();
    public List<TrackOption> EmbeddedSubtitles { get => embeddedSubtitles; private set { if (Set(ref embeddedSubtitles, value)) Raise(nameof(SubtitleOptions)); } }
    private List<SubtitleFileOption> subtitleFiles = new();
    public List<SubtitleFileOption> SubtitleFiles { get => subtitleFiles; private set { if (Set(ref subtitleFiles, value)) Raise(nameof(SubtitleOptions)); } }
    private List<MkvTrack> streamTracks = new();
    public List<MkvTrack> StreamTracks { get => streamTracks; private set { if (Set(ref streamTracks, value)) Raise(nameof(SubtitleOptions)); } }

    private SubtitleSource primarySource = SubtitleSource.Off;
    public SubtitleSource PrimarySource { get => primarySource; private set { if (Set(ref primarySource, value)) RaiseSources(); } }
    private SubtitleSource secondarySource = SubtitleSource.Off;
    public SubtitleSource SecondarySource { get => secondarySource; private set { if (Set(ref secondarySource, value)) RaiseSources(); } }

    /// <summary>Two-way binding targets for the subtitle menus.</summary>
    public SubtitleSource PrimarySelection { get => PrimarySource; set { if (value != null && value != PrimarySource) Select(value, SubtitleSlot.Primary); } }
    public SubtitleSource SecondarySelection { get => SecondarySource; set { if (value != null && value != SecondarySource) Select(value, SubtitleSlot.Secondary); } }

    private void RaiseSources()
    {
        Raise(nameof(PrimarySelection));
        Raise(nameof(SecondarySelection));
        Raise(nameof(VlcRendersSubtitle));
        Raise(nameof(SubtitleSummary));
    }

    private double primaryDelay;
    public double PrimaryDelay { get => primaryDelay; set { if (Set(ref primaryDelay, Math.Round(value, 1))) { ApplyEmbeddedDelay(); RefreshSubtitleText(); } } }
    private double secondaryDelay;
    public double SecondaryDelay { get => secondaryDelay; set { if (Set(ref secondaryDelay, Math.Round(value, 1))) { ApplyEmbeddedDelay(); RefreshSubtitleText(); } } }

    private string? primaryText;
    public string? PrimaryText { get => primaryText; private set => Set(ref primaryText, value); }
    private string? secondaryText;
    public string? SecondaryText { get => secondaryText; private set => Set(ref secondaryText, value); }
    private string? osdMessage;
    public string? OsdMessage { get => osdMessage; private set => Set(ref osdMessage, value); }
    private string? errorText;
    public string? ErrorText { get => errorText; private set => Set(ref errorText, value); }

    public bool VlcRendersSubtitle => PrimarySource.Kind == SourceKind.Embedded || SecondarySource.Kind == SourceKind.Embedded;

    public string SubtitleSummary
    {
        get
        {
            var count = new[] { PrimarySource, SecondarySource }.Count(s => s.Kind != SourceKind.Off);
            return count == 0 ? "Выкл" : count == 2 ? "1 + 2" : "1";
        }
    }

    /// <summary>VLC-rendered tracks are offered only when the engine couldn't read all embedded tracks itself.</summary>
    public List<TrackOption> VlcSubtitleOptions =>
        StreamTracks.Count == 0 || EmbeddedSubtitles.Count > StreamTracks.Count ? EmbeddedSubtitles : new();

    public List<SubtitleOption> SubtitleOptions
    {
        get
        {
            var list = new List<SubtitleOption> { new(SubtitleSource.Off, "Выключены", "") };
            list.AddRange(StreamTracks.Select(t => new SubtitleOption(SubtitleSource.Stream(t.Number), t.DisplayName, "Встроенные")));
            var vlcGroup = StreamTracks.Count == 0 ? "Встроенные" : "Встроенные (через VLC)";
            list.AddRange(VlcSubtitleOptions.Select(t => new SubtitleOption(SubtitleSource.Embedded(t.Id), t.Name, vlcGroup)));
            list.AddRange(SubtitleFiles.Select(f => new SubtitleOption(SubtitleSource.File(f.Id), f.Name, "Файлы")));
            return list;
        }
    }

    // MARK: Playback

    public void Open(Uri uri, string title, List<SubtitleFileOption> subtitleCandidates, MediaSource? source)
    {
        Stop();
        Title = title;
        generation++;
        mediaSource = source;
        StreamTracks = new();
        liveTracks.Clear();
        liveCoverage.Clear();
        liveLoading.Clear();
        SubtitleFiles = subtitleCandidates;
        parsedTracks.Clear();
        primaryTrack = secondaryTrack = null;
        PrimarySource = SubtitleSource.Off;
        SecondarySource = SubtitleSource.Off;
        PrimaryText = SecondaryText = null;
        PrimaryDelay = SecondaryDelay = 0;
        userChoseSubtitles = false;
        AudioTracks = new();
        EmbeddedSubtitles = new();
        CurrentAudioTrack = -1;
        TimeMs = 0;
        LengthMs = 0;
        lastTime = -1;
        stalledTicks = 0;
        endedReported = false;
        ErrorText = null;
        IsBuffering = true;

        var caching = Math.Max(500, Prefs.Current.NetworkCaching);
        currentMedia?.Dispose();
        currentMedia = new Media(LibVLC, uri, $":network-caching={caching}", ":file-caching=1000", ":http-reconnect");
        Player.Play(currentMedia);
        Player.SetRate(Rate);
        Player.Volume = (int)Volume;
        HasMedia = true;
        timer.Start();
        AutoSelectSubtitles();
        if (source != null) LoadStreamTracks(source);
    }

    private async void LoadStreamTracks(MediaSource source)
    {
        var gen = generation;
        try
        {
            var tracks = await TorrentEngine.Shared.MkvTracksAsync(source, CancellationToken.None);
            if (gen != generation) return;
            StreamTracks = tracks.Where(t => t.Text).ToList();
            AutoSelectSubtitles();
        }
        catch { /* not an MKV or engine busy: VLC tracks remain */ }
    }

    public void Stop()
    {
        timer.Stop();
        KeepDisplayAwake(false);
        if (Player.Media != null) Player.Stop();
        IsPlaying = false;
        IsBuffering = false;
        HasMedia = false;
    }

    public void TogglePlay()
    {
        if (!HasMedia) return;
        if (Player.IsPlaying)
        {
            Player.SetPause(true);
        }
        else
        {
            if ((Player.State == VLCState.Ended || Player.State == VLCState.Stopped) && currentMedia != null)
            {
                Player.Play(currentMedia);
                endedReported = false;
            }
            else
            {
                Player.SetPause(false);
            }
        }
    }

    public void SeekToMs(long ms)
    {
        if (!HasMedia) return;
        var target = LengthMs > 0 ? Math.Clamp(ms, 0, Math.Max(0, LengthMs - 500)) : Math.Max(0, ms);
        Player.Time = target;
        TimeMs = target;
        stalledTicks = 0;
        RefreshSubtitleText();
        UpdateLiveSubtitles();
    }

    public void SeekFraction(double fraction)
    {
        if (LengthMs > 0) SeekToMs((long)(fraction * LengthMs));
        else Player.Position = (float)fraction;
    }

    public void Jump(int seconds)
    {
        SeekToMs(TimeMs + seconds * 1000L);
        ShowOsd(seconds > 0 ? $"+{seconds} с" : $"−{-seconds} с");
    }

    public void ChangeVolume(double delta)
    {
        Volume += delta;
        if (IsMuted) ToggleMute();
        ShowOsd($"Громкость {(int)Volume}%");
    }

    public void ToggleMute()
    {
        IsMuted = !IsMuted;
        Player.Mute = IsMuted;
        ShowOsd(IsMuted ? "Звук выключен" : "Звук включён");
    }

    public void SetRate(float value)
    {
        Rate = value;
        Player.SetRate(value);
        ShowOsd($"Скорость {value:0.##}×");
    }

    public void SelectAudioTrack(int id)
    {
        Player.SetAudioTrack(id);
        CurrentAudioTrack = id;
    }

    // MARK: Subtitles

    public SubtitleSource SourceFor(SubtitleSlot slot) => slot == SubtitleSlot.Primary ? PrimarySource : SecondarySource;

    public void Select(SubtitleSource source, SubtitleSlot slot)
    {
        userChoseSubtitles = true;
        Apply(source, slot);
    }

    public void SwapSlots()
    {
        userChoseSubtitles = true;
        (primarySource, secondarySource) = (secondarySource, primarySource);
        (primaryTrack, secondaryTrack) = (secondaryTrack, primaryTrack);
        (primaryDelay, secondaryDelay) = (secondaryDelay, primaryDelay);
        Raise(nameof(PrimarySource));
        Raise(nameof(SecondarySource));
        Raise(nameof(PrimaryDelay));
        Raise(nameof(SecondaryDelay));
        RaiseSources();
        SyncVlcSubtitle();
        RefreshSubtitleText();
    }

    public string NameOf(SubtitleSource source) =>
        SubtitleOptions.FirstOrDefault(o => o.Source == source)?.Name ?? "Выключены";

    public void Cycle(SubtitleSlot slot)
    {
        var all = SubtitleOptions.Select(o => o.Source).ToList();
        var index = Math.Max(0, all.IndexOf(SourceFor(slot)));
        var next = all[(index + 1) % all.Count];
        Select(next, slot);
        ShowOsd($"Субтитры {(slot == SubtitleSlot.Primary ? 1 : 2)}: {NameOf(next)}");
    }

    public void AdjustDelay(SubtitleSlot slot, double delta)
    {
        if (slot == SubtitleSlot.Primary)
        {
            PrimaryDelay += delta;
            ShowOsd($"Задержка субтитров 1: {PrimaryDelay:+0.0;−0.0;0.0} с");
        }
        else
        {
            SecondaryDelay += delta;
            ShowOsd($"Задержка субтитров 2: {SecondaryDelay:+0.0;−0.0;0.0} с");
        }
    }

    public void AddSubtitleFile(string path)
    {
        var id = "local:" + path;
        if (!SubtitleFiles.Any(f => f.Id == id))
        {
            SubtitleFiles = SubtitleFiles.Append(new SubtitleFileOption(id, Path.GetFileName(path), new Uri(path))).ToList();
        }
        var slot = PrimarySource.Kind == SourceKind.Off ? SubtitleSlot.Primary : SubtitleSlot.Secondary;
        Select(SubtitleSource.File(id), slot);
        ShowOsd($"Субтитры {(slot == SubtitleSlot.Primary ? 1 : 2)}: {Path.GetFileName(path)}");
    }

    private void Apply(SubtitleSource source, SubtitleSlot slot)
    {
        // VLC can draw only one subtitle track: the other slot gives it up.
        if (source.Kind == SourceKind.Embedded)
        {
            var other = slot == SubtitleSlot.Primary ? SubtitleSlot.Secondary : SubtitleSlot.Primary;
            if (SourceFor(other).Kind == SourceKind.Embedded) SetSlot(SubtitleSource.Off, null, other);
        }
        switch (source.Kind)
        {
            case SourceKind.Off:
            case SourceKind.Embedded:
                SetSlot(source, null, slot);
                break;
            case SourceKind.File:
                parsedTracks.TryGetValue(source.Key, out var parsed);
                SetSlot(source, parsed, slot);
                if (parsed == null) LoadFileTrack(source.Key, slot);
                break;
            case SourceKind.Stream:
                var number = (ulong)source.Number;
                if (!liveTracks.TryGetValue(number, out var live))
                {
                    live = new SubtitleTrack(Array.Empty<SubtitleCue>());
                    liveTracks[number] = live;
                }
                SetSlot(source, live, slot);
                UpdateLiveSubtitles();
                break;
        }
        SyncVlcSubtitle();
        RefreshSubtitleText();
    }

    private void SetSlot(SubtitleSource source, SubtitleTrack? track, SubtitleSlot slot)
    {
        if (slot == SubtitleSlot.Primary) { PrimarySource = source; primaryTrack = track; }
        else { SecondarySource = source; secondaryTrack = track; }
    }

    private async void LoadFileTrack(string id, SubtitleSlot slot)
    {
        var option = SubtitleFiles.FirstOrDefault(f => f.Id == id);
        if (option == null) return;
        try
        {
            var data = option.Uri.IsFile
                ? await File.ReadAllBytesAsync(option.Uri.LocalPath)
                : await TorrentEngine.Shared.GetBytesAsync(option.Uri, CancellationToken.None);
            var track = SubtitleParser.Parse(data, Path.GetExtension(option.Uri.AbsolutePath));
            parsedTracks[id] = track;
            if (SourceFor(slot) == SubtitleSource.File(id))
            {
                SetSlot(SubtitleSource.File(id), track, slot);
                RefreshSubtitleText();
            }
        }
        catch (Exception ex)
        {
            ShowOsd($"Не удалось загрузить «{option.Name}»: {ex.Message}");
            if (SourceFor(slot) == SubtitleSource.File(id)) SetSlot(SubtitleSource.Off, null, slot);
        }
    }

    private void SyncVlcSubtitle()
    {
        var index = -1;
        if (PrimarySource.Kind == SourceKind.Embedded) index = (int)PrimarySource.Number;
        else if (SecondarySource.Kind == SourceKind.Embedded) index = (int)SecondarySource.Number;
        if (Player.Spu != index) Player.SetSpu(index);
        ApplyEmbeddedDelay();
    }

    private void ApplyEmbeddedDelay()
    {
        double delay = 0;
        if (PrimarySource.Kind == SourceKind.Embedded) delay = PrimaryDelay;
        if (SecondarySource.Kind == SourceKind.Embedded) delay = SecondaryDelay;
        var micros = (long)(delay * 1_000_000);
        if (Player.SpuDelay != micros) Player.SetSpuDelay(micros);
    }

    private void RefreshSubtitleText()
    {
        var t = TimeMs / 1000.0;
        PrimaryText = primaryTrack?.TextAt(t - PrimaryDelay);
        SecondaryText = secondaryTrack?.TextAt(t - SecondaryDelay);
    }

    /// <summary>Picks subtitles by the preferred languages until the user chooses by hand.</summary>
    private void AutoSelectSubtitles()
    {
        if (userChoseSubtitles || !Prefs.Current.AutoSubtitles) return;
        var primary = BestMatch(Prefs.Current.SubLang1, allowVlc: true);
        if (primary == null && Player.Spu >= 0)
        {
            // Keep a track enabled when the file asks for one by default.
            var track = StreamTracks.FirstOrDefault(t => t.IsDefault && !t.Forced) ?? StreamTracks.FirstOrDefault();
            if (track != null) primary = SubtitleSource.Stream(track.Number);
            else if (VlcSubtitleOptions.Count > 0) primary = SubtitleSource.Embedded(Player.Spu);
        }
        var secondary = BestMatch(Prefs.Current.SubLang2, allowVlc: primary?.Kind != SourceKind.Embedded);
        if (secondary == primary) secondary = null;
        if (primary != null && primary != PrimarySource) Apply(primary, SubtitleSlot.Primary);
        if (secondary != null && secondary != SecondarySource) Apply(secondary, SubtitleSlot.Secondary);
    }

    private SubtitleSource? BestMatch(string language, bool allowVlc)
    {
        if (language == "none") return null;
        T? Pick<T>(IEnumerable<T> items, Func<T, string> name) where T : class
        {
            var matching = items.Where(i => SubtitleLanguage.Matches(name(i), language)).ToList();
            return matching.FirstOrDefault(i => !SubtitleLanguage.IsForced(name(i))) ?? matching.FirstOrDefault();
        }
        if (Pick(StreamTracks.Where(t => !t.Forced), t => t.MatchText) is { } stream) return SubtitleSource.Stream(stream.Number);
        if (allowVlc && Pick(VlcSubtitleOptions, t => t.Name) is { } vlc) return SubtitleSource.Embedded(vlc.Id);
        if (Pick(SubtitleFiles, f => f.Name) is { } file) return SubtitleSource.File(file.Id);
        return null;
    }

    /// <summary>
    /// Keeps a window of embedded cues loaded around the playback position:
    /// a fresh window after a seek, the next one before the current runs out.
    /// </summary>
    private void UpdateLiveSubtitles()
    {
        if (mediaSource is not { } source) return;
        var active = new List<ulong>();
        foreach (var s in new[] { PrimarySource, SecondarySource })
        {
            if (s.Kind == SourceKind.Stream && !active.Contains((ulong)s.Number)) active.Add((ulong)s.Number);
        }
        var now = TimeMs / 1000.0;
        foreach (var number in active)
        {
            if (liveLoading.Contains(number)) continue;
            double from, to;
            bool extending;
            if (liveCoverage.TryGetValue(number, out var covered) && now >= covered.From && now <= covered.To)
            {
                if (now <= covered.To - 20) continue;
                (from, to, extending) = (covered.To, covered.To + 90, true);
            }
            else
            {
                (from, to, extending) = (Math.Max(0, now - 3), now + 45, false);
            }
            if (LengthMs > 0 && from > LengthMs / 1000.0) continue;
            FetchLive(source, number, from, to, extending);
        }
    }

    private async void FetchLive(MediaSource source, ulong number, double from, double to, bool extending)
    {
        liveLoading.Add(number);
        var gen = generation;
        List<SubtitleCue>? cues = null;
        try { cues = await TorrentEngine.Shared.MkvCuesAsync(source, number, from, to, CancellationToken.None); }
        catch { /* retried on the next tick */ }
        if (gen != generation) return;
        liveLoading.Remove(number);
        if (cues == null) return;
        if (liveTracks.TryGetValue(number, out var track)) track.Merge(cues);
        if (extending && liveCoverage.TryGetValue(number, out var covered) && covered.To >= from)
            liveCoverage[number] = (covered.From, to);
        else
            liveCoverage[number] = (from, to);
        RefreshSubtitleText();
    }

    // MARK: Ticking

    private void Tick()
    {
        var state = Player.State;
        var playing = Player.IsPlaying;
        if (IsPlaying != playing)
        {
            IsPlaying = playing;
            KeepDisplayAwake(playing);
        }
        var t = Math.Max(0, Player.Time);
        TimeMs = t;
        var length = Player.Length;
        if (length > 0) LengthMs = length;

        // VLC's own "buffering" state is noisy; treat "time doesn't move while
        // we are supposed to be playing" as buffering instead. For some files
        // VLC reports the time only about once a second, so a real stall is
        // assumed only after 2.5 s without progress.
        stalledTicks = playing && t == lastTime ? stalledTicks + 1 : 0;
        lastTime = t;
        IsBuffering = state == VLCState.Opening || (state == VLCState.Buffering && t == 0) || stalledTicks >= 25;

        if (state == VLCState.Error && ErrorText == null) ErrorText = "VLC не смог воспроизвести этот файл";
        if (state == VLCState.Ended && !endedReported)
        {
            endedReported = true;
            Ended?.Invoke();
        }

        tickCount++;
        if (tickCount % 10 == 1) RefreshTracks();
        if (tickCount % 3 == 0) UpdateLiveSubtitles();
        RefreshSubtitleText();
    }

    private void RefreshTracks()
    {
        var audio = (Player.AudioTrackDescription ?? Array.Empty<LibVLCSharp.Shared.Structures.TrackDescription>())
            .Where(d => d.Id >= 0).Select(d => new TrackOption(d.Id, d.Name ?? $"Дорожка {d.Id}")).ToList();
        if (!audio.SequenceEqual(AudioTracks)) AudioTracks = audio;
        CurrentAudioTrack = Player.AudioTrack;

        var subs = (Player.SpuDescription ?? Array.Empty<LibVLCSharp.Shared.Structures.TrackDescription>())
            .Where(d => d.Id >= 0).Select(d => new TrackOption(d.Id, d.Name ?? $"Дорожка {d.Id}")).ToList();
        if (!subs.SequenceEqual(EmbeddedSubtitles))
        {
            EmbeddedSubtitles = subs;
            AutoSelectSubtitles();
        }
        // VLC may auto-enable a default track after we've chosen otherwise.
        if (HasMedia && (Player.State == VLCState.Playing || Player.State == VLCState.Paused)) SyncVlcSubtitle();
    }

    // MARK: Display sleep

    [System.Runtime.InteropServices.DllImport("kernel32.dll")]
    private static extern uint SetThreadExecutionState(uint flags);

    private const uint EsContinuous = 0x80000000, EsSystemRequired = 0x00000001, EsDisplayRequired = 0x00000002;

    /// <summary>
    /// Keeps the screen on while a video is playing (Windows would otherwise
    /// turn the display off after the idle timeout). Called on the UI thread,
    /// which lives as long as the app.
    /// </summary>
    private static void KeepDisplayAwake(bool awake) =>
        SetThreadExecutionState(awake ? EsContinuous | EsSystemRequired | EsDisplayRequired : EsContinuous);

    // MARK: OSD

    public async void ShowOsd(string message)
    {
        OsdMessage = message;
        osdCts?.Cancel();
        var cts = osdCts = new CancellationTokenSource();
        try
        {
            await Task.Delay(1600, cts.Token);
            OsdMessage = null;
        }
        catch (TaskCanceledException) { }
    }
}
