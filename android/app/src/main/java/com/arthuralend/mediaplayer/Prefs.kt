package com.arthuralend.mediaplayer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** User settings in SharedPreferences. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    var networkCaching: Int
        get() = sp.getInt("networkCaching", 4000)
        set(v) = sp.edit().putInt("networkCaching", v).apply()
    var keepDownloads: Boolean
        get() = sp.getBoolean("keepDownloads", false)
        set(v) = sp.edit().putBoolean("keepDownloads", v).apply()
    var autoSubtitles: Boolean
        get() = sp.getBoolean("autoSubtitles", true)
        set(v) = sp.edit().putBoolean("autoSubtitles", v).apply()
    var subLang1: String
        get() = sp.getString("subLang1", "ru") ?: "ru"
        set(v) = sp.edit().putString("subLang1", v).apply()
    var subLang2: String
        get() = sp.getString("subLang2", "en") ?: "en"
        set(v) = sp.edit().putString("subLang2", v).apply()
    var subFontSize: Float
        get() = sp.getFloat("subFontSize", 20f)
        set(v) = sp.edit().putFloat("subFontSize", v).apply()
    var secondaryPosition: String
        get() = sp.getString("secondaryPosition", "top") ?: "top"
        set(v) = sp.edit().putString("secondaryPosition", v).apply()
    var secondaryColor: String
        get() = sp.getString("secondaryColor", "yellow") ?: "yellow"
        set(v) = sp.edit().putString("secondaryColor", v).apply()
    var subBackground: Boolean
        get() = sp.getBoolean("subBackground", true)
        set(v) = sp.edit().putBoolean("subBackground", v).apply()

    var recents: List<RecentItem>
        get() {
            val arr = try { JSONArray(sp.getString("recents", "[]")) } catch (_: Exception) { JSONArray() }
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                RecentItem(o.getString("uri"), o.getString("name"), o.optLong("date"))
            }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("date", it.date)) }
            sp.edit().putString("recents", arr.toString()).apply()
        }
}
