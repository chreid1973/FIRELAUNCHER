package com.threehpm.firelauncher

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.util.Locale

enum class WallpaperSource(val prefValue: String, val label: String) {
    NASA("nasa", "NASA"),
    TMDB_TRENDING("tmdb_trending", "TMDB Trending"),
    TMDB_TRENDING_MOVIES("tmdb_trending_movies", "TMDB Trending Movies"),
    TMDB_TRENDING_TV("tmdb_trending_tv", "TMDB Trending TV"),
    TMDB_POPULAR_MOVIES("tmdb_popular_movies", "TMDB Popular Movies"),
    TMDB_POPULAR_TV("tmdb_popular_tv", "TMDB Popular TV"),
    REDDIT_EARTHPORN("reddit_earthporn", "Reddit EarthPorn"),
    LOCAL_FOLDER("local_folder", "Local Folder"),
    FIXED_IMAGE("fixed_image", "Fixed Image")
}

object WallpaperPrefs {
    const val PREF_SOURCE = "wallpaper_source"
    const val PREF_INTERVAL_MS = "wallpaper_interval_ms"
    const val PREF_NASA_API_KEY = "wallpaper_nasa_api_key"
    const val PREF_SHUFFLE = "wallpaper_shuffle"
    const val PREF_CHANGE_ON_OPEN = "wallpaper_change_on_open"
    const val PREF_FIXED_PATH = "fixed_wallpaper_path"
    const val PREF_REFRESH_TOKEN = "wallpaper_refresh_token"
    const val PREF_HOME_REFRESH_TOKEN = "home_refresh_token"
    const val DEFAULT_INTERVAL_MS = 30 * 60_000L
    const val DEFAULT_NASA_API_KEY = "DEMO_KEY"

    const val PREFS_NAME = "launcher_state"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSource(context: Context): WallpaperSource {
        val storedValue = prefs(context).getString(PREF_SOURCE, WallpaperSource.NASA.prefValue)
        return WallpaperSource.values().firstOrNull { it.prefValue == storedValue }
            ?: WallpaperSource.NASA
    }

    fun setSource(context: Context, source: WallpaperSource) {
        prefs(context).edit().putString(PREF_SOURCE, source.prefValue).apply()
    }

    fun getIntervalMs(context: Context): Long {
        return prefs(context).getLong(PREF_INTERVAL_MS, DEFAULT_INTERVAL_MS)
    }

    fun setIntervalMs(context: Context, intervalMs: Long) {
        prefs(context).edit().putLong(PREF_INTERVAL_MS, intervalMs).apply()
    }

    fun getStoredNasaApiKey(context: Context): String? {
        return prefs(context)
            .getString(PREF_NASA_API_KEY, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun getNasaApiKey(context: Context): String {
        return getStoredNasaApiKey(context) ?: DEFAULT_NASA_API_KEY
    }

    fun setNasaApiKey(context: Context, apiKey: String?) {
        prefs(context).edit()
            .putString(PREF_NASA_API_KEY, apiKey?.trim()?.takeIf { it.isNotBlank() })
            .apply()
    }

    fun isShuffleEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_SHUFFLE, true)
    }

    fun setShuffleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_SHUFFLE, enabled).apply()
    }

    fun isChangeOnOpenEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_CHANGE_ON_OPEN, true)
    }

    fun setChangeOnOpenEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_CHANGE_ON_OPEN, enabled).apply()
    }

    fun getFixedPath(context: Context): String? {
        return prefs(context).getString(PREF_FIXED_PATH, null)
    }

    fun setFixedPath(context: Context, path: String) {
        prefs(context).edit().putString(PREF_FIXED_PATH, path).apply()
    }

    fun getRefreshToken(context: Context): Long {
        return prefs(context).getLong(PREF_REFRESH_TOKEN, 0L)
    }

    fun touchRefreshToken(context: Context) {
        prefs(context).edit().putLong(PREF_REFRESH_TOKEN, System.currentTimeMillis()).apply()
    }

    fun getHomeRefreshToken(context: Context): Long {
        return prefs(context).getLong(PREF_HOME_REFRESH_TOKEN, 0L)
    }

    fun touchHomeRefreshToken(context: Context) {
        prefs(context).edit().putLong(PREF_HOME_REFRESH_TOKEN, System.currentTimeMillis()).apply()
    }

    fun formatInterval(intervalMs: Long): String {
        return when (intervalMs) {
            5 * 60_000L -> "5 minutes"
            15 * 60_000L -> "15 minutes"
            30 * 60_000L -> "30 minutes"
            60 * 60_000L -> "1 hour"
            24 * 60 * 60_000L -> "Daily"
            else -> "${intervalMs / 60_000L} minutes"
        }
    }

    fun buildSummary(context: Context): String {
        val parts = mutableListOf(
            getSource(context).label,
            formatInterval(getIntervalMs(context))
        )
        if (getSource(context) == WallpaperSource.NASA) {
            parts += if (getStoredNasaApiKey(context) != null) "Custom API Key" else "DEMO_KEY"
        }
        if (isShuffleEnabled(context)) {
            parts += "Shuffle"
        }
        if (isChangeOnOpenEnabled(context)) {
            parts += "Change On Open"
        }
        return parts.joinToString(" • ")
    }

    fun getLocalWallpaperFolder(): File {
        return File(Environment.getExternalStorageDirectory(), "3HPMLounge/wallpapers").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    fun loadLocalWallpaperFiles(): List<File> {
        val allowedExtensions = setOf("jpg", "jpeg", "png", "webp")
        return getLocalWallpaperFolder()
            .listFiles()
            ?.filter { file ->
                file.isFile && allowedExtensions.contains(file.extension.lowercase(Locale.getDefault()))
            }
            ?.sortedBy { it.name.lowercase(Locale.getDefault()) }
            .orEmpty()
    }

    fun getStoragePermission(): String? {
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> null
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_IMAGES
            else -> Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }
}
