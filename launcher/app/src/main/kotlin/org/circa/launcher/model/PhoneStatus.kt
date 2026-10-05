package org.circa.launcher.model

/** The phone-connection pill's text (quick settings, under the grid). */
object PhoneStatus {
    fun label(connected: Boolean) = if (connected) "Phone connected" else "Phone disconnected"

    /** What fits in the pill at the circle's bottom edge (the phone icon carries the noun). */
    fun short(connected: Boolean) = if (connected) "Connected" else "Disconnected"
}
