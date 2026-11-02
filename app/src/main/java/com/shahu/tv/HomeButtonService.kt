package com.shahu.tv

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * OPTIONAL + EXPERIMENTAL Home-button redirect for Home Screen Mode = SHAHU'S TV.
 *
 * VERIFIED NOT TO WORK on AFTSSS / Fire OS 7.7.1.7 (2 Nov 2026): a physical HOME press never reaches accessibility
 * key filters. Fire OS broadcasts amazon.intent.action.HOME_PRESSED (signature-protected; a normal app does not
 * receive it) and starts com.amazon.tv.launcher by explicit component. Kept for other Fire OS / Android TV builds.
 *
 * Off unless the user enables it (Fire TV Settings → Accessibility, or the adb command in TOMORROW-TESTING.md).
 * Whether Fire OS 7.7.1.7 delivers KEYCODE_HOME to accessibility key filters must be verified on the device.
 *
 * Safety:
 *  - Only KEYCODE_HOME is looked at; every other key is passed through (return false).
 *  - Classic mode → everything passes through.
 *  - Key DOWN is never consumed, so Fire OS still sees the press (long-press Home = quick settings keeps working).
 *  - Only a SHORT press's UP is consumed, and only if SHAHU'S TV actually started; otherwise it is passed through.
 *  - No event types are subscribed to, nothing is read from the screen.
 */
class HomeButtonService : AccessibilityService() {
    private var downTime = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME || !HomeMode.isShahu(this)) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                downTime = event.eventTime
                Log.i(TAG, "Home key DOWN received by accessibility filter")
            }
            KeyEvent.ACTION_UP -> {
                val held = event.eventTime - downTime
                if (event.isCanceled || held > LONG_PRESS_MS) return false
                val ok = HomeMode.launchShahu(this)
                Log.i(TAG, "Home short press (${held}ms) → SHAHU'S TV: $ok")
                return ok
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private const val TAG = "ShahuHomeButton"
        private const val LONG_PRESS_MS = 500L
    }
}
