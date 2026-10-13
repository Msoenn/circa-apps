package org.circa.watchlink;

import java.util.Map;

/**
 * Host tests for navigation (PhoneData's `nav` state + distance parsing, and the turn-alert decision in NavAlert).
 * The message shapes are the ones Gadgetbridge's BangleJSDeviceSupport.onSetNavigationInfo emits for Google Maps.
 */
final class NavTest {
    static final long NOW = 1_759_600_000_000L;

    /** GB's onSetNavigationInfo object for one Maps update. */
    private static String nav(String fields) {
        return "{t:\"nav\"" + (fields.isEmpty() ? "" : "," + fields) + "}";
    }

    @SuppressWarnings("unchecked")
    private static int feed(PhoneData d, String json, long now) {
        Proto.In in = Proto.classify("GB(" + json + ")");
        HostTest.check(in.kind == Proto.KIND_GB, "nav: parses " + json);
        return d.apply((Map<String, Object>) in.msg, now, 0);
    }

    static void run() {
        // Maps' distance strings, in the phone's locale/units: number + unit (either order), "." or "," decimal.
        HostTest.check(PhoneData.parseDistanceMeters("100 yd") == 91, "distance: 100 yd");
        HostTest.check(PhoneData.parseDistanceMeters("0.2 mi") == 322, "distance: 0.2 mi");
        HostTest.check(PhoneData.parseDistanceMeters("0,3 mi") == 483, "distance: 0,3 mi (comma decimal)");
        HostTest.check(PhoneData.parseDistanceMeters("500 ft") == 152, "distance: 500 ft");
        HostTest.check(PhoneData.parseDistanceMeters("300 m") == 300, "distance: 300 m");
        HostTest.check(PhoneData.parseDistanceMeters("1.2 km") == 1200, "distance: 1.2 km");
        HostTest.check(PhoneData.parseDistanceMeters("1,2 km") == 1200, "distance: 1,2 km (comma decimal)");
        HostTest.check(PhoneData.parseDistanceMeters("  MI 2  ") == 3219, "distance: unit first, trimmed and upper-cased");
        HostTest.check(PhoneData.parseDistanceMeters("80\u00A0m") == 80, "distance: non-breaking space");
        HostTest.check(PhoneData.parseDistanceMeters("0 m") == 0, "distance: 0 m");
        HostTest.check(PhoneData.parseDistanceMeters(null) == -1, "distance: null -> -1");
        HostTest.check(PhoneData.parseDistanceMeters("") == -1, "distance: empty -> -1");
        HostTest.check(PhoneData.parseDistanceMeters("   ") == -1, "distance: blank -> -1");
        HostTest.check(PhoneData.parseDistanceMeters("100") == -1, "distance: no unit -> -1");
        HostTest.check(PhoneData.parseDistanceMeters("m") == -1, "distance: no number -> -1");
        HostTest.check(PhoneData.parseDistanceMeters("-5 m") == -1, "distance: negative -> -1");
        HostTest.check(PhoneData.parseDistanceMeters("1e3 m") == -1, "distance: exponent -> -1");
        HostTest.check(PhoneData.parseDistanceMeters("2 yds") == -1, "distance: unknown unit -> -1");
        HostTest.check(PhoneData.parseDistanceMeters("100 yd away") == -1, "distance: trailing text -> -1");
        HostTest.check(PhoneData.parseDistanceMeters("60 km/h") == -1, "distance: speed -> -1");

        // One nav message stores the maneuver; the raw string and the parsed metres travel together.
        PhoneData d = new PhoneData();
        int ch = feed(d, nav("instr:\"Turn left onto Main St\",distance:\"0.2 mi\",action:\"left\",eta:\"11:55\""), NOW);
        PhoneData.Nav n = d.nav;
        HostTest.check(ch == PhoneData.CH_NAV && n != null, "nav: a message changes nav");
        HostTest.check("Turn left onto Main St".equals(n.instruction) && "0.2 mi".equals(n.distance) && n.distanceM == 322
                && "left".equals(n.action) && "11:55".equals(n.eta) && n.updatedMs == NOW, "nav: message fields");
        HostTest.check(d.navState(NOW) == n, "nav: the current state is served");

        // Maps omits what it has no value for; an unknown action is stored as GB sent it (GB's list may grow).
        long later = NOW + 1_000;
        feed(d, nav("instr:\"Main St\",distance:null,action:\"left_hairpin\",eta:null"), later);
        n = d.navState(later);
        HostTest.check(n != null && n.distance == null && n.distanceM == -1 && "left_hairpin".equals(n.action)
                && n.eta == null, "nav: null distance/eta and an unknown action are kept as sent");

        // A new message revives a stale state; without one the route is inactive after NAV_STALE_MS.
        HostTest.check(d.navState(later + PhoneData.NAV_STALE_MS) != null, "nav: still active at the stale boundary");
        HostTest.check(d.navState(later + PhoneData.NAV_STALE_MS + 1) == null, "nav: stale state is inactive");
        long revived = later + PhoneData.NAV_STALE_MS + 1;
        feed(d, nav("instr:\"Main St\",distance:\"100 yd\",action:\"left\""), revived);
        HostTest.check(d.navState(revived) != null, "nav: a new message revives the state");

        // GB's blank message ends the route: no instruction, no action, no distance.
        ch = feed(d, nav(""), revived + 1_000);
        HostTest.check(ch == PhoneData.CH_NAV && d.nav == null && d.navState(revived + 1_000) == null,
                "nav: blank message ends the route");
        HostTest.check(feed(d, nav(""), revived + 1_001) == 0, "nav: a second blank message is no change");
        // ... an empty distance alone is not the end of the route
        ch = feed(d, nav("instr:\"Main St\",distance:\"\",action:null"), revived + 2_000);
        HostTest.check(ch == PhoneData.CH_NAV && d.navState(revived + 2_000) != null
                && d.navState(revived + 2_000).distanceM == -1, "nav: an empty distance keeps the route");

        // Navigation is never persisted (weather, music and calendar are).
        PhoneData reloaded = new PhoneData();
        reloaded.load(d.toJson());
        HostTest.check(reloaded.nav == null && reloaded.navState(revived + 2_000) == null,
                "nav: a restart starts with no route");

        // The turn alert: once per maneuver, at the first distance inside 150 m.
        NavAlert a = new NavAlert();
        HostTest.check(a.onNav("Turn left onto Main St", "left", 800) == NavAlert.Kind.NONE, "alert: far away is silent");
        HostTest.check(a.onNav("Turn left onto Main St", "left", 151) == NavAlert.Kind.NONE, "alert: 151 m is still far");
        HostTest.check(a.onNav("Turn left onto Main St", "left", NavAlert.TURN_M) == NavAlert.Kind.TURN, "alert: 150 m buzzes");
        HostTest.check(a.onNav("Turn left onto Main St", "left", 40) == NavAlert.Kind.NONE, "alert: the same maneuver buzzes once");
        HostTest.check(a.onNav("Turn left onto Main St", "left", 900) == NavAlert.Kind.NONE, "alert: no second buzz when it grows again");
        HostTest.check(a.onNav("Turn left onto Main St", "left", 90) == NavAlert.Kind.NONE, "alert: and none when it drops again");

        // A maneuver already inside 150 m when it first shows still buzzes, but not the route's first one.
        NavAlert b = new NavAlert();
        HostTest.check(b.onNav("Turn left onto Main St", "left", 100) == NavAlert.Kind.NONE,
                "alert: the route's first maneuver is not announced");
        HostTest.check(b.onNav("Turn right onto Oak Ave", "right", 100) == NavAlert.Kind.TURN,
                "alert: the next maneuver is announced even when already close");
        HostTest.check(b.onNav("Turn right onto Oak Ave", "right", 90) == NavAlert.Kind.NONE, "alert: once per maneuver");

        // A new maneuver is a new instruction or a new action.
        HostTest.check(b.onNav("Turn right onto Oak Ave", "uturn_right", 90) == NavAlert.Kind.TURN, "alert: a changed action is a new maneuver");
        HostTest.check(b.onNav("Turn right onto Elm St", "uturn_right", 90) == NavAlert.Kind.TURN, "alert: a changed instruction is a new maneuver");

        // End of the route: the next route's first maneuver is a first again.
        b.onEnd();
        HostTest.check(b.onNav("Keep left", "keep_left", 90) == NavAlert.Kind.NONE, "alert: a new route starts silent");

        // finish is the arrival itself: one longer buzz, however far the watch is from the turn threshold.
        NavAlert c = new NavAlert();
        HostTest.check(c.onNav("Turn left onto Main St", "left", 500) == NavAlert.Kind.NONE, "alert: approach is silent");
        HostTest.check(c.onNav("You have arrived", "finish", 20) == NavAlert.Kind.FINISH, "alert: finish buzzes on arrival");
        HostTest.check(c.onNav("You have arrived", "finish", 10) == NavAlert.Kind.NONE, "alert: finish buzzes once");
        c.onEnd();
        HostTest.check(c.onNav(null, "finish", -1) == NavAlert.Kind.FINISH, "alert: finish does not need a distance");

        // An unparseable distance is never acted on, and does not use up the maneuver's alert.
        NavAlert e = new NavAlert();
        HostTest.check(e.onNav("Turn left", "left", -1) == NavAlert.Kind.NONE, "alert: an unreadable distance is silent");
        HostTest.check(e.onNav("Turn left", "left", 120) == NavAlert.Kind.TURN, "alert: the next readable distance buzzes");

        // The buzzes themselves.
        HostTest.check(NavAlert.TURN_ON_MS == 60 && NavAlert.TURN_GAP_MS == 80 && NavAlert.FINISH_MS == 300,
                "alert: 60/80/60 ms turn pattern and a 300 ms arrival");
    }
}
