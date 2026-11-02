package com.shahu.tv

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Date

/**
 * SHAHU'S TV home: Apple TV-inspired circular app carousel over a full-screen cinematic wallpaper.
 * The wallpaper follows the selected app (YouTube picks one of several at random each time it gains focus).
 * Settings opens SHAHU'S TV Settings (Fire TV settings, Home Screen Mode, Device Manager).
 * "More Apps" opens an in-app panel with every other installed app (same discovery + launching as before).
 */
class MainActivity : Activity() {

    // ---- views
    private lateinit var wallBack: ImageView
    private lateinit var wallFront: ImageView
    private lateinit var homeLayer: View
    private lateinit var carousel: CarouselView
    private lateinit var appName: TextView
    private lateinit var appDesc: TextView
    private lateinit var morePanel: View
    private lateinit var moreScroll: ScrollView
    private lateinit var moreGrid: GridLayout
    private lateinit var moreCount: TextView
    private lateinit var moreFocused: TextView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var wifi: WifiIndicatorView
    private lateinit var empty: TextView

    // ---- state
    private lateinit var repo: AppRepository
    private lateinit var config: WallpaperConfig
    private lateinit var loader: WallpaperLoader
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("shahu", MODE_PRIVATE) }

    private var homeEntries: List<AppEntry> = emptyList()
    private var otherEntries: List<AppEntry> = emptyList()
    private var rowSignature = ""
    private var focusedKey: String? = null     // focused HOME tile
    private var lastFocusIndex = 0
    private var moveDelta = 1                  // last grid move (for preloading the likely next wallpaper)
    private var moreOpen = false
    private var moreBuilt = false
    private var moreFocusKey: String? = null

    private var activeWallpaper: String? = null // wallpaper chosen for the focused tile (stable while focused)
    private val variants = VariantPicker()

    private var shownPath: String? = null      // wallpaper currently on screen
    private var shownBitmap: Bitmap? = null
    private var requestedPath: String? = null  // wallpaper we are waiting for
    private var assetStamp = 0L
    private var started = false
    private var fade: android.animation.ObjectAnimator? = null
    private var windowBgVisible = true

    private val wallpaperRunnable = Runnable { applyWallpaperForFocus() }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        val t0 = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        wallBack = findViewById(R.id.wallBack)
        wallFront = findViewById(R.id.wallFront)
        homeLayer = findViewById(R.id.homeLayer)
        carousel = findViewById(R.id.carousel)
        carousel.onSelectionChanged = { onHomeTileFocused(it) }
        carousel.onClick = { i -> homeEntries.getOrNull(i)?.let { activate(it) } }
        appName = findViewById(R.id.appName)
        appDesc = findViewById(R.id.appDesc)
        morePanel = findViewById(R.id.morePanel)
        moreScroll = findViewById(R.id.moreScroll)
        moreGrid = findViewById(R.id.moreGrid)
        moreCount = findViewById(R.id.moreCount)
        moreFocused = findViewById(R.id.moreFocused)
        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        wifi = findViewById(R.id.wifi)
        empty = findViewById(R.id.empty)

        val size = Point().also { windowManager.defaultDisplay.getRealSize(it) }
        // Wallpapers are decoded at most 1280 px wide and scaled up by the GPU (ImageView centerCrop).
        // The Sony KLV-32R302E panel is 1366x768, so this is visually identical on this TV, while it halves
        // the per-change texture upload and bitmap memory (measured jank on AFTSSS: see DEVELOPMENT-REPORT.md).
        val sw = size.x.coerceAtLeast(1); val sh = size.y.coerceAtLeast(1)
        val ww = minOf(sw, MAX_WALLPAPER_W)
        loader = WallpaperLoader(this, ww, (ww.toLong() * sh / sw).toInt().coerceAtLeast(1))
        repo = AppRepository(this).apply { cornerRadius = resources.getDimension(R.dimen.tile_radius) }
        loader.scrims = ::paintScrims
        loader.onEvicted = { b -> if (!isOnScreen(b) && !b.isRecycled) b.recycle() }
        config = WallpaperConfig.load(this)
        assetStamp = externalStamp()
        focusedKey = prefs.getString(KEY_LAST, null)
        prefs.all.forEach { (k, v) -> if (k.startsWith(KEY_VARIANT) && v is String) variants.last[k.removePrefix(KEY_VARIANT)] = v }
        applyHomeModeExtra(intent)
        HomeMode.apply(this)
        rebuildIfChanged(force = true)
        Log.i(TAG, "onCreate ${SystemClock.elapsedRealtime() - t0}ms, home=${homeEntries.size} more=${otherEntries.size}, screen ${size.x}x${size.y}")
    }

    override fun onStart() {
        super.onStart()
        started = true
        // Config / wallpaper files pushed while we were away?  Reload without a rebuild of the APK.
        val stamp = externalStamp()
        if (stamp != assetStamp) {
            Log.i(TAG, "External config/wallpapers changed — reloading")
            assetStamp = stamp
            config = WallpaperConfig.load(this)
            loader.release()
            shownPath = null
            activeWallpaper = null
            variants.clearUpcoming()
            rowSignature = "" // labels/order may have changed
        }
        rebuildIfChanged(force = false)
        registerSystemReceivers()
        updateClock()
        updateNetwork()
        scheduleWallpaper(0)
    }

    override fun onStop() {
        started = false
        unregisterSystemReceivers()
        main.removeCallbacks(wallpaperRunnable)
        saveLastFocus()
        // Free the preload cache + back layer while a streaming app runs. The single on-screen
        // bitmap is kept so returning is instant; it is dropped too if the system asks (onTrimMemory).
        fade?.end()
        wallBack.setImageDrawable(null)
        loader.release()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !started)) {
            Log.i(TAG, "onTrimMemory($level) — releasing wallpaper")
            loader.release()
            wallBack.setImageDrawable(null)
            wallFront.setImageDrawable(null)
            shownPath = null; shownBitmap = null
            window.setBackgroundDrawable(ColorDrawable(getColor(R.color.bg))); windowBgVisible = true
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        loader.shutdown()
        artExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyHomeModeExtra(intent)
        // Home button redirect (Home Screen Mode = SHAHU'S TV): always land on the carousel.
        if (intent.getBooleanExtra(EXTRA_FROM_HOME, false) && moreOpen) closeMore()
    }

    /**
     * Recovery hook for scripts/restore.sh: `am start -n com.shahu.tv/.MainActivity --es com.shahu.tv.SET_HOME_MODE classic`.
     * Only ever switches TO Classic (the safe direction); it can never turn SHAHU mode on.
     */
    private fun applyHomeModeExtra(i: Intent?) {
        if (i?.getStringExtra(EXTRA_SET_HOME_MODE) == HomeMode.CLASSIC) {
            HomeMode.set(this, HomeMode.CLASSIC)
            Log.i(TAG, "Home Screen Mode set to Classic by recovery intent")
        }
    }

    /** HOME pressed while this screen is visible (Home Screen Mode = SHAHU'S TV) → see HomeReturn. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        HomeReturn.onUserLeave(this)
    }

    override fun onBackPressed() {
        if (moreOpen) { closeMore(); return }
        super.onBackPressed() // unchanged: leave SHAHU'S TV → Amazon Home
    }

    // ------------------------------------------------------------------ home grid
    private fun rebuildIfChanged(force: Boolean) {
        val all = try { repo.query(config) } catch (e: Exception) {
            Log.e(TAG, "App query failed", e); null
        }
        val home = if (all != null) repo.homeEntries(config, all) else homeEntries
        val homeKeys = home.map { it.key }.toSet()
        val others = all?.filter { it.key !in homeKeys && !it.isSettings } ?: otherEntries
        val sig = repo.signature(home) + "#" + repo.signature(others)
        if (!force && sig == rowSignature) return
        rowSignature = sig
        homeEntries = home
        otherEntries = others

        val t0 = SystemClock.elapsedRealtime()
        // Restore selection: same app if it is still on the carousel, else nearest index, else first.
        val idx = home.indexOfFirst { it.key == focusedKey }.takeIf { it >= 0 }
            ?: lastFocusIndex.coerceAtMost(home.size - 1).coerceAtLeast(0)
        carousel.setItems(home.map { e ->
            val (icon, glow) = CAROUSEL_ART[e.key] ?: (R.drawable.circle_more to 0xFFFFFFFF.toInt())
            CarouselView.Item(icon, e.label, glow)
        }, idx)
        lastFocusIndex = idx

        // More Apps panel: rebuilt now if it is open, otherwise lazily the next time it opens.
        moreBuilt = false
        if (moreOpen) buildMoreGrid(focus = true)

        if (home.isNotEmpty()) {
            // Establish the focused app (caption + wallpaper) now — not on a later frame —
            // so the very first wallpaper decoded is the right one.
            onHomeTileFocused(idx)
            // Focus synchronously too, so the framework never auto-focuses the first tile on first layout.
            if (!moreOpen) { carousel.requestFocus(); carousel.post { if (!moreOpen && !carousel.isFocused) carousel.requestFocus() } }
        }
        Log.i(TAG, "Home built in ${SystemClock.elapsedRealtime() - t0}ms: ${home.joinToString { it.key }} | more=${others.size}")
    }

    private val tileOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, resources.getDimension(R.dimen.tile_radius))
        }
    }

    private fun buildTile(e: AppEntry, syncArt: Boolean, onFocus: () -> Unit, onClick: () -> Unit): View {
        val tile = FrameLayout(this)
        tile.isFocusable = true
        tile.isFocusableInTouchMode = false
        tile.foreground = getDrawable(R.drawable.tile_focus)   // glass sheen + hairline / focus border
        tile.contentDescription = e.label
        tile.outlineProvider = tileOutline                     // soft shadow follows the rounded tile (no clipping)
        tile.elevation = resources.getDimension(R.dimen.tile_elevation)
        if (e.isMissing) tile.alpha = 0.6f

        val art = synchronized(artCache) { artCache[e.key] }
        if (art != null) bindArt(tile, e, art)
        else if (syncArt) bindArt(tile, e, loadArt(e))
        else {
            bindArt(tile, e, TileArt(null, null))  // label placeholder until the banner is ready
            artExecutor.execute {
                val a = loadArt(e)
                main.post { if (tile.isAttachedToWindow || tile.parent != null) bindArt(tile, e, a) }
            }
        }
        val focusZ = resources.getDimension(R.dimen.tile_focus_z)
        tile.setOnFocusChangeListener { v, has ->
            // Gentle lift: scale + shadow, render-thread properties only (no layout pass, no bounce).
            v.animate().scaleX(if (has) FOCUS_SCALE else 1f).scaleY(if (has) FOCUS_SCALE else 1f)
                .translationZ(if (has) focusZ else 0f)
                .setDuration(FOCUS_MS).setInterpolator(EASE).start()
            if (has) onFocus()
        }
        tile.setOnClickListener { onClick() }
        return tile
    }

    private val artCache = HashMap<String, TileArt>()
    private val artExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun loadArt(e: AppEntry): TileArt {
        synchronized(artCache) { artCache[e.key]?.let { return it } }
        val w = resources.getDimensionPixelSize(R.dimen.tile_w)
        val h = resources.getDimensionPixelSize(R.dimen.tile_h)
        val a = repo.tileArt(e, w, h, dp(40))
        synchronized(artCache) { artCache[e.key] = a }
        return a
    }

    private fun bindArt(tile: FrameLayout, e: AppEntry, art: TileArt) {
        tile.removeAllViews()
        // Banner tiles: the (pre-rounded) bitmap is the whole tile → no background layer underneath.
        tile.background = if (art.banner != null) null else getDrawable(R.drawable.tile_bg)
        if (art.banner != null) {
            tile.addView(ImageView(this).apply {
                setImageBitmap(art.banner)
                scaleType = ImageView.ScaleType.FIT_XY
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return
        }
        // Glass tile: icon + label.
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val iconRes = when { e.isSettings -> R.drawable.ic_settings; e.isMore -> R.drawable.ic_apps; else -> 0 }
        if (iconRes != 0) {
            box.addView(ImageView(this).apply { setImageResource(iconRes) }, LinearLayout.LayoutParams(dp(32), dp(32)))
        } else if (art.icon != null) {
            box.addView(ImageView(this).apply { setImageBitmap(art.icon) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        box.addView(TextView(this).apply {
            text = e.label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), 0)
        })
        if (e.isMissing) box.addView(TextView(this).apply {
            text = getString(R.string.not_installed)
            setTextColor(getColor(R.color.text_tertiary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
        })
        tile.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun onHomeTileFocused(index: Int) {
        val e = homeEntries.getOrNull(index) ?: return
        if (index != lastFocusIndex) {
            // Shortest way round the ring: +1 / -1 (used to preload the likely next wallpaper).
            moveDelta = if (CarouselMath.offset(index, lastFocusIndex, homeEntries.size) >= 0) 1 else -1
        }
        lastFocusIndex = index
        if (e.key != focusedKey || activeWallpaper == null) {
            focusedKey = e.key
            activeWallpaper = pickWallpaper(e.key)   // new pick only when focus ENTERS a tile
        }
        appName.text = e.label
        appDesc.text = if (e.isMissing) getString(R.string.not_installed)
            else config.themeFor(e.key)?.description ?: getString(R.string.default_description)
        scheduleWallpaper(WALLPAPER_DEBOUNCE_MS)
    }

    /** OK on the selected carousel app. */
    private fun activate(e: AppEntry) {
        when {
            e.isMore -> openMore()
            e.isSettings -> {
                saveLastFocus()
                try { startActivity(Intent(this, SettingsActivity::class.java)) }
                catch (ex: Exception) { Log.w(TAG, "SHAHU'S TV Settings failed — opening Fire TV settings", ex); launch(e) }
            }
            else -> launch(e)
        }
    }

    private fun launch(e: AppEntry) {
        saveLastFocus()
        val intent = repo.launchIntent(e)
        try {
            if (intent == null) throw IllegalStateException("no intent")
            Log.i(TAG, "Launching ${e.key} ${intent.component ?: intent.action}")
            startActivity(intent)
        } catch (ex: Exception) {
            Log.w(TAG, "Launch failed for ${e.key}", ex)
            Toast.makeText(this, getString(R.string.launch_failed, e.label), Toast.LENGTH_SHORT).show()
            rebuildIfChanged(force = false) // maybe it was just uninstalled
        }
    }

    private fun saveLastFocus() {
        focusedKey?.let { prefs.edit().putString(KEY_LAST, it).apply() }
    }

    // ------------------------------------------------------------------ More Apps panel
    private fun openMore() {
        if (moreOpen) return
        moreOpen = true
        morePanel.visibility = View.VISIBLE
        if (!moreBuilt) buildMoreGrid(focus = true) else focusMoreTile()
        homeLayer.visibility = View.GONE        // after the panel took focus, so focus never "falls" elsewhere
        scheduleWallpaper(0)                     // plain library backdrop behind the panel
        Log.i(TAG, "More Apps opened (${otherEntries.size} apps)")
    }

    private fun closeMore() {
        moreOpen = false
        homeLayer.visibility = View.VISIBLE
        carousel.requestFocus()
        morePanel.visibility = View.GONE
        scheduleWallpaper(0)
        Log.i(TAG, "More Apps closed")
    }

    private fun buildMoreGrid(focus: Boolean) {
        moreGrid.removeAllViews()
        val w = resources.getDimensionPixelSize(R.dimen.tile_w)
        val h = resources.getDimensionPixelSize(R.dimen.tile_h)
        val gap = resources.getDimensionPixelSize(R.dimen.tile_gap)
        val rowGap = resources.getDimensionPixelSize(R.dimen.tile_row_gap)
        val cols = moreGrid.columnCount
        otherEntries.forEachIndexed { i, e ->
            val tile = buildTile(e, syncArt = i < 8,
                onFocus = { moreFocusKey = e.key; moreFocused.text = e.label },
                onClick = { launch(e) })
            val lp = GridLayout.LayoutParams().apply {
                width = w; height = h
                rightMargin = if (i % cols < cols - 1) gap else 0
                bottomMargin = rowGap
            }
            moreGrid.addView(tile, lp)
        }
        moreCount.text = getString(R.string.more_count, otherEntries.size)
        empty.visibility = if (otherEntries.isEmpty()) View.VISIBLE else View.GONE
        moreBuilt = true
        if (focus) focusMoreTile()
    }

    private fun focusMoreTile() {
        if (moreGrid.childCount == 0) { moreFocused.text = ""; return }
        val idx = otherEntries.indexOfFirst { it.key == moreFocusKey }.takeIf { it >= 0 } ?: 0
        val t = moreGrid.getChildAt(idx)
        if (!t.requestFocus()) t.post { t.requestFocus() }
        if (idx == 0) moreScroll.scrollTo(0, 0)
    }

    // ------------------------------------------------------------------ wallpaper
    /** New random variant for [key], never the same as the previous one (when there is a choice). */
    private fun pickWallpaper(key: String): String {
        val choices = config.wallpaperChoicesFor(key)
        val prev = variants.last[key]
        val pick = variants.pick(key, choices) ?: return config.defaultWallpaper
        if (choices.size > 1) {
            prefs.edit().putString(KEY_VARIANT + key, pick).apply()
            Log.i(TAG, "Wallpaper for $key → $pick (previous $prev)")
        }
        return pick
    }

    /** Wallpaper that WOULD be shown if [key] gained focus next (for preloading only). */
    private fun upcomingWallpaperFor(key: String): String {
        if (key == focusedKey) activeWallpaper?.let { return it }
        return variants.upcoming(key, config.wallpaperChoicesFor(key)) ?: config.defaultWallpaper
    }

    private fun currentWallpaperPath(): String =
        if (moreOpen) config.themeFor(LIBRARY_KEY)?.wallpaper ?: config.wallpaperFor(AppEntry.MORE_KEY)
        else activeWallpaper ?: config.defaultWallpaper

    private fun scheduleWallpaper(delay: Long) {
        main.removeCallbacks(wallpaperRunnable)
        main.postDelayed(wallpaperRunnable, delay)
    }

    private fun applyWallpaperForFocus() {
        if (!started) return
        val path = currentWallpaperPath()
        if (path == shownPath && wallFront.drawable != null) { preloadNext(); return }
        requestedPath = path
        loader.load(path, if (moreOpen) config.defaultWallpaper else config.fallbackFor(focusedKey)) { p, bmp ->
            if (!started || p != requestedPath) return@load   // user already moved on
            show(p, bmp)
            preloadNext()
        }
    }

    private fun isOnScreen(b: Bitmap): Boolean =
        b === shownBitmap ||
            (wallFront.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap === b ||
            (wallBack.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap === b

    private fun show(path: String, bmp: Bitmap?) {
        if (bmp != null && bmp.isRecycled) { shownPath = null; scheduleWallpaper(0); return } // defensive
        fade?.cancel()
        if (bmp == null) {           // nothing decodable at all → plain background colour
            wallFront.setImageDrawable(ColorDrawable(getColor(R.color.bg)))
            wallBack.setImageDrawable(null)
            shownPath = path; shownBitmap = null
            return
        }
        if (bmp === shownBitmap) { shownPath = path; return } // same image file (shared mapping): no fade
        if (windowBgVisible) { window.setBackgroundDrawable(null); windowBgVisible = false } // wallpaper is opaque
        if (shownBitmap == null) {   // first picture after a (cold) start: show immediately, no fade
            wallBack.setImageDrawable(null)
            wallFront.setImageBitmap(bmp); wallFront.alpha = 1f
            shownPath = path; shownBitmap = bmp
            return
        }
        // Old picture goes to the back layer, new one fades in on top (one smooth crossfade).
        wallBack.setImageBitmap(shownBitmap)
        wallBack.alpha = 1f
        wallFront.setImageBitmap(bmp)
        wallFront.alpha = 0f
        shownPath = path; shownBitmap = bmp
        fade = android.animation.ObjectAnimator.ofFloat(wallFront, View.ALPHA, 0f, 1f).apply {
            duration = CROSSFADE_MS
            interpolator = EASE
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    wallFront.alpha = 1f
                    val old = (wallBack.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    wallBack.setImageDrawable(null)   // drop the reference to the old bitmap
                    // Evicted while it was still fading out? Free it now.
                    if (old != null && !old.isRecycled && !isOnScreen(old) && !loader.isCached(old)) old.recycle()
                }
            })
            start()
        }
    }

    /** Readability gradients, painted into each wallpaper bitmap on the decoder thread (no overlay layers). */
    private fun paintScrims(c: android.graphics.Canvas, w: Int, h: Int) {
        val k = w / resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
        val px = { v: Int -> dp(v) * k }
        val bg = getColor(R.color.bg) and 0x00FFFFFF
        fun col(a: Int) = (a shl 24) or bg
        val p = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG)
        // top: light veil behind the header only (the wallpaper stays the primary visual)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, px(110), intArrayOf(col(0x80), col(0x30), col(0)),
            floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), px(116), p)
        // bottom: soft fade behind the carousel + caption (no heavy dark overlay)
        val top = h - px(230)
        p.shader = android.graphics.LinearGradient(0f, top, 0f, h.toFloat(),
            intArrayOf(col(0), col(0x40), col(0x8C), col(0xB8)), floatArrayOf(0f, 0.35f, 0.72f, 1f),
            android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, top, w.toFloat(), h.toFloat(), p)
    }

    private fun preloadNext() {
        if (moreOpen) return
        if (homeEntries.isEmpty()) return
        val next = homeEntries[CarouselMath.wrap(lastFocusIndex + moveDelta, homeEntries.size)]
        val p = upcomingWallpaperFor(next.key)
        if (p != shownPath) loader.preload(p, shownPath, config.fallbackFor(next.key))
    }

    /** Stamp of external (adb-pushed) config + wallpaper files, to detect edits without rebuilding. */
    private fun externalStamp(): Long {
        var s = WallpaperConfig.currentExternalStamp(this)
        val dir = WallpaperConfig.externalDir(this)?.let { File(it, "wallpapers") }
        dir?.listFiles()?.forEach { s = s * 31 + it.lastModified() + it.length() }
        return s
    }

    // ------------------------------------------------------------------ keys
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val e = if (moreOpen) otherEntries.firstOrNull { it.key == moreFocusKey } else homeEntries.getOrNull(lastFocusIndex)
            if (e != null && !e.isSettings && !e.isMore && !e.isMissing) {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.key}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (ex: Exception) { Log.w(TAG, "App info unavailable", ex) }
            }
            return true
        }
        // Recover focus if (for any reason) no tile is focused.
        if (isDpad(keyCode)) {
            if (moreOpen && !moreGrid.hasFocus() && moreGrid.childCount > 0) { focusMoreTile(); return true }
            if (!moreOpen && !carousel.isFocused) { carousel.requestFocus(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isDpad(k: Int) = k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT ||
        k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN ||
        k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER

    // ------------------------------------------------------------------ clock / network / packages
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { updateClock(); updateNetwork() }
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Log.i(TAG, "Package change: ${i.action} ${i.data}")
            main.postDelayed({ if (started) rebuildIfChanged(force = false) }, 300)
        }
    }
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { main.post { updateNetwork() } }
        override fun onLost(n: Network) { main.post { updateNetwork() } }
        override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) { main.post { updateNetwork() } }
    }
    private var receiversRegistered = false

    private fun registerSystemReceivers() {
        if (receiversRegistered) return
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        try { getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(netCallback) }
        catch (e: Exception) { Log.w(TAG, "Network callback unavailable", e) }
        receiversRegistered = true
    }

    private fun unregisterSystemReceivers() {
        if (!receiversRegistered) return
        runCatching { unregisterReceiver(timeReceiver) }
        runCatching { unregisterReceiver(packageReceiver) }
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(netCallback) }
        receiversRegistered = false
    }

    private fun updateClock() {
        val now = Date()
        clock.text = DateFormat.format(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a", now)
        date.text = DateFormat.format("EEEE, d MMMM", now)
    }

    @Suppress("DEPRECATION")
    private fun updateNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        wifi.level = when {
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> -1
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 4
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val rssi = applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.rssi ?: -100
                WifiManager.calculateSignalLevel(rssi, 3) + 1
            }
            else -> 3
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val EXTRA_FROM_HOME = "com.shahu.tv.FROM_HOME"
        const val EXTRA_SET_HOME_MODE = "com.shahu.tv.SET_HOME_MODE"
        private const val TAG = "ShahuTV"
        /** Circular icon + halo colour per home key. */
        private val CAROUSEL_ART = mapOf(
            "com.amazon.firetv.youtube" to (R.drawable.circle_youtube to 0xFFFF2A2A.toInt()),
            "in.startv.hotstar" to (R.drawable.circle_hotstar to 0xFF7B2FF7.toInt()),
            "com.netflix.ninja" to (R.drawable.circle_netflix to 0xFFE50914.toInt()),
            "com.zee5.amazon" to (R.drawable.circle_zee5 to 0xFF9B2FD6.toInt()),
            "com.amazon.firebat" to (R.drawable.circle_prime to 0xFF1A98FF.toInt()),
            "com.fancode.tv" to (R.drawable.circle_fancode to 0xFFFF6A13.toInt()),
            AppEntry.SETTINGS_KEY to (R.drawable.circle_settings to 0xFFC9CED6.toInt()),
            AppEntry.MORE_KEY to (R.drawable.circle_more to 0xFF8C96BE.toInt()),
        )
        private const val KEY_LAST = "last_focused"
        private const val KEY_VARIANT = "variant:"
        private const val LIBRARY_KEY = "shahu.library"
        private const val FOCUS_SCALE = 1.07f
        private const val MAX_WALLPAPER_W = 1280
        private const val FOCUS_MS = 200L
        private const val CROSSFADE_MS = 320L
        private const val WALLPAPER_DEBOUNCE_MS = 90L
        private val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    }
}
