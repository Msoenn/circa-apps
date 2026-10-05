# WatchLink (`org.circa.watchlink`)

The watch's link to the phone. WatchLink makes the watch look like a **Bangle.js** to
[Gadgetbridge](https://gadgetbridge.org): a Bluetooth LE GATT server with the Nordic UART service, advertising as
`Bangle.js xxxx`, speaking the Bangle.js line protocol. Pair the watch in Gadgetbridge as a Bangle.js. Plain Kotlin,
no AndroidX, no dependencies, no INTERNET permission.

What it does:

- mirrors phone notifications (open and dismiss) and incoming calls (accept, reject);
- measures heart rate and steps and sends Gadgetbridge 10-minute activity records;
- keeps the latest phone data (weather with forecast, media, calendar) and serves it, with heart rate and steps, to
  the other Circa apps through two content providers: see **[DATA-CONTRACT.md](DATA-CONTRACT.md)**;
- syncs finished workouts from the Exercise app as Bangle.js recorder tracks;
- find my phone, and lets the phone ring the watch.

## Protocol

Phone to watch: Gadgetbridge writes `\x10GB({...})\n`, a JavaScript object literal (strings may use `\xHH`, octal,
`\uXXXX` escapes and `atob("...")`). `LineAssembler` reassembles GATT writes into lines, `JsParser`/`Proto` parse them.
`setTime(...)` lines set WatchLink's clock and time zone. When Settings.Global `auto_time_zone` is on, WatchLink also
applies the phone's UTC offset to the system zone (`TzPolicy` + `AlarmManager.setTimeZone`), since this watch has no
SIM and no location provider and Android's detector reports NOT_SUPPORTED; a zone that already matches is kept.
Watch to phone: JSON lines framed as `\r\n{json}\r\n`, as a Bangle.js sends them. After a connect the watch sends
`ver` (`fw`, `hw`) and a status line, padded to the MTU, as a real Bangle.js does.

The advertised name is `Bangle.js` plus four hex digits. Gadgetbridge only offers a device as a Bangle.js by that
name. If the Bluetooth adapter already has such a name it is kept, so a paired phone keeps knowing the watch.

## Running

WatchLink runs as a foreground service (connected device + health). Start it once from its activity; it then starts
again after every boot and update (`BootReceiver`) until it is stopped. Bonding is done by Android's pairing dialog.

## Tests

`./gradlew :app:hostTest` runs the host-JVM protocol tests (`src/hostTest`). Their golden source is
`src/hostTest/resources/gb-golden.tsv`: for every test case, the exact bytes Gadgetbridge's own encoder writes,
followed by the value WatchLink must parse back. Each case is cut into GATT writes of several sizes, reassembled
and parsed. The test also writes `build/hostTest/gb-vectors.tsv` (hex bytes TAB expected JSON) for external
harnesses. The same task covers the bucketing, heart-rate windows, notification policy, health snapshot, phone data
and track sync logic.

## Signing

Signed with the AOSP **testkey**, not the platform key: WatchLink processes input from the phone over Bluetooth, and
its one privileged power (`SET_TIME_ZONE`, to follow the phone's UTC offset) is granted by the device's privapp
allowlist. Its providers check callers by UID (see the data contract).
