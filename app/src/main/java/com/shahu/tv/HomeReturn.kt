package com.shahu.tv

import android.app.Activity
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * HOME button while SHAHU'S TV is in front (Home Screen Mode = SHAHU'S TV) → come straight back to SHAHU'S TV.
 *
 * Why it works this way (verified on AFTSSS / Fire OS 7.7.1.7, 2 Nov 2026):
 *  - The physical HOME key is handled by Fire OS itself: it always starts
 *    com.amazon.tv.launcher/.ui.HomeActivity_vNext by explicit component. Preferred-home settings are ignored,
 *    accessibility key filters never see HOME, and amazon.intent.action.HOME_PRESSED is signature-protected.
 *  - What a normal app CAN see: Android calls onUserLeaveHint() on the visible activity when the user leaves it.
 *    UsageStatsManager (Usage Access) then tells us WHAT came to the front.
 * So: only if the thing that came to the front is exactly Amazon's Home activity do we return. Apps opened from
 * SHAHU'S TV, by Alexa, or Fire TV Settings (also in the launcher package, different class) are left alone.
 *
 * Safety valves: nothing happens in Classic mode, without Usage Access, after "Open Amazon Home", or on a second
 * HOME press within [ESCAPE_MS] (double-press HOME = Amazon Home). No service, nothing runs in the background.
 */
object HomeReturn {
    private const val TAG = "ShahuHomeReturn"
    const val AMAZON_HOME_CLASS = "com.amazon.tv.launcher.ui.HomeActivity_vNext"
    private const val CHECK_DELAY_MS = 450L
    private const val ESCAPE_MS = 4000L
    private const val RETRY_MS = 250L
    private const val MAX_RETRIES = 7
    private val main = Handler(Looper.getMainLooper())
    private var suppressUntil = 0L
    private var lastReturn = 0L

    /** Call before deliberately opening Amazon Home from SHAHU'S TV. */
    fun suppress(ms: Long = 5000L) { suppressUntil = SystemClock.elapsedRealtime() + ms }

    /** Call from every SHAHU'S TV activity's onUserLeaveHint(). */
    fun onUserLeave(a: Activity) {
        val ctx = a.applicationContext
        if (!HomeMode.isShahu(ctx)) return
        val leftAtWall = System.currentTimeMillis()
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ check(ctx, leftAtWall, 0) }, CHECK_DELAY_MS)
    }

    private fun check(ctx: Context, leftAtWall: Long, attempt: Int) {
        val now = SystemClock.elapsedRealtime()
        if (ShahuApp.isInFront) return                     // moved between our own screens
        if (now < suppressUntil) { Log.i(TAG, "Amazon Home opened on purpose — staying"); return }
        val front = lastForegroundClass(ctx, leftAtWall - 1500) ?: run {
            // Amazon Home can take ~0.5–1.5 s to report "in front" on this stick: look again a few times.
            if (attempt < MAX_RETRIES) { main.postDelayed({ check(ctx, leftAtWall, attempt + 1) }, RETRY_MS); return }
            Log.i(TAG, "No usage access / no foreground event — leaving the system alone"); return
        }
        if (front != AMAZON_HOME_CLASS) { Log.i(TAG, "Left to $front — not HOME, staying out"); return }
        if (now - lastReturn < ESCAPE_MS) { Log.i(TAG, "Second HOME within ${ESCAPE_MS}ms — letting Amazon Home stay"); lastReturn = 0; return }
        lastReturn = now
        Log.i(TAG, "HOME pressed in SHAHU'S TV → returning to SHAHU'S TV")
        HomeMode.launchShahu(ctx)
    }

    /** Class name of the most recent activity that moved to the foreground since [sinceWall], via Usage Access. */
    private fun lastForegroundClass(ctx: Context, sinceWall: Long): String? = try {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(sinceWall, System.currentTimeMillis() + 1000)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && e.packageName != ctx.packageName) last = e.className
        }
        last
    } catch (e: Exception) { null }
}
