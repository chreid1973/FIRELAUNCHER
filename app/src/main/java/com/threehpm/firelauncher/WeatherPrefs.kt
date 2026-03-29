package com.threehpm.firelauncher

import android.content.Context

enum class WeatherUnit(val prefValue: String, val label: String, val suffix: String) {
    CELSIUS("celsius", "Celsius", "°C"),
    FAHRENHEIT("fahrenheit", "Fahrenheit", "°F");

    companion object {
        fun fromPref(value: String?): WeatherUnit {
            return entries.firstOrNull { it.prefValue == value } ?: CELSIUS
        }
    }
}

object WeatherPrefs {
    const val PREF_UNIT = "weather_unit"
    const val PREF_QUERY = "weather_query"
    const val PREF_LOCATION = "weather_location"
    const val PREF_TEMPERATURE = "weather_temperature"
    const val PREF_SUMMARY = "weather_summary"

    private fun prefs(context: Context) =
        context.getSharedPreferences(WallpaperPrefs.PREFS_NAME, Context.MODE_PRIVATE)

    fun getUnit(context: Context): WeatherUnit {
        return WeatherUnit.fromPref(
            prefs(context).getString(PREF_UNIT, WeatherUnit.CELSIUS.prefValue)
        )
    }

    fun setUnit(context: Context, unit: WeatherUnit) {
        prefs(context).edit()
            .putString(PREF_UNIT, unit.prefValue)
            .clearCachedWeather()
            .apply()
    }

    fun getQuery(context: Context): String? {
        return prefs(context).getString(PREF_QUERY, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setQuery(context: Context, query: String?) {
        prefs(context).edit()
            .putString(PREF_QUERY, query?.trim().orEmpty().ifBlank { null })
            .clearCachedWeather()
            .apply()
    }

    private fun android.content.SharedPreferences.Editor.clearCachedWeather():
        android.content.SharedPreferences.Editor {
        return remove(PREF_LOCATION)
            .remove(PREF_TEMPERATURE)
            .remove(PREF_SUMMARY)
    }
}
