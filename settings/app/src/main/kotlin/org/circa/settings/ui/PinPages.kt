package org.circa.settings.ui

import org.circa.symbols.CircaSymbols
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.circa.settings.SettingsController
import org.circa.settings.data.PinLock
import org.circa.settings.model.PinEffect
import org.circa.settings.model.PinFlow
import org.circa.settings.model.PinMode
import org.circa.settings.model.PinStage
import org.circa.settings.model.PinState
import org.circa.settings.model.SettingsPage

// The keypad is the lock-screen bouncer's (settings/README.md): lavender keys,
// dark bold digits, a bare outlined delete icon, filled grey dots, no form, no system keyboard, laid out
// in the 200 dp logical width this app is designed against (bouncer pixels / 1.92 on a 384 px display).
private val KEY_FILL = Color(0xFFCDD4EB)
private val KEY_TEXT = Color(0xFF383F51)
private val DOT_FILLED = Color(0xFFE3E3E8)
private val DOT_EMPTY = Color(0xFF6E6F78)
private val ERROR = Color(0xFFF2B8B5)

private val KEY_W = 45.dp
private val KEY_H = 38.dp
private val KEY_GAP_X = 4.7.dp
private val KEY_GAP_Y = 1.6.dp
private val PAD_TOP = 42.dp
private val KEY_SHAPE = RoundedCornerShape(17.dp)

private fun modeOf(page: SettingsPage) = when (page) {
    SettingsPage.PIN_CHANGE -> PinMode.CHANGE
    SettingsPage.PIN_REMOVE -> PinMode.REMOVE
    else -> PinMode.NEW
}

/** Set / change / remove PIN: one full-screen keypad, driven by [PinFlow]. */
@Composable
internal fun PinPage(c: SettingsController) {
    val page = c.settingsPage.value
    val context = LocalContext.current.applicationContext
    val pinLock = remember { PinLock(context) }
    val scope = rememberCoroutineScope()
    var state: PinState by remember(page) { mutableStateOf(PinFlow.start(modeOf(page))) }

    // The stored length lets the current-PIN stage submit by itself at the right digit (like the bouncer).
    LaunchedEffect(page) {
        if (state.stage == PinStage.CURRENT) {
            val len = withContext(Dispatchers.IO) { pinLock.storedLength() }
            if (len != null && len >= PinFlow.MIN_LENGTH) state = state.copy(knownLength = len)
        }
    }

    fun handle(result: Pair<PinState, PinEffect>) {
        state = result.first
        when (val e = result.second) {
            PinEffect.None -> Unit
            is PinEffect.Verify -> scope.launch {
                val check = withContext(Dispatchers.IO) { pinLock.check(e.pin) }
                val (next, effect) = when (check) {
                    PinLock.Check.Ok -> PinFlow.verified(state, ok = true)
                    is PinLock.Check.Throttled -> PinFlow.verified(state, ok = false, waitSeconds = check.seconds)
                    else -> PinFlow.verified(state, ok = false)
                }
                afterVerify(next, effect, pinLock, c, scope) { state = it }
            }
            is PinEffect.Commit -> scope.launch { commit(e, state, pinLock, c) { state = it } }
        }
    }

    // No clock: the keypad owns the whole circle, like the bouncer.
    PageFrame(c, page, showClock = false) {
        PinPad(
            state = state,
            tag = settingsPageTag(page),
            onDigit = { d -> handle(PinFlow.digit(state, d)) },
            onDelete = { state = PinFlow.backspace(state) },
            onSubmit = { handle(PinFlow.submit(state)) },
        )
    }
}

private fun afterVerify(
    next: PinState,
    effect: PinEffect,
    pinLock: PinLock,
    c: SettingsController,
    scope: CoroutineScope,
    setState: (PinState) -> Unit,
) {
    setState(next)
    if (effect is PinEffect.Commit) scope.launch { commit(effect, next, pinLock, c, setState) }
}

private suspend fun commit(
    e: PinEffect.Commit,
    state: PinState,
    pinLock: PinLock,
    c: SettingsController,
    setState: (PinState) -> Unit,
) {
    val ok = withContext(Dispatchers.IO) { pinLock.set(e.new, e.old) }
    if (ok) c.pinChanged() else setState(PinFlow.commitFailed(state))
}

@Composable
private fun PinPad(
    state: PinState,
    tag: String,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onSubmit: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag(tag)) {
        // Caption: one or two short words, centred in the narrow top of the circle; an error replaces it.
        val message = PinFlow.message(state)
        Text(
            text = message ?: PinFlow.title(state),
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 8.dp).testTag("pin_caption"),
            color = if (message != null) ERROR else DOT_FILLED.copy(alpha = 0.8f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Dots(
            typed = state.typed.length,
            placeholders = maxOf(PinFlow.placeholderDots(state), if (state.stage == PinStage.ENTER) 4 else 0),
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 28.dp),
        )
        Column(
            modifier = Modifier.align(Alignment.TopCenter).offset(y = PAD_TOP),
            verticalArrangement = Arrangement.spacedBy(KEY_GAP_Y),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            listOf("123", "456", "789").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP_X)) {
                    row.forEach { d -> DigitKey(d, onDigit) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP_X)) {
                // Bare delete icon bottom left, 0 in the middle, bare check bottom right once there is something to submit
                // (a filled key in that corner would stick out of the circle).
                Box(
                    Modifier.size(KEY_W, KEY_H).testTag("pin_delete").clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CircaSymbols.Outlined.Backspace, contentDescription = "Delete", modifier = Modifier.size(22.dp), tint = KEY_FILL)
                }
                DigitKey('0', onDigit)
                if (PinFlow.showSubmit(state)) {
                    Box(
                        Modifier.size(KEY_W, KEY_H).testTag("pin_submit").clickable(onClick = onSubmit),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(CircaSymbols.Filled.Check, contentDescription = "Next", modifier = Modifier.size(28.dp), tint = KEY_FILL)
                    }
                } else {
                    Box(Modifier.size(KEY_W, KEY_H))
                }
            }
        }
    }
}

@Composable
private fun DigitKey(d: Char, onDigit: (Char) -> Unit) {
    Box(
        Modifier
            .size(KEY_W, KEY_H)
            .clip(KEY_SHAPE)
            .background(KEY_FILL)
            .testTag("pin_key_$d")
            .semantics { contentDescription = d.toString() }
            .clickable { onDigit(d) },
        contentAlignment = Alignment.Center,
    ) {
        Text(d.toString(), color = KEY_TEXT, fontSize = 25.sp, fontWeight = FontWeight.Bold)
    }
}

/** Filled dots: bright for typed digits, dim placeholders for the digits still to come. */
@Composable
private fun Dots(typed: Int, placeholders: Int, modifier: Modifier) {
    val count = maxOf(typed, placeholders)
    Row(modifier.height(6.dp).testTag("pin_dots"), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(count) { i ->
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (i < typed) DOT_FILLED else DOT_EMPTY))
        }
    }
}
