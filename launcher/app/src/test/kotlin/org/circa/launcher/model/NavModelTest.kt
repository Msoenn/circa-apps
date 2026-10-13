package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The navigation view's action -> glyph mapping and its distance/instruction/ETA formatting (model/NavModel.kt). */
class NavModelTest {
    @Test fun glyphPerAction() {
        assertEquals(NavGlyph.STRAIGHT, NavModel.glyph("continue"))
        assertEquals(NavGlyph.TURN_LEFT, NavModel.glyph("left"))
        assertEquals(NavGlyph.TURN_RIGHT, NavModel.glyph("right"))
        assertEquals(NavGlyph.SLIGHT_LEFT, NavModel.glyph("left_slight"))
        assertEquals(NavGlyph.SLIGHT_RIGHT, NavModel.glyph("right_slight"))
        assertEquals(NavGlyph.SHARP_LEFT, NavModel.glyph("left_sharp"))
        assertEquals(NavGlyph.SHARP_RIGHT, NavModel.glyph("right_sharp"))
        assertEquals(NavGlyph.FORK_LEFT, NavModel.glyph("keep_left"))
        assertEquals(NavGlyph.FORK_RIGHT, NavModel.glyph("keep_right"))
        assertEquals(NavGlyph.U_TURN_LEFT, NavModel.glyph("uturn_left"))
        assertEquals(NavGlyph.U_TURN_RIGHT, NavModel.glyph("uturn_right"))
        assertEquals(NavGlyph.ROUNDABOUT_LEFT, NavModel.glyph("roundabout_left"))
        assertEquals(NavGlyph.ROUNDABOUT_RIGHT, NavModel.glyph("roundabout_right"))
        assertEquals(NavGlyph.WRONG_LOCATION, NavModel.glyph("offroute"))
        assertEquals(NavGlyph.FINISH, NavModel.glyph("finish"))
    }

    @Test fun bothRoundaboutExitsUseTheRightHandGlyph() {
        // Maps draws the straight-through and the U-turn roundabout exit with the right-hand glyph too.
        assertEquals(NavGlyph.ROUNDABOUT_RIGHT, NavModel.glyph("roundabout_straight"))
        assertEquals(NavGlyph.ROUNDABOUT_RIGHT, NavModel.glyph("roundabout_uturn"))
    }

    @Test fun unknownActionStillShowsARoute() {
        assertEquals(NavGlyph.NAVIGATION, NavModel.glyph(null))
        assertEquals(NavGlyph.NAVIGATION, NavModel.glyph(""))
        assertEquals(NavGlyph.NAVIGATION, NavModel.glyph("left_hairpin")) // a name a later Gadgetbridge may add
        assertEquals(NavGlyph.NAVIGATION, NavModel.glyph("LEFT")) // GB's names are lower-case only
    }

    @Test fun viewFormatsWhatTheFaceDraws() {
        assertNull(NavModel.view(null))
        val nav = NavState(
            updatedMs = 0,
            instruction = " Turn left onto Main St ",
            distance = " 0.2 mi ",
            distanceM = 322,
            action = "left",
            eta = "11:55",
        )
        val view = NavModel.view(nav)!!
        assertEquals(NavGlyph.TURN_LEFT, view.glyph)
        assertEquals("0.2 mi", view.distance) // Maps' own string, as the phone shows it
        assertEquals("Turn left onto Main St", view.instruction)
        assertEquals("Arrive 11:55", view.etaText)
    }

    @Test fun viewHidesWhatMapsLeftOut() {
        val bare = NavModel.view(NavState(0, null, "300 m", 300, "continue", null))!!
        assertEquals(NavGlyph.STRAIGHT, bare.glyph)
        assertEquals("300 m", bare.distance)
        assertNull(bare.instruction)
        assertNull(bare.etaText)
        // an empty ETA is absent, not "Arrive "
        val blank = NavModel.view(NavState(0, " ", "", 300, null, "  "))!!
        assertEquals(NavGlyph.NAVIGATION, blank.glyph)
        assertNull(blank.instruction)
        assertNull(blank.etaText)
        assertEquals("", blank.distance)
    }
}
