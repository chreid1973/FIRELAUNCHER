package com.threehpm.firelauncher

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.threehpm.firelauncher.databinding.ActivityLauncherSettingsBinding
import java.io.File
import java.io.IOException

class LauncherSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherSettingsBinding
    private var currentSection = SettingsSection.APPEARANCE
    private var suppressSwitchCallbacks = false
    private var pendingStorageAction: (() -> Unit)? = null
    private val exportSetupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(LauncherSetupBackup.MIME_TYPE)) { uri ->
            uri?.let(::exportSetupToUri)
        }
    private val importSetupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::importSetupFromUri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindControls()
        refreshState()
    }

    private fun bindControls() {
        bindTab(binding.appearanceTab, SettingsSection.APPEARANCE)
        bindTab(binding.weatherTab, SettingsSection.WEATHER)
        bindTab(binding.appsTab, SettingsSection.APPS)
        bindTab(binding.backupTab, SettingsSection.BACKUP)
        applyFocusAnimation(binding.backButton)
        applyFocusAnimation(binding.sourceRow)
        applyFocusAnimation(binding.weatherUnitsRow)
        applyFocusAnimation(binding.weatherLocationRow)
        applyFocusAnimation(binding.intervalRow)
        applyFocusAnimation(binding.nasaKeyRow)
        applyFocusAnimation(binding.shuffleRow)
        applyFocusAnimation(binding.changeOnOpenRow)
        applyFocusAnimation(binding.fixedImageRow)
        applyFocusAnimation(binding.localFolderRow)
        applyFocusAnimation(binding.refreshWallpaperRow)
        applyFocusAnimation(binding.seeAllAppsRow)
        applyFocusAnimation(binding.exportSetupRow)
        applyFocusAnimation(binding.importSetupRow)

        binding.backButton.setOnClickListener { finish() }
        binding.sourceRow.setOnClickListener { showSourceDialog() }
        binding.weatherUnitsRow.setOnClickListener { showWeatherUnitDialog() }
        binding.weatherLocationRow.setOnClickListener { showWeatherLocationDialog() }
        binding.intervalRow.setOnClickListener { showIntervalDialog() }
        binding.nasaKeyRow.setOnClickListener { showNasaApiKeyDialog() }
        binding.fixedImageRow.setOnClickListener {
            ensureStorageAccess { showFixedImagePickerDialog() }
        }
        binding.localFolderRow.setOnClickListener { showLocalFolderDialog() }
        binding.refreshWallpaperRow.setOnClickListener {
            WallpaperPrefs.touchRefreshToken(this)
            Toast.makeText(this, "Wallpaper will refresh on return.", Toast.LENGTH_SHORT).show()
            refreshState()
        }
        binding.seeAllAppsRow.setOnClickListener {
            startActivity(Intent(this, AllAppsActivity::class.java))
        }
        binding.exportSetupRow.setOnClickListener {
            exportSetupLauncher.launch(LauncherSetupBackup.defaultFileName())
        }
        binding.importSetupRow.setOnClickListener {
            importSetupLauncher.launch(arrayOf(LauncherSetupBackup.MIME_TYPE, "text/*"))
        }

        binding.shuffleRow.setOnClickListener {
            binding.shuffleSwitch.isChecked = !binding.shuffleSwitch.isChecked
        }
        binding.changeOnOpenRow.setOnClickListener {
            binding.changeOnOpenSwitch.isChecked = !binding.changeOnOpenSwitch.isChecked
        }

        val switchListener = CompoundButton.OnCheckedChangeListener { _, _ ->
            if (suppressSwitchCallbacks) {
                return@OnCheckedChangeListener
            }
            WallpaperPrefs.touchRefreshToken(this)
            refreshState()
        }

        binding.shuffleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallbacks) {
                return@setOnCheckedChangeListener
            }
            WallpaperPrefs.setShuffleEnabled(this, isChecked)
            switchListener.onCheckedChanged(binding.shuffleSwitch, isChecked)
        }

        binding.changeOnOpenSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallbacks) {
                return@setOnCheckedChangeListener
            }
            WallpaperPrefs.setChangeOnOpenEnabled(this, isChecked)
            switchListener.onCheckedChanged(binding.changeOnOpenSwitch, isChecked)
        }
    }

    private fun refreshState() {
        val wallpaperSource = WallpaperPrefs.getSource(this)
        binding.settingsSubtitle.text =
            currentSection.subtitle
        binding.currentWallpaperSummary.text = WallpaperPrefs.buildSummary(this)
        binding.sourceValue.text = wallpaperSource.label
        binding.weatherUnitsValue.text = WeatherPrefs.getUnit(this).label
        binding.weatherLocationValue.text = WeatherPrefs.getQuery(this) ?: "Auto (IP-based)"
        binding.intervalValue.text = WallpaperPrefs.formatInterval(WallpaperPrefs.getIntervalMs(this))
        binding.localFolderValue.text = WallpaperPrefs.getLocalWallpaperFolder().absolutePath
        binding.nasaKeyValue.text = WallpaperPrefs.getStoredNasaApiKey(this)
            ?.let(::maskApiKey)
            ?: "Using DEMO_KEY (rate-limited)"
        binding.seeAllAppsValue.text = "Browse installed apps that stay out of the Home shelves"
        binding.nasaKeyRow.visibility =
            if (wallpaperSource == WallpaperSource.NASA) View.VISIBLE else View.GONE

        val fixedFileName = WallpaperPrefs.getFixedPath(this)
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?.name
            ?: "Not selected"
        binding.fixedImageValue.text = fixedFileName

        suppressSwitchCallbacks = true
        binding.shuffleSwitch.isChecked = WallpaperPrefs.isShuffleEnabled(this)
        binding.changeOnOpenSwitch.isChecked = WallpaperPrefs.isChangeOnOpenEnabled(this)
        suppressSwitchCallbacks = false
        renderSectionState()
    }

    private fun bindTab(view: View, section: SettingsSection) {
        view.setOnClickListener { selectSection(section) }
        view.setOnFocusChangeListener { focusedView, hasFocus ->
            focusedView.animate()
                .scaleX(if (hasFocus) 1.03f else 1f)
                .scaleY(if (hasFocus) 1.03f else 1f)
                .translationY(if (hasFocus) -2f else 0f)
                .setDuration(140L)
                .start()
            if (hasFocus) {
                selectSection(section)
            } else if (currentSection != section) {
                focusedView.alpha = 0.84f
            }
        }
    }

    private fun selectSection(section: SettingsSection) {
        currentSection = section
        renderSectionState()
    }

    private fun renderSectionState() {
        binding.settingsSubtitle.text = currentSection.subtitle

        binding.appearancePanel.visibility =
            if (currentSection == SettingsSection.APPEARANCE) View.VISIBLE else View.GONE
        binding.weatherPanel.visibility =
            if (currentSection == SettingsSection.WEATHER) View.VISIBLE else View.GONE
        binding.appsPanel.visibility =
            if (currentSection == SettingsSection.APPS) View.VISIBLE else View.GONE
        binding.backupPanel.visibility =
            if (currentSection == SettingsSection.BACKUP) View.VISIBLE else View.GONE

        updateTabState(binding.appearanceTab, currentSection == SettingsSection.APPEARANCE)
        updateTabState(binding.weatherTab, currentSection == SettingsSection.WEATHER)
        updateTabState(binding.appsTab, currentSection == SettingsSection.APPS)
        updateTabState(binding.backupTab, currentSection == SettingsSection.BACKUP)
    }

    private fun updateTabState(view: View, selected: Boolean) {
        view.isSelected = selected
        view.alpha = if (selected || view.hasFocus()) 1f else 0.84f
    }

    private fun maskApiKey(apiKey: String): String {
        if (apiKey.length <= 8) {
            return "Custom key saved"
        }
        return "Custom key saved (${apiKey.take(4)}...${apiKey.takeLast(4)})"
    }

    private fun showSourceDialog() {
        val sources = WallpaperSource.values()
        val selectedIndex = sources.indexOf(WallpaperPrefs.getSource(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Wallpaper Source")
            .setSingleChoiceItems(
                sources.map { it.label }.toTypedArray(),
                selectedIndex
            ) { dialog, which ->
                when (sources[which]) {
                    WallpaperSource.NASA -> {
                        WallpaperPrefs.setSource(this, WallpaperSource.NASA)
                        WallpaperPrefs.touchRefreshToken(this)
                        refreshState()
                        dialog.dismiss()
                    }
                    WallpaperSource.TMDB_TRENDING -> {
                        WallpaperPrefs.setSource(this, WallpaperSource.TMDB_TRENDING)
                        WallpaperPrefs.touchRefreshToken(this)
                        refreshState()
                        dialog.dismiss()
                    }
                    WallpaperSource.TMDB_TRENDING_MOVIES -> {
                        WallpaperPrefs.setSource(this, WallpaperSource.TMDB_TRENDING_MOVIES)
                        WallpaperPrefs.touchRefreshToken(this)
                        refreshState()
                        dialog.dismiss()
                    }
                    WallpaperSource.TMDB_TRENDING_TV -> {
                        WallpaperPrefs.setSource(this, WallpaperSource.TMDB_TRENDING_TV)
                        WallpaperPrefs.touchRefreshToken(this)
                        refreshState()
                        dialog.dismiss()
                    }
                    WallpaperSource.TMDB_POPULAR_MOVIES -> {
                        WallpaperPrefs.setSource(this, WallpaperSource.TMDB_POPULAR_MOVIES)
                        WallpaperPrefs.touchRefreshToken(this)
                        refreshState()
                        dialog.dismiss()
                    }
                    WallpaperSource.TMDB_POPULAR_TV -> {
                        WallpaperPrefs.setSource(this, WallpaperSource.TMDB_POPULAR_TV)
                        WallpaperPrefs.touchRefreshToken(this)
                        refreshState()
                        dialog.dismiss()
                    }
                    WallpaperSource.REDDIT_EARTHPORN -> {
                        WallpaperPrefs.setSource(this, WallpaperSource.REDDIT_EARTHPORN)
                        WallpaperPrefs.touchRefreshToken(this)
                        refreshState()
                        dialog.dismiss()
                    }
                    WallpaperSource.LOCAL_FOLDER -> {
                        ensureStorageAccess {
                            if (WallpaperPrefs.loadLocalWallpaperFiles().isEmpty()) {
                                showLocalFolderDialog("No local wallpapers found yet.\n\n")
                            } else {
                                WallpaperPrefs.setSource(this, WallpaperSource.LOCAL_FOLDER)
                                WallpaperPrefs.touchRefreshToken(this)
                                refreshState()
                            }
                            dialog.dismiss()
                        }
                    }
                    WallpaperSource.FIXED_IMAGE -> {
                        ensureStorageAccess {
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

    private fun showWeatherUnitDialog() {
        val units = WeatherUnit.entries.toTypedArray()
        val selectedIndex = units.indexOf(WeatherPrefs.getUnit(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Weather Units")
            .setSingleChoiceItems(
                units.map { it.label }.toTypedArray(),
                selectedIndex
            ) { dialog, which ->
                WeatherPrefs.setUnit(this, units[which])
                refreshState()
                Toast.makeText(this, "Weather units will refresh on return.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWeatherLocationDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
            setSingleLine(true)
            setText(WeatherPrefs.getQuery(this@LauncherSettingsActivity) ?: "")
            hint = "Postal code, ZIP, or city"
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Weather Location")
            .setMessage("Leave blank to use your connection's detected location.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                WeatherPrefs.setQuery(this, input.text?.toString())
                refreshState()
                Toast.makeText(this, "Weather location will refresh on return.", Toast.LENGTH_SHORT)
                    .show()
            }
            .setNeutralButton("Use Auto") { _, _ ->
                WeatherPrefs.setQuery(this, null)
                refreshState()
                Toast.makeText(this, "Weather location reset to auto.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showIntervalDialog() {
        val intervals = arrayOf(
            5 * 60_000L,
            15 * 60_000L,
            30 * 60_000L,
            60 * 60_000L,
            24 * 60 * 60_000L
        )
        val current = WallpaperPrefs.getIntervalMs(this)
        val selectedIndex = intervals.indexOf(current).let { if (it >= 0) it else 1 }

        AlertDialog.Builder(this)
            .setTitle("Rotation Interval")
            .setSingleChoiceItems(
                intervals.map(WallpaperPrefs::formatInterval).toTypedArray(),
                selectedIndex
            ) { dialog, which ->
                WallpaperPrefs.setIntervalMs(this, intervals[which])
                WallpaperPrefs.touchRefreshToken(this)
                refreshState()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNasaApiKeyDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
            setText(WallpaperPrefs.getStoredNasaApiKey(this@LauncherSettingsActivity) ?: "")
            hint = "Paste NASA API key"
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("NASA API Key")
            .setMessage("Leave blank to use DEMO_KEY. A personal key avoids DEMO_KEY rate limits.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                WallpaperPrefs.setNasaApiKey(this, input.text?.toString())
                WallpaperPrefs.touchRefreshToken(this)
                refreshState()
                Toast.makeText(this, "NASA API key saved.", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Use DEMO_KEY") { _, _ ->
                WallpaperPrefs.setNasaApiKey(this, null)
                WallpaperPrefs.touchRefreshToken(this)
                refreshState()
                Toast.makeText(this, "Using DEMO_KEY again.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFixedImagePickerDialog(onDismiss: (() -> Unit)? = null) {
        val files = WallpaperPrefs.loadLocalWallpaperFiles()
        if (files.isEmpty()) {
            showLocalFolderDialog("No local wallpapers found yet.\n\n")
            onDismiss?.invoke()
            return
        }

        val selectedPath = WallpaperPrefs.getFixedPath(this)
        val selectedIndex = files.indexOfFirst { it.absolutePath == selectedPath }

        AlertDialog.Builder(this)
            .setTitle("Choose Fixed Image")
            .setSingleChoiceItems(
                files.map { it.name }.toTypedArray(),
                selectedIndex
            ) { dialog, which ->
                WallpaperPrefs.setFixedPath(this, files[which].absolutePath)
                WallpaperPrefs.setSource(this, WallpaperSource.FIXED_IMAGE)
                WallpaperPrefs.touchRefreshToken(this)
                refreshState()
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .setNegativeButton("Cancel") { _, _ ->
                onDismiss?.invoke()
            }
            .show()
    }

    private fun showLocalFolderDialog(messagePrefix: String = "") {
        AlertDialog.Builder(this)
            .setTitle("Local Wallpaper Folder")
            .setMessage(
                buildString {
                    append(messagePrefix)
                    append("Add JPG, PNG, or WEBP images to:\n\n")
                    append(WallpaperPrefs.getLocalWallpaperFolder().absolutePath)
                }
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun exportSetupToUri(uri: Uri) {
        try {
            val summary = LauncherSetupBackup.exportToUri(this, uri)
            Toast.makeText(
                this,
                "Setup exported: ${summary.favorites} favourites, ${summary.customCategories} custom categories",
                Toast.LENGTH_LONG
            ).show()
        } catch (error: IOException) {
            Toast.makeText(
                this,
                error.message ?: "Setup export failed.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun importSetupFromUri(uri: Uri) {
        try {
            val summary = LauncherSetupBackup.importFromUri(this, uri)
            refreshState()
            Toast.makeText(
                this,
                "Setup imported: ${summary.favorites} favourites, ${summary.customCategories} categories",
                Toast.LENGTH_LONG
            ).show()
        } catch (error: Exception) {
            Toast.makeText(
                this,
                error.message ?: "Setup import failed.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun ensureStorageAccess(onGranted: () -> Unit) {
        val permission = WallpaperPrefs.getStoragePermission() ?: run {
            onGranted()
            return
        }

        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
            return
        }

        pendingStorageAction = onGranted
        requestPermissions(arrayOf(permission), REQUEST_WALLPAPER_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WALLPAPER_PERMISSION) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            val action = pendingStorageAction
            pendingStorageAction = null

            if (granted) {
                action?.invoke()
            } else {
                Toast.makeText(this, "Wallpaper folder access was not granted.", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun applyFocusAnimation(view: android.view.View) {
        view.setOnFocusChangeListener { focusedView, hasFocus ->
            focusedView.animate()
                .scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .translationY(if (hasFocus) -2f else 0f)
                .setDuration(140L)
                .start()
            focusedView.alpha = if (hasFocus) 1f else 0.94f
        }
    }

    private companion object {
        const val REQUEST_WALLPAPER_PERMISSION = 4001
    }

    private enum class SettingsSection(val subtitle: String) {
        APPEARANCE("Wallpaper source, rotation, and home presentation"),
        WEATHER("Forecast format and the location used for weather"),
        APPS("Installed app browsing outside the Home shelves"),
        BACKUP("Export or restore your launcher setup")
    }
}
