using System.Globalization;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;

namespace MediaPlayerApp;

public sealed record SubtitleCue(double Start, double End, string Text);

/// <summary>
/// Subtitle lines rendered by our own overlay, so that two tracks can be
/// shown at once. Embedded MKV tracks are merged in as time windows arrive.
/// </summary>
public sealed class SubtitleTrack
{
    private readonly List<SubtitleCue> cues;
    private readonly HashSet<string> seen = new();

    public SubtitleTrack(IEnumerable<SubtitleCue> initial)
    {
        cues = new List<SubtitleCue>();
        Merge(initial);
    }

    public void Merge(IEnumerable<SubtitleCue> newCues)
    {
        var added = false;
        foreach (var cue in newCues)
        {
            if (seen.Add($"{cue.Start.ToString(CultureInfo.InvariantCulture)}|{cue.Text}"))
            {
                cues.Add(cue);
                added = true;
            }
        }
        if (added) cues.Sort((a, b) => a.Start.CompareTo(b.Start));
    }

    public string? TextAt(double time)
    {
        // Last cue that starts at or before `time`.
        int lo = 0, hi = cues.Count;
        while (lo < hi)
        {
            var mid = (lo + hi) / 2;
            if (cues[mid].Start <= time) lo = mid + 1; else hi = mid;
        }
        var lines = new List<string>();
        for (int i = lo - 1, scanned = 0; i >= 0 && scanned < 30; i--, scanned++)
        {
            if (cues[i].End > time) lines.Add(cues[i].Text);
        }
        if (lines.Count == 0) return null;
        lines.Reverse();
        return string.Join("\n", lines);
    }
}

public static class SubtitleParser
{
    public static readonly HashSet<string> SupportedExtensions = new(StringComparer.OrdinalIgnoreCase) { "srt", "vtt", "ass", "ssa" };

    static SubtitleParser() => Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);

    public static SubtitleTrack Parse(byte[] data, string extension)
    {
        var text = Decode(data);
        var ext = extension.TrimStart('.').ToLowerInvariant();
        var cues = ext is "ass" or "ssa" || text.Contains("[Events]") ? ParseAss(text) : ParseSrt(text);
        if (cues.Count == 0) cues = ParseAss(text);
        if (cues.Count == 0) throw new InvalidOperationException("В файле не найдено ни одной строки субтитров");
        return new SubtitleTrack(cues);
    }

    /// <summary>UTF-8/UTF-16 first, then Windows-1251 (old Russian .srt files).</summary>
    public static string Decode(byte[] data)
    {
        if (data.Length >= 2 && (data[0] == 0xFF && data[1] == 0xFE || data[0] == 0xFE && data[1] == 0xFF))
        {
            return (data[0] == 0xFF ? Encoding.Unicode : Encoding.BigEndianUnicode).GetString(data, 2, data.Length - 2);
        }
        try
        {
            var s = new UTF8Encoding(false, true).GetString(data);
            return s.TrimStart('﻿');
        }
        catch (DecoderFallbackException)
        {
            return Encoding.GetEncoding(1251).GetString(data);
        }
    }

    /// <summary>SRT and WebVTT.</summary>
    public static List<SubtitleCue> ParseSrt(string text)
    {
        var lines = text.Replace("\r\n", "\n").Replace('\r', '\n').Split('\n');
        var cues = new List<SubtitleCue>();
        var i = 0;
        while (i < lines.Length)
        {
            var line = lines[i];
            var arrow = line.IndexOf("-->", StringComparison.Ordinal);
            if (arrow < 0) { i++; continue; }
            var startText = line[..arrow].Trim();
            var endText = line[(arrow + 3)..].Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries).FirstOrDefault() ?? "";
            i++;
            var body = new List<string>();
            while (i < lines.Length && lines[i].Trim().Length > 0 && !lines[i].Contains("-->"))
            {
                body.Add(lines[i]);
                i++;
            }
            if (ParseTime(startText) is { } start && ParseTime(endText) is { } end && end > start)
            {
                var cleaned = Clean(string.Join("\n", body));
                if (cleaned.Length > 0) cues.Add(new SubtitleCue(start, end, cleaned));
            }
        }
        return cues;
    }

    /// <summary>ASS / SSA: reads the [Events] Format line to locate the fields.</summary>
    public static List<SubtitleCue> ParseAss(string text)
    {
        var fields = new List<string> { "layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text" };
        var cues = new List<SubtitleCue>();
        var inEvents = false;
        foreach (var raw in text.Split('\n'))
        {
            var line = raw.Trim();
            if (line.StartsWith('['))
            {
                inEvents = line.Equals("[events]", StringComparison.OrdinalIgnoreCase);
                continue;
            }
            if (!inEvents) continue;
            if (line.StartsWith("format:", StringComparison.OrdinalIgnoreCase))
            {
                fields = line[7..].Split(',').Select(f => f.Trim().ToLowerInvariant()).ToList();
                continue;
            }
            if (!line.StartsWith("dialogue:", StringComparison.OrdinalIgnoreCase)) continue;
            var parts = line[9..].Split(',', Math.Max(fields.Count, 2));
            int si = fields.IndexOf("start"), ei = fields.IndexOf("end"), ti = fields.IndexOf("text");
            if (si < 0 || ei < 0 || ti < 0 || parts.Length <= Math.Max(si, Math.Max(ei, ti))) continue;
            var body = parts[ti];
            if (body.Contains(@"\p1") || body.Contains(@"\p2")) continue;
            if (ParseTime(parts[si].Trim()) is not { } start || ParseTime(parts[ei].Trim()) is not { } end || end <= start) continue;
            var cleaned = Clean(body.Replace(@"\N", "\n").Replace(@"\n", "\n").Replace(@"\h", " "));
            if (cleaned.Length > 0) cues.Add(new SubtitleCue(start, end, cleaned));
        }
        return cues;
    }

    /// <summary>"HH:MM:SS,mmm", "HH:MM:SS.mmm", "MM:SS.mmm" and "H:MM:SS.cc".</summary>
    public static double? ParseTime(string text)
    {
        var parts = text.Replace(',', '.').Split(':');
        if (parts.Length is not (2 or 3)) return null;
        double total = 0;
        foreach (var part in parts)
        {
            if (!double.TryParse(part.Trim(), NumberStyles.Float, CultureInfo.InvariantCulture, out var v)) return null;
            total = total * 60 + v;
        }
        return total;
    }

    private static readonly Regex Tags = new(@"<[^>]*>|\{[^}]*\}", RegexOptions.Compiled);

    public static string Clean(string text)
    {
        var s = WebUtility.HtmlDecode(Tags.Replace(text, ""));
        return string.Join("\n", s.Split('\n').Select(l => l.Trim()).Where(l => l.Length > 0));
    }
}

public static class SubtitleLanguage
{
    public sealed record Language(string Code, string Name, string[] Keys);

    public static readonly Language[] All =
    {
        new("ru", "Русский", new[] { "ru", "rus", "russian", "рус", "русский", "russkiy" }),
        new("en", "English", new[] { "en", "eng", "english", "англ", "английский" }),
        new("uk", "Українська", new[] { "uk", "ukr", "ukrainian", "укр", "украинский", "українська" }),
        new("de", "Deutsch", new[] { "de", "ger", "deu", "german", "deutsch" }),
        new("fr", "Français", new[] { "fr", "fre", "fra", "french", "français", "francais" }),
        new("es", "Español", new[] { "es", "spa", "spanish", "español", "espanol" }),
        new("it", "Italiano", new[] { "ita", "italian", "italiano" }),
        new("pt", "Português", new[] { "pt", "por", "portuguese", "português", "portugues" }),
        new("pl", "Polski", new[] { "pl", "pol", "polish", "polski" }),
        new("tr", "Türkçe", new[] { "tr", "tur", "turkish", "türkçe" }),
        new("ja", "日本語", new[] { "ja", "jpn", "japanese", "日本語" }),
        new("zh", "中文", new[] { "zh", "chi", "zho", "chinese", "中文" }),
        new("ko", "한국어", new[] { "ko", "kor", "korean", "한국어" }),
    };

    public static bool Matches(string name, string code)
    {
        var language = All.FirstOrDefault(l => l.Code == code);
        if (language == null) return false;
        var tokens = Regex.Split(name.ToLowerInvariant(), @"[^\p{L}]+").Where(t => t.Length > 0);
        return tokens.Any(t => language.Keys.Contains(t));
    }

    /// <summary>"rus" / "ru-RU" → "Русский".</summary>
    public static string DisplayName(string code)
    {
        var b = code.ToLowerInvariant().Split('-')[0];
        var language = All.FirstOrDefault(l => l.Code == b || l.Keys.Contains(b));
        return language?.Name ?? (code == "und" ? "" : code);
    }

    public static bool IsForced(string name)
    {
        var lower = name.ToLowerInvariant();
        return lower.Contains("forced") || lower.Contains("форс") || lower.Contains("signs");
    }
}
