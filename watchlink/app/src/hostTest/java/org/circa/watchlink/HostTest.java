package org.circa.watchlink;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Host-JVM tests for the watch's protocol core. Golden source = the exact bytes Gadgetbridge's own encoder
 * (BangleJSDeviceSupport.jsonToString + uartTxJSON) writes for each case, committed as test data in
 * src/hostTest/resources/gb-golden.tsv (one line per case: name TAB flags TAB hex of GB's bytes TAB expected JSON).
 * Every case: take GB's bytes, cut them into GATT writes of several sizes, reassemble with LineAssembler, parse
 * with Proto/JsParser, and compare with the expected value. The cases themselves are built by {@link #namedCases} and
 * {@link #fuzzCase}; the vectors are regenerated from those with Gadgetbridge's encoder outside this repository.
 *
 * Usage: HostTest --golden gb-golden.tsv [--vectors out.tsv]
 *   (vectors = hex of GB bytes TAB expected ASCII JSON, for the Python harness selftest)
 */
public final class HostTest {
    static int pass = 0, fail = 0;

    /** Seed and sizes of the fuzz cases in the golden file (the first FUZZ_VEC have no doubles). */
    static final long FUZZ_SEED = 20260929L;
    static final int FUZZ_VEC = 1000, FUZZ_ALL = 1200;

    /** One golden vector: GB's bytes for a case and the value the watch must parse back. */
    static final class Golden {
        final String name; final boolean vec; final byte[] bytes; final String exp;
        Golden(String n, boolean v, byte[] b, String e) { name = n; vec = v; bytes = b; exp = e; }
    }
    static final LinkedHashMap<String, Golden> GOLDEN = new LinkedHashMap<>();

    static void loadGolden(String path) throws IOException {
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.US_ASCII)) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t", 4);
            byte[] b = new byte[f[2].length() / 2];
            for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(f[2].substring(2 * i, 2 * i + 2), 16);
            GOLDEN.put(f[0], new Golden(f[0], f[1].contains("v"), b, f[3]));
        }
    }

    static Golden golden(String name) {
        Golden g = GOLDEN.get(name);
        if (g == null) throw new IllegalStateException("no golden vector " + name);
        return g;
    }

    static String hex(byte[] b) {
        StringBuilder hx = new StringBuilder();
        for (byte x : b) hx.append(String.format("%02x", x & 0xff));
        return hx.toString();
    }

    static void check(boolean ok, String what) {
        if (ok) pass++;
        else { fail++; System.out.println("FAIL: " + what); }
    }

    // --- building paired values: GB-side (org.json) and expected (Map/List) ---
    static final class Pair { Object gb; Object exp; Pair(Object g, Object e) { gb = g; exp = e; } }

    static Pair obj(Object... kv) throws Exception {
        JSONObject j = new JSONObject();
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Pair v = wrap(kv[i + 1]);
            j.put((String) kv[i], v.gb);
            if (v.gb != null) m.put((String) kv[i], v.exp);
        }
        return new Pair(j, m);
    }

    static Pair wrap(Object v) {
        if (v instanceof Pair) return (Pair) v;
        if (v instanceof Integer) return new Pair(v, (long) (Integer) v);
        return new Pair(v, v);
    }

    static Pair arr(Object... vs) {
        JSONArray a = new JSONArray();
        ArrayList<Object> l = new ArrayList<>();
        for (Object o : vs) { Pair p = wrap(o); a.put(p.gb); l.add(p.exp); }
        return new Pair(a, l);
    }


    /** Feed bytes in chunks, return parsed lines. chunk<=0 means random sizes 1..130. */
    static List<Proto.In> feed(byte[] bytes, int chunk, Random r) {
        LineAssembler la = new LineAssembler();
        List<Proto.In> out = new ArrayList<>();
        int i = 0;
        while (i < bytes.length) {
            int n = chunk > 0 ? chunk : 1 + r.nextInt(130);
            n = Math.min(n, bytes.length - i);
            byte[] c = new byte[n];
            System.arraycopy(bytes, i, c, 0, n);
            i += n;
            for (String line : la.feed(c)) out.add(Proto.classify(line));
        }
        check(la.pending() == 0, "assembler left bytes pending");
        return out;
    }

    static void roundTrip(String name, int chunk, Random r) {
        Golden g = golden(name);
        byte[] b = g.bytes;
        List<Proto.In> ins = feed(b, chunk, r);
        boolean ok = ins.size() == 1 && ins.get(0).kind == Proto.KIND_GB
                && JsonOut.encode(ins.get(0).msg).equals(g.exp);
        if (!ok) {
            System.out.println("  wire: " + JsonOut.escapeRaw(JsParser.latin1(b, b.length)));
            System.out.println("  want: " + g.exp);
            System.out.println("  got : " + (ins.isEmpty() ? "nothing" : ins.get(0).kind == Proto.KIND_GB
                    ? JsonOut.encode(ins.get(0).msg) : "kind=" + ins.get(0).kind + " " + ins.get(0).error));
        }
        check(ok, name + " (chunk " + chunk + ")");
    }

    static String wire(String name) {
        byte[] b = golden(name).bytes;
        return JsParser.latin1(b, b.length);
    }

    // --- random values ---
    static String randString(Random r) {
        int len = r.nextInt(r.nextInt(8) == 0 ? 450 : 24);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int k = r.nextInt(10);
            char c;
            switch (k) {
                case 0: c = (char) r.nextInt(0x20); break;                  // control chars incl. \0-\7, DLE, XON/XOFF
                case 1: c = (char) ('0' + r.nextInt(10)); break;           // digits right after controls (octal traps)
                case 2: c = (char) (0x80 + r.nextInt(0x80)); break;        // Latin-1 upper half (raw bytes + \xHH)
                case 3: c = (char) (0xC2 + r.nextInt(0xF4 - 0xC2 + 1)); break;
                case 4: c = (char) (0x100 + r.nextInt(0xD800 - 0x100)); break; // BMP above Latin-1
                case 5: {                                                   // surrogate pair
                    int cp = 0x10000 + r.nextInt(0x10FFFF - 0x10000);
                    sb.appendCodePoint(cp);
                    continue;
                }
                case 6: c = "\"\\'/\u007f\u00ad\u0010".charAt(r.nextInt(7)); break;
                default: c = (char) (0x20 + r.nextInt(0x5f));
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static Pair randValue(Random r, int depth, boolean allowDouble) throws Exception {
        int k = r.nextInt(depth > 2 ? 5 : 7);
        switch (k) {
            case 0: return wrap(randString(r));
            case 1: return wrap(randString(r));
            case 2: {
                long v = r.nextInt(3) == 0 ? r.nextLong() : r.nextInt(2000) - 1000;
                return new Pair(v, v);
            }
            case 3: return wrap(r.nextBoolean());
            case 4: {
                if (!allowDouble) return wrap(r.nextInt(100));
                double d = r.nextInt(2) == 0 ? (r.nextInt(20000) - 10000) / 8.0 : r.nextGaussian() * Math.pow(10, r.nextInt(30) - 15);
                return new Pair(d, d);
            }
            case 5: {
                int n = r.nextInt(4);
                Object[] kv = new Object[n * 2];
                for (int i = 0; i < n; i++) { kv[2 * i] = "k" + i + (char) ('a' + r.nextInt(26)); kv[2 * i + 1] = randValue(r, depth + 1, allowDouble); }
                return obj(kv);
            }
            default: {
                int n = r.nextInt(4);
                Object[] vs = new Object[n];
                for (int i = 0; i < n; i++) vs[i] = r.nextInt(8) == 0 ? new Pair(null, null) : randValue(r, depth + 1, allowDouble);
                return arr(vs);
            }
        }
    }

    /** The named protocol cases (test plan P1-P12 and friends); [names] and [cases] are filled in parallel. */
    static void namedCases(List<String> names, List<Pair> named) throws Exception {
        names.add("wire.gps"); named.add(obj("t", "is_gps_active"));
        names.add("wire.octal"); named.add(obj("t", "notify", "id", 8, "src", "Chat", "body", "The code is\u00017 and\u0001 done, plus a much longer tail of text"));
        StringBuilder all = new StringBuilder();
        for (int c = 0; c < 256; c++) all.append((char) c);
        names.add("P1"); named.add(obj("t", "notify", "id", 1234, "src", "Signal", "title", "Zo\u00eb", "body",
                "Gr\u00fc\u00dfe aus K\u00f6ln\n\u041f\u0440\u0438\u0432\u0435\u0442", "sender", "Zo\u00eb", "reply", true));
        names.add("P2"); named.add(obj("t", "notify", "id", 7, "src", "Mail", "title", "\u00e9\u00e8\u00ea\u00eb\u00e0\u00e2", "body", "x"));
        names.add("P3"); named.add(obj("t", "notify", "id", 8, "src", "Chat", "body", "The code is\u00017 and\u0001 done"));
        for (int q = 0; q < 4; q++) { names.add("P4." + q); named.add(obj("t", "notify", "id", 40 + q, "body", all.substring(q * 64, q * 64 + 64))); }
        names.add("P4.all"); named.add(obj("t", "notify", "id", 44, "body", all.toString()));
        names.add("P5a"); named.add(obj("t", "notify", "id", 50, "body", "a\u0010b"));
        names.add("P5b"); named.add(obj("t", "notify", "id", 51, "body", "tab\there\u000bvt\fff\bbs"));
        names.add("P5c"); named.add(obj("t", "notify", "id", 52, "body", "dle then digit \u00107 and \u00101"));
        names.add("P6"); named.add(obj("t", "notify", "id", 60, "body", "\ud83d\ude00 \u65e5\u672c"));
        StringBuilder b400 = new StringBuilder();
        for (int i = 0; i < 400; i++) b400.append((char) ('a' + i % 26));
        names.add("P7"); named.add(obj("t", "notify", "id", 70, "body", b400.toString()));
        names.add("P10"); named.add(obj("t", "notify", "id", 100, "src", "SMS Message", "tel", "+15551234", "body", "hi"));
        names.add("P12.cal-"); named.add(obj("t", "calendar-", "id", arr(1, 2, 3)));
        names.add("P12.alarm"); named.add(obj("t", "alarm", "d", arr(obj("h", 7, "m", 30, "rep", 31, "on", true))));
        names.add("P12.audio"); named.add(obj("t", "audio", "v", 42.5));
        names.add("P12.float"); named.add(obj("t", "x", "a", 1.0E10, "b", -3.5, "c", 2.0, "d", 1.0E-5));
        names.add("act"); named.add(obj("t", "act", "hrm", true, "stp", false, "int", 10));
        names.add("actfetch"); named.add(obj("t", "actfetch", "ts", 1759100000000L));
        names.add("nulls"); named.add(obj("t", "n", "tel", null, "a", arr(1, new Pair(null, null), "x")));
        names.add("empty"); named.add(obj("t", "notify", "id", 1, "title", "", "body", ""));
    }

    /** Fuzz case i, drawn from [r] (seeded with FUZZ_SEED and used in order 0..FUZZ_ALL-1). */
    static Pair fuzzCase(Random r, int i) throws Exception {
        return obj("t", "fuzz", "i", i, "v", randValue(r, 0, i >= FUZZ_VEC), "s", randString(r));
    }

    /** Whether a named case goes into the Python harness vectors (no doubles: Python formats them differently). */
    static boolean vectorCase(String name) {
        return !name.startsWith("P12.audio") && !name.startsWith("P12.float");
    }

    public static void main(String[] args) throws Exception {
        String vectorsOut = null, goldenIn = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--vectors")) vectorsOut = args[++i];
            else if (args[i].equals("--golden")) goldenIn = args[++i];
        }
        if (goldenIn == null) throw new IllegalArgumentException("usage: HostTest --golden gb-golden.tsv [--vectors out.tsv]");
        loadGolden(goldenIn);
        Random r = new Random(20260929L);

        // 1. The checked wire examples from watchlink/README.md ("Protocol") / spec §2.4
        check(wire("wire.gps").equals("\u0010GB({\"t\":\"is_gps_active\"})\n"), "wire is_gps_active");
        check(wire("P2")
                .equals("\u0010GB({\"t\":\"notify\",\"id\":7,\"src\":\"Mail\",\"title\":atob(\"6ejq6+Di\"),\"body\":\"x\"})\n"), "wire atob title");
        check(wire("P1")
                .equals("\u0010GB({\"t\":\"notify\",\"id\":1234,\"src\":\"Signal\",\"title\":\"Zo\\xeb\",\"body\":\"Gr\u00fc\\xdfe aus K\u00f6ln\\n\\u041f\\u0440\\u0438\\u0432\\u0435\\u0442\",\"sender\":\"Zo\\xeb\",\"reply\":true})\n"),
                "wire P1 (raw Latin-1 + \\x + \\u)");
        check(wire("wire.octal")
                .equals("\u0010GB({\"t\":\"notify\",\"id\":8,\"src\":\"Chat\",\"body\":\"The code is\\x017 and\\1 done, plus a much longer tail of text\"})\n"),
                "wire octal vs \\x0");

        // 2. Test-plan P cases through the full path, several chunkings (20 = GB default, 3 = inside escapes, 128 = high MTU)
        List<String> names = new ArrayList<>();
        namedCases(names, new ArrayList<>());
        for (String name : names) {
            for (int chunk : new int[]{20, 3, 1, 128, 0}) roundTrip(name, chunk, r);
        }
        // P2 really takes the atob path; P1 really has raw Latin-1 bytes
        check(wire("P2").contains("atob(\""), "P2 uses atob");
        check(wire("P1").indexOf('\u00fc') >= 0, "P1 has raw 0xFC byte");

        // 3. Two lines in one burst, a leading DLE-less raw line, garbage, then a good line (P9, P11)
        byte[] l1 = golden("P1").bytes, l2 = golden("P2").bytes;
        byte[] garbage = "\u0010g.dump()\nthis is not(JS]\n\u0010GB({\"t\":broken)\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] burst = new byte[garbage.length + l1.length + l2.length];
        System.arraycopy(garbage, 0, burst, 0, garbage.length);
        System.arraycopy(l1, 0, burst, garbage.length, l1.length);
        System.arraycopy(l2, 0, burst, garbage.length + l1.length, l2.length);
        List<Proto.In> ins = feed(burst, 20, r);
        check(ins.size() == 5, "P9/P11 line count " + ins.size());
        if (ins.size() == 5) {
            check(ins.get(0).kind == Proto.KIND_RAW, "g.dump raw");
            check(ins.get(1).kind == Proto.KIND_RAW, "garbage raw");
            check(ins.get(2).kind == Proto.KIND_BAD_GB, "broken GB() reported");
            check(ins.get(3).kind == Proto.KIND_GB && "Zo\u00eb".equals(ins.get(3).msg.get("title")), "P1 after garbage");
            check(ins.get(4).kind == Proto.KIND_GB && "\u00e9\u00e8\u00ea\u00eb\u00e0\u00e2".equals(ins.get(4).msg.get("title")), "P2 after P1");
        }

        // 4. Time line exactly as GB's transmitTime builds it (BJS:1242-1252)
        for (float tz : new float[]{2.0f, -4.0f, 5.5f, 0.0f, -9.5f}) {
            long ts = 1759140000L;
            String cmd = "\u0010setTime(" + ts + ");" + "E.setTimeZone(" + tz + ");"
                    + "(s=>s&&(s.timezone=" + tz + ",require('Storage').write('setting.json',s)))(require('Storage').readJSON('setting.json',1))\n";
            List<Proto.In> t = feed(cmd.getBytes(StandardCharsets.ISO_8859_1), 20, r);
            check(t.size() == 1 && t.get(0).kind == Proto.KIND_TIME && t.get(0).unixSec == ts && t.get(0).tzHours == tz,
                    "time line tz=" + tz);
            if (tz == 2.0f) check(cmd.length() == 155, "time line is 155 bytes (" + cmd.length() + ")");
        }
        // CR before LF and multiple DLEs are stripped
        List<Proto.In> cr = feed("\u0010\u0010GB({\"t\":\"x\"})\r\n".getBytes(StandardCharsets.ISO_8859_1), 5, r);
        check(cr.size() == 1 && cr.get(0).kind == Proto.KIND_GB && "x".equals(cr.get(0).type()), "CR/DLE strip");

        // 5. JsonOut output is ASCII-only, has no XON/XOFF, matches expected escapes
        String jo = JsonOut.encode(obj("t", "notify", "n", "REPLY", "msg", "\u0011\u0013 Zo\u00eb \ud83d\ude00 \"q\" \\ \u007f").exp);
        check(jo.equals("{\"t\":\"notify\",\"n\":\"REPLY\",\"msg\":\"\\u0011\\u0013 Zo\\u00eb \\ud83d\\ude00 \\\"q\\\" \\\\ \\u007f\"}"), "JsonOut escapes: " + jo);
        boolean ascii = true;
        for (char ch : jo.toCharArray()) if (ch < 0x20 || ch > 0x7e) ascii = false;
        check(ascii, "JsonOut ASCII only");

        // 6. Buckets
        check(Buckets.nextBoundary(0) == 600000 && Buckets.nextBoundary(599999) == 600000 && Buckets.nextBoundary(600000) == 1200000, "nextBoundary");
        check(Buckets.lastBoundary(1759140000123L) == 1759140000000L - (1759140000000L % 600000), "lastBoundary");
        Buckets.Record rec = Buckets.Record.fromCsv(new Buckets.Record(1759140600000L, 12, 71, 30).csv());
        check(rec != null && rec.ts == 1759140600000L && rec.steps == 12 && rec.hr == 71 && rec.hrCount == 30, "record csv");

        // 6b. HR acquisition: ends at the first usable reading, or at the hard cap (the wakelock's worst case)
        int LOW = 1, MED = HrWindow.ACCURACY_MEDIUM, HIGH = 3;
        HrWindow hw = new HrWindow(0, 10_000);
        check(!hw.onSample(0, HIGH), "hr window: bpm 0 is not a reading");
        check(!hw.onSample(-1, HIGH), "hr window: negative bpm is not a reading");
        check(!hw.onSample(70, 0), "hr window: accuracy 0 is not usable");
        check(!hw.onSample(70, -1), "hr window: no contact is not usable");
        check(!hw.readingReady() && hw.validSamples() == 0 && hw.lastBpm() == 0,
                "hr window: not ready before a reading");
        check(!hw.onSample(69, LOW), "hr window: a LOW warm-up reading does not end the window");
        check(hw.onSample(71, MED), "hr window: the first MEDIUM+ reading ends the window");
        check(hw.readingReady() && hw.validSamples() == 1 && hw.lastBpm() == 71, "hr window: reading kept");
        check(hw.onSample(72, HIGH), "hr window: further usable readings stay ready");

        HrWindow hwCap = new HrWindow(0, 10_000);
        check(!hwCap.capReached(9_999), "hr window: cap not reached at 9.999 s");
        check(hwCap.capReached(10_000), "hr window: cap reached at 10 s");
        check(!hwCap.readingReady() && hwCap.validSamples() == 0, "hr window: nothing usable -> ends at the cap");
        check(hwCap.maxHoldMs() == 10_000, "hr window: worst case is the cap");
        HrWindow held = new HrWindow(5_000, 10_000);
        check(held.holdMs(12_500) == 7_500, "hr window: the hold is measured from the start");

        // 6b'. NO_CONTACT (off the wrist): the window ends NO_CONTACT_GRACE_MS after the first such event, unless a
        // usable reading arrives inside the grace (the PPG can report no-contact briefly at power-up on the wrist).
        check(HrWindow.NO_CONTACT_GRACE_MS == 3_000L, "hr window: 3 s no-contact grace");
        HrWindow nc = new HrWindow(0, 12_000);
        check(!nc.noContactSeen() && nc.noContactDeadlineMs() == HrWindow.NO_DEADLINE,
                "hr window: no deadline before a NO_CONTACT event");
        check(!nc.onNoContact(0), "hr window: the first NO_CONTACT event starts the grace, no immediate end");
        check(nc.noContactSeen() && nc.noContactDeadlineMs() == 3_000L,
                "hr window: the deadline is the grace after the first NO_CONTACT event");
        check(!nc.shouldEnd(2_999) && !nc.noContactTimedOut(2_999),
                "hr window: the wakelock is still held inside the grace");
        check(nc.shouldEnd(3_000) && nc.noContactTimedOut(3_000),
                "hr window: 3 s of NO_CONTACT ends the window");
        check(!nc.readingReady() && nc.validSamples() == 0 && nc.lastBpm() == 0,
                "hr window: a no-contact end measured nothing (GB hrm = 0)");

        HrWindow ncRead = new HrWindow(0, 12_000);
        check(!ncRead.onNoContact(400), "hr window: a brief NO_CONTACT does not end the window");
        check(!ncRead.shouldEnd(3_399), "hr window: a reading may still arrive inside the grace");
        check(ncRead.onSample(72, MED), "hr window: a usable reading inside the grace ends the window");
        check(ncRead.readingReady() && !ncRead.noContactTimedOut(3_400),
                "hr window: the reading wins over the grace");
        ncRead.onNoContact(4_000);
        check(ncRead.noContactDeadlineMs() == 3_400L, "hr window: later NO_CONTACT events do not move the deadline");

        HrWindow ncCap = new HrWindow(0, 12_000);
        check(!ncCap.shouldEnd(11_999) && ncCap.shouldEnd(12_000),
                "hr window: the cap still ends a window with no reading and no NO_CONTACT");

        // 6c. Notification alert policy: dedupe by id + one alert per window (nothing here turns the screen on)
        NotifyPolicy np = new NotifyPolicy();
        check(np.decide(7, "Mom", "call me", 0), "notify: first post alerts");
        check(!np.decide(7, "Mom", "call me", 1_000), "notify: identical re-send is silent");
        check(!np.decide(7, "Mom", "call me", 60_000), "notify: identical re-send still silent after the window");
        check(np.decide(7, "Mom", "call me back", 60_000), "notify: changed body alerts");
        check(!np.decide(7, "Mom", "call me back", 61_000), "notify: changed content re-sent identical is silent");
        check(np.decide(8, "Mom", "call me", 70_000), "notify: a different id alerts after the window");

        NotifyPolicy npBurst = new NotifyPolicy();
        int alerts = 0;
        for (int i = 0; i < 10; i++) if (npBurst.decide(100 + i, "App", "msg " + i, i * 1_000L)) alerts++;
        check(alerts == 1, "notify: burst of 10 in 10 s -> 1 alert, got " + alerts);
        check(npBurst.decide(200, "App", "after the window", 10_000), "notify: alerts again once the window elapses");
        check(!npBurst.decide(201, "App", "next", 10_500), "notify: window restarts after that alert");
        check(npBurst.decide(202, "App", "next", 20_000), "notify: alerts again in the next window");

        NotifyPolicy seq = new NotifyPolicy();
        check(seq.decide(1, "A", "x", 0), "notify: first A alerts");
        check(!seq.decide(1, "A", "y", 1_000), "notify: content change inside the window is silent");
        check(!seq.decide(1, "A", "y", 2_000), "notify: the silently-updated content is not re-alerted");
        check(seq.decide(1, "A", "z", 11_000), "notify: content change after the window alerts");

        NotifyPolicy nulls = new NotifyPolicy();
        check(nulls.decide(3, null, null, 0), "notify: null head/text alerts once");
        check(!nulls.decide(3, null, null, 1_000), "notify: identical nulls stay silent");
        check(nulls.decide(3, "", "", 10_000), "notify: empty differs from null and alerts after the window");
        check(!nulls.decide(3, "", "", 10_500), "notify: identical empty stays silent");

        NotifyPolicy evict = new NotifyPolicy();
        check(evict.decide(0, "T", "B", 0), "notify: eviction probe alerts");
        for (int i = 1; i <= NotifyPolicy.MAX_TRACKED; i++) evict.decide(i, "T", "B", 0);   // pushes id 0 out
        check(evict.decide(0, "T", "B", 10_000), "notify: an evicted id is treated as new");

        // 6d. HealthSnapshot: HR staleness + history trimming + steps since local midnight (the launcher provider)
        final long MIN = 60_000L, DAY = 24 * 60 * MIN;
        HealthSnapshot hs = new HealthSnapshot();
        check(hs.latest(0) == null && hs.history(0).isEmpty(), "health: empty snapshot has no HR");
        hs.addHr(0, 70);
        hs.addHr(4 * MIN, 72);
        check(hs.latest(4 * MIN).bpm == 72 && hs.latest(4 * MIN).timeMs == 4 * MIN, "health: newest reading wins");
        check(hs.latest(4 * MIN + HealthSnapshot.HR_MAX_AGE_MS).bpm == 72, "health: fresh at exactly 15 min");
        check(hs.latest(4 * MIN + HealthSnapshot.HR_MAX_AGE_MS + 1) == null, "health: stale past 15 min -> null");

        HealthSnapshot inv = new HealthSnapshot();
        inv.addHr(0, 0);
        inv.addHr(0, -5);
        check(inv.latest(0) == null, "health: non-positive bpm is not a reading");

        HealthSnapshot hist = new HealthSnapshot();
        hist.addHr(0, 60);
        hist.addHr(10 * MIN, 61);
        hist.addHr(70 * MIN, 62);
        List<HealthSnapshot.Sample> hl = hist.history(70 * MIN);
        check(hl.size() == 2 && hl.get(0).bpm == 61 && hl.get(1).bpm == 62 && hl.get(1).timeMs == 70 * MIN,
                "health: history trims samples older than 60 min");
        check(hist.history(70 * MIN).size() == 2, "health: trimming does not drop the boundary sample");

        HealthSnapshot cap = new HealthSnapshot();
        for (int i = 0; i < HealthSnapshot.MAX_SAMPLES + 10; i++) cap.addHr(i, 60 + (i % 30));
        check(cap.history(HealthSnapshot.MAX_SAMPLES + 10L).size() == HealthSnapshot.MAX_SAMPLES,
                "health: ring is capped at MAX_SAMPLES");

        check(HealthSnapshot.localMidnight(0, 0) == 0L, "health: localMidnight at the epoch");
        check(HealthSnapshot.localMidnight(DAY + 1_000, 0) == DAY, "health: localMidnight inside a day");
        check(HealthSnapshot.localMidnight(DAY - 3 * 3_600_000L, 2 * 3_600_000) == -2 * 3_600_000L,
                "health: localMidnight uses the tz offset (east)");
        check(HealthSnapshot.localMidnight(DAY - 1_000, 2 * 3_600_000) == DAY - 2 * 3_600_000L,
                "health: one second shy of local midnight stays on the current local day");
        check(HealthSnapshot.localMidnight(1_000, -5 * 3_600_000) == -DAY + 5 * 3_600_000L,
                "health: localMidnight with a negative (west) offset");

        HealthSnapshot st = new HealthSnapshot();
        check(st.stepsToday() == null && st.stepsTimeMs() == null, "health: no step value before an observation");
        check(st.updateSteps(0, -1, 0) == null, "health: unknown step counter (-1) yields null");
        long t2350 = DAY - 10 * MIN, t2352 = DAY - 8 * MIN, t0005 = DAY + 5 * MIN, t0010 = DAY + 10 * MIN;
        check(st.updateSteps(t2350, 1000, 0) == 0L, "health: first observation of the day is 0");
        check(st.updateSteps(t2352, 1200, 0) == 200L, "health: steps accumulate within the day");
        check(st.updateSteps(t0005, 1210, 0) == 0L, "health: a new local day rebases steps to 0");
        check(st.updateSteps(t0010, 1250, 0) == 40L, "health: steps accumulate in the new day");
        check(st.stepsTimeMs() == t0010, "health: steps timestamp is the last observation");

        HealthSnapshot rb = new HealthSnapshot();
        rb.updateSteps(0, 5000, 0);
        check(rb.updateSteps(MIN, 30, 0) == 0L, "health: a counter reset (reboot) rebases to 0");
        check(rb.updateSteps(2 * MIN, 55, 0) == 25L, "health: counts from the reset value");

        HealthSnapshot rs = new HealthSnapshot();
        rs.restoreSteps(0L, 800L);
        check(rs.stepsToday() == null, "health: a restored baseline has no value until the next update");
        check(rs.updateSteps(3 * MIN, 950L, 0) == 150L, "health: a restored baseline survives a restart");

        // 6e. 5-minute HR sample schedule + live-HR (screen/theater) policy + provider notify throttle
        HrSampleSchedule sched = new HrSampleSchedule();
        final long HR5 = HrSampleSchedule.HR_SAMPLE_INTERVAL_MS;
        check(HR5 == 5 * MIN, "hr schedule: 5-minute interval");
        check(Buckets.BUCKET_MS % HR5 == 0, "hr schedule: 10-min buckets are a whole number of samples");
        check(sched.nextAt(0) == HR5, "hr schedule: next grid after the epoch");
        check(sched.nextAt(HR5 - 1) == HR5, "hr schedule: just before a grid point");
        check(sched.nextAt(HR5) == 2 * HR5, "hr schedule: a grid point itself is not the next sample");
        check(sched.nextAt(12 * MIN + 30_000) == 15 * MIN, "hr schedule: 12:02:30 -> 12:05:00");
        check(sched.dueAt(HR5 - 60_000, 0) == HR5, "hr schedule: no pending sample -> next grid");
        check(sched.dueAt(HR5 + 200, HR5) == 2 * HR5, "hr schedule: a due sample is not repeated");
        check(sched.dueAt(HR5 - 62 * MIN, HR5) == HR5, "hr schedule: a backwards clock jump keeps the pending sample");
        check(sched.nextAt(Buckets.BUCKET_MS - 1) == Buckets.BUCKET_MS,
                "hr schedule: every bucket boundary is also a sample point");

        LiveHrPolicy live = new LiveHrPolicy();
        check(!live.wanted(), "live hr: off before the first transition");
        check(live.setScreenOn(true), "live hr: screen on -> live");
        check(!live.setScreenOn(false), "live hr: screen off -> off");
        check(live.setScreenOn(true), "live hr: screen on again -> live");
        check(!live.setTheaterMode(true), "live hr: theater mode suppresses live HR");
        check(!live.setScreenOn(false), "live hr: screen off in theater mode stays off");
        check(!live.setScreenOn(true), "live hr: screen on in theater mode is still suppressed");
        check(live.setTheaterMode(false), "live hr: leaving theater mode with the screen on resumes");
        check(!live.setScreenOn(false), "live hr: screen off after theater mode -> off");

        NotifyThrottle thr = new NotifyThrottle();
        check(NotifyThrottle.BACKGROUND_INTERVAL_MS == 30_000L && NotifyThrottle.INTERACTIVE_INTERVAL_MS == 3_000L,
                "notify throttle: intervals are 30 s and 3 s");
        check(thr.intervalMs(false) == 30_000L && thr.intervalMs(true) == 3_000L,
                "notify throttle: 30 s idle / 3 s interactive");
        check(thr.shouldNotify(-NotifyThrottle.BACKGROUND_INTERVAL_MS, 0, false), "notify throttle: first update passes");
        check(!thr.shouldNotify(0, 29_999, false), "notify throttle: 29.999 s idle is too soon");
        check(thr.shouldNotify(0, 30_000, false), "notify throttle: 30 s idle passes");
        check(thr.shouldNotify(0, 3_000, true), "notify throttle: 3 s while interactive passes");
        check(!thr.shouldNotify(0, 2_999, true), "notify throttle: 2.999 s interactive is too soon");
        check(!thr.shouldNotify(0, 3_000, false), "notify throttle: 3 s idle is still too soon");
        check(thr.shouldNotify(0, 29_999, true), "notify throttle: interactive interval applies independently");

        // 6f. One wake per acquisition: the bucket close rides the sample's wakelock when the grids coincide
        final long B = Buckets.BUCKET_MS;
        WakePlan both = SampleWake.plan(B, 0, B, B);
        check(both.getBucketEndMs() == B && both.getSampleDue(), "wake plan: a boundary closes the bucket and samples HR");
        check(both.batched() && both.anythingDue(), "wake plan: the bucket close is batched into the sample wake");
        WakePlan five = SampleWake.plan(B + HR5, B, 2 * B, B + HR5);
        check(five.getBucketEndMs() == SampleWake.NO_BUCKET && five.getSampleDue() && !five.batched(),
                "wake plan: a 5-minute sample alone closes no bucket");
        check(five.anythingDue(), "wake plan: the sample alone is still work");
        WakePlan early = SampleWake.plan(B - 1_200, 0, B, B);
        check(!early.anythingDue(), "wake plan: an early alarm has nothing to do");
        WakePlan dup = SampleWake.plan(B + 300, B, 2 * B, B + HR5);
        check(!dup.anythingDue(), "wake plan: a closed bucket is not closed twice");
        WakePlan lateOnly = SampleWake.plan(B + 400, 0, B, 2 * B);
        check(lateOnly.getBucketEndMs() == B && !lateOnly.getSampleDue() && !lateOnly.batched(),
                "wake plan: a desynced bucket close does not open a sample");

        // 6g. Phone data (weather / music / calendar for the Circa apps) + provider caller policy
        PhoneDataTest.run();
        TrackSyncTest.run();
        BleNameTest.run();
        NotifIconTest.run();

        // 6h. System time zone from the phone's UTC offset (GB setTime / E.setTimeZone)
        TzPolicyTest.run();

        // 7. Fuzz: random values as GB encoded them, random chunking
        int fuzz = 0;
        for (Golden g : GOLDEN.values()) {
            if (!g.name.startsWith("fuzz#")) continue;
            roundTrip(g.name, 0, r);
            fuzz++;
            if (fail > 20) break;
        }
        check(fuzz == FUZZ_ALL, "golden file has " + FUZZ_ALL + " fuzz cases (" + fuzz + ")");
        if (vectorsOut != null) {
            try (Writer vw = new OutputStreamWriter(new FileOutputStream(vectorsOut), StandardCharsets.US_ASCII)) {
                for (Golden g : GOLDEN.values()) if (g.vec && g.name.startsWith("fuzz#")) vw.write(hex(g.bytes) + "\t" + g.exp + "\n");
                for (Golden g : GOLDEN.values()) if (g.vec && !g.name.startsWith("fuzz#")) vw.write(hex(g.bytes) + "\t" + g.exp + "\n");
            }
        }

        System.out.println("HostTest: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
