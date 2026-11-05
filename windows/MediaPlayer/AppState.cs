using System.IO;

namespace MediaPlayerApp;

public enum Screen { Home, Loading, Files, Player }

public sealed class AppState : Observable
{
    public static AppState Shared { get; } = new();

    public PlayerController Player { get; } = new();

    private CancellationTokenSource? addCts;
    private CancellationTokenSource? statusCts;

    public static readonly HashSet<string> VideoExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "webm", "ts", "m2ts", "mts", "mpg", "mpeg",
        "flv", "ogv", "3gp", "vob", "divx", "rmvb", "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav",
    };

    private AppState()
    {
        Player.Ended = PlayNext;
        Recents = Prefs.Current.Recents;
    }

    private Screen screen = Screen.Home;
    public Screen Screen { get => screen; set => Set(ref screen, value); }
    private string loadingMessage = "";
    public string LoadingMessage { get => loadingMessage; private set => Set(ref loadingMessage, value); }
    private TorrentInfo? currentTorrent;
    public TorrentInfo? CurrentTorrent { get => currentTorrent; private set { if (Set(ref currentTorrent, value)) RaiseTorrent(); } }
    private TorrentFile? currentFile;
    public TorrentFile? CurrentFile { get => currentFile; private set { if (Set(ref currentFile, value)) RaiseTorrent(); } }
    private TorrentStatus? status;
    public TorrentStatus? Status { get => status; private set { if (Set(ref status, value)) Raise(nameof(StatusText)); } }
    private List<RecentItem> recents = new();
    public List<RecentItem> Recents { get => recents; private set { if (Set(ref recents, value)) Raise(nameof(HasRecents)); } }
    public bool HasRecents => Recents.Count > 0;

    /// <summary>Raised when something should be shown to the user as an error dialog.</summary>
    public event Action<string>? Error;

    private void RaiseTorrent()
    {
        Raise(nameof(NextFile));
        Raise(nameof(HasNextFile));
        Raise(nameof(FilesSummary));
    }

    public string StatusText => Status is { } s
        ? $"👥 {s.ActivePeers}/{s.Peers}   ↓ {Format.Speed(s.DownloadSpeed)}" + (s.FileProgress is { } p ? $"   {p * 100:0}%" : "")
        : "";

    public string FilesSummary => CurrentTorrent is { } t
        ? $"{t.PlayableFiles.Count} видео · {t.SubtitleFiles.Count} файлов субтитров · {Format.Bytes(t.Length)}"
        : "";

    // MARK: Opening things

    public void Open(string raw)
    {
        var input = raw.Trim().Trim('"');
        if (input.Length == 0) return;
        var lower = input.ToLowerInvariant();

        if (lower.StartsWith("acestream://"))
        {
            // Ace Stream content IDs need the Ace Stream engine (its own P2P
            // network); if it runs locally we can play through it.
            var id = input["acestream://".Length..].Trim('/');
            PlayDirect(new Uri($"http://127.0.0.1:6878/ace/getstream?id={id}"), $"Ace Stream {id[..Math.Min(10, id.Length)]}…");
            return;
        }
        if ((lower.StartsWith("http://") || lower.StartsWith("https://")) && Uri.TryCreate(input, UriKind.Absolute, out var url))
        {
            var ext = Path.GetExtension(url.AbsolutePath).TrimStart('.');
            if (VideoExtensions.Contains(ext) || ext.Equals("m3u8", StringComparison.OrdinalIgnoreCase) || lower.Contains("/ace/getstream"))
            {
                PlayDirect(url, Path.GetFileName(url.AbsolutePath));
                return;
            }
        }
        if (lower.StartsWith("file://") && Uri.TryCreate(input, UriKind.Absolute, out var fileUri))
        {
            OpenFile(fileUri.LocalPath);
            return;
        }
        if (input.Length > 2 && (input[1] == ':' || input.StartsWith(@"\\")) && File.Exists(input))
        {
            OpenFile(input);
            return;
        }
        AddTorrent(input);
    }

    public void OpenFile(string path)
    {
        var ext = Path.GetExtension(path).TrimStart('.');
        if (ext.Equals("torrent", StringComparison.OrdinalIgnoreCase))
        {
            AddTorrent(path);
        }
        else if (SubtitleParser.SupportedExtensions.Contains(ext))
        {
            if (Player.HasMedia) Player.AddSubtitleFile(path);
            else Error?.Invoke("Сначала откройте видео, затем добавьте субтитры.");
        }
        else
        {
            PlayDirect(new Uri(path), Path.GetFileName(path));
        }
    }

    private async void AddTorrent(string uri)
    {
        addCts?.Cancel();
        var cts = addCts = new CancellationTokenSource();
        LeaveCurrentTorrent();
        LoadingMessage = "Получение информации о торренте…";
        Screen = Screen.Loading;
        try
        {
            var info = await TorrentEngine.Shared.AddAsync(uri, cts.Token);
            if (cts.IsCancellationRequested) return;
            RememberRecent(uri, info.Name);
            CurrentTorrent = info;
            var files = info.PlayableFiles;
            if (files.Count == 1)
            {
                Play(files[0]);
            }
            else
            {
                if (files.Count == 0) Error?.Invoke("В этом торренте нет видеофайлов.");
                Screen = Screen.Files;
            }
        }
        catch (Exception ex)
        {
            if (cts.IsCancellationRequested) return;
            Screen = Screen.Home;
            Error?.Invoke(ex.Message);
        }
    }

    public void CancelLoading()
    {
        addCts?.Cancel();
        Screen = Screen.Home;
    }

    // MARK: Playback

    public void Play(TorrentFile file)
    {
        if (CurrentTorrent is not { } torrent || TorrentEngine.Shared.StreamUri(file) is not { } uri) return;
        CurrentFile = file;
        MediaSource? source = MediaSource.MatroskaExtensions.Contains(file.Extension)
            ? new MediaSource.Torrent(torrent.InfoHash, file.Index) : null;
        Player.Open(uri, file.Name, SubtitleCandidates(file, torrent), source);
        Screen = Screen.Player;
        StartStatusPolling();
    }

    public void PlayDirect(Uri uri, string title)
    {
        LeaveCurrentTorrent();
        MediaSource? source = null;
        var subs = new List<SubtitleFileOption>();
        if (uri.IsFile)
        {
            var path = uri.LocalPath;
            if (MediaSource.MatroskaExtensions.Contains(Path.GetExtension(path).TrimStart('.'))) source = new MediaSource.Local(path);
            subs = LocalSubtitles(path);
        }
        Player.Open(uri, title, subs, source);
        Screen = Screen.Player;
    }

    public TorrentFile? NextFile
    {
        get
        {
            if (CurrentTorrent is not { } t || CurrentFile is not { } f) return null;
            var files = t.PlayableFiles;
            var i = files.FindIndex(x => x.Index == f.Index);
            return i >= 0 && i + 1 < files.Count ? files[i + 1] : null;
        }
    }

    public bool HasNextFile => NextFile != null;

    public void PlayNext()
    {
        if (NextFile is { } next) Play(next);
    }

    public void ClosePlayer()
    {
        Player.Stop();
        if (CurrentTorrent is { } t && t.PlayableFiles.Count > 1)
        {
            StopStatusPolling();
            Screen = Screen.Files;
        }
        else
        {
            GoHome();
        }
    }

    public void GoHome()
    {
        Player.Stop();
        LeaveCurrentTorrent();
        Screen = Screen.Home;
    }

    private void LeaveCurrentTorrent()
    {
        StopStatusPolling();
        if (CurrentTorrent is { } t) _ = TorrentEngine.Shared.DropAsync(t.InfoHash);
        CurrentTorrent = null;
        CurrentFile = null;
    }

    private async void StartStatusPolling()
    {
        StopStatusPolling();
        if (CurrentTorrent is not { } t) return;
        var fileIndex = CurrentFile?.Index;
        var cts = statusCts = new CancellationTokenSource();
        while (!cts.IsCancellationRequested)
        {
            try { Status = await TorrentEngine.Shared.StatusAsync(t.InfoHash, fileIndex, cts.Token); } catch { }
            try { await Task.Delay(1000, cts.Token); } catch (TaskCanceledException) { break; }
        }
    }

    private void StopStatusPolling()
    {
        statusCts?.Cancel();
        statusCts = null;
        Status = null;
    }

    /// <summary>Subtitle files from the torrent, those named like the video first.</summary>
    private static List<SubtitleFileOption> SubtitleCandidates(TorrentFile file, TorrentInfo torrent)
    {
        var baseName = Path.GetFileNameWithoutExtension(file.Name).ToLowerInvariant();
        var subs = torrent.SubtitleFiles;
        var related = subs.Where(s => s.Name.ToLowerInvariant().StartsWith(baseName)).ToList();
        // In a season pack show only the current episode's subtitles if any match.
        var list = related.Count > 0 ? related : subs;
        var result = new List<SubtitleFileOption>();
        foreach (var sub in list)
        {
            if (TorrentEngine.Shared.StreamUri(sub) is not { } uri) continue;
            var shown = sub.Directory.Length == 0 ? sub.Name : $"{Path.GetFileName(sub.Directory)}/{sub.Name}";
            result.Add(new SubtitleFileOption($"torrent:{sub.Index}", shown, uri));
        }
        return result;
    }

    private static List<SubtitleFileOption> LocalSubtitles(string video)
    {
        var dir = Path.GetDirectoryName(video) ?? "";
        var baseName = Path.GetFileNameWithoutExtension(video).ToLowerInvariant();
        var files = new List<string>();
        foreach (var folder in new[] { dir, Path.Combine(dir, "Subs"), Path.Combine(dir, "Subtitles") })
        {
            if (!Directory.Exists(folder)) continue;
            files.AddRange(Directory.EnumerateFiles(folder).Where(f => SubtitleParser.SupportedExtensions.Contains(Path.GetExtension(f).TrimStart('.'))));
        }
        var related = files.Where(f => Path.GetFileName(f).ToLowerInvariant().StartsWith(baseName)).ToList();
        var list = related.Count > 0 ? related : files;
        list.Sort(TorrentInfo.NaturalCompare);
        return list.Select(f => new SubtitleFileOption("local:" + f, Path.GetFileName(f), new Uri(f))).ToList();
    }

    // MARK: Recents

    private void RememberRecent(string uri, string name)
    {
        var list = Recents.Where(r => r.Uri != uri).ToList();
        list.Insert(0, new RecentItem { Uri = uri, Name = name, Date = DateTime.Now });
        if (list.Count > 20) list.RemoveRange(20, list.Count - 20);
        Recents = list;
        Prefs.Current.Recents = list;
        Prefs.Current.Save();
    }

    public void RemoveRecent(RecentItem item)
    {
        Recents = Recents.Where(r => r.Uri != item.Uri).ToList();
        Prefs.Current.Recents = Recents;
        Prefs.Current.Save();
    }

    public void ClearRecents()
    {
        Recents = new();
        Prefs.Current.Recents = Recents;
        Prefs.Current.Save();
    }
}
