package surfer.x10.state;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts the saved browser state: AES-256-CBC, then HMAC-SHA256 over the IV
 * and ciphertext (encrypt-then-MAC). Android 2.1 has neither AES-GCM nor the
 * Android keystore, so the two random keys live in the app's private
 * preferences, which other apps cannot read.
 *
 * File format: version (1) | IV (16) | ciphertext | MAC (32).
 */
final class StateCrypto {
    /** The key is unusable: saved state cannot be read. */
    static final class KeyLostException extends Exception {
        KeyLostException(Throwable cause) {
            super(cause);
        }
    }

    private static final String PREF_KEY = "stateKeyCbcHmac";
    private static final int VERSION = 1;
    private static final int IV_BYTES = 16;
    private static final int MAC_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec encKey;
    private final SecretKeySpec macKey;

    StateCrypto(Context context, SharedPreferences prefs) throws KeyLostException {
        byte[] keys = loadOrCreateKeys(prefs);
        if (keys.length != 64) throw new KeyLostException(new IllegalStateException("bad key length"));
        byte[] enc = new byte[32];
        byte[] mac = new byte[32];
        System.arraycopy(keys, 0, enc, 0, 32);
        System.arraycopy(keys, 32, mac, 0, 32);
        encKey = new SecretKeySpec(enc, "AES");
        macKey = new SecretKeySpec(mac, "HmacSHA256");
    }

    /** Forgets the key (used after it was lost); the next instance creates a new one. */
    static void reset(SharedPreferences prefs) {
        prefs.edit().remove(PREF_KEY).commit();
    }

    byte[] encrypt(byte[] plain) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, encKey, new IvParameterSpec(iv));
        byte[] body = cipher.doFinal(plain);
        byte[] out = new byte[1 + IV_BYTES + body.length + MAC_BYTES];
        out[0] = VERSION;
        System.arraycopy(iv, 0, out, 1, IV_BYTES);
        System.arraycopy(body, 0, out, 1 + IV_BYTES, body.length);
        byte[] tag = mac(out, 0, out.length - MAC_BYTES);
        System.arraycopy(tag, 0, out, out.length - MAC_BYTES, MAC_BYTES);
        return out;
    }

    byte[] decrypt(byte[] data) throws GeneralSecurityException {
        if (data.length < 1 + IV_BYTES + 16 + MAC_BYTES || data[0] != VERSION) {
            throw new GeneralSecurityException("not a state file");
        }
        int macStart = data.length - MAC_BYTES;
        byte[] expected = mac(data, 0, macStart);
        byte[] actual = new byte[MAC_BYTES];
        System.arraycopy(data, macStart, actual, 0, MAC_BYTES);
        if (!MessageDigest.isEqual(expected, actual)) throw new GeneralSecurityException("state file was modified");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, encKey, new IvParameterSpec(data, 1, IV_BYTES));
        return cipher.doFinal(data, 1 + IV_BYTES, macStart - 1 - IV_BYTES);
    }

    private byte[] mac(byte[] data, int offset, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(macKey);
        mac.update(data, offset, length);
        return mac.doFinal();
    }

    private byte[] loadOrCreateKeys(SharedPreferences prefs) throws KeyLostException {
        String hex = prefs.getString(PREF_KEY, null);
        if (hex != null) {
            try {
                return fromHex(hex);
            } catch (IllegalArgumentException e) {
                throw new KeyLostException(e);
            }
        }
        byte[] keys = new byte[64];
        random.nextBytes(keys);
        prefs.edit().putString(PREF_KEY, toHex(keys)).commit();
        return keys;
    }

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        }
        return sb.toString();
    }

    static byte[] fromHex(String s) {
        if (s.length() % 2 != 0) throw new IllegalArgumentException("odd length");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16);
            int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("not hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
