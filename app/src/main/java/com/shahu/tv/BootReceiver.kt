package com.shahu.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Startup: when Home Screen Mode = SHAHU'S TV, open SHAHU'S TV after Fire OS finishes booting.
 * Fire OS always shows Amazon Home first (it cannot be replaced); Android 9 still allows a normal app to open its
 * screen from BOOT_COMPLETED, so SHAHU'S TV comes up on top. Classic mode → does nothing. One-shot, no service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val shahu = HomeMode.isShahu(ctx)
        Log.i(TAG, "BOOT_COMPLETED received — Home Screen Mode SHAHU=$shahu")
        if (!shahu) return
        val app = ctx.applicationContext
        val pending = goAsync()
        // Give Amazon Home a moment to come up first, so SHAHU'S TV lands on top of it rather than under it.
        Handler(Looper.getMainLooper()).postDelayed({
            try { Log.i(TAG, "Opening SHAHU'S TV after boot: ${HomeMode.launchShahu(app)}") } finally { pending.finish() }
        }, BOOT_DELAY_MS)
    }

    companion object {
        private const val TAG = "ShahuBoot"
        private const val BOOT_DELAY_MS = 1500L
    }
}
