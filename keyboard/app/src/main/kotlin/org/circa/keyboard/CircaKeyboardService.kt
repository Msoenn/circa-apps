package org.circa.keyboard

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Circa Keyboard: a full-screen round IME (keyboard/README.md). The service owns all state
 * (page, shift, suggestions) and talks to the editor; [KeyboardView] only draws and reports touches.
 * Every InputConnection call tolerates a null connection (editor gone) and a failing one.
 */
class CircaKeyboardService : InputMethodService(), KeyboardView.Listener {

    private var view: KeyboardView? = null
    private var info: EditorInfo? = null
    private var profile = EditorProfile.from(InputType.TYPE_CLASS_TEXT, 0)
    private var page = Page.LETTERS
    private var shift = Shift.OFF
    private var revealed = false
    private var before = ""
    private var after = ""
    private var suggestions: List<String> = emptyList()
    private var spellWord: String? = null
    private var spellExtra: List<String> = emptyList()

    /** The last thing committed was a suggestion followed by an automatic space. */
    private var autoSpaced = false
    private var lastShiftTap = 0L
    private var lastSpaceTap = 0L
    private var rotaryAcc = 0f

    @Volatile private var suggester: Suggester? = null
    private var learnedDirty = false
    private var spell: SpellCheckerSession? = null

    override fun onCreate() {
        super.onCreate()
        thread(name = "circa-kb-dict") {
            val t0 = SystemClock.elapsedRealtime()
            val words = runCatching {
                resources.openRawResource(R.raw.words_en).bufferedReader().useLines { Suggester.parse(it) }
            }.getOrElse { emptyList() }
            val sg = Suggester(words)
            loadLearned(sg)
            suggester = sg
            Log.i(TAG, "dictionary: ${sg.size} words in ${SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    override fun onDestroy() {
        saveLearned()
        runCatching { spell?.close() }
        spell = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        coverWholePanel()
        return KeyboardView(this, this).also { view = it }
    }

    /**
     * The IME window by default fits inside the status bar inset (frame starting ~28 px down). The keyboard's
     * geometry is the round panel itself, so the window must start at the panel's top edge.
     */
    private fun coverWholePanel() {
        val w = window?.window ?: return
        val a = w.attributes
        if (a.fitInsetsTypes != 0 || a.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
            a.fitInsetsTypes = 0
            a.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            w.attributes = a
        }
    }

    /** We draw our own full-screen view; the framework's extract-text fullscreen mode is never used. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /** Show even if the system thinks a hardware keyboard is present (the emulator may say so). */
    override fun onEvaluateInputViewShown(): Boolean = true

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // The keyboard covers the whole panel: all of it is touchable, and the app behind is fully covered.
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
        outInsets.contentTopInsets = 0
        outInsets.visibleTopInsets = 0
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        coverWholePanel()
        info = editorInfo
        profile = EditorProfile.from(editorInfo.inputType, editorInfo.imeOptions, editorInfo.actionLabel)
        page = LayoutEngine.firstPage(profile)
        shift = Shift.OFF
        revealed = false
        autoSpaced = false
        spellWord = null
        spellExtra = emptyList()
        rotaryAcc = 0f
        view?.reset()
        Log.i(TAG, "startInputView ${editorInfo.packageName} inputType=0x${Integer.toHexString(editorInfo.inputType)} " +
            "imeOptions=0x${Integer.toHexString(editorInfo.imeOptions)} -> ${profile.kind} enter=${profile.enter}")
        readEditor()
        autoShift()
        refresh()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        view?.reset()
        saveLearned()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        readEditor()
        refresh()
    }

    // ------------------------------------------------------------------ key handling
    override fun onKey(key: Key, text: String) {
        val ic = currentInputConnection
        when (key.type) {
            KeyType.CHAR -> typeChar(ic, text)
            KeyType.SPACE -> typeSpace(ic)
            KeyType.DELETE -> delete(ic)
            KeyType.ENTER -> enter(ic)
            KeyType.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shift = when {
                    shift == Shift.LOCK -> Shift.OFF
                    shift == Shift.ONCE && now - lastShiftTap < DOUBLE_TAP_MS -> Shift.LOCK
                    shift == Shift.ONCE -> Shift.OFF
                    else -> Shift.ONCE
                }
                lastShiftTap = now
            }
            KeyType.TO_SYMBOLS -> page = Page.SYMBOLS
            KeyType.TO_MORE_SYMBOLS -> page = Page.SYMBOLS2
            KeyType.TO_LETTERS -> { page = Page.LETTERS; autoShift() }
            KeyType.HIDE -> { requestHideSelf(0); return }
        }
        if (key.type != KeyType.SPACE) lastSpaceTap = 0L
        refresh()
    }

    private fun typeChar(ic: InputConnection?, text: String) {
        if (ic == null || text.isEmpty()) return
        val punct = text.length == 1 && text[0] in ".,?!:;)"
        if (punct) learnCurrentWord()
        if (autoSpaced && punct && before.endsWith(" ")) {
            // "word␣" + "." -> "word. ": the automatic space moves behind the punctuation.
            ic.deleteSurroundingText(1, 0)
            ic.commitText("$text ", 1)
        } else {
            ic.commitText(text, 1)
        }
        autoSpaced = false
        if (shift == Shift.ONCE) shift = Shift.OFF
        readEditor()
        if (page == Page.LETTERS) autoShift()
        // A symbol page drops back to letters after an apostrophe (as stock keyboards do).
        if (page != Page.LETTERS && text == "'") page = Page.LETTERS
    }

    private fun typeSpace(ic: InputConnection?) {
        if (ic == null) return
        val now = SystemClock.uptimeMillis()
        learnCurrentWord()
        if (profile.kind == InputKind.TEXT && now - lastSpaceTap < DOUBLE_TAP_MS && before.length >= 2 &&
            before.endsWith(" ") && before[before.length - 2].isLetterOrDigit()
        ) {
            // Double space: ". " (and the next letter is capitalised by autoShift).
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            lastSpaceTap = 0L
        } else {
            ic.commitText(" ", 1)
            lastSpaceTap = now
        }
        autoSpaced = false
        // A space ends a run of symbols: back to letters (as stock keyboards do).
        if (page == Page.SYMBOLS || page == Page.SYMBOLS2) page = Page.LETTERS
        readEditor()
        autoShift()
    }

    private fun delete(ic: InputConnection?) {
        autoSpaced = false
        if (ic == null) return
        val selected = runCatching { ic.getSelectedText(0) }.getOrNull()
        val ok = when {
            !selected.isNullOrEmpty() -> ic.commitText("", 1)
            info?.inputType == InputType.TYPE_NULL -> false
            before.isEmpty() -> false // nothing to delete (or an editor that hides its text): send a key
            else -> ic.deleteSurroundingTextInCodePoints(1, 0)
        }
        if (!ok) sendKey(ic, KeyEvent.KEYCODE_DEL)
        readEditor()
        autoShift()
    }

    override fun onDeleteRepeat() {
        delete(currentInputConnection)
        refresh()
    }

    private fun enter(ic: InputConnection?) {
        if (ic == null) return
        learnCurrentWord()
        autoSpaced = false
        val id = profile.actionId
        when {
            id != null -> ic.performEditorAction(id)
            profile.enter == EnterAction.NEWLINE -> ic.commitText("\n", 1)
            else -> sendKey(ic, KeyEvent.KEYCODE_ENTER)
        }
        readEditor()
        autoShift()
    }

    private fun sendKey(ic: InputConnection, code: Int) {
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, 0, KeyEvent.KEYCODE_UNKNOWN, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE))
        ic.sendKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, KeyEvent.KEYCODE_UNKNOWN, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE))
    }

    override fun onSuggestion(index: Int) {
        val ic = currentInputConnection ?: return
        val word = suggestions.getOrNull(index) ?: return
        if (chipsFor(profile, page) != null) {
            // Email / URL shortcut chip: inserted as typed, no word replacement, no space.
            ic.commitText(word, 1)
            autoSpaced = false
            readEditor()
            refresh()
            return
        }
        val current = Suggester.currentWord(before)
        ic.beginBatchEdit()
        if (current.isNotEmpty()) ic.deleteSurroundingText(current.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        if (profile.learn) suggester?.learn(word)?.also { learnedDirty = true }
        autoSpaced = true
        if (shift == Shift.ONCE) shift = Shift.OFF
        readEditor()
        autoShift()
        refresh()
    }

    override fun onToggleReveal() {
        revealed = !revealed
        refresh()
    }

    override fun onSwipeHide() {
        requestHideSelf(0)
    }

    // ------------------------------------------------------------------ crown
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val rotaryScroll = event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        // The IME session is offered generic motion events even while the keyboard window is hidden;
        // only consume the crown while the keyboard is on screen and editing, so a hidden keyboard
        // never steals scrolling from the focused app (audit A09).
        if (!RotaryGate.shouldConsume(rotaryScroll, isInputViewShown, currentInputStarted)) {
            return super.onGenericMotionEvent(event)
        }
        // Crown: move the caret one character per detent. Clockwise is a negative AXIS_SCROLL (the Wear
        // convention: clockwise scrolls a list down), and moves the caret right.
        rotaryAcc += -event.getAxisValue(MotionEvent.AXIS_SCROLL)
        val steps = rotaryAcc.toInt()
        if (steps != 0) {
            rotaryAcc -= steps
            moveCursor(steps)
        }
        return true
    }

    private fun moveCursor(steps: Int) {
        val ic = currentInputConnection ?: return
        val et = runCatching { ic.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()
        if (et != null && et.text != null && et.selectionStart >= 0) {
            val len = et.text.length
            val pos = (et.startOffset + et.selectionEnd + steps).coerceIn(et.startOffset, et.startOffset + len)
            ic.setSelection(pos, pos)
        } else {
            val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            repeat(kotlin.math.abs(steps)) { sendKey(ic, code) }
        }
        autoSpaced = false
    }

    // ------------------------------------------------------------------ state
    private fun readEditor() {
        val ic = currentInputConnection
        before = runCatching { ic?.getTextBeforeCursor(PREVIEW_CHARS, 0)?.toString() }.getOrNull() ?: ""
        after = runCatching { ic?.getTextAfterCursor(PREVIEW_CHARS, 0)?.toString() }.getOrNull() ?: ""
    }

    /** Sentence-start capitals from the editor's own caps mode; a locked shift is left alone. */
    private fun autoShift() {
        if (shift == Shift.LOCK || page != Page.LETTERS) return
        val ei = info ?: return
        val ic = currentInputConnection ?: return
        val caps = profile.kind == InputKind.TEXT &&
            runCatching { ic.getCursorCapsMode(ei.inputType) }.getOrDefault(0) != 0
        shift = if (caps) Shift.ONCE else Shift.OFF
    }

    private fun learnCurrentWord() {
        if (!profile.learn) return
        val w = Suggester.currentWord(before)
        if (w.length >= 2) {
            suggester?.learn(w)
            learnedDirty = true
        }
    }

    private fun refresh() {
        val v = view ?: return
        val sg = suggester
        val word = Suggester.currentWord(before)
        val chips = chipsFor(profile, page)
        suggestions = if (chips != null) chips
        else if (!profile.suggestions || sg == null || page != Page.LETTERS || word.isEmpty()) emptyList()
        else {
            val local = sg.suggest(word, 3)
            if (local.size < 3 && word.length >= 3 && !sg.isKnown(word)) requestSpell(word)
            val extra = if (spellWord.equals(word, ignoreCase = true)) spellExtra else emptyList()
            (local + extra.map { Suggester.matchCase(word, it) }).distinctBy { it.lowercase() }.take(3)
        }
        v.update(
            KeyboardView.State(
                profile = profile,
                page = page,
                shift = shift,
                before = before,
                after = after,
                hint = info?.hintText?.toString()?.takeIf { it.isNotBlank() },
                revealed = revealed,
                suggestions = suggestions,
                chips = chips != null,
                accent = accent(),
            )
        )
    }

    /** Fixed shortcut chips shown in the strip for editors that get no word suggestions. */
    private fun chipsFor(p: EditorProfile, pg: Page): List<String>? = if (pg != Page.LETTERS) null else when (p.kind) {
        InputKind.EMAIL -> EMAIL_CHIPS
        InputKind.URI -> URI_CHIPS
        else -> null
    }

    private fun accent(): Int = runCatching {
        Settings.Secure.getString(contentResolver, ACCENT_KEY)?.trim()?.toIntOrNull()
    }.getOrNull() ?: DEFAULT_ACCENT

    // ------------------------------------------------------------------ spell checker (optional)
    private fun requestSpell(word: String) {
        if (spellWord.equals(word, ignoreCase = true)) return
        spellWord = word
        spellExtra = emptyList()
        val session = spell ?: runCatching {
            val tsm = getSystemService(TextServicesManager::class.java) ?: return
            @Suppress("DEPRECATION")
            tsm.newSpellCheckerSession(null, Locale.US, spellListener, true)
        }.getOrNull()?.also { spell = it } ?: return
        runCatching { session.getSentenceSuggestions(arrayOf(TextInfo(word)), 3) }
    }

    private val spellListener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) = Unit
        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
            val out = ArrayList<String>()
            results?.forEach { s ->
                for (i in 0 until s.suggestionsCount) {
                    val si = s.getSuggestionsInfoAt(i) ?: continue
                    for (j in 0 until si.suggestionsCount) si.getSuggestionAt(j)?.let { out += it }
                }
            }
            spellExtra = out
            if (out.isNotEmpty()) refresh()
        }
    }

    // ------------------------------------------------------------------ learned words (credential storage)
    private fun learnedFile(): File? {
        val um = getSystemService(UserManager::class.java)
        if (um != null && !um.isUserUnlocked) return null // before first unlock: learn nothing, read nothing
        return File(filesDir, "learned.tsv")
    }

    private fun loadLearned(sg: Suggester) {
        val f = runCatching { learnedFile() }.getOrNull() ?: return
        if (!f.exists()) return
        runCatching {
            val m = HashMap<String, Int>()
            f.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) line.substring(tab + 1).toIntOrNull()?.let { m[line.substring(0, tab)] = it }
            }
            sg.restoreLearned(m)
        }
    }

    private fun saveLearned() {
        if (!learnedDirty) return
        val sg = suggester ?: return
        val f = runCatching { learnedFile() }.getOrNull() ?: return
        val snapshot = sg.learnedSnapshot()
        learnedDirty = false
        thread(name = "circa-kb-save") {
            runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.bufferedWriter().use { w -> snapshot.forEach { (k, v) -> w.write("$k\t$v\n") } }
                tmp.renameTo(f)
            }
        }
    }

    companion object {
        private const val TAG = "CircaKeyboard"
        const val ACCENT_KEY = "circa_accent_color"
        const val DEFAULT_ACCENT = 0xFF8AB4F8.toInt()
        private const val PREVIEW_CHARS = 200
        private const val DOUBLE_TAP_MS = 450L
        val EMAIL_CHIPS = listOf(".com", ".org", ".net")
        val URI_CHIPS = listOf("https://", "www.", ".com")
    }
}
