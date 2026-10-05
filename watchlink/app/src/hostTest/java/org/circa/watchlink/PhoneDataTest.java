package org.circa.watchlink;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Host tests for the phone-data model (PhoneData / CallerPolicy): message shapes as Gadgetbridge's BangleJSDeviceSupport emits them. */
final class PhoneDataTest {
    static final long NOW = 1_759_600_000_000L;   // 2025-10-04T17:46:40Z

    @SuppressWarnings("unchecked")
    static int feed(PhoneData d, String json, long now) {
        Proto.In in = Proto.classify("GB(" + json + ")");
        HostTest.check(in.kind == Proto.KIND_GB, "phone: parses " + json.substring(0, Math.min(30, json.length())));
        return d.apply((Map<String, Object>) in.msg, now, 0);
    }

    static void b1(ByteArrayOutputStream o, int v) { o.write((byte) v); }
    static void b2(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >>> 8); }
    static void b4(ByteArrayOutputStream o, int v) { b2(o, v); b2(o, v >>> 16); }

    static void run() {
        PhoneData d = new PhoneData();

        // weather v1 (handleWeatherV1): Kelvin ints
        int ch = feed(d, "{t:\"weather\",v:1,temp:293,hi:297,lo:287,hum:55,rain:20,uv:3,code:803,txt:\"broken clouds\",wind:12.5,wdir:270,loc:\"Berlin\"}", NOW);
        PhoneData.Weather w = d.weather;
        HostTest.check(ch == PhoneData.CH_WEATHER && w != null, "weather v1 changes weather");
        HostTest.check(w.tempC == 20 && w.hiC == 24 && w.loC == 14, "weather v1 Kelvin -> C (" + w.tempC + "/" + w.hiC + "/" + w.loC + ")");
        HostTest.check(w.code == 803 && "broken clouds".equals(w.text) && "Berlin".equals(w.location) && w.humidity == 55
                && w.windKmh == 12.5 && w.windDir == 270 && w.rainPct == 20 && w.uv == 3.0 && w.updatedMs == NOW && w.forecastJson == null,
                "weather v1 fields");

        // weather v2 with forecast (handleWeatherV2): current block, 2 hourly entries, 2 daily entries
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b1(o, 19); b1(o, 24); b1(o, 12);         // temp, hi, lo (K-273)
        o.write(60); o.write(30); o.write(25);   // humidity, rain, uv*10
        o.write(96 + 1);                         // mapped code: rain 501
        b2(o, 1500); b2(o, 90);                  // wind*100, wdir
        b1(o, 8); b2(o, 10000); o.write(40); b4(o, 0);   // dew, pressure, cloud, visibility
        b4(o, 1); b4(o, 2); b4(o, 3); b4(o, 4);  // sun/moon times
        b2(o, 7); b1(o, 18);                     // moon phase, feels like
        HostTest.check(o.size() == 38, "v2 fixture current block is 38 bytes (" + o.size() + ")");
        o.write(2); b4(o, 1759600000);           // 2 hourly, first timestamp
        for (int i = 0; i < 2; i++) o.write(i * 10);   // hours delta
        for (int i = 0; i < 2; i++) b1(o, 15);          // temp
        for (int i = 0; i < 2; i++) o.write(192);       // code
        for (int i = 0; i < 2; i++) o.write(5);         // wind
        for (int i = 0; i < 2; i++) o.write(45);        // wdir/2
        for (int i = 0; i < 2; i++) o.write(10);        // rain
        o.write(2);                                     // 2 daily
        b1(o, 21); b1(o, 17);                           // max
        b1(o, 9); b1(o, -3);                            // min
        o.write(192); o.write(128 + 1);                 // code: clear 800, snow 601
        o.write(7); o.write(8);                         // wind
        o.write(10); o.write(11);                       // wdir/2
        o.write(5); o.write(80);                        // rain
        String b64 = Base64.getEncoder().encodeToString(o.toByteArray());
        long t2 = NOW + 60_000;
        ch = feed(d, "{t:\"weather\",v:2,l:\"Berlin\",c:\"light rain\",d:\"" + b64 + "\"}", t2);
        w = d.weather;
        HostTest.check(ch == PhoneData.CH_WEATHER && w.tempC == 19 && w.hiC == 24 && w.loC == 12 && w.code == 501
                && w.humidity == 30 + 30 && w.rainPct == 30 && w.uv == 2.5 && w.windKmh == 15.0 && w.windDir == 90
                && "light rain".equals(w.text) && w.updatedMs == t2,
                "weather v2 current block (" + w.tempC + "," + w.code + "," + w.humidity + "," + w.windKmh + ")");
        // tomorrow 00:00 UTC (offset 0): next multiple of a day after t2
        long tomorrow = (t2 / 86_400_000L + 1) * 86_400_000L;
        String exp = "[{\"day_ms\":" + tomorrow + ",\"hi_c\":21,\"lo_c\":9,\"code\":800,\"rain_pct\":5},{\"day_ms\":" + (tomorrow + 86_400_000L)
                + ",\"hi_c\":17,\"lo_c\":-3,\"code\":601,\"rain_pct\":80}]";
        HostTest.check(exp.equals(w.forecastJson), "weather v2 daily forecast: " + w.forecastJson);
        // a later v1 push keeps the forecast
        feed(d, "{t:\"weather\",v:1,temp:294,hi:297,lo:287,hum:55,rain:20,uv:3,code:800,txt:\"clear\",wind:1,wdir:1,loc:\"Berlin\"}", t2 + 1);
        HostTest.check(d.weather.tempC == 21 && exp.equals(d.weather.forecastJson), "weather v1 push keeps the v2 forecast");
        HostTest.check(feed(d, "{t:\"weather\",v:2,d:\"AAAA\"}", t2) == 0, "weather v2 short payload ignored");
        HostTest.check(PhoneData.owmCode(255) == 0 && PhoneData.owmCode(192 + 4) == 804 && PhoneData.owmCode(32) == 300
                && PhoneData.owmCode(9) == 232 && PhoneData.owmCode(160 + 9) == 781 && PhoneData.owmCode(99) == 503,
                "owmCode inverse mapping");

        // music
        feed(d, "{t:\"musicstate\",state:\"play\",position:12,shuffle:0,repeat:0}", NOW);
        feed(d, "{t:\"musicinfo\",artist:\"Daft Punk\",album:\"Discovery\",track:\"Digital Love\",dur:301,c:14,n:3}", NOW + 1000);
        PhoneData.Music mu = d.music;
        HostTest.check("play".equals(mu.state) && "Daft Punk".equals(mu.artist) && "Digital Love".equals(mu.track)
                && mu.durationS == 301 && mu.positionS == 12 && mu.positionAtMs == NOW && mu.updatedMs == NOW + 1000,
                "music info merges with state");
        feed(d, "{t:\"musicstate\",state:\"pause\",position:40}", NOW + 5000);
        HostTest.check("pause".equals(d.music.state) && d.music.positionS == 40 && d.music.positionAtMs == NOW + 5000
                && "Discovery".equals(d.music.album), "music state keeps the track");
        feed(d, "{t:\"musicstate\",state:\"\",position:0}", NOW + 6000);
        HostTest.check("stop".equals(d.music.state), "music unknown state -> stop");

        // calendar
        feed(d, "{t:\"calendar\",id:5,type:0,timestamp:1759650000,durationInSeconds:3600,title:\"Standup\",description:\"d\",location:\"Room 1\",calName:\"Work\",color:-16776961,allDay:false}", NOW);
        feed(d, "{t:\"calendar\",id:4,type:0,timestamp:1759640000,durationInSeconds:86400,title:\"Holiday\",location:\"\",calName:\"Home\",color:255,allDay:true}", NOW);
        feed(d, "{t:\"calendar\",id:9,type:1,timestamp:1759650000,durationInSeconds:0,title:\"Sunrise\"}", NOW);
        List<PhoneData.Event> ev = d.calendar(NOW);
        HostTest.check(ev.size() == 2 && ev.get(0).id == 4 && ev.get(1).id == 5, "calendar ordered by start, sunrise type skipped");
        HostTest.check(ev.get(1).startMs == 1759650000_000L && ev.get(1).endMs == 1759653600_000L && !ev.get(1).allDay
                && "Room 1".equals(ev.get(1).location) && "Work".equals(ev.get(1).calendar) && ev.get(1).color == -16776961L && ev.get(0).allDay,
                "calendar fields");
        feed(d, "{t:\"calendar\",id:5,type:0,timestamp:1759650000,durationInSeconds:1800,title:\"Standup 2\",calName:\"Work\",color:1,allDay:false}", NOW);
        HostTest.check(d.calendar(NOW).size() == 2 && "Standup 2".equals(d.calendar(NOW).get(1).title) && d.calendar(NOW).get(1).endMs == 1759651800_000L,
                "calendar same id replaces");
        HostTest.check(d.calendarIds().equals(Arrays.asList(5L, 4L)) || d.calendarIds().containsAll(Arrays.asList(4L, 5L)), "calendar ids");
        HostTest.check(feed(d, "{t:\"calendar-\",id:4}", NOW) == PhoneData.CH_CALENDAR && d.calendar(NOW).size() == 1, "calendar- single id");
        feed(d, "{t:\"calendar\",id:6,type:0,timestamp:1759660000,durationInSeconds:60,title:\"a\",allDay:false}", NOW);
        feed(d, "{t:\"calendar\",id:7,type:0,timestamp:1759670000,durationInSeconds:60,title:\"b\",allDay:false}", NOW);
        HostTest.check(feed(d, "{t:\"calendar-\",id:[6,7,99]}", NOW) == PhoneData.CH_CALENDAR && d.calendar(NOW).size() == 1, "calendar- id array");
        HostTest.check(feed(d, "{t:\"calendar-\",id:99}", NOW) == 0, "calendar- unknown id is no change");
        // past events older than 24 h are dropped
        feed(d, "{t:\"calendar\",id:20,type:0,timestamp:" + ((NOW - 30 * 3600_000L) / 1000) + ",durationInSeconds:3600,title:\"old\",allDay:false}", NOW);
        HostTest.check(d.calendar(NOW).size() == 1, "event that ended 29 h ago is dropped");
        feed(d, "{t:\"calendar\",id:21,type:0,timestamp:" + ((NOW - 10 * 3600_000L) / 1000) + ",durationInSeconds:3600,title:\"recent\",allDay:false}", NOW);
        HostTest.check(d.calendar(NOW).size() == 2, "event that ended 9 h ago is kept");
        // 200 cap
        PhoneData big = new PhoneData();
        for (int i = 0; i < 250; i++) feed(big, "{t:\"calendar\",id:" + i + ",type:0,timestamp:" + (NOW / 1000 + 100L * i) + ",durationInSeconds:60,title:\"e" + i + "\",allDay:false}", NOW);
        List<PhoneData.Event> be = big.calendar(NOW);
        HostTest.check(be.size() == 200 && be.get(0).id == 50 && be.get(199).id == 249, "calendar capped at 200 (drops earliest): " + be.size());

        // not data
        HostTest.check(feed(d, "{t:\"find\",n:true}", NOW) == 0 && feed(d, "{t:\"notify\",id:1}", NOW) == 0, "non-data messages change nothing");

        // persistence round trip
        String js = d.toJson();
        PhoneData r = new PhoneData();
        r.load(js);
        HostTest.check(js.equals(r.toJson()), "persist round trip is stable");
        HostTest.check(r.weather != null && exp.equals(r.weather.forecastJson) && r.music != null && "stop".equals(r.music.state)
                && r.calendar(NOW).size() == 2, "persist round trip keeps weather, music, calendar");
        PhoneData junk = new PhoneData();
        junk.load("not json");
        HostTest.check(junk.weather == null && junk.music == null && junk.calendar(NOW).isEmpty(), "unreadable persisted state -> empty");

        // caller policy
        List<String> none = Arrays.asList();
        HostTest.check(CallerPolicy.allowed(Arrays.asList("org.circa.weather"), Arrays.asList(true), "org.circa.watchlink"), "caller: system org.circa.*");
        HostTest.check(CallerPolicy.allowed(Arrays.asList("org.circa.launcher"), Arrays.asList(true), "org.circa.watchlink"), "caller: system launcher");
        HostTest.check(CallerPolicy.allowed(Arrays.asList("org.circa.watchlink"), Arrays.asList(false), "org.circa.watchlink"), "caller: self");
        HostTest.check(!CallerPolicy.allowed(Arrays.asList("com.android.shell"), Arrays.asList(true), "org.circa.watchlink"), "caller: shell denied");
        HostTest.check(!CallerPolicy.allowed(Arrays.asList("org.circa.evil"), Arrays.asList(false), "org.circa.watchlink"), "caller: non-system org.circa.* denied");
        HostTest.check(!CallerPolicy.allowed(Arrays.asList("org.circacafe.x"), Arrays.asList(true), "org.circa.watchlink"), "caller: prefix needs the dot");
        HostTest.check(!CallerPolicy.allowed(none, none.isEmpty() ? Arrays.asList() : Arrays.asList(), "org.circa.watchlink"), "caller: unknown uid denied");
    }
}
