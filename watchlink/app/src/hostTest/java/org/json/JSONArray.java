package org.json;

import java.util.ArrayList;

/** Host-test shim. */
public class JSONArray {
    public final ArrayList<Object> list = new ArrayList<>();
    public JSONArray put(Object v) { list.add(v); return this; }
    public int length() { return list.size(); }
    public Object get(int i) throws JSONException { return list.get(i); }
}
