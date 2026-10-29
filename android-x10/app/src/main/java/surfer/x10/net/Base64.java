package surfer.x10.net;

/** Base64 encoding (android.util.Base64 only exists from Android 2.2). */
public final class Base64 {
    private static final char[] STANDARD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final char[] URL_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private Base64() {}

    /** Standard alphabet with padding (HTTP headers). */
    public static String encode(byte[] data) {
        return encode(data, STANDARD, true);
    }

    /** URL-safe alphabet without padding (identifiers). */
    public static String encodeUrlSafe(byte[] data) {
        return encode(data, URL_SAFE, false);
    }

    private static String encode(byte[] data, char[] alphabet, boolean pad) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xff;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xff : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xff : 0;
            int n = (b0 << 16) | (b1 << 8) | b2;
            sb.append(alphabet[(n >> 18) & 63]).append(alphabet[(n >> 12) & 63]);
            if (i + 1 < data.length) sb.append(alphabet[(n >> 6) & 63]);
            else if (pad) sb.append('=');
            if (i + 2 < data.length) sb.append(alphabet[n & 63]);
            else if (pad) sb.append('=');
        }
        return sb.toString();
    }
}
