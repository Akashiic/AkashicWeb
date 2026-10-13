package com.akashiic.web.client.install;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * TLS for the installer only (ADR-011 "TLS"). Forbidden clients run Java 8u51 (2015), whose {@code cacerts} may lack
 * the roots GitHub chains to today. Every installer connection therefore uses this class's own SSLContext: never the
 * JVM default (another mod may have replaced it with a permissive one) and never installed with {@code setDefault}, so
 * nothing else in the game changes how it trusts servers. Its trust manager accepts a server chain when one of these
 * stores does, tried in this order:
 * <ol>
 * <li>{@link Layer#JRE}: the JRE's own trust store ({@code cacerts}, or {@code javax.net.ssl.trustStore});</li>
 * <li>{@link Layer#OS}: on Windows the {@code Windows-ROOT} key store (SunMSCAPI, present in every Windows Java 8),
 * which also holds the root of an antivirus that scans HTTPS; on Linux the distribution's PEM bundle;</li>
 * <li>{@link Layer#PINNED}: roots bundled in the jar, each accepted only if its SHA-256 fingerprint matches the pin
 * below.</li>
 * </ol>
 * A store is consulted only when the previous one found no path to a trusted root ({@link #isPathFailure}). Any other
 * certificate failure (a wrong host name, a revoked or malformed certificate) ends the check at once: another store
 * cannot fix it. A store is loaded the first time it is needed. Host names are always checked inside the handshake
 * ({@link IdentityCheckingFactory}), whatever default HostnameVerifier another mod installed.
 * <p>
 * Bundled roots, for the two hosts the installer talks to (VERIFIED 2026-10-13, SSL Labs API assessments of
 * github.com and release-assets.githubusercontent.com, agreeing with Certificate Transparency logs):
 * <ul>
 * <li>github.com: leaf, Sectigo Public Server Authentication CA DV R36 (or E36), Sectigo Public Server Authentication
 * Root R46 (or E46) as served cross-signed by USERTrust RSA (or ECC) Certification Authority. Pinned: USERTrust RSA,
 * USERTrust ECC, Sectigo R46, Sectigo E46.</li>
 * <li>release-assets.githubusercontent.com (the redirect target): Let's Encrypt YR1, ISRG Root YR cross-signed by ISRG
 * Root X1. Pinned: ISRG Root X1 and X2.</li>
 * </ul>
 * Each fingerprint was compared between the Mozilla CCADB "included CA certificates" export and Debian's
 * ca-certificates 20260601 copy (both agree). Re-check these chains when GitHub changes certificate authority.
 */
public final class InstallerTls {

    private static final Logger LOG = LogManager.getLogger("AkashicWeb/Installer");

    /** The trust stores, in the order they are tried. */
    public enum Layer {

        JRE("JRE trust store"),
        OS("operating-system root store"),
        PINNED("bundled roots");

        public final String label;

        Layer(String label) {
            this.label = label;
        }
    }

    /** Loads one store as a trust manager; returns null when that store does not exist on this machine. */
    @FunctionalInterface
    interface Source {

        X509ExtendedTrustManager load() throws GeneralSecurityException, IOException;
    }

    /** Opens a bundled resource by name, or returns null. */
    @FunctionalInterface
    interface Resources {

        InputStream open(String name) throws IOException;
    }

    static final String[] ROOT_RESOURCES = { "isrg-root-x1.pem", "isrg-root-x2.pem", "usertrust-rsa.pem",
        "usertrust-ecc.pem", "sectigo-root-r46.pem", "sectigo-root-e46.pem" };
    static final String[] ROOT_SHA256 = { "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6",
        "69729b8e15a86efc177a57afb7171dfc64add28c2fca8cf1507e34453ccb1470",
        "e793c9b02fd8aa13e21c31228accb08119643b749c898964b1746d46c3d4cbd2",
        "4ff460d54b9c86dabfbcfc5712e0400d2bed3fbc4d4fbdaa86e06adcd2a9ad7a",
        "7bb647a62aeeac88bf257aa522d01ffea395e0ab45c73f93f65654ec38f25a06",
        "c90f26f0fb1b4018b22227519b5ca2b53e2ca5b3be5cf18efe1bef47380c5383" };

    /** Where Linux distributions keep the system PEM bundle (Debian/Ubuntu/Arch, Fedora/RHEL, openSUSE). */
    static final String[] LINUX_BUNDLES = { "/etc/ssl/certs/ca-certificates.crt", "/etc/pki/tls/certs/ca-bundle.crt",
        "/etc/ssl/ca-bundle.pem" };
    static final int MAX_BUNDLE_BYTES = 4 * 1024 * 1024;
    private static final Pattern PEM = Pattern
        .compile("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+?)-----END CERTIFICATE-----");

    private final LayeredTrustManager trust;
    private volatile SSLSocketFactory factory;

    /** The production stores for this JVM's operating system. */
    public InstallerTls() {
        this(
            InstallerTls::jreStore,
            System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .startsWith("windows") ? InstallerTls::windowsRoot : () -> linuxBundle(LINUX_BUNDLES),
            () -> pkix(keyStore(loadBundledRoots())));
    }

    /** Explicit stores (tests use self-made CAs for each layer). */
    InstallerTls(Source jre, Source os, Source pinned) {
        this.trust = new LayeredTrustManager(new Source[] { jre, os, pinned });
    }

    /** The factory every installer HTTPS connection uses, built on first use. */
    public SSLSocketFactory socketFactory() throws InstallException {
        SSLSocketFactory f = factory;
        if (f != null) return f;
        synchronized (this) {
            if (factory == null) {
                try {
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, new TrustManager[] { trust }, null);
                    factory = new IdentityCheckingFactory(ctx.getSocketFactory());
                } catch (GeneralSecurityException e) {
                    throw new InstallException(InstallFailure.TLS_FAILED, "cannot set up TLS: " + e, e);
                }
            }
            return factory;
        }
    }

    /** The store that accepted the last trusted chain, or null before the first one (for logs and tests). */
    public Layer lastTrustedBy() {
        return trust.lastTrustedBy;
    }

    /** True when the failure is about the server's certificate (any store, host name included): never retried. */
    public static boolean isTrustFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof CertificateException || c instanceof CertPathValidatorException
                || c instanceof CertPathBuilderException) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a store found no valid path from the chain to one of its roots ("PKIX path building failed", or a path
     * that failed validation against that store's roots): the one case a different store can fix.
     */
    public static boolean isPathFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof CertPathBuilderException || c instanceof CertPathValidatorException) return true;
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

    // ---------------------------------------------------------------- stores

    static X509ExtendedTrustManager jreStore() throws GeneralSecurityException {
        return pkix(null);
    }

    /**
     * The roots Windows trusts for this user. Java 8 lists the certificates present in the store when it is loaded;
     * roots that Windows would fetch on demand (automatic root update) are not there yet, which is one reason the
     * pinned layer exists. Null when SunMSCAPI is missing (a non-Windows or stripped JRE).
     */
    static X509ExtendedTrustManager windowsRoot() throws GeneralSecurityException, IOException {
        KeyStore ks;
        try {
            ks = KeyStore.getInstance("Windows-ROOT");
        } catch (GeneralSecurityException e) {
            LOG.info("Windows-ROOT key store unavailable ({}); skipping the OS layer", e.toString());
            return null;
        }
        ks.load(null, null);
        return ks.size() == 0 ? null : pkix(ks);
    }

    /** The first existing PEM bundle among {@code paths}, or null when there is none or it holds no certificate. */
    static X509ExtendedTrustManager linuxBundle(String... paths) throws GeneralSecurityException, IOException {
        for (String path : paths) {
            File f = new File(path);
            if (!f.isFile()) continue;
            if (f.length() > MAX_BUNDLE_BYTES) {
                LOG.warn("CA bundle {} is larger than {} B; skipping it", path, MAX_BUNDLE_BYTES);
                continue;
            }
            List<X509Certificate> certs;
            try (InputStream in = new FileInputStream(f)) {
                certs = parsePem(readAll(in, MAX_BUNDLE_BYTES));
            }
            if (certs.isEmpty()) continue;
            return pkix(keyStore(certs));
        }
        return null;
    }

    static List<X509Certificate> loadBundledRoots() throws InstallException {
        return pinnedRoots(InstallerTls.class::getResourceAsStream, ROOT_RESOURCES, ROOT_SHA256);
    }

    /** Each named PEM, accepted only if the SHA-256 of its DER form is the pin at the same index. */
    static List<X509Certificate> pinnedRoots(Resources resources, String[] names, String[] sha256)
        throws InstallException {
        List<X509Certificate> roots = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            List<X509Certificate> certs;
            try (InputStream in = resources.open(names[i])) {
                if (in == null) throw new InstallException(InstallFailure.TLS_FAILED, "missing " + names[i]);
                certs = parsePem(readAll(in, 64 * 1024));
            } catch (InstallException e) {
                throw e;
            } catch (IOException | GeneralSecurityException e) {
                throw new InstallException(InstallFailure.TLS_FAILED, "cannot read " + names[i] + ": " + e, e);
            }
            if (certs.size() != 1) {
                throw new InstallException(InstallFailure.TLS_FAILED, names[i] + " must hold exactly one certificate");
            }
            String fp;
            try {
                fp = Sha256.of(
                    certs.get(0)
                        .getEncoded());
            } catch (GeneralSecurityException e) {
                throw new InstallException(InstallFailure.TLS_FAILED, "cannot encode " + names[i], e);
            }
            if (!fp.equals(sha256[i])) {
                throw new InstallException(
                    InstallFailure.TLS_FAILED,
                    names[i] + " fingerprint " + fp + " is not the pinned one");
            }
            roots.add(certs.get(0));
        }
        return roots;
    }

    /** Every certificate in PEM text; text between the blocks (comments, labels) is ignored. */
    static List<X509Certificate> parsePem(byte[] text) throws GeneralSecurityException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> out = new ArrayList<>();
        Matcher m = PEM.matcher(new String(text, StandardCharsets.US_ASCII));
        while (m.find()) {
            byte[] der;
            try {
                der = Base64.getMimeDecoder()
                    .decode(m.group(1));
            } catch (IllegalArgumentException e) {
                continue; // a damaged block in a system bundle must not hide the others
            }
            out.add((X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der)));
        }
        return out;
    }

    static KeyStore keyStore(List<X509Certificate> certs) throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        for (int i = 0; i < certs.size(); i++) ks.setCertificateEntry("akashicweb-" + i, certs.get(i));
        return ks;
    }

    /** The JRE's PKIX trust manager over {@code ks} (null: the JRE's own trust store). */
    static X509ExtendedTrustManager pkix(KeyStore ks) throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509ExtendedTrustManager) return (X509ExtendedTrustManager) tm;
        }
        throw new GeneralSecurityException("no X509ExtendedTrustManager from " + tmf.getAlgorithm());
    }

    private static byte[] readAll(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > cap) throw new IOException("larger than " + cap + " bytes");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- trust manager

    /**
     * Tries the stores in {@link Layer} order. Extends X509ExtendedTrustManager and delegates the socket/engine
     * variants, so host-name and algorithm-constraint checks stay exactly as the JRE does them in every layer.
     */
    static final class LayeredTrustManager extends X509ExtendedTrustManager {

        private final Source[] sources;
        private final X509ExtendedTrustManager[] loaded;
        private final boolean[] tried;
        volatile Layer lastTrustedBy;

        LayeredTrustManager(Source[] sources) {
            this.sources = sources.clone();
            this.loaded = new X509ExtendedTrustManager[sources.length];
            this.tried = new boolean[sources.length];
        }

        private interface Check {

            void run(X509ExtendedTrustManager tm) throws CertificateException;
        }

        /** The layer's trust manager, loading it once; null when the store does not exist or cannot be read. */
        private synchronized X509ExtendedTrustManager manager(Layer layer) throws CertificateException {
            int i = layer.ordinal();
            if (!tried[i]) {
                try {
                    loaded[i] = sources[i] == null ? null : sources[i].load();
                } catch (InstallException e) {
                    // Our own pinned roots failed their check: report it instead of quietly trusting less.
                    throw new CertificateException(layer.label + " unusable: " + e.getMessage(), e);
                } catch (GeneralSecurityException | IOException | RuntimeException e) {
                    // Boundary: an OS store we cannot read is simply not there; the next layer still runs.
                    LOG.warn("TLS trust layer '{}' cannot be loaded: {}", layer.label, e.toString());
                    loaded[i] = null;
                }
                tried[i] = true;
            }
            return loaded[i];
        }

        private void check(Check check) throws CertificateException {
            CertificateException first = null;
            List<String> consulted = new ArrayList<>();
            for (Layer layer : Layer.values()) {
                X509ExtendedTrustManager tm = manager(layer);
                if (tm == null) continue;
                consulted.add(layer.label);
                CertificateException failure;
                try {
                    check.run(tm);
                    lastTrustedBy = layer;
                    return;
                } catch (CertificateException e) {
                    failure = e;
                } catch (RuntimeException e) {
                    // Boundary: JSSE reports some store problems (e.g. no usable anchor) as a RuntimeException; that
                    // store cannot vouch for the chain, so it counts as "no path" and the next layer is tried.
                    failure = new CertificateException(
                        layer.label + " failed: " + e,
                        new CertPathBuilderException(e.toString(), e));
                }
                if (first == null) first = failure;
                else first.addSuppressed(failure);
                if (!isPathFailure(failure)) throw first; // host name, revocation...: no other store can fix it
            }
            String tried = consulted.isEmpty() ? "no trust store is available" : String.join(", ", consulted);
            CertificateException summary = new CertificateException(
                "no trusted root for this certificate in: " + tried,
                first);
            throw summary;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(tm -> tm.checkServerTrusted(chain, authType));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
            check(tm -> tm.checkServerTrusted(chain, authType, socket));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
            check(tm -> tm.checkServerTrusted(chain, authType, engine));
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

        /** Only the stores loaded so far: listing an issuer must not load the OS store. */
        @Override
        public synchronized X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>();
            for (X509ExtendedTrustManager tm : loaded) {
                if (tm != null) all.addAll(Arrays.asList(tm.getAcceptedIssuers()));
            }
            return all.toArray(new X509Certificate[0]);
        }
    }

    // ---------------------------------------------------------------- host names

    /**
     * Sets endpoint identification ("HTTPS") on every socket, so the host name is checked inside the handshake by the
     * JRE's trust manager. HttpsURLConnection would otherwise defer to the default HostnameVerifier, which any mod in
     * the pack can replace with one that accepts everything.
     */
    static final class IdentityCheckingFactory extends SSLSocketFactory {

        private final SSLSocketFactory delegate;

        IdentityCheckingFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private static Socket harden(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) s;
                SSLParameters p = ssl.getSSLParameters();
                p.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(p);
            }
            return s;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket() throws IOException {
            return harden(delegate.createSocket());
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return harden(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return harden(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return harden(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return harden(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
            return harden(delegate.createSocket(address, port, localAddress, localPort));
        }
    }
}
