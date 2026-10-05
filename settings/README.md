# Circa Settings (`org.circa.settings`)

The round Settings app, in stock Wear OS's order: connectivity (Wi-Fi, Bluetooth with a round pairing prompt),
display (brightness, always-on, the launcher's face and accent), sound and vibration, Do Not Disturb, apps (info,
force stop, disable, uninstall, permissions), security (PIN set/change/remove with the keyguard's keypad, lock when
taken off), exercise profile and buttons, system (time zone, date and time, about, restart). Kotlin + Compose for
Wear OS, no INTERNET permission.

It answers `android.settings.SETTINGS` and the deep links it has a page for (priority 100). AOSP Settings stays
installed as the backend; only its launcher entry is hidden (`device/circa/apps/etc/sysconfig-circa-settings.xml`).
Settings shared with the launcher and the Circa shade live in `Settings.Secure` (`circa_*` keys). A platform-signed
priv-app; its privileged permissions are listed in `device/circa/apps/etc/privapp-permissions-circa.xml`.

Build: `../build-all.sh --only settings`. The pure parts (option tables, navigation, PIN flow, app actions, profile)
are unit-tested.
