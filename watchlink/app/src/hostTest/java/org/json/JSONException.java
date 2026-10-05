package org.json;

/** Host-test shim of Android's org.json (only what GB's encoder uses). */
public class JSONException extends Exception {
    public JSONException(String m) { super(m); }
}
