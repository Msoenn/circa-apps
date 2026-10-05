# Circa Companion (`org.circa.companion`)

Five app-list entries for the phone side of the watch: **Media** (track, play/pause, previous/next, the crown turns
the phone volume), **Weather** (current conditions, today's high/low, the forecast), **Agenda** (today and the next
days), **Find phone** and **Flashlight** (a white screen at full brightness). All but the flashlight read WatchLink's
phone-data provider ([data contract](../watchlink/DATA-CONTRACT.md)), which only answers system `org.circa.*`
packages. No permissions, no INTERNET.

Build: `../build-all.sh --only companion`; the parsing and grouping logic is unit-tested.
