using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;

namespace MediaPlayerApp;

/// <summary>A file the engine can read embedded subtitles from.</summary>
public abstract record MediaSource
{
    public sealed record Torrent(string InfoHash, int FileIndex) : MediaSource;
    public sealed record Local(string Path) : MediaSource;

    public string Query => this switch
    {
        Torrent t => $"ih={t.InfoHash}&file={t.FileIndex}",
        Local l => $"path={Uri.EscapeDataString(l.Path)}",
        _ => "",
    };

    public static readonly HashSet<string> MatroskaExtensions = new(StringComparer.OrdinalIgnoreCase) { "mkv", "mka", "mk3d", "webm" };
}

/// <summary>
/// Runs the bundled Go helper (torengine.exe) and talks to it over HTTP on
/// 127.0.0.1. The helper downloads torrents and serves files with Range
/// support, prioritising the pieces the player is reading right now.
/// </summary>
public sealed class TorrentEngine
{
    public static TorrentEngine Shared { get; } = new();

    private static readonly JsonSerializerOptions Json = new() { PropertyNameCaseInsensitive = true };

    private readonly HttpClient http = new() { Timeout = TimeSpan.FromMinutes(10) };
    private Process? process;
    private Task<Uri>? startTask;
    private Uri? baseUri;

    public static string DataDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MediaPlayer");

    public static string DownloadsDirectory => Path.Combine(DataDirectory, "Downloads");

    public Task<Uri> EnsureStartedAsync()
    {
        if (baseUri != null) return Task.FromResult(baseUri);
        return startTask ??= LaunchAsync();
    }

    private async Task<Uri> LaunchAsync()
    {
        try
        {
            var exe = Path.Combine(AppContext.BaseDirectory, "torengine.exe");
            if (!File.Exists(exe)) throw new InvalidOperationException("Не найден торрент-движок (torengine.exe) рядом с программой.");

            var keep = Prefs.Current.KeepDownloads;
            Directory.CreateDirectory(DataDirectory);
            if (!keep)
            {
                try { Directory.Delete(DownloadsDirectory, true); } catch { /* leftovers in use */ }
            }

            var psi = new ProcessStartInfo(exe)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            psi.ArgumentList.Add("-data");
            psi.ArgumentList.Add(DownloadsDirectory);
            psi.ArgumentList.Add($"-cleanup={(!keep).ToString().ToLowerInvariant()}");
            var p = Process.Start(psi) ?? throw new InvalidOperationException("Не удалось запустить торрент-движок.");
            process = p;
            p.EnableRaisingEvents = true;
            p.Exited += (_, _) => { baseUri = null; startTask = null; process = null; };

            var log = new StreamWriter(Path.Combine(DataDirectory, "engine.log"), false) { AutoFlush = true };
            p.ErrorDataReceived += (_, e) => { if (e.Data != null) lock (log) log.WriteLine(e.Data); };
            p.BeginErrorReadLine();

            // The helper prints "LISTENING <port>" once its HTTP server is up.
            while (true)
            {
                var line = await p.StandardOutput.ReadLineAsync() ?? throw new InvalidOperationException("Торрент-движок завершился при запуске.");
                if (line.StartsWith("LISTENING ") && int.TryParse(line[10..].Trim(), out var port))
                {
                    baseUri = new Uri($"http://127.0.0.1:{port}");
                    return baseUri;
                }
            }
        }
        catch
        {
            startTask = null;
            throw;
        }
    }

    public void Stop()
    {
        var p = process;
        if (p == null) return;
        try
        {
            // Closing stdin makes the helper shut down and delete temporary files.
            p.StandardInput.Close();
            if (!p.WaitForExit(2000)) p.Kill(true);
        }
        catch { /* already gone */ }
        process = null;
        baseUri = null;
        startTask = null;
    }

    public Uri? StreamUri(TorrentFile file) => baseUri == null ? null : new Uri(baseUri, file.StreamPath);

    public async Task<TorrentInfo> AddAsync(string uri, CancellationToken ct)
    {
        var b = await EnsureStartedAsync();
        using var resp = await http.PostAsJsonAsync(new Uri(b, "api/add"), new { uri }, ct);
        return await ReadAsync<TorrentInfo>(resp, ct);
    }

    public async Task<TorrentStatus> StatusAsync(string infoHash, int? fileIndex, CancellationToken ct)
    {
        var b = await EnsureStartedAsync();
        var q = fileIndex is { } i ? $"?file={i}" : "";
        using var resp = await http.GetAsync(new Uri(b, $"api/status/{infoHash}{q}"), ct);
        return await ReadAsync<TorrentStatus>(resp, ct);
    }

    public async Task DropAsync(string infoHash)
    {
        if (baseUri == null) return;
        try { using var _ = await http.PostAsync(new Uri(baseUri, $"api/drop/{infoHash}"), null); } catch { }
    }

    public async Task<List<MkvTrack>> MkvTracksAsync(MediaSource source, CancellationToken ct)
    {
        var b = await EnsureStartedAsync();
        using var resp = await http.GetAsync(new Uri(b, $"api/mkv/tracks?{source.Query}"), ct);
        var result = await ReadAsync<MkvTracksResponse>(resp, ct);
        return result.Tracks ?? new();
    }

    /// <summary>Embedded subtitle cues of a track visible between from and to seconds.</summary>
    public async Task<List<SubtitleCue>> MkvCuesAsync(MediaSource source, ulong track, double from, double to, CancellationToken ct)
    {
        var b = await EnsureStartedAsync();
        var inv = CultureInfo.InvariantCulture;
        var url = new Uri(b, $"api/mkv/cues?{source.Query}&tracks={track}&from={from.ToString("0.###", inv)}&to={to.ToString("0.###", inv)}");
        using var resp = await http.GetAsync(url, ct);
        var result = await ReadAsync<MkvCuesResponse>(resp, ct);
        var list = new List<SubtitleCue>();
        if (result.Cues != null && result.Cues.TryGetValue(track.ToString(inv), out var cues))
        {
            foreach (var c in cues)
            {
                var text = SubtitleParser.Clean(c.Text);
                if (text.Length > 0) list.Add(new SubtitleCue(c.Start, c.End, text));
            }
        }
        return list;
    }

    public async Task<byte[]> GetBytesAsync(Uri uri, CancellationToken ct) => await http.GetByteArrayAsync(uri, ct);

    private static async Task<T> ReadAsync<T>(HttpResponseMessage resp, CancellationToken ct)
    {
        var body = await resp.Content.ReadAsStringAsync(ct);
        if (!resp.IsSuccessStatusCode)
        {
            string? message = null;
            try { message = JsonSerializer.Deserialize<Dictionary<string, string>>(body)?.GetValueOrDefault("error"); } catch { }
            throw new InvalidOperationException(message ?? $"Ошибка торрент-движка (HTTP {(int)resp.StatusCode})");
        }
        return JsonSerializer.Deserialize<T>(body, Json) ?? throw new InvalidOperationException("Пустой ответ торрент-движка");
    }

    private sealed record MkvTracksResponse(List<MkvTrack>? Tracks);
    private sealed record CueJson(double Start, double End, string Text);
    private sealed record MkvCuesResponse(Dictionary<string, List<CueJson>>? Cues);
}
