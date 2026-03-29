package com.threehpm.firelauncher

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LauncherSetupBackup {
    const val MIME_TYPE = "application/json"

    private const val PREF_FAVORITE_PACKAGES = "favorite_packages"
    private const val PREF_HIDDEN_PACKAGES = "hidden_packages"
    private const val PREF_CATEGORY_ASSIGNMENTS = "category_assignments"
    private const val PREF_CATEGORY_TITLE_OVERRIDES = "category_title_overrides"
    private const val PREF_CUSTOM_CATEGORIES = "custom_categories"

    fun defaultFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "firelauncher-setup-$timestamp.json"
    }

    @Throws(IOException::class)
    fun exportToUri(context: Context, uri: Uri): ExportSummary {
        val payload = buildExportJson(context).toString(2)
        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(payload)
        } ?: throw IOException("Could not open export destination.")

        val prefs = context.getSharedPreferences(WallpaperPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        return ExportSummary(
            favorites = readStoredJsonArray(prefs, PREF_FAVORITE_PACKAGES).length(),
            hiddenApps = readStoredJsonArray(prefs, PREF_HIDDEN_PACKAGES).length(),
            customCategories = readStoredJsonArray(prefs, PREF_CUSTOM_CATEGORIES).length()
        )
    }

    @Throws(IOException::class)
    fun importFromUri(context: Context, uri: Uri): ImportSummary {
        val payload = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
            it.readText()
        } ?: throw IOException("Could not open setup backup.")

        val root = JSONObject(payload)
        val wallpaper = root.optJSONObject("wallpaper")
        val home = root.optJSONObject("home")
        val prefs = context.getSharedPreferences(WallpaperPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        if (wallpaper != null) {
            val sourceValue = wallpaper.optString("source")
            val wallpaperSource = WallpaperSource.values().firstOrNull { it.prefValue == sourceValue }
            if (wallpaperSource != null) {
                editor.putString(WallpaperPrefs.PREF_SOURCE, wallpaperSource.prefValue)
            }

            val intervalMs = wallpaper.optLong("interval_ms", WallpaperPrefs.DEFAULT_INTERVAL_MS)
            editor.putLong(WallpaperPrefs.PREF_INTERVAL_MS, intervalMs)
            editor.putBoolean(
                WallpaperPrefs.PREF_SHUFFLE,
                wallpaper.optBoolean("shuffle", true)
            )
            editor.putBoolean(
                WallpaperPrefs.PREF_CHANGE_ON_OPEN,
                wallpaper.optBoolean("change_on_open", true)
            )

            val nasaApiKey = wallpaper.optString("nasa_api_key").trim()
            if (nasaApiKey.isNotBlank()) {
                editor.putString(WallpaperPrefs.PREF_NASA_API_KEY, nasaApiKey)
            } else {
                editor.remove(WallpaperPrefs.PREF_NASA_API_KEY)
            }

            val fixedImagePath = wallpaper.optString("fixed_image_path").trim()
            if (fixedImagePath.isNotBlank()) {
                editor.putString(WallpaperPrefs.PREF_FIXED_PATH, fixedImagePath)
            } else {
                editor.remove(WallpaperPrefs.PREF_FIXED_PATH)
            }
        }

        val weather = root.optJSONObject("weather")
        if (weather != null) {
            val unit = WeatherUnit.fromPref(weather.optString("unit"))
            editor.putString(WeatherPrefs.PREF_UNIT, unit.prefValue)
            val query = weather.optString("query").trim()
            if (query.isNotBlank()) {
                editor.putString(WeatherPrefs.PREF_QUERY, query)
            } else {
                editor.remove(WeatherPrefs.PREF_QUERY)
            }
            editor.remove(WeatherPrefs.PREF_LOCATION)
            editor.remove(WeatherPrefs.PREF_TEMPERATURE)
            editor.remove(WeatherPrefs.PREF_SUMMARY)
        }

        if (home != null) {
            editor.putString(
                PREF_FAVORITE_PACKAGES,
                home.optJSONArray("favorites")?.toString() ?: JSONArray().toString()
            )
            editor.putString(
                PREF_HIDDEN_PACKAGES,
                home.optJSONArray("hidden")?.toString() ?: JSONArray().toString()
            )
            editor.putString(
                PREF_CATEGORY_ASSIGNMENTS,
                home.optJSONObject("category_assignments")?.toString() ?: JSONObject().toString()
            )
            editor.putString(
                PREF_CATEGORY_TITLE_OVERRIDES,
                home.optJSONObject("category_titles")?.toString() ?: JSONObject().toString()
            )
            editor.putString(
                PREF_CUSTOM_CATEGORIES,
                home.optJSONArray("custom_categories")?.toString() ?: JSONArray().toString()
            )
        }

        editor.apply()
        WallpaperPrefs.touchRefreshToken(context)
        WallpaperPrefs.touchHomeRefreshToken(context)

        return ImportSummary(
            favorites = home?.optJSONArray("favorites")?.length() ?: 0,
            hiddenApps = home?.optJSONArray("hidden")?.length() ?: 0,
            customCategories = home?.optJSONArray("custom_categories")?.length() ?: 0,
            wallpaperSource = wallpaper?.optString("source").orEmpty()
        )
    }

    private fun buildExportJson(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(WallpaperPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("format_version", 1)
            put(
                "exported_at",
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
            )
            put("app", "FireLauncher")
            put(
                "wallpaper",
                JSONObject().apply {
                    put("source", WallpaperPrefs.getSource(context).prefValue)
                    put("interval_ms", WallpaperPrefs.getIntervalMs(context))
                    put("shuffle", WallpaperPrefs.isShuffleEnabled(context))
                    put("change_on_open", WallpaperPrefs.isChangeOnOpenEnabled(context))
                    WallpaperPrefs.getStoredNasaApiKey(context)?.let { put("nasa_api_key", it) }
                    WallpaperPrefs.getFixedPath(context)?.let { put("fixed_image_path", it) }
                }
            )
            put(
                "weather",
                JSONObject().apply {
                    put("unit", WeatherPrefs.getUnit(context).prefValue)
                    WeatherPrefs.getQuery(context)?.let { put("query", it) }
                }
            )
            put(
                "home",
                JSONObject().apply {
                    put("favorites", readStoredJsonArray(prefs, PREF_FAVORITE_PACKAGES))
                    put("hidden", readStoredJsonArray(prefs, PREF_HIDDEN_PACKAGES))
                    put(
                        "category_assignments",
                        readStoredJsonObject(prefs, PREF_CATEGORY_ASSIGNMENTS)
                    )
                    put(
                        "category_titles",
                        readStoredJsonObject(prefs, PREF_CATEGORY_TITLE_OVERRIDES)
                    )
                    put("custom_categories", readStoredJsonArray(prefs, PREF_CUSTOM_CATEGORIES))
                }
            )
        }
    }

    private fun readStoredJsonArray(
        prefs: android.content.SharedPreferences,
        key: String
    ): JSONArray {
        val raw = prefs.getString(key, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun readStoredJsonObject(
        prefs: android.content.SharedPreferences,
        key: String
    ): JSONObject {
        val raw = prefs.getString(key, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    data class ExportSummary(
        val favorites: Int,
        val hiddenApps: Int,
        val customCategories: Int
    )

    data class ImportSummary(
        val favorites: Int,
        val hiddenApps: Int,
        val customCategories: Int,
        val wallpaperSource: String
    )
}
