package org.circa.watchlink;

import java.util.Arrays;

/** Host tests for Smart Wake: the stirring rule and the per-minute movement count of SmartWakeDetector. */
final class SmartWakeTest {
    static void run() {
        // ---- the rule ----
        HostTest.check(!SmartWakeRule.stirring(Arrays.asList(5, 5)), "smart wake: too few minutes never trigger");
        HostTest.check(!SmartWakeRule.stirring(Arrays.asList(0, 0, 0, 0)), "smart wake: a still wrist does not trigger");
        HostTest.check(SmartWakeRule.stirring(Arrays.asList(0, 0, 0, 2)), "smart wake: one restless minute triggers");
        HostTest.check(!SmartWakeRule.stirring(Arrays.asList(0, 0, 1, 0)), "smart wake: a single twitch does not trigger");
        HostTest.check(SmartWakeRule.stirring(Arrays.asList(0, 0, 1, 1)), "smart wake: two minutes in a row with movement trigger");

        // ---- the detector on samples: 5 still minutes, then the arm turns ----
        long t0 = 10_000_000L;   // a whole minute
        SmartWakeDetector d = new SmartWakeDetector(t0);
        long t = t0;
        for (; t < t0 + 5 * 60_000L; t += 640) d.add(t, 0.3f, 0.2f, 9.8f);
        HostTest.check(!d.evaluate(t, true), "smart wake: still minutes do not trigger (" + d.getMinutes() + ")");
        long turnUntil = t + 60_000L;
        for (; t < turnUntil; t += 640) {
            double a = ((t - t0) / 5_000L) % 2 == 0 ? 0 : Math.toRadians(30);
            d.add(t, (float) (9.8 * Math.sin(a)), 0f, (float) (9.8 * Math.cos(a)));
        }
        for (long e = t + 640; t < e; t += 640) d.add(t, 0.3f, 0.2f, 9.8f);
        HostTest.check(!d.evaluate(t, false), "smart wake: never while off the wrist or charging");
        HostTest.check(d.evaluate(t, true), "smart wake: a turning arm triggers (" + d.getMinutes() + ")");
    }
}
