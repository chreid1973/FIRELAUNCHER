package com.threehpm.firelauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.provider.Settings

data class LaunchableAppEntry(
    val label: String,
    val packageName: String,
    val launchIntent: Intent,
    val icon: Drawable,
    val systemBanner: Drawable?,
    val packBanner: Drawable?
) {
    val hasIconPackMatch: Boolean
        get() = packBanner != null
}

object LaunchableAppRepository {

    fun discoverLaunchableApps(context: Context): List<LaunchableAppEntry> {
        val packageManager = context.packageManager
        val categories = listOf(
            Intent.CATEGORY_LEANBACK_LAUNCHER,
            Intent.CATEGORY_LAUNCHER
        )
        val launchableApps = LinkedHashMap<String, LaunchableAppEntry>()

        categories.forEach { category ->
            val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(category)
            }

            packageManager.queryIntentActivities(queryIntent, 0).forEach { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@forEach
                val appPackage = activityInfo.packageName

                if (appPackage == context.packageName || launchableApps.containsKey(appPackage)) {
                    return@forEach
                }

                val className = normalizeClassName(appPackage, activityInfo.name)
                val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(category)
                    component = ComponentName(appPackage, className)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }

                val label = resolveInfo.loadLabel(packageManager)?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?: appPackage
                val systemBanner = loadBannerArt(context, activityInfo)
                val packBanner = IconPackCatalog.loadBanner(context, appPackage, label)

                launchableApps[appPackage] = LaunchableAppEntry(
                    label = label,
                    packageName = appPackage,
                    launchIntent = launchIntent,
                    icon = resolveInfo.loadIcon(packageManager) ?: packageManager.defaultActivityIcon,
                    systemBanner = systemBanner,
                    packBanner = packBanner
                )
            }
        }

        return launchableApps.values.toList()
    }

    fun resolveSettingsApp(context: Context): LaunchableAppEntry? {
        return resolveSystemTarget(context, Intent(Settings.ACTION_SETTINGS))
            ?: resolveSystemTarget(context, Intent("com.amazon.tv.settings.action.MAIN_SETTINGS"))
    }

    private fun resolveSystemTarget(context: Context, baseIntent: Intent): LaunchableAppEntry? {
        val packageManager = context.packageManager
        val resolveInfo = packageManager.resolveActivity(baseIntent, 0) ?: return null
        val activityInfo = resolveInfo.activityInfo ?: return null
        val className = normalizeClassName(activityInfo.packageName, activityInfo.name)

        val launchIntent = Intent(baseIntent).apply {
            component = ComponentName(activityInfo.packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        val label = resolveInfo.loadLabel(packageManager)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: activityInfo.packageName

        return LaunchableAppEntry(
            label = label,
            packageName = activityInfo.packageName,
            launchIntent = launchIntent,
            icon = resolveInfo.loadIcon(packageManager) ?: packageManager.defaultActivityIcon,
            systemBanner = loadBannerArt(context, activityInfo),
            packBanner = IconPackCatalog.loadBanner(context, activityInfo.packageName, label)
        )
    }

    private fun loadBannerArt(
        context: Context,
        activityInfo: android.content.pm.ActivityInfo
    ): Drawable? {
        val packageManager = context.packageManager
        return activityInfo.loadBanner(packageManager)
            ?: activityInfo.applicationInfo?.loadBanner(packageManager)
            ?: activityInfo.loadLogo(packageManager)
            ?: activityInfo.applicationInfo?.loadLogo(packageManager)
    }

    private fun normalizeClassName(packageName: String, className: String): String {
        return if (className.startsWith(".")) {
            packageName + className
        } else {
            className
        }
    }
}
