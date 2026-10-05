package org.circa.keyboard

/**
 * Whether the IME may consume a rotary-encoder (crown) event.
 *
 * The framework offers generic motion events to the current IME session before the focused app
 * (`ViewRootImpl.ImeInputStage` -> `InputMethodManager.dispatchInputEvent`), and that session stays
 * current even when the keyboard window is hidden. An IME that answers `true` for every
 * `AXIS_SCROLL` therefore swallows the crown in every app (Circa audit A09: nothing scrolls once
 * Circa Keyboard is the system IME).
 *
 * Consume only while the keyboard is actually on screen and an editor is being edited. Otherwise
 * `onGenericMotionEvent` must fall through (`super`) so the app below gets the crown.
 */
internal object RotaryGate {
    fun shouldConsume(isRotaryScroll: Boolean, inputViewShown: Boolean, inputStarted: Boolean): Boolean =
        isRotaryScroll && inputViewShown && inputStarted
}
