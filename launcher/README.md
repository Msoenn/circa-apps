# Circa launcher (`org.circa.launcher`)

The watch's home screen: watch faces, the app list, tiles, the always-on display and the wake gestures. Kotlin +
Jetpack Compose for Wear OS (Material 3), no Google services, no INTERNET permission. A platform-signed priv-app on
`/product`; it replaces Launcher3 in a Circa image.

## Screens

- **Watch faces** (digital, concentric, analog) with complications for heart rate, steps, weather and the next
  event. While a workout records (or is paused), a small 24 dp indicator with the activity's Material Symbols glyph
  sits in the face's free space (above the digital time, between the concentric hour and its inner ring, above the
  analog complications); tapping it opens the exercise app's live screen. The exercise app's state provider
  (`content://org.circa.exercise.state/current`, a `ContentObserver` - no polling) drives it. A long press on the face
  opens the face picker (`org.circa.intent.action.PICK_FACE`); the choice and the accent colour are the launcher's own
  preferences (the accent and "lock when taken off" are shared with Circa Settings through `Settings.Secure` keys
  `circa_accent_color` and `circa_lock_when_taken_off`).
- **Carousel of tiles** next to the face: health (heart rate and steps from WatchLink), media and agenda.
- **App list** (`android.intent.action.ALL_APPS`) and the recent apps.

## Buttons

The platform side lives in Circa's `frameworks/base` (`CircaKeyPolicy`) and is configured by the launcher overlay in
`device/circa/apps`. The crown's press is the POWER key: on the face it opens the app list (an `ALL_APPS` intent to
the HOME holder), elsewhere it returns to the face, and over a PIN keyguard it shows the bouncer. The side button
(`KEYCODE_STEM_PRIMARY`) toggles the notifications screen of the Circa shade; a long press starts the Exercise app's
`org.circa.action.EXERCISE_LONG_PRESS` activity. The launcher leaves those keys unconsumed so the framework's action
runs (`CircaButtons`).

## Lock screen

The face shows over the keyguard (it is an occluding activity); any interaction that needs the PIN goes through
`requireUnlock`. Before the first unlock after boot, when the launcher cannot run (credential-encrypted storage),
SystemUI draws its own copy of the face.

## Always-on display

The ambient face is the launcher's `AmbientDreamService` (a `DreamService` with `BIND_DREAM_SERVICE`), set as the
framework's `config_dozeComponent` by the overlay. It redraws once a minute from an exact alarm and lets the CPU
suspend in between. Only the platform signature lets the app be the doze component. The time is near-white (#E8EAED,
regular weight) on black, and the dream sets its doze brightness from Circa Settings › Display › Always-on brightness
(`Settings.Secure circa_aod_brightness`: Low 0.0 / Normal 0.02, the default / High 0.043; the watch's own doze curve
runs 0.0 to 0.0428). `MainActivity` is `showWhenLocked` but not `turnScreenOn`: WindowManager lets a turn-screen-on
activity launched while the screen was on wake it again at its next keyguard update, which bounced a sleep right
after a HOME start.

## Tilt-to-wake and lock when taken off

The launcher listens to the sensor hub's wrist-tilt gesture and decides with its own gate (`TiltGate`) whether a tilt
is a glance: a short glance shows the face and drops back to ambient (`TiltGlance`). Every decision is appended to a
small CSV telemetry file in the app's external files dir (`Android/data/org.circa.launcher/files/tilt-log.csv`),
used to retune the thresholds. With "lock when taken off" on, the off-body sensor locks the watch
(`WakeGestures`). In theater mode neither gesture wakes the screen.

## Data

Heart rate and steps come from WatchLink's health provider, weather/media/agenda from its phone-data provider (see
[the data contract](../watchlink/DATA-CONTRACT.md)). The launcher is also the default notification listener (the
notification stream of the shade) and holds Do Not Disturb access.

## Debug broadcasts (adb)

`org.circa.launcher.DEMO_HEALTH` (`--ei hr 72 --ei steps 4213`), `org.circa.launcher.DEBUG_TILT`,
`org.circa.launcher.DEBUG_OFFBODY` (`--ei value 0`).

## Build and test

`../build-all.sh --only launcher`, or `./gradlew :app:testDebugUnitTest` here. The pure logic (models, gestures,
gauges, policies) is unit-tested on the JVM.
