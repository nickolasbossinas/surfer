package surfer.tablet;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

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
            id = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            sp.edit().putString("deviceId", id).commit();
        }
        return id;
    }

    String server() { return sp.getString("server", ""); }
    void server(String value) { sp.edit().putString("server", value).apply(); }

    /** Hide the status and navigation bars (off by default, like Chrome). */
    boolean fullScreen() { return sp.getBoolean("fullScreen", false); }
    void fullScreen(boolean value) { sp.edit().putBoolean("fullScreen", value).apply(); }

    boolean debug() { return sp.getBoolean("debug", false); }
    void debug(boolean value) { sp.edit().putBoolean("debug", value).apply(); }

    /** Stack trace of the last crash, sent to the server on the next login. */
    String takeCrash() {
        String crash = sp.getString("crash", null);
        if (crash != null) sp.edit().remove("crash").apply();
        return crash;
    }

    void crash(String trace) {
        // commit(), not apply(): the process is about to die.
        sp.edit().putString("crash", trace).commit();
    }
}
