package com.threehpm.firelauncher

import android.content.ActivityNotFoundException
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.threehpm.firelauncher.databinding.ActivityAllAppsBinding
import com.threehpm.firelauncher.databinding.ItemAllAppsRowBinding
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class AllAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAllAppsBinding
    private val appExecutor = Executors.newSingleThreadExecutor()
    private val launcherPrefs by lazy { getSharedPreferences("launcher_state", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAllAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindChrome()
        loadApps()
    }

    override fun onDestroy() {
        appExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindChrome() {
        applyFocusAnimation(binding.backButton)
        binding.backButton.setOnClickListener { finish() }
    }

    private fun loadApps() {
        binding.allAppsSummary.text = "Loading apps..."
        appExecutor.execute {
            val apps = LaunchableAppRepository.discoverLaunchableApps(this)
                .sortedBy { it.label.lowercase(Locale.getDefault()) }
            val hiddenPackages = loadHiddenPackages()
            val offHomeApps = apps.filterNot { it.hasIconPackMatch }
            val homeApps = apps.filter { it.hasIconPackMatch }

            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }

                binding.allAppsSummary.text =
                    "${apps.size} installed • ${homeApps.size} on Home • ${offHomeApps.size} here only"
                binding.offHomeMeta.text = if (offHomeApps.isEmpty()) {
                    "Every installed app already has a home banner match."
                } else {
                    "${offHomeApps.size} apps stay out of the Home shelves."
                }
                binding.homeAppsMeta.text = if (homeApps.isEmpty()) {
                    "No apps have a home banner match yet."
                } else {
                    "${homeApps.size} apps already map into the home shelves."
                }

                renderSection(
                    container = binding.offHomeContainer,
                    apps = offHomeApps,
                    statusLabel = "Other",
                    statusColor = ContextCompat.getColor(this, R.color.accent_silver),
                    emptyMessage = "No overflow apps right now.",
                    hiddenPackages = hiddenPackages
                )
                renderSection(
                    container = binding.homeAppsContainer,
                    apps = homeApps,
                    statusLabel = "Home",
                    statusColor = ContextCompat.getColor(this, R.color.accent_mint),
                    emptyMessage = "No home-ready apps found.",
                    hiddenPackages = hiddenPackages
                )
            }
        }
    }

    private fun renderSection(
        container: LinearLayout,
        apps: List<LaunchableAppEntry>,
        statusLabel: String,
        statusColor: Int,
        emptyMessage: String,
        hiddenPackages: Set<String>
    ) {
        container.removeAllViews()
        if (apps.isEmpty()) {
            container.addView(createPlaceholder(emptyMessage))
            return
        }

        apps.forEach { app ->
            val rowBinding = ItemAllAppsRowBinding.inflate(layoutInflater, container, false)
            bindRow(rowBinding, app, statusLabel, statusColor, app.packageName in hiddenPackages)
            container.addView(rowBinding.root)
        }
    }

    private fun bindRow(
        binding: ItemAllAppsRowBinding,
        app: LaunchableAppEntry,
        statusLabel: String,
        statusColor: Int,
        isHidden: Boolean
    ) {
        val banner = app.packBanner ?: app.systemBanner
        val usesBannerArt = hasWideBanner(banner)
        binding.appTitle.text = app.label
        binding.appSubtitle.text = if (app.hasIconPackMatch) {
            if (isHidden) {
                "Hidden from Home • ${app.packageName}"
            } else {
                "Shown on Home • ${app.packageName}"
            }
        } else {
            "Only in See All Apps • ${app.packageName}"
        }
        val resolvedStatusLabel = if (app.hasIconPackMatch && isHidden) "Hidden" else statusLabel
        val resolvedStatusColor = if (app.hasIconPackMatch && isHidden) {
            ContextCompat.getColor(this, R.color.accent_coral)
        } else {
            statusColor
        }
        binding.appStatus.text = resolvedStatusLabel
        binding.appStatus.setTextColor(resolvedStatusColor)
        binding.appStatus.background = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(ColorUtils.setAlphaComponent(resolvedStatusColor, 32))
            setStroke(dp(1), ColorUtils.setAlphaComponent(resolvedStatusColor, 96))
        }

        binding.appArt.setImageDrawable(if (usesBannerArt) banner else app.icon)
        binding.appArt.scaleType = if (usesBannerArt) {
            ImageView.ScaleType.CENTER_CROP
        } else {
            ImageView.ScaleType.FIT_CENTER
        }
        if (usesBannerArt) {
            binding.appArt.setPadding(0, 0, 0, 0)
            binding.appArtSurface.background = createArtSurfaceBackground(resolvedStatusColor, true)
        } else {
            binding.appArt.setPadding(dp(14), dp(10), dp(14), dp(10))
            binding.appArtSurface.background = createArtSurfaceBackground(resolvedStatusColor, false)
        }

        applyFocusAnimation(binding.root, binding.appArtSurface)
        binding.root.setOnClickListener { showAppActions(app, isHidden) }
    }

    private fun createPlaceholder(message: String): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = message
            setTextColor(ContextCompat.getColor(this@AllAppsActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(2))
        }
    }

    private fun createArtSurfaceBackground(accentColor: Int, usesBannerArt: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(
                ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(accentColor, Color.BLACK, if (usesBannerArt) 0.9f else 0.82f),
                    if (usesBannerArt) 72 else 132
                )
            )
            setStroke(dp(1), ColorUtils.setAlphaComponent(accentColor, 80))
        }
    }

    private fun launchApp(app: LaunchableAppEntry) {
        try {
            startActivity(app.launchIntent)
            recordRecentLaunch(app.packageName)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "App not available: ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppActions(app: LaunchableAppEntry, isHidden: Boolean) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Launch" to { launchApp(app) }

        if (app.hasIconPackMatch) {
            if (isHidden) {
                actions += "Show on Home" to {
                    setPackageHidden(app.packageName, false)
                    Toast.makeText(this, "${app.label} is visible on Home again", Toast.LENGTH_SHORT)
                        .show()
                    loadApps()
                }
            } else {
                actions += "Hide from Home" to {
                    setPackageHidden(app.packageName, true)
                    Toast.makeText(this, "${app.label} hidden from Home", Toast.LENGTH_SHORT).show()
                    loadApps()
                }
            }
        }

        actions += "Cancel" to {}

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(actions.map { it.first }.toTypedArray()) { dialog, which ->
                actions.getOrNull(which)?.second?.invoke()
                dialog.dismiss()
            }
            .show()
    }

    private fun recordRecentLaunch(packageName: String) {
        val recentPackages = readStringList(PREF_RECENT_PACKAGES).toMutableList()
        recentPackages.remove(packageName)
        recentPackages.add(0, packageName)
        writeStringList(PREF_RECENT_PACKAGES, recentPackages.take(MAX_RECENT_APPS))
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

    private fun loadHiddenPackages(): Set<String> {
        return readStringList(PREF_HIDDEN_PACKAGES).toSet()
    }

    private fun setPackageHidden(packageName: String, hidden: Boolean) {
        val hiddenPackages = loadHiddenPackages().toMutableList()
        val changed = if (hidden) {
            if (packageName in hiddenPackages) {
                false
            } else {
                hiddenPackages += packageName
                true
            }
        } else {
            hiddenPackages.remove(packageName)
        }
        writeStringList(PREF_HIDDEN_PACKAGES, hiddenPackages.sorted())
        if (changed) {
            WallpaperPrefs.touchHomeRefreshToken(this)
        }
    }

    private fun applyFocusAnimation(view: View, highlightedView: View = view) {
        view.setOnFocusChangeListener { focusedView, hasFocus ->
            focusedView.animate()
                .scaleX(if (hasFocus) 1.015f else 1f)
                .scaleY(if (hasFocus) 1.015f else 1f)
                .translationY(if (hasFocus) -3f else 0f)
                .setDuration(150L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            focusedView.alpha = if (hasFocus) 1f else 0.94f
            highlightedView.translationZ = if (hasFocus) 18f else 0f
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private companion object {
        const val PREF_HIDDEN_PACKAGES = "hidden_packages"
        const val PREF_RECENT_PACKAGES = "recent_packages"
        const val MAX_RECENT_APPS = 12
    }
}
