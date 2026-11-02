package com.shahu.tv

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One entry from wallpapers.json → "applications". All fields optional. */
data class AppTheme(
    val name: String?,
    val wallpaper: String?,
    val description: String?,
    /** Optional variants: one is picked at random each time the app gains focus (e.g. YouTube). */
    val wallpapers: List<String> = emptyList(),
    /** Optional per-app fallback if [wallpaper] is missing (e.g. a file not supplied yet). */
    val fallbackWallpaper: String? = null,
)

/**
 * Wallpaper / app-row configuration.
 *
 * Sources (later overrides earlier, per key):
 *   1. Built-in  : APK assets/wallpapers.json
 *   2. External  : /sdcard/Android/data/com.shahu.tv/files/wallpapers.json  (adb push, no rebuild)
 *
 * Invalid or missing JSON is logged and ignored — the app never crashes because of config.
 */
class WallpaperConfig private constructor(
    val defaultWallpaper: String,
    val fallbackWallpapers: List<String>,
    val pinned: List<String>,
    val hidden: Set<String>,
    /** Exactly the tiles of the home grid, in order (packages, "shahu.settings", "shahu.more"). */
    val home: List<String>,
    val apps: Map<String, AppTheme>,
    val externalStamp: Long,
) {
    fun themeFor(pkg: String): AppTheme? = apps[pkg]

    /** Wallpaper path for a package: explicit mapping, else a stable pick from the fallback list. */
    fun wallpaperFor(pkg: String): String {
        apps[pkg]?.wallpaper?.let { return it }
        if (fallbackWallpapers.isEmpty()) return defaultWallpaper
        val idx = (pkg.hashCode() and 0x7fffffff) % fallbackWallpapers.size
        return fallbackWallpapers[idx]
    }

    /** Image used when [key]'s own wallpaper cannot be loaded: the app's fallback, else the global default. */
    fun fallbackFor(key: String?): String = key?.let { apps[it]?.fallbackWallpaper } ?: defaultWallpaper

    /** All wallpaper choices for a key: the variant list if present, else the single mapping. */
    fun wallpaperChoicesFor(pkg: String): List<String> =
        apps[pkg]?.wallpapers?.takeIf { it.isNotEmpty() } ?: listOf(wallpaperFor(pkg))

    companion object {
        private const val TAG = "ShahuConfig"
        const val FILE_NAME = "wallpapers.json"

        /** Used when wallpapers.json has no "home" list. */
        val DEFAULT_HOME = listOf(
            "com.amazon.firetv.youtube", "in.startv.hotstar", "com.netflix.ninja", "com.zee5.amazon",
            "com.amazon.firebat", "com.fancode.tv", AppEntry.SETTINGS_KEY, AppEntry.MORE_KEY,
        )

        fun externalDir(ctx: Context): File? = ctx.getExternalFilesDir(null)

        fun externalFile(ctx: Context): File? = externalDir(ctx)?.let { File(it, FILE_NAME) }

        /** Stamp used to detect "config changed since last load" cheaply. */
        fun currentExternalStamp(ctx: Context): Long {
            val f = externalFile(ctx) ?: return 0L
            return if (f.isFile) f.lastModified() xor f.length() else 0L
        }

        fun load(ctx: Context): WallpaperConfig {
            val builtIn = runCatching {
                ctx.assets.open(FILE_NAME).bufferedReader().use { JSONObject(it.readText()) }
            }.onFailure { Log.e(TAG, "Built-in config invalid", it) }.getOrNull()

            val extFile = externalFile(ctx)
            val external = if (extFile != null && extFile.isFile && extFile.length() < 512 * 1024) {
                runCatching { JSONObject(extFile.readText()) }
                    .onSuccess { Log.i(TAG, "Using external config ${extFile.absolutePath}") }
                    .onFailure { Log.w(TAG, "External config is invalid JSON — ignored: ${it.message}") }
                    .getOrNull()
            } else null

            var default = "wallpapers/default.jpg"
            var fallbacks = emptyList<String>()
            var pinned = emptyList<String>()
            var hidden = emptySet<String>()
            var home = DEFAULT_HOME
            val apps = LinkedHashMap<String, AppTheme>()

            for (json in listOfNotNull(builtIn, external)) {
                json.optString("defaultWallpaper").takeIf { it.isNotBlank() }?.let { default = it }
                json.optJSONArray("fallbackWallpapers")?.let { fallbacks = it.strings() }
                json.optJSONArray("pinned")?.let { pinned = it.strings() }
                json.optJSONArray("hidden")?.let { hidden = it.strings().toSet() }
                json.optJSONArray("home")?.let { a -> a.strings().distinct().takeIf { it.isNotEmpty() }?.let { home = it } }
                json.optJSONObject("applications")?.let { obj ->
                    for (pkg in obj.keys()) {
                        val e = obj.optJSONObject(pkg) ?: continue
                        val prev = apps[pkg]
                        apps[pkg] = AppTheme(
                            name = e.optStringOrNull("name") ?: prev?.name,
                            wallpaper = e.optStringOrNull("wallpaper") ?: prev?.wallpaper,
                            description = e.optStringOrNull("description") ?: prev?.description,
                            wallpapers = e.optJSONArray("wallpapers")?.strings() ?: prev?.wallpapers ?: emptyList(),
                            fallbackWallpaper = e.optStringOrNull("fallbackWallpaper") ?: prev?.fallbackWallpaper,
                        )
                    }
                }
            }
            return WallpaperConfig(default, fallbacks, pinned, hidden, home, apps, currentExternalStamp(ctx))
        }

        private fun JSONArray.strings(): List<String> =
            (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

        private fun JSONObject.optStringOrNull(k: String): String? =
            if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() } else null
    }
}
