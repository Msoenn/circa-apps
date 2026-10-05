# Circa Clock (`org.circa.clock`)

Alarms, timers and a stopwatch for the round screen; replaces DeskClock. It claims `SET_ALARM`, `SHOW_ALARMS`,
`SET_TIMER` and `SHOW_TIMERS` at priority 100. A ringing alarm or timer is a full-screen activity: the crown or
leaving it snoozes (`onUserLeaveHint`); Snooze and Dismiss buttons do the rest. Kotlin + Compose for Wear OS, platform-signed priv-app.

Build: `../build-all.sh --only clock`; the scheduling and formatting logic is unit-tested.
