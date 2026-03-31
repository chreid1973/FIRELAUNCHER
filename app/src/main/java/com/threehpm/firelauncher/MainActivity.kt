package com.threehpm.firelauncher

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.Html
import android.text.InputType
import android.text.format.Formatter
import android.view.KeyEvent
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.threehpm.firelauncher.databinding.ActivityMainBinding
import com.threehpm.firelauncher.databinding.ItemAppBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.random.Random
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val appCardStates = mutableListOf<AppCardState>()
    private val clockHandler = Handler(Looper.getMainLooper())
    private val weatherExecutor = Executors.newSingleThreadExecutor()
    private val feedExecutor = Executors.newSingleThreadExecutor()
    private val wallpaperExecutor = Executors.newSingleThreadExecutor()
    private var ambientAnimator: AnimatorSet? = null
    private var backdropAnimator: ValueAnimator? = null
    private var currentBackdropColor: Int = Color.TRANSPARENT
    private var wallpaperCrossfadeAnimator: AnimatorSet? = null
    private var wallpaperCatalog: List<WallpaperImage> = emptyList()
    private var wallpaperCatalogFetchedAt = 0L
    private var wallpaperIndex = -1
    private var currentWallpaperId: String? = null
    private var lastWallpaperConfigSignature: String? = null
    private var lastWallpaperRefreshToken = 0L
    private var showingPrimaryWallpaper = true
    private var pendingLocalWallpaperAction: (() -> Unit)? = null
    private val launcherPrefs by lazy { getSharedPreferences("launcher_state", MODE_PRIVATE) }
    private var defaultFavoritePackages: List<String> = emptyList()
    private var shouldRefreshSectionsOnStart = false
    private var lastHomeRefreshToken = 0L
    private var lastFocusedCardViewId: Int = View.NO_ID
    private val clockTicker = object : Runnable {
        override fun run() {
            updateClock()
            updateStorage()
            updateNetworkStatus()
            clockHandler.postDelayed(this, 30_000L)
        }
    }
    private val weatherTicker = object : Runnable {
        override fun run() {
            refreshWeather()
            clockHandler.postDelayed(this, 30 * 60_000L)
        }
    }
    private val sportsTicker = object : Runnable {
        override fun run() {
            refreshSportsFeeds()
            clockHandler.postDelayed(this, 60 * 60_000L)
        }
    }
    private val wallpaperTicker = object : Runnable {
        override fun run() {
            rotateWallpaper()
            scheduleWallpaperTicker()
        }
    }
    private val tickerItemLimit = 8
    private val sportsLiveWindowMs = 2 * 60 * 60_000L
    private val sportsLiveTickerLimit = 3
    private var sportsEntries: List<SportsEntry> = emptyList()
    private val sportsFeeds = listOf(
        SportsFeedSource("ppv1", "PPV 1", "https://3hpm.ca/ppv/events_ppv_1.json"),
        SportsFeedSource("ppv2", "PPV 2", "https://3hpm.ca/ppv/events_ppv_2.json"),
        SportsFeedSource("nhl", "NHL", "https://3hpm.ca/ppv/events_nhl.json"),
        SportsFeedSource("mlb", "MLB", "https://3hpm.ca/ppv/events_mlb.json"),
        SportsFeedSource("nba", "NBA", "https://3hpm.ca/ppv/events_nba.json")
    )
    private val tvAppKeywords = listOf(
        "tv", "live", "channel", "guide", "youtube", "smarttube", "stremio",
        "flixvision", "plex", "pluto", "tivimate", "iptv", "roku", "xumo",
        "haystack", "sports", "kodi", "twitch"
    )
    private val freeVodKeywords = listOf(
        "youtube", "smarttube", "tubi", "pluto", "freevee", "plex", "stremio",
        "flixvision", "crackle", "filmrise", "vix", "xumo", "roku", "kodi",
        "twitch", "haystack"
    )
    private val paidAppKeywords = listOf(
        "netflix", "prime video", "amazon video", "disney", "max", "hbo",
        "hulu", "paramount", "apple tv", "crave", "peacock", "starz",
        "showtime", "britbox", "dazn"
    )
    private val utilityKeywords = listOf(
        "settings", "downloader", "browser", "silk", "file", "manager",
        "x-plore", "xplore", "aptoide", "send files", "vpn", "tool"
    )
    private val maxRecentApps = 12
    private val defaultCategoryDefinitions = listOf(
        CategoryDefinition(
            id = CATEGORY_ID_TV_APPS,
            title = "TV Apps",
            accentColorRes = R.color.accent_blue,
            keywords = tvAppKeywords
        ),
        CategoryDefinition(
            id = CATEGORY_ID_FREE_VOD,
            title = "Free VOD",
            accentColorRes = R.color.accent_mint,
            keywords = freeVodKeywords
        ),
        CategoryDefinition(
            id = CATEGORY_ID_PAID_APPS,
            title = "Paid Apps",
            accentColorRes = R.color.accent_amber,
            keywords = paidAppKeywords
        ),
        CategoryDefinition(
            id = CATEGORY_ID_UTILITIES,
            title = "Utilities",
            accentColorRes = R.color.accent_silver,
            keywords = utilityKeywords,
            includeSettingsApp = true
        )
    )

    private val preferredApps = listOf(
        PreferredApp(
            title = "YouTube",
            description = "Lean-back video, instantly",
            packageNames = listOf(
                "org.smarttube.stable",
                "com.teamsmart.videomanager.tv",
                "com.liskovsoft.smartyoutubetv2.tv",
                "com.google.android.youtube.tv"
            ),
            labelKeywords = listOf("smarttube", "youtube"),
            accentColorRes = R.color.accent_coral
        ),
        PreferredApp(
            title = "Movies/TV",
            description = "Library and stream hub",
            packageNames = listOf("com.stremio.one"),
            labelKeywords = listOf("stremio"),
            accentColorRes = R.color.accent_mint
        ),
        PreferredApp(
            title = "FlixVision",
            description = "Movies and series",
            packageNames = listOf("flix.com.vision"),
            labelKeywords = listOf("flixvision"),
            accentColorRes = R.color.accent_amber
        ),
        PreferredApp(
            title = "Live TV",
            description = "Channels and guide",
            packageNames = listOf("ar.tvplayer.tv"),
            labelKeywords = listOf("live tv"),
            accentColorRes = R.color.accent_blue
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindUtilityPill()
        bindTickerBar()
        binding.appRowsScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            updateAppCardTranslucency(scrollY)
        }
        applyFeaturedHero(null, null)
        lastWallpaperConfigSignature = buildWallpaperConfigSignature()
        lastWallpaperRefreshToken = WallpaperPrefs.getRefreshToken(this)
        lastHomeRefreshToken = WallpaperPrefs.getHomeRefreshToken(this)

        val sections = loadAppSections()
        renderSections(sections)
    }

    override fun onStart() {
        super.onStart()
        if (!isTmdbWallpaperSource()) {
            applyFeaturedHero(null, null)
        }
        val currentHomeRefreshToken = WallpaperPrefs.getHomeRefreshToken(this)
        if (shouldRefreshSectionsOnStart || currentHomeRefreshToken != lastHomeRefreshToken) {
            renderSections(loadAppSections())
            shouldRefreshSectionsOnStart = false
            lastHomeRefreshToken = currentHomeRefreshToken
        }
        clockTicker.run()
        weatherTicker.run()
        sportsTicker.run()
        val wallpaperSettingsChanged = syncWallpaperSettingsFromPrefs()
        if (wallpaperSettingsChanged) {
            rotateWallpaper(forceCatalogRefresh = true, showStatusToast = true)
        } else {
            maybeRotateWallpaperOnStart()
        }
        scheduleWallpaperTicker()
        startAmbientMotion()
        binding.tickerText.isSelected = true
    }

    override fun onStop() {
        clockHandler.removeCallbacks(clockTicker)
        clockHandler.removeCallbacks(weatherTicker)
        clockHandler.removeCallbacks(sportsTicker)
        clockHandler.removeCallbacks(wallpaperTicker)
        ambientAnimator?.cancel()
        ambientAnimator = null
        backdropAnimator?.cancel()
        backdropAnimator = null
        wallpaperCrossfadeAnimator?.cancel()
        wallpaperCrossfadeAnimator = null
        super.onStop()
    }

    override fun onDestroy() {
        weatherExecutor.shutdownNow()
        feedExecutor.shutdownNow()
        wallpaperExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindUtilityPill() {
        binding.settingsQuickIcon.setImageDrawable(
            IconPackCatalog.loadBanner(this, "", "Settings")
                ?: ContextCompat.getDrawable(this, R.drawable.ic_settings_gear)
        )

        binding.utilityPill.setOnClickListener {
            resolveSettingsApp()?.let(::launchApp)
                ?: runCatching {
                    startActivity(
                        Intent(Settings.ACTION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
        }

        binding.utilityPill.setOnLongClickListener {
            startActivity(Intent(this, LauncherSettingsActivity::class.java))
            true
        }

        binding.utilityPill.setOnFocusChangeListener { view, hasFocus ->
            view.animate()
                .scaleX(if (hasFocus) 1.04f else 1f)
                .scaleY(if (hasFocus) 1.04f else 1f)
                .translationY(if (hasFocus) -2f else 0f)
                .setDuration(150L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            view.alpha = if (hasFocus) 1f else 0.92f
            binding.settingsQuickIcon.alpha = if (hasFocus) 1f else 0.88f
        }
    }

    private fun bindTickerBar() {
        binding.tickerBar.setOnClickListener {
            showSportsGuideDialog()
        }

        binding.tickerBar.setOnLongClickListener {
            showSportsGuideDialog()
            true
        }

        binding.tickerBar.setOnFocusChangeListener { view, hasFocus ->
            view.animate()
                .alpha(if (hasFocus) 1f else 0.94f)
                .scaleX(if (hasFocus) 1.01f else 1f)
                .scaleY(if (hasFocus) 1.01f else 1f)
                .setDuration(140L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun syncWallpaperSettingsFromPrefs(): Boolean {
        val currentSignature = buildWallpaperConfigSignature()
        val refreshToken = WallpaperPrefs.getRefreshToken(this)
        val changed = currentSignature != lastWallpaperConfigSignature ||
            refreshToken != lastWallpaperRefreshToken

        if (changed) {
            wallpaperCatalog = emptyList()
            wallpaperCatalogFetchedAt = 0L
            wallpaperIndex = -1
            currentWallpaperId = null
            lastWallpaperConfigSignature = currentSignature
            lastWallpaperRefreshToken = refreshToken
        }

        return changed
    }

    private fun buildWallpaperConfigSignature(): String {
        return buildString {
            append(WallpaperPrefs.getSource(this@MainActivity).prefValue)
            append('|')
            append(WallpaperPrefs.getIntervalMs(this@MainActivity))
            append('|')
            append(WallpaperPrefs.isShuffleEnabled(this@MainActivity))
            append('|')
            append(WallpaperPrefs.isChangeOnOpenEnabled(this@MainActivity))
            append('|')
            append(WallpaperPrefs.getFixedPath(this@MainActivity) ?: "")
            append('|')
            append(WallpaperPrefs.getNasaApiKey(this@MainActivity))
        }
    }

    private fun openWallpaperSettings() {
        val options = mutableListOf(
            "Wallpaper Source: ${getWallpaperSource().label}",
            "Rotation Interval: ${formatWallpaperInterval(getWallpaperRotationIntervalMs())}",
            "Shuffle: ${if (isWallpaperShuffleEnabled()) "On" else "Off"}",
            "Change On Open: ${if (isWallpaperChangeOnOpenEnabled()) "On" else "Off"}",
            "Pick Fixed Image",
            "Refresh Wallpaper Now",
            "Show Local Folder Path"
        )

        AlertDialog.Builder(this)
            .setTitle("Wallpaper Settings")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showWallpaperSourceDialog()
                    1 -> showWallpaperIntervalDialog()
                    2 -> {
                        setWallpaperShuffleEnabled(!isWallpaperShuffleEnabled())
                        openWallpaperSettings()
                    }
                    3 -> {
                        setWallpaperChangeOnOpenEnabled(!isWallpaperChangeOnOpenEnabled())
                        openWallpaperSettings()
                    }
                    4 -> ensureWallpaperStorageAccess { showFixedImagePickerDialog() }
                    5 -> rotateWallpaper(forceCatalogRefresh = true)
                    6 -> showLocalWallpaperFolderDialog()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showWallpaperSourceDialog() {
        val sources = WallpaperSource.values()
        val selectedIndex = sources.indexOf(getWallpaperSource()).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Wallpaper Source")
            .setSingleChoiceItems(
                sources.map { it.label }.toTypedArray(),
                selectedIndex
            ) { dialog, which ->
                when (sources[which]) {
                    WallpaperSource.NASA -> {
                        saveWallpaperSource(WallpaperSource.NASA)
                        rotateWallpaper(forceCatalogRefresh = true)
                        scheduleWallpaperTicker()
                        dialog.dismiss()
                    }
                    WallpaperSource.TMDB_TRENDING -> {
                        saveWallpaperSource(WallpaperSource.TMDB_TRENDING)
                        rotateWallpaper(forceCatalogRefresh = true)
                        scheduleWallpaperTicker()
                        dialog.dismiss()
                    }
                    WallpaperSource.TMDB_TRENDING_MOVIES -> {
                        saveWallpaperSource(WallpaperSource.TMDB_TRENDING_MOVIES)
                        rotateWallpaper(forceCatalogRefresh = true)
                        scheduleWallpaperTicker()
                        dialog.dismiss()
                    }
                    WallpaperSource.TMDB_TRENDING_TV -> {
                        saveWallpaperSource(WallpaperSource.TMDB_TRENDING_TV)
                        rotateWallpaper(forceCatalogRefresh = true)
                        scheduleWallpaperTicker()
                        dialog.dismiss()
                    }
                    WallpaperSource.TMDB_POPULAR_MOVIES -> {
                        saveWallpaperSource(WallpaperSource.TMDB_POPULAR_MOVIES)
                        rotateWallpaper(forceCatalogRefresh = true)
                        scheduleWallpaperTicker()
                        dialog.dismiss()
                    }
                    WallpaperSource.TMDB_POPULAR_TV -> {
                        saveWallpaperSource(WallpaperSource.TMDB_POPULAR_TV)
                        rotateWallpaper(forceCatalogRefresh = true)
                        scheduleWallpaperTicker()
                        dialog.dismiss()
                    }
                    WallpaperSource.REDDIT_EARTHPORN -> {
                        saveWallpaperSource(WallpaperSource.REDDIT_EARTHPORN)
                        rotateWallpaper(forceCatalogRefresh = true)
                        scheduleWallpaperTicker()
                        dialog.dismiss()
                    }
                    WallpaperSource.LOCAL_FOLDER -> {
                        ensureWallpaperStorageAccess {
                            if (loadLocalWallpaperCatalog().isEmpty()) {
                                showLocalWallpaperFolderDialog(
                                    messagePrefix = "No local wallpapers found yet.\n\n"
                                )
                            } else {
                                saveWallpaperSource(WallpaperSource.LOCAL_FOLDER)
                                rotateWallpaper(forceCatalogRefresh = true)
                                scheduleWallpaperTicker()
                            }
                            dialog.dismiss()
                        }
                    }
                    WallpaperSource.FIXED_IMAGE -> {
                        ensureWallpaperStorageAccess {
                            showFixedImagePickerDialog {
                                dialog.dismiss()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWallpaperIntervalDialog() {
        val intervals = arrayOf(
            5 * 60_000L,
            15 * 60_000L,
            30 * 60_000L,
            60 * 60_000L,
            24 * 60 * 60_000L
        )
        val selectedInterval = getWallpaperRotationIntervalMs()
        val selectedIndex = intervals.indexOf(selectedInterval).let { if (it >= 0) it else 1 }

        AlertDialog.Builder(this)
            .setTitle("Rotation Interval")
            .setSingleChoiceItems(
                intervals.map(::formatWallpaperInterval).toTypedArray(),
                selectedIndex
            ) { dialog, which ->
                saveWallpaperRotationIntervalMs(intervals[which])
                scheduleWallpaperTicker()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFixedImagePickerDialog(onDismiss: (() -> Unit)? = null) {
        val files = loadLocalWallpaperFiles()
        if (files.isEmpty()) {
            showLocalWallpaperFolderDialog(messagePrefix = "No local wallpapers found yet.\n\n")
            onDismiss?.invoke()
            return
        }

        val selectedPath = getFixedWallpaperPath()
        val selectedIndex = files.indexOfFirst { it.absolutePath == selectedPath }

        AlertDialog.Builder(this)
            .setTitle("Choose Fixed Image")
            .setSingleChoiceItems(
                files.map { it.name }.toTypedArray(),
                selectedIndex
            ) { dialog, which ->
                saveFixedWallpaperPath(files[which].absolutePath)
                saveWallpaperSource(WallpaperSource.FIXED_IMAGE)
                rotateWallpaper(forceCatalogRefresh = true)
                scheduleWallpaperTicker()
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .setNegativeButton("Cancel") { _, _ ->
                onDismiss?.invoke()
            }
            .show()
    }

    private fun showLocalWallpaperFolderDialog(messagePrefix: String = "") {
        val folder = getLocalWallpaperFolder()
        AlertDialog.Builder(this)
            .setTitle("Local Wallpaper Folder")
            .setMessage(
                buildString {
                    append(messagePrefix)
                    append("Add JPG, PNG, or WEBP images to:\n\n")
                    append(folder.absolutePath)
                }
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun maybeRotateWallpaperOnStart() {
        if (currentWallpaperId == null || isWallpaperChangeOnOpenEnabled()) {
            rotateWallpaper(forceCatalogRefresh = currentWallpaperId == null)
        }
    }

    private fun scheduleWallpaperTicker() {
        clockHandler.removeCallbacks(wallpaperTicker)
        clockHandler.postDelayed(wallpaperTicker, getWallpaperRotationIntervalMs())
    }

    private fun ensureWallpaperStorageAccess(onGranted: () -> Unit) {
        val permission = getWallpaperStoragePermission() ?: run {
            onGranted()
            return
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
            return
        }

        pendingLocalWallpaperAction = onGranted
        requestPermissions(arrayOf(permission), REQUEST_WALLPAPER_PERMISSION)
    }

    private fun getWallpaperStoragePermission(): String? {
        return WallpaperPrefs.getStoragePermission()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WALLPAPER_PERMISSION) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            val pendingAction = pendingLocalWallpaperAction
            pendingLocalWallpaperAction = null

            if (granted) {
                pendingAction?.invoke()
            } else {
                Toast.makeText(this, "Wallpaper folder access was not granted.", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun getWallpaperSource(): WallpaperSource {
        return WallpaperPrefs.getSource(this)
    }

    private fun saveWallpaperSource(source: WallpaperSource) {
        WallpaperPrefs.setSource(this, source)
        wallpaperCatalog = emptyList()
        wallpaperCatalogFetchedAt = 0L
        wallpaperIndex = -1
        currentWallpaperId = null
    }

    private fun getWallpaperRotationIntervalMs(): Long {
        return WallpaperPrefs.getIntervalMs(this)
    }

    private fun saveWallpaperRotationIntervalMs(intervalMs: Long) {
        WallpaperPrefs.setIntervalMs(this, intervalMs)
    }

    private fun isWallpaperShuffleEnabled(): Boolean {
        return WallpaperPrefs.isShuffleEnabled(this)
    }

    private fun setWallpaperShuffleEnabled(enabled: Boolean) {
        WallpaperPrefs.setShuffleEnabled(this, enabled)
    }

    private fun isWallpaperChangeOnOpenEnabled(): Boolean {
        return WallpaperPrefs.isChangeOnOpenEnabled(this)
    }

    private fun setWallpaperChangeOnOpenEnabled(enabled: Boolean) {
        WallpaperPrefs.setChangeOnOpenEnabled(this, enabled)
    }

    private fun getFixedWallpaperPath(): String? {
        return WallpaperPrefs.getFixedPath(this)
    }

    private fun saveFixedWallpaperPath(path: String) {
        WallpaperPrefs.setFixedPath(this, path)
    }

    private fun formatWallpaperInterval(intervalMs: Long): String {
        return WallpaperPrefs.formatInterval(intervalMs)
    }

    private fun getLocalWallpaperFolder(): File {
        return WallpaperPrefs.getLocalWallpaperFolder()
    }

    private fun loadLocalWallpaperFiles(): List<File> {
        return WallpaperPrefs.loadLocalWallpaperFiles()
    }

    private fun loadAppSections(): List<AppSection> {
        val launchableApps = LaunchableAppRepository.discoverLaunchableApps(this)
        val homeLaunchableApps = launchableApps.filter { it.hasIconPackMatch }
        val preferredResolvedApps = preferredApps
            .mapNotNull { resolvePreferredApp(it, homeLaunchableApps) }
            .distinctBy { it.packageName }
        val settingsApp = resolveSettingsApp()
        val catalogApps = buildCatalogApps(homeLaunchableApps)
        val sourceApps = (preferredResolvedApps + catalogApps + listOfNotNull(settingsApp))
            .distinctBy { it.packageName }
        defaultFavoritePackages = preferredResolvedApps.map { it.packageName }
        val favoriteApps = loadFavoritePackages(sourceApps)
        val recentApps = loadRecentApps(sourceApps)
        val sections = buildSections(favoriteApps, recentApps, sourceApps, settingsApp)

        if (sections.isEmpty()) {
            Toast.makeText(this, "No launchable apps found.", Toast.LENGTH_LONG).show()
        }

        return sections
    }

    private fun buildCatalogApps(launchableApps: List<LaunchableAppEntry>): List<AppItem> {
        val neutralAccent = ContextCompat.getColor(this, R.color.accent_silver)
        return launchableApps
            .map { app ->
                val banner = app.packBanner ?: app.systemBanner
                AppItem(
                    title = app.label,
                    subtitle = "",
                    badge = app.label,
                    packageName = app.packageName,
                    launchIntent = app.launchIntent,
                    icon = app.icon,
                    banner = banner,
                    accentColor = deriveAccentColor(banner ?: app.icon, neutralAccent)
                )
            }
            .sortedBy { it.title.lowercase(Locale.getDefault()) }
    }

    private fun buildSections(
        favoriteApps: List<AppItem>,
        recentApps: List<AppItem>,
        sourceApps: List<AppItem>,
        settingsApp: AppItem?
    ): List<AppSection> {
        val sections = mutableListOf<AppSection>()
        val hiddenPackages = loadHiddenPackages()
        val appMap = sourceApps.associateBy { it.packageName }
        val visibleSourceApps = sourceApps.filterNot { it.packageName in hiddenPackages }
        val visiblePackageSet = visibleSourceApps.map { it.packageName }.toSet()
        val customCategories = loadCustomCategories()
        val validCategoryIds = (
            defaultCategoryDefinitions.map { it.id } +
                customCategories.map { it.id }
            ).toSet()
        val categoryAssignments = loadCategoryAssignments()
            .filterKeys(appMap::containsKey)
            .filterValues(validCategoryIds::contains)
        val manuallyAssignedVisiblePackages = categoryAssignments
            .filterKeys(visiblePackageSet::contains)
            .keys
        val manuallyAssignedVisiblePackageSet = manuallyAssignedVisiblePackages.toSet()
        val autoCategorizedVisiblePackageSet = visibleSourceApps
            .asSequence()
            .filter { app ->
                app.packageName !in manuallyAssignedVisiblePackageSet &&
                    defaultCategoryDefinitions.any { definition ->
                        (definition.includeSettingsApp && settingsApp?.packageName == app.packageName) ||
                            matchesKeywords(app, definition.keywords)
                    }
            }
            .map { it.packageName }
            .toSet()
        val categorizedVisiblePackageSet =
            manuallyAssignedVisiblePackageSet + autoCategorizedVisiblePackageSet

        val favoriteSectionApps = favoriteApps.filterNot { it.packageName in hiddenPackages }
        if (favoriteSectionApps.isNotEmpty()) {
            sections += AppSection(
                id = SECTION_ID_FAVOURITES,
                title = "Favourites",
                apps = favoriteSectionApps,
                type = SectionType.FAVOURITES,
                accentColor = ContextCompat.getColor(this, R.color.accent_coral)
            )
        }

        val recentSectionApps = recentApps.filterNot { it.packageName in hiddenPackages }
        if (recentSectionApps.isNotEmpty()) {
            sections += AppSection(
                id = SECTION_ID_RECENT,
                title = "Recently Used",
                apps = recentSectionApps,
                type = SectionType.RECENT,
                accentColor = ContextCompat.getColor(this, R.color.accent_amber)
            )
        }
        val surfacedPackageSet = favoriteSectionApps.map { it.packageName }.toSet() +
            recentSectionApps.map { it.packageName }.toSet()

        defaultCategoryDefinitions.forEach { definition ->
            val accentColor = ContextCompat.getColor(this, definition.accentColorRes)
            val sectionTitle = getCategoryTitle(definition.id, definition.title)
            val leadingPackages = if (definition.includeSettingsApp) {
                listOfNotNull(settingsApp?.packageName)
            } else {
                emptyList()
            }
            val leadingApps = leadingPackages
                .mapNotNull(appMap::get)
                .filterNot { it.packageName in hiddenPackages }
                .map { it.copy(accentColor = accentColor) }
            val manualApps = categoryAssignments.entries
                .asSequence()
                .filter { it.value == definition.id }
                .mapNotNull { entry -> appMap[entry.key] }
                .filterNot { it.packageName in hiddenPackages }
                .sortedBy { it.title.lowercase(Locale.getDefault()) }
                .map { it.copy(accentColor = accentColor) }
                .toList()
            val reservedPackages = leadingApps.map { it.packageName }.toSet() +
                manualApps.map { it.packageName }.toSet() +
                manuallyAssignedVisiblePackages
            val matchedApps = visibleSourceApps
                .filter { it.packageName !in reservedPackages && matchesKeywords(it, definition.keywords) }
                .sortedBy { it.title.lowercase(Locale.getDefault()) }
                .map { it.copy(accentColor = accentColor) }

            val apps = (leadingApps + manualApps + matchedApps)
                .distinctBy { it.packageName }
                .take(18)

            if (apps.isNotEmpty()) {
                sections += AppSection(
                    id = definition.id,
                    title = sectionTitle,
                    apps = apps,
                    type = SectionType.CATEGORY,
                    accentColor = accentColor
                )
            }
        }

        customCategories.forEach { category ->
            val accentColor = resolveCustomCategoryAccent(category.id)
            val apps = categoryAssignments.entries
                .asSequence()
                .filter { it.value == category.id }
                .mapNotNull { entry -> appMap[entry.key] }
                .filterNot { it.packageName in hiddenPackages }
                .sortedBy { it.title.lowercase(Locale.getDefault()) }
                .map { it.copy(accentColor = accentColor) }
                .toList()

            if (apps.isNotEmpty()) {
                sections += AppSection(
                    id = category.id,
                    title = category.title,
                    apps = apps,
                    type = SectionType.CATEGORY,
                    isCustom = true,
                    accentColor = accentColor
                )
            }
        }

        val uncategorizedApps = visibleSourceApps
            .filter { it.packageName !in categorizedVisiblePackageSet && it.packageName !in surfacedPackageSet }
            .sortedBy { it.title.lowercase(Locale.getDefault()) }
            .map { it.copy(accentColor = ContextCompat.getColor(this, R.color.accent_silver)) }
            .take(18)

        if (uncategorizedApps.isNotEmpty()) {
            sections += AppSection(
                id = SECTION_ID_UNCATEGORIZED,
                title = "Uncategorized",
                apps = uncategorizedApps,
                type = SectionType.UNCATEGORIZED,
                accentColor = ContextCompat.getColor(this, R.color.accent_silver)
            )
        }

        return sections
    }

    private fun matchesKeywords(item: AppItem, keywords: List<String>): Boolean {
        val haystack = listOf(item.title, item.subtitle, item.badge, item.packageName)
            .joinToString(" ")
            .lowercase(Locale.getDefault())
        return keywords.any { haystack.contains(it) }
    }

    private fun renderSections(sections: List<AppSection>, focusRequest: FocusRequest? = null) {
        binding.appRowsContainer.removeAllViews()
        appCardStates.clear()
        val renderedSections = mutableListOf<RenderedSection>()

        sections.forEachIndexed { index, section ->
            val accentColor = section.accentColor ?: section.apps.firstOrNull()?.accentColor
                ?: ContextCompat.getColor(this, R.color.accent_silver)
            val cardViews = mutableListOf<View>()

            val sectionContainer = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (index == 0) dp(2) else dp(14)
                }
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
            }

            val headerRow = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }

            val sectionIndicator = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(14)).apply {
                    marginStart = dp(2)
                    marginEnd = dp(8)
                }
            }

            val headerTextGroup = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                orientation = LinearLayout.VERTICAL
            }

            val sectionLabel = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = section.title
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                letterSpacing = 0.06f
                alpha = 0.86f
            }

            val sectionMeta = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(1)
                }
                text = buildSectionMeta(section)
                setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
                letterSpacing = 0.08f
                alpha = 0.68f
                visibility = View.GONE
            }

            val shelfFrame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(1)
                }
                clipChildren = false
                clipToPadding = false
            }

            val scrollView = HorizontalScrollView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
                isHorizontalScrollBarEnabled = false
                isHorizontalFadingEdgeEnabled = true
                isSmoothScrollingEnabled = true
                overScrollMode = View.OVER_SCROLL_NEVER
                clipChildren = false
                clipToPadding = false
                isFillViewport = true
                setFadingEdgeLength(dp(42))
                setPadding(dp(4), dp(2), 0, dp(8))
            }

            val row = LinearLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.START
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
                setPadding(0, 0, dp(24), 0)
            }

            section.apps.forEach { app ->
                val cardBinding = ItemAppBinding.inflate(layoutInflater, row, false)
                bindAppCard(cardBinding, app, section) { hasFocus ->
                    sectionContainer.post {
                        applySectionVisual(
                            indicatorView = sectionIndicator,
                            titleView = sectionLabel,
                            metaView = sectionMeta,
                            shelfView = shelfFrame,
                            accentColor = accentColor,
                            isActive = sectionContainer.hasFocus(),
                            showShelfHaze = section.type == SectionType.FAVOURITES
                        )
                    }
                    if (hasFocus) {
                        lastFocusedCardViewId = cardBinding.root.id
                        updateEdgeFocusTargets(renderedSections)
                    }
                }
                row.addView(cardBinding.root)
                cardViews += cardBinding.root
            }

            headerTextGroup.addView(sectionLabel)
            headerTextGroup.addView(sectionMeta)
            headerRow.addView(sectionIndicator)
            headerRow.addView(headerTextGroup)
            scrollView.addView(row)
            shelfFrame.addView(scrollView)
            sectionContainer.addView(headerRow)
            sectionContainer.addView(shelfFrame)
            binding.appRowsContainer.addView(sectionContainer)

            applySectionVisual(
                indicatorView = sectionIndicator,
                titleView = sectionLabel,
                metaView = sectionMeta,
                shelfView = shelfFrame,
                accentColor = accentColor,
                isActive = false,
                showShelfHaze = section.type == SectionType.FAVOURITES
            )

            renderedSections += RenderedSection(
                section = section,
                containerView = sectionContainer,
                cardViews = cardViews,
                accentColor = accentColor
            )
        }

        if (renderedSections.none { renderedSection ->
                renderedSection.cardViews.any { it.id == lastFocusedCardViewId }
            }
        ) {
            lastFocusedCardViewId = View.NO_ID
        }

        binding.appRowsContainer.post {
            linkCardFocus(renderedSections)
            updateEdgeFocusTargets(renderedSections)
            focusRequest?.scrollY?.let { binding.appRowsScroll.scrollTo(0, it) }

            val focusedView = resolveFocusView(renderedSections, focusRequest)

            if (focusedView != null) {
                focusedView.requestFocus()
            } else {
                val initialTarget = renderedSections.firstOrNull()?.cardViews?.firstOrNull()
                initialTarget?.requestFocus()
            }
            updateAppCardTranslucency(binding.appRowsScroll.scrollY)
        }
    }

    private fun resolveFocusView(
        renderedSections: List<RenderedSection>,
        focusRequest: FocusRequest?
    ): View? {
        if (focusRequest == null) {
            return null
        }

        val targetSectionEntry = renderedSections.firstOrNull { it.section.id == focusRequest.sectionId }
        val targetSectionViews = targetSectionEntry?.cardViews.orEmpty()
        val exactTag = focusRequest.packageName?.let {
            buildCardTag(focusRequest.sectionId, it)
        }
        val exactMatch = exactTag?.let { tag ->
            targetSectionViews.firstOrNull { it.tag == tag }
        }
        if (exactMatch != null) {
            return exactMatch
        }

        if (targetSectionViews.isNotEmpty()) {
            return targetSectionViews[focusRequest.itemIndex.coerceIn(0, targetSectionViews.lastIndex)]
        }

        val fallbackSectionViews = renderedSections
            .getOrNull(focusRequest.sectionIndex.coerceIn(0, renderedSections.lastIndex))
            ?.cardViews
            .orEmpty()
        if (fallbackSectionViews.isNotEmpty()) {
            return fallbackSectionViews[
                focusRequest.itemIndex.coerceIn(0, fallbackSectionViews.lastIndex)
            ]
        }

        return null
    }

    private fun linkCardFocus(renderedSections: List<RenderedSection>) {
        val sectionCardViews = renderedSections.map { it.cardViews }
        sectionCardViews.forEachIndexed { sectionIndex, rowViews ->
            rowViews.forEachIndexed { itemIndex, view ->
                if (view.id == View.NO_ID) {
                    view.id = View.generateViewId()
                }

                view.nextFocusLeftId = rowViews.getOrNull(itemIndex - 1)?.id ?: View.NO_ID
                view.nextFocusRightId = rowViews.getOrNull(itemIndex + 1)?.id ?: View.NO_ID
                view.nextFocusUpId = findAdjacentFocusTarget(
                    sectionCardViews = sectionCardViews,
                    currentSectionIndex = sectionIndex,
                    currentItemIndex = itemIndex,
                    direction = -1
                )?.id ?: View.NO_ID
                view.nextFocusDownId = findAdjacentFocusTarget(
                    sectionCardViews = sectionCardViews,
                    currentSectionIndex = sectionIndex,
                    currentItemIndex = itemIndex,
                    direction = 1
                )?.id ?: View.NO_ID
            }
        }

        renderedSections.firstOrNull()?.let { firstSection ->
            firstSection.cardViews.forEach { it.nextFocusUpId = binding.utilityPill.id }
        }

        renderedSections.lastOrNull()?.cardViews?.forEach { view ->
            view.nextFocusDownId = binding.tickerBar.id
        }
    }

    private fun updateEdgeFocusTargets(renderedSections: List<RenderedSection>) {
        val firstCardTargetId = renderedSections
            .firstOrNull()
            ?.cardViews
            ?.firstOrNull()
            ?.id
            ?: View.NO_ID
        val lastCardTargetId = renderedSections
            .lastOrNull()
            ?.cardViews
            ?.lastOrNull()
            ?.id
            ?: View.NO_ID
        val bottomReturnTargetId = when {
            lastFocusedCardViewId != View.NO_ID -> lastFocusedCardViewId
            lastCardTargetId != View.NO_ID -> lastCardTargetId
            else -> firstCardTargetId
        }

        binding.utilityPill.nextFocusDownId = firstCardTargetId
        binding.tickerBar.nextFocusUpId =
            if (bottomReturnTargetId != View.NO_ID) bottomReturnTargetId else binding.utilityPill.id
    }

    private fun findAdjacentFocusTarget(
        sectionCardViews: List<List<View>>,
        currentSectionIndex: Int,
        currentItemIndex: Int,
        direction: Int
    ): View? {
        val adjacentRow = sectionCardViews.getOrNull(currentSectionIndex + direction).orEmpty()
        if (adjacentRow.isEmpty()) {
            return null
        }

        if (adjacentRow.size == 1) {
            return adjacentRow.first()
        }

        val currentRow = sectionCardViews.getOrNull(currentSectionIndex).orEmpty()
        if (currentRow.size <= 1) {
            return adjacentRow.first()
        }

        val currentProgress = currentItemIndex.toFloat() / currentRow.lastIndex.toFloat()
        val targetIndex = (currentProgress * adjacentRow.lastIndex)
            .roundToInt()
            .coerceIn(0, adjacentRow.lastIndex)
        return adjacentRow[targetIndex]
    }

    private fun refreshSectionsAfterAction(section: AppSection, anchorPackageName: String) {
        val anchorIndex = section.apps.indexOfFirst { it.packageName == anchorPackageName }
            .coerceAtLeast(0)
        val currentSectionIndex = binding.appRowsContainer.indexOfChild(
            generateSequence(currentFocus?.parent) { parent -> (parent as? View)?.parent }
                .filterIsInstance<LinearLayout>()
                .firstOrNull { it.parent == binding.appRowsContainer }
        ).takeIf { it >= 0 } ?: 0

        renderSections(
            loadAppSections(),
            FocusRequest(
                sectionId = section.id,
                packageName = anchorPackageName,
                itemIndex = anchorIndex,
                sectionIndex = currentSectionIndex,
                scrollY = binding.appRowsScroll.scrollY
            )
        )
    }

    private fun buildCardTag(sectionId: String, packageName: String): String {
        return "$sectionId::$packageName"
    }

    private fun applyCardSize(binding: ItemAppBinding, useLargeCard: Boolean) {
        val rootWidth = if (useLargeCard) dp(174) else dp(174)
        val rootHeight = if (useLargeCard) dp(142) else dp(142)
        val sideMargin = if (useLargeCard) dp(6) else dp(6)
        val rootTopMargin = if (useLargeCard) dp(3) else dp(3)
        val rootBottomMargin = if (useLargeCard) dp(6) else dp(6)
        val containerTopPadding = if (useLargeCard) dp(4) else dp(4)
        val containerBottomPadding = if (useLargeCard) dp(4) else dp(4)
        val frameHeight = if (useLargeCard) dp(102) else dp(102)
        val glowWidth = if (useLargeCard) dp(152) else dp(152)
        val glowHeight = if (useLargeCard) dp(18) else dp(18)
        val tileHeight = if (useLargeCard) dp(94) else dp(94)
        val titleMarginTop = if (useLargeCard) dp(8) else dp(4)
        val titleMaxWidth = if (useLargeCard) dp(104) else dp(64)
        val titlePaddingHorizontal = if (useLargeCard) dp(10) else dp(6)
        val titlePaddingVertical = if (useLargeCard) dp(4) else dp(2)

        (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            width = rootWidth
            height = rootHeight
            marginStart = sideMargin
            topMargin = rootTopMargin
            marginEnd = sideMargin
            bottomMargin = rootBottomMargin
            binding.root.layoutParams = this
        }

        val contentLayout = binding.root.getChildAt(0) as? LinearLayout
        contentLayout?.setPadding(0, containerTopPadding, 0, containerBottomPadding)

        (binding.appGlow.layoutParams as? FrameLayout.LayoutParams)?.apply {
            width = glowWidth
            height = glowHeight
            binding.appGlow.layoutParams = this
        }

        (binding.appGlow.parent as? FrameLayout)?.layoutParams =
            (binding.appGlow.parent as? FrameLayout)?.layoutParams?.apply {
                height = frameHeight
            }

        (binding.appTileSurface.layoutParams as? FrameLayout.LayoutParams)?.apply {
            height = tileHeight
            binding.appTileSurface.layoutParams = this
        }

        (binding.appTitle.layoutParams as? LinearLayout.LayoutParams)?.apply {
            topMargin = titleMarginTop
            binding.appTitle.layoutParams = this
        }
        binding.appTitle.maxWidth = titleMaxWidth
        binding.appTitle.setPadding(
            titlePaddingHorizontal,
            titlePaddingVertical,
            titlePaddingHorizontal,
            titlePaddingVertical
        )
        binding.appTitle.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (useLargeCard) 10f else 8f
        )
    }

    private fun bindAppCard(
        binding: ItemAppBinding,
        item: AppItem,
        section: AppSection,
        onFocusChanged: (Boolean) -> Unit = {}
    ) {
        val useLargeCard = section.type == SectionType.FAVOURITES
        val usesBannerArt = hasWideBanner(item.banner)
        val cardState = AppCardState(
            binding = binding,
            accentColor = item.accentColor,
            usesBannerArt = usesBannerArt
        )
        appCardStates += cardState
        applyCardSize(binding, useLargeCard)
        binding.appTitle.text = item.title
        binding.appIcon.setImageDrawable(if (usesBannerArt) item.banner else item.icon)
        binding.appIcon.scaleType = if (usesBannerArt) {
            android.widget.ImageView.ScaleType.CENTER_CROP
        } else {
            android.widget.ImageView.ScaleType.FIT_CENTER
        }
        if (usesBannerArt) {
            binding.appIcon.setPadding(0, 0, 0, 0)
        } else {
            if (useLargeCard) {
                binding.appIcon.setPadding(dp(22), dp(14), dp(22), dp(14))
            } else {
                binding.appIcon.setPadding(dp(8), dp(8), dp(8), dp(8))
            }
        }
        binding.root.contentDescription = item.title
        binding.root.tag = buildCardTag(section.id, item.packageName)
        if (binding.root.id == View.NO_ID) {
            binding.root.id = View.generateViewId()
        }
        binding.appTitle.visibility = View.GONE
        binding.appTitle.alpha = 0f
        binding.appTitle.translationY = -3f
        binding.appTileSurface.clipToOutline = true
        binding.appTileSurface.outlineProvider = ViewOutlineProvider.BACKGROUND
        binding.appTileSurface.scaleX = 1f
        binding.appTileSurface.scaleY = 1f
        binding.appGlow.alpha = 0f
        binding.appGlow.scaleX = 0.86f
        binding.appGlow.scaleY = 0.38f
        binding.appFocusPlate.alpha = 0f
        binding.appFocusPlate.scaleX = 0.94f
        binding.appFocusPlate.scaleY = 0.94f
        applyCardVisual(cardState)

        binding.root.setOnClickListener {
            launchApp(item)
        }

        binding.root.setOnLongClickListener {
            showAppActions(item, section)
            true
        }

        binding.root.setOnKeyListener { _, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        showAppActions(item, section)
                    }
                    true
                }
                else -> false
            }
        }

        binding.root.setOnFocusChangeListener { view, hasFocus ->
            cardState.isFocused = hasFocus
            applyCardVisual(cardState)
            view.isSelected = hasFocus
            view.animate()
                .scaleX(if (hasFocus) 1.035f else 1.0f)
                .scaleY(if (hasFocus) 1.035f else 1.0f)
                .translationY(if (hasFocus) -6f else 0f)
                .alpha(calculateCardAlpha(hasFocus))
                .setDuration(190L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            binding.appTileSurface.animate()
                .scaleX(if (hasFocus) 1.05f else 1.0f)
                .scaleY(if (hasFocus) 1.05f else 1.0f)
                .setDuration(190L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            binding.appIcon.animate()
                .scaleX(if (hasFocus && !usesBannerArt) 1.05f else 1.0f)
                .scaleY(if (hasFocus && !usesBannerArt) 1.05f else 1.0f)
                .setDuration(190L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            binding.appGlow.animate()
                .alpha(if (hasFocus) 0.78f else 0f)
                .scaleX(if (hasFocus) 1.04f else 0.86f)
                .scaleY(if (hasFocus) 0.72f else 0.38f)
                .setDuration(190L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            binding.appFocusPlate.animate()
                .alpha(if (hasFocus) 1f else 0f)
                .scaleX(if (hasFocus) 1f else 0.94f)
                .scaleY(if (hasFocus) 1f else 0.94f)
                .setDuration(190L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            view.translationZ = if (hasFocus) 28f else 0f
            if (hasFocus) {
                updateFocusBackdrop(view, item.accentColor)
                view.post {
                    val rect = Rect()
                    view.getDrawingRect(rect)
                    rect.left -= dp(18)
                    rect.right += dp(18)
                    view.requestRectangleOnScreen(rect, true)
                }
            }
            onFocusChanged(hasFocus)
        }
    }

    private fun applyCardVisual(cardState: AppCardState) {
        val binding = cardState.binding
        val accentColor = cardState.accentColor
        val usesBannerArt = cardState.usesBannerArt
        val hasFocus = cardState.isFocused
        binding.root.background = null
        binding.root.alpha = calculateCardAlpha(hasFocus)
        binding.appTitle.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        binding.appTitle.background = createTitleChipBackground(accentColor, hasFocus)
        binding.appTileSurface.background =
            createTileSurfaceBackground(accentColor, usesBannerArt, hasFocus)
        binding.appIcon.alpha = if (hasFocus || usesBannerArt) 1.0f else 0.94f
        binding.appGlow.background = createGlowBackground(accentColor)
        binding.appFocusPlate.background =
            createFocusPlateBackground(accentColor, usesBannerArt, hasFocus)
    }

    private fun updateAppCardTranslucency(scrollY: Int) {
        appCardStates.forEach(::applyCardVisual)
    }

    private fun calculateCardAlpha(hasFocus: Boolean): Float {
        if (binding.heroContainer.visibility != View.VISIBLE) {
            return if (hasFocus) 1f else 0.78f
        }

        val scrollProgress = (binding.appRowsScroll.scrollY / HERO_CARD_ALPHA_SCROLL_RANGE.toFloat())
            .coerceIn(0f, 1f)
        return if (hasFocus) {
            0.9f + (0.1f * scrollProgress)
        } else {
            0.58f + (0.24f * scrollProgress)
        }
    }

    private fun buildSectionMeta(section: AppSection): String {
        return when (section.type) {
            SectionType.FAVOURITES -> "${section.apps.size} pinned"
            SectionType.RECENT -> "${section.apps.size} recent"
            SectionType.UNCATEGORIZED -> "${section.apps.size} unassigned"
            SectionType.ALL_APPS -> {
                if (section.hiddenCount > 0) {
                    "${section.apps.size} installed • ${section.hiddenCount} hidden"
                } else {
                    "${section.apps.size} installed"
                }
            }
            SectionType.CATEGORY -> {
                if (section.isCustom) "${section.apps.size} assigned" else "${section.apps.size} apps"
            }
        }
    }

    private fun showAppActions(item: AppItem, section: AppSection) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val favoritePackages = loadFavoritePackageOrder()
        val favoriteIndex = favoritePackages.indexOf(item.packageName)
        val isFavorite = favoriteIndex >= 0
        val currentCategoryId = loadCategoryAssignments()[item.packageName]
        val isHidden = isPackageHidden(item.packageName)

        if (isFavorite) {
            if (favoriteIndex > 0) {
                actions += "Move Left" to {
                    if (moveFavorite(item.packageName, -1)) {
                        refreshSectionsAfterAction(section, item.packageName)
                    }
                }
            }
            if (favoriteIndex in 0 until favoritePackages.lastIndex) {
                actions += "Move Right" to {
                    if (moveFavorite(item.packageName, 1)) {
                        refreshSectionsAfterAction(section, item.packageName)
                    }
                }
            }
            actions += "Remove from Favourites" to {
                removeFavorite(item.packageName)
                Toast.makeText(
                    this,
                    "${item.title} removed from Favourites",
                    Toast.LENGTH_SHORT
                ).show()
                refreshSectionsAfterAction(section, item.packageName)
            }
        } else {
            actions += "Add to Favourites" to {
                toggleFavorite(item.packageName)
                Toast.makeText(
                    this,
                    "${item.title} added to Favourites",
                    Toast.LENGTH_SHORT
                ).show()
                refreshSectionsAfterAction(section, item.packageName)
            }
        }

        actions += "Assign to Category" to {
            showAssignCategoryDialog(item, section)
        }

        if (currentCategoryId != null) {
            actions += "Clear Manual Category" to {
                clearCategoryAssignment(item.packageName)
                Toast.makeText(
                    this,
                    "${item.title} returned to automatic grouping",
                    Toast.LENGTH_SHORT
                ).show()
                refreshSectionsAfterAction(section, item.packageName)
            }
        }

        if (isHidden) {
            actions += "Show on Home" to {
                setPackageHidden(item.packageName, false)
                Toast.makeText(
                    this,
                    "${item.title} is visible on Home again",
                    Toast.LENGTH_SHORT
                ).show()
                refreshSectionsAfterAction(section, item.packageName)
            }
        } else {
            actions += "Hide from Home" to {
                setPackageHidden(item.packageName, true)
                Toast.makeText(
                    this,
                    "${item.title} hidden from Home",
                    Toast.LENGTH_SHORT
                ).show()
                refreshSectionsAfterAction(section, item.packageName)
            }
        }

        if (section.type == SectionType.CATEGORY) {
            actions += "Rename \"${section.title}\"" to {
                promptForCategoryName(
                    dialogTitle = "Rename Category",
                    initialValue = section.title
                ) { newName ->
                    if (section.isCustom) {
                        renameCustomCategory(section.id, newName)
                    } else {
                        renameCategoryTitle(section.id, newName)
                    }
                    Toast.makeText(
                        this,
                        "Category renamed to $newName",
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshSectionsAfterAction(section, item.packageName)
                }
            }
        }

        if (section.isCustom) {
            actions += "Delete \"${section.title}\"" to {
                AlertDialog.Builder(this)
                    .setTitle("Delete Category")
                    .setMessage("Delete ${section.title}? Apps will return to automatic grouping.")
                    .setPositiveButton("Delete") { dialog, _ ->
                        deleteCustomCategory(section.id)
                        Toast.makeText(
                            this,
                            "${section.title} deleted",
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshSectionsAfterAction(section, item.packageName)
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        actions += "Cancel" to {}

        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(actions.map { it.first }.toTypedArray()) { dialog, which ->
                actions.getOrNull(which)?.second?.invoke()
                dialog.dismiss()
            }
            .show()
    }

    private fun showAssignCategoryDialog(item: AppItem, section: AppSection) {
        val currentCategoryId = loadCategoryAssignments()[item.packageName]
        val categoryOptions = buildList {
            defaultCategoryDefinitions.forEach {
                add(it.id to getCategoryTitle(it.id, it.title))
            }
            loadCustomCategories().forEach { add(it.id to it.title) }
        }

        val labels = categoryOptions
            .map { (categoryId, title) ->
                if (categoryId == currentCategoryId) "$title  •  Current" else title
            } + "Create New Category"

        AlertDialog.Builder(this)
            .setTitle("Assign ${item.title}")
            .setItems(labels.toTypedArray()) { dialog, which ->
                if (which == categoryOptions.size) {
                    dialog.dismiss()
                    promptForCategoryName("New Category") { categoryName ->
                        val categoryId = createCustomCategory(categoryName)
                        assignCategory(item.packageName, categoryId)
                        setPackageHidden(item.packageName, false)
                        Toast.makeText(
                            this,
                            "${item.title} assigned to $categoryName",
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshSectionsAfterAction(section, item.packageName)
                    }
                } else {
                    val selectedCategory = categoryOptions[which]
                    assignCategory(item.packageName, selectedCategory.first)
                    setPackageHidden(item.packageName, false)
                    Toast.makeText(
                        this,
                        "${item.title} assigned to ${selectedCategory.second}",
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshSectionsAfterAction(section, item.packageName)
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptForCategoryName(
        dialogTitle: String,
        initialValue: String = "",
        onSave: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            setText(initialValue)
            setSelection(text.length)
            hint = "Category name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setView(input)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val categoryName = input.text.toString().trim()
                        if (categoryName.isBlank()) {
                            input.error = "Enter a category name"
                            return@setOnClickListener
                        }
                        onSave(categoryName)
                        dialog.dismiss()
                    }
                }
            }
            .show()
    }

    private fun applySectionVisual(
        indicatorView: View,
        titleView: TextView,
        metaView: TextView,
        shelfView: View,
        accentColor: Int,
        isActive: Boolean,
        showShelfHaze: Boolean
    ) {
        indicatorView.background = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(ColorUtils.setAlphaComponent(accentColor, if (isActive) 255 else 84))
        }

        titleView.setTextColor(
            if (isActive) ContextCompat.getColor(this, R.color.text_primary)
            else ContextCompat.getColor(this, R.color.text_secondary)
        )
        metaView.setTextColor(
            if (isActive) ContextCompat.getColor(this, R.color.text_secondary)
            else ContextCompat.getColor(this, R.color.text_muted)
        )

        titleView.alpha = if (isActive) 0.96f else 0.68f
        metaView.alpha = if (isActive) 0.72f else 0.46f
        shelfView.animate()
            .alpha(1f)
            .setDuration(160L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        shelfView.background = if (showShelfHaze) createShelfHazeBackground(isActive) else null
    }

    private fun createShelfHazeBackground(isActive: Boolean): Drawable {
        val coreAlpha = if (isActive) 92 else 74
        val edgeAlpha = if (isActive) 42 else 28
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(Color.BLACK, edgeAlpha),
                ColorUtils.setAlphaComponent(Color.BLACK, coreAlpha),
                ColorUtils.setAlphaComponent(Color.BLACK, edgeAlpha),
                Color.TRANSPARENT
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(28).toFloat()
        }
        val vignette = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.BLACK, edgeAlpha),
                ColorUtils.setAlphaComponent(Color.BLACK, coreAlpha / 2),
                ColorUtils.setAlphaComponent(Color.BLACK, coreAlpha / 2),
                ColorUtils.setAlphaComponent(Color.BLACK, edgeAlpha)
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(28).toFloat()
        }

        return LayerDrawable(arrayOf(base, vignette)).apply {
            setLayerInset(0, 0, dp(2), 0, dp(2))
            setLayerInset(1, dp(6), dp(10), dp(6), dp(6))
        }
    }

    private fun createTileSurfaceBackground(
        accentColor: Int,
        usesBannerArt: Boolean,
        hasFocus: Boolean
    ): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(Color.TRANSPARENT)
        }
    }

    private fun createGlowBackground(accentColor: Int): Drawable {
        val pool = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(Color.WHITE, 18),
                ColorUtils.setAlphaComponent(Color.WHITE, 58),
                ColorUtils.setAlphaComponent(Color.WHITE, 18),
                Color.TRANSPARENT
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
        }
        val haze = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, 28),
                ColorUtils.setAlphaComponent(Color.WHITE, 8),
                Color.TRANSPARENT
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
        }
        val shimmer = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(Color.WHITE, 22),
                ColorUtils.setAlphaComponent(Color.WHITE, 92),
                ColorUtils.setAlphaComponent(Color.WHITE, 22),
                Color.TRANSPARENT
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
        }

        return LayerDrawable(arrayOf(pool, haze, shimmer)).apply {
            setLayerInset(0, 0, dp(5), 0, 0)
            setLayerInset(1, dp(14), 0, dp(14), dp(4))
            setLayerInset(2, dp(26), dp(2), dp(26), dp(8))
        }
    }

    private fun createFocusPlateBackground(
        accentColor: Int,
        usesBannerArt: Boolean,
        hasFocus: Boolean
    ): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(Color.TRANSPARENT)
        }
    }

    private fun createTitleChipBackground(accentColor: Int, hasFocus: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(
                if (hasFocus) {
                    ColorUtils.setAlphaComponent(
                        ColorUtils.blendARGB(accentColor, Color.BLACK, 0.9f),
                        196
                    )
                } else {
                    ColorUtils.setAlphaComponent(
                        ContextCompat.getColor(this@MainActivity, R.color.panel_surface),
                        0
                    )
                }
            )
            setStroke(
                dp(1),
                if (hasFocus) ColorUtils.setAlphaComponent(accentColor, 172)
                else ColorUtils.setAlphaComponent(
                    ContextCompat.getColor(this@MainActivity, R.color.panel_stroke),
                    0
                )
            )
        }
    }

    private fun updateFocusBackdrop(anchorView: View, accentColor: Int) {
        binding.root.post {
            val rootLocation = IntArray(2)
            val anchorLocation = IntArray(2)
            binding.root.getLocationOnScreen(rootLocation)
            anchorView.getLocationOnScreen(anchorLocation)

            val centerX = ((anchorLocation[0] - rootLocation[0]) + (anchorView.width / 2f)) /
                binding.root.width.toFloat()
            val centerY = ((anchorLocation[1] - rootLocation[1]) + (anchorView.height / 2f)) /
                binding.root.height.toFloat()
            val targetColor = ColorUtils.setAlphaComponent(accentColor, 76)

            backdropAnimator?.cancel()
            backdropAnimator = ValueAnimator.ofObject(
                ArgbEvaluator(),
                currentBackdropColor,
                targetColor
            ).apply {
                duration = 320L
                addUpdateListener { animator ->
                    val animatedColor = animator.animatedValue as Int
                    binding.focusBackdrop.background =
                        createBackdropGradient(animatedColor, centerX, centerY)
                }
                start()
            }

            currentBackdropColor = targetColor
            binding.focusBackdrop.animate()
                .alpha(1f)
                .setDuration(260L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun createBackdropGradient(
        accentColor: Int,
        centerX: Float,
        centerY: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = maxOf(binding.root.width, binding.root.height) * 0.62f
            colors = intArrayOf(
                ColorUtils.setAlphaComponent(accentColor, 138),
                ColorUtils.setAlphaComponent(accentColor, 44),
                ColorUtils.setAlphaComponent(accentColor, 0)
            )
            setGradientCenter(centerX.coerceIn(0.14f, 0.86f), centerY.coerceIn(0.12f, 0.78f))
        }
    }

    private fun startAmbientMotion() {
        binding.root.post {
            ambientAnimator?.cancel()

            val sweepWidth = binding.ambientSweep.width.toFloat()
            val travel = binding.root.width.toFloat() + sweepWidth
            binding.ambientSweep.translationX = -sweepWidth
            binding.ambientGlow.scaleX = 1f
            binding.ambientGlow.scaleY = 1f

            val sweepMotion = ObjectAnimator.ofFloat(
                binding.ambientSweep,
                View.TRANSLATION_X,
                -sweepWidth,
                travel
            ).apply {
                duration = 22_000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
            }

            val sweepAlpha = ObjectAnimator.ofFloat(
                binding.ambientSweep,
                View.ALPHA,
                0.05f,
                0.12f
            ).apply {
                duration = 6_500L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
            }

            val glowAlpha = ObjectAnimator.ofFloat(
                binding.ambientGlow,
                View.ALPHA,
                0.09f,
                0.17f
            ).apply {
                duration = 9_000L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
            }

            val glowScaleX = ObjectAnimator.ofFloat(
                binding.ambientGlow,
                View.SCALE_X,
                1f,
                1.12f
            ).apply {
                duration = 10_000L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
            }

            val glowScaleY = ObjectAnimator.ofFloat(
                binding.ambientGlow,
                View.SCALE_Y,
                1f,
                1.12f
            ).apply {
                duration = 10_000L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
            }

            ambientAnimator = AnimatorSet().apply {
                playTogether(sweepMotion, sweepAlpha, glowAlpha, glowScaleX, glowScaleY)
                start()
            }
        }
    }

    private fun resolvePreferredApp(
        preferredApp: PreferredApp,
        launchableApps: List<LaunchableAppEntry>
    ): AppItem? {
        val launchableApp = preferredApp.packageNames.firstNotNullOfOrNull { candidatePackage ->
            launchableApps.firstOrNull { it.packageName == candidatePackage }
        } ?: launchableApps.firstOrNull { launchableApp ->
            preferredApp.labelKeywords.any { keyword ->
                launchableApp.label.contains(keyword, ignoreCase = true)
            }
        }

        return launchableApp?.let {
            val banner = it.packBanner ?: it.systemBanner
            AppItem(
                title = preferredApp.title,
                subtitle = preferredApp.description,
                badge = it.label,
                packageName = it.packageName,
                launchIntent = it.launchIntent,
                icon = it.icon,
                banner = banner,
                accentColor = deriveAccentColor(
                    banner ?: it.icon,
                    ContextCompat.getColor(this, preferredApp.accentColorRes)
                )
            )
        }
    }

    private fun resolveSettingsApp(): AppItem? {
        val settingsTarget = LaunchableAppRepository.resolveSettingsApp(this)

        return settingsTarget?.let {
            val banner = it.packBanner ?: it.systemBanner
            AppItem(
                title = "Settings",
                subtitle = "Device controls",
                badge = it.label,
                packageName = it.packageName,
                launchIntent = it.launchIntent,
                icon = ContextCompat.getDrawable(this, R.drawable.ic_settings_gear)
                    ?: it.icon,
                banner = banner,
                accentColor = ContextCompat.getColor(this, R.color.accent_silver)
            )
        }
    }

    private fun launchApp(app: AppItem) {
        try {
            startActivity(app.launchIntent)
            recordRecentLaunch(app.packageName)
            shouldRefreshSectionsOnStart = true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "App not available: ${app.title}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateClock() {
        val now = Date()
        binding.timeText.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
        binding.dateText.text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now)
    }

    private fun updateStorage() {
        val statFs = StatFs(filesDir.absolutePath)
        val availableBytes = statFs.availableBytes
        val totalBytes = statFs.totalBytes

        binding.storagePrimaryText.text = "${Formatter.formatFileSize(this, availableBytes)} free"
        binding.storageSecondaryText.text = "${Formatter.formatFileSize(this, totalBytes)} total"
    }

    private fun updateNetworkStatus() {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        val networkCapabilities = connectivityManager
            ?.getNetworkCapabilities(connectivityManager.activeNetwork)

        val (label, dotColor) = when {
            networkCapabilities == null -> "Offline" to ContextCompat.getColor(this, R.color.accent_coral)
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                "Ethernet" to ContextCompat.getColor(this, R.color.accent_blue)
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                "Wi-Fi" to ContextCompat.getColor(this, R.color.accent_mint)
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                "Mobile" to ContextCompat.getColor(this, R.color.accent_amber)
            else -> "Online" to ContextCompat.getColor(this, R.color.accent_silver)
        }

        binding.utilityPill.contentDescription = "Settings, $label"
    }

    private fun refreshWeather() {
        weatherExecutor.execute {
            val weather = fetchWeatherInfo() ?: loadCachedWeatherInfo()
            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }

                if (weather == null) {
                    binding.weatherLocationText.text = "Local Weather"
                    binding.weatherPrimaryText.text = "--"
                    binding.weatherSecondaryText.text = "Unavailable"
                    return@runOnUiThread
                }

                binding.weatherLocationText.text = weather.location
                binding.weatherPrimaryText.text = weather.temperature
                binding.weatherSecondaryText.text = weather.summary
            }
        }
    }

    private fun refreshSportsFeeds() {
        feedExecutor.execute {
            val entries = fetchSportsEntries()
            val tickerText = formatSportsTickerText(entries)
            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }
                sportsEntries = entries
                binding.tickerLabel.text = "Sports Feed"
                binding.tickerText.text = tickerText
                binding.tickerText.isSelected = false
                binding.tickerText.post {
                    binding.tickerText.isSelected = true
                }
            }
        }
    }

    private fun rotateWallpaper(
        forceCatalogRefresh: Boolean = false,
        showStatusToast: Boolean = false
    ) {
        wallpaperExecutor.execute {
            val catalog = loadWallpaperCatalog(forceCatalogRefresh)
            if (catalog.isEmpty()) {
                runOnUiThread {
                    if (!isDestroyed && !isFinishing && isTmdbWallpaperSource()) {
                        applyFeaturedHero(null, null)
                    }
                }
                if (showStatusToast) {
                    runOnUiThread {
                        if (!isDestroyed && !isFinishing) {
                            Toast.makeText(
                                this,
                                "Wallpaper refresh failed: no images available for ${getWallpaperSource().label}.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                return@execute
            }

            val nextWallpaperSelection = buildWallpaperCandidateIndexes(catalog)
                .firstNotNullOfOrNull { candidateIndex ->
                    downloadWallpaperBitmap(catalog[candidateIndex].imageUrl)
                        ?.let { bitmap -> candidateIndex to bitmap }
                }
                ?: run {
                    runOnUiThread {
                        if (!isDestroyed && !isFinishing && isTmdbWallpaperSource()) {
                            applyFeaturedHero(null, null)
                        }
                    }
                    if (showStatusToast) {
                        runOnUiThread {
                            if (!isDestroyed && !isFinishing) {
                                Toast.makeText(
                                    this,
                                    "Wallpaper refresh failed: image download was unavailable.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    return@execute
                }

            val (nextIndex, bitmap) = nextWallpaperSelection
            val nextWallpaper = catalog[nextIndex]
            val featuredHero = if (isTmdbWallpaperSource()) {
                fetchTmdbFeaturedHero(nextWallpaper)
            } else {
                null
            }
            val heroLogoBitmap = featuredHero?.logoUrl?.let(::downloadOverlayBitmap)

            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }
                applyWallpaperBitmap(bitmap)
                applyFeaturedHero(featuredHero, heroLogoBitmap)
                wallpaperIndex = nextIndex
                currentWallpaperId = nextWallpaper.imageUrl
                if (showStatusToast) {
                    Toast.makeText(
                        this,
                        "Wallpaper updated: ${nextWallpaper.title}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun loadWallpaperCatalog(forceCatalogRefresh: Boolean): List<WallpaperImage> {
        val wallpaperSource = getWallpaperSource()
        return when (wallpaperSource) {
            WallpaperSource.NASA,
            WallpaperSource.TMDB_TRENDING,
            WallpaperSource.TMDB_TRENDING_MOVIES,
            WallpaperSource.TMDB_TRENDING_TV,
            WallpaperSource.TMDB_POPULAR_MOVIES,
            WallpaperSource.TMDB_POPULAR_TV,
            WallpaperSource.REDDIT_EARTHPORN -> {
                val now = System.currentTimeMillis()
                val needsRefresh = forceCatalogRefresh ||
                    wallpaperCatalog.isEmpty() ||
                    now - wallpaperCatalogFetchedAt > getWallpaperCatalogRefreshMs()

                if (needsRefresh) {
                    val refreshedCatalog = when (wallpaperSource) {
                        WallpaperSource.NASA -> fetchNasaWallpaperCatalog()
                        WallpaperSource.TMDB_TRENDING,
                        WallpaperSource.TMDB_TRENDING_MOVIES,
                        WallpaperSource.TMDB_TRENDING_TV,
                        WallpaperSource.TMDB_POPULAR_MOVIES,
                        WallpaperSource.TMDB_POPULAR_TV -> fetchTmdbWallpaperCatalog(wallpaperSource)
                        WallpaperSource.REDDIT_EARTHPORN -> fetchRedditEarthPornWallpaperCatalog()
                        WallpaperSource.LOCAL_FOLDER,
                        WallpaperSource.FIXED_IMAGE -> emptyList()
                    }
                    if (refreshedCatalog.isNotEmpty()) {
                        wallpaperCatalog = refreshedCatalog
                        wallpaperCatalogFetchedAt = now
                    }
                }
                wallpaperCatalog
            }
            WallpaperSource.LOCAL_FOLDER -> loadLocalWallpaperCatalog()
            WallpaperSource.FIXED_IMAGE -> loadFixedWallpaperCatalog()
        }
    }

    private fun getWallpaperCatalogRefreshMs(): Long {
        return when (getWallpaperSource()) {
            WallpaperSource.TMDB_TRENDING,
            WallpaperSource.TMDB_TRENDING_MOVIES,
            WallpaperSource.TMDB_TRENDING_TV,
            WallpaperSource.TMDB_POPULAR_MOVIES,
            WallpaperSource.TMDB_POPULAR_TV -> TMDB_WALLPAPER_CATALOG_REFRESH_MS
            WallpaperSource.REDDIT_EARTHPORN -> REDDIT_WALLPAPER_CATALOG_REFRESH_MS
            WallpaperSource.NASA -> WALLPAPER_CATALOG_REFRESH_MS
            WallpaperSource.LOCAL_FOLDER,
            WallpaperSource.FIXED_IMAGE -> WALLPAPER_CATALOG_REFRESH_MS
        }
    }

    private fun isTmdbWallpaperSource(source: WallpaperSource = getWallpaperSource()): Boolean {
        return when (source) {
            WallpaperSource.TMDB_TRENDING,
            WallpaperSource.TMDB_TRENDING_MOVIES,
            WallpaperSource.TMDB_TRENDING_TV,
            WallpaperSource.TMDB_POPULAR_MOVIES,
            WallpaperSource.TMDB_POPULAR_TV -> true
            WallpaperSource.NASA,
            WallpaperSource.REDDIT_EARTHPORN,
            WallpaperSource.LOCAL_FOLDER,
            WallpaperSource.FIXED_IMAGE -> false
        }
    }

    private fun chooseNextWallpaperIndex(catalog: List<WallpaperImage>): Int {
        if (catalog.size == 1) {
            return 0
        }

        val currentIndex = currentWallpaperId
            ?.let { currentId -> catalog.indexOfFirst { it.imageUrl == currentId } }
            ?.takeIf { it >= 0 }
            ?: -1

        return if (isWallpaperShuffleEnabled()) {
            val candidateIndexes = catalog.indices.filter { it != currentIndex }
            candidateIndexes.random(Random(System.currentTimeMillis()))
        } else {
            if (currentIndex == -1) 0 else (currentIndex + 1) % catalog.size
        }
    }

    private fun buildWallpaperCandidateIndexes(catalog: List<WallpaperImage>): List<Int> {
        if (catalog.isEmpty()) {
            return emptyList()
        }
        if (catalog.size == 1) {
            return listOf(0)
        }

        val currentIndex = currentWallpaperId
            ?.let { currentId -> catalog.indexOfFirst { it.imageUrl == currentId } }
            ?.takeIf { it >= 0 }
            ?: -1

        return if (isWallpaperShuffleEnabled()) {
            val shuffled = catalog.indices
                .filter { it != currentIndex }
                .shuffled(Random(System.currentTimeMillis()))
                .toMutableList()
            if (currentIndex >= 0) {
                shuffled += currentIndex
            }
            shuffled
        } else {
            buildList {
                val startIndex = if (currentIndex == -1) 0 else (currentIndex + 1) % catalog.size
                repeat(catalog.size) { offset ->
                    add((startIndex + offset) % catalog.size)
                }
            }
        }
    }

    private fun loadLocalWallpaperCatalog(): List<WallpaperImage> {
        return loadLocalWallpaperFiles().map { file ->
            WallpaperImage(
                date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(file.lastModified())),
                title = file.nameWithoutExtension,
                imageUrl = file.absolutePath
            )
        }
    }

    private fun loadFixedWallpaperCatalog(): List<WallpaperImage> {
        val fixedPath = getFixedWallpaperPath()
        val fixedFile = fixedPath?.let(::File)?.takeIf { it.exists() && it.isFile }
        val fallbackFile = loadLocalWallpaperFiles().firstOrNull()
        val file = fixedFile ?: fallbackFile ?: return emptyList()
        return listOf(
            WallpaperImage(
                date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(file.lastModified())),
                title = file.nameWithoutExtension,
                imageUrl = file.absolutePath
            )
        )
    }

    private fun fetchNasaWallpaperCatalog(): List<WallpaperImage> {
        val endDate = Calendar.getInstance()
        val startDate = Calendar.getInstance().apply {
            timeInMillis = endDate.timeInMillis
            add(Calendar.DAY_OF_YEAR, -(NASA_APOD_LOOKBACK_DAYS - 1))
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val requestUrl = buildString {
            append("https://api.nasa.gov/planetary/apod")
            append("?api_key=").append(WallpaperPrefs.getNasaApiKey(this@MainActivity))
            append("&thumbs=true")
            append("&start_date=").append(dateFormat.format(startDate.time))
            append("&end_date=").append(dateFormat.format(endDate.time))
        }

        val connection = try {
            (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "3HPM-Lounge/1.0")
            }
        } catch (_: IOException) {
            return emptyList()
        }

        return try {
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            if (!payload.trimStart().startsWith("[")) {
                return emptyList()
            }
            val jsonArray = JSONArray(payload)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(index) ?: continue
                    if (!item.optString("media_type").equals("image", ignoreCase = true)) {
                        continue
                    }

                    val imageUrl = item.optString("url")
                        .takeIf { it.startsWith("http", ignoreCase = true) }
                        ?: continue
                    val date = item.optString("date").takeIf { it.isNotBlank() } ?: continue
                    val title = item.optString("title").ifBlank { "NASA" }

                    add(
                        WallpaperImage(
                            date = date,
                            title = title,
                            imageUrl = imageUrl
                        )
                    )
                }
            }.sortedByDescending { it.date }
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchRedditEarthPornWallpaperCatalog(): List<WallpaperImage> {
        val connection = try {
            (URL(REDDIT_EARTHPORN_FEED_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                useCaches = false
                setRequestProperty("Accept", "application/atom+xml, application/xml, text/xml")
                setRequestProperty("User-Agent", "3HPMLounge/1.0 (Android TV wallpaper feed)")
            }
        } catch (_: IOException) {
            return emptyList()
        }

        return try {
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            parseRedditEarthPornFeed(payload)
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRedditEarthPornFeed(payload: String): List<WallpaperImage> {
        return REDDIT_ENTRY_REGEX.findAll(payload)
            .mapNotNull { match ->
                val entryXml = match.groupValues[1]
                val encodedTitle = REDDIT_TITLE_REGEX.find(entryXml)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val imageUrl = REDDIT_DIRECT_IMAGE_REGEX.find(entryXml)?.value
                    ?: return@mapNotNull null
                val dimensions = parseWallpaperDimensions(encodedTitle) ?: return@mapNotNull null
                if (dimensions.first < REDDIT_MIN_LANDSCAPE_WIDTH || dimensions.first <= dimensions.second) {
                    return@mapNotNull null
                }

                val publishedDate = REDDIT_PUBLISHED_REGEX.find(entryXml)
                    ?.groupValues
                    ?.get(1)
                    ?.take(10)
                    ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

                WallpaperImage(
                    date = publishedDate,
                    title = cleanRedditWallpaperTitle(encodedTitle),
                    imageUrl = imageUrl
                )
            }
            .distinctBy { it.imageUrl }
            .take(REDDIT_WALLPAPER_LIMIT)
            .toList()
    }

    private fun fetchTmdbWallpaperCatalog(source: WallpaperSource): List<WallpaperImage> {
        val apiKey = BuildConfig.TMDB_API_KEY.trim()
        if (apiKey.isBlank()) {
            return emptyList()
        }

        val feedSpec = getTmdbFeedSpec(source)
        val requestUrl = "https://api.themoviedb.org/3/${feedSpec.path}?api_key=$apiKey&language=en-US"
        val connection = try {
            (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "3HPMLounge/1.0")
            }
        } catch (_: IOException) {
            return emptyList()
        }

        return try {
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(payload).optJSONArray("results") ?: return emptyList()
            buildList {
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val mediaType = item.optString("media_type")
                        .ifBlank { feedSpec.mediaType ?: "" }
                    if (mediaType != "movie" && mediaType != "tv") {
                        continue
                    }

                    val mediaId = item.optInt("id").takeIf { it > 0 } ?: continue
                    val backdropPath = item.optString("backdrop_path")
                        .takeIf { it.isNotBlank() }
                        ?: continue
                    val title = item.optString("title")
                        .ifBlank { item.optString("name") }
                        .ifBlank { "TMDB Trending" }
                    val date = item.optString("release_date")
                        .ifBlank { item.optString("first_air_date") }
                        .takeIf { it.isNotBlank() }
                        ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    val overview = item.optString("overview").takeIf { it.isNotBlank() }

                    add(
                        WallpaperImage(
                            date = date,
                            title = title,
                            imageUrl = "$TMDB_IMAGE_BASE_URL$backdropPath",
                            mediaType = mediaType,
                            mediaId = mediaId,
                            overview = overview
                        )
                    )
                }
            }
                .distinctBy { "${it.mediaType}:${it.mediaId}" }
                .take(TMDB_WALLPAPER_LIMIT)
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun getTmdbFeedSpec(source: WallpaperSource): TmdbFeedSpec {
        return when (source) {
            WallpaperSource.TMDB_TRENDING -> TmdbFeedSpec(
                path = "trending/all/week",
                mediaType = null,
                heroLabel = "TMDB Trending"
            )
            WallpaperSource.TMDB_TRENDING_MOVIES -> TmdbFeedSpec(
                path = "trending/movie/week",
                mediaType = "movie",
                heroLabel = "Trending Movies"
            )
            WallpaperSource.TMDB_TRENDING_TV -> TmdbFeedSpec(
                path = "trending/tv/week",
                mediaType = "tv",
                heroLabel = "Trending TV"
            )
            WallpaperSource.TMDB_POPULAR_MOVIES -> TmdbFeedSpec(
                path = "movie/popular",
                mediaType = "movie",
                heroLabel = "Popular Movies"
            )
            WallpaperSource.TMDB_POPULAR_TV -> TmdbFeedSpec(
                path = "tv/popular",
                mediaType = "tv",
                heroLabel = "Popular TV"
            )
            WallpaperSource.NASA,
            WallpaperSource.REDDIT_EARTHPORN,
            WallpaperSource.LOCAL_FOLDER,
            WallpaperSource.FIXED_IMAGE -> TmdbFeedSpec(
                path = "trending/all/week",
                mediaType = null,
                heroLabel = "TMDB"
            )
        }
    }

    private fun fetchTmdbFeaturedHero(wallpaper: WallpaperImage): FeaturedHero? {
        val mediaType = wallpaper.mediaType ?: return buildFallbackTmdbHero(wallpaper)
        val mediaId = wallpaper.mediaId ?: return buildFallbackTmdbHero(wallpaper)
        val apiKey = BuildConfig.TMDB_API_KEY.trim()
        if (apiKey.isBlank()) {
            return buildFallbackTmdbHero(wallpaper)
        }
        val heroLabel = getTmdbFeedSpec(getWallpaperSource()).heroLabel

        val requestUrl = buildString {
            append("https://api.themoviedb.org/3/")
            append(mediaType)
            append('/')
            append(mediaId)
            append("?api_key=")
            append(apiKey)
            append("&language=en-US&append_to_response=credits")
        }

        val connection = try {
            (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "3HPMLounge/1.0")
            }
        } catch (_: IOException) {
            return buildFallbackTmdbHero(wallpaper)
        }

        return try {
            val details = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val title = details.optString("title")
                .ifBlank { details.optString("name") }
                .ifBlank { wallpaper.title }
            val genres = details.optJSONArray("genres")
                ?.let { genreArray ->
                    buildList {
                        for (index in 0 until genreArray.length()) {
                            genreArray.optJSONObject(index)
                                ?.optString("name")
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::add)
                        }
                    }
                }
                .orEmpty()
                .take(3)

            val year = details.optString("release_date")
                .ifBlank { details.optString("first_air_date") }
                .take(4)
                .takeIf { it.length == 4 }

            val runtimeText = when (mediaType) {
                "movie" -> formatRuntime(details.optInt("runtime"))
                "tv" -> {
                    val seasons = details.optInt("number_of_seasons")
                    when {
                        seasons > 1 -> "$seasons Seasons"
                        seasons == 1 -> "1 Season"
                        else -> details.optJSONArray("episode_run_time")
                            ?.optInt(0)
                            ?.takeIf { it > 0 }
                            ?.let(::formatRuntime)
                    }
                }
                else -> null
            }

            val ratingText = details.optDouble("vote_average")
                .takeIf { it > 0.0 }
                ?.let { "TMDB ${(it * 10).toInt()}%" }

            val primaryMeta = listOfNotNull(
                genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
                year,
                runtimeText,
                ratingText
            ).joinToString(" • ")

            val credits = details.optJSONObject("credits")
            val castText = credits?.optJSONArray("cast")
                ?.let { castArray ->
                    buildList {
                        for (index in 0 until minOf(castArray.length(), 3)) {
                            castArray.optJSONObject(index)
                                ?.optString("name")
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::add)
                        }
                    }
                }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ")

            val creatorText = when (mediaType) {
                "movie" -> credits?.optJSONArray("crew")
                    ?.let { crewArray ->
                        buildList {
                            for (index in 0 until crewArray.length()) {
                                val crew = crewArray.optJSONObject(index) ?: continue
                                if (crew.optString("job").equals("Director", ignoreCase = true)) {
                                    crew.optString("name")
                                        .takeIf { it.isNotBlank() }
                                        ?.let(::add)
                                }
                            }
                        }
                    }
                    ?.distinct()
                    ?.take(2)
                    ?.joinToString(", ")
                "tv" -> details.optJSONArray("created_by")
                    ?.let { createdBy ->
                        buildList {
                            for (index in 0 until createdBy.length()) {
                                createdBy.optJSONObject(index)
                                    ?.optString("name")
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(::add)
                            }
                        }
                    }
                    ?.take(2)
                    ?.joinToString(", ")
                else -> null
            }

            val secondaryMeta = listOfNotNull(
                castText,
                creatorText?.let { if (mediaType == "movie") "Dir. $it" else "By $it" }
            )
                .joinToString(" • ")
                .takeIf { it.isNotBlank() }

            FeaturedHero(
                eyebrow = heroLabel,
                title = title,
                primaryMeta = primaryMeta.ifBlank { mediaType.uppercase(Locale.getDefault()) },
                secondaryMeta = secondaryMeta,
                overview = details.optString("overview")
                    .takeIf { it.isNotBlank() }
                    ?.let(::trimHeroOverview)
                    ?: wallpaper.overview?.let(::trimHeroOverview),
                logoUrl = fetchTmdbLogoUrl(mediaType, mediaId)
            )
        } catch (_: Exception) {
            buildFallbackTmdbHero(wallpaper)
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchTmdbLogoUrl(mediaType: String, mediaId: Int): String? {
        val apiKey = BuildConfig.TMDB_API_KEY.trim()
        if (apiKey.isBlank()) {
            return null
        }

        val requestUrl = buildString {
            append("https://api.themoviedb.org/3/")
            append(mediaType)
            append('/')
            append(mediaId)
            append("/images?api_key=")
            append(apiKey)
            append("&include_image_language=en,null")
        }

        val connection = try {
            (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "3HPMLounge/1.0")
            }
        } catch (_: IOException) {
            return null
        }

        return try {
            val payload = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val logos = payload.optJSONArray("logos") ?: return null
            var bestPath: String? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (index in 0 until logos.length()) {
                val logo = logos.optJSONObject(index) ?: continue
                val filePath = logo.optString("file_path").takeIf { it.isNotBlank() } ?: continue
                val language = logo.optString("iso_639_1")
                val voteAverage = logo.optDouble("vote_average", 0.0)
                val languageBoost = when {
                    language.equals("en", ignoreCase = true) -> 2.0
                    language.isBlank() || language.equals("null", ignoreCase = true) -> 1.0
                    else -> 0.0
                }
                val score = voteAverage + languageBoost
                if (score > bestScore) {
                    bestScore = score
                    bestPath = filePath
                }
            }
            bestPath?.let { "$TMDB_LOGO_BASE_URL$it" }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun buildFallbackTmdbHero(wallpaper: WallpaperImage): FeaturedHero {
        return FeaturedHero(
            eyebrow = getTmdbFeedSpec(getWallpaperSource()).heroLabel,
            title = wallpaper.title,
            primaryMeta = listOfNotNull(
                wallpaper.mediaType?.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                },
                wallpaper.date.take(4).takeIf { it.length == 4 }
            ).joinToString(" • ").ifBlank { "Featured" },
            secondaryMeta = null,
            overview = wallpaper.overview?.let(::trimHeroOverview),
            logoUrl = null
        )
    }

    private fun formatRuntime(runtimeMinutes: Int): String? {
        if (runtimeMinutes <= 0) {
            return null
        }
        val hours = runtimeMinutes / 60
        val minutes = runtimeMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    private fun trimHeroOverview(overview: String): String {
        val normalized = overview.replace(Regex("\\s+"), " ").trim()
        return if (normalized.length <= HERO_OVERVIEW_MAX_CHARS) {
            normalized
        } else {
            normalized.take(HERO_OVERVIEW_MAX_CHARS - 3).trimEnd() + "..."
        }
    }

    private fun parseWallpaperDimensions(title: String): Pair<Int, Int>? {
        val decodedTitle = decodeHtmlText(title)
        val match = REDDIT_DIMENSION_REGEX.find(decodedTitle) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        return width to height
    }

    private fun cleanRedditWallpaperTitle(title: String): String {
        return decodeHtmlText(title)
            .replace(Regex("\\[(?:OC|oc)\\]"), "")
            .replace(Regex("\\((?:OC|oc)\\)"), "")
            .replace(Regex("[\\[(](\\d{3,5})\\s*[x×X]\\s*(\\d{3,5})[\\])]"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .trim('-', '•', ',', ' ')
            .ifBlank { "EarthPorn" }
    }

    private fun decodeHtmlText(value: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(value).toString()
        }
    }

    private fun downloadWallpaperBitmap(imageUrl: String): Bitmap? {
        if (!imageUrl.startsWith("http", ignoreCase = true)) {
            return BitmapFactory.decodeFile(imageUrl)
        }

        val connection = try {
            (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 12000
                useCaches = true
                setRequestProperty("User-Agent", "3HPM-Lounge/1.0")
            }
        } catch (_: IOException) {
            return null
        }

        return try {
            val imageBytes = connection.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)

            val decodeOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = calculateInSampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    resources.displayMetrics.widthPixels,
                    resources.displayMetrics.heightPixels
                )
            }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadOverlayBitmap(imageUrl: String): Bitmap? {
        val connection = try {
            (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 10000
                useCaches = true
                setRequestProperty("User-Agent", "3HPMLounge/1.0")
            }
        } catch (_: IOException) {
            return null
        }

        return try {
            val imageBytes = connection.inputStream.use { it.readBytes() }
            val decodeOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun applyFeaturedHero(featuredHero: FeaturedHero?, logoBitmap: Bitmap?) {
        if (featuredHero == null) {
            binding.heroContainer.visibility = View.GONE
            updateAppRowsTopOffset(heroVisible = false)
            binding.heroLogo.setImageDrawable(null)
            binding.heroLogo.visibility = View.GONE
            binding.heroTitle.visibility = View.GONE
            binding.heroMetaSecondary.visibility = View.GONE
            binding.heroOverview.visibility = View.GONE
            updateAppCardTranslucency(binding.appRowsScroll.scrollY)
            return
        }

        binding.heroContainer.visibility = View.VISIBLE
        updateAppRowsTopOffset(heroVisible = true)
        binding.heroEyebrow.text = featuredHero.eyebrow

        if (logoBitmap != null) {
            binding.heroLogo.setImageBitmap(logoBitmap)
            binding.heroLogo.visibility = View.VISIBLE
            binding.heroTitle.visibility = View.GONE
        } else {
            binding.heroLogo.setImageDrawable(null)
            binding.heroLogo.visibility = View.GONE
            binding.heroTitle.visibility = View.VISIBLE
            binding.heroTitle.text = featuredHero.title
        }

        if (binding.heroTitle.visibility == View.VISIBLE) {
            binding.heroTitle.text = featuredHero.title
        }

        binding.heroMetaPrimary.text = featuredHero.primaryMeta
        binding.heroMetaSecondary.text = featuredHero.secondaryMeta
        binding.heroMetaSecondary.visibility =
            if (featuredHero.secondaryMeta.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.heroOverview.text = featuredHero.overview
        binding.heroOverview.visibility =
            if (featuredHero.overview.isNullOrBlank()) View.GONE else View.VISIBLE
        updateAppCardTranslucency(binding.appRowsScroll.scrollY)
    }

    private fun updateAppRowsTopOffset(heroVisible: Boolean) {
        (binding.appRowsScroll.layoutParams as? LinearLayout.LayoutParams)?.apply {
            topMargin = if (heroVisible) dp(4) else dp(10)
            binding.appRowsScroll.layoutParams = this
        }
    }

    private fun calculateInSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        var sampleSize = 1
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return sampleSize
        }

        while ((sourceWidth / sampleSize) > targetWidth * 1.6f ||
            (sourceHeight / sampleSize) > targetHeight * 1.6f
        ) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun applyWallpaperBitmap(bitmap: Bitmap) {
        val incomingView = if (showingPrimaryWallpaper) binding.wallpaperSecondary else binding.wallpaperPrimary
        val outgoingView = if (showingPrimaryWallpaper) binding.wallpaperPrimary else binding.wallpaperSecondary

        wallpaperCrossfadeAnimator?.cancel()
        incomingView.setImageBitmap(bitmap)
        incomingView.alpha = 0f

        wallpaperCrossfadeAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(incomingView, View.ALPHA, 0f, 0.96f),
                ObjectAnimator.ofFloat(outgoingView, View.ALPHA, outgoingView.alpha, 0f)
            )
            duration = 1400L
            interpolator = DecelerateInterpolator()
            start()
        }

        showingPrimaryWallpaper = !showingPrimaryWallpaper
    }

    private fun fetchWeatherInfo(): WeatherInfo? {
        val weatherQuery = WeatherPrefs.getQuery(this)
        val weather = if (weatherQuery.isNullOrBlank()) {
            fetchAutoWeatherInfo()
        } else {
            fetchWeatherInfoForQuery(weatherQuery)
        }
        return weather?.also(::cacheWeatherInfo)
    }

    private fun fetchWeatherInfoForQuery(weatherQuery: String): WeatherInfo? {
        repeat(2) { attempt ->
            val geocodeUrl =
                "https://geocoding-api.open-meteo.com/v1/search?name=${
                    Uri.encode(weatherQuery)
                }&count=1&language=en&format=json"
            val geocodeConnection = openWeatherConnection(geocodeUrl) ?: return null

            try {
                val geocodeJson = JSONObject(
                    geocodeConnection.inputStream.bufferedReader().use { it.readText() }
                )
                val geocodeResult = geocodeJson.optJSONArray("results")?.optJSONObject(0)
                    ?: throw IOException("No location match")

                val latitude = geocodeResult.optDouble("latitude", Double.NaN)
                val longitude = geocodeResult.optDouble("longitude", Double.NaN)
                if (latitude.isNaN() || longitude.isNaN()) {
                    throw IOException("Location match missing coordinates")
                }

                val location = formatOpenMeteoLocation(geocodeResult, weatherQuery)
                val weatherUnit = WeatherPrefs.getUnit(this)
                val temperatureUnit =
                    if (weatherUnit == WeatherUnit.FAHRENHEIT) "fahrenheit" else "celsius"
                val forecastUrl = buildString {
                    append("https://api.open-meteo.com/v1/forecast?")
                    append("latitude=").append(latitude)
                    append("&longitude=").append(longitude)
                    append("&current=temperature_2m,apparent_temperature,weather_code,is_day")
                    append("&temperature_unit=").append(temperatureUnit)
                    append("&timezone=auto")
                    append("&forecast_days=1")
                }

                val forecastConnection = openWeatherConnection(forecastUrl) ?: return null
                try {
                    val forecastJson = JSONObject(
                        forecastConnection.inputStream.bufferedReader().use { it.readText() }
                    )
                    val current = forecastJson.optJSONObject("current")
                        ?: throw IOException("Weather payload missing current conditions")

                    val temperatureValue = current.optDouble("temperature_2m", Double.NaN)
                    val apparentValue = current.optDouble("apparent_temperature", Double.NaN)
                    val weatherCode = current.optInt("weather_code", Int.MIN_VALUE)
                    val isDay = current.optInt("is_day", 1) == 1

                    val summaryParts = mutableListOf<String>()
                    if (weatherCode != Int.MIN_VALUE) {
                        summaryParts += describeOpenMeteoWeatherCode(weatherCode, isDay)
                    }
                    if (!apparentValue.isNaN()) {
                        summaryParts += "Feels like ${
                            formatWeatherTemperature(apparentValue, weatherUnit)
                        }"
                    }

                    return WeatherInfo(
                        location = location,
                        temperature = if (temperatureValue.isNaN()) {
                            "--"
                        } else {
                            formatWeatherTemperature(temperatureValue, weatherUnit)
                        },
                        summary = summaryParts.joinToString(" • ").ifBlank { "Current conditions" }
                    )
                } finally {
                    forecastConnection.disconnect()
                }
            } catch (_: Exception) {
                if (attempt == 0) {
                    Thread.sleep(700L)
                }
            } finally {
                geocodeConnection.disconnect()
            }
        }

        return null
    }

    private fun fetchAutoWeatherInfo(): WeatherInfo? {
        repeat(2) { attempt ->
            val connection = openWeatherConnection("https://wttr.in/?format=j1&lang=en")
                ?: return null

            try {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val currentCondition = json.optJSONArray("current_condition")?.optJSONObject(0)
                    ?: throw IOException("Weather payload missing current condition")
                val nearestArea = json.optJSONArray("nearest_area")?.optJSONObject(0)
                val location = nearestArea
                    ?.optJSONArray("areaName")
                    ?.optJSONObject(0)
                    ?.optString("value")
                    ?.takeIf { it.isNotBlank() }
                    ?: "Local Weather"

                val weatherUnit = WeatherPrefs.getUnit(this)
                val temperatureKey =
                    if (weatherUnit == WeatherUnit.FAHRENHEIT) "temp_F" else "temp_C"
                val feelsLikeKey =
                    if (weatherUnit == WeatherUnit.FAHRENHEIT) "FeelsLikeF" else "FeelsLikeC"

                val temperature = currentCondition.optString(temperatureKey)
                    .takeIf { it.isNotBlank() }
                    ?.let { "$it${weatherUnit.suffix}" }
                    ?: "--"

                val summaryParts = mutableListOf<String>()
                currentCondition.optJSONArray("weatherDesc")
                    ?.optJSONObject(0)
                    ?.optString("value")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(summaryParts::add)

                currentCondition.optString(feelsLikeKey)
                    .takeIf { it.isNotBlank() }
                    ?.let { summaryParts += "Feels like ${it}${weatherUnit.suffix}" }

                return WeatherInfo(
                    location = location,
                    temperature = temperature,
                    summary = summaryParts.joinToString(" • ").ifBlank { "Current conditions" }
                )
            } catch (_: Exception) {
                if (attempt == 0) {
                    Thread.sleep(700L)
                }
            } finally {
                connection.disconnect()
            }
        }

        return null
    }

    private fun openWeatherConnection(url: String): HttpURLConnection? {
        return try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "FireLauncher/1.0")
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun formatOpenMeteoLocation(locationJson: JSONObject, fallbackQuery: String): String {
        val name = locationJson.optString("name").takeIf { it.isNotBlank() }
        val admin1 = locationJson.optString("admin1").takeIf { it.isNotBlank() }
        val country = locationJson.optString("country").takeIf { it.isNotBlank() }

        val parts = buildList {
            name?.let(::add)
            admin1?.takeIf { it != name }?.let(::add)
            country?.takeIf { it != admin1 }?.let(::add)
        }

        return parts.joinToString(", ").ifBlank { fallbackQuery }
    }

    private fun formatWeatherTemperature(
        value: Double,
        weatherUnit: WeatherUnit
    ): String = "${value.roundToInt()}${weatherUnit.suffix}"

    private fun describeOpenMeteoWeatherCode(code: Int, isDay: Boolean): String {
        return when (code) {
            0 -> if (isDay) "Clear sky" else "Clear night"
            1 -> if (isDay) "Mainly clear" else "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51 -> "Light drizzle"
            53 -> "Drizzle"
            55 -> "Heavy drizzle"
            56, 57 -> "Freezing drizzle"
            61 -> "Light rain"
            63 -> "Rain"
            65 -> "Heavy rain"
            66, 67 -> "Freezing rain"
            71 -> "Light snow"
            73 -> "Snow"
            75 -> "Heavy snow"
            77 -> "Snow grains"
            80 -> "Light rain showers"
            81 -> "Rain showers"
            82 -> "Heavy rain showers"
            85 -> "Light snow showers"
            86 -> "Heavy snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> "Current conditions"
        }
    }

    private fun formatSportsTickerText(entries: List<SportsEntry>): String {
        val (liveEntries, upcomingEntries) = splitSportsEntries(entries)
        val liveSegments = liveEntries
            .take(sportsLiveTickerLimit)
            .map(::formatLiveTickerEntry)
            .filter { it.isNotBlank() }
        val upcomingSegments = upcomingEntries
            .take(tickerItemLimit)
            .map(::formatSportsTickerEntry)
            .filter { it.isNotBlank() }

        val sections = buildList {
            if (liveSegments.isNotEmpty()) {
                add("LIVE NOW: " + liveSegments.joinToString("     "))
            }
            if (upcomingSegments.isNotEmpty()) {
                add("UPCOMING: " + upcomingSegments.joinToString("     "))
            }
        }

        return if (sections.isEmpty()) {
            "Sports feed unavailable right now"
        } else {
            val tickerText = sections.joinToString("     ")
            val marqueeText = if (tickerText.length < 140) {
                "$tickerText     $tickerText"
            } else {
                tickerText
            }
            "   $marqueeText"
        }
    }

    private fun splitSportsEntries(
        entries: List<SportsEntry>,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<List<SportsEntry>, List<SportsEntry>> {
        val liveEntries = entries.filter { entry ->
            entry.startsAt.time in (nowMs - sportsLiveWindowMs)..nowMs
        }
        val upcomingEntries = entries.filter { entry ->
            entry.startsAt.time > nowMs
        }
        return liveEntries to upcomingEntries
    }

    private fun fetchSportsEntries(): List<SportsEntry> {
        return sportsFeeds
            .flatMap(::fetchSportsEntriesForSource)
            .sortedWith(
                compareBy<SportsEntry> { it.startsAt.time }
                    .thenBy { sportsFeedOrder(it.feedKey) }
                    .thenBy { it.channelLabel ?: "" }
                    .thenBy { it.title.lowercase(Locale.getDefault()) }
            )
    }

    private fun fetchSportsEntriesForSource(source: SportsFeedSource): List<SportsEntry> {
        val connection = try {
            (URL(source.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "3HPM-Lounge/1.0")
            }
        } catch (_: IOException) {
            return emptyList()
        }

        return try {
            connection.inputStream.bufferedReader().use { reader ->
                parseSportsEntries(
                    source = source,
                    payload = JSONObject(reader.readText())
                )
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSportsEntries(source: SportsFeedSource, payload: JSONObject): List<SportsEntry> {
        val dateText = payload.optString("date").takeIf { it.isNotBlank() } ?: return emptyList()
        val timezoneId = payload.optString("timezone").takeIf { it.isNotBlank() }
            ?: "America/New_York"
        val events = payload.optJSONArray("events") ?: return emptyList()
        val parser = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone(timezoneId)
        }

        val parsedEntries = mutableListOf<SportsEntry>()
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            val channel = event.optString("channel")
                .takeIf { it.isNotBlank() }
                ?.let(::normalizeSportsChannel)
            val timeText = event.optString("time").takeIf { it.isNotBlank() } ?: continue
            val title = event.optString("title").takeIf { it.isNotBlank() } ?: continue
            val startsAt = runCatching {
                parser.parse("$dateText $timeText")
            }.getOrNull() ?: continue

            parsedEntries += SportsEntry(
                feedKey = source.key,
                feedLabel = source.label,
                channelLabel = channel,
                title = normalizeSportsTitle(title),
                startsAt = startsAt
            )
        }

        return parsedEntries
    }

    private fun normalizeSportsTitle(rawTitle: String): String {
        return rawTitle
            .replace(Regex("\\s+"), " ")
            .replace("(ESP)", "(En Espanol)")
            .trim()
    }

    private fun normalizeSportsChannel(rawChannel: String): String {
        val compact = rawChannel.replace(Regex("\\s+"), "").uppercase(Locale.getDefault())
        Regex("^(PPV)(\\d)(\\d{2})$").matchEntire(compact)?.let { match ->
            return "${match.groupValues[1]}${match.groupValues[2]} ${match.groupValues[3]}"
        }

        Regex("^([A-Z]+)(\\d{2})$").matchEntire(compact)?.let { match ->
            return "${match.groupValues[1]} ${match.groupValues[2]}"
        }

        Regex("^([A-Z]+)(\\d+)$").matchEntire(compact)?.let { match ->
            return "${match.groupValues[1]} ${match.groupValues[2]}"
        }

        return rawChannel.trim()
    }

    private fun formatLiveTickerEntry(entry: SportsEntry): String {
        val parts = mutableListOf<String>()
        parts += entry.channelLabel ?: entry.feedLabel
        parts += shortenSportsTitle(entry.title)
        return parts.joinToString(" • ")
    }

    private fun formatSportsTickerEntry(entry: SportsEntry): String {
        val parts = mutableListOf(formatSportsTickerWhen(entry.startsAt))
        parts += entry.channelLabel ?: entry.feedLabel
        parts += shortenSportsTitle(entry.title)
        return parts.joinToString(" • ")
    }

    private fun formatSportsTickerWhen(date: Date): String {
        val target = Calendar.getInstance().apply { time = date }
        val now = Calendar.getInstance()
        val prefix = when {
            isSameDay(now, target) -> "Today"
            isTomorrow(now, target) -> "Tomorrow"
            else -> SimpleDateFormat("EEE", Locale.getDefault()).format(date)
        }
        val timeText = SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        return "$prefix $timeText"
    }

    private fun shortenSportsTitle(title: String): String {
        return title
            .replace(Regex("\\s+"), " ")
            .trim()
            .let {
                when {
                    it.length <= 54 -> it
                    it.contains(" vs. ", ignoreCase = true) -> it.substringBefore(" vs. ").trim()
                    it.contains(" at ", ignoreCase = true) -> it.substringBeforeLast(" at ").trim()
                    else -> it.take(51).trimEnd() + "..."
                }
            }
    }

    private fun showSportsGuideDialog() {
        if (sportsEntries.isEmpty()) {
            Toast.makeText(this, "Sports listings are still loading.", Toast.LENGTH_SHORT).show()
            return
        }

        val (liveEntries, upcomingEntries) = splitSportsEntries(sportsEntries)
        val timezoneLabel = TimeZone.getDefault().getDisplayName(
            false,
            TimeZone.SHORT,
            Locale.getDefault()
        )
        val guideText = buildString {
            append("Times shown in ")
            append(timezoneLabel)
            append('.')
            append("\n\n")
            append(
                buildSportsGuideSection(
                    title = "LIVE NOW",
                    entries = liveEntries,
                    emptyMessage = "No live events started in the last 2 hours."
                )
            )
            append("\n\n")
            append(
                buildSportsGuideSection(
                    title = "UPCOMING",
                    entries = upcomingEntries,
                    emptyMessage = "No upcoming events right now."
                )
            )
        }

        val messageView = TextView(this).apply {
            text = guideText
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(2).toFloat(), 1.1f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        val scrollView = android.widget.ScrollView(this).apply {
            addView(
                messageView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Sports Feed")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun buildSportsGuideSection(
        title: String,
        entries: List<SportsEntry>,
        emptyMessage: String
    ): String {
        if (entries.isEmpty()) {
            return "$title\n$emptyMessage"
        }

        return buildString {
            append(title)
            append('\n')
            entries
                .groupBy { it.feedKey }
                .toList()
                .sortedBy { (feedKey, _) -> sportsFeedOrder(feedKey) }
                .forEachIndexed { index, (_, feedEntries) ->
                    if (index > 0) {
                        append('\n')
                    }
                    append(feedEntries.first().feedLabel)
                    append('\n')
                    feedEntries.forEach { entry ->
                        append(formatSportsGuideLine(entry))
                        append('\n')
                    }
                }
        }.trimEnd()
    }

    private fun formatSportsGuideLine(entry: SportsEntry): String {
        val whenText = formatSportsTickerWhen(entry.startsAt)
        val channelText = entry.channelLabel?.let { "$it  " } ?: ""
        return "$whenText  $channelText${entry.title}"
    }

    private fun sportsFeedOrder(feedKey: String): Int {
        val order = sportsFeeds.indexOfFirst { it.key == feedKey }
        return if (order >= 0) order else Int.MAX_VALUE
    }

    private fun isSameDay(first: Calendar, second: Calendar): Boolean {
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
    }

    private fun isTomorrow(today: Calendar, target: Calendar): Boolean {
        val tomorrow = today.clone() as Calendar
        tomorrow.add(Calendar.DAY_OF_YEAR, 1)
        return isSameDay(tomorrow, target)
    }

    private fun loadFavoritePackages(sourceApps: List<AppItem>): List<AppItem> {
        val packageOrder = loadFavoritePackageOrder()
        if (packageOrder.isEmpty()) {
            return emptyList()
        }

        val appMap = sourceApps.associateBy { it.packageName }
        return packageOrder.mapNotNull(appMap::get)
    }

    private fun loadRecentApps(sourceApps: List<AppItem>): List<AppItem> {
        val packageOrder = readStringList(PREF_RECENT_PACKAGES)
        if (packageOrder.isEmpty()) {
            return emptyList()
        }

        val appMap = sourceApps.associateBy { it.packageName }
        return packageOrder.mapNotNull(appMap::get)
    }

    private fun loadFavoritePackageOrder(): List<String> {
        val savedPackages = readStringList(PREF_FAVORITE_PACKAGES)
        return when {
            launcherPrefs.contains(PREF_FAVORITE_PACKAGES) -> savedPackages
            else -> defaultFavoritePackages
        }
    }

    private fun toggleFavorite(packageName: String): Boolean {
        val currentPackages = loadFavoritePackageOrder().toMutableList()
        val nowFavorite = if (currentPackages.remove(packageName)) {
            false
        } else {
            currentPackages.add(0, packageName)
            true
        }
        writeStringList(PREF_FAVORITE_PACKAGES, currentPackages)
        return nowFavorite
    }

    private fun removeFavorite(packageName: String) {
        val currentPackages = loadFavoritePackageOrder().toMutableList()
        if (currentPackages.remove(packageName)) {
            writeStringList(PREF_FAVORITE_PACKAGES, currentPackages)
        }
    }

    private fun isFavoritePackage(packageName: String): Boolean {
        return loadFavoritePackageOrder().contains(packageName)
    }

    private fun moveFavorite(packageName: String, direction: Int): Boolean {
        val currentPackages = loadFavoritePackageOrder().toMutableList()
        val currentIndex = currentPackages.indexOf(packageName)
        if (currentIndex == -1) {
            return false
        }

        val targetIndex = (currentIndex + direction).coerceIn(0, currentPackages.lastIndex)
        if (targetIndex == currentIndex) {
            return false
        }

        currentPackages.removeAt(currentIndex)
        currentPackages.add(targetIndex, packageName)
        writeStringList(PREF_FAVORITE_PACKAGES, currentPackages)
        return true
    }

    private fun loadHiddenPackages(): Set<String> {
        return readStringList(PREF_HIDDEN_PACKAGES).toSet()
    }

    private fun isPackageHidden(packageName: String): Boolean {
        return packageName in loadHiddenPackages()
    }

    private fun setPackageHidden(packageName: String, hidden: Boolean) {
        val hiddenPackages = loadHiddenPackages().toMutableList()
        if (hidden) {
            if (packageName !in hiddenPackages) {
                hiddenPackages += packageName
            }
        } else {
            hiddenPackages.remove(packageName)
        }
        writeStringList(PREF_HIDDEN_PACKAGES, hiddenPackages.sorted())
    }

    private fun loadCategoryAssignments(): Map<String, String> {
        val raw = launcherPrefs.getString(PREF_CATEGORY_ASSIGNMENTS, null) ?: return emptyMap()
        return runCatching {
            val jsonObject = JSONObject(raw)
            buildMap {
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val packageName = keys.next()
                    jsonObject.optString(packageName)
                        .takeIf { it.isNotBlank() }
                        ?.let { put(packageName, it) }
                }
            }
        }.getOrElse { emptyMap() }
    }

    private fun writeCategoryAssignments(assignments: Map<String, String>) {
        val jsonObject = JSONObject()
        assignments.toSortedMap().forEach { (packageName, categoryId) ->
            jsonObject.put(packageName, categoryId)
        }
        launcherPrefs.edit()
            .putString(PREF_CATEGORY_ASSIGNMENTS, jsonObject.toString())
            .apply()
    }

    private fun assignCategory(packageName: String, categoryId: String) {
        val assignments = loadCategoryAssignments().toMutableMap()
        assignments[packageName] = categoryId
        writeCategoryAssignments(assignments)
    }

    private fun clearCategoryAssignment(packageName: String) {
        val assignments = loadCategoryAssignments().toMutableMap()
        if (assignments.remove(packageName) != null) {
            writeCategoryAssignments(assignments)
        }
    }

    private fun loadCustomCategories(): List<CustomCategory> {
        val raw = launcherPrefs.getString(PREF_CUSTOM_CATEGORIES, null) ?: return emptyList()
        return runCatching {
            val jsonArray = JSONArray(raw)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val title = item.optString("title").trim()
                    if (id.isNotBlank() && title.isNotBlank()) {
                        add(CustomCategory(id = id, title = title))
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun writeCustomCategories(categories: List<CustomCategory>) {
        val jsonArray = JSONArray()
        categories.forEach { category ->
            jsonArray.put(
                JSONObject().apply {
                    put("id", category.id)
                    put("title", category.title)
                }
            )
        }
        launcherPrefs.edit()
            .putString(PREF_CUSTOM_CATEGORIES, jsonArray.toString())
            .apply()
    }

    private fun createCustomCategory(categoryName: String): String {
        val trimmedName = categoryName.trim()
        val existingCategory = loadCustomCategories()
            .firstOrNull { it.title.equals(trimmedName, ignoreCase = true) }
        if (existingCategory != null) {
            return existingCategory.id
        }

        val currentCategories = loadCustomCategories().toMutableList()
        val existingIds = currentCategories.map { it.id }.toSet()
        val baseId = buildCustomCategoryId(trimmedName)
        var uniqueId = baseId
        var suffix = 2
        while (uniqueId in existingIds) {
            uniqueId = "${baseId}_$suffix"
            suffix++
        }

        currentCategories += CustomCategory(id = uniqueId, title = trimmedName)
        writeCustomCategories(currentCategories)
        return uniqueId
    }

    private fun renameCustomCategory(categoryId: String, newName: String) {
        val renamedCategories = loadCustomCategories().map { category ->
            if (category.id == categoryId) category.copy(title = newName.trim()) else category
        }
        writeCustomCategories(renamedCategories)
    }

    private fun deleteCustomCategory(categoryId: String) {
        val remainingCategories = loadCustomCategories().filterNot { it.id == categoryId }
        writeCustomCategories(remainingCategories)
        val remainingAssignments = loadCategoryAssignments().filterValues { it != categoryId }
        writeCategoryAssignments(remainingAssignments)
    }

    private fun loadCategoryTitleOverrides(): Map<String, String> {
        val raw = launcherPrefs.getString(PREF_CATEGORY_TITLE_OVERRIDES, null) ?: return emptyMap()
        return runCatching {
            val jsonObject = JSONObject(raw)
            buildMap {
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val categoryId = keys.next()
                    jsonObject.optString(categoryId)
                        .takeIf { it.isNotBlank() }
                        ?.let { put(categoryId, it) }
                }
            }
        }.getOrElse { emptyMap() }
    }

    private fun writeCategoryTitleOverrides(overrides: Map<String, String>) {
        val jsonObject = JSONObject()
        overrides.toSortedMap().forEach { (categoryId, title) ->
            jsonObject.put(categoryId, title)
        }
        launcherPrefs.edit()
            .putString(PREF_CATEGORY_TITLE_OVERRIDES, jsonObject.toString())
            .apply()
    }

    private fun getCategoryTitle(categoryId: String, defaultTitle: String): String {
        return loadCategoryTitleOverrides()[categoryId] ?: defaultTitle
    }

    private fun renameCategoryTitle(categoryId: String, newName: String) {
        val overrides = loadCategoryTitleOverrides().toMutableMap()
        overrides[categoryId] = newName.trim()
        writeCategoryTitleOverrides(overrides)
    }

    private fun buildCustomCategoryId(categoryName: String): String {
        val slug = categoryName
            .trim()
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim { it == '_' }
            .ifBlank { "category_${System.currentTimeMillis()}" }
        return "custom_$slug"
    }

    private fun resolveCustomCategoryAccent(categoryId: String): Int {
        val palette = listOf(
            R.color.accent_blue,
            R.color.accent_mint,
            R.color.accent_amber,
            R.color.accent_coral,
            R.color.accent_silver
        )
        val paletteIndex = Math.floorMod(categoryId.hashCode(), palette.size)
        return ContextCompat.getColor(this, palette[paletteIndex])
    }

    private fun recordRecentLaunch(packageName: String) {
        val recentPackages = readStringList(PREF_RECENT_PACKAGES).toMutableList()
        recentPackages.remove(packageName)
        recentPackages.add(0, packageName)
        writeStringList(PREF_RECENT_PACKAGES, recentPackages.take(maxRecentApps))
    }

    private fun readStringList(key: String): List<String> {
        val raw = launcherPrefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val jsonArray = JSONArray(raw)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    jsonArray.optString(index)
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun writeStringList(key: String, values: List<String>) {
        launcherPrefs.edit()
            .putString(key, JSONArray(values).toString())
            .apply()
    }

    private fun cacheWeatherInfo(weatherInfo: WeatherInfo) {
        launcherPrefs.edit()
            .putString(WeatherPrefs.PREF_LOCATION, weatherInfo.location)
            .putString(WeatherPrefs.PREF_TEMPERATURE, weatherInfo.temperature)
            .putString(WeatherPrefs.PREF_SUMMARY, weatherInfo.summary)
            .apply()
    }

    private fun loadCachedWeatherInfo(): WeatherInfo? {
        val location = launcherPrefs.getString(WeatherPrefs.PREF_LOCATION, null)
        val temperature = launcherPrefs.getString(WeatherPrefs.PREF_TEMPERATURE, null)
        val summary = launcherPrefs.getString(WeatherPrefs.PREF_SUMMARY, null)

        return if (location.isNullOrBlank() || temperature.isNullOrBlank() || summary.isNullOrBlank()) {
            null
        } else {
            WeatherInfo(
                location = location,
                temperature = temperature,
                summary = summary
            )
        }
    }

    private fun hasWideBanner(drawable: Drawable?): Boolean {
        if (drawable == null) {
            return false
        }

        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        return width > 0 && height > 0 && width >= height * 1.35f
    }

    private fun deriveAccentColor(drawable: Drawable, fallbackColor: Int): Int {
        val bitmap = drawableToBitmap(drawable, 48, 48) ?: return fallbackColor
        val hsl = FloatArray(3)
        var redTotal = 0.0
        var greenTotal = 0.0
        var blueTotal = 0.0
        var weightTotal = 0.0

        for (x in 0 until bitmap.width step 2) {
            for (y in 0 until bitmap.height step 2) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = Color.alpha(pixel) / 255f
                if (alpha < 0.5f) {
                    continue
                }

                ColorUtils.colorToHSL(pixel, hsl)
                val saturation = hsl[1]
                val lightness = hsl[2]
                if (saturation < 0.18f || lightness < 0.12f || lightness > 0.86f) {
                    continue
                }

                val weight = (0.65f + saturation) * alpha
                redTotal += Color.red(pixel) * weight
                greenTotal += Color.green(pixel) * weight
                blueTotal += Color.blue(pixel) * weight
                weightTotal += weight
            }
        }

        if (weightTotal <= 0.01) {
            return fallbackColor
        }

        val derivedColor = Color.rgb(
            (redTotal / weightTotal).toInt().coerceIn(0, 255),
            (greenTotal / weightTotal).toInt().coerceIn(0, 255),
            (blueTotal / weightTotal).toInt().coerceIn(0, 255)
        )

        ColorUtils.colorToHSL(derivedColor, hsl)
        hsl[1] = hsl[1].coerceAtLeast(0.42f)
        hsl[2] = hsl[2].coerceIn(0.36f, 0.68f)
        return ColorUtils.blendARGB(ColorUtils.HSLToColor(hsl), fallbackColor, 0.14f)
    }

    private fun drawableToBitmap(drawable: Drawable, width: Int, height: Int): Bitmap? {
        return runCatching {
            val safeWidth = width.coerceAtLeast(1)
            val safeHeight = height.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable.mutate()
            copy.setBounds(0, 0, canvas.width, canvas.height)
            copy.draw(canvas)
            bitmap
        }.getOrNull()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private companion object {
        const val PREF_FAVORITE_PACKAGES = "favorite_packages"
        const val PREF_RECENT_PACKAGES = "recent_packages"
        const val PREF_HIDDEN_PACKAGES = "hidden_packages"
        const val PREF_CATEGORY_ASSIGNMENTS = "category_assignments"
        const val PREF_CATEGORY_TITLE_OVERRIDES = "category_title_overrides"
        const val PREF_CUSTOM_CATEGORIES = "custom_categories"
        const val SECTION_ID_FAVOURITES = "favourites"
        const val SECTION_ID_RECENT = "recent"
        const val SECTION_ID_UNCATEGORIZED = "uncategorized"
        const val SECTION_ID_ALL_APPS = "all_apps"
        const val CATEGORY_ID_TV_APPS = "tv_apps"
        const val CATEGORY_ID_FREE_VOD = "free_vod"
        const val CATEGORY_ID_PAID_APPS = "paid_apps"
        const val CATEGORY_ID_UTILITIES = "utilities"
        const val REQUEST_WALLPAPER_PERMISSION = 3001
        const val WALLPAPER_CATALOG_REFRESH_MS = 12 * 60 * 60_000L
        const val TMDB_WALLPAPER_CATALOG_REFRESH_MS = 60 * 60_000L
        const val REDDIT_WALLPAPER_CATALOG_REFRESH_MS = 3 * 60 * 60_000L
        const val NASA_APOD_LOOKBACK_DAYS = 8
        const val TMDB_WALLPAPER_LIMIT = 18
        const val HERO_OVERVIEW_MAX_CHARS = 220
        const val HERO_CARD_ALPHA_SCROLL_RANGE = 220
        const val REDDIT_EARTHPORN_FEED_URL = "https://www.reddit.com/r/EarthPorn/.rss"
        const val REDDIT_MIN_LANDSCAPE_WIDTH = 1600
        const val REDDIT_WALLPAPER_LIMIT = 24
        const val TMDB_IMAGE_BASE_URL = "https://image.tmdb.org/t/p/original"
        const val TMDB_LOGO_BASE_URL = "https://image.tmdb.org/t/p/w780"
        val REDDIT_ENTRY_REGEX = Regex(
            "<entry>(.*?)</entry>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val REDDIT_TITLE_REGEX = Regex(
            "<title>(.*?)</title>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val REDDIT_PUBLISHED_REGEX = Regex(
            "<(?:published|updated)>(.*?)</(?:published|updated)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val REDDIT_DIRECT_IMAGE_REGEX = Regex(
            "https://i\\.redd\\.it/[^\\s<\\\"&]+",
            RegexOption.IGNORE_CASE
        )
        val REDDIT_DIMENSION_REGEX = Regex("(\\d{3,5})\\s*[x×X]\\s*(\\d{3,5})")
    }


    private data class PreferredApp(
        val title: String,
        val description: String,
        val packageNames: List<String>,
        val labelKeywords: List<String>,
        @param:ColorRes val accentColorRes: Int
    )

    private enum class SectionType {
        FAVOURITES,
        RECENT,
        UNCATEGORIZED,
        CATEGORY,
        ALL_APPS
    }

    private data class CategoryDefinition(
        val id: String,
        val title: String,
        @param:ColorRes val accentColorRes: Int,
        val keywords: List<String>,
        val includeSettingsApp: Boolean = false
    )

    private data class CustomCategory(
        val id: String,
        val title: String
    )

    private data class AppSection(
        val id: String,
        val title: String,
        val apps: List<AppItem>,
        val type: SectionType,
        val isCustom: Boolean = false,
        val accentColor: Int? = null,
        val hiddenCount: Int = 0
    )

    private data class RenderedSection(
        val section: AppSection,
        val containerView: View,
        val cardViews: List<View>,
        val accentColor: Int
    )

    private data class WeatherInfo(
        val location: String,
        val temperature: String,
        val summary: String
    )

    private data class SportsFeedSource(
        val key: String,
        val label: String,
        val url: String
    )

    private data class SportsEntry(
        val feedKey: String,
        val feedLabel: String,
        val channelLabel: String?,
        val title: String,
        val startsAt: Date
    )

    private data class WallpaperImage(
        val date: String,
        val title: String,
        val imageUrl: String,
        val mediaType: String? = null,
        val mediaId: Int? = null,
        val overview: String? = null
    )

    private data class TmdbFeedSpec(
        val path: String,
        val mediaType: String?,
        val heroLabel: String
    )

    private data class FocusRequest(
        val sectionId: String,
        val packageName: String?,
        val itemIndex: Int,
        val sectionIndex: Int,
        val scrollY: Int
    )

    private data class FeaturedHero(
        val eyebrow: String,
        val title: String,
        val primaryMeta: String,
        val secondaryMeta: String?,
        val overview: String?,
        val logoUrl: String?
    )

    private data class AppCardState(
        val binding: ItemAppBinding,
        val accentColor: Int,
        val usesBannerArt: Boolean,
        var isFocused: Boolean = false
    )
}
