package com.shahu.tv

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Tracks whether any SHAHU'S TV screen is in front (used by [HomeReturn]). No background work. */
class ShahuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { resumed++ }
            override fun onActivityPaused(a: Activity) { resumed = (resumed - 1).coerceAtLeast(0) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    companion object {
        @Volatile var resumed = 0
            private set
        val isInFront get() = resumed > 0
    }
}
