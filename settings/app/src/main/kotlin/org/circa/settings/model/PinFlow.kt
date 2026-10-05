package org.circa.settings.model

/**
 * The set / change / remove PIN flow as a pure state machine (settings/README.md): the
 * full-screen keypad drives it, `data/PinLock` applies its effects to the lock settings service.
 *
 * Stages: CURRENT (verify the existing PIN: change and remove only), ENTER (new PIN, confirmed with the
 * check key once it has [MIN_LENGTH] digits), CONFIRM (the same PIN again; submits by itself at the
 * length of the first entry, like the bouncer's auto-confirm).
 */
enum class PinMode { NEW, CHANGE, REMOVE }

enum class PinStage { CURRENT, ENTER, CONFIRM }

enum class PinMessage { WRONG, MISMATCH, TOO_SHORT, THROTTLED, FAILED }

data class PinState(
    val mode: PinMode,
    val stage: PinStage,
    val typed: String = "",
    /** The first entry of the new PIN, while in CONFIRM. */
    val first: String? = null,
    /** The verified current PIN (needed as the "saved credential" when changing). */
    val current: String? = null,
    /** Length of the stored PIN when the platform knows it (CURRENT auto-submits at it); null = unknown. */
    val knownLength: Int? = null,
    val message: PinMessage? = null,
    /** Seconds the platform asked us to wait (message THROTTLED). */
    val waitSeconds: Int = 0,
    /** A platform call is running; keys are ignored. */
    val busy: Boolean = false,
)

/** What the UI has to do after an input. */
sealed interface PinEffect {
    data object None : PinEffect

    /** Check [pin] against the stored one, then call [PinFlow.verified]. */
    data class Verify(val pin: String) : PinEffect

    /** Store [new] (null = remove the PIN), proving the old one with [old] (null = none was set). */
    data class Commit(val new: String?, val old: String?) : PinEffect
}

object PinFlow {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8

    fun start(mode: PinMode, knownLength: Int? = null): PinState = PinState(
        mode = mode,
        stage = if (mode == PinMode.NEW) PinStage.ENTER else PinStage.CURRENT,
        knownLength = knownLength?.takeIf { it >= MIN_LENGTH },
    )

    /** Dots on screen: the placeholder count in CURRENT / CONFIRM when the length is known, else typed so far. */
    fun placeholderDots(s: PinState): Int = when (s.stage) {
        PinStage.CURRENT -> s.knownLength ?: 0
        PinStage.CONFIRM -> s.first?.length ?: 0
        PinStage.ENTER -> 0
    }

    fun title(s: PinState): String = when (s.stage) {
        PinStage.CURRENT -> if (s.mode == PinMode.REMOVE) "Remove PIN" else "Current PIN"
        PinStage.ENTER -> "Enter new PIN"
        PinStage.CONFIRM -> "Confirm PIN"
    }

    fun message(s: PinState): String? = when (s.message) {
        null -> null
        PinMessage.WRONG -> "Wrong PIN"
        PinMessage.MISMATCH -> "PINs didn't match"
        PinMessage.TOO_SHORT -> "Use at least $MIN_LENGTH digits"
        PinMessage.THROTTLED -> "Try again in ${s.waitSeconds} s"
        PinMessage.FAILED -> "Couldn't save PIN"
    }

    /** The check key shows when ENTER has enough digits, or CURRENT has a PIN of unknown length typed. */
    fun showSubmit(s: PinState): Boolean = !s.busy && when (s.stage) {
        PinStage.ENTER -> s.typed.length >= MIN_LENGTH
        PinStage.CURRENT -> s.knownLength == null && s.typed.isNotEmpty()
        PinStage.CONFIRM -> false
    }

    fun digit(s: PinState, d: Char): Pair<PinState, PinEffect> {
        if (s.busy || d !in '0'..'9') return s to PinEffect.None
        val limit = when (s.stage) {
            PinStage.CONFIRM -> s.first?.length ?: MAX_LENGTH
            PinStage.CURRENT -> s.knownLength ?: 16 // a PIN set elsewhere may be longer than ours
            PinStage.ENTER -> MAX_LENGTH
        }
        if (s.typed.length >= limit) return s to PinEffect.None
        val next = s.copy(typed = s.typed + d, message = null)
        val full = next.typed.length == limit
        return when {
            s.stage == PinStage.CONFIRM && full -> confirm(next)
            s.stage == PinStage.CURRENT && s.knownLength != null && full ->
                next.copy(busy = true) to PinEffect.Verify(next.typed)
            else -> next to PinEffect.None
        }
    }

    fun backspace(s: PinState): PinState =
        if (s.busy || s.typed.isEmpty()) s else s.copy(typed = s.typed.dropLast(1), message = null)

    /** The check key. */
    fun submit(s: PinState): Pair<PinState, PinEffect> {
        if (s.busy) return s to PinEffect.None
        return when (s.stage) {
            PinStage.ENTER ->
                if (s.typed.length < MIN_LENGTH) s.copy(message = PinMessage.TOO_SHORT) to PinEffect.None
                else s.copy(stage = PinStage.CONFIRM, first = s.typed, typed = "", message = null) to PinEffect.None
            PinStage.CURRENT ->
                if (s.typed.isEmpty()) s to PinEffect.None
                else s.copy(busy = true) to PinEffect.Verify(s.typed)
            PinStage.CONFIRM -> s to PinEffect.None
        }
    }

    private fun confirm(s: PinState): Pair<PinState, PinEffect> =
        if (s.typed == s.first) {
            s.copy(busy = true) to PinEffect.Commit(new = s.typed, old = s.current)
        } else {
            s.copy(stage = PinStage.ENTER, typed = "", first = null, message = PinMessage.MISMATCH) to PinEffect.None
        }

    /** The platform's answer to [PinEffect.Verify]: [waitSeconds] > 0 = throttled. */
    fun verified(s: PinState, ok: Boolean, waitSeconds: Int = 0): Pair<PinState, PinEffect> = when {
        ok && s.mode == PinMode.REMOVE ->
            s.copy(current = s.typed) to PinEffect.Commit(new = null, old = s.typed)
        ok -> s.copy(stage = PinStage.ENTER, current = s.typed, typed = "", busy = false, message = null) to PinEffect.None
        waitSeconds > 0 -> s.copy(typed = "", busy = false, message = PinMessage.THROTTLED, waitSeconds = waitSeconds) to PinEffect.None
        else -> s.copy(typed = "", busy = false, message = PinMessage.WRONG) to PinEffect.None
    }

    /** The platform's answer to [PinEffect.Commit]; success is handled by leaving the flow. */
    fun commitFailed(s: PinState): PinState = start(s.mode, s.knownLength).copy(message = PinMessage.FAILED)
}
