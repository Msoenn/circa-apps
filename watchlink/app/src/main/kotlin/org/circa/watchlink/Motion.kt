package org.circa.watchlink

/**
 * Wrist movement for sleep tracking (docs/watch-ui/circa/sleep.md). Pure Kotlin, host-tested.
 *
 * The accelerometer is read batched at its lowest rate (about 1.6 Hz). Every 5-second epoch gets one arm angle from
 * its mean gravity vector, `atan(z / sqrt(x^2 + y^2))` (the "z-angle" of van Hees et al., 2015). An epoch whose angle
 * differs from the previous epoch's by more than [ANGLE_DEG] is *restless*. A minute is *still* when at least
 * [MIN_EPOCHS] of its epochs could be compared and none of them was restless. The angle only needs the direction of
 * gravity, so a low sample rate is enough.
 *
 * Times are elapsedRealtime milliseconds (SensorEvent.timestamp / 1e6). Samples arrive in order within a batch;
 * a sample older than the current epoch is dropped.
 */
class MotionEpochs {

    /** One bucket's worth: [minutes] with data, of which [still] were still; [restless] epochs in total. */
    class Tally(@JvmField val minutes: Int, @JvmField val still: Int, @JvmField val restless: Int)

    private class Minute {
        var compared = 0
        var restless = 0
    }

    private var epoch = NONE
    private var sx = 0.0
    private var sy = 0.0
    private var sz = 0.0
    private var n = 0
    private var prevEpoch = NONE
    private var prevAngle = 0.0
    private val minutes = java.util.TreeMap<Long, Minute>()

    /** One accelerometer sample (m/s^2, any consistent unit). */
    fun add(tMs: Long, x: Float, y: Float, z: Float) {
        val e = Math.floorDiv(tMs, EPOCH_MS)
        if (epoch != NONE && e < epoch) return
        if (e != epoch) {
            closeEpoch()
            epoch = e
        }
        sx += x
        sy += y
        sz += z
        n++
    }

    private fun closeEpoch() {
        if (epoch == NONE || n == 0) {
            n = 0
            return
        }
        val mx = sx / n
        val my = sy / n
        val mz = sz / n
        val angle = Math.toDegrees(Math.atan2(mz, Math.sqrt(mx * mx + my * my)))
        if (prevEpoch == epoch - 1) {
            val m = minutes.getOrPut(Math.floorDiv(epoch * EPOCH_MS, MINUTE_MS)) { Minute() }
            m.compared++
            if (Math.abs(angle - prevAngle) > ANGLE_DEG) m.restless++
        }
        prevEpoch = epoch
        prevAngle = angle
        sx = 0.0
        sy = 0.0
        sz = 0.0
        n = 0
    }

    /** Tally and drop every minute that ended at or before [endMs]; later minutes stay for the next bucket. */
    fun take(endMs: Long): Tally {
        if (epoch != NONE && (epoch + 1) * EPOCH_MS <= endMs) {
            closeEpoch()
            epoch = NONE
        }
        var count = 0
        var still = 0
        var restless = 0
        val it = minutes.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if ((e.key + 1) * MINUTE_MS > endMs) break
            val m = e.value
            count++
            if (m.compared >= MIN_EPOCHS && m.restless == 0) still++
            restless += m.restless
            it.remove()
        }
        return Tally(count, still, restless)
    }

    companion object {
        const val EPOCH_MS = 5_000L
        const val MINUTE_MS = 60_000L

        /** Arm-angle change between consecutive 5-s epochs that counts as movement (van Hees et al., 2015). */
        const val ANGLE_DEG = 5.0

        /** Compared epochs a minute needs (of 12) before it can count as still. */
        const val MIN_EPOCHS = 6

        /** Accelerometer request: about 1.6 Hz (the sensor's lowest rate), batched up to 5 minutes. */
        const val SAMPLING_US = 640_000
        const val MAX_LATENCY_US = 300_000_000

        private const val NONE = Long.MIN_VALUE
    }
}

/**
 * Minutes the watch was worn: on the wrist (low-latency off-body sensor) and not on the charger. Pure Kotlin,
 * host-tested; times are elapsedRealtime ms. With no off-body sensor the watch counts as on the wrist.
 * Nothing counts before [begin]; a state change older than the last accounted time applies from that time.
 */
class WornClock {
    private var onBody = true
    private var charging = false
    private var since = NONE
    private var wornMs = 0L

    fun begin(tMs: Long) {
        since = tMs
        wornMs = 0
    }

    fun setOnBody(tMs: Long, on: Boolean) {
        advance(tMs)
        onBody = on
    }

    fun setCharging(tMs: Long, on: Boolean) {
        advance(tMs)
        charging = on
    }

    fun worn(): Boolean = onBody && !charging

    private fun advance(tMs: Long) {
        if (since == NONE || tMs <= since) return
        if (worn()) wornMs += tMs - since
        since = tMs
    }

    /** Whole worn minutes since the last take (at most one bucket's worth), accounted up to [endMs]. */
    fun take(endMs: Long): Int {
        advance(endMs)
        val m = Math.round(wornMs / 60_000.0).toInt()
        wornMs = 0
        return Math.min(m, (Buckets.BUCKET_MS / 60_000L).toInt())
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
