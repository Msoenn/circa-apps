package org.circa.watchlink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Host tests for the health alerts and resting heart rate: [HeartAlerts] (two consecutive samples, inactivity,
 * workout, cooldowns and the off switches), [RestingHr] (percentile, daily and 7-day values, persistence encoding)
 * and [PhoneLeftAlert] (one alert per disconnection inside the five-minute window).
 */
final class HealthAlertsTest {
    static final long MIN = 60_000L;
    static final long DAY = 86_400_000L;
    static final long NOW = 1_790_000_000_000L;

    static List<HeartAlerts.StepBucket> buckets(int... steps) {
        List<HeartAlerts.StepBucket> out = new ArrayList<>();
        for (int i = 0; i < steps.length; i++) out.add(new HeartAlerts.StepBucket(NOW - (steps.length - 1 - i) * Buckets.BUCKET_MS, steps[i]));
        return out;
    }

    static HeartAlerts.Alert decide(int prevBpm, int bpm, int steps, boolean worn, boolean workout,
                                    int high, int low, long lastHigh, long lastLow) {
        List<HeartAlerts.Sample> samples = Arrays.asList(
                new HeartAlerts.Sample(NOW - 5 * MIN, prevBpm),
                new HeartAlerts.Sample(NOW, bpm));
        return HeartAlerts.decide(samples, buckets(steps), worn, workout, high, low, lastHigh, lastLow, NOW);
    }

    static void run() {
        // ---- HeartAlerts: high ----
        HeartAlerts.Alert a = decide(128, 130, 0, true, false, 120, 40, 0, 0);
        HostTest.check(a != null && a.kind == HeartAlerts.Kind.HIGH && a.bpm == 130,
                "hr alert: two high readings while inactive -> high");
        HostTest.check(decide(120, 120, 0, true, false, 120, 40, 0, 0) != null,
                "hr alert: a reading at the threshold counts as high");
        HostTest.check(decide(128, 130, 29, true, false, 120, 40, 0, 0) != null,
                "hr alert: 29 steps still counts as inactive");
        HostTest.check(decide(128, 130, 30, true, false, 120, 40, 0, 0) == null,
                "hr alert: 30 steps (not fewer than 30) is not inactive");
        HostTest.check(decide(128, 130, 0, true, true, 120, 40, 0, 0) == null,
                "hr alert: no high alert during a workout");
        HostTest.check(decide(119, 130, 0, true, false, 120, 40, 0, 0) == null,
                "hr alert: both samples must clear the high threshold");
        HostTest.check(decide(128, 130, 0, true, false, 0, 40, 0, 0) == null,
                "hr alert: high threshold 0 is off");
        HostTest.check(decide(128, 130, 0, true, false, 120, 40, NOW - 29 * MIN, 0) == null,
                "hr alert: high cooldown blocks for 29 minutes");
        HostTest.check(decide(128, 130, 0, true, false, 120, 40, NOW - 30 * MIN, 0) != null,
                "hr alert: high cooldown ends at 30 minutes");
        HostTest.check(decide(128, 130, 0, false, false, 120, 40, 0, 0) != null,
                "hr alert: the high check uses inactivity, not the worn flag");

        // ---- HeartAlerts: low ----
        a = decide(38, 36, 500, true, false, 120, 40, 0, 0);
        HostTest.check(a != null && a.kind == HeartAlerts.Kind.LOW && a.bpm == 36,
                "hr alert: two low readings while worn -> low");
        HostTest.check(decide(40, 40, 500, true, false, 120, 40, 0, 0) != null,
                "hr alert: a reading at the threshold counts as low");
        HostTest.check(decide(38, 36, 500, false, false, 120, 40, 0, 0) == null,
                "hr alert: no low alert off the wrist");
        HostTest.check(decide(41, 36, 500, true, false, 120, 40, 0, 0) == null,
                "hr alert: both samples must be at or below the low threshold");
        HostTest.check(decide(38, 36, 500, true, false, 120, 0, 0, 0) == null,
                "hr alert: low threshold 0 is off");
        HostTest.check(decide(38, 36, 500, true, false, 120, 40, 0, NOW - 10 * MIN) == null,
                "hr alert: low cooldown is independent of the high one");
        HostTest.check(decide(38, 36, 500, true, false, 120, 40, 0, NOW - 30 * MIN) != null,
                "hr alert: low cooldown ends at 30 minutes");

        // ---- HeartAlerts: sample validity and timing ----
        List<HeartAlerts.Sample> withInvalid = Arrays.asList(
                new HeartAlerts.Sample(NOW - 15 * MIN, 128),
                new HeartAlerts.Sample(NOW - 10 * MIN, 0),
                new HeartAlerts.Sample(NOW - 5 * MIN, 130),
                new HeartAlerts.Sample(NOW, 129));
        HostTest.check(HeartAlerts.decide(withInvalid, buckets(0), true, false, 120, 40, 0, 0, NOW) != null,
                "hr alert: zero readings are ignored, the two valid ones stay consecutive");
        List<HeartAlerts.Sample> stale = Arrays.asList(
                new HeartAlerts.Sample(NOW - 13 * MIN, 130),
                new HeartAlerts.Sample(NOW - 8 * MIN, 130));
        HostTest.check(HeartAlerts.decide(stale, buckets(0), true, false, 120, 40, 0, 0, NOW) == null,
                "hr alert: a pair older than the freshness window does not fire");
        List<HeartAlerts.Sample> wide = Arrays.asList(
                new HeartAlerts.Sample(NOW - 15 * MIN, 130),
                new HeartAlerts.Sample(NOW, 130));
        HostTest.check(HeartAlerts.decide(wide, buckets(0), true, false, 120, 40, 0, 0, NOW) == null,
                "hr alert: samples more than 10 minutes apart are not consecutive");
        List<HeartAlerts.Sample> same = Arrays.asList(
                new HeartAlerts.Sample(NOW, 130),
                new HeartAlerts.Sample(NOW, 130));
        HostTest.check(HeartAlerts.decide(same, buckets(0), true, false, 120, 40, 0, 0, NOW) == null,
                "hr alert: duplicate timestamps are not two samples");
        HostTest.check(HeartAlerts.high(null) == 120 && HeartAlerts.high(" 0 ") == 0
                        && HeartAlerts.high("130") == 130 && HeartAlerts.high("x") == 120,
                "hr alert: high threshold parses default, 0 and a value");

        // ---- RestingHr ----
        List<RestingHr.Sample> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) six.add(new RestingHr.Sample(NOW + i * MIN, 55 + i, 0));
        HostTest.check(RestingHr.daily(six, NOW) == 55,
                "resting: 6 inactive samples -> 10th percentile (" + RestingHr.daily(six, NOW) + ")");
        HostTest.check(RestingHr.daily(six.subList(0, 5), NOW) == null,
                "resting: fewer than 6 inactive samples -> unknown");
        List<RestingHr.Sample> active = new ArrayList<>(six);
        active.add(new RestingHr.Sample(NOW + 6 * MIN, 50, 30));
        HostTest.check(RestingHr.daily(active, NOW) == 55,
                "resting: 30 steps in the bucket is active and does not lower the percentile");
        List<RestingHr.Sample> unknownSteps = new ArrayList<>(six);
        unknownSteps.add(new RestingHr.Sample(NOW + 6 * MIN, 50, -1));
        HostTest.check(RestingHr.daily(unknownSteps, NOW) == 55,
                "resting: an unmeasured bucket is not an inactive sample");
        List<RestingHr.Sample> zero = new ArrayList<>(six);
        zero.add(new RestingHr.Sample(NOW + 6 * MIN, 0, 0));
        HostTest.check(RestingHr.daily(zero, NOW) == 55, "resting: zero bpm is ignored");
        HostTest.check(RestingHr.daily(six, NOW + DAY) == null, "resting: samples belong to their own local day");

        List<RestingHr.Day> days = Arrays.asList(
                new RestingHr.Day(NOW, 58),
                new RestingHr.Day(NOW - DAY, 60),
                new RestingHr.Day(NOW - 2 * DAY, 62));
        HostTest.check(RestingHr.sevenDay(days, NOW) == 60,
                "resting: 7-day value is the mean (" + RestingHr.sevenDay(days, NOW) + ")");
        List<RestingHr.Day> old = Arrays.asList(
                new RestingHr.Day(NOW, 58),
                new RestingHr.Day(NOW - 7 * DAY, 100));
        HostTest.check(RestingHr.sevenDay(old, NOW) == 58, "resting: a value 7 days old is outside the window");
        HostTest.check(RestingHr.sevenDay(new ArrayList<>(), NOW) == null, "resting: no days -> no 7-day value");

        List<RestingHr.Day> replaced = RestingHr.withDay(days, NOW, 61);
        HostTest.check(replaced.size() == 3 && replaced.get(2).bpm == 61, "resting: a day is replaced, not duplicated");
        HostTest.check(RestingHr.withDay(days, NOW, null).size() == 2, "resting: a null value removes the day");
        List<RestingHr.Day> tooOld = new ArrayList<>(days);
        tooOld.add(new RestingHr.Day(NOW - 14 * DAY, 70));
        HostTest.check(RestingHr.prune(tooOld, NOW).size() == 3, "resting: values older than 14 days are pruned");
        HostTest.check(RestingHr.decode(RestingHr.encode(days)).size() == 3
                        && RestingHr.decode(RestingHr.encode(days)).get(0).dayStartMs == NOW - 2 * DAY,
                "resting: daily values round trip through their encoding");
        HostTest.check(RestingHr.decode("x,y:1,1:2,1:3").size() == 1
                        && RestingHr.decode("x,y:1,1:2,1:3").get(0).bpm == 3,
                "resting: invalid entries are dropped and a repeated day keeps the later value");
        HostTest.check(RestingHr.decodeSamples(RestingHr.encodeSamples(six)).size() == 6,
                "resting: today's samples round trip through their encoding");

        // ---- PhoneLeftAlert ----
        HostTest.check(PhoneLeftAlert.shouldAlert(false, true, 40, MIN, false, true),
                "phone left: 40 steps inside the window");
        HostTest.check(!PhoneLeftAlert.shouldAlert(false, true, 39, MIN, false, true),
                "phone left: 39 steps are not enough");
        HostTest.check(!PhoneLeftAlert.shouldAlert(true, true, 40, MIN, false, true),
                "phone left: a live link never alerts");
        HostTest.check(!PhoneLeftAlert.shouldAlert(false, false, 40, MIN, false, true),
                "phone left: not armed when the watch was off the wrist");
        HostTest.check(!PhoneLeftAlert.shouldAlert(false, true, 40, 0, true, true),
                "phone left: once per disconnection");
        HostTest.check(!PhoneLeftAlert.shouldAlert(false, true, 40, 5 * MIN + 1, false, true),
                "phone left: after five minutes it is too late");
        HostTest.check(PhoneLeftAlert.shouldAlert(false, true, 40, 5 * MIN, false, true),
                "phone left: the window includes five minutes");
        HostTest.check(!PhoneLeftAlert.shouldAlert(false, true, 40, MIN, false, false),
                "phone left: the setting turns it off");
        HostTest.check(PhoneLeftAlert.enabled(null) && PhoneLeftAlert.enabled("1")
                        && !PhoneLeftAlert.enabled("0") && !PhoneLeftAlert.enabled(" 0 "),
                "phone left: setting defaults to on, explicit 0 is off");
    }
}
