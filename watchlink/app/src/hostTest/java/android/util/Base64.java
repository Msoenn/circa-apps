package android.util;

/** Host-test shim of android.util.Base64.encodeToString(..., DEFAULT): MIME lines of 76 chars, trailing newline. */
public class Base64 {
    public static final int DEFAULT = 0;
    public static String encodeToString(byte[] b, int flags) {
        String s = java.util.Base64.getEncoder().encodeToString(b);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i += 76) sb.append(s, i, Math.min(s.length(), i + 76)).append('\n');
        return sb.toString();
    }
}
