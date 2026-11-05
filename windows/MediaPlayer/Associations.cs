using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace MediaPlayerApp;

/// <summary>Registers MediaPlayer for magnet: links and .torrent files (per user, no admin rights).</summary>
public static class Associations
{
    private const string ProgId = "MediaPlayer.Torrent";

    public static void Register()
    {
        var exe = Environment.ProcessPath ?? throw new InvalidOperationException("Не удалось определить путь к программе.");
        var command = $"\"{exe}\" \"%1\"";
        using var classes = Registry.CurrentUser.CreateSubKey(@"Software\Classes");

        using (var magnet = classes.CreateSubKey("magnet"))
        {
            magnet.SetValue("", "URL:Magnet link");
            magnet.SetValue("URL Protocol", "");
            using var icon = magnet.CreateSubKey("DefaultIcon");
            icon.SetValue("", $"\"{exe}\",0");
            using var cmd = magnet.CreateSubKey(@"shell\open\command");
            cmd.SetValue("", command);
        }
        using (var prog = classes.CreateSubKey(ProgId))
        {
            prog.SetValue("", "Торрент-файл");
            using var icon = prog.CreateSubKey("DefaultIcon");
            icon.SetValue("", $"\"{exe}\",0");
            using var cmd = prog.CreateSubKey(@"shell\open\command");
            cmd.SetValue("", command);
        }
        using (var ext = classes.CreateSubKey(".torrent"))
        {
            ext.SetValue("", ProgId);
            ext.SetValue("Content Type", "application/x-bittorrent");
            using var open = ext.CreateSubKey("OpenWithProgids");
            open.SetValue(ProgId, Array.Empty<byte>(), RegistryValueKind.None);
        }
        SHChangeNotify(0x08000000, 0, IntPtr.Zero, IntPtr.Zero); // SHCNE_ASSOCCHANGED
    }

    [DllImport("shell32.dll")]
    private static extern void SHChangeNotify(int eventId, int flags, IntPtr item1, IntPtr item2);
}
