package surfer.tablet.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * TLS that trusts exactly one certificate: the server's own, bundled into the
 * APK at build time. Android 4.4 supports TLS 1.2 but leaves it disabled, so
 * the socket factory turns it on.
 */
public final class PinnedTls {
    public final SSLSocketFactory socketFactory;
    public final X509TrustManager trustManager;
    public final HostnameVerifier hostnameVerifier;

    private PinnedTls(SSLSocketFactory socketFactory, X509TrustManager trustManager, final X509Certificate pinned) {
        this.socketFactory = socketFactory;
        this.trustManager = trustManager;
        // The address changes with the network (DHCP), so the host name is not
        // checked; instead the presented certificate must be the pinned one.
        this.hostnameVerifier = new HostnameVerifier() {
            @Override
            public boolean verify(String hostname, SSLSession session) {
                try {
                    Certificate[] chain = session.getPeerCertificates();
                    return chain.length > 0 && chain[0].equals(pinned);
                } catch (IOException e) {
                    return false;
                }
            }
        };
    }

    public static PinnedTls fromCertificate(InputStream certStream) throws GeneralSecurityException, IOException {
        X509Certificate cert;
        try {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(certStream);
        } finally {
            certStream.close();
        }
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setCertificateEntry("surfer", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        X509TrustManager trustManager = null;
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) trustManager = (X509TrustManager) tm;
        }
        if (trustManager == null) throw new GeneralSecurityException("no X509TrustManager");

        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(null, new TrustManager[] {trustManager}, null);
        return new PinnedTls(new Tls12SocketFactory(context.getSocketFactory()), trustManager, cert);
    }

    /** Enables TLS 1.2 (and only 1.2 or newer) on every socket it creates. */
    static final class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        Tls12SocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private Socket patch(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                List<String> wanted = new ArrayList<String>();
                List<String> supported = Arrays.asList(ssl.getSupportedProtocols());
                for (String p : new String[] {"TLSv1.3", "TLSv1.2"}) {
                    if (supported.contains(p)) wanted.add(p);
                }
                ssl.setEnabledProtocols(wanted.toArray(new String[0]));
            }
            return socket;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return patch(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return patch(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return patch(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return patch(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return patch(delegate.createSocket(address, port, localAddress, localPort));
        }
    }
}
