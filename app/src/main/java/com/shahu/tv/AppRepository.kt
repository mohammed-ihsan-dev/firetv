package com.shahu.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.provider.Settings

/** Tile artwork: a 16:9 banner, or (fallback) an icon to show above the app name. */
class TileArt(val banner: Bitmap?, val icon: Bitmap?)

/** A launchable entry in the app row. */
data class AppEntry(
    val key: String,              // package name, or "shahu.settings" for the Settings shortcut
    val label: String,
    val component: ComponentName?,
    val isLeanback: Boolean,
) {
    val isSettings get() = key == SETTINGS_KEY
    val isMore get() = key == MORE_KEY
    /** Home tile for an app that is not installed (component unknown). */
    val isMissing get() = component == null && !isSettings && !isMore

    companion object {
        const val SETTINGS_KEY = "shahu.settings"
        const val MORE_KEY = "shahu.more"
    }
}

/**
 * Discovers apps that are ACTUALLY installed, enabled and launchable right now.
 * Nothing is hard-coded: wallpapers.json only controls order, names and wallpapers.
 */
class AppRepository(private val ctx: Context) {
    var cornerRadius = 0f
    private val pm: PackageManager = ctx.packageManager

    fun query(config: WallpaperConfig): List<AppEntry> {
        val byPkg = LinkedHashMap<String, AppEntry>()

        // 1) TV (leanback) launchable activities — what the Fire TV "Your Apps" shows.
        val leanback = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        for (ri in leanback) addIfValid(byPkg, ri, config, leanback = true)

        // 2) Sideloaded (non-system) phone-style apps that only declare LAUNCHER.
        val launcher = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        for (ri in launcher) {
            val ai = ri.activityInfo.applicationInfo
            if (ai.flags and ApplicationInfo.FLAG_SYSTEM == 0) addIfValid(byPkg, ri, config, leanback = false)
        }

        val pinnedIndex = config.pinned.withIndex().associate { it.value to it.index }
        val sorted = byPkg.values.sortedWith(
            compareBy<AppEntry>({ pinnedIndex[it.key] ?: Int.MAX_VALUE }, { it.label.lowercase() }))

        val settings = AppEntry(
            AppEntry.SETTINGS_KEY,
            config.themeFor(AppEntry.SETTINGS_KEY)?.name ?: "Settings",
            null, true)
        val settingsAvailable = Intent(Settings.ACTION_SETTINGS).resolveActivity(pm) != null
        return if (settingsAvailable) sorted + settings else sorted
    }

    /**
     * The fixed home grid: exactly the keys in config.home, in order. Installed apps come from [all]
     * (same discovery as before); an app that is not installed still keeps its slot (shown dimmed).
     */
    fun homeEntries(config: WallpaperConfig, all: List<AppEntry>): List<AppEntry> {
        val byKey = all.associateBy { it.key }
        return config.home.map { key ->
            byKey[key] ?: when (key) {
                AppEntry.SETTINGS_KEY -> AppEntry(key, config.themeFor(key)?.name ?: "Settings", null, true)
                AppEntry.MORE_KEY -> AppEntry(key, config.themeFor(key)?.name ?: "More Apps", null, true)
                else -> AppEntry(key, config.themeFor(key)?.name ?: key, null, true)
            }
        }
    }

    private fun addIfValid(out: MutableMap<String, AppEntry>, ri: ResolveInfo, config: WallpaperConfig, leanback: Boolean) {
        val ai = ri.activityInfo
        val pkg = ai.packageName
        if (pkg == ctx.packageName || pkg in config.hidden) return
        // queryIntentActivities already skips disabled components; also drop disabled/non-exported apps.
        if (!ai.applicationInfo.enabled || !ai.exported) return
        if (out.containsKey(pkg)) {
            // Several launcher activities in one package: prefer the platform's own choice.
            val preferred = pm.getLeanbackLaunchIntentForPackage(pkg)?.component
            if (preferred == null || preferred.className != ai.name) return
        }
        val label = config.themeFor(pkg)?.name ?: ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
        out[pkg] = AppEntry(pkg, label, ComponentName(pkg, ai.name), leanback)
    }

    /**
     * Tile artwork rasterised to EXACTLY tile size, so we never keep other apps' full-size banners
     * (some are 1280x720+) in memory. Safe to call from a background thread.
     */
    fun tileArt(e: AppEntry, w: Int, h: Int, iconSize: Int): TileArt {
        if (e.isSettings || e.isMore) return TileArt(null, null)
        val cn = e.component ?: return TileArt(null, null)
        val banner = try { pm.getActivityBanner(cn) ?: pm.getApplicationBanner(cn.packageName) } catch (ex: Exception) { null }
        if (banner != null) rasterize(banner, w, h, crop = true)?.let { return TileArt(it, null) }
        val icon = try { pm.getActivityIcon(cn) } catch (ex: Exception) { null }
        return TileArt(null, icon?.let { rasterize(it, iconSize, iconSize, crop = false) })
    }

    private fun rasterize(d: Drawable, w: Int, h: Int, crop: Boolean): Bitmap? = try {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (crop && cornerRadius > 0f) {
            // Rounded corners baked into the bitmap: no per-frame outline clipping on the GPU.
            c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), cornerRadius, cornerRadius, Path.Direction.CW) })
        }
        val iw = d.intrinsicWidth; val ih = d.intrinsicHeight
        if (iw > 0 && ih > 0) {
            val scale = if (crop) maxOf(w.toFloat() / iw, h.toFloat() / ih) else minOf(w.toFloat() / iw, h.toFloat() / ih)
            val dw = (iw * scale).toInt(); val dh = (ih * scale).toInt()
            val l = (w - dw) / 2; val t = (h - dh) / 2
            d.setBounds(l, t, l + dw, t + dh)
        } else d.setBounds(0, 0, w, h)
        d.draw(c)
        bmp
    } catch (ex: Throwable) { null }

    fun launchIntent(e: AppEntry): Intent? {
        if (e.isSettings) return Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val cn = e.component ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(if (e.isLeanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(cn)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    /** Cheap signature of the current app set, to avoid rebuilding the row when nothing changed. */
    fun signature(list: List<AppEntry>): String = list.joinToString("|") { it.key + "/" + (it.component?.className ?: "") + "/" + it.label }
}
