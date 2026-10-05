# Circa Exercise (`org.circa.exercise`)

Workout tracking: pick an activity, see heart rate with zones, duration, distance, pace and calories while
recording, then a summary. GPS distance and route where the activity uses it. The heart-rate zones and calorie
estimate use the exercise profile from Circa Settings (shared through `Settings.Secure`).

- **Side button:** a long press anywhere starts the app (the platform starts the system app that handles
  `org.circa.action.EXERCISE_LONG_PRESS`, unless the side-button long press is set to the power menu). While the app
  is in front, the side button pauses and resumes the workout (it holds `OVERRIDE_SYSTEM_KEY_BEHAVIOR_IN_FOCUSED_WINDOW`).
- **Sync:** finished workouts are stored as Bangle.js recorder CSV, and WatchLink sends them to Gadgetbridge as
  recorder tracks. The sync provider `org.circa.exercise.sync` is exported without a permission but checks callers:
  system uids, this app, and preinstalled `org.circa.*` packages. Its `call()` methods: `list` (ids after a given id),
  `csv` (one track), `state` (whether a workout is recording, and its Gadgetbridge activity name).

Build: `../build-all.sh --only exercise`; the models (zones, pace, CSV, caller policy) are unit-tested.
