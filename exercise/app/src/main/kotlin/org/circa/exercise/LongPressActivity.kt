package org.circa.exercise

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import org.circa.exercise.data.Live
import org.circa.exercise.data.Storage

/**
 * Target of the side-button long press (`org.circa.action.EXERCISE_LONG_PRESS`, exercise/README.md). A
 * no-UI trampoline:
 *
 * - a workout is recording (or paused) -> the live screen with the End confirmation;
 * - else Settings.Global `circa_exercise_long_press`: "list" (default, also when unset) -> the activity list;
 *   "last" -> a 3 s cancellable countdown, then the last exercise starts (the list if there is none).
 *   "power" never gets here: the framework shows the power menu itself.
 */
class LongPressActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Live.isRecording(this)) {
            i.putExtra(MainActivity.EXTRA_ENTRY, MainActivity.ENTRY_END)
        } else {
            val mode = runCatching { Settings.Global.getString(contentResolver, SETTING) }.getOrNull()
            val last = Storage(this).lastType()
            if (mode == MODE_LAST && last != null) {
                i.putExtra(MainActivity.EXTRA_ENTRY, MainActivity.ENTRY_COUNTDOWN).putExtra(MainActivity.EXTRA_TYPE, last.id)
            } else {
                i.putExtra(MainActivity.EXTRA_ENTRY, MainActivity.ENTRY_LIST)
            }
        }
        startActivity(i)
        finish()
    }

    companion object {
        const val SETTING = "circa_exercise_long_press"
        const val MODE_LIST = "list"
        const val MODE_LAST = "last"
    }
}
