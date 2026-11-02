package com.shahu.tv

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Device Manager — honest, supported-API-only tools for Android 9 / Fire OS 7:
 *  - Real memory + storage numbers from the OS (ActivityManager.MemoryInfo, StatFs). No "RAM booster".
 *  - Recently active apps from UsageStatsManager (needs Usage Access; Android 9 does not let normal apps list
 *    other apps' running processes, so nothing is invented).
 *  - Installed (user) apps: Open, Stop background processes (ActivityManager.killBackgroundProcesses — only
 *    background processes; Android refuses foreground/system ones), and the NATIVE App Info page for
 *    Force stop / Clear cache / Clear data / Uninstall (each with the system's own confirmation).
 *  - Restart: apps cannot reboot Fire TV (REBOOT is a system permission) → opens native settings + remote shortcut.
 * Nothing runs automatically; nothing is killed, cleared or disabled without the user selecting it.
 */
class DeviceManagerActivity : Activity() {
    private lateinit var ui: TvUi
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val am by lazy { getSystemService(ActivityManager::class.java) }

    private lateinit var memRow: TvUi.Row
    private lateinit var storageRow: TvUi.Row
    private lateinit var uptimeRow: TvUi.Row

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = TvUi(this).screen("Device Manager", "Live values reported by Fire OS. Clearing and force stop use the native Fire TV pages.")

        ui.section("Memory & storage")
        memRow = ui.row("Memory (RAM)")
        storageRow = ui.row("Internal storage")
        memRow.view.requestFocus()

        ui.section("Applications")
        ui.row("Manage installed applications", "Native Fire TV page: force stop, clear cache, clear data, uninstall") {
            open(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
        }
        // Fire OS 7 has no standalone storage page (verified on AFTSSS); show the row only where one exists.
        Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).takeIf { opensRealPage(it) }?.let { i ->
            ui.row("Storage settings", "Native Fire TV storage page") { open(i) }
        }

        ui.section("Recently active apps (last 24 h)")
        if (hasUsageAccess()) {
            val placeholder = ui.row("Loading…")
            io.execute {
                val rows = loadRecent()
                main.post {
                    val at = ui.list.indexOfChild(placeholder.view)
                    ui.list.removeView(placeholder.view)
                    if (rows.isEmpty()) insertRow(at, "No app activity recorded in the last 24 h", null, null, null)
                    rows.forEachIndexed { i, r -> insertRow(at + i, r.label, r.detail, r.ago) { appActions(r.pkg, r.label) } }
                }
            }
        } else {
            ui.row("Usage access is not granted",
                "Android 9 only lets apps see other running apps with Usage Access. Grant it once via adb (see TOMORROW-TESTING.md).",
                null, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).takeIf { opensRealPage(it) }?.let { i -> { open(i) } })
        }

        ui.section("Installed apps (select for actions)")
        val loading = ui.row("Loading…")
        io.execute {
            val apps = loadUserApps()
            main.post {
                val at = ui.list.indexOfChild(loading.view)
                ui.list.removeView(loading.view)
                apps.forEachIndexed { i, a -> insertRow(at + i, a.label, a.pkg, a.size) { appActions(a.pkg, a.label) } }
                appendTail()
            }
        }
    }

    /** Sections after the (async) app list. */
    private fun appendTail() {
        ui.section("Restart")
        ui.row("Restart Fire TV…", "Apps cannot reboot Fire TV. Opens My Fire TV, which has Restart.") { restart() }

        ui.section("Device information")
        val dm = resources.displayMetrics
        ui.row("Model", null, "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        ui.row("Android", null, "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        ui.row("Build (Fire OS)", null, Build.DISPLAY)
        ui.row("Screen", null, "${dm.widthPixels} × ${dm.heightPixels} @ ${dm.densityDpi} dpi")
        uptimeRow = ui.row("Uptime")
        ui.row("IP address", null, ipAddress() ?: "—")
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        ui.row("SHAHU'S TV", null, "v$v")
        refreshLive()
    }

    /** HOME pressed while this screen is visible (Home Screen Mode = SHAHU'S TV) → see HomeReturn. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        HomeReturn.onUserLeave(this)
    }

    override fun onResume() {
        super.onResume()
        refreshLive()
        main.postDelayed(ticker, 3000)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private val ticker = object : Runnable {
        override fun run() { refreshLive(); main.postDelayed(this, 3000) }
    }

    private fun refreshLive() {
        if (!::memRow.isInitialized) return
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        memRow.setValue("${fmt(mi.availMem)} free of ${fmt(mi.totalMem)}")
        memRow.setSubtitle(if (mi.lowMemory) "Fire OS reports LOW memory. Close apps you are not using."
                           else "Reported by Fire OS. Fire OS manages memory itself; no booster is needed.")
        val st = StatFs(Environment.getDataDirectory().path)
        storageRow.setValue("${fmt(st.availableBytes)} free of ${fmt(st.totalBytes)}")
        if (::uptimeRow.isInitialized) uptimeRow.setValue(duration(SystemClock.elapsedRealtime()))
    }

    // ------------------------------------------------------------------ app actions
    private fun appActions(pkg: String, label: String) {
        val launch = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        val system = try { packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 } catch (e: Exception) { true }
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (launch != null) actions += "Open" to { open(launch) }
        actions += "Stop background processes" to { stopBackground(pkg, label) }
        actions += "Force stop / Clear cache… (App Info)" to { openAppInfo(pkg) }
        if (!system && pkg != packageName) actions += "Uninstall…" to { confirmUninstall(pkg, label) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun stopBackground(pkg: String, label: String) {
        try {
            am.killBackgroundProcesses(pkg)
            Log.i(TAG, "killBackgroundProcesses($pkg)")
            toast("Asked Fire OS to stop $label's background processes. A foreground app is not affected; use Force stop in App Info for that.")
        } catch (e: Exception) {
            Log.w(TAG, "killBackgroundProcesses failed", e); toast("Not allowed by Fire OS")
        }
    }

    private fun openAppInfo(pkg: String) = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    private fun confirmUninstall(pkg: String, label: String) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Uninstall $label?")
            .setMessage("Fire TV will ask you to confirm again. App data will be removed.")
            .setPositiveButton("Continue") { _, _ -> open(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun restart() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Restart Fire TV")
            .setMessage("Fire OS does not let apps restart the device.\n\n" +
                "• Settings → My Fire TV → Restart\n" +
                "• or hold SELECT + PLAY/PAUSE on the remote for about 5 seconds.")
            .setPositiveButton("Open My Fire TV") { _, _ -> open(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ data
    private class AppRow(val pkg: String, val label: String, val size: String?)
    private class RecentRow(val pkg: String, val label: String, val detail: String, val ago: String)

    private fun loadUserApps(): List<AppRow> {
        val pm = packageManager
        val ssm = if (hasUsageAccess()) getSystemService(StorageStatsManager::class.java) else null
        return pm.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0 }
            .map { ai ->
                val size = try {
                    ssm?.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle())
                        ?.let { "${fmt(it.appBytes + it.dataBytes)} · cache ${fmt(it.cacheBytes)}" }
                } catch (e: Exception) { null }
                AppRow(ai.packageName, pm.getApplicationLabel(ai).toString(), size)
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun loadRecent(): List<RecentRow> {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val now = System.currentTimeMillis()
        val stats = try { usm.queryAndAggregateUsageStats(now - DateUtils.DAY_IN_MILLIS, now) } catch (e: Exception) { return emptyList() }
        val pm = packageManager
        return stats.values
            .filter { it.lastTimeUsed > now - DateUtils.DAY_IN_MILLIS && it.totalTimeInForeground > 0 && it.packageName != packageName }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null || pm.getLeanbackLaunchIntentForPackage(it.packageName) != null }
            .sortedByDescending { it.lastTimeUsed }
            .take(10)
            .map {
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(it.packageName, 0)).toString() } catch (e: Exception) { it.packageName }
                RecentRow(it.packageName, label, "On screen ${duration(it.totalTimeInForeground)} in the last 24 h",
                    DateUtils.getRelativeTimeSpanString(it.lastTimeUsed, now, DateUtils.MINUTE_IN_MILLIS).toString())
            }
    }

    private fun hasUsageAccess(): Boolean = try {
        val ops = getSystemService(AppOpsManager::class.java)
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    @Suppress("DEPRECATION")
    private fun ipAddress(): String? {
        val ip = applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }

    private fun insertRow(index: Int, title: String, subtitle: String?, value: String?, onClick: (() -> Unit)?) {
        val r = ui.row(title, subtitle, value, onClick)
        ui.list.removeView(r.view)
        ui.list.addView(r.view, index.coerceAtMost(ui.list.childCount), r.view.layoutParams)
    }

    private fun duration(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> "less than a minute"
            min < 60 -> "$min min"
            else -> "${min / 60} h ${min % 60} min"
        }
    }

    private fun fmt(b: Long) = Formatter.formatShortFileSize(this, b)

    private fun open(intent: Intent) {
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) {
            Log.w(TAG, "Cannot open $intent", e)
            try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); toast("Opened Fire TV Settings instead") }
            catch (e2: Exception) { toast("Not available on this device") }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object { private const val TAG = "ShahuDeviceMgr" }
}
