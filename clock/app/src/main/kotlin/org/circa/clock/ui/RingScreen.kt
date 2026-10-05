package org.circa.clock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import org.circa.clock.data.RingState

/** The full-screen alert: label, big time, and two buttons - Snooze (not for timers) and Dismiss. */
@Composable
internal fun RingScreen(onSnooze: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().testTag("ring_screen")) {
        Column(Modifier.align(Alignment.TopCenter).padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(RingState.title, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1,
                modifier = Modifier.padding(horizontal = 36.dp).testTag("ring_title"))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(RingState.timeText, fontSize = if (RingState.timeText.length > 6) 38.sp else 52.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.testTag("ring_time"))
                if (RingState.ampm.isNotEmpty()) Text(RingState.ampm, fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (RingState.canSnooze) {
                FilledTonalButton(onClick = onSnooze, modifier = Modifier.width(80.dp).height(56.dp).testTag("ring_snooze"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                    label = { Text("Snooze", fontSize = 14.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()) })
            }
            Button(onClick = onDismiss, modifier = Modifier.width(if (RingState.canSnooze) 80.dp else 120.dp).height(56.dp).testTag("ring_dismiss"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                label = { Text("Dismiss", fontSize = 14.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()) })
        }
    }
}

