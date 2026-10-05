package org.circa.exercise.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import org.circa.exercise.model.ActivityType
import org.circa.symbols.CircaSymbols

/** The activity list: last used on top (marked), the short list, then "More". */
@Composable
internal fun ActivityListScreen(last: ActivityType?, onPick: (ActivityType) -> Unit, onMore: () -> Unit) {
    CurvedList(modifier = Modifier.testTag("screen_list")) { spec ->
        item(key = "header") { ListHeader(Modifier.transformedHeight(this, spec)) { Text("Exercise", maxLines = 1) } }
        for ((i, t) in ActivityType.listOrder(last).withIndex()) {
            item(key = t.id) {
                val isLast = i == 0 && last != null
                FilledTonalButton(
                    onClick = { onPick(t) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = ROW_MIN_HEIGHT).transformedHeight(this, spec).testTag("act_${t.id}"),
                    transformation = SurfaceTransformation(spec),
                    colors = if (isLast) ButtonDefaults.filledTonalButtonColors(containerColor = LAST_USED_BG) else ButtonDefaults.filledTonalButtonColors(),
                    icon = { IconDisc(t.icon(), t.color()) },
                    label = { Text(t.label, maxLines = 1, softWrap = false) },
                    secondaryLabel = if (isLast) ({ Text("Last used", maxLines = 1, softWrap = false) }) else null,
                )
            }
        }
        item(key = "more") {
            FilledTonalButton(
                onClick = onMore,
                modifier = Modifier.fillMaxWidth().heightIn(min = ROW_MIN_HEIGHT).transformedHeight(this, spec).testTag("act_more"),
                transformation = SurfaceTransformation(spec),
                icon = { IconDisc(CircaSymbols.Filled.MoreHoriz, DIM) },
                label = { Text("More", maxLines = 1, softWrap = false) },
                secondaryLabel = { Text("Swim, Yoga, HIIT…", maxLines = 1, softWrap = false, overflow = TextOverflow.Clip) },
            )
        }
    }
}

/** Behind "More": Indoor run, Swim, Yoga, HIIT, Elliptical, Rowing. */
@Composable
internal fun MoreScreen(onPick: (ActivityType) -> Unit) {
    CurvedList(modifier = Modifier.testTag("screen_more")) { spec ->
        item(key = "header") { ListHeader(Modifier.transformedHeight(this, spec)) { Text("More", maxLines = 1) } }
        for (t in ActivityType.MORE) {
            item(key = t.id) {
                FilledTonalButton(
                    onClick = { onPick(t) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = ROW_MIN_HEIGHT).transformedHeight(this, spec).testTag("act_${t.id}"),
                    transformation = SurfaceTransformation(spec),
                    icon = { IconDisc(t.icon(), t.color()) },
                    label = { Text(t.label, maxLines = 1, softWrap = false) },
                )
            }
        }
    }
}
