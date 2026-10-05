package org.circa.exercise.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.circa.exercise.model.ActivityType
import org.circa.exercise.model.ActivityType.*
import org.circa.symbols.CircaSymbols

/** Material Symbols glyph and disc colour of each activity (design round 2). */
internal fun ActivityType.icon(): ImageVector = when (this) {
    RUN -> CircaSymbols.Filled.DirectionsRun
    WALK -> CircaSymbols.Filled.DirectionsWalk
    BIKE -> CircaSymbols.Filled.DirectionsBike
    HIKE -> CircaSymbols.Filled.Hiking
    STRENGTH -> CircaSymbols.Filled.FitnessCenter
    OTHER -> CircaSymbols.Filled.Timer // time + HR only; the dumbbell glyphs read as Strength
    INDOOR_RUN -> CircaSymbols.Filled.Sprint
    SWIM -> CircaSymbols.Filled.Pool
    YOGA -> CircaSymbols.Filled.SelfImprovement
    HIIT -> CircaSymbols.Filled.SportsGymnastics
    ELLIPTICAL -> CircaSymbols.Filled.Steps
    ROWING -> CircaSymbols.Filled.Rowing
}

internal fun ActivityType.color(): Color = when (this) {
    RUN, INDOOR_RUN -> Color(0xFF81C995)
    WALK, SWIM, ROWING -> Color(0xFF8AB4F8)
    BIKE, ELLIPTICAL -> Color(0xFFFDD663)
    HIKE, YOGA -> Color(0xFFC58AF9)
    STRENGTH, HIIT -> Color(0xFFF28B82)
    OTHER -> Color(0xFF9AA0A6)
}
