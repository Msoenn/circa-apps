package org.json;

import java.util.Iterator;
import java.util.LinkedHashMap;

/** Host-test shim: insertion-ordered like Android's org.json; put(k, null) removes k (as Android does). */
public class JSONObject {
    public final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    public JSONObject put(String k, Object v) throws JSONException {
        if (v == null) map.remove(k); else map.put(k, v);
        return this;
    }
    public Object get(String k) throws JSONException {
        if (!map.containsKey(k)) throw new JSONException("no " + k);
        return map.get(k);
    }
    public Iterator<String> keys() { return map.keySet().iterator(); }
}
