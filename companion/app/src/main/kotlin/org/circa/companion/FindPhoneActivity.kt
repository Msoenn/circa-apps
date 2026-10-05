package org.circa.companion

import org.circa.symbols.CircaSymbols
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.circa.companion.data.Phone
import org.circa.companion.data.rememberPhone
import org.circa.companion.ui.Message

/** Find my phone: one big button -> `call("findPhone", "start")`, then "Ringing..." and Stop. */
class FindPhoneActivity : CircaActivity() {
    @Composable override fun Content() = FindPhoneScreen()
}

@Composable
private fun FindPhoneScreen() {
    val cr = LocalContext.current.contentResolver
    val status by rememberPhone("status", Phone::readStatus)
    val scope = rememberCoroutineScope()
    var ringing by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(error) { if (error != null) { delay(3500); error = null } }

    val st = status ?: return
    val connected = st.value?.connected == true
    // A dropped link ends the ringing state (the phone keeps ringing until its own timeout).
    LaunchedEffect(connected) { if (!connected) ringing = false }

    fun command(arg: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { Phone.call(cr, "findPhone", arg) }
            if (r.ok) { ringing = arg == "start"; error = null }
            else { ringing = false; error = r.message }
        }
    }

    when {
        st.error != null -> Message("Phone data unavailable", "WatchLink is not running", icon = null)
        !connected && !ringing -> Message(error ?: "Phone not connected", "Check Gadgetbridge on your phone")
        ringing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Pulse()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(CircaSymbols.Filled.Call, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Ringing…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("ringing"))
                Spacer(Modifier.height(10.dp))
                IconButton(onClick = { command("stop") }, modifier = Modifier.size(64.dp).testTag("stop_ringing"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError)) { Icon(CircaSymbols.Filled.Stop, "Stop ringing", Modifier.size(30.dp)) }
            }
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { command("start") }, modifier = Modifier.size(104.dp).testTag("ring_phone"),
                    colors = IconButtonDefaults.filledIconButtonColors()) { Icon(CircaSymbols.Filled.Call, "Ring phone", Modifier.size(48.dp)) }
                Spacer(Modifier.height(6.dp))
                Text(error ?: "Ring phone", style = MaterialTheme.typography.titleSmall,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Two accent rings that expand and fade behind the "Ringing" state. */
@Composable
private fun Pulse() {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val maxR = size.minDimension / 2 - 4.dp.toPx()
        for (k in 0..1) {
            val q = (p + k * 0.5f) % 1f
            drawCircle(color.copy(alpha = (1f - q) * 0.45f), maxR * (0.74f + 0.26f * q), style = Stroke(3.dp.toPx()))
        }
    }
}
