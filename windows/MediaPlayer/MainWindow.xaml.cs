using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Win32;

namespace MediaPlayerApp;

public partial class MainWindow : Window
{
    private readonly AppState state = AppState.Shared;
    private PlayerController Player => state.Player;

    private readonly DispatcherTimer hideTimer = new() { Interval = TimeSpan.FromMilliseconds(2800) };
    private bool controlsVisible = true;
    private bool seeking;
    private Point lastMouse;
    private bool fullScreen;
    private WindowState savedState;
    private WindowStyle savedStyle;
    private bool loadingStyle;
    private Window? overlayWindow;

    public MainWindow()
    {
        InitializeComponent();
        DataContext = state;
        state.PropertyChanged += State_PropertyChanged;
        Player.PropertyChanged += Player_PropertyChanged;
        state.Error += message => MessageBox.Show(this, message, "MediaPlayer", MessageBoxButton.OK, MessageBoxImage.Warning);
        hideTimer.Tick += (_, _) => HideControls();
        PreviewKeyDown += (_, e) => e.Handled = HandleKey(e.Key);
        Drop += (_, e) => HandleDrop(e);
        Overlay.Drop += (_, e) => HandleDrop(e);
        PlayerPanel.SizeChanged += (_, _) => ApplySubtitleStyle();
        InputBox.TextChanged += (_, _) => InputHint.Visibility = InputBox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        Loaded += (_, _) =>
        {
            if (Clipboard.ContainsText() && Clipboard.GetText().Trim().StartsWith("magnet:", StringComparison.OrdinalIgnoreCase))
                InputBox.Text = Clipboard.GetText().Trim();
        };
        ShowScreen();
    }

    // MARK: State → UI

    private void State_PropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(AppState.Screen) or nameof(AppState.CurrentTorrent)) ShowScreen();
        if (e.PropertyName is nameof(AppState.Status)) UpdateBuffering();
    }

    private void ShowScreen()
    {
        HomePanel.Visibility = state.Screen == Screen.Home ? Visibility.Visible : Visibility.Collapsed;
        LoadingPanel.Visibility = state.Screen == Screen.Loading ? Visibility.Visible : Visibility.Collapsed;
        FilesPanel.Visibility = state.Screen == Screen.Files ? Visibility.Visible : Visibility.Collapsed;
        PlayerPanel.Visibility = state.Screen == Screen.Player ? Visibility.Visible : Visibility.Collapsed;
        FilesList.ItemsSource = state.CurrentTorrent?.PlayableFiles;
        if (state.Screen == Screen.Player)
        {
            ShowControls();
            ApplySubtitleStyle();
            Overlay.Focus();
        }
        else
        {
            hideTimer.Stop();
            if (fullScreen) ToggleFullScreen();
        }
    }

    private void Player_PropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        switch (e.PropertyName)
        {
            case nameof(PlayerController.PrimaryText):
            case nameof(PlayerController.SecondaryText):
            case nameof(PlayerController.VlcRendersSubtitle):
                ApplySubtitleStyle();
                break;
            case nameof(PlayerController.IsPlaying):
                PlayButton.Content = Player.IsPlaying ? "" : "";
                if (!Player.IsPlaying) ShowControls();
                break;
            case nameof(PlayerController.IsMuted):
            case nameof(PlayerController.Volume):
                MuteButton.Content = Player.IsMuted || Player.Volume == 0 ? "" : "";
                break;
            case nameof(PlayerController.Rate):
                RateButton.Content = $"{Player.Rate:0.##}×";
                break;
            case nameof(PlayerController.Progress):
                if (!seeking) SeekSlider.Value = Player.Progress;
                break;
            case nameof(PlayerController.IsBuffering):
            case nameof(PlayerController.ErrorText):
                UpdateBuffering();
                break;
        }
    }

    private void UpdateBuffering()
    {
        var error = Player.ErrorText;
        BufferingBadge.Visibility = Player.IsBuffering || error != null ? Visibility.Visible : Visibility.Collapsed;
        BufferingText.Text = error ?? "Буферизация…";
        BufferingBar.Visibility = error == null ? Visibility.Visible : Visibility.Collapsed;
    }

    private static Brush SecondaryBrush(string name) => name switch
    {
        "white" => Brushes.White,
        "cyan" => new SolidColorBrush(Color.FromRgb(140, 230, 255)),
        "green" => new SolidColorBrush(Color.FromRgb(153, 255, 153)),
        _ => new SolidColorBrush(Color.FromRgb(255, 224, 77)),
    };

    /// <summary>Lays out both subtitle lines; sizes follow the video height.</summary>
    private void ApplySubtitleStyle()
    {
        var p = Prefs.Current;
        var scale = Math.Max(0.55, PlayerPanel.ActualHeight / 720);
        var size = p.SubFontSize * scale;
        var background = p.SubBackground ? new SolidColorBrush(Color.FromArgb(0x73, 0, 0, 0)) : Brushes.Transparent;
        var secondaryBrush = SecondaryBrush(p.SecondaryColor);
        var top = p.SecondaryPosition == "top";
        var primary = Player.PrimaryText;
        var secondary = Player.SecondaryText;

        PrimarySubText.FontSize = size;
        TopSubText.FontSize = StackedSubText.FontSize = size * 0.92;
        TopSubText.Foreground = StackedSubText.Foreground = secondaryBrush;
        TopSubBox.Background = StackedSubBox.Background = PrimarySubBox.Background = background;

        PrimarySubText.Text = primary ?? "";
        PrimarySubBox.Visibility = primary != null ? Visibility.Visible : Visibility.Collapsed;
        TopSubText.Text = top ? secondary ?? "" : "";
        TopSubBox.Visibility = top && secondary != null ? Visibility.Visible : Visibility.Collapsed;
        StackedSubText.Text = !top ? secondary ?? "" : "";
        StackedSubBox.Visibility = !top && secondary != null ? Visibility.Visible : Visibility.Collapsed;

        // Leave room for VLC's own (embedded) subtitle line at the bottom.
        var vlcLine = Player.VlcRendersSubtitle ? size * 2.8 : 0;
        var bottom = Math.Max(controlsVisible ? 110 : 0, 28 * scale + vlcLine);
        BottomSubs.Margin = new Thickness(0, 0, 0, bottom);
        TopSubBox.Margin = new Thickness(0, controlsVisible ? 64 : 24 * scale, 0, 0);
    }

    // MARK: Home

    private void InputBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) state.Open(InputBox.Text);
    }

    private void Watch_Click(object sender, RoutedEventArgs e) => state.Open(InputBox.Text);

    private void Paste_Click(object sender, RoutedEventArgs e)
    {
        if (!Clipboard.ContainsText()) return;
        InputBox.Text = Clipboard.GetText().Trim();
        state.Open(InputBox.Text);
    }

    private void OpenFile_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Title = "Откройте .torrent файл, видео или субтитры",
            Filter = "Торренты, видео и субтитры|*.torrent;*.mkv;*.mp4;*.avi;*.mov;*.webm;*.ts;*.m2ts;*.wmv;*.flv;*.mpg;*.mpeg;*.m4v;*.srt;*.ass;*.ssa;*.vtt|Все файлы|*.*",
        };
        if (dialog.ShowDialog(this) == true) state.OpenFile(dialog.FileName);
    }

    private void Settings_Click(object sender, RoutedEventArgs e) => new SettingsWindow { Owner = this }.ShowDialog();

    private void ClearRecents_Click(object sender, RoutedEventArgs e) => state.ClearRecents();

    private void Recent_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is RecentItem item) state.Open(item.Uri);
    }

    private void RecentCopy_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is RecentItem item) Clipboard.SetText(item.Uri);
    }

    private void RecentRemove_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is RecentItem item) state.RemoveRecent(item);
    }

    private void CancelLoading_Click(object sender, RoutedEventArgs e) => state.CancelLoading();

    private void Home_Click(object sender, RoutedEventArgs e) => state.GoHome();

    private void File_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is TorrentFile file) state.Play(file);
    }

    private void HandleDrop(DragEventArgs e)
    {
        if (e.Data.GetData(DataFormats.FileDrop) is string[] files)
        {
            foreach (var f in files) state.OpenFile(f);
        }
        else if (e.Data.GetData(DataFormats.UnicodeText) is string text)
        {
            state.Open(text);
        }
        e.Handled = true;
    }

    // MARK: Player

    private void VideoView_Loaded(object sender, RoutedEventArgs e)
    {
        VideoView.MediaPlayer = Player.Player;
    }

    private void Overlay_Loaded(object sender, RoutedEventArgs e)
    {
        Overlay.DataContext = state;
        // The overlay lives in its own window: forward its keys too.
        var w = Window.GetWindow(Overlay);
        if (w != null && w != this && w != overlayWindow)
        {
            overlayWindow = w;
            w.PreviewKeyDown += (_, args) => args.Handled = HandleKey(args.Key);
        }
    }

    private void Overlay_MouseMove(object sender, MouseEventArgs e)
    {
        var pos = e.GetPosition(Overlay);
        if (Math.Abs(pos.X - lastMouse.X) < 2 && Math.Abs(pos.Y - lastMouse.Y) < 2) return;
        lastMouse = pos;
        ShowControls();
    }

    private void Overlay_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource != Overlay) return;
        Player.TogglePlay();
        if (e.ClickCount == 2)
        {
            Player.TogglePlay(); // undo the first click's toggle
            ToggleFullScreen();
        }
        ShowControls();
    }

    private void ShowControls()
    {
        Overlay.Cursor = null;
        if (!controlsVisible)
        {
            controlsVisible = true;
            TopBar.Visibility = BottomBar.Visibility = Visibility.Visible;
            ApplySubtitleStyle();
        }
        hideTimer.Stop();
        hideTimer.Start();
    }

    private void HideControls()
    {
        hideTimer.Stop();
        if (!Player.IsPlaying || seeking || SubsPopup.IsOpen || BottomBar.IsMouseOver || TopBar.IsMouseOver) return;
        controlsVisible = false;
        TopBar.Visibility = BottomBar.Visibility = Visibility.Collapsed;
        Overlay.Cursor = Cursors.None;
        ApplySubtitleStyle();
    }

    private void Seek_DragStarted(object sender, MouseButtonEventArgs e) => seeking = true;

    private void Seek_DragCompleted(object sender, MouseButtonEventArgs e)
    {
        // Let the slider apply the click position first.
        Dispatcher.BeginInvoke(() =>
        {
            Player.SeekFraction(SeekSlider.Value);
            seeking = false;
        }, DispatcherPriority.Input);
    }

    private void ClosePlayer_Click(object sender, RoutedEventArgs e) => state.ClosePlayer();
    private void Play_Click(object sender, RoutedEventArgs e) => Player.TogglePlay();
    private void Back10_Click(object sender, RoutedEventArgs e) => Player.Jump(-10);
    private void Forward10_Click(object sender, RoutedEventArgs e) => Player.Jump(10);
    private void Next_Click(object sender, RoutedEventArgs e) => state.PlayNext();
    private void Mute_Click(object sender, RoutedEventArgs e) => Player.ToggleMute();
    private void FullScreen_Click(object sender, RoutedEventArgs e) => ToggleFullScreen();

    private void Audio_Click(object sender, RoutedEventArgs e)
    {
        var menu = new ContextMenu { PlacementTarget = AudioButton, Placement = PlacementMode.Top };
        if (Player.AudioTracks.Count == 0) menu.Items.Add(new MenuItem { Header = "Нет звуковых дорожек", IsEnabled = false });
        foreach (var track in Player.AudioTracks)
        {
            var item = new MenuItem { Header = track.Name, IsCheckable = true, IsChecked = track.Id == Player.CurrentAudioTrack };
            item.Click += (_, _) => Player.SelectAudioTrack(track.Id);
            menu.Items.Add(item);
        }
        menu.IsOpen = true;
    }

    private void Rate_Click(object sender, RoutedEventArgs e)
    {
        var menu = new ContextMenu { PlacementTarget = RateButton, Placement = PlacementMode.Top };
        foreach (var value in new[] { 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f })
        {
            var item = new MenuItem { Header = $"{value:0.##}×", IsCheckable = true, IsChecked = Math.Abs(Player.Rate - value) < 0.01 };
            item.Click += (_, _) => Player.SetRate(value);
            menu.Items.Add(item);
        }
        menu.IsOpen = true;
    }

    // MARK: Subtitle settings

    private void Subs_Click(object sender, RoutedEventArgs e)
    {
        loadingStyle = true;
        var p = Prefs.Current;
        PosTop.IsChecked = p.SecondaryPosition == "top";
        PosStacked.IsChecked = p.SecondaryPosition != "top";
        ColorBox.SelectedItem = ColorBox.Items.OfType<ComboBoxItem>().FirstOrDefault(i => (string)i.Tag == p.SecondaryColor) ?? ColorBox.Items[0];
        SizeSlider.Value = p.SubFontSize;
        SizeLabel.Text = ((int)p.SubFontSize).ToString();
        BgCheck.IsChecked = p.SubBackground;
        loadingStyle = false;
        SubsPopup.IsOpen = !SubsPopup.IsOpen;
    }

    private void SubStyle_Changed(object sender, RoutedEventArgs e)
    {
        if (loadingStyle || !IsInitialized || SizeLabel == null) return;
        var p = Prefs.Current;
        p.SecondaryPosition = PosTop.IsChecked == true ? "top" : "stacked";
        p.SecondaryColor = (ColorBox.SelectedItem as ComboBoxItem)?.Tag as string ?? "yellow";
        p.SubFontSize = SizeSlider.Value;
        p.SubBackground = BgCheck.IsChecked == true;
        SizeLabel.Text = ((int)p.SubFontSize).ToString();
        p.Save();
        ApplySubtitleStyle();
    }

    private void Delay1Minus_Click(object sender, RoutedEventArgs e) => Player.AdjustDelay(SubtitleSlot.Primary, -0.1);
    private void Delay1Plus_Click(object sender, RoutedEventArgs e) => Player.AdjustDelay(SubtitleSlot.Primary, 0.1);
    private void Delay2Minus_Click(object sender, RoutedEventArgs e) => Player.AdjustDelay(SubtitleSlot.Secondary, -0.1);
    private void Delay2Plus_Click(object sender, RoutedEventArgs e) => Player.AdjustDelay(SubtitleSlot.Secondary, 0.1);
    private void SwapSubs_Click(object sender, RoutedEventArgs e) => Player.SwapSlots();

    private void LoadSubs_Click(object sender, RoutedEventArgs e)
    {
        SubsPopup.IsOpen = false;
        var dialog = new OpenFileDialog
        {
            Title = "Выберите файлы субтитров",
            Filter = "Субтитры|*.srt;*.ass;*.ssa;*.vtt",
            Multiselect = true,
        };
        if (dialog.ShowDialog(this) == true)
        {
            foreach (var f in dialog.FileNames) Player.AddSubtitleFile(f);
        }
    }

    // MARK: Window

    private void ToggleFullScreen()
    {
        if (!fullScreen)
        {
            savedState = WindowState;
            savedStyle = WindowStyle;
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            if (WindowState == WindowState.Maximized) WindowState = WindowState.Normal;
            WindowState = WindowState.Maximized;
        }
        else
        {
            WindowStyle = savedStyle;
            ResizeMode = ResizeMode.CanResize;
            WindowState = savedState;
        }
        fullScreen = !fullScreen;
    }

    /// <summary>Player hotkeys; virtual keys work with any keyboard layout.</summary>
    private bool HandleKey(Key key)
    {
        if (state.Screen != Screen.Player) return false;
        if (Keyboard.FocusedElement is TextBox) return false;
        if (Keyboard.Modifiers.HasFlag(ModifierKeys.Control) || Keyboard.Modifiers.HasFlag(ModifierKeys.Alt)) return false;
        var shift = Keyboard.Modifiers.HasFlag(ModifierKeys.Shift);
        switch (key)
        {
            case Key.Space: Player.TogglePlay(); break;
            case Key.Left: Player.Jump(shift ? -60 : -10); break;
            case Key.Right: Player.Jump(shift ? 60 : 10); break;
            case Key.Up: Player.ChangeVolume(5); break;
            case Key.Down: Player.ChangeVolume(-5); break;
            case Key.F:
            case Key.Enter:
            case Key.F11: ToggleFullScreen(); break;
            case Key.Escape:
                if (fullScreen) ToggleFullScreen(); else return false;
                break;
            case Key.M: Player.ToggleMute(); break;
            case Key.S: Player.Cycle(SubtitleSlot.Primary); break;
            case Key.D: Player.Cycle(SubtitleSlot.Secondary); break;
            case Key.Z: Player.AdjustDelay(SubtitleSlot.Primary, -0.1); break;
            case Key.X: Player.AdjustDelay(SubtitleSlot.Primary, 0.1); break;
            case Key.C: Player.AdjustDelay(SubtitleSlot.Secondary, -0.1); break;
            case Key.V: Player.AdjustDelay(SubtitleSlot.Secondary, 0.1); break;
            case Key.N: state.PlayNext(); break;
            case Key.OemOpenBrackets: Player.SetRate(Math.Max(0.25f, Player.Rate - 0.25f)); break;
            case Key.OemCloseBrackets: Player.SetRate(Math.Min(4f, Player.Rate + 0.25f)); break;
            case Key.Oem5:
            case Key.OemBackslash: Player.SetRate(1f); break;
            case Key.Back: state.ClosePlayer(); break;
            default: return false;
        }
        ShowControls();
        return true;
    }
}
