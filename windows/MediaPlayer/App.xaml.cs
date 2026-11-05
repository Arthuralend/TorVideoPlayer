using System.IO;
using System.IO.Pipes;
using System.Windows;

namespace MediaPlayerApp;

public partial class App : Application
{
    private const string PipeName = "MediaPlayer-open-v1";
    private Mutex? instanceMutex;

    protected override void OnStartup(StartupEventArgs e)
    {
        // One window: a second launch (magnet link clicked in a browser,
        // .torrent double-clicked) hands its arguments to the running app.
        instanceMutex = new Mutex(true, $"MediaPlayer-{Environment.UserName}", out var first);
        if (!first)
        {
            try
            {
                using var client = new NamedPipeClientStream(".", PipeName, PipeDirection.Out);
                client.Connect(2000);
                using var writer = new StreamWriter(client);
                foreach (var arg in e.Args) writer.WriteLine(arg);
            }
            catch { /* the other instance is closing */ }
            Shutdown();
            return;
        }

        base.OnStartup(e);
        DispatcherUnhandledException += (_, args) =>
        {
            LogCrash(args.Exception);
            MessageBox.Show($"Произошла ошибка:\n{args.Exception.Message}\n\nПодробности: {CrashLogPath}", "MediaPlayer",
                MessageBoxButton.OK, MessageBoxImage.Error);
            args.Handled = true;
        };
        AppDomain.CurrentDomain.UnhandledException += (_, args) => { if (args.ExceptionObject is Exception ex) LogCrash(ex); };

        var window = new MainWindow();
        MainWindow = window;
        window.Show();
        _ = TorrentEngine.Shared.EnsureStartedAsync().ContinueWith(_ => { });
        foreach (var arg in e.Args) AppState.Shared.Open(arg);
        ListenForArguments();
    }

    private async void ListenForArguments()
    {
        while (true)
        {
            try
            {
                using var server = new NamedPipeServerStream(PipeName, PipeDirection.In, 1);
                await server.WaitForConnectionAsync();
                using var reader = new StreamReader(server);
                string? line;
                while ((line = await reader.ReadLineAsync()) != null)
                {
                    if (line.Length > 0) AppState.Shared.Open(line);
                }
                if (MainWindow is { } w)
                {
                    if (w.WindowState == WindowState.Minimized) w.WindowState = WindowState.Normal;
                    w.Activate();
                }
            }
            catch
            {
                await Task.Delay(500);
            }
        }
    }

    private static string CrashLogPath => Path.Combine(TorrentEngine.DataDirectory, "crash.log");

    private static void LogCrash(Exception ex)
    {
        try
        {
            Directory.CreateDirectory(TorrentEngine.DataDirectory);
            File.AppendAllText(CrashLogPath, $"[{DateTime.Now:u}] {ex}\n\n");
        }
        catch { }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        if (MainWindow != null)
        {
            try { AppState.Shared.Player.Stop(); } catch { }
            TorrentEngine.Shared.Stop();
            Prefs.Current.Save();
        }
        base.OnExit(e);
    }
}
