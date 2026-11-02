package com.shahu.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * SHAHU'S TV Settings (opened by the Settings tile).
 * The native Fire TV settings stay one click away (first row) — that is what the tile opened before.
 */
class SettingsActivity : Activity() {
    private lateinit var ui: TvUi
    private lateinit var modeRow: TvUi.Row
    private lateinit var serviceRow: TvUi.Row

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = TvUi(this).screen(getString(R.string.settings_title), getString(R.string.settings_subtitle))

        ui.section("Fire TV")
        val first = ui.row("Fire TV Settings", "Network, display, sound, applications, accessibility, My Fire TV") {
            open(Intent(Settings.ACTION_SETTINGS))
        }

        ui.section("Home screen mode")
        modeRow = ui.row("Home Screen Mode", null, null) { chooseMode() }
        serviceRow = ui.row("Home button → SHAHU'S TV (experimental)", null, null) { explainHomeButton() }
        ui.row("Open Amazon Fire TV Home now", "The original Amazon interface. Always available.") {
            if (!HomeMode.openAmazonHome(this)) toast("Amazon Home could not be opened")
        }

        ui.section("Device")
        ui.row("Device Manager", "Installed apps, force stop & cache (native), memory, storage, restart, device info") {
            startActivity(Intent(this, DeviceManagerActivity::class.java))
        }

        ui.section("About")
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        ui.row("SHAHU'S TV", "Premium. Cinematic. Personalized.", "v$v")

        first.view.requestFocus()
    }

    /** HOME pressed while this screen is visible (Home Screen Mode = SHAHU'S TV) → see HomeReturn. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        HomeReturn.onUserLeave(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val shahu = HomeMode.isShahu(this)
        modeRow.setValue(if (shahu) "SHAHU'S TV" else "Amazon Classic")
        modeRow.setSubtitle(if (shahu && isFireOs7()) "Preferred: SHAHU'S TV. Open it from Your Apps; Fire OS 7 keeps the Home button on Amazon Home."
                            else if (shahu) "Custom Home. Amazon's launcher stays installed and enabled."
                            else "Classic Amazon Fire TV Home. SHAHU'S TV is opened from Your Apps.")
        val svc = HomeMode.isHomeButtonServiceEnabled(this)
        val isFireOs7 = isFireOs7()
        serviceRow.setValue(if (svc) "On" else "Off")
        serviceRow.setSubtitle(when {
            isFireOs7 -> "Not supported on Fire OS 7: the Home button always opens Amazon Home (tested)."
            !svc -> "Off: the Home button opens Amazon Home (Fire OS default)."
            shahu -> "On: a short Home press opens SHAHU'S TV. Long-press Home is unchanged."
            else -> "On, but Classic mode: the Home button is passed through to Amazon."
        })
    }

    private fun chooseMode() {
        val labels = arrayOf("SHAHU'S TV — Custom Home", "Amazon Fire TV — Classic Home")
        val current = if (HomeMode.isShahu(this)) 0 else 1
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Home Screen Mode")
            .setSingleChoiceItems(labels, current) { dlg, which ->
                HomeMode.set(this, if (which == 0) HomeMode.SHAHU else HomeMode.CLASSIC)
                dlg.dismiss()
                refresh()
                if (which == 1) {
                    toast("Classic mode: opening Amazon Fire TV Home")
                    HomeMode.openAmazonHome(this)
                } else {
                    toast("SHAHU'S TV is your home screen")
                    HomeMode.launchShahu(this)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun explainHomeButton() {
        val on = HomeMode.isHomeButtonServiceEnabled(this)
        val a11y = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Home button redirect")
            .setMessage(
                "Fire OS sends the Home button to Amazon's launcher. A normal app cannot replace it.\n\n" +
                "SHAHU'S TV includes an optional accessibility service. When it is on and Home Screen Mode is " +
                "SHAHU'S TV, a short Home press opens SHAHU'S TV. Amazon's launcher is never disabled.\n\n" +
                "Status: ${if (on) "ON" else "OFF"}.\n\n" +
                (if (isFireOs7()) "TESTED ON THIS FIRE TV (Fire OS 7.7.1.7): NOT SUPPORTED. Fire OS handles the Home button itself " +
                    "and always opens Amazon Home. Tip: put SHAHU'S TV first in Your Apps (highlight it, press ☰, Move to front).\n\n" else "") +
                "Turn it on or off in Fire TV Settings → Accessibility. You can also use the adb commands " +
                "in TOMORROW-TESTING.md. Switching to Classic mode disables the redirect immediately.")
            .setNegativeButton(android.R.string.ok, null)
        // On Fire OS 7 the standard Accessibility action is a placeholder page, so only offer it where it is real.
        if (opensRealPage(a11y)) dlg.setPositiveButton("Accessibility settings") { _, _ -> open(a11y) }
        dlg.show()
    }

    private fun open(intent: Intent) {
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) {
            Log.w("ShahuSettings", "Cannot open $intent", e)
            try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e2: Exception) { toast("Not available on this device") }
        }
    }

    private fun isFireOs7() = android.os.Build.MANUFACTURER.equals("Amazon", true) && android.os.Build.VERSION.SDK_INT == 28

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
