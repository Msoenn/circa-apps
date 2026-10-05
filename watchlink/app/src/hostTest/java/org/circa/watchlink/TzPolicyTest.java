package org.circa.watchlink;

import java.util.TimeZone;

/**
 * Host tests for TzPolicy: the system time zone chosen from the phone's UTC offset (Gadgetbridge's
 * setTime(...);E.setTimeZone(...) line). Instants are mid-January and mid-July 2026 so that the same phone offset
 * maps to different zones in the two hemispheres (and in DST-observing zones).
 */
final class TzPolicyTest {
    static final long JAN = 1768478400000L;   // 2026-01-15T12:00:00Z
    static final long JUL = 1784116800000L;   // 2026-07-15T12:00:00Z
    static final int H = 3_600_000;

    static void run() {
        // Rule 1: automatic time zone is off -> leave the system zone alone.
        HostTest.check(TzPolicy.choose(false, "UTC", -8 * H, JAN) == null, "tz: auto off -> no change");
        // No tz in the setTime line -> nothing to follow.
        HostTest.check(TzPolicy.choose(true, "UTC", null, JAN) == null, "tz: no phone offset -> no change");

        // Rule 2: the current named zone already has the phone's offset -> keep it.
        HostTest.check("America/Los_Angeles".equals(TzPolicy.choose(true, "America/Los_Angeles", -8 * H, JAN)),
                "tz: LA in winter already -8 -> keep");
        HostTest.check("America/Los_Angeles".equals(TzPolicy.choose(true, "America/Los_Angeles", -7 * H, JUL)),
                "tz: LA in summer already -7 -> keep");
        HostTest.check("Asia/Kathmandu".equals(TzPolicy.choose(true, "Asia/Kathmandu", 5 * H + 45 * 60_000, JAN)),
                "tz: a matching non-preferred zone is kept");

        // Rule 3a: preferred list, matched by the offset at this instant.
        HostTest.check("America/Los_Angeles".equals(TzPolicy.choose(true, "UTC", -8 * H, JAN)),
                "tz: -8 in January -> America/Los_Angeles (PST)");
        HostTest.check("America/Los_Angeles".equals(TzPolicy.choose(true, "UTC", -7 * H, JUL)),
                "tz: -7 in July -> America/Los_Angeles (PDT)");
        HostTest.check("America/Denver".equals(TzPolicy.choose(true, "UTC", -7 * H, JAN)),
                "tz: -7 in January -> America/Denver (MST), not LA");
        HostTest.check("America/Anchorage".equals(TzPolicy.choose(true, "UTC", -8 * H, JUL)),
                "tz: -8 in July -> America/Anchorage (AKDT) before any Etc/GMT zone");
        HostTest.check("Pacific/Honolulu".equals(TzPolicy.choose(true, "UTC", -10 * H, JAN)),
                "tz: -10 -> Pacific/Honolulu");

        // India (+5.5, rule 3a) and Nepal (+5.75, rule 3b: a non-preferred fractional offset).
        HostTest.check("Asia/Kolkata".equals(TzPolicy.choose(true, "UTC", 5 * H + 30 * 60_000, JAN)),
                "tz: +5.5 -> Asia/Kolkata");
        HostTest.check("Asia/Kathmandu".equals(TzPolicy.choose(true, "UTC", 5 * H + 45 * 60_000, JAN)),
                "tz: +5.75 -> Asia/Kathmandu");

        // New Zealand: +13 in January (NZDT), +12 in July (NZST).
        HostTest.check("Pacific/Auckland".equals(TzPolicy.choose(true, "UTC", 13 * H, JAN)),
                "tz: +13 in January -> Pacific/Auckland (NZDT)");
        HostTest.check("Pacific/Auckland".equals(TzPolicy.choose(true, "UTC", 12 * H, JUL)),
                "tz: +12 in July -> Pacific/Auckland (NZST)");

        // No tzdb zone has these offsets.
        HostTest.check(TzPolicy.choose(true, "UTC", 25 * H, JAN) == null, "tz: +25 h has no zone -> no change");
        HostTest.check(TzPolicy.choose(true, "UTC", 5 * H + 10 * 60_000, JAN) == null,
                "tz: +5 h 10 min has no zone -> no change");

        // Rule 3c: the Etc/GMT±H last resort inverts the sign (Etc/GMT+7 is UTC-7).
        HostTest.check("Etc/GMT+7".equals(TzPolicy.etcGmtZone(-7)), "tz: -7 h -> Etc/GMT+7");
        HostTest.check(TimeZone.getTimeZone("Etc/GMT+7").getOffset(JAN) == -7 * H, "tz: Etc/GMT+7 is UTC-7");
        HostTest.check("Etc/GMT-5".equals(TzPolicy.etcGmtZone(5)), "tz: +5 h -> Etc/GMT-5");
        HostTest.check(TimeZone.getTimeZone("Etc/GMT-5").getOffset(JUL) == 5 * H, "tz: Etc/GMT-5 is UTC+5");
        HostTest.check("Etc/GMT".equals(TzPolicy.etcGmtZone(0)), "tz: 0 h -> Etc/GMT");
        HostTest.check(TzPolicy.etcGmtZone(13) == null && TzPolicy.etcGmtZone(-15) == null,
                "tz: outside tzdb's Etc/GMT+12..Etc/GMT-14 -> null");
    }
}
