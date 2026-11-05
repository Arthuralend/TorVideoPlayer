using System.Diagnostics;
using System.IO;
using System.Windows;

namespace MediaPlayerApp;

public partial class SettingsWindow : Window
{
    private sealed record LanguageItem(string Code, string Name);

    public SettingsWindow()
    {
        InitializeComponent();
        var languages = new List<LanguageItem> { new("none", "Не выбирать") };
        languages.AddRange(SubtitleLanguage.All.Select(l => new LanguageItem(l.Code, l.Name)));
        Lang1.ItemsSource = languages;
        Lang2.ItemsSource = languages;

        var p = Prefs.Current;
        AutoSubs.IsChecked = p.AutoSubtitles;
        Lang1.SelectedValue = p.SubLang1;
        Lang2.SelectedValue = p.SubLang2;
        Cache.Value = p.NetworkCaching;
        KeepDownloads.IsChecked = p.KeepDownloads;
        Cache.ValueChanged += (_, _) => UpdateCacheLabel();
        UpdateCacheLabel();
    }

    private void UpdateCacheLabel() => CacheLabel.Text = $"{Cache.Value / 1000:0.0} с";

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        var p = Prefs.Current;
        p.AutoSubtitles = AutoSubs.IsChecked == true;
        p.SubLang1 = Lang1.SelectedValue as string ?? "none";
        p.SubLang2 = Lang2.SelectedValue as string ?? "none";
        p.NetworkCaching = (int)Cache.Value;
        p.KeepDownloads = KeepDownloads.IsChecked == true;
        p.Save();
        DialogResult = true;
    }

    private void OpenDownloads_Click(object sender, RoutedEventArgs e)
    {
        Directory.CreateDirectory(TorrentEngine.DownloadsDirectory);
        Process.Start(new ProcessStartInfo("explorer.exe", TorrentEngine.DownloadsDirectory) { UseShellExecute = true });
    }

    private void Associate_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Associations.Register();
            MessageBox.Show(this, "Готово. Magnet-ссылки и .torrent файлы теперь открываются в MediaPlayer.\n\nЕсли Windows спросит, каким приложением открывать, выберите MediaPlayer.",
                "MediaPlayer", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "MediaPlayer", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }
}
