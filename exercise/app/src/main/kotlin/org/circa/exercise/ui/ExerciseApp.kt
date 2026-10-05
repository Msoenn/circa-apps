package org.circa.exercise.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.AppScaffold
import org.circa.exercise.data.Live
import org.circa.exercise.model.ActivityType

/** The app's screens. */
sealed interface Screen {
    data object List : Screen
    data object More : Screen
    data class Countdown(val type: ActivityType) : Screen
    data object Live : Screen
    data object Controls : Screen
    data object ConfirmEnd : Screen
    data object ConfirmDiscard : Screen
    data object Summary : Screen
}

/** What the screens can ask the activity to do. */
interface Actions {
    fun pick(type: ActivityType)
    fun go(s: Screen)
    fun back()
    fun toggle()
    fun save()
    fun discard()
    fun done()
}

@Composable
fun ExerciseApp(screen: Screen, last: ActivityType?, a: Actions) {
    val view by Live.view.collectAsState()
    val pending by Live.pending.collectAsState()
    // A new workout starts on the first page (the index used to carry over: Bike on distance -> Strength opened
    // on calories, emulator 2026-10-04).
    var page by remember(view?.startMs) { mutableIntStateOf(0) }
    AppScaffold(timeText = {}, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().swipes(onRight = { a.back() }, onLeft = { if (screen == Screen.Controls) a.go(Screen.Live) })) {
            when (screen) {
                Screen.List -> ActivityListScreen(last, a::pick) { a.go(Screen.More) }
                Screen.More -> MoreScreen(a::pick)
                is Screen.Countdown -> CountdownScreen(screen.type, onGo = { a.pick(screen.type) }, onCancel = { a.go(Screen.List) })
                Screen.Live -> view?.let { LiveScreen(it, page) { p -> page = p } }
                Screen.Controls -> view?.let { ControlsScreen(it, a::toggle) { a.go(Screen.ConfirmEnd) } }
                Screen.ConfirmEnd -> ConfirmEndScreen(onSave = a::save, onDiscard = { a.go(Screen.ConfirmDiscard) })
                Screen.ConfirmDiscard -> ConfirmDiscardScreen(onKeep = { a.go(Screen.ConfirmEnd) }, onDiscard = a::discard)
                Screen.Summary -> pending?.let { SummaryScreen(it, a::done) }
            }
        }
    }
}
