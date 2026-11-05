using System.IO;
using System.Text.Json.Serialization;

namespace MediaPlayerApp;

public sealed record TorrentFile(int Index, string Path, long Length, string Kind, string StreamPath)
{
    public string Name => System.IO.Path.GetFileName(Path.Replace('/', '\\'));
    public string Directory => System.IO.Path.GetDirectoryName(Path.Replace('/', '\\')) ?? "";
    public string Extension => System.IO.Path.GetExtension(Path).TrimStart('.').ToLowerInvariant();
    public bool IsPlayable => Kind is "video" or "audio";
    public bool IsTextSubtitle => SubtitleParser.SupportedExtensions.Contains(Extension);
    public string SizeText => Format.Bytes(Length);
}

public sealed record TorrentInfo(string InfoHash, string Name, long Length, List<TorrentFile> Files)
{
    /// <summary>Video (or, failing that, audio) files in natural order.</summary>
    public List<TorrentFile> PlayableFiles
    {
        get
        {
            var videos = Files.Where(f => f.Kind == "video").ToList();
            var list = videos.Count > 0 ? videos : Files.Where(f => f.IsPlayable).ToList();
            list.Sort((a, b) => NaturalCompare(a.Path, b.Path));
            return list;
        }
    }

    public List<TorrentFile> SubtitleFiles
    {
        get
        {
            var list = Files.Where(f => f.IsTextSubtitle).ToList();
            list.Sort((a, b) => NaturalCompare(a.Path, b.Path));
            return list;
        }
    }

    [System.Runtime.InteropServices.DllImport("shlwapi.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
    private static extern int StrCmpLogicalW(string a, string b);

    public static int NaturalCompare(string a, string b)
    {
        try { return StrCmpLogicalW(a, b); }
        catch { return string.Compare(a, b, StringComparison.OrdinalIgnoreCase); }
    }
}

public sealed record TorrentStatus(
    int Peers, int ActivePeers, int Seeders, double DownloadSpeed, double UploadSpeed,
    long BytesCompleted, long Length, long? FileCompleted, long? FileLength)
{
    public double? FileProgress => FileCompleted is { } done && FileLength is { } total && total > 0
        ? (double)done / total : null;
}

/// <summary>A subtitle track inside a Matroska file.</summary>
public sealed record MkvTrack(
    ulong Number, string Codec, string Language, string Name,
    [property: JsonPropertyName("default")] bool IsDefault, bool Forced, bool Text)
{
    public string DisplayName
    {
        get
        {
            var language = SubtitleLanguage.DisplayName(Language);
            var parts = new[] { language, Name }.Where(s => !string.IsNullOrWhiteSpace(s)).ToArray();
            return parts.Length == 0 ? $"Дорожка {Number}" : string.Join(" — ", parts);
        }
    }

    public string MatchText => $"{Language} {Name}";
}

public sealed class RecentItem
{
    public string Uri { get; set; } = "";
    public string Name { get; set; } = "";
    public DateTime Date { get; set; }
    public string DateText => Date.ToString("d");
}

public static class Format
{
    public static string Bytes(long value)
    {
        string[] units = { "Б", "КБ", "МБ", "ГБ", "ТБ" };
        double v = value;
        int i = 0;
        while (v >= 1024 && i < units.Length - 1) { v /= 1024; i++; }
        return i == 0 ? $"{value} {units[0]}" : $"{v:0.#} {units[i]}";
    }

    public static string Speed(double bytesPerSecond) => Bytes((long)bytesPerSecond) + "/с";

    public static string Time(long ms)
    {
        var total = Math.Max(0, ms / 1000);
        long h = total / 3600, m = total % 3600 / 60, s = total % 60;
        return h > 0 ? $"{h}:{m:00}:{s:00}" : $"{m}:{s:00}";
    }
}
