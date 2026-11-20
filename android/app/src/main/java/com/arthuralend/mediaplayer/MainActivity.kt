package com.arthuralend.mediaplayer

import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupWindow
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private enum class Screen { HOME, LOADING, FILES, PLAYER }

    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var player: PlayerController

    private var screen = Screen.HOME
    private var currentTorrent: TorrentInfo? = null
    private var currentFile: TorrentFile? = null
    private var status: TorrentStatus? = null
    private var addJob: Job? = null
    private var statusJob: Job? = null
    private var controlsVisible = true
    private var seeking = false
    private var resumeOnStart = false

    /** Android TV / TV box: remote control, no touchscreen. */
    private val isTv by lazy {
        (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /** Target of a seek made with the remote; applied once the key is released. */
    private var pendingSeekMs: Long? = null
    private val applyPendingSeek = Runnable {
        pendingSeekMs?.let { player.seekToMs(it) }
        pendingSeekMs = null
        seeking = false
        if (controlsVisible) pokeControls()
    }
    private var pendingBrowseRequest = 0

    private val videoLayout by lazy { findViewById<VLCVideoLayout>(R.id.videoLayout) }
    private val input by lazy { findViewById<EditText>(R.id.input) }
    private val seekBar by lazy { findViewById<SeekBar>(R.id.seekBar) }
    private val controls by lazy { findViewById<View>(R.id.controls) }
    private val primarySub by lazy { findViewById<TextView>(R.id.primarySub) }
    private val stackedSub by lazy { findViewById<TextView>(R.id.stackedSub) }
    private val topSub by lazy { findViewById<TextView>(R.id.topSub) }
    private val osd by lazy { findViewById<TextView>(R.id.osd) }
    private val bottomSubs by lazy { findViewById<View>(R.id.bottomSubs) }

    private var hidePending = false
    private var lastLoggedPrimary: String? = null
    private var lastLoggedSecondary: String? = null
    private val hideControls = Runnable {
        hidePending = false
        setControlsVisible(false)
        if (screen == Screen.PLAYER) setFullscreenPlayer(true)
    }
    private val hideOsd = Runnable { osd.visibility = View.GONE }

    /**
     * Subtitles live in their own transparent, touch-through window above the
     * activity: on many devices the video surface covers ordinary views drawn
     * over it, so text inside the activity window could end up hidden.
     */
    private val subtitlePopup: PopupWindow by lazy {
        val layer = findViewById<View>(R.id.subtitleLayer)
        (layer.parent as ViewGroup).removeView(layer)
        PopupWindow(layer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, false).apply {
            isTouchable = false
            isClippingEnabled = false
            animationStyle = 0
            setBackgroundDrawable(null)
        }
    }

    private fun updateSubtitleWindow() {
        val root = findViewById<View>(R.id.root)
        val wanted = screen == Screen.PLAYER && hasWindowFocusOrResumed && root.windowToken != null && root.width > 0
        if (!wanted) {
            if (subtitlePopup.isShowing) subtitlePopup.dismiss()
            return
        }
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        if (!subtitlePopup.isShowing) {
            subtitlePopup.width = root.width
            subtitlePopup.height = root.height
            subtitlePopup.showAtLocation(root, Gravity.NO_GRAVITY, loc[0], loc[1])
        } else {
            subtitlePopup.update(loc[0], loc[1], root.width, root.height)
        }
    }

    private var hasWindowFocusOrResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        TorrentEngine.init(this)
        ContentReader.init(this)
        getExternalFilesDir(null) // create the app folder in shared storage
        setContentView(R.layout.activity_main)
        // Resolve the subtitle views now: they move to their own window later.
        listOf(primarySub, stackedSub, topSub, bottomSubs)

        player = PlayerController(this)
        player.onChange = { renderPlayer() }
        player.onEnded = { playNext() }
        player.onOsd = { showOsd(it) }

        setupHome()
        setupPlayer()
        adaptLayout()
        // Follow size changes (rotation, fullscreen) with the subtitle window.
        findViewById<View>(R.id.root).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSubtitleWindow() }
        findViewById<Button>(R.id.btnFilesBack).setOnClickListener { goHome() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { cancelLoading() }

        scope.launch { try { TorrentEngine.ensureStarted() } catch (_: Exception) {} }
        if (BuildConfig.LT_ENGINE) scope.launch { try { TorrServerLT.ensureStarted() } catch (_: Exception) {} }
        showScreen(Screen.HOME)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        player.attach(videoLayout)
        if (resumeOnStart) { player.togglePlay(); resumeOnStart = false }
    }

    override fun onStop() {
        // Pause in the background; the torrent keeps downloading.
        if (player.isPlaying) { player.pause(); resumeOnStart = true }
        player.detach()
        super.onStop()
    }

    override fun onDestroy() {
        if (subtitlePopup.isShowing) subtitlePopup.dismiss()
        scope.cancel()
        player.onChange = null
        player.release()
        if (isFinishing) TorrentEngine.stop()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            // With a remote, Back first hides the controls, then leaves the player.
            Screen.PLAYER -> if (isTv && controlsVisible && player.hasMedia && player.isPlaying) setControlsVisible(false) else closePlayer()
            Screen.FILES -> goHome()
            Screen.LOADING -> cancelLoading()
            Screen.HOME -> super.onBackPressed()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        adaptLayout()
        if (screen == Screen.PLAYER) setFullscreenPlayer(true)
        renderPlayer()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && screen == Screen.PLAYER) setFullscreenPlayer(true)
        if (hasFocus) window.decorView.post { updateSubtitleWindow() }
    }

    override fun onResume() {
        super.onResume()
        hasWindowFocusOrResumed = true
        if (screen == Screen.PLAYER) window.decorView.post { setFullscreenPlayer(true) }
        window.decorView.post { updateSubtitleWindow() }
    }

    override fun onPause() {
        hasWindowFocusOrResumed = false
        updateSubtitleWindow()
        super.onPause()
    }

    // MARK: Intents

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { openUri(it) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                // Shared text may contain more than the link itself.
                val link = Regex("(magnet:\\?\\S+|https?://\\S+|acestream://\\S+)").find(text)?.value ?: text
                open(link)
            }
        }
    }

    private fun openUri(uri: Uri) {
        when (uri.scheme) {
            "magnet", "acestream", "http", "https" -> open(uri.toString())
            else -> openFile(uri)
        }
    }

    // MARK: Home

    private fun setupHome() {
        findViewById<Button>(R.id.btnWatch).setOnClickListener { open(input.text.toString()) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { open(input.text.toString()); true } else false
        }
        findViewById<Button>(R.id.btnPaste).setOnClickListener {
            val text = clipboardText()
            if (text.isNullOrBlank()) toast("Буфер обмена пуст") else { input.setText(text); open(text) }
        }
        findViewById<Button>(R.id.btnOpenFile).setOnClickListener { pickFile(REQUEST_OPEN_FILE) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.btnClearRecents).setOnClickListener { Prefs.recents = emptyList(); renderRecents() }
        renderRecents()
    }

    private fun clipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
    }

    private fun renderRecents() {
        val list = findViewById<LinearLayout>(R.id.recentsList)
        list.removeAllViews()
        val recents = Prefs.recents
        findViewById<View>(R.id.recentsBox).visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE
        val df = DateFormat.getDateInstance(DateFormat.SHORT)
        for (item in recents) {
            list.addView(listRow(item.name, df.format(Date(item.date))).apply {
                setOnClickListener { open(item.uri) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(item.name)
                        .setItems(arrayOf("Удалить из списка")) { _, _ ->
                            Prefs.recents = Prefs.recents.filter { it.uri != item.uri }
                            renderRecents()
                        }.show()
                    true
                }
            })
        }
    }

    private fun listRow(title: String, detail: String, sub: String? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.row_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            isClickable = true
            isFocusable = true
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.fg))
            textSize = 15f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        if (!sub.isNullOrEmpty()) texts.addView(TextView(this).apply {
            text = sub
            setTextColor(getColor(R.color.fg_dim))
            textSize = 12f
            maxLines = 1
        })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = detail
            setTextColor(getColor(R.color.fg_dim))
            textSize = 12f
            setPadding(dp(10), 0, 0, 0)
        })
        return row
    }

    // MARK: Opening things

    private fun open(raw: String) {
        val text = raw.trim().trim('"')
        if (text.isEmpty()) return
        hideKeyboard()
        val lower = text.lowercase()
        if (lower.startsWith("acestream://")) {
            // Ace Stream content IDs need the Ace Stream engine (its own P2P
            // network); if it is installed and running we play through it.
            val id = text.substring("acestream://".length).trim('/')
            playDirect(Uri.parse("http://127.0.0.1:6878/ace/getstream?id=$id"), "Ace Stream ${id.take(10)}…")
            return
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            val ext = Uri.parse(text).lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
            if (ext in VIDEO_EXTENSIONS || ext == "m3u8" || lower.contains("/ace/getstream")) {
                playDirect(Uri.parse(text), Uri.parse(text).lastPathSegment ?: text)
                return
            }
        }
        if (lower.startsWith("content://") || lower.startsWith("file://")) { openFile(Uri.parse(text)); return }
        addTorrent(text, text)
    }

    private fun openFile(uri: Uri) {
        val name = ContentReader.displayName(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        when {
            ext == "torrent" || contentResolver.getType(uri) == "application/x-bittorrent" -> scope.launch {
                try {
                    val file = ContentReader.copyToCache(uri, if (ext == "torrent") name else "$name.torrent")
                    addTorrent(file.path, null)
                } catch (e: Exception) { error(e.message ?: "Не удалось открыть файл") }
            }
            ext in SubtitleParser.SUPPORTED_EXTENSIONS ->
                if (player.hasMedia) player.addSubtitleFile(name, uri.toString())
                else error("Сначала откройте видео, затем добавьте субтитры.")
            else -> playDirect(uri, name)
        }
    }

    /** recentUri is what "Recent" will reopen; null for temporary copies. */
    private fun addTorrent(uri: String, recentUri: String?) {
        addJob?.cancel()
        leaveCurrentTorrent()
        findViewById<TextView>(R.id.loadingText).text = "Получение информации о торренте…"
        showScreen(Screen.LOADING)
        addJob = scope.launch {
            try {
                val info = TorrentEngine.add(uri)
                if (recentUri != null) rememberRecent(recentUri, info.name)
                currentTorrent = info
                val files = info.playableFiles
                if (files.size == 1) {
                    play(files[0])
                } else {
                    if (files.isEmpty()) error("В этом торренте нет видеофайлов.")
                    renderFiles()
                    showScreen(Screen.FILES)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showScreen(Screen.HOME)
                error(e.message ?: "Ошибка")
            }
        }
    }

    private fun cancelLoading() {
        addJob?.cancel()
        showScreen(Screen.HOME)
    }

    private fun rememberRecent(uri: String, name: String) {
        val list = Prefs.recents.filter { it.uri != uri }.toMutableList()
        list.add(0, RecentItem(uri, name, System.currentTimeMillis()))
        Prefs.recents = list.take(20)
        renderRecents()
    }

    private fun renderFiles() {
        val t = currentTorrent ?: return
        findViewById<TextView>(R.id.torrentName).text = t.name
        findViewById<TextView>(R.id.filesSummary).text =
            "${t.playableFiles.size} видео · ${t.subtitleFiles.size} файлов субтитров · ${Format.bytes(t.length)}"
        val list = findViewById<LinearLayout>(R.id.filesList)
        list.removeAllViews()
        for (f in t.playableFiles) {
            val title = (if (f == currentFile) "▶ " else "") + f.name
            list.addView(listRow(title, Format.bytes(f.length), f.directory).apply { tag = f.index; setOnClickListener { play(f) } })
        }
    }

    // MARK: Playback

    private fun play(file: TorrentFile) {
        val torrent = currentTorrent ?: return
        val url = TorrentEngine.streamUrl(file) ?: return
        currentFile = file
        val source = if (file.extension in MediaSource.MATROSKA_EXTENSIONS) TorrentEngine.subtitleSource(torrent, file) else null
        player.open(Uri.parse(url), file.name, subtitleCandidates(file, torrent), source)
        showScreen(Screen.PLAYER)
        startStatusPolling()
    }

    private fun playDirect(uri: Uri, title: String) {
        leaveCurrentTorrent()
        // The engine can read embedded subtitles of local MKV files by path.
        val path = if (uri.scheme == "file") uri.path else null
        val source = if (path != null && path.substringAfterLast('.', "").lowercase() in MediaSource.MATROSKA_EXTENSIONS) MediaSource.Local(path) else null
        player.open(uri, title, emptyList(), source)
        showScreen(Screen.PLAYER)
    }

    private val nextFile: TorrentFile?
        get() {
            val t = currentTorrent ?: return null
            val f = currentFile ?: return null
            val files = t.playableFiles
            val i = files.indexOfFirst { it.index == f.index }
            return if (i >= 0 && i + 1 < files.size) files[i + 1] else null
        }

    private fun playNext() {
        nextFile?.let { play(it) }
    }

    private fun closePlayer() {
        player.stop()
        val t = currentTorrent
        if (t != null && t.playableFiles.size > 1) {
            stopStatusPolling()
            renderFiles()
            showScreen(Screen.FILES)
        } else {
            goHome()
        }
    }

    private fun goHome() {
        player.stop()
        leaveCurrentTorrent()
        showScreen(Screen.HOME)
    }

    private fun leaveCurrentTorrent() {
        stopStatusPolling()
        currentTorrent?.let { t -> scope.launch { TorrentEngine.drop(t.infoHash) } }
        currentTorrent = null
        currentFile = null
    }

    private fun startStatusPolling() {
        stopStatusPolling()
        val t = currentTorrent ?: return
        val index = currentFile?.index
        statusJob = scope.launch {
            while (true) {
                status = try { TorrentEngine.status(t.infoHash, index) } catch (_: Exception) { status }
                delay(1000)
            }
        }
    }

    private fun stopStatusPolling() {
        statusJob?.cancel()
        statusJob = null
        status = null
    }

    /** Subtitle files from the torrent, those named like the video first. */
    private fun subtitleCandidates(file: TorrentFile, torrent: TorrentInfo): List<SubtitleFileOption> {
        val base = file.name.substringBeforeLast('.').lowercase()
        val subs = torrent.subtitleFiles
        val related = subs.filter { it.name.lowercase().startsWith(base) }
        // In a season pack show only the current episode's subtitles if any match.
        return related.ifEmpty { subs }.mapNotNull { sub ->
            val url = TorrentEngine.streamUrl(sub) ?: return@mapNotNull null
            val shown = if (sub.directory.isEmpty()) sub.name else "${sub.directory.substringAfterLast('/')}/${sub.name}"
            SubtitleFileOption("torrent:${sub.index}", shown, url)
        }
    }

    // MARK: Screens

    private fun showScreen(s: Screen) {
        screen = s
        findViewById<View>(R.id.homePanel).visibility = if (s == Screen.HOME) View.VISIBLE else View.GONE
        findViewById<View>(R.id.loadingPanel).visibility = if (s == Screen.LOADING) View.VISIBLE else View.GONE
        findViewById<View>(R.id.filesPanel).visibility = if (s == Screen.FILES) View.VISIBLE else View.GONE
        val inPlayer = s == Screen.PLAYER
        // The player stays laid out underneath the other screens: keep the
        // remote's focus out of its (hidden) buttons.
        findViewById<ViewGroup>(R.id.playerPanel).descendantFocusability =
            if (inPlayer) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setFullscreenPlayer(inPlayer)
        if (inPlayer) {
            setControlsVisible(true)
            if (isTv) findViewById<View>(R.id.btnPlay).requestFocus()
        } else {
            cancelPendingSeek()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            focusScreen(s)
        }
        findViewById<View>(R.id.root).post { updateSubtitleWindow() }
        renderPlayer()
    }

    /**
     * Hides the status and navigation bars in the player. Android brings them
     * back after dialogs, app switches and rotation, so this is re-applied
     * whenever the window regains focus.
     */
    private fun setFullscreenPlayer(on: Boolean) {
        @Suppress("DEPRECATION")
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            // Use the area around the camera cutout too.
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    if (on) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        window.setDecorFitsSystemWindowsCompat(!on)
        val controller = if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController else null
        if (controller != null) {
            if (on) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on)
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            else View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    /** Puts the remote's focus on something sensible on the screen just shown. */
    private fun focusScreen(s: Screen) {
        if (!isTv) return
        when (s) {
            Screen.HOME -> findViewById<View>(R.id.btnWatch).requestFocus()
            Screen.LOADING -> findViewById<View>(R.id.btnCancel).requestFocus()
            Screen.FILES -> {
                val list = findViewById<LinearLayout>(R.id.filesList)
                val current = (0 until list.childCount).map { list.getChildAt(it) }
                    .firstOrNull { (it.tag as? Int) == currentFile?.index }
                (current ?: list.getChildAt(0) ?: findViewById(R.id.btnFilesBack)).requestFocus()
            }
            Screen.PLAYER -> {}
        }
    }

    /** TV: room for overscan and no rotate button; wide screens: a centred home column. */
    private fun adaptLayout() {
        val wide = resources.configuration.screenWidthDp >= 720
        findViewById<View>(R.id.homeContent).let { v ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            val w = if (wide) dp(640) else ViewGroup.LayoutParams.MATCH_PARENT
            if (lp.width != w) { lp.width = w; lp.gravity = Gravity.CENTER_HORIZONTAL; v.layoutParams = lp }
        }
        if (isTv) {
            findViewById<View>(R.id.btnRotate).visibility = View.GONE
            findViewById<View>(R.id.controls).setPadding(dp(36), dp(20), dp(36), dp(20))
            findViewById<View>(R.id.filesPanel).setPadding(dp(48), dp(27), dp(48), dp(27))
        }
    }

    private fun android.view.Window.setDecorFitsSystemWindowsCompat(fits: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(fits)
    }

    // MARK: Player UI

    private fun setupPlayer() {
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { closePlayer() }
        findViewById<ImageButton>(R.id.btnPlay).setOnClickListener { player.togglePlay(); pokeControls() }
        findViewById<View>(R.id.btnBack10).setOnClickListener { player.jump(-10); pokeControls() }
        findViewById<View>(R.id.btnForward10).setOnClickListener { player.jump(10); pokeControls() }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { playNext() }
        findViewById<View>(R.id.btnSubs).setOnClickListener { showSubtitleDialog() }
        findViewById<ImageButton>(R.id.btnAudio).setOnClickListener { showAudioDialog() }
        findViewById<View>(R.id.btnRate).setOnClickListener { showRateDialog() }
        findViewById<ImageButton>(R.id.btnRotate).setOnClickListener {
            requestedOrientation =
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            pokeControls()
        }

        // Remote: left/right on the focused seek bar jump through the video.
        seekBar.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) keySeek(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1, event.repeatCount)
            true
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) findViewById<TextView>(R.id.timeText).text = Format.time(progress * player.lengthMs / 10000)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { seeking = true; handler.removeCallbacks(hideControls); hidePending = false }
            override fun onStopTrackingTouch(sb: SeekBar) {
                player.seekFraction(sb.progress / 10000.0)
                seeking = false
                pokeControls()
            }
        })

        // Tap: show/hide controls. Double tap: left third −10 s, right third +10 s, middle pause.
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controlsVisible) setControlsVisible(false) else pokeControls()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = findViewById<View>(R.id.touchLayer).width
                when {
                    e.x < w / 3f -> player.jump(-10)
                    e.x > w * 2 / 3f -> player.jump(10)
                    else -> player.togglePlay()
                }
                return true
            }
        })
        findViewById<View>(R.id.touchLayer).setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) v.performClick()
            detector.onTouchEvent(ev)
        }
        controls.setOnClickListener { setControlsVisible(false) }
        // Only the buttons themselves should take the remote's focus.
        controls.isFocusable = false
    }

    private fun scheduleHide() {
        hidePending = true
        handler.postDelayed(hideControls, if (isTv) 5000 else 3500)
    }

    // MARK: Remote control

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (screen == Screen.PLAYER && handlePlayerKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handlePlayerKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        // Media keys of the remote work whether or not the controls are shown.
        when (e.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (first) { player.togglePlay(); pokeControls() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (first && !player.isPlaying) { player.togglePlay(); pokeControls() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (first) { player.pause(); pokeControls() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (first) closePlayer()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                if (down) keySeek(1, e.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                if (down) keySeek(-1, e.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (first) playNext()
                return true
            }
            KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_YELLOW -> {
                if (first) showSubtitleDialog()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK, KeyEvent.KEYCODE_PROG_GREEN -> {
                if (first) showAudioDialog()
                return true
            }
        }
        if (!controlsVisible) {
            when (e.keyCode) {
                // Hidden controls: left/right seek, OK and up/down bring the controls back.
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) keySeek(if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1, e.repeatCount)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SPACE -> {
                    if (first) {
                        setControlsVisible(true)
                        findViewById<View>(R.id.btnPlay).requestFocus()
                    }
                    return true
                }
            }
            return false
        }
        // Any key keeps the controls on screen a little longer.
        if (down && player.isPlaying && !seeking) {
            handler.removeCallbacks(hideControls)
            scheduleHide()
        }
        return false
    }

    /** Seeks with the remote; holding the key speeds up. Applied after a short pause. */
    private fun keySeek(direction: Int, repeat: Int) {
        if (!player.hasMedia) return
        val step = when {
            repeat > 30 -> 60_000L
            repeat > 8 -> 30_000L
            else -> 10_000L
        }
        val length = player.lengthMs
        var target = (pendingSeekMs ?: player.timeMs) + direction * step
        target = if (length > 0) target.coerceIn(0, maxOf(0, length - 1000)) else maxOf(0, target)
        pendingSeekMs = target
        seeking = true
        handler.removeCallbacks(hideControls)
        hidePending = false
        findViewById<TextView>(R.id.timeText).text = Format.time(target)
        if (length > 0) seekBar.progress = (target * 10000 / length).toInt()
        val delta = (target - player.timeMs) / 1000
        showOsd((if (delta >= 0) "▶▶ +" else "◀◀ −") + Format.time(Math.abs(delta) * 1000) + "  ·  " + Format.time(target) +
            (if (length > 0) " / " + Format.time(length) else ""))
        handler.removeCallbacks(applyPendingSeek)
        handler.postDelayed(applyPendingSeek, 700)
    }

    private fun cancelPendingSeek() {
        handler.removeCallbacks(applyPendingSeek)
        if (pendingSeekMs != null) seeking = false
        pendingSeekMs = null
    }

    private fun pokeControls() {
        setControlsVisible(true)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        controls.visibility = if (visible) View.VISIBLE else View.GONE
        handler.removeCallbacks(hideControls)
        hidePending = false
        if (visible && player.isPlaying && !seeking) scheduleHide()
        renderSubtitles()
    }

    private fun renderPlayer() {
        if (screen != Screen.PLAYER) return
        // Keep the screen on while a video is playing.
        if (player.isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        findViewById<TextView>(R.id.playerTitle).text = player.title
        findViewById<TextView>(R.id.statusText).text = status?.text ?: ""
        val playBtn = findViewById<ImageButton>(R.id.btnPlay)
        val icon = if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        if (playBtn.tag != icon) { playBtn.setImageResource(icon); playBtn.tag = icon }
        if (!seeking) {
            findViewById<TextView>(R.id.timeText).text = Format.time(player.timeMs)
            seekBar.progress = if (player.lengthMs > 0) (player.timeMs * 10000 / player.lengthMs).toInt() else 0
        }
        findViewById<TextView>(R.id.lengthText).text = Format.time(player.lengthMs)
        findViewById<View>(R.id.btnNext).visibility = if (nextFile != null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.subsSummary).text = player.subtitleSummary
        findViewById<TextView>(R.id.btnRate).text = PlayerController.formatRate(player.rate)

        val error = player.errorText
        findViewById<View>(R.id.bufferingBadge).visibility = if (player.isBuffering || error != null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.bufferingText).text = error ?: "Буферизация…"
        findViewById<View>(R.id.bufferingSpinner).visibility = if (error == null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.bufferingStatus).text = status?.let { "Пиры: ${it.activePeers} · ${Format.speed(it.downloadSpeed)}" } ?: ""

        if (!player.isPlaying && !controlsVisible && player.hasMedia) setControlsVisible(true)
        else if (player.isPlaying && controlsVisible && !seeking && !hidePending) scheduleHide()
        renderSubtitles()
    }

    private fun secondaryColor(): Int = when (Prefs.secondaryColor) {
        "white" -> Color.WHITE
        "cyan" -> Color.rgb(140, 230, 255)
        "green" -> Color.rgb(153, 255, 153)
        else -> Color.rgb(255, 224, 77)
    }

    /** Lays out both subtitle lines; sizes follow the player height. */
    private fun renderSubtitles() {
        val heightDp = findViewById<View>(R.id.playerPanel).height / resources.displayMetrics.density
        val scale = (heightDp / 360f).coerceIn(0.8f, 2.2f)
        val size = Prefs.subFontSize * scale
        val bg = if (Prefs.subBackground) Color.argb(0x73, 0, 0, 0) else Color.TRANSPARENT
        val top = Prefs.secondaryPosition == "top"
        val p = player.primaryText
        val s = player.secondaryText

        fun TextView.show(text: String?, sizeSp: Float, color: Int) {
            visibility = if (text != null) View.VISIBLE else View.GONE
            if (text != null && this.text.toString() != text) this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            setBackgroundColor(bg)
        }
        if (p != lastLoggedPrimary || s != lastLoggedSecondary) {
            lastLoggedPrimary = p
            lastLoggedSecondary = s
            android.util.Log.i(PlayerController.TAG, "overlay primary=${p?.replace('\n', '|')} secondary=${s?.replace('\n', '|')} " +
                "shown=${primarySub.isShown} h=${primarySub.height} popup=${subtitlePopup.isShowing}")
        }
        primarySub.show(p, size, Color.WHITE)
        topSub.show(if (top) s else null, size * 0.92f, secondaryColor())
        stackedSub.show(if (!top) s else null, size * 0.92f, secondaryColor())

        // Leave room for VLC's own (embedded) subtitle line and for the controls.
        val vlcLine = if (player.vlcRendersSubtitle) size * 2.8f else 0f
        val bottom = maxOf(if (controlsVisible) 110f else 0f, 20f + vlcLine)
        (bottomSubs.layoutParams as FrameLayout.LayoutParams).let {
            if (it.bottomMargin != dp(bottom)) { it.bottomMargin = dp(bottom); bottomSubs.layoutParams = it }
        }
        (topSub.layoutParams as FrameLayout.LayoutParams).let {
            val m = dp(if (controlsVisible) 64f else 16f)
            if (it.topMargin != m) { it.topMargin = m; topSub.layoutParams = it }
        }
    }

    private fun showOsd(message: String) {
        osd.text = message
        osd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, 1600)
    }

    // MARK: Dialogs

    private fun showAudioDialog() {
        val tracks = player.audioTracks
        if (tracks.isEmpty()) { toast("Нет звуковых дорожек"); return }
        val checked = tracks.indexOfFirst { it.id == player.currentAudioTrack }
        AlertDialog.Builder(this)
            .setTitle("Звуковая дорожка")
            .setSingleChoiceItems(tracks.map { it.name }.toTypedArray(), checked) { d, which ->
                player.selectAudioTrack(tracks[which].id); d.dismiss()
            }.show()
    }

    private fun showRateDialog() {
        val rates = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        val checked = rates.indexOfFirst { Math.abs(it - player.rate) < 0.01 }
        AlertDialog.Builder(this)
            .setTitle("Скорость")
            .setSingleChoiceItems(rates.map { PlayerController.formatRate(it) }.toTypedArray(), checked) { d, which ->
                player.setRate(rates[which]); d.dismiss()
            }.show()
    }

    private fun showSubtitleDialog() {
        handler.removeCallbacks(hideControls)
        hidePending = false
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        lateinit var dialog: AlertDialog

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.fg_dim))
            setPadding(0, dp(12), 0, dp(4))
        }

        fun slotRow(slot: SubtitleSlot, title: String) {
            box.addView(label(title))
            val chooser = Button(this).apply {
                text = player.subtitleOptions.firstOrNull { it.source == player.sourceFor(slot) }?.name ?: "Выключены"
                isAllCaps = false
                setOnClickListener {
                    val options = player.subtitleOptions
                    val checked = options.indexOfFirst { it.source == player.sourceFor(slot) }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(title)
                        .setSingleChoiceItems(options.map { it.name }.toTypedArray(), checked) { d, which ->
                            player.select(options[which].source, slot)
                            text = options[which].name
                            d.dismiss()
                        }.show()
                }
            }
            box.addView(chooser)
            val delayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val delayText = TextView(this).apply { setTextColor(getColor(R.color.fg)); gravity = Gravity.CENTER; minWidth = dp(90) }
            fun updateDelay() {
                val d = if (slot == SubtitleSlot.PRIMARY) player.primaryDelay else player.secondaryDelay
                delayText.text = String.format("%+.1f с", d)
            }
            updateDelay()
            delayRow.addView(TextView(this).apply { text = "Задержка"; setTextColor(getColor(R.color.fg)) })
            delayRow.addView(Button(this).apply { text = "−"; setOnClickListener { player.adjustDelay(slot, -0.1); updateDelay() } })
            delayRow.addView(delayText)
            delayRow.addView(Button(this).apply { text = "+"; setOnClickListener { player.adjustDelay(slot, 0.1); updateDelay() } })
            box.addView(delayRow)
        }

        slotRow(SubtitleSlot.PRIMARY, "Субтитры 1 (белые, внизу)")
        slotRow(SubtitleSlot.SECONDARY, "Субтитры 2 (цветные)")

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "⇅ Поменять"; isAllCaps = false
            setOnClickListener { player.swapSlots(); dialog.dismiss(); showSubtitleDialog() }
        })
        actions.addView(Button(this).apply {
            text = "Из файла…"; isAllCaps = false
            setOnClickListener {
                dialog.dismiss()
                pickFile(REQUEST_OPEN_SUBTITLE)
            }
        })
        box.addView(actions)

        box.addView(label("Положение субтитров 2"))
        val positions = arrayOf("Вверху экрана", "Над субтитрами 1")
        box.addView(Button(this).apply {
            isAllCaps = false
            text = if (Prefs.secondaryPosition == "top") positions[0] else positions[1]
            setOnClickListener {
                Prefs.secondaryPosition = if (Prefs.secondaryPosition == "top") "stacked" else "top"
                text = if (Prefs.secondaryPosition == "top") positions[0] else positions[1]
                renderSubtitles()
            }
        })
        box.addView(label("Цвет субтитров 2"))
        val colors = listOf("yellow" to "Жёлтый", "cyan" to "Голубой", "green" to "Зелёный", "white" to "Белый")
        box.addView(Button(this).apply {
            isAllCaps = false
            text = colors.first { it.first == Prefs.secondaryColor }.second
            setOnClickListener {
                val i = (colors.indexOfFirst { it.first == Prefs.secondaryColor } + 1) % colors.size
                Prefs.secondaryColor = colors[i].first
                text = colors[i].second
                renderSubtitles()
            }
        })
        val sizeLabel = label("Размер: ${Prefs.subFontSize.toInt()}")
        box.addView(sizeLabel)
        box.addView(SeekBar(this).apply {
            max = 40
            progress = (Prefs.subFontSize - 12).toInt().coerceIn(0, 40)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    Prefs.subFontSize = 12f + value
                    sizeLabel.text = "Размер: ${Prefs.subFontSize.toInt()}"
                    renderSubtitles()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
        box.addView(android.widget.CheckBox(this).apply {
            text = "Тёмная подложка под текстом"
            isChecked = Prefs.subBackground
            setOnCheckedChangeListener { _, checked -> Prefs.subBackground = checked; renderSubtitles() }
        })

        dialog = AlertDialog.Builder(this)
            .setTitle("Двойные субтитры")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Готово", null)
            .setOnDismissListener { pokeControls() }
            .create()
        dialog.show()
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val auto = android.widget.CheckBox(this).apply { text = "Включать субтитры автоматически"; isChecked = Prefs.autoSubtitles }
        box.addView(auto)
        val langs = listOf("none" to "Не выбирать") + SubtitleLanguage.ALL.map { it.code to it.name }
        var lang1 = Prefs.subLang1
        var lang2 = Prefs.subLang2
        fun langButton(title: String, get: () -> String, set: (String) -> Unit) {
            box.addView(TextView(this).apply { text = title; setTextColor(getColor(R.color.fg_dim)); setPadding(0, dp(10), 0, 0) })
            box.addView(Button(this).apply {
                isAllCaps = false
                text = langs.firstOrNull { it.first == get() }?.second ?: get()
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle(title)
                        .setItems(langs.map { it.second }.toTypedArray()) { _, which ->
                            set(langs[which].first); text = langs[which].second
                        }.show()
                }
            })
        }
        langButton("Язык субтитров 1", { lang1 }, { lang1 = it })
        langButton("Язык субтитров 2", { lang2 }, { lang2 = it })
        val cacheLabel = TextView(this).apply { setTextColor(getColor(R.color.fg_dim)); setPadding(0, dp(14), 0, 0) }
        var caching = Prefs.networkCaching
        fun updateCache() { cacheLabel.text = String.format("Буфер VLC: %.1f с", caching / 1000.0) }
        updateCache()
        box.addView(cacheLabel)
        box.addView(SeekBar(this).apply {
            max = 28
            progress = ((caching - 1000) / 500).coerceIn(0, 28)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) { caching = 1000 + value * 500; updateCache() }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
        val keep = android.widget.CheckBox(this).apply {
            text = "Сохранять скачанные файлы (вступит в силу после перезапуска)"
            isChecked = Prefs.keepDownloads
        }
        box.addView(keep)
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        box.addView(TextView(this).apply {
            text = "О программе"
            setTextColor(getColor(R.color.fg_dim))
            setPadding(0, dp(18), 0, dp(4))
        })
        box.addView(TextView(this).apply {
            text = "${getString(R.string.app_name)} $version\nРазработчик: ${getString(R.string.developer)}\n${getString(R.string.developer_email)}"
            setTextColor(getColor(R.color.fg))
            setLineSpacing(0f, 1.15f)
        })
        box.addView(Button(this).apply {
            text = "Журнал торрент-движка"
            isAllCaps = false
            setOnClickListener { showEngineLog() }
        })
        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Сохранить") { _, _ ->
                Prefs.autoSubtitles = auto.isChecked
                Prefs.subLang1 = lang1
                Prefs.subLang2 = lang2
                Prefs.networkCaching = caching
                Prefs.keepDownloads = keep.isChecked
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // MARK: Picking files

    private fun acceptsFile(requestCode: Int, file: File): Boolean {
        val ext = file.extension.lowercase()
        return if (requestCode == REQUEST_OPEN_SUBTITLE) ext in SubtitleParser.SUPPORTED_EXTENSIONS
        else ext in VIDEO_EXTENSIONS || ext == "torrent" || ext in SubtitleParser.SUPPORTED_EXTENSIONS
    }

    /** The system picker opened by "Другое приложение…" or as the default on phones; false if none exists. */
    private fun launchSystemPicker(requestCode: Int): Boolean {
        val intents = listOf(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
        )
        for (intent in intents) {
            if (intent.resolveActivity(packageManager) == null) continue
            try { startActivityForResult(intent, requestCode); return true } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
        }
        return false
    }

    /**
     * Phones use the system picker; Android TV (which usually has none) and
     * phones without one get the built-in browser.
     */
    private fun pickFile(requestCode: Int) {
        if (!isTv && launchSystemPicker(requestCode)) return
        openBrowser(requestCode)
    }

    private fun storagePermissions(): Array<String> =
        if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf("android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO")
        else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun openBrowser(requestCode: Int) {
        val missing = storagePermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && pendingBrowseRequest == 0) {
            pendingBrowseRequest = requestCode
            requestPermissions(missing.toTypedArray(), REQUEST_STORAGE)
            return
        }
        pendingBrowseRequest = 0
        val hasSystemPicker = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .resolveActivity(packageManager) != null ||
            Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").resolveActivity(packageManager) != null
        FileBrowser(
            this,
            accept = { acceptsFile(requestCode, it) },
            onPick = { file -> onFilePicked(requestCode, Uri.fromFile(file)) },
            systemPicker = if (hasSystemPicker) ({ if (!launchSystemPicker(requestCode)) toast("Нет приложения для выбора файлов") }) else null,
        ).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE && pendingBrowseRequest != 0) {
            val request = pendingBrowseRequest
            // Show whatever is readable even if access was refused.
            openBrowser(request)
        }
    }

    private fun onFilePicked(requestCode: Int, uri: Uri) {
        when (requestCode) {
            REQUEST_OPEN_FILE -> {
                if (uri.scheme == "file") {
                    val name = ContentReader.displayName(uri)
                    if (name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS) rememberRecent(uri.toString(), name)
                }
                openFile(uri)
            }
            REQUEST_OPEN_SUBTITLE -> player.addSubtitleFile(ContentReader.displayName(uri), uri.toString())
        }
    }

    /** Shows the engine log: on a TV there is no adb at hand to see why a torrent won't start. */
    private fun showEngineLog() {
        val text = TorrentEngine.logTail().ifEmpty { "Журнал пуст." }
        val view = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.fg))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isFocusable = true
        }
        val scroll = ScrollView(this).apply { addView(view) }
        AlertDialog.Builder(this)
            .setTitle("Журнал торрент-движка")
            .setView(scroll)
            .setPositiveButton("Закрыть", null)
            .show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        onFilePicked(requestCode, uri)
    }

    // MARK: Helpers

    private fun error(message: String) {
        AlertDialog.Builder(this).setTitle(getString(R.string.app_name)).setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_OPEN_FILE = 1
        private const val REQUEST_OPEN_SUBTITLE = 2
        private const val REQUEST_STORAGE = 3
        val VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "m4v", "avi", "mov", "wmv", "webm", "ts", "m2ts", "mts", "mpg", "mpeg",
            "flv", "ogv", "3gp", "vob", "divx", "rmvb", "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav",
        )
    }
}
