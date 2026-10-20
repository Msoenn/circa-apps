# Circa Settings (`org.circa.settings`)

The round Settings app, in stock Wear OS's order: connectivity (Wi-Fi, Bluetooth with a round pairing prompt,
USB data, Wi-Fi auto-off and internet check), display (brightness, always-on and its brightness, the launcher's face and accent), sound and vibration, Do Not Disturb, apps (info,
force stop, disable, uninstall, permissions), security (PIN set/change/remove with the keyguard's keypad, lock when
taken off, auto restart, scrambled PIN pad), exercise profile and buttons, system (time zone, date and time, about, restart). Kotlin + Compose for
Wear OS, no INTERNET permission.

It answers `android.settings.SETTINGS` and the deep links it has a page for (priority 100). AOSP Settings stays
installed as the backend; only its launcher entry is hidden (`device/circa/apps/etc/sysconfig-circa-settings.xml`).
Settings shared with the launcher and the Circa shade live in `Settings.Secure` (`circa_*` keys). A platform-signed
priv-app; its privileged permissions are listed in `device/circa/apps/etc/privapp-permissions-circa.xml`.

Build: `../build-all.sh --only settings`. The pure parts (option tables, navigation, PIN flow, app actions, profile)
are unit-tested.

## Privacy and security keys

This app only writes the keys; the framework reads and applies them.

| Setting | Key | Values |
|---|---|---|
| Auto restart | `Settings.Global circa_auto_reboot_ms` | unset = 18 h, 0 = off |
| USB data | `Settings.Global circa_usb_data` | unset/1 = on, 0 = charging only, 2 = charging only when locked |
| Turn off Wi-Fi when unused | `Settings.Global circa_wifi_auto_off_ms` | unset = 15 min, 0 = off |
| Internet check | `Settings.Global captive_portal_mode` | unset/0 = off, 1 = standard |
| Random Wi-Fi address | `Settings.Global non_persistent_mac_randomization_force_enabled` | 1 = on; unset/other = off (Circa default 1) |
| Background scanning | `Settings.Global wifi_scan_always_enabled` and `ble_scan_always_enabled` | on only when both are 1; otherwise off |
| Scramble PIN pad | LineageSettings.System `lockscreen_scramble_pin_layout` | 0/1, via the `lineagesettings` provider (row hidden without it) |
| Heart rate alerts (high) | `Settings.Secure circa_hr_high_bpm` | unset = 120 bpm, 0 = off (WatchLink alerts the user) |
| Heart rate alerts (low) | `Settings.Secure circa_hr_low_bpm` | unset = 40 bpm, 0 = off (WatchLink alerts the user) |
| Phone left behind alert | `Settings.Secure circa_phone_left_alert` | unset/1 = on, 0 = off |

The two heart-rate alert thresholds live on the Profile page ("Heart rate alerts", with High and Low radio pages);
the phone-left-behind toggle is on the Connectivity page. WatchLink reads the keys and posts the notifications.

Auto restart and Scramble PIN pad are disabled until a PIN is set. A one-shot migration, guarded by
`Settings.Global circa_privacy_defaults_v1`, gives existing watches the privacy defaults (internet check off,
MAC randomisation, no always-on BLE/Wi-Fi scanning, no Wi-Fi wake-up); it runs with the first settings read after
the app starts (plan in `model/PrivacyModel.kt`). There is no boot receiver and no `MANAGE_USB` permission any more.
