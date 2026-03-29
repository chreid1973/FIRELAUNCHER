package com.threehpm.firelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.util.Locale

object IconPackCatalog {

    private const val ASSET_ROOT = "iconpack/640x360"
    private val bitmapCache = mutableMapOf<String, Bitmap>()

    private val packageMap = mapOf(
        "org.smarttube.stable" to "smarttube",
        "com.teamsmart.videomanager.tv" to "smarttube",
        "com.liskovsoft.smartyoutubetv2.tv" to "smarttube",
        "com.google.android.youtube.tv" to "youtube",
        "com.stremio.one" to "stremio",
        "flix.com.vision" to "flixvision",
        "ar.tvplayer.tv" to "tivi",
        "com.amazon.amazonvideo.livingroom" to "prime",
        "com.apple.atve.amazon.appletv" to "apple_tv",
        "com.apple.atve.androidtv.appletv" to "apple_tv",
        "org.adaway" to "adaway",
        "com.plexapp.android" to "plex",
        "tv.pluto.android" to "pluto",
        "com.spotify.tv.android" to "spotify",
        "com.esaba.downloader" to "downloader",
        "org.xbmc.kodi" to "kodi",
        "de.markusfisch.android.tvbro" to "tv_bro",
        "org.localsend.localsend_app" to "localsend"
    )

    private val aliasMap = mapOf(
        "smarttube" to "smarttube",
        "youtube" to "youtube",
        "stremio" to "stremio",
        "flixvision" to "flixvision",
        "tivimate" to "tivi",
        "tivi mate" to "tivi",
        "implayer" to "implayer",
        "plex" to "plex",
        "tubi" to "tubi",
        "pluto" to "pluto",
        "pluto tv" to "pluto",
        "spotify" to "spotify",
        "downloader" to "downloader",
        "x plore" to "xplore",
        "x plore file manager" to "xplore",
        "xplore" to "xplore",
        "settings" to "settings",
        "device settings" to "settings",
        "apple tv" to "apple_tv",
        "apple tv+" to "apple_tv",
        "apple tv plus" to "apple_tv",
        "adaway" to "adaway",
        "ad away" to "adaway",
        "crave" to "crave",
        "prime video" to "prime",
        "amazon prime video" to "prime",
        "hdo box" to "hdobox",
        "hdobox" to "hdobox",
        "netflix" to "netflix",
        "disney+" to "disney_plus",
        "disney plus" to "disney_plus",
        "max" to "max",
        "hbo" to "hbo",
        "hbo max" to "hbo_max",
        "hulu" to "hulu",
        "paramount+" to "paramount_plus",
        "paramount plus" to "paramount_plus",
        "peacock" to "peacock",
        "starz" to "starz",
        "dazn" to "dazn",
        "espn" to "espn",
        "fox sports" to "fox_sports",
        "mlb" to "mlb",
        "nba" to "nba",
        "nfl" to "nfl",
        "ufc" to "ufc",
        "f1" to "f1",
        "formula 1" to "f1",
        "kodi" to "kodi",
        "haystack" to "haystack_news",
        "haystack news" to "haystack_news",
        "tv bro" to "tv_bro",
        "aptoide tv" to "aptoide_tv",
        "localsend" to "localsend",
        "send files" to "localsend",
        "onstream" to "onstream",
        "on stream" to "onstream",
        "speedtest" to "speedtest",
        "speed test" to "speedtest",
        "ookla speedtest" to "speedtest",
        "tizentube" to "tizentube",
        "tizen tube" to "tizentube",
        "weyd" to "weyd"
    )

    private val fileMap = mapOf(
        "smarttube" to "SMARTTUBE.png",
        "youtube" to "YOUTUBE.png",
        "stremio" to "STREMIO.png",
        "flixvision" to "FLIXVISION.png",
        "tivi" to "TIVI.png",
        "implayer" to "IMPLAYER.png",
        "plex" to "PLEXALT.png",
        "tubi" to "TUBI.png",
        "pluto" to "PLUTOALT.png",
        "spotify" to "SPOTIFY.png",
        "apple_tv" to "APPLE.png",
        "adaway" to "ADAWAY.png",
        "crave" to "CRAVE.png",
        "downloader" to "DOWNLOADER.png",
        "xplore" to "FILEMANAGER+.png",
        "settings" to "SETTINGS.png",
        "prime" to "PRIME.png",
        "hdobox" to "HDOBOX.png",
        "netflix" to "NETFLIX.png",
        "disney_plus" to "DISNEY+.png",
        "max" to "MAX.png",
        "hbo" to "HBO.png",
        "hbo_max" to "HBOMAX.png",
        "hulu" to "hulu.png",
        "paramount_plus" to "paramount+.png",
        "peacock" to "PEACOCK.png",
        "starz" to "STARZ.png",
        "dazn" to "DAZN.png",
        "espn" to "espn.png",
        "fox_sports" to "FOXSPORTS.png",
        "mlb" to "MLB.png",
        "nba" to "NBA.png",
        "nfl" to "nfl.png",
        "onstream" to "ONSTREAM.png",
        "speedtest" to "SPEEDTESTALT.png",
        "tizentube" to "TIZENTUBE.png",
        "ufc" to "ufc.png",
        "f1" to "F1.png",
        "kodi" to "KODI.png",
        "haystack_news" to "HAYSTACKNEWS.png",
        "tv_bro" to "TVBRO.png",
        "aptoide_tv" to "APTOIDETV.png",
        "localsend" to "LOCALSEND.png",
        "weyd" to "WEYD.png"
    )

    fun hasMatch(packageName: String, label: String): Boolean {
        return resolveFileName(packageName, label) != null
    }

    fun loadBanner(context: Context, packageName: String, label: String): Drawable? {
        val fileName = resolveFileName(packageName, label) ?: return null
        val assetPath = "$ASSET_ROOT/$fileName"
        val bitmap = synchronized(bitmapCache) { bitmapCache[assetPath] } ?: loadBitmap(context, assetPath)
        return bitmap?.let { BitmapDrawable(context.resources, it) }
    }

    private fun resolveFileName(packageName: String, label: String): String? {
        val canonicalKey = packageMap[packageName] ?: aliasMap[normalize(label)]
        return canonicalKey?.let(fileMap::get)
    }

    private fun loadBitmap(context: Context, assetPath: String): Bitmap? {
        val bitmap = runCatching {
            context.assets.open(assetPath).use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull() ?: return null

        synchronized(bitmapCache) {
            bitmapCache[assetPath] = bitmap
        }
        return bitmap
    }

    private fun normalize(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace("&", "and")
            .replace(Regex("[^a-z0-9+]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
