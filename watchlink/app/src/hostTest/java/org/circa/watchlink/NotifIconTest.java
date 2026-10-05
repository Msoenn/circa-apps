package org.circa.watchlink;

/**
 * Host tests for {@link NotifIcon}: the substituted app name ("SMS Message" -> "Messages") and the deterministic
 * avatar (palette hash and first letter). The avatar drawables are drawn by WatchLinkService; the icon table the
 * old version had is gone - every mirrored notification uses the generated bell.
 */
final class NotifIconTest {
    static void run() {
        // app name substituted into Notification.EXTRA_SUBSTITUTE_APP_NAME
        HostTest.check("Messages".equals(NotifIcon.appName("SMS Message")), "notif name: SMS Message -> Messages");
        HostTest.check("Gmail".equals(NotifIcon.appName("Gmail")), "notif name: Gmail unchanged");
        HostTest.check("SMS".equals(NotifIcon.appName("SMS")), "notif name: only the exact SMS app is renamed");
        HostTest.check(NotifIcon.appName(null) == null, "notif name: no src -> no substitute");
        HostTest.check("".equals(NotifIcon.appName("")), "notif name: empty src -> empty");

        // avatar palette: fixed 8 colors, FNV-1a over src, deterministic
        HostTest.check(NotifIcon.getPaletteSize() == 8, "notif icon: 8-color palette");
        HostTest.check(NotifIcon.paletteIndex("Gmail") == NotifIcon.paletteIndex("Gmail"),
                "notif icon: palette index is deterministic");
        HostTest.check(NotifIcon.paletteIndex("Gmail") == 5, "notif icon: Gmail hashes to index 5");
        HostTest.check(NotifIcon.paletteIndex("Signal") == 1, "notif icon: Signal hashes to index 1");
        HostTest.check(NotifIcon.paletteIndex("My Bank") == 3, "notif icon: My Bank hashes to index 3");
        HostTest.check(NotifIcon.paletteIndex(null) == 5, "notif icon: null hashes like the empty string");
        HostTest.check(NotifIcon.color("Gmail") == 0xFF009688, "notif icon: Gmail avatar is palette teal");
        HostTest.check(NotifIcon.color("Signal") == 0xFFE91E63, "notif icon: Signal avatar is palette pink");
        HostTest.check(NotifIcon.color("My Bank") == 0xFF3F51B5, "notif icon: My Bank avatar is palette indigo");

        // letter for the avatar circle
        HostTest.check(NotifIcon.letter("Gmail") == 'G', "notif icon: letter of Gmail");
        HostTest.check(NotifIcon.letter("messages") == 'M', "notif icon: letter is upper-cased");
        HostTest.check(NotifIcon.letter("  x") == 'X', "notif icon: letter skips leading spaces");
        HostTest.check(NotifIcon.letter(null) == '?', "notif icon: no src -> ?");
        HostTest.check(NotifIcon.letter("") == '?', "notif icon: empty src -> ?");
    }
}
