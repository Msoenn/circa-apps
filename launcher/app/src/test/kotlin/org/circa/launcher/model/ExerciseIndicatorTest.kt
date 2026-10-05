package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The face indicator's activity -> glyph mapping (model/ExerciseIndicator.kt). */
class ExerciseIndicatorTest {
    @Test fun glyphPerActivity() {
        assertEquals(ExerciseGlyph.RUN, ExerciseIndicator.glyph("run"))
        assertEquals(ExerciseGlyph.RUN, ExerciseIndicator.glyph("indoor_run"))
        assertEquals(ExerciseGlyph.WALK, ExerciseIndicator.glyph("walk"))
        assertEquals(ExerciseGlyph.BIKE, ExerciseIndicator.glyph("bike"))
        assertEquals(ExerciseGlyph.HIKE, ExerciseIndicator.glyph("hike"))
        assertEquals(ExerciseGlyph.STRENGTH, ExerciseIndicator.glyph("strength"))
    }

    @Test fun genericGlyphForEveryOtherActivity() {
        // swim, yoga, hiit, elliptical, rowing, other: all 12 exercise ids are covered.
        for (id in listOf("other", "swim", "yoga", "hiit", "elliptical", "rowing")) {
            assertEquals("$id should use the generic glyph", ExerciseGlyph.OTHER, ExerciseIndicator.glyph(id))
        }
    }

    @Test fun noGlyphWhenNothingRecords() {
        assertNull(ExerciseIndicator.glyph(null))
        assertNull(ExerciseIndicator.glyph(""))
        // Unknown ids are still an active workout (the service only reports real ones), so they get the generic glyph.
        assertEquals(ExerciseGlyph.OTHER, ExerciseIndicator.glyph("future_sport"))
    }
}
