package surfer.x10;

import android.content.Context;
import android.content.SharedPreferences;
import surfer.x10.net.Base64;

import java.security.SecureRandom;

/** Persistent app settings: device ID, server address, debug switch, last crash. */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("surfer", Context.MODE_PRIVATE);
    }

    SharedPreferences raw() {
        return sp;
    }

    /**
     * Random secret created once per install. It identifies this device's session
     * on the server; nothing else is needed to connect.
     */
    String deviceId() {
        String id = sp.getString("deviceId", null);
        if (id == null) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            id = Base64.encodeUrlSafe(bytes);
            sp.edit().putString("deviceId", id).commit();
        }
        return id;
    }

    String server() { return sp.getString("server", ""); }
    void server(String value) { sp.edit().putString("server", value).commit(); }

    /** Hide the status bar (off by default, like Chrome). */
    boolean fullScreen() { return sp.getBoolean("fullScreen", false); }
    void fullScreen(boolean value) { sp.edit().putBoolean("fullScreen", value).commit(); }

    boolean debug() { return sp.getBoolean("debug", false); }
    void debug(boolean value) { sp.edit().putBoolean("debug", value).commit(); }

    /** Stack trace of the last crash, sent to the server on the next login. */
    String takeCrash() {
        String crash = sp.getString("crash", null);
        if (crash != null) sp.edit().remove("crash").commit();
        return crash;
    }

    void crash(String trace) {
        // Written at once: the process is about to die.
        sp.edit().putString("crash", trace).commit();
    }
}
