using System.IO;
using System.Text.Json;

namespace MediaPlayerApp;

/// <summary>User settings, stored as JSON in %APPDATA%\MediaPlayer.</summary>
public sealed class Prefs
{
    public int NetworkCaching { get; set; } = 3000;
    public bool KeepDownloads { get; set; }
    public bool AutoSubtitles { get; set; } = true;
    public string SubLang1 { get; set; } = "ru";
    public string SubLang2 { get; set; } = "en";
    public double SubFontSize { get; set; } = 34;
    public string SecondaryPosition { get; set; } = "top";
    public string SecondaryColor { get; set; } = "yellow";
    public bool SubBackground { get; set; } = true;
    public double Volume { get; set; } = 100;
    public List<RecentItem> Recents { get; set; } = new();

    private static string FilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "MediaPlayer", "settings.json");

    public static Prefs Current { get; } = Load();

    private static Prefs Load()
    {
        try
        {
            if (File.Exists(FilePath)) return JsonSerializer.Deserialize<Prefs>(File.ReadAllText(FilePath)) ?? new Prefs();
        }
        catch { /* corrupt file: start fresh */ }
        return new Prefs();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { /* read-only profile */ }
    }
}
