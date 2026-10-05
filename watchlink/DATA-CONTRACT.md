# WatchLink data contract

WatchLink makes the watch look like a Bangle.js to [Gadgetbridge](https://gadgetbridge.org) on the phone. It receives
Gadgetbridge's messages over Bluetooth LE, keeps the latest state, and serves it to the other Circa apps through
two content providers:

- `content://org.circa.watchlink.health/…`: heart rate and steps, for the launcher's face and tiles;
- `content://org.circa.watchlink.data/…`: phone data (status, weather, media, agenda) and two commands (media
  controls, find my phone), for the launcher and Circa Companion.

This file is the contract between WatchLink (producer) and the apps (consumers).

## Gadgetbridge Bangle.js messages

Confirmed against Gadgetbridge's `BangleJSDeviceSupport.java`. Framing: Gadgetbridge writes `\x10GB({...})\n` (a
JavaScript object literal, parsed by `JsParser`). Phone to watch:

| `t` | fields as Gadgetbridge sends them | notes |
|---|---|---|
| `weather` v1 (`"v":1`) | `temp`, `hi`, `lo` (integer **Kelvin**), `hum`, `rain` (%), `uv`, `code` (OpenWeatherMap), `txt`, `wind` (km/h), `wdir` (deg), `loc` | Pushed whenever Gadgetbridge's weather data updates. **No forecast.** |
| `weather` v2 (`"v":2`) | `l` (location), `c` (condition text), `d` = base64 binary | Sent **only as the answer to a watch request** `{"t":"weather","v":2,"f":true}`. `d` = 38-byte current block (temp/hi/lo as K-273 signed bytes, humidity, rain, uv*10, mapped condition byte, wind*100 u16, wdir u16, dew point, pressure, cloud, visibility, sun/moon times, feels-like), then with `f:true` the hourly arrays (max 25) and the daily arrays (max 7: hi, lo, mapped code, wind, wdir/2, rain; **no dates**). |
| `musicinfo` | `artist`, `album`, `track`, `dur` (s), `c` (track count), `n` (track number) | Sent when the track changes. |
| `musicstate` | `state` (`play`/`pause`/`stop`, `""` when unknown), `position` (s), `shuffle`, `repeat` | Position is a snapshot at send time. |
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

Interpretation: v1 weather has no forecast; the forecast only comes through v2 and only when asked. The v2 daily
arrays have no dates: WatchLink numbers the days from local midnight tomorrow. v2 condition codes are Gadgetbridge's
1-byte mapping, which WatchLink inverts, so `code` is always an OpenWeatherMap code. Calendar `type` 1/2 (sunrise /
sunset pseudo events) are dropped.

### Gadgetbridge settings (phone)

Nothing needs a capabilities reply: WatchLink's `ver` message (`fw`, `hw`) is only shown, Gadgetbridge gates nothing
on it. The Bangle.js device type supports weather, calendar, music info and find phone.

1. **Calendar**: in the device's settings, turn on **Sync calendar** (off by default), grant Gadgetbridge the
   Calendar permission and pick the calendars. Without it Gadgetbridge silently ignores calendar events.
2. **Weather**: Gadgetbridge has no weather source of its own; it needs a weather app that publishes to it (for
   example Breezy Weather with its Gadgetbridge integration). The forecast needs nothing extra (WatchLink asks).
3. **Music**: Gadgetbridge reports the phone's media session; it needs notification access.
4. **Find phone**: no setting on the Bangle.js device type.
5. Keep **"Text as bitmaps" off** (default): with it on, non-ASCII text arrives as images WatchLink does not decode.

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
| `/weather` | 0–1 | `updated_ms`, `location`, `temp_c`, `hi_c`, `lo_c`, `code`, `text`, `humidity`, `wind_kmh`, `wind_dir`, `uv`, `rain_pct`, `forecast_json` (nullable; daily `[{"day_ms","hi_c","lo_c","code","rain_pct"}]`, up to 7 days starting tomorrow; `code` is an OpenWeatherMap code; whole degrees C) |
| `/music` | 0–1 | `updated_ms`, `state` (`play`/`pause`/`stop`), `artist`, `album`, `track`, `duration_s`, `position_s`, `position_at_ms` (when `position_s` was reported, so the UI can extrapolate) |
| `/calendar` | n | `id`, `title`, `start_ms`, `end_ms`, `all_day` (0/1), `location`, `calendar`, `color` (ARGB int); ordered by `start_ms`; events that ended more than 24 h ago are dropped |

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

## Test hook (no phone needed)

```
adb shell am broadcast -n org.circa.watchlink/.DebugGbLineReceiver -a org.circa.watchlink.DEBUG_GB_LINE --es line '<GB line>'
```

The receiver is protected by `android.permission.DUMP`, so only the adb shell can send it; a line starting with `{`
is wrapped in `GB(...)`. It goes through the same parser as Bluetooth input. Examples:

```
--es line 'GB({"t":"weather","v":1,"temp":293,"hi":297,"lo":287,"hum":55,"rain":20,"uv":3,"code":803,"txt":"broken clouds","wind":12.5,"wdir":270,"loc":"Berlin"})'
--es line '{"t":"musicstate","state":"play","position":12}'
--es line '{"t":"calendar","id":5,"type":0,"timestamp":1759650000,"durationInSeconds":3600,"title":"Standup","location":"Room 1","calName":"Work","color":-16776961,"allDay":false}'
```

`--ez connected true|false` fakes the `/status` link flag (nothing else) for testing "connected" screens.
