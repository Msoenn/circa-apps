package org.circa.launcher

import android.app.Application

/**
 * Launcher process. Everything the launcher has to watch for at *process* scope is hooked up here
 * rather than in an activity, so it survives the activity being stopped, finished or recreated:
 * stock's "return to the watch face" ([WatchFaceReturn]) needs to have seen the screen-off, which
 * can happen while no launcher activity is running at all, and the wake-up gesture sensors
 * ([WakeGestures]) have to stay registered with the panel off, when no activity exists.
 */
class AuroraApp : Application() {

    /** Held for the life of the process: it owns registered listeners, observers and receivers. */
    private var wakeGestures: WakeGestures? = null

    override fun onCreate() {
        super.onCreate()
        WatchFaceReturn.register(this)
        wakeGestures = WakeGestures(this).also { it.start() }
    }
}
