package com.akashiic.web.client.install;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * TLS for the installer only (ADR-011 "TLS"). Connections first use the JRE's own trust store. If that fails on a
 * certificate path (an old 8u51 {@code cacerts} missing a root), the download is retried with an SSLContext that
 * trusts the JRE roots <i>plus</i> the roots bundled in the jar. That context is set per connection and never
 * installed with {@code setDefault}, so nothing else in the game changes how it trusts servers.
 * <p>
 * Bundled roots: ISRG Root X1 and X2 (Let's Encrypt). Each PEM is accepted only if its SHA-256 fingerprint matches
 * the pin below (compared on 2026-10-12 between letsencrypt.org/certs and the Debian ca-certificates copy).
 */
public final class InstallerTls {

    static final String[] ROOT_RESOURCES = { "isrg-root-x1.pem", "isrg-root-x2.pem" };
    static final String[] ROOT_SHA256 = { "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6",
        "69729b8e15a86efc177a57afb7171dfc64add28c2fca8cf1507e34453ccb1470" };

    private volatile SSLSocketFactory fallback;

    /** The fallback factory, built on first use. */
    public SSLSocketFactory fallbackFactory() throws InstallException {
        SSLSocketFactory f = fallback;
        if (f != null) return f;
        synchronized (this) {
            if (fallback == null) fallback = buildFallback();
            return fallback;
        }
    }

    /** True when the failure is about trusting the server's certificate chain, the case bundled roots can fix. */
    public static boolean isTrustFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof CertificateException || c instanceof CertPathValidatorException
                || c instanceof CertPathBuilderException) {
                return true;
            }
        }
        return false;
    }

    /** True for any TLS-layer failure (trust, protocol version, cipher overlap, handshake alert). */
    public static boolean isTlsFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SSLException) return true;
        }
        return isTrustFailure(t);
    }

    static List<X509Certificate> loadBundledRoots() throws InstallException {
        List<X509Certificate> roots = new ArrayList<>();
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (int i = 0; i < ROOT_RESOURCES.length; i++) {
                try (InputStream in = InstallerTls.class.getResourceAsStream(ROOT_RESOURCES[i])) {
                    if (in == null) {
                        throw new InstallException(InstallFailure.TLS_FAILED, "missing " + ROOT_RESOURCES[i]);
                    }
                    X509Certificate cert = (X509Certificate) cf.generateCertificate(in);
                    String fp = Sha256.of(cert.getEncoded());
                    if (!fp.equals(ROOT_SHA256[i])) {
                        throw new InstallException(
                            InstallFailure.TLS_FAILED,
                            ROOT_RESOURCES[i] + " fingerprint " + fp + " is not the pinned one");
                    }
                    roots.add(cert);
                }
            }
        } catch (GeneralSecurityException | IOException e) {
            if (e instanceof InstallException) throw (InstallException) e;
            throw new InstallException(InstallFailure.TLS_FAILED, "cannot load bundled roots: " + e, e);
        }
        return roots;
    }

    private static SSLSocketFactory buildFallback() throws InstallException {
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            List<X509Certificate> roots = loadBundledRoots();
            for (int i = 0; i < roots.size(); i++) {
                ks.setCertificateEntry("akashicweb-root-" + i, roots.get(i));
            }
            X509ExtendedTrustManager bundled = pkix(ks);
            X509ExtendedTrustManager jre = pkix(null);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { new EitherTrustManager(jre, bundled) }, null);
            return ctx.getSocketFactory();
        } catch (GeneralSecurityException | IOException e) {
            throw new InstallException(InstallFailure.TLS_FAILED, "cannot build fallback TLS context: " + e, e);
        }
    }

    private static X509ExtendedTrustManager pkix(KeyStore ks) throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks); // null = the JRE's cacerts
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509ExtendedTrustManager) return (X509ExtendedTrustManager) tm;
        }
        throw new GeneralSecurityException("no X509ExtendedTrustManager from " + tmf.getAlgorithm());
    }

    /**
     * Trusts a chain if the JRE store or the bundled store does. Extends X509ExtendedTrustManager and delegates the
     * socket/engine variants so hostname and algorithm-constraint checks stay exactly as the JRE does them.
     */
    static final class EitherTrustManager extends X509ExtendedTrustManager {

        private final X509ExtendedTrustManager first;
        private final X509ExtendedTrustManager second;

        EitherTrustManager(X509ExtendedTrustManager first, X509ExtendedTrustManager second) {
            this.first = first;
            this.second = second;
        }

        private interface Check {

            void run(X509ExtendedTrustManager tm) throws CertificateException;
        }

        private void either(Check check) throws CertificateException {
            try {
                check.run(first);
            } catch (CertificateException primary) {
                try {
                    check.run(second);
                } catch (CertificateException secondary) {
                    primary.addSuppressed(secondary);
                    throw primary;
                }
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            either(tm -> tm.checkServerTrusted(chain, authType));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
            either(tm -> tm.checkServerTrusted(chain, authType, socket));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
            either(tm -> tm.checkServerTrusted(chain, authType, engine));
        }

        /** The installer never authenticates clients. */
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client authentication is not used by the installer");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
            checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
            checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>(Arrays.asList(first.getAcceptedIssuers()));
            all.addAll(Arrays.asList(second.getAcceptedIssuers()));
            return all.toArray(new X509Certificate[0]);
        }
    }
}
