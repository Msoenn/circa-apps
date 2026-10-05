package org.circa.launcher.model

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Tilt-to-wake sensitivity (Circa Settings > Gestures > Tilt-to-wake). Stored as `Settings.Secure
 * circa_tilt_wake` = "0"/"1"/"2"; before that key exists the old Gestures toggle
 * `Settings.Global ambient_tilt_to_wake` = 1 means [LOW] (the strict gate is the default), anything
 * else [OFF]. Circa Settings writes both keys (the global one as 0/1) so older readers stay right.
 */
enum class TiltSensitivity(val value: Int, val id: String) {
    OFF(0, "off"),
    LOW(1, "low"),
    NORMAL(2, "normal");

    companion object {
        fun resolve(secureRaw: String?, legacyGlobalRaw: String?): TiltSensitivity {
            secureRaw?.trim()?.toIntOrNull()?.let { v -> entries.firstOrNull { it.value == v }?.let { return it } }
            return if (legacyGlobalRaw?.trim() == "1") LOW else OFF
        }
    }
}

/** A 3-axis sample: `SensorEvent.timestamp` (elapsed-realtime ns) and the three values. */
data class Sample3(val tNs: Long, val x: Float, val y: Float, val z: Float) {
    val norm: Double get() = sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z)
}

/**
 * The thresholds of one sensitivity level. Times are relative to the moment the gate starts sampling
 * (the tilt event's delivery): samples in `[settleMs, windowMs]` are judged; the first [settleMs] are
 * the tail of the raise itself and are ignored.
 */
data class TiltThresholds(
    val settleMs: Long,
    val windowMs: Long,
    val minSamples: Int,
    /** Max angle between the mean accelerometer vector and [TiltGate.LOOK]. */
    val coneDeg: Double,
    /** Max RMS of |a - mean a| (m/s²): linear acceleration = the arm still moving. */
    val maxAccelRms: Double,
    /** Max RMS |ω| (rad/s); only applied when gyro samples exist. */
    val maxGyroRms: Double,
    /** Max | |mean a| - g | (m/s²): the mean must look like gravity alone. */
    val gravityTolerance: Double,
)

/** What the gate computed from one sampling window (logged verbatim by the telemetry). */
data class TiltFeatures(
    val n: Int,
    val spanMs: Long,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val amag: Double,
    val angleDeg: Double,
    val accelRms: Double,
    /** NaN when no gyro samples were available. */
    val gyroRms: Double,
) {
    companion object {
        val EMPTY = TiltFeatures(0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
    }
}

/** Why the gate decided what it did; [OK] is the only accepting reason. */
enum class GateReason(val id: String) {
    OK("ok"),
    FEW("few"),
    GRAVITY("gravity"),
    FACE("face"),
    MOTION("motion"),
    ROTATION("rotation"),
}

data class TiltVerdict(val accept: Boolean, val reason: GateReason, val features: TiltFeatures)

/**
 * Our own check on top of the sensor hub's wrist-tilt gesture (type 26), whose firmware fires on arm
 * swings too (launcher/README.md). On each hub event the launcher samples the
 * wake-up accelerometer (and gyroscope) for [TiltThresholds.windowMs] and accepts the raise only if,
 * after the settle time, the watch is
 *
 *  1. **facing the user**: the mean accelerometer vector (what the accelerometer reads at rest is the
 *     "up" direction: face-up flat reads (0, 0, +9.8)) lies within [TiltThresholds.coneDeg] of [LOOK];
 *  2. **held**: little linear acceleration ([TiltThresholds.maxAccelRms]) and little rotation
 *     ([TiltThresholds.maxGyroRms]) - a walking swing passes through the pose but never holds it;
 *  3. **plausible**: enough samples, and the mean's magnitude close to g.
 *
 * Pure (no Android), unit tested in `TiltGateTest`; thresholds are constants here so the on-watch
 * telemetry can be used to retune them.
 */
object TiltGate {
    const val G = 9.80665

    /**
     * The "looking at the watch" direction of the accelerometer, Android device axes (+X toward the
     * crown / 3 o'clock, +Y toward 12 o'clock, +Z out of the screen), for a watch on the **left wrist,
     * crown toward the hand** (the hub's own `LeftWristCrownToHand`). Looking at it, the forearm lies
     * across the chest and the face is turned up and toward the eyes, i.e. rotated about X so that
     * 12 o'clock is the higher edge: up = cos(θ)·Z + sin(θ)·Y with θ ≈ 20°.
     */
    val LOOK: DoubleArray = unit(doubleArrayOf(0.0, 0.34, 0.94))

    /** The strict default: a clear "face toward me, held" pose. */
    val LOW = TiltThresholds(
        settleMs = 100,
        windowMs = 450,
        minSamples = 6,
        coneDeg = 35.0,
        maxAccelRms = 0.6,
        maxGyroRms = 0.6,
        gravityTolerance = 1.5,
    )

    /** Looser, still gated: a wider cone and more motion allowed, and a shorter wait. */
    val NORMAL = TiltThresholds(
        settleMs = 50,
        windowMs = 300,
        minSamples = 4,
        coneDeg = 50.0,
        maxAccelRms = 1.2,
        maxGyroRms = 1.2,
        gravityTolerance = 2.5,
    )

    fun thresholds(level: TiltSensitivity): TiltThresholds? = when (level) {
        TiltSensitivity.OFF -> null
        TiltSensitivity.LOW -> LOW
        TiltSensitivity.NORMAL -> NORMAL
    }

    /**
     * Features of the samples inside the judged window. [startNs] is when sampling started (the same
     * elapsed-realtime clock as `SensorEvent.timestamp`).
     */
    fun features(accel: List<Sample3>, gyro: List<Sample3>, startNs: Long, t: TiltThresholds): TiltFeatures {
        val from = startNs + t.settleMs * 1_000_000L
        val to = startNs + t.windowMs * 1_000_000L
        val a = accel.filter { it.tNs in from..to }
        if (a.isEmpty()) return TiltFeatures.EMPTY
        val mx = a.sumOf { it.x.toDouble() } / a.size
        val my = a.sumOf { it.y.toDouble() } / a.size
        val mz = a.sumOf { it.z.toDouble() } / a.size
        val mag = sqrt(mx * mx + my * my + mz * mz)
        val angle = angleDeg(doubleArrayOf(mx, my, mz), LOOK)
        val accelRms = sqrt(
            a.sumOf {
                val dx = it.x - mx
                val dy = it.y - my
                val dz = it.z - mz
                dx * dx + dy * dy + dz * dz
            } / a.size,
        )
        val g = gyro.filter { it.tNs in from..to }
        val gyroRms = if (g.isEmpty()) Double.NaN else sqrt(g.sumOf { it.norm * it.norm } / g.size)
        val span = (a.last().tNs - a.first().tNs) / 1_000_000L
        return TiltFeatures(a.size, span, mx, my, mz, mag, angle, accelRms, gyroRms)
    }

    /** The decision for already-computed [f]; the checks run in a fixed order, the first failure wins. */
    fun decide(f: TiltFeatures, t: TiltThresholds): TiltVerdict {
        val reason = when {
            f.n < t.minSamples -> GateReason.FEW
            kotlin.math.abs(f.amag - G) > t.gravityTolerance -> GateReason.GRAVITY
            f.angleDeg > t.coneDeg -> GateReason.FACE
            f.accelRms > t.maxAccelRms -> GateReason.MOTION
            !f.gyroRms.isNaN() && f.gyroRms > t.maxGyroRms -> GateReason.ROTATION
            else -> GateReason.OK
        }
        return TiltVerdict(reason == GateReason.OK, reason, f)
    }

    fun evaluate(accel: List<Sample3>, gyro: List<Sample3>, startNs: Long, t: TiltThresholds): TiltVerdict =
        decide(features(accel, gyro, startNs, t), t)

    /** True once a sample at or beyond the end of the window has arrived: time to decide. */
    fun windowComplete(latestNs: Long, startNs: Long, t: TiltThresholds): Boolean =
        latestNs - startNs >= t.windowMs * 1_000_000L

    fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (na == 0.0 || nb == 0.0) return 180.0
        val c = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }

    private fun unit(v: DoubleArray): DoubleArray {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }
}

/**
 * Wrist-down while a tilt glance is on screen: the arm was lowered (the hand points down: the
 * accelerometer's X - toward the hand on a left wrist, crown to hand - reads well below zero) or the
 * face was turned away/down (Z negative). Sustained for [holdMs] so one jolt does not count, and
 * ignored for [graceMs] after the wake (the raise's own end can overshoot).
 */
class WristDownDetector(
    private val graceMs: Long = GRACE_MS,
    private val holdMs: Long = HOLD_MS,
) {
    private var downSinceNs: Long? = null
    private var startNs: Long? = null

    /** Feed one accelerometer sample; returns true once wrist-down has been held long enough. */
    fun onSample(s: Sample3): Boolean {
        val start = startNs ?: s.tNs.also { startNs = it }
        if (s.tNs - start < graceMs * 1_000_000L) {
            downSinceNs = null
            return false
        }
        if (!isDownPose(s)) {
            downSinceNs = null
            return false
        }
        val since = downSinceNs ?: s.tNs.also { downSinceNs = it }
        return s.tNs - since >= holdMs * 1_000_000L
    }

    companion object {
        const val GRACE_MS = 400L
        const val HOLD_MS = 300L

        /** Hand below the elbow by ~30° or more (x ≤ -g·sin 30°). */
        const val HAND_DOWN_X = -4.9

        /** Face turned past vertical, away from the user / down. */
        const val FACE_DOWN_Z = -3.0

        fun isDownPose(s: Sample3): Boolean = s.x <= HAND_DOWN_X || s.z <= FACE_DOWN_Z
    }
}

/**
 * The glance that follows an accepted tilt: stock Wear returns to ambient a few seconds after a
 * gesture wake nobody touched, instead of running the full screen timeout.
 */
object GlancePolicy {
    /** Screen-on time of an untouched tilt wake before the launcher sends it back to ambient. */
    const val QUICK_TIMEOUT_MS = 5_500L

    /** The telemetry's "did the user interact" horizon. */
    const val INTERACT_HORIZON_MS = 5_000L

    /** A wake whose screen never turned interactive within this is logged as `nowake`. */
    const val NO_WAKE_AFTER_MS = 3_000L

    /**
     * Whether the quick timeout may send the screen back to ambient: only while no interaction was
     * seen and the launcher (where interactions are visible to us) has been the focused window
     * throughout. Another app or a system window on top means touches we cannot see: leave it to the
     * normal system timeout.
     */
    fun mayQuickSleep(interacted: Boolean, launcherFocused: Boolean, leftLauncher: Boolean): Boolean =
        !interacted && launcherFocused && !leftLauncher
}

/** How a tilt glance ended (telemetry `end` column). */
enum class GlanceEnd(val id: String) {
    QUICK("quick"),
    WRIST_DOWN("wristdown"),
    USER("user"),
    OTHER("other"),
    NO_WAKE("nowake"),
}

/**
 * One telemetry line (launcher/README.md); [HEADER] lists the columns.
 * Numbers are written with '.' decimals regardless of locale; blanks are empty fields.
 */
object TiltLog {
    const val HEADER =
        "wall_ms,uptime_ms,source,level,decision,reason,n,span_ms,ax,ay,az,amag,angle_deg,accel_rms," +
            "gyro_rms,latency_ms,interact_ms,interacted5,screen_on_ms,end"

    /** Keep this many data lines; trim back to it once the file has grown [TRIM_SLACK] past it. */
    const val MAX_LINES = 2_000
    const val TRIM_SLACK = 200

    fun line(
        wallMs: Long,
        uptimeMs: Long,
        source: String,
        level: TiltSensitivity,
        decision: String,
        reason: String,
        f: TiltFeatures?,
        latencyMs: Long?,
        interactMs: Long?,
        screenOnMs: Long?,
        end: GlanceEnd?,
    ): String {
        val wake = decision == "wake"
        val interacted5 = when {
            !wake -> ""
            interactMs != null && interactMs <= GlancePolicy.INTERACT_HORIZON_MS -> "1"
            else -> "0"
        }
        val feats = if (f == null || f.n == 0) {
            List(9) { "" }
        } else {
            listOf(
                f.n.toString(), f.spanMs.toString(), num(f.ax), num(f.ay), num(f.az), num(f.amag),
                num(f.angleDeg, 1), num(f.accelRms), num(f.gyroRms),
            )
        }
        return (
            listOf(wallMs.toString(), uptimeMs.toString(), source, level.id, decision, reason) + feats +
                listOf(
                    latencyMs?.toString() ?: "", interactMs?.toString() ?: "", interacted5,
                    screenOnMs?.toString() ?: "", end?.id ?: "",
                )
            ).joinToString(",")
    }

    /** The lines to keep after appending: header + the newest [MAX_LINES] data lines, or null = no trim. */
    fun trim(lines: List<String>): List<String>? {
        val data = lines.filter { it.isNotBlank() && it != HEADER }
        if (data.size <= MAX_LINES + TRIM_SLACK) return null
        return listOf(HEADER) + data.takeLast(MAX_LINES)
    }

    private fun num(v: Double, decimals: Int = 3): String {
        if (v.isNaN() || v.isInfinite()) return ""
        val scale = Math.pow(10.0, decimals.toDouble())
        val r = Math.round(v * scale) / scale
        // Double.toString is locale-independent ('.' decimals).
        return r.toString()
    }
}
