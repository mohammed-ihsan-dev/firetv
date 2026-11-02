package com.shahu.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * HOME SCREEN MODE: "SHAHU'S TV — Custom Home" or "Amazon Fire TV — Classic Home".
 *
 * Fire OS 7.7.1.7 facts (device-inspection/REPORT.md):
 *  - HOME resolves to com.amazon.tv.launcher/.ui.HomeActivity_vNext at priority 950 (system priv-app).
 *  - The Home KEY is routed to Amazon's launcher by Fire OS itself (HOME_PRESSED signature permission),
 *    not by normal HOME resolution, so a third-party HOME intent filter cannot take the Home button.
 *  - We never disable or modify Amazon packages.
 *
 * What SHAHU mode therefore does (all reversible, all inside our own package):
 *  1. Enables our own `.HomeAlias` (HOME intent filter). Harmless on Fire OS (Amazon's 950 priority still wins),
 *     but makes SHAHU'S TV a valid HOME candidate.
 *  2. If the user has enabled the optional accessibility service [HomeButtonService] (EXPERIMENTAL, must be
 *     verified on the device), a short Home press opens SHAHU'S TV. Long-press Home is passed through.
 * Classic mode disables the alias and the service passes every key through untouched.
 */
object HomeMode {
    const val SHAHU = "shahu"
    const val CLASSIC = "classic"
    private const val PREF = "home_mode"
    private const val TAG = "ShahuHomeMode"
    const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"

    fun get(ctx: Context): String =
        ctx.getSharedPreferences("shahu", Context.MODE_PRIVATE).getString(PREF, SHAHU) ?: SHAHU

    fun isShahu(ctx: Context) = get(ctx) == SHAHU

    fun set(ctx: Context, mode: String) {
        ctx.getSharedPreferences("shahu", Context.MODE_PRIVATE).edit().putString(PREF, mode).apply()
        apply(ctx)
        Log.i(TAG, "Home mode → $mode")
    }

    /** Make the component state match the saved mode (cheap; safe to call on every start). */
    fun apply(ctx: Context) {
        val want = if (isShahu(ctx)) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                   else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val cn = ComponentName(ctx, "com.shahu.tv.HomeAlias")
        val pm = ctx.packageManager
        try {
            if (pm.getComponentEnabledSetting(cn) != want) pm.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP)
        } catch (e: Exception) { Log.w(TAG, "Cannot change HomeAlias state", e) }
        // Verified on AFTSSS / Fire OS 7.7.1.7 (2 Nov 2026): `cmd package set-home-activity` stores a preferred HOME
        // for SHAHU'S TV but Fire OS ignores it (HOME still resolves to and opens Amazon). Never leave such a dormant
        // preference behind: it could take effect unexpectedly after a Fire OS / launcher change.
        try { pm.clearPackagePreferredActivities(ctx.packageName) } catch (e: Exception) { Log.w(TAG, "clearPackagePreferredActivities failed", e) }
    }

    /** Is our accessibility service (the optional Home-button redirect) switched on in system settings? */
    fun isHomeButtonServiceEnabled(ctx: Context): Boolean {
        val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, HomeButtonService::class.java)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Open SHAHU'S TV's home carousel (closes the More Apps panel if it is open). */
    fun launchShahu(ctx: Context): Boolean = try {
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_FROM_HOME, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        true
    } catch (e: Exception) { Log.w(TAG, "Cannot open SHAHU'S TV", e); false }

    /** Safe way back to the original Amazon interface. Never touches Amazon's package state. */
    fun openAmazonHome(ctx: Context): Boolean {
        HomeReturn.suppress()  // deliberate: do not bounce back to SHAHU'S TV
        val base = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (intent in listOf(Intent(base).setPackage(AMAZON_LAUNCHER), base)) {
            try { ctx.startActivity(intent); return true } catch (e: Exception) { Log.w(TAG, "Amazon Home via $intent failed", e) }
        }
        return false
    }
}
