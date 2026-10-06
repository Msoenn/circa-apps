package org.circa.exercise.data

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

/** The app's buzzes: a tap (start, pause, resume), a double pulse on a zone change, a happy burst on the summary. */
object Haptics {
    private fun vibe(ctx: Context, e: VibrationEffect) {
        runCatching { ctx.getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(e) }
    }

    fun tap(ctx: Context) = vibe(ctx, VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))

    fun zone(ctx: Context) = vibe(ctx, VibrationEffect.createWaveform(longArrayOf(0, 90, 90, 90), -1))

    /** One firm buzz: an auto-detected workout started. */
    fun detected(ctx: Context) = vibe(ctx, VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))

    fun warn(ctx: Context) = vibe(ctx, VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))

    fun happy(ctx: Context) = vibe(
        ctx,
        VibrationEffect.createWaveform(longArrayOf(0, 50, 60, 50, 60, 50, 90, 220), intArrayOf(0, 120, 0, 170, 0, 220, 0, 255), -1),
    )
}
