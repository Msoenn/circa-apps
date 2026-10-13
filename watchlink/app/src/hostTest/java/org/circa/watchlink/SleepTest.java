package org.circa.watchlink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Host tests for sleep tracking: the movement measures (MotionEpochs, WornClock), the bucket classifier and the
 * hold-back state machine (SleepModel, SleepTracker) on synthetic nights (SleepReplay specs).
 *
 * The Gadgetbridge side is checked as stated rules, not as code: GB stores a sample's kind once (its Bangle.js upsert
 * never changes it), counts LIGHT_SLEEP / DEEP_SLEEP / REM_SLEEP as sleep and AWAKE_SLEEP as awake time inside a
 * session, and ends a sleep session at a non-sleep sample with steps. So: every bucket is sent exactly once, with its
 * final kind; no unlabelled bucket with steps falls between two sleep buckets of one night; and no bucket is held
 * longer than SleepModel.MAX_HOLD_MIN.
 */
final class SleepTest {
    static final long B = Buckets.BUCKET_MS;
    static final long DAY = 86_400_000L;
    static final long DAY0 = 1_790_000_000_000L - Math.floorMod(1_790_000_000_000L, DAY);   // a UTC midnight

    /** Local time = UTC in these tests. */
    static int minuteOfDay(long ms) {
        return (int) (Math.floorMod(ms, DAY) / 60_000L);
    }

    static List<Buckets.Record> night(String spec) {
        return SleepReplay.parse(spec, m -> DAY0 + m * 60_000L);
    }

    /** Result of a run: the kind each bucket went out with, and the longest hold seen (ms). */
    static final class Run {
        final List<Labelled> out = new ArrayList<>();
        final Map<Long, String> kinds = new HashMap<>();
        long maxHoldMs = 0;
        boolean duplicate = false;
    }

    static Run run(List<Buckets.Record> recs, int resting) {
        SleepTracker t = new SleepTracker(SleepTest::minuteOfDay);
        Run r = new Run();
        for (Buckets.Record b : recs) collect(r, t.offer(b, resting), b.ts);
        collect(r, t.flush(), recs.get(recs.size() - 1).ts);
        return r;
    }

    static void collect(Run r, List<Labelled> out, long nowTs) {
        for (Labelled l : out) {
            if (r.kinds.containsKey(l.record.ts)) r.duplicate = true;
            r.kinds.put(l.record.ts, l.kind);
            r.out.add(l);
            r.maxHoldMs = Math.max(r.maxHoldMs, nowTs - l.record.ts);
        }
    }

    static int count(Run r, String kind) {
        int n = 0;
        for (String k : r.kinds.values()) if (kind == null ? k == null : kind.equals(k)) n++;
        return n;
    }

    /** The GB rules from the class comment, for any run. */
    static void invariants(String name, List<Buckets.Record> recs, Run r) {
        HostTest.check(!r.duplicate, name + ": no bucket is sent twice");
        HostTest.check(r.kinds.size() == recs.size(), name + ": every bucket is sent (" + r.kinds.size() + "/" + recs.size() + ")");
        HostTest.check(r.maxHoldMs <= SleepModel.MAX_HOLD_MIN * 60_000L,
                name + ": held at most " + SleepModel.MAX_HOLD_MIN + " min (" + r.maxHoldMs / 60_000 + ")");
        boolean ordered = true;
        for (int i = 1; i < r.out.size(); i++) if (r.out.get(i).record.ts <= r.out.get(i - 1).record.ts) ordered = false;
        HostTest.check(ordered, name + ": buckets go out oldest first");
        // Between two LIGHT_SLEEP buckets with only labelled buckets in between, GB sees one night; an unlabelled
        // bucket with steps there would split it. Check that sleep runs never contain one.
        boolean split = false;
        Long lastLight = null;
        boolean stepsSinceLight = false;
        for (Buckets.Record b : recs) {
            String k = r.kinds.get(b.ts);
            if (SleepModel.LIGHT.equals(k)) {
                if (lastLight != null && stepsSinceLight && b.ts - lastLight <= 60 * 60_000L) split = true;
                lastLight = b.ts;
                stepsSinceLight = false;
            } else if (k == null && b.steps > 0) {
                stepsSinceLight = true;
            }
        }
        HostTest.check(!split, name + ": no stepping unlabelled bucket inside a night");
        for (String k : r.kinds.values()) {
            HostTest.check(k == null || k.equals(SleepModel.LIGHT) || k.equals(SleepModel.AWAKE),
                    name + ": only LIGHT_SLEEP / AWAKE_SLEEP labels (" + k + ")");
        }
    }

    static void run() {
        // ---- records: the CSV stays compatible both ways ----
        Buckets.Record legacy = Buckets.Record.fromCsv("1790000400000,12,61,2");
        HostTest.check(legacy != null && legacy.still == -1 && legacy.worn == -1 && !legacy.measured(),
                "sleep csv: an old 4-field row parses as not measured");
        HostTest.check("1790000400000,12,61,2".equals(legacy.csv()), "sleep csv: an unmeasured row is written in the old form");
        Buckets.Record ext = Buckets.Record.fromCsv("1790000400000,0,52,2,9,3,10");
        HostTest.check(ext != null && ext.still == 9 && ext.restless == 3 && ext.worn == 10 && ext.measured(),
                "sleep csv: the 7-field row carries still / restless / worn");
        HostTest.check("1790000400000,0,52,2,9,3,10".equals(ext.csv()), "sleep csv: round trip");
        HostTest.check(ext.at(1790001000000L).still == 9, "sleep csv: snapping to the grid keeps the measures");

        // ---- MotionEpochs ----
        MotionEpochs still = new MotionEpochs();
        long t0 = 1_000_000L;
        for (long t = t0; t < t0 + 10 * 60_000L; t += 640) {
            float n = (float) (0.02 * Math.sin(t / 997.0));     // sensor noise, well under the 5 degree threshold
            still.add(t, 0.3f + n, 0.2f, 9.8f);
        }
        MotionEpochs.Tally ts = still.take(t0 + 10 * 60_000L);
        HostTest.check(ts.still >= 9 && ts.restless == 0, "motion: a still wrist is still (" + ts.still + " still, " + ts.restless + " restless)");

        MotionEpochs moving = new MotionEpochs();
        for (long t = t0; t < t0 + 10 * 60_000L; t += 640) {
            double a = ((t - t0) / 5_000L) % 2 == 0 ? 0 : Math.toRadians(30);   // the arm turns 30 degrees every 5 s
            moving.add(t, (float) (9.8 * Math.sin(a)), 0f, (float) (9.8 * Math.cos(a)));
        }
        MotionEpochs.Tally tm = moving.take(t0 + 10 * 60_000L);
        HostTest.check(tm.still == 0 && tm.restless > 50, "motion: a turning wrist is restless (" + tm.restless + ")");

        MotionEpochs part = new MotionEpochs();
        for (long t = 0; t < 12 * 60_000L; t += 640) part.add(t, 0f, 0f, 9.8f);
        MotionEpochs.Tally first = part.take(10 * 60_000L);
        MotionEpochs.Tally rest = part.take(12 * 60_000L);
        HostTest.check(first.minutes == 10 && rest.minutes == 2, "motion: minutes after the bucket end stay for the next one");

        MotionEpochs sparse = new MotionEpochs();
        for (long t = 0; t < 10 * 60_000L; t += 30_000L) sparse.add(t, 0f, 0f, 9.8f);
        HostTest.check(sparse.take(10 * 60_000L).still == 0, "motion: too few samples is never still");

        // ---- WornClock ----
        WornClock w = new WornClock();
        w.begin(0);
        w.setOnBody(3 * 60_000L, false);
        w.setOnBody(6 * 60_000L, true);
        HostTest.check(w.take(10 * 60_000L) == 7, "worn: 3 minutes off the wrist leave 7");
        w.setCharging(12 * 60_000L, true);
        HostTest.check(w.take(20 * 60_000L) == 2, "worn: charging counts as not worn");
        HostTest.check(new WornClock().take(60_000L) == 0, "worn: nothing counts before begin");

        // ---- classifier ----
        HostTest.check(SleepModel.candidate(new Buckets.Record(B, 0, 52, 2, 10, 0, 10), 50), "classify: still, worn, low HR");
        HostTest.check(!SleepModel.candidate(new Buckets.Record(B, 0, 52, 2), 0), "classify: unmeasured is never sleep");
        HostTest.check(!SleepModel.candidate(new Buckets.Record(B, 0, 52, 2, 10, 0, 5), 0), "classify: half off the wrist");
        HostTest.check(!SleepModel.candidate(new Buckets.Record(B, 0, 52, 2, 6, 20, 10), 0), "classify: restless");
        HostTest.check(!SleepModel.candidate(new Buckets.Record(B, 30, 52, 2, 9, 2, 10), 0), "classify: steps");
        HostTest.check(!SleepModel.candidate(new Buckets.Record(B, 0, 75, 2, 10, 0, 10), 55), "classify: heart rate well above resting");
        HostTest.check(SleepModel.candidate(new Buckets.Record(B, 0, 75, 2, 10, 0, 10), 0), "classify: no resting estimate, no HR check");
        int[] few = new int[20];
        java.util.Arrays.fill(few, 60);
        HostTest.check(SleepModel.restingHr(few) == 0, "resting HR: too few buckets -> no estimate");
        int[] hrs = new int[100];
        for (int i = 0; i < 100; i++) hrs[i] = i < 10 ? 0 : 40 + i;   // zeros are ignored
        HostTest.check(SleepModel.restingHr(hrs) == 58, "resting HR: 10th percentile of the 90 readings 50..139 (" + SleepModel.restingHr(hrs) + ")");
        HostTest.check(SleepModel.onsetMinutes(10 * 60 + 59) == 30 && SleepModel.onsetMinutes(11 * 60) == 45
                && SleepModel.onsetMinutes(19 * 60 + 59) == 45 && SleepModel.onsetMinutes(20 * 60) == 30,
                "onset: 45 min from 11:00 until 20:00, else 30");

        // ---- a normal night ----
        List<Buckets.Record> n = night(SleepReplay.NIGHT);
        Run rn = run(n, SleepModel.restingHr(hrsOf(n)));
        invariants("night", n, rn);
        long sleepStart = DAY0 + 22 * 3_600_000L + 30 * 60_000L;   // first S bucket starts 22:30
        HostTest.check(rn.kinds.get(sleepStart + B) != null && rn.kinds.get(sleepStart + B).equals(SleepModel.LIGHT),
                "night: onset is backdated to the first still bucket");
        HostTest.check(rn.kinds.get(sleepStart) == null, "night: the evening before stays unlabelled");
        HostTest.check(count(rn, SleepModel.LIGHT) == 54, "night: 54 sleep buckets (" + count(rn, SleepModel.LIGHT) + ")");
        HostTest.check(count(rn, SleepModel.AWAKE) == 3, "night: restless spell and bathroom trip are awake-in-bed (" + count(rn, SleepModel.AWAKE) + ")");
        long trip = sleepStart + (36 + 2 + 6 + 1) * B;
        HostTest.check(SleepModel.AWAKE.equals(rn.kinds.get(trip)), "night: the bathroom trip (40 steps) stays inside the night");
        HostTest.check(count(rn, null) == 7, "night: evening and morning go out unlabelled");
        TreeMap<Long, String> labels = new TreeMap<>();
        for (Map.Entry<Long, String> e : rn.kinds.entrySet()) if (e.getValue() != null) labels.put(e.getKey(), e.getValue());
        SleepModel.Session s = SleepModel.lastSession(labels);
        HostTest.check(s != null && s.startMs == sleepStart && s.asleepMin == 540
                        && s.endMs == sleepStart + (36 + 2 + 6 + 1 + 12) * B,
                "night: session 22:30 to the last sleep bucket, 9 h asleep");

        // ---- not sleep ----
        List<Buckets.Record> tv = night("19:30;Q*12;W");
        Run rtv = run(tv, 55);
        invariants("tv", tv, rtv);
        HostTest.check(count(rtv, SleepModel.LIGHT) == 0, "tv: still on the sofa with a high heart rate is not sleep");
        List<Buckets.Record> desk = night("23:00;O*48");
        Run rdesk = run(desk, 55);
        invariants("off wrist", desk, rdesk);
        HostTest.check(count(rdesk, SleepModel.LIGHT) == 0 && rdesk.maxHoldMs == 0, "off wrist: no sleep, nothing held");
        List<Buckets.Record> offMid = night("23:00;S*12;O;S*6");
        Run roff = run(offMid, 55);
        invariants("taken off", offMid, roff);
        HostTest.check(roff.kinds.get(offMid.get(12).ts) == null, "taken off: the off-wrist bucket ends the night unlabelled");

        // ---- onset thresholds ----
        HostTest.check(count(run(night("23:00;S*3;W"), 55), SleepModel.LIGHT) == 3, "onset: 30 min at night is sleep");
        HostTest.check(count(run(night("23:00;S*2;W"), 55), SleepModel.LIGHT) == 0, "onset: 20 min at night is not");
        HostTest.check(count(run(night("14:00;S*4;W"), 55), SleepModel.LIGHT) == 0, "nap: 40 min by day is not sleep");
        List<Buckets.Record> nap = night("14:00;W;S*5;W*2");
        Run rnap = run(nap, 55);
        invariants("nap", nap, rnap);
        HostTest.check(count(rnap, SleepModel.LIGHT) == 5, "nap: 50 min by day is sleep");

        // ---- wake periods ----
        Run longWake = run(night("23:00;S*6;R*6;S*3"), 55);
        HostTest.check(count(longWake, SleepModel.AWAKE) == 0 && longWake.kinds.get(DAY0 + 23 * 3_600_000L + 7 * B) == null,
                "wake: an hour awake ends the night");
        HostTest.check(longWake.maxHoldMs <= SleepModel.MAX_HOLD_MIN * 60_000L, "wake: an hour awake is held at most an hour");
        Run walk = run(night("23:00;S*6;250/80/1/60/10;S*3"), 55);
        HostTest.check(walk.kinds.get(DAY0 + 23 * 3_600_000L + 7 * B) == null, "wake: 250 steps end the night at once");

        // ---- a gap in the series ends the night ----
        List<Buckets.Record> gap = new ArrayList<>(night("23:00;S*6"));
        List<Buckets.Record> later = night("23:00;R;S*2");
        for (Buckets.Record b : later) gap.add(b.at(b.ts + 2 * 3_600_000L));
        Run rgap = run(gap, 55);
        invariants("gap", gap, rgap);
        HostTest.check(rgap.kinds.get(gap.get(6).ts) == null, "gap: after 2 h without buckets the night has ended");

        // ---- restart: persist resumeTs, restore, offer the stored buckets after it again ----
        HashSet<Integer> mismatches = new HashSet<>();
        for (int cut = 1; cut < n.size(); cut++) {
            SleepTracker a = new SleepTracker(SleepTest::minuteOfDay);
            int resting = SleepModel.restingHr(hrsOf(n));
            Map<Long, String> got = new HashMap<>();
            for (int i = 0; i < cut; i++) for (Labelled l : a.offer(n.get(i), resting)) got.put(l.record.ts, l.kind);
            SleepTracker b = new SleepTracker(SleepTest::minuteOfDay);
            b.restore(a.getAsleep(), a.resumeTs());
            for (Buckets.Record r : n) {
                if (r.ts <= a.resumeTs()) continue;
                for (Labelled l : b.offer(r, resting)) got.put(l.record.ts, l.kind);
            }
            for (Labelled l : b.flush()) got.put(l.record.ts, l.kind);
            for (Buckets.Record r : n) {
                String k1 = rn.kinds.get(r.ts);
                String k2 = got.get(r.ts);
                if (!got.containsKey(r.ts) || (k1 == null ? k2 != null : !k1.equals(k2))) mismatches.add(cut);
            }
        }
        HostTest.check(mismatches.isEmpty(), "restart: a restart after any bucket gives the same labels " + mismatches);

        // ---- flush (tracking off, workout) releases everything unlabelled ----
        SleepTracker f = new SleepTracker(SleepTest::minuteOfDay);
        List<Buckets.Record> two = night("23:00;S*2");
        for (Buckets.Record b : two) f.offer(b, 55);
        HostTest.check(f.heldCount() == 2 && f.heldFrom() == two.get(0).ts, "flush: two candidate buckets are held");
        List<Labelled> fl = f.flush();
        HostTest.check(fl.size() == 2 && fl.get(0).kind == null && f.heldCount() == 0 && !f.getAsleep(),
                "flush: held buckets go out unlabelled");

        // ---- spec parsing ----
        boolean threw = false;
        try { night("25:00;S"); } catch (IllegalArgumentException e) { threw = true; }
        HostTest.check(threw, "replay: a bad start time is rejected");
        List<Buckets.Record> p = night("23:00;0/50/10/0/10;S*2");
        HostTest.check(p.size() == 3 && p.get(0).ts == DAY0 + 23 * 3_600_000L + B && p.get(2).ts - p.get(0).ts == 2 * B,
                "replay: buckets end on the 10-minute grid after the start");
    }

    static int[] hrsOf(List<Buckets.Record> recs) {
        int[] a = new int[recs.size()];
        for (int i = 0; i < a.length; i++) a[i] = recs.get(i).hr;
        return a;
    }
}
