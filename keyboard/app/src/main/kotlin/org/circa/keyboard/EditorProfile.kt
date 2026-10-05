package org.circa.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Which family of keyboard an editor gets. */
enum class InputKind { TEXT, PASSWORD, EMAIL, URI, NUMBER, PHONE, DATETIME }

/** What the enter key does. */
enum class EnterAction { NEWLINE, GO, NEXT, PREVIOUS, DONE, SEND, SEARCH, NONE }

/**
 * Everything the keyboard needs to know about the focused editor, derived from [EditorInfo.inputType]
 * and [EditorInfo.imeOptions] alone (pure; unit-tested in EditorProfileTest).
 */
data class EditorProfile(
    val kind: InputKind,
    val enter: EnterAction,
    /** Label for the enter key ("Go", "Next", ...); null = draw the return arrow. */
    val enterLabel: String?,
    /** imeOptions action id to pass to performEditorAction, or null when enter sends a newline / ENTER key. */
    val actionId: Int?,
    val suggestions: Boolean,
    /** Content is a password: the preview shows dots (with a "show" toggle); no suggestions, no learning. */
    val secret: Boolean,
    /** May typed words be remembered? Never for passwords or IME_FLAG_NO_PERSONALIZED_LEARNING. */
    val learn: Boolean,
    val multiline: Boolean,
    val signed: Boolean = false,
    val decimal: Boolean = false,
) {
    val numeric: Boolean get() = kind == InputKind.NUMBER || kind == InputKind.PHONE || kind == InputKind.DATETIME

    companion object {
        fun from(inputType: Int, imeOptions: Int, actionLabel: CharSequence? = null): EditorProfile {
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val flags = inputType and InputType.TYPE_MASK_FLAGS

            val kind = when (cls) {
                InputType.TYPE_CLASS_NUMBER -> InputKind.NUMBER
                InputType.TYPE_CLASS_PHONE -> InputKind.PHONE
                InputType.TYPE_CLASS_DATETIME -> InputKind.DATETIME
                InputType.TYPE_CLASS_TEXT -> when (variation) {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> InputKind.PASSWORD
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> InputKind.EMAIL
                    InputType.TYPE_TEXT_VARIATION_URI -> InputKind.URI
                    else -> InputKind.TEXT
                }
                else -> InputKind.TEXT // TYPE_NULL: raw key events; the text keyboard still works.
            }
            val secret = isSecret(inputType)
            val multiline = cls == InputType.TYPE_CLASS_TEXT && (flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

            val noPersonalized = (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
            val noSuggestFlag = cls == InputType.TYPE_CLASS_TEXT &&
                (flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
            val suggestions = kind == InputKind.TEXT && cls == InputType.TYPE_CLASS_TEXT && !noSuggestFlag
            val learn = suggestions && !noPersonalized

            val action = imeOptions and EditorInfo.IME_MASK_ACTION
            val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            val enter = when {
                multiline && (noEnterAction || action == EditorInfo.IME_ACTION_UNSPECIFIED ||
                    action == EditorInfo.IME_ACTION_NONE) -> EnterAction.NEWLINE
                noEnterAction -> EnterAction.NEWLINE
                action == EditorInfo.IME_ACTION_GO -> EnterAction.GO
                action == EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
                action == EditorInfo.IME_ACTION_PREVIOUS -> EnterAction.PREVIOUS
                action == EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
                action == EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
                action == EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
                action == EditorInfo.IME_ACTION_NONE -> EnterAction.NONE
                // Unspecified on a single-line editor: an ENTER key, which TextView treats as "done".
                else -> EnterAction.NONE
            }
            val label = actionLabel?.toString()?.takeIf { it.isNotBlank() && enter != EnterAction.NEWLINE }
                ?: when (enter) {
                    EnterAction.GO -> "Go"
                    EnterAction.NEXT -> "Next"
                    EnterAction.PREVIOUS -> "Prev"
                    EnterAction.DONE -> "Done"
                    EnterAction.SEND -> "Send"
                    EnterAction.SEARCH -> "Search"
                    EnterAction.NEWLINE, EnterAction.NONE -> null
                }
            val actionId = when (enter) {
                EnterAction.NEWLINE, EnterAction.NONE -> null
                else -> action
            }
            return EditorProfile(
                kind = kind,
                enter = enter,
                enterLabel = label,
                actionId = actionId,
                suggestions = suggestions,
                secret = secret,
                learn = learn,
                multiline = multiline,
                signed = cls == InputType.TYPE_CLASS_NUMBER && (flags and InputType.TYPE_NUMBER_FLAG_SIGNED) != 0,
                decimal = cls == InputType.TYPE_CLASS_NUMBER && (flags and InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0,
            )
        }

        /** Is the editor content a hidden password (dots in the preview)? A visible password is not. */
        fun isSecret(inputType: Int): Boolean {
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val v = inputType and InputType.TYPE_MASK_VARIATION
            return (cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        }
    }
}
