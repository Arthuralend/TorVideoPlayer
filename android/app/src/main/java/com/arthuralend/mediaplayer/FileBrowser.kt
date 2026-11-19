package com.arthuralend.mediaplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import java.io.File

/**
 * A simple remote-friendly file browser. Android TV devices usually have no
 * system file picker (ACTION_OPEN_DOCUMENT), so videos, .torrent files and
 * subtitles from internal storage or a USB drive are picked here.
 */
class FileBrowser(
    private val activity: Activity,
    private val accept: (File) -> Boolean,
    private val onPick: (File) -> Unit,
    /** Offered as "Другое приложение…" when a system picker exists; null hides the button. */
    private val systemPicker: (() -> Unit)? = null,
) {
    private class Entry(val label: String, val file: File?, val isDir: Boolean)

    /** Shows the storage list, or [start] when it is still readable. */
    fun show(start: File? = Prefs.lastBrowseDir?.let { File(it) }) {
        if (start != null && start.isDirectory && start.canRead()) showDir(start) else showRoots()
    }

    private fun roots(): List<Pair<String, File>> {
        val list = ArrayList<Pair<String, File>>()
        val seen = HashSet<String>()
        fun add(name: String, dir: File?) {
            dir ?: return
            val path = try { dir.canonicalPath } catch (_: Exception) { dir.path }
            if (dir.isDirectory && seen.add(path)) list.add(name to dir)
        }
        add("Внутренняя память", Environment.getExternalStorageDirectory())
        // Removable storage and USB drives: strip ".../Android/data/<pkg>/files".
        activity.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            val root = dir.path.substringBefore("/Android/data/", "")
            if (root.isNotEmpty()) add("Накопитель ${File(root).name}", File(root))
        }
        File("/storage").listFiles()?.sortedBy { it.name }?.forEach {
            if (it.name != "self" && it.name != "emulated" && it.canRead()) add("Накопитель ${it.name}", it)
        }
        File("/mnt/media_rw").listFiles()?.forEach { if (it.canRead()) add("USB ${it.name}", it) }
        return list
    }

    private fun showRoots() {
        val roots = roots()
        val entries = roots.map { Entry("💾  ${it.first}", it.second, true) }
        present("Выберите накопитель", entries, null)
    }

    private fun showDir(dir: File) {
        val children = dir.listFiles()
        if (children == null) {
            Toast.makeText(activity, "Нет доступа к папке ${dir.path}", Toast.LENGTH_LONG).show()
            showRoots()
            return
        }
        Prefs.lastBrowseDir = dir.path
        val visible = children.filter { !it.name.startsWith(".") }
        val dirs = visible.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val files = visible.filter { it.isFile && accept(it) }.sortedBy { it.name.lowercase() }
        val entries = ArrayList<Entry>()
        entries.add(Entry("⬆  ..", dir.parentFile?.takeIf { it.canRead() && isInsideRoots(it) }, true))
        dirs.forEach { entries.add(Entry("📁  ${it.name}", it, true)) }
        files.forEach { entries.add(Entry("${it.name}   ·  ${Format.bytes(it.length())}", it, false)) }
        if (dirs.isEmpty() && files.isEmpty()) entries.add(Entry("(здесь нет видео, .torrent и субтитров)", null, false))
        present(dir.path, entries, dir)
    }

    private fun isInsideRoots(dir: File): Boolean {
        val path = dir.path
        return roots().any { path.startsWith(it.second.path) }
    }

    private fun present(title: String, entries: List<Entry>, current: File?) {
        val builder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(entries.map { it.label }.toTypedArray()) { _, which ->
                val e = entries[which]
                when {
                    e.isDir && e.file != null -> showDir(e.file)
                    e.isDir -> showRoots()
                    e.file != null -> onPick(e.file)
                    current != null -> showDir(current)
                }
            }
            .setNegativeButton("Закрыть", null)
        if (systemPicker != null) builder.setNeutralButton("Другое приложение…") { _, _ -> systemPicker.invoke() }
        if (needsAllFilesAccess()) {
            builder.setPositiveButton("Доступ ко всем файлам") { _, _ -> requestAllFilesAccess() }
        }
        builder.show()
    }

    /** Without "all files" access Android 11+ hides .torrent and subtitle files. */
    private fun needsAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager() && allFilesIntent() != null

    private fun allFilesIntent(): Intent? {
        if (Build.VERSION.SDK_INT < 30) return null
        val pm = activity.packageManager
        val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}"))
        if (own.resolveActivity(pm) != null) return own
        val all = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        return if (all.resolveActivity(pm) != null) all else null
    }

    private fun requestAllFilesAccess() {
        val intent = allFilesIntent() ?: return
        try { activity.startActivity(intent) } catch (_: Exception) {
            Toast.makeText(activity, "Не удалось открыть настройки доступа", Toast.LENGTH_LONG).show()
        }
    }
}
