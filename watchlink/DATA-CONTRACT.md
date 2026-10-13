# WatchLink data contract

WatchLink makes the watch look like a Bangle.js to [Gadgetbridge](https://gadgetbridge.org) on the phone. It receives
Gadgetbridge's messages over Bluetooth LE, keeps the latest state, and serves it to the other Circa apps through
two content providers:

- `content://org.circa.watchlink.health/…`: heart rate and steps, for the launcher's face and tiles;
- `content://org.circa.watchlink.data/…`: phone data (status, weather, media, agenda, navigation) and two commands
  (media controls, find my phone), for the launcher and Circa Companion.

This file is the contract between WatchLink (producer) and the apps (consumers).

## Gadgetbridge Bangle.js messages

Confirmed against Gadgetbridge's `BangleJSDeviceSupport.java`. Framing: Gadgetbridge writes `\x10GB({...})\n` (a
JavaScript object literal, parsed by `JsParser`). Phone to watch:

| `t` | fields as Gadgetbridge sends them | notes |
|---|---|---|
| `weather` v1 (`"v":1`) | `temp`, `hi`, `lo` (integer **Kelvin**), `hum`, `rain` (%), `uv`, `code` (OpenWeatherMap), `txt`, `wind` (km/h), `wdir` (deg), `loc` | Pushed whenever Gadgetbridge's weather data updates. **No forecast.** |
| `weather` v2 (`"v":2`) | `l` (location), `c` (condition text), `d` = base64 binary | Sent **only as the answer to a watch request** `{"t":"weather","v":2,"f":true}`. `d` = 38-byte current block (byte layout below), then with `f:true` the hourly arrays (max 25) and the daily arrays (max 7: hi, lo, mapped code, wind, wdir/2, rain; **no dates**). |
| `musicinfo` | `artist`, `album`, `track`, `dur` (s), `c` (track count), `n` (track number) | Sent when the track changes. |
| `musicstate` | `state` (`play`/`pause`/`stop`, `""` when unknown), `position` (s), `shuffle`, `repeat` | Position is a snapshot at send time. |
| `nav` | `instr` (Maps' notification text: `Main St`, `Turn left onto Main St`; omitted when null), `distance` (Maps' raw display string in the phone's locale and units: `100 yd`, `0.3 mi`, `300 m`, `1.2 km`; may be null/empty), `action` (one of Gadgetbridge's `navActions` names; omitted when unknown), `eta` (arrival clock time as Maps shows it, `11:55`; omitted when absent) | One message per Maps notification update, **no throttling**. A blank `{"t":"nav"}` (no `instr`, no `action`, empty/absent `distance`) means navigation ended. **Only if Gadgetbridge's navigation for Google Maps is on.** |
| `calendar` | `id`, `type`, `timestamp` (s), `durationInSeconds`, `title` (cropped to 40), `description` (200), `location` (40), `calName` (20), `color`, `allDay` | One event per message. **Only if the device setting "Sync calendar" is on.** |
| `calendar-` | `id` = a number, or an array of numbers | Event removed. Same gate. |
| `force_calendar_sync_start` | none | Sent on every `setTime` (so on every connect). The watch answers `{"t":"force_calendar_sync","ids":[...]}` with its stored ids; Gadgetbridge deletes ids it no longer has and resends the events the watch lacks. |
| `find` | `n` (bool) | The phone asks the *watch* to ring (vibration for up to 60 s). |

Watch to phone, as JSON lines framed like a Bangle.js (`\r\n{json}\r\n`):

| message | meaning |
|---|---|
| `{"t":"music","n":"play"\|"pause"\|"playpause"\|"next"\|"previous"\|"volumeup"\|"volumedown"}` | media control (`n` is one of Gadgetbridge's `GBDeviceEventMusicControl.Event` names, lower-cased). |
| `{"t":"findPhone","n":true\|false}` | start/stop ringing the phone. |
| `{"t":"weather","v":2,"f":true}` | request the weather with forecast. Sent 2 s after every connect and then hourly while linked. |
| `{"t":"force_calendar_sync","ids":[...]}` | the answer to `force_calendar_sync_start`. |
| `{"t":"act","ts":ms,"stp":n,"hrm":bpm[,"act":KIND]}` | one 10-minute activity sample (`ts` = end of the bucket), pushed live and in `actfetch` backfills. `act` is a Gadgetbridge `ActivityKind` name: the running workout's kind, or `LIGHT_SLEEP` / `AWAKE_SLEEP` from sleep tracking; absent = plain activity. Gadgetbridge keeps the kind a sample first arrives with, so a sample that may be sleep is held back (at most 60 min) until its label is final. |

Interpretation: v1 weather has no forecast; the forecast only comes through v2 and only when asked. The v2 daily
arrays have no dates: WatchLink numbers the days from local midnight tomorrow. v2 condition codes are Gadgetbridge's
1-byte mapping, which WatchLink inverts, so `code` is always an OpenWeatherMap code. Calendar `type` 1/2 (sunrise /
sunset pseudo events) are dropped.

### Weather v2 `d` block, current part

Verified against Gadgetbridge's `handleWeatherV2` and the Bangle.js weather app's decoder (both master, 2026-10-13).
Multi-byte fields are little-endian; the sun/moon times are unix **epoch seconds** (so `×1000` for ms), the rest of
the fixed-point fields are as written by Gadgetbridge.

| off | bytes | field | Gadgetbridge writes | `/weather` column |
|---|---|---|---|---|
| 0 | 1 | temperature | signed °C (`K − 273`, clamped to −128..127) | `temp_c` |
| 1 | 1 | today's high | same | `hi_c` |
| 2 | 1 | today's low | same | `lo_c` |
| 3 | 1 | humidity | percent | `humidity` |
| 4 | 1 | rain probability | percent | `rain_pct` |
| 5 | 1 | UV index | `×10` | `uv` |
| 6 | 1 | condition | Gadgetbridge's 1-byte mapping (255 = unknown) | `code` (inverted to an OpenWeatherMap code) |
| 7 | 2 | wind | km/h `×100` | `wind_kmh` |
| 9 | 2 | wind direction | degrees | `wind_dir` |
| 11 | 1 | dew point | signed °C | `dew_c` |
| 12 | 2 | pressure | hPa `×10` | `pressure_hpa` |
| 14 | 1 | cloud cover | percent | `cloud_pct` |
| 15 | 4 | visibility | m `×10` | `visibility_m` |
| 19 | 4 | sunrise | epoch s | `sunrise_ms` |
| 23 | 4 | sunset | epoch s | `sunset_ms` |
| 27 | 4 | moonrise | epoch s | — (not served) |
| 31 | 4 | moonset | epoch s | — (not served) |
| 35 | 2 | moon phase | degrees | — (not served) |
| 37 | 1 | feels-like | signed °C | `feels_c` |

Gadgetbridge writes the whole block every time and uses its `WeatherSpec` defaults for a field the phone's weather
provider left alone (0 K, 0 hPa, 0 m, 0 %, epoch 0). WatchLink serves such a field as NULL, never as its encoded
value: the signed-byte temperatures clamp 0 K to −128, and 0 is impossible for the others (a genuinely clear sky
therefore reads as "no cloud cover" too, since Gadgetbridge cannot tell 0 % from unset). A field is never derived
from another: `hi_c`/`lo_c` are Gadgetbridge's values as sent, so a phone provider that reports today's high and low
equal to the current temperature yields three equal `/weather` values.

Navigation `action` names are Gadgetbridge's `BangleJSDeviceSupport.navActions`, index 1..17: `continue`, `left`,
`left_slight`, `left_sharp`, `right`, `right_slight`, `right_sharp`, `keep_left`, `keep_right`, `uturn_left`,
`uturn_right`, `offroute`, `roundabout_right`, `roundabout_left`, `roundabout_straight`, `roundabout_uturn`, `finish`
(index 0 is the empty string, and Gadgetbridge omits the key for it). WatchLink stores the name exactly as sent and
does not validate it; consumers must fall back to something sensible for a name they do not know.

### Gadgetbridge settings (phone)

Nothing needs a capabilities reply: WatchLink's `ver` message (`fw`, `hw`) is only shown, Gadgetbridge gates nothing
on it. The Bangle.js device type supports weather, calendar, music info, navigation and find phone.

1. **Calendar**: in the device's settings, turn on **Sync calendar** (off by default), grant Gadgetbridge the
   Calendar permission and pick the calendars. Without it Gadgetbridge silently ignores calendar events.
2. **Weather**: Gadgetbridge has no weather source of its own; it needs a weather app that publishes to it (for
   example Breezy Weather with its Gadgetbridge integration). The forecast needs nothing extra (WatchLink asks).
3. **Music**: Gadgetbridge reports the phone's media session; it needs notification access.
4. **Navigation**: **Gadgetbridge settings, Navigation, enable Google Maps** (Gadgetbridge checks this before it
   forwards Maps' navigation notification; the exact label in the app is unverified). Google Maps' own guidance also
   needs notification access. Without it no `nav` message and no route on the watch.
5. **Find phone**: no setting on the Bangle.js device type.
6. Keep **"Text as bitmaps" off** (default): with it on, non-ASCII text arrives as images WatchLink does not decode.

## Health provider: `content://org.circa.watchlink.health/…`

### `/latest`: exactly one row

| column | type | meaning |
|---|---|---|
| `hr_bpm` | INTEGER | Newest accepted heart-rate reading, **null if there is none in the last 15 minutes**. |
| `hr_time_ms` | INTEGER | Time of that reading (ms since epoch, WatchLink's clock). Null when `hr_bpm` is null. |
| `steps_today` | INTEGER | Steps since local midnight (step counter). 0 when the counter has not been read yet. |
| `steps_time_ms` | INTEGER | Time of the last step observation. Null before the first observation. |

### `/history`: 0..N rows, oldest first

| column | type | meaning |
|---|---|---|
| `time_ms` | INTEGER | Reading time (ms since epoch, WatchLink's clock). |
| `bpm` | INTEGER | Accepted heart-rate reading. |

Readings of the last 60 minutes, capped. `getType()` returns `vnd.android.cursor.item/vnd.org.circa.watchlink.health.latest`
and `vnd.android.cursor.dir/vnd.org.circa.watchlink.health.history`.

Sampling: a heart-rate sample every 5 minutes (an exact alarm opens a short measuring window), plus live readings while
the screen is on (not in theater mode). Steps since local midnight: the baseline is re-taken when the local date
changes or the counter goes backwards (reboot), and persisted so a restart within the day keeps the count. Timestamps
use WatchLink's clock: the phone-synced time once Gadgetbridge has sent `setTime`, the watch clock before that.

### `/sleep`: zero or one row

| column | type | meaning |
|---|---|---|
| `sleep_start_ms` | INTEGER | Start of the last sleep session (ms since epoch, WatchLink's clock). |
| `sleep_end_ms` | INTEGER | End of its last sleep bucket. |
| `sleep_minutes` | INTEGER | Minutes asleep (awake periods inside the night excluded, like Gadgetbridge's total). |

One row while the last session ended within the last 18 hours, else none (also with sleep tracking off before any
session). `getType()`: `vnd.android.cursor.item/vnd.org.circa.watchlink.health.sleep`. An older WatchLink rejects the
path with `IllegalArgumentException`; read it as "no sleep".

Access: `query()` checks the calling UID: WatchLink itself or `org.circa.launcher`; anything else gets a
`SecurityException`. Change notifications: `notifyChange` on both paths when HR or steps update, at most every 3 s
while the screen is on and every 30 s when it is off; re-read rather than count callbacks.

Limitations: values are empty until WatchLink has run since boot (the provider does not start the service); the HR
history is in memory only.

## Phone data provider: `content://org.circa.watchlink.data/…`

Access (`CallerPolicy`, by calling UID, because the apps are signed with different keys): system (FLAG_SYSTEM)
packages named `org.circa.*`, and WatchLink itself. Everyone else, shell included, gets a `SecurityException`. Every
write calls `notifyChange(uri)`.

| path | rows | columns |
|---|---|---|
| `/status` | 1 | `connected` (0/1), `last_seen_ms` |
| `/weather` | 0–1 | `updated_ms`, `location`, `temp_c`, `hi_c`, `lo_c`, `code`, `text`, `humidity`, `wind_kmh`, `wind_dir`, `uv`, `rain_pct`, `feels_c`, `dew_c`, `pressure_hpa`, `cloud_pct`, `visibility_m`, `sunrise_ms`, `sunset_ms`, `forecast_json` (nullable; daily `[{"day_ms","hi_c","lo_c","code","rain_pct"}]`, up to 7 days starting tomorrow; `code` is an OpenWeatherMap code; whole degrees C). The detail columns come from the v2 reply only and are NULL when Gadgetbridge has no value (`feels_c`/`dew_c` whole °C, `pressure_hpa` hPa, `cloud_pct` percent, `visibility_m` metres, `sunrise_ms`/`sunset_ms` ms since epoch); a later v1 push keeps them, like `forecast_json`. |
| `/music` | 0–1 | `updated_ms`, `state` (`play`/`pause`/`stop`), `artist`, `album`, `track`, `duration_s`, `position_s`, `position_at_ms` (when `position_s` was reported, so the UI can extrapolate) |
| `/calendar` | n | `id`, `title`, `start_ms`, `end_ms`, `all_day` (0/1), `location`, `calendar`, `color` (ARGB int); ordered by `start_ms`; events that ended more than 24 h ago are dropped |
| `/nav` | 0–1 | `updated_ms`, `instruction` (nullable), `distance` (Maps' raw string), `distance_m` (parsed metres as a long, -1 when unparseable), `action` (nullable), `eta` (nullable, an arrival clock time) |

Commands: `ContentResolver.call(uri, method, arg, null)` returns a Bundle with `ok` (boolean) and, on failure, `error`
(`not_connected`, `bad_arg`):

| method | arg |
|---|---|
| `music` | `play`, `pause`, `playpause`, `next`, `previous`, `volumeup`, `volumedown` |
| `findPhone` | `start`, `stop` |

Weather, music and calendar persist across WatchLink restarts (up to 200 calendar events). Consumers treat music as
"Nothing playing" when `updated_ms` is older than 10 minutes and `state` is not `play`. `/music` has no row until
Gadgetbridge has sent something. `connected` is 1 while a Gadgetbridge link is subscribed. `call()` throws
`SecurityException` for a denied caller. `not_connected` means no subscribed link (or the service is not running).
Notifications are coalesced (300 ms).

`/nav` is live-only and never persisted (a route does not survive a restart). It has one row while a route is active:
Gadgetbridge's blank `{"t":"nav"}` clears it, and a state that has not been refreshed for 5 minutes reads as inactive
(a new message revives it). A `/nav` message also buzzes the watch for the coming maneuver (WatchLink's own
vibration: two short pulses when `distance_m` first reaches 150 m for a maneuver, and one longer buzz when the
`action` becomes `finish`), unless theater mode is on.

## Test hook (no phone needed)

```
adb shell am broadcast -n org.circa.watchlink/.DebugGbLineReceiver -a org.circa.watchlink.DEBUG_GB_LINE --es line '<GB line>'
```

The receiver is protected by `android.permission.DUMP`, so only the adb shell can send it; a line starting with `{`
is wrapped in `GB(...)`. It goes through the same parser as Bluetooth input. Examples:

```
--es line 'GB({"t":"weather","v":1,"temp":293,"hi":297,"lo":287,"hum":55,"rain":20,"uv":3,"code":803,"txt":"broken clouds","wind":12.5,"wdir":270,"loc":"Berlin"})'
--es line 'GB({"t":"weather","v":2,"l":"Springfield","c":"light rain","d":"ExoLSFAjY4oHOwEJiydaAHECAEAa4miAu+JowPnhaJjQ4mi0AA8AAxoVEgsJBsJgwBQPCp1kLRQ3BQ=="})'
--es line '{"t":"musicstate","state":"play","position":12}'
--es line '{"t":"calendar","id":5,"type":0,"timestamp":1759650000,"durationInSeconds":3600,"title":"Standup","location":"Room 1","calName":"Work","color":-16776961,"allDay":false}'
--es line '{"t":"nav","instr":"Turn left onto Main St","distance":"0.2 mi","action":"left","eta":"11:55"}'
```

The v2 line is a full reply: a 38-byte current block with every detail the layout above describes (19 °C, H 26 L 11,
72 % humidity, 80 % rain, UV 3.5, code 503, wind 19.3 km/h from 315°, dew 9 °C, 1012.3 hPa, 90 % cloud, 16 km
visibility, sunrise 07:12 UTC, sunset 18:40 UTC, feels-like 15 °C) plus three dry days. WatchLink's host test feeds
this exact line (`PhoneDataTest.DOC_V2_LINE`).

A nav line goes to the running service (which stores the route and runs the turn alert) and is only stored when no
service is running; a blank `{"t":"nav"}` ends the route. `--ez connected true|false` fakes the `/status` link flag
(nothing else) for testing "connected" screens.

`--es sleep_replay night` (or a spec such as `'23:00;W*2;S*30;T;S*10;W*3'`) runs a synthetic night through the sleep
tracker and logs the `act` lines it would send as `OUT.debug ...`; nothing is stored or sent (see `SleepReplay`).
