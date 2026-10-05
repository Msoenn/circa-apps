package org.circa.companion

import org.circa.symbols.CircaSymbols
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.circa.companion.data.Phone
import org.circa.companion.data.rememberPhone
import org.circa.companion.model.Music
import org.circa.companion.ui.Message
import org.circa.companion.ui.RingArc
import org.circa.companion.ui.RoundButton
import org.circa.companion.ui.rememberWallNow
import kotlin.math.abs

/** Media: controls for the phone's current player, through WatchLink's `/music` + `call("music")`. */
class MediaActivity : CircaActivity() {
    @Composable override fun Content() = MediaScreen()
}

private const val VOLUME_STEPS = 15f        // the phone's volume range as the arc shows it

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaScreen() {
    val ctx = LocalContext.current
    val cr = ctx.contentResolver
    val status by rememberPhone("status", Phone::readStatus)
    val music by rememberPhone("music", Phone::readMusic)
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) { if (notice != null) { delay(3000); notice = null } }

    fun send(n: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { Phone.call(cr, "music", n) }
            notice = if (r.ok) null else r.message
        }
    }

    // Crown = phone volume. WatchLink exposes no volume level, so the arc shows a level relative to where this
    // screen started (half), clamped; one crown detent = one volumeup/volumedown, batched over 150 ms.
    var level by remember { mutableFloatStateOf(0.5f) }
    var arcShown by remember { mutableStateOf(false) }
    var arcTick by remember { mutableIntStateOf(0) }
    val volume = remember { Channel<Int>(Channel.UNLIMITED) }
    val acc = remember { floatArrayOf(0f) }
    // One volume step per full unit of crown scroll (Compose reports units x the scaled scroll factor).
    val stepPx = remember { android.view.ViewConfiguration.get(ctx).scaledVerticalScrollFactor.coerceAtLeast(1f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(arcTick) { if (arcTick > 0) { arcShown = true; delay(1400); arcShown = false } }
    LaunchedEffect(Unit) {
        for (first in volume) {
            var net = first
            delay(150)
            while (true) { net += volume.tryReceive().getOrNull() ?: break }
            if (net == 0) continue
            level = (level + net / VOLUME_STEPS).coerceIn(0f, 1f)
            arcTick++
            val n = minOf(abs(net), 8)
            withContext(Dispatchers.IO) { repeat(n) { Phone.call(cr, "music", if (net > 0) "volumeup" else "volumedown") } }
        }
    }

    val st = status
    val mu = music
    val connected = st?.value?.connected == true
    val now = rememberWallNow(active = connected && mu?.value?.state == "play")

    Box(
        Modifier.fillMaxSize()
            .onRotaryScrollEvent {
                acc[0] += it.verticalScrollPixels
                while (abs(acc[0]) >= stepPx) {
                    val d = if (acc[0] > 0) 1 else -1
                    acc[0] -= d * stepPx
                    volume.trySend(d)
                }
                true
            }
            .focusRequester(focus).focusable(),
    ) {
        when {
            st == null || mu == null -> Unit
            st.error != null -> Message("Phone data unavailable", "WatchLink is not running")
            !connected -> Message("Phone not connected", "Check Gadgetbridge on your phone")
            else -> {
                val m = mu.value
                if (m == null || Music.isStale(m.updatedMs, m.state, now)) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                        Text("Nothing playing", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        Text(notice ?: "Start music on your phone", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                            color = if (notice != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        RoundButton(CircaSymbols.Filled.PlayArrow, "Play", "play", primary = true, size = 56.dp, iconSize = 32.dp) { send("play") }
                    }
                } else {
                    val playing = m.state == "play"
                    val pos = Music.position(m.positionS, m.positionAtMs, now, m.durationS, playing)
                    RingArc(Music.progress(pos, m.durationS) ?: 0f, stroke = 3.dp)
                    Column(Modifier.fillMaxSize().padding(top = 20.dp, bottom = 14.dp),
                        Arrangement.Center, Alignment.CenterHorizontally) {
                        Text(m.track?.ifBlank { null } ?: "Unknown track", style = MaterialTheme.typography.titleSmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp))
                        Text(notice ?: m.artist?.ifBlank { null } ?: m.album.orEmpty(), style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, textAlign = TextAlign.Center,
                            color = if (notice != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp).basicMarquee())
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RoundButton(CircaSymbols.Filled.SkipPrevious, "Previous", "prev", size = 48.dp) { send("previous") }
                            RoundButton(if (playing) CircaSymbols.Filled.Pause else CircaSymbols.Filled.PlayArrow, if (playing) "Pause" else "Play", "playpause",
                                primary = true, size = 60.dp, iconSize = 34.dp) { send("playpause") }
                            RoundButton(CircaSymbols.Filled.SkipNext, "Next", "next", size = 48.dp) { send("next") }
                        }
                    }
                }
                if (arcShown) VolumeArc(level)
            }
        }
    }
}

/** The volume arc on the right edge (-60..60 degrees around 3 o'clock), filled from the bottom. */
@Composable
private fun VolumeArc(level: Float) {
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val w = 6.dp.toPx()
        val inset = 3.dp.toPx() + w / 2
        val s = Size(size.width - 2 * inset, size.height - 2 * inset)
        drawArc(track, -60f, 120f, false, Offset(inset, inset), s, style = Stroke(w, cap = StrokeCap.Round))
        if (level > 0f) drawArc(color, 60f - 120f * level, 120f * level, false, Offset(inset, inset), s, style = Stroke(w, cap = StrokeCap.Round))
    }
}
