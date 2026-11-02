package com.shahu.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import java.io.File
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Memory-conscious wallpaper decoding.
 *
 *  - Decodes on ONE background thread, sampled down to the screen size (never larger).
 *  - LruCache holds at most ~2 full-screen bitmaps (current + one preloaded neighbour).
 *  - [release] drops everything (called when the app goes to the background).
 *  - Lookup order for a relative path like "wallpapers/batman.jpg":
 *        external files dir (adb-pushed)  →  APK assets  →  default wallpaper  →  null (solid colour)
 *  - Paths are validated: no absolute paths, no "..", must stay inside the base directory.
 */
class WallpaperLoader(context: Context, private val targetW: Int, private val targetH: Int) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shahu-wallpaper").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes(targetW, targetH)) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue) main.post { onEvicted?.invoke(oldValue) }
        }
    }

    /**
     * Called on the MAIN thread for every bitmap leaving the cache. The UI recycles it if it is not on
     * screen, so its 8 MB native buffer + GPU texture are freed immediately instead of waiting for a GC
     * (this app allocates almost nothing on the Java heap, so GCs are rare).
     */
    var onEvicted: ((Bitmap) -> Unit)? = null

    @Volatile var lastDecodeMs: Long = 0; private set

    fun cached(path: String): Bitmap? = cache.get(path)

    fun isCached(b: Bitmap): Boolean = cache.snapshot().values.any { it === b }

    /** Async load; [onLoaded] runs on the main thread with the bitmap (or null if nothing decodable). */
    fun load(path: String, defaultPath: String, onLoaded: (String, Bitmap?) -> Unit) {
        cache.get(path)?.let { onLoaded(path, it); return }
        executor.execute {
            val bmp = decodeCached(path) ?: if (path != defaultPath) decodeCached(defaultPath) else null
            main.post { onLoaded(path, bmp) }
        }
    }

    /**
     * Decode the next likely wallpaper in the background. [keep] (the one on screen) is touched first so
     * it is most-recently-used; with a 2-bitmap cache only an older, no-longer-shown bitmap is evicted.
     */
    fun preload(path: String, keep: String?, fallback: String? = null) {
        if (cache.get(path) != null) return
        executor.execute {
            keep?.let { cache.get(it) }
            if (decodeCached(path) == null && fallback != null && fallback != path) decodeCached(fallback)
        }
    }

    fun release() {
        cache.evictAll()
    }

    fun shutdown() {
        executor.shutdownNow()
        cache.evictAll()
    }

    private fun decodeCached(path: String): Bitmap? {
        cache.get(path)?.let { return it }
        val t0 = SystemClock.elapsedRealtime()
        val bmp = try {
            decode(path)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM decoding $path — clearing cache", oom)
            cache.evictAll(); null
        } catch (e: Exception) {
            Log.w(TAG, "Cannot decode $path: ${e.message}"); null
        }
        lastDecodeMs = SystemClock.elapsedRealtime() - t0
        if (bmp != null) {
            cache.put(path, bmp)
            Log.i(TAG, "decoded $path ${bmp.width}x${bmp.height} in ${lastDecodeMs}ms cache=${cache.size() / 1024}KB")
        }
        return bmp
    }

    private fun decode(path: String): Bitmap? {
        if (!isSafeRelative(path)) {
            Log.w(TAG, "Rejected unsafe wallpaper path: $path"); return null
        }
        val opener: (() -> InputStream)? = externalFile(path)?.let { f -> { f.inputStream() } }
            ?: assetOpener(path)
        if (opener == null) {
            Log.w(TAG, "Wallpaper not found: $path"); return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        opener().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "Not an image: $path"); return null
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val decoded = opener().use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return composeForScreen(decoded)
    }

    /**
     * Produce an exactly screen-sized bitmap with the readability scrims baked in, so the UI draws
     * ONE opaque full-screen image per frame (no runtime gradient layers — big win on this GPU).
     * Exact-size wallpapers are processed in place (no extra allocation).
     */
    private fun composeForScreen(src: Bitmap): Bitmap {
        val out: Bitmap
        if (src.width == targetW && src.height == targetH && src.isMutable) {
            out = src
        } else {
            out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val scale = maxOf(targetW.toFloat() / src.width, targetH.toFloat() / src.height) // centerCrop
            val m = Matrix().apply {
                setScale(scale, scale)
                postTranslate((targetW - src.width * scale) / 2f, (targetH - src.height * scale) / 2f)
            }
            Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
            src.recycle() // private, never displayed
        }
        scrims?.invoke(Canvas(out), targetW, targetH)
        out.setHasAlpha(false)
        out.prepareToDraw() // ask the render thread to upload the GPU texture now, not on the first fade frame
        return out
    }

    /** Scrim painter supplied by the UI (keeps layout knowledge out of the loader). */
    var scrims: ((Canvas, Int, Int) -> Unit)? = null

    private fun sampleSize(w: Int, h: Int): Int {
        var s = 1
        // Largest power of two that keeps the image >= screen size in both dimensions (centerCrop).
        while (w / (s * 2) >= targetW && h / (s * 2) >= targetH) s *= 2
        return s
    }

    private fun externalFile(path: String): File? {
        val base = WallpaperConfig.externalDir(app) ?: return null
        val f = File(base, path)
        return try {
            val canon = f.canonicalFile
            if (canon.path.startsWith(base.canonicalPath + File.separator) && canon.isFile && canon.length() > 0) canon else null
        } catch (e: Exception) { null }
    }

    private fun assetOpener(path: String): (() -> InputStream)? {
        val exists = try { app.assets.open(path).close(); true } catch (e: Exception) { false }
        return if (exists) ({ app.assets.open(path) }) else null
    }

    companion object {
        private const val TAG = "ShahuWallpaper"

        /** Two full-screen ARGB_8888 bitmaps (~16.6 MB at 1920x1080). */
        fun maxCacheBytes(w: Int, h: Int) = w * h * 4 * 2 + 64 * 1024

        fun isSafeRelative(p: String): Boolean =
            p.isNotBlank() && !p.startsWith("/") && !p.contains("..") && !p.contains('\\') && !p.contains(':')
    }
}
