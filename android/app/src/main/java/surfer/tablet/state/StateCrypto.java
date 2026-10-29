package surfer.tablet.state;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.KeyPairGeneratorSpec;
import android.util.Base64;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Calendar;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

/**
 * Encrypts the saved browser state with AES-256-GCM. The AES key is random and
 * stored wrapped by an RSA key in the Android keystore (hardware-backed on most
 * devices), so the files alone are useless. Devices without a working keystore
 * keep the key in the app's private preferences instead.
 */
final class StateCrypto {
    /** The keystore no longer has the key (e.g. the lock screen was reset): saved state is unreadable. */
    static final class KeyLostException extends Exception {
        KeyLostException(Throwable cause) {
            super(cause);
        }
    }

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "surfer-state";
    private static final String PREF_WRAPPED = "stateKeyWrapped";
    private static final String PREF_PLAIN = "stateKeyPlain";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    StateCrypto(Context context, SharedPreferences prefs) throws KeyLostException {
        key = new SecretKeySpec(loadOrCreateKey(context, prefs), "AES");
    }

    /** Forgets the key (used after it was lost); the next instance creates a new one. */
    static void reset(SharedPreferences prefs) {
        prefs.edit().remove(PREF_WRAPPED).remove(PREF_PLAIN).commit();
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            ks.deleteEntry(ALIAS);
        } catch (Exception ignored) {
        }
    }

    byte[] encrypt(byte[] plain) throws GeneralSecurityException {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] body = cipher.doFinal(plain);
        byte[] out = new byte[IV_BYTES + body.length];
        System.arraycopy(iv, 0, out, 0, IV_BYTES);
        System.arraycopy(body, 0, out, IV_BYTES, body.length);
        return out;
    }

    byte[] decrypt(byte[] data) throws GeneralSecurityException {
        if (data.length < IV_BYTES) throw new GeneralSecurityException("truncated");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
        return cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
    }

    private byte[] loadOrCreateKey(Context context, SharedPreferences prefs) throws KeyLostException {
        String wrapped = prefs.getString(PREF_WRAPPED, null);
        String plain = prefs.getString(PREF_PLAIN, null);
        if (plain != null) return Base64.decode(plain, Base64.NO_WRAP);
        if (wrapped != null) {
            try {
                Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                rsa.init(Cipher.DECRYPT_MODE, keystorePrivateKey());
                return rsa.doFinal(Base64.decode(wrapped, Base64.NO_WRAP));
            } catch (Exception e) {
                throw new KeyLostException(e);
            }
        }

        byte[] aes = new byte[32];
        random.nextBytes(aes);
        try {
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, keystorePublicKey(context));
            prefs.edit().putString(PREF_WRAPPED, Base64.encodeToString(rsa.doFinal(aes), Base64.NO_WRAP)).commit();
        } catch (Exception e) {
            // No usable keystore on this device: the app's private storage is the protection.
            prefs.edit().putString(PREF_PLAIN, Base64.encodeToString(aes, Base64.NO_WRAP)).commit();
        }
        return aes;
    }

    private static PrivateKey keystorePrivateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (!(entry instanceof KeyStore.PrivateKeyEntry)) throw new GeneralSecurityException("key missing");
        return ((KeyStore.PrivateKeyEntry) entry).getPrivateKey();
    }

    @SuppressWarnings("deprecation") // KeyPairGeneratorSpec is the only option on API 18-22.
    private static PublicKey keystorePublicKey(Context context) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            Calendar start = Calendar.getInstance();
            Calendar end = Calendar.getInstance();
            end.add(Calendar.YEAR, 100);
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA", KEYSTORE);
            gen.initialize(new KeyPairGeneratorSpec.Builder(context)
                    .setAlias(ALIAS)
                    .setSubject(new X500Principal("CN=" + ALIAS))
                    .setSerialNumber(BigInteger.ONE)
                    .setStartDate(start.getTime())
                    .setEndDate(end.getTime())
                    .setKeySize(2048)
                    .build());
            gen.generateKeyPair();
        }
        return ks.getCertificate(ALIAS).getPublicKey();
    }
}
