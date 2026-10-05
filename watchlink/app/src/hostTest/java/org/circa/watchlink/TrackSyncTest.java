package org.circa.watchlink;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host tests for TrackSync: the Bangle.js recorder-track packets as Gadgetbridge's BangleJSActivityTrack consumes them
 * (cnt from 0 with no gap, "erase" first, lines appended verbatim, end packet without a "lines" key).
 */
final class TrackSyncTest {

    /** What GB ends up with in its recorder.log<id>.csv: every lines packet appended verbatim, erase = truncate. */
    static String gbFile(List<? extends Map<String, Object>> packets) {
        StringBuilder f = new StringBuilder();
        for (Map<String, Object> p : packets) {
            if (!p.containsKey("lines")) break;
            String l = (String) p.get("lines");
            if (l.equals("erase")) f.setLength(0); else f.append(l);
        }
        return f.toString();
    }

    /** Encode as the watch sends it and parse back (JsonOut -> Proto), like GB's JSONObject on the other end. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> wire(Map<String, Object> m) {
        String json = JsonOut.encode(m);
        HostTest.check(json.indexOf('\n') < 0 && json.indexOf('\r') < 0, "track: no raw newline on the wire");
        Proto.In in = Proto.classify("GB(" + json + ")");
        HostTest.check(in.kind == Proto.KIND_GB, "track: packet parses back");
        return (Map<String, Object>) in.msg;
    }

    static Map<String, Object> msg(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    static void run() {
        String csv = "Time,Latitude,Longitude,Altitude,Heartrate,Confidence,Source\r\n"
                + "1759600000,52.520008,13.404954,34,120,90,int\n"
                + "1759600001.5,52.520108,13.405054,35,121,90,int\n"
                + "\n"
                + "1759600002,,,,122,80,int\n"
                + "1759600003,52.520308,13.405254,36,,,\n"
                + "1759600004,52.520408,13.405354,36,124,95,int\n"
                + "1759600005,52.520508,13.405454,37,125,95,int";
        List<LinkedHashMap<String, Object>> p = (List<LinkedHashMap<String, Object>>) (List<?>) TrackSync.packets("20261004a", csv);

        // 1 erase + 7 lines / 4 per packet = 2 data packets + 1 end packet
        HostTest.check(p.size() == 4, "track: packet count " + p.size());
        for (int i = 0; i < p.size(); i++) {
            Map<String, Object> w = wire(p.get(i));
            HostTest.check("actTrk".equals(w.get("t")) && "20261004a".equals(w.get("log")), "track: t/log of packet " + i);
            HostTest.check(Long.valueOf(i).equals(w.get("cnt")), "track: cnt " + i + " = " + w.get("cnt"));
        }
        HostTest.check("erase".equals(p.get(0).get("lines")), "track: erase first");
        Map<String, Object> end = wire(p.get(p.size() - 1));
        HostTest.check(!end.containsKey("lines") && end.size() == 3, "track: end packet has no lines key " + end);
        HostTest.check(JsonOut.encode(p.get(p.size() - 1)).equals("{\"t\":\"actTrk\",\"log\":\"20261004a\",\"cnt\":3}"),
                "track: end packet bytes " + JsonOut.encode(p.get(p.size() - 1)));
        for (int i = 1; i < p.size() - 1; i++) {
            String l = (String) wire(p.get(i)).get("lines");
            HostTest.check(l.endsWith("\n") && l.indexOf('\r') < 0, "track: packet " + i + " lines end in \\n");
        }

        // What GB reassembles: the CSV unchanged apart from \r\n -> \n and the blank line dropped; every row has the
        // header's cell count, so GB's parser keeps them all.
        List<Map<String, Object>> parsed = new java.util.ArrayList<>();
        for (LinkedHashMap<String, Object> x : p) parsed.add(wire(x));
        String file = gbFile(parsed);
        String expected = csv.replace("\r\n", "\n").replace("\n\n", "\n") + "\n";
        HostTest.check(file.equals(expected), "track: GB file equals the CSV:\n" + file);
        String[] rows = file.split("\n");
        int cells = rows[0].split(",", -1).length;
        boolean same = true;
        for (String r : rows) same &= r.split(",", -1).length == cells;
        HostTest.check(rows.length == 7 && same, "track: rows keep their cell count (commas intact)");

        // Empty / missing track: erase then the end packet at cnt 1.
        List<LinkedHashMap<String, Object>> e = (List<LinkedHashMap<String, Object>>) (List<?>) TrackSync.packets("20261004b", "");
        HostTest.check(e.size() == 2 && "erase".equals(e.get(0).get("lines")) && !e.get(1).containsKey("lines")
                && Integer.valueOf(1).equals(e.get(1).get("cnt")), "track: empty csv = erase + end");

        // Exactly 4 lines: one data packet.
        HostTest.check(TrackSync.packets("x", "a\nb\nc\nd\n").size() == 3, "track: 4 lines = 1 data packet");

        // listRecs reply, empty and filled
        HostTest.check(JsonOut.encode(TrackSync.listReply(Collections.<String>emptyList())).equals("{\"t\":\"actTrksList\",\"list\":[]}"),
                "track: empty list reply");
        HostTest.check(JsonOut.encode(TrackSync.listReply(Arrays.asList("20261004a", "20261004b")))
                .equals("{\"t\":\"actTrksList\",\"list\":[\"20261004a\",\"20261004b\"]}"), "track: list reply");

        // ids and last as GB sends them
        HostTest.check(TrackSync.idOf(msg("t", "listRecs")).equals("19700101a"), "track: default id");
        HostTest.check(TrackSync.idOf(msg("t", "listRecs", "id", "")).equals("19700101a"), "track: blank id = default");
        HostTest.check(TrackSync.idOf(msg("t", "fetchRec", "id", "20261004a")).equals("20261004a"), "track: id");
        HostTest.check(TrackSync.lastOf(msg("last", "true")) && !TrackSync.lastOf(msg("last", "false"))
                && !TrackSync.lastOf(msg()) && TrackSync.lastOf(msg("last", Boolean.TRUE)), "track: last as string");
        Proto.In fr = Proto.classify("GB({\"t\":\"fetchRec\",\"id\":\"stop\"})");
        HostTest.check(TrackSync.STOP_ID.equals(TrackSync.idOf((Map<String, Object>) (Map<?, ?>) fr.msg)), "track: stop id");
    }
}
