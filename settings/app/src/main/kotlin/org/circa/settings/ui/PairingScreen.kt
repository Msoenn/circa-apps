package org.circa.settings.ui

import org.circa.symbols.CircaSymbols
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import org.circa.settings.model.Pairing
import org.circa.settings.model.PairingMode

/** What the pairing screen shows: the request plus whether an answer is out and we wait for the bond. */
data class PairingRequest(
    val device: BluetoothDevice,
    val label: String,
    val variant: Int,
    /** The code to show (numeric comparison / display variants), formatted; null when none. */
    val code: String?,
    val waiting: Boolean = false,
)

/** Stock keypad colours (sampled from wear-ref h-screenlock-pin-01): pale lavender keys, dark digits. */
private val KEY_BG = Color(0xFFD3D8EF)
private val KEY_FG = Color(0xFF3B3F51)

@Composable
fun PairingScreen(r: PairingRequest, onAnswer: (String?) -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().testTag("pairing_${Pairing.mode(r.variant).name.lowercase()}")) {
        when {
            r.waiting -> Waiting(r)
            Pairing.mode(r.variant) == PairingMode.ENTER_PIN || Pairing.mode(r.variant) == PairingMode.ENTER_PASSKEY ->
                Keypad(r, onAnswer)
            else -> Ask(r, onAnswer, onCancel)
        }
    }
}

@Composable
private fun Waiting(r: PairingRequest) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(10.dp))
        Text("Pairing with", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            r.label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Numeric comparison, consent, and display-only codes. */
@Composable
private fun Ask(r: PairingRequest, onAnswer: (String?) -> Unit, onCancel: () -> Unit) {
    val mode = Pairing.mode(r.variant)
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(CircaSymbols.Outlined.Bluetooth, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        // A small lead line, then the device name on its own (2 lines max), so a long name
        // never pushes the question words out of view.
        val lead = when (mode) {
            PairingMode.CONFIRM_CODE, PairingMode.CONSENT -> "Pair with"
            PairingMode.SHOW_CODE -> "Type this code on"
            else -> "Can't pair with"
        }
        Text(lead, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            r.label, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("pairing_prompt"),
        )
        if (r.code != null && (mode == PairingMode.CONFIRM_CODE || mode == PairingMode.SHOW_CODE)) {
            Text(
                r.code,
                modifier = Modifier.padding(top = 2.dp).testTag("pairing_code"),
                style = MaterialTheme.typography.displaySmall.copy(letterSpacing = 2.sp),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            if (mode == PairingMode.CONFIRM_CODE) {
                Text(
                    "Check it matches", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundIconButton("pairing_no", CircaSymbols.Filled.Close, "Cancel", tonal = true) { onCancel() }
            if (mode == PairingMode.CONFIRM_CODE || mode == PairingMode.CONSENT) {
                RoundIconButton("pairing_yes", CircaSymbols.Filled.Check, "Pair") { onAnswer(null) }
            }
        }
    }
}

/**
 * PIN / passkey entry: the stock round PIN pad (3 x 4 pill keys, h-screenlock-pin-01), the typed
 * digits on top (pairing codes are not secret, and the user must compare them), delete bottom-left,
 * tick bottom-right. A passkey is always 6 digits; BACK / swipe cancels.
 */
@Composable
private fun Keypad(r: PairingRequest, onAnswer: (String?) -> Unit) {
    var entered by remember(r) { mutableStateOf("") }
    val max = Pairing.maxLength(r.variant)
    val canSubmit = Pairing.canSubmit(r.variant, entered)
    Column(
        Modifier.fillMaxSize().padding(top = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(26.dp).widthIn(max = 130.dp), contentAlignment = Alignment.Center) {
            if (entered.isEmpty()) {
                Text(
                    if (Pairing.mode(r.variant) == PairingMode.ENTER_PASSKEY) "Enter code" else "Enter PIN",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("pairing_prompt"),
                )
            } else {
                Text(
                    entered.takeLast(10), style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 2.sp),
                    maxLines = 1, modifier = Modifier.testTag("pairing_entered"),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("del", "0", "ok"))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { k ->
                        when (k) {
                            "del" -> KeyIcon("key_del", CircaSymbols.Outlined.Backspace, "Delete", MaterialTheme.colorScheme.onSurfaceVariant, entered.isNotEmpty()) {
                                entered = entered.dropLast(1)
                            }
                            "ok" -> KeyIcon("key_ok", CircaSymbols.Filled.Check, "Pair", MaterialTheme.colorScheme.primary, canSubmit) {
                                onAnswer(entered)
                            }
                            else -> DigitKey(k) {
                                if (entered.length < max) {
                                    entered += k
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val KEY_W = 46.dp
private val KEY_H = 37.dp

@Composable
private fun DigitKey(d: String, onClick: () -> Unit) {
    Box(
        Modifier.size(KEY_W, KEY_H).clip(RoundedCornerShape(50)).background(KEY_BG).clickable(onClick = onClick).testTag("key_$d"),
        contentAlignment = Alignment.Center,
    ) {
        Text(d, color = KEY_FG, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun KeyIcon(tag: String, icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(KEY_W, KEY_H).clip(RoundedCornerShape(50)).clickable(enabled = enabled, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.width(24.dp), tint = if (enabled) tint else tint.copy(alpha = 0.3f))
    }
}
