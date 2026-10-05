package org.circa.settings.ui

import org.circa.symbols.CircaSymbols
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/** What `RequestActivity` shows. */
sealed interface RequestUi {
    /** Nothing yet (before the intent was read). */
    data object Closed : RequestUi
    data class Ask(val caption: String?, val title: String, val pkg: String?, val detail: String? = null) : RequestUi
    data class Working(val title: String) : RequestUi
    /** The request can't be done; one round OK button. */
    data class Refused(val message: String, val pkg: String?) : RequestUi
}

/** Round confirm: who asks, the question, a round cross and tick (same as Restart / Power off). */
@Composable
fun RequestScreen(state: RequestUi, onNo: () -> Unit, onYes: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("request_screen"), contentAlignment = Alignment.Center) {
        when (state) {
            RequestUi.Closed -> {}
            is RequestUi.Ask -> RequestBody(state.caption, state.title, state.pkg, state.detail) {
                RoundChoice("request_no", CircaSymbols.Filled.Close, "No", tonal = true, onClick = onNo)
                RoundChoice("request_yes", CircaSymbols.Filled.Check, "Yes", tonal = false, onClick = onYes)
            }
            is RequestUi.Working -> RequestBody(null, state.title, null, null) {
                Text("Working…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is RequestUi.Refused -> RequestBody(null, state.message, state.pkg, null) {
                RoundChoice("request_ok", CircaSymbols.Filled.Check, "OK", tonal = false, onClick = onClose)
            }
        }
    }
}

@Composable
private fun RequestBody(caption: String?, title: String, pkg: String?, detail: String?, buttons: @Composable () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (pkg != null) AppIcon(pkg, 32.dp)
        if (caption != null) {
            Text(
                caption, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("request_caption"),
            )
        }
        Text(
            title, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
            maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("request_title"),
        )
        if (detail != null) {
            Text(
                detail, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("request_detail"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 2.dp)) { buttons() }
    }
}
