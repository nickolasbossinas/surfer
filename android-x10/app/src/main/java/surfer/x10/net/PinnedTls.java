package surfer.x10.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS that trusts exactly one certificate: the server's own, bundled into the
 * APK at build time. The certificate is compared byte for byte, so neither the
 * host name (the address changes with the network) nor a certificate authority
 * matters.
 *
 * Android 2.1 speaks TLS 1.0 at most; the server's legacy port accepts it with
 * ephemeral Diffie-Hellman AES suites only.
 */
public final class PinnedTls {
    /** Thrown during the handshake when the server presents another certificate. */
    public static final class MismatchException extends CertificateException {
        MismatchException() {
            super("server certificate is not the one this app was built for");
        }
    }

    private final SSLSocketFactory factory;

    private PinnedTls(SSLSocketFactory factory) {
        this.factory = factory;
    }

    public static PinnedTls fromCertificate(InputStream certStream) throws GeneralSecurityException, IOException {
        final byte[] pinned;
        try {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(certStream);
            pinned = cert.getEncoded();
        } finally {
            certStream.close();
        }
        X509TrustManager trust = new X509TrustManager() {
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0 || !Arrays.equals(chain[0].getEncoded(), pinned)) {
                    throw new MismatchException();
                }
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("no client certificates");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {trust}, new SecureRandom());
        return new PinnedTls(context.getSocketFactory());
    }

    /** Connects and completes the TLS handshake (blocking). */
    public SSLSocket connect(String host, int port, int timeoutMs) throws IOException {
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(host, port), timeoutMs);
            plain.setTcpNoDelay(true);
            SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true);
            ssl.setSoTimeout(timeoutMs);
            ssl.startHandshake();
            ssl.setSoTimeout(0);
            return ssl;
        } catch (IOException e) {
            try {
                plain.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }
}
