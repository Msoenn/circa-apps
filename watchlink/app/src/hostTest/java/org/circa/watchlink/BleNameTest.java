package org.circa.watchlink;

/** Host tests for BleName: keep an existing "Bangle.js xxxx" adapter name, otherwise derive one from ANDROID_ID. */
final class BleNameTest {
    static void run() {
        HostTest.check(BleName.choose("Bangle.js 1a2b", "ffffffffffff9999").equals("Bangle.js 1a2b"),
                "ble name: an existing Bangle.js name is kept (signing key change keeps the paired name)");
        HostTest.check(BleName.choose(null, "abcdef0123456789").equals("Bangle.js 6789"),
                "ble name: no adapter name -> ANDROID_ID suffix");
        HostTest.check(BleName.choose("Pixel Watch 2", "abcdef01234567AB").equals("Bangle.js 67ab"),
                "ble name: another name is replaced, suffix lower-cased");
        HostTest.check(BleName.choose("Bangle.js 1A2B", "0000000000001234").equals("Bangle.js 1234"),
                "ble name: an upper-case suffix is not ours");
        HostTest.check(BleName.choose("Bangle.js 1a2b3", "0000000000001234").equals("Bangle.js 1234"),
                "ble name: a longer suffix is not ours");
        HostTest.check(BleName.choose("", "12").equals("Bangle.js 0000"), "ble name: short ANDROID_ID -> 0000");
        HostTest.check(BleName.choose(null, null).equals("Bangle.js 0000"), "ble name: nothing known -> 0000");
    }
}
